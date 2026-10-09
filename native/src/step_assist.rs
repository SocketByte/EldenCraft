//! Minecraft step-up onto placed blocks. Elden Ring's player capsule (radius
//! 0.3 m) cannot ride over the square 0.5 m edge of a slab or a stair step that
//! vanilla walks straight onto (Player maxUpStep 0.6; horses 1.0). Native rays
//! cannot run inside the physics stage, so PostPhysics hands the movement model
//! the merged Minecraft collider boxes around the feet, and the model starts a
//! small owned hop when the next stride runs into a low step that has headroom
//! above it. Elden Ring's own terrain keeps its native stepping.

/// A box list older than this is ignored (about nine frames).
pub const FRESH_MS: u64 = 150;
/// Vanilla Player.maxUpStep.
pub const FOOT_STEP_M: f32 = 0.6;
/// Vanilla AbstractHorse maxUpStep: Torrent climbs a full block.
pub const TORRENT_STEP_M: f32 = 1.0;
/// Lower edges already ride under the capsule's rounded bottom.
const MIN_STEP_M: f32 = 0.08;
/// Minecraft player half width and height, as the native capsule is sized.
const RADIUS_M: f32 = 0.3;
const HEIGHT_M: f32 = 1.8;
/// How far ahead of the capsule a stride is checked, in seconds of travel.
const LOOKAHEAD_S: f32 = 0.12;
const MAX_REACH_M: f32 = 1.2;
/// Boxes farther than this from the feet are never sent to the physics stage.
pub const NEAR_M: f64 = 4.0;
pub const MAX_BOXES: usize = 256;

/// Havok-space box: min x, y, z then max x, y, z.
pub type Box6 = [f32; 6];

fn rect_distance(b: &Box6, x: f32, z: f32) -> f32 {
    let dx = (b[0] - x).max(x - b[3]).max(0.0);
    let dz = (b[2] - z).max(z - b[5]).max(0.0);
    dx.hypot(dz)
}

/// Height of the low step the next stride runs into, if it can be climbed.
/// `direction` is the horizontal travel direction (any length), `speed` m/s.
pub fn rise(
    feet: [f32; 3],
    direction: [f32; 2],
    speed: f32,
    boxes: &[Box6],
    max_step: f32,
) -> Option<f32> {
    let length = direction[0].hypot(direction[1]);
    if !(speed.is_finite() && speed >= 0.5)
        || !length.is_finite()
        || length < 1e-6
        || !feet.iter().all(|v| v.is_finite())
    {
        return None;
    }
    let dir = [direction[0] / length, direction[1] / length];
    let reach = (RADIUS_M + 0.05 + speed * LOOKAHEAD_S).min(MAX_REACH_M);
    let touch = RADIUS_M * 0.95;
    let mut step: Option<f32> = None;
    for b in boxes {
        if !b.iter().all(|v| v.is_finite()) {
            continue;
        }
        let height = b[4] - feet[1];
        // Only obstacles standing at foot level: not the floor we are on, not a ceiling.
        if height <= MIN_STEP_M || b[1] >= feet[1] + max_step {
            continue;
        }
        // Sweep the footprint circle along the stride.
        let mut hit = None;
        for i in 0..=8 {
            let t = reach * i as f32 / 8.0;
            if rect_distance(b, feet[0] + dir[0] * t, feet[2] + dir[1] * t) <= touch {
                hit = Some(t);
                break;
            }
        }
        let Some(at) = hit else {
            continue;
        };
        // Behind or beside the player is not in the way of this stride.
        let cx = (b[0] + b[3]) * 0.5 - feet[0];
        let cz = (b[2] + b[5]) * 0.5 - feet[2];
        let ahead = cx * dir[0] + cz * dir[1];
        let current = rect_distance(b, feet[0], feet[2]);
        if ahead <= 0.0 && current > 0.0 {
            continue;
        }
        if height > max_step + 0.01 {
            // A wall right in front stops the stride; one further ahead does not
            // forbid stepping onto a lower step before it (stairs).
            if at <= RADIUS_M * 0.5 || current <= RADIUS_M + 0.05 {
                return None;
            }
            continue;
        }
        step = Some(step.map_or(height, |s| s.max(height)));
    }
    let height = step?;
    // Headroom for the whole body where the stride lands on the step.
    let land = [
        feet[0] + dir[0] * (RADIUS_M + 0.15),
        feet[2] + dir[1] * (RADIUS_M + 0.15),
    ];
    let floor = feet[1] + height + 0.02;
    let blocked = boxes.iter().any(|b| {
        b[4] > floor
            && b[1] < feet[1] + height + HEIGHT_M
            && b[0] < land[0] + RADIUS_M
            && b[3] > land[0] - RADIUS_M
            && b[2] < land[1] + RADIUS_M
            && b[5] > land[1] - RADIUS_M
    });
    (!blocked).then_some(height)
}

/// Apex of an owned vertical launch under the movement model's Minecraft
/// gravity: (v - 1.6 m/s) * 0.98 per 50 ms tick.
pub fn apex(velocity: f32) -> f32 {
    let mut v = velocity;
    let mut height = 0.0f32;
    let mut best = 0.0f32;
    for _ in 0..40 {
        height += v * 0.05;
        best = best.max(height);
        v = (v - 1.6) * 0.98;
        if v <= 0.0 && height < best {
            break;
        }
    }
    best
}

/// Smallest launch speed whose apex clears `height` with a little margin.
pub fn hop_velocity(height: f32) -> f32 {
    let target = height.clamp(0.0, TORRENT_STEP_M) + 0.08;
    let (mut low, mut high) = (0.5f32, 14.0f32);
    for _ in 0..24 {
        let mid = (low + high) * 0.5;
        if apex(mid) >= target {
            high = mid;
        } else {
            low = mid;
        }
    }
    high
}

