//! A one-shot, native position-set/synchronize transaction for an acknowledged
//! vanilla ender-pearl impact. No direct coordinate writes or save warps.
//! Destination admission is deliberately conservative: sampled known terrain,
//! current guest collision boxes, and live native character-filter rays. The
//! ray lattice is not advertised as a general Havok capsule-overlap API.
use crate::{native_colliders, world_native, world_wire as wire};
use eldenring::cs::{
    CSChrPhysicsModule, CSMenuManImp, CSSessionManager, GameMan, LadderState, LobbyState,
    PlayerIns, ProtocolState,
};
use fromsoftware_shared::{FromStatic, program::Program};
use pelite::pe64::PeObject;
use std::ffi::c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcessId() -> u32;
}
#[link(name = "user32")]
unsafe extern "system" {
    fn GetForegroundWindow() -> *mut c_void;
    fn GetWindowThreadProcessId(w: *mut c_void, p: *mut u32) -> u32;
}

pub const CLEARANCE_RAYS: usize = 59; // five support rays and 27 bidirectional lines
const SET_POSITION: usize = 0x45fe70;
const SYNC_POSITION: usize = 0x45c910;
const PREFIXES: &[(usize, &[u8])] = &[
    (
        SET_POSITION,
        &[
            0x0f, 0x28, 0x02, 0x0f, 0x11, 0x41, 0x70, 0x0f, 0x28, 0x02, 0x0f, 0x11, 0x81, 0x80,
            0x00, 0x00, 0x00, 0x66, 0xc7, 0x81, 0x90, 0x00, 0x00, 0x00, 0x01, 0x01,
        ],
    ),
    (
        SYNC_POSITION,
        &[
            0x40, 0x53, 0x48, 0x83, 0xec, 0x40, 0x48, 0x8b, 0x05, 0xf3, 0x24, 0x80, 0x03, 0x48,
            0x33, 0xc4, 0x48, 0x89, 0x44, 0x24, 0x30, 0x80, 0xb9, 0x91,
        ],
    ),
    (
        0xc5d860,
        &[
            0x40, 0x57, 0x48, 0x83, 0xec, 0x40, 0x48, 0xc7, 0x44, 0x24, 0x20, 0xfe, 0xff, 0xff,
            0xff, 0x48, 0x89, 0x5c, 0x24, 0x50,
        ],
    ),
];
#[repr(C, align(16))]
struct Position([f32; 4]);
type Setter = unsafe extern "system" fn(*mut c_void, *const Position);
type Sync = unsafe extern "system" fn(*mut c_void);
pub struct Sink {
    set: Setter,
    sync: Sync,
    rays: native_colliders::Api,
}
#[derive(Clone, Copy, Debug)]
pub struct Receipt {
    pub before: [f64; 3],
    pub after: [f64; 3],
}
#[derive(Clone, Copy, Debug)]
struct Identity {
    player: usize,
    physics: usize,
    map: u32,
    handle: u64,
    feet: [f64; 3],
    height: f64,
    radius: f64,
}
#[derive(Clone, Debug)]
pub struct Clearance {
    pub ground: Vec<([f64; 3], [f64; 3])>,
    pub lines: Vec<([f64; 3], [f64; 3])>,
}
fn distance(a: [f64; 3], b: [f64; 3]) -> f64 {
    (0..3).map(|i| (a[i] - b[i]).powi(2)).sum::<f64>().sqrt()
}
fn overlaps(a: [f64; 6], b: [f64; 6]) -> bool {
    (0..3).all(|i| a[i] < b[i + 3] - 0.001 && a[i + 3] > b[i] + 0.001)
}
pub fn covers_destination(
    known: [f64; 6],
    destination: [f64; 3],
    height: f64,
    radius: f64,
) -> bool {
    let r = radius.max(0.30) + 0.03;
    let h = height.max(1.80) + 0.03;
    wire::valid_box(known)
        && destination[0] - r >= known[0] + 0.01
        && destination[0] + r <= known[3] - 0.01
        && destination[2] - r >= known[2] + 0.01
        && destination[2] + r <= known[5] - 0.01
        && destination[1] - 1.5 >= known[1]
        && destination[1] + h <= known[4] - 0.01
}
pub fn landing_bounds(destination: [f64; 3], height: f64, radius: f64) -> [f64; 6] {
    let r = radius.max(0.30) + 0.05;
    let h = height.max(1.80) + 0.05;
    [
        destination[0] - r,
        destination[1] - 1.52,
        destination[2] - r,
        destination[0] + r,
        destination[1] + h,
        destination[2] + r,
    ]
}

