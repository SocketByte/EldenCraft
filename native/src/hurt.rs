//! Vanilla getting-hit feedback for the Elden Ring camera and movement.
//!
//! - Hurt tilt (GameRenderer.bobHurt): for hurtDuration = 10 ticks the view rolls by
//!   sin(f^4 * pi) * 14 degrees (x damageTiltStrength), about an axis turned toward
//!   the attacker (hurtDir), where f runs from 1 to 0.
//! - Knockback (LivingEntity.knockback, strength 0.4): horizontal velocity becomes
//!   half of itself plus 0.4 blocks/tick away from the attacker and, on the ground,
//!   vertical velocity min(0.4, vy/2 + 0.4).
//!
//! Elden Ring HP stays the health pool; this only detects its drops.

/// Seconds of vanilla hurtDuration (10 ticks).
pub const HURT_SECONDS: f32 = 0.5;
pub const TILT_DEGREES: f32 = 14.0;
/// One ongoing HP drain is one feedback episode, ending after a full hurt
/// animation's worth of quiet. Damage, healing and armor processing are separate.
const QUIET_MILLIS: u64 = 500;
const MAX_SAMPLE_GAP_MILLIS: u64 = 250;
/// Knockback applies only when the recorded attacker is this close (melee and close spells).
pub const KNOCKBACK_RANGE_M: f32 = 8.0;

/// Roll in degrees for a hurt started `elapsed` seconds ago (0 once finished).
pub fn tilt_degrees(elapsed: f32, strength: f32) -> f32 {
    if !elapsed.is_finite() || !(0.0..HURT_SECONDS).contains(&elapsed) {
        return 0.0;
    }
    let f = 1.0 - elapsed / HURT_SECONDS;
    (f * f * f * f * std::f32::consts::PI).sin() * TILT_DEGREES * strength.clamp(0.0, 1.0)
}

/// Turns fresh HP samples into feedback episodes instead of restarting the
/// camera and knockback on every frame of a continuous damage-over-time effect.
#[derive(Debug, Default)]
pub struct Detector {
    previous: Option<(usize, i32, u64)>,
    last_drop: Option<u64>,
}
impl Detector {
    /// `identity`: the live player instance. Returns true when a fresh HP drop
    /// starts a new feedback episode, after at least 500ms without another drop.
    pub fn observe(&mut self, identity: usize, hp: i32, now: u64) -> bool {
        if identity == 0 || hp < 0 {
            self.reset();
            return false;
        }
        let continuous = self.previous.is_some_and(|(id, _, at)| {
            id == identity
                && now
                    .checked_sub(at)
                    .is_some_and(|gap| gap < MAX_SAMPLE_GAP_MILLIS)
        });
        if !continuous {
            self.last_drop = None;
        }
        let dropped = continuous && self.previous.is_some_and(|(_, before, _)| hp < before);
        self.previous = Some((identity, hp, now));
        if !dropped {
            return false;
        }
        let starts_episode = self.last_drop.is_none_or(|at| now - at >= QUIET_MILLIS);
        self.last_drop = Some(now);
        starts_episode
    }
    pub fn reset(&mut self) {
        self.previous = None;
        self.last_drop = None;
    }
}

/// Unit XZ vector from the attacker to the player, when the game recorded a
/// living attacker within knockback range. None for falls, poison, our own
/// Minecraft hazards or distant attackers.
#[cfg(windows)]
pub unsafe fn away_from_attacker() -> Option<[f32; 2]> {
    use eldenring::cs::{FieldInsType, PlayerIns, WorldChrMan};
    use fromsoftware_shared::FromStatic;
    let player = unsafe { PlayerIns::local_player() }.ok()?;
    let handle = player.chr_ins.last_hit_by;
    if handle.is_empty()
        || handle == player.chr_ins.field_ins_handle
        || handle.selector.field_ins_type() != Some(FieldInsType::Chr)
    {
        return None;
    }
    let manager = unsafe { WorldChrMan::instance() }.ok()?;
    let attacker = manager.chr_ins_by_handle(&handle)?;
    if attacker.field_ins_handle != handle || attacker.modules.data.hp <= 0 {
        return None;
    }
    let a = &player.chr_ins.modules.physics.position;
    let b = &attacker.modules.physics.position;
    away(a.0 - b.0, a.2 - b.2)
}

