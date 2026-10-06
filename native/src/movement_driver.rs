//! Exact-build movement at the native root-motion and proxy velocity boundaries.
//!
//! Install once before gameplay, after the executable SHA guard. Authorize from
//! PostPhysics; the narrow detour consumes only a fresh, matching local player.
//! Native functions receive temporary aligned transform/velocity arguments.
//! No player position, gravity/animation/collision flag, or retained state is
//! directly written. The original proxy setter owns velocity submission.
//! See ../README.md for compatibility and ownership requirements.

use eldenring::{
    cs::{
        CSChrPhysicsModule, CSMenuManImp, CSSessionManager, GameMan, LadderState, LobbyState,
        PlayerIns, ProtocolState,
    },
    fd4::FD4Time,
};
use fromsoftware_shared::{FromStatic, program::Program};
use ilhook::x64::{HookFlags, Registers, hook_closure_jmp_back, hook_closure_retn};
use pelite::pe64::PeObject;
use std::{
    cell::Cell,
    ffi::c_void,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

const STAGE_RVA: usize = 0x4609c0;
const STAGE_PREFIX: &[u8] = &[
    0x48, 0x8b, 0xc4, 0x56, 0x57, 0x41, 0x56, 0x48, 0x83, 0xec, 0x70, 0x48, 0xc7, 0x40, 0xa0, 0xfe,
    0xff, 0xff, 0xff,
];
const COPY_RVA: usize = 0x460a5d;
const COPY_PREFIX: &[u8] = &[
    0x41, 0x0f, 0x28, 0x00, 0x0f, 0x29, 0x81, 0xd0, 0, 0, 0, 0x41, 0x0f, 0x28, 0x48, 0x10, 0x0f,
    0x29, 0x89, 0xe0, 0, 0, 0,
];
const CALLER_RVA: usize = 0x3c891d;
const CALLER_PREFIX: &[u8] = &[
    0x4c, 0x8d, 0x83, 0x40, 0x01, 0, 0, 0x48, 0x8d, 0x55, 0xc0, 0x48, 0x8b, 0xce, 0xe8, 0x90, 0x80,
    0x09, 0,
];
const ORIENTATION_RVA: usize = 0x463d90;
const ORIENTATION_PREFIX: &[u8] = &[
    0x48, 0x8b, 0xc4, 0x53, 0x48, 0x81, 0xec, 0x90, 0, 0, 0, 0x80, 0xb9, 0xdc, 0x01, 0, 0, 0, 0x48,
    0x8b, 0xda, 0x0f, 0x84, 0xd2, 0x01, 0, 0,
];
const ORIENTATION_CALL_RVA: usize = 0x4672f1;
const ORIENTATION_CALL_PREFIX: &[u8] = &[
    0x48, 0x8b, 0xf9, 0xe8, 0x97, 0xca, 0xff, 0xff, 0x4c, 0x8b, 0xc3,
];
const VELOCITY_RVA: usize = 0xc5db30;
const VELOCITY_PREFIX: &[u8] = &[
    0x48, 0x8b, 0x81, 0xa0, 0, 0, 0, 0x48, 0x85, 0xc0, 0x74, 0x06, 0x0f, 0x28, 0x02, 0x0f, 0x29,
    0x00, 0xc3,
];
const VELOCITY_CALL_RVA: usize = 0x46779f;
const VELOCITY_CALL_PREFIX: &[u8] = &[
    0x48, 0x8b, 0x8f, 0x98, 0, 0, 0, 0x48, 0x8d, 0x54, 0x24, 0x50, 0xe8, 0x80, 0x63, 0x7f, 0, 0x48,
    0x8b, 0x8f, 0xa0, 0, 0, 0, 0x48, 0x85, 0xc9, 0x74, 0x0a, 0x48, 0x8d, 0x54, 0x24, 0x50, 0xe8,
    0x6a, 0x63, 0x7f, 0,
];
// In native468a40, after the capsule has resolved motion, this JBE chooses
// ordinary ground-offset removal instead of the extra standing adhesion snap.
// RDI is the physics module; the preceding COMISS compares adhesion radius
// (XMM6) with floor distance (XMM8). Taking the existing branch preserves all
// capsule/contact queries and the normal ground offset in XMM7.
const ADHESION_RVA: usize = 0x469437;
const ADHESION_PREFIX: &[u8] = &[
    0x76, 0x1f, 0x45, 0x0f, 0x57, 0xc4, 0xc6, 0x87, 0x92, 0, 0, 0, 1, 0xf3, 0x44, 0x0f, 0x11, 0x87,
    0x0c, 1, 0, 0,
];
const ADHESION_RADIUS_RVA: usize = 0x4692e4;
const ADHESION_RADIUS_PREFIX: &[u8] = &[
    0x80, 0xbf, 0xd0, 1, 0, 0, 0, 0x75, 0x0d, 0x80, 0xbf, 0xd7, 1, 0, 0, 0, 0xf3, 0x0f, 0x58, 0xf7,
    0x74, 3, 0x0f, 0x28, 0xf7,
];
const PERMIT_MS: u64 = 100;
/// Vanilla ClientInput.hasForwardImpulse threshold.
const FORWARD_IMPULSE: f32 = 1.0e-5;
/// A gap in authorization this short (a frame hitch, a contended mailbox, one
/// failed gate) keeps momentum, the jump and the sprint latch of the same body.
/// Longer gaps (menus, loads) start from rest as before.
const CARRY_MS: u64 = 400;
const TICK: f32 = 0.05;
static INSTALLED: AtomicBool = AtomicBool::new(false);
static INPUT_READY: AtomicBool = AtomicBool::new(false);
/// Revoked by the input phase before physics if the shared pad transaction fails.
pub fn set_input_ready(ready: bool) {
    INPUT_READY.store(ready, Ordering::Release);
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetTickCount64() -> u64;
    fn GetCurrentProcessId() -> u32;
}
#[link(name = "user32")]
unsafe extern "system" {
    fn GetForegroundWindow() -> *mut c_void;
    fn GetWindowThreadProcessId(window: *mut c_void, process: *mut u32) -> u32;
}
fn now() -> u64 {
    unsafe { GetTickCount64() }
}
fn foreground() -> bool {
    let mut pid = 0;
    unsafe {
        GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
        pid == GetCurrentProcessId()
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Input {
    /// Forward and right axes in [-1,1], from raw controls, independent of F5.
    pub forward: f32,
    pub right: f32,
    pub sprint: bool,
    pub sneak: bool,
    pub using_item: bool,
    pub jump: bool,
}
impl Input {
    /// The sprint latch has already applied vanilla's start/stop rules to `sprint`.
    pub fn sprinting(self) -> bool {
        self.sprint && self.forward > FORWARD_IMPULSE
    }
    pub fn from_buttons(buttons: u32, using_item: bool) -> Self {
        let axis = |positive: u32, negative: u32| {
            ((buttons >> positive) & 1) as f32 - ((buttons >> negative) & 1) as f32
        };
        Self {
            forward: axis(16, 17),
            right: axis(19, 18),
            sprint: buttons & (1 << 15) != 0,
            sneak: buttons & (1 << 14) != 0,
            jump: buttons & (1 << 13) != 0,
            using_item,
        }
    }
}
/// What carries the body: the player's own legs, or Torrent while the guest
/// keeps publishing a fresh mount. Collision stays native for both.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum Gait {
    #[default]
    Foot,
    Torrent,
}
impl Gait {
    /// Torrent dashes on held sprint in any direction, as in Elden Ring. On foot
    /// the vanilla latch has already decided.
    fn sprinting(self, input: Input) -> bool {
        match self {
            Self::Foot => input.sprinting(),
            Self::Torrent => input.sprint && (input.forward != 0.0 || input.right != 0.0),
        }
    }
    /// Per-tick input acceleration in blocks/tick.
    fn acceleration(self, grounded: bool, sprint: bool) -> f32 {
        let dash = if sprint {
            crate::torrent::DASH_MULTIPLIER
        } else {
            1.0
        };
        match (self, grounded) {
            (Self::Foot, true) => 0.1 * if sprint { 1.3 } else { 1.0 },
            (Self::Foot, false) => {
                if sprint {
                    0.026
                } else {
                    0.02
                }
            }
            (Self::Torrent, true) => crate::torrent::GALLOP_ACCELERATION * dash,
            (Self::Torrent, false) => crate::torrent::AIR_ACCELERATION * dash,
        }
    }
    fn max_step(self) -> f32 {
        match self {
            Self::Foot => 1.0,
            Self::Torrent => crate::torrent::MAX_STEP_M,
        }
    }
    fn jump_mps(self) -> f32 {
        match self {
            Self::Foot => 8.4,
            Self::Torrent => crate::torrent::JUMP_MPS,
        }
    }
    fn air_jumps(self) -> u8 {
        match self {
            Self::Foot => 0,
            Self::Torrent => crate::torrent::AIR_JUMPS,
        }
    }
    /// The rider's crouch and raised item do not slow Torrent.
    fn input(self, input: Input) -> Input {
        match self {
            Self::Foot => input,
            Self::Torrent => Input {
                sneak: false,
                using_item: false,
                ..input
            },
        }
    }
}
#[derive(Clone, Copy, Debug, Default)]
#[allow(dead_code)] // Complete motion telemetry is emitted through Debug logs.
pub struct Status {
    pub authorized: bool,
    pub applied_frames: u64,
    pub grounded: bool,
    /// Native mode observations, never modified or treated as movement locks.
    pub surface_constrained: bool,
    pub gravity_disabled: bool,
    pub effective_orientation: [f32; 4],
    pub requested_speed_mps: f32,
    pub native_dt: f32,
    pub sprinting: bool,
    pub gliding: bool,
    /// Riding Torrent: the mounted gait drove this step.
    pub mounted: bool,
    pub flight_sequence: u64,
    pub glide_observation: Option<crate::player_flight::Observation>,
    pub vertical_speed_mps: f32,
    pub jumping: bool,
    pub jump_count: u64,
    pub vertical_submissions: u64,
    /// Owned upward steps that skipped the post-collision standing snap.
    pub ground_adhesion_skips: u64,
    /// Observed after the native stage selected its collision route.
    pub native_physics_mode: u32,
    pub last_jump: Option<JumpObservation>,
    /// Native steps whose collision cancelled part of the request; that part of
    /// the model velocity was removed, as Minecraft zeroes a blocked axis.
    pub collision_feedback: u64,
    /// Physics steps where crouching stopped movement at a ledge.
    pub ledge_stops: u64,
    pub motion_multiplier: f32,
    pub speed_multiplier: f32,
    /// Quarter-second path-length observation; not a straight-line speed proof.
    pub measured_speed_mps: f32,
    pub original_local_translation: [f32; 3],
    pub injected_local_translation: [f32; 3],
    pub last_error: Option<&'static str>,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // The request ID correlates formatted takeoff/landing evidence.
pub struct JumpObservation {
    pub request: u64,
    pub start_y: f32,
    pub max_rise_m: f32,
    pub duration_s: f32,
    pub observed_takeoff: bool,
    pub ceiling: bool,
    pub ended: Option<&'static str>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Identity {
    player: usize,
    physics: usize,
    map: i32,
}
impl Identity {
    /// The same live character body. Crossing an open-world map tile changes
    /// only `map`; that must not revoke a glide or reset movement momentum.
    /// Warps, deaths and reloads close the PostPhysics gates separately.
    fn same_body(self, other: Self) -> bool {
        self.player == other.player && self.physics == other.physics
    }
}
#[derive(Clone, Copy)]
struct Permit {
    identity: Identity,
    input: Input,
    forward: [f32; 3],
    right: [f32; 3],
    issued: u64,
}

/// Public for portable policy tests; units inside the model are metres/tick.
#[derive(Clone, Debug, Default)]
struct HorizontalModel {
    velocity: [f32; 2],
    moving: [f32; 2],
    acceleration: [f32; 2],
    remaining: f32,
    tick_drag: f32,
    tick_grounded: bool,
}
impl HorizontalModel {
    fn reset(&mut self) {
        *self = Self::default();
    }
    /// Torrent's air jump turns the whole horizontal momentum toward the input.
    fn redirect(&mut self, direction: [f32; 3]) {
        let length = direction[0].hypot(direction[2]);
        if length < 0.0001 || !length.is_finite() {
            return;
        }
        for v in [&mut self.velocity, &mut self.moving] {
            let speed = v[0].hypot(v[1]);
            v[0] = direction[0] / length * speed;
            v[1] = direction[2] / length * speed;
        }
    }
    fn sprint_jump(&mut self, forward: [f32; 3]) {
        let length = forward[0].hypot(forward[2]);
        if length < 0.0001 || !length.is_finite() {
            return;
        }
        let v = if self.remaining > 0.0000001 {
            &mut self.moving
        } else {
            &mut self.velocity
        };
        v[0] += forward[0] / length * 0.2;
        v[1] += forward[2] / length * 0.2;
    }
    /// Removes the velocity component that native collision cancelled in the
    /// last step (expected vs actual horizontal travel, metres). Sliding along a
    /// wall keeps its tangential part; a slope or a small loss changes nothing.
    /// Returns true when velocity was removed.
    #[cfg(test)]
    fn collide(&mut self, expected: [f32; 2], actual: [f32; 2]) -> bool {
        self.collision(expected, actual).is_some()
    }
    /// Zero the model velocity on blocked world axes (crouch edge protection).
    fn stop_axes(&mut self, blocked: [bool; 2]) {
        for (axis, stop) in blocked.into_iter().enumerate() {
            if stop {
                self.velocity[axis] = 0.0;
                self.moving[axis] = 0.0;
            }
        }
    }
    /// LivingEntity.knockback: half the current velocity plus 0.4 blocks/tick away.
    fn knockback(&mut self, away: [f32; 2]) {
        if !away.iter().all(|v| v.is_finite()) {
            return;
        }
        for v in [&mut self.velocity, &mut self.moving] {
            v[0] = v[0] * 0.5 + away[0] * 0.4;
            v[1] = v[1] * 0.5 + away[1] * 0.4;
        }
    }
    /// As `collide`, returning the blocked direction's alignment with the request
    /// (1 = head-on) when velocity was removed.
    #[cfg(test)]
    fn collision(&mut self, expected: [f32; 2], actual: [f32; 2]) -> Option<f32> {
        let (normal, alignment) = blocked(expected, actual)?;
        self.remove(normal).then_some(alignment)
    }
    /// Removes the velocity component moving into `normal`; true when any was.
    fn remove(&mut self, normal: [f32; 2]) -> bool {
        let mut changed = false;
        for v in [&mut self.velocity, &mut self.moving] {
            let into = v[0] * normal[0] + v[1] * normal[1];
            if into > 0.0 {
                v[0] -= into * normal[0];
                v[1] -= into * normal[1];
                changed = true;
            }
        }
        changed
    }
    #[cfg(test)]
    fn advance(
        &mut self,
        dt: f32,
        input: Input,
        forward: [f32; 3],
        right: [f32; 3],
        grounded: bool,
    ) -> Result<[f32; 3], &'static str> {
        self.travel(dt, input, forward, right, grounded, Gait::Foot)
    }
    fn travel(
        &mut self,
        dt: f32,
        input: Input,
        forward: [f32; 3],
        right: [f32; 3],
        grounded: bool,
        gait: Gait,
    ) -> Result<[f32; 3], &'static str> {
        if !dt.is_finite() || !(0.00001..=0.1).contains(&dt) {
            self.reset();
            return Err("movement timestep outside 10us..100ms");
        }
        let direction = world_input(input, forward, right)?;
        let sprint = gait.sprinting(input);
        let amount = |grounded| gait.acceleration(grounded, sprint);
        let acceleration = amount(grounded);
        let drag = if grounded { 0.6 * 0.91 } else { 0.91 };
        let acceleration = [direction[0] * acceleration, direction[2] * acceleration];
        // Replace this tick's input contribution immediately on a native frame.
        // Do not wait for the remaining 20Hz slice, and do not add acceleration
        // twice. Constant input retains the fixed-tick trajectory exactly.
        if self.remaining > 0.0000001 {
            // A lift-off does not retroactively turn this already-started
            // grounded20Hz tick into a weak air-input tick.
            let a = amount(self.tick_grounded);
            let updated = [direction[0] * a, direction[2] * a];
            self.moving =
                std::array::from_fn(|i| self.moving[i] + updated[i] - self.acceleration[i]);
            self.acceleration = updated;
        }
        let mut elapsed = dt;
        let mut result = [0.0; 3];
        // At most three 20Hz boundaries for the validated native timestep.
        for _ in 0..4 {
            if elapsed <= 0.0000001 {
                break;
            }
            if self.remaining <= 0.0000001 {
                self.moving = [
                    self.velocity[0] + acceleration[0],
                    self.velocity[1] + acceleration[1],
                ];
                self.acceleration = acceleration;
                self.remaining = TICK;
                self.tick_drag = drag;
                self.tick_grounded = grounded;
            }
            let slice = elapsed.min(self.remaining);
            result[0] += self.moving[0] * slice / TICK;
            result[2] += self.moving[1] * slice / TICK;
            elapsed -= slice;
            self.remaining -= slice;
            if self.remaining <= 0.0000001 {
                self.velocity = self.moving.map(|v| {
                    if (v * self.tick_drag).abs() < 0.003 {
                        0.0
                    } else {
                        v * self.tick_drag
                    }
                });
            }
        }
        if result.iter().any(|v| !v.is_finite()) || result[0].hypot(result[2]) > gait.max_step() {
            self.reset();
            return Err("movement displacement exceeded bounded step");
        }
        Ok(result)
    }
}

/// The blocked direction of a native step (unit XZ, pointing into the obstacle)
/// and its alignment with the request (1 = head-on), when collision cancelled a
/// substantial part of the expected travel.
fn blocked(expected: [f32; 2], actual: [f32; 2]) -> Option<([f32; 2], f32)> {
    let length = expected[0].hypot(expected[1]);
    if !(0.002..=2.0).contains(&length)
        || !actual.iter().all(|v| v.is_finite())
        || actual[0].hypot(actual[1]) > 2.0
    {
        return None;
    }
    let lost = [expected[0] - actual[0], expected[1] - actual[1]];
    let lost_length = lost[0].hypot(lost[1]);
    // The loss must be substantial and oppose the request (not a push from behind).
    if lost_length < 0.5 * length || lost[0] * expected[0] + lost[1] * expected[1] <= 0.0 {
        return None;
    }
    let normal = [lost[0] / lost_length, lost[1] / lost_length];
    Some((
        normal,
        (normal[0] * expected[0] + normal[1] * expected[1]) / length,
    ))
}

/// Native steps around takeoff, landing and contact changes are ignored for
/// this long: ER's state switch and standing adhesion can drop one or two steps
/// of horizontal travel there, which is not a wall.
const WALL_SETTLE_S: f32 = 0.12;
/// A wall must cancel travel on this many consecutive steps, from the same
/// direction, before momentum or sprint is stopped. Frame-timing losses never do.
const WALL_CONFIRM_STEPS: u8 = 3;
/// Consecutive blocked directions must agree this closely (about 25 degrees);
/// a noisy normal would otherwise strip forward speed and leave a sideways drift.
const WALL_NORMAL_AGREEMENT: f32 = 0.9;
#[derive(Clone, Debug, Default)]
struct WallFeedback {
    streak: u8,
    settle: f32,
    normal: [f32; 2],
}
impl WallFeedback {
    fn settle(&mut self) {
        self.settle = WALL_SETTLE_S;
        self.streak = 0;
    }
    /// The confirmed blocked direction and alignment for the last native step.
    /// Only grounded steps are judged: in the air Elden Ring's own contact and
    /// integration timing does not describe walls (and the request is applied as
    /// velocity there), so an airborne step only clears the streak.
    fn observe(
        &mut self,
        dt: f32,
        expected: [f32; 2],
        actual: [f32; 2],
        grounded: bool,
    ) -> Option<([f32; 2], f32)> {
        if self.settle > 0.0 {
            self.settle -= dt;
            self.streak = 0;
            return None;
        }
        if !grounded {
            self.streak = 0;
            return None;
        }
        let Some(hit) = blocked(expected, actual) else {
            self.streak = 0;
            return None;
        };
        let agrees = self.streak == 0
            || hit.0[0] * self.normal[0] + hit.0[1] * self.normal[1] >= WALL_NORMAL_AGREEMENT;
        self.streak = if agrees {
            self.streak.saturating_add(1)
        } else {
            1
        };
        self.normal = hit.0;
        (self.streak >= WALL_CONFIRM_STEPS).then_some(hit)
    }
}

/// Animation-independent ordinary Minecraft vertical travel. Velocity is m/s;
/// the .42, .08 and .98 vanilla20Hz constants become8.4m/s and1.6m/s per tick.
/// Collision remains native. A jump starts on a fresh press; like vanilla, a
/// Space held since that jump jumps again on landing once 10 ticks passed
/// (LivingEntity.noJumpDelay), and a press made in the air that is still held
/// on landing jumps then. A stale held Space never starts the first jump.
#[derive(Clone, Debug, Default)]
struct VerticalModel {
    initialized: bool,
    was_down: bool,
    flying: bool,
    left_ground: bool,
    ground_seen: bool,
    /// Space has stayed down since a jump this model started.
    held_since_jump: bool,
    since_jump: f32,
    /// Space has stayed down since a fresh press that has not jumped yet.
    armed: bool,
    /// A knockback lift waits for the next grounded step.
    knocked: bool,
    /// Time without native contact while not in an owned flight. A walk off a
    /// ledge becomes a fall only after one Minecraft tick of it, so contact
    /// flicker on bumps and slopes never turns running into air ticks.
    ungrounded: f32,
    velocity: f32,
    remaining: f32,
    previous_y: Option<f32>,
    previous_velocity: f32,
    jumps: u64,
    /// Air jumps spent since leaving the ground (Torrent's double jump).
    air_jumps: u8,
    flight_time: f32,
    observation: Option<JumpObservation>,
}
impl VerticalModel {
    #[cfg(test)]
    fn advance(
        &mut self,
        dt: f32,
        down: bool,
        grounded: bool,
        y: f32,
    ) -> Result<f32, &'static str> {
        self.step(dt, down, grounded, y, Gait::Foot)
    }
    fn step(
        &mut self,
        dt: f32,
        down: bool,
        grounded: bool,
        y: f32,
        gait: Gait,
    ) -> Result<f32, &'static str> {
        if !dt.is_finite() || !(0.00001..=0.1).contains(&dt) || !y.is_finite() {
            return Err("vertical input invalid");
        }
        let rise = self.previous_y.map(|previous| y - previous);
        self.previous_y = Some(y);
        let pressed = self.initialized && down && !self.was_down;
        self.was_down = down;
        self.initialized = true;
        if !down {
            self.held_since_jump = false;
            self.armed = false;
        }
        if pressed {
            self.armed = true;
        }
        self.since_jump += dt;
        let repeat = down && self.held_since_jump && self.since_jump >= JUMP_DELAY_S;
        let pressed = self.armed && !self.held_since_jump;
        if grounded {
            self.ground_seen = true;
            self.ungrounded = 0.0;
        } else if !self.flying {
            self.ungrounded += dt;
        }
        if self.flying {
            self.flight_time += dt;
            if let Some(observation) = self
                .observation
                .as_mut()
                .filter(|value| value.ended.is_none())
            {
                observation.max_rise_m = observation.max_rise_m.max(y - observation.start_y);
                observation.observed_takeoff = observation.max_rise_m > 0.05;
                observation.duration_s = self.flight_time;
            }
            if !grounded || rise.is_some_and(|dy| dy > 0.002) {
                self.left_ground = true;
            }
            if (self.left_ground || self.flight_time > 0.075) && grounded && self.velocity <= 0.0 {
                self.flying = false;
                self.air_jumps = 0;
                self.velocity = 0.0;
                self.remaining = 0.0;
                if let Some(observation) = self
                    .observation
                    .as_mut()
                    .filter(|value| value.ended.is_none())
                {
                    observation.ended = Some(if observation.observed_takeoff {
                        "landed"
                    } else {
                        "blocked before takeoff"
                    });
                }
            } else if self.previous_velocity > 0.1
                && self.flight_time > 0.05
                && rise.is_some_and(|dy| dy <= 0.0005)
            {
                // A stopped upward step is not proof of a ceiling before any
                // observed takeoff: native ground adhesion can also reject it.
                // Both cases stop accumulating lift, but keep evidence distinct.
                self.velocity = 0.0;
                self.remaining = 0.0;
                if let Some(observation) = self
                    .observation
                    .as_mut()
                    .filter(|value| value.ended.is_none())
                {
                    observation.ceiling = observation.observed_takeoff;
                }
            }
        }
        if self.knocked && grounded && !self.flying {
            // Knockback lift: min(0.4, vy/2 + 0.4) blocks/tick from standing = 8 m/s.
            self.knocked = false;
            self.held_since_jump = false;
            self.armed = false;
            self.flying = true;
            self.left_ground = false;
            self.flight_time = 0.0;
            self.velocity = 8.0;
            self.remaining = TICK;
        } else if (pressed || repeat) && grounded && !self.flying {
            self.held_since_jump = true;
            self.armed = false;
            self.since_jump = 0.0;
            self.flying = true;
            self.left_ground = false;
            self.flight_time = 0.0;
            self.velocity = gait.jump_mps();
            self.remaining = TICK;
            self.jumps = self.jumps.saturating_add(1);
            self.observation = Some(JumpObservation {
                request: self.jumps,
                start_y: y,
                max_rise_m: 0.0,
                duration_s: 0.0,
                observed_takeoff: false,
                ceiling: false,
                ended: None,
            });
        } else if pressed
            && self.flying
            && !grounded
            && self.flight_time >= AIR_JUMP_DELAY_S
            && self.air_jumps < gait.air_jumps()
        {
            // Torrent's double jump: one fresh press in the air restarts the rise.
            self.held_since_jump = true;
            self.armed = false;
            self.since_jump = 0.0;
            self.left_ground = true;
            self.air_jumps += 1;
            self.velocity = gait.jump_mps();
            self.remaining = TICK;
            self.jumps = self.jumps.saturating_add(1);
        } else if !grounded && !self.flying && self.ground_seen && self.ungrounded >= LEDGE_FALL_S {
            // Walking off a ledge starts gravity, without manufacturing a jump.
            self.flying = true;
            self.left_ground = true;
            self.flight_time = 0.0;
            self.velocity = 0.0;
            self.remaining = 0.0;
        }
        if !self.flying {
            self.previous_velocity = 0.0;
            return Ok(0.0);
        }
        let mut elapsed = dt;
        let mut distance = 0.0;
        for _ in 0..4 {
            if elapsed <= 0.0000001 {
                break;
            }
            if self.remaining <= 0.0000001 {
                self.velocity = ((self.velocity - 1.6) * 0.98).max(-78.4);
                self.remaining = TICK;
            }
            let slice = elapsed.min(self.remaining);
            distance += self.velocity * slice;
            self.remaining -= slice;
            elapsed -= slice;
        }
        let velocity = distance / dt;
        self.previous_velocity = velocity;
        Ok(velocity)
    }
}

