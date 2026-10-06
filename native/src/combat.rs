//! Split-phase control of native ER combat from real Minecraft combat metadata.
//! PostPhysics authorizes 100 ms of activity; GameFlowStep applies pad decisions.
//! Root must suspend its ordinary input_capture before authorizing this driver,
//! and suspend this driver before capturing a guest GUI. No concurrent pad writer.
use crate::{combat_pad, combat_state};
use eldenring::cs::{
    CSMenuManImp, CSSessionManager, ChrAsmArmStyle, ChrAsmHand, FieldInsHandle, FieldInsType,
    GameMan, LobbyState, PlayerIns, ProtocolState, WorldChrMan,
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
fn foreground() -> bool {
    let mut pid = 0;
    unsafe {
        GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
        pid == GetCurrentProcessId()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Identity {
    pub(crate) player: usize,
    pub(crate) map: i32,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Target health/range are part of the formatted observation.
pub struct Target {
    pub handle: FieldInsHandle,
    pub hp: i32,
    pub max_hp: i32,
    pub last_hit_by: FieldInsHandle,
    pub distance_m: f32,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Full native action/equipment readback is logged with Debug.
pub struct Observation {
    pub action_bits: u64,
    pub new_press_bits: u64,
    pub queued_bits: u64,
    pub player: FieldInsHandle,
    pub hp: i32,
    pub stamina: i32,
    pub right_weapon_param: i32,
    pub left_weapon_param: i32,
    pub arm_style: ChrAsmArmStyle,
    pub attack_readback: bool,
    pub guard_requested: bool,
    pub guard_animation_flag: bool,
    pub attack_pad: Option<combat_pad::PollObservation>,
    pub guard_pad: Option<combat_pad::PollObservation>,
    pub target: Option<Target>,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Logs retain the attribution limits alongside the HP observation.
pub struct HitObservation {
    pub swing: u64,
    pub target: FieldInsHandle,
    pub hp_lost: i32,
    /// SDK last_hit_by matches the local player; ongoing player DoT can also match.
    pub attributed_to_player: bool,
    /// Always false: no source-verified unique melee hit-event ID exists here.
    pub unique_melee_hit_verified: bool,
}
#[derive(Clone, Debug, Default)]
#[allow(dead_code)] // The complete status is emitted on diagnostic transitions.
pub struct Status {
    pub active: bool,
    pub attack_requested: bool,
    pub guard_requested: bool,
    pub movement_captured: bool,
    pub movement_error: Option<&'static str>,
    pub item: String,
    pub swing: u64,
    pub blocked_codes: usize,
    pub attack_poll: Option<combat_pad::PollObservation>,
    pub guard_poll: Option<combat_pad::PollObservation>,
    pub observation: Option<Observation>,
    pub hit: Option<HitObservation>,
    pub melee: crate::minecraft_melee::Status,
    pub shield: crate::minecraft_shield::Status,
}
struct Pending {
    swing: u64,
    target: FieldInsHandle,
    player: FieldInsHandle,
    hp: i32,
    until: u64,
}
pub struct Driver {
    pad: combat_pad::Controller,
    reader: combat_state::Reader,
    policy: combat_state::Policy,
    permit: Option<(Identity, u64)>,
    status: Status,
    pending: Option<Pending>,
    last_swing: u64,
    melee: crate::minecraft_melee::Driver,
    movement_jump: Option<bool>,
    shield_forward: Option<[f32; 3]>,
}
impl Default for Driver {
    fn default() -> Self {
        Self::new()
    }
}
impl Driver {
    pub fn new() -> Self {
        Self {
            pad: combat_pad::Controller::new(),
            reader: combat_state::Reader::new(),
            policy: combat_state::Policy::default(),
            permit: None,
            status: Status::default(),
            pending: None,
            last_swing: 0,
            melee: crate::minecraft_melee::Driver::new(),
            movement_jump: None,
            shield_forward: None,
        }
    }
    pub fn status(&self) -> &Status {
        &self.status
    }
    pub fn set_debug_bounds(&mut self, enabled: bool) {
        self.melee.set_debug_bounds(enabled);
    }
    pub fn set_movement_jump(&mut self, jump: Option<bool>) {
        self.movement_jump = jump;
    }
    pub fn set_shield_forward(&mut self, forward: Option<[f32; 3]>) {
        self.shield_forward = forward;
    }
    pub fn diagnostic(&self) -> Option<&str> {
        self.pad.diagnostic()
    }
    /// Read the same raw pad predicates at a later task phase while a requested
    /// attack/guard is still active. No writes or allocation occur here.
    /// # Safety
    /// Exact-build game task with no outstanding SDK references.
    pub unsafe fn probe(&self) -> Result<Option<[combat_pad::PollObservation; 2]>, &'static str> {
        let now = unsafe { GetTickCount64() };
        let Some((identity, _)) = self.permit.filter(|(_, until)| now < *until) else {
            return Ok(None);
        };
        if !self.status.active || !(self.status.attack_requested || self.status.guard_requested) {
            return Ok(None);
        }
        if unsafe { context()? } != identity {
            return Err("combat probe player or region changed");
        }
        Ok(Some([
            unsafe { combat_pad::observe_poll(eldenring::cs::UserInputKey::Attack)? },
            unsafe { combat_pad::observe_poll(eldenring::cs::UserInputKey::Guard)? },
        ]))
    }
    /// # Safety
    /// PostPhysics after exact-build and full local-player activity checks.
    /// `enabled` also requires fresh actual compositor output and no guest GUI.
    /// No outstanding SDK references or concurrent pad writers.
    pub unsafe fn authorize(&mut self, enabled: bool) -> Result<(), &'static str> {
        self.permit = None;
        if !enabled {
            return unsafe { self.suspend() };
        }
        match unsafe { context() } {
            Ok(identity) => {
                self.permit = Some((identity, unsafe { GetTickCount64() }.saturating_add(100)));
                // Character activity/task bits are meaningful in PostPhysics,
                // not the earlier input phase. Dispatch only after that phase
                // has actually acquired raw combat suppression.
                if self.status.active {
                    self.status.melee =
                        match unsafe { self.melee.tick(identity.player, identity.map) } {
                            Ok(status) => status,
                            Err(reason) => self.melee.fail(reason),
                        };
                }
                Ok(())
            }
            Err(error) => {
                let _ = unsafe { self.suspend() };
                Err(error)
            }
        }
    }
    /// # Safety
    /// GameFlowStep after PadStep, before InGameStep; version already verified.
    /// Caller wraps this SDK callback with catch_unwind and suspends on panic.
    pub unsafe fn tick(&mut self) -> Result<Status, &'static str> {
        let result = unsafe { self.tick_inner() };
        if result.is_err() {
            let _ = unsafe { self.suspend() };
        }
        result
    }
    unsafe fn tick_inner(&mut self) -> Result<Status, &'static str> {
        let now = unsafe { GetTickCount64() };
        let Some((identity, until)) = self.permit.filter(|(_, until)| now < *until) else {
            unsafe { self.suspend()? };
            return Ok(self.status.clone());
        };
        let _ = until;
        if unsafe { context()? } != identity {
            return Err("combat player or region changed");
        }
        let snapshot = self.reader.poll(now);
        let now = unsafe { GetTickCount64() }; // Metadata can publish during the reader's CPU copy.
        let mut intent = self.policy.update(now, snapshot.as_ref());
        // Minecraft now owns melee. Physical and synthetic R1 remain suppressed,
        // including during missing receipts; never fall back to native lunges.
        intent.attack = false;
        let guard_requested = intent.guard;
        crate::minecraft_shield::authorize(
            identity,
            self.shield_forward,
            now,
            intent.active && guard_requested,
        );
        // A raised Minecraft shield applies a fixed reduction before HP loss.
        // Do not also apply the currently equipped ER weapon's block absorption.
        if crate::minecraft_shield::available() {
            intent.guard = false;
        }
        let applied = unsafe { self.pad.update_with_movement(intent, self.movement_jump)? };
        let melee = self.status.melee.clone();
        let observation = if applied.active {
            Some(unsafe { observe()? })
        } else {
            None
        };
        let swing = self.policy.accepted_swing();
        let mut hit = None;
        // Blocking intent remains active across metadata loss, but a policy
        // session reset must discard evidence attached to the previous session.
        if swing == 0 {
            self.pending = None;
            self.last_swing = 0;
        }
        if swing != 0 && swing != self.last_swing && applied.attack {
            self.pending = observation.and_then(|o| {
                o.target.map(|t| Pending {
                    swing,
                    target: t.handle,
                    player: o.player,
                    hp: t.hp,
                    until: now.saturating_add(1500),
                })
            });
        }
        self.last_swing = swing;
        if let Some(pending) = self.pending.as_mut() {
            if now >= pending.until || !applied.active {
                self.pending = None;
            } else if let Some(target) = observation.and_then(|o| o.target) {
                if target.handle != pending.target {
                    self.pending = None;
                } else if target.hp < pending.hp {
                    if target.last_hit_by == pending.player {
                        hit = Some(HitObservation {
                            swing: pending.swing,
                            target: pending.target,
                            hp_lost: pending.hp - target.hp,
                            attributed_to_player: true,
                            unique_melee_hit_verified: false,
                        });
                    }
                    self.pending = None; // At most one candidate observation per emitted swing.
                }
            }
        }
        if !applied.active {
            self.pending = None;
            self.last_swing = 0;
        }
        let guard_requested = if crate::minecraft_shield::available() {
            applied.active && guard_requested
        } else {
            applied.guard
        };
        self.status = Status {
            active: applied.active,
            attack_requested: applied.attack,
            guard_requested,
            movement_captured: applied.movement_captured,
            movement_error: applied.movement_error,
            item: snapshot.map_or_else(String::new, |s| s.item),
            swing,
            blocked_codes: applied.blocked_codes,
            attack_poll: applied.attack_poll,
            guard_poll: applied.guard_poll,
            observation,
            hit,
            melee,
            shield: crate::minecraft_shield::status(),
        };
        Ok(self.status.clone())
    }
    /// # Safety
    /// Same gated game task and no-SDK-reference contract. Retry an error from a
    /// busy input device; the pad controller keeps ownership until restored.
    pub unsafe fn suspend(&mut self) -> Result<(), &'static str> {
        self.permit = None;
        self.policy.reset();
        self.pending = None;
        self.last_swing = 0;
        self.movement_jump = None;
        self.shield_forward = None;
        crate::minecraft_shield::revoke();
        self.melee.suspend();
        self.status = Status::default();
        unsafe { self.pad.suspend() }
    }
}

pub(crate) unsafe fn context() -> Result<Identity, &'static str> {
    if !foreground() {
        return Err("combat game is not foreground");
    }
    let menu =
        unsafe { CSMenuManImp::instance() }.map_err(|_| "combat menu manager unavailable")?;
    if !unsafe { menu.system_announce_view_model.view.as_ref() }.is_active {
        return Err("combat blocking host menu open");
    }
    unsafe { world_context() }
}