fn away(dx: f32, dz: f32) -> Option<[f32; 2]> {
    let length = dx.hypot(dz);
    (length.is_finite() && length > 0.05 && length <= KNOCKBACK_RANGE_M)
        .then(|| [dx / length, dz / length])
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tilt_follows_vanilla_bob_hurt() {
        assert!(tilt_degrees(0.0, 1.0).abs() < 1e-4, "starts level");
        let peak = (0..50)
            .map(|i| tilt_degrees(i as f32 * 0.01, 1.0))
            .fold(0.0f32, f32::max);
        assert!((peak - 14.0).abs() < 0.2, "peak {peak}");
        assert_eq!(tilt_degrees(0.5, 1.0), 0.0);
        assert_eq!(
            tilt_degrees(0.1, 0.0),
            0.0,
            "damage tilt strength 0 disables it"
        );
        assert!(tilt_degrees(0.1, 0.5) < tilt_degrees(0.1, 1.0));
    }
    #[test]
    fn hp_drops_on_the_same_character_start_a_hurt() {
        let mut d = Detector::default();
        assert!(!d.observe(1, 1000, 1000));
        assert!(d.observe(1, 900, 1050));
        assert!(!d.observe(1, 950, 1100), "healing");
        assert!(!d.observe(2, 100, 1150), "new character after a load");
        d.reset();
        assert!(!d.observe(2, 50, 1200));
    }
    #[test]
    fn a_continuous_burn_starts_one_tilt_and_knockback_instead_of_restarting_each_frame() {
        let mut d = Detector::default();
        assert!(!d.observe(1, 10000, 1000));
        assert!(d.observe(1, 9500, 1016));
        for frame in 1..=300 {
            assert!(
                !d.observe(1, 9500 - frame * 2, 1016 + frame as u64 * 16),
                "damage-over-time frame {frame} must not restart feedback"
            );
        }
        assert_eq!(tilt_degrees(300. * 0.016, 1.), 0.);
        // Healing does not turn the next tick of the same burn into a new hit.
        assert!(!d.observe(1, 9500, 5866));
        assert!(!d.observe(1, 9498, 5916));
        for now in (5966..=6416).step_by(50) {
            assert!(!d.observe(1, 9498, now));
        }
        assert!(
            d.observe(1, 9000, 6466),
            "a later separate hit has normal feedback"
        );
    }
    #[test]
    fn periodic_drain_inside_the_quiet_window_cannot_keep_pulsing_after_the_animation_ends() {
        let mut d = Detector::default();
        assert!(!d.observe(1, 1000, 1000));
        assert!(d.observe(1, 998, 1100));
        for tick in 2..=30 {
            assert!(!d.observe(1, 1000 - tick * 2, 1000 + tick as u64 * 100));
        }
    }
    #[test]
    fn a_gap_invalid_clock_or_invalid_character_rebases_without_replaying_damage() {
        let mut d = Detector::default();
        assert!(!d.observe(1, 1000, 1000));
        assert!(d.observe(1, 900, 1050));
        assert!(!d.observe(1, 500, 1300), "250ms gap is a baseline");
        assert!(d.observe(1, 490, 1350));
        assert!(!d.observe(1, 480, 1349), "clock rollback");
        assert!(!d.observe(0, 470, 1400));
        assert!(!d.observe(1, 460, 1450));
        assert!(!d.observe(1, -1, 1500));
        assert!(!d.observe(1, 450, 1550));
        assert!(d.observe(1, 440, 1600));
        assert!(!d.observe(2, 50, 1650));
        assert!(d.observe(2, 40, 1700));
    }
    #[test]
    fn knockback_needs_a_nearby_attacker() {
        assert_eq!(away(3.0, 4.0), Some([0.6, 0.8]));
        assert_eq!(away(0.0, 0.0), None);
        assert_eq!(away(10.0, 0.0), None);
    }
}
