//! Reserve native combat and item-use bindings while Minecraft owns gameplay.
//!
//! Uses only the pinned SDK's public InputTypeGroup and digital-input bitset.
//! Input-code gates are changed only after proving every alias is an exact
//! captured gameplay binding. Unknown logical keys are accepted only when their
//! entire raw binding group exactly duplicates a canonical captured group. This preserves
//! the existing physical binding's coupled behavior without naming those keys.
//! Never empty input groups: live ER interprets empty combat groups as held.
//! This low-level adapter only owns input; higher-level combat/flight policies
//! decide which native actions remain enabled.
use crate::pad_layout::{self, GroupMap, RawInputTypeGroup};
use eldenring::{
    cs::{CSInGamePad, UserInputKey},
    fd4::{FD4PadManager, InputCodeState},
};
use fromsoftware_shared::FromStatic;

const MAX_BINDINGS: usize = 256;
const MAX_WORDS: usize = 128;
const ACTIONS: [UserInputKey; 5] = [
    UserInputKey::Attack,
    UserInputKey::StrongAttack,
    UserInputKey::Guard,
    UserInputKey::Skill,
    // The SDK identifies this logical action as native item use (key15).
    // Reserve its actual current bindings, including R and controller inputs,
    // rather than removing items, rebinding keys or blocking desktop input.
    UserInputKey::UseItem,
];
fn is_combat(key: i32) -> bool {
    ACTIONS.iter().any(|k| *k as i32 == key)
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Intent {
    /// Reserve native combat while true; false restores owned changes.
    pub active: bool,
    /// Bounded pulse for a fully charged accepted vanilla swing.
    pub attack: bool,
    /// Native guard request; the Minecraft shield adapter consumes real shield
    /// intent separately and clears this to avoid equipment-dependent reduction.
    pub guard: bool,
}
#[derive(Clone, Copy, Debug)]
pub struct Status {
    pub active: bool,
    pub attack: bool,
    pub guard: bool,
    pub blocked_codes: usize,
    pub attack_poll: Option<PollObservation>,
    pub guard_poll: Option<PollObservation>,
    /// True only after the extended jump/dodge transaction was acquired.
    pub movement_captured: bool,
    /// Extended mapping rejection; combat-only suppression remains active.
    pub movement_error: Option<&'static str>,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Preserve the full raw polling predicate in diagnostic logs.
pub struct SlotObservation {
    pub code: i32,
    pub kind: u32,
    pub index: Option<i32>,
    pub gate: Option<[bool; 2]>,
    pub digital_down: bool,
    pub mouse_fallback: bool,
    pub analog: Option<f32>,
    pub result: bool,
}
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // These raw binding details are consumed by Debug diagnostics.
pub struct PollObservation {
    pub action: i32,
    pub allow_polling: bool,
    pub forced: bool,
    pub raw_codes: [i32; 4],
    pub raw_kinds: [u32; 4],
    pub slots: [Option<SlotObservation>; 4],
    pub result: bool,
}
/// Source-equivalent read-only CSPad polling with raw integer map keys. Unlike
/// SDK poll_digital_input this never traverses the incomplete enum-key map.
/// # Safety
/// Exact-build game task, no concurrent pad mutation or retained SDK references.
pub unsafe fn observe_poll(action: UserInputKey) -> Result<PollObservation, &'static str> {
    let manager =
        unsafe { FD4PadManager::instance() }.map_err(|_| "combat poll manager unavailable")?;
    let pad = manager
        .get_in_game_pad()
        .ok_or("combat poll pad unavailable")?;
    let group = *unsafe { pad_layout::groups(pad) }
        .find(&(action as i32))
        .ok_or("combat poll group unavailable")?;
    let checks = unsafe { pad.input_code_check.as_ref() };
    let assignment = unsafe { pad.key_assign.as_ref() };
    let multi = unsafe { pad.pad_device.as_ref() };
    let device = unsafe { multi.virtual_multi_device.as_ref() };
    let bits = &device.virtual_input_data.dynamic_bitset;
    if bits.integer_count == 0 || bits.integer_count > MAX_WORDS {
        return Err("combat poll bitset rejected");
    }
    let mut slots = [None; 4];
    for (slot, code, kind) in group.iter() {
        if kind > 2 {
            return Err("combat poll kind rejected");
        }
        let gate = checks.find(&code).map(|c| [c.state_1, c.state_2]);
        let index = assignment.get_virtual_input_index(code);
        let mut digital_down = false;
        let mut mouse_fallback = false;
        let mut analog = None;
        if let Some(index) = index.filter(|n| *n >= 0) {
            if kind == 2 {
                analog = Some(device.get_virtual_analog_state(index as usize));
            } else {
                if index as usize >= bits.integer_count * 32 {
                    return Err("combat poll digital index rejected");
                }
                digital_down = bits.get(index as usize);
                if let Some(mouse) = assignment
                    .mouse_button_states_map
                    .find(&index)
                    .and_then(|n| n.checked_sub(1000))
                    && mouse > 0
                    && mouse <= 80
                {
                    mouse_fallback = multi.unk78.mouse_button_states[mouse as usize];
                }
            }
        }
        let down = digital_down || mouse_fallback;
        let result = gate == Some([true, false])
            && index.is_some_and(|n| n >= 0)
            && match kind {
                0 => down,
                1 => !down,
                2 => analog.is_some_and(|v| v != 0.0),
                _ => false,
            };
        slots[slot] = Some(SlotObservation {
            code,
            kind,
            index,
            gate,
            digital_down,
            mouse_fallback,
            analog,
            result,
        });
    }
    let forced = unsafe { pad_layout::forced(pad, action) };
    let result = pad.allow_polling && (forced || slots.iter().flatten().any(|s| s.result));
    Ok(PollObservation {
        action: action as i32,
        allow_polling: pad.allow_polling,
        forced,
        raw_codes: group.mapped_input_list,
        raw_kinds: group.input_type_list,
        slots,
        result,
    })
}
#[derive(Clone, Copy, Debug)]
struct Binding {
    key: i32,
    slot: usize,
    code: i32,
    index: Option<usize>,
    kind: u32,
}
#[derive(Clone, Copy, Debug)]
struct GroupPlan {
    key: i32,
    original: RawInputTypeGroup,
}
struct Plan {
    groups: Vec<GroupPlan>,
    attack: Binding,
    guard: Binding,
    jump: Option<Binding>,
    codes: Vec<i32>,
}

fn plan(
    groups: &[(i32, RawInputTypeGroup)],
    bindings: &[Binding],
    bit_count: usize,
) -> Result<Plan, &'static str> {
    plan_with_movement(groups, bindings, bit_count, false)
}
fn plan_with_movement(
    groups: &[(i32, RawInputTypeGroup)],
    bindings: &[Binding],
    bit_count: usize,
    movement: bool,
) -> Result<Plan, &'static str> {
    if groups.is_empty()
        || groups.len() > MAX_BINDINGS
        || bindings.is_empty()
        || bindings.len() > MAX_BINDINGS
        || bit_count == 0
        || bit_count > MAX_WORDS * 32
    {
        return Err("combat input mapping size rejected");
    }
    let mut reserved = Vec::with_capacity(8);
    let mut codes = Vec::with_capacity(16);
    let actions: Vec<_> = ACTIONS
        .into_iter()
        .chain(
            movement
                .then_some([UserInputKey::Jump, UserInputKey::Backstep])
                .into_iter()
                .flatten(),
        )
        .collect();
    for action in actions {
        let key = action as i32;
        let canonical = groups
            .iter()
            .find(|(k, _)| *k == key)
            .map(|(_, g)| *g)
            .ok_or("combat action mapping unavailable")?;
        if canonical.iter().next().is_none() {
            return Err("combat action mapping empty");
        }
        for (_, code, kind) in canonical.iter() {
            if code < 0 || kind > 2 {
                return Err("combat input code or polling kind rejected");
            }
            if reserved
                .iter()
                .any(|g: &GroupPlan| g.original.iter().any(|(_, c, _)| c == code))
            {
                return Err("combat canonical actions share an input code");
            }
            if !codes.contains(&code) {
                codes.push(code);
            }
        }
        reserved.push(GroupPlan {
            key,
            original: canonical,
        });
        for (other, group) in groups.iter().filter(|(k, _)| *k != key) {
            if *group == canonical {
                let dodge_alias = movement
                    && action == UserInputKey::Backstep
                    && *other == UserInputKey::BackstepTapped as i32;
                if !dodge_alias
                    && (pad_layout::key_name(*other) != "UnknownKey" || is_combat(*other))
                {
                    return Err("combat exact binding also controls another known action");
                }
                if reserved.iter().any(|g| g.key == *other) {
                    return Err("combat clone belongs to multiple actions");
                }
                reserved.push(GroupPlan {
                    key: *other,
                    original: *group,
                });
            }
        }
    }
    if reserved.len() > 64 || codes.len() > (ACTIONS.len() + 2) * 4 {
        return Err("combat coupled binding set exceeds bound");
    }
    // Different/mixed groups sharing a combat code are not exact clones.
    if bindings
        .iter()
        .any(|b| codes.contains(&b.code) && !reserved.iter().any(|g| g.key == b.key))
    {
        return Err("combat input code has a nonidentical or known noncombat alias");
    }
    let select = |action: UserInputKey| {
        bindings.iter().copied().find(|b| {
            b.key == action as i32
                && b.kind == 0
                && b.index.is_some_and(|n| n < bit_count)
                && !bindings.iter().any(|other| {
                    other.kind != 2
                        && other.index == b.index
                        && !reserved.iter().any(|g| g.key == other.key)
                })
        })
    };
    let attack = select(UserInputKey::Attack)
        .ok_or("combat attack bit aliases an unrelated digital action")?;
    let guard = select(UserInputKey::Guard)
        .ok_or("combat guard bit aliases an unrelated digital action")?;
    let jump = if movement {
        Some(select(UserInputKey::Jump).ok_or("Minecraft jump bit aliases an unrelated action")?)
    } else {
        None
    };
    Ok(Plan {
        groups: reserved,
        attack,
        guard,
        jump,
        codes,
    })
}