impl Clearance {
    pub fn prepare(
        feet: [f64; 3],
        destination: [f64; 3],
        impact: [f64; 3],
        height: f64,
        radius: f64,
        known: [f64; 6],
        blocks: &[wire::Block],
    ) -> Result<Self, &'static str> {
        if !wire::finite_position(destination)
            || !wire::finite_position(feet)
            || !wire::finite_position(impact)
            || distance(feet, destination) > 64.
            || distance(destination, impact) > 8.05
            || !height.is_finite()
            || !(0.8..=3.0).contains(&height)
            || !radius.is_finite()
            || !(0.1..=1.0).contains(&radius)
        {
            return Err("pearl destination or capsule bounds invalid");
        }
        // Include the genuine MC body and the wider/taller native capsule.
        let r = radius.max(0.30) + 0.03;
        let h = height.max(1.80) + 0.03;
        let bounds = [
            destination[0] - r,
            destination[1] + 0.04,
            destination[2] - r,
            destination[0] + r,
            destination[1] + h,
            destination[2] + r,
        ];
        if !wire::valid_box(known)
            || (0..3).any(|i| bounds[i] < known[i] + 0.01 || bounds[i + 3] > known[i + 3] - 0.01)
            || destination[1] - 1.5 < known[1]
        {
            return Err("pearl destination outside verified terrain coverage");
        }
        // Sampled terrain patches deliberately over-approximate surfaces and
        // can hang into real empty space. They establish coverage, not a final
        // landing obstruction. Actual native support/prism rays below decide
        // host geometry; genuine placed MC shapes remain authoritative here.
        if blocks
            .iter()
            .flat_map(|b| b.boxes.iter())
            .any(|b| overlaps(bounds, *b))
        {
            return Err("pearl destination capsule intersects Minecraft geometry");
        }
        let ground = [
            [0., 0.],
            [r * 0.6, 0.],
            [-r * 0.6, 0.],
            [0., r * 0.6],
            [0., -r * 0.6],
        ]
        .into_iter()
        .map(|[x, z]| {
            (
                [
                    destination[0] + x,
                    destination[1] + 0.45,
                    destination[2] + z,
                ],
                [0., -1.95, 0.],
            )
        })
        .collect();
        let mut lines = Vec::with_capacity(27);
        let xs = [bounds[0], destination[0], bounds[3]];
        let ys = [bounds[1], (bounds[1] + bounds[4]) * 0.5, bounds[4]];
        let zs = [bounds[2], destination[2], bounds[5]];
        for y in ys {
            for z in zs {
                lines.push(([bounds[0], y, z], [bounds[3], y, z]));
            }
        }
        for y in ys {
            for x in xs {
                lines.push(([x, y, bounds[2]], [x, y, bounds[5]]));
            }
        }
        for x in xs {
            for z in zs {
                lines.push(([x, bounds[1], z], [x, bounds[4], z]));
            }
        }
        Ok(Self { ground, lines })
    }
}
impl Sink {
    pub unsafe fn dimensions(&self) -> Result<(f64, f64), &'static str> {
        let p = unsafe { identity() }?;
        Ok((p.height, p.radius))
    }
    pub fn resolve() -> Result<Self, &'static str> {
        let program = Program::current();
        let image = program.image();
        if std::mem::offset_of!(CSChrPhysicsModule, position) != 0x70
            || std::mem::offset_of!(CSChrPhysicsModule, last_update_position) != 0x80
            || std::mem::offset_of!(CSChrPhysicsModule, chr_proxy_pos_update_requested) != 0x91
        {
            return Err("pearl SDK layout mismatch");
        }
        for &(rva, bytes) in PREFIXES {
            if image.get(rva..rva + bytes.len()) != Some(bytes) {
                return Err("pearl native fingerprint mismatch");
            }
        }
        let base = image.as_ptr() as usize;
        Ok(Self {
            set: unsafe { std::mem::transmute::<usize, Setter>(base + SET_POSITION) },
            sync: unsafe { std::mem::transmute::<usize, Sync>(base + SYNC_POSITION) },
            rays: native_colliders::Api::resolve()?,
        })
    }
    /// Caller consumed the receipt exactly once and verified its genuine peer,
    /// epoch, owned projectile path and impact frame. PostPhysics only.
    pub unsafe fn apply(
        &self,
        expected: u64,
        scene: &world_native::Snapshot,
        e: &wire::Event,
        blocks: &[wire::Block],
        mutation_started: &mut bool,
    ) -> Result<Receipt, &'static str> {
        if !scene.terrain_ready
            || scene.coordinate_mode != world_native::CoordinateMode::BlockCenter
        {
            return Err("pearl needs verified stable terrain coordinates");
        }
        let destination = e.destination.ok_or("pearl destination missing")?;
        let initial = unsafe { identity() }?;
        if initial.handle != expected
            || initial.map != scene.source_map
            || distance(
                initial.feet,
                std::array::from_fn(|i| scene.feet[i] - scene.offset[i]),
            ) > 0.25
        {
            return Err("pearl player changed before relocation");
        }
        let clearance = Clearance::prepare(
            scene.feet,
            destination,
            e.position,
            initial.height,
            initial.radius,
            scene.terrain_bounds,
            blocks,
        )?;
        let world = unsafe { self.rays.world() }?;
        let filter = unsafe { self.rays.character_filter(world) }?;
        for (origin, delta) in clearance.ground {
            let origin = std::array::from_fn(|i| origin[i] - scene.offset[i]);
            let Some(hit) = unsafe { self.rays.ray(world, origin, delta, filter) }? else {
                return Err("pearl destination has no native support");
            };
            let gap = destination[1] - (hit.point[1] + scene.offset[1]);
            if !(-0.04..=1.5).contains(&gap) || hit.normal[1] < 0.60 {
                return Err("pearl native support is unsafe");
            }
        }
        for (a, b) in clearance.lines {
            for (a, b) in [(a, b), (b, a)] {
                let origin = std::array::from_fn(|i| a[i] - scene.offset[i]);
                let delta = std::array::from_fn(|i| b[i] - a[i]);
                if unsafe { self.rays.ray(world, origin, delta, filter) }?.is_some() {
                    return Err("pearl native capsule clearance obstructed");
                }
            }
        }
        // Reacquire after all native queries. Never retain SDK references across
        // either position call or the listener/proxy synchronization it invokes.
        let current = unsafe { identity() }?;
        if current.player != initial.player
            || current.physics != initial.physics
            || current.map != initial.map
            || current.handle != initial.handle
            || distance(current.feet, initial.feet) > 0.05
        {
            return Err("pearl player changed during clearance");
        }
        let requested: [f64; 3] = std::array::from_fn(|i| destination[i] - scene.offset[i]);
        let value = Position([
            requested[0] as f32,
            requested[1] as f32,
            requested[2] as f32,
            0.,
        ]);
        // The caller must revoke owned movement even if a post-call readback
        // rejects. Mutation cannot be rolled back from an unverified snapshot.
        *mutation_started = true;
        unsafe {
            (self.set)(current.physics as *mut c_void, &value);
            (self.sync)(current.physics as *mut c_void);
        }
        let after = unsafe { identity() }?;
        let proxy_after = unsafe { self.rays.character_position(world) }?;
        if after.player != current.player
            || after.physics != current.physics
            || after.handle != current.handle
            || distance(after.feet, requested) > 0.02
            || distance(proxy_after, requested) > 0.02
        {
            return Err("pearl native relocation readback disagrees");
        }
        Ok(Receipt {
            before: scene.feet,
            after: std::array::from_fn(|i| after.feet[i] + scene.offset[i]),
        })
    }
}
unsafe fn identity() -> Result<Identity, &'static str> {
    let mut pid = 0;
    unsafe {
        GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
    }
    if pid != unsafe { GetCurrentProcessId() } {
        return Err("pearl foreground gate closed");
    }
    let game = unsafe { GameMan::instance() }.map_err(|_| "pearl game unavailable")?;
    let session =
        unsafe { CSSessionManager::instance() }.map_err(|_| "pearl session unavailable")?;
    if game.is_in_online_mode
        || game.warp_requested
        || session.lobby_state != LobbyState::None
        || session.protocol_state != ProtocolState::None
    {
        return Err("pearl offline gate closed");
    }
    let menu = unsafe { CSMenuManImp::instance() }.map_err(|_| "pearl menu unavailable")?;
    if !unsafe { menu.system_announce_view_model.view.as_ref() }.is_active {
        return Err("pearl blocking menu open");
    }
    let p = unsafe { PlayerIns::local_player() }.map_err(|_| "pearl player unavailable")?;
    let c = &p.chr_ins;
    if !c.chr_flags1c8.is_active()
        || !c.chr_flags1c8.update_tasks_registered()
        || c.chr_flags1c5.death_flag()
        || c.modules.data.hp <= 0
        || p.current_block_id.0 == -1
        || c.modules.ride.is_mounted
        || c.modules.ride.is_mounting
        || c.chr_ctrl.disable_move
        || c.modules.ladder.state != LadderState::None
        || !unsafe { c.chr_set_entry.as_ref() }
            .chr_ins
            .is_some_and(|v| std::ptr::eq(v.as_ptr(), c))
    {
        return Err("pearl local player inactive or scripted");
    }
    let physics = &*c.modules.physics;
    if !std::ptr::eq(physics.owner.as_ptr(), c) || physics.fade_out_gravity_disabled {
        return Err("pearl physics owner/transition gate");
    }
    let h = c.field_ins_handle;
    let pos = physics.position;
    Ok(Identity {
        player: p as *const _ as usize,
        physics: physics as *const _ as usize,
        map: p.current_block_id.0 as u32,
        handle: u64::from(h.selector.0) | (u64::from(h.block_id.0 as u32) << 32),
        feet: [pos.0 as f64, pos.1 as f64, pos.2 as f64],
        height: physics.hit_height as f64,
        radius: physics.hit_radius as f64,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    fn plan(destination: [f64; 3], blocks: &[wire::Block]) -> Result<Clearance, &'static str> {
        Clearance::prepare(
            [0., 2., 0.],
            destination,
            destination,
            1.8,
            0.3,
            [-16., -4., -16., 16., 8., 16.],
            blocks,
        )
    }
    // Sampled shadow boxes are not an input at all: only coverage and real
    // placed blocks decide clearance; native rays check the host geometry.
    #[test]
    fn pearl_clearance_uses_real_blocks_and_coverage_not_sampled_shadow_boxes() {
        assert!(plan([2., 0., 2.], &[]).is_ok());
        assert!(plan([15.9, 0., 0.], &[]).is_err());
        assert!(
            plan(
                [2., 0., 2.],
                &[wire::Block {
                    boxes: vec![[1., 0., 1., 3., 1., 3.]],
                    ..Default::default()
                }]
            )
            .is_err()
        );
    }
    #[test]
    fn pearl_native_clearance_budget_is_fixed_and_capsule_dimensions_are_bounded() {
        let p = plan([2., 0., 2.], &[]).unwrap();
        assert_eq!(p.ground.len() + p.lines.len() * 2, CLEARANCE_RAYS);
        assert_eq!(std::mem::align_of::<Position>(), 16);
        assert_eq!(std::mem::size_of::<Position>(), 16);
        assert!(plan([f64::NAN, 0., 0.], &[]).is_err());
        assert!(
            Clearance::prepare(
                [0.; 3],
                [2.; 3],
                [2.; 3],
                0.2,
                0.3,
                [-16., -4., -16., 16., 8., 16.],
                &[]
            )
            .is_err()
        );
    }
}
