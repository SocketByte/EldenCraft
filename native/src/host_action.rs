//! Elden Ring's own "Event Action" (doors, levers, items, Sites of Grace) on R.
//!
//! E belongs to Minecraft's inventory, so the native interaction gets its own
//! key. A press is the game's one-byte "Event Action tapped" latch in
//! CSActionButtonManImp: the selected prompt's handler consumes it in the next
//! event update exactly as a physical tap would. No input injection and no game
//! function calls. A press that is not taken within 500 ms is withdrawn, so a
//! stale latch can never fire later on a different prompt.
//!
//! Offsets and the code fingerprints guarding them are for the worldwide
//! 2.7.1.0 executable; they follow Minecraft-Ring (MIT, siddoff/justbustin),
//! which reverse engineered the same build. Any mismatch disables only this
//! feature.
use std::collections::VecDeque;

const SELECTED: usize = 0x20; // entry* of the prompt on offer, null = none
const CAN_EXECUTE: usize = 0x29; // 0 while talk, menus or item popups block actions
const GRAYED: usize = 0x2B; // prompt grayed out: a press does nothing
const TEXT_ID: usize = 0x2C; // ActionButtonText id on offer, -1 = none
const CONSUMED: usize = 0x80; // set by the handler that took the press
const PRESSED: usize = 0x81; // the latch; the game clears it once used
const ENTRY_PARAM: usize = 0x08; // entry: ActionButtonParam row id
const MANAGER_BYTES: usize = 0xC0;
/// ActionButtonParam rows for ladders ("Climb", "Descend"): they need native up/down input.
const LADDER_PARAMS: [i32; 2] = [5000, 5010];
const PRESS_TIMEOUT_MS: u64 = 500;

/// Code that reads and writes the fields above, by RVA.
const FINGERPRINTS: [(usize, &[u8]); 4] = [
    (
        0xA658A1,
        &[
            0x80, 0xBB, 0x88, 0, 0, 0, 0, 0x74, 0x09, 0x66, 0xC7, 0x83, 0x81, 0, 0, 0, 0x01, 0x01,
        ],
    ),
    (
        0xA656CC,
        &[
            0x80, 0xBB, 0x80, 0, 0, 0, 0, 0x74, 0x10, 0x66, 0xC7, 0x83, 0x80, 0, 0, 0, 0, 0, 0xC6,
            0x83, 0x82, 0, 0, 0, 0,
        ],
    ),
    (
        0xA64FA0,
        &[
            0x4C, 0x8B, 0xC1, 0x48, 0x85, 0xD2, 0x74, 0x61, 0x0F, 0xB6, 0x4A, 0x20, 0xF6, 0xC1,
            0x04, 0x74, 0x58, 0x41, 0x80, 0xB8, 0x81, 0, 0, 0, 0,
        ],
    ),
    (
        0xA64E61,
        &[
            0x38, 0x43, 0x28, 0x74, 0x51, 0x48, 0x8B, 0x4B, 0x20, 0x48, 0x85, 0xC9, 0x74, 0x48,
            0x0F, 0xB6, 0x41, 0x20, 0xA8, 0x08, 0x75, 0x40, 0xA8, 0x01, 0x74, 0x04, 0xC6, 0x43,
            0x2B, 0x01, 0xF6, 0x41, 0x20, 0x20, 0x74, 0x04, 0xC6, 0x43, 0x2B, 0x00, 0x8B, 0x41,
            0x10, 0x89, 0x43, 0x2C,
        ],
    ),
];

/// The prompt state read from the manager, decoupled from game memory for tests.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Offer {
    pub selected: bool,
    /// Internal selected-entry identity; never published as a game pointer.
    pub selection: usize,
    pub can_execute: bool,
    pub grayed: bool,
    pub text: i32,
    pub param: Option<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    Press,
    Nothing,
    Blocked,
    Ladder,
}

