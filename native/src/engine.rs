//! Offline Elden Ring integration for actual Minecraft rendering and gameplay.
use crate::control::Command;
use crate::{first_person, input_capture};
use eldenring::{
    cs::{
        CSCamExt, CSCamera, CSMenuManImp, CSSessionManager, CSTaskGroupIndex, CSTaskImp,
        GameDataMan, GameMan, LobbyState, PlayerIns, ProtocolState,
    },
    fd4::FD4TaskData,
};
use fromsoftware_shared::{
    FromStatic,
    game_version::{GameVersion, LANG_ID_EN},
};
use pelite::pe64::PeView;
use sha2::{Digest, Sha256};
use std::{
    ffi::c_void,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::mpsc::{self, Receiver, Sender},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
static PASSTHROUGH_DEADLINE: AtomicU64 = AtomicU64::new(0);
static GUI_DEADLINE: AtomicU64 = AtomicU64::new(0);
static LOOK_DEADLINE: AtomicU64 = AtomicU64::new(0);
static MENU_OPEN_DEADLINE: AtomicU64 = AtomicU64::new(0);
static LOOK_SAMPLES: AtomicU64 = AtomicU64::new(0);
/// Compositor thread gate: atomics only, never game SDK objects.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_passthrough_active() -> u32 {
    (unsafe { GetTickCount64() } < PASSTHROUGH_DEADLINE.load(Ordering::Acquire)) as u32
}
/// ReShade uses this bounded gate to release the host cursor for the real guest GUI.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_gui_open() -> u32 {
    let now = unsafe { GetTickCount64() };
    (eldencraft_passthrough_active() != 0
        && (now < GUI_DEADLINE.load(Ordering::Acquire)
            || now < MENU_OPEN_DEADLINE.load(Ordering::Acquire)
            || crate::chat_input::active(now))) as u32
}
/// Ordered text is emitted by ReShade's real Unicode queue, not virtual-key translation.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_chat_active() -> u32 {
    crate::chat_input::active(unsafe { GetTickCount64() }) as u32
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_chat_event(kind: u32, code: u32, modifiers: u32) -> u32 {
    if eldencraft_passthrough_active() == 0 || !foreground() {
        return 0;
    }
    let accepted = crate::chat_input::event(unsafe { GetTickCount64() }, kind, code, modifiers);
    if accepted && (kind == 1 || kind == 2) {
        crate::movement_driver::set_input_ready(false);
        LOOK_DEADLINE.store(0, Ordering::Release);
        crate::overlay_input::clear_look();
    }
    accepted as u32
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_overlay_input(buttons: u32, x: f32, y: f32, wheel: i32) {
    if eldencraft_passthrough_active() != 0 {
        crate::overlay_input::publish(unsafe { GetTickCount64() }, buttons, [x, y], wheel);
    }
}
/// Separate menu navigation keeps the established ECHS gameplay button layout.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_menu_input(buttons: u32, x: f32, y: f32, wheel: i32) {
    if eldencraft_passthrough_active() == 0 || !foreground() {
        return;
    }
    let now = unsafe { GetTickCount64() };
    let pressed = crate::overlay_input::publish_menu(now, buttons, [x, y], wheel);
    // Vanilla Pause imports Escape through ECHS independently of the interaction
    // mailbox. M opens Elden Ring's own map, which needs no Minecraft GUI.
    let pause_open = pressed & crate::overlay_input::MENU_CANCEL != 0;
    if pause_open && eldencraft_gui_open() == 0 {
        MENU_OPEN_DEADLINE.store(now + 500, Ordering::Release);
        LOOK_DEADLINE.store(0, Ordering::Release);
        crate::movement_driver::set_input_ready(false);
        crate::overlay_input::clear_look();
    }
}
/// GameFlowStep occurs once after PadStep. Read this device poll once here,
/// never once per render and never mixed with another raw mouse producer.
unsafe fn sample_direct_look() {
    let now = unsafe { GetTickCount64() };
    if now >= LOOK_DEADLINE.load(Ordering::Acquire) || eldencraft_gui_open() != 0 || !foreground() {
        crate::overlay_input::clear_look();
        return;
    }
    let delta = (|| unsafe {
        let manager = eldenring::fd4::FD4PadManager::instance().ok()?;
        let pad = manager.get_in_game_pad()?;
        let mouse = pad.pad_device.as_ref().mouse_device.as_ref();
        let _lock = mouse.mutex.try_lock()?;
        Some([
            mouse.di_mouse_state.lx as f32,
            mouse.di_mouse_state.ly as f32,
        ])
    })();
    if let Some(delta) = delta {
        if delta != [0.; 2] {
            LOOK_SAMPLES.fetch_add(1, Ordering::Relaxed);
        }
        crate::overlay_input::publish_look(now, delta);
    } else {
        crate::overlay_input::clear_look();
    }
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetModuleHandleW(name: *const u16) -> *mut c_void;
    fn GetModuleFileNameW(module: *mut c_void, buffer: *mut u16, size: u32) -> u32;
    fn GetCurrentProcessId() -> u32;
    fn GetTickCount64() -> u64;
    fn GetModuleHandleExW(flags: u32, address: *const u16, output: *mut *mut c_void) -> i32;
}
#[link(name = "user32")]
unsafe extern "system" {
    fn GetForegroundWindow() -> *mut c_void;
    fn GetWindowThreadProcessId(window: *mut c_void, process: *mut u32) -> u32;
    fn GetAsyncKeyState(key: i32) -> i16;
    fn GetCursorPos(point: *mut CursorPoint) -> i32;
    fn ScreenToClient(window: *mut c_void, point: *mut CursorPoint) -> i32;
    fn GetClientRect(window: *mut c_void, rectangle: *mut ClientRect) -> i32;
}
#[repr(C)]
#[derive(Default)]
struct CursorPoint {
    x: i32,
    y: i32,
}
#[repr(C)]
#[derive(Default)]
struct ClientRect {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}
fn cursor_normalized() -> [f32; 2] {
    unsafe {
        let window = GetForegroundWindow();
        let mut point = CursorPoint::default();
        let mut rect = ClientRect::default();
        if GetCursorPos(&mut point) == 0
            || ScreenToClient(window, &mut point) == 0
            || GetClientRect(window, &mut rect) == 0
            || rect.right <= rect.left
            || rect.bottom <= rect.top
        {
            return [0.5; 2];
        }
        [
            (point.x as f32 / (rect.right - rect.left) as f32).clamp(0.0, 1.0),
            (point.y as f32 / (rect.bottom - rect.top) as f32).clamp(0.0, 1.0),
        ]
    }
}

struct SupportedVersion;
impl GameVersion for SupportedVersion {
    const NAME: &'static str = "elden ring";
    fn from_lang_version(language: u16, version: &str) -> Option<Self> {
        (language == LANG_ID_EN && version == "2.7.1.0").then_some(Self)
    }
}

