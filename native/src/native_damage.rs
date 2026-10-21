//! Experimental, exact-build entry into ER's *calculated* damage processor.
//!
//! This deliberately does not call the attack dispatcher or damage calculator:
//! Minecraft supplies the final HP amount. It does not request player animation,
//! patch parameters, or retain game pointers. The only direct HP write is the
//! bounded mirror onto a verified dormant phase owner. See
//! `../README.md` for compatibility and ownership requirements. Caller owns the
//! fresh guest transaction, target/line-of-sight policy, and gameplay task gate.

use crate::boss_damage_links::{self as links, Actor, Kind, Link};
use eldenring::cs::{
    CSFeManImp, CSSessionManager, ChrIns, FieldInsHandle, FieldInsType, GameMan, LobbyState,
    NpcParam, ProtocolState, SoloParamRepository, SpEffectParam, WorldChrMan, WorldChrManDbgFlags,
};
use fromsoftware_shared::{FromStatic, program::Program};
use pelite::pe64::PeObject;
use std::ffi::c_void;

const CONSTRUCTOR_RVA: usize = 0x5294b0;
const DESTRUCTOR_RVA: usize = 0x529660;
const PROCESS_RVA: usize = 0x4489f0;
const REACTION_RVA: usize = 0x445fa0;
const NOTIFY_RVA: usize = 0x4487d0;
const ENEMY_DAMAGE_VTABLE_RVA: usize = 0x2a3c0d8;
// Verified RTTI in the SHA-gated executable: CSPlayerDamageModule. Registered
// human bosses such as Gideon, hostile human NPCs and NPC invaders are
// PlayerIns; the shared processor/feedback use this module.
const PLAYER_DAMAGE_VTABLE_RVA: usize = 0x2a3c3e0;
const DAMAGE_MODULE_OFFSET: usize = 0x98;
const REQUEST_BYTES: usize = 0x270;
pub const MAX_DAMAGE: i32 = 1_000_000;
// Verified native on-hit callsite 0x449389..0x4493d3 and callee
// 0x3e8e70 -> 0x3fb0a0 -> 0x43e0e0. This uses the game's resistance
// processor, including active-status immunity and increasing proc thresholds.
const STATUS_RVA: usize = 0x3e8e70;
const STATUS_ORIGIN_RVA: usize = 0x4ffb10;
const STATUS_PREFIX: &[u8] = &[
    0x48, 0x8b, 0xc4, 0x57, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41, 0x57, 0x48, 0x83, 0xec, 0x50,
    0x48, 0xc7, 0x40, 0xc8, 0xfe, 0xff, 0xff, 0xff,
];
const STATUS_ORIGIN_PREFIX: &[u8] = &[
    0x8b, 0x82, 0xa8, 0, 0, 0, 0x89, 0x01, 0x8b, 0x82, 0xac, 0, 0, 0, 0x89, 0x41, 0x04, 0x48, 0x8b,
    0xc1,
];
type ApplyStatus = unsafe extern "system" fn(
    *mut c_void,
    i32,
    *const c_void,
    u8,
    *const c_void,
    *mut c_void,
    u8,
    u8,
    u8,
) -> u8;
type StatusOrigin = unsafe extern "system" fn(*mut c_void, *const c_void) -> *mut c_void;

