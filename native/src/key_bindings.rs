//! Minecraft's own key bindings for every key the host samples. Minecraft
//! publishes `keybinds.json` in the shared campaign directory whenever a
//! binding changes; until then, and for any action it leaves out, the built-in
//! keys apply. Escape stays fixed, as it is in vanilla.
//!
//! Slots 0..23 follow the ECHS button order; the last two are the native
//! interaction and view hotkeys. Values are Windows virtual-key codes, 0 for an
//! unbound action. The compositor reads the same table through
//! `eldencraft_key_bindings`, so both input paths always agree.

use serde::Deserialize;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::SystemTime;

pub const GAMEPLAY: usize = 23;
pub const INTERACT: usize = 23;
pub const PERSPECTIVE: usize = 24;
pub const COUNT: usize = 25;

/// The keys before Minecraft publishes any: exact ECHS order, then R and F5.
pub const DEFAULTS: [u32; COUNT] = [
    0x01, 0x02, 0x45, 0x1B, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x20, 0x10, 0x11,
    0x57, 0x53, 0x41, 0x44, 0x51, 0x46, 0x59, 0x52, 0x74,
];

/// Minecraft's action names per slot; Escape (slot 3) is never rebound.
const ACTIONS: [&str; COUNT] = [
    "attack",
    "use",
    "inventory",
    "",
    "hotbar_1",
    "hotbar_2",
    "hotbar_3",
    "hotbar_4",
    "hotbar_5",
    "hotbar_6",
    "hotbar_7",
    "hotbar_8",
    "hotbar_9",
    "jump",
    "sneak",
    "sprint",
    "forward",
    "backward",
    "left",
    "right",
    "drop",
    "swap_hands",
    "torrent",
    "interact",
    "perspective",
];

/// Bit 16 marks a published binding, so zero still means "built-in key".
const PUBLISHED: u32 = 1 << 16;
static CURRENT: [AtomicU32; COUNT] = [const { AtomicU32::new(0) }; COUNT];

/// The keys in force now. Atomics only: safe from the compositor thread.
pub fn keys() -> [u32; COUNT] {
    std::array::from_fn(|i| match CURRENT[i].load(Ordering::Acquire) {
        0 => DEFAULTS[i],
        value => value & 0xFF,
    })
}

#[derive(Deserialize)]
struct File {
    version: u32,
    keys: HashMap<String, Binding>,
}

#[derive(Deserialize)]
struct Binding {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    code: i32,
}

/// The virtual key for one binding, or None to keep the built-in key.
/// Keys are SDL scancodes (USB HID usages); `layout` turns a PC set-1
/// scancode into the active layout's virtual key (0: unknown).
fn resolve(binding: &Binding, layout: &dyn Fn(u32) -> u32) -> Option<u32> {
    Some(match binding.kind.as_str() {
        "none" => 0,
        // SDL numbers mouse buttons from 1: left, middle, right, X1, X2.
        "mouse" => match binding.code {
            1 => 0x01,
            2 => 0x04,
            3 => 0x02,
            4 => 0x05,
            5 => 0x06,
            _ => 0,
        },
        // A character key reads whatever the player's layout puts at that
        // position; every other key means the same in every layout.
        "key" => match set1(binding.code) {
            Some(scancode) if layout(scancode) != 0 => layout(scancode),
            _ => usage_key(binding.code),
        },
        _ => return None,
    })
}

/// PC set-1 scancode of a character key's HID usage.
fn set1(usage: i32) -> Option<u32> {
    const LETTERS: [u32; 26] = [
        0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18,
        0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    ];
    Some(match usage {
        4..=29 => LETTERS[(usage - 4) as usize],
        30..=39 => 0x02 + (usage - 30) as u32,
        45 => 0x0C,
        46 => 0x0D,
        47 => 0x1A,
        48 => 0x1B,
        49 | 50 => 0x2B,
        51 => 0x27,
        52 => 0x28,
        53 => 0x29,
        54 => 0x33,
        55 => 0x34,
        56 => 0x35,
        100 => 0x56,
        _ => return None,
    })
}

