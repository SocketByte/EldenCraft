//! Settling after the game moves the player by itself.
//!
//! After a loading screen, a death and respawn at a Site of Grace, or a warp,
//! Elden Ring can first place the character at a temporary spot and move it
//! again a few frames later. Anchoring the shared world or the camera there
//! sends Minecraft's player to the wrong place. The bridge therefore resumes only
//! once the player has stayed still for a short run of frames (or a bounded wait
//! passed). Menus and focus changes never move the player and do not unsettle it.

/// Gate failures that mean the game may relocate the player.
pub fn relocating(reason: &str) -> bool {
    matches!(
        reason,
        "world transition requested"
            | "waiting for local player"
            | "local player activity/update tasks are not ready"
            | "local player death flag is active"
            | "player has no health"
            | "current block has not initialized"
            | "player entry identity does not match local player"
    )
}

const STILL_METRES: f32 = 0.05;
const STILL_FRAMES: u32 = 8;
const MIN_MS: u64 = 400;
const MAX_MS: u64 = 3000;

#[derive(Debug, Default)]
pub struct Settle {
    unsettled: bool,
    since: u64,
    last: Option<[f32; 3]>,
    still: u32,
}
impl Settle {
    /// The game may move the player: require settling before the next resume.
    pub fn unsettle(&mut self) {
        self.unsettled = true;
        self.since = 0;
        self.last = None;
        self.still = 0;
    }
    /// Feed each ready snapshot. True when the bridge may run this frame.
    pub fn observe(&mut self, now: u64, feet: [f32; 3]) -> bool {
        if !self.unsettled {
            return true;
        }
        if self.since == 0 {
            self.since = now.max(1);
        }
        let moved = self.last.is_none_or(|last| {
            (0..3)
                .map(|i| (feet[i] - last[i]).powi(2))
                .sum::<f32>()
                .sqrt()
                > STILL_METRES
        });
        self.still = if moved { 0 } else { self.still + 1 };
        self.last = Some(feet);
        let waited = now.saturating_sub(self.since);
        if (self.still >= STILL_FRAMES && waited >= MIN_MS) || waited >= MAX_MS {
            self.unsettled = false;
            return true;
        }
        false
    }
    #[cfg(test)]
    pub fn settling(&self) -> bool {
        self.unsettled
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn waits_for_the_post_load_relocation_to_finish() {
        let mut s = Settle::default();
        assert!(
            s.observe(0, [0.0; 3]),
            "an untouched session runs immediately"
        );
        s.unsettle();
        assert!(
            !s.observe(1000, [5.0, 0.0, 5.0]),
            "temporary spot after the load"
        );
        assert!(
            !s.observe(1016, [90.0, 3.0, 40.0]),
            "the game moves the character again"
        );
        for frame in 1..STILL_FRAMES {
            assert!(!s.observe(1016 + 16 * u64::from(frame), [90.0, 3.0, 40.0]));
        }
        assert!(
            !s.observe(1300, [90.0, 3.0, 40.0]),
            "still, but not yet the minimum wait"
        );
        assert!(s.observe(1400, [90.0, 3.0, 40.0]));
        assert!(!s.settling());
    }
    #[test]
    fn a_player_walking_with_native_controls_settles_after_the_bounded_wait() {
        let mut s = Settle::default();
        s.unsettle();
        for frame in 0..100u64 {
            let now = 1000 + frame * 30;
            let done = s.observe(now, [frame as f32 * 0.2, 0.0, 0.0]);
            assert_eq!(done, now - 1000 >= MAX_MS);
            if done {
                break;
            }
        }
    }
    #[test]
    fn only_relocating_gates_unsettle() {
        assert!(relocating("current block has not initialized"));
        assert!(relocating("local player death flag is active"));
        assert!(!relocating("blocking game menu is open"));
        assert!(!relocating("game is not foreground"));
    }
}
