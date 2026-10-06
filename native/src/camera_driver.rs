//! Split-phase camera driver: authoritative liveness in ChrIns_PostPhysics,
//! camera application in DrawParamUpdate after the host CameraStep. Only the driver is
//! shared, never SDK references. Integration should use Arc<Mutex<Driver>> with
//! try_lock in both callbacks so no task waits for another task.
use crate::first_person::{
    self, CameraObservation, Controller, FramePermit, RestoreStatus, Settings, Status,
};
use std::ffi::c_void;

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcessId() -> u32;
    fn GetTickCount64() -> u64;
}
#[link(name = "user32")]
unsafe extern "system" {
    fn GetForegroundWindow() -> *mut c_void;
    fn GetWindowThreadProcessId(window: *mut c_void, process: *mut u32) -> u32;
}
fn foreground() -> bool {
    let mut pid = 0;
    unsafe {
        GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
        pid == GetCurrentProcessId()
    }
}

pub struct Driver {
    controller: Controller,
    permit: Option<FramePermit>,
    error: Option<&'static str>,
    look: Option<Look>,
    /// Character only: crossing a map tile must not re-seed the view.
    look_identity: Option<usize>,
    /// Orientation kept across short permit gaps and suspensions (tile changes,
    /// menus, transient gates). Re-seeding from Elden Ring's own camera instead
    /// snaps to wherever its auto-follow turned while the player walked.
    retained: Option<(Look, usize, u64)>,
    look_enabled: bool,
    menu_locked: bool,
    /// A GUI freezes the camera already shown, scoped to a live player/region.
    menu_camera: Option<((usize, i32), first_person::LockedCamera)>,
    sprint_fov: SprintFov,
    last_fov_tick: Option<u64>,
    /// Character the sprint FOV belongs to; survives permit gaps, unlike look_identity.
    fov_identity: Option<usize>,
    /// Degrees per mouse count from the guest's sensitivity; X/Y inversion.
    look_gain: f64,
    invert: [bool; 2],
}
impl Driver {
    pub fn new(settings: Settings) -> Result<Self, &'static str> {
        Ok(Self {
            controller: Controller::with_settings(settings)?,
            permit: None,
            error: None,
            look: None,
            look_identity: None,
            retained: None,
            look_enabled: false,
            menu_locked: false,
            menu_camera: None,
            sprint_fov: SprintFov::default(),
            last_fov_tick: None,
            fov_identity: None,
            look_gain: look_gain(DEFAULT_SENSITIVITY),
            invert: [false; 2],
        })
    }
    pub fn error(&self) -> Option<&'static str> {
        self.error
    }
    pub fn set_view_mode(&mut self, mode: u32) {
        self.controller.set_view_mode(mode);
    }
    /// Guest GUI freezes look, but retains the independent orientation. The raw
    /// mailbox is still consumed while frozen, so closing it never replays drag.
    pub fn set_look_enabled(&mut self, enabled: bool) {
        if !enabled && self.look_enabled {
            crate::overlay_input::clear_look();
        }
        self.look_enabled = enabled;
    }
    /// Freeze the whole presented camera while Minecraft owns menu input.
    /// Keeping eye, basis and FOV also stops host NPC/rest camera animations
    /// from moving the scene underneath menu buttons. A changed player or map
    /// invalidates the captured pose before it can be applied again.
    pub fn set_menu_locked(&mut self, locked: bool) {
        if locked && !self.menu_locked {
            self.menu_camera = self.permit.as_ref().and_then(|permit| {
                self.controller
                    .presented_camera()
                    .map(|camera| (permit.identity_token(), camera))
            });
            crate::overlay_input::clear_look();
        } else if !locked {
            self.menu_camera = None;
        }
        self.menu_locked = locked;
    }
    /// The player's actual Minecraft mouse sensitivity, inversion and view bobbing.
    pub fn set_view_settings(
        &mut self,
        sensitivity: f64,
        invert_x: bool,
        invert_y: bool,
        bob: bool,
        damage_tilt: f64,
    ) {
        if sensitivity.is_finite() && (0.0..=1.0).contains(&sensitivity) {
            self.look_gain = look_gain(sensitivity);
        }
        self.invert = [invert_x, invert_y];
        self.controller.set_view_bob(bob);
        self.controller.set_hurt_tilt_strength(damage_tilt as f32);
    }
    /// The player was hurt; `away` points from the attacker to the player (XZ), if known.
    /// The tilt axis turns toward the attacker relative to the current look, as hurtDir does.
    pub fn hurt(&mut self, away: Option<[f32; 2]>) {
        let direction = match (away, self.look) {
            (Some(away), Some(look)) => {
                let [right, _, forward] = look.basis();
                let toward = [-away[0], -away[1]];
                (right[0] * toward[0] + right[2] * toward[1])
                    .atan2(forward[0] * toward[0] + forward[2] * toward[1])
                    .to_degrees()
            }
            _ => 0.0,
        };
        self.controller.start_hurt(direction);
    }
    /// Call with actually authorized forward sprint, never raw Ctrl alone.
    pub fn set_sprinting(&mut self, sprinting: bool) {
        self.sprint_fov.sprinting = sprinting;
    }
    /// Extra eye height above the feet while riding Torrent; zero on foot.
    pub fn set_eye_lift(&mut self, lift: f32) {
        self.controller.set_eye_lift(lift);
    }
    /// Gameplay directions, independent of rear/front camera placement.
    #[cfg(test)]
    fn look_forward(&self) -> Option<[f32; 3]> {
        self.look.map(|look| look.basis()[2])
    }
    pub fn look_right(&self) -> Option<[f32; 3]> {
        self.look.map(|look| look.basis()[0])
    }
    pub fn look_yaw_degrees(&self) -> Option<f32> {
        self.look.map(|look| look.yaw as f32)
    }

    /// PostPhysics only; call after the normal host snapshot succeeds. Calling
    /// false revokes the permit and immediately restores owned camera/body state.
    /// Caller must have completed the executable guard and hold no SDK references.
    /// A supported host menu that already has a Minecraft replacement may keep
    /// a presentation permit. All other liveness, foreground, offline and
    /// identity checks remain required.
    pub unsafe fn authorize_for_menu(
        &mut self,
        enabled: bool,
        supported_host_menu: bool,
    ) -> Result<(), &'static str> {
        self.permit = None;
        if !enabled || !foreground() {
            unsafe {
                self.suspend();
            }
            return Ok(());
        }
        let permit = if supported_host_menu {
            unsafe { first_person::authorize_frame_for_menu(true) }
        } else {
            unsafe { first_person::authorize_frame() }
        };
        match permit {
            Ok(permit) => {
                let identity = permit.identity_token().0;
                if self
                    .menu_camera
                    .is_some_and(|(token, _)| token != permit.identity_token())
                {
                    self.menu_camera = None;
                }
                if self.look_identity != Some(identity) {
                    self.retain(unsafe { GetTickCount64() });
                    self.look = None;
                    self.look_identity = Some(identity);
                    if self.fov_identity != Some(identity) {
                        self.fov_identity = Some(identity);
                        self.sprint_fov = SprintFov::default();
                        self.last_fov_tick = None;
                    }
                    self.controller.set_fov_multiplier(1.0)?;
                    self.controller.set_look_basis(None)?;
                }
                self.permit = Some(permit);
                Ok(())
            }
            Err(error) => {
                unsafe {
                    self.suspend();
                }
                self.error = Some(error);
                Err(error)
            }
        }
    }

    /// DrawParamUpdate only. Every attempt rechecks foreground, fresh permit, current
    /// player identity/map, offline/menu/death/mount gates and camera sanity.
    /// Activity/task bits are checked only when the PostPhysics permit is issued.
    pub unsafe fn tick(&mut self) -> Result<Status, &'static str> {
        // A delta is consumed once even when look is frozen or no permission
        // remains. Never derive relative look from a recentered desktop cursor.
        let timestamp = unsafe { GetTickCount64() };
        let delta = crate::overlay_input::take_look(timestamp);
        if !foreground() {
            self.permit = None;
        }
        let result = (|| {
            let Some(permit) = self.permit.as_ref().filter(|permit| permit.fresh()) else {
                self.retain(timestamp);
                self.look = None;
                self.look_identity = None;
                // Keep the sprint FOV's current widening: a permit gap is not a
                // sprint stop, and snapping to 70 degrees made the FOV pump.
                // Only a different character (above) starts it from 1 again.
                self.controller.set_fov_multiplier(1.0)?;
                self.controller.set_look_basis(None)?;
                return unsafe { self.controller.update_authorized(None) };
            };
            let newly_seeded = self.look.is_none();
            if newly_seeded {
                let retained = self
                    .retained
                    .take()
                    .filter(|&(_, identity, at)| {
                        Some(identity) == self.look_identity && retained_fresh(at, timestamp)
                    })
                    .map(|(look, _, _)| look);
                self.look = Some(match retained {
                    Some(look) => look,
                    None => Look::seed(unsafe { self.controller.look_seed(permit)? })?,
                });
            }
            if self.look_enabled
                && !self.menu_locked
                && !newly_seeded
                && let Some(delta) = delta
            {
                let delta = [
                    if self.invert[0] { -delta[0] } else { delta[0] },
                    if self.invert[1] { -delta[1] } else { delta[1] },
                ];
                self.look
                    .as_mut()
                    .expect("seeded above")
                    .apply_scaled(delta, self.look_gain)?;
            }
            self.controller
                .set_look_basis(self.look.map(|look| look.basis()))?;
            let dt = self
                .last_fov_tick
                .map_or(0.0, |last| timestamp.saturating_sub(last) as f32 / 1000.0);
            self.last_fov_tick = Some(timestamp);
            self.controller
                .set_fov_multiplier(self.sprint_fov.advance(dt))?;
            let offset = if crate::pose_lock::enabled() {
                unsafe { first_person::block_offset() }
            } else {
                None
            };
            let menu_camera = self
                .menu_camera
                .filter(|(token, _)| self.menu_locked && *token == permit.identity_token())
                .map(|(_, camera)| camera);
            self.controller.set_locked_camera(menu_camera);
            if menu_camera.is_none()
                && let Some((map, offset)) = offset
            {
                let guest = crate::guest_status::latest_host_frame(timestamp);
                let locked = crate::pose_lock::lock()
                    .try_lock()
                    .ok()
                    .and_then(|mut lock| lock.select(map, guest, timestamp));
                self.controller
                    .set_locked_camera(locked.map(|pose| first_person::LockedCamera {
                        eye: std::array::from_fn(|i| (pose.camera[i] - offset[i]) as f32),
                        basis: pose.basis,
                        fov: pose.fov,
                    }));
            }
            let status = unsafe { self.controller.update_authorized(Some(permit)) };
            if status.is_ok() && self.menu_locked && self.menu_camera.is_none() {
                self.menu_camera = self
                    .controller
                    .presented_camera()
                    .map(|camera| (permit.identity_token(), camera));
            }
            let desired = self
                .menu_camera
                .filter(|(token, _)| self.menu_locked && *token == permit.identity_token())
                .map(|(_, camera)| camera)
                .or_else(|| self.controller.desired_camera());
            if let (Some((map, offset)), Ok(_), Some(desired)) = (offset, status.as_ref(), desired)
                && let Ok(mut lock) = crate::pose_lock::lock().try_lock()
            {
                lock.note_desired(
                    crate::pose_lock::Pose {
                        map,
                        camera: std::array::from_fn(|i| f64::from(desired.eye[i]) + offset[i]),
                        basis: desired.basis,
                        fov: desired.fov,
                    },
                    timestamp,
                );
            }
            status
        })();
        match result {
            Ok(_) => {
                self.error = None;
            }
            Err(error) => {
                unsafe {
                    self.suspend();
                }
                self.error = Some(error);
            }
        }
        result
    }

    /// Sample in PostPhysics BEFORE authorize: this preserves evidence about
    /// whether the preceding DrawParamUpdate write survived later engine tasks.
    pub unsafe fn observe(&self) -> Result<CameraObservation, &'static str> {
        unsafe { self.controller.observe() }
    }

    /// Call for every early-return gate and before canceling callbacks. There is
    /// intentionally no Drop implementation that might access SDK from a worker.
    pub unsafe fn suspend(&mut self) -> RestoreStatus {
        self.retain(unsafe { GetTickCount64() });
        self.permit = None;
        self.error = None;
        self.look = None;
        self.look_identity = None;
        self.look_enabled = false;
        self.menu_locked = false;
        self.menu_camera = None;
        self.controller.set_locked_camera(None);
        // The sprint widening resumes where it was after a gate blip; a new
        // character (fov_identity) still starts from 1.
        let _ = self.controller.set_fov_multiplier(1.0);
        let _ = self.controller.set_look_basis(None);
        unsafe { self.controller.suspend() }
    }
}