/// Exact temporary metadata initialized at 0x44900b..0x449086. The first
/// seven floats are status power coefficients, followed by seven accumulators.
/// No guest pointer, native parameter patch or manual resistance write occurs.
#[repr(C, align(16))]
struct StatusInfo {
    bytes: [u8; 0x44],
}
impl StatusInfo {
    fn new(correction: f32) -> Self {
        let mut value = Self { bytes: [0; 0x44] };
        for at in (0..0x1c).step_by(4) {
            value.bytes[at..at + 4].copy_from_slice(&1_f32.to_le_bytes());
        }
        value.bytes[0x38..0x3c].copy_from_slice(&correction.to_le_bytes());
        value.bytes[0x41] = 1;
        value
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StatusReceipt {
    pub effect: i32,
    /// Nominal pre-mitigation buildup. Native immunity and reductions own the outcome.
    pub buildup: u32,
    pub level: u32,
}
fn status_spec(kind: usize, level: u32) -> Option<(u32, u16, u32)> {
    // Verified from installed regulation.bin version 11711000 using the
    // SoulsFormats ER AES/DCX/BND4/PARAM layouts: 6500 has poison power 60,
    // 6400 blood power 45, and 6700 frost power 55; the other powers are zero.
    // These are native weapon on-hit rows, also named by Paramdex:
    // https://github.com/soulsmods/Paramdex/blob/master/ER/Names/SpEffectParam.txt
    let (id, state) = [(6500, 2), (6400, 6), (6700, 260)].get(kind).copied()?;
    (1..=3)
        .contains(&level)
        .then_some((id, state, 20 + 15 * level))
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Element {
    Fire,
    Lightning,
    Magic,
    Holy,
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ElementReceipt {
    pub element: Element,
    pub level: u32,
    /// The target's authored NpcParam damage cut rate for this element.
    pub cut_rate: f32,
    pub base_damage: i32,
    /// Physical part of the hit; the remainder is the absorbed elemental part.
    pub physical: i32,
    pub damage: i32,
}
/// As in Elden Ring, an affinity converts part of the physical damage: by
/// level, the share kept as physical and the share dealt as the element.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Split {
    physical: f64,
    element: f64,
}
const ELEMENT_SPLITS: [Split; 3] = [
    Split {
        physical: 0.90,
        element: 0.15,
    },
    Split {
        physical: 0.85,
        element: 0.22,
    },
    Split {
        physical: 0.80,
        element: 0.30,
    },
];
fn element_spec(kind: usize, level: u32) -> Option<(Element, Split)> {
    let element = [
        Element::Fire,
        Element::Lightning,
        Element::Magic,
        Element::Holy,
    ]
    .get(kind.checked_sub(crate::combat_wire::FIRST_ELEMENT)?)
    .copied()?;
    let split = ELEMENT_SPLITS
        .get((level as usize).checked_sub(1)?)
        .copied()?;
    Some((element, split))
}
fn element_cut_rate(npc: &eldenring::param::NPC_PARAM_ST, element: Element) -> f32 {
    // Paramdex: "dark" damage is holy. A cut rate is calculated damage x rate,
    // so 1 is neutral, 0.8 is 20% absorption and 1.2 is a 20% weakness.
    match element {
        Element::Fire => npc.fire_damage_cut_rate(),
        Element::Lightning => npc.thunder_damage_cut_rate(),
        Element::Magic => npc.magic_damage_cut_rate(),
        Element::Holy => npc.dark_damage_cut_rate(),
    }
}
/// Physical and total HP loss. Bounded so a malformed row can neither heal nor
/// more than double the elemental part; an immune target keeps the physical part.
/// `boost` is a Wondrous Physick multiplier on the elemental part only.
fn element_damage(damage: i32, split: Split, cut_rate: f32, boost: f64) -> Option<(i32, i32)> {
    if !cut_rate.is_finite()
        || !(0. ..=3.).contains(&cut_rate)
        || !valid_damage(damage)
        || !boost.is_finite()
        || !(1. ..=2.).contains(&boost)
    {
        return None;
    }
    let bound = |value: f64| value.round().clamp(1., MAX_DAMAGE as f64) as i32;
    let physical = damage as f64 * split.physical;
    let total = physical + damage as f64 * split.element * cut_rate.min(2.) as f64 * boost;
    Some((bound(physical), bound(total)))
}
/// Nominal buildup after a Wondrous Physick multiplier (Intelligence-knot
/// raises frostbite). Never lowers buildup and never exceeds 1.5 times it.
fn physick_buildup(buildup: u32, scale: f64) -> u32 {
    let scale = if scale.is_finite() {
        scale.clamp(1., 1.5)
    } else {
        1.
    };
    (buildup as f64 * scale).round() as u32
}
/// Stable per-instance key for an enemy's stance meter.
pub(crate) fn packed_handle(handle: FieldInsHandle) -> u64 {
    handle.selector.0 as u64 | ((handle.block_id.0 as u32 as u64) << 32)
}
unsafe fn dispatch_affinity(
    construct: Construct,
    destruct: Destruct,
    make_origin: StatusOrigin,
    apply: ApplyStatus,
    resolved: Resolved,
    id: u32,
    base_power: i32,
    buildup: u32,
) {
    let mut info = StatusInfo::new(buildup as f32 / base_power as f32);
    #[repr(C, align(16))]
    struct Origin([u8; 16]);
    let mut origin = Origin([0; 16]);
    let mut request = Request::zeroed();
    let request_ptr = (&mut request as *mut Request).cast::<c_void>();
    unsafe {
        construct(request_ptr);
    }
    request.configure(resolved.source, resolved.target, 1, resolved.direction);
    unsafe {
        make_origin(origin.0.as_mut_ptr().cast(), request_ptr);
        // 0x449389..0x4493d3 passes these exact nine arguments. False
        // flags preserve the ordinary hit path; the game owns status procs.
        // A false return may mean positive buildup below its proc threshold;
        // it never authorizes replay of this already consumed transaction.
        apply(
            resolved.target as *mut c_void,
            id as i32,
            resolved.source as *const c_void,
            0,
            origin.0.as_ptr().cast(),
            (&mut info as *mut StatusInfo).cast(),
            0,
            0,
            0,
        );
        destruct(request_ptr);
    }
}

// m13_00_00_00 event 13002860 links group 13005851 (these two bodies) to
// entity 13000850, whose HP owns both the displayed bar and the defeat event.
// https://soulsmodding.com/doku.php?id=tutorial:intro-to-elden-ring-emevd
#[cfg(test)]
const GODSKIN_DUO_BLOCK: i32 = 0x0d00_0000;
#[cfg(test)]
const GODSKIN_DUO_GAUGE: i32 = 903575000;
#[cfg(test)]
const GODSKIN_DUO_POOL: u32 = 13000850;

// Ashen Capital's first-phase Godfrey and the Hoarah Loux health owner are
// different models. Identities corroborated by the primary enemy dataset and
// live body receipts / exact boss-health registration in the supported build.
// https://gist.github.com/gracenotes/745ee2d07878f81067fc23f43670a808
#[cfg(test)]
const GODFREY_BLOCK: i32 = 0x0b05_0000;
#[cfg(test)]
const GODFREY_GAUGE: i32 = 904720000;

// Public primary-source DAMAGE_PROCESS_PATTERN, independently matched against
// the already SHA-verified local executable. Constructor/destructor fingerprints
// also checked against that exact executable. These are validation metadata.
const CONSTRUCTOR_PREFIX: &[u8] = &[
    0x48, 0x89, 0x4c, 0x24, 0x08, 0x53, 0x48, 0x83, 0xec, 0x30, 0x48, 0xc7, 0x44, 0x24, 0x20, 0xfe,
    0xff, 0xff, 0xff, 0x48, 0x8b, 0xd9,
];
const DESTRUCTOR_PREFIX: &[u8] = &[
    0x48, 0x89, 0x4c, 0x24, 0x08, 0x48, 0x83, 0xec, 0x38, 0x48, 0xc7, 0x44, 0x24, 0x20, 0xfe, 0xff,
    0xff, 0xff,
];
const REACTION_PREFIX: &[u8] = &[
    0x40, 0x56, 0x57, 0x41, 0x56, 0x48, 0x83, 0xec, 0x50, 0x48, 0xc7, 0x44, 0x24, 0x28, 0xfe, 0xff,
    0xff, 0xff,
];
const NOTIFY_PREFIX: &[u8] = &[
    0x48, 0x89, 0x6c, 0x24, 0x18, 0x56, 0x57, 0x41, 0x57, 0x48, 0x83, 0xec, 0x30, 0x48, 0x8b, 0xf1,
];
// The game's own stance routine, which its hit pipeline calls on the target's stance module right
// after the damage processor and before the reaction and the notification: it spends the enemy's
// stance with the request's poise damage and, when that runs out, marks the stance broken. The
// bridge's sequence used to skip it, so no poise in a request ever reached the enemy's stance.
const STANCE_RVA: usize = 0x47e4e0;
const STANCE_PREFIX: &[u8] = &[
    0x48, 0x8b, 0xc4, 0x53, 0x56, 0x57, 0x48, 0x81, 0xec, 0xd0, 0x00, 0x00, 0x00, 0x0f, 0x29, 0x78,
    0xc8, 0x49, 0x8b, 0xf0, 0x4c, 0x89, 0x70, 0x18, 0x48, 0x8b, 0xf9, 0x44, 0x0f, 0x29, 0x40, 0xb8,
];
// Smithbox ATKPARAM_DMGTYPE_NEW: 1 = short stagger. This is a deliberately
// bounded host reaction policy, not Minecraft's knockback or ER weapon poise.
const SHORT_STAGGER: u8 = 1;
// A hit that breaks the enemy's stance is made like the game's own: poise damage well past any
// enemy's whole stance, at the offsets its own hits carry it, and the stagger level they use.
const BREAK_POISE: f32 = 10_000.;
const BREAK_LEVEL: u8 = 2;
const STANCE_SCALE_OFFSET: usize = 0x244;

type Construct = unsafe extern "system" fn(*mut c_void) -> *mut c_void;
type Destruct = unsafe extern "system" fn(*mut c_void);
// Callsite 0x449fe3..0x449ffa passes these five args. The fourth is an
// already-dead state and the fifth suppresses HP mutation. Both are false for
// this live-target local call. It returns no documented result; use HP readback.
type Process = unsafe extern "system" fn(*mut c_void, *const c_void, *mut c_void, u8, u8);
type React = unsafe extern "system" fn(*mut c_void, *mut c_void);
// Exact upstream 0x44a24e..0x44a268: module, source, request, previous-dead,
// pre-hit HP, suppress-HP. Keep the complete six-argument caller contract.
type Notify = unsafe extern "system" fn(*mut c_void, *const c_void, *mut c_void, u8, i32, u8);
// Stance module, the attacking character (or null) and the request.
type SpendStance = unsafe extern "system" fn(*mut c_void, *const c_void, *mut c_void);

/// Address of the stance routine, once its bytes have been verified in this executable.
static STANCE_ROUTINE: std::sync::OnceLock<usize> = std::sync::OnceLock::new();

#[repr(C, align(16))]
struct Request {
    bytes: [u8; REQUEST_BYTES],
}

impl Request {
    fn zeroed() -> Self {
        Self {
            bytes: [0; REQUEST_BYTES],
        }
    }
    fn i32(&mut self, at: usize, value: i32) {
        self.bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }
    fn f32(&mut self, at: usize, value: f32) {
        self.bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }
    fn ptr(&mut self, at: usize, value: usize) {
        self.bytes[at..at + 8].copy_from_slice(&(value as u64).to_le_bytes());
    }

    /// Call only after the native constructor. These are the only fields owned
    /// by this adapter. All other native defaults remain intact.
    fn configure(&mut self, source: usize, target: usize, damage: i32, direction: [f32; 3]) {
        self.f32(0x00, damage as f32); // positive component: native damage classification
        self.bytes[0x24] = SHORT_STAGGER;
        for (i, v) in direction.into_iter().chain([0.0]).enumerate() {
            self.f32(0x1c0 + i * 4, v);
        }
        self.ptr(0x1d8, source);
        self.ptr(0x1e0, target);
        self.i32(0x228, damage); // final calculated HP loss consumed by native processor
        // Exact processor tests bit3 before inherited attack/on-hit Speffects.
        // Minecraft owns those effects; do not apply the hidden ER weapon buffs.
        self.bytes[0x267] |= 0x08;
    }
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // All fields are intentionally included in receipt diagnostics.
pub struct ProtectionObservation {
    /// SDK GameData.AllNoDead. Observation only; never changed by this adapter.
    pub all_no_dead: Option<bool>,
    pub all_no_damage: Option<bool>,
    /// Exact native predicate 0x437a60 reads data+0x19b bit0.
    pub character_debug_no_dead: bool,
    /// Processor predicate 0x437a00 reads data+0x19b bit1 and AllNoDamage.
    pub character_debug_no_damage: bool,
    /// Exact native predicate 0x437d30 also reads data+0x19a bit7.
    pub character_no_dead_state: bool,
    /// Pinned SDK TAE Event 0 / action 96 SET_IMMORTALITY.
    pub animation_immortality: bool,
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Identity fields remain useful in the formatted native receipt.
pub struct TargetObservation {
    pub handle: FieldInsHandle,
    pub character_id: u32,
    pub hp: i32,
    pub max_hp: i32,
    pub death_flag: bool,
    pub last_hit_by: FieldInsHandle,
    /// Havok position of the character.
    pub position: [f32; 3],
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Full processor/feedback evidence is emitted through Debug logs.
pub struct Receipt {
    pub source: FieldInsHandle,
    pub target: FieldInsHandle,
    pub requested_damage: i32,
    pub hp_before: i32,
    pub max_hp_before: i32,
    /// Immediate native damage-processor result, before reaction/notification.
    pub hp_after_processor: i32,
    /// Latest available HP after feedback. See post_feedback if target vanished.
    pub hp_after: i32,
    /// Loss directly observed from the processor, used for transaction outcome.
    pub actual_delta: i32,
    /// Zero HP or native death flag observed after feedback; later death can lag.
    pub killed: bool,
    /// Pre-hit boss classification of the target; see `combat_targets::boss_encounter`.
    pub boss: bool,
    /// Latest observed Havok position of the target, where a kill's loot lands.
    pub position: [f32; 3],
    /// Native target field, observed after the call. Often updated only on death.
    pub last_hit_by: FieldInsHandle,
    /// Pre-call target protection observations, to distinguish a native
    /// nonlethal clamp from request construction. Speffect protections are not
    /// covered by this diagnostic; false fields do not prove death is allowed.
    pub protection_before: ProtectionObservation,
    /// Native reaction and on-hit notification ran after positive HP readback.
    /// This records dispatch, not a claim that a particular animation played.
    pub feedback_dispatched: bool,
    /// No value means the same instance was no longer available after feedback.
    pub post_feedback: Option<TargetObservation>,
    /// Loss mirrored onto a dormant boss-gauge owner of this same body.
    pub gauge_mirror: Option<GaugeMirror>,
    /// Native damage forwarded to a verified active shared-health controller.
    /// A failure is diagnostic only: the body's accepted hit must not be retried.
    pub referred_damage: Option<Result<ReferredDamage, &'static str>>,
    /// An affinity may fail closed independently after accepted HP damage.
    pub affinity: Option<Result<StatusReceipt, &'static str>>,
    /// An elemental affinity scales the requested damage before the hit. A
    /// locked or unreadable element fails closed to the unscaled damage.
    pub element: Option<Result<ElementReceipt, &'static str>>,
}

impl Receipt {
    /// A verified immortal visible body may have reached its native 1 HP floor
    /// while its live health controller still accepts damage. Count that real
    /// controller loss in transaction acknowledgements, without summing the
    /// body's copy of the same hit or retrying an already accepted body hit.
    pub(crate) fn applied_delta(&self) -> i32 {
        let forwarded = self
            .referred_damage
            .as_ref()
            .and_then(|result| result.as_ref().ok())
            .map_or(0, |owner| {
                owner
                    .hp_before
                    .saturating_sub(owner.hp_before_forwarding)
                    .saturating_add(owner.actual_delta)
                    .clamp(0, self.requested_damage)
            });
        self.actual_delta.max(forwarded)
    }
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Emitted through Debug receipt logs.
pub struct ReferredDamage {
    pub owner: FieldInsHandle,
    /// Controller HP before damage to the visible body.
    pub hp_before: i32,
    /// Controller HP after the body processed its hit and native feedback.
    pub hp_before_forwarding: i32,
    pub forwarded_damage: i32,
    pub hp_after: i32,
    pub actual_delta: i32,
    pub killed: bool,
    pub feedback_dispatched: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct ReferredOwner<H> {
    handle: H,
    entity_id: u32,
    npc_id: i32,
    npc_param_id: i32,
    block_id: i32,
    fmg_id: i32,
    ready: bool,
    hp: i32,
}

fn referred_link(entity_id: u32, npc_id: i32, block_id: i32, npc_param_id: i32) -> Option<Link> {
    links::body_link(Actor {
        entity: entity_id,
        model: npc_id,
        block: block_id,
        param: npc_param_id,
    })
}

/// The active shared pool is a different actor, and the Noble is a different
/// model. Never infer this relationship from proximity or a generic boss bar.
fn linked_referred_owner<H: Copy + PartialEq>(
    target: H,
    entity_id: u32,
    npc_id: i32,
    block_id: i32,
    npc_param_id: i32,
    owners: &[ReferredOwner<H>],
) -> Option<ReferredOwner<H>> {
    let link = referred_link(entity_id, npc_id, block_id, npc_param_id)?;
    if owners.iter().any(|owner| owner.handle == target) {
        return None;
    }
    let mut linked = owners.iter().filter(|owner| {
        owner.handle != target
            && link.owner_matches(
                block_id,
                Actor {
                    entity: owner.entity_id,
                    model: owner.npc_id,
                    param: owner.npc_param_id,
                    block: owner.block_id,
                },
                owner.fmg_id,
            )
            && owner.ready
            && owner.hp > 0
    });
    let first = *linked.next()?;
    linked.all(|owner| *owner == first).then_some(first)
}

/// Account for any forwarding already performed by the native processor or
/// feedback. A reset/heal during the call invalidates this damage relationship.
fn missing_referred_damage(loss: i32, hp_before: i32, hp_now: i32) -> Result<i32, &'static str> {
    if hp_before <= 0 || hp_now > hp_before {
        return Err("native referred damage controller health changed");
    }
    if loss <= 0 || hp_now <= 0 {
        return Ok(0);
    }
    let already_forwarded = hp_before.saturating_sub(hp_now);
    Ok(loss.saturating_sub(already_forwarded).clamp(0, MAX_DAMAGE))
}

/// Copied identity evidence for this one synchronous hit, never a stored lease.
struct ReferredSnapshot {
    owner: ReferredOwner<FieldInsHandle>,
    link: Link,
    target_ptr: usize,
    module: usize,
    data: usize,
    max_hp: i32,
}

fn referred_hit_loss(receipt: &Receipt, link: Link) -> i32 {
    let accepted = receipt.actual_delta.min(receipt.requested_damage).max(0);
    let protection = receipt.protection_before;
    if link.kind == Kind::Pool
        && link.immortal_body
        && receipt.hp_after_processor == 1
        && !receipt.killed
        && protection.all_no_dead == Some(false)
        && protection.all_no_damage == Some(false)
        && !protection.character_debug_no_dead
        && !protection.character_debug_no_damage
        && (protection.character_no_dead_state || protection.animation_immortality)
    {
        // The authored body's HP is a nonlethal copy. Its current registered
        // controller remains the damage authority and preserves native immunity.
        receipt.requested_damage
    } else {
        accepted
    }
}

/// Some phase-split bosses (live: Fire Giant and Godfrey) register the health gauge
/// to a dormant later-phase character. Native attacks on the fighting body
/// lower that gauge; this direct processor call does not, so mirror its loss.
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)] // Emitted through Debug receipt logs.
pub struct GaugeMirror {
    pub owner: FieldInsHandle,
    pub hp_before: i32,
    pub hp_before_mirroring: i32,
    pub hp_after: i32,
    pub mirrored_damage: i32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct GaugeOwner<H> {
    handle: H,
    entity_id: u32,
    npc_id: i32,
    npc_param_id: i32,
    block_id: i32,
    fmg_id: i32,
    /// Fails combat readiness, such as unregistered update tasks.
    dormant: bool,
    hp: i32,
}

#[derive(Clone, Copy)]
struct GaugeSnapshot {
    owner: GaugeOwner<FieldInsHandle>,
    instance: usize,
    data: usize,
    max_hp: i32,
}

/// Only an authored continuous-health phase pair can have a dormant mirror.
/// A registered target owns its own gauge; ambiguity mirrors nothing.
fn linked_gauge_owner<H: Copy + PartialEq>(
    target: H,
    npc_id: i32,
    block_id: i32,
    entity_id: u32,
    npc_param_id: i32,
    owners: &[GaugeOwner<H>],
) -> Option<GaugeOwner<H>> {
    let link = referred_link(entity_id, npc_id, block_id, npc_param_id)?;
    if link.kind != Kind::Phase {
        return None;
    }
    if owners.iter().any(|owner| owner.handle == target) {
        return None;
    }
    let mut linked = owners.iter().filter(|owner| {
        link.owner_matches(
            block_id,
            Actor {
                entity: owner.entity_id,
                model: owner.npc_id,
                param: owner.npc_param_id,
                block: owner.block_id,
            },
            owner.fmg_id,
        ) && owner.dormant
            && owner.hp > 1
    });
    let first = *linked.next()?;
    linked.all(|owner| *owner == first).then_some(first)
}

/// Never kills the dormant owner: its scripted phase owns that transition.
fn mirrored_hp(hp: i32, loss: i32) -> i32 {
    hp.saturating_sub(loss.max(0)).max(1)
}

fn mirrored_gauge_hp(snapshot_hp: i32, current_hp: i32, loss: i32) -> Option<i32> {
    if current_hp <= 1 {
        return None;
    }
    let missing = missing_referred_damage(loss, snapshot_hp, current_hp).ok()?;
    Some(mirrored_hp(current_hp, missing))
}

/// Copy the dormant phase owner's identity/HP before native body feedback can
/// replace it. No SDK references span the native processor or feedback calls.
unsafe fn snapshot_gauge_owner(target: FieldInsHandle) -> Option<GaugeSnapshot> {
    let frontend = unsafe { CSFeManImp::instance() }.ok()?;
    let world = unsafe { WorldChrMan::instance() }.ok()?;
    let body = world.chr_ins_by_handle(&target)?;
    if body.field_ins_handle != target
        || links::body_link(links::actor(body)).is_none_or(|link| link.kind != Kind::Phase)
    {
        return None;
    }
    let owners = frontend
        .boss_health_displays
        .iter()
        .filter(|display| display.fmg_id > 0 && !display.field_ins_handle.is_empty())
        .filter_map(|display| {
            let chr = world
                .chr_ins_by_handle(&display.field_ins_handle)
                .filter(|chr| chr.field_ins_handle == display.field_ins_handle)?;
            let dead = chr.chr_flags1c5.death_flag();
            Some(GaugeOwner {
                handle: display.field_ins_handle,
                entity_id: chr.event_entity_id,
                npc_id: chr.npc_id,
                npc_param_id: chr.npc_param_id,
                block_id: display.field_ins_handle.block_id.0,
                fmg_id: display.fmg_id,
                dormant: links::dormant_phase(crate::combat_targets::readiness_rejection(chr)),
                hp: if dead { 0 } else { chr.modules.data.hp },
            })
        })
        .collect::<Vec<_>>();
    let owner = linked_gauge_owner(
        target,
        body.npc_id,
        target.block_id.0,
        body.event_entity_id,
        body.npc_param_id,
        &owners,
    )?;
    let chr = world.chr_ins_by_handle(&owner.handle)?;
    let max_hp = chr.modules.data.max_hp;
    (owner.hp <= max_hp).then_some(GaugeSnapshot {
        owner,
        instance: chr as *const _ as usize,
        data: chr.modules.data.as_ref() as *const _ as usize,
        max_hp,
    })
}

/// Game thread only, after body feedback. Native phase ownership is unchanged;
/// only accepted loss still missing from this exact dormant gauge is mirrored.
unsafe fn mirror_gauge_owner(snapshot: GaugeSnapshot, loss: i32) -> Option<GaugeMirror> {
    let game = unsafe { GameMan::instance() }.ok()?;
    let session = unsafe { CSSessionManager::instance() }.ok()?;
    if game.is_in_online_mode
        || game.warp_requested
        || session.lobby_state != LobbyState::None
        || session.protocol_state != ProtocolState::None
    {
        return None;
    }
    let owner = snapshot.owner;
    let frontend = unsafe { CSFeManImp::instance() }.ok()?;
    if !frontend
        .boss_health_displays
        .iter()
        .any(|display| display.field_ins_handle == owner.handle && display.fmg_id == owner.fmg_id)
    {
        return None;
    }
    let world = unsafe { WorldChrMan::instance_mut() }.ok()?;
    let chr = world.chr_ins_by_handle_mut(&owner.handle)?;
    if chr.field_ins_handle != owner.handle
        || chr as *const _ as usize != snapshot.instance
        || chr.modules.data.as_ref() as *const _ as usize != snapshot.data
        || chr.event_entity_id != owner.entity_id
        || chr.npc_id != owner.npc_id
        || chr.npc_param_id != owner.npc_param_id
        || chr.modules.data.max_hp != snapshot.max_hp
        || chr.chr_flags1c5.death_flag()
        || !links::dormant_phase(crate::combat_targets::readiness_rejection(chr))
    {
        return None;
    }
    let hp_before = chr.modules.data.hp;
    let hp_after = mirrored_gauge_hp(owner.hp, hp_before, loss)?;
    chr.modules.data.hp = hp_after;
    Some(GaugeMirror {
        owner: owner.handle,
        hp_before: owner.hp,
        hp_before_mirroring: hp_before,
        hp_after,
        mirrored_damage: hp_before - hp_after,
    })
}

/// Immutable entrypoint addresses, never character/module pointers.
/// Both validated characters of one hit, read on the game thread.
#[derive(Clone, Copy)]
struct Resolved {
    source: usize,
    target: usize,
    module: usize,
    hp: i32,
    protection: ProtectionObservation,
    direction: [f32; 3],
    boss: bool,
}
pub struct Sink {
    base: usize,
    construct: Construct,
    destruct: Destruct,
    process: Process,
    react: React,
    notify: Notify,
    stance: StanceIo,
}

/// Reads and writes the game's own stance (poise) value of a validated target. A seam, so the
/// hit sequence can be exercised against stand-ins like the other native calls.
#[derive(Clone, Copy)]
struct StanceIo {
    read: unsafe fn(module: usize, target: usize) -> Option<f32>,
    write: unsafe fn(module: usize, target: usize, value: f32),
    /// Everything the game keeps that decides whether the enemy visibly staggers.
    inspect: unsafe fn(module: usize, target: usize) -> Option<crate::stance::Real>,
    /// Returns the (hyper armor, forced break) animation flags, first writing the given pair.
    flags:
        unsafe fn(module: usize, target: usize, set: Option<(bool, bool)>) -> Option<(bool, bool)>,
    /// Runs the game's own stance routine on the target with this request, as its hit pipeline
    /// does. False when the routine is unavailable or the target's stance module is not its own.
    spend: unsafe fn(module: usize, target: usize, source: usize, request: *mut c_void) -> bool,
}

/// The target's current stance, only when that module demonstrably belongs to this character.
unsafe fn game_stance_read(_module: usize, target: usize) -> Option<f32> {
    let chr = unsafe { &*(target as *const ChrIns) };
    let stance = &chr.modules.super_armor;
    let (current, maximum) = (stance.sa_durability, stance.sa_durability_max);
    (stance.owner.as_ptr() as usize == target
        && current.is_finite()
        && maximum.is_finite()
        && maximum > 0.)
        .then_some(current)
}

unsafe fn game_stance_write(_module: usize, target: usize, value: f32) {
    let chr = unsafe { &mut *(target as *mut ChrIns) };
    chr.modules.super_armor.sa_durability = value;
}

/// A copy of the enemy's stagger-deciding state, only for a module that belongs to this character.
pub(crate) unsafe fn game_inspect(_module: usize, target: usize) -> Option<crate::stance::Real> {
    let chr = unsafe { &*(target as *const ChrIns) };
    let stance = &chr.modules.super_armor;
    if stance.owner.as_ptr() as usize != target {
        return None;
    }
    let toughness = &chr.modules.toughness;
    let action = &chr.modules.action_flag;
    let timeline = &chr.modules.time_act;
    let anim = timeline
        .anim_queue
        .get(timeline.read_idx as usize)
        .map_or(-1, |slot| slot.anim_id);
    Some(crate::stance::Real {
        stance: stance.sa_durability,
        stance_max: stance.sa_durability_max,
        toughness: toughness.toughness,
        damage_level: action.damage_level,
        hyper_armor: action.action_modifiers_flags.super_armor(),
        forced_break: action.action_modifiers_flags.poise_forced_break(),
        anim,
        broken_lock: unsafe { *(stance as *const _ as *const u8).add(0x20) },
    })
}

unsafe fn game_spend(_module: usize, target: usize, source: usize, request: *mut c_void) -> bool {
    let Some(&address) = STANCE_ROUTINE.get() else {
        return false;
    };
    let chr = unsafe { &mut *(target as *mut ChrIns) };
    let stance = &mut chr.modules.super_armor;
    if stance.owner.as_ptr() as usize != target {
        return false;
    }
    let routine = unsafe { std::mem::transmute::<usize, SpendStance>(address) };
    unsafe {
        routine(
            &mut **stance as *mut _ as *mut c_void,
            source as *const c_void,
            request,
        );
    }
    true
}

unsafe fn game_flags(
    _module: usize,
    target: usize,
    set: Option<(bool, bool)>,
) -> Option<(bool, bool)> {
    let chr = unsafe { &mut *(target as *mut ChrIns) };
    if chr.modules.super_armor.owner.as_ptr() as usize != target {
        return None;
    }
    let flags = &mut chr.modules.action_flag.action_modifiers_flags;
    let previous = (flags.super_armor(), flags.poise_forced_break());
    if let Some((hyper_armor, forced_break)) = set {
        flags.set_super_armor(hyper_armor);
        flags.set_poise_forced_break(forced_break);
    }
    Some(previous)
}

const GAME_STANCE: StanceIo = StanceIo {
    read: game_stance_read,
    write: game_stance_write,
    inspect: game_inspect,
    flags: game_flags,
    spend: game_spend,
};

fn matches(image: &[u8], at: usize, prefix: &[u8]) -> bool {
    at.checked_add(prefix.len())
        .and_then(|end| image.get(at..end))
        == Some(prefix)
}
fn valid_damage(value: i32) -> bool {
    (1..=MAX_DAMAGE).contains(&value)
}
fn verified_damage_module_rva(rva: usize, enemy_class: bool, player_combatant: bool) -> bool {
    matches!(
        (rva, enemy_class, player_combatant),
        (ENEMY_DAMAGE_VTABLE_RVA, true, false) | (PLAYER_DAMAGE_VTABLE_RVA, false, true)
    )
}
fn hit_direction(source: [f32; 3], target: [f32; 3]) -> Option<[f32; 3]> {
    let delta = std::array::from_fn::<_, 3, _>(|i| target[i] - source[i]);
    let n = delta.iter().map(|v| v * v).sum::<f32>();
    if !source.iter().chain(target.iter()).all(|v| v.is_finite()) || !n.is_finite() || n < 0.000001
    {
        return None;
    }
    Some(delta.map(|v| v / n.sqrt()))
}
fn frost_damage_factor(state: u16, factor: f32) -> f64 {
    if state == 260 && factor.is_finite() && (1. ..=2.).contains(&factor) {
        factor as f64
    } else {
        1.
    }
}
fn frost_damage_multiplier(chr: &ChrIns) -> f64 {
    // HP is supplied after Minecraft calculation, so read the native frost
    // debuff's authored vulnerability for that final HP amount as well.
    // Installed regulation 11711000 row 6700: state 260, duration 30 seconds,
    // all physical damage-cut multipliers 1.2 (paramdef: calculated damage x rate).
    chr.special_effect
        .entries()
        .take(128)
        .filter_map(|entry| {
            let row = unsafe { entry.param_data?.as_ref() };
            Some(frost_damage_factor(
                row.state_info(),
                row.neutral_damage_cut_rate(),
            ))
        })
        .fold(1., f64::max)
}

impl Sink {
    /// Resolve only after the engine's complete executable SHA-256 gate passes:
    /// f9118fea2cfd8fa3901e8060bfadb1552cb2471771ded8a5b662c4e298ffb328.
    /// These extra fingerprints fail closed on altered entrypoints. No SDK
    /// singleton or game function is invoked during resolution.
    pub fn resolve() -> Result<Self, &'static str> {
        let program = Program::current();
        let image = program.image();
        if !matches(image, CONSTRUCTOR_RVA, CONSTRUCTOR_PREFIX)
            || !matches(image, DESTRUCTOR_RVA, DESTRUCTOR_PREFIX)
            || !crate::minecraft_shield::processor_matches(image)
            || !matches(image, REACTION_RVA, REACTION_PREFIX)
            || !matches(image, NOTIFY_RVA, NOTIFY_PREFIX)
            || ENEMY_DAMAGE_VTABLE_RVA + 16 > image.len()
            || PLAYER_DAMAGE_VTABLE_RVA + 16 > image.len()
        {
            return Err("native damage entrypoint fingerprint mismatch");
        }
        let base = image.as_ptr() as usize;
        // Optional: without it a stance break falls back to writing the stance directly.
        if matches(image, STANCE_RVA, STANCE_PREFIX) {
            let _ = STANCE_ROUTINE.set(base + STANCE_RVA);
        }
        Ok(Self {
            base,
            construct: unsafe { std::mem::transmute::<usize, Construct>(base + CONSTRUCTOR_RVA) },
            destruct: unsafe { std::mem::transmute::<usize, Destruct>(base + DESTRUCTOR_RVA) },
            process: unsafe { std::mem::transmute::<usize, Process>(base + PROCESS_RVA) },
            react: unsafe { std::mem::transmute::<usize, React>(base + REACTION_RVA) },
            notify: unsafe { std::mem::transmute::<usize, Notify>(base + NOTIFY_RVA) },
            stance: GAME_STANCE,
        })
    }

    /// Apply one already-authorized Minecraft melee transaction, synchronously.
    ///
    /// # Safety
    /// Engine SHA gate passed, current valid foreground offline gameplay task,
    /// fresh target identity/hostility/reach/LOS and guest commit already checked.
    /// No live SDK references may span this call, and no concurrent game writer.
    /// It requests an enemy reaction after positive HP loss: every hit when `stance` is None,
    /// otherwise only the hit that breaks the enemy's stance. It does not
    /// implement Minecraft knockback, guard or upstream dispatcher immunity.
    pub unsafe fn apply_enchanted(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
        hp_damage: i32,
        enchantments: u32,
        stance: Option<f32>,
    ) -> Result<Receipt, &'static str> {
        if !crate::combat_wire::valid_enchantments(enchantments) {
            return Err("invalid native enchantment levels");
        }
        // Copy identity before ordinary hit feedback can swap a phase instance.
        let identity = unsafe { self.resolve_characters(source, target)? };
        let affinity = crate::combat_wire::affinity(enchantments);
        let element = affinity
            .filter(|(kind, _)| *kind >= crate::combat_wire::FIRST_ELEMENT)
            .map(|(kind, level)| unsafe { Self::element(identity.target, kind, level, hp_damage) });
        let scaled = match element {
            Some(Ok(element)) => element.damage,
            _ => hp_damage,
        };
        let mut receipt = unsafe { self.apply_world(source, target, scaled, None, stance)? };
        receipt.element = element;
        if receipt.applied_delta() > 0
            && !receipt.killed
            && let Some((kind, level)) =
                affinity.filter(|(kind, _)| *kind < crate::combat_wire::FIRST_ELEMENT)
        {
            receipt.affinity =
                Some(unsafe { self.apply_affinity(source, target, identity.target, kind, level) });
            if let Ok(after) =
                unsafe { self.read_receipt(target, identity.target, identity.module) }
            {
                receipt.hp_after = after.hp;
                receipt.killed = after.hp <= 0 || after.death_flag;
                receipt.position = after.position;
                receipt.last_hit_by = after.last_hit_by;
                receipt.post_feedback = Some(after);
            }
        }
        Ok(receipt)
    }
    /// The stagger policy of this hit. Capacity is the target's authored stance, read here on the
    /// game thread from its NPC row; an unreadable row keeps the original stagger on every hit.
    unsafe fn reaction(target: usize, stance: Option<f32>) -> crate::stance::Reaction {
        use crate::stance::Reaction;
        let (Some(damage), Some(rules)) = (stance, crate::campaign_runtime::stance_rules()) else {
            return Reaction::Always;
        };
        let npc_param_id = unsafe { (&*(target as *const ChrIns)).npc_param_id };
        if crate::stance::reacts_to_every_hit(npc_param_id) {
            return Reaction::Always;
        }
        let Some(authored) = (unsafe { SoloParamRepository::instance() })
            .ok()
            .and_then(|repo| {
                repo.get::<NpcParam>(npc_param_id as u32)
                    .map(|npc| npc.super_armor_durability())
            })
        else {
            return Reaction::Always;
        };
        Reaction::Stance {
            damage,
            capacity: authored * rules.capacity_scale as f32,
            recovery_ms: (rules.recovery_seconds * 1000.) as u64,
        }
    }
    /// Read the validated target's authored elemental absorption on the game
    /// thread; the element itself is the scaled damage of this one hit.
    unsafe fn element(
        target: usize,
        kind: usize,
        level: u32,
        hp_damage: i32,
    ) -> Result<ElementReceipt, &'static str> {
        if !crate::campaign_runtime::affinity_unlocked(kind) {
            return Err("native whetblade affinity is locked");
        }
        let (element, split) = element_spec(kind, level).ok_or("invalid native element")?;
        let npc_param_id = unsafe { (&*(target as *const ChrIns)).npc_param_id };
        let repo = unsafe { SoloParamRepository::instance() }
            .map_err(|_| "native element params unavailable")?;
        let npc = repo
            .get::<NpcParam>(npc_param_id as u32)
            .ok_or("native element target param unavailable")?;
        let cut_rate = element_cut_rate(npc, element);
        let boost = crate::campaign_runtime::physick_affinity_scale(kind);
        let (physical, damage) = element_damage(hp_damage, split, cut_rate, boost)
            .ok_or("native element absorption rejected")?;
        Ok(ElementReceipt {
            element,
            level,
            cut_rate,
            base_damage: hp_damage,
            physical,
            damage,
        })
    }
    unsafe fn apply_affinity(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
        expected: usize,
        kind: usize,
        level: u32,
    ) -> Result<StatusReceipt, &'static str> {
        if !crate::campaign_runtime::affinity_unlocked(kind) {
            return Err("native whetblade affinity is locked");
        }
        let image = Program::current().image();
        if !matches(image, STATUS_RVA, STATUS_PREFIX)
            || !matches(image, STATUS_ORIGIN_RVA, STATUS_ORIGIN_PREFIX)
        {
            return Err("native status entrypoint fingerprint mismatch");
        }
        let (id, state, buildup) = status_spec(kind, level).ok_or("invalid native affinity")?;
        let buildup = physick_buildup(
            buildup,
            crate::campaign_runtime::physick_affinity_scale(kind),
        );
        let base_power = {
            let repo = unsafe { SoloParamRepository::instance() }
                .map_err(|_| "native status params unavailable")?;
            let row = repo
                .get::<SpEffectParam>(id)
                .ok_or("native status effect unavailable")?;
            let powers = [
                row.poizon_attack_power(),
                row.blood_attack_power(),
                row.freeze_attack_power(),
            ];
            if row.state_info() != state
                || powers[kind] <= 0
                || powers[kind] > 10000
                || powers.iter().enumerate().any(|(i, n)| i != kind && *n != 0)
            {
                return Err("native status row identity changed");
            }
            powers[kind]
        };
        let resolved = unsafe { self.resolve_characters(source, target)? };
        if resolved.target != expected {
            return Err("native status target instance changed");
        }
        let apply = unsafe { std::mem::transmute::<usize, ApplyStatus>(self.base + STATUS_RVA) };
        let make_origin =
            unsafe { std::mem::transmute::<usize, StatusOrigin>(self.base + STATUS_ORIGIN_RVA) };
        unsafe {
            dispatch_affinity(
                self.construct,
                self.destruct,
                make_origin,
                apply,
                resolved,
                id,
                base_power,
                buildup,
            );
        }
        Ok(StatusReceipt {
            effect: id as i32,
            buildup,
            level,
        })
    }
    /// Genuine guest mob/explosion damage retains the verified local-player
    /// ownership/credit, but its reaction direction originates at that event.
    /// Native proxy attribution is a separate adapter; callers cannot provide
    /// arbitrary native actor pointers or bypass the standard source checks.
    pub unsafe fn apply_world(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
        hp_damage: i32,
        event_origin: Option<[f32; 3]>,
        stance: Option<f32>,
    ) -> Result<Receipt, &'static str> {
        if !valid_damage(hp_damage) {
            return Err("native damage amount out of range");
        }
        if source == target
            || source.is_empty()
            || target.is_empty()
            || source.selector.field_ins_type() != Some(FieldInsType::Chr)
            || target.selector.field_ins_type() != Some(FieldInsType::Chr)
        {
            return Err("native damage character handles rejected");
        }
        let mut resolved = unsafe { self.resolve_characters(source, target)? };
        // The target was resolved and validated on this game-thread call. Tune
        // encounter HP pressure by the pinned SDK's typed NPC param identity.
        let npc_param_id = unsafe { (&*(resolved.target as *const ChrIns)).npc_param_id };
        let hp_damage = (hp_damage as f64
            * crate::campaign_runtime::enemy_damage_multiplier(npc_param_id)
            * frost_damage_multiplier(unsafe { &*(resolved.target as *const ChrIns) }))
        .round()
        .clamp(1., MAX_DAMAGE as f64) as i32;
        if let Some(origin) = event_origin {
            let position = unsafe {
                (&*(resolved.target as *const ChrIns))
                    .modules
                    .physics
                    .position
            };
            if let Some(value) = hit_direction(origin, [position.0, position.1, position.2]) {
                resolved.direction = value;
            } else if origin.iter().any(|v| !v.is_finite()) {
                return Err("world damage origin invalid");
            }
        }
        // Capture the encounter link before a lethal body hit can remove that
        // body. Only copied identity/HP evidence spans the native calls.
        let referred = unsafe { self.referred_owner(source, target) };
        let gauge = if referred.is_none() {
            unsafe { snapshot_gauge_owner(target) }
        } else {
            None
        };
        let reaction = unsafe { Self::reaction(resolved.target, stance) };
        let mut receipt = unsafe {
            self.process_prepared(source, target, hp_damage, resolved, reaction, || {
                self.read_receipt(target, resolved.target, resolved.module)
            })?
        };
        if receipt.killed {
            crate::stance::forget(packed_handle(target));
        }
        if let Some(snapshot) = referred {
            let loss = referred_hit_loss(&receipt, snapshot.link);
            if loss > 0 {
                receipt.referred_damage =
                    Some(unsafe { self.forward_referred_damage(source, snapshot, loss) });
            }
        }
        if receipt.actual_delta > 0 {
            if let Some(snapshot) = gauge {
                receipt.gauge_mirror = unsafe {
                    mirror_gauge_owner(snapshot, receipt.actual_delta.min(receipt.requested_damage))
                };
            }
        }
        Ok(receipt)
    }

    /// Uses a freshly validated enemy module and an already calculated amount.
    /// Shared-pool loss deliberately bypasses encounter scaling and link lookup:
    /// those were applied to the body, so forwarding cannot scale or recurse.
    /// The observer returns copied state and retains no SDK references.
    unsafe fn process_prepared(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
        hp_damage: i32,
        resolved: Resolved,
        reaction: crate::stance::Reaction,
        mut observe: impl FnMut() -> Result<TargetObservation, &'static str>,
    ) -> Result<Receipt, &'static str> {
        let Resolved {
            source: source_ptr,
            target: target_ptr,
            module,
            hp: hp_before,
            protection: protection_before,
            direction,
            boss,
        } = resolved;
        let mut request = Request::zeroed();
        let request_ptr = (&mut request as *mut Request).cast::<c_void>();
        unsafe {
            (self.construct)(request_ptr);
        }
        request.configure(source_ptr, target_ptr, hp_damage, direction);
        // Only a stance-metered hit may move the game's own stance value; the legacy every-hit
        // stagger keeps its original behavior untouched.
        let metered = matches!(reaction, crate::stance::Reaction::Stance { .. });
        let tracked = packed_handle(target);
        let real_before = if metered {
            unsafe { (self.stance.read)(module, target_ptr) }
        } else {
            None
        };
        let state_before = if metered {
            unsafe { (self.stance.inspect)(module, target_ptr) }
        } else {
            None
        };
        // A hit that will break the meter carries enough poise damage to spend the enemy's whole
        // stance, as the game's own hits do, so that its processor breaks the stance itself and
        // the enemy plays the real stance break rather than a flinch.
        if metered && crate::stance::peek(tracked, reaction) == crate::stance::Outcome::Staggered {
            let poise = real_before
                .filter(|stance| *stance > 0.)
                .map_or(BREAK_POISE, |stance| stance + 1.);
            for offset in crate::hit_watch::OFFSET_POISE {
                request.f32(offset, poise);
            }
            // The stance routine multiplies the poise at 0x100 by this float.
            request.f32(STANCE_SCALE_OFFSET, 1.);
            request.bytes[crate::hit_watch::OFFSET_LEVEL] = BREAK_LEVEL;
        }
        // Native call owns its temporary request only for this synchronous call.
        {
            let _own = crate::hit_watch::OwnCall::begin();
            unsafe {
                (self.process)(
                    module as *mut c_void,
                    source_ptr as *const c_void,
                    request_ptr,
                    0,
                    0,
                );
            }
        }
        // The closure returns only copied observations. No SDK references span
        // the feedback calls; native cleanup also runs on a receipt failure.
        let result = (|| {
            let initial = observe()?;
            let actual_delta = hp_before.saturating_sub(initial.hp).max(0);
            let mut feedback_dispatched = false;
            // Read while the observation above still proves the target is the same character;
            // a reaction may replace it, so the game's stance is never touched after that.
            let real_processed = if metered {
                unsafe { (self.stance.read)(module, target_ptr) }
            } else {
                None
            };
            let state_processed = if metered {
                unsafe { (self.stance.inspect)(module, target_ptr) }
            } else {
                None
            };
            let mut real_left;
            let mut decision = None;
            let mut previous_flags = None;
            let mut flags_at_stagger = None;
            let mut real_after_react = None;
            let mut spent = false;
            let mut state_after_spend = None;
            if actual_delta > 0 {
                // Notifications reflect actual loss, including a native clamp.
                // Neither callee recalculates HP or requests a player attack.
                request.i32(0x228, actual_delta);
                // A hit that leaves the enemy's stance standing still counts as a hit, but only a
                // stance break asks the game for the stagger animation.
                let (outcome, taken) = crate::stance::hit_total(tracked, reaction);
                decision = Some((outcome, taken));
                // A hit that breaks the stance leaves the game's value alone for now: the game's
                // reaction spends it with the poise damage in the request, which is what makes it
                // break the stance for real. Any other hit gets back what the processor took.
                real_left = if outcome == crate::stance::Outcome::Staggered {
                    real_processed
                } else {
                    unsafe {
                        self.settle_stance(outcome, module, target_ptr, real_before, real_processed)
                    }
                };
                if outcome == crate::stance::Outcome::Staggered {
                    // A stance break overrides the enemy's own animation windows for the moment
                    // the stagger is requested: no hyper armor, and a forced break.
                    if metered {
                        // The game's own order: the stance routine spends the enemy's stance with
                        // the request's poise first, and marks it broken when it runs out. Only
                        // then does the reaction pick the stagger.
                        spent = unsafe {
                            (self.stance.spend)(module, target_ptr, source_ptr, request_ptr)
                        };
                        state_after_spend = unsafe { (self.stance.inspect)(module, target_ptr) };
                        previous_flags =
                            unsafe { (self.stance.flags)(module, target_ptr, Some((false, true))) };
                        flags_at_stagger = previous_flags;
                    }
                    unsafe {
                        (self.react)(module as *mut c_void, request_ptr);
                    }
                }
                // Reaction can replace a character or reset its health for a
                // new phase. Do not notify a retired module or carry old lethal
                // feedback across that reset. Observation revalidates identity.
                if observe().is_ok_and(|after| {
                    after.handle == initial.handle
                        && after.character_id == initial.character_id
                        && after.max_hp == initial.max_hp
                        && after.hp <= initial.hp
                }) {
                    // Same character still: put its animation flags back. A reaction that
                    // replaced it leaves nothing to restore, and the old pointer is not touched.
                    if previous_flags.is_some() {
                        unsafe { (self.stance.flags)(module, target_ptr, previous_flags) };
                    }
                    unsafe {
                        (self.notify)(
                            module as *mut c_void,
                            source_ptr as *const c_void,
                            request_ptr,
                            0,
                            hp_before,
                            0,
                        );
                    }
                    feedback_dispatched = true;
                    // The game may spend the poise in either the reaction or the notification,
                    // so only now, once both ran and the same character is still there, is a
                    // stance nothing spent spent directly.
                    if metered
                        && decision.is_some_and(|(outcome, _)| {
                            outcome == crate::stance::Outcome::Staggered
                        })
                        && observe().is_ok_and(|after| {
                            after.handle == initial.handle
                                && after.character_id == initial.character_id
                                && after.max_hp == initial.max_hp
                        })
                    {
                        let (read, left) =
                            unsafe { self.finish_break(module, target_ptr, real_before) };
                        real_after_react = read;
                        real_left = left.or(real_left);
                    }
                }
            } else {
                // No HP was lost, so nothing counts as a hit: give back any stance the game took.
                real_left = unsafe {
                    self.settle_stance(
                        crate::stance::Outcome::Held,
                        module,
                        target_ptr,
                        real_before,
                        real_processed,
                    )
                };
            }
            if metered || actual_delta > 0 {
                let (damage, capacity) = match reaction {
                    crate::stance::Reaction::Stance {
                        damage, capacity, ..
                    } => (Some(damage), Some(capacity)),
                    crate::stance::Reaction::Always => (None, None),
                };
                crate::stance::record_trace(crate::stance::Trace {
                    target: tracked,
                    damage,
                    capacity,
                    taken: decision.map_or(0., |(_, taken)| taken),
                    outcome: decision.map_or(crate::stance::Outcome::Held, |(outcome, _)| outcome),
                    real_before,
                    real_processed,
                    real_left,
                    state_before,
                    state_processed,
                    flags_at_stagger,
                    real_after_react,
                    spent,
                    state_after_spend,
                });
            }
            // A native reaction can advance scripted death/phase state after
            // the HP processor. Preserve both stages; a later scripted death
            // may still occur after this synchronous receipt.
            let post_feedback = observe().ok();
            let latest = post_feedback.unwrap_or(initial);
            Ok(Receipt {
                source,
                target,
                requested_damage: hp_damage,
                hp_before,
                max_hp_before: initial.max_hp,
                hp_after_processor: initial.hp,
                hp_after: latest.hp,
                actual_delta,
                killed: latest.hp <= 0 || latest.death_flag,
                boss,
                position: latest.position,
                last_hit_by: latest.last_hit_by,
                protection_before,
                feedback_dispatched,
                post_feedback,
                gauge_mirror: None,
                referred_damage: None,
                affinity: None,
                element: None,
            })
        })();
        unsafe {
            (self.destruct)(request_ptr);
        }
        result
    }

