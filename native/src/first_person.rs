//! A reversible, camera-only first-person view for the pinned Elden Ring SDK.
//!
//! Calling contract: construct anywhere, but call `update` and `suspend` only
//! from the game's task thread after the executable/version guard succeeds.
//! Run in `DrawParamUpdate` after the host `CameraStep` has finalized its camera. The
//! Diagnostic mode can retain host look. Passthrough supplies its own independent
//! basis; host locomotion and gravity are separate controllers.
//! Re-read the engine camera snapshot AFTER `update` for ray picking and HUD.
//! Call `suspend` on every focus/menu/world/disabled early-return path.
//!
//! No retained game references or unverified memory offsets are used. Recovery
//! reacquires live objects and restores only properties that still equal our
//! last write. A replaced player/camera/map releases ownership without touching
//! an old pointer. Coordinate-independent FOV/near-plane writes can still be
//! restored on the same live camera after a map change. There is intentionally
//! no Drop implementation: dropping on
//! an arbitrary thread cannot safely call the game's SDK.

use eldenring::cs::{
    CSCamera, CSMenuManImp, CSSessionManager, GameMan, LadderState, LobbyState, PlayerIns,
    ProtocolState,
};
use fromsoftware_shared::{F32Vector4, FromStatic};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug)]
pub struct Settings {
    /// Design height above the player's physics origin; not a skeletal bone.
    pub eye_height_m: f32,
    /// Small horizontal displacement along the host's look direction.
    pub forward_offset_m: f32,
    /// None preserves the host's field of view.
    pub vertical_fov_radians: Option<f32>,
    /// Small near plane allows a camera-facing HUD in front of the eye.
    pub near_plane_m: Option<f32>,
    /// Optional whole-character render suppression. This also hides equipment;
    /// it does not supply a first-person arms model or change physics/combat.
    pub hide_body: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            eye_height_m: 1.62,
            forward_offset_m: 0.12,
            vertical_fov_radians: None,
            near_plane_m: Some(0.05),
            hide_body: false,
        }
    }
}

impl Settings {
    pub fn validate(self) -> Result<Self, &'static str> {
        if !self.eye_height_m.is_finite() || !(0.8..=2.4).contains(&self.eye_height_m) {
            return Err("first-person eye height must be 0.8 to 2.4 meters");
        }
        if !self.forward_offset_m.is_finite() || !(0.0..=0.4).contains(&self.forward_offset_m) {
            return Err("first-person forward offset must be 0 to 0.4 meters");
        }
        if self
            .vertical_fov_radians
            .is_some_and(|v| !v.is_finite() || !(0.5..=1.75).contains(&v))
        {
            return Err("first-person FOV must be 0.5 to 1.75 radians");
        }
        if self
            .near_plane_m
            .is_some_and(|v| !v.is_finite() || !(0.02..=0.2).contains(&v))
        {
            return Err("first-person near plane must be 0.02 to 0.2 meters");
        }
        Ok(self)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum RestoreStatus {
    #[default]
    Inactive,
    Restored,
    /// The host replaced an object, changed coordinate frame, or overwrote all
    /// our properties. Its current values were retained.
    ReleasedToHost,
}

#[derive(Clone, Copy, Debug)]
pub struct Status {
    pub active: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Identity {
    player: usize,
    map: i32,
}

/// Issued only after the full post-physics liveness checks. DrawParamUpdate rechecks
/// identity and all phase-independent gates, without reading phase-local bits.
pub(crate) struct FramePermit {
    identity: Identity,
    issued: Instant,
    /// Issued only while the interaction bridge has a supported replacement UI.
    blocking_menu: bool,
}
impl FramePermit {
    pub(crate) fn fresh(&self) -> bool {
        self.issued.elapsed() <= Duration::from_millis(100)
    }
    pub(crate) fn identity_token(&self) -> (usize, i32) {
        (self.identity.player, self.identity.map)
    }
}
pub(crate) unsafe fn authorize_frame() -> Result<FramePermit, &'static str> {
    unsafe { authorize_frame_for_menu(false) }
}
/// A blocking host menu may keep the presentation camera only when the caller
/// has identified a supported replacement. The ordinary gameplay permit stays
/// strict; this never authorizes movement, combat, or saved-game mutations.
pub(crate) unsafe fn authorize_frame_for_menu(
    blocking_menu: bool,
) -> Result<FramePermit, &'static str> {
    let (identity, _, _) = unsafe { live_player_frame(true, blocking_menu)? };
    Ok(FramePermit {
        identity,
        issued: Instant::now(),
        blocking_menu,
    })
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Logged together to compare requested and observed camera state.
pub struct CameraObservation {
    pub camera_mask: u32,
    pub observed_eye: [f32; 3],
    pub requested_eye: Option<[f32; 3]>,
    pub same_camera: bool,
    pub written_position_preserved: bool,
    pub boom_distance: Option<f32>,
    pub boom_view_mode: Option<u32>,
    pub boom_owner_filtered: bool,
}

#[derive(Clone, Copy)]
struct ScalarWrite {
    original: f32,
    written: f32,
}

impl ScalarWrite {
    fn restore(self, current: &mut f32) -> bool {
        if current.to_bits() == self.written.to_bits() {
            *current = self.original;
            true
        } else {
            false
        }
    }
}

/// Three camera basis rows as native aligned vectors.
type Basis4 = [[f32; 4]; 3];
struct OwnedWrites {
    identity: Identity,
    camera: usize,
    original_position: [f32; 4],
    written_position: [f32; 4],
    /// Original and written camera basis rows.
    basis: Option<(Basis4, Basis4)>,
    fov: Option<ScalarWrite>,
    near_plane: Option<ScalarWrite>,
    original_body_render: Option<bool>,
}

pub struct Controller {
    settings: Settings,
    writes: Option<OwnedWrites>,
    view_mode: u32,
    direct_basis: Option<[[f32; 3]; 3]>,
    fov_multiplier: f32,
    boom: Option<BoomState>,
    boom_queries: Option<Result<crate::native_colliders::Api, &'static str>>,
    locked: Option<LockedCamera>,
    last_desired: Option<LockedCamera>,
    view_bob: bool,
    bob: ViewBob,
    hurt: Option<(Instant, f32)>,
    hurt_tilt_strength: f32,
    /// Extra eye height while riding, eased toward its target from the last update.
    eye_lift: f32,
    eye_lift_target: f32,
    eye_lift_at: Option<Instant>,
    mounted_ground_camera: MountedGroundCamera,
}

/// Mount and dismount settle the rider's eye within about a quarter second.
const EYE_LIFT_RATE: f32 = 12.0;

/// Small grounded height corrections should not shake the saddle camera. Only
/// presentation Y is eased: native feet, horizontal motion and airborne jumps
/// remain exact. A new character, region, long gap or teleport starts afresh.
#[derive(Default)]
struct MountedGroundCamera {
    previous: Option<(Identity, [f32; 3], f32, Instant)>,
}
impl MountedGroundCamera {
    fn feet(
        &mut self,
        identity: Identity,
        feet: [f32; 3],
        mounted: bool,
        grounded: bool,
        now: Instant,
    ) -> [f32; 3] {
        if !mounted || !grounded || !feet.iter().all(|v| v.is_finite()) {
            self.previous = None;
            return feet;
        }
        let y = self.previous.map_or(feet[1], |(owner, raw, y, at)| {
            let dt = now.saturating_duration_since(at).as_secs_f32();
            if owner != identity
                || dt > 0.25
                || (feet[0] - raw[0]).hypot(feet[2] - raw[2]) > 2.0
                || (feet[1] - raw[1]).abs() > 0.6
                || (feet[1] - y).abs() > 0.6
            {
                return feet[1];
            }
            // Settle a terrain step in about 0.2 s, independent of frame rate,
            // with at most 35 cm of visual separation on a steep grounded rise.
            let eased = feet[1] + (y - feet[1]) * (-14.0 * dt).exp();
            eased.clamp(feet[1] - 0.35, feet[1] + 0.35)
        });
        self.previous = Some((identity, feet, y, now));
        [feet[0], y, feet[2]]
    }
}

/// Vanilla view bobbing (GameRenderer.bobView), driven by the host feet:
/// walkDist grows by 0.6 x horizontal travel, bob eases toward min(0.1, metres
/// per tick) on the ground (0 in the air) by 0.4 per tick. The camera moves
/// sideways sin(g)*bob/2 and up |cos(g)|*bob, rolls sin(g)*bob*3 degrees and
/// pitches |cos(g-0.2)|*bob*5 degrees, with g = walkDist*pi.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct ViewBob {
    previous: Option<[f32; 3]>,
    walk: f32,
    bob: f32,
    last: Option<Instant>,
}
impl ViewBob {
    pub(crate) fn reset(&mut self) {
        *self = Self::default();
    }
    /// Advance by one frame of `dt` seconds and return (walk distance, amplitude).
    pub(crate) fn advance(&mut self, feet: [f32; 3], grounded: bool, dt: f32) -> (f32, f32) {
        let Some(previous) = self.previous.replace(feet) else {
            return (self.walk, self.bob);
        };
        let distance = (feet[0] - previous[0]).hypot(feet[2] - previous[2]);
        if !dt.is_finite()
            || !(0.0001..=0.25).contains(&dt)
            || !distance.is_finite()
            || distance > 2.0
        {
            self.bob = 0.0;
            return (self.walk, self.bob);
        }
        self.walk = (self.walk + distance * 0.6) % 2.0;
        let target = if grounded {
            (distance / dt / 20.0).min(0.1)
        } else {
            0.0
        };
        self.bob += (target - self.bob) * (1.0 - 0.6f32.powf(dt * 20.0));
        (self.walk, self.bob)
    }
    /// (sideways m, up m, roll degrees, pitch-down degrees).
    pub(crate) fn offsets(walk: f32, bob: f32) -> (f32, f32, f32, f32) {
        let g = walk * std::f32::consts::PI;
        (
            g.sin() * bob * 0.5,
            (g.cos() * bob).abs(),
            g.sin() * bob * 3.0,
            ((g - 0.2).cos() * bob).abs() * 5.0,
        )
    }
}
/// Rotate the view about the horizontal axis that points `direction` degrees
/// from forward toward right, by `degrees` (vanilla bobHurt's Y-Z-Y rotation).
fn hurt_basis(basis: [[f32; 3]; 3], direction: f32, degrees: f32) -> [[f32; 3]; 3] {
    let [right, _, forward] = basis;
    let (sd, cd) = direction.to_radians().sin_cos();
    let axis: [f32; 3] = std::array::from_fn(|i| forward[i] * cd + right[i] * sd);
    let length = axis.iter().map(|v| v * v).sum::<f32>().sqrt();
    if length.is_nan() || length <= 0.5 {
        return basis;
    }
    let axis = axis.map(|v| v / length);
    let (s, c) = degrees.to_radians().sin_cos();
    // Rodrigues rotation of each basis vector.
    basis.map(|v| {
        let dot: f32 = (0..3).map(|i| axis[i] * v[i]).sum();
        let cross = [
            axis[1] * v[2] - axis[2] * v[1],
            axis[2] * v[0] - axis[0] * v[2],
            axis[0] * v[1] - axis[1] * v[0],
        ];
        std::array::from_fn(|i| v[i] * c + cross[i] * s + axis[i] * dot * (1.0 - c))
    })
}
fn bob_basis(basis: [[f32; 3]; 3], roll_degrees: f32, pitch_degrees: f32) -> [[f32; 3]; 3] {
    let [right, up, forward] = basis;
    let (sr, cr) = roll_degrees.to_radians().sin_cos();
    let right1: [f32; 3] = std::array::from_fn(|i| right[i] * cr + up[i] * sr);
    let up1: [f32; 3] = std::array::from_fn(|i| up[i] * cr - right[i] * sr);
    let (sp, cp) = pitch_degrees.to_radians().sin_cos();
    let forward2: [f32; 3] = std::array::from_fn(|i| forward[i] * cp - up1[i] * sp);
    let up2: [f32; 3] = std::array::from_fn(|i| up1[i] * cp + forward[i] * sp);
    [right1, up2, forward2]
}

