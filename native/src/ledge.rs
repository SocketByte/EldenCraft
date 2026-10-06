//! Vanilla crouch edge protection (Player.maybeBackOffFromEdge): while sneaking on
//! the ground you never step off a drop higher than the 0.6-block step height.
//!
//! Native rays cannot run inside Elden Ring's physics step, so the post-physics
//! task probes the ground just beyond the player's half width along each world
//! axis and hands the movement model a fresh mask. The model then cancels the
//! per-axis displacement toward a drop, as vanilla does per axis.

/// Probe distance from the feet: player half width 0.3 plus a small margin.
pub const REACH_M: f64 = 0.4;
/// Vanilla maxUpStep: a lower ledge is walkable, a higher one is a drop.
pub const STEP_M: f64 = 0.6;
const START_ABOVE_M: f64 = 0.3;
/// +X, -X, +Z, -Z.
pub const DIRECTIONS: [[f64; 2]; 4] = [[1.0, 0.0], [-1.0, 0.0], [0.0, 1.0], [0.0, -1.0]];

/// Which axis directions lead off a drop, decided from probe hits (true = ground found).
pub fn mask(ground: [bool; 4]) -> [bool; 4] {
    ground.map(|found| !found)
}

/// Cancel the displacement components that would cross a drop (world X/Z, metres).
pub fn clamp(displacement: [f32; 3], drops: [bool; 4]) -> ([f32; 3], [bool; 2]) {
    let mut out = displacement;
    let block_x = (out[0] > 0.0 && drops[0]) || (out[0] < 0.0 && drops[1]);
    let block_z = (out[2] > 0.0 && drops[2]) || (out[2] < 0.0 && drops[3]);
    if block_x {
        out[0] = 0.0;
    }
    if block_z {
        out[2] = 0.0;
    }
    (out, [block_x, block_z])
}

/// Probe the four directions around Havok feet. None when queries are unavailable.
#[cfg(windows)]
pub unsafe fn probe(
    api: &crate::native_colliders::Api,
    feet: [f64; 3],
    owner: usize,
) -> Option<[bool; 4]> {
    let world = unsafe { api.world() }.ok()?;
    let filter = unsafe { api.character_filter(world) }.ok()?;
    let mut ground = [false; 4];
    for (i, [dx, dz]) in DIRECTIONS.into_iter().enumerate() {
        let origin = [
            feet[0] + dx * REACH_M,
            feet[1] + START_ABOVE_M,
            feet[2] + dz * REACH_M,
        ];
        let delta = [0.0, -(START_ABOVE_M + STEP_M), 0.0];
        ground[i] = unsafe { api.camera_ray(world, origin, delta, filter, owner) }
            .ok()?
            .is_some();
    }
    Some(mask(ground))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sneaking_stops_per_axis_toward_a_drop_only() {
        let drops = mask([false, true, true, true]); // drop toward +X only
        let (out, blocked) = clamp([0.05, 0.0, 0.03], drops);
        assert_eq!(out, [0.0, 0.0, 0.03], "keeps sliding along the edge");
        assert_eq!(blocked, [true, false]);
        let (away, _) = clamp([-0.05, 0.0, 0.0], drops);
        assert_eq!(
            away,
            [-0.05, 0.0, 0.0],
            "walking back from the edge is free"
        );
        assert_eq!(clamp([0.05, 0.0, 0.05], [false; 4]).0, [0.05, 0.0, 0.05]);
    }
}
