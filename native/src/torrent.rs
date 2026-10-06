//! Latest genuine integrated-server Torrent observation. Never an accumulated command.
//!
//! Minecraft owns the summon, the horse entity and its health; native owns the
//! gait. A mount stays in effect only while the guest keeps republishing it.
use serde::{Deserialize, Serialize};
pub const FRESH_MS: u64 = 150;
/// Grounded acceleration in blocks/tick, a top-tier horse movement-speed
/// attribute (vanilla ridden travel; terminal gallop is about 11 m/s).
pub const GALLOP_ACCELERATION: f32 = 0.25;
/// Held sprint dashes, as Torrent does without spending stamina (about 16.5 m/s).
pub const DASH_MULTIPLIER: f32 = 1.5;
/// AbstractHorse.getFlyingSpeed: a tenth of the movement speed.
pub const AIR_ACCELERATION: f32 = 0.025;
/// Takeoff of both the ground jump and the single air jump; a 2.25 m rise each
/// under vanilla gravity.
pub const JUMP_MPS: f32 = 12.0;
pub const AIR_JUMPS: u8 = 1;
/// A dash at a 10 Hz native step still fits; on foot the bound stays 1 m.
pub const MAX_STEP_M: f32 = 2.0;
/// Rider feet above the horse: Horse passenger attachment 1.44375 m minus the
/// player's 0.6 m vehicle attachment. The guest seats the avatar identically.
pub const RIDER_LIFT_M: f32 = 0.84375;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Sample {
    pub sequence: u64,
    pub time_ms: u64,
    pub observed_frame: u64,
    pub mounted: bool,
}
impl Sample {
    pub fn valid(self) -> bool {
        self.sequence > 0 && self.time_ms > 0 && self.observed_frame > 0
    }
    pub fn fresh(self, now: u64) -> bool {
        self.valid() && self.time_ms <= now && now - self.time_ms <= FRESH_MS
    }
}

#[derive(Default)]
pub struct Latest {
    context: Option<crate::player_flight::Context>,
    high: u64,
    sample: Option<Sample>,
}
impl Latest {
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    pub fn revoke(&mut self) {
        self.sample = None;
    }
    pub fn observe(
        &mut self,
        context: crate::player_flight::Context,
        sample: Option<Sample>,
        now: u64,
        observed: bool,
    ) {
        if self.context != Some(context) {
            self.reset();
            self.context = Some(context);
        }
        let Some(s) = sample.filter(|s| s.fresh(now) && observed) else {
            self.revoke();
            return;
        };
        // A repeated envelope keeps its original timestamp; it never renews the mount.
        if s.sequence < self.high || (s.sequence == self.high && self.sample != Some(s)) {
            self.revoke();
            return;
        }
        self.high = s.sequence;
        self.sample = Some(s);
    }
    pub fn get(&self, now: u64) -> Option<Sample> {
        self.sample.filter(|s| s.mounted && s.fresh(now))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample() -> Sample {
        Sample {
            sequence: 1,
            time_ms: 1000,
            observed_frame: 3,
            mounted: true,
        }
    }
    fn context() -> crate::player_flight::Context {
        crate::player_flight::Context {
            pid: 1,
            session: 2,
            epoch: 3,
            map: 4,
        }
    }
    #[test]
    fn a_mount_expires_unless_the_guest_republishes_it() {
        let mut l = Latest::default();
        l.observe(context(), Some(sample()), 1000, true);
        assert!(l.get(1150).is_some());
        assert!(l.get(1151).is_none());
        l.observe(context(), Some(sample()), 1151, true);
        assert!(l.get(1151).is_none());
    }
    #[test]
    fn dismount_missing_unobserved_and_regressed_samples_revoke() {
        for (next, observed) in [
            (
                Some(Sample {
                    sequence: 2,
                    mounted: false,
                    ..sample()
                }),
                true,
            ),
            (None, true),
            (
                Some(Sample {
                    sequence: 2,
                    ..sample()
                }),
                false,
            ),
            (
                Some(Sample {
                    observed_frame: 9,
                    ..sample()
                }),
                true,
            ),
        ] {
            let mut l = Latest::default();
            l.observe(context(), Some(sample()), 1000, true);
            l.observe(context(), next, 1000, observed);
            assert!(l.get(1000).is_none());
        }
    }
    #[test]
    fn a_new_context_resets_the_sequence_high_water() {
        let mut l = Latest::default();
        l.observe(
            context(),
            Some(Sample {
                sequence: 9,
                ..sample()
            }),
            1000,
            true,
        );
        l.observe(
            crate::player_flight::Context {
                session: 7,
                ..context()
            },
            Some(sample()),
            1000,
            true,
        );
        assert!(l.get(1000).is_some());
    }
    #[test]
    fn identity_fields_are_required() {
        for s in [
            Sample {
                sequence: 0,
                ..sample()
            },
            Sample {
                time_ms: 0,
                ..sample()
            },
            Sample {
                observed_frame: 0,
                ..sample()
            },
        ] {
            assert!(!s.valid());
        }
    }
}
