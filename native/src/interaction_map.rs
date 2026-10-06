//! Grace travel through the game's own Lua warp handler. Admission is limited
//! to an already established native rest loop: map browsing never grants field
//! warping, dungeon escape, DLC access, or undiscovered destinations.
use eldenring::cs::{
    BonfireWarpParam, CSEventFlagMan, CSLuaEventManImp, CSLuaEventProxy, CSLuaEventScriptImitation,
    CSSessionManager, GameMan, LadderState, LobbyState, PlayerIns, ProtocolState,
    SoloParamRepository,
};
use fromsoftware_shared::{FromStatic, program::Program};
use pelite::pe64::PeObject;
use std::ffi::c_void;

// Unique TGA LuaWarp01 signature, verified against the pinned executable's
// disassembly. The function writes lua_warp_bonfire_entity_id and executes the
// normal Lua transition. Its callers supply the target entity minus 1000.
// It has no travel admission checks, so all checks below precede every call.
const LUA_WARP: usize = 0x59aa60;
const WARP_CODE: &[u8] = &[
    0x48, 0x89, 0x5c, 0x24, 0x10, 0x57, 0x48, 0x83, 0xec, 0x20, 0x48, 0x8b, 0xfa, 0x44, 0x89, 0x41,
    0x1c, 0x48, 0x8b, 0xd9, 0xc7, 0x44, 0x24, 0x30, 0x10, 0x27, 0x00, 0x00, 0x48, 0x8b, 0xcf, 0x48,
    0x8d, 0x54, 0x24, 0x30, 0x41, 0xb8, 0x64, 0xeb, 0x00, 0x00, 0xe8, 0x31, 0x80, 0xff, 0xff, 0x8b,
    0x43, 0x1c, 0x48, 0x8d, 0x54, 0x24, 0x30, 0x33, 0xc9, 0x83, 0xf8, 0xff, 0x0f, 0x44, 0xc1, 0x48,
    0x8b, 0xcf, 0x89, 0x44, 0x24, 0x30, 0xe8, 0x15, 0xb5, 0xff, 0xff, 0x48, 0x8b, 0xcf, 0xe8, 0xfd,
    0xa7, 0xff, 0xff,
];
type Warp = unsafe extern "system" fn(*mut CSLuaEventScriptImitation, *mut CSLuaEventProxy, i32);

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcessId() -> u32;
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
fn verified() -> bool {
    Program::current()
        .image()
        .get(LUA_WARP..LUA_WARP + WARP_CODE.len())
        == Some(WARP_CODE)
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct Context {
    player: usize,
    block: i32,
    grace: u32,
}
fn destination_argument(entity: u32) -> Option<i32> {
    entity.checked_sub(1000).and_then(|v| i32::try_from(v).ok())
}

unsafe fn admit() -> Result<Context, &'static str> {
    if !foreground() || !verified() {
        return Err("Grace travel waiting for verified foreground gameplay");
    }
    let game = unsafe { GameMan::instance() }.map_err(|_| "Grace travel waiting for game")?;
    let session = unsafe { CSSessionManager::instance() }
        .map_err(|_| "Grace travel waiting for offline session")?;
    if game.is_in_online_mode
        || game.warp_requested
        || session.lobby_state != LobbyState::None
        || session.protocol_state != ProtocolState::None
    {
        return Err("Grace travel requires a stable offline world");
    }
    let player = unsafe { PlayerIns::local_player() }
        .map_err(|_| "Grace travel waiting for local player")?;
    let chr = &player.chr_ins;
    if player.current_block_id.0 == -1
        || !chr.chr_flags1c8.is_active()
        || !chr.chr_flags1c8.update_tasks_registered()
        || chr.chr_flags1c5.death_flag()
        || chr.modules.data.hp <= 0
        || chr.modules.ride.is_mounted
        || chr.modules.ride.is_mounting
        || chr.modules.ladder.state != LadderState::None
        || !unsafe { chr.chr_set_entry.as_ref() }
            .chr_ins
            .is_some_and(|owner| std::ptr::eq(owner.as_ptr(), chr))
        || !std::ptr::eq(chr.modules.physics.owner.as_ptr(), chr)
        || !std::ptr::eq(chr.special_effect.owner.as_ptr(), chr)
        || chr.modules.physics.fade_out_gravity_disabled
    {
        return Err("Grace travel local player or owner changed");
    }
    // 4270 is the native uncleared-mini-dungeon travel prohibition. Its effect
    // and regulation are read unchanged; this UI cannot defeat that restriction.
    for (index, effect) in chr.special_effect.entries().take(513).enumerate() {
        if index == 512 {
            return Err("Grace travel effect list exceeds its validation bound");
        }
        if effect.param_id == 4270 {
            return Err("Grace travel is blocked in this dungeon");
        }
    }
    let events = unsafe { CSLuaEventManImp::instance() }
        .map_err(|_| "Grace travel waiting for Lua event manager")?;
    let proxy = &events.lua_event_proxy;
    let controls = proxy.control_flags;
    if !controls.bonfire_sitting_loop_active()
        || controls.bonfire_end_pending()
        || controls.bonfire_stand_up_in_progress()
        || proxy.is_load_wait
        || proxy.is_lobby_state_client
        || proxy.is_net_message
    {
        return Err("Rest at a Site of Grace to travel");
    }
    let script = events
        .lua_event_script_imitation
        .as_ref()
        .ok_or("Grace travel waiting for native rest script")?;
    let grace = script.bonfire_entity_id;
    if destination_argument(grace).is_none() || script.is_wait_reentry_to_map {
        return Err("Grace travel waiting for native rest context");
    }
    let params = unsafe { SoloParamRepository::instance() }
        .map_err(|_| "Grace travel waiting for grace parameters")?;
    let current = params
        .get_by_bonfire_warp_param_by_entity_id(grace)
        .ok_or("Grace travel current grace unavailable")?;
    let flags = unsafe { CSEventFlagMan::instance() }
        .map_err(|_| "Grace travel waiting for discovery flags")?;
    if current.disable_param_nt()
        || current.eventflag_id() == 0
        || !flags.virtual_memory_flag.get_flag(current.eventflag_id())
    {
        return Err("Grace travel current grace is not discovered");
    }
    Ok(Context {
        player: player as *const _ as usize,
        block: player.current_block_id.0,
        grace,
    })
}

