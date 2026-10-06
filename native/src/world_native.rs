//! Native shared-world geometry adapter for the pinned offline executable.
//! Terrain uses bounded native queries. Optional exact-build static colliders
//! consume copied guest shapes; first live acceptance is explicitly opt-in.
//! Actor ownership is handled separately by the shared-world bridge.

use crate::worldterrain::{self, Box6, Cache};
use serde::Serialize;

pub const RAY_FILTER: u32 = 0x0200_0058;
const SAMPLE_INTERVAL_MS: u64 = 50;
/// PlayerIns.block_position can trail the Havok body by one simulation step.
/// At glide/fall speeds (30+ m/s) that skew exceeds 0.5 m and used to suspend
/// the whole shared world mid-flight. A wrong block center is off by a map
/// tile (hundreds of metres), so a few metres still rejects real mismatches.
const BLOCK_REFERENCE_TOLERANCE_M: f64 = 4.0;

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CoordinateMode {
    BlockCenter,
    PlayerReference,
}

#[derive(Clone, Copy, Debug, Serialize)]
pub struct Features {
    pub terrain_filter_experimental: bool,
    pub terrain_sampled_surfaces: bool,
    pub native_block_colliders: bool,
    pub native_mob_proxies: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct Snapshot {
    pub epoch: u64,
    /// Stable anchor block ID, not necessarily the player's current block.
    pub map: u32,
    pub source_map: u32,
    pub coordinate_mode: CoordinateMode,
    /// Current-block-local -> anchored region translation. ECHS consumers must
    /// add this before converting their camera/player pose into the MC island.
    pub source_to_region: [f64; 3],
    /// Havok -> region translation. Never persist this rebasing offset.
    pub offset: [f64; 3],
    pub feet: [f64; 3],
    pub camera: [f64; 3],
    pub forward: [f64; 3],
    pub grounded: bool,
    pub hp: i32,
    pub max_hp: i32,
    pub sampled_ms: u64,
    pub terrain_revision: u64,
    pub terrain_ready: bool,
    pub terrain_bounds: Box6,
    pub terrain_boxes: Vec<Box6>,
    /// Havok body material per terrain box (worldterrain::NO_MATERIAL unknown).
    pub terrain_materials: Vec<u16>,
    /// Elden Ring hit material (HitMtrlParam row) under the feet, -1 when airborne/unknown.
    pub ground_material: i32,
    /// Learned Havok body material -> hit material pairs.
    pub material_table: Vec<crate::surface::Pair>,
    pub terrain_scanned_cells: usize,
    pub terrain_total_cells: usize,
    pub terrain_truncated: bool,
    pub terrain_rays: usize,
    pub native_collider_status: Option<crate::native_colliders::Status>,
    pub native_collider_error: Option<&'static str>,
    pub features: Features,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Transform {
    pub offset: [f64; 3],
    pub source_to_region: [f64; 3],
}
impl Transform {
    /// SDK WorldBlockInfo.physics_center is the Havok position of the block
    /// center. Verify that independent player coordinates agree before use.
    pub fn from_centers(
        block_feet: [f64; 3],
        havok_feet: [f64; 3],
        current_center: [f64; 3],
        anchor_center: [f64; 3],
    ) -> Result<Self, &'static str> {
        for p in [block_feet, havok_feet, current_center, anchor_center] {
            if !finite_position(p) {
                return Err("world coordinate is nonfinite or unbounded");
            }
        }
        if (0..3).any(|i| {
            (block_feet[i] + current_center[i] - havok_feet[i]).abs() > BLOCK_REFERENCE_TOLERANCE_M
        }) {
            return Err("world block-center transform disagrees with player reference");
        }
        let source_to_region = std::array::from_fn(|i| current_center[i] - anchor_center[i]);
        if source_to_region.iter().any(|v| v.abs() > 4096.0) {
            return Err("world block centers too far apart");
        }
        Ok(Self {
            offset: anchor_center.map(|v| -v),
            source_to_region,
        })
    }
    pub fn player_reference(
        block_feet: [f64; 3],
        havok_feet: [f64; 3],
    ) -> Result<Self, &'static str> {
        if !finite_position(block_feet) || !finite_position(havok_feet) {
            return Err("world player reference invalid");
        }
        Ok(Self {
            offset: std::array::from_fn(|i| block_feet[i] - havok_feet[i]),
            source_to_region: [0.0; 3],
        })
    }
    pub fn to_region(self, p: [f64; 3]) -> [f64; 3] {
        std::array::from_fn(|i| p[i] + self.offset[i])
    }
    pub fn to_havok(self, p: [f64; 3]) -> [f64; 3] {
        std::array::from_fn(|i| p[i] - self.offset[i])
    }
}
fn finite_position(p: [f64; 3]) -> bool {
    p.iter().all(|v| v.is_finite() && v.abs() <= 1_000_000.0)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Identity {
    player: usize,
    selector: u32,
    world: usize,
}

pub struct Driver {
    epoch: u64,
    identity: Option<Identity>,
    anchor_map: Option<u32>,
    mode: Option<CoordinateMode>,
    terrain: Cache,
    last_sample_ms: u64,
    last_tick_ms: u64,
    block_revision: u64,
    desired_blocks: Vec<Box6>,
    guest_session: u64,
    guest_last_ms: u64,
    colliders_enabled: bool,
    colliders: Option<Result<crate::native_colliders::Driver, &'static str>>,
    collider_error: Option<&'static str>,
    /// Rich ray queries (normal + physical character filter) without owned colliders.
    rays: Option<Result<crate::native_colliders::Api, &'static str>>,
    surfaces: crate::surface::Learner,
    last_surface_ms: u64,
}
impl Default for Driver {
    fn default() -> Self {
        Self::new()
    }
}
impl Driver {
    pub fn new() -> Self {
        Self {
            epoch: 0,
            identity: None,
            anchor_map: None,
            mode: None,
            terrain: Cache::new(),
            last_sample_ms: 0,
            last_tick_ms: 0,
            block_revision: 0,
            desired_blocks: Vec::new(),
            guest_session: 0,
            guest_last_ms: 0,
            colliders_enabled: std::env::var("ELDENCRAFT_NATIVE_COLLIDERS").is_ok_and(|s| s == "1"),
            colliders: None,
            collider_error: None,
            rays: None,
            surfaces: Default::default(),
            last_surface_ms: 0,
        }
    }
    /// Do not reset persistent coordinate identity on temporary focus/menu loss.
    /// Called by the shared-world coordinator on its game task. Cleanup is
    /// bounded and retains unmatched/retired-world ownership for later retry.
    pub fn suspend(&mut self) {
        self.last_sample_ms = 0;
        self.guest_last_ms = 0;
        if let Some(Ok(c)) = self.colliders.as_mut() {
            self.collider_error = unsafe { c.suspend() }.err();
            self.terrain.refresh_boxes(&c.take_removed_boxes());
        }
    }
    #[cfg(test)]
    fn epoch(&self) -> u64 {
        self.epoch
    }
    pub fn set_blocks(
        &mut self,
        epoch: u64,
        revision: u64,
        boxes: &[Box6],
    ) -> Result<(), &'static str> {
        if epoch == 0 || epoch != self.epoch {
            return Err("block snapshot belongs to another world epoch");
        }
        if revision < self.block_revision {
            return Err("block snapshot revision moved backwards");
        }
        if boxes.len() > worldterrain::MAX_BOXES
            || boxes.iter().any(|b| !worldterrain::valid_box(*b))
        {
            return Err("block collision snapshot outside bounds");
        }
        let mut canonical = boxes.to_vec();
        canonical.sort_by(|a, b| a.partial_cmp(b).unwrap());
        if revision == self.block_revision && canonical != self.desired_blocks {
            return Err("block revision changed content");
        }
        self.block_revision = revision;
        self.desired_blocks = canonical;
        Ok(())
    }
    #[cfg(test)]
    fn desired_block_boxes(&self) -> &[Box6] {
        &self.desired_blocks
    }
    pub fn revoke_guest(&mut self) {
        self.guest_last_ms = 0;
    }
    /// The bridge owns freshness/PID checks and event processing. This method
    /// only accepts copied full shape/entity state for the matching region.
    /// Use the producer timestamp from the validated envelope. Re-reading a
    /// still-fresh publication must not extend its lifetime by another500ms.
    pub fn accept_guest_at(
        &mut self,
        guest: &crate::world_wire::Guest,
        produced_ms: u64,
    ) -> Result<(), &'static str> {
        let now = crate::world_transport::now();
        if produced_ms == 0 || now.checked_sub(produced_ms).is_none_or(|age| age > 500) {
            return Err("guest shape publication stale or future");
        }
        guest.validate()?;
        if guest.epoch != self.epoch || Some(guest.map) != self.anchor_map {
            return Err("guest world epoch/map mismatch");
        }
        if guest.terrain_revision > self.terrain.revision() {
            return Err("guest references future terrain revision");
        }
        let boxes: Vec<_> = guest
            .blocks
            .iter()
            .flat_map(|b| b.boxes.iter().copied())
            .collect();
        if self.guest_session != guest.session {
            self.block_revision = 0;
            self.desired_blocks.clear();
            self.guest_session = guest.session;
        }
        self.set_blocks(guest.epoch, guest.blocks_revision, &boxes)?;
        self.guest_last_ms = produced_ms;
        Ok(())
    }
    fn reset(&mut self, identity: Identity, map: u32, mode: CoordinateMode) {
        self.epoch = self.epoch.saturating_add(1).max(1);
        self.identity = Some(identity);
        self.anchor_map = Some(map);
        self.mode = Some(mode);
        self.terrain.clear();
        self.last_sample_ms = 0;
        self.block_revision = 0;
        self.desired_blocks.clear();
        self.guest_session = 0;
        self.guest_last_ms = 0;
    }
}

