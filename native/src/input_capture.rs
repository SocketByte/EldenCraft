//! Reversible native input capture while the actual Minecraft GUI owns input.
//! Call on the game task after the executable/version and offline gates.
//! `suspend` releases capture on focus/menu/world loss or shutdown.

use eldenring::{cs::CSInGamePad, fd4::FD4PadManager};
use fromsoftware_shared::FromStatic;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RestoreStatus {
    Inactive,
    Restored,
    ReleasedToHost,
}

#[derive(Clone, Copy)]
struct BoolWrite {
    original: bool,
    written: bool,
}
impl BoolWrite {
    fn restore(self, current: &mut bool) -> bool {
        if *current == self.written {
            *current = self.original;
            true
        } else {
            false
        }
    }
}

struct OwnedWrites {
    pad: usize,
    polling: BoolWrite,
}

#[derive(Default)]
pub struct Capture {
    writes: Option<OwnedWrites>,
}

impl Capture {
    pub fn new() -> Self {
        Self::default()
    }

    /// Block CSInGamePad polling while the guest inventory or chat is open.
    ///
    /// # Safety
    /// Game task only, after executable validation and offline/world gates;
    /// no outstanding references into FD4PadManager or its pads.
    pub unsafe fn capture(&mut self) -> Result<(), &'static str> {
        let _ = unsafe { self.suspend() };
        let manager = unsafe { FD4PadManager::instance_mut() }
            .map_err(|_| "input capture waiting for pad manager")?;
        let pad = manager
            .get_in_game_pad_mut()
            .ok_or("input capture waiting for in-game pad")?;
        let polling = BoolWrite {
            original: pad.allow_polling,
            written: false,
        };
        pad.allow_polling = false;
        self.writes = Some(OwnedWrites {
            pad: pad as *mut CSInGamePad as usize,
            polling,
        });
        Ok(())
    }

    /// Restore only values still owned on the same reacquired live pad.
    /// Replaced objects are released without dereferencing old addresses.
    ///
    /// # Safety
    /// Game task after executable validation; no outstanding pad references.
    pub unsafe fn suspend(&mut self) -> RestoreStatus {
        let Some(writes) = self.writes.take() else {
            return RestoreStatus::Inactive;
        };
        let Ok(manager) = (unsafe { FD4PadManager::instance_mut() }) else {
            return RestoreStatus::ReleasedToHost;
        };
        let Some(pad) = manager.get_in_game_pad_mut() else {
            return RestoreStatus::ReleasedToHost;
        };
        if pad as *mut CSInGamePad as usize != writes.pad {
            return RestoreStatus::ReleasedToHost;
        }
        if writes.polling.restore(&mut pad.allow_polling) {
            RestoreStatus::Restored
        } else {
            RestoreStatus::ReleasedToHost
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn inventory_capture_restores_prior_polling_state() {
        for original in [true, false] {
            let mut polling = false;
            assert!(
                BoolWrite {
                    original,
                    written: false
                }
                .restore(&mut polling)
            );
            assert_eq!(polling, original);
        }
    }
    #[test]
    fn restoration_preserves_new_host_state() {
        let mut host_enabled = true;
        assert!(
            !BoolWrite {
                original: false,
                written: false
            }
            .restore(&mut host_enabled)
        );
        assert!(host_enabled);
    }
}
