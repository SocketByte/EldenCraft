use crate::Watch;
use eldenring::{
    cs::{CSTaskGroupIndex, CSTaskImp},
    fd4::FD4TaskData,
};
use fromsoftware_shared::SharedTaskImpExt;
use std::ffi::c_void;
use std::io::Write;
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU32, AtomicUsize, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

type Module = *mut c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn LoadLibraryW(name: *const u16) -> Module;
    fn GetProcAddress(module: Module, name: *const u8) -> *mut c_void;
    fn GetModuleFileNameW(module: Module, buffer: *mut u16, size: u32) -> u32;
    fn GetCurrentProcessId() -> u32;
}

type Start = unsafe extern "C" fn(usize) -> u32;
type Tick = unsafe extern "C" fn(u32, *const FD4TaskData);
type Shutdown = unsafe extern "C" fn(*const FD4TaskData) -> u32;
/// The compositor-facing exports of one loaded core copy.
struct Core {
    tick: Tick,
    shutdown: Shutdown,
    passthrough_active: Option<unsafe extern "C" fn() -> u32>,
    gui_open: Option<unsafe extern "C" fn() -> u32>,
    overlay_input: Option<unsafe extern "C" fn(u32, f32, f32, i32)>,
    menu_input: Option<unsafe extern "C" fn(u32, f32, f32, i32)>,
    chat_active: Option<unsafe extern "C" fn() -> u32>,
    chat_event: Option<unsafe extern "C" fn(u32, u32, u32) -> u32>,
    scene_camera: Option<unsafe extern "C" fn(*mut c_void, u32) -> u32>,
}

/// Current core; old ones are leaked with their modules.
static CURRENT: AtomicPtr<Core> = AtomicPtr::new(std::ptr::null_mut());
/// Calls currently inside a core (tasks and exports).
static INFLIGHT: AtomicUsize = AtomicUsize::new(0);
static RELOAD: AtomicBool = AtomicBool::new(false);
static GENERATION: AtomicU32 = AtomicU32::new(0);
static SELF_MODULE: AtomicUsize = AtomicUsize::new(0);
static LOG: Mutex<Option<std::fs::File>> = Mutex::new(None);