pub fn decide(offer: Offer) -> Decision {
    if !offer.selected || offer.text < 0 {
        return Decision::Nothing;
    }
    if !offer.can_execute || offer.grayed {
        return Decision::Blocked;
    }
    if offer
        .param
        .is_some_and(|param| LADDER_PARAMS.contains(&param))
    {
        return Decision::Ladder;
    }
    Decision::Press
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Pending {
    Taken,
    Cleared,
    Waiting,
    Expired,
}
pub fn pending(consumed: bool, latched: bool, pressed_at: u64, now: u64) -> Pending {
    if consumed {
        Pending::Taken
    } else if !latched {
        Pending::Cleared
    } else if now.saturating_sub(pressed_at) > PRESS_TIMEOUT_MS {
        Pending::Expired
    } else {
        Pending::Waiting
    }
}

#[derive(Default)]
pub struct Driver {
    verified: Option<Result<(), String>>,
    press: Option<(u64, i32, usize)>,
    pub events: VecDeque<String>,
}

#[cfg(windows)]
impl Driver {
    /// Read the currently selected action using the same exact code guard as R.
    /// This is read-only and remains available while a Minecraft menu is open.
    pub unsafe fn offer(&mut self) -> Option<Offer> {
        if self.verified.is_none() {
            self.verified = Some(Self::verify());
        }
        if !matches!(self.verified, Some(Ok(()))) {
            return None;
        }
        let manager = Self::manager()?;
        let selected = unsafe { std::ptr::read_volatile(manager.add(SELECTED).cast::<usize>()) };
        let param = (selected != 0)
            .then(|| unsafe { std::ptr::read_volatile((selected + ENTRY_PARAM) as *const i32) });
        Some(Offer {
            selected: selected != 0,
            selection: selected,
            can_execute: unsafe { std::ptr::read_volatile(manager.add(CAN_EXECUTE)) } != 0,
            grayed: unsafe { std::ptr::read_volatile(manager.add(GRAYED)) } != 0,
            text: unsafe { std::ptr::read_volatile(manager.add(TEXT_ID).cast::<i32>()) },
            param,
        })
    }
    fn manager() -> Option<*mut u8> {
        use eldenring::cs::CSActionButtonManImp;
        use fromsoftware_shared::FromStatic;
        let manager = unsafe { CSActionButtonManImp::instance_mut() }.ok()?;
        Some(manager as *mut CSActionButtonManImp as *mut u8)
    }
    fn verify() -> Result<(), String> {
        let image = shared_program_image();
        for (at, bytes) in FINGERPRINTS {
            if image.get(at..at + bytes.len()) != Some(bytes) {
                return Err(format!("action button fingerprint mismatch at {at:x}"));
            }
        }
        Ok(())
    }
    /// Game task thread only. `allowed`: foreground offline gameplay with no
    /// Minecraft GUI or chat. `pressed`: rising edge of the action key.
    pub unsafe fn tick(&mut self, allowed: bool, pressed: bool, now: u64) {
        if self.verified.is_none() {
            let result = Self::verify();
            self.events.push_back(match &result {
                Ok(()) => {
                    "Elden Ring interaction on R ready (doors, levers, items, Sites of Grace)."
                        .into()
                }
                Err(error) => format!("Elden Ring interaction on R unavailable: {error}"),
            });
            self.verified = Some(result);
        }
        if !matches!(self.verified, Some(Ok(()))) {
            return;
        }
        let Some(manager) = Self::manager() else {
            self.press = None;
            return;
        };
        let read = |offset: usize| unsafe { std::ptr::read_volatile(manager.add(offset)) };
        if let Some((at, text, selection)) = self.press {
            let current = unsafe { std::ptr::read_volatile(manager.add(SELECTED).cast::<usize>()) };
            if current != selection && read(CONSUMED) == 0 {
                unsafe { std::ptr::write_volatile(manager.add(PRESSED), 0) };
                self.press = None;
            }
            match pending(read(CONSUMED) != 0, read(PRESSED) != 0, at, now) {
                Pending::Taken => {
                    self.events
                        .push_back(format!("Elden Ring took the interaction (prompt {text})."));
                    self.press = None;
                }
                Pending::Cleared => {
                    self.press = None;
                }
                Pending::Expired => {
                    unsafe { std::ptr::write_volatile(manager.add(PRESSED), 0) };
                    self.events.push_back(format!(
                        "Interaction not taken within 0.5 s (prompt {text}); withdrawn."
                    ));
                    self.press = None;
                }
                Pending::Waiting => {}
            }
        }
        if !allowed || !pressed || self.press.is_some() {
            return;
        }
        let selected = unsafe { std::ptr::read_volatile(manager.add(SELECTED).cast::<usize>()) };
        let param = (selected != 0)
            .then(|| unsafe { std::ptr::read_volatile((selected + ENTRY_PARAM) as *const i32) });
        let offer = Offer {
            selected: selected != 0,
            selection: selected,
            can_execute: read(CAN_EXECUTE) != 0,
            grayed: read(GRAYED) != 0,
            text: unsafe { std::ptr::read_volatile(manager.add(TEXT_ID).cast::<i32>()) },
            param,
        };
        match decide(offer) {
            Decision::Press => {
                unsafe { std::ptr::write_volatile(manager.add(PRESSED), 1) };
                self.press = Some((now, offer.text, offer.selection));
                self.events.push_back(format!(
                    "Interaction on prompt {} (ActionButtonParam {:?}).",
                    offer.text, offer.param
                ));
            }
            Decision::Ladder => self
                .events
                .push_back("Ladders need Elden Ring's own interact key and up/down input.".into()),
            Decision::Blocked | Decision::Nothing => {}
        }
    }
    /// Withdraw an unconsumed latch on every suspension path.
    pub unsafe fn suspend(&mut self) {
        if self.press.take().is_some()
            && let Some(manager) = Self::manager()
            && unsafe { std::ptr::read_volatile(manager.add(CONSUMED)) } == 0
        {
            unsafe { std::ptr::write_volatile(manager.add(PRESSED), 0) };
        }
    }
}

#[cfg(windows)]
fn shared_program_image() -> &'static [u8] {
    use fromsoftware_shared::program::Program;
    use pelite::pe64::PeObject;
    Program::current().image()
}