/// An unsupported movement binding must not release established Minecraft
/// combat authority. The caller applies the returned combat-only plan normally.
fn select_plan(
    groups: &[(i32, RawInputTypeGroup)],
    bindings: &[Binding],
    bit_count: usize,
    movement: bool,
) -> Result<(Plan, Option<&'static str>), &'static str> {
    match plan_with_movement(groups, bindings, bit_count, movement) {
        Ok(plan) => Ok((plan, None)),
        Err(error) if movement => plan(groups, bindings, bit_count).map(|plan| (plan, Some(error))),
        Err(error) => Err(error),
    }
}

fn mapping_diagnostic(
    groups: &[(i32, RawInputTypeGroup)],
    bindings: &[Binding],
    bit_count: usize,
) -> String {
    let relevant = |key| {
        is_combat(key)
            || [
                UserInputKey::Jump,
                UserInputKey::Backstep,
                UserInputKey::BackstepTapped,
            ]
            .iter()
            .any(|action| *action as i32 == key)
    };
    let combat: Vec<_> = bindings.iter().filter(|b| relevant(b.key)).collect();
    let related: Vec<_> = bindings
        .iter()
        .filter(|b| {
            relevant(b.key)
                || combat
                    .iter()
                    .any(|c| c.code == b.code || c.index.is_some() && c.index == b.index)
        })
        .take(64)
        .collect();
    let mut text = format!(
        "groups={} bindings={} digital_bits={bit_count};",
        groups.len(),
        bindings.len()
    );
    for b in related {
        use std::fmt::Write;
        let group = groups.iter().find(|(key, _)| *key == b.key).map(|(_, g)| g);
        let _ = write!(
            text,
            " {}({}) code={}({:#x}) index={:?} kind={} slot={} group={:?};",
            pad_layout::key_name(b.key),
            b.key,
            b.code,
            b.code,
            b.index,
            b.kind,
            b.slot,
            group
        );
    }
    text
}
#[cfg(test)]
fn gate_value(code: i32, plan: &Plan, intent: Intent, original: bool) -> bool {
    gate_value_with_jump(code, plan, intent, false, original)
}
fn gate_value_with_jump(
    code: i32,
    plan: &Plan,
    intent: Intent,
    jump: bool,
    original: bool,
) -> bool {
    if intent.attack && code == plan.attack.code
        || intent.guard && code == plan.guard.code
        || jump && plan.jump.is_some_and(|b| b.code == code)
    {
        original
    } else {
        true
    }
}
#[derive(Clone, Copy)]
struct BoolWrite {
    original: bool,
    written: bool,
}
impl BoolWrite {
    fn restore(self, current: &mut bool) {
        if *current == self.written {
            *current = self.original;
        }
    }
}
struct GateWrite {
    code: i32,
    address: usize,
    value: BoolWrite,
}
impl GateWrite {
    fn restore(&self, current: &mut InputCodeState) {
        self.value.restore(&mut current.state_2);
    }
}
struct BitWrite {
    index: usize,
    value: BoolWrite,
}
struct OwnedWrites {
    pad: usize,
    checks: usize,
    device: usize,
    bitset: usize,
    gates: Vec<GateWrite>,
    bits: Vec<BitWrite>,
}
#[derive(Default)]
pub struct Controller {
    writes: Option<OwnedWrites>,
    diagnostic: Option<String>,
}
impl Controller {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn diagnostic(&self) -> Option<&str> {
        self.diagnostic.as_deref()
    }
    /// Some(jump) reserves native jump/dodge bindings for Minecraft Space. None
    /// releases that ownership. This shares the combat transaction so GUI
    /// capture and locomotion never have competing writers to the pad fields.
    /// # Safety
    /// Exact-build gated game task, no other pad writer or outstanding SDK refs.
    /// Call after physical input polling and before native action consumption.
    pub unsafe fn update_with_movement(
        &mut self,
        intent: Intent,
        jump: Option<bool>,
    ) -> Result<Status, &'static str> {
        unsafe { self.suspend()? };
        if !intent.active {
            return Ok(Status {
                active: false,
                attack: false,
                guard: false,
                blocked_codes: 0,
                attack_poll: None,
                guard_poll: None,
                movement_captured: false,
                movement_error: None,
            });
        }
        if intent.attack && intent.guard {
            return Err("combat attack and guard cannot be requested together");
        }
        let manager = unsafe { FD4PadManager::instance_mut() }
            .map_err(|_| "combat pad manager unavailable")?;
        let pad = manager
            .get_in_game_pad_mut()
            .ok_or("combat in-game pad unavailable")?;
        if !pad.allow_polling {
            return Err("combat cannot override a disabled in-game pad");
        }
        let pad_address = pad as *mut CSInGamePad as usize;
        let groups_pointer = pad.input_type_group.as_ptr().cast::<GroupMap>();
        let assignment = unsafe { pad.key_assign.as_ref() };
        let mut groups = Vec::with_capacity(128);
        let mut bindings = Vec::with_capacity(128);
        for pair in unsafe { pad_layout::groups(pad) }.iter() {
            if groups.len() >= MAX_BINDINGS {
                return Err("combat group table exceeds bound");
            }
            groups.push((pair.first, pair.second));
            for (slot, code, kind) in pair.second.iter() {
                if kind > 2 {
                    return Err("combat input polling kind rejected");
                }
                if bindings.len() >= MAX_BINDINGS {
                    return Err("combat binding table exceeds bound");
                }
                bindings.push(Binding {
                    key: pair.first,
                    slot,
                    code,
                    kind,
                    index: assignment
                        .get_virtual_input_index(code)
                        .and_then(|n| usize::try_from(n).ok()),
                });
            }
        }
        let words = unsafe { pad.pad_device.as_ref().virtual_multi_device.as_ref() }
            .virtual_input_data
            .dynamic_bitset
            .integer_count;
        if words == 0 || words > MAX_WORDS {
            return Err("combat virtual input bitset rejected");
        }
        let (plan, movement_error) =
            match select_plan(&groups, &bindings, words * 32, jump.is_some()) {
                Ok((plan, error)) => {
                    self.diagnostic =
                        error.map(|_| mapping_diagnostic(&groups, &bindings, words * 32));
                    (plan, error)
                }
                Err(error) => {
                    self.diagnostic = Some(mapping_diagnostic(&groups, &bindings, words * 32));
                    return Err(error);
                }
            };
        if plan
            .groups
            .iter()
            .any(|g| unsafe { pad_layout::forced_key(pad, g.key) })
        {
            return Err("combat cannot override a forced host action entry");
        }
        // The complete alias validation above proves these codes belong only to
        // combat groups. Keep state_1 and original state_2 eligibility for an
        // accepted action; set state_2 only to suppress other combat input.
        let checks = unsafe { pad.input_code_check.as_ref() };
        let checks_pointer = pad.input_code_check.as_ptr();
        let mut gates = Vec::with_capacity(plan.codes.len());
        for &code in &plan.codes {
            let current = checks.find(&code).ok_or("combat input check unavailable")?;
            gates.push(GateWrite {
                code,
                address: current as *const _ as usize,
                value: BoolWrite {
                    original: current.state_2,
                    written: gate_value_with_jump(
                        code,
                        &plan,
                        intent,
                        jump == Some(true),
                        current.state_2,
                    ),
                },
            });
        }
        for group in &plan.groups {
            let current = unsafe { &*groups_pointer }
                .find(&group.key)
                .ok_or("combat logical group disappeared")?;
            if *current != group.original {
                return Err("combat logical group changed before application");
            }
        }
        let device = unsafe { pad.pad_device.as_mut().virtual_multi_device.as_mut() };
        let device_address = device as *mut _ as usize;
        let bits_pointer = std::ptr::addr_of_mut!(device.virtual_input_data.dynamic_bitset);
        let _lock = device
            .mutex
            .try_lock()
            .ok_or("combat virtual input device busy")?;
        let bits = unsafe { &mut *bits_pointer };
        if bits.integer_count != words {
            return Err("combat input bitset changed before application");
        }
        let mut bit_writes = Vec::with_capacity(2);
        for (binding, requested) in [(plan.attack, intent.attack), (plan.guard, intent.guard)]
            .into_iter()
            .chain(plan.jump.map(|b| (b, jump == Some(true))))
        {
            if requested {
                let index = binding.index.ok_or("combat digital binding disappeared")?;
                bit_writes.push(BitWrite {
                    index,
                    value: BoolWrite {
                        original: bits.get(index),
                        written: true,
                    },
                });
            }
        }
        self.writes = Some(OwnedWrites {
            pad: pad_address,
            checks: checks_pointer as usize,
            device: device_address,
            bitset: bits.bitset.as_ptr() as usize,
            gates,
            bits: bit_writes,
        });
        let owned = self.writes.as_ref().ok_or("combat ownership unavailable")?;
        for write in &owned.gates {
            let current = unsafe { &mut *checks_pointer }
                .find_mut(&write.code)
                .ok_or("combat check changed during application")?;
            current.state_2 = write.value.written;
        }
        for write in &owned.bits {
            bits.set(write.index, write.value.written);
        }
        drop(_lock);
        let attack_poll = if intent.attack {
            Some(unsafe { observe_poll(UserInputKey::Attack)? })
        } else {
            None
        };
        let guard_poll = if intent.guard {
            Some(unsafe { observe_poll(UserInputKey::Guard)? })
        } else {
            None
        };
        Ok(Status {
            active: true,
            attack: intent.attack,
            guard: intent.guard,
            blocked_codes: plan.codes.len(),
            attack_poll,
            guard_poll,
            movement_captured: jump.is_some() && movement_error.is_none(),
            movement_error,
        })
    }
    /// # Safety
    /// Gated game task, no competing pad writer. Retry busy-device errors;
    /// ownership is retained until restoration can be attempted again.
    pub unsafe fn suspend(&mut self) -> Result<(), &'static str> {
        let Some(writes) = self.writes.take() else {
            return Ok(());
        };
        let Ok(manager) = (unsafe { FD4PadManager::instance_mut() }) else {
            return Ok(());
        };
        let Some(pad) = manager.get_in_game_pad_mut() else {
            return Ok(());
        };
        if pad as *mut CSInGamePad as usize != writes.pad {
            return Ok(());
        }
        let checks_pointer = pad.input_code_check.as_ptr();
        let device = unsafe { pad.pad_device.as_mut().virtual_multi_device.as_mut() };
        let device_address = device as *mut _ as usize;
        let bits_pointer = std::ptr::addr_of_mut!(device.virtual_input_data.dynamic_bitset);
        let Some(_lock) = device.mutex.try_lock() else {
            self.writes = Some(writes);
            return Err("combat restoration waiting for input device");
        };
        let bits = unsafe { &mut *bits_pointer };
        if device_address == writes.device
            && bits.bitset.as_ptr() as usize == writes.bitset
            && bits.integer_count > 0
            && bits.integer_count <= MAX_WORDS
        {
            for write in writes.bits {
                if write.index < bits.integer_count * 32 {
                    let mut current = bits.get(write.index);
                    write.value.restore(&mut current);
                    bits.set(write.index, current);
                }
            }
        }
        if checks_pointer as usize == writes.checks {
            for write in writes.gates {
                if let Some(current) = unsafe { &mut *checks_pointer }.find_mut(&write.code)
                    && current as *mut _ as usize == write.address
                {
                    write.restore(current);
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn bindings() -> Vec<Binding> {
        ACTIONS
            .into_iter()
            .enumerate()
            .map(|(i, key)| Binding {
                key: key as i32,
                slot: 0,
                code: 100 + i as i32,
                index: Some(40 + i),
                kind: 0,
            })
            .collect()
    }
    fn groups(bindings: &[Binding]) -> Vec<(i32, RawInputTypeGroup)> {
        let mut result: Vec<(i32, RawInputTypeGroup)> = Vec::new();
        for b in bindings {
            if !result.iter().any(|(k, _)| *k == b.key) {
                result.push((
                    b.key,
                    RawInputTypeGroup {
                        mapped_input_list: [-1; 4],
                        input_type_list: [0; 4],
                    },
                ));
            }
            let group = &mut result.iter_mut().find(|(k, _)| *k == b.key).unwrap().1;
            group.mapped_input_list[b.slot] = b.code;
            group.input_type_list[b.slot] = b.kind;
        }
        result
    }
    #[test]
    fn exact_unknown_clones_preserve_original_physical_binding_without_semantic_whitelist() {
        let mut b = bindings();
        b.push(Binding { key: 12345, ..b[0] });
        let p = plan(&groups(&b), &b, 128).unwrap();
        assert_eq!(p.groups.len(), ACTIONS.len() + 1);
        assert!(gate_value(
            100,
            &p,
            Intent {
                active: true,
                ..Default::default()
            },
            false
        ));
        assert!(!gate_value(
            100,
            &p,
            Intent {
                active: true,
                attack: true,
                guard: false
            },
            false
        ));
        assert!(gate_value(
            100,
            &p,
            Intent {
                active: true,
                attack: true,
                guard: false
            },
            true
        ));
    }
    #[test]
    fn rejects_known_movement_alias_even_when_entire_group_matches() {
        let mut b = bindings();
        b.push(Binding {
            key: UserInputKey::MoveForwards as i32,
            ..b[0]
        });
        assert!(plan(&groups(&b), &b, 128).is_err());
    }
    #[test]
    fn minecraft_space_opens_jump_but_never_the_native_dodge_binding() {
        let mut b = bindings();
        b.push(Binding {
            key: UserInputKey::Jump as i32,
            slot: 0,
            code: 200,
            index: Some(60),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::Backstep as i32,
            slot: 0,
            code: 201,
            index: Some(61),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::BackstepTapped as i32,
            ..b[ACTIONS.len() + 1]
        });
        let p = plan_with_movement(&groups(&b), &b, 128, true).unwrap();
        let intent = Intent {
            active: true,
            ..Default::default()
        };
        assert!(!gate_value_with_jump(200, &p, intent, true, false));
        assert!(gate_value_with_jump(201, &p, intent, true, false));
        assert!(gate_value_with_jump(200, &p, intent, false, false));
        // No movement ownership means original jump/dodge codes are not captured.
        assert!(!plan(&groups(&b), &b, 128).unwrap().codes.contains(&200));
        b.push(Binding {
            key: UserInputKey::EventAction as i32,
            ..b[ACTIONS.len()]
        });
        assert!(plan_with_movement(&groups(&b), &b, 128, true).is_err());
    }
    #[test]
    fn rejected_jump_mapping_keeps_combat_suppression_and_reports_movement_failure() {
        let mut b = bindings();
        b.push(Binding {
            key: UserInputKey::Jump as i32,
            slot: 0,
            code: 200,
            index: Some(60),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::Backstep as i32,
            slot: 0,
            code: 201,
            index: Some(61),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::EventAction as i32,
            ..b[ACTIONS.len()]
        });
        let g = groups(&b);
        let (p, error) = select_plan(&g, &b, 128, true).unwrap();
        assert!(error.is_some());
        assert!(p.jump.is_none());
        assert_eq!(p.codes, vec![100, 101, 102, 103, 104]);
        for &code in &p.codes {
            assert!(gate_value_with_jump(
                code,
                &p,
                Intent {
                    active: true,
                    ..Default::default()
                },
                true,
                false
            ));
        }
        let diagnostic = mapping_diagnostic(&g, &b, 128);
        assert!(diagnostic.contains("code=200"));
        assert!(diagnostic.contains("code=201"));
        assert!(diagnostic.contains(&format!("({})", UserInputKey::EventAction as i32)));
    }
    #[test]
    fn movement_readiness_requires_a_successful_extended_plan() {
        let mut b = bindings();
        // Missing movement groups still allows ordinary combat suppression.
        let (p, error) = select_plan(&groups(&b), &b, 128, true).unwrap();
        assert!(p.jump.is_none());
        assert!(error.is_some());
        b.push(Binding {
            key: UserInputKey::Jump as i32,
            slot: 0,
            code: 200,
            index: Some(60),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::Backstep as i32,
            slot: 0,
            code: 201,
            index: Some(61),
            kind: 0,
        });
        let (p, error) = select_plan(&groups(&b), &b, 128, true).unwrap();
        assert!(p.jump.is_some());
        assert!(error.is_none());
        let (p, error) = select_plan(&groups(&b), &b, 128, false).unwrap();
        assert!(p.jump.is_none());
        assert!(error.is_none());
        // Fallback cannot authorize a combat binding that aliases another action.
        b.push(Binding {
            key: UserInputKey::MoveForwards as i32,
            ..b[0]
        });
        assert!(select_plan(&groups(&b), &b, 128, true).is_err());
    }
    #[test]
    fn suppression_preserves_bindings_and_unblocks_only_the_accepted_code() {
        let b = bindings();
        let g = groups(&b);
        let p = plan(&g, &b, 128).unwrap();
        for intent in [
            Intent {
                active: true,
                attack: true,
                guard: false,
            },
            Intent {
                active: true,
                attack: false,
                guard: true,
            },
        ] {
            for &code in &p.codes {
                let selected = if intent.attack {
                    p.attack.code
                } else {
                    p.guard.code
                };
                assert_eq!(gate_value(code, &p, intent, false), code != selected);
            }
        }
        assert!(
            p.groups
                .iter()
                .all(|r| g.iter().any(|(k, v)| *k == r.key && *v == r.original))
        );
    }
    #[test]
    fn native_item_use_never_opens_for_minecraft_attack_guard_or_jump() {
        let mut b = bindings();
        let item = b
            .iter()
            .copied()
            .find(|b| b.key == UserInputKey::UseItem as i32)
            .unwrap();
        b.push(Binding { key: 12345, ..item });
        b.push(Binding {
            key: UserInputKey::Jump as i32,
            slot: 0,
            code: 200,
            index: Some(60),
            kind: 0,
        });
        b.push(Binding {
            key: UserInputKey::Backstep as i32,
            slot: 0,
            code: 201,
            index: Some(61),
            kind: 0,
        });
        let g = groups(&b);
        let p = plan_with_movement(&g, &b, 128, true).unwrap();
        assert!(
            p.groups
                .iter()
                .any(|group| group.key == UserInputKey::UseItem as i32)
        );
        assert!(p.groups.iter().any(|group| group.key == 12345));
        for intent in [
            Intent {
                active: true,
                ..Default::default()
            },
            Intent {
                active: true,
                attack: true,
                guard: false,
            },
            Intent {
                active: true,
                attack: false,
                guard: true,
            },
        ] {
            for jump in [false, true] {
                assert!(gate_value_with_jump(item.code, &p, intent, jump, false));
            }
        }
        assert!(p.groups.iter().all(|saved| {
            g.iter()
                .any(|(key, group)| *key == saved.key && *group == saved.original)
        }));
        // Releasing gameplay ownership restores the native item's own prior
        // eligibility. The physical bindings and item inventory are untouched.
        for original in [false, true] {
            let mut current = InputCodeState {
                state_1: true,
                state_2: true,
            };
            GateWrite {
                code: item.code,
                address: 0,
                value: BoolWrite {
                    original,
                    written: true,
                },
            }
            .restore(&mut current);
            assert_eq!(current.state_2, original);
            assert!(current.state_1);
        }
    }
    #[test]
    fn item_use_cannot_capture_an_unrelated_native_menu_binding() {
        let mut b = bindings();
        let item = b
            .iter()
            .copied()
            .find(|b| b.key == UserInputKey::UseItem as i32)
            .unwrap();
        b.push(Binding {
            key: UserInputKey::EventAction as i32,
            ..item
        });
        assert!(plan(&groups(&b), &b, 128).is_err());
    }
    #[test]
    fn all_four_slots_remain_supported_with_item_use_and_movement_capture() {
        let mut b = Vec::new();
        for key in ACTIONS
            .into_iter()
            .chain([UserInputKey::Jump, UserInputKey::Backstep])
        {
            for slot in 0..4 {
                let index = b.len();
                b.push(Binding {
                    key: key as i32,
                    slot,
                    code: 300 + index as i32,
                    index: Some(30 + index),
                    kind: 0,
                });
            }
        }
        let p = plan_with_movement(&groups(&b), &b, 128, true).unwrap();
        assert_eq!(p.codes.len(), 28);
        for item in b.iter().filter(|b| b.key == UserInputKey::UseItem as i32) {
            assert!(gate_value_with_jump(
                item.code,
                &p,
                Intent {
                    active: true,
                    attack: true,
                    guard: false
                },
                true,
                false
            ));
        }
    }
    #[test]
    fn shared_code_between_different_combat_actions_is_rejected() {
        let mut b = bindings();
        b.push(Binding {
            key: UserInputKey::StrongAttack as i32,
            slot: 1,
            ..b[0]
        });
        assert!(plan(&groups(&b), &b, 128).is_err());
    }
    #[test]
    fn rejects_unknown_mixed_group_and_unused_slot_type_differences() {
        let mut b = bindings();
        b.push(Binding { key: 12345, ..b[0] });
        let mut g = groups(&b);
        g.last_mut().unwrap().1.input_type_list[3] = 99;
        assert!(plan(&g, &b, 128).is_err());
        b.push(Binding {
            key: 12345,
            slot: 1,
            code: 999,
            index: Some(99),
            kind: 0,
        });
        assert!(plan(&groups(&b), &b, 128).is_err());
    }
    #[test]
    fn rejects_unrelated_digital_down_and_release_aliases_but_not_analog_namespace() {
        for kind in [0, 1, 2] {
            let mut b = bindings();
            b.push(Binding {
                key: UserInputKey::MoveForwards as i32,
                code: 999,
                kind,
                ..b[0]
            });
            assert_eq!(plan(&groups(&b), &b, 128).is_ok(), kind == 2);
        }
    }
    #[test]
    fn never_injects_release_mapping_or_out_of_bounds_bit() {
        let mut b = bindings();
        b[0].kind = 1;
        assert!(plan(&groups(&b), &b, 128).is_err());
        let b = bindings();
        assert!(plan(&groups(&b), &b, 40).is_err());
    }
    #[test]
    fn gate_restore_preserves_host_eligibility_changes() {
        let write = GateWrite {
            code: 100,
            address: 0,
            value: BoolWrite {
                original: false,
                written: true,
            },
        };
        let mut value = InputCodeState {
            state_1: true,
            state_2: true,
        };
        write.restore(&mut value);
        assert!(!value.state_2);
        value.state_1 = false;
        value.state_2 = true;
        write.restore(&mut value);
        assert!(!value.state_1);
        assert!(!value.state_2);
    }
    #[test]
    fn bit_restore_preserves_host_changes() {
        let mut current = true;
        BoolWrite {
            original: false,
            written: false,
        }
        .restore(&mut current);
        assert!(current);
        current = false;
        BoolWrite {
            original: true,
            written: false,
        }
        .restore(&mut current);
        assert!(current);
    }
    #[test]
    fn inactive_controller_never_accesses_sdk() {
        let mut c = Controller::new();
        let status = unsafe { c.update_with_movement(Intent::default(), Some(true)) }.unwrap();
        assert!(!status.active);
        assert!(!status.movement_captured);
        assert!(status.movement_error.is_none());
        assert!(unsafe { c.suspend() }.is_ok());
    }
    #[test]
    fn diagnostic_keeps_unknown_keys_numeric_and_includes_all_raw_slots() {
        let mut b = bindings();
        b.push(Binding { key: 268, ..b[0] });
        let text = mapping_diagnostic(&groups(&b), &b, 128);
        assert!(text.contains("UnknownKey(268)"));
        assert!(text.contains("mapped_input_list"));
        assert!(!text.contains("Map(268)"));
    }
}