/// HID usage to Windows virtual key, with US meanings for character keys.
/// Either side's Shift, Ctrl and Alt read as the generic key, as the host has
/// always read them.
fn usage_key(usage: i32) -> u32 {
    match usage {
        4..=29 => 0x41 + (usage - 4) as u32,
        30..=38 => 0x31 + (usage - 30) as u32,
        39 => 0x30,
        40 | 88 => 0x0D,
        41 => 0x1B,
        42 => 0x08,
        43 => 0x09,
        44 => 0x20,
        45 => 0xBD,
        46 => 0xBB,
        47 => 0xDB,
        48 => 0xDD,
        49 | 50 => 0xDC,
        51 => 0xBA,
        52 => 0xDE,
        53 => 0xC0,
        54 => 0xBC,
        55 => 0xBE,
        56 => 0xBF,
        57 => 0x14,
        58..=69 => 0x70 + (usage - 58) as u32,
        70 => 0x2C,
        71 => 0x91,
        72 => 0x13,
        73 => 0x2D,
        74 => 0x24,
        75 => 0x21,
        76 => 0x2E,
        77 => 0x23,
        78 => 0x22,
        79 => 0x27,
        80 => 0x25,
        81 => 0x28,
        82 => 0x26,
        83 => 0x90,
        84 => 0x6F,
        85 => 0x6A,
        86 => 0x6D,
        87 => 0x6B,
        89..=97 => 0x61 + (usage - 89) as u32,
        98 => 0x60,
        99 => 0x6E,
        100 => 0xE2,
        101 => 0x5D,
        104..=115 => 0x7C + (usage - 104) as u32,
        224 | 228 => 0x11,
        225 | 229 => 0x10,
        226 | 230 => 0x12,
        227 => 0x5B,
        231 => 0x5C,
        _ => 0,
    }
}

/// Apply a publication; returns the resulting table, or None if it is not one.
fn apply(text: &str, layout: &dyn Fn(u32) -> u32) -> Option<[u32; COUNT]> {
    let file: File = serde_json::from_str(text).ok()?;
    if file.version != 1 {
        return None;
    }
    for (slot, action) in ACTIONS.iter().enumerate() {
        let value = file
            .keys
            .get(*action)
            .filter(|_| !action.is_empty())
            .and_then(|binding| resolve(binding, layout))
            .map_or(0, |vk| PUBLISHED | vk);
        CURRENT[slot].store(value, Ordering::Release);
    }
    Some(keys())
}

#[cfg(windows)]
fn active_layout(scancode: u32) -> u32 {
    #[link(name = "user32")]
    unsafe extern "system" {
        fn MapVirtualKeyW(code: u32, map_type: u32) -> u32;
    }
    const MAPVK_VSC_TO_VK: u32 = 1;
    unsafe { MapVirtualKeyW(scancode, MAPVK_VSC_TO_VK) }
}
#[cfg(not(windows))]
fn active_layout(_: u32) -> u32 {
    0
}

/// The same directory as the interaction and campaign files.
fn path() -> PathBuf {
    std::env::var_os("ELDENCRAFT_CAMPAIGN_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            std::env::var_os("ELDENCRAFT_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("eldencraft-data"))
                .join("campaign")
        })
        .join("keybinds.json")
}

/// Rereads the file after it changes, at most twice a second.
#[derive(Default)]
pub struct Watcher {
    next_ms: u64,
    seen: Option<(SystemTime, u64)>,
}

