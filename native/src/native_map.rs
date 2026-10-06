//! Elden Ring's own world map. M, or the native Map binding (G by default),
//! opens it from Minecraft gameplay. While it is open the engine releases
//! composition and input exactly as for any other native menu, so the map is
//! drawn without the Minecraft HUD and receives the real cursor and clicks.
//!
//! M has no native binding: it opens the map through a bounded press of the
//! actual Map binding's digital input, the same bitset the combat adapter uses.
//! The map is considered closed after a close key is released, or as soon as
//! the player has moved (it was closed some other way, or fast travel ran).
use crate::pad_layout;
use eldenring::fd4::FD4PadManager;
use fromsoftware_shared::FromStatic;

/// Native logical key of the world map (`pad_layout::key_name`).
const MAP_KEY: i32 = 300;
const PULSE_MS: u64 = 120;
const RELEASE_MS: u64 = 250;
const MOVED_M: f32 = 1.5;
const MAX_WORDS: usize = 128;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Keys {
    /// M, the EldenCraft map key.
    pub open: bool,
    /// Escape.
    pub cancel: bool,
    /// Any physical input bound to the native Map action.
    pub binding: bool,
}
impl Keys {
    fn any(self) -> bool {
        self.open || self.cancel || self.binding
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum State {
    Closed,
    Open {
        feet: Option<[f32; 3]>,
        pulse_until: u64,
    },
    Closing {
        feet: Option<[f32; 3]>,
        release_at: Option<u64>,
    },
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Step {
    pub open: bool,
    /// Hold the native Map binding down this tick.
    pub pulse: bool,
}

/// Pure open/close bookkeeping; no game state.
pub struct Tracker {
    state: State,
    previous: Keys,
}
impl Default for Tracker {
    fn default() -> Self {
        Self {
            state: State::Closed,
            previous: Keys::default(),
        }
    }
}
impl Tracker {
    /// `gameplay`: Minecraft owns gameplay with no screen open, so a press can
    /// reach the native in-game pad. `feet` is None while a native menu blocks
    /// the snapshot; movement is then not evaluated.
    pub fn observe(
        &mut self,
        now: u64,
        keys: Keys,
        feet: Option<[f32; 3]>,
        gameplay: bool,
    ) -> Step {
        let rising = Keys {
            open: keys.open && !self.previous.open,
            cancel: keys.cancel && !self.previous.cancel,
            binding: keys.binding && !self.previous.binding,
        };
        self.previous = keys;
        let moved = |entry: Option<[f32; 3]>| match (entry, feet) {
            (Some(a), Some(b)) => (a[0] - b[0]).powi(2) + (a[2] - b[2]).powi(2) > MOVED_M * MOVED_M,
            _ => false,
        };
        self.state = match self.state {
            State::Closed if gameplay && (rising.open || rising.binding) => State::Open {
                feet,
                // A native binding press already reached the game.
                pulse_until: if rising.open && !keys.binding {
                    now + PULSE_MS
                } else {
                    0
                },
            },
            State::Closed => State::Closed,
            State::Open { feet: entry, .. } if moved(entry) => State::Closed,
            // Our own pulse, and the device settling after it, read back as
            // binding presses; ignore those.
            State::Open {
                feet: entry,
                pulse_until,
            } if rising.open
                || rising.cancel
                || rising.binding && (pulse_until == 0 || now >= pulse_until + RELEASE_MS) =>
            {
                State::Closing {
                    feet: entry.or(feet),
                    release_at: None,
                }
            }
            State::Open {
                feet: entry,
                pulse_until,
            } => State::Open {
                feet: entry.or(feet),
                pulse_until,
            },
            State::Closing { feet: entry, .. } if moved(entry) => State::Closed,
            // Resume only once the close key is up, so it is not replayed into
            // Minecraft (Escape would open its pause menu).
            State::Closing { feet: entry, .. } if keys.any() => State::Closing {
                feet: entry,
                release_at: None,
            },
            State::Closing {
                release_at: Some(at),
                ..
            } if now >= at => State::Closed,
            State::Closing {
                feet: entry,
                release_at,
            } => State::Closing {
                feet: entry,
                release_at: Some(release_at.unwrap_or(now + RELEASE_MS)),
            },
        };
        Step {
            open: self.state != State::Closed,
            pulse: matches!(self.state, State::Open { pulse_until, .. } if now < pulse_until),
        }
    }
    pub fn reset(&mut self) {
        self.state = State::Closed;
    }
}

struct BitWrite {
    device: usize,
    index: usize,
}

#[derive(Default)]
pub struct Driver {
    tracker: Tracker,
    pulse: Option<BitWrite>,
}
impl Driver {
    /// # Safety
    /// Game task after executable validation; no outstanding pad references.
    pub unsafe fn tick(
        &mut self,
        now: u64,
        open_key: bool,
        cancel_key: bool,
        feet: Option<[f32; 3]>,
        gameplay: bool,
    ) -> bool {
        let keys = Keys {
            open: open_key,
            cancel: cancel_key,
            binding: unsafe { binding_down() },
        };
        let step = self.tracker.observe(now, keys, feet, gameplay);
        if step.pulse {
            // The device can refresh the bit every frame; keep it held.
            if let Ok(write) = unsafe { press() } {
                self.pulse = Some(write);
            }
        } else {
            unsafe { self.release() };
        }
        step.open
    }
    /// Forget an open map after loads, warps and gate loss.
    ///
    /// # Safety
    /// Same contract as `tick`.
    pub unsafe fn reset(&mut self) {
        self.tracker.reset();
        unsafe { self.release() };
    }
    unsafe fn release(&mut self) {
        let Some(write) = self.pulse.take() else {
            return;
        };
        let Ok(manager) = (unsafe { FD4PadManager::instance_mut() }) else {
            return;
        };
        let Some(pad) = manager.get_in_game_pad_mut() else {
            return;
        };
        let device = unsafe { pad.pad_device.as_mut().virtual_multi_device.as_mut() };
        if device as *mut _ as usize != write.device {
            return;
        }
        let bits_pointer = std::ptr::addr_of_mut!(device.virtual_input_data.dynamic_bitset);
        let Some(_lock) = device.mutex.try_lock() else {
            // Retry next tick rather than leave the map key held.
            self.pulse = Some(write);
            return;
        };
        let bits = unsafe { &mut *bits_pointer };
        if write.index < bits.integer_count * 32 {
            bits.set(write.index, false);
        }
    }
}

/// Virtual digital indices of the native Map binding's pressable inputs.
unsafe fn map_indices() -> Result<Vec<usize>, &'static str> {
    let manager = unsafe { FD4PadManager::instance() }.map_err(|_| "map pad manager")?;
    let pad = manager.get_in_game_pad().ok_or("map in-game pad")?;
    let group = *unsafe { pad_layout::groups(pad) }
        .find(&MAP_KEY)
        .ok_or("map binding group")?;
    let assignment = unsafe { pad.key_assign.as_ref() };
    Ok(group
        .iter()
        .filter(|(_, _, kind)| *kind == 0)
        .filter_map(|(_, code, _)| assignment.get_virtual_input_index(code))
        .filter_map(|index| usize::try_from(index).ok())
        .collect())
}

unsafe fn binding_down() -> bool {
    let Ok(indices) = (unsafe { map_indices() }) else {
        return false;
    };
    let Ok(manager) = (unsafe { FD4PadManager::instance() }) else {
        return false;
    };
    let Some(pad) = manager.get_in_game_pad() else {
        return false;
    };
    let device = unsafe { pad.pad_device.as_ref().virtual_multi_device.as_ref() };
    let bits = &device.virtual_input_data.dynamic_bitset;
    if bits.integer_count == 0 || bits.integer_count > MAX_WORDS {
        return false;
    }
    indices
        .into_iter()
        .any(|index| index < bits.integer_count * 32 && bits.get(index))
}

/// Hold the first Map binding whose input gate the native pad accepts.
unsafe fn press() -> Result<BitWrite, &'static str> {
    let indices = unsafe { map_indices() }?;
    let manager = unsafe { FD4PadManager::instance_mut() }.map_err(|_| "map pad manager")?;
    let pad = manager.get_in_game_pad_mut().ok_or("map in-game pad")?;
    if !pad.allow_polling {
        return Err("map in-game pad is not polling");
    }
    let group = *unsafe { pad_layout::groups(pad) }
        .find(&MAP_KEY)
        .ok_or("map binding group")?;
    let checks = unsafe { pad.input_code_check.as_ref() };
    let assignment = unsafe { pad.key_assign.as_ref() };
    let index = group
        .iter()
        .filter(|(_, code, kind)| {
            *kind == 0
                && checks
                    .find(code)
                    .is_some_and(|check| check.state_1 && !check.state_2)
        })
        .filter_map(|(_, code, _)| assignment.get_virtual_input_index(code))
        .filter_map(|index| usize::try_from(index).ok())
        .find(|index| indices.contains(index))
        .ok_or("map binding unavailable")?;
    let device = unsafe { pad.pad_device.as_mut().virtual_multi_device.as_mut() };
    let device_address = device as *mut _ as usize;
    let bits_pointer = std::ptr::addr_of_mut!(device.virtual_input_data.dynamic_bitset);
    let _lock = device.mutex.try_lock().ok_or("map input device busy")?;
    let bits = unsafe { &mut *bits_pointer };
    if bits.integer_count == 0 || bits.integer_count > MAX_WORDS || index >= bits.integer_count * 32
    {
        return Err("map input bitset rejected");
    }
    bits.set(index, true);
    Ok(BitWrite {
        device: device_address,
        index,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    const FEET: Option<[f32; 3]> = Some([10., 5., 20.]);
    fn keys(open: bool, cancel: bool, binding: bool) -> Keys {
        Keys {
            open,
            cancel,
            binding,
        }
    }

    #[test]
    fn m_presses_the_native_binding_briefly_and_only_from_gameplay() {
        let mut t = Tracker::default();
        assert_eq!(
            t.observe(0, keys(true, false, false), FEET, false),
            Step::default()
        );
        assert!(!t.observe(10, keys(false, false, false), FEET, true).open);
        let step = t.observe(20, keys(true, false, false), FEET, true);
        assert!(step.open && step.pulse);
        // The pulse reads back as a binding press without closing the map.
        let step = t.observe(40, keys(true, false, true), FEET, true);
        assert!(step.open && step.pulse);
        let step = t.observe(20 + PULSE_MS, keys(false, false, false), FEET, false);
        assert!(step.open && !step.pulse);
        // A stale readback just after the pulse is not the player's close.
        assert!(
            t.observe(30 + PULSE_MS, keys(false, false, true), FEET, false)
                .open
        );
        t.observe(40 + PULSE_MS, Keys::default(), FEET, false);
        assert!(
            t.observe(
                20 + PULSE_MS + RELEASE_MS,
                keys(false, false, true),
                FEET,
                false
            )
            .open
        );
        t.observe(5000, Keys::default(), FEET, false);
        assert!(
            !t.observe(5000 + RELEASE_MS, Keys::default(), FEET, true)
                .open
        );
    }

    #[test]
    fn native_binding_opens_without_a_second_press() {
        let mut t = Tracker::default();
        let step = t.observe(0, keys(false, false, true), FEET, true);
        assert!(step.open && !step.pulse);
        // An Elden Ring rebinding of Map to M is a native press too.
        let mut t = Tracker::default();
        assert!(!t.observe(0, keys(true, false, true), FEET, true).pulse);
    }

    #[test]
    fn close_keys_resume_minecraft_only_after_release() {
        for close in [
            keys(true, false, false),
            keys(false, true, false),
            keys(false, false, true),
        ] {
            let mut t = Tracker::default();
            t.observe(0, keys(false, false, true), FEET, true);
            t.observe(50, keys(false, false, false), None, false);
            assert!(t.observe(1000, close, None, false).open);
            assert!(t.observe(2000, close, None, false).open);
            assert!(t.observe(2010, Keys::default(), None, false).open);
            assert!(
                t.observe(2010 + RELEASE_MS - 1, Keys::default(), None, false)
                    .open
            );
            assert!(
                !t.observe(2010 + RELEASE_MS, Keys::default(), FEET, true)
                    .open
            );
        }
    }

    #[test]
    fn movement_or_travel_proves_the_map_closed() {
        let mut t = Tracker::default();
        t.observe(0, keys(false, false, true), FEET, true);
        assert!(
            t.observe(100, Keys::default(), Some([10.5, 5., 20.5]), false)
                .open
        );
        // Height alone (a ledge settling) does not close it.
        assert!(
            t.observe(200, Keys::default(), Some([10., 9., 20.]), false)
                .open
        );
        assert!(
            !t.observe(300, Keys::default(), Some([400., 5., 20.]), false)
                .open
        );
        // Entry recorded while blocked is taken from the first real sample.
        let mut t = Tracker::default();
        t.observe(0, keys(false, false, true), None, true);
        assert!(t.observe(10, Keys::default(), FEET, false).open);
        assert!(
            !t.observe(20, Keys::default(), Some([20., 5., 20.]), false)
                .open
        );
    }

    #[test]
    fn reset_forgets_an_open_map() {
        let mut t = Tracker::default();
        t.observe(0, keys(false, false, true), FEET, true);
        t.reset();
        assert!(!t.observe(10, keys(false, false, true), FEET, false).open);
    }
}
