//! Read-only crosshair targeting diagnostics for the pinned Elden Ring SDK.
//!
//! This is NOT a damage authorization API. The ray filter is observed in the
//! pinned mapper's `ba 58 00 00 02 e8 ...` callsite, but its collision-layer
//! semantics and the capsule proxy must be checked live before enabling damage.
//! No target pointers or SDK references leave the sampling game task.

use serde::Serialize;

/// Positive combat-role evidence. ChrIns/EnemyIns alone also represent world
/// helpers, while different teams alone do not establish a damageable enemy.
#[derive(Clone, Copy, Debug, Serialize)]
pub struct Eligibility {
    pub enemy_class: bool,
    /// Human NPC bosses such as Gideon inherit PlayerIns, not EnemyIns.
    pub player_class: bool,
    pub character_type: i32,
    pub team: u8,
    pub player_team: u8,
    pub npc_param_present: bool,
    /// Registration of this exact handle in the boss-health roster establishes
    /// its combat role independently of PvP/PvE appearance type or lock-on.
    pub boss_registered: bool,
    /// An exact authored body/owner pair with its current native boss roster.
    /// This does not infer an encounter from a matching model or nearby boss.
    pub boss_damage_linked: bool,
    pub lock_distance: u8,
    pub lock_disabled: bool,
}
impl Eligibility {
    pub(crate) fn rejection(self) -> Option<&'static str> {
        let boss_role = self.boss_registered || self.boss_damage_linked;
        if !self.enemy_class && !(self.player_class && self.boss_registered) {
            Some("not_enemy_class")
        } else if !boss_role && !native_enemy_type(self.character_type) {
            Some("not_native_npc")
        } else if self.team == self.player_team {
            Some("same_team")
        } else if !self.npc_param_present {
            Some("npc_param_missing")
        } else if !boss_role && self.lock_distance == 0 {
            Some("npc_not_lockable")
        } else if !boss_role && self.lock_disabled {
            Some("native_lock_disabled")
        } else {
            None
        }
    }
}

/// Ordinary NPC enemies use type 5. Large scripted enemies (live: Fire Giant
/// c4760, c4750, c2030) use the unnamed type 7. The Fire Giant's boss-health
/// gauge is registered to its dormant second-phase character, so the body in
/// combat must qualify through this ordinary path. Phantoms/ghosts never do.
fn native_enemy_type(character_type: i32) -> bool {
    use eldenring::cs::ChrType;
    character_type == ChrType::Npc as i32 || character_type == ChrType::Unk7 as i32
}

/// Rechecked immediately before damage as well as when publishing target boxes.
/// A previous hit may have started an invulnerable boss phase in the same tick.
#[derive(Clone, Copy, Debug)]
struct TargetActivity {
    active: bool,
    tasks_registered: bool,
    dead: bool,
    invincible: bool,
    character_disabled: bool,
    hit_disabled: bool,
    delta_time: f32,
}
impl TargetActivity {
    fn rejection(self) -> Option<&'static str> {
        if !self.active {
            Some("target_inactive")
        } else if !self.tasks_registered {
            Some("target_tasks_unregistered")
        } else if self.dead {
            Some("target_dead")
        } else if self.invincible {
            Some("target_invincible")
        } else if self.character_disabled {
            Some("target_character_disabled")
        } else if self.hit_disabled {
            Some("target_hit_disabled")
        } else if !self.delta_time.is_finite() || !(0.0..=0.25).contains(&self.delta_time) {
            Some("target_delta_time_invalid")
        } else {
            None
        }
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct RejectedTarget {
    pub handle: Handle,
    pub npc_id: i32,
    pub npc_param_id: i32,
    /// Absent when activity/task readiness prevented safe module sampling.
    pub position_havok: Option<[f32; 3]>,
    pub reason: &'static str,
    pub eligibility: Option<Eligibility>,
    /// Copied collision dimensions when all shape sources were rejected.
    pub rejected_shapes: Option<RejectedShapes>,
}

#[derive(Clone, Debug, Serialize)]
pub struct RejectedShapes {
    pub character: [f32; 2],
    pub map: [f32; 2],
    pub npc_character: Option<[f32; 2]>,
    pub npc_map: Option<[f32; 2]>,
}

/// Read only on the existing authorized game task with a current ChrIns. No
/// incomplete Rust enum is instantiated from the native character type.
#[cfg(windows)]
pub fn eligibility(chr: &eldenring::cs::ChrIns, player_team: u8) -> Eligibility {
    use eldenring::cs::{EnemyIns, NpcParam, PlayerIns, SoloParamRepository};
    use fromsoftware_shared::{FromStatic, Superclass};
    let enemy_class = chr.as_subclass::<EnemyIns>().is_some();
    let player_class = chr.as_subclass::<PlayerIns>().is_some();
    let boss_registered = registered_boss(chr);
    let character_type = unsafe { std::ptr::addr_of!(chr.chr_type).cast::<i32>().read() };
    let lock_distance =
        if (enemy_class || (player_class && boss_registered)) && chr.npc_param_id >= 0 {
            unsafe { SoloParamRepository::instance() }
                .ok()
                .and_then(|repo| repo.get::<NpcParam>(chr.npc_param_id as u32))
                .map(|row| row.lock_dist())
        } else {
            None
        };
    Eligibility {
        enemy_class,
        player_class,
        character_type,
        team: chr.team_type,
        player_team,
        npc_param_present: lock_distance.is_some(),
        boss_registered,
        boss_damage_linked: crate::boss_damage_links::registered_body(chr),
        lock_distance: lock_distance.unwrap_or(0),
        lock_disabled: chr.chr_ctrl.modifier.data.action_flags.disable_lock_on(),
    }
}

#[cfg(windows)]
fn registered_boss(chr: &eldenring::cs::ChrIns) -> bool {
    use eldenring::cs::CSFeManImp;
    use fromsoftware_shared::FromStatic;
    unsafe { CSFeManImp::instance() }.is_ok_and(|frontend| {
        frontend.boss_health_displays.iter().any(|display| {
            display.fmg_id > 0
                && !display.field_ins_handle.is_empty()
                && display.field_ins_handle == chr.field_ins_handle
        })
    })
}

/// Shared by snapshot publication, native damage dispatch and actor templates.
/// This is conservative targetability, not a universal faction-relation table.
#[cfg(windows)]
pub fn target_rejection(chr: &eldenring::cs::ChrIns, player_team: u8) -> Option<&'static str> {
    eligibility(chr, player_team).rejection()
}

/// Minor and major bosses keep their authored rewards instead of ordinary
/// enemy loot. Either the frontend's boss-health registration for this exact
/// character or its NpcParam boss rune award classifies it. Read only on the
/// existing authorized game task with a current ChrIns.
#[cfg(windows)]
pub fn boss_encounter(chr: &eldenring::cs::ChrIns) -> bool {
    use eldenring::cs::{CSFeManImp, NpcParam, SoloParamRepository};
    use fromsoftware_shared::FromStatic;
    let registered = unsafe { CSFeManImp::instance() }.is_ok_and(|frontend| {
        frontend.boss_health_displays.iter().any(|display| {
            !display.field_ins_handle.is_empty() && display.field_ins_handle == chr.field_ins_handle
        })
    });
    registered
        || crate::boss_damage_links::registered_body(chr)
        || (chr.npc_param_id >= 0
            && unsafe { SoloParamRepository::instance() }
                .ok()
                .and_then(|repo| repo.get::<NpcParam>(chr.npc_param_id as u32))
                .is_some_and(|row| row.is_soul_get_by_boss()))
}

/// Camera ray length, not player reach: rear view can be four metres behind the player.
pub const MAX_REACH_M: f32 = 10.0;
const GROUND_RAY_M: f32 = 6.0;
pub const MAX_NEARBY_M: f32 = 16.0;
pub const MAX_TARGETS: usize = 32;
const MAX_SCANNED: usize = 256;
const MAX_BOSS_SOURCES: usize = 3;
const MAX_VECTOR_ENTRIES: usize = 4096;
pub const EXPERIMENTAL_RAY_FILTER: u32 = 0x0200_0058;