    /// After the stagger was requested with the poise damage in the request: if the game spent
    /// none of the enemy's stance, its reaction ignored that poise, so spend it directly so the
    /// enemy at least flinches. A stance the reaction did spend, or reset, is left exactly as the
    /// game left it. Returns the stance as read after the reaction and the notification, and what it holds now.
    unsafe fn finish_break(
        &self,
        module: usize,
        target: usize,
        before: Option<f32>,
    ) -> (Option<f32>, Option<f32>) {
        let Some(current) = (unsafe { (self.stance.read)(module, target) }) else {
            return (None, None);
        };
        if let Some(before) = before
            && before > 0.
            && (current - before).abs() < 1.0e-3
        {
            unsafe { (self.stance.write)(module, target, 0.) };
            return (Some(current), Some(0.));
        }
        (Some(current), Some(current))
    }

    /// Leaves the game's own stance where the meter's outcome says it belongs, and returns what
    /// it now holds. Values that could not be read are never written.
    unsafe fn settle_stance(
        &self,
        outcome: crate::stance::Outcome,
        module: usize,
        target: usize,
        before: Option<f32>,
        current: Option<f32>,
    ) -> Option<f32> {
        let (Some(before), Some(current)) = (before, current) else {
            return current;
        };
        match crate::stance::real_stance_after(outcome, before, current) {
            Some(value) => {
                unsafe { (self.stance.write)(module, target, value) };
                Some(value)
            }
            None => Some(current),
        }
    }

