//! Read only Minecraft's MCPT header for its actual GUI-open state.
//! No pixels, game pointers or input mutation. Freshness requires advancing publications.
const HEADER_BYTES: usize = 4096;
const DESCRIPTOR_BYTES: usize = 128;
const STRIDE: u64 = 1920 * 1080 * 4 * 3;
const AVATAR_STRIDE: u64 = 1920 * 1080 * 4 * 5;
const NAME: &str = "Local\\EldenCraftFrame";

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset + 4)?.try_into().ok()?,
    ))
}
fn u64_at(bytes: &[u8], offset: usize) -> Option<u64> {
    Some(u64::from_le_bytes(
        bytes.get(offset..offset + 8)?.try_into().ok()?,
    ))
}
#[derive(Clone, Copy)]
struct Header {
    pid: u32,
    publication: u64,
    slot: Option<usize>,
    stride: u64,
}
fn header(bytes: &[u8]) -> Option<Header> {
    if bytes.len() != HEADER_BYTES
        || u32_at(bytes, 0)? != 0x5450_434d
        || u32_at(bytes, 4)? != 1
        || u32_at(bytes, 8)? != HEADER_BYTES as u32
        || u32_at(bytes, 12)? != 3
        || ![STRIDE, AVATAR_STRIDE].contains(&u64_at(bytes, 16)?)
        || u32_at(bytes, 24)? != 1920
        || u32_at(bytes, 28)? != 1080
    {
        return None;
    }
    let pid = u32_at(bytes, 44)?;
    let slot = match u32_at(bytes, 40)? {
        u32::MAX => None,
        n @ 0..=2 => Some(n as usize),
        _ => return None,
    };
    if pid == 0 {
        return None;
    }
    Some(Header {
        pid,
        publication: u64_at(bytes, 32)?,
        slot,
        stride: u64_at(bytes, 16)?,
    })
}
fn gui_open(bytes: &[u8], stride: u64) -> Option<bool> {
    if bytes.len() != DESCRIPTOR_BYTES {
        return None;
    }
    let seq = u64_at(bytes, 0)?;
    let w = u32_at(bytes, 24)?;
    let h = u32_at(bytes, 28)?;
    if seq == 0
        || seq & 1 != 0
        || u64_at(bytes, 8)? == 0
        || w == 0
        || h == 0
        || u32_at(bytes, 44)? & !127 != 0
    {
        return None;
    }
    let flags = u32_at(bytes, 44)?;
    // GPU-shared frames may use the full host resolution; CPU planes stay 1080p.
    let (max_w, max_h) = if flags & 64 != 0 {
        (3840, 2160)
    } else {
        (1920, 1080)
    };
    if w > max_w || h > max_h {
        return None;
    }
    if flags & 32 != 0 && (flags & 16 == 0 || flags & 8 != 0 || stride != AVATAR_STRIDE) {
        return None;
    }
    // Flag 64: planes live in shared GPU textures; the descriptor still names its set.
    if flags & 64 != 0 && (u32_at(bytes, 112)? == 0 || u32_at(bytes, 116)? > 2) {
        return None;
    }
    match u32_at(bytes, 104)? {
        0 => Some(false),
        // 2: the open screen takes typed text (a sign editor).
        1 | 2 => Some(true),
        _ => None,
    }
}
fn text_entry(bytes: &[u8]) -> bool {
    u32_at(bytes, 104) == Some(2)
}

/// Host frame (ECHS frame counter) of Minecraft's newest fresh publication, for
/// pose-locked composition. Zero when no fresh publication was observed.
static LATEST_HOST_FRAME: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static LATEST_HOST_FRAME_AT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
pub fn latest_host_frame(now: u64) -> Option<u64> {
    use std::sync::atomic::Ordering::Acquire;
    let at = LATEST_HOST_FRAME_AT.load(Acquire);
    let frame = LATEST_HOST_FRAME.load(Acquire);
    (frame != 0 && now >= at && now - at < 250).then_some(frame)
}
fn note_host_frame(frame: u64, now: u64) {
    use std::sync::atomic::Ordering::Release;
    LATEST_HOST_FRAME.store(frame, Release);
    LATEST_HOST_FRAME_AT.store(now, Release);
}