impl Watcher {
    /// Game thread. Returns a log line when a new publication took effect.
    pub fn poll(&mut self, now_ms: u64) -> Option<String> {
        if now_ms < self.next_ms {
            return None;
        }
        self.next_ms = now_ms + 500;
        let path = path();
        let metadata = std::fs::metadata(&path).ok()?;
        let stamp = (metadata.modified().ok()?, metadata.len());
        if self.seen == Some(stamp) || stamp.1 > 16 * 1024 {
            return None;
        }
        self.seen = Some(stamp);
        let text = std::fs::read_to_string(&path).ok()?;
        let keys = apply(&text, &active_layout)?;
        Some(format!(
            "Minecraft key bindings applied from {}: {keys:02x?}",
            path.display()
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, MutexGuard};

    /// The table is process-wide; tests take turns with it.
    fn reset() -> MutexGuard<'static, ()> {
        static TABLE: Mutex<()> = Mutex::new(());
        let guard = TABLE.lock().unwrap_or_else(|e| e.into_inner());
        for slot in &CURRENT {
            slot.store(0, Ordering::Release);
        }
        guard
    }

    #[test]
    fn publication_rebinds_and_omissions_keep_defaults() {
        let _table = reset();
        assert_eq!(keys(), DEFAULTS);
        let text = r#"{"version":1,"keys":{
            "forward":{"type":"key","code":82},
            "attack":{"type":"mouse","code":2},
            "use":{"type":"mouse","code":3},
            "inventory":{"type":"none"},
            "sneak":{"type":"key","code":225},
            "torrent":{"type":"key","code":10},
            "perspective":{"type":"key","code":62}}}"#;
        let keys = apply(text, &|_| 0).unwrap();
        assert_eq!(keys[16], 0x26, "Up arrow");
        assert_eq!(keys[0], 0x04, "middle mouse");
        assert_eq!(keys[1], 0x02, "right mouse");
        assert_eq!(keys[2], 0, "unbound never fires");
        assert_eq!(keys[14], 0x10, "left shift reads either Shift");
        assert_eq!(keys[22], 0x47, "G with US meaning when the layout has none");
        assert_eq!(keys[PERSPECTIVE], 0x74, "F5");
        assert_eq!(keys[17], DEFAULTS[17], "an omitted action keeps its key");
        assert_eq!(keys[3], 0x1B, "Escape is fixed");
    }

    #[test]
    fn character_keys_follow_the_layout_and_others_do_not() {
        let _table = reset();
        let text = r#"{"version":1,"keys":{
            "forward":{"type":"key","code":26},
            "jump":{"type":"key","code":44},
            "left":{"type":"key","code":4}}}"#;
        // AZERTY: the key in the US W position reads Z, and US A reads Q.
        let azerty = |scancode| match scancode {
            0x11 => 0x5A,
            0x1E => 0x51,
            _ => 0,
        };
        let keys = apply(text, &azerty).unwrap();
        assert_eq!(keys[16], 0x5A);
        assert_eq!(keys[18], 0x51);
        assert_eq!(keys[13], 0x20, "Space never consults the layout");
    }

    #[test]
    fn minecraft_defaults_reproduce_the_built_in_keys() {
        let _table = reset();
        // Vanilla's default bindings, as Minecraft 26.3 publishes them on QWERTY.
        let text = r#"{"version":1,"keys":{
            "attack":{"type":"mouse","code":1},"use":{"type":"mouse","code":3},
            "inventory":{"type":"key","code":8},
            "hotbar_1":{"type":"key","code":30},"hotbar_2":{"type":"key","code":31},
            "hotbar_3":{"type":"key","code":32},"hotbar_4":{"type":"key","code":33},
            "hotbar_5":{"type":"key","code":34},"hotbar_6":{"type":"key","code":35},
            "hotbar_7":{"type":"key","code":36},"hotbar_8":{"type":"key","code":37},
            "hotbar_9":{"type":"key","code":38},
            "jump":{"type":"key","code":44},"sneak":{"type":"key","code":225},
            "sprint":{"type":"key","code":224},
            "forward":{"type":"key","code":26},"backward":{"type":"key","code":22},
            "left":{"type":"key","code":4},"right":{"type":"key","code":7},
            "drop":{"type":"key","code":20},"swap_hands":{"type":"key","code":9},
            "torrent":{"type":"key","code":28},"interact":{"type":"key","code":21},
            "perspective":{"type":"key","code":62}}}"#;
        assert_eq!(apply(text, &|_| 0).unwrap(), DEFAULTS);
    }
    #[test]
    fn foreign_publications_change_nothing() {
        let _table = reset();
        assert!(apply(r#"{"version":2,"keys":{}}"#, &|_| 0).is_none());
        assert!(apply("not json", &|_| 0).is_none());
        assert_eq!(keys(), DEFAULTS);
    }
}
