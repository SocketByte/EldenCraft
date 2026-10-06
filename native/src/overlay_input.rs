//! Nonblocking render-thread to game-thread input mailbox. No SDK pointers cross threads.
use std::sync::Mutex;

const BUTTONS: usize = 23; // ECHS v2 order; bit 22 is the Torrent whistle.
const BUTTON_MASK: u32 = (1 << BUTTONS) - 1;
const FRESH_MS: u64 = 250;
const MIN_PRESS_MS: u64 = 80; // Minecraft ticks at 20 Hz; preserve a short desktop click.

#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub buttons: u32,
    pub cursor: [f32; 2],
    pub wheel: i32,
}
struct State {
    timestamp: u64,
    buttons: u32,
    cursor: [f32; 2],
    wheel: i32,
    held_until: [u64; BUTTONS],
}
impl State {
    const fn new() -> Self {
        Self {
            timestamp: 0,
            buttons: 0,
            cursor: [0.5; 2],
            wheel: 0,
            held_until: [0; BUTTONS],
        }
    }
    fn publish(&mut self, now: u64, buttons: u32, cursor: [f32; 2], wheel: i32) {
        if buttons & !BUTTON_MASK != 0
            || cursor
                .iter()
                .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
            || !(-16..=16).contains(&wheel)
        {
            return;
        }
        if now.saturating_sub(self.timestamp) > FRESH_MS {
            *self = Self::new();
        }
        for bit in 0..BUTTONS {
            // Movement must release immediately; only discrete guest actions need
            // a minimum pulse long enough to survive the Minecraft tick interval.
            if !(13..=19).contains(&bit)
                && buttons & (1 << bit) != 0
                && self.buttons & (1 << bit) == 0
            {
                self.held_until[bit] = now.saturating_add(MIN_PRESS_MS);
            }
        }
        self.timestamp = now;
        self.buttons = buttons;
        self.cursor = cursor;
        self.wheel = (self.wheel + wheel).clamp(-16, 16);
    }
    fn buttons_at(&self, now: u64) -> Option<u32> {
        if self.timestamp == 0 || now < self.timestamp || now - self.timestamp > FRESH_MS {
            return None;
        }
        let mut buttons = self.buttons;
        for bit in 0..BUTTONS {
            if now < self.held_until[bit] {
                buttons |= 1 << bit;
            }
        }
        Some(buttons)
    }
    fn take(&mut self, now: u64) -> Option<Sample> {
        let buttons = self.buttons_at(now)?;
        Some(Sample {
            buttons,
            cursor: self.cursor,
            wheel: std::mem::take(&mut self.wheel),
        })
    }
}
static INPUT: Mutex<State> = Mutex::new(State::new());
pub fn publish(now: u64, buttons: u32, cursor: [f32; 2], wheel: i32) {
    if let Ok(mut state) = INPUT.try_lock() {
        state.publish(now, buttons, cursor, wheel);
    }
}
pub fn take(now: u64) -> Option<Sample> {
    INPUT.try_lock().ok()?.take(now)
}
/// Read controls at the native physics boundary without consuming GUI wheel or
/// other render-to-guest events. Uses the same bounded freshness as `take`.
pub fn peek_buttons(now: u64) -> Option<u32> {
    INPUT.try_lock().ok()?.buttons_at(now)
}
pub fn clear() {
    if let Ok(mut state) = INPUT.try_lock() {
        *state = State::new();
    }
    clear_look();
    clear_menu();
}

/// Menu controls use a separate ABI so older ECHS readers keep their exact
/// 22-button layout. The render thread supplies held controls and the mailbox
/// preserves short presses until the game task can consume them once. Cursor,
/// mouse buttons and wheel remain on ECHS and its existing screen input path.
pub const MENU_CONFIRM: u32 = 1;
pub const MENU_CANCEL: u32 = 1 << 1;
pub const MENU_UP: u32 = 1 << 2;
pub const MENU_DOWN: u32 = 1 << 3;
pub const MENU_LEFT: u32 = 1 << 4;
pub const MENU_RIGHT: u32 = 1 << 5;
pub const MENU_MAP: u32 = 1 << 6;
pub const MENU_TAB: u32 = 1 << 7;
pub const MENU_ZOOM_IN: u32 = 1 << 8;
pub const MENU_ZOOM_OUT: u32 = 1 << 9;
pub const MENU_SHIFT: u32 = 1 << 10;
pub const MENU_HOME: u32 = 1 << 11;
const MENU_MASK: u32 = MENU_CONFIRM
    | MENU_CANCEL
    | MENU_UP
    | MENU_DOWN
    | MENU_LEFT
    | MENU_RIGHT
    | MENU_MAP
    | MENU_TAB
    | MENU_ZOOM_IN
    | MENU_ZOOM_OUT
    | MENU_SHIFT
    | MENU_HOME;

