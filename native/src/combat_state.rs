//! Read-only ECCB v1 guest combat mailbox and replay-resistant pulse policy.
//! The producer reports accepted, fully charged vanilla swings, not mouse edges.
use crate::combat_pad::Intent;

pub const BYTES: usize = 256;
pub const MAGIC: u32 = 0x4243_4345; // ECCB
pub const ACTIVE: u32 = 1;
pub const MELEE_USABLE: u32 = 2;
pub const SHIELD_USING: u32 = 4;
pub const SHIELD_USABLE: u32 = 8;
pub const GUI_OPEN: u32 = 16;
pub const FRESH_MS: u64 = 500;
const PULSE_MS: u64 = 90;

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub sequence: u64,
    pub frame: u64,
    pub timestamp: u64,
    pub pid: u32,
    pub flags: u32,
    pub swing: u64,
    pub item: String,
    pub damage: i32,
    pub max_damage: i32,
    pub charge: f32,
    pub session: u64,
}
fn u32_at(b: &[u8], n: usize) -> u32 {
    u32::from_le_bytes(b[n..n + 4].try_into().unwrap())
}
fn u64_at(b: &[u8], n: usize) -> u64 {
    u64::from_le_bytes(b[n..n + 8].try_into().unwrap())
}
pub fn decode(b: &[u8], now: u64) -> Result<Snapshot, &'static str> {
    if b.len() != BYTES || u32_at(b, 0) != MAGIC || u32_at(b, 4) != 1 {
        return Err("combat mailbox header rejected");
    }
    let sequence = u64_at(b, 8);
    let frame = u64_at(b, 16);
    let timestamp = u64_at(b, 24);
    let pid = u32_at(b, 32);
    let flags = u32_at(b, 36);
    let length = u32_at(b, 48) as usize;
    if sequence == 0
        || sequence & 1 != 0
        || frame == 0
        || pid == 0
        || flags & !31 != 0
        || now < timestamp
        || now - timestamp > FRESH_MS
        || length == 0
        || length > 128
    {
        return Err("combat mailbox state stale or malformed");
    }
    let damage = u32_at(b, 52) as i32;
    let max_damage = u32_at(b, 56) as i32;
    let charge = f32::from_bits(u32_at(b, 60));
    let session = u64_at(b, 192);
    if session == 0
        || damage < 0
        || !(0..=1_000_000).contains(&max_damage)
        || damage > max_damage
        || !charge.is_finite()
        || !(0.0..=1.0).contains(&charge)
        || b[64 + length..192]
            .iter()
            .chain(b[200..].iter())
            .any(|v| *v != 0)
    {
        return Err("combat mailbox item metadata rejected");
    }
    let item =
        std::str::from_utf8(&b[64..64 + length]).map_err(|_| "combat item ID is not UTF-8")?;
    if !item.contains(':')
        || !item
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"_:/.-".contains(&c))
    {
        return Err("combat item ID rejected");
    }
    Ok(Snapshot {
        sequence,
        frame,
        timestamp,
        pid,
        flags,
        swing: u64_at(b, 40),
        item: item.to_owned(),
        damage,
        max_damage,
        charge,
        session,
    })
}

