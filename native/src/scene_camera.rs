//! Read-only export at the main graphics-camera submission boundary.
//! DrawParamUpdate grants a short-lived permit; it does not publish its newer
//! gameplay camera. This is a CPU submission identity, not GPU/present identity.
use eldenring::cs::PlayerIns;
use fromsoftware_shared::program::Program;
use ilhook::x64::{HookFlags, Registers, hook_closure_jmp_back};
use pelite::pe64::PeObject;
use std::{
    ffi::c_void,
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

const MAX_AGE_MS: u64 = 100;
const HANDOFF_RVA: usize = 0xccd106;
const RENDER_ROOT_CELL: usize = 0x3d7f130;
const PERS_CAMERA_VTABLE: usize = 0x2aa0a40;
const PERS_CAMERA_SUBMIT: usize = 0xb13730;
const FINGERPRINTS: &[(usize, &[u8])] = &[
    (
        0xb15790,
        &[
            0x48, 0x8b, 0x0d, 0x99, 0x99, 0x26, 0x03, 0x48, 0x85, 0xc9, 0x74, 0x05, 0xe8, 0x7f,
            0x3c, 0x1b, 0x00,
        ],
    ),
    (
        0xcc9c53,
        &[
            0x48, 0x8b, 0x4f, 0x10, 0x48, 0x85, 0xc9, 0x74, 0x08, 0x48, 0x8b, 0xd3, 0xe8, 0x6c,
            0x31, 0x00, 0x00,
        ],
    ),
    (
        0xccd0f8,
        &[
            0x48, 0x8b, 0x4e, 0x28, 0x48, 0x8b, 0x01, 0x48, 0x8b, 0x56, 0x18, 0xff, 0x50, 0x18,
            0x48, 0x8b, 0x46, 0x18, 0x44, 0x38, 0xae, 0x80, 0x07, 0x00, 0x00,
        ],
    ),
    (
        0x1a55500,
        &[
            0x0f, 0x28, 0x02, 0x0f, 0x29, 0x01, 0x0f, 0x28, 0x4a, 0x10, 0x0f, 0x29, 0x49, 0x10,
            0x0f, 0x28, 0x42, 0x20, 0x0f, 0x29, 0x41, 0x20, 0x0f, 0x28, 0x4a, 0x30, 0x0f, 0x29,
            0x49, 0x30, 0xc3,
        ],
    ),
    (
        0xb13762,
        &[
            0xf3, 0x44, 0x0f, 0x10, 0x41, 0x50, 0x48, 0x8b, 0xf9, 0xf3, 0x48, 0x0f, 0x2a, 0xce,
            0xf3, 0x48, 0x0f, 0x2a, 0xc5, 0xf3, 0x0f, 0x5e, 0xc8, 0xf3, 0x0f, 0x11, 0x49, 0x54,
        ],
    ),
    (
        0xb13818,
        &[
            0x8b, 0x47, 0x58, 0x4c, 0x8d, 0x9c, 0x24, 0x80, 0, 0, 0, 0x89, 0x43, 0x54, 0x8b, 0x47,
            0x5c, 0xf3, 0x44, 0x0f, 0x11, 0x43, 0x50,
        ],
    ),
    (
        0xb13865,
        &[0x8b, 0x47, 0x54, 0x49, 0x8b, 0x6b, 0x18, 0x89, 0x43, 0x6c],
    ),
];
static INSTALLED: AtomicBool = AtomicBool::new(false);
static FAULTED: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Copy, Debug)]
struct Transform {
    epoch: u64,
    map: u32,
    source_map: u32,
    offset: [f64; 3],
    issued: u64,
}
impl Transform {
    fn same_context(self, other: Self) -> bool {
        self.epoch == other.epoch
            && self.map == other.map
            && self.source_map == other.source_map
            && self.offset == other.offset
    }
}
#[derive(Clone, Copy)]
struct Permit {
    issued: u64,
    generation: u64,
}
#[derive(Default)]
struct State {
    world: Option<Transform>,
    permit: Option<Permit>,
    camera: Option<[u8; 256]>,
    generation: u64,
    frame: u64,
}
impl State {
    fn invalidate(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        self.permit = None;
        self.camera = None;
    }
    fn set_world(&mut self, world: Transform) {
        if !self.world.is_some_and(|old| old.same_context(world)) {
            self.invalidate();
        }
        self.world = Some(world);
    }
    fn authorized(&self, now: u64) -> Option<Transform> {
        let t = self.world?;
        let p = self.permit?;
        (p.generation == self.generation && fresh(p.issued, now) && fresh(t.issued, now))
            .then_some(t)
    }
}
static STATE: Mutex<State> = Mutex::new(State {
    world: None,
    permit: None,
    camera: None,
    generation: 0,
    frame: 0,
});
fn fresh(timestamp: u64, now: u64) -> bool {
    now >= timestamp && now - timestamp <= MAX_AGE_MS
}