enum IoRequest {
    Log(String),
    CommandAck { seq: u64, status: &'static str },
}

fn log(tx: &Sender<IoRequest>, message: impl Into<String>) {
    let _ = tx.send(IoRequest::Log(message.into()));
}

fn io_worker(folder: PathBuf) -> std::io::Result<(Sender<IoRequest>, Receiver<Command>)> {
    // This function runs on the bootstrap thread, never on the game thread.
    // Fail before registering any callback if persistence cannot be initialized.
    fs::create_dir_all(&folder)?;
    let mut logfile = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(folder.join("eldencraft-native.log"))?;
    let (tx, requests) = mpsc::channel();
    let (commands, command_rx) = mpsc::sync_channel(8);
    let file_control = std::env::var("ELDENCRAFT_FILE_CONTROL").is_ok_and(|value| value == "1");
    std::thread::spawn(move || {
        let mut write_log = |line: &str| {
            let timestamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();
            let _ = writeln!(logfile, "{timestamp} {line}");
            let _ = logfile.flush();
        };
        let command_path = folder.join("command.json");
        // Never replay a leftover command when the game is restarted.
        let mut previous_command = read_command(&command_path).unwrap_or_default();
        let mut previous_sequence = Command::parse(&previous_command)
            .map(|command| command.seq)
            .unwrap_or(0);
        if file_control {
            write_log("Local file controls enabled; existing command.json ignored until changed.");
        }
        loop {
            if file_control
                && let Ok(text) = read_command(&command_path)
                && text != previous_command
            {
                previous_command = text.clone();
                match Command::parse(&text) {
                    Ok(command) if command.seq > previous_sequence => {
                        previous_sequence = command.seq;
                        if commands.try_send(command).is_err() {
                            write_log("Local control queue full; command discarded.");
                        }
                    }
                    Ok(_) => write_log("Local control ignored: sequence did not increase."),
                    Err(error) => write_log(&format!("Local control rejected: {error}")),
                }
            }
            let request = match requests.recv_timeout(Duration::from_millis(50)) {
                Ok(request) => request,
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            };
            match request {
                IoRequest::Log(message) => write_log(&message),
                IoRequest::CommandAck { seq, status } => {
                    write_log(&format!("Local control seq={seq}: {status}."));
                    let ack = serde_json::json!({"seq":seq,"status":status});
                    let _ = fs::write(folder.join("command-ack.json"), ack.to_string());
                }
            }
        }
    });
    Ok((tx, command_rx))
}

fn read_command(path: &Path) -> std::io::Result<String> {
    let mut text = String::new();
    fs::File::open(path)?.take(1025).read_to_string(&mut text)?;
    Ok(text)
}

fn acknowledge(tx: &Sender<IoRequest>, command: Option<Command>, status: &'static str) {
    if let Some(command) = command {
        let _ = tx.send(IoRequest::CommandAck {
            seq: command.seq,
            status,
        });
    }
}

fn data_folder(module: usize) -> PathBuf {
    if let Some(path) = std::env::var_os("ELDENCRAFT_DATA_DIR") {
        return PathBuf::from(path);
    }
    let mut buffer = vec![0u16; 32768];
    let count = unsafe {
        GetModuleFileNameW(
            module as *mut c_void,
            buffer.as_mut_ptr(),
            buffer.len() as u32,
        )
    } as usize;
    let dll = PathBuf::from(String::from_utf16_lossy(&buffer[..count.min(buffer.len())]));
    dll.parent()
        .unwrap_or(Path::new("."))
        .join("eldencraft-data")
}

fn verify_executable() -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    if !exe
        .file_name()
        .is_some_and(|name| name.to_string_lossy().eq_ignore_ascii_case("eldenring.exe"))
    {
        return Err("This plugin only loads inside eldenring.exe.".into());
    }
    let module = unsafe { GetModuleHandleW(std::ptr::null()) };
    if module.is_null() {
        return Err("No executable module.".into());
    }
    let pe = unsafe { PeView::module(module as *const u8) };
    SupportedVersion::detect(&pe).map_err(|e| e.to_string())?;
    {
        const ANALYZED_SHA256: &str =
            "1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891";
        let expected = std::env::var("ELDENCRAFT_EXPECTED_SHA256")
            .map_err(|_| "Missing analyzed executable hash.")?;
        if !expected.eq_ignore_ascii_case(ANALYZED_SHA256) {
            return Err("Executable is not the analyzed movement build.".into());
        }
        if expected.len() != 64 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("Invalid expected executable hash.".into());
        }
        let mut file = fs::File::open(exe).map_err(|e| e.to_string())?;
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 65536];
        loop {
            let count = file.read(&mut buffer).map_err(|e| e.to_string())?;
            if count == 0 {
                break;
            }
            digest.update(&buffer[..count]);
        }
        if format!("{:x}", digest.finalize()) != expected.to_ascii_lowercase() {
            return Err("Executable hash changed; revalidate before enabling the plugin.".into());
        }
    }
    Ok(())
}

