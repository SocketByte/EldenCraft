//! Hot-reload hosting for the native core.
//!
//! Standalone, the core registers its game tasks directly and pins itself, as
//! before. Hosted by `eldencraft_native.dll` (native/loader), the loader owns the
//! game tasks and the compositor-facing exports for the whole process; this core
//! only keeps its closures in a registry that the loader ticks. A reload asks the
//! old core to clean up on the game thread, restore every code patch it made and
//! drop its state; the loader then loads a fresh copy. Old copies are never
//! unloaded: a thread still returning through an old trampoline or closure keeps
//! valid code.
//!
//! Every code patch goes through [`hook`]: other threads are suspended while the
//! jump is written (and never released while one executes the patched bytes), and
//! the original bytes are kept so [`restore_patches`] can put them back exactly.
use eldenring::{
    cs::{CSTaskGroupIndex, CSTaskImp},
    fd4::FD4TaskData,
};
use fromsoftware_shared::SharedTaskImpExt;
use ilhook::x64::{CallbackOption, ThreadCallback};
use std::ffi::c_void;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

type Handle = *mut c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateToolhelp32Snapshot(flags: u32, pid: u32) -> Handle;
    fn Thread32First(snapshot: Handle, entry: *mut ThreadEntry) -> i32;
    fn Thread32Next(snapshot: Handle, entry: *mut ThreadEntry) -> i32;
    fn OpenThread(access: u32, inherit: i32, tid: u32) -> Handle;
    fn SuspendThread(thread: Handle) -> u32;
    fn ResumeThread(thread: Handle) -> u32;
    fn GetThreadContext(thread: Handle, context: *mut Context) -> i32;
    fn CloseHandle(handle: Handle) -> i32;
    fn GetCurrentThreadId() -> u32;
    fn GetCurrentProcessId() -> u32;
    fn GetCurrentProcess() -> Handle;
    fn VirtualProtect(address: *mut c_void, size: usize, protect: u32, old: *mut u32) -> i32;
    fn FlushInstructionCache(process: Handle, address: *const c_void, size: usize) -> i32;
    fn Sleep(milliseconds: u32);
}
#[repr(C)]
struct ThreadEntry {
    size: u32,
    usage: u32,
    thread: u32,
    owner: u32,
    base_priority: i32,
    delta_priority: i32,
    flags: u32,
}
/// x64 CONTEXT: ContextFlags at 0x30, Rip at 0xF8.
#[repr(C, align(16))]
struct Context([u8; 1232]);
const CONTEXT_CONTROL: u32 = 0x0010_0001;
const INVALID_HANDLE: Handle = -1isize as Handle;

static HOSTED: AtomicBool = AtomicBool::new(false);
static SHUTTING_DOWN: AtomicBool = AtomicBool::new(false);
type Task = Box<dyn FnMut(&FD4TaskData) + Send>;
static TASKS: Mutex<Vec<(u32, Task)>> = Mutex::new(Vec::new());
struct Patch {
    address: usize,
    original: [u8; PATCH_BYTES],
    len: usize,
}
const PATCH_BYTES: usize = 16;
static PATCHES: Mutex<Vec<Patch>> = Mutex::new(Vec::new());

pub fn hosted() -> bool {
    HOSTED.load(Ordering::Acquire)
}
/// True once the loader asked this core to retire; task closures must clean up and return.
pub fn shutting_down() -> bool {
    SHUTTING_DOWN.load(Ordering::Acquire)
}

/// Register a game task: with the loader's persistent task when hosted, directly otherwise.
pub fn register(
    task: &CSTaskImp,
    group: CSTaskGroupIndex,
    closure: impl FnMut(&FD4TaskData) + Send + 'static,
) {
    if hosted() {
        if shutting_down() {
            return;
        }
        if let Ok(mut tasks) = TASKS.lock() {
            tasks.push((group as u32, Box::new(closure)));
        }
    } else {
        // Standalone: SDK task unregistration is not used; the module is pinned.
        std::mem::forget(task.run_recurring(closure, group));
    }
}