#[derive(Default)]
struct Freshness {
    pid: u32,
    publication: u64,
    advanced_at: Option<u64>,
    gui: bool,
}
impl Freshness {
    fn invalidate(&mut self) {
        self.advanced_at = None;
    }
    fn observe(&mut self, pid: u32, publication: u64, gui: bool, now: u64) -> Option<bool> {
        if publication == 0 {
            self.invalidate();
            return None;
        }
        if self.pid != pid || publication < self.publication {
            self.pid = pid;
            self.publication = publication;
            self.gui = gui;
            self.invalidate();
            return None; // An old retained mapping is not proof of a running producer.
        }
        if publication > self.publication {
            self.publication = publication;
            self.advanced_at = Some(now);
            self.gui = gui;
        }
        let age = now.checked_sub(self.advanced_at?)?;
        (age < 2000).then_some(self.gui)
    }
}

#[cfg(windows)]
mod windows {
    use super::*;
    use std::{
        ffi::c_void,
        ptr,
        sync::atomic::{Ordering, fence},
    };
    type Handle = *mut c_void;
    #[link(name = "kernel32")]
    unsafe extern "system" {
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
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
        fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
        fn GetTickCount64() -> u64;
    }
    pub struct Reader {
        name: Vec<u16>,
        view: *const u8,
        mapping: Handle,
        process: Handle,
        pid: u32,
        next_open: u64,
        freshness: Freshness,
        text: bool,
    }
    // Only poll(&mut self) touches the mapping; no references into it are exposed.
    unsafe impl Send for Reader {}
    impl Default for Reader {
        fn default() -> Self {
            Self::new()
        }
    }
    impl Reader {
        pub fn new() -> Self {
            Self::named(NAME)
        }
        fn named(name: &str) -> Self {
            Self {
                name: name.encode_utf16().chain(Some(0)).collect(),
                view: ptr::null(),
                mapping: ptr::null_mut(),
                process: ptr::null_mut(),
                pid: 0,
                next_open: 0,
                freshness: Freshness::default(),
                text: false,
            }
        }
        fn close_mapping(&mut self) {
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
            // Preserve publication identity: reopening the same old bytes must not refresh them.
            self.freshness.invalidate();
        }
        fn open(&mut self, now: u64) -> bool {
            if !self.view.is_null() {
                return true;
            }
            if now < self.next_open {
                return false;
            }
            self.next_open = now.saturating_add(1000);
            unsafe {
                self.mapping = OpenFileMappingW(4, 0, self.name.as_ptr());
                if self.mapping.is_null() {
                    return false;
                }
                self.view = MapViewOfFile(self.mapping, 4, 0, 0, HEADER_BYTES).cast();
            }
            if self.view.is_null() {
                self.close_mapping();
                return false;
            }
            true
        }
        fn live(&mut self, pid: u32) -> bool {
            unsafe {
                if self.pid != pid {
                    if !self.process.is_null() {
                        CloseHandle(self.process);
                    }
                    self.process = OpenProcess(0x0010_0000, 0, pid);
                    self.pid = pid;
                }
                !self.process.is_null() && WaitForSingleObject(self.process, 0) == 258
            }
        }
        unsafe fn counter(&self, offset: usize) -> u64 {
            let value = unsafe { ptr::read_volatile(self.view.add(offset).cast::<u64>()) };
            fence(Ordering::SeqCst);
            value
        }
        /// True when the last fresh poll saw an open screen that takes typed text.
        pub fn text_entry(&self) -> bool {
            self.text
        }
        /// Some(true/false) is a fresh actual guest GUI state; None means unverified/stale.
        pub fn poll(&mut self) -> Option<bool> {
            self.text = false;
            let now = unsafe { GetTickCount64() };
            if !self.open(now) {
                return None;
            }
            for _ in 0..3 {
                let mut bytes = [0u8; HEADER_BYTES];
                unsafe {
                    ptr::copy_nonoverlapping(self.view, bytes.as_mut_ptr(), HEADER_BYTES);
                }
                fence(Ordering::SeqCst);
                let Some(h) = header(&bytes) else {
                    self.close_mapping();
                    return None;
                };
                if !self.live(h.pid) {
                    self.close_mapping();
                    return None;
                }
                let Some(slot) = h.slot else {
                    self.freshness.invalidate();
                    return None;
                };
                if h.publication == 0 {
                    self.freshness.invalidate();
                    return None;
                }
                let offset = 256 + DESCRIPTOR_BYTES * slot;
                let before = unsafe { self.counter(offset) };
                if before == 0 || before & 1 != 0 {
                    continue;
                }
                let mut descriptor = [0u8; DESCRIPTOR_BYTES];
                unsafe {
                    ptr::copy_nonoverlapping(
                        self.view.add(offset),
                        descriptor.as_mut_ptr(),
                        DESCRIPTOR_BYTES,
                    );
                }
                fence(Ordering::SeqCst);
                let stable = unsafe {
                    self.counter(offset) == before
                        && self.counter(32) == h.publication
                        && ptr::read_volatile(self.view.add(40).cast::<u32>()) == slot as u32
                        && ptr::read_volatile(self.view.add(44).cast::<u32>()) == h.pid
                };
                if !stable || u64_at(&descriptor, 0) != Some(before) {
                    continue;
                }
                let Some(gui) = gui_open(&descriptor, h.stride) else {
                    self.close_mapping();
                    return None;
                };
                let observed = self.freshness.observe(h.pid, h.publication, gui, now);
                self.text = observed == Some(true) && text_entry(&descriptor);
                if observed.is_some()
                    && let Some(frame) = u64_at(&descriptor, 16)
                {
                    note_host_frame(frame, now);
                }
                return observed;
            }
            None
        }
    }
    impl Drop for Reader {
        fn drop(&mut self) {
            self.close_mapping();
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn CreateFileMappingW(
                file: Handle,
                attributes: *const c_void,
                protect: u32,
                high: u32,
                low: u32,
                name: *const u16,
            ) -> Handle;
            fn GetCurrentProcessId() -> u32;
        }
        #[test]
        fn real_header_only_mapping_requires_advancement_and_reads_gui_under_seqlock() {
            let name = format!(
                "Local\\EldenCraftGuestTest-{}-{}",
                std::process::id(),
                unsafe { GetTickCount64() }
            );
            let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
            unsafe {
                let mapping = CreateFileMappingW(
                    -1isize as Handle,
                    ptr::null(),
                    4,
                    0,
                    HEADER_BYTES as u32,
                    wide.as_ptr(),
                );
                assert!(!mapping.is_null());
                let view = MapViewOfFile(mapping, 2, 0, 0, HEADER_BYTES).cast::<u8>();
                assert!(!view.is_null());
                let mut bytes = super::super::tests::fixture();
                bytes[44..48].copy_from_slice(&GetCurrentProcessId().to_le_bytes());
                ptr::copy_nonoverlapping(bytes.as_ptr(), view, HEADER_BYTES);
                let mut reader = Reader::named(&name);
                assert_eq!(reader.poll(), None);
                ptr::write_volatile(view.add(32).cast::<u64>(), 2);
                assert_eq!(reader.poll(), Some(false));
                ptr::write_volatile(view.add(256).cast::<u64>(), 3);
                fence(Ordering::SeqCst);
                assert_eq!(reader.poll(), None);
                ptr::write_volatile(view.add(256 + 104).cast::<u32>(), 1);
                fence(Ordering::SeqCst);
                ptr::write_volatile(view.add(256).cast::<u64>(), 4);
                ptr::write_volatile(view.add(32).cast::<u64>(), 3);
                assert_eq!(reader.poll(), Some(true));
                assert_eq!(reader.poll(), Some(true));
                ptr::write_volatile(view.add(40).cast::<u32>(), u32::MAX);
                assert_eq!(reader.poll(), None);
                drop(reader);
                UnmapViewOfFile(view.cast());
                CloseHandle(mapping);
            }
        }
    }
}
#[cfg(windows)]
pub use windows::Reader;