/// A complete camera in Havok coordinates: eye, final right/up/forward and
/// vertical FOV in radians. Used by pose-locked composition.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LockedCamera {
    pub eye: [f32; 3],
    pub basis: [[f32; 3]; 3],
    pub fov: f32,
}
impl LockedCamera {
    fn valid(&self) -> bool {
        self.eye.iter().all(|v| v.is_finite())
            && self.fov.is_finite()
            && (0.05..3.13).contains(&self.fov)
            && valid_basis(self.basis)
    }
}

struct BoomState {
    identity: Identity,
    mode: u32,
    distance: f32,
    updated: Instant,
}

impl Default for Controller {
    fn default() -> Self {
        Self::new()
    }
}

impl Controller {
    pub fn new() -> Self {
        Self {
            settings: Settings::default(),
            writes: None,
            view_mode: 0,
            direct_basis: None,
            fov_multiplier: 1.0,
            boom: None,
            boom_queries: None,
            locked: None,
            last_desired: None,
            view_bob: true,
            bob: ViewBob::default(),
            hurt: None,
            hurt_tilt_strength: 1.0,
            eye_lift: 0.0,
            eye_lift_target: 0.0,
            eye_lift_at: None,
            mounted_ground_camera: MountedGroundCamera::default(),
        }
    }
    /// Raise the direct camera above the configured eye height (riding Torrent).
    pub(crate) fn set_eye_lift(&mut self, lift: f32) {
        if lift.is_finite() {
            self.eye_lift_target = lift.clamp(0.0, 1.0);
        }
    }
    fn advance_eye_lift(&mut self, now: Instant) -> f32 {
        let dt = self
            .eye_lift_at
            .map_or(0.0, |at| now.duration_since(at).as_secs_f32().min(0.25));
        self.eye_lift_at = Some(now);
        self.eye_lift = ease_lift(self.eye_lift, self.eye_lift_target, dt);
        self.eye_lift
    }
    /// Minecraft's damageTiltStrength option.
    pub(crate) fn set_hurt_tilt_strength(&mut self, strength: f32) {
        if strength.is_finite() {
            self.hurt_tilt_strength = strength.clamp(0.0, 1.0);
        }
    }
    /// Start vanilla hurt tilt; `direction` is the attacker's yaw relative to the look, in degrees.
    pub(crate) fn start_hurt(&mut self, direction: f32) {
        self.hurt = Some((
            Instant::now(),
            if direction.is_finite() {
                direction
            } else {
                0.0
            },
        ));
    }
    /// Vanilla view bobbing in first person (the guest's bobView option).
    pub(crate) fn set_view_bob(&mut self, enabled: bool) {
        if !enabled {
            self.bob.reset();
        }
        self.view_bob = enabled;
    }
    /// Apply this exact camera on the next update instead of the computed one
    /// (consumed once). The computed camera is still reported by `desired_camera`.
    pub(crate) fn set_locked_camera(&mut self, camera: Option<LockedCamera>) {
        self.locked = camera;
    }
    /// The camera the player asked for on the last successful update.
    pub(crate) fn desired_camera(&self) -> Option<LockedCamera> {
        self.last_desired
    }
    /// Last camera actually presented, including a frame synchronization lock.
    /// Used to freeze menus without snapping to an unpresented future camera.
    pub(crate) fn presented_camera(&self) -> Option<LockedCamera> {
        let writes = self.writes.as_ref()?;
        let desired = self.last_desired?;
        Some(LockedCamera {
            eye: std::array::from_fn(|i| writes.written_position[i]),
            basis: writes.basis.map_or(desired.basis, |(_, written)| {
                written.map(|v| [v[0], v[1], v[2]])
            }),
            fov: writes.fov.map_or(desired.fov, |value| value.written),
        })
    }
    pub fn with_settings(settings: Settings) -> Result<Self, &'static str> {
        Ok(Self {
            settings: settings.validate()?,
            ..Self::new()
        })
    }
    pub fn set_view_mode(&mut self, mode: u32) {
        let mode = mode.min(2);
        if mode != self.view_mode {
            self.boom = None;
        }
        self.view_mode = mode;
    }
    /// Owned camera FOV only; the host pose publishes this same projection to
    /// Minecraft, so the guest must not apply the sprint multiplier again.
    pub(crate) fn set_fov_multiplier(&mut self, multiplier: f32) -> Result<(), &'static str> {
        if !multiplier.is_finite() || !(1.0..=1.15).contains(&multiplier) {
            return Err("sprint FOV multiplier outside 1..1.15");
        }
        self.fov_multiplier = multiplier;
        Ok(())
    }

    /// Independent right/up/forward, supplied by the direct mouse-look driver.
    /// Retained across the internal per-frame release, cleared by the driver on
    /// actual suspension. Validation happens before any game write.
    pub(crate) fn set_look_basis(
        &mut self,
        basis: Option<[[f32; 3]; 3]>,
    ) -> Result<(), &'static str> {
        if basis.is_some_and(|b| !valid_basis(b)) {
            return Err("direct camera basis invalid");
        }
        self.direct_basis = basis;
        Ok(())
    }

    /// Seed a newly authorized look once from the underlying host camera. Never
    /// seed from our front-view reversal or dereference a retained address.
    pub(crate) unsafe fn look_seed(
        &self,
        permit: &FramePermit,
    ) -> Result<[[f32; 3]; 3], &'static str> {
        if !permit.fresh() {
            return Err("direct camera permit expired");
        }
        let (identity, _, _) = unsafe { live_player_frame(false, permit.blocking_menu)? };
        if identity != permit.identity {
            return Err("direct camera player changed");
        }
        let cameras = unsafe { CSCamera::instance() }.map_err(|_| "direct camera unavailable")?;
        let camera = cameras.pers_cam_1.as_ref();
        let current = [
            to_array(camera.matrix.0),
            to_array(camera.matrix.1),
            to_array(camera.matrix.2),
        ];
        let basis = self
            .writes
            .as_ref()
            .filter(|w| w.identity == identity && w.camera == camera as *const _ as usize)
            .and_then(|w| w.basis)
            .filter(|(_, written)| same_basis(current, *written))
            .map_or(current, |(original, _)| original)
            .map(|v| [v[0], v[1], v[2]]);
        if !valid_basis(basis) {
            return Err("direct camera seed basis invalid");
        }
        Ok(basis)
    }

    /// DrawParamUpdate entry point: a recent post-physics permit is required.
    pub(crate) unsafe fn update_authorized(
        &mut self,
        permit: Option<&FramePermit>,
    ) -> Result<Status, &'static str> {
        let permit = permit.filter(|permit| permit.fresh());
        unsafe { self.update_inner(permit.is_some(), permit) }
    }

    /// Read-only late-phase sample for distinguishing an overwritten camera
    /// from a camera object that is not used by the final renderer.
    pub unsafe fn observe(&self) -> Result<CameraObservation, &'static str> {
        let cameras =
            unsafe { CSCamera::instance() }.map_err(|_| "camera observation unavailable")?;
        let camera = cameras.pers_cam_1.as_ref();
        let position = to_array(camera.matrix.3);
        let same_camera = self
            .writes
            .as_ref()
            .is_some_and(|writes| camera as *const _ as usize == writes.camera);
        Ok(CameraObservation {
            camera_mask: cameras.camera_mask,
            observed_eye: [position[0], position[1], position[2]],
            requested_eye: self.writes.as_ref().map(|writes| {
                [
                    writes.written_position[0],
                    writes.written_position[1],
                    writes.written_position[2],
                ]
            }),
            same_camera,
            written_position_preserved: same_camera
                && self
                    .writes
                    .as_ref()
                    .is_some_and(|writes| same_bits(position, writes.written_position)),
            boom_distance: self.boom.as_ref().map(|b| b.distance),
            boom_view_mode: self.boom.as_ref().map(|b| b.mode),
            boom_owner_filtered: self.boom.is_some(),
        })
    }

    unsafe fn update_inner(
        &mut self,
        enabled: bool,
        permit: Option<&FramePermit>,
    ) -> Result<Status, &'static str> {
        let _ = unsafe { self.release_writes() };
        if !enabled {
            self.boom = None;
            self.mounted_ground_camera = MountedGroundCamera::default();
            return Ok(Status { active: false });
        }

        // Copy data out before acquiring mutable game references.
        let (identity, feet, grounded) = unsafe {
            live_player_frame(
                permit.is_none(),
                permit.is_some_and(|permit| permit.blocking_menu),
            )?
        };
        if permit.is_some_and(|permit| permit.identity != identity) {
            return Err("first-person post-physics permit no longer matches player/region");
        }
        // The queries finish before any mutable camera reference is acquired.
        let direct_eye = match self.direct_basis {
            Some(basis) => {
                Some(unsafe { self.direct_camera_position(identity, feet, grounded, basis) }?)
            }
            None => None,
        };
        // Vanilla first-person bob: moves the eye and tilts the look, not the body.
        let now = Instant::now();
        let dt = self
            .bob
            .last
            .replace(now)
            .map_or(0.0, |last| now.duration_since(last).as_secs_f32());
        let bobbing = self.view_bob
            && self.view_mode == 0
            && self.eye_lift_target == 0.0
            && self.direct_basis.is_some()
            && self.locked.is_none();
        let (direct_eye, direct_basis) = if bobbing {
            let (walk, amount) = self.bob.advance(feet, grounded, dt);
            let (side, up, roll, pitch) = ViewBob::offsets(walk, amount);
            let basis = self.direct_basis.expect("checked above");
            (
                direct_eye.map(|eye| {
                    std::array::from_fn(|i| eye[i] - basis[0][i] * side + basis[1][i] * up)
                }),
                Some(bob_basis(basis, -roll, pitch)),
            )
        } else {
            if !self.view_bob || self.view_mode != 0 || self.eye_lift_target > 0.0 {
                self.bob.reset();
            }
            (direct_eye, self.direct_basis)
        };
        // Vanilla hurt tilt: roll about an axis turned toward the attacker.
        let tilt = self.hurt.map_or(0.0, |(at, _)| {
            crate::hurt::tilt_degrees(
                now.duration_since(at).as_secs_f32(),
                self.hurt_tilt_strength,
            )
        });
        if self.hurt.is_some_and(|(at, _)| {
            now.duration_since(at).as_secs_f32() >= crate::hurt::HURT_SECONDS
        }) {
            self.hurt = None;
        }
        let direct_basis = match (direct_basis, self.hurt) {
            (Some(basis), Some((_, direction)))
                if self.view_mode == 0 && self.locked.is_none() && tilt != 0.0 =>
            {
                Some(hurt_basis(basis, direction, -tilt))
            }
            _ => direct_basis,
        };
        let (camera_address, original_position, written_position, basis, fov, near_plane) = unsafe {
            let cameras =
                CSCamera::instance_mut().map_err(|_| "first-person camera unavailable")?;
            let camera = cameras.pers_cam_1.as_mut();
            let original_position = to_array(camera.matrix.3);
            let forward = direct_basis.map_or(
                [camera.matrix.2.0, camera.matrix.2.1, camera.matrix.2.2],
                |b| b[2],
            );
            if !original_position
                .iter()
                .chain(forward.iter())
                .all(|value| value.is_finite())
                || !camera.fov.is_finite()
                || !(0.05..3.13).contains(&camera.fov)
                || !camera.near_plane.is_finite()
                || !(0.0..1.0).contains(&camera.near_plane)
                || distance_squared(
                    feet,
                    [
                        original_position[0],
                        original_position[1],
                        original_position[2],
                    ],
                ) > 2500.0
            {
                return Err("first-person camera failed sanity checks");
            }
            let eye = if let Some(eye) = direct_eye {
                eye
            } else {
                view_position(
                    feet,
                    forward,
                    original_position,
                    self.settings,
                    self.view_mode,
                )?
            };
            let mut written_position = [eye[0], eye[1], eye[2], original_position[3]];
            // Front view reverses both horizontal camera axes, preserving a
            // right-handed basis. Restore only if the host has not replaced it.
            let mut basis = if self.direct_basis.is_some() || self.view_mode == 2 {
                let original = [
                    to_array(camera.matrix.0),
                    to_array(camera.matrix.1),
                    to_array(camera.matrix.2),
                ];
                let mut written = direct_basis.map_or(original, |b| {
                    std::array::from_fn(|i| [b[i][0], b[i][1], b[i][2], original[i][3]])
                });
                if self.view_mode == 2 {
                    for i in [0, 2] {
                        for v in written[i].iter_mut().take(3) {
                            *v = -*v;
                        }
                    }
                }
                camera.matrix.0 = from_array(written[0]);
                camera.matrix.1 = from_array(written[1]);
                camera.matrix.2 = from_array(written[2]);
                Some((original, written))
            } else {
                None
            };
            let mut fov = self.settings.vertical_fov_radians.map(|base| ScalarWrite {
                original: camera.fov,
                written: base * self.fov_multiplier,
            });
            let near_plane = self.settings.near_plane_m.map(|written| ScalarWrite {
                original: camera.near_plane,
                written,
            });
            // The camera the player asked for, after any basis write above.
            let current_basis = [
                to_array(camera.matrix.0),
                to_array(camera.matrix.1),
                to_array(camera.matrix.2),
            ];
            self.last_desired = Some(LockedCamera {
                eye,
                basis: current_basis.map(|v| [v[0], v[1], v[2]]),
                fov: fov.map_or(camera.fov, |change| change.written),
            });
            // Pose lock: render the exact camera Minecraft already finished a frame for.
            if let Some(lock) = self
                .locked
                .take()
                .filter(|lock| lock.valid() && distance_squared(feet, lock.eye) <= 2500.0)
            {
                written_position = [lock.eye[0], lock.eye[1], lock.eye[2], original_position[3]];
                let original = basis.map_or(current_basis, |(original, _)| original);
                let written: [[f32; 4]; 3] = std::array::from_fn(|i| {
                    [
                        lock.basis[i][0],
                        lock.basis[i][1],
                        lock.basis[i][2],
                        original[i][3],
                    ]
                });
                camera.matrix.0 = from_array(written[0]);
                camera.matrix.1 = from_array(written[1]);
                camera.matrix.2 = from_array(written[2]);
                basis = Some((original, written));
                fov = Some(ScalarWrite {
                    original: fov.map_or(camera.fov, |change| change.original),
                    written: lock.fov,
                });
            }
            let address = camera as *mut _ as usize;
            camera.matrix.3 = from_array(written_position);
            if let Some(change) = fov {
                camera.fov = change.written;
            }
            if let Some(change) = near_plane {
                camera.near_plane = change.written;
            }
            (
                address,
                original_position,
                written_position,
                basis,
                fov,
                near_plane,
            )
        };
        // Store camera ownership immediately so a failed optional body lookup
        // cannot leave untracked writes behind.
        self.writes = Some(OwnedWrites {
            identity,
            camera: camera_address,
            original_position,
            written_position,
            basis,
            fov,
            near_plane,
            original_body_render: None,
        });
        if self.settings.hide_body
            && let Ok(player) = unsafe { PlayerIns::local_player_mut() }
            && player as *mut _ as usize == identity.player
            && player.current_block_id.0 == identity.map
        {
            let original = player.chr_ins.chr_flags1c5.enable_render();
            player.chr_ins.chr_flags1c5.set_enable_render(false);
            if let Some(writes) = self.writes.as_mut() {
                writes.original_body_render = Some(original);
            }
        }
        Ok(Status { active: true })
    }

    /// Release overrides on disable, focus loss, menu entry or world changes.
    /// An old raw address is compared only; it is never dereferenced.
    ///
    /// # Safety
    /// Same game-thread/version/no-retained-references contract as `update`.
    pub unsafe fn suspend(&mut self) -> RestoreStatus {
        self.boom = None;
        self.mounted_ground_camera = MountedGroundCamera::default();
        unsafe { self.release_writes() }
    }

    /// Restore the preceding frame's owned writes without resetting the F5
    /// obstruction recovery. Actual suspension always clears that recovery.
    unsafe fn release_writes(&mut self) -> RestoreStatus {
        let Some(writes) = self.writes.take() else {
            return RestoreStatus::Inactive;
        };
        let live = unsafe { PlayerIns::local_player() }
            .ok()
            .map(|player| Identity {
                player: player as *const _ as usize,
                map: player.current_block_id.0,
            });
        let mut restored = false;
        if let Ok(cameras) = unsafe { CSCamera::instance_mut() } {
            let camera = cameras.pers_cam_1.as_mut();
            if camera as *mut _ as usize == writes.camera {
                if live == Some(writes.identity)
                    && same_bits(to_array(camera.matrix.3), writes.written_position)
                {
                    camera.matrix.3 = from_array(writes.original_position);
                    restored = true;
                }
                if live == Some(writes.identity)
                    && let Some((original, written)) = writes.basis
                {
                    // Restore the complete basis atomically. Mixing our axes
                    // with a newer host basis can create an invalid camera.
                    let current = [
                        to_array(camera.matrix.0),
                        to_array(camera.matrix.1),
                        to_array(camera.matrix.2),
                    ];
                    if same_basis(current, written) {
                        camera.matrix.0 = from_array(original[0]);
                        camera.matrix.1 = from_array(original[1]);
                        camera.matrix.2 = from_array(original[2]);
                        restored = true;
                    }
                }
                if let Some(change) = writes.fov {
                    restored |= change.restore(&mut camera.fov);
                }
                if let Some(change) = writes.near_plane {
                    restored |= change.restore(&mut camera.near_plane);
                }
            }
        }
        if let Some(original) = writes.original_body_render
            && let Ok(player) = unsafe { PlayerIns::local_player_mut() }
            && player as *mut _ as usize == writes.identity.player
            && !player.chr_ins.chr_flags1c5.enable_render()
        {
            player.chr_ins.chr_flags1c5.set_enable_render(original);
            restored = true;
        }
        if restored {
            RestoreStatus::Restored
        } else {
            RestoreStatus::ReleasedToHost
        }
    }

    unsafe fn direct_camera_position(
        &mut self,
        identity: Identity,
        feet: [f32; 3],
        grounded: bool,
        basis: [[f32; 3]; 3],
    ) -> Result<[f32; 3], &'static str> {
        let now = Instant::now();
        let feet = self.mounted_ground_camera.feet(
            identity,
            feet,
            self.eye_lift_target > 0.0,
            grounded,
            now,
        );
        let lift = self.advance_eye_lift(now);
        let eye = direct_view_position(feet, basis[2], self.settings.eye_height_m + lift, 0)?;
        if self.view_mode == 0 {
            self.boom = None;
            return Ok(eye);
        }
        let direction = basis[2].map(|v| if self.view_mode == 1 { -v } else { v });
        let api = self
            .boom_queries
            .get_or_insert_with(crate::native_colliders::Api::resolve)
            .as_ref()
            .map_err(|e| *e)?;
        let world = unsafe { api.world() }?;
        let filter = unsafe { api.character_filter(world) }?;
        // Keep real physical geometry and placed Minecraft colliders, while
        // supplying this exact local PlayerIns as native query.userData. The
        // game's own family/owner predicate excludes self-owned bodies; the
        // rich ray's null owner bypassed it and could collapse front F5 to zero.
        let limit = boom_limit(eye, direction, basis[0], basis[1], |origin, delta| unsafe {
            api.camera_ray(world, origin, delta, filter, identity.player)
        })?;
        let now = Instant::now();
        let distance = match self
            .boom
            .as_ref()
            .filter(|s| s.identity == identity && s.mode == self.view_mode)
        {
            Some(previous) => recover_boom(
                previous.distance,
                limit,
                now.duration_since(previous.updated).as_secs_f32(),
            ),
            None => limit,
        };
        self.boom = Some(BoomState {
            identity,
            mode: self.view_mode,
            distance,
            updated: now,
        });
        Ok(std::array::from_fn(|i| eye[i] + direction[i] * distance))
    }
}

