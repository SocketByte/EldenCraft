//! Hits the game's damage processor applies to enemies that did not come from the bridge.
//!
//! The bridge owns every hit the player deals, and its own calls are marked here. Anything else
//! that reaches an enemy through the processor (an attack the player never asked for, an enemy's
//! own attack landing on itself, a scripted hit) is written to the log with the raw contents of
//! the request, so its attacker and attack id can be read straight from it.
//!
//! One kind is stopped: a hit from the player's own equipped weapon. Minecraft owns the player's
//! melee, and the game's jump was found to land such a hit on whatever stood next to the player,
//! each worth a little damage, ten stance and a flinch. Its damage, poise and reaction fields are
//! zeroed before the processor sees them. Everything else is only logged, and logging is bounded.
use eldenring::cs::{ChrAsmHand, PlayerIns};
use ilhook::x64::Registers;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};

const REQUEST_BYTES: usize = 0x270;
/// Request offsets, read off the game's own hits: the damage classification (a float), the poise
/// damage (a float, stored twice), the damage level and type bytes, the weapon the hit was made
/// with, and the final HP loss.
const OFFSET_CLASS: usize = 0x00;
pub const OFFSET_POISE: [usize; 2] = [0x20, 0x100];
pub const OFFSET_LEVEL: usize = 0x24;
const OFFSET_WEAPON: usize = 0x144;
const OFFSET_LOSS: usize = 0x228;
/// Full dumps are for the first few hits only; after that one compact line a second.
const MAX_DUMPS: u32 = 40;
const MAX_WORDS: usize = 72;

/// Set while the bridge's own sink is inside the processor.
static OWN_CALL: AtomicBool = AtomicBool::new(false);
/// Set each frame by the enemy watcher while the stance system is on.
static ENABLED: AtomicBool = AtomicBool::new(false);
/// Whether the player's own weapon hits on enemies are stopped, not just logged.
static STOP_STRAYS: AtomicBool = AtomicBool::new(false);
static DUMPS: AtomicU32 = AtomicU32::new(0);
static LAST_COMPACT_SECOND: AtomicU64 = AtomicU64::new(0);

pub fn set_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Release);
    STOP_STRAYS.store(enabled, Ordering::Release);
}

/// Marks the bridge's own processor call for as long as it lives.
pub struct OwnCall;

impl OwnCall {
    pub fn begin() -> Self {
        OWN_CALL.store(true, Ordering::Release);
        OwnCall
    }
}

impl Drop for OwnCall {
    fn drop(&mut self) {
        OWN_CALL.store(false, Ordering::Release);
    }
}

/// How the attacker relates to the victim, from the pointers the request carries.
fn relation(source: usize, target: usize, local: usize) -> &'static str {
    if source == 0 {
        "no source"
    } else if source == target {
        "the victim itself"
    } else if source == local {
        "the local player"
    } else {
        "another character"
    }
}

/// Whether the request was made with a weapon the player has equipped.
fn from_equipped_weapon(weapon: i32, equipped: [i32; 2]) -> bool {
    weapon > 0 && equipped.contains(&weapon)
}

/// Takes the damage, poise and reaction out of a request, leaving a hit that changes nothing.
fn neutralize(request: &mut [u8]) {
    request[OFFSET_CLASS..OFFSET_CLASS + 4].fill(0);
    for offset in OFFSET_POISE {
        request[offset..offset + 4].fill(0);
    }
    request[OFFSET_LEVEL..OFFSET_LEVEL + 2].fill(0);
    request[OFFSET_LOSS..OFFSET_LOSS + 4].fill(0);
}

/// The non-zero 32-bit words of a request, as `offset:value` pairs.
fn words(request: &[u8]) -> Vec<String> {
    request
        .chunks_exact(4)
        .enumerate()
        .filter_map(|(index, word)| {
            let value = u32::from_le_bytes(word.try_into().ok()?);
            (value != 0).then(|| format!("{:#x}:{}", index * 4, value as i32))
        })
        .take(MAX_WORDS)
        .collect()
}

/// The param ids of the weapons the player has in hand, or zeros when the equipment is unreadable.
unsafe fn equipped_weapons() -> [i32; 2] {
    let Ok(player) = (unsafe { PlayerIns::local_player() }) else {
        return [0, 0];
    };
    let asm = &player.chr_asm;
    if asm.equipment.selected_slots.left_weapon_slot > 2
        || asm.equipment.selected_slots.right_weapon_slot > 2
    {
        return [0, 0];
    }
    [
        asm.equipment_param_ids[asm.equipment.active_weapon_slot(ChrAsmHand::Right)],
        asm.equipment_param_ids[asm.equipment.active_weapon_slot(ChrAsmHand::Left)],
    ]
}