impl VerticalModel {
    fn knock(&mut self) {
        self.knocked = true;
    }
}
/// Ungrounded this long (one Minecraft tick) before a walk-off becomes a fall.
const LEDGE_FALL_S: f32 = TICK;
/// A crouch edge probe older than this is ignored (about six frames).
const LEDGE_FRESH_MS: u64 = 100;
/// Controls read this recently stand in for a contended input mailbox.
const BUTTONS_REUSE_MS: u64 = 50;
/// Ground friction for the horizontal model. During an owned jump or fall the
/// player is airborne even while ER's contact flags linger near the floor
/// (touching contact, standing adhesion radius); extra ground ticks there would
/// eat the sprint-jump impulse. The takeoff step itself is a ground tick, as in
/// vanilla, where the jump tick still applies block friction.
fn horizontal_grounded(grounded: bool, flying: bool, jumped: bool) -> bool {
    grounded && (!flying || jumped)
}
/// Vanilla LivingEntity.noJumpDelay: 10 ticks between held-Space jumps.
const JUMP_DELAY_S: f32 = 0.5;
/// An air jump needs two ticks of flight, so one press never spends both jumps.
const AIR_JUMP_DELAY_S: f32 = 2.0 * TICK;

/// Vanilla 26.3 sprint state. LocalPlayer.canStartSprinting: the sprint key or a
/// forward double tap within 7 ticks starts it with forward impulse, not sneaking
/// and not slowed by item use. shouldStopRunSprinting: it then lasts, without
/// the key, through jumps, sneaking and item use, until forward impulse stops or
/// a head-on (not minor) horizontal collision. Glancing wall contact keeps it.
#[derive(Clone, Debug, Default)]
struct SprintLatch {
    active: bool,
    forward_was_down: bool,
    since_forward_tap: Option<f32>,
}
const SPRINT_DOUBLE_TAP_S: f32 = 0.35;
impl SprintLatch {
    fn apply(&mut self, mut input: Input, dt: f32, head_on: bool) -> Input {
        let forward = input.forward > FORWARD_IMPULSE;
        let tapped = forward && !self.forward_was_down;
        let double_tap = tapped
            && self
                .since_forward_tap
                .is_some_and(|t| t <= SPRINT_DOUBLE_TAP_S);
        if let Some(t) = self.since_forward_tap.as_mut() {
            *t += dt;
        }
        if tapped {
            self.since_forward_tap = Some(0.0);
        }
        self.forward_was_down = forward;
        if self.active {
            if !forward || head_on {
                self.active = false;
            }
        } else if (input.sprint || double_tap) && forward && !input.sneak && !input.using_item {
            self.active = true;
        }
        input.sprint = self.active;
        input
    }
}