/// Suspends every other thread of the process around a code write, retrying
/// while any of them executes the bytes being replaced. No allocation happens
/// while threads are suspended (one of them may hold the heap lock).
struct Freezer {
    start: usize,
    len: usize,
    threads: Mutex<Vec<(Handle, bool)>>,
}
// Handles are used only on the patching thread inside pre/post.
unsafe impl Send for Freezer {}
unsafe impl Sync for Freezer {}
impl Freezer {
    fn new(start: usize, len: usize) -> Self {
        Self {
            start,
            len,
            threads: Mutex::new(Vec::new()),
        }
    }
    fn freeze(&self) -> bool {
        let Ok(mut threads) = self.threads.lock() else {
            return false;
        };
        threads.clear();
        unsafe {
            let snapshot = CreateToolhelp32Snapshot(0x4, 0);
            if snapshot.is_null() || snapshot == INVALID_HANDLE {
                return false;
            }
            let (pid, me) = (GetCurrentProcessId(), GetCurrentThreadId());
            let mut entry = ThreadEntry {
                size: std::mem::size_of::<ThreadEntry>() as u32,
                usage: 0,
                thread: 0,
                owner: 0,
                base_priority: 0,
                delta_priority: 0,
                flags: 0,
            };
            let mut more = Thread32First(snapshot, &mut entry) != 0;
            while more {
                if entry.owner == pid && entry.thread != me {
                    let handle = OpenThread(0x0002 | 0x0008 | 0x0040, 0, entry.thread);
                    if !handle.is_null() {
                        threads.push((handle, false));
                    }
                }
                more = Thread32Next(snapshot, &mut entry) != 0;
            }
            CloseHandle(snapshot);
            for _ in 0..200 {
                for (handle, suspended) in threads.iter_mut() {
                    *suspended = SuspendThread(*handle) != u32::MAX;
                }
                let mut clear = true;
                for (handle, suspended) in threads.iter() {
                    if !*suspended {
                        continue;
                    }
                    let mut context = Context([0; 1232]);
                    context.0[0x30..0x34].copy_from_slice(&CONTEXT_CONTROL.to_le_bytes());
                    if GetThreadContext(*handle, &mut context) == 0 {
                        continue;
                    }
                    let rip =
                        u64::from_le_bytes(context.0[0xF8..0x100].try_into().unwrap_or([0; 8]))
                            as usize;
                    if rip >= self.start && rip < self.start + self.len {
                        clear = false;
                        break;
                    }
                }
                if clear {
                    return true;
                }
                for (handle, suspended) in threads.iter_mut() {
                    if *suspended {
                        ResumeThread(*handle);
                        *suspended = false;
                    }
                }
                Sleep(1);
            }
            for (handle, _) in threads.drain(..) {
                CloseHandle(handle);
            }
        }
        false
    }
    fn thaw(&self) {
        let Ok(mut threads) = self.threads.lock() else {
            return;
        };
        unsafe {
            FlushInstructionCache(GetCurrentProcess(), self.start as *const c_void, self.len);
            for (handle, suspended) in threads.drain(..) {
                if suspended {
                    ResumeThread(handle);
                }
                CloseHandle(handle);
            }
        }
    }
}
impl ThreadCallback for Freezer {
    fn pre(&self) -> bool {
        self.freeze()
    }
    fn post(&self) {
        self.thaw()
    }
}

unsafe fn read_code(address: usize) -> [u8; PATCH_BYTES] {
    let mut bytes = [0; PATCH_BYTES];
    unsafe {
        std::ptr::copy_nonoverlapping(address as *const u8, bytes.as_mut_ptr(), PATCH_BYTES);
    }
    bytes
}

/// Install one ilhook detour with other threads frozen during the write, and
/// remember the exact bytes it replaced. The caller keeps (or leaks) the hook.
/// # Safety
/// Same contract as the ilhook call made by `install`.
pub unsafe fn hook<T, E>(
    address: usize,
    install: impl FnOnce(CallbackOption) -> Result<T, E>,
) -> Result<T, E> {
    let before = unsafe { read_code(address) };
    let result = install(CallbackOption::Some(Box::new(Freezer::new(
        address,
        PATCH_BYTES,
    ))));
    if result.is_ok() {
        let after = unsafe { read_code(address) };
        let len = (0..PATCH_BYTES)
            .rev()
            .find(|&i| before[i] != after[i])
            .map_or(0, |i| i + 1);
        if len > 0
            && let Ok(mut patches) = PATCHES.lock()
        {
            patches.push(Patch {
                address,
                original: before,
                len,
            });
        }
    }
    result
}

