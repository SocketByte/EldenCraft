//! ECHS v2 host pose/input/clock publication. This module never reads game pointers or drives input.
//! Windows x64 readers must copy under the aligned seqlock and reject data older than 2 seconds.
use std::fmt;

pub const MAPPING_NAME: &str = "Local\\EldenCraftHost";
pub const MAPPING_BYTES: usize = 256;
pub const MAGIC: u32 = 0x5348_4345; // ECHS, little endian.
pub const VERSION: u32 = 2;
pub const ACTIVE: u32 = 1;
pub const FOREGROUND: u32 = 2;
pub const FIRST_PERSON: u32 = 4;
pub const TIME_VALID: u32 = 8;
pub const SPRINTING: u32 = 16;
/// ECHS v2 extension at offset160; independent of the pose/input flag word.
pub const RUNES_VALID: u32 = 1;
pub const BUTTON_ATTACK: u32 = 1;
pub const BUTTON_USE: u32 = 1 << 1;
pub const BUTTON_INVENTORY: u32 = 1 << 2;
pub const BUTTON_ESCAPE: u32 = 1 << 3;
pub const BUTTON_HOTBAR_1: u32 = 1 << 4; // Slots 1..9 occupy bits 4..12.
pub const BUTTON_JUMP: u32 = 1 << 13;
pub const BUTTON_SNEAK: u32 = 1 << 14;
pub const BUTTON_SPRINT: u32 = 1 << 15;
pub const BUTTON_FORWARD: u32 = 1 << 16;
pub const BUTTON_BACKWARD: u32 = 1 << 17;
pub const BUTTON_LEFT: u32 = 1 << 18;
pub const BUTTON_RIGHT: u32 = 1 << 19;
pub const BUTTON_DROP: u32 = 1 << 20;
pub const BUTTON_SWAP_HANDS: u32 = 1 << 21;
pub const BUTTON_TORRENT: u32 = 1 << 22; // Y: summon or dismiss Torrent.
pub const BUTTON_MASK: u32 = (1 << 23) - 1;

#[derive(Clone, Copy, Debug)]
pub struct Snapshot {
    pub flags: u32,
    /// Host coordinates, Y up. The consumer must calibrate relative anchors before applying them.
    pub camera_xyz: [f64; 3],
    pub forward: [f32; 3],
    pub vertical_fov_degrees: f32,
    pub feet_xyz: [f64; 3],
    /// Actual host HP only; this field does not modify Minecraft's health.
    pub hp: i32,
    pub max_hp: i32,
    pub buttons_down: u32,
    /// Signed wheel notches for this input sequence; consumers must not replay it.
    pub wheel_delta: i32,
    /// Client-area normalized coordinates, origin at top left.
    pub cursor_xy: [f32; 2],
    /// Increment when input changes or a wheel pulse is published, not for every repeated pose.
    pub input_sequence: u64,
    /// 0 first person, 1 third person rear, 2 third person front.
    pub view_mode: u32,
    pub time_seconds: f32,
    /// Minecraft degrees: atan2(-forward_x, forward_z).
    pub avatar_yaw: f32,
    pub movement_speed: f32,
    pub grounded: bool,
    pub map_id: u32,
    /// Current native currency, including a valid zero. Never Minecraft XP.
    pub runes: Option<u32>,
}

