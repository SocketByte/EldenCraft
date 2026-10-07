//! Experimental, exact-build entry into ER's *calculated* damage processor.
//!
//! This deliberately does not call the attack dispatcher or damage calculator:
//! Minecraft supplies the final HP amount. It does not request player animation,
//! write character HP, patch parameters, or retain game pointers. See
//! `../README.md` for compatibility and ownership requirements. Caller owns the
//! fresh guest transaction, target/line-of-sight policy, and gameplay task gate.

use eldenring::cs::{
    CSSessionManager, ChrIns, FieldInsHandle, FieldInsType, GameMan, LobbyState, ProtocolState,
    WorldChrMan, WorldChrManDbgFlags,
};
use fromsoftware_shared::{FromStatic, program::Program};
use pelite::pe64::PeObject;
use std::ffi::c_void;

const CONSTRUCTOR_RVA: usize = 0x529000;
const DESTRUCTOR_RVA: usize = 0x5291b0;
const PROCESS_RVA: usize = 0x448910;
const REACTION_RVA: usize = 0x445ec0;
const NOTIFY_RVA: usize = 0x4486f0;
const ENEMY_DAMAGE_VTABLE_RVA: usize = 0x2a3a0d8;
const DAMAGE_MODULE_OFFSET: usize = 0x98;
const REQUEST_BYTES: usize = 0x270;
pub const MAX_DAMAGE: i32 = 1_000_000;

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
// Smithbox ATKPARAM_DMGTYPE_NEW: 1 = short stagger. This is a deliberately
// bounded host reaction policy, not Minecraft's knockback or ER weapon poise.
const SHORT_STAGGER: u8 = 1;

type Construct = unsafe extern "system" fn(*mut c_void) -> *mut c_void;
type Destruct = unsafe extern "system" fn(*mut c_void);
// Callsite 0x449f03..0x449f1a passes these five args. The fourth is an
// already-dead state and the fifth suppresses HP mutation. Both are false for
// this live-target local call. It returns no documented result; use HP readback.
type Process = unsafe extern "system" fn(*mut c_void, *const c_void, *mut c_void, u8, u8);
type React = unsafe extern "system" fn(*mut c_void, *mut c_void);
// Exact upstream 0x44a16e..0x44a188: module, source, request, previous-dead,
// pre-hit HP, suppress-HP. Keep the complete six-argument caller contract.
type Notify = unsafe extern "system" fn(*mut c_void, *const c_void, *mut c_void, u8, i32, u8);

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
    /// Exact native predicate 0x4379d0 reads data+0x19b bit0.
    pub character_debug_no_dead: bool,
    /// Exact native predicate 0x437ca0 also reads data+0x19a bit7.
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
}

/// Immutable entrypoint addresses, never character/module pointers.
/// Both validated characters of one hit, read on the game thread.
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
}

