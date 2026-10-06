//! Nonblocking, single-writer, bounded JSON world mapping. No process memory access.
use crate::world_wire::{self as wire, BYTES, HEADER};
use std::{
    ffi::c_void,
    io, ptr,
    sync::atomic::{AtomicU64, Ordering, fence},
};
type Handle = *mut c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateMutexW(a: *const c_void, o: i32, n: *const u16) -> Handle;
    fn CreateFileMappingW(
        f: Handle,
        a: *const c_void,
        p: u32,
        h: u32,
        l: u32,
        n: *const u16,
    ) -> Handle;
    fn OpenFileMappingW(a: u32, i: i32, n: *const u16) -> Handle;
    fn MapViewOfFile(m: Handle, a: u32, h: u32, l: u32, b: usize) -> *mut c_void;
    fn UnmapViewOfFile(v: *const c_void) -> i32;
    fn CloseHandle(h: Handle) -> i32;
    fn GetLastError() -> u32;
    fn GetCurrentProcessId() -> u32;
    fn GetTickCount64() -> u64;
    fn OpenProcess(a: u32, i: i32, p: u32) -> Handle;
    fn WaitForSingleObject(h: Handle, t: u32) -> u32;
}
pub fn now() -> u64 {
    unsafe { GetTickCount64() }
}
pub fn pid() -> u32 {
    unsafe { GetCurrentProcessId() }
}
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}
pub struct Publisher {
    view: *mut u8,
    mapping: Handle,
    guard: Handle,
    sequence: u64,
    pub frame: u64,
}
unsafe impl Send for Publisher {}
impl Publisher {
    pub fn open() -> io::Result<Self> {
        unsafe {
            let name = "Local\\EldenCraftWorldHost";
            let guard = CreateMutexW(ptr::null(), 0, wide(&format!("{name}.Writer")).as_ptr());
            let error = GetLastError();
            if guard.is_null() {
                return Err(io::Error::from_raw_os_error(error as i32));
            }
            if error == 183 {
                CloseHandle(guard);
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "world publisher exists",
                ));
            }
            let mapping = CreateFileMappingW(
                -1isize as Handle,
                ptr::null(),
                4,
                0,
                BYTES as u32,
                wide(name).as_ptr(),
            );
            if mapping.is_null() {
                let e = io::Error::last_os_error();
                CloseHandle(guard);
                return Err(e);
            }
            let view = MapViewOfFile(mapping, 2, 0, 0, BYTES).cast::<u8>();
            if view.is_null() {
                let e = io::Error::last_os_error();
                CloseHandle(mapping);
                CloseHandle(guard);
                return Err(e);
            }
            let sequence = (&*view.add(8).cast::<AtomicU64>()).load(Ordering::SeqCst) & !1;
            let mut p = Self {
                view,
                mapping,
                guard,
                sequence,
                frame: 0,
            };
            let _ = p.publish(false, &serde_json::json!({}));
            Ok(p)
        }
    }
    pub fn publish(
        &mut self,
        active: bool,
        value: &impl serde::Serialize,
    ) -> Result<u64, &'static str> {
        let body = serde_json::to_vec(value).map_err(|_| "world JSON serialization failed")?;
        if body.is_empty() || body.len() > BYTES - HEADER {
            return Err("world publication too large");
        }
        let seq = match self.sequence.wrapping_add(2) & !1 {
            0 => 2,
            n => n,
        };
        let frame = self.frame.checked_add(1).ok_or("world frame exhausted")?;
        let mut h = [0u8; HEADER];
        h[0..4].copy_from_slice(&wire::HOST_MAGIC.to_le_bytes());
        h[4..8].copy_from_slice(&wire::VERSION.to_le_bytes());
        h[16..24].copy_from_slice(&frame.to_le_bytes());
        h[24..32].copy_from_slice(&now().to_le_bytes());
        h[32..36].copy_from_slice(&pid().to_le_bytes());
        h[36..40].copy_from_slice(&u32::from(active).to_le_bytes());
        h[40..44].copy_from_slice(&(body.len() as u32).to_le_bytes());
        unsafe {
            let counter = &*self.view.add(8).cast::<AtomicU64>();
            counter.store(seq - 1, Ordering::SeqCst);
            fence(Ordering::SeqCst);
            ptr::copy_nonoverlapping(h.as_ptr(), self.view, 8);
            ptr::copy_nonoverlapping(h.as_ptr().add(16), self.view.add(16), HEADER - 16);
            ptr::copy_nonoverlapping(body.as_ptr(), self.view.add(HEADER), body.len());
            fence(Ordering::SeqCst);
            counter.store(seq, Ordering::SeqCst);
        }
        self.sequence = seq;
        self.frame = frame;
        Ok(frame)
    }
}
impl Drop for Publisher {
    fn drop(&mut self) {
        let _ = self.publish(false, &serde_json::json!({}));
        unsafe {
            UnmapViewOfFile(self.view.cast());
            CloseHandle(self.mapping);
            CloseHandle(self.guard);
        }
    }
}
pub struct Reader {
    view: *const u8,
    mapping: Handle,
    process: Handle,
    pid: u32,
    next_open: u64,
    copy: Vec<u8>,
}
unsafe impl Send for Reader {}
impl Default for Reader {
    fn default() -> Self {
        Self {
            view: ptr::null(),
            mapping: ptr::null_mut(),
            process: ptr::null_mut(),
            pid: 0,
            next_open: 0,
            copy: Vec::new(),
        }
    }
}
impl Reader {
    fn close(&mut self) {
        unsafe {
            if !self.view.is_null() {
                UnmapViewOfFile(self.view.cast());
            }
            if !self.mapping.is_null() {
                CloseHandle(self.mapping);
            }
            if !self.process.is_null() {
                CloseHandle(self.process);
            }
        }
        self.view = ptr::null();
        self.mapping = ptr::null_mut();
        self.process = ptr::null_mut();
        self.pid = 0;
    }
    pub fn poll(&mut self) -> Option<wire::Envelope> {
        let timestamp = now();
        if self.view.is_null() {
            if timestamp < self.next_open {
                return None;
            }
            self.next_open = timestamp.saturating_add(1000);
            unsafe {
                self.mapping = OpenFileMappingW(4, 0, wide("Local\\EldenCraftWorldGuest").as_ptr());
                if self.mapping.is_null() {
                    return None;
                }
                self.view = MapViewOfFile(self.mapping, 4, 0, 0, BYTES).cast();
            }
            if self.view.is_null() {
                self.close();
                return None;
            }
        }
        for _ in 0..3 {
            let before = unsafe { ptr::read_volatile(self.view.add(8).cast::<u64>()) };
            if before == 0 || before & 1 != 0 {
                continue;
            }
            fence(Ordering::SeqCst);
            let length = unsafe { ptr::read_volatile(self.view.add(40).cast::<u32>()) } as usize;
            if length == 0 || length > BYTES - HEADER {
                return None;
            }
            self.copy.resize(HEADER + length, 0);
            unsafe {
                ptr::copy_nonoverlapping(self.view, self.copy.as_mut_ptr(), self.copy.len());
            }
            fence(Ordering::SeqCst);
            if before != unsafe { ptr::read_volatile(self.view.add(8).cast::<u64>()) }
                || before != u64::from_le_bytes(self.copy[8..16].try_into().ok()?)
            {
                continue;
            }
            let m = wire::decode(&self.copy, now()).ok()?;
            if self.pid != m.pid {
                unsafe {
                    if !self.process.is_null() {
                        CloseHandle(self.process);
                    }
                    self.process = OpenProcess(0x0010_0000, 0, m.pid);
                    self.pid = m.pid;
                }
            }
            if self.process.is_null() || unsafe { WaitForSingleObject(self.process, 0) } != 258 {
                self.close();
                return None;
            }
            return Some(m);
        }
        None
    }
}
impl Drop for Reader {
    fn drop(&mut self) {
        self.close();
    }
}