pub fn start(module: usize) {
    let folder = data_folder(module);
    let crash_folder = folder.clone();
    let (tx, commands) = match io_worker(folder) {
        Ok(channels) => channels,
        Err(error) => {
            eprintln!("EldenCraft disabled: cannot initialize mod data/log folder: {error}");
            return;
        }
    };
    log(
        &tx,
        "EldenCraft actual Minecraft passthrough host starting.",
    );
    let checked = std::panic::catch_unwind(verify_executable);
    match checked {
        Ok(Ok(())) => {}
        Ok(Err(reason)) => {
            log(&tx, format!("Disabled: {reason}"));
            return;
        }
        Err(_) => {
            log(&tx, "Disabled: executable metadata could not be verified.");
            return;
        }
    }
    log(
        &tx,
        "Verified worldwide product version 2.7.1.0 before SDK initialization.",
    );
    match crate::crash::install(&crash_folder) {
        Ok(()) => log(
            &tx,
            "Fault recorder ready: bridge faults write crash/eldencraft-crash-N.txt and .dmp next to this log.",
        ),
        Err(error) => log(&tx, format!("Fault recorder unavailable: {error}")),
    }
    if crate::hosting::hosted() {
        log(
            &tx,
            "Hosted by the hot-reload loader: tasks and exports persist across core reloads.",
        );
    }
    // SDK task unregistration is not implemented upstream. Pin our module for
    // this process lifetime rather than allowing the callback to outlive it.
    let mut pinned = std::ptr::null_mut();
    if unsafe { GetModuleHandleExW(0x1 | 0x4, start as *const () as *const u16, &mut pinned) } == 0
    {
        log(&tx, "Disabled: could not pin callback module lifetime.");
        return;
    }
    match unsafe { crate::minecraft_shield::install() } {
        Ok(()) => log(
            &tx,
            "Minecraft frontal shield: 90% native HP-damage reduction installed.",
        ),
        Err(error) => log(
            &tx,
            format!("Minecraft shield reduction unavailable: {error}"),
        ),
    }
    match unsafe { crate::footsteps::install() } {
        Ok(()) => log(
            &tx,
            "Minecraft footsteps: paired local-player native walk/run sounds suppressed.",
        ),
        Err(error) => log(
            &tx,
            format!("Minecraft footstep ownership unavailable: {error}"),
        ),
    }
    // Capture the camera handed to the renderer. Sampling the simulation
    // camera here can pair a newer view with an older host color/depth frame.
    match unsafe { crate::scene_camera::install() } {
        Ok(()) => log(
            &tx,
            "Minecraft scene render-camera submission hook installed.",
        ),
        Err(error) => log(
            &tx,
            format!("Minecraft world rendering unavailable: {error}"),
        ),
    }
    // Install before character processing can start; exact SHA and module lifetime
    // are established above. An unavailable hook leaves native movement intact.
    let movement = match unsafe { crate::movement_driver::Driver::install() } {
        Ok(driver) => {
            log(&tx, "Minecraft movement collision-stage hook installed.");
            Some(driver)
        }
        Err(error) => {
            log(&tx, format!("Minecraft movement unavailable: {error}"));
            None
        }
    };
    if crate::pose_lock::enabled() {
        log(
            &tx,
            "Pose-locked composition enabled: Elden Ring renders the exact camera of Minecraft's newest frame (adds about one Minecraft frame of camera latency).",
        );
    }
    let task = match CSTaskImp::wait_for_instance(Duration::from_secs(120)) {
        Ok(task) => task,
        Err(error) => {
            log(&tx, format!("SDK initialization failed: {error}"));
            return;
        }
    };
    log(
        &tx,
        "Ready: real Minecraft composition starts with the save; F6 toggles it, F5 cycles first/rear/front view.",
    );
    let diagnostic_task_only =
        std::env::var("ELDENCRAFT_DIAGNOSTIC_MODE").is_ok_and(|mode| mode == "task-only");
    if diagnostic_task_only {
        log(
            &tx,
            "Diagnostic mode: task-only. No singleton reads or drawing will run in the callback.",
        );
    }
    let campaign = match crate::campaign_runtime::Driver::open(&crash_folder) {
        Ok(driver) => driver,
        Err(error) => {
            log(&tx, format!("Campaign disabled: {error}"));
            None
        }
    };
    if campaign.is_some() {
        match unsafe { crate::campaign_runtime::install_talk_hook() } {
            Ok(()) => log(
                &tx,
                "Campaign shops and automatic remembrance capacities ready; native leveling disabled.",
            ),
            Err(error) => log(&tx, format!("Campaign merchant hook unavailable: {error}")),
        }
    }
    let interactions = if diagnostic_task_only {
        None
    } else {
        match unsafe { crate::interaction_runtime::Driver::init() } {
            Ok(driver) => Some(driver),
            Err(error) => {
                log(
                    &tx,
                    format!("Minecraft interaction UI unavailable: {error}"),
                );
                None
            }
        }
    };
    let passthrough_camera = Arc::new(Mutex::new(
        crate::camera_driver::Driver::new(first_person::Settings {
            hide_body: true,
            vertical_fov_radians: Some(70.0f32.to_radians()),
            ..Default::default()
        })
        .expect("constant camera settings"),
    ));
    let render_camera = passthrough_camera.clone();
    let camera_log = tx.clone();
    let mut camera_failed = false;
    crate::hosting::register(
        task,
        CSTaskGroupIndex::DrawParamUpdate,
        move |_: &FD4TaskData| {
            let _phase = crate::crash::phase("camera task");
            if crate::hosting::shutting_down() {
                crate::scene_camera::suspend();
                if let Ok(mut driver) = render_camera.try_lock() {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                        driver.suspend()
                    }));
                }
                return;
            }
            if camera_failed {
                return;
            }
            if let Ok(mut driver) = render_camera.try_lock()
                && std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                    let result = driver.tick();
                    crate::scene_camera::capture(result.as_ref().is_ok_and(|status| status.active));
                    result
                }))
                .is_err()
            {
                camera_failed = true;
                crate::scene_camera::suspend();
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                    driver.suspend()
                }));
                log(
                    &camera_log,
                    "First-person camera disabled after a callback panic.",
                );
            }
        },
    );
    let combat = Arc::new(Mutex::new(crate::combat::Driver::new()));
    let input_combat = combat.clone();
    let combat_log = tx.clone();
    let mut combat_failed = false;
    let mut combat_event = (false, 0u64, false, false, 0u64, 0u64, false, None);
    let mut combat_error = None;
    let mut combat_sample = Instant::now();
    crate::hosting::register(
        task,
        CSTaskGroupIndex::GameFlowStep,
        move |_: &FD4TaskData| {
            let _phase = crate::crash::phase("input task");
            // Drain bounded hit observations on the game task. The detour only
            // collects data; the existing worker performs all log file I/O.
            for trace in crate::minecraft_shield::take_hit_traces() {
                log(&combat_log, format!("Minecraft shield hit: {trace:?}"));
            }
            if crate::hosting::shutting_down() {
                crate::movement_driver::set_input_ready(false);
                crate::overlay_input::clear_look();
                if let Ok(mut driver) = input_combat.try_lock() {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                        driver.suspend()
                    }));
                }
                return;
            }
            // Own panic boundary: input inspection must never unwind into the engine.
            if std::panic::catch_unwind(|| unsafe { sample_direct_look() }).is_err() {
                crate::overlay_input::clear_look();
            }
            if let Ok(mut driver) = input_combat.try_lock() {
                if eldencraft_gui_open() != 0 {
                    crate::movement_driver::set_input_ready(false);
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                        driver.suspend()
                    }));
                    return;
                }
                if combat_failed {
                    crate::movement_driver::set_input_ready(false);
                    // A busy device can defer restoration. Stop issuing gameplay
                    // inputs after a panic, but keep releasing any writes we own.
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                        driver.suspend()
                    }));
                    return;
                }
                match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                    driver.tick()
                })) {
                    Ok(Ok(status)) => {
                        crate::movement_driver::set_input_ready(
                            status.active && status.movement_captured,
                        );
                        let event = (
                            status.active,
                            status.swing,
                            status.attack_requested,
                            status.guard_requested,
                            status.melee.applied,
                            status.melee.rejected,
                            status.movement_captured,
                            status.movement_error,
                        );
                        if event != combat_event
                            || status.hit.is_some()
                            || status.active && combat_sample.elapsed() > Duration::from_secs(5)
                        {
                            log(&combat_log, format!("Minecraft combat: {status:?}"));
                            combat_event = event;
                            combat_sample = Instant::now();
                        }
                        combat_error = None;
                    }
                    Ok(Err(error)) => {
                        crate::movement_driver::set_input_ready(false);
                        if combat_error != Some(error) {
                            log(
                                &combat_log,
                                format!(
                                    "Combat suspended: {error}; mapping={:?}",
                                    driver.diagnostic()
                                ),
                            );
                            combat_error = Some(error);
                        }
                    }
                    Err(_) => {
                        crate::movement_driver::set_input_ready(false);
                        combat_failed = true;
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                            driver.suspend()
                        }));
                        log(&combat_log, "Combat disabled after a callback panic.");
                    }
                }
            }
        },
    );
    let host_publisher = match crate::host_pose::HostPublisher::open() {
        Ok(writer) => Some(writer),
        Err(error) => {
            log(&tx, format!("Host pose publication unavailable: {error}"));
            None
        }
    };
    let mut host = Host {
        io: tx.clone(),
        commands,
        map: None,
        enabled: true,
        debug_hitboxes: false,
        passthrough_view: 0,
        previous_pose: None,
        combat_evidence: None,
        combat_probe_signature: None,
        combat,
        passthrough_camera,
        host_publisher,
        pose_lock_logged: 0,
        host_action: Default::default(),
        interactions,
        campaign,
        settle: Default::default(),
        grace_reset: Default::default(),
        hurt: Default::default(),
        ledge_queries: None,
        guest_status: crate::guest_status::Reader::new(),
        host_hud: crate::host_hud::Controller::new(),
        last_host_hud_error: None,
        last_pause_support: None,
        control_pulse: None,
        previous_buttons: 0,
        input_sequence: 0,
        last_camera_sample: Instant::now(),
        keys: [false; KEY_COUNT],
        failed: false,
        movement,
        movement_error: None,
        last_movement_sample: Instant::now(),
        healing: crate::minecraft_healing::Driver::new(),
        glide_safety: crate::glide_safety::Driver::new(),
        shared_world: crate::world_bridge::Driver::new(),
        last_gate: "",
        last_diagnostic: Instant::now(),
        diagnostic_ticks: 0,
        snapshot_stage: 0,
        last_player_flags: None,
        native_map: Default::default(),
        last_weather: None,
        input_capture: input_capture::Capture::new(),
        last_camera_error: "",
        combat_trace: std::env::var("ELDENCRAFT_COMBAT_TRACE").is_ok_and(|s| s == "1"),
        combat_trace_previous: None,
    };
    let mut diagnostic_reported = false;
    crate::hosting::register(
        task,
        CSTaskGroupIndex::ChrIns_PostPhysics,
        move |_: &FD4TaskData| {
            let _phase = crate::crash::phase("post-physics task");
            if crate::hosting::shutting_down() {
                // Restore every native write this host owns before its state is dropped.
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| host.suspend()));
                return;
            }
            if diagnostic_task_only {
                if !diagnostic_reported {
                    log(&host.io, "Diagnostic callback is running.");
                    diagnostic_reported = true;
                }
                return;
            }
            if host.failed {
                return;
            }
            if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| host.tick())).is_err() {
                crate::footsteps::revoke();
                PASSTHROUGH_DEADLINE.store(0, Ordering::Release);
                GUI_DEADLINE.store(0, Ordering::Release);
                MENU_OPEN_DEADLINE.store(0, Ordering::Release);
                LOOK_DEADLINE.store(0, Ordering::Release);
                if let Some(ui) = host.interactions.as_mut() {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                        ui.suspend();
                    }));
                }
                if let Some(driver) = host.movement.as_mut() {
                    driver.suspend();
                }
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    if let Ok(mut driver) = host.combat.try_lock() {
                        let _ = unsafe { driver.suspend() };
                    }
                }));
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe {
                    host.input_capture.suspend()
                }));
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    let guard = host.passthrough_camera.try_lock();
                    match guard {
                        Ok(mut driver) => unsafe {
                            driver.suspend();
                        },
                        Err(std::sync::TryLockError::Poisoned(error)) => unsafe {
                            error.into_inner().suspend();
                        },
                        Err(std::sync::TryLockError::WouldBlock) => {}
                    }
                    if let Some(writer) = host.host_publisher.as_mut() {
                        writer.inactive();
                    }
                    let _ = unsafe { host.host_hud.suspend() };
                }));
                host.failed = true;
                log(
                    &host.io,
                    "Disabled after a callback panic; no further SDK calls will be made.",
                );
            }
        },
    );
}