const LOOK_RETAIN_MS: u64 = 3000;
fn retained_fresh(at: u64, now: u64) -> bool {
    now >= at && now - at <= LOOK_RETAIN_MS
}
impl Driver {
    fn retain(&mut self, now: u64) {
        if let (Some(look), Some(identity)) = (self.look, self.look_identity) {
            self.retained = Some((look, identity, now));
        }
    }
}

/// Vanilla's ordinary sprint speed is 1.3 times walk; its movement FOV factor
/// is (speed/walk_speed + 1)/2 = 1.15. Exponential interpolation keeps the
/// half-distance-per-20Hz-tick response independent of the host frame rate.
#[derive(Clone, Copy, Debug)]
struct SprintFov {
    sprinting: bool,
    multiplier: f32,
}
impl Default for SprintFov {
    fn default() -> Self {
        Self {
            sprinting: false,
            multiplier: 1.0,
        }
    }
}
impl SprintFov {
    fn advance(&mut self, dt: f32) -> f32 {
        if !dt.is_finite() || dt < 0.0 {
            return self.multiplier;
        }
        let target = if self.sprinting { 1.15 } else { 1.0 };
        self.multiplier = (target + (self.multiplier - target) * 0.5f32.powf(dt.min(0.25) / 0.05))
            .clamp(1.0, 1.15);
        self.multiplier
    }
}

