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

/// Turns HP samples into hurt events: a drop on the same character starts one.
#[derive(Debug, Default)]
pub struct Detector {
    previous: Option<(usize, i32)>,
}
impl Detector {
    /// `identity`: the live player instance. Returns true when HP dropped since the last sample.
    pub fn observe(&mut self, identity: usize, hp: i32) -> bool {
        let dropped = self
            .previous
            .is_some_and(|(id, before)| id == identity && hp < before && hp >= 0);
        self.previous = Some((identity, hp));
        dropped
    }
    pub fn reset(&mut self) {
        self.previous = None;
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
        assert!(!d.observe(1, 1000));
        assert!(d.observe(1, 900));
        assert!(!d.observe(1, 950), "healing");
        assert!(!d.observe(2, 100), "new character after a load");
        d.reset();
        assert!(!d.observe(2, 50));
    }
    #[test]
    fn knockback_needs_a_nearby_attacker() {
        assert_eq!(away(3.0, 4.0), Some([0.6, 0.8]));
        assert_eq!(away(0.0, 0.0), None);
        assert_eq!(away(10.0, 0.0), None);
    }
}
