//! Offline Elden Ring host for actual Minecraft passthrough.
mod boss_fmg;
mod boss_hud;
pub mod campaign;
#[cfg(windows)]
mod campaign_runtime;
#[cfg(windows)]
mod chat_input;
mod clock_sync;
#[cfg(windows)]
mod combat_targets;
#[cfg(windows)]
mod combat_transport;
pub mod combat_wire;
#[cfg(any(windows, test))]
mod control;
#[cfg(windows)]
mod crash;
mod glide_safety;
#[cfg(any(windows, test))]
mod grace_reset;
#[cfg(windows)]
mod healing_transport;
pub mod healing_wire;
mod host_action;
pub mod host_pose;
#[cfg(windows)]
mod hosting;
mod hurt;
#[cfg(windows)]
mod interaction_runtime;
pub mod interaction_wire;
mod ledge;
#[cfg(windows)]
mod minecraft_healing;
#[cfg(windows)]
mod minecraft_melee;
#[cfg(windows)]
mod minecraft_shield;
#[cfg(windows)]
mod mob_proxy;
#[cfg(windows)]
mod native_colliders;
#[cfg(windows)]
mod native_damage;
#[cfg(windows)]
mod native_map;
#[cfg(windows)]
mod native_teleport;
mod overlay_input;
#[cfg(windows)]
mod player_capsule;
mod player_flight;
mod pose_lock;
pub mod projectile_flight;
mod projectile_path;
#[cfg(windows)]
mod scene_camera;
mod settle;
mod step_assist;
mod surface;
mod torrent;
mod weather_sync;
#[cfg(windows)]
mod world_bridge;
mod world_fluids;
mod world_incoming;
#[cfg(windows)]
mod world_native;
#[cfg(windows)]
mod world_transport;
pub mod world_wire;
mod worldterrain;

#[cfg(windows)]
mod camera_driver;
#[cfg(windows)]
mod combat;
#[cfg(windows)]
mod combat_pad;
#[cfg(windows)]
mod combat_state;
#[cfg(windows)]
mod engine;
#[cfg(windows)]
mod first_person;
#[cfg(windows)]
mod footsteps;
#[cfg(windows)]
mod guest_status;
#[cfg(windows)]
mod host_hud;
#[cfg(windows)]
mod input_capture;
#[cfg(windows)]
mod movement_driver;
#[cfg(windows)]
mod pad_layout;

/// True when `eldencraft_native.dll` is the hot-reload loader (it exports its ABI marker).
#[cfg(windows)]
fn loader_present() -> bool {
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetModuleHandleW(name: *const u16) -> *mut core::ffi::c_void;
        fn GetProcAddress(
            module: *mut core::ffi::c_void,
            name: *const u8,
        ) -> *mut core::ffi::c_void;
    }
    let name: Vec<u16> = "eldencraft_native.dll"
        .encode_utf16()
        .chain(Some(0))
        .collect();
    unsafe {
        let module = GetModuleHandleW(name.as_ptr());
        !module.is_null()
            && !GetProcAddress(module, c"eldencraft_loader_abi".as_ptr().cast()).is_null()
    }
}

#[cfg(windows)]
#[unsafe(no_mangle)]
/// # Safety
/// Windows loader entry point; never call it directly.
pub unsafe extern "system" fn DllMain(
    module: *mut core::ffi::c_void,
    reason: u32,
    _: *mut core::ffi::c_void,
) -> i32 {
    if reason == 1 {
        let module = module as usize;
        // Under the hot-reload loader, the loader starts this core explicitly.
        if loader_present() {
            return 1;
        }
        // All file access, version checks and task initialization occur outside
        // the loader lock. No game functions are called from DllMain.
        std::thread::spawn(move || engine::start(module));
    }
    1
}
