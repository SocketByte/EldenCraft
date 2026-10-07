//! Minecraft owns the paired player's footsteps and Torrent hoofbeats.
//!
//! The supported executable's four TAE sound paths already skip categories 8
//! and 9 while Mimic Veil is active. Intercept that same decision and use its
//! no-sound epilogue for our local player only. No character mimic flags, saved
//! volume settings, enemy sounds or other sound categories are changed.

use fromsoftware_shared::program::Program;
use ilhook::x64::{HookFlags, Registers, hook_closure_jmp_to_ret};
use pelite::pe64::PeObject;
use std::sync::{
    Mutex,
    atomic::{AtomicBool, Ordering},
};

const LEASE_MS: u64 = 100;
static INSTALLED: AtomicBool = AtomicBool::new(false);
static FAULTED: AtomicBool = AtomicBool::new(false);
static PERMIT: Mutex<Option<Permit>> = Mutex::new(None);

#[derive(Clone, Copy)]
struct Permit {
    player: usize,
    issued: u64,
}

impl Permit {
    fn suppresses(self, owner: usize, category: u32, now: u64) -> bool {
        owner != 0
            && owner == self.player
            && matches!(category, 8 | 9)
            && now >= self.issued
            && now - self.issued < LEASE_MS
    }
}

#[derive(Clone, Copy)]
enum Source {
    RsiRbx,
    RdiRbp,
    RdiR15,
}

#[derive(Clone, Copy)]
struct Site {
    at: usize,
    prefix: &'static [u8],
    epilogue: usize,
    epilogue_prefix: &'static [u8],
    source: Source,
}

impl Site {
    fn event(self, regs: &Registers) -> (usize, u32) {
        match self.source {
            Source::RsiRbx => (regs.rsi as usize, regs.rbx as u32),
            Source::RdiRbp => (regs.rdi as usize, regs.rbp as u32),
            Source::RdiR15 => (regs.rdi as usize, regs.r15 as u32),
        }
    }

    fn matches(self, image: &[u8]) -> bool {
        image.get(self.at..self.at + self.prefix.len()) == Some(self.prefix)
            && image.get(self.epilogue..self.epilogue + self.epilogue_prefix.len())
                == Some(self.epilogue_prefix)
    }
}

// Verified in worldwide 2.7.1.0, SHA256 guarded by engine::verify_executable.
// Each prefix tests ChrIns+0x1c7 bit 5, then skips only sound types 8 and 9.
// Registers below contain the live ChrIns and the original TAE sound category.
// Intercept before that test so the original trampoline preserves its flags
// and native Mimic Veil behavior whenever the replacement does not own sound.
const SITES: &[Site] = &[
    Site {
        at: 0x42b045,
        prefix: &[
            0xf6, 0x86, 0xc7, 0x01, 0, 0, 0x20, 0x74, 0x0c, 0x8d, 0x43, 0xf8, 0x83, 0xf8, 0x01,
            0x0f, 0x86, 0x84, 0x02, 0, 0,
        ],
        epilogue: 0x42b2de,
        epilogue_prefix: &[
            0x48, 0x8b, 0x9c, 0x24, 0x08, 0x01, 0, 0, 0x48, 0x81, 0xc4, 0xc0,
        ],
        source: Source::RsiRbx,
    },
    Site {
        at: 0x42b37e,
        prefix: &[
            0xf6, 0x87, 0xc7, 0x01, 0, 0, 0x20, 0x74, 0x0c, 0x8d, 0x45, 0xf8, 0x83, 0xf8, 0x01,
            0x0f, 0x86, 0x95, 0, 0, 0,
        ],
        epilogue: 0x42b428,
        epilogue_prefix: &[
            0x48, 0x8b, 0x5c, 0x24, 0x78, 0x48, 0x83, 0xc4, 0x50, 0x5f, 0x5e, 0x5d,
        ],
        source: Source::RdiRbp,
    },
    Site {
        at: 0x42b4e7,
        prefix: &[
            0xf6, 0x87, 0xc7, 0x01, 0, 0, 0x20, 0x74, 0x0c, 0x8d, 0x45, 0xf8, 0x83, 0xf8, 0x01,
            0x0f, 0x86, 0x98, 0, 0, 0,
        ],
        epilogue: 0x42b594,
        epilogue_prefix: &[
            0x48, 0x8b, 0x5c, 0x24, 0x78, 0x48, 0x83, 0xc4, 0x50, 0x5f, 0x5e, 0x5d,
        ],
        source: Source::RdiRbp,
    },
    Site {
        at: 0x42c04a,
        prefix: &[
            0xf6, 0x87, 0xc7, 0x01, 0, 0, 0x20, 0x74, 0x0d, 0x41, 0x8d, 0x47, 0xf8, 0x83, 0xf8,
            0x01, 0x0f, 0x86, 0x70, 0x01, 0, 0,
        ],
        epilogue: 0x42c1d0,
        epilogue_prefix: &[
            0x48, 0x8b, 0x9c, 0x24, 0xe8, 0, 0, 0, 0x48, 0x81, 0xc4, 0xa0,
        ],
        source: Source::RdiR15,
    },
];

