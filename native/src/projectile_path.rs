//! Bounded validation of the genuine server projectile's launch-to-impact path.
//! The caller still owns peer/epoch/shooter provenance, receipt replay and target
//! admission. This module never substitutes a straight shooter-to-target ray for
//! the trajectory and never applies damage, movement or native pointer writes.

pub type Point = [f64; 3];
pub const MAX_POINTS: usize = 128;
pub const MAX_FLIGHT_MS: u64 = 6000;
pub const MAX_SEGMENT_METRES: f64 = 8.0;
pub const MAX_ARC_METRES: f64 = 256.0;
pub const MAX_FROM_LAUNCH_METRES: f64 = 64.0;
pub const MAX_RAYS_PER_UPDATE: usize = 192;
const IMPACT_TOLERANCE: f64 = 0.001;
const HIT_END_TOLERANCE: f64 = 0.05;
const HIT_OFF_RAY_TOLERANCE: f64 = 0.02;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Segment {
    pub origin: Point,
    pub end: Point,
}

#[derive(Clone, Debug)]
pub struct Path {
    points: Vec<Point>,
    segments: Vec<Segment>,
    pub launch: Point,
    pub impact: Point,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CheckError {
    Deferred,
    Rejected(&'static str),
}

/// One instance per complete physics update, shared by every projectile and
/// pearl receipt. Other native cover queries can reserve from this same budget.
pub struct RayBudget {
    remaining: usize,
}
impl Default for RayBudget {
    fn default() -> Self {
        Self::new()
    }
}
impl RayBudget {
    pub fn new() -> Self {
        Self {
            remaining: MAX_RAYS_PER_UPDATE,
        }
    }
    pub fn remaining(&self) -> usize {
        self.remaining
    }
    pub fn can_fit(&self, count: usize) -> bool {
        count <= self.remaining
    }
    pub fn reserve(&mut self, count: usize) -> bool {
        if !self.can_fit(count) {
            return false;
        }
        self.remaining -= count;
        true
    }
}

fn finite(p: Point) -> bool {
    p.iter().all(|v| v.is_finite() && v.abs() < 1_000_000.0)
}
fn distance(a: Point, b: Point) -> f64 {
    (0..3).map(|i| (a[i] - b[i]).powi(2)).sum::<f64>().sqrt()
}

impl Path {
    /// Points are recorded without decimation on the actual server thread. The
    /// final point is the actual per-hit location, not projectile.position()
    /// (which can still identify the first target of a piercing arrow).
    pub fn prepare(
        points: &[Point],
        impact: Point,
        launch_ms: u64,
        impact_ms: u64,
        now: u64,
    ) -> Result<Self, &'static str> {
        if !(2..=MAX_POINTS).contains(&points.len())
            || !finite(impact)
            || !points.iter().copied().all(finite)
        {
            return Err("projectile trajectory points invalid");
        }
        if launch_ms == 0
            || launch_ms > impact_ms
            || impact_ms > now
            || impact_ms - launch_ms > MAX_FLIGHT_MS
        {
            return Err("projectile flight timestamp invalid");
        }
        // Receipt freshness itself is enforced by the owning transport/ledger.
        if distance(*points.last().unwrap(), impact) > IMPACT_TOLERANCE {
            return Err("projectile impact differs from trajectory endpoint");
        }
        let launch = points[0];
        let mut total = 0.0;
        let mut segments = Vec::with_capacity(points.len() - 1);
        for pair in points.windows(2) {
            if distance(launch, pair[1]) > MAX_FROM_LAUNCH_METRES {
                return Err("projectile trajectory outside launch radius");
            }
            let length = distance(pair[0], pair[1]);
            if length > MAX_SEGMENT_METRES {
                return Err("projectile trajectory step exceeded");
            }
            total += length;
            if total > MAX_ARC_METRES {
                return Err("projectile trajectory arc exceeded");
            }
            // Repeated positions are legitimate while the genuine projectile
            // is stationary; only exact repeats can omit a cover query.
            if length > 0.0 {
                segments.push(Segment {
                    origin: pair[0],
                    end: pair[1],
                });
            }
        }
        Ok(Self {
            points: points.to_vec(),
            segments,
            launch,
            impact,
        })
    }

    pub fn ray_count(&self) -> usize {
        self.segments.len()
    }

    /// Remove only an exact prefix already checked by the same-context live
    /// flight ledger. This avoids making later doors invalidate past flight.
    pub fn after_verified_prefix(mut self, prefix: &[Point]) -> Result<Self, &'static str> {
        if prefix.is_empty() || !self.points.starts_with(prefix) {
            return Err("projectile verified prefix differs");
        }
        self.segments = self
            .points
            .windows(2)
            .skip(prefix.len() - 1)
            .filter(|p| p[0] != p[1])
            .map(|p| Segment {
                origin: p[0],
                end: p[1],
            })
            .collect();
        Ok(self)
    }