#[cfg(windows)]
mod live {
    use super::*;
    use eldenring::{
        cs::{
            BlockId, CSCamExt, CSCamera, CSHavokMan, CSMenuManImp, CSSessionManager, FieldArea,
            GameMan, LobbyState, PlayerIns, ProtocolState, WorldInfo,
        },
        position::{HavokPosition, PositionDelta},
    };
    use fromsoftware_shared::FromStatic;
    use std::ffi::c_void;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetTickCount64() -> u64;
        fn GetCurrentProcessId() -> u32;
    }
    #[link(name = "user32")]
    unsafe extern "system" {
        fn GetForegroundWindow() -> *mut c_void;
        fn GetWindowThreadProcessId(window: *mut c_void, pid: *mut u32) -> u32;
    }

    fn center(info: &WorldInfo, map: u32) -> Result<Option<[f64; 3]>, &'static str> {
        // The SDK convenience methods slice fixed arrays using these counts.
        if info.world_area_info_count > 28
            || info.world_grid_area_info_count > 6
            || info.world_block_info_count > 192
        {
            return Err("world block-info count rejected");
        }
        let Some(block) = info.world_block_info_by_map(&BlockId(map as i32)) else {
            return Ok(None);
        };
        if block.block_id.0 as u32 != map {
            return Err("world block lookup identity mismatch");
        }
        let p = block.physics_center;
        let p = [p.0 as f64, p.1 as f64, p.2 as f64];
        if !finite_position(p) {
            return Err("world block center invalid");
        }
        Ok(Some(p))
    }

    impl Driver {
        /// Bounded landing scans use the same owned-collider exclusion as the
        /// ordinary terrain cache, so breaking a block cannot leave a phantom
        /// terrain surface after adopting the scanned destination window.
        pub unsafe fn sample_landing_ray(
            &self,
            ray: worldterrain::Ray,
            offset: [f64; 3],
        ) -> Result<Option<[f64; 3]>, &'static str> {
            let o = std::array::from_fn(|i| ray.origin[i] - offset[i]);
            if !finite_position(o) {
                return Err("landing terrain origin rejected");
            }
            let p = if let Some(Ok(c)) = self.colliders.as_ref() {
                unsafe { c.terrain_ray(o, ray.delta, RAY_FILTER) }?
            } else {
                let havok =
                    unsafe { CSHavokMan::instance() }.map_err(|_| "landing Havok unavailable")?;
                let player = unsafe { PlayerIns::local_player() }
                    .map_err(|_| "landing ray owner unavailable")?;
                havok
                    .phys_world
                    .cast_ray(
                        RAY_FILTER,
                        &HavokPosition::from_xyz(o[0] as f32, o[1] as f32, o[2] as f32),
                        PositionDelta(
                            ray.delta[0] as f32,
                            ray.delta[1] as f32,
                            ray.delta[2] as f32,
                        ),
                        player,
                    )
                    .map(|p| [p.0 as f64, p.1 as f64, p.2 as f64])
            };
            Ok(p.map(|p| std::array::from_fn(|i| p[i] + offset[i])))
        }
        pub fn adopt_landing(
            &mut self,
            scene: &mut Snapshot,
            mut cache: Cache,
            feet: [f64; 3],
            now: u64,
        ) {
            cache.advance_revision_after(self.terrain.revision());
            let t = cache.snapshot();
            self.terrain = cache;
            self.last_sample_ms = now;
            scene.feet = feet;
            scene.grounded = false;
            scene.sampled_ms = now;
            scene.terrain_revision = t.revision;
            scene.terrain_ready = t.ready;
            scene.terrain_bounds = t.bounds;
            scene.terrain_boxes = t.boxes;
            scene.terrain_scanned_cells = t.scanned_cells;
            scene.terrain_total_cells = t.total_cells;
            scene.terrain_truncated = t.truncated;
            scene.terrain_rays = t.last_rays;
        }
        /// # Safety
        /// Call only from the authorized game task after the exact executable
        /// SHA guard. Caller must not retain mutable SDK references. No native
        /// data or pointer escapes this call; return value owns copied data.
        pub unsafe fn tick(&mut self) -> Result<Snapshot, &'static str> {
            let now = unsafe { GetTickCount64() };
            let mut pid = 0;
            unsafe {
                GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
            }
            if pid != unsafe { GetCurrentProcessId() } {
                return Err("world foreground gate closed");
            }
            let game = unsafe { GameMan::instance() }.map_err(|_| "world GameMan unavailable")?;
            let session =
                unsafe { CSSessionManager::instance() }.map_err(|_| "world session unavailable")?;
            if game.is_in_online_mode
                || game.warp_requested
                || session.lobby_state != LobbyState::None
                || session.protocol_state != ProtocolState::None
            {
                return Err("world offline/stable gate closed");
            }
            let menu = unsafe { CSMenuManImp::instance() }.map_err(|_| "world menu unavailable")?;
            if !unsafe { menu.system_announce_view_model.view.as_ref() }.is_active {
                return Err("world blocking menu open");
            }
            let player = unsafe { PlayerIns::local_player() }
                .map_err(|_| "world local player unavailable")?;
            let chr = &player.chr_ins;
            if !chr.chr_flags1c8.is_active()
                || !chr.chr_flags1c8.update_tasks_registered()
                || chr.chr_flags1c5.death_flag()
                || chr.modules.data.hp <= 0
                || chr.modules.data.max_hp <= 0
                || player.current_block_id.0 == -1
            {
                return Err("world local player inactive");
            }
            if !unsafe { chr.chr_set_entry.as_ref() }
                .chr_ins
                .is_some_and(|p| std::ptr::eq(p.as_ptr(), chr))
            {
                return Err("world local player identity mismatch");
            }
            let map = player.current_block_id.0 as u32;
            let block = player.block_position;
            let block_feet = [block.x as f64, block.y as f64, block.z as f64];
            let physics = &chr.modules.physics;
            let p = physics.position;
            let havok_feet = [p.0 as f64, p.1 as f64, p.2 as f64];
            let field = unsafe { FieldArea::instance() }.ok();
            let info = field
                .as_ref()
                .map(|f| &f.world_info_owner.world_res.world_info);
            let identity = Identity {
                player: chr as *const _ as usize,
                selector: chr.field_ins_handle.selector.0,
                world: info.map_or(0, |i| i as *const _ as usize),
            };
            let current_center = match info {
                Some(i) => center(i, map)?,
                None => None,
            };
            let mode = if current_center.is_some() {
                CoordinateMode::BlockCenter
            } else {
                CoordinateMode::PlayerReference
            };
            if self.identity != Some(identity)
                || self.mode != Some(mode)
                || self.anchor_map.is_none()
                || (mode == CoordinateMode::PlayerReference && self.anchor_map != Some(map))
            {
                self.reset(identity, map, mode);
            }
            let transform = if let (Some(info), Some(current)) = (info, current_center) {
                let anchor = match center(info, self.anchor_map.unwrap())? {
                    Some(anchor) => anchor,
                    None => {
                        self.reset(identity, map, mode);
                        current
                    }
                };
                Transform::from_centers(block_feet, havok_feet, current, anchor)?
            } else {
                Transform::player_reference(block_feet, havok_feet)?
            };
            let feet = transform.to_region(havok_feet);
            let camera = unsafe { CSCamera::instance() }.map_err(|_| "world camera unavailable")?;
            let p = camera.pers_cam_1.position();
            let camera = transform.to_region([p.0 as f64, p.1 as f64, p.2 as f64]);
            let cameras =
                unsafe { CSCamera::instance() }.map_err(|_| "world camera unavailable")?;
            let d = cameras.pers_cam_1.forward();
            let mut forward = [d.0 as f64, d.1 as f64, d.2 as f64];
            let len = forward.iter().map(|v| v * v).sum::<f64>().sqrt();
            if !finite_position(feet)
                || !finite_position(camera)
                || !len.is_finite()
                || !(0.5..=2.0).contains(&len)
            {
                return Err("world camera/feet invalid");
            }
            forward.iter_mut().for_each(|v| *v /= len);
            // Copy all player observations before body creation/destruction can
            // dispatch native listeners. No retained player reference is used
            // after the optional collider mutations below.
            let grounded = physics.standing_on_solid_ground || physics.touching_solid_ground;
            let hp = chr.modules.data.hp;
            let max_hp = chr.modules.data.max_hp;
            let ground_material = if grounded {
                physics.material_info.hit_material
            } else {
                -1
            };
            if self.colliders_enabled && self.colliders.is_none() {
                self.colliders = Some(crate::native_colliders::Driver::resolve());
            }
            let guest_fresh =
                self.guest_last_ms != 0 && now.saturating_sub(self.guest_last_ms) <= 500;
            if let Some(result) = self.colliders.as_mut() {
                match result {
                    Ok(c) => {
                        self.collider_error = if guest_fresh {
                            unsafe {
                                c.sync(
                                    self.epoch,
                                    transform.offset,
                                    havok_feet,
                                    &self.desired_blocks,
                                )
                            }
                            .err()
                        } else {
                            unsafe { c.suspend() }.err()
                        };
                        self.terrain.refresh_boxes(&c.take_removed_boxes());
                    }
                    Err(e) => self.collider_error = Some(*e),
                }
            }
            self.terrain.recenter(feet, now)?;
            if self.last_sample_ms == 0
                || now.saturating_sub(self.last_sample_ms) >= SAMPLE_INTERVAL_MS
            {
                if self.colliders.is_none() && self.rays.is_none() {
                    self.rays = Some(crate::native_colliders::Api::resolve());
                }
                let to_region = |h: Option<worldterrain::Hit>| {
                    h.map(|h| worldterrain::Hit {
                        point: transform.to_region(h.point),
                        normal: h.normal,
                        material: h.material,
                    })
                };
                if let Some(Ok(c)) = self.colliders.as_ref() {
                    // The BodyID-returning ray is essential once native blocks
                    // exist: never feed those surfaces back as permanent terrain.
                    // Sample what the player physically collides with; the
                    // diagnostic filter also reports query-only volumes in mid-air.
                    let filter = unsafe { c.character_filter() }.unwrap_or(RAY_FILTER);
                    self.terrain
                        .tick_hits(now, worldterrain::MAX_RAYS_PER_TICK, |ray| {
                            let o = transform.to_havok(ray.origin);
                            unsafe { c.terrain_hit(o, ray.delta, filter) }.map(to_region)
                        });
                } else if let Some((api, world)) = self
                    .rays
                    .as_ref()
                    .and_then(|r| r.as_ref().ok())
                    .and_then(|api| unsafe { api.world() }.ok().map(|world| (api, world)))
                {
                    let filter = unsafe { api.character_filter(world) }.unwrap_or(RAY_FILTER);
                    self.terrain
                        .tick_hits(now, worldterrain::MAX_RAYS_PER_TICK, |ray| {
                            let o = transform.to_havok(ray.origin);
                            if !finite_position(o) {
                                return Err("terrain native ray origin rejected");
                            }
                            unsafe { api.ray(world, o, ray.delta, filter) }.map(|h| {
                                to_region(h.map(|h| worldterrain::Hit {
                                    point: h.point,
                                    normal: Some(h.normal),
                                    material: unsafe { api.body_material(world, h.body) },
                                }))
                            })
                        });
                } else {
                    let havok =
                        unsafe { CSHavokMan::instance() }.map_err(|_| "world Havok unavailable")?;
                    let player = unsafe { PlayerIns::local_player() }
                        .map_err(|_| "world ray owner unavailable")?;
                    self.terrain
                        .tick(now, worldterrain::MAX_RAYS_PER_TICK, |ray| {
                            let o = transform.to_havok(ray.origin);
                            if !finite_position(o) {
                                return Err("terrain native ray origin rejected");
                            }
                            Ok(havok
                                .phys_world
                                .cast_ray(
                                    RAY_FILTER,
                                    &HavokPosition::from_xyz(o[0] as f32, o[1] as f32, o[2] as f32),
                                    PositionDelta(
                                        ray.delta[0] as f32,
                                        ray.delta[1] as f32,
                                        ray.delta[2] as f32,
                                    ),
                                    player,
                                )
                                .map(|p| transform.to_region([p.0 as f64, p.1 as f64, p.2 as f64])))
                        });
                }
                self.last_sample_ms = now;
            }
            // Learn body material -> hit material beside the feet (outside the capsule).
            if ground_material > 0 && now.saturating_sub(self.last_surface_ms) >= 250 {
                self.last_surface_ms = now;
                let probe = |api: &crate::native_colliders::Api| -> Option<u16> {
                    unsafe {
                        let world = api.world().ok()?;
                        let filter = api.character_filter(world).ok()?;
                        let origin = [havok_feet[0] + 0.6, havok_feet[1] + 0.5, havok_feet[2]];
                        let hit = api.ray(world, origin, [0., -1.5, 0.], filter).ok()??;
                        api.body_material(world, hit.body)
                    }
                };
                let material = match (self.colliders.as_ref(), self.rays.as_ref()) {
                    (Some(Ok(c)), _) => probe(c.api()),
                    (_, Some(Ok(api))) => probe(api),
                    _ => None,
                };
                if let Some(material) = material {
                    self.surfaces.observe(material, ground_material);
                }
            }
            self.last_tick_ms = now;
            let terrain = self.terrain.snapshot();
            let collider_status = self
                .colliders
                .as_ref()
                .and_then(|r| r.as_ref().ok())
                .map(|c| c.status());
            Ok(Snapshot {
                epoch: self.epoch,
                map: self.anchor_map.unwrap(),
                source_map: map,
                coordinate_mode: mode,
                source_to_region: transform.source_to_region,
                offset: transform.offset,
                feet,
                camera,
                forward,
                grounded,
                hp,
                max_hp,
                sampled_ms: now,
                terrain_revision: terrain.revision,
                terrain_ready: terrain.ready,
                terrain_bounds: terrain.bounds,
                terrain_boxes: terrain.boxes,
                terrain_materials: terrain.materials,
                ground_material,
                material_table: self.surfaces.table(),
                terrain_scanned_cells: terrain.scanned_cells,
                terrain_total_cells: terrain.total_cells,
                terrain_truncated: terrain.truncated,
                terrain_rays: terrain.last_rays,
                native_collider_status: collider_status,
                native_collider_error: self.collider_error,
                features: Features {
                    terrain_filter_experimental: true,
                    terrain_sampled_surfaces: true,
                    native_block_colliders: guest_fresh
                        && self.collider_error.is_none()
                        && collider_status.is_some_and(|s| s.complete && s.broadphase > 0),
                    native_mob_proxies: false,
                },
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn block_center_transform_survives_havok_rebase() {
        let a = Transform::from_centers(
            [4.0, 2.0, 8.0],
            [104.0, 12.0, 208.0],
            [100.0, 10.0, 200.0],
            [80.0, 10.0, 200.0],
        )
        .unwrap();
        let b = Transform::from_centers(
            [4.0, 2.0, 8.0],
            [14.0, 2.0, 18.0],
            [10.0, 0.0, 10.0],
            [-10.0, 0.0, 10.0],
        )
        .unwrap();
        assert_eq!(
            a.to_region([104.0, 12.0, 208.0]),
            b.to_region([14.0, 2.0, 18.0])
        );
        assert_eq!(a.source_to_region, b.source_to_region);
        assert_eq!(b.to_havok([24.0, 2.0, 8.0]), [14.0, 2.0, 18.0]);
    }
    #[test]
    fn adjacent_block_transition_preserves_anchor_coordinates() {
        let a = Transform::from_centers([98.0, 0.0, 0.0], [98.0, 0.0, 0.0], [0.0; 3], [0.0; 3])
            .unwrap();
        let b = Transform::from_centers(
            [-2.0, 0.0, 0.0],
            [98.0, 0.0, 0.0],
            [100.0, 0.0, 0.0],
            [0.0; 3],
        )
        .unwrap();
        assert_eq!(a.to_region([98.0, 0.0, 0.0]), b.to_region([98.0, 0.0, 0.0]));
        assert_eq!(b.source_to_region, [100.0, 0.0, 0.0]);
    }
    #[test]
    fn fast_motion_reference_skew_keeps_the_anchor_transform() {
        // One 60Hz step at 35 m/s: block-local feet still report the previous step.
        let t = Transform::from_centers(
            [10.0, 5.0, 0.0],
            [110.58, 5.0, 0.0],
            [100.0, 0.0, 0.0],
            [0.0; 3],
        )
        .unwrap();
        assert_eq!(t.offset, [0.0; 3]);
        assert_eq!(t.source_to_region, [100.0, 0.0, 0.0]);
        assert_eq!(t.to_region([110.58, 5.0, 0.0]), [110.58, 5.0, 0.0]);
    }
    #[test]
    fn transform_refuses_wrong_or_nonfinite_center() {
        assert!(Transform::from_centers([0.0; 3], [5.0, 0.0, 0.0], [0.0; 3], [0.0; 3]).is_err());
        assert!(Transform::from_centers([0.0; 3], [256.0, 0.0, 0.0], [0.0; 3], [0.0; 3]).is_err());
        assert!(Transform::player_reference([f64::NAN, 0.0, 0.0], [0.0; 3]).is_err());
    }
    #[test]
    fn block_desired_state_is_epoch_scoped_and_never_claims_installation() {
        let mut d = Driver::new();
        d.reset(
            Identity {
                player: 1,
                selector: 1,
                world: 1,
            },
            42,
            CoordinateMode::PlayerReference,
        );
        let b = [0.0, 0.0, 0.0, 1.0, 1.0, 1.0];
        assert!(d.set_blocks(d.epoch(), 1, &[b]).is_ok());
        assert!(d.set_blocks(d.epoch(), 1, &[]).is_err());
        assert!(d.set_blocks(d.epoch() + 1, 2, &[b]).is_err());
        d.suspend();
        assert_eq!(d.desired_block_boxes(), &[b]);
        d.reset(
            Identity {
                player: 2,
                selector: 2,
                world: 1,
            },
            42,
            CoordinateMode::PlayerReference,
        );
        assert!(d.desired_block_boxes().is_empty());
    }
}