#[derive(Clone, Debug, Default)]
struct TravelMeter {
    previous: Option<[f32; 3]>,
    time: f32,
    distance: f32,
    speed: f32,
}
impl TravelMeter {
    fn sample(&mut self, position: [f32; 3], dt: f32) -> f32 {
        if let Some(previous) = self.previous {
            let distance = (position[0] - previous[0]).hypot(position[2] - previous[2]);
            if distance.is_finite() && distance < 2.0 && dt.is_finite() && dt > 0.0 {
                self.time += dt;
                self.distance += distance;
                if self.time >= 0.25 {
                    self.speed = self.distance / self.time;
                    self.time = 0.0;
                    self.distance = 0.0;
                }
            } else {
                *self = Self::default();
            }
        }
        self.previous = Some(position);
        self.speed
    }
}

fn horizontal(v: [f32; 3]) -> Result<[f32; 3], &'static str> {
    if !v.iter().all(|v| v.is_finite()) {
        return Err("movement look is non-finite");
    }
    let length = v[0].hypot(v[2]);
    if !(0.00001..=1.1).contains(&length) {
        return Err("movement look has no horizontal axis");
    }
    Ok([v[0] / length, 0.0, v[2] / length])
}
fn world_input(input: Input, forward: [f32; 3], right: [f32; 3]) -> Result<[f32; 3], &'static str> {
    if !input.forward.is_finite()
        || !input.right.is_finite()
        || input.forward.abs() > 1.0
        || input.right.abs() > 1.0
    {
        return Err("movement input outside normalized range");
    }
    let right = horizontal(right)?;
    // Looking exactly up/down still has a meaningful horizontal right vector.
    // Near vertical camera forward falls back to the caller's last valid yaw.
    let forward = horizontal(forward)?;
    if (right[0] * forward[0] + right[2] * forward[2]).abs() > 0.02 {
        return Err("movement look axes are not orthogonal");
    }
    let [strafe, ahead] = vanilla_input(input);
    Ok(std::array::from_fn(|i| {
        forward[i] * ahead + right[i] * strafe
    }))
}
/// Minecraft 26.3's movement input as (strafe, forward): KeyboardInput
/// normalizes the impulse; LocalPlayer.modifyInput scales it by 0.98, item use
/// (0.2) and sneaking (0.3), then modifyInputSpeedForSquareMovement stretches it
/// toward the unit square (diagonals reach full length, capped at 1); finally
/// LivingEntity.getInputVector normalizes only a length above one.
fn vanilla_input(input: Input) -> [f32; 2] {
    let length = input.right.hypot(input.forward);
    if length <= 0.0 {
        return [0.0; 2];
    }
    let scale =
        0.98 * if input.using_item { 0.2 } else { 1.0 } * if input.sneak { 0.3 } else { 1.0 };
    let v = [input.right / length * scale, input.forward / length * scale];
    let l = v[0].hypot(v[1]);
    if l <= 0.0 {
        return [0.0; 2];
    }
    let u = [v[0] / l, v[1] / l];
    let (ax, ay) = (u[0].abs(), u[1].abs());
    let ratio = if ay > ax { ax / ay } else { ay / ax };
    let stretched = (l * (1.0 + ratio * ratio).sqrt()).min(1.0);
    [u[0] * stretched, u[1] * stretched]
}
fn inverse_rotate(q: [f32; 4], v: [f32; 3]) -> Result<[f32; 3], &'static str> {
    let norm = q.iter().map(|v| v * v).sum::<f32>();
    if !norm.is_finite() || !(0.95..=1.05).contains(&norm) {
        return Err("movement character quaternion invalid");
    }
    let [x, y, z, w] = q.map(|v| v / norm.sqrt());
    let u = [-x, -y, -z];
    let cross = |a: [f32; 3], b: [f32; 3]| {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    };
    let t = cross(u, v).map(|v| v * 2.0);
    let c = cross(u, t);
    Ok(std::array::from_fn(|i| v[i] + w * t[i] + c[i]))
}
#[cfg(test)]
fn replace_world_horizontal(
    q: [f32; 4],
    original: [f32; 4],
    desired: [f32; 3],
) -> Result<[f32; 4], &'static str> {
    replace_world_translation(q, original, desired, None)
}
fn replace_world_translation(
    q: [f32; 4],
    original: [f32; 4],
    desired: [f32; 3],
    vertical: Option<f32>,
) -> Result<[f32; 4], &'static str> {
    let conjugate = [-q[0], -q[1], -q[2], q[3]];
    let world = inverse_rotate(conjugate, [original[0], original[1], original[2]])?;
    let local = inverse_rotate(q, [desired[0], vertical.unwrap_or(world[1]), desired[2]])?;
    Ok([local[0], local[1], local[2], original[3]])
}

#[repr(C, align(16))]
#[derive(Clone, Copy, Debug)]
struct RootTransform {
    translation: [f32; 4],
    rotation: [f32; 4],
    scale: [f32; 4],
}
type Stage = unsafe extern "system" fn(
    *mut CSChrPhysicsModule,
    *const FD4Time,
    *const RootTransform,
    u8,
) -> usize;
#[repr(C, align(16))]
#[derive(Clone, Copy)]
struct NativeVelocity([f32; 4]);
type SubmitVelocity = unsafe extern "system" fn(usize, *const NativeVelocity) -> usize;
#[derive(Clone, Copy)]
struct VelocityContext {
    proxies: [usize; 2],
    y: f32,
    flight: Option<[f32; 3]>,
    calls: u32,
}
thread_local! {static VELOCITY_CONTEXT:Cell<Option<VelocityContext>>=const{Cell::new(None)};}
struct VelocityScope(Option<VelocityContext>);
impl VelocityScope {
    fn enter(context: Option<VelocityContext>) -> Self {
        Self(VELOCITY_CONTEXT.with(|value| value.replace(context)))
    }
}
impl Drop for VelocityScope {
    fn drop(&mut self) {
        VELOCITY_CONTEXT.with(|value| value.set(self.0));
    }
}
fn vertical_replacement(
    context: Option<VelocityContext>,
    proxy: usize,
    input: NativeVelocity,
) -> Option<NativeVelocity> {
    let context = context?;
    if proxy == 0
        || !context.proxies.contains(&proxy)
        || !context.y.is_finite()
        || input.0.iter().any(|v| !v.is_finite())
    {
        return None;
    }
    let mut output = input;
    if let Some(v) = context.flight {
        if !v.iter().all(|x| x.is_finite())
            || v.iter().map(|v| v * v).sum::<f32>() > crate::player_flight::MAX_SPEED.powi(2)
        {
            return None;
        }
        output.0[..3].copy_from_slice(&v);
    } else {
        if !(-80.0..=crate::torrent::JUMP_MPS + 0.01).contains(&context.y) {
            return None;
        }
        output.0[1] = context.y;
    }
    Some(output)
}
struct Replacement {
    transform: RootTransform,
    velocity: Option<VelocityContext>,
}
#[repr(C, align(16))]
struct NativeOrientation([f32; 4]);
// Native463d90: RCX physics, RDX aligned output; returns that output pointer.
// Both branches read game memory only and write the caller-owned quaternion.
type OrientationGetter = unsafe extern "system" fn(
    *const CSChrPhysicsModule,
    *mut NativeOrientation,
) -> *mut NativeOrientation;
unsafe fn effective_orientation(
    physics: *const CSChrPhysicsModule,
    get: OrientationGetter,
) -> Result<[f32; 4], &'static str> {
    let mut output = NativeOrientation([f32::NAN; 4]);
    let returned = unsafe { get(physics, &mut output) };
    if !std::ptr::eq(returned, &output) {
        return Err("movement orientation getter returned foreign output");
    }
    // Reuse the transform's quaternion validation before accepting any result.
    inverse_rotate(output.0, [0.0; 3])?;
    Ok(output.0)
}

/// World velocity for an owned jump or fall: the model's horizontal step as m/s
/// plus its vertical velocity.
fn airborne_velocity(desired: [f32; 3], vertical: f32, dt: f32) -> Option<[f32; 3]> {
    if dt.is_nan() || dt <= 0.0 {
        return None;
    }
    let v = [desired[0] / dt, vertical, desired[2] / dt];
    v.iter().all(|x| x.is_finite()).then_some(v)
}
fn speed_multiplier(value: Option<&str>) -> Result<f32, &'static str> {
    let speed = match value {
        Some(value) => value
            .parse::<f32>()
            .map_err(|_| "ELDENCRAFT_MOVE_SPEED must be a number from0.5 to2")?,
        None => 1.0,
    };
    if !speed.is_finite() || !(0.5..=2.0).contains(&speed) {
        return Err("ELDENCRAFT_MOVE_SPEED must be from0.5 to2");
    }
    Ok(speed)
}
fn tune_horizontal(displacement: [f32; 3], multiplier: f32) -> [f32; 3] {
    [
        displacement[0] * multiplier,
        displacement[1],
        displacement[2] * multiplier,
    ]
}
/// An owned upward request may suppress standing adhesion only for the same
/// current physics object. This permission deliberately outlives Stage's TLS:
/// the native floor/contact pass executes later in PostPhysics.
fn owns_upward_step(
    permit: Option<Permit>,
    physics: usize,
    timestamp: u64,
    flying: bool,
    velocity: f32,
) -> bool {
    permit.is_some_and(|permit| {
        permit.identity.physics == physics
            && timestamp >= permit.issued
            && timestamp - permit.issued <= PERMIT_MS
    }) && flying
        && velocity.is_finite()
        && velocity > 0.05
        && velocity <= crate::player_flight::MAX_SPEED
}
fn branch_without_adhesion(flags: u64) -> u64 {
    flags | 1
} // JBE = CF || ZF.

