//! Exact-build, independently owned Havok static bodies for Minecraft boxes.
//!
//! The caller must pass the complete executable SHA gate and call only from the
//! offline game task, after Havok's update has joined. See the collider contract.
//! No game allocator is imitated: shape/Cinfo construction and body insertion /
//! destruction use matched native entrypoints. Nothing calls native code in Drop.

use crate::worldterrain::{Box6, valid_box};
use eldenring::cs::{CSHavokMan, CSSessionManager, GameMan, LobbyState, PlayerIns, ProtocolState};
use eldenring::position::{HavokPosition, PositionDelta};
use fromsoftware_shared::{FromStatic, program::Program};
use pelite::pe64::PeObject;
use serde::Serialize;
use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::c_void,
};

const INVALID_BODY: u32 = 0x00ff_ffff;
pub const MAX_BODIES: usize = 4096;
const CREATE_BUDGET: usize = 8;
const REMOVE_BUDGET: usize = 32;
const MAX_RETIRED_WORLDS: usize = 8;
const PROBE_INTERVAL_MS: u64 = 500;
const BODY_STRIDE: usize = 0xb0;
const CS_WORLD_VTABLE: usize = 0x2b96978;
const HK_WORLD_VTABLE: usize = 0x2ef0dd8;
// CsHkCharacterProxy derives from hknpCharacterProxy; its constructor replaces
// the base vtable after the native base constructor has initialized the fields.
const CHARACTER_PROXY_VTABLE: usize = 0x2b95260;
const CONSTRAINT_FILTER_VTABLE: usize = 0x2ef22f8;
const COLLISION_FILTER_VTABLE: usize = 0x2b94df0;
// hknpBody::setShape tests this before addReference/removeReference. Require
// that the body itself owns a shape ref before we may retire our independent one.
const BODY_SHAPE_REFS_RVA: usize = 0x3c19cc0;

// Validation metadata from the exact owned 2.7.1.0 executable. Full analysis is
// deliberately outside the repository; these short prefixes are not game code.
const ENTRYPOINTS: &[(usize, &[u8])] = &[
    // SDK owner-aware ray: native query.userData receives PlayerIns. The child
    // collision filter compares that identity before its family exclusion.
    (
        0xc71e00,
        &[
            0x40, 0x55, 0x56, 0x57, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41, 0x57, 0x48, 0x8d,
            0xac, 0x24,
        ],
    ),
    (
        0xc71f72,
        &[0x44, 0x89, 0x7c, 0x24, 0x54, 0x48, 0x89, 0x74, 0x24, 0x58],
    ),
    (
        0xc632aa,
        &[0x48, 0x8b, 0x40, 0x10, 0x48, 0x39, 0x46, 0x08, 0x75, 0x22],
    ),
    (
        0x18b4c8e,
        &[
            0x48, 0x8d, 0x05, 0x63, 0xd6, 0x63, 0x01, 0xc6, 0x43, 0x18, 0x01, 0x48, 0x89, 0x03,
            0x48, 0x8b, 0xc3,
        ],
    ),
    (
        0xc643a6,
        &[
            0xe8, 0x45, 0x05, 0xc4, 0x00, 0x90, 0x48, 0x8d, 0x05, 0xad, 0x0e, 0xf3, 0x01, 0x48,
            0x89, 0x03,
        ],
    ),
    (
        0xc5d3e0,
        &[
            0x48, 0x8b, 0x8f, 0x88, 0x00, 0x00, 0x00, 0xe8, 0xd4, 0x86, 0xc4, 0x00,
        ],
    ),
    (
        0xc72060,
        &[
            0x40, 0x55, 0x56, 0x57, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41, 0x57, 0x48, 0x8d,
            0x6c, 0x24, 0x80,
        ],
    ),
    (
        0x1882bb0,
        &[
            0x33, 0xd2, 0x48, 0x8d, 0x05, 0xb7, 0xad, 0x66, 0x01, 0x48, 0x89, 0x51, 0x20,
        ],
    ),
    (
        0x18836f0,
        &[
            0xf3, 0x0f, 0x11, 0x4c, 0x24, 0x10, 0x55, 0x53, 0x56, 0x57, 0x48, 0x8d, 0xac, 0x24,
            0x88, 0xf9, 0xff, 0xff,
        ],
    ),
    (
        0x1682ca0,
        &[
            0x40, 0x53, 0x48, 0x83, 0xec, 0x20, 0x48, 0x8d, 0x05, 0xcb, 0xec, 0x35, 0x01,
        ],
    ),
    (
        0x1682cd0,
        &[0x66, 0x83, 0x79, 0x10, 0x00, 0x4c, 0x8b, 0xc9, 0x74, 0x4c],
    ),
    (
        0x1682e10,
        &[0x66, 0x83, 0x79, 0x10, 0x00, 0x4c, 0x8b, 0xc1, 0x74, 0x5f],
    ),
    (
        0x1913080,
        &[
            0x48, 0x89, 0x5c, 0x24, 0x08, 0x57, 0x48, 0x83, 0xec, 0x20, 0x33, 0xff, 0x48, 0x8b,
            0xd9,
        ],
    ),
    (
        0x18aac30,
        &[
            0x48, 0x89, 0x5c, 0x24, 0x08, 0x57, 0x48, 0x83, 0xec, 0x20, 0x48, 0x8b, 0xd9, 0x8b,
            0xfa,
        ],
    ),
    (
        0x18aca60,
        &[
            0x40, 0x55, 0x53, 0x56, 0x57, 0x41, 0x56, 0x48, 0x8d, 0xac, 0x24, 0x00, 0xff, 0xff,
            0xff,
        ],
    ),
    (
        0x18abe60,
        &[
            0x45, 0x85, 0xc0, 0x0f, 0x8e, 0xe5, 0x06, 0x00, 0x00, 0x48, 0x8b, 0xc4,
        ],
    ),
    (
        0x18ae410,
        &[
            0x48, 0x8b, 0xc4, 0x44, 0x89, 0x40, 0x18, 0x48, 0x89, 0x50, 0x10, 0x48, 0x83, 0xec,
            0x68,
        ],
    ),
];

type Ptr = *mut c_void;
type Init = unsafe extern "system" fn(Ptr) -> Ptr;
type RefOp = unsafe extern "system" fn(Ptr);
type CinfoDestroy = unsafe extern "system" fn(Ptr, u32) -> Ptr;
type ShapeFactory = unsafe extern "system" fn(*const Vertices, f32, Ptr) -> Ptr;
type CreateBody = unsafe extern "system" fn(Ptr, *mut u32, Ptr) -> *mut u32;
type AddBodies = unsafe extern "system" fn(Ptr, *const u32, i32, i32, i32);
type DestroyBodies = unsafe extern "system" fn(Ptr, *const u32, i32, i32);
type CastRay = unsafe extern "system" fn(
    Ptr,
    *mut u32,
    u32,
    *const Vector,
    *const Vector,
    *mut Vector,
    *mut Vector,
    *mut u32,
) -> *mut u32;