#[derive(Clone, Copy, Debug)]
pub struct MenuSample {
    pub buttons: u32,
    /// Rising edges consumed once, independent of the Minecraft tick rate.
    pub pressed: u32,
}
struct MenuState {
    timestamp: u64,
    buttons: u32,
    pressed: u32,
    held_until: [u64; 12],
}
impl MenuState {
    const fn new() -> Self {
        Self {
            timestamp: 0,
            buttons: 0,
            pressed: 0,
            held_until: [0; 12],
        }
    }
    fn publish(&mut self, now: u64, buttons: u32, cursor: [f32; 2], wheel: i32) -> u32 {
        if now == 0
            || buttons & !MENU_MASK != 0
            || cursor
                .iter()
                .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
            || !(-16..=16).contains(&wheel)
        {
            return 0;
        }
        if now < self.timestamp || now - self.timestamp > FRESH_MS {
            *self = Self::new();
        }
        let pressed = buttons & !self.buttons;
        self.pressed |= pressed;
        for bit in 0..12 {
            if bit != 10 && pressed & (1 << bit) != 0 {
                self.held_until[bit] = now.saturating_add(MIN_PRESS_MS);
            }
        }
        self.timestamp = now;
        self.buttons = buttons;
        pressed
    }
    fn take(&mut self, now: u64) -> Option<MenuSample> {
        if self.timestamp == 0 || now < self.timestamp || now - self.timestamp > FRESH_MS {
            *self = Self::new();
            return None;
        }
        let mut buttons = self.buttons;
        for bit in 0..12 {
            if now < self.held_until[bit] {
                buttons |= 1 << bit;
            }
        }
        Some(MenuSample {
            buttons,
            pressed: std::mem::take(&mut self.pressed),
        })
    }
}
static MENU_INPUT: Mutex<MenuState> = Mutex::new(MenuState::new());
/// Return accepted rising edges so pending GUI capture cannot be armed by
/// malformed samples or continually extended by a held map key.
pub fn publish_menu(now: u64, buttons: u32, cursor: [f32; 2], wheel: i32) -> u32 {
    if let Ok(mut state) = MENU_INPUT.try_lock() {
        state.publish(now, buttons, cursor, wheel)
    } else {
        0
    }
}
pub fn take_menu(now: u64) -> Option<MenuSample> {
    MENU_INPUT.try_lock().ok()?.take(now)
}
pub fn clear_menu() {
    if let Ok(mut state) = MENU_INPUT.try_lock() {
        *state = MenuState::new();
    }
}