fn log(message: impl AsRef<str>) {
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    if let Ok(mut file) = LOG.lock()
        && let Some(file) = file.as_mut()
    {
        let _ = writeln!(file, "{time} {}", message.as_ref());
        let _ = file.flush();
    }
}
fn wide(path: &Path) -> Vec<u16> {
    path.as_os_str().encode_wide().chain(Some(0)).collect()
}
fn self_directory(module: usize) -> PathBuf {
    let mut buffer = vec![0u16; 32768];
    let count =
        unsafe { GetModuleFileNameW(module as Module, buffer.as_mut_ptr(), buffer.len() as u32) }
            as usize;
    PathBuf::from(String::from_utf16_lossy(&buffer[..count.min(buffer.len())]))
        .parent()
        .map(Path::to_path_buf)
        .unwrap_or_default()
}
fn data_directory(directory: &Path) -> PathBuf {
    std::env::var_os("ELDENCRAFT_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| directory.join("eldencraft-data"))
}

/// Guard counting a call into the current core.
struct Inflight;
impl Inflight {
    fn enter() -> Self {
        INFLIGHT.fetch_add(1, Ordering::AcqRel);
        Self
    }
}
impl Drop for Inflight {
    fn drop(&mut self) {
        INFLIGHT.fetch_sub(1, Ordering::AcqRel);
    }
}
fn with_core<T>(default: T, call: impl FnOnce(&Core) -> Option<T>) -> T {
    let _inflight = Inflight::enter();
    let core = CURRENT.load(Ordering::Acquire);
    if core.is_null() {
        return default;
    }
    call(unsafe { &*core }).unwrap_or(default)
}

unsafe fn symbol<T>(module: Module, name: &std::ffi::CStr) -> Option<T> {
    let address = unsafe { GetProcAddress(module, name.as_ptr().cast()) };
    (!address.is_null()).then(|| unsafe { std::mem::transmute_copy::<*mut c_void, T>(&address) })
}

/// Copies the core next to its build output and starts it. The copy keeps the
/// original free for the next build.
fn load_core(directory: &Path) -> Result<(), String> {
    let generation = GENERATION.fetch_add(1, Ordering::AcqRel) + 1;
    let source = directory.join("eldencraft_core.dll");
    let loaded = directory.join("loaded");
    std::fs::create_dir_all(&loaded)
        .map_err(|error| format!("cannot create {}: {error}", loaded.display()))?;
    let copy = loaded.join(format!("eldencraft_core_{}_{generation}.dll", unsafe {
        GetCurrentProcessId()
    }));
    std::fs::copy(&source, &copy)
        .map_err(|error| format!("cannot copy {}: {error}", source.display()))?;
    let module = unsafe { LoadLibraryW(wide(&copy).as_ptr()) };
    if module.is_null() {
        return Err(format!("LoadLibrary failed for {}", copy.display()));
    }
    unsafe {
        let start: Start =
            symbol(module, c"eldencraft_core_start").ok_or("core lacks eldencraft_core_start")?;
        let core = Core {
            tick: symbol(module, c"eldencraft_core_tick")
                .ok_or("core lacks eldencraft_core_tick")?,
            shutdown: symbol(module, c"eldencraft_core_shutdown")
                .ok_or("core lacks eldencraft_core_shutdown")?,
            passthrough_active: symbol(module, c"eldencraft_passthrough_active"),
            gui_open: symbol(module, c"eldencraft_gui_open"),
            overlay_input: symbol(module, c"eldencraft_overlay_input"),
            menu_input: symbol(module, c"eldencraft_menu_input"),
            chat_active: symbol(module, c"eldencraft_chat_active"),
            chat_event: symbol(module, c"eldencraft_chat_event"),
            scene_camera: symbol(module, c"eldencraft_scene_camera"),
        };
        if start(SELF_MODULE.load(Ordering::Acquire)) == 0 {
            return Err("core start failed".into());
        }
        CURRENT.store(Box::into_raw(Box::new(core)), Ordering::Release);
    }
    log(format!(
        "core generation {generation} started from {}",
        copy.display()
    ));
    Ok(())
}

/// Game thread, inside a loader task: retire the old core, then start a new copy.
unsafe fn reload(data: *const FD4TaskData) {
    let old = CURRENT.swap(std::ptr::null_mut(), Ordering::AcqRel);
    // Exports on other threads finish their current call into the old core.
    for _ in 0..200 {
        if INFLIGHT.load(Ordering::Acquire) == 0 {
            break;
        }
        std::thread::sleep(Duration::from_millis(1));
    }
    if !old.is_null() {
        let restored = unsafe { ((*old).shutdown)(data) };
        log(format!(
            "core generation {} retired; {restored} code patches restored",
            GENERATION.load(Ordering::Acquire)
        ));
    }
    let directory = self_directory(SELF_MODULE.load(Ordering::Acquire));
    if let Err(error) = load_core(&directory) {
        log(format!(
            "reload failed: {error}; the game continues without the bridge until the next reload"
        ));
    }
}

const GROUPS: [CSTaskGroupIndex; 3] = [
    CSTaskGroupIndex::DrawParamUpdate,
    CSTaskGroupIndex::GameFlowStep,
    CSTaskGroupIndex::ChrIns_PostPhysics,
];

fn start(module: usize) {
    SELF_MODULE.store(module, Ordering::Release);
    let directory = self_directory(module);
    let data = data_directory(&directory);
    let _ = std::fs::create_dir_all(&data);
    if let Ok(file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(data.join("eldencraft-loader.log"))
        && let Ok(mut log) = LOG.lock()
    {
        *log = Some(file);
    }
    // Copies from earlier sessions are no longer mapped by any process.
    if let Ok(entries) = std::fs::read_dir(directory.join("loaded")) {
        for entry in entries.flatten() {
            let _ = std::fs::remove_file(entry.path());
        }
    }
    let hot = std::env::var("ELDENCRAFT_HOT_RELOAD").is_ok_and(|value| value == "1");
    log(format!(
        "loader started; hot reload {}",
        if hot { "enabled" } else { "disabled" }
    ));
    if let Err(error) = load_core(&directory) {
        log(format!("core unavailable: {error}"));
        return;
    }
    let task = match CSTaskImp::wait_for_instance(Duration::from_secs(120)) {
        Ok(task) => task,
        Err(error) => {
            log(format!("task system unavailable: {error}"));
            return;
        }
    };
    for group in GROUPS {
        let index = group as u32;
        let handle = task.run_recurring(
            move |data: &FD4TaskData| {
                let data = data as *const FD4TaskData;
                if index == CSTaskGroupIndex::ChrIns_PostPhysics as u32
                    && RELOAD.swap(false, Ordering::AcqRel)
                {
                    unsafe { reload(data) };
                }
                with_core((), |core| {
                    unsafe { (core.tick)(index, data) };
                    Some(())
                });
            },
            group,
        );
        // Loader tasks live for the whole process; the loader is never unloaded.
        std::mem::forget(handle);
    }
    log("game tasks registered");
    if !hot {
        return;
    }
    let source = directory.join("eldencraft_core.dll");
    let mut watch = Watch::default();
    loop {
        let stamp = std::fs::metadata(&source).ok().and_then(|meta| {
            let modified = meta
                .modified()
                .ok()?
                .duration_since(UNIX_EPOCH)
                .ok()?
                .as_millis() as u64;
            Some((modified, meta.len()))
        });
        if watch.observe(stamp) {
            log("eldencraft_core.dll changed; reloading on the next post-physics task");
            RELOAD.store(true, Ordering::Release);
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

fn game_process() -> bool {
    std::env::current_exe()
        .ok()
        .and_then(|exe| {
            exe.file_name()
                .map(|name| name.to_string_lossy().eq_ignore_ascii_case("eldenring.exe"))
        })
        .unwrap_or(false)
}

/// # Safety
/// Windows loader entry point; never call it directly.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn DllMain(module: Module, reason: u32, _: *mut c_void) -> i32 {
    if reason == 1 {
        let module = module as usize;
        // No file access or loading under the loader lock.
        std::thread::spawn(move || {
            if game_process() {
                start(module)
            }
        });
    }
    1
}

/// Marks this module as the hot-reload loader for the core's DllMain.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_loader_abi() -> u32 {
    1
}

// Compositor exports, forwarded to the current core (closed gates without one).
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_passthrough_active() -> u32 {
    with_core(0, |core| core.passthrough_active.map(|f| unsafe { f() }))
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_gui_open() -> u32 {
    with_core(0, |core| core.gui_open.map(|f| unsafe { f() }))
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_overlay_input(buttons: u32, x: f32, y: f32, wheel: i32) {
    with_core((), |core| {
        core.overlay_input
            .map(|f| unsafe { f(buttons, x, y, wheel) })
    })
}
/// Optional menu ABI: older cores simply ignore it without changing ECHS.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_menu_input(buttons: u32, x: f32, y: f32, wheel: i32) {
    with_core((), |core| {
        core.menu_input.map(|f| unsafe { f(buttons, x, y, wheel) })
    })
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_chat_active() -> u32 {
    with_core(0, |core| core.chat_active.map(|f| unsafe { f() }))
}
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_chat_event(kind: u32, code: u32, modifiers: u32) -> u32 {
    with_core(0, |core| {
        core.chat_event.map(|f| unsafe { f(kind, code, modifiers) })
    })
}
/// # Safety
/// `out` must point to `bytes` writable bytes, as for the core export.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn eldencraft_scene_camera(out: *mut c_void, bytes: u32) -> u32 {
    with_core(0, |core| {
        core.scene_camera.map(|f| unsafe { f(out, bytes) })
    })
}