/// The offline-world, session and live-player checks of `context`, without its
/// focus and menu checks. Only for resolving a hit already covered by a fresh
/// raised-shield permit issued under the full `context`.
pub(crate) unsafe fn world_context() -> Result<Identity, &'static str> {
    let game = unsafe { GameMan::instance() }.map_err(|_| "combat GameMan unavailable")?;
    if game.is_in_online_mode || game.warp_requested {
        return Err("combat requires offline stable world");
    }
    let session = unsafe { CSSessionManager::instance() }
        .map_err(|_| "combat session manager unavailable")?;
    if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None {
        return Err("combat multiplayer session rejected");
    }
    let player =
        unsafe { PlayerIns::local_player() }.map_err(|_| "combat local player unavailable")?;
    if player.chr_ins.modules.data.hp <= 0 || player.chr_ins.chr_flags1c5.death_flag() {
        return Err("combat player is not alive");
    }
    if player.current_block_id.0 == -1 {
        return Err("combat player region unavailable");
    }
    Ok(Identity {
        player: player as *const _ as usize,
        map: player.current_block_id.0,
    })
}

/// Read-only evidence from the same exact SDK. Weapon IDs are observations,
/// not a guessed equipment whitelist; verify a melee weapon and shield in-game.
/// Target HP/last_hit_by corroborate combat but cannot uniquely identify a swing.
/// # Safety
/// Validated offline game task; no outstanding mutable game references.
pub unsafe fn observe() -> Result<Observation, &'static str> {
    let player =
        unsafe { PlayerIns::local_player() }.map_err(|_| "combat telemetry player unavailable")?;
    let asm = &player.chr_asm;
    if asm.equipment.selected_slots.left_weapon_slot > 2
        || asm.equipment.selected_slots.right_weapon_slot > 2
    {
        return Err("combat equipment slot state rejected");
    }
    let right_weapon_param =
        asm.equipment_param_ids[asm.equipment.active_weapon_slot(ChrAsmHand::Right)];
    let left_weapon_param =
        asm.equipment_param_ids[asm.equipment.active_weapon_slot(ChrAsmHand::Left)];
    let own = player.chr_ins.field_ins_handle;
    let handle = player.locked_on_enemy;
    let target = if !handle.is_empty()
        && handle != own
        && handle.selector.field_ins_type() == Some(FieldInsType::Chr)
    {
        let manager =
            unsafe { WorldChrMan::instance() }.map_err(|_| "combat target manager unavailable")?;
        manager.chr_ins_by_handle(&handle).and_then(|target| {
            if target.field_ins_handle != handle
                || target.modules.data.max_hp <= 0
                || target.modules.data.hp < 0
            {
                return None;
            }
            let a = player.chr_ins.modules.physics.position;
            let b = target.modules.physics.position;
            let distance = ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2) + (a.2 - b.2).powi(2)).sqrt();
            if !distance.is_finite() || distance > 30.0 {
                return None;
            }
            Some(Target {
                handle,
                hp: target.modules.data.hp,
                max_hp: target.modules.data.max_hp,
                last_hit_by: target.last_hit_by,
                distance_m: distance,
            })
        })
    } else {
        None
    };
    Ok(Observation {
        action_bits: player.chr_ins.modules.action_request.action_requests.0,
        new_press_bits: player.chr_ins.modules.action_request.new_action_presses.0,
        queued_bits: player.chr_ins.modules.action_request.queued_action_inputs.0,
        player: own,
        hp: player.chr_ins.modules.data.hp,
        stamina: player.chr_ins.modules.data.stamina,
        right_weapon_param,
        left_weapon_param,
        arm_style: asm.equipment.arm_style,
        attack_readback: player
            .chr_ins
            .modules
            .action_request
            .readback_new_presses
            .r1(),
        guard_requested: player
            .chr_ins
            .modules
            .action_request
            .action_requests
            .guard(),
        guard_animation_flag: player
            .chr_ins
            .modules
            .action_flag
            .action_modifiers_flags
            .guard_type_set(),
        attack_pad: unsafe { combat_pad::observe_poll(eldenring::cs::UserInputKey::Attack) }.ok(),
        guard_pad: unsafe { combat_pad::observe_poll(eldenring::cs::UserInputKey::Guard) }.ok(),
        target,
    })
}
