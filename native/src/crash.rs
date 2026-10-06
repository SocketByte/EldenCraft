//! First-chance fault recorder. It never handles, swallows or resumes a fault:
//! it writes evidence and returns EXCEPTION_CONTINUE_SEARCH so the game's own
//! handlers decide. Protected game code raises and handles faults on purpose,
//! so only faults inside this module, inside a bridge phase (our task callbacks
//! and detours, including the game calls they make) or heap corruption are
//! recorded. At most three records per process; nothing allocates in the handler.
//!
//! Output, next to the native log: `crash/eldencraft-crash-N.txt` (time, code,
//! phase, registers, module-relative RIP, access address, 128 raw stack words)
//! and `crash/eldencraft-crash-N.dmp` (minidump with thread info). A dump holds
//! process memory and local paths: inspect it before sharing.
use std::cell::Cell;
use std::ffi::c_void;
use std::path::Path;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};

type Handle = *mut c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn AddVectoredExceptionHandler(
        first: u32,
        handler: unsafe extern "system" fn(*mut Pointers) -> i32,
    ) -> *mut c_void;
    fn RemoveVectoredExceptionHandler(handle: *mut c_void) -> u32;
    fn CreateFileW(
        name: *const u16,
        access: u32,
        share: u32,
        security: *const c_void,
        disposition: u32,
        flags: u32,
        template: Handle,
    ) -> Handle;
    fn WriteFile(
        file: Handle,
        buffer: *const u8,
        bytes: u32,
        written: *mut u32,
        overlapped: *mut c_void,
    ) -> i32;
    fn CloseHandle(handle: Handle) -> i32;
    fn GetCurrentThreadId() -> u32;
    fn GetCurrentProcess() -> Handle;
    fn GetCurrentProcessId() -> u32;
    fn ReadProcessMemory(
        process: Handle,
        address: *const c_void,
        buffer: *mut c_void,
        size: usize,
        read: *mut usize,
    ) -> i32;
    fn LoadLibraryW(name: *const u16) -> Handle;
    fn GetProcAddress(module: Handle, name: *const u8) -> *mut c_void;
    fn GetModuleHandleW(name: *const u16) -> Handle;
    fn GetModuleHandleExW(flags: u32, address: *const u16, module: *mut Handle) -> i32;
    fn GetLocalTime(time: *mut SystemTime);
}
#[repr(C)]
struct Pointers {
    record: *const Record,
    context: *const u8,
}
#[repr(C)]
struct Record {
    code: u32,
    flags: u32,
    chained: *const Record,
    address: usize,
    parameters: u32,
    information: [usize; 15],
}
#[repr(C)]
#[derive(Default)]
struct SystemTime {
    year: u16,
    month: u16,
    weekday: u16,
    day: u16,
    hour: u16,
    minute: u16,
    second: u16,
    millisecond: u16,
}
#[repr(C, packed(4))]
struct DumpException {
    thread: u32,
    pointers: *mut Pointers,
    client_pointers: i32,
}
type WriteDump = unsafe extern "system" fn(
    Handle,
    u32,
    Handle,
    u32,
    *const DumpException,
    *const c_void,
    *const c_void,
) -> i32;

const RECORDED_CODES: [u32; 6] = [
    0xC000_0005,
    0xC000_001D,
    0xC000_0094,
    0xC000_008C,
    0xC000_0374,
    0xC000_0409,
];
const HEAP_CORRUPTION: [u32; 2] = [0xC000_0374, 0xC000_0409];
const MAX_RECORDS: u32 = 3;