#[repr(C, align(16))]
#[derive(Clone, Copy, Debug)]
struct Vector([f32; 4]);
#[repr(C)]
struct Vertices {
    data: *const Vector,
    count: i32,
    stride: i32,
}
#[repr(C, align(16))]
struct Native<const N: usize>([u8; N]);
impl<const N: usize> Native<N> {
    fn ptr(&mut self) -> Ptr {
        self.0.as_mut_ptr().cast()
    }
    fn u16(&mut self, at: usize, value: u16) {
        self.0[at..at + 2].copy_from_slice(&value.to_le_bytes());
    }
    fn u32(&mut self, at: usize, value: u32) {
        self.0[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }
    fn usize(&mut self, at: usize, value: usize) {
        self.0[at..at + 8].copy_from_slice(&value.to_le_bytes());
    }
    fn vector(&mut self, at: usize, value: [f32; 4]) {
        for (i, v) in value.into_iter().enumerate() {
            self.u32(at + i * 4, v.to_bits());
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct World {
    cs: usize,
    hk: usize,
}
#[derive(Clone, Copy, Debug)]
pub struct Hit {
    pub body: u32,
    pub shape_key: u32,
    pub point: [f64; 3],
    pub normal: [f64; 3],
}
/// Copied read-only diagnostics. User data presence is not an actor identity:
/// no unverified pointer chain or guessed body-layer exclusion follows it.
#[derive(Clone, Copy, Debug)]
pub struct BodyEvidence {
    pub filter: u32,
    pub motion_id: u32,
    pub flags: u32,
    pub user_data_present: bool,
}
#[derive(Clone, Copy, Debug)]
struct Template {
    body: u32,
    filter: u32,
    material: u16,
    character_filter: u32,
}
#[derive(Clone, Copy, Debug)]
struct Owned {
    id: u32,
    shape: usize,
    bounds: Box6,
}
type Key = [u64; 6];

#[derive(Clone, Copy, Debug, Default, Serialize)]
pub struct ProbeRay {
    pub hit_body: Option<u32>,
    pub hit_point: Option<[f64; 3]>,
    pub matches_body: bool,
    pub error: Option<&'static str>,
}
#[derive(Clone, Copy, Debug, Serialize)]
pub struct Probe {
    pub sampled_ms: u64,
    pub body: u32,
    pub bounds_region: Box6,
    pub expected_center_havok: [f64; 3],
    pub actual_center_havok: [f64; 3],
    pub body_flags: u32,
    pub body_filter: u32,
    pub material: u16,
    pub shape_type: u8,
    pub shape_flags: u16,
    pub broadphase_handle: u32,
    pub character_filter: Option<u32>,
    pub character_pair_allowed: Option<bool>,
    /// One bounded inward ray from each positive box face, in X/Y/Z order.
    pub rays: [ProbeRay; 3],
    pub character_rays: [ProbeRay; 3],
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
pub struct Status {
    pub desired: usize,
    pub owned: usize,
    pub broadphase: usize,
    pub pending: usize,
    pub quarantined_worlds: usize,
    pub created_this_tick: usize,
    pub removed_this_tick: usize,
    pub template_body: Option<u32>,
    pub template_filter: Option<u32>,
    pub template_character_filter: Option<u32>,
    /// Read-only evidence from the first generation/shape-validated owned body.
    pub sample_body_flags: Option<u32>,
    pub sample_broadphase_handle: Option<u32>,
    pub probe: Option<Probe>,
    /// Entire desired set has entered the native broadphase; this does not
    /// certify AI navigation, camera collision, or a successful player test.
    pub complete: bool,
}

pub struct Api {
    base: usize,
    image_len: usize,
    ray: CastRay,
    config_init: Init,
    config_base_drop: RefOp,
    shape_factory: ShapeFactory,
    add_ref: RefOp,
    release: RefOp,
    cinfo_init: Init,
    cinfo_drop: CinfoDestroy,
    create: CreateBody,
    add: AddBodies,
    destroy: DestroyBodies,
}
impl Api {
    pub fn resolve() -> Result<Self, &'static str> {
        let program = Program::current();
        let image = program.image();
        if ENTRYPOINTS
            .iter()
            .any(|(r, p)| image.get(*r..*r + p.len()) != Some(*p))
        {
            return Err("native collider entrypoint fingerprint mismatch");
        }
        let base = image.as_ptr() as usize;
        // The complete executable hash is checked by engine before this API is
        // constructed. Every extra entrypoint check above is independent.
        unsafe {
            Ok(Self {
                base,
                image_len: image.len(),
                ray: std::mem::transmute::<usize, CastRay>(base + 0xc72060),
                config_init: std::mem::transmute::<usize, Init>(base + 0x1882bb0),
                config_base_drop: std::mem::transmute::<usize, RefOp>(base + 0x1682ca0),
                shape_factory: std::mem::transmute::<usize, ShapeFactory>(base + 0x18836f0),
                add_ref: std::mem::transmute::<usize, RefOp>(base + 0x1682cd0),
                release: std::mem::transmute::<usize, RefOp>(base + 0x1682e10),
                cinfo_init: std::mem::transmute::<usize, Init>(base + 0x1913080),
                cinfo_drop: std::mem::transmute::<usize, CinfoDestroy>(base + 0x18aac30),
                create: std::mem::transmute::<usize, CreateBody>(base + 0x18aca60),
                add: std::mem::transmute::<usize, AddBodies>(base + 0x18abe60),
                destroy: std::mem::transmute::<usize, DestroyBodies>(base + 0x18ae410),
            })
        }
    }
    /// Reacquire the SDK singleton and validate both exact-build vtables.
    /// No borrowed SDK object is retained after this returns.
    pub unsafe fn world(&self) -> Result<World, &'static str> {
        let h = unsafe { CSHavokMan::instance() }
            .map_err(|_| "collider Havok singleton unavailable")?;
        let cs = (&*h.phys_world) as *const _ as usize;
        if cs == 0
            || !cs.is_multiple_of(8)
            || unsafe { read::<usize>(cs, 0) } != self.base + CS_WORLD_VTABLE
        {
            return Err("collider CSPhysWorld identity invalid");
        }
        let hk = unsafe { read::<usize>(cs, 8) };
        if hk == 0 || hk % 8 != 0 || unsafe { read::<usize>(hk, 0) } != self.base + HK_WORLD_VTABLE
        {
            return Err("collider hknpWorld identity invalid");
        }
        Ok(World { cs, hk })
    }
    pub unsafe fn ray(
        &self,
        world: World,
        origin: [f64; 3],
        delta: [f64; 3],
        filter: u32,
    ) -> Result<Option<Hit>, &'static str> {
        if !finite(origin)
            || !finite(delta)
            || delta.iter().map(|v| v * v).sum::<f64>() < 1e-12
            || origin
                .iter()
                .chain(delta.iter())
                .any(|v| !(*v as f32).is_finite())
        {
            return Err("collider ray input invalid");
        }
        let origin = Vector([origin[0] as f32, origin[1] as f32, origin[2] as f32, 0.]);
        let delta = Vector([delta[0] as f32, delta[1] as f32, delta[2] as f32, 0.]);
        let mut point = Vector([f32::NAN; 4]);
        let mut normal = point;
        let mut body = INVALID_BODY;
        let mut shape_key = u32::MAX;
        unsafe {
            (self.ray)(
                world.cs as Ptr,
                &mut body,
                filter,
                &origin,
                &delta,
                &mut point,
                &mut normal,
                &mut shape_key,
            );
        }
        if body & INVALID_BODY == INVALID_BODY {
            return Ok(None);
        }
        let point = std::array::from_fn(|i| point.0[i] as f64);
        let normal = std::array::from_fn(|i| normal.0[i] as f64);
        if !ray_hit_valid(origin.0, delta.0, point, normal) {
            return Err("collider ray returned invalid segment hit");
        }
        unsafe { self.body(world, body) }?;
        Ok(Some(Hit {
            body,
            shape_key,
            point,
            normal,
        }))
    }
    /// F5 uses the native owner-aware query, not a distance-based self-hit skip.
    /// c71e00 supplies owner in the query filter data; c632aa compares it with
    /// the body's resolved owner and c632b4..d1 rejects equal nonzero families.
    /// The ordinary rich ray leaves query.userData zero, bypassing that check.
    /// No borrowed SDK reference or owner address survives the synchronous call.
    pub unsafe fn camera_ray(
        &self,
        world: World,
        origin: [f64; 3],
        delta: [f64; 3],
        filter: u32,
        owner: usize,
    ) -> Result<Option<[f64; 3]>, &'static str> {
        if owner == 0
            || !owner.is_multiple_of(8)
            || !finite(origin)
            || !finite(delta)
            || delta.iter().map(|v| v * v).sum::<f64>() < 1e-12
        {
            return Err("camera owner-aware ray input invalid");
        }
        if unsafe { self.world() }? != world {
            return Err("camera physics world changed");
        }
        let player =
            unsafe { PlayerIns::local_player() }.map_err(|_| "camera local owner unavailable")?;
        if player as *const _ as usize != owner {
            return Err("camera ray owner changed");
        }
        let havok =
            unsafe { CSHavokMan::instance() }.map_err(|_| "camera Havok singleton unavailable")?;
        let point = havok.phys_world.cast_ray(
            filter,
            &HavokPosition(origin[0] as f32, origin[1] as f32, origin[2] as f32, 0.),
            PositionDelta(delta[0] as f32, delta[1] as f32, delta[2] as f32),
            player,
        );
        let Some(point) = point else {
            return Ok(None);
        };
        let point = [point.0 as f64, point.1 as f64, point.2 as f64];
        if !ray_point_valid(origin.map(|v| v as f32), delta.map(|v| v as f32), point) {
            return Err("camera owner-aware ray returned invalid segment hit");
        }
        Ok(Some(point))
    }
    /// The body's single Havok material (hknpMaterialId at +0x6a), or None for a
    /// per-triangle mesh (sentinel) or a body that no longer exists.
    pub unsafe fn body_material(&self, world: World, id: u32) -> Option<u16> {
        let body = unsafe { self.body(world, id) }.ok()?;
        let material = unsafe { read::<u16>(body, 0x6a) };
        (material != u16::MAX).then_some(material)
    }
    unsafe fn body(&self, world: World, id: u32) -> Result<usize, &'static str> {
        unsafe { self.body_slot(world, id) }?.ok_or("collider body generation no longer matches")
    }
    pub unsafe fn body_evidence(
        &self,
        world: World,
        id: u32,
    ) -> Result<BodyEvidence, &'static str> {
        let body = unsafe { self.body(world, id) }?;
        Ok(BodyEvidence {
            filter: unsafe { read(body, 0x6c) },
            motion_id: unsafe { read(body, 0x40) },
            flags: unsafe { read(body, 0x44) },
            user_data_present: unsafe { read::<usize>(body, 0xa0) } != 0,
        })
    }
    unsafe fn body_slot(&self, world: World, id: u32) -> Result<Option<usize>, &'static str> {
        let index = (id & INVALID_BODY) as usize;
        // hknpBodyManager +0x10 table / +0x18 slot count, inside world+0x18.
        let slots = unsafe { read::<u32>(world.hk, 0x30) } as usize;
        let table = unsafe { read::<usize>(world.hk, 0x28) };
        if index == INVALID_BODY as usize
            || slots == 0
            || slots > 1_000_000
            || table == 0
            || table % 16 != 0
        {
            return Err("collider body table bounds invalid");
        }
        if index >= slots {
            return Ok(None);
        }
        let body = table + index * BODY_STRIDE;
        if unsafe { read::<u32>(body, 0x70) } != id || unsafe { read::<u32>(body, 0x44) } & 3 == 0 {
            return Ok(None);
        }
        Ok(Some(body))
    }
    unsafe fn template(
        &self,
        world: World,
        feet: [f64; 3],
        owned: &BTreeSet<u32>,
    ) -> Result<Template, &'static str> {
        // The terrain diagnostic ray's layer88 also sees query-only layer55.
        // Use the validated local character's real filter, then independently
        // require the floor body itself to pass the native pair layer/group test.
        let character_filter = unsafe { self.character_filter(world) }?;
        for [x, z] in [[0., 0.], [0.75, 0.], [-0.75, 0.], [0., 0.75], [0., -0.75]] {
            let o = [feet[0] + x, feet[1] + 0.5, feet[2] + z];
            let Some(hit) = unsafe { self.ray(world, o, [0., -5., 0.], character_filter) }? else {
                continue;
            };
            if owned.contains(&hit.body) || hit.normal[1] < 0.5 {
                continue;
            }
            let b = unsafe { self.body(world, hit.body) }?;
            if unsafe { read::<u32>(b, 0x40) } != 0 || unsafe { read::<u32>(b, 0x44) } & 3 != 1 {
                continue;
            }
            let material = unsafe { read::<u16>(b, 0x6a) };
            // An invalid/per-shape sentinel cannot be borrowed from a mesh
            // whose triangles supply material tags that our box does not have.
            if material == u16::MAX {
                continue;
            }
            let filter = unsafe { read::<u32>(b, 0x6c) };
            if !unsafe { self.filter_pair_allowed(world, character_filter, filter) }? {
                continue;
            }
            return Ok(Template {
                body: hit.body,
                filter,
                material,
                character_filter,
            });
        }
        Err("native collider needs static ground accepted by the local character filter")
    }
    pub unsafe fn character_filter(&self, world: World) -> Result<u32, &'static str> {
        let player = unsafe { PlayerIns::local_player() }
            .map_err(|_| "collider local character unavailable")?;
        let physics = &*player.chr_ins.modules.physics;
        if !std::ptr::eq(physics.owner.as_ptr(), &player.chr_ins) {
            return Err("collider local physics owner mismatch");
        }
        let address = physics as *const _ as usize;
        // Exact native stage46779f reads physics+98/+a0 as CS proxy wrappers.
        // Wrapper constructor c5cd5e calls CsHkCharacterProxy constructor
        // c64390 and stores its result at wrapper+88 (c5cd66). c5d3e0 passes
        // that object to native integration18a5ac0. Wrapper+98 instead points
        // to integration INPUT; it must never be interpreted as a proxy.
        // Native body creator18a4ef0 reads shape+28, BodyID+38, filter+3c,
        // world+40. Derived ctor c643ac replaces the base proxy vtable.
        // CS construction explicitly disables the optional proxy body
        // (config+b1=0 at c5ccf7; base ctor18a4ae2). A valid body is therefore
        // extra evidence, not a requirement for this ordinary phantom proxy.
        let mut unavailable = "collider native local character wrapper unavailable";
        for at in [0x98, 0xa0] {
            let wrapper = unsafe { read::<usize>(address, at) };
            if wrapper == 0 || wrapper % 8 != 0 {
                continue;
            }
            let proxy = unsafe { read::<usize>(wrapper, 0x88) };
            if proxy == 0 || proxy % 8 != 0 {
                unavailable = "collider native character proxy pointer unavailable";
                continue;
            }
            if unsafe { read::<usize>(proxy, 0) } != self.base + CHARACTER_PROXY_VTABLE {
                unavailable = "collider native character proxy vtable mismatch";
                continue;
            }
            if unsafe { read::<usize>(proxy, 0x40) } != world.hk {
                unavailable = "collider native character proxy world mismatch";
                continue;
            }
            let shape = unsafe { read::<usize>(proxy, 0x28) };
            let filter = unsafe { read::<u32>(proxy, 0x3c) };
            // c5cc72..c5cc98 copies wrapper+80 into the proxy's shape config;
            // native18a6f50 retains it as proxy+28.
            if shape == 0 || shape % 16 != 0 || unsafe { read::<usize>(wrapper, 0x80) } != shape {
                unavailable = "collider native character proxy shape owner mismatch";
                continue;
            }
            let id = unsafe { read::<u32>(proxy, 0x38) };
            if id & INVALID_BODY != INVALID_BODY {
                let Ok(body) = (unsafe { self.body(world, id) }) else {
                    unavailable = "collider native character proxy body unavailable";
                    continue;
                };
                if unsafe { read::<usize>(body, 0x60) } != shape
                    || unsafe { read::<u32>(body, 0x6c) } != filter
                {
                    unavailable = "collider native character proxy body shape/filter mismatch";
                    continue;
                }
            }
            return Ok(filter);
        }
        Err(unavailable)
    }
    /// Readback for the verified native position synchronization route. The
    /// wrapper setter c5d926 copies its supplied position to proxy+0x80.
    pub unsafe fn character_position(&self, world: World) -> Result<[f64; 3], &'static str> {
        let filter = unsafe { self.character_filter(world) }?;
        let player = unsafe { PlayerIns::local_player() }
            .map_err(|_| "collider local character unavailable")?;
        let physics = (&*player.chr_ins.modules.physics) as *const _ as usize;
        for at in [0x98, 0xa0] {
            let wrapper = unsafe { read::<usize>(physics, at) };
            if wrapper == 0 || wrapper % 8 != 0 {
                continue;
            }
            let proxy = unsafe { read::<usize>(wrapper, 0x88) };
            if proxy == 0 || proxy % 8 != 0 {
                continue;
            }
            if unsafe { read::<usize>(proxy, 0) } != self.base + CHARACTER_PROXY_VTABLE
                || unsafe { read::<usize>(proxy, 0x40) } != world.hk
                || unsafe { read::<u32>(proxy, 0x3c) } != filter
                || unsafe { read::<usize>(proxy, 0x28) } != unsafe { read::<usize>(wrapper, 0x80) }
            {
                continue;
            }
            let p = unsafe { read::<[f32; 4]>(proxy, 0x80) };
            let p = [p[0] as f64, p[1] as f64, p[2] as f64];
            return finite(p)
                .then_some(p)
                .ok_or("native character position nonfinite");
        }
        Err("native character position unavailable")
    }
    unsafe fn filter_pair_allowed(
        &self,
        world: World,
        a: u32,
        b: u32,
    ) -> Result<bool, &'static str> {
        let filter = unsafe { self.collision_filter(world) }?;
        // CSCollisionFilter c63220/c63320: 128 layers, two u64 words per row,
        // then reject equal nonzero two-bit groups. Our owned userdata is zero,
        // so the remaining child family/owner predicate accepts the body. This
        // is a compatibility precheck only: native rays/contacts still execute
        // the wrapper, including its body-pair constraint exclusions.
        let word = unsafe {
            read::<u64>(
                filter,
                0x20 + ((a as usize & 0x7f) * 2 + ((b as usize & 0x7f) >> 6)) * 8,
            )
        };
        Ok(layer_pair_allowed(a, b, word))
    }
    unsafe fn collision_filter(&self, world: World) -> Result<usize, &'static str> {
        // CSHavokMan init c51410 installs hknpConstraintCollisionFilter through
        // c71950 -> 18b3450 at world+4d0. It owns CSCollisionFilter at +30 and
        // records this world at +38 (18b5020). Query slot 1913cf0 forwards to
        // the child's matching virtual slot; the layer matrix is on the child.
        // Follow exactly this one validated chain, never scan unknown filters.
        let wrapper = unsafe { read::<usize>(world.hk, 0x4d0) };
        if wrapper == 0 || wrapper % 8 != 0 {
            return Err("collider native constraint-filter pointer unavailable");
        }
        if unsafe { read::<usize>(wrapper, 0) } != self.base + CONSTRAINT_FILTER_VTABLE {
            return Err("collider native constraint-filter vtable mismatch");
        }
        if unsafe { read::<usize>(wrapper, 0x38) } != world.hk {
            return Err("collider native constraint-filter world mismatch");
        }
        let filter = unsafe { read::<usize>(wrapper, 0x30) };
        if filter == 0 || filter % 8 != 0 {
            return Err("collider native collision-filter child unavailable");
        }
        if unsafe { read::<usize>(filter, 0) } != self.base + COLLISION_FILTER_VTABLE {
            return Err("collider native collision-filter child vtable mismatch");
        }
        Ok(filter)
    }
    unsafe fn create_box(
        &self,
        world: World,
        template: Template,
        bounds: Box6,
        offset: [f64; 3],
    ) -> Result<Owned, &'static str> {
        if unsafe { read::<u8>(self.base, BODY_SHAPE_REFS_RVA) } != 1 {
            return Err("native body shape reference ownership mode changed");
        }
        let (center, vertices) = geometry(bounds, offset)?;
        let points = Vertices {
            data: vertices.as_ptr(),
            count: 8,
            stride: 16,
        };
        let mut config = Native::<0x50>([0; 0x50]);
        unsafe {
            (self.config_init)(config.ptr());
        }
        let shape = unsafe { (self.shape_factory)(&points, 0.0, config.ptr()) } as usize;
        unsafe {
            (self.config_base_drop)(config.0.as_mut_ptr().add(0x18).cast());
        }
        if shape == 0 {
            return Err("native convex box factory returned null");
        }
        // Shape construction owns one reference, retained independently until
        // removal. Cinfo owns an additional ref; the engine may also own one.
        if !shape.is_multiple_of(16) {
            return Err("native shape alignment invalid");
        }
        let vt = unsafe { read::<usize>(shape, 0) };
        if !(self.base..self.base + self.image_len).contains(&vt) {
            return Err("native convex box vtable outside exact image");
        }
        let mut cinfo = Native::<0xb0>([0; 0xb0]);
        unsafe {
            (self.cinfo_init)(cinfo.ptr());
            (self.add_ref)(shape as Ptr);
        }
        cinfo.usize(0, shape);
        cinfo.u32(0x10, template.filter);
        cinfo.u16(0x14, template.material);
        cinfo.vector(0x30, center);
        // Keep the constructor's static motion type, zero user data, zero
        // velocities, identity orientation, default quality and allocator IDs.
        let mut id = INVALID_BODY;
        unsafe {
            (self.create)(world.hk as Ptr, &mut id, cinfo.ptr());
            (self.cinfo_drop)(cinfo.ptr(), 0);
        }
        if id & INVALID_BODY == INVALID_BODY {
            unsafe {
                (self.release)(shape as Ptr);
            }
            return Err("native createBody returned invalid ID");
        }
        // Record an independently held shape pointer for robust generation
        // checks; never pretend an arbitrary numeric userData is an ER object.
        let own = Owned { id, shape, bounds };
        Ok(own)
    }
    unsafe fn owned_body(&self, world: World, owned: &Owned) -> Result<usize, &'static str> {
        unsafe { self.owned_slot(world, owned) }?
            .ok_or("native collider ownership no longer matches")
    }
    unsafe fn owned_slot(
        &self,
        world: World,
        owned: &Owned,
    ) -> Result<Option<usize>, &'static str> {
        let Some(body) = unsafe { self.body_slot(world, owned.id) }? else {
            return Ok(None);
        };
        if unsafe { read::<usize>(body, 0x60) } != owned.shape {
            return Ok(None);
        }
        if unsafe { read::<usize>(body, 0xa0) } != 0 {
            return Err("native collider ownership mismatch");
        }
        Ok(Some(body))
    }
    unsafe fn broadphase_handle(
        &self,
        world: World,
        owned: &Owned,
    ) -> Result<Option<u32>, &'static str> {
        let body = unsafe { self.owned_body(world, owned) }?;
        // Native body initialization and removal use 0xffffffff at +0x78.
        // Both exact-build Hybrid/Wide broadphase add implementations replace
        // it with a handle. Body flags+0x44 bit8 is dynamic activation, and
        // therefore cannot identify insertion of these static bodies.
        let handle = unsafe { read::<u32>(body, 0x78) };
        Ok((handle != u32::MAX).then_some(handle))
    }
    unsafe fn probe(
        &self,
        world: World,
        owned: &Owned,
        offset: [f64; 3],
        now: u64,
    ) -> Result<Probe, &'static str> {
        let body = unsafe { self.owned_body(world, owned) }?;
        let (center, _) = geometry(owned.bounds, offset)?;
        let expected_center_havok = std::array::from_fn(|i| center[i] as f64);
        // Native body setTransform (0x19216a0) copies translation XYZ at +0x30.
        let actual = unsafe { read::<[f32; 3]>(body, 0x30) }.map(|v| v as f64);
        if !finite(actual) {
            return Err("owned collider transform is nonfinite");
        }
        let mut rays = [ProbeRay::default(); 3];
        let character_filter = unsafe { self.character_filter(world) }.ok();
        let mut character_rays = [ProbeRay::default(); 3];
        for (axis, ray) in rays.iter_mut().enumerate() {
            let (origin, delta) = probe_ray(owned.bounds, offset, axis)?;
            // Deliberately bypass terrain_ray's owned-body exclusion: this is
            // read-only evidence of whether the native query can see our body.
            match unsafe { self.ray(world, origin, delta, crate::world_native::RAY_FILTER) } {
                Ok(Some(hit)) => {
                    ray.hit_body = Some(hit.body);
                    ray.hit_point = Some(hit.point);
                    ray.matches_body = hit.body == owned.id;
                }
                Ok(None) => {}
                Err(error) => ray.error = Some(error),
            }
            if let Some(filter) = character_filter {
                let ray = &mut character_rays[axis];
                match unsafe { self.ray(world, origin, delta, filter) } {
                    Ok(Some(hit)) => {
                        ray.hit_body = Some(hit.body);
                        ray.hit_point = Some(hit.point);
                        ray.matches_body = hit.body == owned.id;
                    }
                    Ok(None) => {}
                    Err(error) => ray.error = Some(error),
                }
            }
        }
        let body_filter = unsafe { read(body, 0x6c) };
        Ok(Probe {
            sampled_ms: now,
            body: owned.id,
            bounds_region: owned.bounds,
            expected_center_havok,
            actual_center_havok: actual,
            body_flags: unsafe { read(body, 0x44) },
            body_filter,
            material: unsafe { read(body, 0x6a) },
            shape_type: unsafe { read(owned.shape, 0x1a) },
            shape_flags: unsafe { read(owned.shape, 0x18) },
            broadphase_handle: unsafe { read(body, 0x78) },
            rays,
            character_filter,
            character_pair_allowed: character_filter
                .and_then(|f| unsafe { self.filter_pair_allowed(world, f, body_filter) }.ok()),
            character_rays,
        })
    }
    unsafe fn remove(&self, world: World, owned: Owned) -> Result<(), &'static str> {
        // A recycled/missing generation has already been retired by the engine.
        // Never delete its replacement; only release our held shape reference.
        if unsafe { self.owned_slot(world, &owned) }?.is_some() {
            unsafe {
                (self.destroy)(world.hk as Ptr, &owned.id, 1, 0);
            }
        }
        unsafe {
            (self.release)(owned.shape as Ptr);
        }
        Ok(())
    }
}