#[cfg(test)]
mod tests {
    use super::*;
    pub(super) fn fixture() -> [u8; HEADER_BYTES] {
        let mut b = [0u8; HEADER_BYTES];
        for (offset, value) in [
            (0, 0x5450434du32),
            (4, 1),
            (8, 4096),
            (12, 3),
            (24, 1920),
            (28, 1080),
            (40, 0),
            (44, 42),
            (280, 16),
            (284, 16),
        ] {
            b[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for (offset, value) in [(16, STRIDE), (32, 1), (256, 2), (264, 1)] {
            b[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
        }
        b
    }
    #[test]
    fn exact_header_and_gui_extension_bounds() {
        let good = fixture();
        assert!(header(&good).is_some());
        assert_eq!(gui_open(&good[256..384], STRIDE), Some(false));
        let mut overlay = good;
        overlay[300..304].copy_from_slice(&15u32.to_le_bytes());
        assert_eq!(gui_open(&overlay[256..384], STRIDE), Some(false));
        overlay[300..304].copy_from_slice(&31u32.to_le_bytes());
        assert_eq!(gui_open(&overlay[256..384], STRIDE), Some(false));
        overlay[300..304].copy_from_slice(&32u32.to_le_bytes());
        assert_eq!(gui_open(&overlay[256..384], STRIDE), None);
        for (offset, bad) in [
            (0, 0u32),
            (4, 2),
            (8, 128),
            (12, 4),
            (24, 3840),
            (28, 2160),
            (40, 3),
            (44, 0),
        ] {
            let mut b = good;
            b[offset..offset + 4].copy_from_slice(&bad.to_le_bytes());
            assert!(header(&b).is_none());
        }
        let mut b = good;
        b[360..364].copy_from_slice(&1u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), Some(true));
        assert!(!text_entry(&b[256..384]));
        b[360..364].copy_from_slice(&2u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), Some(true));
        assert!(text_entry(&b[256..384]));
        b[360..364].copy_from_slice(&3u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), None);
        b = good;
        b[280..284].copy_from_slice(&1921u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), None);
        b = good;
        b[256..264].copy_from_slice(&3u64.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), None);
    }
    #[test]
    fn coherent_avatar_frames_keep_gui_header_compatible() {
        let mut b = fixture();
        b[16..24].copy_from_slice(&AVATAR_STRIDE.to_le_bytes());
        assert_eq!(header(&b).unwrap().stride, AVATAR_STRIDE);
        b[300..304].copy_from_slice(&55u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], AVATAR_STRIDE), Some(false));
        assert_eq!(gui_open(&b[256..384], STRIDE), None);
        b[300..304].copy_from_slice(&32u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], AVATAR_STRIDE), None);
        b[300..304].copy_from_slice(&63u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], AVATAR_STRIDE), None);
        b[16..24].copy_from_slice(&(AVATAR_STRIDE - 4).to_le_bytes());
        assert!(header(&b).is_none());
    }
    #[test]
    fn gpu_shared_frames_keep_gui_state_and_require_a_texture_set() {
        let mut b = fixture();
        b[300..304].copy_from_slice(&(64u32 | 16 | 2 | 1).to_le_bytes());
        assert_eq!(
            gui_open(&b[256..384], STRIDE),
            None,
            "GPU frame without generation"
        );
        b[368..372].copy_from_slice(&77u32.to_le_bytes());
        b[372..376].copy_from_slice(&2u32.to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), Some(false));
        b[372..376].copy_from_slice(&3u32.to_le_bytes());
        assert_eq!(
            gui_open(&b[256..384], STRIDE),
            None,
            "texture set out of range"
        );
        b[300..304].copy_from_slice(&(128u32 | 16).to_le_bytes());
        assert_eq!(gui_open(&b[256..384], STRIDE), None, "unknown flag");
    }
    #[test]
    fn host_frame_is_fresh_only_briefly() {
        note_host_frame(41, 1000);
        assert_eq!(latest_host_frame(1100), Some(41));
        assert_eq!(latest_host_frame(1250), None);
        assert_eq!(latest_host_frame(999), None);
    }
    #[test]
    fn stale_state_never_becomes_fresh_just_from_reopening_or_duplicate_publication() {
        let mut f = Freshness::default();
        assert_eq!(f.observe(42, 100, true, 1000), None);
        assert_eq!(f.observe(42, 100, true, 9000), None);
        assert_eq!(f.observe(42, 101, true, 9000), Some(true));
        assert_eq!(f.observe(42, 101, false, 10_999), Some(true));
        assert_eq!(f.observe(42, 101, true, 11_000), None);
        f.invalidate();
        assert_eq!(f.observe(42, 101, true, 12_000), None);
        assert_eq!(f.observe(42, 102, false, 12_000), Some(false));
        assert_eq!(f.observe(43, 1, true, 12_001), None);
        assert_eq!(f.observe(43, 2, true, 12_002), Some(true));
        assert_eq!(f.observe(43, 1, false, 12_003), None);
    }
}