/// Refresh only after the existing offline/foreground/compositor gates pass.
/// `player` is the validated local PlayerIns (its ChrIns is at offset zero).
pub fn authorize(player: Option<usize>, now: u64) {
    if let Ok(mut permit) = PERMIT.lock() {
        *permit = player.filter(|p| *p != 0).map(|player| Permit {
            player,
            issued: now,
        });
    }
}

pub fn revoke() {
    authorize(None, 0);
}

/// # Safety
/// Call after the exact executable guard and module lifetime pin, before play.
pub unsafe fn install() -> Result<(), String> {
    let program = Program::current();
    let image = program.image();
    if !SITES.iter().all(|site| site.matches(image)) {
        return Err("native footstep sound fingerprints do not match".into());
    }
    if INSTALLED.load(Ordering::Acquire) {
        return Err("native footstep suppression already installed".into());
    }
    let base = image.as_ptr() as usize;
    for &site in SITES {
        let callback = move |regs: *mut Registers, original: usize| {
            if !INSTALLED.load(Ordering::Acquire)
                || FAULTED.load(Ordering::Acquire)
                || crate::hosting::shutting_down()
                || crate::engine::eldencraft_passthrough_active() == 0
            {
                return original;
            }
            let result = std::panic::catch_unwind(|| {
                let (owner, category) = site.event(unsafe { &*regs });
                let now = crate::world_transport::now();
                let suppressed = PERMIT.try_lock().ok().is_some_and(|permit| {
                    permit.is_some_and(|permit| permit.suppresses(owner, category, now))
                });
                suppressed.then_some(category)
            });
            match result {
                Ok(Some(category)) => {
                    // The original skip executes `lea eax, [category - 8]`
                    // before its epilogue. Preserve that register result too.
                    unsafe { (*regs).rax = u64::from(category - 8) };
                    base + site.epilogue
                }
                Ok(None) => original,
                Err(_) => {
                    FAULTED.store(true, Ordering::Release);
                    original
                }
            }
        };
        let address = base + site.at;
        let hook = unsafe {
            crate::hosting::hook(address, |option| {
                hook_closure_jmp_to_ret(address, callback, option, HookFlags::empty())
            })
        }
        .map_err(|error| format!("native footstep hook at {:x}: {error:?}", site.at))?;
        // hosting owns restoration; callbacks remain allocated across reload.
        // A partial installation stays disabled and runs native instructions.
        std::mem::forget(hook);
    }
    INSTALLED.store(true, Ordering::Release);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_paired_players_fresh_walk_and_run_sounds_are_suppressed() {
        let permit = Permit {
            player: 0x1000,
            issued: 1000,
        };
        for category in 0..=15 {
            assert_eq!(
                permit.suppresses(0x1000, category, 1050),
                matches!(category, 8 | 9)
            );
            assert!(!permit.suppresses(0x2000, category, 1050), "enemy sound");
        }
        for now in [0, 999, 1100, u64::MAX] {
            assert!(
                !permit.suppresses(0x1000, 8, now),
                "expired or regressed time"
            );
        }
        assert!(permit.suppresses(0x1000, 9, 1099));
        assert!(!permit.suppresses(0, 8, 1050));
    }

    #[test]
    fn all_four_native_paths_read_the_correct_owner_and_sound_registers() {
        // Registers consists only of integer scalars.
        let mut regs: Registers = unsafe { std::mem::zeroed() };
        regs.rsi = 0x1000;
        regs.rdi = 0x2000;
        regs.rbx = 8;
        regs.rbp = 9;
        regs.r15 = 2;
        assert_eq!(SITES[0].event(&regs), (0x1000, 8));
        assert_eq!(SITES[1].event(&regs), (0x2000, 9));
        assert_eq!(SITES[2].event(&regs), (0x2000, 9));
        assert_eq!(SITES[3].event(&regs), (0x2000, 2));
        assert_eq!(std::mem::offset_of!(eldenring::cs::PlayerIns, chr_ins), 0);
    }

    #[test]
    fn changed_decisions_or_epilogues_disable_installation() {
        let mut image = vec![0; 0x42c200];
        for site in SITES {
            image[site.at..site.at + site.prefix.len()].copy_from_slice(site.prefix);
            image[site.epilogue..site.epilogue + site.epilogue_prefix.len()]
                .copy_from_slice(site.epilogue_prefix);
        }
        assert!(SITES.iter().all(|site| site.matches(&image)));
        for site in SITES {
            image[site.at] ^= 1;
            assert!(!site.matches(&image));
            image[site.at] ^= 1;
            image[site.epilogue] ^= 1;
            assert!(!site.matches(&image));
            image[site.epilogue] ^= 1;
        }
    }
}