struct Setup {
    text: Vec<Vec<u16>>,
    dump: Vec<Vec<u16>>,
    write_dump: Option<WriteDump>,
    game: (usize, usize),
    module: (usize, usize),
}
// Raw module ranges and a function pointer only.
unsafe impl Send for Setup {}
unsafe impl Sync for Setup {}
static SETUP: OnceLock<Setup> = OnceLock::new();
static HANDLER: AtomicUsize = AtomicUsize::new(0);
static RECORDS: AtomicU32 = AtomicU32::new(0);
static WRITING: AtomicBool = AtomicBool::new(false);
thread_local! { static PHASE: Cell<&'static str> = const { Cell::new("") }; }

/// Marks the current thread as running bridge code until dropped.
pub struct Phase(&'static str);
pub fn phase(name: &'static str) -> Phase {
    Phase(PHASE.with(|p| p.replace(name)))
}
impl Drop for Phase {
    fn drop(&mut self) {
        PHASE.with(|p| p.set(self.0));
    }
}

unsafe fn image_range(base: usize) -> (usize, usize) {
    if base == 0 {
        return (0, 0);
    }
    unsafe {
        let pe = base + *((base + 0x3C) as *const u32) as usize;
        let size = *((pe + 24 + 56) as *const u32) as usize;
        (base, base + size)
    }
}
fn wide(path: &Path) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    path.as_os_str().encode_wide().chain(Some(0)).collect()
}

/// Install once per core. `folder` is the native data folder.
pub fn install(folder: &Path) -> Result<(), String> {
    let directory = folder.join("crash");
    std::fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
    let text = (0..MAX_RECORDS)
        .map(|n| wide(&directory.join(format!("eldencraft-crash-{n}.txt"))))
        .collect();
    let dump = (0..MAX_RECORDS)
        .map(|n| wide(&directory.join(format!("eldencraft-crash-{n}.dmp"))))
        .collect();
    let write_dump = unsafe {
        let library = LoadLibraryW(wide(Path::new("dbghelp.dll")).as_ptr());
        let function = if library.is_null() {
            std::ptr::null_mut()
        } else {
            GetProcAddress(library, c"MiniDumpWriteDump".as_ptr().cast())
        };
        (!function.is_null()).then(|| std::mem::transmute::<*mut c_void, WriteDump>(function))
    };
    let mut module = std::ptr::null_mut();
    unsafe {
        GetModuleHandleExW(0x4 | 0x2, install as *const () as *const u16, &mut module);
    }
    let setup = Setup {
        text,
        dump,
        write_dump,
        game: unsafe { image_range(GetModuleHandleW(std::ptr::null()) as usize) },
        module: unsafe { image_range(module as usize) },
    };
    if SETUP.set(setup).is_err() {
        return Err("crash recorder already configured".into());
    }
    let handle = unsafe { AddVectoredExceptionHandler(1, observe) };
    if handle.is_null() {
        return Err("vectored handler unavailable".into());
    }
    HANDLER.store(handle as usize, Ordering::Release);
    Ok(())
}
pub fn uninstall() {
    let handle = HANDLER.swap(0, Ordering::AcqRel);
    if handle != 0 {
        unsafe {
            RemoveVectoredExceptionHandler(handle as *mut c_void);
        }
    }
}

/// Whether a fault is ours to record; pure for tests.
fn relevant(code: u32, rip: usize, phase: &str, module: (usize, usize)) -> bool {
    RECORDED_CODES.contains(&code)
        && (HEAP_CORRUPTION.contains(&code)
            || !phase.is_empty()
            || (rip >= module.0 && rip < module.1))
}

/// Fixed-size, allocation-free text buffer.
struct Text {
    bytes: [u8; 8192],
    len: usize,
}
impl std::fmt::Write for Text {
    fn write_str(&mut self, s: &str) -> std::fmt::Result {
        let n = s.len().min(self.bytes.len() - self.len);
        self.bytes[self.len..self.len + n].copy_from_slice(&s.as_bytes()[..n]);
        self.len += n;
        Ok(())
    }
}

unsafe extern "system" fn observe(pointers: *mut Pointers) -> i32 {
    const CONTINUE_SEARCH: i32 = 0;
    let Some(setup) = SETUP.get() else {
        return CONTINUE_SEARCH;
    };
    if pointers.is_null() {
        return CONTINUE_SEARCH;
    }
    let (record, context) = unsafe { (&*(*pointers).record, (*pointers).context) };
    let register = |offset: usize| unsafe { *(context.add(offset) as *const u64) };
    let rip = register(0xF8) as usize;
    let phase = PHASE.try_with(|p| p.get()).unwrap_or("");
    if !relevant(record.code, rip, phase, setup.module) {
        return CONTINUE_SEARCH;
    }
    if WRITING.swap(true, Ordering::AcqRel) {
        return CONTINUE_SEARCH;
    }
    let index = RECORDS.fetch_add(1, Ordering::AcqRel);
    if index < MAX_RECORDS {
        use std::fmt::Write;
        let mut text = Text {
            bytes: [0; 8192],
            len: 0,
        };
        let mut time = SystemTime::default();
        unsafe {
            GetLocalTime(&mut time);
        }
        let _ = writeln!(
            text,
            "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
            time.year, time.month, time.day, time.hour, time.minute, time.second, time.millisecond
        );
        let _ = writeln!(
            text,
            "code={:08x} thread={} phase={}",
            record.code,
            unsafe { GetCurrentThreadId() },
            if phase.is_empty() { "-" } else { phase }
        );
        let relative = |address: usize| {
            if address >= setup.game.0 && address < setup.game.1 {
                ("eldenring.exe", address - setup.game.0)
            } else if address >= setup.module.0 && address < setup.module.1 {
                ("eldencraft core", address - setup.module.0)
            } else {
                ("other", address)
            }
        };
        let (owner, offset) = relative(rip);
        let _ = writeln!(
            text,
            "rip={rip:x} ({owner}+{offset:x}) game_base={:x} core_base={:x}",
            setup.game.0, setup.module.0
        );
        let _ = writeln!(
            text,
            "rax={:x} rcx={:x} rdx={:x} rbx={:x} rsp={:x} rbp={:x} rsi={:x} rdi={:x}",
            register(0x78),
            register(0x80),
            register(0x88),
            register(0x90),
            register(0x98),
            register(0xA0),
            register(0xA8),
            register(0xB0)
        );
        let _ = writeln!(
            text,
            "r8={:x} r9={:x} r10={:x} r11={:x} r12={:x} r13={:x} r14={:x} r15={:x}",
            register(0xB8),
            register(0xC0),
            register(0xC8),
            register(0xD0),
            register(0xD8),
            register(0xE0),
            register(0xE8),
            register(0xF0)
        );
        if record.code == 0xC000_0005 && record.parameters >= 2 {
            let _ = writeln!(
                text,
                "access={} address={:x}",
                record.information[0], record.information[1]
            );
        }
        let rsp = register(0x98) as usize;
        for i in 0..128usize {
            let mut value = 0u64;
            let mut read = 0usize;
            // Through the OS: the faulting stack itself may be invalid.
            if unsafe {
                ReadProcessMemory(
                    GetCurrentProcess(),
                    (rsp + i * 8) as *const c_void,
                    (&mut value as *mut u64).cast(),
                    8,
                    &mut read,
                )
            } == 0
                || read != 8
            {
                break;
            }
            let (owner, offset) = relative(value as usize);
            if owner == "other" {
                let _ = writeln!(text, "stack+{:04x}={value:016x}", i * 8);
            } else {
                let _ = writeln!(
                    text,
                    "stack+{:04x}={value:016x} ({owner}+{offset:x})",
                    i * 8
                );
            }
        }
        unsafe {
            let file = CreateFileW(
                setup.text[index as usize].as_ptr(),
                0x4000_0000,
                1,
                std::ptr::null(),
                2,
                0x80,
                std::ptr::null_mut(),
            );
            if !file.is_null() && file != -1isize as Handle {
                let mut written = 0;
                WriteFile(
                    file,
                    text.bytes.as_ptr(),
                    text.len as u32,
                    &mut written,
                    std::ptr::null_mut(),
                );
                CloseHandle(file);
            }
            if let Some(write_dump) = setup.write_dump {
                let file = CreateFileW(
                    setup.dump[index as usize].as_ptr(),
                    0x4000_0000,
                    1,
                    std::ptr::null(),
                    2,
                    0x80,
                    std::ptr::null_mut(),
                );
                if !file.is_null() && file != -1isize as Handle {
                    let info = DumpException {
                        thread: GetCurrentThreadId(),
                        pointers,
                        client_pointers: 0,
                    };
                    // MiniDumpNormal | WithUnloadedModules | WithThreadInfo
                    write_dump(
                        GetCurrentProcess(),
                        GetCurrentProcessId(),
                        file,
                        0x0000_0020 | 0x0000_1000,
                        &info,
                        std::ptr::null(),
                        std::ptr::null(),
                    );
                    CloseHandle(file);
                }
            }
        }
    }
    WRITING.store(false, Ordering::Release);
    CONTINUE_SEARCH
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn records_only_bridge_faults_and_heap_corruption() {
        let module = (0x1000, 0x2000);
        assert!(
            relevant(0xC000_0005, 0x1800, "", module),
            "fault in our module"
        );
        assert!(
            relevant(0xC000_0005, 0x9000, "camera task", module),
            "game call made by a bridge task"
        );
        assert!(
            !relevant(0xC000_0005, 0x9000, "", module),
            "protected game code faulting on purpose"
        );
        assert!(
            relevant(0xC000_0374, 0x9000, "", module),
            "heap corruption anywhere"
        );
        assert!(
            !relevant(0x8000_0003, 0x1800, "camera task", module),
            "breakpoints and other codes"
        );
    }
    #[test]
    fn phase_guard_nests_and_restores() {
        assert_eq!(PHASE.with(|p| p.get()), "");
        {
            let _outer = phase("outer");
            {
                let _inner = phase("inner");
                assert_eq!(PHASE.with(|p| p.get()), "inner");
            }
            assert_eq!(PHASE.with(|p| p.get()), "outer");
        }
        assert_eq!(PHASE.with(|p| p.get()), "");
    }
    #[test]
    fn text_buffer_truncates_without_allocating() {
        use std::fmt::Write;
        let mut text = Text {
            bytes: [0; 8192],
            len: 0,
        };
        for _ in 0..2000 {
            let _ = write!(text, "0123456789");
        }
        assert_eq!(text.len, 8192);
    }
}
