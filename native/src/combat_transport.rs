//! Single host writer and bounded receipt reader. No game objects cross this layer.
use crate::combat_wire::{self as wire, BYTES, Damage, Targets};
use std::{
    ffi::c_void,
    io, ptr,
    sync::atomic::{AtomicU64, Ordering, fence},
};
type Handle = *mut c_void;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateMutexW(attributes: *const c_void, owner: i32, name: *const u16) -> Handle;
    fn CreateFileMappingW(
        file: Handle,
        attributes: *const c_void,
        protect: u32,
        high: u32,
        low: u32,
        name: *const u16,
    ) -> Handle;
    fn OpenFileMappingW(access: u32, inherit: i32, name: *const u16) -> Handle;
    fn MapViewOfFile(
        mapping: Handle,
        access: u32,
        high: u32,
        low: u32,
        bytes: usize,
    ) -> *mut c_void;
    fn UnmapViewOfFile(base: *const c_void) -> i32;
    fn CloseHandle(handle: Handle) -> i32;
    fn GetLastError() -> u32;
    fn GetCurrentProcessId() -> u32;
    fn GetTickCount64() -> u64;
    fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
    fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
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
unsafe impl Send for Publisher {} // Mutably owned by one game-task driver.
impl Publisher {
    pub fn open() -> io::Result<Self> {
        Self::open_named("Local\\EldenCraftTargets")
    }
    fn open_named(name: &str) -> io::Result<Self> {
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
                    "target publisher already present",
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
            let mut out = Self {
                view,
                mapping,
                guard,
                pid: GetCurrentProcessId(),
                sequence,
                frame: 0,
            };
            let _ = out.publish(&Targets::default());
            Ok(out)
        }
    }
    pub fn publish(&mut self, snapshot: &Targets) -> Result<u64, &'static str> {
        let sequence = match self.sequence.wrapping_add(2) & !1 {
            0 => 2,
            n => n,
        };
        let frame = self.frame.checked_add(1).ok_or("target frame exhausted")?;
        let b = wire::encode_targets(
            snapshot,
            sequence,
            frame,
            unsafe { GetTickCount64() },
            self.pid,
        )?;
        unsafe {
            let counter = &*self.view.add(8).cast::<AtomicU64>();
            counter.store(sequence - 1, Ordering::SeqCst);
            fence(Ordering::SeqCst);
            ptr::copy_nonoverlapping(b.as_ptr(), self.view, 8);
            ptr::copy_nonoverlapping(b.as_ptr().add(16), self.view.add(16), BYTES - 16);
            fence(Ordering::SeqCst);
            counter.store(sequence, Ordering::SeqCst);
        }
        self.sequence = sequence;
        self.frame = frame;
        Ok(frame)
    }
}
impl Drop for Publisher {
    fn drop(&mut self) {
        let _ = self.publish(&Targets::default());
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
unsafe impl Send for Reader {} // poll takes exclusive ownership; pointers stay private.
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
            self.view = ptr::null();
            self.mapping = ptr::null_mut();
            self.process = ptr::null_mut();
            self.pid = 0;
        }
    }
    pub fn poll(&mut self) -> Option<Damage> {
        let now = unsafe { GetTickCount64() };
        if self.view.is_null() {
            if now < self.next_open {
                return None;
            }
            self.next_open = now.saturating_add(1000);
            unsafe {
                self.mapping = OpenFileMappingW(4, 0, wide("Local\\EldenCraftDamage").as_ptr());
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
            if before != unsafe { ptr::read_volatile(self.view.add(8).cast::<u64>()) } {
                continue;
            }
            let Ok(s) = wire::decode_damage(&b, unsafe { GetTickCount64() }) else {
                return None;
            };
            if s.sequence != before {
                continue;
            }
            if self.pid != s.pid {
                unsafe {
                    if !self.process.is_null() {
                        CloseHandle(self.process);
                    }
                    self.process = OpenProcess(0x0010_0000, 0, s.pid);
                    self.pid = s.pid;
                }
            }
            if self.process.is_null() || unsafe { WaitForSingleObject(self.process, 0) } != 258 {
                self.close();
                return None;
            }
            return Some(s);
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
    fn one_writer_and_coherent_publication() {
        let name = format!(
            "Local\\EldenCraftTargetsTest-{}-{}",
            std::process::id(),
            unsafe { GetTickCount64() }
        );
        let mut p = Publisher::open_named(&name).unwrap();
        assert_eq!(
            Publisher::open_named(&name).err().unwrap().kind(),
            io::ErrorKind::AlreadyExists
        );
        p.publish(&Targets {
            epoch: 19,
            ack_session: 20,
            ..Targets::default()
        })
        .unwrap();
        unsafe {
            let handle = OpenFileMappingW(4, 0, wide(&name).as_ptr());
            assert!(!handle.is_null());
            let view = MapViewOfFile(handle, 4, 0, 0, BYTES).cast::<u8>();
            assert!(!view.is_null());
            let b = std::slice::from_raw_parts(view, BYTES);
            assert_eq!(u64::from_le_bytes(b[40..48].try_into().unwrap()), 19);
            assert_eq!(u64::from_le_bytes(b[96..104].try_into().unwrap()), 20);
            assert_eq!(u64::from_le_bytes(b[8..16].try_into().unwrap()) & 1, 0);
            drop(p);
            assert_eq!(u32::from_le_bytes(b[36..40].try_into().unwrap()), 0);
            UnmapViewOfFile(view.cast());
            CloseHandle(handle);
        }
    }
}
