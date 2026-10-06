//! Small seqlocked health publications; no game pointers cross process boundaries.
use crate::healing_wire::{self as wire, BYTES, Host, Message};
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
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}
pub struct Publisher {
    view: *mut u8,
    mapping: Handle,
    guard: Handle,
    pid: u32,
    sequence: u64,
    pub frame: u64,
}
unsafe impl Send for Publisher {} // Only accessed through its game-task owner.
impl Publisher {
    pub fn open() -> io::Result<Self> {
        Self::named("Local\\EldenCraftHealingHost")
    }
    fn named(name: &str) -> io::Result<Self> {
        unsafe {
            let guard = CreateMutexW(ptr::null(), 0, wide(&format!("{name}.Writer")).as_ptr());
            let error = GetLastError();
            if guard.is_null() {
                return Err(io::Error::from_raw_os_error(error as i32));
            }
            if error == 183 {
                CloseHandle(guard);
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "healing publisher exists",
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
                pid: GetCurrentProcessId(),
                sequence,
                frame: 0,
            };
            let _ = p.publish(&Host::default());
            Ok(p)
        }
    }
    pub fn publish(&mut self, s: &Host) -> Result<u64, &'static str> {
        let seq = match self.sequence.wrapping_add(2) & !1 {
            0 => 2,
            n => n,
        };
        let frame = self.frame.checked_add(1).ok_or("healing frame exhausted")?;
        let b = wire::encode_host(s, seq, frame, unsafe { GetTickCount64() }, self.pid)?;
        unsafe {
            let counter = &*self.view.add(8).cast::<AtomicU64>();
            counter.store(seq - 1, Ordering::SeqCst);
            fence(Ordering::SeqCst);
            ptr::copy_nonoverlapping(b.as_ptr(), self.view, 8);
            ptr::copy_nonoverlapping(b.as_ptr().add(16), self.view.add(16), BYTES - 16);
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
        let _ = self.publish(&Host::default());
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
    pub fn poll(&mut self) -> Option<Message> {
        let now = unsafe { GetTickCount64() };
        if self.view.is_null() {
            if now < self.next_open {
                return None;
            }
            self.next_open = now.saturating_add(1000);
            unsafe {
                self.mapping = OpenFileMappingW(4, 0, wide("Local\\EldenCraftHealing").as_ptr());
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
            let mut b = [0; BYTES];
            unsafe {
                ptr::copy_nonoverlapping(self.view, b.as_mut_ptr(), BYTES);
            }
            fence(Ordering::SeqCst);
            if before != unsafe { ptr::read_volatile(self.view.add(8).cast::<u64>()) }
                || before != u64::from_le_bytes(b[8..16].try_into().ok()?)
            {
                continue;
            }
            let m = wire::decode(&b, unsafe { GetTickCount64() }).ok()?;
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
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mapping_is_single_writer_and_inactive_on_drop() {
        let name = format!(
            "Local\\EldenCraftHealTest-{}-{}",
            std::process::id(),
            unsafe { GetTickCount64() }
        );
        let mut p = Publisher::named(&name).unwrap();
        assert!(Publisher::named(&name).is_err());
        p.publish(&Host {
            flags: 1,
            epoch: 1,
            hp: 5.,
            max_hp: 20.,
            ..Host::default()
        })
        .unwrap();
        unsafe {
            let h = OpenFileMappingW(4, 0, wide(&name).as_ptr());
            let v = MapViewOfFile(h, 4, 0, 0, BYTES).cast::<u8>();
            assert_eq!(ptr::read_unaligned(v.add(36).cast::<u32>()), 1);
            drop(p);
            assert_eq!(ptr::read_unaligned(v.add(36).cast::<u32>()), 0);
            UnmapViewOfFile(v.cast());
            CloseHandle(h);
        }
    }
}
