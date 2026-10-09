//! Latest integrated-server glide, creative, fluid or climb velocity. Never an accumulated command.
use serde::{Deserialize, Serialize};
/// The pipeline (guest read, server tick, guest publish, native read) already
/// spends up to ~130 ms of a sample's life before native sees it, and samples
/// arrive every 50 ms. A tighter lease dropped glides on Minecraft frame jitter.
pub const FRESH_MS: u64 = 250;
pub const MAX_SPEED: f32 = 120.0;
#[derive(Clone, Copy, Debug, Default)]
#[allow(dead_code)] // Low-rate movement log emits the complete observation.
pub struct Observation {
    pub seconds: f32,
    pub actual_mps: [f32; 3],
    pub requested_mps: [f32; 3],
    pub grounded: bool,
    pub sample_age_ms: u64,
}
#[derive(Default)]
pub struct Meter {
    previous: Option<([f32; 3], [f32; 3], f32)>,
    seconds: f32,
    actual: [f32; 3],
    requested: [f32; 3],
    last: Option<Observation>,
}
impl Meter {
    pub fn observe(
        &mut self,
        position: [f32; 3],
        sample: Option<Sample>,
        now: u64,
        dt: f32,
        grounded: bool,
    ) -> Option<Observation> {
        let Some(s) = sample.filter(|s| s.gliding && s.fresh(now)) else {
            self.previous = None;
            self.seconds = 0.;
            self.actual = [0.; 3];
            self.requested = [0.; 3];
            return self.last;
        };
        if let Some((prior, velocity, elapsed)) = self.previous {
            let d: [f32; 3] = std::array::from_fn(|i| position[i] - prior[i]);
            if d.iter().all(|v| v.is_finite())
                && d.iter().map(|v| v * v).sum::<f32>() <= 36.01
                && elapsed > 0.
                && elapsed <= 0.1
            {
                self.seconds += elapsed;
                for i in 0..3 {
                    self.actual[i] += d[i];
                    self.requested[i] += velocity[i] * elapsed;
                }
                if self.seconds >= 0.2 {
                    self.last = Some(Observation {
                        seconds: self.seconds,
                        actual_mps: self.actual.map(|v| v / self.seconds),
                        requested_mps: self.requested.map(|v| v / self.seconds),
                        grounded,
                        sample_age_ms: now - s.time_ms,
                    });
                    self.seconds = 0.;
                    self.actual = [0.; 3];
                    self.requested = [0.; 3];
                }
            } else {
                self.seconds = 0.;
                self.actual = [0.; 3];
                self.requested = [0.; 3];
            }
        }
        self.previous = Some((position, s.velocity, dt));
        self.last
    }
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Sample {
    pub sequence: u64,
    pub time_ms: u64,
    pub observed_frame: u64,
    pub gliding: bool,
    #[serde(default)]
    pub travel: Travel,
    pub velocity: [f32; 3],
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Travel {
    #[default]
    None,
    Water,
    Lava,
    Climb,
    Creative,
}
impl Sample {
    pub fn active(self) -> bool {
        self.gliding || self.travel != Travel::None
    }
    pub fn valid(self) -> bool {
        self.sequence > 0
            && self.time_ms > 0
            && self.observed_frame > 0
            && self.velocity.iter().all(|v| v.is_finite())
            && self.velocity.iter().map(|v| v * v).sum::<f32>() <= MAX_SPEED * MAX_SPEED
            && (!self.gliding || self.travel == Travel::None)
            && (self.travel == Travel::None
                || self.velocity.iter().map(|v| v * v).sum::<f32>() <= 30.0 * 30.0)
            && (self.active() || self.velocity == [0.; 3])
    }
    pub fn fresh(self, now: u64) -> bool {
        self.valid() && self.time_ms <= now && now - self.time_ms <= FRESH_MS
    }
    pub fn displacement(self, now: u64, dt: f32) -> Option<[f32; 3]> {
        if !self.active() || !self.fresh(now) || !dt.is_finite() || !(0.00001..=0.1).contains(&dt) {
            return None;
        }
        let d = self.velocity.map(|v| v * dt);
        (d.iter().map(|v| v * v).sum::<f32>() <= 36.).then_some(d)
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Context {
    pub pid: u32,
    pub session: u64,
    pub epoch: u64,
    pub map: u32,
}
#[derive(Default)]
pub struct Latest {
    context: Option<Context>,
    high: u64,
    sample: Option<Sample>,
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
    pub fn observe(&mut self, context: Context, sample: Option<Sample>, now: u64, observed: bool) {
        if self.context != Some(context) {
            self.reset();
            self.context = Some(context);
        }
        let Some(s) = sample.filter(|s| s.fresh(now) && observed) else {
            self.revoke();
            return;
        };
        // A repeated envelope may keep the *original* lease but never renew it.
        if s.sequence < self.high || (s.sequence == self.high && self.sample != Some(s)) {
            self.revoke();
            return;
        }
        if s.sequence > self.high {
            self.ticket = self.ticket.saturating_add(1);
        }
        self.high = s.sequence;
        self.sample = Some(s);
    }
    // Native monotonic ticket survives a guest restart whose sequence returns
    // to1, so movement's release barrier cannot either replay or lock out it.
    pub fn get(&self, now: u64) -> Option<Sample> {
        self.sample
            .filter(|s| s.active() && s.fresh(now))
            .map(|s| Sample {
                sequence: self.ticket,
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
            gliding: true,
            travel: Travel::None,
            velocity: [3., -2., 4.],
        }
    }
    fn context() -> Context {
        Context {
            pid: 1,
            session: 2,
            epoch: 3,
            map: 4,
        }
    }
    #[test]
    fn stale_and_repeated_samples_cannot_extend_motion() {
        let mut l = Latest::default();
        l.observe(context(), Some(sample()), 1000, true);
        assert!(l.get(1000 + FRESH_MS).is_some());
        l.observe(context(), Some(sample()), 1001 + FRESH_MS, true);
        assert!(l.get(1001 + FRESH_MS).is_none());
    }
    #[test]
    fn sequence_is_immutable_and_context_change_resets_highwater() {
        let mut l = Latest::default();
        let mut s = sample();
        s.sequence = 9;
        l.observe(context(), Some(s), 1000, true);
        s.velocity[0] = 5.;
        l.observe(context(), Some(s), 1000, true);
        assert!(l.get(1000).is_none());
        l.observe(
            Context {
                session: 3,
                ..context()
            },
            Some(sample()),
            1000,
            true,
        );
        assert!(l.get(1000).is_some());
    }
    #[test]
    fn missing_stop_and_unobserved_frames_revoke() {
        for stop in [
            None,
            Some(Sample {
                gliding: false,
                velocity: [0.; 3],
                ..sample()
            }),
        ] {
            let mut l = Latest::default();
            l.observe(context(), Some(sample()), 1000, true);
            l.observe(context(), stop, 1000, true);
            assert!(l.get(1000).is_none());
        }
        let mut l = Latest::default();
        l.observe(context(), Some(sample()), 1000, false);
        assert!(l.get(1000).is_none());
    }
    #[test]
    fn finite_vector_and_native_step_bounds() {
        assert!(sample().displacement(1000, 0.05).is_some());
        for v in [f32::NAN, f32::INFINITY, 121.] {
            assert!(
                !Sample {
                    velocity: [v, 0., 0.],
                    ..sample()
                }
                .valid()
            );
        }
        assert!(
            Sample {
                velocity: [120., 0., 0.],
                ..sample()
            }
            .displacement(1000, 0.1)
            .is_none()
        );
        assert!(sample().displacement(999, 0.01).is_none());
    }
    #[test]
    fn guest_restart_cannot_reuse_a_native_release_ticket() {
        let mut l = Latest::default();
        l.observe(
            context(),
            Some(Sample {
                sequence: 80,
                ..sample()
            }),
            1000,
            true,
        );
        let first = l.get(1000).unwrap().sequence;
        l.reset();
        l.observe(
            Context {
                pid: 2,
                ..context()
            },
            Some(sample()),
            1000,
            true,
        );
        assert!(l.get(1000).unwrap().sequence > first);
    }
    #[test]
    fn non_gliding_modes_share_expiry_stop_and_frame_witness() {
        for travel in [Travel::Water, Travel::Lava, Travel::Climb, Travel::Creative] {
            let s = Sample {
                gliding: false,
                travel,
                velocity: [1., 2., 0.],
                ..sample()
            };
            assert!(s.valid());
            assert_eq!(s.displacement(1000, 0.05), Some([0.05, 0.1, 0.]));
            let mut latest = Latest::default();
            latest.observe(context(), Some(s), 1000, true);
            assert!(latest.get(1000 + FRESH_MS).is_some());
            assert!(latest.get(1001 + FRESH_MS).is_none());
            latest.observe(context(), Some(s), 1000, false);
            assert!(latest.get(1000).is_none());
            latest.observe(context(), Some(s), 1000, true);
            latest.observe(
                context(),
                Some(Sample {
                    sequence: 2,
                    travel: Travel::None,
                    velocity: [0.; 3],
                    ..s
                }),
                1000,
                true,
            );
            assert!(latest.get(1000).is_none());
            assert!(!Sample { gliding: true, ..s }.valid());
            assert!(
                !Sample {
                    velocity: [30., 1., 0.],
                    ..s
                }
                .valid()
            );
        }
    }
    #[test]
    fn creative_hover_remains_active_without_displacement() {
        let s = Sample {
            gliding: false,
            travel: Travel::Creative,
            velocity: [0.; 3],
            ..sample()
        };
        assert!(s.active());
        assert!(s.valid());
        assert_eq!(s.displacement(1000, 0.05), Some([0.; 3]));
        let mut latest = Latest::default();
        latest.observe(context(), Some(s), 1000, true);
        assert!(latest.get(1000).is_some());
        assert!(latest.get(1001 + FRESH_MS).is_none());
        assert_eq!(
            serde_json::from_str::<Sample>(&serde_json::to_string(&s).unwrap()).unwrap(),
            s
        );
    }
    #[test]
    fn old_glide_wire_defaults_to_no_fluid_or_climb() {
        let s: Sample = serde_json::from_str(
            r#"{"sequence":1,"time_ms":1000,"observed_frame":3,"gliding":true,"velocity":[1,2,0]}"#,
        )
        .unwrap();
        assert_eq!(s.travel, Travel::None);
        assert!(s.valid());
        assert!(serde_json::from_str::<Sample>(r#"{"sequence":1,"time_ms":1000,"observed_frame":3,"gliding":false,"travel":"teleport","velocity":[0,0,0]}"#).is_err());
    }
    #[test]
    fn meter_uses_only_consecutive_glide_displacement_not_ordinary_walk() {
        let mut m = Meter::default();
        for i in 0..6 {
            let now = 1000 + i * 50;
            let s = Sample {
                time_ms: now,
                velocity: [10., 0., 0.],
                ..sample()
            };
            m.observe([i as f32 * 0.25, 0., 0.], Some(s), now, 0.05, false);
        }
        let o = m.last.unwrap();
        assert!((o.actual_mps[0] - 5.).abs() < 0.001);
        assert!((o.requested_mps[0] - 10.).abs() < 0.001);
        m.observe([100., 0., 0.], None, 1300, 0.05, true);
        m.observe(
            [101., 0., 0.],
            Some(Sample {
                time_ms: 1350,
                ..sample()
            }),
            1350,
            0.05,
            false,
        );
        assert_eq!(m.seconds, 0.);
    }
}