    unsafe fn referred_owner(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
    ) -> Option<ReferredSnapshot> {
        let (body_actor, link, owners) = {
            let world = unsafe { WorldChrMan::instance() }.ok()?;
            let body = world.chr_ins_by_handle(&target)?;
            if body.field_ins_handle != target {
                return None;
            }
            let body_actor = links::actor(body);
            let link = links::body_link(body_actor)?;
            let frontend = unsafe { CSFeManImp::instance() }.ok()?;
            let owners = frontend
                .boss_health_displays
                .iter()
                .filter(|display| link.gauges.contains(&display.fmg_id))
                .filter_map(|display| {
                    let chr = world.chr_ins_by_handle(&display.field_ins_handle)?;
                    if chr.field_ins_handle != display.field_ins_handle
                        || crate::combat_targets::readiness_rejection(chr).is_some()
                    {
                        return None;
                    }
                    Some(ReferredOwner {
                        handle: display.field_ins_handle,
                        entity_id: chr.event_entity_id,
                        npc_id: chr.npc_id,
                        npc_param_id: chr.npc_param_id,
                        block_id: chr.field_ins_handle.block_id.0,
                        fmg_id: display.fmg_id,
                        ready: true,
                        hp: chr.modules.data.hp,
                    })
                })
                .collect::<Vec<_>>();
            (body_actor, link, owners)
        };
        let owner = linked_referred_owner(
            target,
            body_actor.entity,
            body_actor.model,
            body_actor.block,
            body_actor.param,
            &owners,
        )?;
        let resolved = unsafe { self.resolve_characters(source, owner.handle) }.ok()?;
        let (data, max_hp) = {
            let chr = unsafe { &*(resolved.target as *const ChrIns) };
            (
                chr.modules.data.as_ref() as *const _ as usize,
                chr.modules.data.max_hp,
            )
        };
        (resolved.hp == owner.hp).then_some(ReferredSnapshot {
            owner,
            link,
            target_ptr: resolved.target,
            module: resolved.module,
            data,
            max_hp,
        })
    }