unsafe fn skip_ground_adhesion(shared: &Mutex<Shared>, physics: usize, timestamp: u64) -> bool {
    let Ok(mut state) = shared.try_lock() else {
        return false;
    };
    let glide = state.flight.filter(|s| s.gliding && s.fresh(timestamp));
    if !owns_upward_step(
        state.permit,
        physics,
        timestamp,
        glide.is_some() || state.vertical.flying,
        glide.map_or(state.vertical.previous_velocity, |s| s.velocity[1]),
    ) || !foreground()
    {
        return false;
    }
    // Recheck offline/player/map after matching the address, before trusting any
    // SDK object. Never dereference an arbitrary other character's RDI value.
    let Some(permit) = state.permit else {
        return false;
    };
    if !unsafe { identity(false) }.is_ok_and(|i| i.same_body(permit.identity)) {
        return false;
    }
    state.status.ground_adhesion_skips = state.status.ground_adhesion_skips.saturating_add(1);
    true
}
struct Shared {
    permit: Option<Permit>,
    model: HorizontalModel,
    vertical: VerticalModel,
    travel: TravelMeter,
    speed: f32,
    status: Status,
    orientation: Option<OrientationGetter>,
    flight: Option<crate::player_flight::Sample>,
    /// Latest server-owned mount; its own timestamp is checked at each stage.
    torrent: Option<crate::torrent::Sample>,
    was_gliding: bool,
    glide_meter: crate::player_flight::Meter,
    /// Position before the previous owned step and its expected horizontal travel.
    last_step: Option<([f32; 3], [f32; 2])>,
    sprint: SprintLatch,
    wall: WallFeedback,
    /// Native contact of the previous owned step.
    last_grounded: Option<bool>,
    /// Controls read at the last physics boundary, with their time.
    last_buttons: Option<(u32, u64)>,
    /// Unit XZ direction away from an attacker, applied at the next physics step.
    knockback: Option<[f32; 2]>,
    /// Drops around the feet (+X,-X,+Z,-Z) from the post-physics probe, with its time.
    ledges: Option<([bool; 4], u64)>,
    /// The body the models describe and the last time they were driven: a short
    /// revocation of the same body keeps momentum, jump and sprint (CARRY_MS).
    body: Option<Identity>,
    active_at: u64,
    /// Last time the latched sprint was authorized, for the camera FOV.
    sprint_at: u64,
}
impl Shared {
    fn new(speed: f32, orientation: Option<OrientationGetter>) -> Self {
        Self {
            permit: None,
            model: HorizontalModel::default(),
            vertical: VerticalModel::default(),
            travel: TravelMeter::default(),
            speed,
            status: Status::default(),
            orientation,
            flight: None,
            torrent: None,
            was_gliding: false,
            glide_meter: crate::player_flight::Meter::default(),
            last_step: None,
            sprint: SprintLatch::default(),
            wall: WallFeedback::default(),
            last_grounded: None,
            last_buttons: None,
            knockback: None,
            ledges: None,
            body: None,
            active_at: 0,
            sprint_at: 0,
        }
    }
    fn reset_models(&mut self) {
        self.model.reset();
        self.vertical = VerticalModel::default();
        self.travel = TravelMeter::default();
        self.glide_meter = crate::player_flight::Meter::default();
        self.last_step = None;
        self.sprint = SprintLatch::default();
        self.wall = WallFeedback::default();
        self.last_grounded = None;
        self.knockback = None;
        self.ledges = None;
    }
    /// Controls at the physics boundary. The render thread briefly holds the
    /// mailbox while publishing; contention reuses the controls read a moment
    /// ago instead of dropping the permit and all momentum.
    fn buttons(&mut self, timestamp: u64, sampled: Option<u32>) -> Option<u32> {
        if let Some(buttons) = sampled {
            self.last_buttons = Some((buttons, timestamp));
            return Some(buttons);
        }
        self.last_buttons
            .filter(|(_, at)| timestamp >= *at && timestamp - at <= BUTTONS_REUSE_MS)
            .map(|(buttons, _)| buttons)
    }
    /// Stops driving the body. The models stay for a quick reacquisition of the
    /// same body (`carry`); anything longer or another body starts from rest.
    fn revoke(&mut self) {
        self.permit = None;
        self.flight = None;
        self.torrent = None;
        self.was_gliding = false;
        self.last_step = None;
        self.knockback = None;
        self.status.authorized = false;
    }
    fn carry(&self, identity: Identity, now: u64) -> bool {
        self.body.is_some_and(|b| b.same_body(identity))
            && now >= self.active_at
            && now - self.active_at <= CARRY_MS
    }
}
/// The installed trampoline deliberately lives until process exit. Dropping the
/// controller revokes its permit; it never frees executable code under a caller.
pub struct Driver {
    shared: Arc<Mutex<Shared>>,
    faulted: Arc<AtomicBool>,
    enabled: Arc<AtomicBool>,
    reset_required: bool,
    flight_seen: u64,
    flight_revoked: u64,
    /// Last status read; stands in while the physics hook holds the state lock.
    last_status: Cell<Status>,
}
impl Driver {
    /// # Safety
    /// Exact executable SHA must already match. Install during native startup,
    /// before any character task can execute this entrypoint, never hot-install.
    pub unsafe fn install() -> Result<Self, String> {
        let speed = speed_multiplier(std::env::var("ELDENCRAFT_MOVE_SPEED").ok().as_deref())
            .map_err(str::to_owned)?;
        let program = Program::current();
        let image = program.image();
        for (at, bytes) in [
            (STAGE_RVA, STAGE_PREFIX),
            (COPY_RVA, COPY_PREFIX),
            (CALLER_RVA, CALLER_PREFIX),
            (ORIENTATION_RVA, ORIENTATION_PREFIX),
            (ORIENTATION_CALL_RVA, ORIENTATION_CALL_PREFIX),
            (VELOCITY_RVA, VELOCITY_PREFIX),
            (VELOCITY_CALL_RVA, VELOCITY_CALL_PREFIX),
            (ADHESION_RVA, ADHESION_PREFIX),
            (ADHESION_RADIUS_RVA, ADHESION_RADIUS_PREFIX),
        ] {
            if image.get(at..at + bytes.len()) != Some(bytes) {
                return Err(format!("movement fingerprint mismatch at {at:x}"));
            }
        }
        if std::mem::offset_of!(CSChrPhysicsModule, orientation) != 0x50
            || std::mem::offset_of!(CSChrPhysicsModule, position) != 0x70
            || std::mem::offset_of!(CSChrPhysicsModule, owner) != 8
        {
            return Err("movement SDK layout mismatch".into());
        }
        if INSTALLED.swap(true, Ordering::AcqRel) {
            return Err("movement detour already installed".into());
        }
        let orientation = unsafe {
            std::mem::transmute::<usize, OrientationGetter>(
                image.as_ptr() as usize + ORIENTATION_RVA,
            )
        };
        let shared = Arc::new(Mutex::new(Shared::new(speed, Some(orientation))));
        let faulted = Arc::new(AtomicBool::new(false));
        let enabled = Arc::new(AtomicBool::new(false));
        let state = shared.clone();
        let failed = faulted.clone();
        let active = enabled.clone();
        // Install the consumer before the outer stage. Without a scoped local
        // context every call retains the original pointer and arguments.
        let velocity_callback = |registers: *mut Registers, original: usize| {
            let regs = unsafe { &*registers };
            let input = regs.rdx as *const NativeVelocity;
            let original: SubmitVelocity = unsafe { std::mem::transmute(original) };
            let context = VELOCITY_CONTEXT.with(Cell::get);
            let replacement = if context.is_some() && !input.is_null() && input as usize & 15 == 0 {
                vertical_replacement(context, regs.rcx as usize, unsafe { *input })
            } else {
                None
            };
            if replacement.is_some() {
                VELOCITY_CONTEXT.with(|value| {
                    if let Some(mut context) = value.get() {
                        context.calls = context.calls.saturating_add(1);
                        value.set(Some(context));
                    }
                });
            }
            unsafe {
                original(
                    regs.rcx as usize,
                    replacement
                        .as_ref()
                        .map_or(input, |value| value as *const _),
                )
            }
        };
        let base = image.as_ptr() as usize;
        let velocity_hook = unsafe {
            crate::hosting::hook(base + VELOCITY_RVA, |option| {
                hook_closure_retn(
                    base + VELOCITY_RVA,
                    velocity_callback,
                    option,
                    HookFlags::empty(),
                )
            })
        }
        .map_err(|error| {
            INSTALLED.store(false, Ordering::Release);
            format!("movement velocity detour install failed: {error:?}")
        })?;
        let adhesion_state = shared.clone();
        let adhesion_failed = faulted.clone();
        let adhesion_active = enabled.clone();
        let adhesion_callback = move |registers: *mut Registers| {
            if adhesion_failed.load(Ordering::Acquire)
                || !adhesion_active.load(Ordering::Acquire)
                || !INPUT_READY.load(Ordering::Acquire)
            {
                return;
            }
            let regs = unsafe { &mut *registers };
            if regs.rflags & (1 | 0x40) != 0 {
                return;
            } // Native JBE already avoids adhesion.
            let skip = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                skip_ground_adhesion(&adhesion_state, regs.rdi as usize, now())
            }));
            match skip {
                // Only saved CPU carry changes. The displaced native JBE
                // then follows its own non-adhesion path. No player flag,
                // coordinate, contact, or retained gravity state is edited.
                Ok(true) => regs.rflags = branch_without_adhesion(regs.rflags),
                Err(_) => {
                    adhesion_failed.store(true, Ordering::Release);
                }
                _ => {}
            }
        };
        let adhesion_hook = unsafe {
            crate::hosting::hook(base + ADHESION_RVA, |option| {
                hook_closure_jmp_back(
                    base + ADHESION_RVA,
                    adhesion_callback,
                    option,
                    HookFlags::empty(),
                )
            })
        }
        .map_err(|error| {
            INSTALLED.store(false, Ordering::Release);
            format!("movement adhesion detour install failed: {error:?}")
        })?;
        let stage_callback = move |registers: *mut Registers, original: usize| {
            let _phase = crate::crash::phase("movement detour");
            let regs = unsafe { &*registers };
            let physics = regs.rcx as *mut CSChrPhysicsModule;
            let time = regs.rdx as *const FD4Time;
            let transform = regs.r8 as *const RootTransform;
            let original: Stage = unsafe { std::mem::transmute(original) };
            // All policy/SDK access ends before entering the original. A
            // Rust panic falls back exactly once; none unwinds across FFI.
            let replacement = if failed.load(Ordering::Acquire)
                || !active.load(Ordering::Acquire)
                || !INPUT_READY.load(Ordering::Acquire)
            {
                None
            } else {
                match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                    prepare(&state, physics, time, transform)
                })) {
                    Ok(value) => value,
                    Err(_) => {
                        failed.store(true, Ordering::Release);
                        None
                    }
                }
            };
            let _velocity =
                VelocityScope::enter(replacement.as_ref().and_then(|value| value.velocity));
            let result = unsafe {
                original(
                    physics,
                    time,
                    replacement
                        .as_ref()
                        .map_or(transform, |v| &v.transform as *const _),
                    regs.r9 as u8,
                )
            };
            let calls = VELOCITY_CONTEXT
                .with(Cell::get)
                .map_or(0, |value| value.calls);
            if replacement.is_some()
                && let Ok(mut state) = state.try_lock()
            {
                state.status.vertical_submissions = state
                    .status
                    .vertical_submissions
                    .saturating_add(calls as u64);
                // Native4609c0 writes this u32; this read is diagnostic only.
                state.status.native_physics_mode =
                    unsafe { std::ptr::read_unaligned((physics as *const u8).add(0x388).cast()) };
            }
            result
        };
        let hook = unsafe {
            crate::hosting::hook(base + STAGE_RVA, |option| {
                hook_closure_retn(base + STAGE_RVA, stage_callback, option, HookFlags::empty())
            })
        };
        match hook {
            Ok(hook) => {
                std::mem::forget(velocity_hook);
                std::mem::forget(adhesion_hook);
                std::mem::forget(hook);
                Ok(Self {
                    shared,
                    faulted,
                    enabled,
                    reset_required: true,
                    flight_seen: 0,
                    flight_revoked: 0,
                    last_status: Cell::default(),
                })
            }
            Err(error) => {
                INSTALLED.store(false, Ordering::Release);
                Err(format!("movement detour install failed: {error:?}"))
            }
        }
    }
    /// Fresh crouch edge probe (+X,-X,+Z,-Z drops), or None to stop protecting.
    pub fn set_ledges(&mut self, drops: Option<[bool; 4]>) {
        if let Ok(mut state) = self.shared.try_lock() {
            state.ledges = drops.map(|d| (d, now()));
        }
    }
    /// Queue vanilla knockback away from an attacker for the next owned physics step.
    pub fn knockback(&mut self, away: [f32; 2]) {
        if let Ok(mut state) = self.shared.try_lock()
            && state.permit.is_some()
        {
            state.knockback = Some(away);
        }
    }
    /// PostPhysics only, with no retained SDK references in the caller. Caller
    /// additionally gates guest readiness/GUI and supplies independent look.
    pub unsafe fn authorize(
        &mut self,
        enabled: bool,
        input: Input,
        forward: [f32; 3],
        right: [f32; 3],
    ) -> Result<(), &'static str> {
        if !enabled || !foreground() || self.faulted.load(Ordering::Acquire) {
            self.suspend();
            return Ok(());
        }
        let identity = unsafe { identity(true) };
        let validated = world_input(input, forward, right);
        let (identity, _) = match (identity, validated) {
            (Ok(identity), Ok(direction)) => (identity, direction),
            (Err(error), _) | (_, Err(error)) => {
                self.suspend();
                return Err(error);
            }
        };
        self.enabled.store(false, Ordering::Release);
        let mut state = match self.shared.try_lock() {
            Ok(state) => state,
            Err(_) => {
                self.reset_required = true;
                return Err("movement state busy");
            }
        };
        let timestamp = now();
        if state
            .permit
            .is_some_and(|p| !p.identity.same_body(identity))
        {
            state.flight = None;
            self.flight_revoked = self.flight_seen;
        }
        // A frame hitch or one failed gate must not wipe momentum and the sprint
        // latch (the FOV visibly dropping in and out); longer gaps start at rest.
        if !state.carry(identity, timestamp) {
            state.reset_models();
        }
        // A suspension whose revoke lost the state lock still must not judge a
        // wall or apply a queued push across the gap.
        else if self.reset_required {
            state.last_step = None;
            state.knockback = None;
            state.wall.settle();
        }
        self.reset_required = false;
        state.body = Some(identity);
        state.active_at = timestamp;
        state.permit = Some(Permit {
            identity,
            input,
            forward,
            right,
            issued: timestamp,
        });
        state.status.authorized = true;
        self.enabled.store(true, Ordering::Release);
        Ok(())
    }
    pub fn suspend(&mut self) {
        // The driver itself is exclusively borrowed; this flag survives a
        // contended state lock and must be consumed before any reauthorization.
        self.reset_required = true;
        self.flight_revoked = self.flight_seen;
        self.enabled.store(false, Ordering::Release);
        // Atomic revocation succeeds even if a hook is using the state mutex.
        if let Ok(mut state) = self.shared.try_lock() {
            state.revoke();
        }
    }
    /// Latest server-owned glide sample; next native stage checks its original
    /// timestamp again. Contention revokes the entire movement permit.
    pub fn set_flight(&mut self, sample: Option<crate::player_flight::Sample>) {
        if let Some(s) = sample {
            self.flight_seen = self.flight_seen.max(s.sequence);
        }
        let sample = sample.filter(|s| s.sequence > self.flight_revoked);
        match self.shared.try_lock() {
            Ok(mut state) => state.flight = sample,
            Err(_) => {
                self.enabled.store(false, Ordering::Release);
                self.reset_required = true;
                self.flight_revoked = self.flight_seen;
            }
        }
    }
    /// Latest server-owned Torrent mount. A contended lock keeps the previous
    /// sample, whose own timestamp still expires it at the physics stage.
    pub fn set_torrent(&mut self, sample: Option<crate::torrent::Sample>) {
        if let Ok(mut state) = self.shared.try_lock() {
            state.torrent = sample;
        }
    }
    /// Sprint for the camera FOV: the latched sprint, held through authorization
    /// gaps no longer than CARRY_MS so the FOV never pumps on a hitch.
    pub fn camera_sprinting(&self) -> bool {
        let status = self.status();
        let Ok(state) = self.shared.try_lock() else {
            return status.authorized && status.sprinting || self.last_status.get().sprinting;
        };
        (status.authorized && status.sprinting)
            || state.sprint.active && now().saturating_sub(state.sprint_at) <= CARRY_MS
    }
    pub fn status(&self) -> Status {
        // A contended read must not report "not sprinting" for a frame: the
        // camera would pull the sprint FOV in and out while running.
        let mut status = self
            .shared
            .try_lock()
            .map_or_else(|_| self.last_status.get(), |s| s.status);
        self.last_status.set(status);
        status.authorized &= self.enabled.load(Ordering::Acquire);
        if self.faulted.load(Ordering::Acquire) {
            status.authorized = false;
            status.last_error = Some("movement detour panic; disabled until restart");
        }
        status
    }
}
impl Drop for Driver {
    fn drop(&mut self) {
        self.suspend();
    }
}