impl Default for Snapshot {
    fn default() -> Self {
        Self {
            flags: 0,
            camera_xyz: [0.0; 3],
            forward: [0.0, 0.0, 1.0],
            vertical_fov_degrees: 60.0,
            feet_xyz: [0.0; 3],
            hp: 0,
            max_hp: 1,
            buttons_down: 0,
            wheel_delta: 0,
            cursor_xy: [0.5; 2],
            input_sequence: 0,
            view_mode: 0,
            time_seconds: 0.0,
            avatar_yaw: 0.0,
            movement_speed: 0.0,
            grounded: false,
            map_id: 0,
            runes: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PoseError(pub &'static str);
impl fmt::Display for PoseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for PoseError {}

impl Snapshot {
    pub fn validated(mut self) -> Result<Self, PoseError> {
        if self.flags & !31 != 0 || self.buttons_down & !BUTTON_MASK != 0 {
            return Err(PoseError("unknown flags or buttons"));
        }
        if self.view_mode > 2
            || !self.avatar_yaw.is_finite()
            || self.avatar_yaw.abs() > 360.0
            || !self.movement_speed.is_finite()
            || !(0.0..=100.0).contains(&self.movement_speed)
            || !self.time_seconds.is_finite()
            || !(0.0..86400.0).contains(&self.time_seconds)
            || (self.flags & (ACTIVE | FOREGROUND) == ACTIVE | FOREGROUND
                && (self.view_mode == 0) != (self.flags & FIRST_PERSON != 0))
        {
            return Err(PoseError("invalid view, avatar, or clock"));
        }
        if self
            .camera_xyz
            .iter()
            .chain(self.feet_xyz.iter())
            .any(|v| !v.is_finite() || v.abs() > 30_000_000.0)
        {
            return Err(PoseError("invalid host position"));
        }
        let norm = self
            .forward
            .iter()
            .map(|v| f64::from(*v).powi(2))
            .sum::<f64>()
            .sqrt();
        if !norm.is_finite() || norm < 0.000001 || norm > 1_000_000.0 {
            return Err(PoseError("invalid camera direction"));
        }
        self.forward = self.forward.map(|v| (f64::from(v) / norm) as f32);
        if !self.vertical_fov_degrees.is_finite()
            || !(1.0..179.0).contains(&self.vertical_fov_degrees)
        {
            return Err(PoseError("invalid vertical field of view"));
        }
        if self.max_hp <= 0 || self.max_hp > 10_000_000 || self.hp < 0 || self.hp > self.max_hp {
            return Err(PoseError("invalid host health"));
        }
        if self
            .cursor_xy
            .iter()
            .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
            || self.wheel_delta.abs_diff(0) > 16
        {
            return Err(PoseError("invalid cursor or wheel delta"));
        }
        if self.flags & (ACTIVE | FOREGROUND) != ACTIVE | FOREGROUND {
            self.flags &= !ACTIVE;
            self.buttons_down = 0;
            self.wheel_delta = 0;
            self.runes = None;
        }
        Ok(self)
    }
}

fn encode(
    snapshot: Snapshot,
    sequence: u64,
    frame: u64,
    timestamp_ms: u64,
    pid: u32,
) -> Result<[u8; MAPPING_BYTES], PoseError> {
    let s = snapshot.validated()?;
    if sequence == 0 || sequence & 1 != 0 || frame == 0 || pid == 0 {
        return Err(PoseError("invalid publication identity"));
    }
    let mut bytes = [0u8; MAPPING_BYTES];
    macro_rules! put {
        ($offset:expr, $value:expr) => {{
            let value = $value.to_le_bytes();
            bytes[$offset..$offset + value.len()].copy_from_slice(&value);
        }};
    }
    put!(0, MAGIC);
    put!(4, VERSION);
    put!(8, sequence);
    put!(16, frame);
    put!(24, timestamp_ms);
    put!(32, pid);
    put!(36, s.flags);
    for i in 0..3 {
        put!(40 + i * 8, s.camera_xyz[i]);
        put!(64 + i * 4, s.forward[i]);
        put!(80 + i * 8, s.feet_xyz[i]);
    }
    put!(76, s.vertical_fov_degrees);
    put!(104, s.hp);
    put!(108, s.max_hp);
    put!(112, s.buttons_down);
    put!(116, s.wheel_delta);
    put!(120, s.cursor_xy[0]);
    put!(124, s.cursor_xy[1]);
    put!(128, s.input_sequence);
    put!(136, s.view_mode);
    put!(140, s.time_seconds);
    put!(144, s.avatar_yaw);
    put!(148, s.movement_speed);
    put!(152, u32::from(s.grounded));
    put!(156, s.map_id);
    put!(160, if s.runes.is_some() { RUNES_VALID } else { 0 });
    put!(164, s.runes.unwrap_or(0));
    Ok(bytes)
}

#[cfg(windows)]
mod windows {
    use super::*;
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
    }
    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }

    /// Single producer. A named guard handle prevents concurrent writers without tying ownership
    /// to a thread. Readers do not open the guard, so process exit permits a new publisher.
    pub struct HostPublisher {
        view: *mut u8,
        mapping: Handle,
        guard: Handle,
        pid: u32,
        sequence: u64,
        frame: u64,
    }
    // Only this producer writes; movement between threads is safe, and all publication requires &mut.
    unsafe impl Send for HostPublisher {}
    impl HostPublisher {
        pub fn open() -> io::Result<Self> {
            Self::open_named(MAPPING_NAME)
        }
        fn open_named(name: &str) -> io::Result<Self> {
            if name.encode_utf16().count() > 220 || name.contains('\0') {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "invalid mapping name",
                ));
            }
            unsafe {
                let guard_name = wide(&format!("{name}.Writer"));
                let guard = CreateMutexW(ptr::null(), 0, guard_name.as_ptr());
                let guard_error = GetLastError();
                if guard.is_null() {
                    return Err(io::Error::from_raw_os_error(guard_error as i32));
                }
                if guard_error == 183 {
                    CloseHandle(guard);
                    return Err(io::Error::new(
                        io::ErrorKind::AlreadyExists,
                        "a host pose publisher is already present",
                    ));
                }
                let mapping_name = wide(name);
                let mapping = CreateFileMappingW(
                    -1isize as Handle,
                    ptr::null(),
                    4,
                    0,
                    MAPPING_BYTES as u32,
                    mapping_name.as_ptr(),
                );
                if mapping.is_null() {
                    let error = io::Error::last_os_error();
                    CloseHandle(guard);
                    return Err(error);
                }
                let view = MapViewOfFile(mapping, 0x0002, 0, 0, MAPPING_BYTES).cast::<u8>();
                if view.is_null() {
                    let error = io::Error::last_os_error();
                    CloseHandle(mapping);
                    CloseHandle(guard);
                    return Err(error);
                }
                let sequence = (&*view.add(8).cast::<AtomicU64>()).load(Ordering::SeqCst) & !1;
                let mut result = Self {
                    view,
                    mapping,
                    guard,
                    pid: GetCurrentProcessId(),
                    sequence,
                    frame: 0,
                };
                result.inactive();
                Ok(result)
            }
        }
        pub fn publish(&mut self, snapshot: Snapshot) -> Result<(), PoseError> {
            let mut sequence = self.sequence.wrapping_add(2) & !1;
            if sequence == 0 {
                sequence = 2;
            }
            let frame = self
                .frame
                .checked_add(1)
                .ok_or(PoseError("host frame counter exhausted"))?;
            let bytes = encode(
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
                ptr::copy_nonoverlapping(bytes.as_ptr(), self.view, 8);
                ptr::copy_nonoverlapping(
                    bytes.as_ptr().add(16),
                    self.view.add(16),
                    MAPPING_BYTES - 16,
                );
                fence(Ordering::SeqCst);
                counter.store(sequence, Ordering::SeqCst);
            }
            self.sequence = sequence;
            self.frame = frame;
            Ok(())
        }
        /// Explicitly releases controls on focus loss, loading, online sessions, or any failed gate.
        pub fn inactive(&mut self) {
            let _ = self.publish(Snapshot::default());
        }
        /// Frame counter of the last successful publication (0 before the first).
        pub fn frame(&self) -> u64 {
            self.frame
        }
    }
    impl Drop for HostPublisher {
        fn drop(&mut self) {
            self.inactive();
            unsafe {
                UnmapViewOfFile(self.view.cast());
                CloseHandle(self.mapping);
                CloseHandle(self.guard);
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn mapping_has_one_writer_and_drop_publishes_inactive() {
            let name = format!(
                "Local\\EldenCraftHostTest-{}-{}",
                std::process::id(),
                unsafe { GetTickCount64() }
            );
            let mut publisher = HostPublisher::open_named(&name).unwrap();
            assert_eq!(
                HostPublisher::open_named(&name).err().unwrap().kind(),
                io::ErrorKind::AlreadyExists
            );
            publisher
                .publish(Snapshot {
                    flags: ACTIVE | FOREGROUND,
                    view_mode: 1,
                    buttons_down: BUTTON_ATTACK,
                    ..Snapshot::default()
                })
                .unwrap();
            let mapping_name = wide(&name);
            unsafe {
                let reader = CreateFileMappingW(
                    -1isize as Handle,
                    ptr::null(),
                    4,
                    0,
                    MAPPING_BYTES as u32,
                    mapping_name.as_ptr(),
                );
                assert!(!reader.is_null());
                let view = MapViewOfFile(reader, 4, 0, 0, MAPPING_BYTES).cast::<u8>();
                assert!(!view.is_null());
                let flags = ptr::read_unaligned(view.add(36).cast::<u32>());
                assert_eq!(flags, ACTIVE | FOREGROUND);
                drop(publisher);
                assert_eq!(ptr::read_unaligned(view.add(36).cast::<u32>()), 0);
                assert_eq!(ptr::read_unaligned(view.add(112).cast::<u32>()), 0);
                // A reader retaining the old mapping must not prevent a fresh producer.
                let replacement = HostPublisher::open_named(&name).unwrap();
                drop(replacement);
                UnmapViewOfFile(view.cast());
                CloseHandle(reader);
            }
        }
    }
}
#[cfg(windows)]
pub use windows::HostPublisher;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rune_extension_preserves_zero_full_u32_and_revokes_inactive_currency() {
        for count in [0, 123_456, u32::MAX] {
            let s = Snapshot {
                flags: ACTIVE | FOREGROUND | FIRST_PERSON,
                runes: Some(count),
                ..Snapshot::default()
            };
            let b = encode(s, 2, 3, 1000, 99).unwrap();
            assert_eq!(
                u32::from_le_bytes(b[160..164].try_into().unwrap()),
                RUNES_VALID
            );
            assert_eq!(u32::from_le_bytes(b[164..168].try_into().unwrap()), count);
            assert!(b[168..].iter().all(|v| *v == 0));
            assert_eq!(Snapshot { flags: 0, ..s }.validated().unwrap().runes, None);
        }
        let b = encode(Snapshot::default(), 2, 3, 1000, 99).unwrap();
        assert!(b[160..].iter().all(|v| *v == 0));
    }
    #[test]
    fn normalizes_direction_and_serializes_fixed_layout() {
        let s = Snapshot {
            flags: ACTIVE | FOREGROUND | FIRST_PERSON,
            forward: [0.0, 3.0, 4.0],
            hp: 400,
            max_hp: 1000,
            buttons_down: BUTTON_USE,
            input_sequence: 42,
            ..Snapshot::default()
        };
        let bytes = encode(s, 2, 3, 1000, 99).unwrap();
        assert_eq!(&bytes[0..4], b"ECHS");
        assert_eq!(u64::from_le_bytes(bytes[16..24].try_into().unwrap()), 3);
        assert_eq!(u32::from_le_bytes(bytes[32..36].try_into().unwrap()), 99);
        assert_eq!(f32::from_le_bytes(bytes[68..72].try_into().unwrap()), 0.6);
        assert_eq!(f32::from_le_bytes(bytes[72..76].try_into().unwrap()), 0.8);
        assert_eq!(i32::from_le_bytes(bytes[104..108].try_into().unwrap()), 400);
        assert_eq!(u64::from_le_bytes(bytes[128..136].try_into().unwrap()), 42);
        assert!(bytes[136..].iter().all(|b| *b == 0));
    }
    #[test]
    fn torrent_whistle_is_the_last_published_button() {
        let active = |buttons_down| {
            Snapshot {
                flags: ACTIVE | FOREGROUND | FIRST_PERSON,
                hp: 1,
                max_hp: 1,
                buttons_down,
                ..Snapshot::default()
            }
            .validated()
        };
        assert_eq!(
            active(BUTTON_TORRENT | BUTTON_SWAP_HANDS)
                .unwrap()
                .buttons_down,
            BUTTON_TORRENT | BUTTON_SWAP_HANDS
        );
        assert!(active(BUTTON_TORRENT << 1).is_err());
    }
    #[test]
    fn focus_loss_and_inactive_flags_release_all_input() {
        for flags in [0, ACTIVE, FOREGROUND, FIRST_PERSON] {
            let s = Snapshot {
                flags,
                buttons_down: BUTTON_ATTACK,
                wheel_delta: 1,
                ..Snapshot::default()
            }
            .validated()
            .unwrap();
            assert_eq!(s.flags & ACTIVE, 0);
            assert_eq!(s.buttons_down, 0);
            assert_eq!(s.wheel_delta, 0);
        }
    }
    #[test]
    fn view_clock_and_avatar_have_strict_v2_layout_and_consistency() {
        let s = Snapshot {
            flags: ACTIVE | FOREGROUND | TIME_VALID,
            view_mode: 2,
            time_seconds: 43200.0,
            avatar_yaw: -90.0,
            movement_speed: 3.5,
            grounded: true,
            map_id: 42,
            ..Snapshot::default()
        };
        let bytes = encode(s, 2, 3, 1000, 99).unwrap();
        assert_eq!(u32::from_le_bytes(bytes[4..8].try_into().unwrap()), 2);
        assert_eq!(u32::from_le_bytes(bytes[136..140].try_into().unwrap()), 2);
        assert_eq!(
            f32::from_le_bytes(bytes[140..144].try_into().unwrap()),
            43200.0
        );
        assert_eq!(
            f32::from_le_bytes(bytes[144..148].try_into().unwrap()),
            -90.0
        );
        assert_eq!(u32::from_le_bytes(bytes[152..156].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(bytes[156..160].try_into().unwrap()), 42);
        assert!(bytes[160..].iter().all(|b| *b == 0));
        for invalid in [
            Snapshot { view_mode: 3, ..s },
            Snapshot {
                flags: s.flags | FIRST_PERSON,
                ..s
            },
            Snapshot {
                time_seconds: 86400.0,
                ..s
            },
            Snapshot {
                movement_speed: f32::NAN,
                ..s
            },
        ] {
            assert!(invalid.validated().is_err());
        }
    }
    #[test]
    fn rejects_nonfinite_or_invalid_pose_input_and_identity() {
        for s in [
            Snapshot {
                camera_xyz: [f64::NAN, 0.0, 0.0],
                ..Snapshot::default()
            },
            Snapshot {
                forward: [0.0; 3],
                ..Snapshot::default()
            },
            Snapshot {
                forward: [f32::INFINITY, 0.0, 0.0],
                ..Snapshot::default()
            },
            Snapshot {
                vertical_fov_degrees: 180.0,
                ..Snapshot::default()
            },
            Snapshot {
                hp: 2,
                ..Snapshot::default()
            },
            Snapshot {
                buttons_down: 1 << 31,
                ..Snapshot::default()
            },
            Snapshot {
                cursor_xy: [-0.1, 0.0],
                ..Snapshot::default()
            },
            Snapshot {
                wheel_delta: i32::MIN,
                ..Snapshot::default()
            },
        ] {
            assert!(s.validated().is_err());
        }
        assert!(encode(Snapshot::default(), 1, 1, 1, 1).is_err());
        assert!(encode(Snapshot::default(), 2, 0, 1, 1).is_err());
        assert!(encode(Snapshot::default(), 2, 1, 1, 0).is_err());
    }
}