/// Distance/update-priority bookkeeping is not the encounter registry. Always
/// reserve space for the current registered bosses, including a boss absent
/// from (or beyond the bounded prefix of) that ordinary-character list.
fn scan_sources<T: Copy + PartialEq>(
    ordinary: impl IntoIterator<Item = T>,
    bosses: impl IntoIterator<Item = T>,
) -> Vec<T> {
    let mut sources = Vec::with_capacity(MAX_SCANNED + MAX_BOSS_SOURCES);
    for source in ordinary
        .into_iter()
        .take(MAX_SCANNED)
        .chain(bosses.into_iter().take(MAX_BOSS_SOURCES))
    {
        if !sources.contains(&source) {
            sources.push(source);
        }
    }
    sources
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct Handle {
    pub selector: u32,
    pub block_id: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum ShapeSource {
    Character,
    MapFallback,
    NpcCharacterFallback,
    NpcMapFallback,
}

#[derive(Clone, Debug, Serialize)]
pub struct Candidate {
    pub handle: Handle,
    /// Local identity hint only. Never serialize an address into guest transport.
    #[serde(skip)]
    pub instance_token: usize,
    pub npc_id: i32,
    pub npc_param_id: i32,
    /// Raw u8; different teams do not prove hostility.
    pub team: u8,
    pub boss_registered: bool,
    pub position_havok: [f32; 3],
    /// Total upright shape height from the physics origin at its bottom.
    /// Character collision dimensions take priority over the map fallback;
    /// neither is asserted to match every per-bone damage hurtbox.
    pub height: f32,
    pub radius: f32,
    pub shape_source: ShapeSource,
    /// Source dimensions [total height, radius]; invalid pairs are omitted.
    pub character_shape: Option<[f32; 2]>,
    pub map_shape: Option<[f32; 2]>,
    /// Bounding box of the diagnostic capsule, not a native per-bone damage shape.
    pub proxy_min_havok: [f32; 3],
    pub proxy_max_havok: [f32; 3],
    pub hp: i32,
    pub max_hp: i32,
    pub player_distance_m: f32,
    /// Intersection with the exact published proxy AABB, matching guest picking.
    pub ray_entry_m: Option<f32>,
    /// None when the ray diagnostic was unavailable/invalid or this proxy was missed.
    pub ray_obstructed: Option<bool>,
    /// Independent ray to the closest point on this candidate's published AABB.
    /// A contained camera is visible at distance zero; otherwise unknown when
    /// unavailable, invalid or beyond the ten-metre camera-ray cap.
    pub los_clear: Option<bool>,
    pub los_distance_m: f32,
    pub los: RayDiagnostic,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum RayStatus {
    Unavailable,
    Miss,
    Hit,
    InvalidResult,
}

#[derive(Clone, Debug, Serialize)]
pub struct RayDiagnostic {
    pub filter: u32,
    pub experimental: bool,
    pub status: RayStatus,
    pub hit_position_havok: Option<[f32; 3]>,
    pub hit_distance_m: Option<f32>,
}

#[derive(Clone, Debug, Serialize)]
pub struct TargetSnapshot {
    pub sampled_tick_ms: u64,
    pub current_block_id: i32,
    pub player_handle: Handle,
    pub player_team: u8,
    pub player_havok: [f32; 3],
    pub camera_origin_havok: [f32; 3],
    pub forward: [f32; 3],
    pub reach_m: f32,
    pub scanned: usize,
    pub source_count: usize,
    pub truncated: bool,
    pub candidates: Vec<Candidate>,
    /// At most eight nearby rejected roles, with no addresses or retained refs.
    pub rejected_roles: Vec<RejectedTarget>,
    /// Closest published AABB ray entry, including an occluded candidate. No lock-on needed.
    pub nearest: Option<usize>,
    pub ray: RayDiagnostic,
    /// Six metres down from camera. Diagnostic only, never an admission gate.
    pub ground_ray: RayDiagnostic,
    /// Intentionally always false: neither hostility nor terrain filtering is verified.
    pub damage_authorized: bool,
}

fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a.into_iter().zip(b).map(|(a, b)| a * b).sum()
}
fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|i| a[i] - b[i])
}
/// Copied evidence from a current native character/projectile on the damage task.
/// The identity token stays inside native processing and is never published.
pub(crate) struct ShieldAttack {
    pub handle: Handle,
    pub instance_token: usize,
    pub npc_param_id: i32,
    pub source_position: [f32; 3],
    pub projectile_position: Option<[f32; 3]>,
    pub incoming: [f32; 3],
    pub direction_source: &'static str,
}
fn consistent_owner<T: Copy + PartialEq>(caller: Option<T>, request: Option<T>) -> Option<T> {
    match (caller, request) {
        (Some(a), Some(b)) if a != b => None,
        (Some(a), _) => Some(a),
        (_, b) => b,
    }
}
fn shield_direction(
    victim: [f32; 3],
    owner: [f32; 3],
    projectile: Option<([f32; 3], [f32; 3])>,
) -> Option<([f32; 3], &'static str)> {
    let bounded = |v| finite(v) && dot(v, v) <= 256. * 256.;
    let horizontal = |v: [f32; 3]| v[0] * v[0] + v[2] * v[2] >= 0.000001;
    if !finite(victim) || !finite(owner) {
        return None;
    }
    if let Some((position, velocity)) = projectile {
        let delta = sub(victim, position);
        if !finite(position) || !bounded(delta) || !bounded(velocity) {
            return None;
        }
        if horizontal(delta) {
            return Some((delta, "projectile_position"));
        }
        // A moving projectile can already be at the player's centre when the
        // HP processor runs. Its copied velocity still describes its approach.
        if horizontal(velocity) {
            return Some((velocity, "projectile_velocity"));
        }
    }
    let delta = sub(victim, owner);
    (bounded(delta) && horizontal(delta)).then_some((
        delta,
        if projectile.is_some() {
            "stationary_projectile_owner"
        } else {
            "character_position"
        },
    ))
}
fn finite(v: [f32; 3]) -> bool {
    v.into_iter()
        .all(|n| n.is_finite() && n.abs() <= 1_000_000.0)
}
const FALLBACK_EYE_HEIGHT_M: f32 = 1.6;
/// Melee picking needs the actual camera. The shared-world publication only
/// needs nearby targets and line of sight: when the native camera trails the
/// body by >10m (fast glide/fall, F5 boom recovery) use the player's eye rather
/// than failing, and thereby suspending, the entire shared-world tick.
fn view_origin(
    camera: [f32; 3],
    feet: [f32; 3],
    eye_fallback: bool,
) -> Result<[f32; 3], &'static str> {
    if !finite(feet) {
        return Err("target camera origin rejected");
    }
    if finite(camera) && dot(sub(camera, feet), sub(camera, feet)) <= 100.0 {
        return Ok(camera);
    }
    if eye_fallback {
        Ok([feet[0], feet[1] + FALLBACK_EYE_HEIGHT_M, feet[2]])
    } else {
        Err("target camera origin rejected")
    }
}
fn normalize(v: [f32; 3]) -> Option<[f32; 3]> {
    let n = dot(v, v);
    (finite(v) && (0.25..=4.0).contains(&n)).then(|| v.map(|x| x / n.sqrt()))
}
fn valid_reach(reach: f32) -> bool {
    reach.is_finite() && reach > 0.0 && reach <= MAX_REACH_M
}
fn valid_shape(height: f32, radius: f32) -> bool {
    height.is_finite()
        && radius.is_finite()
        // Guest entity extents are bounded to 64m, including combat padding.
        // Giant bosses exceed the old ordinary-enemy 16m/8m limits.
        && (0.05..=63.8).contains(&height)
        && (0.025..=31.25).contains(&radius)
}
fn shape(height: f32, radius: f32) -> Option<[f32; 2]> {
    valid_shape(height, radius).then_some([height, radius])
}
fn select_shape(
    character: Option<[f32; 2]>,
    map: Option<[f32; 2]>,
) -> Option<(ShapeSource, [f32; 2])> {
    character
        .map(|s| (ShapeSource::Character, s))
        .or_else(|| map.map(|s| (ShapeSource::MapFallback, s)))
}
fn select_authored_shape(
    character: Option<[f32; 2]>,
    map: Option<[f32; 2]>,
    npc_character: Option<[f32; 2]>,
    npc_map: Option<[f32; 2]>,
) -> Option<(ShapeSource, [f32; 2])> {
    select_shape(character, map)
        .or_else(|| npc_character.map(|s| (ShapeSource::NpcCharacterFallback, s)))
        .or_else(|| npc_map.map(|s| (ShapeSource::NpcMapFallback, s)))
}
fn proxy_bounds(position: [f32; 3], height: f32, radius: f32) -> ([f32; 3], [f32; 3]) {
    // Exact 2.7.1.0 proxy factory: total height h, radius r; upright capsule
    // segment endpoints are y=r and y=h-r. Physics initialization passes
    // chr_hit_height/radius to this factory. Do not expand total height by 2r.
    // For h<2r the native factory uses a flattened convex shape, still [0,h].
    // We export an upright AABB, not the exact per-bone or tilted native shape.
    (
        [position[0] - radius, position[1], position[2] - radius],
        [
            position[0] + radius,
            position[1] + height,
            position[2] + radius,
        ],
    )
}
#[derive(Clone, Copy, Debug)]
struct HitboxPadding {
    horizontal: f32,
    below: f32,
    above: f32,
}
impl Default for HitboxPadding {
    fn default() -> Self {
        Self {
            horizontal: 0.18,
            below: 0.05,
            above: 0.15,
        }
    }
}
fn bounded_padding(value: Option<&str>, fallback: f32) -> f32 {
    value
        .and_then(|s| s.parse::<f32>().ok())
        .filter(|v| v.is_finite() && (0.0..=0.75).contains(v))
        .unwrap_or(fallback)
}
fn hitbox_padding() -> HitboxPadding {
    static PADDING: std::sync::OnceLock<HitboxPadding> = std::sync::OnceLock::new();
    *PADDING.get_or_init(|| {
        let d = HitboxPadding::default();
        HitboxPadding {
            horizontal: bounded_padding(
                std::env::var("ELDENCRAFT_HITBOX_PADDING").ok().as_deref(),
                d.horizontal,
            ),
            below: d.below,
            above: d.above,
        }
    })
}
fn combat_bounds(
    position: [f32; 3],
    height: f32,
    radius: f32,
    padding: HitboxPadding,
) -> ([f32; 3], [f32; 3]) {
    let (min, max) = proxy_bounds(position, height, radius);
    (
        [
            min[0] - padding.horizontal,
            min[1] - padding.below,
            min[2] - padding.horizontal,
        ],
        [
            max[0] + padding.horizontal,
            max[1] + padding.above,
            max[2] + padding.horizontal,
        ],
    )
}
fn unavailable_ray() -> RayDiagnostic {
    RayDiagnostic {
        filter: EXPERIMENTAL_RAY_FILTER,
        experimental: true,
        status: RayStatus::Unavailable,
        hit_position_havok: None,
        hit_distance_m: None,
    }
}
fn closest_point(origin: [f32; 3], min: [f32; 3], max: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|i| origin[i].clamp(min[i], max[i]))
}
fn distance_to_bounds(point: [f32; 3], min: [f32; 3], max: [f32; 3]) -> f32 {
    let delta = sub(closest_point(point, min, max), point);
    dot(delta, delta).sqrt()
}
fn contains(origin: [f32; 3], min: [f32; 3], max: [f32; 3]) -> bool {
    (0..3).all(|i| origin[i] >= min[i] && origin[i] <= max[i])
}
fn contained_visibility(candidate: &mut Candidate, origin: [f32; 3], forward: [f32; 3]) -> bool {
    if !contains(origin, candidate.proxy_min_havok, candidate.proxy_max_havok) {
        return false;
    }
    // AABB picking accepts t=0; there is no intervening segment to cast.
    candidate.los_distance_m = 0.0;
    candidate.los_clear = Some(true);
    candidate.los = ray_diagnostic(origin, forward, 0.0, None);
    true
}