unsafe fn identity(require_activity: bool) -> Result<Identity, &'static str> {
    unsafe {
        let game = GameMan::instance().map_err(|_| "movement waiting for game")?;
        if game.is_in_online_mode || game.warp_requested {
            return Err("movement online/transition gate");
        }
        let session = CSSessionManager::instance().map_err(|_| "movement waiting for session")?;
        if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None
        {
            return Err("movement session gate");
        }
        let menu = CSMenuManImp::instance().map_err(|_| "movement waiting for menu")?;
        if !menu.system_announce_view_model.view.as_ref().is_active {
            return Err("movement blocking menu");
        }
        let player = PlayerIns::local_player().map_err(|_| "movement waiting for player")?;
        if (require_activity
            && (!player.chr_ins.chr_flags1c8.is_active()
                || !player.chr_ins.chr_flags1c8.update_tasks_registered()))
            || player.chr_ins.chr_flags1c5.death_flag()
            || player.chr_ins.modules.data.hp <= 0
            || player.current_block_id.0 == -1
        {
            return Err("movement player inactive/dead");
        }
        if player.chr_ins.modules.ride.is_mounted
            || player.chr_ins.modules.ride.is_mounting
            || player.chr_ins.chr_ctrl.disable_move
            || player.chr_ins.modules.ladder.state != LadderState::None
        {
            return Err("movement mounted/ladder gate");
        }
        let physics = &*player.chr_ins.modules.physics;
        if !std::ptr::eq(physics.owner.as_ptr(), &player.chr_ins) {
            return Err("movement physics owner mismatch");
        }
        // Surface constraint selects a configured root-motion orientation; the
        // getter below handles both modes. gravity_disabled is a per-stage TAE
        // request consumed/cleared by native467130, not a horizontal movement lock.
        Ok(Identity {
            player: player as *const _ as usize,
            physics: physics as *const _ as usize,
            map: player.current_block_id.0,
        })
    }
}