/// Shared-world `(map, region position)` of a Havok point, where enemy loot
/// lands. The world bridge refreshes its transform every 50 ms; an older or
/// suspended world places nothing.
pub fn region_position(havok: [f32; 3]) -> Option<(u32, [f64; 3])> {
    const LOOT_AGE_MS: u64 = 500;
    let world = STATE.lock().ok()?.world?;
    let now = crate::world_transport::now();
    (havok.iter().all(|v| v.is_finite())
        && now >= world.issued
        && now - world.issued <= LOOT_AGE_MS)
        .then(|| {
            (
                world.map,
                std::array::from_fn(|i| havok[i] as f64 + world.offset[i]),
            )
        })
}

/// Verified prefix: matrix copied by1A55500; effective intrinsics byB13730.
#[repr(C)]
#[derive(Clone, Copy)]
struct GraphicsCamera {
    matrix: [[f32; 4]; 4],
    unknown40: [u8; 16],
    fov: f32,
    near: f32,
    far: f32,
    width: u32,
    height: u32,
    unknown64: [u8; 8],
    aspect: f32,
}
pub fn set_world(epoch: u64, map: u32, source_map: u32, offset: [f64; 3]) {
    if let Ok(mut state) = STATE.try_lock() {
        state.set_world(Transform {
            epoch,
            map,
            source_map,
            offset,
            issued: crate::world_transport::now(),
        });
    }
}
pub fn suspend() {
    if let Ok(mut state) = STATE.try_lock() {
        state.invalidate();
        state.world = None;
    }
}