pub struct Driver {
    api: Api,
    world: Option<World>,
    epoch: u64,
    offset: [f64; 3],
    template: Option<Template>,
    bodies: BTreeMap<Key, Owned>,
    status: Status,
    failure: Option<&'static str>,
    removed_boxes: Vec<Box6>,
    quarantine: Vec<(World, BTreeMap<Key, Owned>)>,
    last_probe_ms: u64,
    probe: Option<Probe>,
}
impl Driver {
    /// The validated query API, for read-only rays outside collider ownership.
    pub fn api(&self) -> &Api {
        &self.api
    }
    pub fn resolve() -> Result<Self, &'static str> {
        Ok(Self {
            api: Api::resolve()?,
            world: None,
            epoch: 0,
            offset: [0.; 3],
            template: None,
            bodies: BTreeMap::new(),
            status: Status::default(),
            failure: None,
            removed_boxes: Vec::new(),
            quarantine: Vec::new(),
            last_probe_ms: 0,
            probe: None,
        })
    }
    pub fn status(&self) -> Status {
        self.status
    }
    pub fn take_removed_boxes(&mut self) -> Vec<Box6> {
        std::mem::take(&mut self.removed_boxes)
    }
    fn owns_in(&self, world: World, id: u32) -> bool {
        (self.world.is_some_and(|w| w.hk == world.hk) && self.bodies.values().any(|b| b.id == id))
            || self
                .quarantine
                .iter()
                .any(|(w, bodies)| w.hk == world.hk && bodies.values().any(|b| b.id == id))
    }
    /// Retain existing native samples under placed blocks. Returning an error
    /// ensures the terrain cache never treats an owned surface as host terrain
    /// or an occluded native volume as proven empty.
    pub unsafe fn terrain_ray(
        &self,
        origin: [f64; 3],
        delta: [f64; 3],
        filter: u32,
    ) -> Result<Option<[f64; 3]>, &'static str> {
        let world = unsafe { self.api.world() }?;
        let hit = unsafe { self.api.ray(world, origin, delta, filter) }?;
        if hit.is_some_and(|h| self.owns_in(world, h.body)) {
            return Err("terrain ray occluded by owned Minecraft block");
        }
        Ok(hit.map(|h| h.point))
    }
    /// Terrain sampling with the surface normal, under the same owned-block rule.
    pub unsafe fn terrain_hit(
        &self,
        origin: [f64; 3],
        delta: [f64; 3],
        filter: u32,
    ) -> Result<Option<crate::worldterrain::Hit>, &'static str> {
        let world = unsafe { self.api.world() }?;
        let hit = unsafe { self.api.ray(world, origin, delta, filter) }?;
        if hit.is_some_and(|h| self.owns_in(world, h.body)) {
            return Err("terrain ray occluded by owned Minecraft block");
        }
        Ok(hit.map(|h| crate::worldterrain::Hit {
            point: h.point,
            normal: Some(h.normal),
            material: unsafe { self.api.body_material(world, h.body) },
        }))
    }
    /// The local character's physical collision filter. The terrain diagnostic
    /// filter also returns query-only volumes the player never stands on.
    pub unsafe fn character_filter(&self) -> Result<u32, &'static str> {
        let world = unsafe { self.api.world() }?;
        unsafe { self.api.character_filter(world) }
    }
    /// Synchronize bounded desired boxes. Caller already checked fresh guest
    /// epoch/session, active offline world, no warp/death/GUI, and task phase.
    pub unsafe fn sync(
        &mut self,
        epoch: u64,
        offset: [f64; 3],
        feet_havok: [f64; 3],
        boxes: &[Box6],
    ) -> Result<Status, &'static str> {
        if epoch == 0
            || !finite(offset)
            || boxes.len() > MAX_BODIES
            || boxes.iter().any(|b| !valid_box(*b))
        {
            return Err("native collider desired state invalid");
        }
        let world = unsafe { self.api.world() }?;
        unsafe { self.bind_world(world) }?;
        if self.epoch != epoch || self.offset != offset {
            unsafe { self.clear_current(world) }?;
            self.template = None;
            self.epoch = epoch;
            self.offset = offset;
            self.failure = None;
        }
        if let Some(e) = self.failure {
            return Err(e);
        }
        self.world = Some(world);
        let desired: BTreeMap<Key, Box6> = boxes.iter().copied().map(|b| (key(b), b)).collect();
        let stale: Vec<Key> = self
            .bodies
            .keys()
            .filter(|k| !desired.contains_key(*k))
            .copied()
            .take(REMOVE_BUDGET)
            .collect();
        let mut removed = 0;
        for k in stale {
            let body = self.bodies[&k];
            unsafe { self.api.remove(world, body) }?;
            self.bodies.remove(&k);
            self.removed_boxes.push(body.bounds);
            removed += 1;
        }
        let missing: Vec<Box6> = desired
            .iter()
            .filter(|(k, _)| !self.bodies.contains_key(*k))
            .map(|(_, b)| *b)
            .take(CREATE_BUDGET)
            .collect();
        if !missing.is_empty() && self.template.is_none() {
            let owned = self.bodies.values().map(|b| b.id).collect();
            self.template = Some(unsafe { self.api.template(world, feet_havok, &owned) }?);
        }
        let mut created = 0;
        // Remove obsolete shapes before adding replacements at their position.
        if self.bodies.keys().all(|k| desired.contains_key(k))
            && let Some(template) = self.template
        {
            // Material/filter came from this world. If its source has gone,
            // reacquire a current template before any more native creation.
            if unsafe { self.api.body(world, template.body) }.is_err() {
                self.template = None;
                return Err("native terrain template retired; retrying");
            }
            let character_filter = unsafe { self.api.character_filter(world) }?;
            if !unsafe {
                self.api
                    .filter_pair_allowed(world, character_filter, template.filter)
            }? {
                self.template = None;
                return Err("native ground template no longer accepts character; retrying");
            }
            for b in missing {
                let own = match unsafe { self.api.create_box(world, template, b, offset) } {
                    Ok(b) => b,
                    Err(e) => {
                        self.failure = Some(e);
                        return Err(e);
                    }
                };
                // Retain the handle BEFORE any postconstruction check. A
                // failed layout check cannot silently orphan a created body
                // and retry allocation forever on subsequent frames.
                self.bodies.insert(key(b), own);
                created += 1;
                if let Err(e) = unsafe { self.api.owned_body(world, &own) } {
                    self.failure = Some(e);
                    return Err(e);
                }
                // Match native hknpCharacterProxy insertion: pending mode1,
                // additional mode0. Native update drains that pending list.
                unsafe {
                    (self.api.add)(world.hk as Ptr, &own.id, 1, 1, 0);
                }
            }
        }
        let mut broadphase = 0;
        let mut sample_body_flags = None;
        let mut sample_broadphase_handle = None;
        for b in self.bodies.values() {
            let ptr = unsafe { self.api.owned_body(world, b) }?;
            let handle = unsafe { self.api.broadphase_handle(world, b) }?;
            if sample_body_flags.is_none() {
                sample_body_flags = Some(unsafe { read::<u32>(ptr, 0x44) });
                sample_broadphase_handle = Some(unsafe { read::<u32>(ptr, 0x78) });
            }
            if handle.is_some() {
                broadphase += 1;
            }
        }
        let remaining = desired
            .keys()
            .filter(|k| !self.bodies.contains_key(*k))
            .count()
            + self
                .bodies
                .keys()
                .filter(|k| !desired.contains_key(*k))
                .count();
        let now = crate::world_transport::now();
        if self.bodies.is_empty() {
            self.probe = None;
            self.last_probe_ms = 0;
        } else if self.last_probe_ms == 0
            || now.saturating_sub(self.last_probe_ms) >= PROBE_INTERVAL_MS
        {
            // Nearest owned block is most useful for a player's physical test.
            // At most six read-only rays per half-second, independent of count.
            let nearest = self
                .bodies
                .values()
                .min_by(|a, b| {
                    let distance = |bounds: Box6| {
                        (0..3)
                            .map(|i| {
                                ((bounds[i] + bounds[i + 3]) * 0.5 - offset[i] - feet_havok[i])
                                    .powi(2)
                            })
                            .sum::<f64>()
                    };
                    distance(a.bounds).total_cmp(&distance(b.bounds))
                })
                .copied();
            self.probe =
                nearest.and_then(|body| unsafe { self.api.probe(world, &body, offset, now) }.ok());
            self.last_probe_ms = now;
        }
        self.status = Status {
            desired: desired.len(),
            owned: self.bodies.len(),
            broadphase,
            pending: remaining + self.bodies.len() - broadphase,
            created_this_tick: created,
            removed_this_tick: removed,
            quarantined_worlds: self.quarantine.len(),
            template_body: self.template.map(|t| t.body),
            template_filter: self.template.map(|t| t.filter),
            template_character_filter: self.template.map(|t| t.character_filter),
            sample_body_flags,
            sample_broadphase_handle,
            probe: self.probe,
            complete: remaining == 0 && broadphase == desired.len(),
        };
        Ok(self.status)
    }
    unsafe fn clear_current(&mut self, world: World) -> Result<(), &'static str> {
        if self.world.is_some_and(|w| w.hk != world.hk) {
            return Err("collider cleanup world changed");
        }
        // Keep each handle until its destruction actually completes. A failed
        // identity check never causes a different body's deletion or silent loss.
        for _ in 0..REMOVE_BUDGET {
            let Some((&k, &body)) = self.bodies.first_key_value() else {
                break;
            };
            unsafe { self.api.remove(world, body) }?;
            self.bodies.remove(&k);
            self.removed_boxes.push(body.bounds);
        }
        if !self.bodies.is_empty() {
            return Err("native collider cleanup pending next task");
        }
        self.status = Status::default();
        self.probe = None;
        self.last_probe_ms = 0;
        Ok(())
    }
    unsafe fn bind_world(&mut self, world: World) -> Result<(), &'static str> {
        if let Some(old) = self.world.filter(|w| w.hk != world.hk) {
            // Singleton replacement is NOT destruction proof. Retain old
            // handles and independent refs; do not dereference the old world.
            if !self.bodies.is_empty() {
                if self.quarantine.len() >= MAX_RETIRED_WORLDS {
                    return Err("native collider retired-world quarantine full");
                }
                self.quarantine
                    .push((old, std::mem::take(&mut self.bodies)));
            }
            self.template = None;
            self.failure = None;
            self.epoch = 0;
            self.status = Status::default();
            self.probe = None;
            self.last_probe_ms = 0;
        }
        self.world = Some(world);
        if let Some(index) = self.quarantine.iter().position(|(w, _)| w.hk == world.hk) {
            for _ in 0..REMOVE_BUDGET {
                let Some((&k, &owned)) = self.quarantine[index].1.first_key_value() else {
                    break;
                };
                unsafe { self.api.remove(world, owned) }?;
                self.quarantine[index].1.remove(&k);
            }
            if self.quarantine[index].1.is_empty() {
                self.quarantine.remove(index);
            } else {
                return Err("native collider quarantined cleanup pending next task");
            }
        }
        Ok(())
    }
    /// Explicit game-task cleanup; no unbounded native work in Rust Drop.
    pub unsafe fn suspend(&mut self) -> Result<(), &'static str> {
        // Suspension may be requested because the outer gameplay gate failed.
        // Keep ownership intact until an offline session can be established;
        // cleanup must not turn a failed network/session gate into native writes.
        let game = unsafe { GameMan::instance() }
            .map_err(|_| "collider cleanup waiting for offline GameMan")?;
        let session = unsafe { CSSessionManager::instance() }
            .map_err(|_| "collider cleanup waiting for offline session")?;
        if game.is_in_online_mode
            || session.lobby_state != LobbyState::None
            || session.protocol_state != ProtocolState::None
        {
            return Err("native collider cleanup deferred until offline session");
        }
        let world = unsafe { self.api.world() }?;
        unsafe { self.bind_world(world) }?;
        unsafe { self.clear_current(world) }?;
        self.world = None;
        self.template = None;
        self.epoch = 0;
        self.failure = None;
        Ok(())
    }
}