/// Read-only capability for map buttons. Game task, after the exact executable
/// guard. A true value never authorizes a later request without rechecking it.
pub unsafe fn permitted() -> bool {
    unsafe { admit() }.is_ok()
}

/// Execute one already validated interaction command. `marker_id` is a
/// BonfireWarpParam row ID from this session's published discovered markers.
/// Caller must validate request pid/session/sequence/freshness and GUI ownership.
/// Game task only; no outstanding references into SDK singletons.
pub unsafe fn travel(marker_id: i32) -> Result<(), String> {
    let context = unsafe { admit() }.map_err(str::to_owned)?;
    let row_id = u32::try_from(marker_id).map_err(|_| "Unknown Site of Grace".to_owned())?;
    let entity = {
        let params = unsafe { SoloParamRepository::instance() }
            .map_err(|_| "Grace travel parameters unavailable".to_owned())?;
        let row = params
            .get::<BonfireWarpParam>(row_id)
            .ok_or_else(|| "Unknown Site of Grace".to_owned())?;
        let flags = unsafe { CSEventFlagMan::instance() }
            .map_err(|_| "Grace travel discovery flags unavailable".to_owned())?;
        if row.disable_param_nt()
            || row.eventflag_id() == 0
            || !flags.virtual_memory_flag.get_flag(row.eventflag_id())
        {
            return Err("This Site of Grace has not been discovered".into());
        }
        row.bonfire_entity_id()
    };
    let argument = destination_argument(entity)
        .ok_or_else(|| "Site of Grace travel destination invalid".to_owned())?;
    if unsafe { admit() }.map_err(str::to_owned)? != context {
        return Err("Grace travel context changed; reopen the map".into());
    }
    let (script, proxy) = {
        let events = unsafe { CSLuaEventManImp::instance_mut() }
            .map_err(|_| "Grace travel event manager unavailable".to_owned())?;
        let script = events
            .lua_event_script_imitation
            .as_mut()
            .ok_or_else(|| "Grace travel rest script unavailable".to_owned())?;
        if script.bonfire_entity_id != context.grace {
            return Err("Grace travel rest context changed".into());
        }
        (script.as_ptr(), events.lua_event_proxy.as_ptr())
    };
    // SDK borrows end before invoking a game function that begins a map load.
    let call: Warp =
        unsafe { std::mem::transmute(Program::current().image().as_ptr().add(LUA_WARP)) };
    unsafe { call(script, proxy, argument) };
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lua_destination_accepts_only_representable_native_entities() {
        assert_eq!(destination_argument(999), None);
        assert_eq!(destination_argument(1042361951), Some(1042360951));
        assert_eq!(destination_argument(u32::MAX), None);
    }
}