#[derive(Default)]
struct Look {
    timestamp: u64,
    delta: [f32; 2],
}
impl Look {
    fn publish(&mut self, now: u64, delta: [f32; 2]) {
        if now == 0 || delta.iter().any(|v| !v.is_finite() || v.abs() > 4096.) {
            return;
        }
        if now < self.timestamp || now - self.timestamp > 100 {
            self.delta = [0.; 2];
        }
        self.timestamp = now;
        self.delta = std::array::from_fn(|i| (self.delta[i] + delta[i]).clamp(-4096., 4096.));
    }
    fn take(&mut self, now: u64) -> Option<[f32; 2]> {
        if self.timestamp == 0 || now < self.timestamp || now - self.timestamp > 100 {
            *self = Self::default();
            return None;
        }
        Some(std::mem::replace(&mut self.delta, [0.; 2]))
    }
}
static LOOK: Mutex<Look> = Mutex::new(Look {
    timestamp: 0,
    delta: [0.; 2],
});
pub fn publish_look(now: u64, delta: [f32; 2]) {
    if let Ok(mut state) = LOOK.try_lock() {
        state.publish(now, delta);
    }
}
pub fn take_look(now: u64) -> Option<[f32; 2]> {
    LOOK.try_lock().ok()?.take(now)
}
pub fn clear_look() {
    if let Ok(mut state) = LOOK.try_lock() {
        *state = Look::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn short_click_survives_guest_tick_and_releases() {
        let mut state = State::new();
        state.publish(1000, 1, [0.2, 0.8], 0);
        state.publish(1010, 0, [0.2, 0.8], 0);
        assert_eq!(state.take(1050).unwrap().buttons, 1);
        assert_eq!(state.take(1080).unwrap().buttons, 0);
        assert_eq!(state.take(1080).unwrap().cursor, [0.2, 0.8]);
    }
    #[test]
    fn menu_short_presses_survive_guest_tick_with_single_native_edge() {
        let mut state = MenuState::new();
        state.publish(1000, MENU_CONFIRM | MENU_UP, [0.2, 0.8], 1);
        state.publish(1001, 0, [0.2, 0.8], 0);
        let first = state.take(1050).unwrap();
        assert_eq!(first.buttons, MENU_CONFIRM | MENU_UP);
        assert_eq!(first.pressed, MENU_CONFIRM | MENU_UP);
        let next = state.take(1051).unwrap();
        assert_eq!(next.pressed, 0);
        assert_eq!(state.take(1080).unwrap().buttons, 0);
        assert_eq!(state.publish(1100, MENU_MAP, [0.2, 0.8], 0), MENU_MAP);
        assert_eq!(state.take(1101).unwrap().pressed, MENU_MAP);
        assert_eq!(state.publish(1102, MENU_MAP, [0.2, 0.8], 0), 0);
        assert_eq!(state.take(1103).unwrap().pressed, 0);
    }
    #[test]
    fn menu_focus_loss_rejects_old_presses_and_invalid_input() {
        let mut state = MenuState::new();
        state.publish(1000, MENU_CONFIRM, [0.2; 2], 1);
        assert_eq!(state.publish(1100, MENU_MASK + 1, [0.5; 2], 0), 0);
        assert_eq!(state.publish(1100, MENU_MAP, [f32::NAN, 0.5], 0), 0);
        assert_eq!(state.take(1100).unwrap().pressed, MENU_CONFIRM);
        assert!(state.take(1251).is_none());
        state.publish(1300, 0, [0.5; 2], 0);
        assert_eq!(state.take(1300).unwrap().pressed, 0);
    }
    #[test]
    fn movement_release_has_no_artificial_hold() {
        let mut state = State::new();
        state.publish(1000, 0x7f << 13, [0.5; 2], 0);
        assert_eq!(state.take(1001).unwrap().buttons, 0x7f << 13);
        state.publish(1002, 0, [0.5; 2], 0);
        assert_eq!(state.take(1003).unwrap().buttons, 0);
    }
    #[test]
    fn wheel_consumed_once_and_stale_input_rejected() {
        let mut state = State::new();
        state.publish(1000, 2, [0.5; 2], 2);
        assert_eq!(state.buttons_at(1020), Some(2));
        assert_eq!(state.wheel, 2);
        assert_eq!(state.take(1020).unwrap().wheel, 2);
        assert_eq!(state.take(1030).unwrap().wheel, 0);
        assert!(state.take(999).is_none());
        assert!(state.take(1251).is_none());
    }
    #[test]
    fn malformed_input_does_not_refresh_or_replace_good_state() {
        let mut state = State::new();
        state.publish(1000, 0, [0.2; 2], 0);
        state.publish(1100, 1 << 23, [0.5; 2], 0);
        state.publish(1100, 1, [f32::NAN, 0.0], 0);
        state.publish(1100, 1, [0.5; 2], 17);
        assert_eq!(state.take(1100).unwrap().cursor, [0.2; 2]);
        assert!(state.take(1251).is_none());
    }
    #[test]
    fn look_is_accumulated_once_and_never_replayed_after_a_pause() {
        let mut s = Look::default();
        s.publish(1000, [2., -3.]);
        s.publish(1001, [4., 1.]);
        assert_eq!(s.take(1002), Some([6., -2.]));
        assert_eq!(s.take(1002), Some([0., 0.]));
        s.publish(1003, [50., 50.]);
        assert_eq!(s.take(1104), None);
        s.publish(1105, [1., 2.]);
        assert_eq!(s.take(1105), Some([1., 2.]));
        s.publish(1106, [f32::NAN, 0.]);
        assert_eq!(s.take(1106), Some([0., 0.]));
        s.publish(1107, [4097., 0.]);
        assert_eq!(s.take(1107), Some([0., 0.]));
        assert_eq!(s.take(1000), None);
    }
}