/// Install only after the existing exact executable SHA guard and module pin.
/// A mismatch disables scene export; HUD export is independent.
/// # Safety
/// The caller must keep this module pinned and install before gameplay starts.
pub unsafe fn install() -> Result<(), String> {
    let program = Program::current();
    let image = program.image();
    for &(at, bytes) in FINGERPRINTS {
        if image.get(at..at + bytes.len()) != Some(bytes) {
            return Err(format!("scene-camera fingerprint mismatch at {at:x}"));
        }
    }
    let base = image.as_ptr() as usize;
    let submit = base + PERS_CAMERA_SUBMIT;
    if image.get(PERS_CAMERA_VTABLE + 24..PERS_CAMERA_VTABLE + 32)
        != Some(submit.to_le_bytes().as_slice())
    {
        return Err("scene-camera submit vtable mismatch".into());
    }
    if INSTALLED.swap(true, Ordering::AcqRel) {
        return Err("scene-camera handoff already installed".into());
    }
    let callback = move |registers: *mut Registers| {
        if FAULTED.load(Ordering::Acquire) {
            return;
        }
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
            // Inspect only: saved registers, flags and original instructions
            // are unchanged; no camera object or native return value is edited.
            latch(base, (*registers).rsi as usize);
        }));
        if result.is_err() {
            FAULTED.store(true, Ordering::Release);
            suspend();
        }
    };
    let hook = unsafe {
        crate::hosting::hook(base + HANDOFF_RVA, |option| {
            hook_closure_jmp_back(base + HANDOFF_RVA, callback, option, HookFlags::empty())
        })
    };
    match hook {
        Ok(hook) => {
            std::mem::forget(hook);
            Ok(())
        }
        Err(error) => {
            INSTALLED.store(false, Ordering::Release);
            Err(format!("scene-camera handoff install failed: {error:?}"))
        }
    }
}
/// Refresh authorization after the driver's offline/foreground/identity gates.
/// No gameplay camera is exported here.
/// # Safety
/// Called only on the existing DrawParamUpdate game callback.
pub unsafe fn capture(active: bool) {
    let now = crate::world_transport::now();
    let source_map =
        if active && INSTALLED.load(Ordering::Acquire) && !FAULTED.load(Ordering::Acquire) {
            unsafe {
                PlayerIns::local_player()
                    .ok()
                    .map(|p| p.current_block_id.0 as u32)
            }
        } else {
            None
        };
    if let Ok(mut state) = STATE.try_lock() {
        if state
            .world
            .is_some_and(|t| source_map == Some(t.source_map) && fresh(t.issued, now))
        {
            state.permit = Some(Permit {
                issued: now,
                generation: state.generation,
            });
        } else {
            state.invalidate();
        }
    }
}
unsafe fn latch(base: usize, owner: usize) {
    let now = crate::world_transport::now();
    let Ok(mut state) = STATE.try_lock() else {
        return;
    };
    let Some(world) = state.authorized(now) else {
        state.camera = None;
        return;
    };
    // Verified main call chain: singleton -> owner+0x10. Reject preview cameras.
    let root = unsafe { std::ptr::read_unaligned((base + RENDER_ROOT_CELL) as *const usize) };
    if root == 0 || owner == 0 || root & 7 != 0 || owner & 7 != 0 {
        state.camera = None;
        return;
    }
    let main_owner = unsafe { std::ptr::read_unaligned((root + 0x10) as *const usize) };
    // Another owner's submission is not ours to export, but it must not erase
    // the main camera already latched this frame (its own age bound still applies).
    if main_owner != owner {
        return;
    }
    let camera = unsafe { std::ptr::read_unaligned((owner + 0x28) as *const usize) };
    let graphics = unsafe { std::ptr::read_unaligned((owner + 0x18) as *const usize) };
    if camera == 0 || camera & 7 != 0 || graphics == 0 || graphics & 15 != 0 {
        state.camera = None;
        return;
    }
    if unsafe { std::ptr::read_unaligned(camera as *const usize) } != base + PERS_CAMERA_VTABLE {
        state.camera = None;
        return;
    }
    let description = unsafe { std::ptr::read_unaligned(graphics as *const GraphicsCamera) };
    let frame = state.frame.saturating_add(1);
    state.camera = encode(description, world, now, frame);
    if state.camera.is_some() {
        state.frame = frame;
    }
}
fn encode(c: GraphicsCamera, t: Transform, now: u64, frame: u64) -> Option<[u8; 256]> {
    let position = std::array::from_fn(|i| c.matrix[3][i] as f64 + t.offset[i]);
    let basis: [[f32; 3]; 3] = std::array::from_fn(|i| std::array::from_fn(|j| c.matrix[i][j]));
    if !crate::world_wire::finite_position(position)
        || c.matrix.iter().flatten().any(|v| !v.is_finite())
        || basis
            .iter()
            .any(|v| !(0.95..=1.05).contains(&v.iter().map(|x| x * x).sum::<f32>()))
        || [(0, 1), (0, 2), (1, 2)].iter().any(|&(a, b)| {
            basis[a]
                .iter()
                .zip(basis[b])
                .map(|(x, y)| x * y)
                .sum::<f32>()
                .abs()
                > 0.02
        })
        || !c.fov.is_finite()
        || !(0.01..3.13).contains(&c.fov)
        || !c.aspect.is_finite()
        || !(0.2..10.).contains(&c.aspect)
        || !(1..=16384).contains(&c.width)
        || !(1..=16384).contains(&c.height)
        || (c.aspect - c.width as f32 / c.height as f32).abs() > 0.01
        || !c.near.is_finite()
        || c.near <= 0.
        || !c.far.is_finite()
        || c.far <= c.near
    {
        return None;
    }
    let mut bytes = [0u8; 256];
    macro_rules! put {
        ($at:expr,$value:expr) => {{
            let value = $value.to_le_bytes();
            bytes[$at..$at + value.len()].copy_from_slice(&value);
        }};
    }
    put!(0, 0x4d414345u32);
    put!(4, 1u32);
    put!(8, now);
    put!(16, frame);
    put!(24, t.epoch);
    put!(32, t.map);
    put!(36, t.source_map);
    for i in 0..3 {
        put!(40 + i * 8, position[i]);
        for row in 0..3 {
            put!(64 + row * 16 + i * 4, basis[row][i]);
        }
    }
    put!(108, c.fov);
    put!(112, c.aspect);
    put!(116, c.near);
    put!(120, c.far);
    Some(bytes)
}
#[unsafe(no_mangle)]
/// # Safety
/// The compositor supplies writable storage of at least `bytes` bytes.
pub unsafe extern "C" fn eldencraft_scene_camera(output: *mut c_void, bytes: u32) -> u32 {
    if output.is_null()
        || bytes < 256
        || FAULTED.load(Ordering::Acquire)
        || crate::engine::eldencraft_passthrough_active() == 0
    {
        return 0;
    }
    // Every holder copies a few hundred bytes. Blocking here is bounded and
    // avoids a contended try_lock hiding the whole scene for a presented frame.
    let Ok(state) = STATE.lock() else {
        return 0;
    };
    let now = crate::world_transport::now();
    if state.authorized(now).is_none() {
        return 0;
    }
    let Some(value) = state.camera.as_ref() else {
        return 0;
    };
    let timestamp = u64::from_le_bytes(value[8..16].try_into().unwrap());
    if !fresh(timestamp, now) {
        return 0;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(value.as_ptr(), output.cast(), 256);
    }
    1
}
#[cfg(test)]
mod tests {
    use super::*;
    fn world() -> Transform {
        Transform {
            epoch: 7,
            map: 11,
            source_map: 12,
            offset: [100., 200., 300.],
            issued: 1000,
        }
    }
    fn camera() -> GraphicsCamera {
        GraphicsCamera {
            matrix: [
                [1., 0., 0., 0.],
                [0., 1., 0., 0.],
                [0., 0., 1., 0.],
                [2., 3., 4., 1.],
            ],
            unknown40: [0; 16],
            fov: 1.1,
            near: 0.025,
            far: 1000.,
            width: 1920,
            height: 1080,
            unknown64: [0; 8],
            aspect: 1920. / 1080.,
        }
    }
    fn f32_at(b: &[u8], at: usize) -> f32 {
        f32::from_le_bytes(b[at..at + 4].try_into().unwrap())
    }
    #[test]
    fn native_description_layout_matches_verified_submission() {
        assert_eq!(std::mem::size_of::<GraphicsCamera>(), 0x70);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, fov), 0x50);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, near), 0x54);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, far), 0x58);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, width), 0x5c);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, height), 0x60);
        assert_eq!(std::mem::offset_of!(GraphicsCamera, aspect), 0x6c);
    }
    #[test]
    fn submission_exports_effective_intrinsics_and_canonical_position() {
        let c = camera();
        let b = encode(c, world(), 1001, 2).unwrap();
        assert_eq!(f32_at(&b, 108), c.fov);
        assert_eq!(f32_at(&b, 112), c.aspect);
        assert_eq!(f32_at(&b, 116), c.near);
        assert_eq!(f32_at(&b, 120), c.far);
        assert_eq!(f64::from_le_bytes(b[40..48].try_into().unwrap()), 102.);
        assert_eq!(f64::from_le_bytes(b[48..56].try_into().unwrap()), 203.);
        assert_eq!(f64::from_le_bytes(b[56..64].try_into().unwrap()), 304.);
        assert_eq!(&b[128..], &[0; 128]);
    }
    #[test]
    fn invalid_camera_rejected_but_valid_handedness_preserved() {
        let mut c = camera();
        c.matrix[0][0] = -1.;
        assert!(encode(c, world(), 1000, 1).is_some());
        c.matrix[0] = c.matrix[1];
        assert!(encode(c, world(), 1000, 1).is_none());
        let mut c = camera();
        c.matrix[3][2] = f32::NAN;
        assert!(encode(c, world(), 1000, 1).is_none());
        let mut c = camera();
        c.near = 0.;
        assert!(encode(c, world(), 1000, 1).is_none());
        let mut c = camera();
        c.aspect = 4. / 3.;
        assert!(encode(c, world(), 1000, 1).is_none());
        let mut c = camera();
        c.width = 0;
        assert!(encode(c, world(), 1000, 1).is_none());
    }
    #[test]
    fn requires_fresh_world_and_camera_driver_permit() {
        let mut s = State::default();
        s.set_world(world());
        assert!(s.authorized(1000).is_none());
        s.permit = Some(Permit {
            issued: 1020,
            generation: s.generation,
        });
        assert!(s.authorized(1019).is_none());
        assert!(s.authorized(1100).is_some());
        assert!(s.authorized(1101).is_none());
        let mut w = world();
        w.issued = 1121;
        s.set_world(w);
        assert!(s.authorized(1121).is_none());
    }
    #[test]
    fn context_change_and_suspend_discard_submission_and_permit() {
        let mut s = State::default();
        s.set_world(world());
        s.permit = Some(Permit {
            issued: 1000,
            generation: s.generation,
        });
        s.camera = encode(camera(), world(), 1000, 1);
        let mut same = world();
        same.issued = 1001;
        s.set_world(same);
        assert!(s.camera.is_some());
        let mut rebase = same;
        rebase.offset[0] += 1.;
        s.set_world(rebase);
        assert!(s.camera.is_none());
        assert!(s.authorized(1001).is_none());
        s.permit = Some(Permit {
            issued: 1001,
            generation: s.generation,
        });
        s.camera = encode(camera(), rebase, 1001, 2);
        s.invalidate();
        assert!(s.camera.is_none());
        assert!(s.authorized(1001).is_none());
    }
}