/// # Safety
/// Called from the damage processor's detour with that call's registers.
pub unsafe fn observe(regs: &Registers) {
    if OWN_CALL.load(Ordering::Acquire) || !ENABLED.load(Ordering::Acquire) {
        return;
    }
    let request = regs.r8 as *const u8;
    if request.is_null() || !(request as usize).is_multiple_of(8) {
        return;
    }
    let bytes = unsafe { std::slice::from_raw_parts_mut(request as *mut u8, REQUEST_BYTES) };
    let request_source = usize::from_le_bytes(bytes[0x1d8..0x1e0].try_into().unwrap());
    let target = usize::from_le_bytes(bytes[0x1e0..0x1e8].try_into().unwrap());
    let source = if regs.rdx as usize != 0 {
        regs.rdx as usize
    } else {
        request_source
    };
    let Some(local) = (unsafe { PlayerIns::local_player() })
        .ok()
        .map(|p| p as *const PlayerIns as usize)
    else {
        return;
    };
    // Hits on the player belong to the shield module.
    if target == 0 || target == local || !target.is_multiple_of(8) {
        return;
    }
    let damage = i32::from_le_bytes(bytes[OFFSET_LOSS..OFFSET_LOSS + 4].try_into().unwrap());
    let kind = relation(source, target, local);
    let weapon = i32::from_le_bytes(bytes[OFFSET_WEAPON..OFFSET_WEAPON + 4].try_into().unwrap());
    let stray = source == local
        && STOP_STRAYS.load(Ordering::Acquire)
        && from_equipped_weapon(weapon, unsafe { equipped_weapons() });
    // Read the evidence before it is wiped.
    let evidence = words(bytes).join(" ");
    if stray {
        neutralize(bytes);
    }
    let dump = DUMPS.fetch_add(1, Ordering::AcqRel) < MAX_DUMPS;
    if !dump {
        // Past the budget: one short line a second, so a long session stays readable.
        let second = crate::world_transport::now() / 1000;
        if LAST_COMPACT_SECOND.swap(second, Ordering::AcqRel) == second {
            return;
        }
        crate::stance::record_watch(format!(
            "Native hit on enemy (compact): victim {target:#x} from {kind}, damage {damage}{}",
            if stray { ", stopped" } else { "" }
        ));
        return;
    }
    crate::stance::record_watch(format!(
        "Native hit on enemy: victim {target:#x} from {kind} (source {source:#x}, module {:#x}), \
         damage {damage}{}, request words [{evidence}]",
        regs.rcx,
        if stray { " (stopped)" } else { "" }
    ));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_attacker_is_classified_from_the_request_pointers() {
        assert_eq!(relation(0, 0x100, 0x200), "no source");
        assert_eq!(relation(0x100, 0x100, 0x200), "the victim itself");
        assert_eq!(relation(0x200, 0x100, 0x200), "the local player");
        assert_eq!(relation(0x300, 0x100, 0x200), "another character");
    }

    #[test]
    fn only_non_zero_words_are_listed_and_the_list_is_bounded() {
        let mut request = vec![0u8; REQUEST_BYTES];
        request[0x24] = 1;
        request[0x228..0x22c].copy_from_slice(&738i32.to_le_bytes());
        assert_eq!(words(&request), ["0x24:1", "0x228:738"]);
        let busy = vec![0xffu8; REQUEST_BYTES];
        assert_eq!(words(&busy).len(), MAX_WORDS);
        assert_eq!(words(&busy)[0], "0x0:-1");
    }

    #[test]
    fn only_a_hit_made_with_an_equipped_weapon_is_a_stray() {
        assert!(from_equipped_weapon(2000000, [2000000, 31330000]));
        assert!(from_equipped_weapon(31330000, [2000000, 31330000]));
        assert!(!from_equipped_weapon(1000000, [2000000, 31330000]));
        // An empty, unknown or unreadable weapon never matches an empty hand.
        assert!(!from_equipped_weapon(0, [0, 0]));
        assert!(!from_equipped_weapon(-1, [-1, 0]));
    }

    #[test]
    fn a_stray_hit_is_wiped_of_damage_poise_and_reaction_and_nothing_else() {
        let mut request = vec![0u8; REQUEST_BYTES];
        request[OFFSET_CLASS..OFFSET_CLASS + 4].copy_from_slice(&30f32.to_le_bytes());
        for offset in OFFSET_POISE {
            request[offset..offset + 4].copy_from_slice(&10f32.to_le_bytes());
        }
        request[OFFSET_LEVEL] = 2;
        request[OFFSET_LEVEL + 1] = 3;
        request[OFFSET_WEAPON..OFFSET_WEAPON + 4].copy_from_slice(&2000000i32.to_le_bytes());
        request[OFFSET_LOSS..OFFSET_LOSS + 4].copy_from_slice(&3i32.to_le_bytes());
        request[0x1d8..0x1e0].copy_from_slice(&0x1234usize.to_le_bytes());
        request[0x1e0..0x1e8].copy_from_slice(&0x5678usize.to_le_bytes());
        neutralize(&mut request);
        assert_eq!(
            words(&request),
            [
                format!("{OFFSET_WEAPON:#x}:2000000"),
                "0x1d8:4660".to_string(),
                "0x1e0:22136".to_string()
            ],
            "the weapon and who hit whom survive, so the hit can still be attributed"
        );
    }

    #[test]
    fn the_bridges_own_call_is_marked_only_while_it_runs() {
        assert!(!OWN_CALL.load(Ordering::Acquire));
        {
            let _own = OwnCall::begin();
            assert!(OWN_CALL.load(Ordering::Acquire));
        }
        assert!(!OWN_CALL.load(Ordering::Acquire));
    }
}