unsafe fn live_player_frame(
    require_activity: bool,
    blocking_menu: bool,
) -> Result<(Identity, [f32; 3], bool), &'static str> {
    unsafe {
        let game = GameMan::instance().map_err(|_| "first-person waiting for game")?;
        if game.is_in_online_mode || game.warp_requested {
            return Err("first-person suspended for online mode or transition");
        }
        let session =
            CSSessionManager::instance().map_err(|_| "first-person waiting for session")?;
        if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None
        {
            return Err("first-person suspended for multiplayer session");
        }
        let menu = CSMenuManImp::instance().map_err(|_| "first-person waiting for menu")?;
        if !menu.system_announce_view_model.view.as_ref().is_active && !blocking_menu {
            return Err("first-person suspended for blocking menu");
        }
        let player =
            PlayerIns::local_player().map_err(|_| "first-person waiting for local player")?;
        if (require_activity
            && (!player.chr_ins.chr_flags1c8.is_active()
                || !player.chr_ins.chr_flags1c8.update_tasks_registered()))
            || player.chr_ins.chr_flags1c5.death_flag()
            || player.current_block_id.0 == -1
        {
            return Err("first-person local player is not ready");
        }
        let entry = player.chr_ins.chr_set_entry.as_ref();
        if !entry
            .chr_ins
            .is_some_and(|pointer| std::ptr::eq(pointer.as_ptr(), &player.chr_ins))
        {
            return Err("first-person player identity mismatch");
        }
        if player.chr_ins.modules.data.hp <= 0 {
            return Err("first-person player has no health");
        }
        if player.chr_ins.modules.ride.is_mounted
            || player.chr_ins.modules.ride.is_mounting
            || player.chr_ins.modules.ladder.state != LadderState::None
            || (player.chr_ins.chr_ctrl.disable_move && !blocking_menu)
        {
            return Err("first-person suspended while mounted or on a ladder");
        }
        let physics = &player.chr_ins.modules.physics;
        let feet = [physics.position.0, physics.position.1, physics.position.2];
        if !feet
            .iter()
            .all(|value| value.is_finite() && value.abs() <= 1_000_000.0)
        {
            return Err("first-person player position invalid");
        }
        Ok((
            Identity {
                player: player as *const _ as usize,
                map: player.current_block_id.0,
            },
            feet,
            physics.standing_on_solid_ground || physics.touching_solid_ground,
        ))
    }
}

