//! Read-only native cover admission for genuine, append-only server flights.
//! SDK calls and owner admission stay with the game-task caller. All positions
//! here use the shared world's canonical coordinates, never native pointers.
use crate::projectile_path::{MAX_FLIGHT_MS, Path, Point, RayBudget, Segment};
use serde::{Deserialize, Serialize};

pub const MAX_FLIGHTS: usize = 32;
const MAX_TRACKED: usize = 128;
const FRESH_MS: u64 = 500;
const RECEIPT_GRACE_MS: u64 = 1000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Context {
    pub pid: u32,
    pub session: u64,
    pub epoch: u64,
    pub map: u32,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Flight {
    pub projectile: String,
    pub projectile_kind: String,
    pub source: String,
    pub launch_frame: u64,
    pub launch_time_ms: u64,
    pub trajectory: Vec<Point>,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Impact {
    pub projectile: String,
    pub point: Point,
    pub normal: Point,
    pub segment: u32,
    pub time_ms: u64,
}
#[derive(Clone, Copy, Debug)]
pub struct Hit {
    pub point: Point,
    pub normal: Point,
}
#[derive(Default, Debug)]
pub struct Report {
    pub impacts: Vec<Impact>,
    pub rejected: Vec<(String, &'static str)>,
    pub deferred: usize,
}
struct Tracked {
    flight: Flight,
    checked: usize,
    contact: Option<Impact>,
    error: Option<&'static str>,
    retired: bool,
    present: bool,
    first_seen: u64,
    last_seen: u64,
}
#[derive(Default)]
pub struct Driver {
    context: Option<Context>,
    flights: Vec<Tracked>,
    cursor: usize,
}

fn identity(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_:-./".contains(&c))
}
fn same_launch(a: &Flight, b: &Flight) -> bool {
    a.projectile == b.projectile
        && a.projectile_kind == b.projectile_kind
        && a.source == b.source
        && a.launch_frame == b.launch_frame
        && a.launch_time_ms == b.launch_time_ms
}
fn validate(f: &Flight, stamp: u64, now: u64) -> Result<(), &'static str> {
    if !identity(&f.projectile)
        || !identity(&f.projectile_kind)
        || !identity(&f.source)
        || f.launch_frame == 0
    {
        return Err("projectile identity invalid");
    }
    if ![
        "minecraft:arrow",
        "minecraft:spectral_arrow",
        "minecraft:ender_pearl",
    ]
    .contains(&f.projectile_kind.as_str())
    {
        return Err("projectile kind is not supported");
    }
    Path::prepare(
        &f.trajectory,
        *f.trajectory.last().ok_or("projectile trajectory absent")?,
        f.launch_time_ms,
        stamp,
        now,
    )
    .map(|_| ())
}
fn distance(a: Point, b: Point) -> f64 {
    (0..3).map(|i| (a[i] - b[i]).powi(2)).sum::<f64>().sqrt()
}
fn checked_hit(segment: Segment, hit: Hit) -> Result<Hit, &'static str> {
    if !hit
        .point
        .iter()
        .chain(hit.normal.iter())
        .all(|v| v.is_finite())
    {
        return Err("projectile native contact nonfinite");
    }
    let length = distance(segment.origin, segment.end);
    if length == 0. {
        return Err("projectile contact on stationary segment");
    }
    let direction: Point = std::array::from_fn(|i| (segment.end[i] - segment.origin[i]) / length);
    let delta: Point = std::array::from_fn(|i| hit.point[i] - segment.origin[i]);
    let along = (0..3).map(|i| delta[i] * direction[i]).sum::<f64>();
    let off = (0..3)
        .map(|i| (delta[i] - along * direction[i]).powi(2))
        .sum::<f64>();
    let normal_length = hit.normal.iter().map(|v| v * v).sum::<f64>().sqrt();
    if !(-0.02..=length + 0.02).contains(&along)
        || off > 0.02 * 0.02
        || !(0.5..=1.5).contains(&normal_length)
    {
        return Err("projectile native contact outside segment");
    }
    // Preserve the actual native normal's direction; only normalize its length.
    Ok(Hit {
        point: hit.point,
        normal: hit.normal.map(|v| v / normal_length),
    })
}

impl Driver {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn reset(&mut self) {
        self.context = None;
        self.flights.clear();
        self.cursor = 0;
    }

    /// One call per fresh coherent guest snapshot. Removed IDs are retired, not
    /// forgotten: neither republishing a UUID nor mutating its prefix can cause
    /// a second contact. The caller shares this budget with final receipts and
    /// any further native cover/teleport queries in this physics update.
    #[allow(clippy::too_many_arguments)] // Distinct per-update inputs, no shared lifetime.
    pub fn update<A, C>(
        &mut self,
        context: Context,
        flights: &[Flight],
        publication_ms: u64,
        now: u64,
        budget: &mut RayBudget,
        mut authorize: A,
        mut cast: C,
    ) -> Result<Report, &'static str>
    where
        A: FnMut(&Flight) -> bool,
        C: FnMut(&Flight, Segment) -> Result<Option<Hit>, &'static str>,
    {
        if context.pid == 0
            || context.session == 0
            || context.epoch == 0
            || publication_ms == 0
            || publication_ms > now
            || now - publication_ms > FRESH_MS
            || flights.len() > MAX_FLIGHTS
        {
            return Err("projectile publication invalid or stale");
        }
        let mut ids = std::collections::HashSet::new();
        if flights.iter().any(|f| !ids.insert(f.projectile.as_str())) {
            return Err("duplicate projectile UUID");
        }
        if self.context != Some(context) {
            self.reset();
            self.context = Some(context);
        }
        self.flights
            .retain(|s| now.saturating_sub(s.first_seen) <= MAX_FLIGHT_MS + RECEIPT_GRACE_MS);
        for state in &mut self.flights {
            state.present = false;
        }
        let mut report = Report::default();
        for flight in flights {
            let index = self
                .flights
                .iter()
                .position(|s| s.flight.projectile == flight.projectile);
            let index = if let Some(i) = index {
                i
            } else {
                if self.flights.len() == MAX_TRACKED {
                    report.rejected.push((
                        flight.projectile.clone(),
                        "projectile retirement capacity reached",
                    ));
                    continue;
                }
                self.flights.push(Tracked {
                    flight: flight.clone(),
                    checked: 0,
                    contact: None,
                    error: None,
                    retired: false,
                    present: false,
                    first_seen: now,
                    last_seen: publication_ms,
                });
                self.flights.len() - 1
            };
            let state = &mut self.flights[index];
            state.present = true;
            let error = if state.retired {
                Some("retired projectile republished")
            } else if state.error.is_some() {
                None
            } else if !same_launch(&state.flight, flight)
                || !flight.trajectory.starts_with(&state.flight.trajectory)
            {
                Some("projectile launch or published prefix changed")
            } else if publication_ms < state.last_seen {
                Some("projectile publication moved backwards")
            } else if !authorize(flight) {
                Some("projectile owner is not authorized")
            } else {
                validate(flight, publication_ms, now).err()
            };
            if let Some(error) = error {
                if state.error.is_none() {
                    report.rejected.push((flight.projectile.clone(), error));
                }
                state.error = Some(error);
                state.contact = None;
                state.retired = true;
            }
            if !state.retired && state.error.is_none() {
                state.flight = flight.clone();
                state.last_seen = publication_ms;
            }
        }
        for state in &mut self.flights {
            if !state.present {
                state.retired = true;
            }
        }
        // Round-robin one segment at a time. A newly arrived long trajectory
        // cannot spend the entire update before another active flight is seen.
        let count = self.flights.len();
        let mut idle = 0;
        while count > 0 && idle < count {
            let index = self.cursor % count;
            self.cursor = (index + 1) % count;
            let state = &mut self.flights[index];
            if !state.present
                || state.retired
                || state.error.is_some()
                || state.contact.is_some()
                || state.checked + 1 >= state.flight.trajectory.len()
            {
                idle += 1;
                continue;
            }
            let segment = Segment {
                origin: state.flight.trajectory[state.checked],
                end: state.flight.trajectory[state.checked + 1],
            };
            if segment.origin == segment.end {
                state.checked += 1;
                idle = 0;
                continue;
            }
            if !budget.reserve(1) {
                break;
            }
            idle = 0;
            match cast(&state.flight, segment)
                .and_then(|hit| hit.map(|h| checked_hit(segment, h)).transpose())
            {
                Ok(None) => state.checked += 1,
                Ok(Some(hit)) => {
                    state.contact = Some(Impact {
                        projectile: state.flight.projectile.clone(),
                        point: hit.point,
                        normal: hit.normal,
                        segment: (state.checked + 1) as u32,
                        time_ms: now,
                    })
                }
                Err(error) => {
                    state.error = Some(error);
                    state.retired = true;
                    report
                        .rejected
                        .push((state.flight.projectile.clone(), error));
                }
            }
        }
        for state in &self.flights {
            if state.present
                && !state.retired
                && state.error.is_none()
                && now - state.last_seen <= FRESH_MS
            {
                if let Some(contact) = &state.contact {
                    report.impacts.push(contact.clone());
                } else if state.checked + 1 < state.flight.trajectory.len() {
                    report.deferred += 1;
                }
            }
        }
        Ok(report)
    }

    /// A final receipt may end partway through the last published tick. Every
    /// other shared point must match exactly. Reuse only already-clear matching
    /// edges; the final partial/unobserved segment still needs its own query.
    /// Retired-but-valid flights remain available for the brief receipt grace.
    pub fn remaining_path(
        &self,
        flight: &Flight,
        impact_ms: u64,
        now: u64,
    ) -> Result<Path, &'static str> {
        validate(flight, impact_ms, now)?;
        let path = Path::prepare(
            &flight.trajectory,
            *flight.trajectory.last().unwrap(),
            flight.launch_time_ms,
            impact_ms,
            now,
        )?;
        let Some(state) = self
            .flights
            .iter()
            .find(|s| s.flight.projectile == flight.projectile)
        else {
            return Ok(path);
        };
        if now.saturating_sub(state.first_seen) > MAX_FLIGHT_MS + RECEIPT_GRACE_MS {
            return Err("projectile validated history expired");
        }
        if let Some(error) = state.error {
            return Err(error);
        }
        if !same_launch(&state.flight, flight) {
            return Err("projectile receipt launch differs");
        }
        let common = flight
            .trajectory
            .iter()
            .zip(&state.flight.trajectory)
            .take_while(|(a, b)| a == b)
            .count();
        if common < flight.trajectory.len().min(state.flight.trajectory.len())
            && common + 1 < flight.trajectory.len()
        {
            return Err("projectile receipt prefix differs");
        }
        if common == 0 {
            return Err("projectile receipt launch position differs");
        }
        if common < flight.trajectory.len().min(state.flight.trajectory.len()) {
            // A temporary per-hit endpoint may shorten the published final
            // tick, but may not invent a different direction for that tick.
            let segment = Segment {
                origin: state.flight.trajectory[common - 1],
                end: state.flight.trajectory[common],
            };
            checked_hit(
                segment,
                Hit {
                    point: *flight.trajectory.last().unwrap(),
                    normal: [1., 0., 0.],
                },
            )
            .map_err(|_| "projectile partial impact differs from published segment")?;
        }
        if let Some(contact) = &state.contact {
            let index = contact.segment as usize;
            if flight.trajectory.len() > index + 1 {
                return Err("projectile receipt passed known native contact");
            }
            if flight.trajectory.len() == index + 1 {
                let start = state.flight.trajectory[index - 1];
                let end = state.flight.trajectory[index];
                let length = distance(start, end);
                let along = |p: Point| {
                    (0..3)
                        .map(|i| (p[i] - start[i]) * (end[i] - start[i]) / length)
                        .sum::<f64>()
                };
                if along(*flight.trajectory.last().unwrap()) > along(contact.point) + 0.05 {
                    return Err("projectile receipt passed known native contact");
                }
            }
        }
        path.after_verified_prefix(&flight.trajectory[..common.min(state.checked + 1)])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn context() -> Context {
        Context {
            pid: 1,
            session: 2,
            epoch: 3,
            map: 4,
        }
    }
    fn flight(id: &str, points: Vec<Point>) -> Flight {
        Flight {
            projectile: id.into(),
            projectile_kind: "minecraft:arrow".into(),
            source: "owner".into(),
            launch_frame: 1,
            launch_time_ms: 1000,
            trajectory: points,
        }
    }
    fn basic(id: &str) -> Flight {
        flight(id, vec![[0.; 3], [1., 0., 0.], [2., 0., 0.]])
    }
    fn update(driver: &mut Driver, flights: &[Flight], now: u64) -> Report {
        driver
            .update(
                context(),
                flights,
                now,
                now,
                &mut RayBudget::new(),
                |_| true,
                |_, _| Ok(None),
            )
            .unwrap()
    }
    #[test]
    fn checked_prefix_not_requeried_and_final_damage_uses_it() {
        let mut driver = Driver::new();
        let mut f = basic("a");
        update(&mut driver, &[f.clone()], 1100);
        let report = driver
            .update(
                context(),
                &[f.clone()],
                1150,
                1150,
                &mut RayBudget::new(),
                |_| true,
                |_, _| panic!("already checked"),
            )
            .unwrap();
        assert_eq!(report.deferred, 0);
        assert_eq!(
            driver.remaining_path(&f, 1150, 1150).unwrap().ray_count(),
            0
        );
        f.trajectory.push([3., 0., 0.]);
        let path = driver.remaining_path(&f, 1200, 1200).unwrap();
        assert_eq!(path.ray_count(), 1);
        assert_eq!(
            path.verify(&mut RayBudget::new(), |s| {
                assert_eq!(s.origin, [2., 0., 0.]);
                Ok(None)
            }),
            Ok(())
        );
    }
    #[test]
    fn contact_is_stable_and_removal_cannot_replay_it() {
        let mut driver = Driver::new();
        let f = basic("a");
        let hit = Hit {
            point: [0.5, 0., 0.],
            normal: [-1., 0., 0.],
        };
        let first = driver
            .update(
                context(),
                std::slice::from_ref(&f),
                1100,
                1100,
                &mut RayBudget::new(),
                |_| true,
                |_, _| Ok(Some(hit)),
            )
            .unwrap();
        assert_eq!(first.impacts[0].segment, 1);
        let again = driver
            .update(
                context(),
                std::slice::from_ref(&f),
                1200,
                1200,
                &mut RayBudget::new(),
                |_| true,
                |_, _| panic!("first contact immutable"),
            )
            .unwrap();
        assert_eq!(first.impacts, again.impacts);
        assert!(driver.remaining_path(&f, 1200, 1200).is_err());
        update(&mut driver, &[], 1250);
        let replay = update(&mut driver, &[f], 1300);
        assert!(replay.impacts.is_empty());
        assert_eq!(replay.rejected.len(), 1);
    }
    #[test]
    fn changed_prefix_owner_and_context_are_not_reused() {
        let mut driver = Driver::new();
        let f = basic("a");
        update(&mut driver, std::slice::from_ref(&f), 1100);
        let mut changed = f.clone();
        changed.trajectory[1][1] = 0.1;
        assert_eq!(update(&mut driver, &[changed], 1200).rejected.len(), 1);
        assert!(driver.remaining_path(&f, 1200, 1200).is_err());
        let new_context = Context {
            session: 99,
            ..context()
        };
        let mut calls = 0;
        driver
            .update(
                new_context,
                &[f],
                1300,
                1300,
                &mut RayBudget::new(),
                |_| true,
                |_, _| {
                    calls += 1;
                    Ok(None)
                },
            )
            .unwrap();
        assert_eq!(calls, 2);
        let denied = driver
            .update(
                new_context,
                &[basic("b")],
                1400,
                1400,
                &mut RayBudget::new(),
                |_| false,
                |_, _| panic!("unauthorized"),
            )
            .unwrap();
        assert_eq!(denied.rejected.len(), 1);
    }
    #[test]
    fn shared_budget_defers_fairly_then_resumes_without_duplicate_rays() {
        let mut driver = Driver::new();
        let flights = [basic("a"), basic("b")];
        let mut budget = RayBudget::new();
        assert!(budget.reserve(crate::projectile_path::MAX_RAYS_PER_UPDATE - 2));
        let mut calls = Vec::new();
        let report = driver
            .update(
                context(),
                &flights,
                1100,
                1100,
                &mut budget,
                |_| true,
                |f, s| {
                    calls.push((f.projectile.clone(), s));
                    Ok(None)
                },
            )
            .unwrap();
        assert_eq!(report.deferred, 2);
        assert_eq!(calls[0].0, "a");
        assert_eq!(calls[1].0, "b");
        driver
            .update(
                context(),
                &flights,
                1150,
                1150,
                &mut RayBudget::new(),
                |_| true,
                |_, s| {
                    assert_eq!(s.origin, [1., 0., 0.]);
                    Ok(None)
                },
            )
            .unwrap();
    }
    #[test]
    fn malformed_contact_stale_batch_and_flight_expiry_fail_closed() {
        let mut driver = Driver::new();
        let f = basic("a");
        assert!(
            driver
                .update(
                    context(),
                    std::slice::from_ref(&f),
                    1000,
                    1501,
                    &mut RayBudget::new(),
                    |_| true,
                    |_, _| panic!()
                )
                .is_err()
        );
        let report = driver
            .update(
                context(),
                std::slice::from_ref(&f),
                1100,
                1100,
                &mut RayBudget::new(),
                |_| true,
                |_, _| {
                    Ok(Some(Hit {
                        point: [0., 1., 0.],
                        normal: [0.; 3],
                    }))
                },
            )
            .unwrap();
        assert_eq!(report.rejected.len(), 1);
        let mut driver = Driver::new();
        assert_eq!(
            update(&mut driver, std::slice::from_ref(&f), 7001)
                .rejected
                .len(),
            1
        );
        assert!(
            driver
                .update(
                    context(),
                    &[f.clone(), f],
                    7100,
                    7100,
                    &mut RayBudget::new(),
                    |_| true,
                    |_, _| panic!()
                )
                .is_err()
        );
    }
    #[test]
    fn piercing_partial_endpoint_preserves_prefix_but_rewrite_is_rejected() {
        let mut driver = Driver::new();
        let f = basic("a");
        update(&mut driver, std::slice::from_ref(&f), 1100);
        let mut receipt = f.clone();
        receipt.trajectory[2] = [1.5, 0., 0.];
        assert_eq!(
            driver
                .remaining_path(&receipt, 1150, 1150)
                .unwrap()
                .ray_count(),
            1
        );
        receipt.trajectory[2] = [1.5, 0.5, 0.];
        assert!(driver.remaining_path(&receipt, 1150, 1150).is_err());
        receipt.trajectory[2] = [1.5, 0., 0.];
        receipt.trajectory[1] = [1., 0.1, 0.];
        assert!(driver.remaining_path(&receipt, 1150, 1150).is_err());
        update(&mut driver, &[], 1200);
        assert_eq!(
            driver.remaining_path(&f, 1250, 1250).unwrap().ray_count(),
            0
        );
    }
    #[test]
    fn retired_capacity_is_bounded_without_evicting_live_replay_evidence() {
        let mut driver = Driver::new();
        for batch in 0..4 {
            let fs: Vec<_> = (0..32).map(|i| basic(&format!("{batch}-{i}"))).collect();
            update(&mut driver, &fs, 1100 + batch * 50);
        }
        let report = update(&mut driver, &[basic("overflow")], 1400);
        assert_eq!(
            report.rejected[0].1,
            "projectile retirement capacity reached"
        );
        assert_eq!(driver.flights.len(), MAX_TRACKED);
    }
}
