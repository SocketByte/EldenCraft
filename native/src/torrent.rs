//! Latest genuine integrated-server Torrent observation. Never an accumulated command.
//!
//! Minecraft owns the summon, the horse entity and its health; native owns the
//! gait. A mount stays in effect only while the guest keeps republishing it.
use serde::{Deserialize, Serialize};
/// The mount crosses native publication, client, integrated-server and native
/// consumption ticks. Keep its original lease through a missed tick; this is a
/// state observation, not a flight velocity or an input command.
pub const FRESH_MS: u64 = 350;
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
        // A missing observation is a scheduling gap, not a dismount. It cannot
        // renew the original timestamp, and get() still expires it. The guest
        // publishes mounted=false for a real dismount; loss of kinematics calls
        // revoke() directly in the world bridge.
        if sample.is_none() {
            return;
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
        assert!(l.get(1000 + FRESH_MS).is_some());
        assert!(l.get(1001 + FRESH_MS).is_none());
        l.observe(context(), Some(sample()), 1001 + FRESH_MS, true);
        assert!(l.get(1001 + FRESH_MS).is_none());
    }
    #[test]
    fn dismount_unobserved_and_mutated_samples_revoke() {
        for (next, observed) in [
            (
                Some(Sample {
                    sequence: 2,
                    mounted: false,
                    ..sample()
                }),
                true,
            ),
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
    fn missed_publications_keep_the_mount_but_never_renew_its_deadline() {
        let mut l = Latest::default();
        l.observe(context(), Some(sample()), 1000, true);
        for now in [1050, 1100, 1200, 1000 + FRESH_MS] {
            l.observe(context(), None, now, false);
            assert_eq!(l.get(now), Some(sample()));
        }
        l.observe(context(), None, 1001 + FRESH_MS, false);
        assert!(l.get(1001 + FRESH_MS).is_none());
        l.observe(context(), Some(sample()), 1001 + FRESH_MS, true);
        assert!(
            l.get(1001 + FRESH_MS).is_none(),
            "replaying an expired mount cannot revive it"
        );
    }
    #[test]
    fn copied_native_clock_avoids_false_future_rejection_between_clock_ticks() {
        let mut l = Latest::default();
        // Extrapolating native time with a different clock can get ahead of the
        // native reader between clock ticks, even for a genuine new server state.
        l.observe(
            context(),
            Some(Sample {
                time_ms: 1008,
                ..sample()
            }),
            1000,
            true,
        );
        assert!(l.get(1000).is_none());
        l.observe(context(), Some(sample()), 1000, true);
        assert!(
            l.get(1000).is_some(),
            "the copied native timestamp is admissible immediately"
        );
    }
    #[test]
    fn interleaved_client_server_ticks_do_not_toggle_the_gait_or_saddle_height() {
        let mut l = Latest::default();
        let mut sequence = 1;
        for frame in 0..600u64 {
            let now = 1000 + frame * 16;
            // A server publication every three frames, one lost publication in
            // each nine. Other client frames can omit the field while copying.
            if frame % 3 == 0 && frame % 9 != 6 {
                l.observe(
                    context(),
                    Some(Sample {
                        sequence,
                        time_ms: now.saturating_sub(80),
                        observed_frame: frame + 1,
                        mounted: true,
                    }),
                    now,
                    true,
                );
                sequence += 1;
            } else {
                l.observe(context(), None, now, false);
            }
            assert!(
                l.get(now).is_some(),
                "frame {frame}: do not switch a summoned rider to foot travel"
            );
        }
        let now = 11000;
        l.observe(
            context(),
            Some(Sample {
                sequence,
                time_ms: now,
                observed_frame: 700,
                mounted: false,
            }),
            now,
            true,
        );
        assert!(
            l.get(now).is_none(),
            "a genuine dismount takes effect immediately"
        );
        l.observe(context(), None, now + 16, false);
        assert!(l.get(now + 16).is_none());
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