/// Block-coordinate offset of the local player's Havok frame (block - Havok)
/// and its map. Read-only; call on the game's task thread.
pub(crate) unsafe fn block_offset() -> Option<(u32, [f64; 3])> {
    let player = unsafe { PlayerIns::local_player() }.ok()?;
    if player.current_block_id.0 == -1 {
        return None;
    }
    let block = player.block_position;
    let havok = &player.chr_ins.modules.physics.position;
    let offset = [
        f64::from(block.x - havok.0),
        f64::from(block.y - havok.1),
        f64::from(block.z - havok.2),
    ];
    offset
        .iter()
        .all(|v| v.is_finite())
        .then_some((player.current_block_id.0 as u32, offset))
}

fn eye_position(
    feet: [f32; 3],
    forward: [f32; 3],
    settings: Settings,
) -> Result<[f32; 3], &'static str> {
    if !feet
        .iter()
        .chain(forward.iter())
        .all(|value| value.is_finite())
    {
        return Err("first-person eye input invalid");
    }
    let length_squared: f32 = forward.iter().map(|value| value * value).sum();
    if !(0.25..=4.0).contains(&length_squared) {
        return Err("first-person look basis invalid");
    }
    let horizontal = (forward[0] * forward[0] + forward[2] * forward[2]).sqrt();
    let offset = if horizontal > 0.0001 {
        settings.forward_offset_m / horizontal
    } else {
        0.0
    };
    Ok([
        feet[0] + forward[0] * offset,
        feet[1] + settings.eye_height_m,
        feet[2] + forward[2] * offset,
    ])
}