#[derive(Default)]
pub struct Policy {
    connection: Option<(u32, u64)>,
    last_frame: u64,
    last_swing: u64,
    pulse_until: u64,
    held_guard: bool,
    accepted_swing: u64,
    last_valid_timestamp: Option<u64>,
}
impl Policy {
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    pub fn accepted_swing(&self) -> u64 {
        self.accepted_swing
    }
    pub fn update(&mut self, now: u64, s: Option<&Snapshot>) -> Intent {
        // This policy runs only under the host driver's live passthrough permit.
        // Missing metadata must therefore block raw host combat, never restore
        // the physical mouse path around Minecraft's charge/item authority.
        let blocked = Intent {
            active: true,
            ..Intent::default()
        };
        let Some(s) = s else {
            self.pulse_until = 0; // A missed sample may not resume an old pulse.
            if self
                .last_valid_timestamp
                .is_none_or(|stamp| now < stamp || now - stamp > FRESH_MS)
            {
                self.reset();
            }
            // A seqlock copy can miss one publication while the real raised
            // shield remains held. Guard is sustained state, unlike a melee
            // pulse; retain it only until the last observation's freshness
            // expires. An explicit release or GUI sample clears it immediately.
            return Intent {
                guard: self.held_guard,
                ..blocked
            };
        };
        if s.flags & ACTIVE == 0
            || s.flags & GUI_OPEN != 0
            || now < s.timestamp
            || now - s.timestamp > FRESH_MS
        {
            self.reset();
            return blocked;
        }
        self.last_valid_timestamp = Some(s.timestamp);
        let shield = s.flags & (SHIELD_USING | SHIELD_USABLE) == (SHIELD_USING | SHIELD_USABLE);
        if self.connection != Some((s.pid, s.session))
            || s.frame < self.last_frame
            || s.swing < self.last_swing
        {
            self.connection = Some((s.pid, s.session));
            self.last_frame = s.frame;
            self.last_swing = s.swing;
            self.pulse_until = 0;
            self.held_guard = shield;
            self.accepted_swing = 0;
            return Intent {
                active: true,
                guard: shield,
                ..Intent::default()
            }; // Rebaseline attack events; a currently raised shield is not an event replay.
        }
        self.held_guard = shield;
        let melee = s.flags & MELEE_USABLE != 0 && (s.max_damage == 0 || s.damage < s.max_damage);
        if s.frame > self.last_frame {
            if s.swing > self.last_swing && melee && s.charge >= 1.0 && !shield {
                self.pulse_until = now.saturating_add(PULSE_MS);
                self.accepted_swing = s.swing;
            }
            self.last_swing = s.swing;
            self.last_frame = s.frame;
        }
        if !melee || shield {
            self.pulse_until = 0;
        }
        let attack = now < self.pulse_until;
        Intent {
            active: true,
            attack,
            guard: !attack && shield,
        }
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
    }
    unsafe impl Send for Reader {} // poll(&mut self) is the sole reader; no references escape.
    impl Default for Reader {
        fn default() -> Self {
            Self::new()
        }
    }
    impl Reader {
        pub fn new() -> Self {
            Self {
                name: "Local\\EldenCraftCombat"
                    .encode_utf16()
                    .chain(Some(0))
                    .collect(),
                view: ptr::null(),
                mapping: ptr::null_mut(),
                process: ptr::null_mut(),
                pid: 0,
                next_open: 0,
            }
        }
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
        pub fn poll(&mut self, now: u64) -> Option<Snapshot> {
            if self.view.is_null() {
                if now < self.next_open {
                    return None;
                }
                self.next_open = now.saturating_add(1000);
                unsafe {
                    self.mapping = OpenFileMappingW(4, 0, self.name.as_ptr());
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
                let mut bytes = [0u8; BYTES];
                unsafe {
                    ptr::copy_nonoverlapping(self.view, bytes.as_mut_ptr(), BYTES);
                }
                fence(Ordering::SeqCst);
                let after = unsafe { ptr::read_volatile(self.view.add(8).cast::<u64>()) };
                if before != after {
                    continue;
                }
                // The producer can publish between entry and this CPU copy.
                // Compare its stamp with a clock sample taken after the copy.
                let Ok(s) = decode(&bytes, unsafe { GetTickCount64() }) else {
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
                if self.process.is_null() || unsafe { WaitForSingleObject(self.process, 0) } != 258
                {
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
}
#[cfg(windows)]
pub use windows::Reader;

#[cfg(test)]
mod tests {
    use super::*;
    fn state() -> Snapshot {
        Snapshot {
            sequence: 2,
            frame: 1,
            timestamp: 1000,
            pid: 42,
            flags: ACTIVE | MELEE_USABLE,
            swing: 8,
            item: "minecraft:iron_sword".into(),
            damage: 0,
            max_damage: 250,
            charge: 1.0,
            session: 77,
        }
    }
    #[test]
    fn old_swings_do_not_replay_but_a_currently_raised_shield_survives_connection() {
        let mut p = Policy::default();
        let mut s = state();
        s.flags |= SHIELD_USING | SHIELD_USABLE;
        assert_eq!(
            p.update(1000, Some(&s)),
            Intent {
                active: true,
                guard: true,
                ..Intent::default()
            }
        );
        s.frame += 1;
        assert!(p.update(1010, Some(&s)).guard);
        s.flags &= !SHIELD_USING;
        s.frame += 1;
        p.update(1020, Some(&s));
        s.flags |= SHIELD_USING;
        s.frame += 1;
        assert!(p.update(1030, Some(&s)).guard);
    }
    #[test]
    fn accepts_new_full_charge_event_once_with_bounded_pulse() {
        let mut p = Policy::default();
        let mut s = state();
        p.update(1000, Some(&s));
        s.frame += 1;
        s.swing += 1;
        assert!(p.update(1010, Some(&s)).attack);
        assert!(p.update(1099, Some(&s)).attack);
        assert!(!p.update(1100, Some(&s)).attack);
        assert_eq!(p.accepted_swing(), 9);
    }
    #[test]
    fn partial_charge_broken_item_gui_stale_and_reconnect_are_not_attacks() {
        let mut p = Policy::default();
        let mut s = state();
        p.update(1000, Some(&s));
        s.frame += 1;
        s.swing += 1;
        s.charge = 0.5;
        assert!(!p.update(1010, Some(&s)).attack);
        s.frame += 1;
        s.swing += 1;
        s.charge = 1.0;
        s.damage = s.max_damage;
        assert!(!p.update(1020, Some(&s)).attack);
        s.flags |= GUI_OPEN;
        assert_eq!(
            p.update(1030, Some(&s)),
            Intent {
                active: true,
                ..Intent::default()
            }
        );
        s.flags &= !GUI_OPEN;
        s.damage = 0;
        s.frame += 1;
        s.swing += 1;
        assert!(!p.update(1040, Some(&s)).attack);
        assert_eq!(
            p.update(1501, Some(&s)),
            Intent {
                active: true,
                ..Intent::default()
            }
        );
    }
    #[test]
    fn missing_metadata_blocks_raw_host_input_and_cancels_pulse_without_replay() {
        let mut p = Policy::default();
        let mut s = state();
        p.update(1000, Some(&s));
        s.frame += 1;
        s.swing += 1;
        assert!(p.update(1010, Some(&s)).attack);
        let blocked = Intent {
            active: true,
            ..Intent::default()
        };
        assert_eq!(p.update(1020, None), blocked);
        // The prior accepted swing is not resumed or re-emitted on recovery.
        assert_eq!(p.update(1030, Some(&s)), blocked);
        s.frame += 1;
        s.swing += 1;
        assert!(p.update(1040, Some(&s)).attack);
    }
    #[test]
    fn long_metadata_loss_rebaselines_swings_and_accepts_a_fresh_held_shield() {
        let mut p = Policy::default();
        let mut s = state();
        p.update(1000, Some(&s));
        let blocked = Intent {
            active: true,
            ..Intent::default()
        };
        assert_eq!(p.update(1501, None), blocked);
        s.timestamp = 1510;
        s.frame += 1;
        s.swing += 1;
        s.flags |= SHIELD_USING | SHIELD_USABLE;
        let recovered = p.update(1510, Some(&s));
        assert!(!recovered.attack);
        assert!(recovered.guard);
        s.frame += 1;
        assert!(p.update(1520, Some(&s)).guard);
    }
    #[test]
    fn short_guard_sample_gaps_do_not_open_the_shield_but_stale_release_and_gui_do() {
        let mut p = Policy::default();
        let mut s = state();
        s.flags |= SHIELD_USING | SHIELD_USABLE;
        assert!(p.update(1000, Some(&s)).guard);
        for now in [1001, 1016, 1050, 1250, 1500] {
            let retained = p.update(now, None);
            assert!(retained.active && retained.guard);
            assert!(!retained.attack);
        }
        assert!(!p.update(1501, None).guard);
        s.timestamp = 1510;
        assert!(p.update(1510, Some(&s)).guard);
        s.flags &= !SHIELD_USING;
        s.frame += 1;
        assert!(!p.update(1520, Some(&s)).guard);
        assert!(!p.update(1521, None).guard);
        s.flags |= SHIELD_USING;
        s.frame += 1;
        assert!(p.update(1530, Some(&s)).guard);
        s.flags |= GUI_OPEN;
        assert!(!p.update(1540, Some(&s)).guard);
        assert!(!p.update(1541, None).guard);
        s.flags &= !GUI_OPEN;
        s.session += 1;
        assert!(p.update(1550, Some(&s)).guard);
        assert!(!p.update(1509, None).guard); // A future-dated retained sample is not trusted.
    }
    #[test]
    fn malformed_wire_is_rejected_before_fields_are_used() {
        assert!(decode(&[0; 10], 1000).is_err());
        let mut b = [0u8; BYTES];
        for (o, v) in [
            (0, MAGIC),
            (4, 1),
            (32, 42),
            (36, ACTIVE),
            (48, 20),
            (56, 250),
            (60, 1.0f32.to_bits()),
        ] {
            b[o..o + 4].copy_from_slice(&v.to_le_bytes());
        }
        for (o, v) in [(8, 2u64), (16, 1), (24, 1000), (192, 77)] {
            b[o..o + 8].copy_from_slice(&v.to_le_bytes());
        }
        b[64..84].copy_from_slice(b"minecraft:iron_sword");
        assert!(decode(&b, 1000).is_ok());
        assert!(decode(&b, 1501).is_err());
        b[200] = 1;
        assert!(decode(&b, 1000).is_err());
    }
}
