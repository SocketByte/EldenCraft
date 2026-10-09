//! Real Minecraft raised-shield intent at the verified native HP processor.
//! The native weapon's absorption is deliberately not involved. This modifies
//! the final positive HP loss before subtraction, including otherwise fatal hits.
use eldenring::cs::{ChrIns, PlayerIns};
use fromsoftware_shared::program::Program;
use ilhook::x64::{HookFlags, Registers, hook_closure_jmp_back};
use pelite::pe64::PeObject;
use std::{
    collections::VecDeque,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

const PROCESS_RVA: usize = 0x448910;
const PREFIX: [u8; 23] = [
    0x4c, 0x8b, 0xdc, 0x55, 0x53, 0x56, 0x57, 0x41, 0x56, 0x41, 0x57, 0x49, 0x8d, 0x6b, 0x88, 0x48,
    0x81, 0xec, 0x48, 0x01, 0x00, 0x00, 0x48,
];
const LEASE_MS: u64 = 100;
static INSTALLED: AtomicBool = AtomicBool::new(false);
static FAULTED: AtomicBool = AtomicBool::new(false);
static GENERATION: AtomicU64 = AtomicU64::new(0);
static PATCH: OnceLock<(usize, [u8; 23])> = OnceLock::new();
static PERMIT: Mutex<Option<Permit>> = Mutex::new(None);
static BLOCKS: AtomicU64 = AtomicU64::new(0);
static BEFORE: AtomicI32 = AtomicI32::new(0);
static AFTER: AtomicI32 = AtomicI32::new(0);
static HIT_SEQUENCE: AtomicU64 = AtomicU64::new(0);
static HIT_TRACES: Mutex<VecDeque<HitTrace>> = Mutex::new(VecDeque::new());
const MAX_HIT_TRACES: usize = 32;

/// Bounded per-hit evidence, drained by the existing asynchronous native logger.
/// Position/direction values are copies; no native pointers escape the hook.
#[derive(Clone, Debug)]
#[allow(dead_code)] // Every field is intentionally included in Debug telemetry.
pub struct HitTrace {
    pub sequence: u64,
    pub damage: i32,
    pub remaining: i32,
    pub decision: &'static str,
    pub source_npc_param: Option<i32>,
    pub source_position: Option<[f32; 3]>,
    pub incoming: Option<[f32; 3]>,
    /// Existing verified request reaction vector; diagnostic only. A native
    /// reaction vector is not verified contact-origin/approach evidence.
    pub request_reaction: [f32; 3],
    pub forward: Option<[f32; 3]>,
    pub incoming_dot: Option<f32>,
    pub permit_age_ms: Option<u64>,
    pub permit_valid: bool,
    /// Full combat-context failure this hit was still resolved through, using
    /// a fresh raised-shield permit (focus or menu check only).
    pub context_fallback: Option<&'static str>,
    pub server_age_ms: Option<u64>,
    pub server_stamina: Option<f64>,
    pub server_shield_ready: Option<bool>,
    pub server_using_item: Option<bool>,
    pub server_guard_ack: Option<u64>,
    pub server_guard_damage: Option<f64>,
}

pub fn take_hit_traces() -> Vec<HitTrace> {
    HIT_TRACES
        .try_lock()
        .map(|mut traces| traces.drain(..).collect())
        .unwrap_or_default()
}

fn retain_trace(traces: &mut VecDeque<HitTrace>, trace: HitTrace) {
    traces.push_back(trace);
    while traces.len() > MAX_HIT_TRACES {
        traces.pop_front();
    }
}

#[derive(Clone, Copy)]
struct Permit {
    identity: crate::combat::Identity,
    issued: u64,
    generation: u64,
    forward: [f32; 3],
}
#[derive(Clone, Copy, Debug, Default)]
#[allow(dead_code)] // Diagnostic readback, emitted alongside native combat state.
pub struct Status {
    pub available: bool,
    pub blocks: u64,
    pub incoming: i32,
    pub remaining: i32,
}
pub fn status() -> Status {
    Status {
        available: available(),
        blocks: BLOCKS.load(Ordering::Acquire),
        incoming: BEFORE.load(Ordering::Relaxed),
        remaining: AFTER.load(Ordering::Relaxed),
    }
}
pub fn available() -> bool {
    INSTALLED.load(Ordering::Acquire) && !FAULTED.load(Ordering::Acquire)
}
pub fn revoke() {
    // Revoke immediately even if a publication currently owns the scalar lock.
    GENERATION.fetch_add(1, Ordering::AcqRel);
    if let Ok(mut permit) = PERMIT.lock() {
        // A writer can have refreshed between the first invalidation and this
        // lock. Invalidate that publication as well before clearing the slot.
        invalidate_permit(&mut permit, &GENERATION);
    }
}
pub fn authorize(
    identity: crate::combat::Identity,
    forward: Option<[f32; 3]>,
    issued: u64,
    blocking: bool,
) {
    // These locks protect only a scalar permit copy. A routine held-shield
    // refresh must not invalidate a hit that already copied its fresh permit.
    if let Ok(mut permit) = PERMIT.lock() {
        publish_permit(
            &mut permit,
            &GENERATION,
            identity,
            forward.filter(|f| blocking && available() && valid_forward(*f)),
            issued,
        );
    }
}
fn invalidate_permit(permit: &mut Option<Permit>, generation: &AtomicU64) {
    generation.fetch_add(1, Ordering::AcqRel);
    *permit = None;
}
fn publish_permit(
    permit: &mut Option<Permit>,
    generation: &AtomicU64,
    identity: crate::combat::Identity,
    forward: Option<[f32; 3]>,
    issued: u64,
) {
    let current = generation.load(Ordering::Acquire);
    let refresh = forward.is_some()
        && permit.is_some_and(|p| p.identity == identity && p.generation == current);
    let generation = if refresh {
        current
    } else {
        generation.fetch_add(1, Ordering::AcqRel).wrapping_add(1)
    };
    *permit = forward.map(|forward| Permit {
        identity,
        issued,
        generation,
        forward,
    });
}
fn valid_forward(f: [f32; 3]) -> bool {
    f.iter().all(|v| v.is_finite()) && (0.9..=1.1).contains(&(f[0] * f[0] + f[2] * f[2]))
}
fn reduced_damage(damage: i32, forward: [f32; 3], incoming: [f32; 3]) -> Option<i32> {
    if !(1..=crate::native_damage::MAX_DAMAGE).contains(&damage)
        || !valid_forward(forward)
        || incoming.iter().any(|v| !v.is_finite())
    {
        return None;
    }
    let horizontal = incoming[0] * incoming[0] + incoming[2] * incoming[2];
    // Incoming points from source to victim, opposite the defender's facing.
    // Like Minecraft, only the front half-plane blocks. Vertical/environmental
    // events with no meaningful horizontal attack direction are left untouched.
    if horizontal < 0.000001
        || !horizontal.is_finite()
        || forward[0] * incoming[0] + forward[2] * incoming[2] >= 0.0
    {
        return None;
    }
    Some((damage + 9) / 10)
}
fn lease_matches(
    permit: Permit,
    identity: crate::combat::Identity,
    now: u64,
    generation: u64,
) -> bool {
    permit.identity == identity
        && permit.generation == generation
        && now >= permit.issued
        && now - permit.issued < LEASE_MS
}

/// Native damage resolution must recognize only our exact installed patch,
/// never accept an arbitrary altered processor just because a hook once existed.
pub fn processor_matches(image: &[u8]) -> bool {
    let prefix = image.get(PROCESS_RVA..PROCESS_RVA + PREFIX.len());
    prefix == Some(PREFIX.as_slice())
        || PATCH.get().is_some_and(|(base, patch)| {
            *base == image.as_ptr() as usize && prefix == Some(patch.as_slice())
        })
}
/// # Safety
/// Called only after complete executable SHA verification and DLL lifetime pin.
pub unsafe fn install() -> Result<(), String> {
    let program = Program::current();
    let image = program.image();
    if image.get(PROCESS_RVA..PROCESS_RVA + PREFIX.len()) != Some(PREFIX.as_slice()) {
        return Err("Minecraft shield damage processor fingerprint mismatch".into());
    }
    if INSTALLED.swap(true, Ordering::AcqRel) {
        return Err("Minecraft shield already installed".into());
    }
    let callback = |registers: *mut Registers| {
        let _phase = crate::crash::phase("shield detour");
        if !available() {
            return;
        }
        if std::panic::catch_unwind(|| unsafe { filter(&*registers) }).is_err() {
            FAULTED.store(true, Ordering::Release);
            revoke();
        }
    };
    let address = image.as_ptr() as usize + PROCESS_RVA;
    let hook = match unsafe {
        crate::hosting::hook(address, |option| {
            hook_closure_jmp_back(address, callback, option, HookFlags::empty())
        })
    } {
        Ok(hook) => hook,
        Err(error) => {
            INSTALLED.store(false, Ordering::Release);
            return Err(format!("shield hook: {error:?}"));
        }
    };
    let patch = image[PROCESS_RVA..PROCESS_RVA + 23]
        .try_into()
        .expect("verified prefix extent");
    if PATCH.set((image.as_ptr() as usize, patch)).is_err() {
        INSTALLED.store(false, Ordering::Release);
        drop(hook);
        return Err("shield patch identity already set".into());
    }
    std::mem::forget(hook);
    Ok(())
}
/// A permit issued under the full combat context covers a hit landing while
/// only its focus/menu checks momentarily fail; the offline world, session and
/// live player are still required.
fn fallback_identity(
    permit: Option<Permit>,
    world: Result<crate::combat::Identity, &'static str>,
    now: u64,
    generation: u64,
) -> Option<crate::combat::Identity> {
    let identity = world.ok()?;
    permit
        .is_some_and(|p| lease_matches(p, identity, now, generation))
        .then_some(identity)
}
fn skipped_trace(
    damage: i32,
    decision: &'static str,
    permit: Option<Permit>,
    now: u64,
) -> HitTrace {
    HitTrace {
        sequence: HIT_SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1,
        damage,
        remaining: damage,
        decision,
        source_npc_param: None,
        source_position: None,
        incoming: None,
        request_reaction: [0.; 3],
        forward: permit.map(|p| p.forward),
        incoming_dot: None,
        permit_age_ms: permit.and_then(|p| now.checked_sub(p.issued)),
        permit_valid: false,
        context_fallback: None,
        server_age_ms: None,
        server_stamina: None,
        server_shield_ready: None,
        server_using_item: None,
        server_guard_ack: None,
        server_guard_damage: None,
    }
}
unsafe fn filter(regs: &Registers) {
    let permit = PERMIT.lock().ok().and_then(|p| *p);
    let now = crate::world_transport::now();
    let module = regs.rcx as usize;
    let source = regs.rdx as usize;
    let request = regs.r8 as *mut u8;
    if request.is_null() || !(request as usize).is_multiple_of(8) {
        return;
    }
    // The processor runs for every character. Only hits on the local player are
    // ours; from here on every skipped one is logged with its reason.
    let Some(local) = (unsafe { PlayerIns::local_player() })
        .ok()
        .map(|p| p as *const PlayerIns as usize)
    else {
        return;
    };
    if unsafe { request.add(0x1e0).cast::<usize>().read_unaligned() } != local {
        return;
    }
    let damage = unsafe { request.add(0x228).cast::<i32>().read_unaligned() };
    let skip = |decision: &'static str| {
        if let Ok(mut traces) = HIT_TRACES.try_lock() {
            retain_trace(&mut traces, skipped_trace(damage, decision, permit, now));
        }
    };
    let mut context_fallback = None;
    let identity = match unsafe { crate::combat::context() } {
        Ok(identity) => identity,
        Err(reason) => match fallback_identity(
            permit,
            unsafe { crate::combat::world_context() },
            now,
            GENERATION.load(Ordering::Acquire),
        ) {
            Some(identity) => {
                context_fallback = Some(reason);
                identity
            }
            None => return skip(reason),
        },
    };
    if identity.player != local {
        return skip("skipped: local player changed during the hit");
    }
    if module == 0 || !module.is_multiple_of(8) {
        return skip("skipped: damage module missing");
    }
    if source == 0 {
        return skip("skipped: attack source missing");
    }
    let self_inflicted = source == identity.player;
    if regs.r9 as u8 != 0 {
        return skip("skipped: target already dead");
    }
    // x64 fifth parameter: return address + four shadow slots.
    if unsafe { ((regs.rsp + 0x28) as *const u8).read() } != 0 {
        return skip("skipped: HP mutation suppressed");
    }
    let player = unsafe { &*(identity.player as *const PlayerIns) };
    let bag = player.chr_ins.modules.as_ref() as *const _ as usize;
    let actual_module = unsafe { ((bag + 0x98) as *const usize).read() };
    if module != actual_module
        || unsafe { ((module + 8) as *const usize).read() } != identity.player
    {
        return skip("skipped: not the player's damage module");
    }
    let request_source = unsafe { request.add(0x1d8).cast::<usize>().read_unaligned() };
    if request_source != source {
        return skip("skipped: request source differs from caller");
    }
    if !(1..=crate::native_damage::MAX_DAMAGE).contains(&damage) {
        return skip("skipped: damage outside bounds");
    }
    let (hp, max_hp) = (
        player.chr_ins.modules.data.hp,
        player.chr_ins.modules.data.max_hp,
    );
    if self_inflicted {
        // Elden Ring's own hazards keep native damage; a held totem still
        // catches a lethal one, as vanilla death protection catches a fall.
        if let Some(saved) = crate::campaign_runtime::death_protection(damage, hp, max_hp) {
            unsafe {
                request.add(0x228).cast::<i32>().write_unaligned(saved);
            }
            return skip("totem: self-inflicted lethal damage");
        }
        return skip("skipped: self-inflicted damage");
    }
    let target = local;
    let incoming = unsafe { crate::combat_targets::shield_incoming(source, target) };
    let source_info = incoming.map(|_| {
        // shield_incoming already resolved this exact current registered ChrIns.
        let chr = unsafe { &*(source as *const ChrIns) };
        let p = chr.modules.physics.position;
        (chr.npc_param_id, [p.0, p.1, p.2])
    });
    let permit_valid =
        permit.is_some_and(|p| lease_matches(p, identity, now, GENERATION.load(Ordering::Acquire)));
    let blocking = permit
        .filter(|_| permit_valid)
        .and_then(|p| incoming.and_then(|direction| reduced_damage(damage, p.forward, direction)));
    let breaks = crate::campaign_runtime::guard_breaks();
    let filtered = crate::campaign_runtime::filter_damage(damage, blocking.is_some(), hp, max_hp);
    let broken_through = crate::campaign_runtime::guard_breaks() != breaks;
    let reduced = filtered.or_else(|| {
        (!crate::campaign_runtime::enabled())
            .then_some(blocking)
            .flatten()
    });
    let state = crate::campaign_runtime::combat_observation();
    let epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    let trace = HitTrace {
        sequence: HIT_SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1,
        damage,
        remaining: reduced.unwrap_or(damage),
        decision: match reduced {
            Some(0) => "blocked",
            Some(_) if broken_through => "guard_broken_partial_block",
            _ if permit.is_none() => "no_raised_guard_permit",
            _ if !permit_valid => "guard_lease_expired_or_revoked",
            _ if incoming.is_none() => "attack_source_unresolved",
            _ if blocking.is_none() => "outside_front_half_plane",
            None => "campaign_state_unavailable",
            Some(_) if crate::campaign_runtime::enabled() => "server_guard_unready_or_stamina_debt",
            Some(_) => "sandbox_block_reduction",
        },
        source_npc_param: source_info.map(|s| s.0),
        source_position: source_info.map(|s| s.1),
        incoming,
        request_reaction: std::array::from_fn(|i| unsafe {
            request.add(0x1c0 + i * 4).cast::<f32>().read_unaligned()
        }),
        forward: permit.map(|p| p.forward),
        incoming_dot: permit
            .and_then(|p| incoming.map(|v| p.forward[0] * v[0] + p.forward[2] * v[2])),
        permit_age_ms: permit.and_then(|p| now.checked_sub(p.issued)),
        permit_valid,
        context_fallback,
        server_age_ms: state
            .as_ref()
            .and_then(|s| epoch.checked_sub(s.timestamp_ms)),
        server_stamina: state.as_ref().map(|s| s.stamina),
        server_shield_ready: state.as_ref().map(|s| s.shield_ready),
        server_using_item: state.as_ref().map(|s| s.using_item),
        server_guard_ack: state.as_ref().map(|s| s.guard_seq),
        server_guard_damage: state.as_ref().map(|s| s.guard_damage),
    };
    if let Ok(mut traces) = HIT_TRACES.try_lock() {
        retain_trace(&mut traces, trace);
    }
    let Some(reduced) = reduced else {
        return;
    };
    unsafe {
        request.add(0x228).cast::<i32>().write_unaligned(reduced);
    }
    BEFORE.store(damage, Ordering::Relaxed);
    AFTER.store(reduced, Ordering::Relaxed);
    BLOCKS.fetch_add(1, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fresh_permit_survives_a_focus_or_menu_check_failure_only_for_the_same_live_player() {
        let identity = crate::combat::Identity {
            player: 100,
            map: 20,
        };
        let permit = Some(Permit {
            identity,
            issued: 100,
            generation: 4,
            forward: [0., 0., 1.],
        });
        assert_eq!(
            fallback_identity(permit, Ok(identity), 150, 4).map(|i| i.player),
            Some(100)
        );
        // Stale or revoked permits, no permit, a different player or map, and a
        // failed offline-world/live-player check never fall back.
        assert!(fallback_identity(permit, Ok(identity), 200, 4).is_none());
        assert!(fallback_identity(permit, Ok(identity), 150, 5).is_none());
        assert!(fallback_identity(None, Ok(identity), 150, 4).is_none());
        for other in [
            crate::combat::Identity {
                player: 101,
                ..identity
            },
            crate::combat::Identity {
                map: 21,
                ..identity
            },
        ] {
            assert!(fallback_identity(permit, Ok(other), 150, 4).is_none());
        }
        assert!(fallback_identity(permit, Err("combat player is not alive"), 150, 4).is_none());
    }
    #[test]
    fn skipped_hits_keep_full_damage_and_their_reason() {
        let trace = skipped_trace(343, "skipped: attack source missing", None, 10);
        assert_eq!((trace.damage, trace.remaining), (343, 343));
        assert_eq!(trace.decision, "skipped: attack source missing");
        assert!(!trace.permit_valid && trace.context_fallback.is_none());
    }
    #[test]
    fn a_hit_snapshot_survives_guard_refresh_but_not_release_revoke_or_identity_change() {
        let generation = AtomicU64::new(0);
        let identity = crate::combat::Identity {
            player: 100,
            map: 20,
        };
        let forward = Some([0., 0., 1.]);
        let mut slot = None;
        publish_permit(&mut slot, &generation, identity, forward, 100);
        let hit = slot.unwrap(); // Hit copies its permit before the next game-frame publication.
        publish_permit(&mut slot, &generation, identity, forward, 116);
        assert!(lease_matches(
            hit,
            identity,
            117,
            generation.load(Ordering::Acquire)
        ));
        assert_eq!(slot.unwrap().generation, hit.generation);
        assert_eq!(slot.unwrap().issued, 116);
        // Releasing guard must still invalidate a previously copied fresh permit.
        publish_permit(&mut slot, &generation, identity, None, 120);
        assert!(slot.is_none());
        assert!(!lease_matches(
            hit,
            identity,
            120,
            generation.load(Ordering::Acquire)
        ));
        publish_permit(&mut slot, &generation, identity, forward, 125);
        let hit = slot.unwrap();
        let other = crate::combat::Identity {
            player: 101,
            ..identity
        };
        publish_permit(&mut slot, &generation, other, forward, 130);
        assert!(!lease_matches(
            hit,
            identity,
            130,
            generation.load(Ordering::Acquire)
        ));
        // Model revoke advancing before waiting for the publication lock.
        let revoked_hit = slot.unwrap();
        generation.fetch_add(1, Ordering::AcqRel);
        assert!(!lease_matches(
            revoked_hit,
            other,
            131,
            generation.load(Ordering::Acquire)
        ));
        publish_permit(&mut slot, &generation, other, forward, 132);
        let raced_refresh = slot.unwrap();
        // Revoke's locked clear also invalidates a writer that raced with that advance.
        invalidate_permit(&mut slot, &generation);
        assert!(slot.is_none());
        assert!(!lease_matches(
            raced_refresh,
            other,
            133,
            generation.load(Ordering::Acquire)
        ));
        publish_permit(&mut slot, &generation, other, forward, 140);
        assert!(lease_matches(
            slot.unwrap(),
            other,
            140,
            generation.load(Ordering::Acquire)
        ));
    }
    #[test]
    fn ninety_percent_reduction_happens_before_fatal_subtraction() {
        let hp = 100;
        let loss = reduced_damage(900, [0., 0., 1.], [0., 0., -1.]).unwrap();
        assert_eq!(loss, 90);
        assert_eq!(hp - loss, 10);
        for damage in 1..=1000 {
            let left = reduced_damage(damage, [0., 0., 1.], [0., 0., -2.]).unwrap();
            assert_eq!(left, (damage + 9) / 10);
            assert!(left >= 1 && left <= damage);
        }
    }
    #[test]
    fn rear_side_vertical_environmental_and_bad_damage_do_not_block() {
        for incoming in [
            [0., 0., 1.],
            [1., 0., 0.],
            [0., 1., 0.],
            [0.; 3],
            [f32::NAN, 0., -1.],
        ] {
            assert_eq!(reduced_damage(100, [0., 0., 1.], incoming), None);
        }
        for damage in [-1, 0, i32::MAX] {
            assert_eq!(reduced_damage(damage, [0., 0., 1.], [0., 0., -1.]), None);
        }
        assert_eq!(reduced_damage(100, [1., 0., 0.], [-1., 0., 0.]), Some(10));
    }
    #[test]
    fn stale_future_revoked_or_new_player_cannot_block() {
        let identity = crate::combat::Identity {
            player: 100,
            map: 20,
        };
        let p = Permit {
            identity,
            issued: 100,
            generation: 4,
            forward: [0., 0., 1.],
        };
        assert!(lease_matches(p, identity, 100, 4));
        assert!(lease_matches(p, identity, 199, 4));
        for now in [99, 200, u64::MAX] {
            assert!(!lease_matches(p, identity, now, 4));
        }
        assert!(!lease_matches(p, identity, 100, 5));
        assert!(!lease_matches(
            p,
            crate::combat::Identity {
                player: 101,
                ..identity
            },
            100,
            4
        ));
        assert!(!lease_matches(
            p,
            crate::combat::Identity {
                map: 21,
                ..identity
            },
            100,
            4
        ));
    }
}