fn view_position(
    feet: [f32; 3],
    forward: [f32; 3],
    host: [f32; 4],
    settings: Settings,
    mode: u32,
) -> Result<[f32; 3], &'static str> {
    let eye = eye_position(feet, forward, settings)?;
    match mode {
        0 => Ok(eye),
        // Rear retains Elden Ring's camera distance and wall collision.
        1 => Ok([host[0], host[1], host[2]]),
        // Reflect through the eye. Front view does not add a collision query.
        2 => Ok([
            2.0 * eye[0] - host[0],
            2.0 * eye[1] - host[1],
            2.0 * eye[2] - host[2],
        ]),
        _ => Err("unknown camera mode"),
    }
}

/// Exponential approach that snaps once within a millimetre.
fn ease_lift(current: f32, target: f32, dt: f32) -> f32 {
    let next = target + (current - target) * (-EYE_LIFT_RATE * dt).exp();
    if (next - target).abs() < 0.001 {
        target
    } else {
        next
    }
}

/// Minecraft's ordinary third-person distance is four metres. This direct orbit
/// intentionally does not inherit ER camera animation, spring, lock-on or body
/// rotation. F5 applies physical boom clipping to this desired position.
fn direct_view_position(
    feet: [f32; 3],
    forward: [f32; 3],
    eye_height: f32,
    mode: u32,
) -> Result<[f32; 3], &'static str> {
    if !feet.iter().chain(forward.iter()).all(|v| v.is_finite()) || !eye_height.is_finite() {
        return Err("direct camera position invalid");
    }
    let distance = match mode {
        0 => 0.0,
        1 => -4.0,
        2 => 4.0,
        _ => return Err("unknown camera mode"),
    };
    Ok(std::array::from_fn(|i| {
        feet[i] + if i == 1 { eye_height } else { 0.0 } + forward[i] * distance
    }))
}