/// Havok boxes near the feet, bounded, for one physics stage.
pub fn near(feet: [f64; 3], boxes: impl Iterator<Item = [f64; 6]>) -> Vec<Box6> {
    let mut out = Vec::new();
    for b in boxes {
        let dx = (b[0] - feet[0]).max(feet[0] - b[3]).max(0.0);
        let dy = (b[1] - feet[1]).max(feet[1] - b[4]).max(0.0);
        let dz = (b[2] - feet[2]).max(feet[2] - b[5]).max(0.0);
        if dx.hypot(dz) <= NEAR_M && dy <= NEAR_M {
            out.push(b.map(|v| v as f32));
            if out.len() >= MAX_BOXES {
                break;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    const FEET: [f32; 3] = [0.5, 10.0, 0.5];
    fn slab_ahead_x(top: f32) -> Box6 {
        [1.0, 10.0, 0.0, 2.0, 10.0 + top, 1.0]
    }
    #[test]
    fn walking_into_a_slab_or_stair_step_hops_onto_it() {
        let boxes = [slab_ahead_x(0.5)];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &boxes, FOOT_STEP_M), Some(0.5));
        // A stair: half-height front plus the full-height back half.
        let stair = [
            [1.0, 10.0, 0.0, 2.0, 10.5, 1.0],
            [1.5, 10.5, 0.0, 2.0, 11.0, 1.0],
        ];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &stair, FOOT_STEP_M), Some(0.5));
        // Standing on the slab, the back step is next.
        let on_slab = [1.2, 10.5, 0.5];
        assert_eq!(
            rise(on_slab, [1.0, 0.0], 4.3, &stair, FOOT_STEP_M),
            Some(0.5)
        );
    }
    #[test]
    fn full_blocks_need_a_jump_on_foot_but_torrent_climbs_them() {
        let block = [slab_ahead_x(1.0)];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &block, FOOT_STEP_M), None);
        assert_eq!(
            rise(FEET, [1.0, 0.0], 11.0, &block, TORRENT_STEP_M),
            Some(1.0)
        );
    }
    #[test]
    fn no_hop_without_travel_sideways_behind_or_far_away() {
        let boxes = [slab_ahead_x(0.5)];
        assert_eq!(rise(FEET, [1.0, 0.0], 0.1, &boxes, FOOT_STEP_M), None);
        assert_eq!(rise(FEET, [-1.0, 0.0], 4.3, &boxes, FOOT_STEP_M), None);
        assert_eq!(rise(FEET, [0.0, 1.0], 4.3, &boxes, FOOT_STEP_M), None);
        let far = [[3.0, 10.0, 0.0, 4.0, 10.5, 1.0]];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &far, FOOT_STEP_M), None);
        // Sprinting reaches a little further ahead.
        let near_far = [[1.25, 10.0, 0.0, 2.0, 10.5, 1.0]];
        assert_eq!(
            rise(FEET, [1.0, 0.0], 5.6, &near_far, FOOT_STEP_M),
            Some(0.5)
        );
    }
    #[test]
    fn floors_carpets_and_ceilings_are_not_steps() {
        let floor = [[-5.0, 9.0, -5.0, 5.0, 10.0, 5.0]];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &floor, FOOT_STEP_M), None);
        let carpet = [slab_ahead_x(0.0625)];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &carpet, FOOT_STEP_M), None);
        let overhead = [[1.0, 11.0, 0.0, 2.0, 12.0, 1.0]];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &overhead, FOOT_STEP_M), None);
    }
    #[test]
    fn no_hop_into_a_low_ceiling_or_a_wall_right_in_front() {
        let low_ceiling = [slab_ahead_x(0.5), [0.0, 12.0, -1.0, 3.0, 13.0, 2.0]];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &low_ceiling, FOOT_STEP_M), None);
        let tall_room = [slab_ahead_x(0.5), [0.0, 13.0, -1.0, 3.0, 14.0, 2.0]];
        assert_eq!(
            rise(FEET, [1.0, 0.0], 4.3, &tall_room, FOOT_STEP_M),
            Some(0.5)
        );
        let wall = [[0.85, 10.0, -1.0, 1.0, 12.0, 2.0], slab_ahead_x(0.5)];
        assert_eq!(rise(FEET, [1.0, 0.0], 4.3, &wall, FOOT_STEP_M), None);
    }
    #[test]
    fn hop_velocity_clears_the_step_with_minecraft_gravity() {
        for height in [0.1f32, 0.25, 0.5, 0.6, 1.0] {
            let v = hop_velocity(height);
            assert!(apex(v) >= height + 0.07, "{height}: {v} {}", apex(v));
            assert!(apex(v) <= height + 0.2, "{height}: no needless leap");
        }
        // A full jump (8.4 m/s) peaks near the vanilla 1.25 blocks.
        assert!((apex(8.4) - 1.25).abs() < 0.05, "{}", apex(8.4));
        assert!(hop_velocity(0.6) < 8.4);
    }
    #[test]
    fn non_finite_input_never_hops_and_near_is_bounded() {
        let boxes = [slab_ahead_x(0.5)];
        assert_eq!(
            rise([f32::NAN, 0.0, 0.0], [1.0, 0.0], 4.3, &boxes, FOOT_STEP_M),
            None
        );
        assert_eq!(rise(FEET, [0.0, 0.0], 4.3, &boxes, FOOT_STEP_M), None);
        let many = (0..1000).map(|i| [i as f64 * 0.001, 0., 0., 1., 1., 1.]);
        assert_eq!(near([0.; 3], many).len(), MAX_BOXES);
        let far = std::iter::once([10., 0., 0., 11., 1., 1.]);
        assert!(near([0.; 3], far).is_empty());
    }
}