struct Snapshot {
    map: u32,
    player: [f32; 3],
    camera: [f32; 3],
    forward: [f32; 3],
    offset: [f32; 3],
    fov: f32,
    hp: i32,
    max_hp: i32,
    avatar_yaw: f32,
    grounded: bool,
    runes: Option<u32>,
    /// Live player instance address, only compared for identity.
    identity: usize,
    native_menu_blocked: bool,
}

fn snapshot(
    tx: &Sender<IoRequest>,
    checkpoint: &mut u8,
    last_player_flags: &mut Option<(u8, u8)>,
    supported_host_menu: bool,
) -> Result<Snapshot, &'static str> {
    fn stage(tx: &Sender<IoRequest>, checkpoint: &mut u8, value: u8, message: &str) {
        if *checkpoint < value {
            *checkpoint = value;
            log(tx, format!("Snapshot initialization: {message}."));
        }
    }
    // Called only within the game's post-physics task, never on the IO thread.
    unsafe {
        let game = GameMan::instance().map_err(|_| "waiting for GameMan")?;
        stage(
            tx,
            checkpoint,
            1,
            &format!(
                "GameMan available; online_mode={}, server_connection_capability={}",
                game.is_in_online_mode, game.server_connection_enabled
            ),
        );
        // server_connection_enabled describes general capability, not the active
        // session. Gate on actual online mode plus lobby/protocol below, which
        // also avoids treating transient title-screen state as a loaded world.
        if game.is_in_online_mode {
            return Err("online mode is active");
        }
        if game.warp_requested {
            return Err("world transition requested");
        }
        let session = CSSessionManager::instance().map_err(|_| "waiting for session manager")?;
        stage(
            tx,
            checkpoint,
            2,
            "session manager available; checking lobby",
        );
        if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None
        {
            return Err("multiplayer session state is active");
        }
        let player = PlayerIns::local_player().map_err(|_| "waiting for local player")?;
        stage(
            tx,
            checkpoint,
            3,
            "local player available; checking active entry",
        );
        // SDK documents these bits as actual activity and registered update tasks.
        // The ChrSetEntry enum fields have no equivalent usage evidence, so they
        // are recorded as raw diagnostic bytes rather than a locality gate.
        let active = player.chr_ins.chr_flags1c8.is_active();
        let tasks_registered = player.chr_ins.chr_flags1c8.update_tasks_registered();
        let dead = player.chr_ins.chr_flags1c5.death_flag();
        if !active || !tasks_registered {
            return Err("local player activity/update tasks are not ready");
        }
        if dead {
            return Err("local player death flag is active");
        }
        let entry = player.chr_ins.chr_set_entry.as_ref();
        let flags = (
            std::ptr::addr_of!(entry.chr_load_status)
                .cast::<u8>()
                .read(),
            std::ptr::addr_of!(entry.chr_update_type)
                .cast::<u8>()
                .read(),
        );
        let entry_matches = entry
            .chr_ins
            .is_some_and(|pointer| std::ptr::eq(pointer.as_ptr(), &player.chr_ins));
        if *last_player_flags != Some(flags) {
            *last_player_flags = Some(flags);
            log(
                tx,
                format!(
                    "Player entry: raw_load_status={}, raw_update_type={}, identity_matches={}, active={active}, tasks_registered={tasks_registered}, dead={dead}, block={:08x}.",
                    flags.0, flags.1, entry_matches, player.current_block_id.0 as u32
                ),
            );
        }
        if !entry_matches {
            return Err("player entry identity does not match local player");
        }
        if player.chr_ins.modules.data.hp <= 0 {
            return Err("player has no health");
        }
        stage(tx, checkpoint, 4, "active player healthy; checking menu");
        let menu = CSMenuManImp::instance().map_err(|_| "waiting for game menu")?;
        // The SDK documents this view as inactive while a blocking menu is open.
        let menu_active = menu.system_announce_view_model.view.as_ref().is_active;
        let physics = &player.chr_ins.modules.physics;
        stage(tx, checkpoint, 5, "menu ready; reading player position");
        let position = player.block_position;
        let block_position = [position.x, position.y, position.z];
        let offset = [
            position.x - physics.position.0,
            position.y - physics.position.1,
            position.z - physics.position.2,
        ];
        let cameras = CSCamera::instance().map_err(|_| "waiting for camera")?;
        stage(
            tx,
            checkpoint,
            6,
            "camera manager available; reading camera",
        );
        let camera = cameras.pers_cam_1.as_ref();
        let eye = camera.position();
        let direction = camera.forward();
        let forward =
            normalize([direction.0, direction.1, direction.2]).ok_or("camera basis invalid")?;
        let direction = camera.right();
        normalize([direction.0, direction.1, direction.2]).ok_or("camera right basis invalid")?;
        let direction = camera.up();
        normalize([direction.0, direction.1, direction.2]).ok_or("camera up basis invalid")?;
        let camera_position = [eye.0 + offset[0], eye.1 + offset[1], eye.2 + offset[2]];
        if !block_position
            .iter()
            .chain(offset.iter())
            .chain(camera_position.iter())
            .all(|v| v.is_finite())
            || !camera.fov.is_finite()
            || camera.fov <= 0.05
            || camera.fov >= 3.13
            || distance_squared(block_position, camera_position) > 2500.0
        {
            return Err("camera/position sanity check rejected");
        }
        if !menu_active && !supported_host_menu {
            return Err("blocking game menu is open");
        }
        if player.current_block_id.0 == -1 {
            return Err("current block has not initialized");
        }
        stage(
            tx,
            checkpoint,
            7,
            &format!(
                "validated snapshot: hp={}, menu_active={}, block={:08x}, player={block_position:?}, camera={camera_position:?}, forward={forward:?}, fov={}, offset={offset:?}, online={}, lobby={:?}, protocol={:?}",
                player.chr_ins.modules.data.hp,
                menu_active,
                player.current_block_id.0 as u32,
                camera.fov,
                game.is_in_online_mode,
                session.lobby_state,
                session.protocol_state
            ),
        );
        // block_position is explicitly paired with current_block_id in PlayerIns;
        // a character's origin block can differ during overworld transitions.
        Ok(Snapshot {
            map: player.current_block_id.0 as u32,
            identity: player as *const _ as usize,
            native_menu_blocked: !menu_active,
            player: block_position,
            camera: camera_position,
            forward,
            offset,
            fov: camera.fov,
            hp: player.chr_ins.modules.data.hp,
            max_hp: player.chr_ins.modules.data.max_hp,
            avatar_yaw: first_person::minecraft_body_yaw([
                physics.orientation.0,
                physics.orientation.1,
                physics.orientation.2,
                physics.orientation.3,
            ])?,
            grounded: physics.standing_on_solid_ground || physics.touching_solid_ground,
            // Both SDK routes must identify the same local save data. This is
            // read-only actual currency; availability is independent of zero.
            runes: GameDataMan::instance().ok().and_then(|data| {
                std::ptr::eq(
                    player.player_game_data.as_ptr(),
                    data.main_player_game_data.as_ref(),
                )
                .then(|| data.main_player_game_data.rune_count)
            }),
        })
    }
}