/// Put every recorded patch back, newest first. Returns how many were restored.
/// # Safety
/// Game hooks must already be revoked; their trampolines and closures stay allocated.
pub unsafe fn restore_patches() -> usize {
    let Ok(mut patches) = PATCHES.lock() else {
        return 0;
    };
    let mut restored = 0;
    while let Some(patch) = patches.pop() {
        let freezer = Freezer::new(patch.address, patch.len);
        if !freezer.freeze() {
            continue;
        }
        unsafe {
            let mut old = 0;
            if VirtualProtect(patch.address as *mut c_void, patch.len, 0x40, &mut old) != 0 {
                std::ptr::copy_nonoverlapping(
                    patch.original.as_ptr(),
                    patch.address as *mut u8,
                    patch.len,
                );
                VirtualProtect(patch.address as *mut c_void, patch.len, old, &mut old);
                restored += 1;
            }
        }
        freezer.thaw();
    }
    restored
}

/// Loader entry: start this core hosted. `paths_module` is the loader module, so
/// data and logs keep living next to `eldencraft_native.dll`, not the private copy.
#[unsafe(no_mangle)]
pub extern "C" fn eldencraft_core_start(paths_module: usize) -> u32 {
    HOSTED.store(true, Ordering::Release);
    // Startup verifies the executable and waits for the task system; never on
    // the caller's (possibly game) thread.
    std::thread::Builder::new()
        .name("eldencraft-core-start".into())
        .spawn(move || crate::engine::start(paths_module))
        .is_ok() as u32
}

/// Loader entry, called from every loader-owned game task.
/// # Safety
/// `data` is the task data the game passed to the loader's task.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn eldencraft_core_tick(group: u32, data: *const FD4TaskData) {
    if data.is_null() || shutting_down() {
        return;
    }
    let Ok(mut tasks) = TASKS.try_lock() else {
        return;
    };
    for (task_group, task) in tasks.iter_mut() {
        if *task_group == group {
            let data = unsafe { &*data };
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| task(data)));
        }
    }
}

/// Loader entry, on the game thread inside a loader task: let every task clean
/// up (restore camera, input, HUD), drop all state, then restore code patches.
/// Returns the number of patches restored.
/// # Safety
/// Game task thread only; `data` as for [`eldencraft_core_tick`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn eldencraft_core_shutdown(data: *const FD4TaskData) -> u32 {
    SHUTTING_DOWN.store(true, Ordering::Release);
    let tasks = match TASKS.lock() {
        Ok(mut tasks) => std::mem::take(&mut *tasks),
        Err(poisoned) => std::mem::take(&mut *poisoned.into_inner()),
    };
    let mut tasks = tasks;
    if !data.is_null() {
        let data = unsafe { &*data };
        for (_, task) in tasks.iter_mut() {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| task(data)));
        }
    }
    // Dropping the closures drops publishers, transports and worker channels.
    drop(tasks);
    let restored = unsafe { restore_patches() } as u32;
    crate::crash::uninstall();
    restored
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn freezer_suspends_and_resumes_other_threads() {
        let stop = std::sync::Arc::new(AtomicBool::new(false));
        let flag = stop.clone();
        let worker = std::thread::spawn(move || {
            let mut n = 0u64;
            while !flag.load(Ordering::Relaxed) {
                n = n.wrapping_add(1);
            }
            n
        });
        let freezer = Freezer::new(1, 1);
        assert!(freezer.freeze());
        assert!(
            freezer
                .threads
                .lock()
                .unwrap()
                .iter()
                .any(|(_, suspended)| *suspended)
        );
        freezer.thaw();
        assert!(freezer.threads.lock().unwrap().is_empty());
        stop.store(true, Ordering::Relaxed);
        worker.join().unwrap();
    }
    #[test]
    fn patch_is_recorded_and_restored_byte_exact() {
        // A private RWX page stands in for game code.
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn VirtualAlloc(a: *mut c_void, s: usize, t: u32, p: u32) -> *mut c_void;
        }
        let page = unsafe { VirtualAlloc(std::ptr::null_mut(), 4096, 0x3000, 0x40) } as usize;
        assert_ne!(page, 0);
        unsafe {
            std::ptr::copy_nonoverlapping([0x90u8; 32].as_ptr(), page as *mut u8, 32);
        }
        let installed: Result<(), ()> = unsafe {
            hook(page, |_option| {
                *(page as *mut [u8; 5]) = [0xE9, 1, 2, 3, 4];
                Ok(())
            })
        };
        assert!(installed.is_ok());
        assert_eq!(
            PATCHES.lock().unwrap().last().map(|p| (p.address, p.len)),
            Some((page, 5))
        );
        assert_eq!(unsafe { restore_patches() }, 1);
        assert_eq!(unsafe { read_code(page) }, [0x90; PATCH_BYTES]);
    }
}