/// A centre ray plus eight near-plane corner/edge rays keep the camera's small
/// aperture out of walls. This is a bounded ray approximation, not a shape cast.
fn boom_limit<F>(
    eye: [f32; 3],
    direction: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    mut ray: F,
) -> Result<f32, &'static str>
where
    F: FnMut([f64; 3], [f64; 3]) -> Result<Option<[f64; 3]>, &'static str>,
{
    if !eye.iter().all(|v| v.is_finite()) || !valid_basis([right, up, direction]) {
        return Err("third-person boom basis invalid");
    }
    let mut limit = 4.0f64;
    for (x, y) in [
        (0., 0.),
        (-1., -1.),
        (-1., 0.),
        (-1., 1.),
        (0., -1.),
        (0., 1.),
        (1., -1.),
        (1., 0.),
        (1., 1.),
    ] {
        let origin = std::array::from_fn(|i| {
            eye[i] as f64 + 0.12 * (x * right[i] as f64 + y * up[i] as f64)
        });
        let delta = direction.map(|v| v as f64 * 4.0);
        if let Some(hit) = ray(origin, delta)? {
            if !hit.iter().all(|v| v.is_finite()) {
                return Err("third-person boom hit nonfinite");
            }
            let along = (0..3)
                .map(|i| (hit[i] - origin[i]) * direction[i] as f64)
                .sum::<f64>();
            let off = (0..3)
                .map(|i| (hit[i] - origin[i] - along * direction[i] as f64).powi(2))
                .sum::<f64>();
            if !(-0.02..=4.02).contains(&along) || off > 0.02 * 0.02 {
                return Err("third-person boom hit outside ray");
            }
            limit = limit.min((along - 0.15).max(0.0));
        }
    }
    Ok(limit as f32)
}
fn recover_boom(previous: f32, limit: f32, dt: f32) -> f32 {
    // Entering a wall snaps inward; only reopening space eases outward. Never
    // carry a long focus/menu delay into an overshoot or delayed correction.
    if !previous.is_finite() || !dt.is_finite() || dt < 0.0 {
        return limit;
    }
    if limit <= previous {
        return limit;
    }
    (limit + (previous - limit) * 0.5f32.powf(dt.min(0.1) / 0.08)).clamp(0.0, limit)
}

fn valid_basis(basis: [[f32; 3]; 3]) -> bool {
    let dot = |a: [f32; 3], b: [f32; 3]| (0..3).map(|i| a[i] * b[i]).sum::<f32>();
    basis
        .iter()
        .all(|v| v.iter().all(|c| c.is_finite()) && (0.95..=1.05).contains(&dot(*v, *v)))
        && dot(basis[0], basis[1]).abs() < 0.02
        && dot(basis[0], basis[2]).abs() < 0.02
        && dot(basis[1], basis[2]).abs() < 0.02
}
fn same_basis(a: [[f32; 4]; 3], b: [[f32; 4]; 3]) -> bool {
    a.into_iter().zip(b).all(|(a, b)| same_bits(a, b))
}

/// Pinned SDK examples/debug-line rotates (0,0,-1) to obtain character-facing
/// forward. Camera forward has a different baseline; using its +Z convention
/// for the body turns the Minecraft avatar backwards.
pub fn minecraft_body_yaw(q: [f32; 4]) -> Result<f32, &'static str> {
    let norm: f32 = q.iter().map(|v| v * v).sum();
    if !norm.is_finite() || !(0.5..2.0).contains(&norm) {
        return Err("invalid character orientation");
    }
    let [x, y, z, w] = q.map(|v| v / norm.sqrt());
    let forward_x = -2.0 * (x * z + w * y);
    let forward_z = -(1.0 - 2.0 * (x * x + y * y));
    Ok((-forward_x).atan2(forward_z).to_degrees())
}

fn to_array(v: F32Vector4) -> [f32; 4] {
    [v.0, v.1, v.2, v.3]
}
fn from_array(v: [f32; 4]) -> F32Vector4 {
    F32Vector4(v[0], v[1], v[2], v[3])
}
fn same_bits(a: [f32; 4], b: [f32; 4]) -> bool {
    a.into_iter()
        .zip(b)
        .all(|(a, b)| a.to_bits() == b.to_bits())
}
fn distance_squared(a: [f32; 3], b: [f32; 3]) -> f32 {
    (0..3).map(|axis| (a[axis] - b[axis]).powi(2)).sum()
}