const VK_M: i32 = 0x4d;
const VK_ESCAPE: i32 = 0x1b;
fn key_down(key: i32) -> bool {
    unsafe { GetAsyncKeyState(key) < 0 }
}
const KEY_COUNT: usize = 4;
const KEYS: [i32; KEY_COUNT] = [0x75, 0x74, 0x73, 0x52]; // F6 composition, F5 view, F4 hitboxes, R interaction.
struct Host {
    io: Sender<IoRequest>,
    commands: Receiver<Command>,
    map: Option<u32>,
    enabled: bool,
    keys: [bool; KEY_COUNT],
    failed: bool,
    last_gate: &'static str,
    last_diagnostic: Instant,
    diagnostic_ticks: u64,
    snapshot_stage: u8,
    last_player_flags: Option<(u8, u8)>,
    native_map: crate::native_map::Driver,
    /// Last native weather ID (None: unread), for change diagnostics only.
    last_weather: Option<Option<i16>>,
    input_capture: input_capture::Capture,
    last_camera_error: &'static str,
    passthrough_camera: Arc<Mutex<crate::camera_driver::Driver>>,
    last_camera_sample: Instant,
    host_publisher: Option<crate::host_pose::HostPublisher>,
    pose_lock_logged: u64,
    host_action: crate::host_action::Driver,
    interactions: Option<crate::interaction_runtime::Driver>,
    campaign: Option<crate::campaign_runtime::Driver>,
    settle: crate::settle::Settle,
    grace_reset: crate::grace_reset::Lease,
    hurt: crate::hurt::Detector,
    ledge_queries: Option<Result<crate::native_colliders::Api, &'static str>>,
    previous_buttons: u32,
    input_sequence: u64,
    control_pulse: Option<(u32, Instant)>,
    guest_status: crate::guest_status::Reader,
    host_hud: crate::host_hud::Controller,
    last_host_hud_error: Option<&'static str>,
    last_pause_support: Option<bool>,
    passthrough_view: u32,
    debug_hitboxes: bool,
    movement: Option<crate::movement_driver::Driver>,
    glide_safety: crate::glide_safety::Driver,
    movement_error: Option<&'static str>,
    last_movement_sample: Instant,
    healing: crate::minecraft_healing::Driver,
    shared_world: crate::world_bridge::Driver,
    previous_pose: Option<([f32; 3], Instant)>,
    combat: Arc<Mutex<crate::combat::Driver>>,
    combat_evidence: Option<(bool, bool, i32, i32)>,
    combat_probe_signature: Option<(u64, bool, bool, bool, bool)>,
    combat_trace: bool,
    combat_trace_previous: Option<(u64, u64, bool, bool)>,
}