/// Slab intersection against the same AABB exported to Minecraft. No sphere/capsule
/// substitution: otherwise Minecraft could accept an edge hit that the host rejects.
pub fn ray_aabb(
    origin: [f32; 3],
    direction: [f32; 3],
    min: [f32; 3],
    max: [f32; 3],
    reach: f32,
) -> Option<f32> {
    if !finite(origin)
        || !finite(direction)
        || !finite(min)
        || !finite(max)
        || !valid_reach(reach)
        || (dot(direction, direction) - 1.0).abs() > 0.001
        || (0..3).any(|i| min[i] >= max[i])
    {
        return None;
    }
    let (mut near, mut far) = (0.0_f32, reach);
    for i in 0..3 {
        if direction[i].abs() < 1e-8 {
            if origin[i] < min[i] || origin[i] > max[i] {
                return None;
            }
            continue;
        }
        let a = (min[i] - origin[i]) / direction[i];
        let b = (max[i] - origin[i]) / direction[i];
        near = near.max(a.min(b));
        far = far.min(a.max(b));
        if far < near {
            return None;
        }
    }
    Some(near)
}

/// Pure bounded ray versus an upright capsule of total height. The native
/// flattened-convex case h<2r is unsupported by this diagnostic helper.
/// Runtime picking uses the AABB instead. Direction must be normalized.
#[cfg(test)]
fn ray_capsule(
    origin: [f32; 3],
    direction: [f32; 3],
    base: [f32; 3],
    height: f32,
    radius: f32,
    reach: f32,
) -> Option<f32> {
    if !finite(origin)
        || !finite(base)
        || !finite(direction)
        || !valid_shape(height, radius)
        || height < 2.0 * radius
        || !valid_reach(reach)
        || (dot(direction, direction) - 1.0).abs() > 0.001
    {
        return None;
    }
    let p = sub(origin, [base[0], base[1] + radius, base[2]]);
    let segment_height = height - 2.0 * radius;
    let nearest_y = p[1].clamp(0.0, segment_height);
    if p[0] * p[0] + (p[1] - nearest_y).powi(2) + p[2] * p[2] <= radius * radius {
        return Some(0.0);
    }
    let mut nearest = f32::INFINITY;
    let a = direction[0].powi(2) + direction[2].powi(2);
    let b = p[0] * direction[0] + p[2] * direction[2];
    let c = p[0].powi(2) + p[2].powi(2) - radius * radius;
    let discriminant = b * b - a * c;
    if a > 1e-8 && discriminant >= 0.0 {
        for t in [
            (-b - discriminant.sqrt()) / a,
            (-b + discriminant.sqrt()) / a,
        ] {
            let y = p[1] + t * direction[1];
            if t >= 0.0 && t <= reach && (0.0..=segment_height).contains(&y) {
                nearest = nearest.min(t);
            }
        }
    }
    for y in [0.0, segment_height] {
        let q = [p[0], p[1] - y, p[2]];
        let b = dot(q, direction);
        let discriminant = b * b - dot(q, q) + radius * radius;
        if discriminant >= 0.0 {
            let t = -b - discriminant.sqrt();
            if t >= 0.0 && t <= reach {
                nearest = nearest.min(t);
            }
        }
    }
    nearest.is_finite().then_some(nearest)
}

fn ray_diagnostic(
    origin: [f32; 3],
    forward: [f32; 3],
    reach: f32,
    hit: Option<[f32; 3]>,
) -> RayDiagnostic {
    let mut out = RayDiagnostic {
        filter: EXPERIMENTAL_RAY_FILTER,
        experimental: true,
        status: RayStatus::Miss,
        hit_position_havok: None,
        hit_distance_m: None,
    };
    if let Some(point) = hit {
        let delta = sub(point, origin);
        let distance = dot(delta, forward);
        let perpendicular = dot(delta, delta) - distance * distance;
        if !finite(point)
            || !distance.is_finite()
            || !(-0.05..=reach + 0.05).contains(&distance)
            || !perpendicular.is_finite()
            || perpendicular > 0.01
        {
            out.status = RayStatus::InvalidResult;
        } else {
            out.status = RayStatus::Hit;
            out.hit_position_havok = Some(point);
            out.hit_distance_m = Some(distance.clamp(0.0, reach));
        }
    }
    out
}

fn finalize(snapshot: &mut TargetSnapshot) {
    snapshot.candidates.sort_by(|a, b| {
        // Reserve the bounded publication for current encounters before nearby
        // ordinary enemies; a crowded arena must not evict its boss hitbox.
        b.boss_registered.cmp(&a.boss_registered).then(
            a.player_distance_m
                .total_cmp(&b.player_distance_m)
                .then(a.handle.selector.cmp(&b.handle.selector))
                .then(a.handle.block_id.cmp(&b.handle.block_id)),
        )
    });
    snapshot.truncated |= snapshot.candidates.len() > MAX_TARGETS;
    snapshot.candidates.truncate(MAX_TARGETS);
    for candidate in &mut snapshot.candidates {
        candidate.ray_entry_m = ray_aabb(
            snapshot.camera_origin_havok,
            snapshot.forward,
            candidate.proxy_min_havok,
            candidate.proxy_max_havok,
            snapshot.reach_m,
        );
        candidate.ray_obstructed =
            candidate
                .ray_entry_m
                .and_then(|entry| match snapshot.ray.status {
                    RayStatus::Miss => Some(false),
                    RayStatus::Hit => snapshot.ray.hit_distance_m.map(|hit| hit + 0.05 < entry),
                    _ => None,
                });
    }
    snapshot.nearest = snapshot
        .candidates
        .iter()
        .enumerate()
        .filter_map(|(i, c)| c.ray_entry_m.map(|d| (i, d)))
        .min_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(i, _)| i);
    snapshot.damage_authorized = false;
}

