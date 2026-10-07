//! Ordered Unicode/chat controls only while the offline foreground guest owns chat
//! or an already open text screen such as a sign editor.
//! Event kinds: 0 cancel, 1 open chat, 2 open command, 3 character, 4 editing
//! key, 5 attach to the text screen Minecraft already has open.
//! No SDK pointers or OS input injection. Lost focus, a missing GUI acknowledgement,
//! a changed map, or a stale reader cancels rather than replaying a partial command.
use std::{
    collections::VecDeque,
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
};
const BYTES: usize = 4160;
const CAPACITY: usize = 128;
const PERMIT_MS: u64 = 250;
const OPEN_MS: u64 = 1000;
const TEXT_SCREEN: u32 = 5;
#[derive(Clone, Copy)]
struct Event {
    sequence: u64,
    time: u64,
    kind: u32,
    code: u32,
    modifiers: u32,
}
struct State {
    map: u32,
    permit: u64,
    gui: bool,
    session: u64,
    opened: u64,
    confirmed: bool,
    next: u64,
    generation: u64,
    events: VecDeque<Event>,
    writer: Option<Writer>,
}
impl State {
    fn new() -> Self {
        Self {
            map: 0,
            permit: 0,
            gui: false,
            session: 0,
            opened: 0,
            confirmed: false,
            next: 0,
            generation: 0,
            events: VecDeque::new(),
            writer: None,
        }
    }
    fn cancel(&mut self) {
        self.opened = 0;
        self.confirmed = false;
        self.events.clear();
        self.next = 0;
    }
    fn update(&mut self, now: u64, allowed: bool, map: u32, gui: bool) {
        if !allowed || self.map != map || now >= self.permit {
            self.cancel();
        }
        self.map = map;
        self.gui = gui;
        self.permit = if allowed { now + PERMIT_MS } else { 0 };
        if self.opened != 0 {
            if gui {
                self.confirmed = true;
            } else if self.confirmed || now.saturating_sub(self.opened) > OPEN_MS {
                self.cancel();
            }
        }
    }
    fn event(&mut self, now: u64, kind: u32, code: u32, modifiers: u32) -> bool {
        if now >= self.permit || !valid_event(kind, code, modifiers) {
            return false;
        }
        if kind == 0 {
            self.cancel();
            return true;
        }
        if kind == 1 || kind == 2 || kind == TEXT_SCREEN {
            // Chat opens over gameplay; a text screen is already on screen.
            if self.opened != 0 || self.gui != (kind == TEXT_SCREEN) {
                return false;
            }
            self.session = self.session.wrapping_add(1).max(1);
            self.opened = now;
            self.next = 0;
            self.events.clear();
            self.confirmed = kind == TEXT_SCREEN;
        } else if self.opened == 0 {
            return false;
        }
        self.next += 1;
        if self.events.len() == CAPACITY {
            self.events.pop_front();
        }
        self.events.push_back(Event {
            sequence: self.next,
            time: now,
            kind,
            code,
            modifiers,
        });
        true
    }
    fn publish(&mut self, now: u64) {
        if self.writer.is_none() {
            self.writer = Writer::open();
        }
        let mut bytes = [0u8; BYTES];
        bytes[0..4].copy_from_slice(b"ECCH");
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[24..32].copy_from_slice(&now.to_le_bytes());
        bytes[32..36].copy_from_slice(&std::process::id().to_le_bytes());
        bytes[36..40].copy_from_slice(&self.map.to_le_bytes());
        bytes[40..48].copy_from_slice(&self.session.to_le_bytes());
        bytes[48..56].copy_from_slice(&self.events.front().map_or(0, |e| e.sequence).to_le_bytes());
        bytes[56..60].copy_from_slice(&(self.events.len() as u32).to_le_bytes());
        bytes[60..64].copy_from_slice(
            &u32::from(
                self.opened != 0
                    && now < self.permit
                    && self.generation == REVOKE.load(Ordering::Acquire),
            )
            .to_le_bytes(),
        );
        for (i, e) in self.events.iter().enumerate() {
            let o = 64 + i * 32;
            bytes[o..o + 8].copy_from_slice(&e.sequence.to_le_bytes());
            bytes[o + 8..o + 16].copy_from_slice(&e.time.to_le_bytes());
            bytes[o + 16..o + 20].copy_from_slice(&e.kind.to_le_bytes());
            bytes[o + 20..o + 24].copy_from_slice(&e.code.to_le_bytes());
            bytes[o + 24..o + 28].copy_from_slice(&e.modifiers.to_le_bytes());
        }
        if let Some(writer) = &mut self.writer {
            writer.publish(bytes);
        }
    }
}
fn valid_event(kind: u32, code: u32, modifiers: u32) -> bool {
    if modifiers & !15 != 0 {
        return false;
    }
    match kind {
        0..=2 | TEXT_SCREEN => code == 0 && modifiers == 0,
        3 => char::from_u32(code).is_some() && code >= 32 && code != 127,
        4 => matches!(code, 65 | 67 | 86 | 88 | 256..=269),
        _ => false,
    }
}
static STATE: Mutex<Option<State>> = Mutex::new(None);
static CHAT_DEADLINE: AtomicU64 = AtomicU64::new(0);
static REVOKE: AtomicU64 = AtomicU64::new(0);
static CHAT_GENERATION: AtomicU64 = AtomicU64::new(0);
fn refresh_gate(state: &State) {
    CHAT_GENERATION.store(state.generation, Ordering::Release);
    CHAT_DEADLINE.store(
        if state.opened != 0 { state.permit } else { 0 },
        Ordering::Release,
    );
}
pub fn active(now: u64) -> bool {
    now < CHAT_DEADLINE.load(Ordering::Acquire)
        && CHAT_GENERATION.load(Ordering::Acquire) == REVOKE.load(Ordering::Acquire)
}
pub fn update(now: u64, allowed: bool, map: u32, gui: bool) {
    if !allowed {
        REVOKE.fetch_add(1, Ordering::AcqRel);
        CHAT_DEADLINE.store(0, Ordering::Release);
    }
    if let Ok(mut slot) = STATE.try_lock() {
        let state = slot.get_or_insert_with(State::new);
        let generation = REVOKE.load(Ordering::Acquire);
        if state.generation != generation {
            state.cancel();
            state.generation = generation;
        }
        state.update(now, allowed, map, gui);
        refresh_gate(state);
        state.publish(now);
    } else if !allowed {
        CHAT_DEADLINE.store(0, Ordering::Release);
    }
}
/// Route typed text into an open guest text screen (a sign editor) while it wants it.
pub fn text_screen(now: u64, wanted: bool) {
    if wanted && !active(now) {
        event(now, TEXT_SCREEN, 0, 0);
    }
}
pub fn suspend(now: u64) {
    CHAT_DEADLINE.store(0, Ordering::Release);
    update(now, false, 0, false);
}
pub fn event(now: u64, kind: u32, code: u32, modifiers: u32) -> bool {
    let Ok(mut slot) = STATE.try_lock() else {
        return false;
    };
    let Some(state) = slot.as_mut() else {
        return false;
    };
    if state.generation != REVOKE.load(Ordering::Acquire) {
        state.cancel();
        state.permit = 0;
        refresh_gate(state);
        state.publish(now);
        return false;
    }
    if !state.event(now, kind, code, modifiers) {
        return false;
    }
    refresh_gate(state);
    state.publish(now);
    true
}
use std::{ffi::c_void, ptr, sync::atomic::fence};
struct Writer {
    mapping: *mut c_void,
    view: *mut u8,
    sequence: u64,
}
// Access is serialized by STATE. The view is our private writer mapping, not a game pointer.
unsafe impl Send for Writer {}
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateFileMappingW(
        file: *mut c_void,
        attributes: *const c_void,
        protect: u32,
        high: u32,
        low: u32,
        name: *const u16,
    ) -> *mut c_void;
    fn MapViewOfFile(
        mapping: *mut c_void,
        access: u32,
        high: u32,
        low: u32,
        bytes: usize,
    ) -> *mut c_void;
    fn UnmapViewOfFile(view: *const c_void) -> i32;
    fn CloseHandle(handle: *mut c_void) -> i32;
}
impl Writer {
    fn open() -> Option<Self> {
        unsafe {
            let name: Vec<u16> = "Local\\EldenCraftChat\0".encode_utf16().collect();
            let mapping =
                CreateFileMappingW(-1isize as _, ptr::null(), 4, 0, BYTES as u32, name.as_ptr());
            if mapping.is_null() {
                return None;
            }
            let view = MapViewOfFile(mapping, 2, 0, 0, BYTES).cast::<u8>();
            if view.is_null() {
                CloseHandle(mapping);
                return None;
            }
            Some(Self {
                mapping,
                view,
                sequence: 0,
            })
        }
    }
    fn publish(&mut self, mut bytes: [u8; BYTES]) {
        self.sequence = self.sequence.wrapping_add(2).max(2);
        bytes[8..16].copy_from_slice(&self.sequence.to_le_bytes());
        bytes[16..24].copy_from_slice(&(self.sequence / 2).to_le_bytes());
        unsafe {
            let lock = &*self.view.add(8).cast::<AtomicU64>();
            lock.store(self.sequence - 1, Ordering::SeqCst);
            fence(Ordering::SeqCst);
            ptr::copy_nonoverlapping(bytes.as_ptr(), self.view, 8);
            ptr::copy_nonoverlapping(bytes.as_ptr().add(16), self.view.add(16), BYTES - 16);
            fence(Ordering::SeqCst);
            lock.store(self.sequence, Ordering::SeqCst);
        }
    }
}
impl Drop for Writer {
    fn drop(&mut self) {
        unsafe {
            UnmapViewOfFile(self.view.cast());
            CloseHandle(self.mapping);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ordered_unicode_and_editing_keys() {
        let mut s = State::new();
        s.update(1000, true, 42, false);
        assert!(s.event(1001, 2, 0, 0));
        for c in ['ł', '漢', '😀'] {
            assert!(s.event(1002, 3, c as u32, 0));
        }
        assert!(s.event(1003, 4, 257, 0));
        assert_eq!(s.events.len(), 5);
        assert_eq!(s.events.back().unwrap().sequence, 5);
        assert!(!valid_event(3, 0xd800, 0));
        assert!(!valid_event(4, 999, 0));
    }
    #[test]
    fn focus_map_and_unacknowledged_open_cancel() {
        let mut s = State::new();
        s.update(1000, true, 42, false);
        s.event(1001, 1, 0, 0);
        s.update(1010, true, 42, true);
        assert!(s.confirmed);
        s.update(1020, true, 42, false);
        assert_eq!(s.opened, 0);
        s.event(1021, 1, 0, 0);
        s.update(1030, true, 43, false);
        assert_eq!(s.opened, 0);
        s.event(1031, 1, 0, 0);
        s.update(1040, false, 43, false);
        assert!(!s.event(1041, 4, 257, 0));
    }
    #[test]
    fn open_text_screen_takes_typing_until_it_closes() {
        let mut s = State::new();
        s.update(1000, true, 42, false);
        // Nothing to attach to without an open guest screen.
        assert!(!s.event(1001, TEXT_SCREEN, 0, 0));
        s.update(1010, true, 42, true);
        assert!(s.event(1011, TEXT_SCREEN, 0, 0));
        assert!(s.confirmed);
        assert!(s.event(1012, 3, 'h' as u32, 0));
        assert!(s.event(1013, 4, 257, 0));
        // Chat cannot open over it, and closing the screen ends the session.
        assert!(!s.event(1014, 1, 0, 0));
        s.update(1020, true, 42, false);
        assert_eq!(s.opened, 0);
        assert!(!s.event(1021, 3, 'h' as u32, 0));
    }
    #[test]
    fn expired_permit_never_replays_submit() {
        let mut s = State::new();
        s.update(1000, true, 1, false);
        s.event(1001, 1, 0, 0);
        assert!(!s.event(1250, 4, 257, 0));
        s.update(1300, true, 1, false);
        assert_eq!(s.opened, 0);
        assert!(!s.event(1301, 4, 257, 0));
    }
    #[test]
    fn bounded_history_exposes_gap_instead_of_reordering() {
        let mut s = State::new();
        s.update(1000, true, 1, false);
        s.event(1001, 1, 0, 0);
        for _ in 0..200 {
            s.event(1002, 3, 97, 0);
        }
        assert_eq!(s.events.len(), 128);
        assert_eq!(s.events.front().unwrap().sequence, 74);
        assert_eq!(s.events.back().unwrap().sequence, 201);
    }
}
