//! Minecraft clearance through the game's own two-proxy capsule resize routine.
//! No Havok pointers, shapes, flags, or character dimension fields are patched.
//! The game owns shape caching/refcounts and broadphase replacement. Restore
//! only our exact last dimensions on the same live local physics object.
use eldenring::cs::{CSChrPhysicsModule, PlayerIns};

pub(crate) const RESIZE: usize = 0x464670;
const MAIN: usize = 0x340;
const SECONDARY: usize = 0x330;
const FINGERPRINTS: &[(usize, &[u8])] = &[
    (
        RESIZE,
        &[
            0x48, 0x89, 0x5c, 0x24, 0x08, 0x48, 0x89, 0x74, 0x24, 0x10, 0x57, 0x48, 0x83, 0xec,
            0x20, 0x48, 0x8b, 0xd9, 0x49, 0x8b, 0xf0, 0x48, 0x8b, 0x89, 0xa0, 0, 0, 0,
        ],
    ),
    (
        0x464694,
        &[
            0x44, 0x0f, 0xb6, 0x4a, 0x0c, 0xf3, 0x0f, 0x10, 0x52, 0x04, 0xf3, 0x0f, 0x10, 0x0a,
            0xe8, 0x59, 0x77, 0x7f, 0,
        ],
    ),
    (
        0x464727,
        &[
            0x8b, 0x07, 0x89, 0x83, 0x40, 0x03, 0, 0, 0x8b, 0x47, 0x04, 0x89, 0x83, 0x44, 0x03, 0,
            0, 0x8b, 0x06, 0x89, 0x83, 0x30, 0x03, 0, 0,
        ],
    ),
    (
        0xc5dbb3,
        &[
            0x0f, 0x28, 0xd6, 0x0f, 0x28, 0xcf, 0xe8, 0xe2, 0x8b, 0, 0, 0x48, 0x89, 0x83, 0x80, 0,
            0, 0,
        ],
    ),
];
#[repr(C, align(16))]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Dimensions([u32; 4]);
impl Dimensions {
    fn profile(self) -> Option<Self> {
        let height = f32::from_bits(self.0[0]);
        let radius = f32::from_bits(self.0[1]);
        if !height.is_finite()
            || !radius.is_finite()
            || !(0.1..=5.).contains(&height)
            || !(0.05..=2.).contains(&radius)
        {
            return None;
        }
        let mut result = self;
        result.0[0] = height.min(1.8).to_bits();
        result.0[1] = radius.min(0.3).to_bits();
        Some(result)
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Pair {
    pub main: Dimensions,
    pub secondary: Dimensions,
}
impl Pair {
    fn sizes(self) -> [[u32; 2]; 2] {
        [
            [self.main.0[0], self.main.0[1]],
            [self.secondary.0[0], self.secondary.0[1]],
        ]
    }
    fn profile(self) -> Option<Self> {
        Some(Self {
            main: self.main.profile()?,
            secondary: self.secondary.profile()?,
        })
    }
}
pub(crate) type Resize =
    unsafe extern "system" fn(*mut CSChrPhysicsModule, *const Dimensions, *const Dimensions);
#[derive(Clone, Copy)]
struct Lease {
    player: usize,
    physics: usize,
    original: Pair,
    written: Pair,
}
impl Lease {
    fn repeats(self, player: usize, physics: usize, request: Pair) -> bool {
        self.player == player && self.physics == physics && self.written.sizes() == request.sizes()
    }
    fn restoration(self, player: usize, physics: usize, actual: Pair) -> Option<Pair> {
        (self.player == player && self.physics == physics && self.written == actual)
            .then_some(self.original)
    }
}
#[derive(Default)]
pub struct Driver {
    resize: Option<Resize>,
    lease: Option<Lease>,
}
impl Driver {
    pub fn profile(&self) -> Option<[[f32; 2]; 2]> {
        self.lease
            .map(|l| l.written.sizes().map(|d| d.map(f32::from_bits)))
    }
    /// Native animation/pose changes resize these same proxies again. Scope
    /// their incoming sizes too, or a PostPhysics resize lasts only until the
    /// next native update. Arguments remain stack-owned and options unchanged.
    pub unsafe fn intercept(
        &mut self,
        player: usize,
        physics: usize,
        main: *const Dimensions,
        secondary: *const Dimensions,
    ) -> Option<Pair> {
        if main.is_null() || secondary.is_null() || !unsafe { Self::current(player, physics) } {
            return None;
        }
        let original = Pair {
            main: unsafe { std::ptr::read_unaligned(main) },
            secondary: unsafe { std::ptr::read_unaligned(secondary) },
        };
        let written = original.profile()?;
        if original == written {
            // A native caller may submit the cached (already scoped) sizes.
            // Preserve restoration in that case; a genuinely smaller native
            // pose supersedes the lease and already has enough clearance.
            if !self
                .lease
                .is_some_and(|l| l.repeats(player, physics, original))
            {
                self.lease = None;
            }
            return None;
        }
        // Resize stores only height/radius, not the trailing cached options.
        let mut before = unsafe { Self::read(physics) };
        before.main.0[..2].copy_from_slice(&original.main.0[..2]);
        before.secondary.0[..2].copy_from_slice(&original.secondary.0[..2]);
        let mut after = before;
        after.main.0[..2].copy_from_slice(&written.main.0[..2]);
        after.secondary.0[..2].copy_from_slice(&written.secondary.0[..2]);
        self.lease = Some(Lease {
            player,
            physics,
            original: before,
            written: after,
        });
        Some(written)
    }

    pub fn install(image: &[u8]) -> Result<Self, String> {
        for &(at, bytes) in FINGERPRINTS {
            if image.get(at..at + bytes.len()) != Some(bytes) {
                return Err(format!("player capsule fingerprint mismatch at {at:x}"));
            }
        }
        if std::mem::offset_of!(CSChrPhysicsModule, hit_height) != 0x2f0
            || std::mem::offset_of!(CSChrPhysicsModule, chr_hit_height) != 0x2e0
        {
            return Err("player capsule SDK layout mismatch".into());
        }
        Ok(Self {
            resize: Some(unsafe {
                std::mem::transmute::<usize, Resize>(image.as_ptr() as usize + RESIZE)
            }),
            lease: None,
        })
    }
    unsafe fn read(physics: usize) -> Pair {
        unsafe {
            Pair {
                main: std::ptr::read_unaligned((physics + MAIN) as *const Dimensions),
                secondary: std::ptr::read_unaligned((physics + SECONDARY) as *const Dimensions),
            }
        }
    }
    /// Called after the movement driver's full offline/foreground/health/ladder
    /// gates on the game task thread. The stage rechecks all those gates.
    pub unsafe fn apply(&mut self, player: usize, physics: usize) -> Result<(), &'static str> {
        let Some(resize) = self.resize else {
            return Ok(());
        };
        if !unsafe { Self::current(player, physics) } {
            return Err("capsule owner changed");
        }
        let actual = unsafe { Self::read(physics) };
        if self
            .lease
            .is_some_and(|l| l.player == player && l.physics == physics && l.written == actual)
        {
            return Ok(());
        }
        // A native animation resize supersedes our previous dimensions. Capture
        // that newer native value; never restore a preceding body's/pose's size.
        self.lease = None;
        let written = actual
            .profile()
            .ok_or("native capsule dimensions invalid")?;
        if actual == written {
            return Ok(());
        }
        unsafe {
            resize(physics as *mut _, &written.main, &written.secondary);
        }
        if unsafe { Self::read(physics) } != written {
            return Err("native capsule resize readback mismatch");
        }
        self.lease = Some(Lease {
            player,
            physics,
            original: actual,
            written,
        });
        Ok(())
    }
    unsafe fn current(player: usize, physics: usize) -> bool {
        let Ok(p) = (unsafe { PlayerIns::local_player() }) else {
            return false;
        };
        let module = &*p.chr_ins.modules.physics;
        p as *const _ as usize == player
            && module as *const _ as usize == physics
            && std::ptr::eq(module.owner.as_ptr(), &p.chr_ins)
            && unsafe { std::ptr::read_unaligned((physics + 0x98) as *const usize) } != 0
    }
    /// Game task thread only; even native ladder/menu gates can release our own
    /// dimensions. Identity loss discards the lease without dereferencing it.
    pub unsafe fn release(&mut self) {
        let Some(l) = self.lease.take() else {
            return;
        };
        if unsafe { Self::current(l.player, l.physics) }
            && let Some(original) =
                l.restoration(l.player, l.physics, unsafe { Self::read(l.physics) })
            && let Some(resize) = self.resize
        {
            unsafe {
                resize(l.physics as *mut _, &original.main, &original.secondary);
            }
        }
    }
    pub unsafe fn release_at_stage(&mut self, physics: usize) {
        if self.lease.is_some_and(|l| l.physics == physics) {
            unsafe { self.release() };
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn dims(height: f32, radius: f32) -> Dimensions {
        Dimensions([height.to_bits(), radius.to_bits(), 0xdeadbeef, 1])
    }
    #[test]
    fn doorway_clearance_preserves_native_options_and_does_not_enlarge() {
        let d = dims(2.2, 0.5).profile().unwrap();
        assert_eq!(d, dims(1.8, 0.3));
        assert!(
            f32::from_bits(d.0[1]) * 2. < 1. - 3. / 16.,
            "open door leaf leaves 13/16 clearance"
        );
        assert!(
            f32::from_bits(d.0[0]) < 2.,
            "two-block lintel clears the capsule"
        );
        assert_eq!(dims(1.2, 0.25).profile(), Some(dims(1.2, 0.25)));
        // The native creator supports flattened/spherical secondary shapes;
        // height < diameter must not silently disable all movement authority.
        assert_eq!(dims(0.4, 0.5).profile(), Some(dims(0.4, 0.3)));
        for (h, r) in [
            (f32::NAN, 0.3),
            (1.8, f32::INFINITY),
            (0.01, 0.3),
            (6., 0.3),
            (1.8, 0.),
        ] {
            assert!(dims(h, r).profile().is_none());
        }
    }
    #[test]
    fn restoration_never_stomps_a_new_native_pose_or_other_body() {
        let original = Pair {
            main: dims(2.2, 0.5),
            secondary: dims(2., 0.4),
        };
        let written = original.profile().unwrap();
        let lease = Lease {
            player: 10,
            physics: 20,
            original,
            written,
        };
        assert_eq!(lease.restoration(10, 20, written), Some(original));
        assert!(lease.repeats(10, 20, written));
        assert!(!lease.repeats(11, 20, written));
        assert!(!lease.repeats(10, 21, written));
        assert!(!lease.repeats(10, 20, original));
        assert_eq!(lease.restoration(11, 20, written), None);
        assert_eq!(lease.restoration(10, 21, written), None);
        assert_eq!(lease.restoration(10, 20, original), None);
        let changed = Pair {
            main: dims(1.2, 0.25),
            ..written
        };
        assert_eq!(lease.restoration(10, 20, changed), None);
    }
}