fn matches(image: &[u8], at: usize, prefix: &[u8]) -> bool {
    at.checked_add(prefix.len())
        .and_then(|end| image.get(at..end))
        == Some(prefix)
}
fn valid_damage(value: i32) -> bool {
    (1..=MAX_DAMAGE).contains(&value)
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

impl Sink {
    /// Resolve only after the engine's complete executable SHA-256 gate passes:
    /// 1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891.
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
        {
            return Err("native damage entrypoint fingerprint mismatch");
        }
        let base = image.as_ptr() as usize;
        Ok(Self {
            base,
            construct: unsafe { std::mem::transmute::<usize, Construct>(base + CONSTRUCTOR_RVA) },
            destruct: unsafe { std::mem::transmute::<usize, Destruct>(base + DESTRUCTOR_RVA) },
            process: unsafe { std::mem::transmute::<usize, Process>(base + PROCESS_RVA) },
            react: unsafe { std::mem::transmute::<usize, React>(base + REACTION_RVA) },
            notify: unsafe { std::mem::transmute::<usize, Notify>(base + NOTIFY_RVA) },
        })
    }

    /// Apply one already-authorized Minecraft transaction, synchronously.
    ///
    /// # Safety
    /// Engine SHA gate passed, current valid foreground offline gameplay task,
    /// fresh target identity/hostility/reach/LOS and guest commit already checked.
    /// No live SDK references may span this call, and no concurrent game writer.
    /// This is experimental until live tests verify feedback, death and rewards.
    /// It requests a short enemy reaction after positive HP loss. It does not
    /// implement Minecraft knockback, guard or upstream dispatcher immunity.
    pub unsafe fn apply(
        &self,
        source: FieldInsHandle,
        target: FieldInsHandle,
        hp_damage: i32,
    ) -> Result<Receipt, &'static str> {
        unsafe { self.apply_world(source, target, hp_damage, None) }
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
        let Resolved {
            source: source_ptr,
            target: target_ptr,
            module,
            hp: hp_before,
            protection: protection_before,
            mut direction,
            boss,
        } = unsafe { self.resolve_characters(source, target)? };
        // The target was resolved and validated on this game-thread call. Tune
        // encounter HP pressure by the pinned SDK's typed NPC param identity.
        let npc_param_id = unsafe { (&*(target_ptr as *const ChrIns)).npc_param_id };
        let hp_damage = (hp_damage as f64
            * crate::campaign_runtime::enemy_damage_multiplier(npc_param_id))
        .round()
        .clamp(1., MAX_DAMAGE as f64) as i32;
        if let Some(origin) = event_origin {
            let position = unsafe { (&*(target_ptr as *const ChrIns)).modules.physics.position };
            if let Some(value) = hit_direction(origin, [position.0, position.1, position.2]) {
                direction = value;
            } else if origin.iter().any(|v| !v.is_finite()) {
                return Err("world damage origin invalid");
            }
        }
        let mut request = Request::zeroed();
        let request_ptr = (&mut request as *mut Request).cast::<c_void>();
        unsafe {
            (self.construct)(request_ptr);
        }
        request.configure(source_ptr, target_ptr, hp_damage, direction);
        // Native call owns its temporary request only for this synchronous call.
        unsafe {
            (self.process)(
                module as *mut c_void,
                source_ptr as *const c_void,
                request_ptr,
                0,
                0,
            );
        }
        // The closure returns only copied observations. No SDK references span
        // the feedback calls; native cleanup also runs on a receipt failure.
        let result = (|| {
            let initial = unsafe { self.read_receipt(target, target_ptr, module)? };
            let actual_delta = hp_before.saturating_sub(initial.hp).max(0);
            if actual_delta > 0 {
                // Notifications reflect actual loss, including a native clamp.
                // Neither callee recalculates HP or requests a player attack.
                request.i32(0x228, actual_delta);
                unsafe {
                    (self.react)(module as *mut c_void, request_ptr);
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
            }
            // A native reaction can advance scripted death/phase state after
            // the HP processor. Preserve both stages; a later scripted death
            // may still occur after this synchronous receipt.
            let post_feedback = unsafe { self.read_receipt(target, target_ptr, module) }.ok();
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
                feedback_dispatched: actual_delta > 0,
                post_feedback,
            })
        })();
        unsafe {
            (self.destruct)(request_ptr);
        }
        result
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
        if chr as *const _ as usize != target_ptr {
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
            character_debug_no_dead: unsafe { ((data + 0x19b) as *const u8).read() } & 1 != 0,
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
            || player.chr_ins.modules.data.hp <= 0
            || player.chr_ins.chr_flags1c5.death_flag()
        {
            return Err("native damage source is not the live local player");
        }
        let chr = world
            .chr_ins_by_handle(&target)
            .ok_or("native damage target unavailable")?;
        if let Some(reason) = crate::combat_targets::target_rejection(chr, player.chr_ins.team_type)
        {
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
        if vtable != self.base + ENEMY_DAMAGE_VTABLE_RVA || owner != target_ptr {
            return Err("native damage requires the verified enemy damage module");
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