    unsafe fn forward_referred_damage(
        &self,
        source: FieldInsHandle,
        snapshot: ReferredSnapshot,
        loss: i32,
    ) -> Result<ReferredDamage, &'static str> {
        let owner = snapshot.owner;
        let current =
            unsafe { self.read_receipt(owner.handle, snapshot.target_ptr, snapshot.module)? };
        if current.max_hp != snapshot.max_hp {
            return Err("native referred damage controller maximum health changed");
        }
        let missing = missing_referred_damage(loss, owner.hp, current.hp)?;
        let mut result = ReferredDamage {
            owner: owner.handle,
            hp_before: owner.hp,
            hp_before_forwarding: current.hp,
            forwarded_damage: 0,
            hp_after: current.hp,
            actual_delta: 0,
            killed: current.hp <= 0 || current.death_flag,
            feedback_dispatched: false,
        };
        if missing == 0 || result.killed {
            return Ok(result);
        }
        // Body feedback may advance encounter scripts. Revalidate the current
        // registered controller and its exact instance/module before forwarding.
        let registered = unsafe { CSFeManImp::instance() }.is_ok_and(|frontend| {
            frontend.boss_health_displays.iter().any(|display| {
                display.fmg_id == owner.fmg_id && display.field_ins_handle == owner.handle
            })
        });
        if !registered {
            return Err("native referred damage controller registration changed");
        }
        let resolved = unsafe { self.resolve_characters(source, owner.handle)? };
        if resolved.target != snapshot.target_ptr
            || resolved.module != snapshot.module
            || resolved.hp != current.hp
        {
            return Err("native referred damage controller instance changed");
        }
        let chr = unsafe { &*(resolved.target as *const ChrIns) };
        if chr.event_entity_id != owner.entity_id
            || chr.npc_id != owner.npc_id
            || chr.npc_param_id != owner.npc_param_id
            || chr.modules.data.as_ref() as *const _ as usize != snapshot.data
        {
            return Err("native referred damage controller identity changed");
        }
        // Unlike a dormant later-phase gauge, this actor owns the final defeat
        // event. Its normal processor/notification must be allowed to reach 0 HP.
        let receipt = unsafe {
            self.process_prepared(
                source,
                owner.handle,
                missing,
                resolved,
                crate::stance::Reaction::Always,
                || self.read_receipt(owner.handle, resolved.target, resolved.module),
            )?
        };
        result.forwarded_damage = missing;
        result.hp_after = receipt.hp_after;
        result.actual_delta = receipt.actual_delta;
        result.killed = receipt.killed;
        result.feedback_dispatched = receipt.feedback_dispatched;
        Ok(result)
    }

    unsafe fn read_receipt(
        &self,
        target: FieldInsHandle,
        target_ptr: usize,
        module: usize,
    ) -> Result<TargetObservation, &'static str> {
        let world = unsafe { WorldChrMan::instance() }
            .map_err(|_| "native damage receipt world unavailable")?;
        let chr = world
            .chr_ins_by_handle(&target)
            .ok_or("native damage receipt target unavailable")?;
        if chr.field_ins_handle != target || chr as *const _ as usize != target_ptr {
            return Err("native damage receipt target changed");
        }
        let bag = chr.modules.as_ref() as *const _ as usize;
        if unsafe { ((bag + DAMAGE_MODULE_OFFSET) as *const usize).read() } != module {
            return Err("native damage receipt module changed");
        }
        Ok(unsafe { self.observe_chr(chr) })
    }

    unsafe fn observe_chr(&self, chr: &ChrIns) -> TargetObservation {
        TargetObservation {
            handle: chr.field_ins_handle,
            character_id: chr.character_id,
            hp: chr.modules.data.hp,
            max_hp: chr.modules.data.max_hp,
            death_flag: chr.chr_flags1c5.death_flag(),
            last_hit_by: chr.last_hit_by,
            position: {
                let p = &chr.modules.physics.position;
                [p.0, p.1, p.2]
            },
        }
    }

    unsafe fn protection(&self, chr: &ChrIns) -> ProtectionObservation {
        let data = chr.modules.data.as_ref() as *const _ as usize;
        ProtectionObservation {
            all_no_dead: unsafe { WorldChrManDbgFlags::instance() }
                .ok()
                .map(|f| f.all_no_dead),
            all_no_damage: unsafe { WorldChrManDbgFlags::instance() }
                .ok()
                .map(|f| f.all_no_damage),
            character_debug_no_dead: unsafe { ((data + 0x19b) as *const u8).read() } & 1 != 0,
            character_debug_no_damage: unsafe { ((data + 0x19b) as *const u8).read() } & 2 != 0,
            character_no_dead_state: unsafe { ((data + 0x19a) as *const u8).read() } & 0x80 != 0,
            animation_immortality: chr
                .modules
                .action_flag
                .action_modifiers_flags
                .set_immortality(),
        }
    }

    unsafe fn resolve_characters(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
    ) -> Result<Resolved, &'static str> {
        let game =
            unsafe { GameMan::instance() }.map_err(|_| "native damage GameMan unavailable")?;
        if game.is_in_online_mode || game.warp_requested {
            return Err("native damage requires offline stable world");
        }
        let session = unsafe { CSSessionManager::instance() }
            .map_err(|_| "native damage session unavailable")?;
        if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None
        {
            return Err("native damage multiplayer session rejected");
        }
        let world =
            unsafe { WorldChrMan::instance() }.map_err(|_| "native damage world unavailable")?;
        let player = world
            .main_player
            .as_ref()
            .ok_or("native damage local player unavailable")?;
        if player.chr_ins.field_ins_handle != source
            || !player.chr_ins.chr_flags1c8.is_active()
            || !player.chr_ins.chr_flags1c8.update_tasks_registered()
            || player.chr_ins.modules.data.hp <= 0
            || player.chr_ins.chr_flags1c5.death_flag()
        {
            return Err("native damage source is not the live local player");
        }
        let chr = world
            .chr_ins_by_handle(&target)
            .ok_or("native damage target unavailable")?;
        if chr.field_ins_handle != target {
            return Err("native damage target handle changed");
        }
        // Target publications are only observations. A prior queued hit can run
        // a phase script before this one: recheck protection at the mutation,
        // before reading modules or entering the already-calculated HP processor.
        if let Some(reason) = crate::combat_targets::readiness_rejection(chr) {
            return Err(reason);
        }
        if !unsafe { chr.chr_set_entry.as_ref() }
            .chr_ins
            .is_some_and(|entry| std::ptr::eq(entry.as_ptr(), chr))
        {
            return Err("native damage target set entry changed");
        }
        let eligibility = crate::combat_targets::eligibility(chr, player.chr_ins.team_type);
        if let Some(reason) = eligibility.rejection() {
            return Err(reason);
        }
        if chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag() || chr.modules.data.max_hp <= 0
        {
            return Err("native damage target is not alive");
        }
        let target_ptr = chr as *const _ as usize;
        let bag = chr.modules.as_ref() as *const _ as usize;
        // Layout independently established by the pinned module container and
        // exact native callers; reject a different module class or owner.
        let module = unsafe { ((bag + DAMAGE_MODULE_OFFSET) as *const usize).read() };
        if module == 0 || module % 8 != 0 {
            return Err("native damage module pointer rejected");
        }
        let vtable = unsafe { (module as *const usize).read() };
        let owner = unsafe { ((module + 8) as *const usize).read() };
        if owner != target_ptr
            || !vtable.checked_sub(self.base).is_some_and(|rva| {
                verified_damage_module_rva(
                    rva,
                    eligibility.enemy_class,
                    eligibility.player_combatant(),
                )
            })
        {
            return Err("native damage requires the verified combat character damage module");
        }
        // Read-only observations of the exact native nonlethal predicate. Do
        // not disable immortality: it can protect a scripted enemy transition.
        let protection = unsafe { self.protection(chr) };
        // Classified while alive: a boss-health registration can clear on death.
        let boss = crate::combat_targets::boss_encounter(chr);
        let a = player.chr_ins.modules.physics.position;
        let b = chr.modules.physics.position;
        let direction = hit_direction([a.0, a.1, a.2], [b.0, b.1, b.2])
            .ok_or("native damage hit direction rejected")?;
        Ok(Resolved {
            source: &player.chr_ins as *const _ as usize,
            target: target_ptr,
            module,
            hp: chr.modules.data.hp,
            protection,
            direction,
            boss,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn elements_scale_only_by_bounded_absorption() {
        for (kind, element) in [
            (3, Element::Fire),
            (4, Element::Lightning),
            (5, Element::Magic),
            (6, Element::Holy),
        ] {
            for (level, physical, share) in [(1, 0.90, 0.15), (2, 0.85, 0.22), (3, 0.80, 0.30)] {
                assert_eq!(
                    element_spec(kind, level),
                    Some((
                        element,
                        Split {
                            physical,
                            element: share
                        }
                    ))
                );
            }
            assert!(element_spec(kind, 0).is_none());
            assert!(element_spec(kind, 4).is_none());
        }
        for kind in [0, 1, 2, 7] {
            assert!(element_spec(kind, 1).is_none());
        }
        let iii = ELEMENT_SPLITS[2];
        // A 500 hit becomes 400 physical and 150 element before absorption.
        assert_eq!(element_damage(500, iii, 1., 1.), Some((400, 550)));
        assert_eq!(element_damage(500, iii, 0.8, 1.), Some((400, 520)));
        assert_eq!(element_damage(500, iii, 1.2, 1.), Some((400, 580)));
        assert_eq!(element_damage(500, iii, 0., 1.), Some((400, 400)));
        assert_eq!(element_damage(500, iii, 2.5, 1.), Some((400, 700)));
        assert_eq!(
            element_damage(100, ELEMENT_SPLITS[0], 1., 1.),
            Some((90, 105))
        );
        for cut in [-0.1, 3.1, f32::NAN, f32::INFINITY] {
            assert_eq!(element_damage(100, iii, cut, 1.), None);
        }
        assert_eq!(element_damage(0, iii, 1., 1.), None);
        // A Magic-Shrouding tear (x1.2) raises only the elemental part.
        assert_eq!(element_damage(500, iii, 1., 1.2), Some((400, 580)));
        assert_eq!(element_damage(500, iii, 0.8, 1.5), Some((400, 580)));
        for boost in [0.9, 2.1, f64::NAN] {
            assert_eq!(element_damage(500, iii, 1., boost), None);
        }
        assert_eq!(element_damage(1, iii, 0., 1.), Some((1, 1)));
        assert_eq!(
            element_damage(MAX_DAMAGE, iii, 2., 1.).map(|(_, total)| total),
            Some(MAX_DAMAGE)
        );
    }
    #[test]
    fn physick_raises_buildup_within_bounds() {
        assert_eq!(physick_buildup(50, 1.), 50);
        assert_eq!(physick_buildup(50, 1.25), 63);
        assert_eq!(physick_buildup(65, 1.25), 81);
        assert_eq!(physick_buildup(50, 0.5), 50);
        assert_eq!(physick_buildup(50, 9.), 75);
        assert_eq!(physick_buildup(50, f64::NAN), 50);
    }

    #[test]
    fn status_levels_are_bounded_and_frost_damage_uses_only_its_native_debuff() {
        for kind in 0..3 {
            for (level, power) in [(1, 35), (2, 50), (3, 65)] {
                assert_eq!(status_spec(kind, level).unwrap().2, power);
            }
            assert!(status_spec(kind, 0).is_none());
            assert!(status_spec(kind, 4).is_none());
        }
        assert!(status_spec(3, 1).is_none());
        assert!((frost_damage_factor(260, 1.2) - 1.2).abs() < 0.00001);
        for (state, value) in [(2, 1.2), (6, 1.2), (260, f32::NAN), (260, 100.), (260, -1.)] {
            assert_eq!(frost_damage_factor(state, value), 1.);
        }
    }
    thread_local! {
        static STATUS_CALLS: std::cell::RefCell<(usize, usize, usize, bool)> = const { std::cell::RefCell::new((0, 0, 0, true)) };
    }
    unsafe extern "system" fn status_construct(p: *mut c_void) -> *mut c_void {
        unsafe {
            *(p as *mut Request) = Request::zeroed();
        }
        STATUS_CALLS.with(|v| v.borrow_mut().0 += 1);
        p
    }
    unsafe extern "system" fn status_destruct(_: *mut c_void) {
        STATUS_CALLS.with(|v| v.borrow_mut().2 += 1);
    }
    unsafe extern "system" fn status_origin(
        out: *mut c_void,
        request: *const c_void,
    ) -> *mut c_void {
        let request = unsafe { &*(request as *const Request) };
        let source = u64::from_le_bytes(request.bytes[0x1d8..0x1e0].try_into().unwrap());
        let target = u64::from_le_bytes(request.bytes[0x1e0..0x1e8].try_into().unwrap());
        STATUS_CALLS.with(|v| v.borrow_mut().3 &= source == 0x1234 && target == 0x5678);
        unsafe {
            *(out as *mut [u8; 16]) = [0x5a; 16];
        }
        out
    }
    unsafe extern "system" fn status_apply(
        target: *mut c_void,
        effect: i32,
        source: *const c_void,
        flag: u8,
        origin: *const c_void,
        info: *mut c_void,
        a: u8,
        b: u8,
        c: u8,
    ) -> u8 {
        let info = unsafe { &*(info as *const StatusInfo) };
        let coefficient = f32::from_le_bytes(info.bytes[0x38..0x3c].try_into().unwrap());
        let valid = target as usize == 0x5678
            && source as usize == 0x1234
            && effect == 6500
            && [flag, a, b, c] == [0; 4]
            && unsafe { *(origin as *const [u8; 16]) } == [0x5a; 16]
            && (coefficient - 0.7).abs() < 0.000001
            && info.bytes[0x1c..0x38].iter().all(|b| *b == 0)
            && (0..0x1c)
                .step_by(4)
                .all(|i| info.bytes[i..i + 4] == 1_f32.to_le_bytes())
            && info.bytes[0x40] == 0
            && info.bytes[0x41] == 1;
        STATUS_CALLS.with(|v| {
            let mut v = v.borrow_mut();
            v.1 += 1;
            v.3 &= valid;
        });
        0 // Native false after non-proc buildup is still consumed once.
    }
    #[test]
    fn native_status_dispatch_keeps_exact_source_metadata_and_cleans_up_once() {
        STATUS_CALLS.with(|v| *v.borrow_mut() = (0, 0, 0, true));
        let resolved = Resolved {
            source: 0x1234,
            target: 0x5678,
            module: 0,
            hp: 100,
            boss: false,
            direction: [0., 0., 1.],
            protection: ProtectionObservation {
                all_no_dead: Some(false),
                all_no_damage: Some(false),
                character_debug_no_dead: false,
                character_debug_no_damage: false,
                character_no_dead_state: false,
                animation_immortality: false,
            },
        };
        unsafe {
            dispatch_affinity(
                status_construct,
                status_destruct,
                status_origin,
                status_apply,
                resolved,
                6500,
                50,
                35,
            );
        }
        STATUS_CALLS.with(|v| assert_eq!(*v.borrow(), (1, 1, 1, true)));
    }

    #[test]
    fn registered_player_boss_damage_uses_its_verified_module_without_accepting_unknown_classes() {
        assert!(verified_damage_module_rva(
            ENEMY_DAMAGE_VTABLE_RVA,
            true,
            false
        ));
        assert!(verified_damage_module_rva(
            PLAYER_DAMAGE_VTABLE_RVA,
            false,
            true
        ));
        for rva in [
            ENEMY_DAMAGE_VTABLE_RVA,
            PLAYER_DAMAGE_VTABLE_RVA,
            0x2a39a90,
            0,
        ] {
            assert!(!verified_damage_module_rva(rva, false, false));
            assert!(!verified_damage_module_rva(rva, true, true));
        }
        assert!(!verified_damage_module_rva(
            ENEMY_DAMAGE_VTABLE_RVA,
            false,
            true
        ));
        assert!(!verified_damage_module_rva(
            PLAYER_DAMAGE_VTABLE_RVA,
            true,
            false
        ));
    }

    fn shared_owner(handle: u32) -> ReferredOwner<u32> {
        ReferredOwner {
            handle,
            entity_id: GODSKIN_DUO_POOL,
            npc_id: 3560,
            npc_param_id: 0,
            block_id: GODSKIN_DUO_BLOCK,
            fmg_id: GODSKIN_DUO_GAUGE,
            ready: true,
            hp: 26350,
        }
    }

    #[test]
    fn both_godskins_link_to_the_active_shared_pool_including_the_different_model() {
        let pool = shared_owner(96);
        for (body, entity_id, npc_id) in [(95, 13000851, 3560), (97, 13000852, 3570)] {
            assert_eq!(
                linked_referred_owner(body, entity_id, npc_id, GODSKIN_DUO_BLOCK, 0, &[pool]),
                Some(pool)
            );
        }
        // The pool is not itself a fighting body, so forwarding cannot recurse.
        assert_eq!(
            linked_referred_owner(96, GODSKIN_DUO_POOL, 3560, GODSKIN_DUO_BLOCK, 0, &[pool]),
            None
        );
    }

    #[test]
    fn shared_pool_links_require_the_authored_body_controller_map_and_registration() {
        let pool = shared_owner(96);
        for (entity_id, npc_id, block_id) in [
            (13000851, 3570, GODSKIN_DUO_BLOCK),
            (13000852, 3560, GODSKIN_DUO_BLOCK),
            (999, 3560, GODSKIN_DUO_BLOCK),
            (13000851, 3560, 7),
            (13000851, 4760, GODSKIN_DUO_BLOCK),
        ] {
            assert_eq!(
                linked_referred_owner(95, entity_id, npc_id, block_id, 0, &[pool]),
                None
            );
        }
        for unrelated in [
            ReferredOwner { handle: 95, ..pool },
            ReferredOwner {
                entity_id: 999,
                ..pool
            },
            ReferredOwner {
                npc_id: 3570,
                ..pool
            },
            ReferredOwner {
                block_id: 7,
                ..pool
            },
            ReferredOwner {
                fmg_id: 903560000,
                ..pool
            },
            ReferredOwner {
                ready: false,
                ..pool
            },
            ReferredOwner { hp: 0, ..pool },
        ] {
            assert_eq!(
                linked_referred_owner(95, 13000851, 3560, GODSKIN_DUO_BLOCK, 0, &[unrelated]),
                None
            );
        }
    }

    #[test]
    fn duplicate_shared_gauge_slots_are_one_link_but_ambiguous_controllers_are_rejected() {
        let pool = shared_owner(96);
        assert_eq!(
            linked_referred_owner(95, 13000851, 3560, GODSKIN_DUO_BLOCK, 0, &[pool, pool]),
            Some(pool)
        );
        for conflicting in [shared_owner(98), ReferredOwner { hp: 26000, ..pool }] {
            assert_eq!(
                linked_referred_owner(
                    95,
                    13000851,
                    3560,
                    GODSKIN_DUO_BLOCK,
                    0,
                    &[pool, conflicting]
                ),
                None
            );
        }
    }

    #[test]
    fn referred_loss_supplements_native_forwarding_without_double_damage_or_reset_damage() {
        assert_eq!(missing_referred_damage(738, 26350, 26350), Ok(738));
        assert_eq!(missing_referred_damage(738, 26350, 26150), Ok(538));
        assert_eq!(missing_referred_damage(738, 26350, 25612), Ok(0));
        assert_eq!(missing_referred_damage(738, 26350, 25000), Ok(0));
        assert_eq!(missing_referred_damage(738, 500, 0), Ok(0));
        assert_eq!(missing_referred_damage(0, 26350, 26350), Ok(0));
        assert_eq!(missing_referred_damage(-1, 26350, 26350), Ok(0));
        assert!(missing_referred_damage(738, 25000, 26350).is_err());
        assert!(missing_referred_damage(738, 0, 0).is_err());
    }

    // Exercise the production calculated-damage request/callback sequence with
    // native-call stand-ins. There are no live SDK objects or absolute addresses.
    struct TestModule {
        hp: i32,
        max_hp: i32,
        model: u32,
        reaction_reset: Option<(i32, i32, u32)>,
        minimum_hp: i32,
        immune: bool,
        dead: bool,
        contract_valid: bool,
        processed: Vec<i32>,
        reacted: Vec<i32>,
        notified: Vec<(i32, i32)>,
        destroyed: usize,
        /// The game's own stance, when this stand-in has one, and what its processor spends.
        stance: Option<f32>,
        processor_poise: f32,
        stance_at_react: Vec<Option<f32>>,
        stance_at_process: Vec<Option<f32>>,
        /// Whether the game's stance routine runs, and what it was handed.
        spend_works: bool,
        spend_calls: u32,
        scale_at_spend: Vec<f32>,
        /// The poise damage and stagger level each hit's request carried into the processor.
        poise_at_process: Vec<f32>,
        level_at_process: Vec<u8>,
        /// The animation flags the enemy is under, and what they were when it was told to stagger.
        hyper_armor: bool,
        forced_break: bool,
        flags_at_react: Vec<(bool, bool)>,
    }

    impl TestModule {
        fn new(hp: i32) -> Self {
            Self {
                hp,
                max_hp: hp,
                model: 3560,
                reaction_reset: None,
                minimum_hp: 0,
                immune: false,
                dead: false,
                contract_valid: true,
                processed: Vec::new(),
                reacted: Vec::new(),
                notified: Vec::new(),
                destroyed: 0,
                stance: None,
                processor_poise: 0.,
                stance_at_react: Vec::new(),
                stance_at_process: Vec::new(),
                spend_works: true,
                spend_calls: 0,
                scale_at_spend: Vec::new(),
                poise_at_process: Vec::new(),
                level_at_process: Vec::new(),
                hyper_armor: false,
                forced_break: false,
                flags_at_react: Vec::new(),
            }
        }
    }

    unsafe fn test_stance_read(module: usize, _target: usize) -> Option<f32> {
        unsafe { &*(module as *const TestModule) }.stance
    }

    unsafe fn test_stance_write(module: usize, _target: usize, value: f32) {
        unsafe { &mut *(module as *mut TestModule) }.stance = Some(value);
    }

    unsafe fn test_spend(
        module: usize,
        _target: usize,
        _source: usize,
        request: *mut c_void,
    ) -> bool {
        let state = unsafe { &mut *(module as *mut TestModule) };
        let bytes = unsafe { &(*(request as *const Request)).bytes };
        let poise = f32::from_le_bytes(bytes[0x100..0x104].try_into().unwrap());
        let scale = f32::from_le_bytes(bytes[0x244..0x248].try_into().unwrap());
        state.spend_calls += 1;
        state.scale_at_spend.push(scale);
        if !state.spend_works {
            return false;
        }
        if let Some(stance) = state.stance.as_mut() {
            *stance = (*stance - poise * scale).max(0.);
        }
        true
    }

    unsafe fn test_stance_inspect(module: usize, _target: usize) -> Option<crate::stance::Real> {
        let state = unsafe { &*(module as *const TestModule) };
        state.stance.map(|stance| crate::stance::Real {
            stance,
            stance_max: 80.,
            toughness: 0.,
            damage_level: 0,
            hyper_armor: state.hyper_armor,
            forced_break: state.forced_break,
            anim: -1,
            broken_lock: u8::from(state.stance == Some(0.)),
        })
    }

    unsafe fn test_stance_flags(
        module: usize,
        _target: usize,
        set: Option<(bool, bool)>,
    ) -> Option<(bool, bool)> {
        let state = unsafe { &mut *(module as *mut TestModule) };
        let previous = (state.hyper_armor, state.forced_break);
        if let Some((hyper_armor, forced_break)) = set {
            state.hyper_armor = hyper_armor;
            state.forced_break = forced_break;
        }
        Some(previous)
    }

    fn request_damage(request: &Request) -> i32 {
        i32::from_le_bytes(request.bytes[0x228..0x22c].try_into().unwrap())
    }

    unsafe extern "system" fn test_construct(request: *mut c_void) -> *mut c_void {
        unsafe { *(request as *mut Request) = Request::zeroed() };
        request
    }

    unsafe extern "system" fn test_process(
        module: *mut c_void,
        source: *const c_void,
        request: *mut c_void,
        dead: u8,
        suppress_hp: u8,
    ) {
        let state = unsafe { &mut *(module as *mut TestModule) };
        let request = unsafe { &mut *(request as *mut Request) };
        let damage = request_damage(request);
        let request_source = u64::from_le_bytes(request.bytes[0x1d8..0x1e0].try_into().unwrap());
        state.contract_valid &= dead == 0
            && suppress_hp == 0
            && valid_damage(damage)
            && source as usize == 0x1234
            && request_source == source as u64
            && request.bytes[0x267] & 8 != 0;
        state.processed.push(damage);
        state.stance_at_process.push(state.stance);
        let poise = f32::from_le_bytes(request.bytes[0x20..0x24].try_into().unwrap());
        state.poise_at_process.push(poise);
        state.level_at_process.push(request.bytes[0x24]);
        if let Some(stance) = state.stance.as_mut() {
            *stance = (*stance - state.processor_poise).max(0.);
        }
        if !state.immune {
            state.hp = state.hp.saturating_sub(damage).max(state.minimum_hp);
        }
        // Test-only constructor payload lets destruction account for cleanup.
        // This field is never written by the production request adapter.
        request.ptr(0x238, module as usize);
    }

    unsafe extern "system" fn test_react(module: *mut c_void, request: *mut c_void) {
        let state = unsafe { &mut *(module as *mut TestModule) };
        state
            .reacted
            .push(request_damage(unsafe { &*(request as *const Request) }));
        state.stance_at_react.push(state.stance);
        state
            .flags_at_react
            .push((state.hyper_armor, state.forced_break));
        if let Some((hp, max_hp, model)) = state.reaction_reset.take() {
            state.hp = hp;
            state.max_hp = max_hp;
            state.model = model;
            state.minimum_hp = 0;
            state.dead = false;
        }
    }

    unsafe extern "system" fn test_notify(
        module: *mut c_void,
        source: *const c_void,
        request: *mut c_void,
        dead: u8,
        hp_before: i32,
        suppress_hp: u8,
    ) {
        let state = unsafe { &mut *(module as *mut TestModule) };
        let damage = request_damage(unsafe { &*(request as *const Request) });
        state.contract_valid &= dead == 0
            && suppress_hp == 0
            && source as usize == 0x1234
            && damage == hp_before.saturating_sub(state.hp);
        state.notified.push((damage, hp_before));
        state.dead = state.hp <= 0;
    }

    unsafe extern "system" fn test_destruct(request: *mut c_void) {
        let request = unsafe { &*(request as *const Request) };
        let module = u64::from_le_bytes(request.bytes[0x238..0x240].try_into().unwrap());
        unsafe { (*(module as *mut TestModule)).destroyed += 1 };
    }

    fn test_sink() -> Sink {
        Sink {
            base: 0,
            construct: test_construct,
            destruct: test_destruct,
            process: test_process,
            react: test_react,
            notify: test_notify,
            stance: StanceIo {
                read: test_stance_read,
                write: test_stance_write,
                inspect: test_stance_inspect,
                flags: test_stance_flags,
                spend: test_spend,
            },
        }
    }

    fn test_handle(selector: u32) -> FieldInsHandle {
        FieldInsHandle {
            selector: eldenring::cs::FieldInsSelector(selector),
            block_id: eldenring::cs::BlockId(GODSKIN_DUO_BLOCK),
        }
    }

    fn test_resolved(state: &mut TestModule) -> Resolved {
        Resolved {
            source: 0x1234,
            target: 0x5678,
            module: state as *mut TestModule as usize,
            hp: state.hp,
            protection: ProtectionObservation {
                all_no_dead: Some(false),
                all_no_damage: Some(false),
                character_debug_no_dead: false,
                character_debug_no_damage: state.immune,
                character_no_dead_state: false,
                animation_immortality: state.minimum_hp > 0,
            },
            direction: [0.0, 0.0, 1.0],
            boss: true,
        }
    }

    unsafe fn test_observation(module: usize, target: FieldInsHandle) -> TargetObservation {
        let state = unsafe { &*(module as *const TestModule) };
        TargetObservation {
            handle: target,
            character_id: state.model,
            hp: state.hp,
            max_hp: state.max_hp,
            death_flag: state.dead,
            last_hit_by: test_handle(0x10000000),
            position: [0.0, -10.0, 0.0],
        }
    }

    fn test_hit(state: &mut TestModule, target: FieldInsHandle, damage: i32) -> Receipt {
        let resolved = test_resolved(state);
        unsafe {
            test_sink().process_prepared(
                test_handle(0x10000000),
                target,
                damage,
                resolved,
                crate::stance::Reaction::Always,
                || Ok(test_observation(resolved.module, target)),
            )
        }
        .unwrap()
    }

    #[test]
    fn alternating_godskin_body_hits_drain_the_shared_pool_through_native_lethal_feedback() {
        let mut apostle = TestModule::new(6668);
        let mut noble = TestModule::new(8000);
        let mut pool = TestModule::new(26350);
        // Already calculated/scaled body damage from the player's live receipt.
        // Forwarding uses exactly that amount rather than scaling a second time.
        for hit in 0..100 {
            let before = pool.hp;
            let (body, target) = if hit % 2 == 0 {
                (&mut apostle, test_handle(286261343))
            } else {
                (&mut noble, test_handle(286261345))
            };
            // The encounter respawns each visible body while the pool survives.
            if body.hp == 0 {
                body.hp = body.max_hp;
                body.dead = false;
            }
            let body_receipt = test_hit(body, target, 738);
            let loss = body_receipt.actual_delta.min(body_receipt.requested_damage);
            let missing = missing_referred_damage(loss, before, pool.hp).unwrap();
            let pool_receipt = test_hit(&mut pool, test_handle(286261344), missing);
            assert_eq!(pool.hp, before.saturating_sub(loss).max(0));
            assert_eq!(pool_receipt.requested_damage, loss);
            assert!(pool_receipt.feedback_dispatched);
            assert!(pool_receipt.referred_damage.is_none());
            if hit == 1 {
                assert_eq!((apostle.hp, noble.hp, pool.hp), (5930, 7262, 24874));
            }
            if pool_receipt.killed {
                break;
            }
        }
        assert_eq!(pool.hp, 0);
        assert!(pool.dead);
        assert!(pool.contract_valid && apostle.contract_valid && noble.contract_valid);
        assert_eq!(pool.processed.len(), pool.notified.len());
        assert_eq!(pool.processed.len(), pool.reacted.len());
        assert_eq!(pool.processed.len(), pool.destroyed);
        let (last_loss, pre_hit_hp) = *pool.notified.last().unwrap();
        assert_eq!(last_loss, pre_hit_hp);
        assert!(last_loss < 738); // Lethal feedback reports accepted loss, not overkill.
        // The unrelated dormant phase-owner policy still preserves script-owned HP.
        assert_eq!(mirrored_hp(pre_hit_hp, 738), 1);
    }

    fn stance_hit(
        state: &mut TestModule,
        target: FieldInsHandle,
        reaction: crate::stance::Reaction,
    ) -> Receipt {
        let resolved = test_resolved(state);
        unsafe {
            test_sink().process_prepared(
                test_handle(0x10000000),
                target,
                10,
                resolved,
                reaction,
                || Ok(test_observation(resolved.module, target)),
            )
        }
        .unwrap()
    }

    fn metered(damage: f32, capacity: f32) -> crate::stance::Reaction {
        crate::stance::Reaction::Stance {
            damage,
            capacity,
            recovery_ms: 60_000,
        }
    }

    #[test]
    fn only_the_meter_staggers_and_the_games_stance_follows_it() {
        // Malenia's 80-stance row against a 22-stance sword whose hits the game's processor
        // would, left alone, eat into: 30 real stance per hit.
        let target = test_handle(0x6000_0001);
        let mut state = TestModule::new(10_000);
        state.stance = Some(80.);
        state.processor_poise = 30.;
        for hit in 1..=3 {
            stance_hit(&mut state, target, metered(22., 80.));
            assert!(
                state.reacted.is_empty(),
                "hit {hit} leaves the enemy standing"
            );
            assert_eq!(
                state.stance,
                Some(80.),
                "whatever the game spent on hit {hit} is given back"
            );
        }
        let receipt = stance_hit(&mut state, target, metered(22., 80.));
        assert!(receipt.feedback_dispatched);
        assert_eq!(state.reacted.len(), 1, "the fourth hit breaks the stance");
        assert_eq!(
            state.poise_at_process[..3],
            [0., 0., 0.],
            "a hit that leaves the enemy standing carries no poise damage"
        );
        assert!(
            state.poise_at_process[3] > 80.,
            "the breaking hit carries more poise damage than the enemy has stance"
        );
        assert_eq!(
            state.level_at_process,
            [SHORT_STAGGER, SHORT_STAGGER, SHORT_STAGGER, BREAK_LEVEL],
            "and the stagger level the game's own hits use"
        );
        assert_eq!(
            state.stance_at_process.last(),
            Some(&Some(80.)),
            "the game processes the breaking hit against the stance the enemy really has"
        );
        assert_eq!(
            state.spend_calls, 1,
            "the game's stance routine runs once, for the break"
        );
        assert_eq!(
            state.scale_at_spend,
            [1.],
            "with the multiplier it reads set"
        );
        assert_eq!(
            state.stance_at_react,
            [Some(0.)],
            "and has spent the stance before the reaction is asked for the stagger, as in the \
             game's own order"
        );
        assert_eq!(state.stance, Some(0.));
    }

    #[test]
    fn a_stance_break_overrides_hyper_armor_for_the_stagger_and_hands_it_back() {
        // A boss mid-attack: hyper armor on, as an animation window sets it.
        let target = test_handle(0x6000_0006);
        let mut state = TestModule::new(10_000);
        state.stance = Some(80.);
        state.hyper_armor = true;
        for hit in 1..=3 {
            stance_hit(&mut state, target, metered(22., 80.));
            assert!(state.reacted.is_empty(), "hit {hit}");
            assert!(
                state.hyper_armor && !state.forced_break,
                "held hits leave the flags alone"
            );
        }
        stance_hit(&mut state, target, metered(22., 80.));
        assert_eq!(state.reacted.len(), 1);
        assert_eq!(
            state.flags_at_react,
            [(false, true)],
            "the stagger is requested with hyper armor lifted and the break forced"
        );
        assert!(
            state.hyper_armor && !state.forced_break,
            "and the enemy's own animation flags are put back straight after"
        );
        // The every-hit stagger never touches them.
        let target = test_handle(0x6000_0007);
        let mut legacy = TestModule::new(10_000);
        legacy.stance = Some(80.);
        legacy.hyper_armor = true;
        stance_hit(&mut legacy, target, crate::stance::Reaction::Always);
        assert_eq!(legacy.flags_at_react, [(true, false)]);
        assert!(legacy.hyper_armor && !legacy.forced_break);
    }

    #[test]
    fn an_unavailable_stance_routine_still_has_the_stance_spent_directly() {
        let target = test_handle(0x6000_0008);
        let mut state = TestModule::new(10_000);
        state.stance = Some(80.);
        state.spend_works = false;
        for _ in 0..3 {
            stance_hit(&mut state, target, metered(22., 80.));
        }
        assert_eq!(state.stance, Some(80.));
        stance_hit(&mut state, target, metered(22., 80.));
        assert_eq!(state.reacted.len(), 1);
        assert_eq!(state.stance, Some(0.), "so the enemy at least flinches");
        // When the routine does spend it, the bridge adds nothing: the value is the game's.
        let target = test_handle(0x6000_0009);
        let mut real = TestModule::new(10_000);
        real.stance = Some(80.);
        for _ in 0..4 {
            stance_hit(&mut real, target, metered(22., 80.));
        }
        assert_eq!(real.spend_calls, 1);
        assert_eq!(real.stance_at_react, [Some(0.)]);
        assert_eq!(real.stance, Some(0.));
    }

    #[test]
    fn a_hit_that_loses_no_hp_gives_stance_back_and_counts_for_nothing() {
        let target = test_handle(0x6000_0002);
        let mut state = TestModule::new(10_000);
        state.stance = Some(80.);
        state.processor_poise = 30.;
        state.immune = true;
        for _ in 0..6 {
            stance_hit(&mut state, target, metered(22., 80.));
        }
        assert!(state.reacted.is_empty() && state.notified.is_empty());
        assert_eq!(state.stance, Some(80.));
        // Immune hits were never counted: it still takes four real hits to break.
        state.immune = false;
        for hit in 1..=3 {
            stance_hit(&mut state, target, metered(22., 80.));
            assert!(state.reacted.is_empty(), "hit {hit}");
        }
        stance_hit(&mut state, target, metered(22., 80.));
        assert_eq!(state.reacted.len(), 1);
    }

    #[test]
    fn the_every_hit_stagger_and_unreadable_stance_are_left_exactly_as_before() {
        let target = test_handle(0x6000_0003);
        let mut legacy = TestModule::new(10_000);
        legacy.stance = Some(80.);
        legacy.processor_poise = 30.;
        stance_hit(&mut legacy, target, crate::stance::Reaction::Always);
        assert_eq!(legacy.reacted.len(), 1, "every accepted hit still staggers");
        assert_eq!(
            legacy.poise_at_process,
            [0.],
            "and carries no poise damage of its own"
        );
        assert_eq!(legacy.level_at_process, [SHORT_STAGGER]);
        assert_eq!(
            legacy.spend_calls, 0,
            "nor does it run the game's stance routine"
        );
        assert_eq!(
            legacy.stance,
            Some(50.),
            "and the game's stance is not ours to touch"
        );
        let target = test_handle(0x6000_0004);
        let mut unreadable = TestModule::new(10_000);
        for _ in 0..4 {
            stance_hit(&mut unreadable, target, metered(22., 80.));
        }
        assert_eq!(
            unreadable.reacted.len(),
            1,
            "the meter still staggers on its own"
        );
        assert_eq!(
            unreadable.stance, None,
            "nothing is written where nothing could be read"
        );
        // An enemy without authored stance flinches on every hit and keeps its stance as it is.
        let target = test_handle(0x6000_0005);
        let mut soft = TestModule::new(10_000);
        soft.stance = Some(0.);
        stance_hit(&mut soft, target, metered(22., -1.));
        stance_hit(&mut soft, target, metered(22., -1.));
        assert_eq!(soft.reacted.len(), 2);
        assert_eq!(soft.stance, Some(0.));
    }

    #[test]
    fn processor_immunity_and_immortality_are_preserved_without_forced_death() {
        let target = test_handle(286261344);
        let mut state = TestModule::new(100);
        state.immune = true;
        let receipt = test_hit(&mut state, target, 738);
        assert_eq!(receipt.actual_delta, 0);
        assert!(!receipt.feedback_dispatched);
        assert!(state.reacted.is_empty() && state.notified.is_empty());
        assert_eq!(state.destroyed, 1);
        state.immune = false;
        state.minimum_hp = 1;
        let receipt = test_hit(&mut state, target, 738);
        assert_eq!(receipt.hp_after, 1);
        assert_eq!(receipt.actual_delta, 99);
        assert!(!receipt.killed && !state.dead);
        assert_eq!(state.notified, [(99, 100)]);
        assert!(state.contract_valid);
    }

    #[test]
    fn a_failed_processor_readback_still_cleans_up_and_does_not_dispatch_unknown_feedback() {
        let mut state = TestModule::new(1000);
        let resolved = test_resolved(&mut state);
        let result = unsafe {
            test_sink().process_prepared(
                test_handle(0x10000000),
                test_handle(286261344),
                738,
                resolved,
                crate::stance::Reaction::Always,
                || Err("target vanished"),
            )
        };
        assert!(result.is_err());
        assert_eq!(state.hp, 262);
        assert_eq!(state.processed, [738]);
        assert!(state.reacted.is_empty() && state.notified.is_empty());
        assert_eq!(state.destroyed, 1);
    }

    #[test]
    fn native_reaction_cannot_notify_a_retired_character_module() {
        let mut state = TestModule::new(1000);
        let target = test_handle(91);
        let resolved = test_resolved(&mut state);
        let mut observations = 0;
        let receipt = unsafe {
            test_sink().process_prepared(
                test_handle(90),
                target,
                100,
                resolved,
                crate::stance::Reaction::Always,
                || {
                    observations += 1;
                    if observations > 1 {
                        Err("phase retired the old module")
                    } else {
                        Ok(test_observation(resolved.module, target))
                    }
                },
            )
        }
        .unwrap();
        assert_eq!(receipt.actual_delta, 100);
        assert_eq!(receipt.applied_delta(), 100);
        assert!(!receipt.feedback_dispatched);
        assert!(receipt.post_feedback.is_none());
        assert_eq!(state.reacted, [100]);
        assert!(state.notified.is_empty());
        assert_eq!(state.destroyed, 1);
    }

    #[test]
    fn malenia_health_reset_and_actor_replacement_do_not_receive_previous_phase_feedback() {
        for reset in [(800, 1000, 2120), (100, 100, 2031)] {
            let mut state = TestModule::new(1000);
            state.model = 2120;
            state.minimum_hp = 1;
            state.reaction_reset = Some(reset);
            let receipt = test_hit(&mut state, test_handle(92), 2000);
            assert_eq!(receipt.actual_delta, 999);
            assert_eq!(receipt.hp_after_processor, 1);
            assert_eq!(receipt.hp_after, reset.0);
            assert!(!receipt.killed && !receipt.feedback_dispatched);
            assert!(state.notified.is_empty());
            assert_eq!(state.destroyed, 1);
            // New phase receives a fresh native request and can die normally.
            let final_hit = test_hit(&mut state, test_handle(92), 2000);
            assert!(final_hit.killed && final_hit.feedback_dispatched);
            assert_eq!(state.notified, [(reset.0, reset.0)]);
            assert!(state.contract_valid);
            assert_eq!(state.destroyed, 2);
        }
    }

    fn link_owner(link: Link, ready: bool, hp: i32) -> ReferredOwner<u32> {
        ReferredOwner {
            handle: 92,
            entity_id: link.owner_entity,
            npc_id: link.owner_model,
            npc_param_id: link.owner_param.unwrap_or(0),
            block_id: link.blocks[0],
            fmg_id: link.gauges[0],
            ready,
            hp,
        }
    }

    #[test]
    fn every_shared_health_body_drains_its_registered_pool_through_native_lethal_feedback() {
        for link in links::LINKS
            .iter()
            .copied()
            .filter(|link| link.kind == Kind::Pool)
        {
            let mut body = TestModule::new(1000);
            body.model = link.body_model as u32;
            body.minimum_hp = i32::from(link.immortal_body);
            let mut pool = TestModule::new(2400);
            pool.model = link.owner_model as u32;
            let mut saw_flat_body = false;
            for _ in 0..20 {
                if body.hp == 0 {
                    body.hp = body.max_hp;
                    body.dead = false;
                }
                let before = pool.hp;
                let owner = link_owner(link, true, before);
                assert_eq!(
                    linked_referred_owner(
                        91,
                        link.body_entity,
                        link.body_model,
                        link.blocks[0],
                        link.body_param.unwrap_or(0),
                        &[owner]
                    ),
                    Some(owner)
                );
                let mut body_receipt = test_hit(&mut body, test_handle(91), 375);
                let loss = referred_hit_loss(&body_receipt, link);
                saw_flat_body |= body_receipt.actual_delta == 0;
                assert!(loss > 0, "{} stalled at body HP {}", link.name, body.hp);
                let missing = missing_referred_damage(loss, before, pool.hp).unwrap();
                let pool_receipt = test_hit(&mut pool, test_handle(92), missing);
                body_receipt.referred_damage = Some(Ok(ReferredDamage {
                    owner: test_handle(92),
                    hp_before: before,
                    hp_before_forwarding: before,
                    forwarded_damage: missing,
                    hp_after: pool.hp,
                    actual_delta: pool_receipt.actual_delta,
                    killed: pool_receipt.killed,
                    feedback_dispatched: pool_receipt.feedback_dispatched,
                }));
                assert!(body_receipt.applied_delta() > 0);
                assert!(body_receipt.applied_delta() <= 375);
                assert!(pool_receipt.feedback_dispatched);
                if pool_receipt.killed {
                    break;
                }
            }
            assert_eq!(pool.hp, 0, "{} retained controller HP", link.name);
            assert!(pool.dead && pool.contract_valid && body.contract_valid);
            if link.immortal_body {
                assert!(
                    saw_flat_body,
                    "{} did not exercise the 1 HP floor",
                    link.name
                );
            }
            assert_eq!(pool.processed.len(), pool.notified.len());
            assert_eq!(pool.processed.len(), pool.destroyed);
        }
    }

    #[test]
    fn shared_health_clamp_supplement_requires_authored_and_observed_immortality() {
        let link = links::LINKS
            .iter()
            .copied()
            .find(|link| link.kind == Kind::Pool && link.immortal_body)
            .unwrap();
        let mut body = TestModule::new(1);
        body.minimum_hp = 1;
        let receipt = test_hit(&mut body, test_handle(91), 500);
        assert_eq!(receipt.actual_delta, 0);
        assert_eq!(referred_hit_loss(&receipt, link), 500);
        for protection in [
            ProtectionObservation {
                all_no_dead: Some(true),
                ..receipt.protection_before
            },
            ProtectionObservation {
                all_no_dead: None,
                ..receipt.protection_before
            },
            ProtectionObservation {
                all_no_damage: Some(true),
                ..receipt.protection_before
            },
            ProtectionObservation {
                all_no_damage: None,
                ..receipt.protection_before
            },
            ProtectionObservation {
                character_debug_no_damage: true,
                ..receipt.protection_before
            },
            ProtectionObservation {
                character_debug_no_dead: true,
                ..receipt.protection_before
            },
            ProtectionObservation {
                animation_immortality: false,
                ..receipt.protection_before
            },
        ] {
            assert_eq!(
                referred_hit_loss(
                    &Receipt {
                        protection_before: protection,
                        ..receipt.clone()
                    },
                    link
                ),
                0
            );
        }
        assert_eq!(
            referred_hit_loss(
                &receipt,
                Link {
                    immortal_body: false,
                    ..link
                }
            ),
            0
        );
        assert_eq!(
            referred_hit_loss(
                &receipt,
                Link {
                    kind: Kind::Phase,
                    ..link
                }
            ),
            0
        );
        let mut immune = TestModule::new(1000);
        immune.immune = true;
        let rejected = test_hit(&mut immune, test_handle(91), 500);
        assert_eq!(referred_hit_loss(&rejected, link), 0);
        assert_eq!(rejected.applied_delta(), 0);
        // The same protection at the 1 HP floor is immunity, not a new hit.
        immune.hp = 1;
        immune.minimum_hp = 1;
        let floor_immune = test_hit(&mut immune, test_handle(91), 500);
        assert_eq!(referred_hit_loss(&floor_immune, link), 0);
        // Controller immunity still belongs to its native processor.
        let result = test_hit(
            &mut immune,
            test_handle(92),
            referred_hit_loss(&receipt, link),
        );
        assert_eq!(result.actual_delta, 0);
        assert!(!result.feedback_dispatched);
    }

    #[test]
    fn all_continuous_phase_pairs_preserve_dormant_health_then_allow_active_native_death() {
        for link in links::LINKS
            .iter()
            .copied()
            .filter(|link| link.kind == Kind::Phase)
        {
            let active = link_owner(link, true, 1000);
            let mut owner = GaugeOwner {
                handle: active.handle,
                entity_id: active.entity_id,
                npc_id: active.npc_id,
                npc_param_id: active.npc_param_id,
                block_id: active.block_id,
                fmg_id: active.fmg_id,
                dormant: true,
                hp: active.hp,
            };
            let mut body = TestModule::new(1000);
            body.model = link.body_model as u32;
            body.minimum_hp = 1;
            let receipt = test_hit(&mut body, test_handle(91), 400);
            assert_eq!(
                linked_gauge_owner(
                    91,
                    link.body_model,
                    link.blocks[0],
                    link.body_entity,
                    link.body_param.unwrap_or(0),
                    &[owner]
                ),
                Some(owner)
            );
            owner.hp = mirrored_gauge_hp(owner.hp, owner.hp, receipt.actual_delta).unwrap();
            assert_eq!(
                owner.hp, 600,
                "{} failed to mirror phase-one loss",
                link.name
            );
            assert_eq!(mirrored_gauge_hp(owner.hp, owner.hp, 2000), Some(1));
            owner.dormant = false;
            assert!(
                linked_gauge_owner(
                    91,
                    link.body_model,
                    link.blocks[0],
                    link.body_entity,
                    link.body_param.unwrap_or(0),
                    &[owner]
                )
                .is_none()
            );
            let mut phase_two = TestModule::new(owner.hp);
            phase_two.model = link.owner_model as u32;
            let lethal = test_hit(&mut phase_two, test_handle(92), 2000);
            assert!(lethal.killed && lethal.feedback_dispatched);
            assert_eq!(phase_two.notified, [(600, 600)]);
            assert!(phase_two.contract_valid);
        }
    }

    #[test]
    fn independent_phase_bars_never_inherit_old_damage_and_both_receive_native_feedback() {
        for (block, first_entity, first_model, second_entity, second_model) in [
            (0x0e00_0000, 14000801, 2030, 14000800, 2031),
            (0x1000_0000, 16000801, 4710, 16000800, 4710),
            (0x1300_0000, 19000810, 2190, 19000800, 2200),
        ] {
            let mut first = TestModule::new(1000);
            first.model = first_model as u32;
            first.minimum_hp = 1;
            let mut second = TestModule::new(5000);
            second.model = second_model as u32;
            let first_hit = test_hit(&mut first, test_handle(91), 2000);
            assert_eq!(first_hit.actual_delta, 999);
            assert!(!first_hit.killed);
            assert!(first_hit.feedback_dispatched);
            let inactive = GaugeOwner {
                handle: 92,
                entity_id: second_entity,
                npc_id: second_model,
                npc_param_id: 0,
                block_id: block,
                fmg_id: 1,
                dormant: true,
                hp: second.hp,
            };
            assert!(
                linked_gauge_owner(91, first_model, block, first_entity, 0, &[inactive]).is_none()
            );
            assert!(
                linked_referred_owner(
                    91,
                    first_entity,
                    first_model,
                    block,
                    0,
                    &[ReferredOwner {
                        handle: 92,
                        entity_id: second_entity,
                        npc_id: second_model,
                        npc_param_id: 0,
                        block_id: block,
                        fmg_id: 1,
                        ready: true,
                        hp: second.hp
                    }]
                )
                .is_none()
            );
            assert_eq!(second.hp, 5000);
            let next_hit = test_hit(&mut second, test_handle(92), 2000);
            assert_eq!(second.hp, 3000);
            assert_eq!(next_hit.actual_delta, 2000);
            assert!(next_hit.feedback_dispatched && !next_hit.killed);
            let final_hit = test_hit(&mut second, test_handle(92), 4000);
            assert!(final_hit.killed && final_hit.feedback_dispatched);
            assert_eq!(second.notified.last(), Some(&(3000, 3000)));
            assert!(first.contract_valid && second.contract_valid);
        }
    }

    #[test]
    fn same_actor_boss_movesets_and_healing_keep_the_ordinary_native_damage_path() {
        for model in [4750, 2130, 4730, 4800, 4511, 4520, 4670, 3050] {
            let mut boss = TestModule::new(2000);
            boss.model = model;
            let first = test_hit(&mut boss, test_handle(91), 500);
            assert_eq!(boss.hp, 1500);
            assert!(first.feedback_dispatched && !first.killed);
            // Native events/animations own a moveset change and any healing.
            boss.hp = 1800;
            let next = test_hit(&mut boss, test_handle(91), 500);
            assert_eq!(next.hp_before, 1800);
            assert_eq!(boss.hp, 1300);
            assert!(next.gauge_mirror.is_none() && next.referred_damage.is_none());
            let lethal = test_hit(&mut boss, test_handle(91), 2000);
            assert!(lethal.killed && lethal.feedback_dispatched);
            assert_eq!(boss.notified.last(), Some(&(1300, 1300)));
            assert!(boss.contract_valid);
        }
    }

    fn owner(handle: u32, npc_id: i32, dormant: bool, hp: i32) -> GaugeOwner<u32> {
        GaugeOwner {
            handle,
            entity_id: 1052520800,
            npc_id,
            npc_param_id: 47601050,
            block_id: 1007488258,
            fmg_id: 904760000,
            dormant,
            hp,
        }
    }

    fn godfrey_owner<H>(handle: H) -> GaugeOwner<H> {
        GaugeOwner {
            handle,
            entity_id: 11050800,
            npc_id: 4721,
            npc_param_id: 47210070,
            block_id: GODFREY_BLOCK,
            fmg_id: GODFREY_GAUGE,
            dormant: true,
            hp: 21903,
        }
    }

    #[test]
    fn godfrey_links_only_its_exact_ashen_capital_phase_pair() {
        // Live: 271581221 fights as c4720; the gauge registers dormant c4721.
        let phase_two = godfrey_owner(271581222);
        let link = |entity, npc, param, block, owners: &[GaugeOwner<u32>]| {
            linked_gauge_owner(271581221, npc, block, entity, param, owners)
        };
        assert_eq!(
            link(11050801, 4720, 47200070, GODFREY_BLOCK, &[phase_two]),
            Some(phase_two)
        );
        assert_eq!(
            link(
                11050801,
                4720,
                47200070,
                GODFREY_BLOCK,
                &[phase_two, phase_two]
            ),
            Some(phase_two)
        );
        for (entity, npc, param, block) in [
            (11000850, 4720, 47200134, 0x0b00_0000), // Golden shade.
            (11050800, 4720, 47200070, GODFREY_BLOCK),
            (11050801, 4721, 47200070, GODFREY_BLOCK),
            (11050801, 4720, 47200000, GODFREY_BLOCK),
            (11050801, 4720, 47200070, 0x0b00_0000),
        ] {
            assert_eq!(link(entity, npc, param, block, &[phase_two]), None);
        }
        for other in [
            GaugeOwner {
                entity_id: 11050801,
                ..phase_two
            },
            GaugeOwner {
                npc_id: 4722,
                ..phase_two
            },
            GaugeOwner {
                npc_param_id: 47210000,
                ..phase_two
            },
            GaugeOwner {
                block_id: 0x0b00_0000,
                ..phase_two
            },
            GaugeOwner {
                fmg_id: GODFREY_GAUGE + 99,
                ..phase_two
            },
            GaugeOwner {
                dormant: false,
                ..phase_two
            },
            GaugeOwner { hp: 1, ..phase_two },
            GaugeOwner { hp: 0, ..phase_two },
        ] {
            assert_eq!(
                link(11050801, 4720, 47200070, GODFREY_BLOCK, &[other]),
                None
            );
        }
        // Conflicting owners or changed evidence for a reused handle are ambiguous.
        for other in [
            GaugeOwner {
                handle: 271581223,
                ..phase_two
            },
            GaugeOwner {
                hp: phase_two.hp - 1,
                ..phase_two
            },
        ] {
            assert_eq!(
                link(11050801, 4720, 47200070, GODFREY_BLOCK, &[phase_two, other]),
                None
            );
        }
    }

    #[test]
    fn dormant_gauge_loss_is_applied_once_and_never_forces_death_or_overwrites_a_reset() {
        assert_eq!(mirrored_gauge_hp(21903, 21903, 864), Some(21039));
        assert_eq!(mirrored_gauge_hp(21903, 21703, 864), Some(21039));
        assert_eq!(mirrored_gauge_hp(21903, 21039, 864), Some(21039));
        assert_eq!(mirrored_gauge_hp(21903, 20000, 864), Some(20000));
        assert_eq!(mirrored_gauge_hp(500, 500, 864), Some(1));
        assert_eq!(mirrored_gauge_hp(500, 1, 864), None);
        assert_eq!(mirrored_gauge_hp(500, 0, 864), None);
        assert_eq!(mirrored_gauge_hp(21039, 21903, 864), None);
    }

    #[test]
    fn godfrey_body_hits_drain_the_gauge_then_active_hoarah_loux_uses_native_lethal_feedback() {
        let body_handle = FieldInsHandle {
            block_id: eldenring::cs::BlockId(GODFREY_BLOCK),
            ..test_handle(271581221)
        };
        let mut phase_two = godfrey_owner(FieldInsHandle {
            selector: eldenring::cs::FieldInsSelector(271581222),
            ..body_handle
        });
        let mut body = TestModule::new(11831);
        body.minimum_hp = 1; // Preserve first-phase native immortality.
        for _ in 0..14 {
            let owner = linked_gauge_owner(
                body_handle,
                4720,
                GODFREY_BLOCK,
                11050801,
                47200070,
                &[phase_two],
            )
            .unwrap();
            let receipt = test_hit(&mut body, body_handle, 864);
            let loss = receipt.actual_delta.min(receipt.requested_damage);
            phase_two.hp = mirrored_gauge_hp(owner.hp, phase_two.hp, loss).unwrap();
            assert_eq!(phase_two.hp, 21903 - (11831 - body.hp));
            assert!(!receipt.killed && !body.dead);
            assert!(receipt.feedback_dispatched);
        }
        assert_eq!((body.hp, phase_two.hp), (1, 10073));
        assert!(body.contract_valid);
        assert_eq!(body.processed.len(), body.destroyed);

        // Model the authored activation: active Hoarah Loux owns the registered
        // gauge and is damaged directly. No further dormant mirror can qualify.
        phase_two.dormant = false;
        assert_eq!(
            linked_gauge_owner(
                phase_two.handle,
                4721,
                GODFREY_BLOCK,
                11050800,
                47210070,
                &[phase_two]
            ),
            None
        );
        assert_eq!(
            linked_gauge_owner(
                body_handle,
                4720,
                GODFREY_BLOCK,
                11050801,
                47200070,
                &[phase_two]
            ),
            None
        );
        let mut hoarah = TestModule::new(phase_two.hp);
        while hoarah.hp > 0 {
            let receipt = test_hit(&mut hoarah, phase_two.handle, 864);
            assert!(receipt.feedback_dispatched);
            assert_eq!(receipt.killed, hoarah.hp == 0);
        }
        assert!(hoarah.dead && hoarah.contract_valid);
        assert_eq!(hoarah.processed.len(), hoarah.notified.len());
        assert_eq!(hoarah.processed.len(), hoarah.reacted.len());
        assert_eq!(hoarah.processed.len(), hoarah.destroyed);
        assert_eq!(hoarah.notified.last(), Some(&(569, 569)));
    }

    #[test]
    fn fire_giant_body_damage_reaches_its_dormant_phase_two_gauge_owner() {
        // Live: body 389021696 (47600050) fights; the gauge belongs to the
        // dormant phase-two character 389021697 (47601050), same model 4760.
        let phase_two = owner(389021697, 4760, true, 43263);
        assert_eq!(
            linked_gauge_owner(
                389021696,
                4760,
                1007488258,
                1052520801,
                47600050,
                &[phase_two]
            ),
            Some(phase_two)
        );
        assert_eq!(mirrored_hp(43263, 648), 42615);
        // The mirror never performs the scripted phase owner's death.
        assert_eq!(mirrored_hp(500, 648), 1);
        assert_eq!(mirrored_hp(500, -5), 500);
    }
    #[test]
    fn gauge_mirror_ignores_registered_unrelated_active_or_ambiguous_owners() {
        let phase_two = owner(2, 4760, true, 43263);
        // A registered target already owns its gauge, e.g. phase two itself.
        assert_eq!(
            linked_gauge_owner(2, 4760, 1007488258, 1052520801, 47600050, &[phase_two]),
            None
        );
        // Another model, another block, or an active registered boss: no link.
        for other in [
            owner(2, 3800, true, 1000),
            GaugeOwner {
                block_id: 7,
                ..phase_two
            },
            owner(2, 4760, false, 43263),
            owner(2, 4760, true, 1),
        ] {
            assert_eq!(
                linked_gauge_owner(1, 4760, 1007488258, 1052520801, 47600050, &[other]),
                None
            );
        }
        // Duplicate display slots for one owner remain one link.
        assert_eq!(
            linked_gauge_owner(
                1,
                4760,
                1007488258,
                1052520801,
                47600050,
                &[phase_two, phase_two]
            ),
            Some(phase_two)
        );
        assert_eq!(
            linked_gauge_owner(
                1,
                4760,
                1007488258,
                1052520801,
                47600050,
                &[phase_two, owner(3, 4760, true, 9)]
            ),
            None
        );
    }
    #[test]
    fn rejects_zero_negative_and_unbounded_damage() {
        assert!(!valid_damage(0));
        assert!(!valid_damage(-1));
        assert!(!valid_damage(i32::MAX));
        assert!(valid_damage(1));
        assert!(valid_damage(MAX_DAMAGE));
    }
    #[test]
    fn request_storage_matches_native_extent_and_alignment() {
        assert_eq!(std::mem::size_of::<Request>(), 0x270);
        assert_eq!(std::mem::align_of::<Request>(), 16);
    }
    #[test]
    fn configures_only_owned_request_fields_and_preserves_native_defaults() {
        let mut request = Request {
            bytes: [0x5a; REQUEST_BYTES],
        };
        request.configure(0x1234, 0x5678, 17, [0.0, 0.0, 1.0]);
        assert_eq!(
            i32::from_le_bytes(request.bytes[0x228..0x22c].try_into().unwrap()),
            17
        );
        assert_eq!(
            f32::from_le_bytes(request.bytes[0..4].try_into().unwrap()),
            17.0
        );
        assert_eq!(
            u64::from_le_bytes(request.bytes[0x1d8..0x1e0].try_into().unwrap()),
            0x1234
        );
        assert_eq!(
            u64::from_le_bytes(request.bytes[0x1e0..0x1e8].try_into().unwrap()),
            0x5678
        );
        assert_eq!(request.bytes[0x24], SHORT_STAGGER);
        assert_eq!(
            f32::from_le_bytes(request.bytes[0x1c8..0x1cc].try_into().unwrap()),
            1.0
        );
        assert_eq!(&request.bytes[0x1cc..0x1d0], &0_f32.to_le_bytes());
        let owned = |i: usize| {
            (0..4).contains(&i)
                || (0x1d8..0x1e8).contains(&i)
                || (0x228..0x22c).contains(&i)
                || (0x1c0..0x1d0).contains(&i)
                || i == 0x24
                || i == 0x267
        };
        assert!(
            request
                .bytes
                .iter()
                .enumerate()
                .all(|(i, b)| owned(i) || *b == 0x5a)
        );
        assert_eq!(request.bytes[0x267], 0x5a | 8);
    }
    #[test]
    fn direction_is_finite_normalized_and_points_from_source_to_target() {
        assert_eq!(
            hit_direction([0.0; 3], [0.0, 0.0, 2.0]),
            Some([0.0, 0.0, 1.0])
        );
        assert_eq!(hit_direction([0.0; 3], [0.0; 3]), None);
        assert_eq!(hit_direction([f32::NAN, 0.0, 0.0], [1.0; 3]), None);
        assert_eq!(hit_direction([f32::MAX; 3], [-f32::MAX; 3]), None);
    }
    #[test]
    fn fingerprint_validation_is_bounded_and_exact() {
        assert!(matches(&[1, 2, 3], 1, &[2, 3]));
        assert!(!matches(&[1, 2, 3], 1, &[2, 4]));
        assert!(!matches(&[1, 2, 3], usize::MAX, &[1]));
        assert!(!matches(&[1], 1, &[2]));
    }
}