/// Minecraft's default mouse sensitivity, used until the guest reports its own.
const DEFAULT_SENSITIVITY: f64 = 0.5;
/// Degrees per raw mouse count for a Minecraft sensitivity in [0,1].
fn look_gain(sensitivity: f64) -> f64 {
    let f = 0.6 * sensitivity + 0.2;
    f * f * f * 8.0 * 0.15
}

/// Vanilla 26.3 MouseHandler.turnPlayer uses (0.6*sensitivity+0.2)^3*8;
/// Entity.turn then applies 0.15 degrees and clamps pitch to [-90,90]. At
/// default sensitivity 0.5 that is 0.15 degrees per relative mouse count.
/// No time or smoothing term.
#[derive(Clone, Copy, Debug)]
struct Look {
    yaw: f64,
    pitch: f64,
    handedness: f64,
}
impl Look {
    fn seed(basis: [[f32; 3]; 3]) -> Result<Self, &'static str> {
        if !basis.iter().flatten().all(|v| v.is_finite()) {
            return Err("direct look seed is not finite");
        }
        let [right, up, forward] = basis;
        let cross = [
            right[1] * up[2] - right[2] * up[1],
            right[2] * up[0] - right[0] * up[2],
            right[0] * up[1] - right[1] * up[0],
        ];
        let determinant = (0..3).map(|i| cross[i] * forward[i]).sum::<f32>();
        if !(0.9..=1.1).contains(&determinant.abs()) {
            return Err("direct look seed is not orthonormal");
        }
        let handedness = if determinant > 0.0 { 1.0 } else { -1.0 };
        let length = forward.iter().map(|v| v * v).sum::<f32>().sqrt();
        if !(0.95..=1.05).contains(&length) {
            return Err("direct look seed forward invalid");
        }
        let yaw = if forward[0].abs() + forward[2].abs() > 0.0001 {
            (-(forward[0] as f64)).atan2(forward[2] as f64).to_degrees()
        } else {
            (right[2] as f64 * handedness)
                .atan2(right[0] as f64 * handedness)
                .to_degrees()
        };
        Ok(Self {
            yaw,
            pitch: (-(forward[1] / length) as f64)
                .clamp(-1.0, 1.0)
                .asin()
                .to_degrees(),
            handedness,
        })
    }
    #[cfg(test)]
    fn apply(&mut self, delta: [f32; 2]) -> Result<(), &'static str> {
        self.apply_scaled(delta, look_gain(DEFAULT_SENSITIVITY))
    }
    fn apply_scaled(&mut self, delta: [f32; 2], gain: f64) -> Result<(), &'static str> {
        if !delta.iter().all(|v| v.is_finite() && v.abs() <= 4096.0) || !gain.is_finite() {
            return Err("direct look mouse delta rejected");
        }
        // The SDK exposes actual camera right; preserve its handedness. Positive
        // mouse X must turn toward the rendered right, not an assumed world axis.
        self.yaw =
            (self.yaw - self.handedness * delta[0] as f64 * gain + 180.0).rem_euclid(360.0) - 180.0;
        self.pitch = (self.pitch + delta[1] as f64 * gain).clamp(-90.0, 90.0);
        Ok(())
    }
    fn basis(self) -> [[f32; 3]; 3] {
        let (sy, cy) = self.yaw.to_radians().sin_cos();
        let (sp, cp) = self.pitch.to_radians().sin_cos();
        [
            [cy * self.handedness, 0.0, sy * self.handedness],
            [-sy * sp, cp, cy * sp],
            [-sy * cp, -sp, cy * cp],
        ]
        .map(|v| v.map(|c| c as f32))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn near(a: f32, b: f32) {
        assert!((a - b).abs() < 0.00001, "{a} != {b}");
    }
    fn seed() -> Look {
        Look::seed([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]).unwrap()
    }
    #[test]
    fn sprint_fov_is_bounded_smooth_and_frame_partition_independent() {
        let mut a = SprintFov {
            sprinting: true,
            ..Default::default()
        };
        let mut b = a;
        near(a.advance(0.05), 1.075);
        for _ in 0..3 {
            b.advance(0.05 / 3.0);
        }
        near(a.multiplier, b.multiplier);
        for _ in 0..20 {
            a.advance(0.05);
        }
        near(a.multiplier, 1.15);
        a.sprinting = false;
        assert!(a.advance(0.05) > 1.0);
        for _ in 0..20 {
            a.advance(0.05);
        }
        near(a.multiplier, 1.0);
        let before = a.multiplier;
        assert_eq!(a.advance(f32::NAN), before);
    }
    #[test]
    fn default_gain_is_direct_and_frame_partition_independent() {
        let mut one = seed();
        one.apply([100.0, 20.0]).unwrap();
        let mut many = seed();
        for _ in 0..10 {
            many.apply([10.0, 2.0]).unwrap();
        }
        near(one.yaw as f32, -15.0);
        near(one.pitch as f32, 3.0);
        for (a, b) in one
            .basis()
            .into_iter()
            .flatten()
            .zip(many.basis().into_iter().flatten())
        {
            near(a, b);
        }
        assert!(one.basis()[2][0] > 0.0);
        assert!(one.basis()[2][1] < 0.0);
    }
    #[test]
    fn look_gain_follows_the_players_minecraft_sensitivity() {
        near(look_gain(0.5) as f32, 0.15);
        near(look_gain(0.2528884242957746) as f32, 0.052218);
        assert!(look_gain(0.0) < look_gain(1.0));
        let mut slow = seed();
        slow.apply_scaled([100.0, 0.0], look_gain(0.25)).unwrap();
        let mut normal = seed();
        normal.apply([100.0, 0.0]).unwrap();
        assert!(
            slow.yaw.abs() < normal.yaw.abs() * 0.4,
            "a low sensitivity turns proportionally slower"
        );
    }
    #[test]
    fn seed_preserves_both_camera_handedness_conventions() {
        for sign in [-1.0, 1.0] {
            let mut look =
                Look::seed([[sign, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]).unwrap();
            look.apply([20.0, 0.0]).unwrap();
            assert!(look.basis()[2][0] * sign > 0.0);
            near(look.basis()[0][0].signum(), sign);
        }
    }
    #[test]
    fn pitch_stops_at_poles_without_basis_collapse_or_delayed_motion() {
        let mut look = seed();
        look.apply([0.0, 2000.0]).unwrap();
        near(look.pitch as f32, 90.0);
        let b = look.basis();
        for row in b {
            near(row.iter().map(|v| v * v).sum::<f32>(), 1.0);
        }
        look.apply([0.0, -1.0]).unwrap();
        near(look.pitch as f32, 89.85);
        look.apply([0.0, -2000.0]).unwrap();
        near(look.pitch as f32, -90.0);
    }
    #[test]
    fn invalid_input_never_mutates_orientation() {
        let mut look = seed();
        let before = look.basis();
        for d in [[f32::NAN, 0.0], [0.0, f32::INFINITY], [4097.0, 0.0]] {
            assert!(look.apply(d).is_err());
            assert_eq!(look.basis(), before);
        }
        assert!(Look::seed([[0.0; 3]; 3]).is_err());
    }
    #[test]
    fn yaw_wrap_and_vertical_seed_keep_a_stable_horizontal_axis() {
        let mut look = seed();
        for _ in 0..100 {
            look.apply([4000.0, 0.0]).unwrap();
        }
        assert!((-180.0..180.0).contains(&look.yaw));
        look.pitch = 90.0;
        let reconstructed = Look::seed(look.basis()).unwrap();
        near(reconstructed.yaw as f32, look.yaw as f32);
    }
    #[test]
    fn view_changes_and_gui_freeze_do_not_reverse_or_discard_gameplay_look() {
        let mut driver = Driver::new(Settings::default()).unwrap();
        let mut look = seed();
        look.apply([120.0, -50.0]).unwrap();
        driver.look = Some(look);
        let forward = driver.look_forward();
        let right = driver.look_right();
        let yaw = driver.look_yaw_degrees();
        driver.set_look_enabled(false);
        driver.set_menu_locked(true);
        let camera = first_person::LockedCamera {
            eye: [2., 3., 4.],
            basis: look.basis(),
            fov: 1.2,
        };
        driver.menu_camera = Some(((7, 100), camera));
        driver.set_menu_locked(true);
        assert_eq!(driver.menu_camera.unwrap().1.eye, camera.eye);
        for mode in [0, 1, 2, 0] {
            driver.set_view_mode(mode);
            assert_eq!(driver.look_forward(), forward);
            assert_eq!(driver.look_right(), right);
            assert_eq!(driver.look_yaw_degrees(), yaw);
        }
        driver.set_look_enabled(true);
        driver.set_menu_locked(false);
        assert!(driver.menu_camera.is_none());
        assert_eq!(driver.look_forward(), forward);
        // No SDK objects have been acquired, so release must not look them up.
        assert_eq!(unsafe { driver.suspend() }, RestoreStatus::Inactive);
        assert!(driver.look_forward().is_none());
        assert!(driver.look_identity.is_none());
        assert!(!driver.look_enabled);
        assert!(!driver.menu_locked);
    }
    #[test]
    fn suspension_retains_the_same_characters_view_for_a_bounded_time() {
        let mut driver = Driver::new(Settings::default()).unwrap();
        let mut look = seed();
        look.apply([300.0, 40.0]).unwrap();
        driver.look = Some(look);
        driver.look_identity = Some(7);
        unsafe { driver.suspend() };
        let (kept, identity, at) = driver.retained.expect("view retained across suspension");
        assert_eq!(identity, 7);
        assert_eq!(kept.basis(), look.basis());
        assert!(retained_fresh(at, at + LOOK_RETAIN_MS));
        assert!(!retained_fresh(at, at + LOOK_RETAIN_MS + 1));
        assert!(!retained_fresh(at, at.saturating_sub(1)));
        // Nothing to retain without an established character identity.
        let mut fresh = Driver::new(Settings::default()).unwrap();
        fresh.look = Some(look);
        fresh.retain(1);
        assert!(fresh.retained.is_none());
    }
}
