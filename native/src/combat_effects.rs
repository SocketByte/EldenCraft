//! Fresh, observed vanilla movement effects and exactly-once mace impulses.
use serde::{Deserialize, Serialize};
pub const FRESH_MS: u64 = 250;
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Sample {
    pub sequence: u64,
    pub time_ms: u64,
    pub observed_frame: u64,
    pub speed: f32,
    pub jump_bonus: f32,
    pub slow_falling: bool,
    pub impulse_sequence: u64,
    pub vertical_impulse: f32,
    pub protect_fall: bool,
    pub use_speed: f32,
    pub use_sprint: bool,
}
impl Sample {
    pub fn valid(self) -> bool {
        self.sequence > 0
            && self.time_ms > 0
            && self.observed_frame > 0
            && self.speed.is_finite()
            && (0. ..=3.).contains(&self.speed)
            && self.jump_bonus.is_finite()
            && (0. ..=10.).contains(&self.jump_bonus)
            && self.vertical_impulse.is_finite()
            && (0. ..=40.).contains(&self.vertical_impulse)
            && self.use_speed.is_finite()
            && (0. ..=1.).contains(&self.use_speed)
    }
    pub fn fresh(self, now: u64) -> bool {
        self.valid() && now >= self.time_ms && now - self.time_ms <= FRESH_MS
    }
}
#[derive(Default)]
pub struct Latest {
    context: Option<crate::player_flight::Context>,
    high: u64,
    sample: Option<Sample>,
    guest_impulse: Option<u64>,
    ticket: u64,
}
impl Latest {
    pub fn reset(&mut self) {
        let ticket = self.ticket;
        *self = Self::default();
        self.ticket = ticket;
    }
    pub fn revoke(&mut self) {
        self.sample = None;
    }
    pub fn observe(
        &mut self,
        context: crate::player_flight::Context,
        sample: Option<Sample>,
        now: u64,
        witnessed: bool,
    ) {
        if self.context != Some(context) {
            self.reset();
            self.context = Some(context);
        }
        let Some(s) = sample.filter(|s| s.fresh(now) && witnessed) else {
            self.revoke();
            return;
        };
        if s.sequence < self.high
            || s.sequence == self.high && self.sample != Some(s)
            || self.guest_impulse.is_some_and(|n| s.impulse_sequence < n)
        {
            self.revoke();
            return;
        }
        if self.guest_impulse.is_some_and(|n| s.impulse_sequence > n) {
            self.ticket = self.ticket.saturating_add(1);
        }
        // First observation in a context establishes a baseline, not a smash.
        self.guest_impulse = Some(s.impulse_sequence);
        self.high = s.sequence;
        self.sample = Some(s);
    }
    pub fn get(&self, now: u64) -> Option<Sample> {
        self.sample.filter(|s| s.fresh(now)).map(|s| Sample {
            impulse_sequence: self.ticket,
            ..s
        })
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
            speed: 1.4,
            jump_bonus: 4.,
            slow_falling: false,
            impulse_sequence: 7,
            vertical_impulse: 24.,
            protect_fall: true,
            use_speed: 1.,
            use_sprint: false,
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
    fn impulses_are_new_once_and_never_replayed_on_context_change() {
        let mut l = Latest::default();
        let s = sample();
        l.observe(context(), Some(s), 1000, true);
        assert_eq!(l.get(1000).unwrap().impulse_sequence, 0);
        let s = Sample {
            sequence: 2,
            impulse_sequence: 8,
            ..s
        };
        l.observe(context(), Some(s), 1000, true);
        assert_eq!(l.get(1000).unwrap().impulse_sequence, 1);
        l.observe(context(), Some(s), 1001, true);
        assert_eq!(l.get(1001).unwrap().impulse_sequence, 1);
        l.observe(
            crate::player_flight::Context {
                session: 9,
                ..context()
            },
            Some(s),
            1001,
            true,
        );
        assert_eq!(l.get(1001).unwrap().impulse_sequence, 1);
        assert!(l.get(1251).is_none());
    }
    #[test]
    fn mutation_missing_witness_and_invalid_effects_revoke() {
        let mut l = Latest::default();
        l.observe(context(), Some(sample()), 1000, true);
        l.observe(
            context(),
            Some(Sample {
                speed: 2.,
                ..sample()
            }),
            1000,
            true,
        );
        assert!(l.get(1000).is_none());
        l.observe(
            context(),
            Some(Sample {
                sequence: 2,
                ..sample()
            }),
            1000,
            false,
        );
        assert!(l.get(1000).is_none());
        for speed in [f32::NAN, -0.1, 3.1] {
            assert!(!Sample { speed, ..sample() }.valid());
        }
    }
}
