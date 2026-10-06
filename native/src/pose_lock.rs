//! Pose-locked composition (opt-in: `ELDENCRAFT_POSE_LOCK=1`).
//!
//! Normally Elden Ring renders the newest camera and the compositor reprojects
//! Minecraft's slightly older capture into it. With the lock, the camera the
//! player asks for is published to Minecraft first (ECHS), and Elden Ring renders
//! with the exact pose of Minecraft's newest finished frame (MCPT `host_frame`).
//! Both images then share one camera, so reprojection has nothing to correct.
//! The cost is that Elden Ring's camera trails input by about one Minecraft frame.
//!
//! Positions are stored in block coordinates (Havok position plus the player's
//! block offset), so a Havok re-centre between publication and use cannot jump.
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose {
    pub map: u32,
    /// Camera in block coordinates, as ECHS publishes it.
    pub camera: [f64; 3],
    /// Final written right/up/forward, front-view reversal included.
    pub basis: [[f32; 3]; 3],
    /// Vertical field of view in radians.
    pub fov: f32,
}

/// A locked pose older than this is not applied; the live camera is used instead.
pub const MAX_AGE_MS: u64 = 200;
/// The desired camera must come from a recent DrawParamUpdate to be published.
pub const DESIRED_AGE_MS: u64 = 100;
const HISTORY: usize = 64;

#[derive(Default)]
pub struct Lock {
    desired: Option<(Pose, u64)>,
    published: VecDeque<(u64, Pose, u64)>,
    pub locked: u64,
    pub fallback: u64,
}

impl Lock {
    pub fn note_desired(&mut self, pose: Pose, now: u64) {
        if valid(&pose) {
            self.desired = Some((pose, now));
        }
    }
    /// The camera ECHS should publish this frame, if a fresh one exists for this map.
    pub fn desired(&self, map: u32, now: u64) -> Option<Pose> {
        self.desired
            .filter(|(pose, at)| pose.map == map && now >= *at && now - at <= DESIRED_AGE_MS)
            .map(|(pose, _)| pose)
    }
    /// ECHS published `pose` as host frame `frame`. A non-advancing frame means a new
    /// publisher; its old history can never match again.
    pub fn note_published(&mut self, frame: u64, pose: Pose, now: u64) {
        if self
            .published
            .back()
            .is_some_and(|(last, _, _)| *last >= frame)
        {
            self.published.clear();
        }
        if self.published.len() == HISTORY {
            self.published.pop_front();
        }
        self.published.push_back((frame, pose, now));
    }
    /// The exact pose Minecraft rendered for its newest fresh frame, or None to keep
    /// the live camera (no frame yet, other map, too old, or unknown frame).
    pub fn select(&mut self, map: u32, guest_frame: Option<u64>, now: u64) -> Option<Pose> {
        let chosen = guest_frame
            .and_then(|frame| self.published.iter().rev().find(|(f, _, _)| *f == frame))
            .filter(|(_, pose, at)| pose.map == map && now >= *at && now - at <= MAX_AGE_MS)
            .map(|(_, pose, _)| *pose);
        if chosen.is_some() {
            self.locked += 1;
        } else {
            self.fallback += 1;
        }
        chosen
    }
    pub fn reset(&mut self) {
        self.desired = None;
        self.published.clear();
    }
}

fn valid(pose: &Pose) -> bool {
    pose.camera.iter().all(|v| v.is_finite())
        && pose.fov.is_finite()
        && (0.05..3.13).contains(&pose.fov)
        && pose.basis.iter().flatten().all(|v| v.is_finite())
        && pose
            .basis
            .iter()
            .all(|axis| (axis.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 0.01)
}

pub static LOCK: OnceLock<Mutex<Lock>> = OnceLock::new();
pub fn lock() -> &'static Mutex<Lock> {
    LOCK.get_or_init(|| Mutex::new(Lock::default()))
}

pub fn enabled() -> bool {
    static ENABLED: OnceLock<bool> = OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var("ELDENCRAFT_POSE_LOCK").is_ok_and(|value| value == "1"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pose(map: u32, x: f64) -> Pose {
        Pose {
            map,
            camera: [x, 2.0, 3.0],
            basis: [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
            fov: 1.2,
        }
    }
    #[test]
    fn desired_camera_is_fresh_and_map_scoped() {
        let mut lock = Lock::default();
        lock.note_desired(pose(7, 1.0), 1000);
        assert_eq!(lock.desired(7, 1100), Some(pose(7, 1.0)));
        assert_eq!(
            lock.desired(7, 1101),
            None,
            "stale desired camera is not published"
        );
        assert_eq!(lock.desired(8, 1000), None, "another map");
        let mut bad = pose(7, 1.0);
        bad.basis[0] = [2.0, 0.0, 0.0];
        lock.note_desired(bad, 1050);
        assert_eq!(
            lock.desired(7, 1050),
            Some(pose(7, 1.0)),
            "invalid basis never replaces a valid desire"
        );
    }
    #[test]
    fn selects_exactly_the_frame_minecraft_rendered() {
        let mut lock = Lock::default();
        lock.note_published(10, pose(7, 10.0), 1000);
        lock.note_published(11, pose(7, 11.0), 1016);
        lock.note_published(12, pose(7, 12.0), 1033);
        assert_eq!(lock.select(7, Some(11), 1040), Some(pose(7, 11.0)));
        assert_eq!(lock.select(7, Some(13), 1040), None, "unpublished frame");
        assert_eq!(lock.select(7, None, 1040), None, "no fresh Minecraft frame");
        assert_eq!(
            lock.select(8, Some(12), 1040),
            None,
            "map changed since publication"
        );
        assert_eq!(lock.select(7, Some(10), 1201), None, "too old to apply");
        assert_eq!((lock.locked, lock.fallback), (1, 4));
    }
    #[test]
    fn restarted_publisher_discards_history_and_ring_is_bounded() {
        let mut lock = Lock::default();
        for frame in 1..=100 {
            lock.note_published(frame, pose(1, frame as f64), 1000);
        }
        assert_eq!(
            lock.select(1, Some(30), 1000),
            None,
            "evicted beyond history"
        );
        assert_eq!(lock.select(1, Some(100), 1000), Some(pose(1, 100.0)));
        lock.note_published(5, pose(1, -5.0), 1001);
        assert_eq!(
            lock.select(1, Some(100), 1001),
            None,
            "old publisher frames cannot match"
        );
        assert_eq!(lock.select(1, Some(5), 1001), Some(pose(1, -5.0)));
    }
}