unsafe fn prepare(
    shared: &Mutex<Shared>,
    physics: *mut CSChrPhysicsModule,
    time: *const FD4Time,
    transform: *const RootTransform,
) -> Option<Replacement> {
    let mut state = shared.try_lock().ok()?;
    let permit = state.permit?;
    if permit.identity.physics != physics as usize {
        return None;
    }
    let timestamp = now();
    if timestamp < permit.issued || timestamp - permit.issued > PERMIT_MS || !foreground() {
        state.revoke();
        return None;
    }
    let result = (|| {
        unsafe {
            if !identity(false)?.same_body(permit.identity) {
                return Err("movement identity changed before stage");
            }
            if time.is_null() || transform.is_null() || (transform as usize) & 15 != 0 {
                return Err("movement stage arguments invalid");
            }
            let physics = &*physics;
            let input = *transform;
            if !input
                .translation
                .iter()
                .chain(input.rotation.iter())
                .chain(input.scale.iter())
                .all(|v| v.is_finite() && v.abs() < 10000.0)
            {
                return Err("movement original transform invalid");
            }
            let grounded = physics.standing_on_solid_ground || physics.touching_solid_ground;
            state.status.surface_constrained = physics.is_surface_constrained;
            state.status.gravity_disabled = physics.gravity_disabled;
            let dt = (*time).time;
            let getter = state
                .orientation
                .ok_or("movement orientation getter unavailable")?;
            let q = effective_orientation(physics as *const _, getter)?;
            // Sample controls immediately before physics instead of replaying the
            // preceding PostPhysics sample. The permit still owns all authorization.
            let buttons = state
                .buttons(timestamp, crate::overlay_input::peek_buttons(timestamp))
                .ok_or("movement controls unavailable at physics boundary")?;
            let position = [physics.position.0, physics.position.1, physics.position.2];
            let glide = state.flight.filter(|s| s.gliding && s.fresh(timestamp));
            let gait = if glide.is_none()
                && state
                    .torrent
                    .is_some_and(|s| s.mounted && s.fresh(timestamp))
            {
                Gait::Torrent
            } else {
                Gait::Foot
            };
            let raw_input = gait.input(Input::from_buttons(buttons, permit.input.using_item));
            let glide_observation = state
                .glide_meter
                .observe(position, glide, timestamp, dt, grounded);
            // Native collision of the previous step: cancel the blocked part of
            // the model velocity so walls stop momentum instead of storing it.
            // Takeoff, landing and contact changes are not walls (`WallFeedback`).
            let mut head_on = false;
            if state.last_grounded.is_some_and(|was| was != grounded) {
                state.wall.settle();
            }
            state.last_grounded = Some(grounded);
            if let Some((before, expected)) = state.last_step.take() {
                let actual = [position[0] - before[0], position[2] - before[2]];
                let judged = grounded && !state.vertical.flying;
                if let Some((normal, alignment)) = state.wall.observe(dt, expected, actual, judged)
                {
                    if state.model.remove(normal) {
                        state.status.collision_feedback =
                            state.status.collision_feedback.saturating_add(1);
                    }
                    head_on = alignment > 0.8;
                }
            }
            let current_input = match gait {
                Gait::Foot => state.sprint.apply(raw_input, dt, head_on),
                // Torrent dashes only while sprint is held; dismounting starts unlatched.
                Gait::Torrent => {
                    state.sprint = SprintLatch::default();
                    raw_input
                }
            };
            // Vanilla knockback (strength 0.4); a glide keeps its own physics and
            // Torrent holds its line.
            if let Some(away) = state
                .knockback
                .take()
                .filter(|_| glide.is_none() && gait == Gait::Foot)
            {
                state.model.knockback(away);
                if grounded {
                    state.vertical.knock();
                }
            }
            let (desired, vertical) = if let Some(glide) = glide {
                let desired = glide
                    .displacement(timestamp, dt)
                    .ok_or("glide displacement exceeded bounded step")?;
                state.model.reset();
                state.vertical = VerticalModel::default();
                (desired, glide.velocity[1])
            } else {
                if state.was_gliding {
                    // Exit into ordinary native-collision fall without replaying
                    // a jump press or retaining the pre-glide horizontal model.
                    state.vertical = VerticalModel {
                        initialized: true,
                        was_down: current_input.jump,
                        ground_seen: true,
                        previous_y: Some(position[1]),
                        velocity: state.status.vertical_speed_mps.min(0.),
                        ..VerticalModel::default()
                    };
                    state.vertical.flying = !grounded;
                    state.vertical.left_ground = !grounded;
                }
                let (jumps, was_flying, air_jumps) = (
                    state.vertical.jumps,
                    state.vertical.flying,
                    state.vertical.air_jumps,
                );
                let vertical =
                    state
                        .vertical
                        .step(dt, current_input.jump, grounded, position[1], gait)?;
                let air_jumped = state.vertical.air_jumps > air_jumps;
                let jumped = state.vertical.jumps != jumps && !air_jumped;
                if jumped || air_jumped || was_flying != state.vertical.flying {
                    state.wall.settle();
                }
                match gait {
                    Gait::Foot if jumped && current_input.sprinting() => {
                        state.model.sprint_jump(permit.forward);
                    }
                    // Torrent leaps along the ridden direction and steers its air jump.
                    Gait::Torrent if jumped || air_jumped => {
                        let direction = world_input(current_input, permit.forward, permit.right)?;
                        if jumped {
                            state.model.sprint_jump(direction);
                        } else {
                            state.model.redirect(direction);
                        }
                    }
                    _ => {}
                }
                let model_grounded = horizontal_grounded(grounded, state.vertical.flying, jumped);
                let desired = state.model.travel(
                    dt,
                    current_input,
                    permit.forward,
                    permit.right,
                    model_grounded,
                    gait,
                )?;
                let mut desired = tune_horizontal(desired, state.speed);
                if desired[0].hypot(desired[2]) > gait.max_step() {
                    return Err("tuned movement exceeded bounded step");
                }
                // Vanilla crouch edge protection, per world axis, from a fresh probe.
                if current_input.sneak
                    && grounded
                    && !state.vertical.flying
                    && let Some((drops, _)) = state
                        .ledges
                        .filter(|(_, at)| timestamp >= *at && timestamp - at <= LEDGE_FRESH_MS)
                {
                    let (clamped, blocked) = crate::ledge::clamp(desired, drops);
                    desired = clamped;
                    state.model.stop_axes(blocked);
                    if blocked.iter().any(|b| *b) {
                        state.status.ledge_stops = state.status.ledge_stops.saturating_add(1);
                    }
                }
                // Native scales the resolved delta by motion_multiplier; expect that much.
                let scale = if physics.motion_multiplier.is_finite() {
                    physics.motion_multiplier.clamp(0.0, 4.0)
                } else {
                    1.0
                };
                state.last_step = Some((position, [desired[0] * scale, desired[2] * scale]));
                (desired, vertical)
            };
            state.was_gliding = glide.is_some();
            state.active_at = timestamp;
            if gait.sprinting(current_input) {
                state.sprint_at = timestamp;
            }
            let measured_speed = state.travel.sample(position, dt);
            // Exact native caller at46779f reads these same two proxy addresses.
            // They are only matched during this particular synchronous stage call;
            // no reference or address is retained in the shared model.
            let address = physics as *const CSChrPhysicsModule as *const u8;
            let proxies = [
                *(address.add(0x98) as *const usize),
                *(address.add(0xa0) as *const usize),
            ];
            // In an owned jump or fall the whole velocity is Minecraft's: Elden Ring's
            // airborne integration otherwise keeps its own horizontal inertia, so the
            // jump curved off the requested direction and lost or gained momentum.
            let flight = glide.map(|s| s.velocity).or_else(|| {
                state
                    .vertical
                    .flying
                    .then(|| airborne_velocity(desired, vertical, dt))
                    .flatten()
            });
            let velocity = (glide.is_some() || state.vertical.flying).then_some(VelocityContext {
                proxies,
                y: vertical,
                flight,
                calls: 0,
            });
            let mut output = input;
            // The same vertical intent must enter the root-motion path: native
            //466820 decides whether to integrate collision from that earlier vector.
            // Updating only the proxy setter leaves stationary motion in native
            //state1, whose461730 branch resets velocity and skips integration.
            output.translation = replace_world_translation(
                q,
                input.translation,
                desired,
                (glide.is_some() || state.vertical.flying).then_some(vertical * dt),
            )?;
            state.status = Status {
                authorized: true,
                applied_frames: state.status.applied_frames.saturating_add(1),
                grounded,
                surface_constrained: physics.is_surface_constrained,
                gravity_disabled: physics.gravity_disabled,
                effective_orientation: q,
                requested_speed_mps: desired[0].hypot(desired[2]) / dt,
                native_dt: dt,
                sprinting: gait.sprinting(current_input),
                gliding: glide.is_some(),
                mounted: gait == Gait::Torrent,
                flight_sequence: glide.map_or(0, |s| s.sequence),
                glide_observation,
                vertical_speed_mps: vertical,
                jumping: state.vertical.flying,
                jump_count: state.vertical.jumps,
                vertical_submissions: state.status.vertical_submissions,
                ground_adhesion_skips: state.status.ground_adhesion_skips,
                native_physics_mode: state.status.native_physics_mode,
                last_jump: state.vertical.observation,
                collision_feedback: state.status.collision_feedback,
                ledge_stops: state.status.ledge_stops,
                motion_multiplier: physics.motion_multiplier,
                speed_multiplier: state.speed,
                measured_speed_mps: measured_speed,
                original_local_translation: [
                    input.translation[0],
                    input.translation[1],
                    input.translation[2],
                ],
                injected_local_translation: [
                    output.translation[0],
                    output.translation[1],
                    output.translation[2],
                ],
                last_error: None,
            };
            Ok(Replacement {
                transform: output,
                velocity,
            })
        }
    })();
    match result {
        Ok(value) => Some(value),
        Err(error) => {
            state.revoke();
            state.status.last_error = Some(error);
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const FORWARD: [f32; 3] = [0.0, 0.0, 1.0];
    const RIGHT: [f32; 3] = [1.0, 0.0, 0.0];
    #[test]
    fn sprint_latches_like_vanilla_26_3_start_and_stop_rules() {
        let dt = 1.0 / 60.0;
        let forward = Input {
            forward: 1.0,
            ..Input::default()
        };
        let mut latch = SprintLatch::default();
        assert!(
            !latch.apply(forward, dt, false).sprinting(),
            "walking without the key"
        );
        assert!(
            latch
                .apply(
                    Input {
                        sprint: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting(),
            "key starts sprint"
        );
        for _ in 0..120 {
            assert!(
                latch.apply(forward, dt, false).sprinting(),
                "keeps sprinting after Ctrl is released"
            );
        }
        for _ in 0..30 {
            assert!(
                latch
                    .apply(
                        Input {
                            jump: true,
                            ..forward
                        },
                        dt,
                        false
                    )
                    .sprinting(),
                "jumping keeps it"
            );
        }
        assert!(
            latch
                .apply(
                    Input {
                        sneak: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting(),
            "shouldStopRunSprinting ignores sneaking"
        );
        assert!(
            latch
                .apply(
                    Input {
                        using_item: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting(),
            "and item use"
        );
        assert!(
            !latch.apply(forward, dt, true).sprinting(),
            "head-on wall ends it"
        );
        assert!(
            !latch
                .apply(
                    Input {
                        sprint: true,
                        sneak: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting(),
            "canStartSprinting refuses while sneaking"
        );
        assert!(
            !latch
                .apply(
                    Input {
                        sprint: true,
                        using_item: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting(),
            "or while slowed by an item"
        );
        assert!(
            latch
                .apply(
                    Input {
                        sprint: true,
                        ..forward
                    },
                    dt,
                    false
                )
                .sprinting()
        );
        assert!(
            !latch.apply(Input::default(), dt, false).sprinting(),
            "stopping ends it"
        );
        assert!(
            !latch.apply(forward, dt, false).sprinting(),
            "does not resume by itself"
        );
        assert!(
            !latch
                .apply(
                    Input {
                        sprint: true,
                        forward: -1.0,
                        ..Input::default()
                    },
                    dt,
                    false
                )
                .sprinting(),
            "needs forward impulse"
        );
    }
    #[test]
    fn double_tapping_forward_starts_sprint_only_within_seven_ticks() {
        let dt = 1.0 / 60.0;
        let forward = Input {
            forward: 1.0,
            ..Input::default()
        };
        let mut latch = SprintLatch::default();
        latch.apply(forward, dt, false);
        for _ in 0..10 {
            latch.apply(Input::default(), dt, false);
        }
        assert!(
            latch.apply(forward, dt, false).sprinting(),
            "second tap after 0.18 s"
        );
        let mut slow = SprintLatch::default();
        slow.apply(forward, dt, false);
        for _ in 0..30 {
            slow.apply(Input::default(), dt, false);
        }
        assert!(
            !slow.apply(forward, dt, false).sprinting(),
            "0.5 s is too slow"
        );
    }
    #[test]
    fn held_space_jumps_again_on_landing_but_never_from_stale_input() {
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        assert!(model.advance(TICK, true, true, 0.0).unwrap() > 8.3);
        assert_eq!(model.jumps, 1);
        let mut y = 0.0;
        let mut grounded = false;
        for _ in 0..40 {
            let v = model.advance(TICK, true, grounded, y).unwrap();
            y = (y + v * TICK).max(0.0);
            grounded = y <= 0.0;
            if model.jumps == 2 {
                break;
            }
        }
        assert_eq!(model.jumps, 2, "held Space chains the next jump on landing");
        let mut stale = VerticalModel::default();
        assert_eq!(stale.advance(TICK, true, true, 0.0).unwrap(), 0.0);
        for _ in 0..40 {
            stale.advance(TICK, true, true, 0.0).unwrap();
        }
        assert_eq!(
            stale.jumps, 0,
            "Space already held at acquisition never jumps"
        );
    }
    #[test]
    fn knockback_halves_velocity_and_pushes_away_then_lifts_once() {
        let mut m = HorizontalModel {
            velocity: [0.2, 0.0],
            moving: [0.2, 0.0],
            ..HorizontalModel::default()
        };
        m.knockback([0.0, 1.0]);
        assert!((m.velocity[0] - 0.1).abs() < 1e-6 && (m.velocity[1] - 0.4).abs() < 1e-6);
        let mut v = VerticalModel::default();
        v.advance(TICK, false, true, 0.0).unwrap();
        v.knock();
        assert!(
            (v.advance(TICK, false, true, 0.0).unwrap() - 8.0).abs() < 0.5,
            "lift from the ground"
        );
        assert_eq!(v.jumps, 0, "a knockback is not a jump");
    }
    #[test]
    fn space_pressed_in_the_air_and_held_jumps_on_landing_but_a_tap_does_not() {
        let fly = |model: &mut VerticalModel, space: bool| {
            model.advance(TICK, space, false, 0.5).unwrap();
        };
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, true, true, 0.0).unwrap();
        fly(&mut model, false);
        for _ in 0..12 {
            fly(&mut model, false);
        }
        fly(&mut model, true);
        fly(&mut model, true);
        model.advance(TICK, true, true, 0.0).unwrap();
        assert_eq!(model.jumps, 2, "held through landing jumps immediately");
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, true, true, 0.0).unwrap();
        fly(&mut model, false);
        for _ in 0..12 {
            fly(&mut model, false);
        }
        fly(&mut model, true);
        fly(&mut model, false);
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, false, true, 0.0).unwrap();
        assert_eq!(
            model.jumps, 1,
            "a press released before landing is not buffered"
        );
    }
    #[test]
    fn takeoff_airborne_and_short_losses_never_stop_momentum_but_a_wall_does() {
        let dt = 1. / 60.;
        let mut wall = WallFeedback::default();
        wall.settle();
        for _ in 0..7 {
            assert!(
                wall.observe(dt, [0.0, 0.1], [0.0, 0.0], true).is_none(),
                "takeoff/landing steps"
            );
        }
        for _ in 0..20 {
            assert!(
                wall.observe(dt, [0.0, 0.1], [0.0, 0.0], false).is_none(),
                "airborne steps are never judged"
            );
        }
        assert!(wall.observe(dt, [0.0, 0.1], [0.0, 0.0], true).is_none());
        assert!(
            wall.observe(dt, [0.0, 0.1], [0.0, 0.0], true).is_none(),
            "two losses are not yet a wall"
        );
        assert!(wall.observe(dt, [0.0, 0.1], [0.0, 0.1], true).is_none());
        assert!(
            wall.observe(dt, [0.0, 0.1], [0.0, 0.06], true).is_none(),
            "a 40% loss is not a wall"
        );
        for _ in 0..2 {
            assert!(wall.observe(dt, [0.0, 0.1], [0.0, 0.0], true).is_none());
        }
        let (normal, alignment) = wall
            .observe(dt, [0.0, 0.1], [0.0, 0.0], true)
            .expect("three consecutive losses are a wall");
        assert!((normal[1] - 1.0).abs() < 1e-6 && alignment > 0.99);
        // Directions that disagree from step to step are noise, not a wall.
        let mut noisy = WallFeedback::default();
        for i in 0..12 {
            let side = if i % 2 == 0 { 0.09 } else { -0.09 };
            assert!(
                noisy.observe(dt, [0.0, 0.1], [side, 0.02], true).is_none(),
                "alternating normals"
            );
        }
    }
    #[test]
    fn short_gaps_keep_momentum_and_sprint_of_the_same_body_but_long_gaps_or_another_body_do_not() {
        let body = Identity {
            player: 1,
            physics: 2,
            map: 3,
        };
        let mut shared = Shared::new(1.0, None);
        shared.body = Some(body);
        shared.active_at = 1000;
        shared.model.velocity = [0.0, 0.28];
        shared.sprint.active = true;
        shared.revoke();
        assert_eq!(
            shared.model.velocity,
            [0.0, 0.28],
            "a revocation keeps the models"
        );
        assert!(shared.sprint.active);
        assert!(shared.carry(body, 1000 + CARRY_MS), "a hitch is carried");
        assert!(
            shared.carry(Identity { map: 4, ..body }, 1200),
            "crossing a map tile is the same body"
        );
        assert!(
            !shared.carry(body, 1001 + CARRY_MS),
            "a long gap starts at rest"
        );
        assert!(
            !shared.carry(Identity { physics: 9, ..body }, 1100),
            "another body starts at rest"
        );
        assert!(
            !Shared::new(1.0, None).carry(body, 0),
            "nothing to carry before the first drive"
        );
    }
    #[test]
    fn contact_flicker_is_not_a_fall_but_a_tick_without_ground_is() {
        let dt = 1. / 60.;
        let mut model = VerticalModel::default();
        model.advance(dt, false, true, 0.0).unwrap();
        assert_eq!(model.advance(dt, false, false, 0.0).unwrap(), 0.0);
        assert!(!model.flying, "one ungrounded frame");
        model.advance(dt, false, true, 0.0).unwrap();
        for _ in 0..2 {
            model.advance(dt, false, false, 0.0).unwrap();
        }
        assert!(!model.flying, "two frames are still flicker");
        model.advance(dt, false, false, 0.0).unwrap();
        assert!(
            model.flying,
            "a full tick without contact walks off the ledge"
        );
    }
    #[test]
    fn owned_flight_uses_air_friction_even_while_native_contact_lingers() {
        assert!(horizontal_grounded(true, false, false));
        assert!(
            horizontal_grounded(true, true, true),
            "the takeoff step is a ground tick"
        );
        assert!(
            !horizontal_grounded(true, true, false),
            "lingering contact during the jump"
        );
        assert!(!horizontal_grounded(false, false, false));
        // Sprint-jump distance over a whole jump: lingering ground ticks lose most of it.
        let sprint = Input {
            forward: 1.0,
            sprint: true,
            ..Input::default()
        };
        let run = |linger: usize| {
            let mut m = HorizontalModel::default();
            for _ in 0..40 {
                m.advance(TICK, sprint, FORWARD, RIGHT, true).unwrap();
            }
            m.sprint_jump(FORWARD);
            (0..12)
                .map(|i| m.advance(TICK, sprint, FORWARD, RIGHT, i < linger).unwrap()[2])
                .sum::<f32>()
        };
        assert!(
            run(0) > run(3) + 0.25,
            "air ticks keep the sprint-jump momentum"
        );
    }
    #[test]
    fn contended_input_mailbox_reuses_recent_controls_then_fails_closed() {
        let mut shared = Shared::new(1.15, None);
        assert_eq!(shared.buttons(1000, None), None);
        assert_eq!(shared.buttons(1000, Some(1 << 16)), Some(1 << 16));
        assert_eq!(shared.buttons(1000 + BUTTONS_REUSE_MS, None), Some(1 << 16));
        assert_eq!(shared.buttons(1001 + BUTTONS_REUSE_MS, None), None);
    }
    #[test]
    fn contended_status_read_keeps_the_last_sprint_state() {
        let shared = Arc::new(Mutex::new(Shared::new(1.15, None)));
        shared.lock().unwrap().status = Status {
            authorized: true,
            sprinting: true,
            ..Status::default()
        };
        let driver = Driver {
            shared: shared.clone(),
            faulted: Arc::new(AtomicBool::new(false)),
            enabled: Arc::new(AtomicBool::new(true)),
            reset_required: false,
            flight_seen: 0,
            flight_revoked: 0,
            last_status: Cell::default(),
        };
        assert!(driver.status().sprinting);
        let locked = shared.lock().unwrap();
        assert!(driver.status().sprinting && driver.status().authorized);
        drop(locked);
    }
    #[test]
    fn head_on_wall_stops_momentum_and_sliding_keeps_the_tangent() {
        let mut m = HorizontalModel {
            velocity: [0.0, 0.2],
            moving: [0.0, 0.25],
            ..HorizontalModel::default()
        };
        assert!(m.collide([0.0, 0.1], [0.0, 0.0]), "head-on");
        assert!(m.velocity[1].abs() < 1e-6 && m.moving[1].abs() < 1e-6);
        let mut m = HorizontalModel {
            velocity: [0.1, 0.1],
            moving: [0.1, 0.1],
            ..HorizontalModel::default()
        };
        assert!(
            m.collide([0.05, 0.05], [0.05, 0.0]),
            "diagonal into a wall along X"
        );
        assert!(
            (m.velocity[0] - 0.1).abs() < 1e-6 && m.velocity[1].abs() < 1e-6,
            "tangent kept, normal removed"
        );
    }
    #[test]
    fn slopes_pushes_and_teleports_do_not_stop_momentum() {
        let mut m = HorizontalModel {
            velocity: [0.0, 0.2],
            moving: [0.0, 0.2],
            ..HorizontalModel::default()
        };
        assert!(!m.collide([0.0, 0.1], [0.0, 0.08]), "uphill loss is small");
        assert!(
            !m.collide([0.0, 0.1], [0.0, 0.3]),
            "pushed forward by the world"
        );
        assert!(!m.collide([0.0, 0.1], [0.0, 5.0]), "teleport");
        assert!(!m.collide([0.0, 0.001], [0.0, 0.0]), "standing still");
        assert_eq!(m.velocity, [0.0, 0.2]);
    }
    #[test]
    fn lift_off_does_not_subtract_this_ticks_ground_acceleration() {
        let input = Input {
            forward: 1.,
            sprint: true,
            ..Input::default()
        };
        let mut m = HorizontalModel::default();
        let first = m.advance(TICK / 3., input, FORWARD, RIGHT, true).unwrap();
        let second = m.advance(TICK / 3., input, FORWARD, RIGHT, false).unwrap();
        assert!((first[2] - second[2]).abs() < 1e-6);
        let third = m.advance(TICK / 3., input, FORWARD, RIGHT, false).unwrap();
        assert!((third[2] - first[2]).abs() < 1e-6);
        assert_eq!(m.tick_drag, 0.6 * 0.91);
        m.advance(TICK / 3., input, FORWARD, RIGHT, false).unwrap();
        assert_eq!(m.tick_drag, 0.91);
    }
    #[test]
    fn sprint_jump_adds_one_real_vanilla_forward_impulse_without_losing_momentum() {
        let input = Input {
            forward: 1.,
            sprint: true,
            ..Input::default()
        };
        let mut base = HorizontalModel::default();
        for _ in 0..20 {
            base.advance(TICK, input, FORWARD, RIGHT, true).unwrap();
        }
        let mut jump = base.clone();
        jump.sprint_jump(FORWARD);
        let normal = base.advance(TICK, input, FORWARD, RIGHT, false).unwrap();
        let boosted = jump.advance(TICK, input, FORWARD, RIGHT, false).unwrap();
        assert!((boosted[2] - normal[2] - 0.2).abs() < 1e-6);
        let normal = base.advance(TICK, input, FORWARD, RIGHT, false).unwrap();
        let boosted = jump.advance(TICK, input, FORWARD, RIGHT, false).unwrap();
        assert!((boosted[2] - normal[2] - 0.2 * 0.91).abs() < 1e-6);
    }
    #[test]
    fn glide_submission_replaces_xyz_only_for_the_scoped_collision_proxy() {
        let input = NativeVelocity([1., 2., 3., 4.]);
        let c = VelocityContext {
            proxies: [1, 2],
            y: 9.,
            flight: Some([12., 9., -3.]),
            calls: 0,
        };
        assert_eq!(
            vertical_replacement(Some(c), 1, input).unwrap().0,
            [12., 9., -3., 4.]
        );
        assert!(vertical_replacement(Some(c), 3, input).is_none());
        assert!(
            vertical_replacement(
                Some(VelocityContext {
                    flight: Some([120., 1., 0.]),
                    ..c
                }),
                1,
                input
            )
            .is_none()
        );
    }
    #[test]
    fn pace_tuning_is_bounded_horizontal_only_and_defaults_to_exact_minecraft() {
        assert_eq!(speed_multiplier(None), Ok(1.0));
        assert_eq!(speed_multiplier(Some("1.0")), Ok(1.0));
        for value in ["NaN", "inf", "0.49", "2.01", "fast"] {
            assert!(speed_multiplier(Some(value)).is_err());
        }
        assert_eq!(tune_horizontal([1.0, 3.0, 2.0], 1.15), [1.15, 3.0, 2.3]);
    }
    #[test]
    fn transform_layout_matches_native_three_aligned_vectors() {
        assert_eq!(std::mem::size_of::<RootTransform>(), 48);
        assert_eq!(std::mem::align_of::<RootTransform>(), 16);
    }
    #[test]
    fn input_follows_minecraft_26_3_square_movement() {
        let length = |input: Input| {
            let v = world_input(input, FORWARD, RIGHT).unwrap();
            v[0].hypot(v[2])
        };
        assert!(
            (length(Input {
                forward: 1.0,
                ..Input::default()
            }) - 0.98)
                .abs()
                < 1e-6,
            "straight is 0.98"
        );
        assert!(
            (length(Input {
                forward: 1.0,
                right: 1.0,
                ..Input::default()
            }) - 1.0)
                .abs()
                < 1e-6,
            "a diagonal stretches to the unit square, capped at 1"
        );
        assert!(
            (length(Input {
                right: 1.0,
                sneak: true,
                ..Input::default()
            }) - 0.294)
                .abs()
                < 1e-6,
            "sneak 0.3"
        );
        assert!(
            (length(Input {
                forward: 1.0,
                right: 1.0,
                sneak: true,
                ..Input::default()
            }) - 0.294 * 2f32.sqrt())
            .abs()
                < 1e-5,
            "sneaking diagonal"
        );
        assert!(
            (length(Input {
                forward: 1.0,
                using_item: true,
                ..Input::default()
            }) - 0.196)
                .abs()
                < 1e-6,
            "item use 0.2"
        );
        let v = world_input(
            Input {
                forward: 1.0,
                right: 1.0,
                ..Input::default()
            },
            FORWARD,
            RIGHT,
        )
        .unwrap();
        assert!(
            (v[0] - v[2]).abs() < 1e-6 && v[0] > 0.0,
            "diagonal stays at 45 degrees"
        );
        assert_eq!(length(Input::default()), 0.0);
    }
    #[test]
    fn owned_flight_submits_the_whole_minecraft_velocity() {
        let input = NativeVelocity([3., -1., 7., 0.]);
        let flight = airborne_velocity([0.05, 0.0, 0.1], 4.0, 0.0125).unwrap();
        assert_eq!(flight, [4.0, 4.0, 8.0]);
        let c = VelocityContext {
            proxies: [5, 6],
            y: 4.0,
            flight: Some(flight),
            calls: 0,
        };
        assert_eq!(
            vertical_replacement(Some(c), 6, input).unwrap().0,
            [4.0, 4.0, 8.0, 0.0],
            "no Elden Ring inertia survives"
        );
        assert!(airborne_velocity([f32::NAN, 0., 0.], 1., 0.01).is_none());
        assert!(airborne_velocity([0.1, 0., 0.], 1., 0.0).is_none());
    }
    #[test]
    fn walk_sprint_and_sneak_converge_to_mc_default_surface_speeds() {
        for (sprint, sneak, expected) in [
            (false, false, 4.31718),
            (true, false, 5.612335),
            (false, true, 1.295154),
        ] {
            let mut model = HorizontalModel::default();
            let mut step = [0.0; 3];
            for _ in 0..100 {
                step = model
                    .advance(
                        TICK,
                        Input {
                            forward: 1.0,
                            sprint,
                            sneak,
                            ..Input::default()
                        },
                        FORWARD,
                        RIGHT,
                        true,
                    )
                    .unwrap();
            }
            assert!((step[2] / TICK - expected).abs() < 0.001);
        }
    }
    #[test]
    fn torrent_gallops_and_dashes_in_any_direction_ignoring_crouch_and_guard() {
        let converge = |input: Input| {
            let mut model = HorizontalModel::default();
            let mut step = [0.0; 3];
            for _ in 0..100 {
                step = model
                    .travel(
                        TICK,
                        Gait::Torrent.input(input),
                        FORWARD,
                        RIGHT,
                        true,
                        Gait::Torrent,
                    )
                    .unwrap();
            }
            step[0].hypot(step[2]) / TICK
        };
        let gallop = converge(Input {
            forward: 1.0,
            ..Input::default()
        });
        assert!((gallop - 10.79295).abs() < 0.001, "gallop={gallop}");
        let dash = converge(Input {
            right: -1.0,
            sprint: true,
            ..Input::default()
        });
        assert!((dash - 16.18943).abs() < 0.001, "dash={dash}");
        let burdened = converge(Input {
            forward: 1.0,
            sneak: true,
            using_item: true,
            ..Input::default()
        });
        assert!((burdened - gallop).abs() < 0.0001);
        // Standing sprint is not a dash; on foot it still needs forward impulse.
        assert!(!Gait::Torrent.sprinting(Input {
            sprint: true,
            ..Input::default()
        }));
        assert!(!Gait::Foot.sprinting(Input {
            right: 1.0,
            sprint: true,
            ..Input::default()
        }));
    }
    #[test]
    fn a_full_dash_fits_a_slow_native_step_only_while_mounted() {
        let input = Input {
            forward: 1.0,
            sprint: true,
            ..Input::default()
        };
        let mut mounted = HorizontalModel::default();
        for _ in 0..100 {
            mounted
                .travel(0.1, input, FORWARD, RIGHT, true, Gait::Torrent)
                .unwrap();
        }
        let mut foot = HorizontalModel {
            velocity: [0.0, 0.8],
            ..HorizontalModel::default()
        };
        assert!(
            foot.travel(0.1, input, FORWARD, RIGHT, true, Gait::Foot)
                .is_err()
        );
    }
    #[test]
    fn torrent_jumps_higher_and_spends_exactly_one_air_jump_per_flight() {
        let mut model = VerticalModel::default();
        let mut y = 0.0f32;
        let mut highest = 0.0f32;
        model.step(TICK, false, true, y, Gait::Torrent).unwrap();
        for tick in 0..12 {
            let velocity = model
                .step(TICK, tick == 0, tick == 0, y, Gait::Torrent)
                .unwrap();
            y += velocity * TICK;
            highest = highest.max(y);
        }
        assert!((2.2..2.5).contains(&highest), "apex={highest}");
        assert_eq!((model.jumps, model.air_jumps), (1, 0));
        // A fresh press in the air restarts the rise once; the next press does not.
        let second = model.step(TICK, true, false, y, Gait::Torrent).unwrap();
        assert!(second > 11.9, "air jump={second}");
        assert_eq!((model.jumps, model.air_jumps), (2, 1));
        model
            .step(TICK, false, false, y + 0.6, Gait::Torrent)
            .unwrap();
        let third = model
            .step(TICK, true, false, y + 1.0, Gait::Torrent)
            .unwrap();
        assert!(third < second);
        assert_eq!(model.air_jumps, 1);
        // Landing restores the air jump.
        for _ in 0..40 {
            if !model.flying {
                break;
            }
            model.step(TICK, false, true, 0.0, Gait::Torrent).unwrap();
        }
        assert!(!model.flying);
        assert_eq!(model.air_jumps, 0);
    }
    #[test]
    fn on_foot_a_press_in_the_air_never_jumps_again() {
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, true, true, 0.0).unwrap();
        model.advance(TICK, true, false, 0.4).unwrap();
        model.advance(TICK, false, false, 0.7).unwrap();
        model.advance(TICK, false, false, 0.9).unwrap();
        let pressed = model.advance(TICK, true, false, 1.0).unwrap();
        assert!(pressed < 8.0);
        assert_eq!((model.jumps, model.air_jumps), (1, 0));
    }
    #[test]
    fn torrent_air_jump_redirects_momentum_without_changing_speed() {
        let mut model = HorizontalModel {
            velocity: [0.0, 0.6],
            moving: [0.0, 0.6],
            ..HorizontalModel::default()
        };
        model.redirect([1.0, 0.0, 0.0]);
        assert!((model.velocity[0] - 0.6).abs() < 0.0001 && model.velocity[1].abs() < 0.0001);
        model.redirect([0.0; 3]);
        assert!((model.velocity[0] - 0.6).abs() < 0.0001);
    }
    #[test]
    fn mounted_vertical_submission_admits_torrent_takeoff_only() {
        let context = |y| {
            Some(VelocityContext {
                proxies: [1, 2],
                y,
                flight: None,
                calls: 0,
            })
        };
        let input = NativeVelocity([0.0; 4]);
        assert!(vertical_replacement(context(crate::torrent::JUMP_MPS), 1, input).is_some());
        assert!(vertical_replacement(context(crate::torrent::JUMP_MPS + 0.1), 1, input).is_none());
    }
    #[test]
    fn fixed_tick_model_does_not_change_with_render_frame_rate() {
        let input = Input {
            forward: 1.0,
            ..Input::default()
        };
        let mut a = HorizontalModel::default();
        let mut b = HorizontalModel::default();
        let mut da = 0.0;
        let mut db = 0.0;
        for _ in 0..20 {
            da += a.advance(0.05, input, FORWARD, RIGHT, true).unwrap()[2];
        }
        for _ in 0..60 {
            db += b.advance(1.0 / 60.0, input, FORWARD, RIGHT, true).unwrap()[2];
        }
        assert!((da - db).abs() < 0.0001, "{da} {db}");
    }
    #[test]
    fn new_input_and_sprint_affect_the_next_native_slice_without_waiting_for_tick() {
        let mut model = HorizontalModel::default();
        assert_eq!(
            model
                .advance(0.01, Input::default(), FORWARD, RIGHT, true)
                .unwrap(),
            [0.0; 3]
        );
        let walk = model
            .advance(
                0.01,
                Input {
                    forward: 1.0,
                    ..Input::default()
                },
                FORWARD,
                RIGHT,
                true,
            )
            .unwrap();
        assert!(walk[2] > 0.0);
        let sprint = model
            .advance(
                0.01,
                Input {
                    forward: 1.0,
                    sprint: true,
                    ..Input::default()
                },
                FORWARD,
                RIGHT,
                true,
            )
            .unwrap();
        assert!((sprint[2] / walk[2] - 1.3).abs() < 0.0001);
        let strafe = model
            .advance(
                0.01,
                Input {
                    right: 1.0,
                    ..Input::default()
                },
                FORWARD,
                RIGHT,
                true,
            )
            .unwrap();
        assert!(strafe[0] > 0.0);
        assert!(strafe[2].abs() < 0.00001);
    }
    #[test]
    fn fresh_grounded_jump_starts_immediately_and_held_input_never_replays_after_reset() {
        let mut model = VerticalModel::default();
        assert_eq!(model.advance(1.0 / 60.0, false, true, 10.0).unwrap(), 0.0);
        assert!((model.advance(1.0 / 60.0, true, true, 10.0).unwrap() - 8.4).abs() < 0.0001);
        assert_eq!(model.jumps, 1);
        model = VerticalModel::default();
        assert_eq!(model.advance(1.0 / 60.0, true, true, 10.0).unwrap(), 0.0);
        assert_eq!(model.jumps, 0);
        model.advance(1.0 / 60.0, false, true, 10.0).unwrap();
        assert!(model.advance(1.0 / 60.0, true, true, 10.0).unwrap() > 8.3);
    }
    #[test]
    fn ordinary_jump_model_has_minecraft_apex_and_gravity_without_release_cancellation() {
        let mut model = VerticalModel::default();
        let mut y = 0.0f32;
        let mut highest = 0.0f32;
        model.advance(TICK, false, true, y).unwrap();
        for tick in 0..30 {
            let velocity = model.advance(TICK, tick == 0, tick == 0, y).unwrap();
            y += velocity * TICK;
            highest = highest.max(y);
        }
        assert!((highest - 1.2522).abs() < 0.01, "apex={highest}");
        assert!(y < 0.0);
        assert_eq!(model.jumps, 1);
    }
    #[test]
    fn ceiling_collision_stops_lift_and_landing_requires_a_new_space_edge() {
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, true, true, 0.0).unwrap();
        model.advance(TICK, true, false, 0.1).unwrap();
        let blocked = model.advance(TICK, true, false, 0.1).unwrap();
        assert!(blocked < 0.0);
        assert_eq!(model.advance(TICK, true, true, 0.0).unwrap(), 0.0);
        assert!(!model.flying);
        assert_eq!(model.advance(TICK, true, true, 0.0).unwrap(), 0.0);
        assert_eq!(model.jumps, 1);
        model.advance(TICK, false, true, 0.0).unwrap();
        assert!(model.advance(TICK, true, true, 0.0).unwrap() > 8.3);
        assert_eq!(model.jumps, 2);
    }
    #[test]
    fn airborne_reauthorization_does_not_reset_an_existing_native_fall() {
        let mut model = VerticalModel::default();
        assert_eq!(model.advance(TICK, true, false, 20.0).unwrap(), 0.0);
        assert!(!model.flying);
        assert_eq!(model.advance(TICK, false, false, 19.5).unwrap(), 0.0);
        assert!(!model.flying);
        model.advance(TICK, false, true, 0.0).unwrap();
        assert!(model.advance(TICK, false, false, -0.01).unwrap() < 0.0);
        assert!(model.flying);
    }
    #[test]
    fn rejected_takeoff_is_bounded_and_does_not_claim_ceiling_without_rise() {
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 0.0).unwrap();
        model.advance(TICK, true, true, 0.0).unwrap();
        for _ in 0..4 {
            model.advance(TICK, true, true, 0.0).unwrap();
        }
        assert!(!model.flying);
        assert_eq!(model.jumps, 1);
        let observation = model.observation.unwrap();
        assert!(!observation.observed_takeoff);
        assert_eq!(observation.ended, Some("blocked before takeoff"));
        assert!(!observation.ceiling);
        model.advance(TICK, false, true, 0.0).unwrap();
        assert!(model.advance(TICK, true, true, 0.0).unwrap() > 8.3);
    }
    #[test]
    fn ground_adhesion_permission_requires_matching_fresh_upward_owned_flight() {
        let permit = Permit {
            identity: Identity {
                player: 1,
                physics: 2,
                map: 3,
            },
            input: Input::default(),
            forward: FORWARD,
            right: RIGHT,
            issued: 1000,
        };
        assert!(owns_upward_step(Some(permit), 2, 1000, true, 8.4));
        assert!(owns_upward_step(Some(permit), 2, 1100, true, 0.1));
        for (physics, time, flying, velocity) in [
            (9, 1000, true, 8.4),
            (2, 999, true, 8.4),
            (2, 1101, true, 8.4),
            (2, 1000, false, 8.4),
            (2, 1000, true, 0.0),
            (2, 1000, true, -0.1),
            (2, 1000, true, 120.1),
            (2, 1000, true, f32::NAN),
        ] {
            assert!(!owns_upward_step(
                Some(permit),
                physics,
                time,
                flying,
                velocity
            ));
        }
        assert!(!owns_upward_step(None, 2, 1000, true, 8.4));
    }
    #[test]
    fn adhesion_branch_changes_only_carry_and_preserves_all_other_cpu_flags() {
        for flags in [
            0,
            0x202,
            0x246,
            0xffff_ffff_ffff_fffe,
            0xffff_ffff_ffff_ffff,
        ] {
            let output = branch_without_adhesion(flags);
            assert_eq!(output & !1, flags & !1);
            assert_ne!(output & 1, 0);
        }
    }
    #[test]
    fn relocated_short_jbe_obeys_saved_carry_and_unhooks_cleanly() {
        // A private executable fixture, not game code. Reproduce the short JBE
        // shape with its destination outside the overwritten instructions. This
        // exercises ilhook relocation and CPU-flags restoration on this host.
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn VirtualAlloc(
                address: *mut c_void,
                size: usize,
                allocation: u32,
                protect: u32,
            ) -> *mut c_void;
            fn VirtualFree(address: *mut c_void, size: usize, kind: u32) -> i32;
            fn FlushInstructionCache(
                process: *mut c_void,
                address: *const c_void,
                size: usize,
            ) -> i32;
        }
        struct Fixture(*mut c_void);
        impl Drop for Fixture {
            fn drop(&mut self) {
                unsafe {
                    VirtualFree(self.0, 0, 0x8000);
                }
            }
        }
        let fixture = Fixture(unsafe { VirtualAlloc(std::ptr::null_mut(), 4096, 0x3000, 0x40) });
        assert!(!fixture.0.is_null());
        let mut code = [0x90u8; 64];
        code[..11].copy_from_slice(&[0xb8, 1, 0, 0, 0, 0x83, 0xf9, 0, 0x76, 0x26, 0xc3]);
        code[48..54].copy_from_slice(&[0xb8, 2, 0, 0, 0, 0xc3]);
        unsafe {
            std::ptr::copy_nonoverlapping(code.as_ptr(), fixture.0.cast::<u8>(), code.len());
            assert_ne!(
                FlushInstructionCache(-1isize as *mut c_void, fixture.0, code.len()),
                0
            );
        }
        let function: unsafe extern "system" fn(u32) -> u32 =
            unsafe { std::mem::transmute(fixture.0) };
        assert_eq!(unsafe { function(1) }, 1);
        assert_eq!(unsafe { function(0) }, 2);
        let enabled = Arc::new(AtomicBool::new(false));
        let armed = enabled.clone();
        let hook = unsafe {
            hook_closure_jmp_back(
                fixture.0 as usize + 8,
                move |registers| {
                    if armed.load(Ordering::Acquire) {
                        (*registers).rflags = branch_without_adhesion((*registers).rflags);
                    }
                },
                ilhook::x64::CallbackOption::None,
                HookFlags::empty(),
            )
        }
        .unwrap();
        assert_eq!(unsafe { function(1) }, 1);
        assert_eq!(unsafe { function(0) }, 2);
        enabled.store(true, Ordering::Release);
        assert_eq!(unsafe { function(1) }, 2);
        drop(hook);
        assert_eq!(unsafe { function(1) }, 1);
    }
    #[test]
    fn jump_enters_root_motion_before_native_state_selection_even_when_stationary() {
        for desired in [[0.0, 0.0, 0.0], [0.06, 0.0, 0.03]] {
            for q in [
                [0.0, 0.0, 0.0, 1.0],
                [0.25f32.sin(), 0.0, 0.0, 0.25f32.cos()],
            ] {
                let local =
                    replace_world_translation(q, [0.0; 4], desired, Some(8.4 / 60.0)).unwrap();
                let world =
                    inverse_rotate([-q[0], -q[1], -q[2], q[3]], [local[0], local[1], local[2]])
                        .unwrap();
                assert!((world[0] - desired[0]).abs() < 0.00001);
                assert!((world[1] - 0.14).abs() < 0.00001);
                assert!((world[2] - desired[2]).abs() < 0.00001);
                // Nonzero pre-gravity displacement is visible to native466820,
                // unlike a proxy-only late velocity change.
                assert!(world.iter().map(|value| value * value).sum::<f32>() > 0.019);
            }
        }
    }
    #[test]
    fn completed_jump_evidence_records_actual_rise_not_submission_count() {
        let mut model = VerticalModel::default();
        model.advance(TICK, false, true, 10.0).unwrap();
        model.advance(TICK, true, true, 10.0).unwrap();
        model.advance(TICK, false, false, 10.35).unwrap();
        model.advance(TICK, false, false, 10.35).unwrap();
        model.advance(TICK, false, true, 10.0).unwrap();
        let observation = model.observation.unwrap();
        assert!(observation.observed_takeoff);
        assert!((observation.max_rise_m - 0.35).abs() < 0.0001);
        assert!(observation.duration_s >= 0.1);
        assert_eq!(observation.ended, Some("landed"));
    }
    #[test]
    fn vertical_model_is_frame_partition_independent_in_free_air() {
        fn trace(dt: f32, n: usize) -> f32 {
            let mut model = VerticalModel::default();
            let mut y = 0.0;
            model.advance(dt, false, true, y).unwrap();
            for tick in 0..n {
                y += model.advance(dt, tick == 0, tick == 0, y).unwrap() * dt;
            }
            y
        }
        assert!((trace(0.05, 10) - trace(1.0 / 60.0, 30)).abs() < 0.001);
    }
    #[test]
    fn velocity_scope_only_changes_matching_local_proxy_y_and_releases_on_drop() {
        let input = NativeVelocity([1.0, -3.0, 2.0, 0.0]);
        let context = VelocityContext {
            proxies: [123, 456],
            y: 8.4,
            flight: None,
            calls: 0,
        };
        assert!(vertical_replacement(Some(context), 789, input).is_none());
        assert_eq!(
            vertical_replacement(Some(context), 123, input).unwrap().0,
            [1.0, 8.4, 2.0, 0.0]
        );
        assert!(vertical_replacement(None, 123, input).is_none());
        {
            let _scope = VelocityScope::enter(Some(context));
            assert!(VELOCITY_CONTEXT.with(Cell::get).is_some());
            {
                let _nested = VelocityScope::enter(None);
                assert!(VELOCITY_CONTEXT.with(Cell::get).is_none());
            }
            assert!(VELOCITY_CONTEXT.with(Cell::get).is_some());
        }
        assert!(VELOCITY_CONTEXT.with(Cell::get).is_none());
    }
    #[test]
    fn travel_meter_averages_native_path_and_rejects_coordinate_jumps() {
        let mut meter = TravelMeter::default();
        for frame in 0..31 {
            meter.sample([frame as f32 / 60.0 * 4.0, 0.0, 0.0], 1.0 / 60.0);
        }
        assert!((meter.speed - 4.0).abs() < 0.0001);
        assert_eq!(meter.sample([1000.0, 0.0, 0.0], 1.0 / 60.0), 0.0);
    }
    #[test]
    fn release_decelerates_and_suspend_reset_clears_momentum() {
        let mut model = HorizontalModel::default();
        for _ in 0..20 {
            model
                .advance(
                    TICK,
                    Input {
                        forward: 1.0,
                        ..Input::default()
                    },
                    FORWARD,
                    RIGHT,
                    true,
                )
                .unwrap();
        }
        let first = model
            .advance(TICK, Input::default(), FORWARD, RIGHT, true)
            .unwrap()[2];
        for _ in 0..20 {
            model
                .advance(TICK, Input::default(), FORWARD, RIGHT, true)
                .unwrap();
        }
        assert!(first > 0.0);
        assert_eq!(model.moving, [0.0; 2]);
        model.reset();
        assert_eq!(
            model
                .advance(TICK, Input::default(), FORWARD, RIGHT, true)
                .unwrap(),
            [0.0; 3]
        );
    }
    #[test]
    fn invalid_input_and_stalled_dt_fail_closed() {
        let mut model = HorizontalModel::default();
        assert!(
            model
                .advance(0.101, Input::default(), FORWARD, RIGHT, true)
                .is_err()
        );
        assert!(
            model
                .advance(
                    TICK,
                    Input {
                        forward: f32::NAN,
                        ..Input::default()
                    },
                    FORWARD,
                    RIGHT,
                    true
                )
                .is_err()
        );
        assert!(world_input(Input::default(), FORWARD, FORWARD).is_err());
    }
    #[test]
    fn inverse_orientation_keeps_world_direction_independent_of_body_yaw() {
        let h = std::f32::consts::FRAC_1_SQRT_2;
        let local = inverse_rotate([0.0, h, 0.0, h], [0.0, 0.0, 1.0]).unwrap();
        assert!((local[0] + 1.0).abs() < 0.00001);
        assert!(local[2].abs() < 0.00001);
    }
    #[test]
    fn tilted_orientation_preserves_original_world_vertical_motion() {
        let q = [0.25f32.sin(), 0.0, 0.0, 0.25f32.cos()];
        let original = [0.03, 0.07, -0.02, 0.0];
        let before = inverse_rotate(
            [-q[0], -q[1], -q[2], q[3]],
            [original[0], original[1], original[2]],
        )
        .unwrap();
        let local = replace_world_horizontal(q, original, [0.1, 0.0, -0.08]).unwrap();
        let after =
            inverse_rotate([-q[0], -q[1], -q[2], q[3]], [local[0], local[1], local[2]]).unwrap();
        assert!((after[0] - 0.1).abs() < 0.00001);
        assert!((after[1] - before[1]).abs() < 0.00001);
        assert!((after[2] + 0.08).abs() < 0.00001);
        assert_eq!(local[3], original[3]);
    }
    #[test]
    fn contended_suspend_revokes_permission_and_requires_momentum_reset() {
        let shared = Arc::new(Mutex::new(Shared::new(1.15, None)));
        let mut driver = Driver {
            shared: shared.clone(),
            faulted: Arc::new(AtomicBool::new(false)),
            enabled: Arc::new(AtomicBool::new(true)),
            reset_required: false,
            flight_seen: 0,
            flight_revoked: 0,
            last_status: Cell::default(),
        };
        let locked = shared.lock().unwrap();
        driver.suspend();
        assert!(!driver.enabled.load(Ordering::Acquire));
        assert!(driver.reset_required);
        drop(locked);
    }
    #[test]
    fn expired_permit_falls_through_before_any_sdk_or_argument_dereference() {
        let identity = Identity {
            player: usize::MAX,
            physics: usize::MAX,
            map: -1,
        };
        let shared = Mutex::new(Shared {
            permit: Some(Permit {
                identity,
                input: Input::default(),
                forward: FORWARD,
                right: RIGHT,
                issued: now().saturating_sub(PERMIT_MS + 1),
            }),
            ..Shared::new(1.15, None)
        });
        assert!(
            unsafe {
                prepare(
                    &shared,
                    usize::MAX as *mut _,
                    std::ptr::null(),
                    std::ptr::null(),
                )
            }
            .is_none()
        );
        assert!(shared.lock().unwrap().permit.is_none());
    }
    #[test]
    fn native_orientation_output_requires_aligned_owned_pointer_and_valid_quaternion() {
        unsafe extern "system" fn valid(
            _: *const CSChrPhysicsModule,
            out: *mut NativeOrientation,
        ) -> *mut NativeOrientation {
            assert_eq!(out as usize & 15, 0);
            unsafe { (*out).0 = [0.0, 0.0, 0.0, 1.0] };
            out
        }
        unsafe extern "system" fn foreign(
            _: *const CSChrPhysicsModule,
            _: *mut NativeOrientation,
        ) -> *mut NativeOrientation {
            std::ptr::null_mut()
        }
        unsafe extern "system" fn invalid(
            _: *const CSChrPhysicsModule,
            out: *mut NativeOrientation,
        ) -> *mut NativeOrientation {
            out
        }
        assert_eq!(
            unsafe { effective_orientation(std::ptr::null(), valid) }.unwrap(),
            [0.0, 0.0, 0.0, 1.0]
        );
        assert!(unsafe { effective_orientation(std::ptr::null(), foreign) }.is_err());
        assert!(unsafe { effective_orientation(std::ptr::null(), invalid) }.is_err());
    }
}