unsafe fn read<T: Copy>(base: usize, at: usize) -> T {
    unsafe { std::ptr::read_unaligned((base + at) as *const T) }
}
fn finite(p: [f64; 3]) -> bool {
    p.iter().all(|v| v.is_finite() && v.abs() <= 1_000_000.)
}
fn ray_hit_valid(origin: [f32; 4], delta: [f32; 4], point: [f64; 3], normal: [f64; 3]) -> bool {
    let normal_length = normal.iter().map(|v| v * v).sum::<f64>().sqrt();
    finite(normal)
        && (0.5..=1.5).contains(&normal_length)
        && ray_point_valid(
            [origin[0], origin[1], origin[2]],
            [delta[0], delta[1], delta[2]],
            point,
        )
}
fn ray_point_valid(origin: [f32; 3], delta: [f32; 3], point: [f64; 3]) -> bool {
    if !finite(point) || !origin.iter().chain(delta.iter()).all(|v| v.is_finite()) {
        return false;
    }
    let length = delta
        .iter()
        .map(|v| (*v as f64).powi(2))
        .sum::<f64>()
        .sqrt();
    if !length.is_finite() || length < 1e-6 {
        return false;
    }
    let direction: [f64; 3] = std::array::from_fn(|i| delta[i] as f64 / length);
    let offset: [f64; 3] = std::array::from_fn(|i| point[i] - origin[i] as f64);
    let along = (0..3).map(|i| offset[i] * direction[i]).sum::<f64>();
    let off = (0..3)
        .map(|i| (offset[i] - along * direction[i]).powi(2))
        .sum::<f64>();
    // Validate against the actual float query, not higher precision inputs
    // that the native ABI cannot represent. Never turn invalid output into air.
    (-0.02..=length + 0.02).contains(&along) && off <= 0.02 * 0.02
}
fn key(b: Box6) -> Key {
    b.map(|v| {
        if v == 0. {
            0.0f64.to_bits()
        } else {
            v.to_bits()
        }
    })
}
fn layer_pair_allowed(a: u32, b: u32, word: u64) -> bool {
    let layer_allowed = word & (1u64 << (b & 0x3f)) != 0;
    let a_group = (a >> 7) & 3;
    let b_group = (b >> 7) & 3;
    layer_allowed && (a_group == 0 || b_group == 0 || a_group != b_group)
}
fn probe_ray(
    bounds: Box6,
    offset: [f64; 3],
    axis: usize,
) -> Result<([f64; 3], [f64; 3]), &'static str> {
    if axis >= 3 || !valid_box(bounds) || !finite(offset) {
        return Err("collider probe input invalid");
    }
    let mut origin = std::array::from_fn(|i| (bounds[i] + bounds[i + 3]) * 0.5 - offset[i]);
    origin[axis] = bounds[axis + 3] - offset[axis] + 0.125;
    let mut delta = [0.; 3];
    delta[axis] = -(bounds[axis + 3] - bounds[axis] + 0.25);
    Ok((origin, delta))
}
fn geometry(bounds: Box6, offset: [f64; 3]) -> Result<([f32; 4], [Vector; 8]), &'static str> {
    if !valid_box(bounds) || !finite(offset) {
        return Err("native box geometry invalid");
    }
    let center: [f64; 3] = std::array::from_fn(|i| (bounds[i] + bounds[i + 3]) * 0.5 - offset[i]);
    let half: [f32; 3] = std::array::from_fn(|i| ((bounds[i + 3] - bounds[i]) * 0.5) as f32);
    if !finite(center)
        || half
            .iter()
            .any(|v| !v.is_finite() || *v < 0.001 || *v > 64.)
    {
        return Err("native box dimensions outside construction limits");
    }
    let vertices = std::array::from_fn(|n| {
        Vector([
            if n & 1 == 0 { -half[0] } else { half[0] },
            if n & 2 == 0 { -half[1] } else { half[1] },
            if n & 4 == 0 { -half[2] } else { half[2] },
            0.,
        ])
    });
    Ok((
        [center[0] as f32, center[1] as f32, center[2] as f32, 0.],
        vertices,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static DESTROYS: AtomicUsize = AtomicUsize::new(0);
    static RELEASES: AtomicUsize = AtomicUsize::new(0);
    #[test]
    fn rich_ray_rejects_off_segment_nonfinite_and_degenerate_contacts() {
        let origin = [1., 2., 3., 0.];
        let delta = [0., 0., 4., 0.];
        let normal = [0., 0., -1.];
        assert!(ray_hit_valid(origin, delta, [1., 2., 5.], normal));
        assert!(ray_hit_valid(origin, delta, [1., 2., 7.], normal));
        for point in [
            [1., 2., 8.],
            [1., 2., 2.9],
            [1.1, 2., 5.],
            [f64::NAN, 2., 5.],
        ] {
            assert!(!ray_hit_valid(origin, delta, point, normal));
        }
        assert!(!ray_hit_valid(origin, delta, [1., 2., 5.], [0.; 3]));
        assert!(!ray_hit_valid(origin, [0.; 4], [1., 2., 3.], normal));
    }
    #[test]
    fn owner_camera_ray_preserves_genuine_close_walls_and_rejects_bad_points() {
        let o = [20., 340., -74.];
        let d = [0., 0., 4.];
        // Ownership belongs to the native filter. Validation must never turn a
        // real contact near the camera origin into clear space.
        assert!(ray_point_valid(o, d, [20., 340., -73.999]));
        assert!(ray_point_valid(o, d, [20., 340., -70.]));
        for p in [
            [20., 340., -69.],
            [20.1, 340., -72.],
            [f64::NAN, 340., -72.],
        ] {
            assert!(!ray_point_valid(o, d, p));
        }
        assert!(!ray_point_valid([f32::NAN, 0., 0.], d, [0.; 3]));
        assert!(!ray_point_valid(o, [0.; 3], [20., 340., -74.]));
    }
    #[test]
    fn owner_camera_ray_invalid_identity_stops_before_any_game_lookup() {
        let api = mock_api();
        let world = World { cs: 0, hk: 0 };
        for owner in [0, 1, usize::MAX] {
            assert!(
                unsafe { api.camera_ray(world, [0.; 3], [0., 0., 4.], 0x02000002, owner) }.is_err()
            );
        }
        assert!(unsafe { api.camera_ray(world, [0.; 3], [0.; 3], 0x02000002, 8) }.is_err());
    }
    fn mock_api() -> Api {
        unsafe extern "system" fn init(p: Ptr) -> Ptr {
            p
        }
        unsafe extern "system" fn release(_: Ptr) {
            RELEASES.fetch_add(1, Ordering::SeqCst);
        }
        unsafe extern "system" fn no_ref(_: Ptr) {}
        unsafe extern "system" fn cdrop(p: Ptr, _: u32) -> Ptr {
            p
        }
        unsafe extern "system" fn factory(_: *const Vertices, _: f32, _: Ptr) -> Ptr {
            std::ptr::null_mut()
        }
        unsafe extern "system" fn create(_: Ptr, id: *mut u32, _: Ptr) -> *mut u32 {
            id
        }
        unsafe extern "system" fn add(_: Ptr, _: *const u32, _: i32, _: i32, _: i32) {}
        unsafe extern "system" fn destroy(_: Ptr, _: *const u32, _: i32, _: i32) {
            DESTROYS.fetch_add(1, Ordering::SeqCst);
        }
        unsafe extern "system" fn ray(
            _: Ptr,
            id: *mut u32,
            _: u32,
            _: *const Vector,
            _: *const Vector,
            _: *mut Vector,
            _: *mut Vector,
            _: *mut u32,
        ) -> *mut u32 {
            id
        }
        Api {
            base: 0,
            image_len: 0,
            ray,
            config_init: init,
            config_base_drop: no_ref,
            shape_factory: factory,
            add_ref: no_ref,
            release,
            cinfo_init: init,
            cinfo_drop: cdrop,
            create,
            add,
            destroy,
        }
    }
    #[test]
    fn native_buffers_have_exact_size_and_alignment() {
        assert_eq!(std::mem::size_of::<Native<0xb0>>(), 0xb0);
        assert_eq!(std::mem::align_of::<Native<0xb0>>(), 16);
        assert_eq!(std::mem::size_of::<Native<0x50>>(), 0x50);
        assert_eq!(std::mem::size_of::<Vertices>(), 16);
        assert_eq!(std::mem::size_of::<Vector>(), 16);
    }
    #[test]
    fn box_geometry_uses_stable_region_minus_havok_offset() {
        let (c, v) = geometry([10., 20., 30., 11., 22., 33.], [8., 16., 24.]).unwrap();
        assert_eq!(c, [2.5, 5., 7.5, 0.]);
        assert_eq!(v[0].0, [-0.5, -1., -1.5, 0.]);
        assert_eq!(v[7].0, [0.5, 1., 1.5, 0.]);
    }
    #[test]
    fn tiny_flat_nonfinite_or_unbounded_shapes_rejected() {
        assert!(geometry([0., 0., 0., 1., 1., 1.], [0.; 3]).is_ok());
        assert!(geometry([0., 0., 0., 0., 1., 1.], [0.; 3]).is_err());
        assert!(geometry([0., 0., 0., 0.0001, 1., 1.], [0.; 3]).is_err());
        assert!(geometry([0., 0., 0., 1., 1., 1.], [f64::NAN; 3]).is_err());
    }
    #[test]
    fn box_identity_normalizes_negative_zero_only() {
        assert_eq!(
            key([0., 0., 0., 1., 1., 1.]),
            key([-0., 0., 0., 1., 1., 1.])
        );
        assert_ne!(key([0., 0., 0., 1., 1., 1.]), key([0., 0., 0., 1., 2., 1.]));
    }
    #[test]
    fn cinfo_only_changes_owned_fields() {
        let mut info = Native::<0xb0>([0x55; 0xb0]);
        info.usize(0, 0x1234);
        info.u32(0x10, 123);
        info.u16(0x14, 4);
        info.vector(0x30, [1., 2., 3., 0.]);
        assert_eq!(&info.0[0x20..0x28], &[0x55; 8]);
        assert_eq!(&info.0[0x40..0x50], &[0x55; 16]);
        assert_eq!(&info.0[0x50..0xb0], &[0x55; 0x60]);
    }
    #[test]
    fn cleanup_never_destroys_recycled_body_and_retains_on_invalid_table() {
        let api = mock_api();
        let mut table = Native::<{ 4 * BODY_STRIDE }>([0; 4 * BODY_STRIDE]);
        let mut world = Native::<0x40>([0; 0x40]);
        world.usize(0x28, table.0.as_ptr() as usize);
        world.u32(0x30, 4);
        let world_id = World {
            cs: 0,
            hk: world.0.as_ptr() as usize,
        };
        let own = Owned {
            id: 3,
            shape: 0x123400,
            bounds: [0., 0., 0., 1., 1., 1.],
        };
        let at = 3 * BODY_STRIDE;
        table.u32(at + 0x70, 3);
        table.u32(at + 0x44, 1);
        table.usize(at + 0x60, own.shape);
        DESTROYS.store(0, Ordering::SeqCst);
        RELEASES.store(0, Ordering::SeqCst);
        unsafe { api.remove(world_id, own) }.unwrap();
        assert_eq!(DESTROYS.load(Ordering::SeqCst), 1);
        table.u32(at + 0x70, 0x01000003); // same index, new generation
        unsafe { api.remove(world_id, own) }.unwrap();
        assert_eq!(DESTROYS.load(Ordering::SeqCst), 1);
        assert_eq!(RELEASES.load(Ordering::SeqCst), 2);
        world.u32(0x30, 1_000_001);
        assert!(unsafe { api.remove(world_id, own) }.is_err());
        assert_eq!(DESTROYS.load(Ordering::SeqCst), 1);
        assert_eq!(RELEASES.load(Ordering::SeqCst), 2);
    }
    #[test]
    fn static_broadphase_membership_uses_handle_not_dynamic_activation_flag() {
        let api = mock_api();
        let mut table = Native::<{ 4 * BODY_STRIDE }>([0; 4 * BODY_STRIDE]);
        let mut world = Native::<0x40>([0; 0x40]);
        world.usize(0x28, table.0.as_ptr() as usize);
        world.u32(0x30, 4);
        let world_id = World {
            cs: 0,
            hk: world.0.as_ptr() as usize,
        };
        let own = Owned {
            id: 3,
            shape: 0x123400,
            bounds: [0., 0., 0., 1., 1., 1.],
        };
        let at = 3 * BODY_STRIDE;
        table.u32(at + 0x70, 3);
        table.u32(at + 0x44, 1);
        table.usize(at + 0x60, own.shape);
        table.u32(at + 0x78, 0); // Static, not active, valid first broadphase handle.
        assert_eq!(
            unsafe { api.broadphase_handle(world_id, &own) }.unwrap(),
            Some(0)
        );
        table.u32(at + 0x44, 2 | 8);
        table.u32(at + 0x78, u32::MAX);
        assert_eq!(
            unsafe { api.broadphase_handle(world_id, &own) }.unwrap(),
            None
        );
        table.u32(at + 0x78, 0x60001234);
        assert_eq!(
            unsafe { api.broadphase_handle(world_id, &own) }.unwrap(),
            Some(0x60001234)
        );
        table.u32(at + 0x70, 0x01000003); // Never report a replacement generation.
        assert!(unsafe { api.broadphase_handle(world_id, &own) }.is_err());
    }
    #[test]
    fn collider_probes_cross_requested_box_faces_in_havok_coordinates() {
        let bounds = [10., 20., 30., 11., 22., 33.];
        let offset = [8., 16., 24.];
        assert_eq!(
            probe_ray(bounds, offset, 0).unwrap(),
            ([3.125, 5., 7.5], [-1.25, 0., 0.])
        );
        assert_eq!(
            probe_ray(bounds, offset, 1).unwrap(),
            ([2.5, 6.125, 7.5], [0., -2.25, 0.])
        );
        assert_eq!(
            probe_ray(bounds, offset, 2).unwrap(),
            ([2.5, 5., 9.125], [0., 0., -3.25])
        );
        assert!(probe_ray(bounds, offset, 3).is_err());
    }
    #[test]
    fn template_pair_filter_rejects_query_only_layer_and_equal_nonzero_groups() {
        // A body visible to layer88 may still fail the real character's row.
        assert!(layer_pair_allowed(88, 55, 1u64 << 55));
        assert!(!layer_pair_allowed(2, 55, 0));
        assert!(layer_pair_allowed(2, 56, 1u64 << 56));
        assert!(!layer_pair_allowed(2 | (1 << 7), 56 | (1 << 7), 1u64 << 56));
        assert!(layer_pair_allowed(2 | (1 << 7), 56 | (2 << 7), 1u64 << 56));
        assert!(layer_pair_allowed(2, 127, 1u64 << 63));
    }
    #[test]
    fn collision_filter_requires_exact_wrapper_world_and_child_before_matrix() {
        let api = mock_api();
        let mut world = Native::<0x4e0>([0; 0x4e0]);
        let mut wrapper = Native::<0x40>([0; 0x40]);
        let mut child = Native::<0x820>([0; 0x820]);
        let w = World {
            cs: 0,
            hk: world.0.as_ptr() as usize,
        };
        world.usize(0x4d0, wrapper.0.as_ptr() as usize);
        wrapper.usize(0, CONSTRAINT_FILTER_VTABLE);
        wrapper.usize(0x30, child.0.as_ptr() as usize);
        wrapper.usize(0x38, w.hk);
        child.usize(0, COLLISION_FILTER_VTABLE);
        let bit = 1u64 << 56;
        child.0[0x40..0x48].copy_from_slice(&bit.to_le_bytes());
        assert!(unsafe { api.filter_pair_allowed(w, 2, 56) }.unwrap());
        assert!(!unsafe { api.filter_pair_allowed(w, 2, 55) }.unwrap());
        wrapper.usize(0x38, 0);
        assert_eq!(
            unsafe { api.collision_filter(w) },
            Err("collider native constraint-filter world mismatch")
        );
        wrapper.usize(0x38, w.hk);
        child.usize(0, CONSTRAINT_FILTER_VTABLE);
        assert_eq!(
            unsafe { api.collision_filter(w) },
            Err("collider native collision-filter child vtable mismatch")
        );
        child.usize(0, COLLISION_FILTER_VTABLE);
        wrapper.usize(0x30, 0);
        assert_eq!(
            unsafe { api.collision_filter(w) },
            Err("collider native collision-filter child unavailable")
        );
        // The original incorrect direct-child assumption must now fail closed.
        world.usize(0x4d0, child.0.as_ptr() as usize);
        assert_eq!(
            unsafe { api.collision_filter(w) },
            Err("collider native constraint-filter vtable mismatch")
        );
    }
}