    /// Before consuming a receipt, the caller must check can_fit(ray_count())
    /// and defer the receipt unchanged if false. verify() reserves the whole
    /// path before its first cast; Deferred performs no query or partial work.
    /// A miss is clear. Only the final segment may contact its endpoint; an
    /// intermediate hit always blocks, even at a sampled point, so a thin wall
    /// cannot hide between two ticks. Final contact must lie on the segment and
    /// no earlier than its endpoint tolerance. Cast against the source-verified
    /// native query filter. Actor exclusion is a caller-side query property;
    /// the SDK owner argument alone does not establish generic self exclusion.
    pub fn verify<F>(&self, budget: &mut RayBudget, mut cast: F) -> Result<(), CheckError>
    where
        F: FnMut(Segment) -> Result<Option<Point>, &'static str>,
    {
        if !budget.reserve(self.ray_count()) {
            return Err(CheckError::Deferred);
        }
        for (index, segment) in self.segments.iter().enumerate() {
            let hit = cast(*segment).map_err(CheckError::Rejected)?;
            if (hit.is_some() && index + 1 != self.segments.len()) || !segment_clear(*segment, hit)
            {
                return Err(CheckError::Rejected(
                    "projectile trajectory blocked by native cover",
                ));
            }
        }
        Ok(())
    }
}

pub fn segment_clear(segment: Segment, hit: Option<Point>) -> bool {
    if !finite(segment.origin) || !finite(segment.end) {
        return false;
    }
    let length = distance(segment.origin, segment.end);
    if length > MAX_SEGMENT_METRES {
        return false;
    }
    let Some(hit) = hit else {
        return true;
    };
    if !finite(hit) || length == 0.0 {
        return false;
    }
    let direction: Point = std::array::from_fn(|i| (segment.end[i] - segment.origin[i]) / length);
    let delta: Point = std::array::from_fn(|i| hit[i] - segment.origin[i]);
    let along = (0..3).map(|i| delta[i] * direction[i]).sum::<f64>();
    let off_ray = (0..3)
        .map(|i| (delta[i] - along * direction[i]).powi(2))
        .sum::<f64>();
    (0.0..=length + HIT_END_TOLERANCE).contains(&along)
        && off_ray <= HIT_OFF_RAY_TOLERANCE * HIT_OFF_RAY_TOLERANCE
        && along + HIT_END_TOLERANCE >= length
}

#[cfg(test)]
mod tests {
    use super::*;
    fn prepare(points: &[Point]) -> Path {
        Path::prepare(points, *points.last().unwrap(), 1000, 2000, 2000).unwrap()
    }