impl Host {
    fn tick_passthrough(
        &mut self,
        state: &Snapshot,
        command: Option<Command>,
        mut pressed: [bool; KEY_COUNT],
    ) {
        if self.map != Some(state.map) {
            // A first entry starts clean. Walking or gliding across an open-world
            // tile keeps the anchored shared world, camera and movement alive:
            // a full suspension here revoked flight mid-air and snapped the view.
            // Region-keyed components (movement identity, healing, chat) refresh
            // themselves from the new block; publish the world in this same task.
            if self.map.is_none() {
                self.suspend();
            } else {
                self.shared_world.publish_now();
            }
            self.map = Some(state.map);
            log(
                &self.io,
                format!(
                    "Passthrough entered region {:08x}; composition enabled={}; view={}. F6 toggles rendering.",
                    state.map, self.enabled, self.passthrough_view
                ),
            );
        }
        if let Some(command) = command {
            if let Some(index) = command.toggle_index() {
                pressed[index] = true;
                acknowledge(
                    &self.io,
                    Some(command),
                    "accepted for current foreground offline world",
                );
            } else if let Some(bits) = command.input_bits() {
                self.control_pulse = Some((bits, Instant::now() + Duration::from_millis(180)));
                acknowledge(
                    &self.io,
                    Some(command),
                    "accepted Minecraft action pulse for current foreground offline world",
                );
            }
        }
        if pressed[0] {
            self.enabled = !self.enabled;
            log(
                &self.io,
                if self.enabled {
                    "Genuine Minecraft frame composition enabled."
                } else {
                    "Minecraft frame composition disabled."
                },
            );
        }
        if self.enabled
            && pressed[1]
            && self.guest_status.poll() != Some(true)
            && !self.interactions.as_ref().is_some_and(|ui| ui.blocking())
        {
            self.passthrough_view = (self.passthrough_view + 1) % 3;
            log(
                &self.io,
                format!(
                    "Minecraft view mode {} (0=first,1=rear,2=front).",
                    self.passthrough_view
                ),
            );
        }
        if self.enabled && pressed[2] {
            self.debug_hitboxes = !self.debug_hitboxes;
            log(
                &self.io,
                format!(
                    "Combat hitbox debugger {} (F4).",
                    if self.debug_hitboxes {
                        "enabled"
                    } else {
                        "disabled"
                    }
                ),
            );
        }
        if !self.enabled {
            self.suspend();
            return;
        }
        let guest_gui = self.guest_status.poll();
        let now_ms = unsafe { GetTickCount64() };
        let raw = crate::overlay_input::take(now_ms);
        let compositor_ready = raw.is_some() && guest_gui.is_some();
        crate::footsteps::authorize(compositor_ready.then_some(state.identity), now_ms);
        crate::chat_input::update(now_ms, compositor_ready, state.map, guest_gui == Some(true));
        crate::chat_input::text_screen(
            now_ms,
            compositor_ready && guest_gui == Some(true) && self.guest_status.text_entry(),
        );
        // Pending-open text ownership closes the gap before the next guest GUI frame.
        let host_ui = self.interactions.as_ref().is_some_and(|ui| ui.blocking());
        let supported_host_menu = self
            .interactions
            .as_ref()
            .is_some_and(|ui| ui.supports_blocking());
        // A native menu blocks gameplay but keeps its own input until the
        // Minecraft replacement actually owns a screen or a pending open.
        let minecraft_gui = guest_gui.map(|open| {
            open || host_ui
                || crate::chat_input::active(now_ms)
                || now_ms < MENU_OPEN_DEADLINE.load(Ordering::Acquire)
        });
        let guest_gui = minecraft_gui.map(|open| open || state.native_menu_blocked);
        // R: Elden Ring's own interaction latch (E stays Minecraft's inventory).
        let requested_interaction = self
            .interactions
            .as_mut()
            .is_some_and(|ui| ui.take_interact());
        unsafe {
            self.host_action.tick(
                compositor_ready && guest_gui == Some(false),
                pressed[3] || requested_interaction,
                now_ms,
            );
        }
        while let Some(event) = self.host_action.events.pop_front() {
            log(&self.io, event);
        }
        LOOK_DEADLINE.store(
            if compositor_ready && guest_gui == Some(false) {
                now_ms + 100
            } else {
                0
            },
            Ordering::Release,
        );
        if compositor_ready
            && guest_gui == Some(false)
            && let Ok(observation) = unsafe { crate::combat::observe() }
        {
            let evidence = (
                observation.attack_readback,
                observation.guard_requested,
                observation.stamina,
                observation.hp,
            );
            if self.combat_evidence.is_none_or(|old| {
                old.0 != evidence.0
                    || old.1 != evidence.1
                    || old.2 > evidence.2
                    || old.3 != evidence.3
            }) {
                log(
                    &self.io,
                    format!("PostPhysics combat readback: {observation:?}"),
                );
            }
            self.combat_evidence = Some(evidence);
        }
        let mut movement_look = None;
        let mut gameplay_yaw = state.avatar_yaw;
        if let Ok(mut driver) = self.passthrough_camera.try_lock() {
            driver.set_view_mode(self.passthrough_view);
            if let Some(view) = self.shared_world.view_settings(now_ms) {
                driver.set_view_settings(
                    view.mouse_sensitivity,
                    view.invert_x,
                    view.invert_y,
                    view.bob_view,
                    view.damage_tilt,
                );
            }
            driver.set_menu_locked(compositor_ready && guest_gui == Some(true));
            driver.set_look_enabled(compositor_ready && guest_gui == Some(false));
            if compositor_ready && self.last_camera_sample.elapsed() > Duration::from_secs(5) {
                log(
                    &self.io,
                    format!(
                        "Minecraft camera view={}: {:?}; driver_error={:?}; raw_look_samples={}",
                        self.passthrough_view,
                        unsafe { driver.observe() },
                        driver.error(),
                        LOOK_SAMPLES.load(Ordering::Relaxed)
                    ),
                );
                log(
                    &self.io,
                    format!("Host combat observation: {:?}", unsafe {
                        crate::combat::observe()
                    }),
                );
                self.last_camera_sample = Instant::now();
            }
            if let Err(error) =
                unsafe { driver.authorize_for_menu(compositor_ready, supported_host_menu) }
            {
                if self.last_camera_error != error {
                    log(&self.io, error);
                    self.last_camera_error = error;
                }
            } else {
                self.last_camera_error = "";
            }
            if let (Some(yaw), Some(right)) = (driver.look_yaw_degrees(), driver.look_right()) {
                gameplay_yaw = yaw;
                let angle = yaw.to_radians();
                movement_look = Some(([-angle.sin(), 0., angle.cos()], right));
            }
        }
        let movement_buttons = raw.map_or(0, |sample| sample.buttons);
        let (using_item, movement_input_ready) =
            self.combat.try_lock().map_or((false, false), |driver| {
                let status = driver.status();
                (
                    status.guard_requested,
                    status.active && status.movement_captured,
                )
            });
        // Vanilla getting-hit feedback: camera tilt toward the attacker and knockback
        // away from it (none while a raised shield takes the hit).
        if self.hurt.observe(state.identity, state.hp) && compositor_ready {
            let away = unsafe { crate::hurt::away_from_attacker() };
            if let Ok(mut camera) = self.passthrough_camera.try_lock() {
                camera.hurt(away);
            }
            if let (Some(away), Some(driver)) = (away, self.movement.as_mut())
                && !using_item
            {
                driver.knockback(away);
            }
        }
        let movement_wanted = compositor_ready
            && guest_gui == Some(false)
            && movement_look.is_some()
            && self.movement.is_some();
        let mut sprinting = false;
        let mut gliding = false;
        let mut mounted = false;
        if let Some(driver) = self.movement.as_mut() {
            driver.set_flight(self.shared_world.player_flight(now_ms));
            driver.set_torrent(self.shared_world.player_torrent(now_ms));
            let enabled = movement_wanted && movement_input_ready;
            let input = crate::movement_driver::Input::from_buttons(movement_buttons, using_item);
            // Crouch edge protection needs native rays, which run here, not in physics.
            let drops = if enabled && input.sneak && state.grounded {
                let api = self
                    .ledge_queries
                    .get_or_insert_with(crate::native_colliders::Api::resolve);
                api.as_ref().ok().and_then(|api| unsafe {
                    let feet =
                        std::array::from_fn(|i| f64::from(state.player[i] - state.offset[i]));
                    crate::ledge::probe(api, feet, state.identity)
                })
            } else {
                None
            };
            driver.set_ledges(drops);
            let (forward, right) = movement_look.unwrap_or(([0., 0., 1.], [1., 0., 0.]));
            match unsafe { driver.authorize(enabled, input, forward, right) } {
                Ok(()) => {
                    self.movement_error = None;
                }
                Err(error) => {
                    if self.movement_error != Some(error) {
                        log(&self.io, format!("Minecraft movement suspended: {error}"));
                        self.movement_error = Some(error);
                    }
                }
            }
            // The physics boundary owns the vanilla sprint latch (key, double tap, release rules).
            // Held through short authorization gaps so the sprint FOV never pumps.
            sprinting = enabled && driver.camera_sprinting();
            gliding = driver.status().gliding || self.shared_world.player_flight(now_ms).is_some();
            // Presentation only: an open inventory must not drop the rider's view.
            mounted = self.shared_world.player_torrent(now_ms).is_some();
            if compositor_ready && self.last_movement_sample.elapsed() > Duration::from_secs(5) {
                log(
                    &self.io,
                    format!("Minecraft movement: {:?}", driver.status()),
                );
                self.last_movement_sample = Instant::now();
            }
        }
        if let Ok(mut camera) = self.passthrough_camera.try_lock() {
            camera.set_sprinting(sprinting);
            // The rider's eye sits on Torrent's saddle, as the guest seats the avatar.
            camera.set_eye_lift(if mounted {
                crate::torrent::RIDER_LIFT_M
            } else {
                0.0
            });
        }
        // F11/stale rendering must release native HUD/input ownership too. The
        // raw-input mailbox is the compositor's foreground, visible-frame heartbeat.
        GUI_DEADLINE.store(
            if compositor_ready && minecraft_gui == Some(true) {
                now_ms + 500
            } else {
                0
            },
            Ordering::Release,
        );
        match unsafe { self.host_hud.update(compositor_ready) } {
            Ok(_) => self.last_host_hud_error = None,
            Err(error) => {
                if self.last_host_hud_error != Some(error) {
                    log(
                        &self.io,
                        format!("Minecraft host HUD ownership unavailable: {error}"),
                    );
                    self.last_host_hud_error = Some(error);
                }
            }
        }
        if compositor_ready {
            let supported = crate::host_hud::pause_permission_supported();
            if self.last_pause_support != Some(supported) {
                if !supported {
                    log(
                        &self.io,
                        "Minecraft native Pause reservation unavailable: permission fingerprints do not match; native permission remains under host control.",
                    );
                } else if self.last_pause_support == Some(false) {
                    log(
                        &self.io,
                        "Minecraft native Pause reservation available again.",
                    );
                }
                self.last_pause_support = Some(supported);
            }
        }
        // These controllers own the same pad fields. Restore one before the
        // other acquires them, under the same nonblocking driver lock.
        if let Ok(mut combat) = self.combat.try_lock() {
            combat.set_shield_forward(movement_look.map(|(forward, _)| forward));
            combat.set_debug_bounds(self.debug_hitboxes);
            // Space is handled at the verified pre-collision velocity boundary.
            // Reserve both native Jump and Backstep without authorizing their
            // animation startup while the Minecraft controller owns movement.
            combat.set_movement_jump(movement_wanted.then_some(false));
            if let Ok(Some(probe)) = unsafe { combat.probe() } {
                let status = combat.status();
                let signature = (
                    status.swing,
                    status.attack_requested,
                    status.guard_requested,
                    probe[0].result,
                    probe[1].result,
                );
                if self.combat_probe_signature != Some(signature) {
                    log(
                        &self.io,
                        format!("PostPhysics input probe: swing={} {probe:?}", status.swing),
                    );
                    self.combat_probe_signature = Some(signature);
                }
            } else {
                self.combat_probe_signature = None;
            }
            if compositor_ready && guest_gui == Some(true) {
                let restored = unsafe { combat.suspend() }.is_ok();
                if minecraft_gui == Some(true) {
                    if restored {
                        let _ = unsafe { self.input_capture.capture() };
                    }
                } else {
                    let _ = unsafe { self.input_capture.suspend() };
                }
            } else {
                let _ = unsafe { self.input_capture.suspend() };
                let _ = unsafe { combat.authorize(compositor_ready) };
            }
        }
        // Exact ECHS order; Y (bit 22) whistles for Torrent.
        let input_keys = [
            0x01, 0x02, 0x45, 0x1B, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x20,
            0x10, 0x11, 0x57, 0x53, 0x41, 0x44, 0x51, 0x46, 0x59,
        ];
        // ReShade owns raw input while its GUI capture blocks the game's OS input APIs.
        // Prefer that stream whenever fresh; never sample the blocked cursor during a guest GUI.
        let (mut buttons, cursor, mut wheel) = if let Some(raw) = raw {
            (raw.buttons, raw.cursor, raw.wheel)
        } else if minecraft_gui == Some(true) {
            (0, [0.5; 2], 0)
        } else {
            (
                input_keys.iter().enumerate().fold(0u32, |value, (i, key)| {
                    value | ((unsafe { GetAsyncKeyState(*key) } < 0) as u32) << i
                }),
                cursor_normalized(),
                0,
            )
        };
        if let Some((pulse, until)) = self.control_pulse {
            if Instant::now() < until {
                buttons |= pulse;
            } else {
                self.control_pulse = None;
            }
        }
        // Pending native grace recovery publishes pose/readiness, not guest
        // actions. The original native menu receives the physical input until
        // an actual Minecraft GUI takes ownership.
        if state.native_menu_blocked && minecraft_gui != Some(true) {
            buttons = 0;
            wheel = 0;
        }
        if buttons != self.previous_buttons || wheel != 0 {
            self.previous_buttons = buttons;
            self.input_sequence = self.input_sequence.wrapping_add(1);
        }
        let now = Instant::now();
        let movement_speed = self.previous_pose.map_or(0.0, |(position, time)| {
            let dt = now.duration_since(time).as_secs_f32();
            let distance = ((state.player[0] - position[0]).powi(2)
                + (state.player[2] - position[2]).powi(2))
            .sqrt();
            if dt > 0.0001 && dt < 0.25 {
                (distance / dt).clamp(0.0, 100.0)
            } else {
                0.0
            }
        });
        self.previous_pose = Some((state.player, now));
        let time = unsafe { crate::clock_sync::read_seconds_since_midnight() };
        let native_weather = unsafe { crate::weather_sync::read() };
        let weather = native_weather.and_then(crate::weather_sync::classify);
        if self.last_weather != Some(native_weather) {
            self.last_weather = Some(native_weather);
            log(
                &self.io,
                match native_weather {
                    Some(id) => format!("Native weather {id}: Minecraft {weather:?}."),
                    None if !crate::weather_sync::supported() => {
                        "Native weather unavailable: executable fingerprint mismatch.".into()
                    }
                    None => "Native weather unavailable.".into(),
                },
            );
        }
        let healed_hp = match unsafe { self.healing.tick(compositor_ready, state.map as i32) } {
            Ok(hp) => hp,
            Err(reason) => {
                self.healing.fail(reason);
                None
            }
        };
        if let Some(event) = self.healing.event.take() {
            log(&self.io, event);
        }
        unsafe {
            self.shared_world.tick(compositor_ready);
        }
        while let Some(event) = self.shared_world.events.pop_front() {
            log(&self.io, event);
        }
        let gliding = gliding || self.shared_world.player_flight(now_ms).is_some();
        unsafe {
            self.glide_safety.tick(gliding, state.grounded, now_ms);
        }
        for event in self.glide_safety.events.drain(..) {
            log(&self.io, event);
        }
        if self.shared_world.take_relocated().is_some() {
            // The shared-world transaction already published the new native feet.
            // This task's input snapshot predates that move: publishing it here
            // would pull the guest camera/player back to the departure point.
            // Keep the prior short-lived ECHS lease for this one task; the next
            // native snapshot and DrawParam camera read use synchronized physics.
            if let Some(driver) = self.movement.as_mut() {
                driver.suspend();
            }
            self.previous_pose = None;
            PASSTHROUGH_DEADLINE.store(unsafe { GetTickCount64() } + 500, Ordering::Release);
            return;
        }
        if let Some(writer) = self.host_publisher.as_mut() {
            use crate::host_pose::{
                ACTIVE, FIRST_PERSON, FOREGROUND, SPRINTING, Snapshot as HostSnapshot, TIME_VALID,
            };
            // Pose lock publishes the camera the player asked for; Elden Ring
            // itself renders it once Minecraft finished a frame with it.
            let locked = if crate::pose_lock::enabled() && compositor_ready {
                crate::pose_lock::lock()
                    .try_lock()
                    .ok()
                    .and_then(|lock| lock.desired(state.map, now_ms))
            } else {
                None
            };
            let (camera_xyz, forward, fov) = locked.map_or(
                (state.camera.map(f64::from), state.forward, state.fov),
                |pose| (pose.camera, pose.basis[2], pose.fov),
            );
            let published = writer.publish(HostSnapshot {
                flags: if compositor_ready {
                    ACTIVE
                        | FOREGROUND
                        | if self.passthrough_view == 0 {
                            FIRST_PERSON
                        } else {
                            0
                        }
                        | if time.is_some() { TIME_VALID } else { 0 }
                        | if sprinting { SPRINTING } else { 0 }
                } else {
                    0
                },
                camera_xyz,
                forward,
                vertical_fov_degrees: fov.to_degrees(),
                feet_xyz: state.player.map(f64::from),
                hp: healed_hp.unwrap_or(state.hp),
                max_hp: state.max_hp,
                buttons_down: buttons,
                input_sequence: self.input_sequence,
                cursor_xy: cursor,
                wheel_delta: wheel,
                view_mode: self.passthrough_view,
                time_seconds: time.unwrap_or(0.0),
                avatar_yaw: gameplay_yaw,
                movement_speed,
                grounded: state.grounded,
                map_id: state.map,
                runes: state.runes,
                weather,
            });
            if let (Ok(()), Some(pose)) = (published, locked)
                && let Ok(mut lock) = crate::pose_lock::lock().try_lock()
            {
                lock.note_published(writer.frame(), pose, now_ms);
                if now_ms.saturating_sub(self.pose_lock_logged) >= 5000 {
                    log(
                        &self.io,
                        format!(
                            "Pose lock: locked={} fallback={} (frames rendered with Minecraft's exact camera vs live camera).",
                            lock.locked, lock.fallback
                        ),
                    );
                    lock.locked = 0;
                    lock.fallback = 0;
                    self.pose_lock_logged = now_ms;
                }
            }
        }
        PASSTHROUGH_DEADLINE.store(unsafe { GetTickCount64() } + 500, Ordering::Release);
    }
    fn suspend(&mut self) {
        self.grace_reset.clear();
        self.suspend_inner(false);
    }
    /// Restore every gameplay writer and publish inactive host health. Only
    /// bounded, passive ESD source observations survive the native rest reset.
    fn suspend_for_grace_reset(&mut self) {
        self.suspend_inner(true);
    }
    fn suspend_inner(&mut self, preserve_grace_sources: bool) {
        crate::footsteps::revoke();
        crate::chat_input::suspend(unsafe { GetTickCount64() });
        self.shared_world.suspend();
        self.healing.suspend();
        PASSTHROUGH_DEADLINE.store(0, Ordering::Release);
        GUI_DEADLINE.store(0, Ordering::Release);
        LOOK_DEADLINE.store(0, Ordering::Release);
        MENU_OPEN_DEADLINE.store(0, Ordering::Release);
        crate::movement_driver::set_input_ready(false);
        if let Some(driver) = self.movement.as_mut() {
            driver.suspend();
        }
        crate::overlay_input::clear();
        self.control_pulse = None;
        self.previous_pose = None;
        self.combat_evidence = None;
        self.combat_probe_signature = None;
        if let Ok(mut combat) = self.combat.try_lock() {
            let _ = unsafe { combat.suspend() };
        }
        unsafe {
            self.glide_safety.suspend();
        }
        let _ = unsafe { self.host_hud.suspend() };
        if let Some(writer) = self.host_publisher.as_mut() {
            writer.inactive();
        }
        // Old poses belong to the suspended session; never lock onto them later.
        if let Ok(mut lock) = crate::pose_lock::lock().try_lock() {
            lock.reset();
        }
        unsafe {
            self.host_action.suspend();
            if let Some(ui) = self.interactions.as_mut() {
                if preserve_grace_sources {
                    ui.suspend_for_grace_reset();
                } else {
                    ui.suspend();
                }
            }
        }
        self.hurt.reset();
        if let Ok(mut driver) = self.passthrough_camera.try_lock() {
            unsafe {
                driver.suspend();
            }
        }
        unsafe {
            self.input_capture.suspend();
        }
    }
    fn tick(&mut self) {
        let now_ms = unsafe { GetTickCount64() };
        self.diagnostic_ticks = self.diagnostic_ticks.saturating_add(1);
        if self.last_diagnostic.elapsed() >= Duration::from_secs(15) {
            self.last_diagnostic = Instant::now();
            // Sample only in this game-thread callback. Periodic state makes a
            // transient title-screen gate distinguishable from a persistent one.
            let online = unsafe { GameMan::instance() }
                .ok()
                .map(|g| g.is_in_online_mode);
            let session = unsafe { CSSessionManager::instance() }
                .ok()
                .map(|s| (s.lobby_state, s.protocol_state));
            log(
                &self.io,
                format!(
                    "Bridge status: ticks={}, gate={}, foreground={}, enabled={}, passthrough_active={}, gui_open={}, online_mode={online:?}, session={session:?}.",
                    self.diagnostic_ticks,
                    self.last_gate,
                    foreground(),
                    self.enabled,
                    eldencraft_passthrough_active(),
                    eldencraft_gui_open(),
                ),
            );
        }
        // Recognize only a bounded loss of the activity bits on the exact same
        // healthy, offline player. This does not admit any gameplay publication.
        let passive_grace_reset = self.enabled
            && foreground()
            && !crate::hosting::shutting_down()
            && unsafe { crate::grace_reset::sample() }
                .filter(|sample| !sample.activity_ready)
                .is_some_and(|sample| self.grace_reset.hold(now_ms, sample));
        let interaction_allowed = self.enabled
            && foreground()
            && !crate::hosting::shutting_down()
            && crate::overlay_input::peek_buttons(now_ms).is_some()
            && self.guest_status.poll().is_some();
        if let Some(ui) = self.interactions.as_mut() {
            unsafe {
                if passive_grace_reset {
                    ui.suspend_for_grace_reset();
                } else {
                    ui.tick(interaction_allowed, now_ms);
                }
            }
            while let Some(event) = ui.events.pop_front() {
                log(&self.io, event);
            }
        }
        if let Some(campaign) = &mut self.campaign {
            let campaign_allowed = self.enabled && foreground() && !crate::hosting::shutting_down();
            unsafe { campaign.tick(campaign_allowed && !passive_grace_reset, campaign_allowed) };
        }
        let command = self.commands.try_recv().ok();
        let now = KEYS.map(|key| key != 0 && unsafe { GetAsyncKeyState(key) < 0 });
        let pressed: [bool; KEY_COUNT] = std::array::from_fn(|i| now[i] && !self.keys[i]);
        self.keys = now;
        if !foreground() {
            acknowledge(&self.io, command, "rejected: game is not foreground");
            self.suspend();
            return;
        }
        // A proven pending native grace menu permits a read-only snapshot so
        // ECHS can regain guest readiness. It does not grant menu replacement or
        // camera ownership; Snapshot retains the native blocking-menu flag.
        let state = match snapshot(
            &self.io,
            &mut self.snapshot_stage,
            &mut self.last_player_flags,
            self.interactions
                .as_ref()
                .is_some_and(|ui| ui.supports_blocking() || ui.pending_grace_recovery()),
        ) {
            Ok(state) => state,
            Err(reason) => {
                // A native world map that blocks the snapshot still closes
                // through its keys; a load or warp ends it outright.
                if crate::settle::relocating(reason) {
                    unsafe { self.native_map.reset() };
                } else {
                    unsafe {
                        self.native_map.tick(
                            now_ms,
                            key_down(VK_M),
                            key_down(VK_ESCAPE),
                            None,
                            false,
                        )
                    };
                }
                if passive_grace_reset
                    && reason == "local player activity/update tasks are not ready"
                {
                    acknowledge(&self.io, command, reason);
                    self.suspend_for_grace_reset();
                    if self.last_gate != "grace_reset" {
                        log(
                            &self.io,
                            "Gate: Site of Grace character reset; gameplay suspended, passive interaction sources retained briefly.",
                        );
                        self.last_gate = "grace_reset";
                    }
                    return;
                }
                // Loads, deaths and warps can place the player twice; settle before resuming.
                if crate::settle::relocating(reason) {
                    self.settle.unsettle();
                }
                acknowledge(&self.io, command, reason);
                self.suspend();
                if self.last_gate != reason {
                    log(&self.io, format!("Gate: {reason}."));
                    self.last_gate = reason;
                }
                return;
            }
        };
        if !self
            .settle
            .observe(unsafe { GetTickCount64() }, state.player)
        {
            acknowledge(
                &self.io,
                command,
                "waiting for the player to settle after a load",
            );
            self.suspend();
            if self.last_gate != "settling" {
                log(&self.io, "Gate: settling after a load, death or warp.");
                self.last_gate = "settling";
            }
            return;
        }
        // Elden Ring's own map: give it the whole frame and every input, as
        // for any native menu, until it closes.
        let gameplay = self.enabled
            && self.guest_status.poll() == Some(false)
            && !self.interactions.as_ref().is_some_and(|ui| ui.blocking())
            && !crate::chat_input::active(now_ms)
            && !state.native_menu_blocked;
        let native_map = unsafe {
            self.native_map.tick(
                now_ms,
                key_down(VK_M),
                key_down(VK_ESCAPE),
                Some(state.player),
                gameplay,
            )
        };
        if native_map {
            acknowledge(&self.io, command, "native map open");
            self.suspend();
            if self.last_gate != "native_map" {
                log(
                    &self.io,
                    "Gate: native map open; Minecraft composition and input released.",
                );
                self.last_gate = "native_map";
            }
            return;
        }
        // Logged only once every gate above passed, so a gate that holds
        // across ticks does not alternate with this line.
        if self.last_gate != "ready" {
            log(&self.io, "Gate: offline world/player/camera ready.");
            self.last_gate = "ready";
        }
        // A complete, settled snapshot is the only source of a new reset lease.
        // Ignore a mismatched second read rather than refresh another player.
        if let Some(mut sample) = unsafe { crate::grace_reset::sample() }
            .filter(|sample| sample.player == state.identity && sample.block as u32 == state.map)
        {
            sample.feet = state.player;
            self.grace_reset.record_ready(now_ms, sample);
        } else {
            self.grace_reset.clear();
        }
        if self.combat_trace
            && let Ok(observation) = unsafe { crate::combat::observe() }
        {
            let signature = (
                observation.action_bits,
                observation.new_press_bits,
                observation.attack_pad.is_some_and(|p| p.result),
                observation.guard_pad.is_some_and(|p| p.result),
            );
            if self.combat_trace_previous != Some(signature) {
                log(
                    &self.io,
                    format!(
                        "Offline control comparison enabled={}: {observation:?}",
                        self.enabled
                    ),
                );
                self.combat_trace_previous = Some(signature);
            }
        }
        self.tick_passthrough(&state, command, pressed);
    }
}

fn foreground() -> bool {
    unsafe {
        let window = GetForegroundWindow();
        if window.is_null() {
            return false;
        }
        let mut process = 0;
        GetWindowThreadProcessId(window, &mut process);
        process == GetCurrentProcessId()
    }
}
fn normalize(v: [f32; 3]) -> Option<[f32; 3]> {
    let length = v.iter().map(|v| v * v).sum::<f32>().sqrt();
    (length.is_finite() && length > 0.0001).then(|| v.map(|v| v / length))
}
fn distance_squared(a: [f32; 3], b: [f32; 3]) -> f32 {
    (0..3).map(|i| (a[i] - b[i]).powi(2)).sum()
}