#[cfg(windows)]
mod live {
    use super::*;
    use eldenring::{
        DLVector,
        cs::{
            CSCamExt, CSCamera, CSFeManImp, CSHavokMan, CSSessionManager, ChrIns,
            ChrInsDistanceEntry, FieldInsType, GameMan, LobbyState, NpcParam, PlayerIns,
            ProtocolState, SoloParamRepository, WorldChrMan,
        },
        position::{HavokPosition, PositionDelta},
    };
    use fromsoftware_shared::FromStatic;
    use std::{
        ffi::c_void,
        mem::{align_of, size_of},
    };

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetTickCount64() -> u64;
    }

    // Pinned shared/stl/src/vector.rs: MSVC2015 allocator, first, last, end.
    // Validate the bounded span BEFORE constructing a slice; do not call SDK Deref first.
    #[repr(C)]
    struct VectorView {
        allocator: *const c_void,
        first: *const ChrInsDistanceEntry,
        last: *const ChrInsDistanceEntry,
        end: *const ChrInsDistanceEntry,
    }
    const _: () = assert!(size_of::<VectorView>() == size_of::<DLVector<ChrInsDistanceEntry>>());
    const _: () = assert!(align_of::<VectorView>() == align_of::<DLVector<ChrInsDistanceEntry>>());

    unsafe fn entries(
        vector: &DLVector<ChrInsDistanceEntry>,
    ) -> Result<&[ChrInsDistanceEntry], &'static str> {
        let raw = unsafe { &*(std::ptr::from_ref(vector).cast::<VectorView>()) };
        let [first, last, end] = [raw.first as usize, raw.last as usize, raw.end as usize];
        if first == 0 {
            return if last == 0 && end == 0 {
                Ok(&[])
            } else {
                Err("target vector null span")
            };
        }
        let bytes = last
            .checked_sub(first)
            .ok_or("target vector reversed span")?;
        let capacity = end
            .checked_sub(first)
            .ok_or("target vector reversed capacity")?;
        let stride = size_of::<ChrInsDistanceEntry>();
        if first % align_of::<ChrInsDistanceEntry>() != 0
            || bytes % stride != 0
            || capacity % stride != 0
            || bytes > capacity
            || capacity / stride > MAX_VECTOR_ENTRIES
        {
            return Err("target vector bounds rejected");
        }
        Ok(unsafe { std::slice::from_raw_parts(raw.first, bytes / stride) })
    }
    fn current_character<'a>(
        world: &'a WorldChrMan,
        id: &eldenring::cs::FieldInsHandle,
    ) -> Option<&'a ChrIns> {
        if id.is_empty() || id.selector.field_ins_type() != Some(FieldInsType::Chr) {
            return None;
        }
        let chr = world.chr_ins_by_handle(id)?;
        if chr.field_ins_handle != *id
            || !chr.chr_flags1c8.is_active()
            || !chr.chr_flags1c8.update_tasks_registered()
            || !unsafe { chr.chr_set_entry.as_ref() }
                .chr_ins
                .is_some_and(|entry| std::ptr::eq(entry.as_ptr(), chr))
        {
            return None;
        }
        Some(chr)
    }
    /// Both arguments originate in the pinned native damage call, whose sources
    /// are FieldIns derivants. Read only their common handle before resolving
    /// and comparing the exact current ChrIns; distance-priority lists are not
    /// the character registry and can omit the owner of a ranged attack.
    unsafe fn source_character(world: &WorldChrMan, source: usize) -> Option<&ChrIns> {
        if source == 0 || !source.is_multiple_of(8) {
            return None;
        }
        const HANDLE_OFFSET: usize = std::mem::offset_of!(ChrIns, field_ins_handle);
        const _: () = assert!(HANDLE_OFFSET == 8);
        let id =
            unsafe { ((source + HANDLE_OFFSET) as *const eldenring::cs::FieldInsHandle).read() };
        current_character(world, &id).filter(|chr| std::ptr::from_ref(*chr) as usize == source)
    }
    #[derive(Clone, Copy)]
    struct ProjectileSource {
        owner: eldenring::cs::FieldInsHandle,
        position: [f32; 3],
        velocity: [f32; 3],
    }
    unsafe fn source_projectile(source: usize) -> Option<ProjectileSource> {
        use eldenring::cs::CSBulletManager;
        if source == 0 || !source.is_multiple_of(8) {
            return None;
        }
        let manager = unsafe { CSBulletManager::instance() }.ok()?;
        // Compare pointers in the current native list before accessing bullet
        // fields. The bound also prevents a malformed/cyclic list from hanging.
        let bullet = manager
            .bullets()
            .take(MAX_VECTOR_ENTRIES)
            .find(|bullet| std::ptr::from_ref(*bullet) as usize == source)?;
        if bullet.field_ins_handle.is_empty()
            || bullet.field_ins_handle.selector.field_ins_type() != Some(FieldInsType::Bullet)
            || !std::ptr::eq(bullet.targeting_owner.bullet.as_ptr(), bullet)
        {
            return None;
        }
        let p = bullet.physics.position;
        let v = bullet.physics.velocity;
        Some(ProjectileSource {
            owner: bullet.targeting_owner.owner_chr_handle,
            position: [p.0, p.1, p.2],
            velocity: [v.0, v.1, v.2],
        })
    }
    /// A native projectile and its owning character may occupy different source
    /// slots. Resolve their owner identities instead of requiring pointer equality.
    /// Reaction vectors remain diagnostics, never attack-direction evidence.
    pub(crate) unsafe fn shield_attack(
        source: usize,
        request_source: usize,
        target: usize,
    ) -> Option<ShieldAttack> {
        let world = unsafe { WorldChrMan::instance() }.ok()?;
        let player = world.main_player.as_ref()?;
        if &player.chr_ins as *const ChrIns as usize != target {
            return None;
        }
        let caller_character = unsafe { source_character(world, source) };
        let caller_projectile = caller_character
            .is_none()
            .then(|| unsafe { source_projectile(source) })
            .flatten();
        let request_character = if request_source == source {
            caller_character
        } else {
            unsafe { source_character(world, request_source) }
        };
        let request_projectile = if request_source == source {
            caller_projectile
        } else {
            request_character
                .is_none()
                .then(|| unsafe { source_projectile(request_source) })
                .flatten()
        };
        let caller_owner = caller_character
            .or_else(|| caller_projectile.and_then(|p| current_character(world, &p.owner)));
        let request_owner = request_character
            .or_else(|| request_projectile.and_then(|p| current_character(world, &p.owner)));
        if caller_projectile.is_some() && caller_owner.is_none()
            || request_projectile.is_some() && request_owner.is_none()
        {
            return None;
        }
        let owner = consistent_owner(
            caller_owner.map(std::ptr::from_ref),
            request_owner.map(std::ptr::from_ref),
        )?;
        let chr = caller_owner
            .filter(|chr| std::ptr::eq(*chr, owner))
            .or(request_owner)?;
        if owner as usize == target {
            return None;
        }
        let projectile = request_projectile.or(caller_projectile);
        let a = chr.modules.physics.position;
        let b = player.chr_ins.modules.physics.position;
        let source_position = [a.0, a.1, a.2];
        let (incoming, direction_source) = shield_direction(
            [b.0, b.1, b.2],
            source_position,
            projectile.map(|p| (p.position, p.velocity)),
        )?;
        Some(ShieldAttack {
            handle: handle(chr),
            instance_token: owner as usize,
            npc_param_id: chr.npc_param_id,
            source_position,
            projectile_position: projectile.map(|p| p.position),
            incoming,
            direction_source,
        })
    }
    fn handle(chr: &ChrIns) -> Handle {
        Handle {
            selector: chr.field_ins_handle.selector.0,
            block_id: chr.field_ins_handle.block_id.0,
        }
    }
    pub(crate) fn readiness_rejection(chr: &ChrIns) -> Option<&'static str> {
        // No ChrType, ChrLoadStatus, ChrUpdateType or OmissionMode enum is read.
        let activity = TargetActivity {
            active: chr.chr_flags1c8.is_active(),
            tasks_registered: chr.chr_flags1c8.update_tasks_registered(),
            dead: chr.chr_flags1c5.death_flag(),
            invincible: chr.chr_flags1c5.is_invincible(),
            character_disabled: chr.debug_flags.character_disabled(),
            hit_disabled: chr.debug_flags.disabled_hit(),
            delta_time: chr.chr_update_delta_time,
        };
        if let Some(reason) = activity.rejection() {
            return Some(reason);
        }
        // Do not access modules until the actor is active and its tasks exist.
        if chr.modules.data.hp <= 0
            || chr.modules.data.max_hp <= 0
            || chr.modules.data.hp > chr.modules.data.max_hp
        {
            Some("target_health_invalid")
        } else {
            None
        }
    }
    fn reject_boss(snapshot: &mut TargetSnapshot, chr: &ChrIns, reason: &'static str) {
        if !registered_boss(chr) {
            return;
        }
        // A nearby helper rejection must not consume the diagnostic budget for
        // the encounter the player is actually fighting. No unready modules read.
        if snapshot.rejected_roles.len() >= 8 {
            snapshot.rejected_roles.pop();
        }
        snapshot.rejected_roles.push(RejectedTarget {
            handle: handle(chr),
            npc_id: chr.npc_id,
            npc_param_id: chr.npc_param_id,
            position_havok: None,
            reason,
            eligibility: None,
            rejected_shapes: None,
        });
    }

    /// # Safety
    /// Exact executable/version guard, game-task phase and foreground/menu/transition
    /// authorization are the caller's responsibility. Hold no mutable SDK references.
    /// Call after the final camera is applied; do not retain this as a hit receipt.
    pub unsafe fn target_snapshot(reach_m: f32) -> Result<TargetSnapshot, &'static str> {
        unsafe { snapshot(reach_m, MAX_NEARBY_M, false) }
    }
    /// Shared-world publication; tolerates a lagging native camera (see view_origin).
    pub unsafe fn target_snapshot_with_radius(
        reach_m: f32,
        nearby_m: f32,
    ) -> Result<TargetSnapshot, &'static str> {
        unsafe { snapshot(reach_m, nearby_m, true) }
    }
    unsafe fn snapshot(
        reach_m: f32,
        nearby_m: f32,
        eye_fallback: bool,
    ) -> Result<TargetSnapshot, &'static str> {
        if !valid_reach(reach_m) {
            return Err("target camera ray must be greater than zero and at most ten metres");
        }
        if !nearby_m.is_finite() || !(1.0..=64.0).contains(&nearby_m) {
            return Err("target nearby radius invalid");
        }
        let game = unsafe { GameMan::instance() }.map_err(|_| "target game unavailable")?;
        let session =
            unsafe { CSSessionManager::instance() }.map_err(|_| "target session unavailable")?;
        if game.is_in_online_mode
            || game.warp_requested
            || session.lobby_state != LobbyState::None
            || session.protocol_state != ProtocolState::None
        {
            return Err("target offline gate closed");
        }
        let player =
            unsafe { PlayerIns::local_player() }.map_err(|_| "target player unavailable")?;
        // The attacker need not be damageable (for example during a roll), but
        // must be the currently active local character in this physics phase.
        if !player.chr_ins.chr_flags1c8.is_active()
            || !player.chr_ins.chr_flags1c8.update_tasks_registered()
            || player.chr_ins.chr_flags1c5.death_flag()
            || player.chr_ins.modules.data.hp <= 0
            || player.current_block_id.0 == -1
        {
            return Err("target player inactive");
        }
        if !unsafe { player.chr_ins.chr_set_entry.as_ref() }
            .chr_ins
            .is_some_and(|p| std::ptr::eq(p.as_ptr(), &player.chr_ins))
        {
            return Err("target player entry mismatch");
        }
        let world = unsafe { WorldChrMan::instance() }.map_err(|_| "target world unavailable")?;
        let camera = unsafe { CSCamera::instance() }.map_err(|_| "target camera unavailable")?;
        let eye = camera.pers_cam_1.position();
        let direction = camera.pers_cam_1.forward();
        let forward = normalize([direction.0, direction.1, direction.2])
            .ok_or("target camera basis invalid")?;
        let p = player.chr_ins.modules.physics.position;
        let feet = [p.0, p.1, p.2];
        let origin = view_origin([eye.0, eye.1, eye.2], feet, eye_fallback)?;
        let source = unsafe { entries(&world.chr_inses_by_distance) }?;
        // Resolve the dedicated registrations on this same game task. Do not
        // depend on HUD visibility or native lock-on to discover major bosses.
        let bosses = unsafe { CSFeManImp::instance() }
            .ok()
            .map(|frontend| {
                frontend
                    .boss_health_displays
                    .iter()
                    .filter(|display| display.fmg_id > 0 && !display.field_ins_handle.is_empty())
                    .filter_map(|display| {
                        world
                            .chr_ins_by_handle(&display.field_ins_handle)
                            .filter(|chr| chr.field_ins_handle == display.field_ins_handle)
                    })
                    .map(std::ptr::NonNull::from)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let mut snapshot = TargetSnapshot {
            sampled_tick_ms: unsafe { GetTickCount64() },
            current_block_id: player.current_block_id.0,
            player_handle: handle(&player.chr_ins),
            player_team: player.chr_ins.team_type,
            player_havok: feet,
            camera_origin_havok: origin,
            forward,
            reach_m,
            scanned: 0,
            source_count: source.len() + bosses.len(),
            truncated: source.len() > MAX_SCANNED,
            candidates: Vec::with_capacity(MAX_TARGETS),
            rejected_roles: Vec::new(),
            nearest: None,
            ray: unavailable_ray(),
            ground_ray: unavailable_ray(),
            damage_authorized: false,
        };
        for source in scan_sources(source.iter().map(|entry| entry.chr_ins), bosses) {
            snapshot.scanned += 1;
            let ptr = source.as_ptr();
            if std::ptr::eq(ptr, &player.chr_ins) {
                continue;
            }
            let chr = unsafe { &*ptr };
            let id = chr.field_ins_handle;
            if id.is_empty() || id.selector.field_ins_type() != Some(FieldInsType::Chr) {
                continue;
            }
            let Some(resolved) = world.chr_ins_by_handle(&id) else {
                continue;
            };
            if !std::ptr::eq(chr, resolved) {
                reject_boss(&mut snapshot, chr, "target_handle_identity_mismatch");
                continue;
            }
            if let Some(reason) = readiness_rejection(chr) {
                reject_boss(&mut snapshot, chr, reason);
                continue;
            }
            let entry = unsafe { chr.chr_set_entry.as_ref() };
            if !entry.chr_ins.is_some_and(|p| std::ptr::eq(p.as_ptr(), chr)) {
                reject_boss(&mut snapshot, chr, "target_set_entry_mismatch");
                continue;
            }
            let physics = &chr.modules.physics;
            let p = physics.position;
            let position = [p.0, p.1, p.2];
            if !finite(position) {
                reject_boss(&mut snapshot, chr, "target_position_invalid");
                continue;
            }
            let eligibility = eligibility(chr, snapshot.player_team);
            if let Some(reason) = eligibility.rejection() {
                if eligibility.boss_registered && snapshot.rejected_roles.len() >= 8 {
                    snapshot.rejected_roles.pop();
                }
                if snapshot.rejected_roles.len() < 8 {
                    snapshot.rejected_roles.push(RejectedTarget {
                        handle: handle(chr),
                        npc_id: chr.npc_id,
                        npc_param_id: chr.npc_param_id,
                        position_havok: Some(position),
                        reason,
                        eligibility: Some(eligibility),
                        rejected_shapes: None,
                    });
                }
                continue;
            }
            let character_shape = shape(physics.chr_hit_height, physics.chr_hit_radius);
            let map_shape = shape(physics.hit_height, physics.hit_radius);
            // Some scripted characters use specialized native collision and
            // leave the upright instance proxy empty. Their authored NpcParam
            // dimensions still provide a bounded Minecraft attack box.
            let authored = (character_shape.is_none() && map_shape.is_none())
                .then(|| unsafe { SoloParamRepository::instance() }.ok())
                .flatten()
                .and_then(|repo| repo.get::<NpcParam>(chr.npc_param_id as u32));
            let npc_character_shape =
                authored.and_then(|row| shape(row.chr_hit_height(), row.chr_hit_radius()));
            let npc_map_shape = authored.and_then(|row| shape(row.hit_height(), row.hit_radius()));
            let Some((shape_source, [height, radius])) = select_authored_shape(
                character_shape,
                map_shape,
                npc_character_shape,
                npc_map_shape,
            ) else {
                if eligibility.boss_registered && snapshot.rejected_roles.len() >= 8 {
                    snapshot.rejected_roles.pop();
                }
                if snapshot.rejected_roles.len() < 8 {
                    snapshot.rejected_roles.push(RejectedTarget {
                        handle: handle(chr),
                        npc_id: chr.npc_id,
                        npc_param_id: chr.npc_param_id,
                        position_havok: Some(position),
                        reason: "target_shape_invalid",
                        eligibility: Some(eligibility),
                        rejected_shapes: Some(RejectedShapes {
                            character: [physics.chr_hit_height, physics.chr_hit_radius],
                            map: [physics.hit_height, physics.hit_radius],
                            npc_character: authored
                                .map(|row| [row.chr_hit_height(), row.chr_hit_radius()]),
                            npc_map: authored.map(|row| [row.hit_height(), row.hit_radius()]),
                        }),
                    });
                }
                continue;
            };
            if snapshot.candidates.iter().any(|c| c.handle == handle(chr)) {
                continue;
            }
            // Publish the same forgiving box used for both selection and visibility.
            // The debugger and Minecraft receive these exact bounds, no extra guest padding.
            let (proxy_min_havok, proxy_max_havok) =
                combat_bounds(position, height, radius, hitbox_padding());
            // An ankle/body can be in reach while a giant's physics origin is
            // outside the nearby radius. Cull and prioritize by the published
            // surface, using the same geometry as picking and damage receipts.
            let distance = distance_to_bounds(feet, proxy_min_havok, proxy_max_havok);
            if distance > nearby_m {
                reject_boss(&mut snapshot, chr, "target_bounds_out_of_range");
                continue;
            }
            let (hp, max_hp) = crate::boss_damage_links::published_health(chr)
                .unwrap_or((chr.modules.data.hp, chr.modules.data.max_hp));
            snapshot.candidates.push(Candidate {
                handle: handle(chr),
                instance_token: ptr as usize,
                npc_id: chr.npc_id,
                npc_param_id: chr.npc_param_id,
                team: chr.team_type,
                boss_registered: eligibility.boss_registered,
                position_havok: position,
                height,
                radius,
                shape_source,
                character_shape,
                map_shape,
                proxy_min_havok,
                proxy_max_havok,
                hp,
                max_hp,
                player_distance_m: distance,
                ray_entry_m: None,
                ray_obstructed: None,
                los_clear: None,
                los_distance_m: 0.0,
                los: unavailable_ray(),
            });
        }
        // Bound per-candidate ray queries to the published list before any calls.
        finalize(&mut snapshot);
        if let Ok(havok) = unsafe { CSHavokMan::instance() } {
            let hit = havok
                .phys_world
                .cast_ray(
                    EXPERIMENTAL_RAY_FILTER,
                    &HavokPosition::from_xyz(origin[0], origin[1], origin[2]),
                    PositionDelta(
                        forward[0] * reach_m,
                        forward[1] * reach_m,
                        forward[2] * reach_m,
                    ),
                    player,
                )
                .map(|p| [p.0, p.1, p.2]);
            snapshot.ray = ray_diagnostic(origin, forward, reach_m, hit);
            let hit = havok
                .phys_world
                .cast_ray(
                    EXPERIMENTAL_RAY_FILTER,
                    &HavokPosition::from_xyz(origin[0], origin[1], origin[2]),
                    PositionDelta(0.0, -GROUND_RAY_M, 0.0),
                    player,
                )
                .map(|p| [p.0, p.1, p.2]);
            snapshot.ground_ray = ray_diagnostic(origin, [0.0, -1.0, 0.0], GROUND_RAY_M, hit);
            for candidate in &mut snapshot.candidates {
                let destination =
                    closest_point(origin, candidate.proxy_min_havok, candidate.proxy_max_havok);
                let delta = sub(destination, origin);
                let distance = dot(delta, delta).sqrt();
                candidate.los_distance_m = distance;
                if contained_visibility(candidate, origin, forward) {
                    continue;
                }
                if !distance.is_finite() || distance <= 0.0001 || distance > MAX_REACH_M {
                    continue;
                }
                let direction = delta.map(|v| v / distance);
                let hit = havok
                    .phys_world
                    .cast_ray(
                        EXPERIMENTAL_RAY_FILTER,
                        &HavokPosition::from_xyz(origin[0], origin[1], origin[2]),
                        PositionDelta(delta[0], delta[1], delta[2]),
                        player,
                    )
                    .map(|p| [p.0, p.1, p.2]);
                candidate.los = ray_diagnostic(origin, direction, distance, hit);
                candidate.los_clear = match candidate.los.status {
                    RayStatus::Miss => Some(true),
                    RayStatus::Hit => candidate
                        .los
                        .hit_distance_m
                        .map(|hit| hit + 0.05 >= distance),
                    _ => None,
                };
            }
        }
        finalize(&mut snapshot);
        Ok(snapshot)
    }
}
#[cfg(windows)]
pub(crate) use live::readiness_rejection;
#[cfg(windows)]
pub(crate) use live::shield_attack;
#[cfg(windows)]
pub use live::{target_snapshot, target_snapshot_with_radius};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn damage_sources_share_owner_identity_without_needing_the_same_pointer() {
        // A caller character and a request projectile resolve to the same native
        // owner, even though their source object pointers differ.
        assert_eq!(consistent_owner(Some(7), Some(7)), Some(7));
        assert_eq!(consistent_owner(Some(7), None), Some(7));
        assert_eq!(consistent_owner(None, Some(7)), Some(7));
        assert_eq!(consistent_owner(Some(7), Some(8)), None);
        assert_eq!(consistent_owner::<usize>(None, None), None);
    }
    #[test]
    fn stationary_snow_wave_and_moving_projectiles_keep_their_approach_direction() {
        let victim = [0.; 3];
        let owner = [0., 0., 20.];
        assert_eq!(
            shield_direction(victim, owner, Some(([0., 0., 2.], [0.; 3]))),
            Some(([0., 0., -2.], "projectile_position"))
        );
        assert_eq!(
            shield_direction(victim, owner, Some((victim, [0., 0., -12.]))),
            Some(([0., 0., -12.], "projectile_velocity"))
        );
        assert_eq!(
            shield_direction(victim, owner, Some((victim, [0.; 3]))),
            Some(([0., 0., -20.], "stationary_projectile_owner"))
        );
        // Shooter position cannot turn a projectile behind the shield into a
        // frontal block, even when the shooter remains in front.
        assert_eq!(
            shield_direction(victim, owner, Some(([0., 0., -2.], [0., 0., 12.]))),
            Some(([0., 0., 2.], "projectile_position"))
        );
        assert_eq!(
            shield_direction(victim, [0., 0., -20.], None),
            Some(([0., 0., 20.], "character_position"))
        );
        for projectile in [
            ([f32::NAN, 0., 2.], [0.; 3]),
            ([0., 0., 2.], [f32::INFINITY, 0., 0.]),
            ([0., 0., 257.], [0.; 3]),
        ] {
            assert!(shield_direction(victim, owner, Some(projectile)).is_none());
        }
        assert!(shield_direction(victim, victim, None).is_none());
        assert!(shield_direction(victim, [0., 0., 257.], None).is_none());
    }

    #[test]
    fn giant_collision_is_admitted_by_body_surface_in_both_height_profiles() {
        // A large upright boss and its lower second-phase profile both exceed
        // the old ordinary-enemy limits. Its root is outside the 16m scan, but
        // the camera ray and player reach intersect the nearby body surface.
        for (height, radius) in [(28., 12.), (12., 12.)] {
            let (_, dimensions) =
                select_authored_shape(shape(height, radius), None, None, None).unwrap();
            assert_eq!(dimensions, [height, radius]);
            let position = [0., 0., 17.];
            let (min, max) = combat_bounds(position, height, radius, HitboxPadding::default());
            assert!(dot(position, position).sqrt() > MAX_NEARBY_M);
            assert!(distance_to_bounds([0.; 3], min, max) < PLAYER_TEST_REACH);
            assert!(ray_aabb([0., 1.6, 0.], [0., 0., 1.], min, max, PLAYER_TEST_REACH).is_some());
            assert!(distance_to_bounds([0., 0., -20.], min, max) > MAX_NEARBY_M);
        }
    }
    #[test]
    fn large_authored_fallback_stays_inside_guest_extent_limits() {
        assert_eq!(
            select_authored_shape(None, None, shape(28., 12.), None),
            Some((ShapeSource::NpcCharacterFallback, [28., 12.]))
        );
        let (min, max) = combat_bounds(
            [0.; 3],
            63.8,
            31.25,
            HitboxPadding {
                horizontal: 0.75,
                ..HitboxPadding::default()
            },
        );
        assert!((0..3).all(|i| max[i] - min[i] <= 64.));
        assert!(shape(63.81, 12.).is_none());
        assert!(shape(28., 31.26).is_none());
        assert!(shape(f32::INFINITY, 12.).is_none());
        assert_eq!(distance_to_bounds([0., 1., 0.], min, max), 0.);
    }
    const PLAYER_TEST_REACH: f32 = 6.;

    #[test]
    fn queued_hits_recheck_scripted_phase_protection_before_native_damage() {
        let vulnerable = TargetActivity {
            active: true,
            tasks_registered: true,
            dead: false,
            invincible: false,
            character_disabled: false,
            hit_disabled: false,
            delta_time: 1. / 60.,
        };
        assert_eq!(vulnerable.rejection(), None);
        // Each is a possible state after the preceding hit's native notification.
        for (current, reason) in [
            (
                TargetActivity {
                    active: false,
                    ..vulnerable
                },
                "target_inactive",
            ),
            (
                TargetActivity {
                    tasks_registered: false,
                    ..vulnerable
                },
                "target_tasks_unregistered",
            ),
            (
                TargetActivity {
                    dead: true,
                    ..vulnerable
                },
                "target_dead",
            ),
            (
                TargetActivity {
                    invincible: true,
                    ..vulnerable
                },
                "target_invincible",
            ),
            (
                TargetActivity {
                    character_disabled: true,
                    ..vulnerable
                },
                "target_character_disabled",
            ),
            (
                TargetActivity {
                    hit_disabled: true,
                    ..vulnerable
                },
                "target_hit_disabled",
            ),
        ] {
            assert_eq!(current.rejection(), Some(reason));
        }
        for delta_time in [f32::NAN, f32::INFINITY, -0.01, 0.251] {
            assert_eq!(
                TargetActivity {
                    delta_time,
                    ..vulnerable
                }
                .rejection(),
                Some("target_delta_time_invalid")
            );
        }
        // Zero-delta frames do not manufacture a permanent phase lock.
        assert_eq!(
            TargetActivity {
                delta_time: 0.,
                ..vulnerable
            }
            .rejection(),
            None
        );
        assert_eq!(vulnerable.rejection(), None);
    }
    fn ordinary_enemy() -> Eligibility {
        Eligibility {
            enemy_class: true,
            player_class: false,
            character_type: eldenring::cs::ChrType::Npc as i32,
            team: 6,
            player_team: 0,
            npc_param_present: true,
            boss_registered: false,
            boss_damage_linked: false,
            lock_distance: 20,
            lock_disabled: false,
        }
    }
    #[test]
    fn authored_shared_health_bodies_keep_their_boss_role_without_relaxing_team_or_class() {
        let body = Eligibility {
            boss_damage_linked: true,
            character_type: 1,
            lock_distance: 0,
            lock_disabled: true,
            ..ordinary_enemy()
        };
        assert_eq!(body.rejection(), None);
        assert_eq!(
            Eligibility {
                boss_damage_linked: false,
                ..body
            }
            .rejection(),
            Some("not_native_npc")
        );
        assert_eq!(
            Eligibility {
                team: body.player_team,
                ..body
            }
            .rejection(),
            Some("same_team")
        );
        assert_eq!(
            Eligibility {
                npc_param_present: false,
                ..body
            }
            .rejection(),
            Some("npc_param_missing")
        );
        assert_eq!(
            Eligibility {
                enemy_class: false,
                player_class: true,
                ..body
            }
            .rejection(),
            Some("not_enemy_class")
        );
    }
    #[test]
    fn registered_bosses_survive_missing_distance_entries_and_scan_limits() {
        let sources = scan_sources(0..MAX_SCANNED + 20, [MAX_SCANNED + 19, MAX_SCANNED + 30]);
        assert!(sources.contains(&(MAX_SCANNED + 19)));
        assert!(sources.contains(&(MAX_SCANNED + 30)));
        assert!(!sources.contains(&MAX_SCANNED));
        assert_eq!(sources.len(), MAX_SCANNED + 2);
        assert_eq!(scan_sources([1, 2, 2], [2, 3]), vec![1, 2, 3]);
        assert_eq!(scan_sources([], [24]), vec![24]);
    }
    #[test]
    fn crowded_encounters_keep_registered_boss_hitboxes_in_the_publication() {
        let mut s = snapshot();
        s.candidates = (1..=MAX_TARGETS as u32 + 10)
            .map(|id| candidate(id, id as f32 / 10.0))
            .collect();
        let mut boss = candidate(999, 15.0);
        boss.boss_registered = true;
        s.candidates.push(boss);
        finalize(&mut s);
        assert!(s.truncated);
        assert_eq!(s.candidates.len(), MAX_TARGETS);
        assert_eq!(s.candidates[0].handle.selector, 999);
        assert_eq!(s.candidates[1].handle.selector, 1);
        // Boss priority reserves publication capacity; ray selection still
        // chooses the closest intersecting ordinary target.
        assert_ne!(s.nearest, Some(0));
    }
    #[test]
    fn registered_bosses_do_not_require_npc_character_type_or_lock_on() {
        let boss = Eligibility {
            boss_registered: true,
            lock_distance: 0,
            lock_disabled: true,
            ..ordinary_enemy()
        };
        assert_eq!(boss.rejection(), None);
        // Margit's live, exact boss-health handle was rejected as
        // not_native_npc. Encounter registration establishes its combat role;
        // the PvP/PvE appearance type must not hide its Minecraft hitbox.
        for character_type in [-1, eldenring::cs::ChrType::Unk6 as i32, 999] {
            assert_eq!(
                Eligibility {
                    character_type,
                    ..boss
                }
                .rejection(),
                None
            );
            assert_eq!(
                Eligibility {
                    character_type,
                    boss_registered: false,
                    ..boss
                }
                .rejection(),
                Some("not_native_npc")
            );
        }
        for (target, reason) in [
            (
                Eligibility {
                    enemy_class: false,
                    ..boss
                },
                "not_enemy_class",
            ),
            (
                Eligibility {
                    team: boss.player_team,
                    ..boss
                },
                "same_team",
            ),
            (
                Eligibility {
                    npc_param_present: false,
                    ..boss
                },
                "npc_param_missing",
            ),
        ] {
            assert_eq!(target.rejection(), Some(reason));
        }
        assert_eq!(
            Eligibility {
                boss_registered: false,
                ..boss
            }
            .rejection(),
            Some("npc_not_lockable")
        );
    }
    #[test]
    fn registered_player_ins_npc_bosses_publish_pickable_hitboxes() {
        // Live Gideon: PlayerIns, type 5, team 6 versus the local player's team 1;
        // his exact boss-health registration is the positive combat-role evidence.
        let gideon = Eligibility {
            enemy_class: false,
            player_class: true,
            character_type: eldenring::cs::ChrType::Npc as i32,
            team: 6,
            player_team: 1,
            npc_param_present: true,
            boss_registered: true,
            boss_damage_linked: false,
            lock_distance: 0,
            lock_disabled: false,
        };
        assert_eq!(gideon.rejection(), None);
        let (_, [height, radius]) =
            select_authored_shape(shape(1.8, 0.3), None, None, None).unwrap();
        let (min, max) = combat_bounds([0., 0., 3.], height, radius, HitboxPadding::default());
        assert!(ray_aabb([0., 1.6, 0.], [0., 0., 1.], min, max, PLAYER_TEST_REACH).is_some());
        // Other players, neutral spell helpers and friendly NPCs are not
        // admitted merely because they share Gideon's native character class.
        for (target, reason) in [
            (
                Eligibility {
                    boss_registered: false,
                    ..gideon
                },
                "not_enemy_class",
            ),
            (
                Eligibility {
                    player_class: false,
                    ..gideon
                },
                "not_enemy_class",
            ),
            (Eligibility { team: 1, ..gideon }, "same_team"),
            (
                Eligibility {
                    npc_param_present: false,
                    ..gideon
                },
                "npc_param_missing",
            ),
        ] {
            assert_eq!(target.rejection(), Some(reason));
        }
    }
    #[test]
    fn fire_giant_combat_body_qualifies_without_its_phase_two_gauge_registration() {
        // Live 47600050: the gauge belongs to dormant 47601050, so this
        // attacking body is unregistered and reports native character type 7.
        let fire_giant = Eligibility {
            enemy_class: true,
            player_class: false,
            character_type: eldenring::cs::ChrType::Unk7 as i32,
            team: 33,
            player_team: 1,
            npc_param_present: true,
            boss_registered: false,
            boss_damage_linked: false,
            lock_distance: 100,
            lock_disabled: false,
        };
        assert_eq!(fire_giant.rejection(), None);
        // The ordinary path still applies every other gate to type 7.
        for (target, reason) in [
            (
                Eligibility {
                    enemy_class: false,
                    ..fire_giant
                },
                "not_enemy_class",
            ),
            (
                Eligibility {
                    team: 1,
                    ..fire_giant
                },
                "same_team",
            ),
            (
                Eligibility {
                    lock_distance: 0,
                    ..fire_giant
                },
                "npc_not_lockable",
            ),
            (
                Eligibility {
                    lock_disabled: true,
                    ..fire_giant
                },
                "native_lock_disabled",
            ),
        ] {
            assert_eq!(target.rejection(), Some(reason));
        }
    }
    #[test]
    fn authored_dimensions_keep_specialized_boss_collision_pickable() {
        // Empty instance shapes must not make a live registered boss vanish.
        let selected = select_authored_shape(
            shape(0.0, 0.0),
            shape(0.0, 0.0),
            shape(4.0, 0.9),
            shape(4.5, 1.0),
        );
        assert_eq!(
            selected,
            Some((ShapeSource::NpcCharacterFallback, [4.0, 0.9]))
        );
        let (_, [height, radius]) = selected.unwrap();
        let (min, max) = combat_bounds([0.0, 0.0, 3.0], height, radius, HitboxPadding::default());
        assert!(ray_aabb([0.0, 1.6, 0.0], [0.0, 0.0, 1.0], min, max, 10.0).is_some());
        assert_eq!(
            select_authored_shape(shape(2.0, 0.5), None, shape(4.0, 0.9), None),
            Some((ShapeSource::Character, [2.0, 0.5]))
        );
        assert_eq!(
            select_authored_shape(None, shape(2.5, 0.6), shape(4.0, 0.9), None),
            Some((ShapeSource::MapFallback, [2.5, 0.6]))
        );
        assert_eq!(
            select_authored_shape(None, None, shape(f32::NAN, 0.9), shape(5.0, 1.0)),
            Some((ShapeSource::NpcMapFallback, [5.0, 1.0]))
        );
        assert!(select_authored_shape(None, None, shape(0.0, 0.0), shape(64.0, 32.0)).is_none());
    }
    #[test]
    fn lagging_camera_falls_back_to_eye_only_for_world_publication() {
        let feet = [1.0, 2.0, 3.0];
        assert_eq!(
            view_origin([1.0, 3.6, 6.0], feet, false),
            Ok([1.0, 3.6, 6.0])
        );
        assert_eq!(
            view_origin([1.0, 3.6, 6.0], feet, true),
            Ok([1.0, 3.6, 6.0])
        );
        assert_eq!(
            view_origin([1.0, 2.0, 20.0], feet, false),
            Err("target camera origin rejected")
        );
        assert_eq!(
            view_origin([1.0, 2.0, 20.0], feet, true),
            Ok([1.0, 2.0 + FALLBACK_EYE_HEIGHT_M, 3.0])
        );
        assert_eq!(
            view_origin([f32::NAN, 0.0, 0.0], feet, true),
            Ok([1.0, 2.0 + FALLBACK_EYE_HEIGHT_M, 3.0])
        );
        assert!(view_origin([0.0; 3], [f32::INFINITY, 0.0, 0.0], true).is_err());
    }
    #[test]
    fn noncombat_characters_are_not_targets_even_with_health_and_a_different_team() {
        let enemy = ordinary_enemy();
        assert_eq!(enemy.rejection(), None);
        assert_eq!(
            Eligibility {
                enemy_class: false,
                ..enemy
            }
            .rejection(),
            Some("not_enemy_class")
        );
        for kind in [
            eldenring::cs::ChrType::BonfireGhost as i32,
            eldenring::cs::ChrType::MessageGhost as i32,
            eldenring::cs::ChrType::BloodstainGhost as i32,
            -1,
            999,
        ] {
            assert_eq!(
                Eligibility {
                    character_type: kind,
                    ..enemy
                }
                .rejection(),
                Some("not_native_npc")
            );
        }
        assert_eq!(
            Eligibility {
                npc_param_present: false,
                ..enemy
            }
            .rejection(),
            Some("npc_param_missing")
        );
        assert_eq!(
            Eligibility {
                lock_distance: 0,
                ..enemy
            }
            .rejection(),
            Some("npc_not_lockable")
        );
    }
    #[test]
    fn native_targetability_is_independent_of_aggression_distance_or_enemy_id() {
        let enemy = ordinary_enemy();
        // Sleeping/idle enemies remain eligible; no battle-state or current AI
        // target is required, and lock distance does not limit MC projectile reach.
        for distance in [1, 20, 255] {
            assert_eq!(
                Eligibility {
                    lock_distance: distance,
                    ..enemy
                }
                .rejection(),
                None
            );
        }
        assert_eq!(
            Eligibility { team: 0, ..enemy }.rejection(),
            Some("same_team")
        );
        assert_eq!(
            Eligibility {
                lock_disabled: true,
                ..enemy
            }
            .rejection(),
            Some("native_lock_disabled")
        );
    }
    fn candidate(id: u32, z: f32) -> Candidate {
        let position = [0.0, 0.0, z];
        let (min, max) = proxy_bounds(position, 1.8, 0.5);
        Candidate {
            handle: Handle {
                selector: id,
                block_id: 42,
            },
            instance_token: 0x12345678,
            npc_id: 1000,
            npc_param_id: 100000,
            team: 6,
            boss_registered: false,
            position_havok: position,
            height: 1.8,
            radius: 0.5,
            proxy_min_havok: min,
            proxy_max_havok: max,
            shape_source: ShapeSource::Character,
            character_shape: Some([1.8, 0.5]),
            map_shape: Some([2.0, 0.6]),
            hp: 100,
            max_hp: 100,
            player_distance_m: z,
            ray_entry_m: None,
            ray_obstructed: None,
            los_clear: None,
            los_distance_m: 0.0,
            los: unavailable_ray(),
        }
    }
    fn snapshot() -> TargetSnapshot {
        TargetSnapshot {
            sampled_tick_ms: 1000,
            current_block_id: 42,
            player_handle: Handle {
                selector: 1,
                block_id: 42,
            },
            player_team: 0,
            player_havok: [0.0; 3],
            camera_origin_havok: [0.0, 1.0, 0.0],
            forward: [0.0, 0.0, 1.0],
            reach_m: 3.0,
            scanned: 0,
            source_count: 0,
            truncated: false,
            candidates: vec![],
            rejected_roles: vec![],
            nearest: None,
            ray: ray_diagnostic([0.0, 1.0, 0.0], [0.0, 0.0, 1.0], 3.0, None),
            ground_ray: unavailable_ray(),
            damage_authorized: false,
        }
    }
    #[test]
    fn nearest_surface_and_range() {
        assert_eq!(
            ray_capsule(
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 0.0, 3.0],
                1.8,
                0.5,
                3.0
            ),
            Some(2.5)
        );
        assert_eq!(
            ray_capsule(
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 0.0, 4.0],
                1.8,
                0.5,
                3.0
            ),
            None
        );
        assert_eq!(
            ray_capsule(
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 0.0, -3.0],
                1.8,
                0.5,
                3.0
            ),
            None
        );
    }
    #[test]
    fn caps_tangent_inside_and_vertical_rays() {
        assert_eq!(
            ray_capsule([0.0, 4.0, 0.0], [0.0, -1.0, 0.0], [0.0; 3], 2.0, 0.5, 3.0),
            Some(2.0)
        );
        assert_eq!(
            ray_capsule(
                [0.5, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 0.0, 2.0],
                2.0,
                0.5,
                3.0
            ),
            Some(2.0)
        );
        assert_eq!(
            ray_capsule([0.0, 1.0, 0.0], [0.0, 1.0, 0.0], [0.0; 3], 2.0, 0.5, 3.0),
            Some(0.0)
        );
        assert_eq!(
            ray_capsule([1.0, 4.0, 0.0], [0.0, -1.0, 0.0], [0.0; 3], 2.0, 0.5, 3.0),
            None
        );
    }
    #[test]
    fn rejects_nonfinite_bad_shape_and_unbounded_reach() {
        for reach in [0.0, -1.0, 10.01, f32::NAN, f32::INFINITY] {
            assert!(!valid_reach(reach));
        }
        assert!(valid_reach(10.0));
        for (height, radius) in [(f32::NAN, 0.5), (2.0, -1.0), (64.0, 0.5), (2.0, 32.0)] {
            assert!(
                ray_capsule(
                    [0.0; 3],
                    [0.0, 0.0, 1.0],
                    [0.0, 0.0, 2.0],
                    height,
                    radius,
                    3.0
                )
                .is_none()
            );
        }
        assert!(
            ray_capsule(
                [f32::NAN, 0.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0; 3],
                2.0,
                0.5,
                3.0
            )
            .is_none()
        );
        assert!(ray_capsule([0.0; 3], [0.0, 0.0, 2.0], [0.0; 3], 2.0, 0.5, 3.0).is_none());
    }
    #[test]
    fn collision_result_must_lie_on_bounded_ray() {
        let origin = [0.0; 3];
        let forward = [0.0, 0.0, 1.0];
        assert_eq!(
            ray_diagnostic(origin, forward, 3.0, None).status,
            RayStatus::Miss
        );
        assert_eq!(
            ray_diagnostic(origin, forward, 3.0, Some([0.0, 0.0, 2.0])).hit_distance_m,
            Some(2.0)
        );
        for point in [
            [0.0, 0.0, -1.0],
            [0.0, 0.0, 4.0],
            [0.5, 0.0, 2.0],
            [f32::NAN, 0.0, 1.0],
        ] {
            assert_eq!(
                ray_diagnostic(origin, forward, 3.0, Some(point)).status,
                RayStatus::InvalidResult
            );
        }
    }
    #[test]
    fn translated_coordinates_do_not_change_hit_distance() {
        let d = ray_capsule(
            [100.0, 101.0, -200.0],
            [0.0, 0.0, 1.0],
            [100.0, 100.0, -197.0],
            1.8,
            0.5,
            3.0,
        );
        assert_eq!(d, Some(2.5));
    }
    #[test]
    fn aabb_pick_matches_proxy_edges_instead_of_capsule_surface() {
        let (min, max) = proxy_bounds([0.0, 0.0, 2.0], 1.8, 0.5);
        assert_eq!(
            ray_aabb([0.49, 1.0, 0.0], [0.0, 0.0, 1.0], min, max, 3.0),
            Some(1.5)
        );
        assert!(
            ray_capsule(
                [0.49, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 0.0, 2.0],
                1.8,
                0.5,
                3.0
            )
            .unwrap()
                > 1.8
        );
        assert_eq!(
            ray_aabb([0.51, 1.0, 0.0], [0.0, 0.0, 1.0], min, max, 3.0),
            None
        );
        assert_eq!(
            ray_aabb([0.0, 1.0, 2.0], [0.0, 0.0, 1.0], min, max, 3.0),
            Some(0.0)
        );
        assert_eq!(
            ray_aabb([0.0, 1.0, 0.0], [0.0, 0.0, -1.0], min, max, 3.0),
            None
        );
    }
    #[test]
    fn visibility_destination_is_nearest_box_point_not_center() {
        let (min, max) = proxy_bounds([0.0, 0.0, 2.0], 1.8, 0.5);
        assert_eq!(closest_point([0.0, 1.0, 0.0], min, max), [0.0, 1.0, 1.5]);
        assert_eq!(closest_point([2.0, 3.0, 0.0], min, max), [0.5, 1.8, 1.5]);
        assert_eq!(closest_point([0.0, 1.0, 2.0], min, max), [0.0, 1.0, 2.0]);
        assert_eq!(
            ray_aabb([0.0; 3], [0.0, 0.0, 1.0], [0.0; 3], [0.0; 3], 3.0),
            None
        );
    }
    #[test]
    fn nearest_list_is_bounded_and_addresses_are_not_serialized() {
        let mut s = snapshot();
        s.candidates = (2..52)
            .rev()
            .map(|i| candidate(i, i as f32 / 10.0))
            .collect();
        finalize(&mut s);
        assert_eq!(s.candidates.len(), MAX_TARGETS);
        assert!(s.truncated);
        assert_eq!(s.candidates.first().unwrap().handle.selector, 2);
        assert_eq!(s.candidates.last().unwrap().handle.selector, 33);
        let json = serde_json::to_string(&s).unwrap();
        assert!(!json.contains("instance_token"));
        assert!(!json.contains("305419896"));
        assert!(!s.damage_authorized);
    }
    #[test]
    fn obstruction_is_diagnostic_and_never_authorizes_damage() {
        let mut s = snapshot();
        s.candidates = vec![candidate(3, 2.5), candidate(2, 2.0)];
        s.ray = ray_diagnostic(
            s.camera_origin_havok,
            s.forward,
            s.reach_m,
            Some([0.0, 1.0, 1.0]),
        );
        finalize(&mut s);
        assert_eq!(s.nearest, Some(0));
        assert_eq!(s.candidates[0].ray_entry_m, Some(1.5));
        assert_eq!(s.candidates[0].ray_obstructed, Some(true));
        assert!(!s.damage_authorized);
        s.ray.status = RayStatus::Unavailable;
        finalize(&mut s);
        assert_eq!(s.candidates[0].ray_obstructed, None);
        s.ray = ray_diagnostic(s.camera_origin_havok, s.forward, s.reach_m, None);
        finalize(&mut s);
        assert_eq!(s.candidates[0].ray_obstructed, Some(false));
        assert!(!s.damage_authorized);
    }
    #[test]
    fn native_total_height_stays_above_feet_without_extra_radius() {
        let (min, max) = proxy_bounds([10.0, 20.0, 30.0], 2.0, 0.5);
        assert_eq!(min, [9.5, 20.0, 29.5]);
        assert_eq!(max, [10.5, 22.0, 30.5]);
        assert_eq!(
            ray_aabb([10.0, 22.25, 28.0], [0.0, 0.0, 1.0], min, max, 3.0),
            None
        );
        assert_eq!(
            ray_aabb([10.0, 19.75, 28.0], [0.0, 0.0, 1.0], min, max, 3.0),
            None
        );
        assert_eq!(
            ray_capsule([0.0, -1.0, 0.0], [0.0, 1.0, 0.0], [0.0; 3], 2.0, 0.5, 3.0),
            Some(1.0)
        );
        let (min, max) = proxy_bounds([0.; 3], 0.5, 1.0);
        assert_eq!(min[1], 0.0);
        assert_eq!(max[1], 0.5);
        assert!(ray_capsule([0., 1., 0.], [0., -1., 0.], [0.; 3], 0.5, 1., 3.).is_none());
    }
    #[test]
    fn character_dimensions_take_priority_and_invalid_pairs_fail_closed() {
        assert_eq!(
            select_shape(shape(1.9, 0.4), shape(3.0, 0.8)),
            Some((ShapeSource::Character, [1.9, 0.4]))
        );
        assert_eq!(
            select_shape(shape(f32::NAN, 0.4), shape(3.0, 0.8)),
            Some((ShapeSource::MapFallback, [3.0, 0.8]))
        );
        assert!(select_shape(shape(1.9, 0.0), shape(3.0, f32::INFINITY)).is_none());
    }
    #[test]
    fn downward_filter_diagnostic_is_bounded_and_does_not_change_target_admission() {
        let mut s = snapshot();
        s.candidates = vec![candidate(2, 2.0)];
        finalize(&mut s);
        let old = (
            s.nearest,
            s.candidates[0].ray_entry_m,
            s.candidates[0].ray_obstructed,
        );
        s.ground_ray = ray_diagnostic(
            s.camera_origin_havok,
            [0., -1., 0.],
            MAX_REACH_M,
            Some([0., 0., 0.]),
        );
        assert_eq!(s.ground_ray.hit_distance_m, Some(1.0));
        finalize(&mut s);
        assert_eq!(
            old,
            (
                s.nearest,
                s.candidates[0].ray_entry_m,
                s.candidates[0].ray_obstructed
            )
        );
        assert!(!s.damage_authorized);
        assert_eq!(
            ray_diagnostic(
                [0., 1., 0.],
                [0., -1., 0.],
                GROUND_RAY_M,
                Some([0., -6., 0.])
            )
            .status,
            RayStatus::InvalidResult
        );
    }
    #[test]
    fn camera_inside_proxy_has_zero_distance_visibility_instead_of_disappearing() {
        let mut c = candidate(2, 2.0);
        let origin = [0., 1., 2.];
        let forward = [0., 0., 1.];
        assert_eq!(
            ray_aabb(origin, forward, c.proxy_min_havok, c.proxy_max_havok, 3.),
            Some(0.)
        );
        assert!(contained_visibility(&mut c, origin, forward));
        assert_eq!(c.los_clear, Some(true));
        assert_eq!(c.los_distance_m, 0.);
        assert_eq!(c.los.status, RayStatus::Miss);
        let mut outside = candidate(2, 2.0);
        assert!(!contained_visibility(
            &mut outside,
            [0., 1., 1.49999],
            forward
        ));
        assert_eq!(outside.los_clear, None); // Tiny positive segments still need the normal query policy.
    }
    #[test]
    fn forgiving_bounds_accept_near_edges_without_unbounded_padding() {
        let (raw_min, raw_max) = proxy_bounds([0., 0., 2.], 1.8, 0.3);
        let (min, max) = combat_bounds([0., 0., 2.], 1.8, 0.3, HitboxPadding::default());
        assert!(ray_aabb([0.45, 1., 0.], [0., 0., 1.], raw_min, raw_max, 3.).is_none());
        assert!(ray_aabb([0.45, 1., 0.], [0., 0., 1.], min, max, 3.).is_some());
        assert!(ray_aabb([0.49, 1., 0.], [0., 0., 1.], min, max, 3.).is_none());
        assert!(ray_aabb([0., 1.9, 0.], [0., 0., 1.], min, max, 3.).is_some());
        assert!(ray_aabb([0., 2.0, 0.], [0., 0., 1.], min, max, 3.).is_none());
        for bad in ["NaN", "inf", "-1", "0.751", "oops"] {
            assert_eq!(bounded_padding(Some(bad), 0.18), 0.18);
        }
        assert_eq!(bounded_padding(Some("0"), 0.18), 0.);
        assert_eq!(bounded_padding(Some("0.4"), 0.18), 0.4);
        // Obstruction still precedes the padded entry; padding is not a wall bypass.
        assert!(ray_aabb([0., 1., 0.], [0., 0., 1.], min, max, 3.).unwrap() > 1.0 + 0.05);
    }
}