#[cfg(test)]
mod tests {
    #[test]
    fn view_bob_follows_vanilla_walking_and_stops_in_the_air() {
        let mut bob = super::ViewBob::default();
        let dt = 1.0 / 60.0;
        let step = 4.317 * dt;
        let mut x = 0.0;
        bob.advance([x, 0.0, 0.0], true, dt);
        let mut peak = 0.0f32;
        for _ in 0..120 {
            x += step;
            let (walk, amount) = bob.advance([x, 0.0, 0.0], true, dt);
            let (side, up, roll, pitch) = super::ViewBob::offsets(walk, amount);
            peak = peak.max(up);
            assert!(side.abs() <= 0.05 + 1e-6 && roll.abs() <= 0.3 + 1e-5 && pitch <= 0.5 + 1e-5);
        }
        assert!(
            (peak - 0.1).abs() < 0.01,
            "walking bob reaches vanilla's 0.1 amplitude, got {peak}"
        );
        for _ in 0..60 {
            x += step;
            bob.advance([x, 2.0, 0.0], false, dt);
        }
        assert!(bob.bob < 0.001, "no bob while airborne");
        let before = bob.walk;
        bob.advance([x + 50.0, 0.0, 0.0], true, dt);
        assert_eq!(bob.walk, before, "teleports do not advance the stride");
    }
    #[test]
    fn hurt_tilt_rolls_about_the_attacker_axis_and_stays_orthonormal() {
        let identity = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
        let front = super::hurt_basis(identity, 0.0, 14.0);
        assert!(
            (front[2][2] - 1.0).abs() < 1e-5,
            "a hit from the front rolls about forward: forward unchanged"
        );
        let side = super::hurt_basis(identity, 90.0, 14.0);
        assert!(
            (side[0][0] - 1.0).abs() < 1e-5,
            "a hit from the right tilts about right: right unchanged"
        );
        for b in [front, side] {
            for axis in b {
                let n: f32 = axis.iter().map(|v| v * v).sum();
                assert!((n - 1.0).abs() < 1e-5);
            }
        }
    }
    #[test]
    fn bobbed_basis_stays_orthonormal() {
        let b = super::bob_basis(
            [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
            0.3,
            0.5,
        );
        for i in 0..3 {
            let n: f32 = b[i].iter().map(|v| v * v).sum();
            assert!((n - 1.0).abs() < 1e-5);
            for j in 0..i {
                let d: f32 = (0..3).map(|k| b[i][k] * b[j][k]).sum();
                assert!(d.abs() < 1e-5);
            }
        }
    }

    use super::*;
    #[test]
    fn f5_boom_uses_nine_physical_rays_and_clips_both_view_directions() {
        for direction in [[0., 0., 1.], [0., 0., -1.]] {
            let mut count = 0;
            let limit = boom_limit(
                [2., 3., 4.],
                direction,
                [1., 0., 0.],
                [0., 1., 0.],
                |o, d| {
                    count += 1;
                    assert_eq!(d, direction.map(|v| v as f64 * 4.));
                    // A side ray alone sees an edge beside the centre of the view.
                    Ok((count == 4)
                        .then(|| std::array::from_fn(|i| o[i] + direction[i] as f64 * 1.5)))
                },
            )
            .unwrap();
            assert_eq!(count, 9);
            assert!((limit - 1.35).abs() < 0.0001);
        }
    }
    #[test]
    fn f5_boom_clear_space_and_near_obstacles_remain_bounded() {
        assert_eq!(
            boom_limit(
                [0.; 3],
                [0., 0., 1.],
                [1., 0., 0.],
                [0., 1., 0.],
                |_, _| Ok(None)
            ),
            Ok(4.)
        );
        assert_eq!(
            boom_limit(
                [0.; 3],
                [0., 0., 1.],
                [1., 0., 0.],
                [0., 1., 0.],
                |o, _| Ok(Some([o[0], o[1], o[2] + 0.05]))
            ),
            Ok(0.)
        );
        assert!(
            boom_limit(
                [0.; 3],
                [0., 0., 1.],
                [1., 0., 0.],
                [0., 1., 0.],
                |o, _| Ok(Some([o[0] + 1., o[1], o[2] + 2.]))
            )
            .is_err()
        );
        assert!(
            boom_limit([0.; 3], [0., 0., 1.], [1., 0., 0.], [0., 1., 0.], |_, _| {
                Err("query unavailable")
            })
            .is_err()
        );
    }
    #[test]
    fn f5_recovery_snaps_inward_and_eases_out_without_overshoot() {
        assert_eq!(recover_boom(4., 1., 1. / 60.), 1.);
        let half = recover_boom(1., 4., 0.08);
        assert!((half - 2.5).abs() < 0.00001);
        let split = recover_boom(recover_boom(1., 4., 0.04), 4., 0.04);
        assert!((split - half).abs() < 0.00001);
        assert_eq!(recover_boom(0., 4., 0.), 0.);
        assert!(recover_boom(0., 4., 50.) < 4.);
        assert_eq!(recover_boom(2., 0., 0.1), 0.);
    }
    #[test]
    fn owner_filtered_f5_hits_still_clip_at_a_real_near_wall() {
        for forward in [[0., 0., 1.], [0., 0., -1.]] {
            // A successful native owner filter can report no self hit; remaining
            // wall contacts are used unchanged, including centimetres from eye.
            let mut ray = 0;
            let result = boom_limit([0.; 3], forward, [1., 0., 0.], [0., 1., 0.], |o, _| {
                ray += 1;
                Ok(if ray == 1 {
                    None
                } else {
                    Some(std::array::from_fn(|i| o[i] + forward[i] as f64 * 0.02))
                })
            })
            .unwrap();
            assert_eq!(ray, 9);
            assert_eq!(result, 0.);
        }
    }
    #[test]
    fn f5_mode_changes_and_real_suspension_reset_boom_only() {
        let mut controller = Controller::new();
        let basis = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        controller.set_look_basis(Some(basis)).unwrap();
        controller.view_mode = 1;
        controller.boom = Some(BoomState {
            identity: Identity { player: 1, map: 1 },
            mode: 1,
            distance: 1.,
            updated: Instant::now(),
        });
        controller.set_view_mode(1);
        assert!(controller.boom.is_some());
        controller.set_view_mode(2);
        assert!(controller.boom.is_none());
        assert_eq!(controller.direct_basis, Some(basis));
        assert_eq!(unsafe { controller.suspend() }, RestoreStatus::Inactive);
        // First person remains the exact unchanged design eye, regardless of F5 history.
        assert_eq!(
            direct_view_position([1., 2., 3.], [0., 0., 1.], 1.62, 0).unwrap(),
            [1., 3.62, 3.]
        );
    }
    #[test]
    fn eye_follows_feet_and_horizontal_look_without_pitch_bob() {
        let settings = Settings::default();
        assert_eq!(
            eye_position([1.0, 2.0, 3.0], [0.0, 0.0, 1.0], settings).unwrap(),
            [1.0, 3.62, 3.12]
        );
        assert_eq!(
            eye_position([1.0, 2.0, 3.0], [0.0, 1.0, 0.0], settings).unwrap(),
            [1.0, 3.62, 3.0]
        );
        assert!(eye_position([0.0; 3], [0.0; 3], settings).is_err());
    }
    #[test]
    fn rear_preserves_host_collision_position_and_front_reflects_around_eye() {
        let settings = Settings::default();
        let host = [0.0, 2.0, -4.0, 1.0];
        let rear = view_position([0.0; 3], [0.0, 0.0, 1.0], host, settings, 1).unwrap();
        assert_eq!(rear, [0.0, 2.0, -4.0]);
        let front = view_position([0.0; 3], [0.0, 0.0, 1.0], host, settings, 2).unwrap();
        assert!((front[1] - 1.24).abs() < 0.00001);
        assert!((front[2] - 4.24).abs() < 0.00001);
    }
    #[test]
    fn avatar_heading_uses_verified_negative_z_character_axis() {
        assert!((minecraft_body_yaw([0.0, 0.0, 0.0, 1.0]).unwrap().abs() - 180.0).abs() < 0.001);
        let h = std::f32::consts::FRAC_1_SQRT_2;
        assert!((minecraft_body_yaw([0.0, h, 0.0, h]).unwrap() - 90.0).abs() < 0.001);
        assert!((minecraft_body_yaw([0.0, -h, 0.0, h]).unwrap() + 90.0).abs() < 0.001);
        assert!(minecraft_body_yaw([f32::NAN; 4]).is_err());
    }
    #[test]
    fn restoration_does_not_overwrite_new_host_values() {
        let write = ScalarWrite {
            original: 0.1,
            written: 0.05,
        };
        let mut ours = 0.05;
        assert!(write.restore(&mut ours));
        assert_eq!(ours, 0.1);
        let mut host_changed = 0.2;
        assert!(!write.restore(&mut host_changed));
        assert_eq!(host_changed, 0.2);
        assert!(!same_bits([0.0, 0.0, 0.0, 1.0], [0.0; 4]));
    }
    #[test]
    fn menu_freeze_uses_presented_position_basis_and_fov() {
        let mut controller = Controller::new();
        let basis = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        controller.last_desired = Some(LockedCamera {
            eye: [20., 30., 40.],
            basis,
            fov: 1.1,
        });
        // A delayed guest frame may have presented an older camera. Menus must
        // freeze those written values, including a front-view reversal.
        let written = [[-1., 0., 0., 0.], [0., 1., 0., 0.], [0., 0., -1., 0.]];
        controller.writes = Some(OwnedWrites {
            identity: Identity {
                player: 7,
                map: 100,
            },
            camera: 1,
            original_position: [0.; 4],
            written_position: [2., 3., 4., 1.],
            basis: Some(([[0.; 4]; 3], written)),
            fov: Some(ScalarWrite {
                original: 1.,
                written: 1.2,
            }),
            near_plane: None,
            original_body_render: None,
        });
        let frozen = controller.presented_camera().unwrap();
        assert_eq!(frozen.eye, [2., 3., 4.]);
        assert_eq!(frozen.basis, written.map(|v| [v[0], v[1], v[2]]));
        assert_eq!(frozen.fov, 1.2);
        controller.writes = None;
        assert!(controller.presented_camera().is_none());
    }
    #[test]
    fn unsafe_configuration_ranges_are_rejected() {
        assert!(
            Settings {
                eye_height_m: f32::NAN,
                ..Settings::default()
            }
            .validate()
            .is_err()
        );
        assert!(
            Settings {
                forward_offset_m: 1.0,
                ..Settings::default()
            }
            .validate()
            .is_err()
        );
        assert!(
            Settings {
                near_plane_m: Some(0.0),
                ..Settings::default()
            }
            .validate()
            .is_err()
        );
        assert!(
            Settings {
                vertical_fov_radians: Some(4.0),
                ..Settings::default()
            }
            .validate()
            .is_err()
        );
        assert!(Settings::default().validate().is_ok());
    }
    #[test]
    fn expired_phase_permission_does_not_access_game_objects() {
        let permit = FramePermit {
            identity: Identity {
                player: usize::MAX,
                map: -1,
            },
            issued: Instant::now() - Duration::from_secs(1),
            blocking_menu: true,
        };
        let mut controller = Controller::new();
        // The test runner is not Elden Ring. A stale permit must return before
        // any singleton lookup, even when its retained identity is invalid.
        let status = unsafe { controller.update_authorized(Some(&permit)) }.unwrap();
        assert!(!status.active);
        assert!(controller.writes.is_none());
        assert_eq!(unsafe { controller.suspend() }, RestoreStatus::Inactive);
    }
    #[test]
    fn direct_views_use_only_eye_and_independent_look() {
        let feet = [10.0, 20.0, 30.0];
        let forward = [0.0, 0.6, 0.8];
        assert_eq!(
            direct_view_position(feet, forward, 1.62, 0).unwrap(),
            [10.0, 21.62, 30.0]
        );
        let rear = direct_view_position(feet, forward, 1.62, 1).unwrap();
        let front = direct_view_position(feet, forward, 1.62, 2).unwrap();
        assert!((rear[1] - 19.22).abs() < 0.00001);
        assert!((rear[2] - 26.8).abs() < 0.00001);
        assert!((front[1] - 24.02).abs() < 0.00001);
        assert!((front[2] - 33.2).abs() < 0.00001);
        assert!(direct_view_position(feet, forward, 1.62, 3).is_err());
    }
    #[test]
    fn mounted_eye_lift_eases_in_and_out_and_rejects_invalid_targets() {
        assert_eq!(ease_lift(0.0, 0.84375, 0.0), 0.0);
        let rising = ease_lift(0.0, 0.84375, 0.05);
        assert!(rising > 0.3 && rising < 0.84375, "rising={rising}");
        assert_eq!(ease_lift(rising, 0.84375, 1.0), 0.84375);
        assert_eq!(ease_lift(0.84375, 0.0, 1.0), 0.0);
        let mut controller = Controller::new();
        controller.set_eye_lift(0.84375);
        controller.set_eye_lift(f32::NAN);
        assert_eq!(controller.eye_lift_target, 0.84375);
        controller.set_eye_lift(5.0);
        assert_eq!(controller.eye_lift_target, 1.0);
    }
    #[test]
    fn mounted_ground_camera_damps_terrain_height_without_delaying_horizontal_travel() {
        let identity = Identity { player: 1, map: 2 };
        let now = Instant::now();
        let mut camera = MountedGroundCamera::default();
        camera.feet(identity, [0.0, 0.0, 3.0], true, true, now);
        for frame in 1..=120 {
            let x = frame as f32 * 0.2;
            let y = if frame % 2 == 0 { 0.1 } else { -0.1 };
            let raw = [x, y, 3.0];
            let shown = camera.feet(
                identity,
                raw,
                true,
                true,
                now + Duration::from_secs_f64(frame as f64 / 60.0),
            );
            assert_eq!([shown[0], shown[2]], [raw[0], raw[2]]);
            assert!(
                shown[1].abs() < 0.025,
                "terrain jitter reached camera: {shown:?}"
            );
        }
    }
    #[test]
    fn mounted_ground_camera_settles_steps_at_the_same_rate_across_frame_rates() {
        let identity = Identity { player: 1, map: 2 };
        let now = Instant::now();
        let mut results = Vec::new();
        for hz in [30, 60, 120] {
            let mut camera = MountedGroundCamera::default();
            camera.feet(identity, [0.0; 3], true, true, now);
            let mut shown = [0.0; 3];
            for frame in 1..=hz / 5 {
                shown = camera.feet(
                    identity,
                    [frame as f32 * 0.1, 0.3, 0.0],
                    true,
                    true,
                    now + Duration::from_secs_f64(frame as f64 / hz as f64),
                );
            }
            assert!((shown[1] - 0.3).abs() < 0.02);
            results.push(shown[1]);
        }
        assert!(
            results
                .windows(2)
                .all(|pair| (pair[0] - pair[1]).abs() < 0.00001)
        );
    }
    #[test]
    fn mounted_ground_camera_snaps_jumps_dismounts_gaps_teleports_and_new_contexts() {
        let identity = Identity { player: 1, map: 2 };
        let now = Instant::now();
        for (owner, feet, mounted, grounded, delay) in [
            (identity, [0.1, 0.2, 0.0], true, false, 16),
            (identity, [0.1, 0.2, 0.0], false, true, 16),
            (identity, [0.1, 0.2, 0.0], true, true, 251),
            (identity, [20.0, 0.2, 0.0], true, true, 16),
            (identity, [0.1, 4.0, 0.0], true, true, 16),
            (
                Identity {
                    player: 3,
                    ..identity
                },
                [0.1, 0.2, 0.0],
                true,
                true,
                16,
            ),
            (
                Identity { map: 4, ..identity },
                [0.1, 0.2, 0.0],
                true,
                true,
                16,
            ),
        ] {
            let mut camera = MountedGroundCamera::default();
            camera.feet(identity, [0.0; 3], true, true, now);
            assert_eq!(
                camera.feet(
                    owner,
                    feet,
                    mounted,
                    grounded,
                    now + Duration::from_millis(delay)
                ),
                feet
            );
        }
        let mut controller = Controller::new();
        controller
            .mounted_ground_camera
            .feet(identity, [0.0; 3], true, true, now);
        unsafe { controller.suspend() };
        assert!(controller.mounted_ground_camera.previous.is_none());
    }
    #[test]
    fn direct_basis_validation_prevents_shear_and_restoration_requires_all_axes() {
        let basis = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
        let mut controller = Controller::new();
        assert!(controller.set_look_basis(Some(basis)).is_ok());
        let mut invalid = basis;
        invalid[1] = invalid[0];
        assert!(controller.set_look_basis(Some(invalid)).is_err());
        assert_eq!(controller.direct_basis, Some(basis));
        // Internal per-frame restoration must not reset direct look settings.
        assert_eq!(unsafe { controller.suspend() }, RestoreStatus::Inactive);
        assert_eq!(controller.direct_basis, Some(basis));
        let written = basis.map(|v| [v[0], v[1], v[2], 0.0]);
        let mut changed = written;
        changed[1][3] = 1.0;
        assert!(!same_basis(changed, written));
        assert!(same_basis(written, written));
    }
}