    #[test]
    fn curved_path_casts_every_real_segment_and_checks_earlier_walls() {
        let points = [[0., 0., 0.], [3., 3., 0.], [6., 0., 0.]];
        let path = prepare(&points);
        let mut casts = Vec::new();
        assert_eq!(
            path.verify(&mut RayBudget::new(), |s| {
                casts.push(s);
                Ok(None)
            }),
            Ok(())
        );
        assert_eq!(
            casts,
            vec![
                Segment {
                    origin: points[0],
                    end: points[1]
                },
                Segment {
                    origin: points[1],
                    end: points[2]
                }
            ]
        );
        assert_eq!(
            path.verify(&mut RayBudget::new(), |_| Ok(Some([1., 1., 0.]))),
            Err(CheckError::Rejected(
                "projectile trajectory blocked by native cover"
            ))
        );
    }
    #[test]
    fn impact_identity_and_all_flight_limits_are_checked() {
        let points = [[0., 0., 0.], [1., 0., 0.]];
        assert!(Path::prepare(&points, [2., 0., 0.], 1000, 2000, 2000).is_err());
        for (launch, impact, now) in [
            (0, 2000, 2000),
            (2001, 2000, 2000),
            (1000, 2001, 2000),
            (1000, 7001, 7001),
        ] {
            assert!(Path::prepare(&points, points[1], launch, impact, now).is_err());
        }
        assert!(Path::prepare(&points, points[1], 1000, 7000, 7000).is_ok());
        assert!(Path::prepare(&points[..1], points[0], 1000, 2000, 2000).is_err());
        assert!(Path::prepare(&vec![[0.; 3]; 129], [0.; 3], 1000, 2000, 2000).is_err());
        assert!(
            Path::prepare(
                &[[0.; 3], [8.001, 0., 0.]],
                [8.001, 0., 0.],
                1000,
                2000,
                2000
            )
            .is_err()
        );
        let far: Vec<_> = (0..=65).map(|x| [x as f64, 0., 0.]).collect();
        assert!(Path::prepare(&far, *far.last().unwrap(), 1000, 2000, 2000).is_err());
        let long: Vec<_> = (0..=33)
            .map(|i| [if i % 2 == 0 { 0. } else { 8. }, 0., 0.])
            .collect();
        assert!(Path::prepare(&long, *long.last().unwrap(), 1000, 2000, 2000).is_err());
        assert!(Path::prepare(&[[0.; 3], [f64::NAN, 0., 0.]], [0.; 3], 1000, 2000, 2000).is_err());
    }
    #[test]
    fn whole_path_defers_without_partial_queries_or_spending_remaining_budget() {
        let path = prepare(&[[0.; 3], [1., 0., 0.], [2., 0., 0.]]);
        let mut budget = RayBudget::new();
        assert!(budget.reserve(MAX_RAYS_PER_UPDATE - 1));
        let mut calls = 0;
        assert_eq!(
            path.verify(&mut budget, |_| {
                calls += 1;
                Ok(None)
            }),
            Err(CheckError::Deferred)
        );
        assert_eq!(calls, 0);
        assert_eq!(budget.remaining(), 1);
        let mut next_update = RayBudget::new();
        assert_eq!(
            path.verify(&mut next_update, |_| {
                calls += 1;
                Ok(None)
            }),
            Ok(())
        );
        assert_eq!(calls, 2);
        assert_eq!(next_update.remaining(), MAX_RAYS_PER_UPDATE - 2);
    }
    #[test]
    fn repeated_points_do_not_invent_a_query_and_tiny_steps_are_not_dropped() {
        let path = prepare(&[[0.; 3], [0.; 3], [0.00001, 0., 0.]]);
        assert_eq!(path.ray_count(), 1);
        let zero = prepare(&[[0.; 3], [0.; 3]]);
        assert_eq!(zero.ray_count(), 0);
        assert_eq!(
            zero.verify(&mut RayBudget::new(), |_| panic!("no traveled segment")),
            Ok(())
        );
    }
    #[test]
    fn native_ray_results_cannot_hide_invalid_or_off_segment_hits() {
        let s = Segment {
            origin: [0.; 3],
            end: [0., 0., 4.],
        };
        assert!(segment_clear(s, None));
        assert!(segment_clear(s, Some([0., 0., 4.])));
        assert!(!segment_clear(s, Some([0., 0., 2.])));
        assert!(!segment_clear(s, Some([0.5, 0., 4.])));
        assert!(!segment_clear(s, Some([0., 0., 5.])));
        assert!(!segment_clear(s, Some([0., 0., -0.01])));
        assert!(!segment_clear(s, Some([f64::NAN, 0., 4.])));
        assert_eq!(
            prepare(&[[0.; 3], [0., 0., 4.]])
                .verify(&mut RayBudget::new(), |_| Err("native query unavailable")),
            Err(CheckError::Rejected("native query unavailable"))
        );
    }
    #[test]
    fn only_final_contact_may_use_endpoint_tolerance() {
        let path = prepare(&[[0.; 3], [1., 0., 0.], [2., 0., 0.]]);
        assert_eq!(
            path.verify(&mut RayBudget::new(), |s| Ok(Some(s.end))),
            Err(CheckError::Rejected(
                "projectile trajectory blocked by native cover"
            ))
        );
        assert_eq!(
            path.verify(&mut RayBudget::new(), |s| if s.end[0] == 2. {
                Ok(Some(s.end))
            } else {
                Ok(None)
            }),
            Ok(())
        );
    }
    #[test]
    fn largest_bounded_path_fits_one_update_and_cannot_overdraw_it() {
        let points: Vec<_> = (0..MAX_POINTS).map(|i| [i as f64 / 4., 0., 0.]).collect();
        let path = prepare(&points);
        let mut budget = RayBudget::new();
        let mut calls = 0;
        assert_eq!(
            path.verify(&mut budget, |_| {
                calls += 1;
                Ok(None)
            }),
            Ok(())
        );
        let remaining = MAX_RAYS_PER_UPDATE - (MAX_POINTS - 1);
        assert_eq!(calls, MAX_POINTS - 1);
        assert_eq!(budget.remaining(), remaining);
        assert!(!budget.reserve(remaining + 1));
        assert_eq!(budget.remaining(), remaining);
    }
}