const _: () = assert!(PRESSED < MANAGER_BYTES && TEXT_ID + 4 <= MANAGER_BYTES);

#[cfg(test)]
mod tests {
    use super::*;
    fn offer() -> Offer {
        Offer {
            selected: true,
            selection: 0x1234,
            can_execute: true,
            grayed: false,
            text: 1000,
            param: Some(1),
        }
    }
    #[test]
    fn presses_only_an_executable_prompt() {
        assert_eq!(decide(offer()), Decision::Press);
        assert_eq!(
            decide(Offer {
                selected: false,
                ..offer()
            }),
            Decision::Nothing
        );
        assert_eq!(
            decide(Offer {
                text: -1,
                ..offer()
            }),
            Decision::Nothing
        );
        assert_eq!(
            decide(Offer {
                can_execute: false,
                ..offer()
            }),
            Decision::Blocked,
            "talk/menu/popup"
        );
        assert_eq!(
            decide(Offer {
                grayed: true,
                ..offer()
            }),
            Decision::Blocked
        );
        assert_eq!(
            decide(Offer {
                param: Some(5000),
                ..offer()
            }),
            Decision::Ladder
        );
        assert_eq!(
            decide(Offer {
                param: Some(5010),
                ..offer()
            }),
            Decision::Ladder
        );
    }
    #[test]
    fn a_latch_is_confirmed_cleared_or_withdrawn() {
        assert_eq!(pending(true, true, 100, 120), Pending::Taken);
        assert_eq!(pending(false, false, 100, 120), Pending::Cleared);
        assert_eq!(pending(false, true, 100, 600), Pending::Waiting);
        assert_eq!(pending(false, true, 100, 601), Pending::Expired);
    }
    #[test]
    fn fingerprints_are_bounded_and_distinct() {
        let mut seen = std::collections::HashSet::new();
        for (at, bytes) in FINGERPRINTS {
            assert!(!bytes.is_empty() && seen.insert(at));
        }
    }
}
