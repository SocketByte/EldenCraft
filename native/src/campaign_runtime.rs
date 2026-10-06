//! Offline campaign integration. Game objects stay on the recurring game task;
//! the worker owns JSON, durable transaction intents and native-save witnesses.
use crate::campaign::*;
use eldenring::cs::{
    CSEventFlagMan, CSEzStateTalkEvent, CSMenuManImp, CSSessionManager, EquipParamGoods,
    FieldInsHandle, GameDataMan, GameMan, ItemCategory, ItemId, ItemLotParam_map, LobbyState,
    MenuType, PlayerIns, ProtocolState, SoloParamRepository, WorldChrMan,
};
use eldenring::ez_state::EzStateEvent;
use fromsoftware_shared::{FromStatic, program::Program};
use ilhook::x64::{HookFlags, Registers, hook_closure_retn};
use pelite::pe64::PeObject;
use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, AtomicUsize, Ordering},
        mpsc::{self, Receiver, Sender},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

static CONFIG: OnceLock<Arc<Config>> = OnceLock::new();
static TALK_HOOK_INSTALLED: Mutex<bool> = Mutex::new(false);
static TALK_EVENT_ORIGINAL: AtomicUsize = AtomicUsize::new(0);
static SHOP: Mutex<Option<Captured>> = Mutex::new(None);
static SHOP_SERIAL: AtomicU64 = AtomicU64::new(1);
static COMBAT: Mutex<Option<CombatState>> = Mutex::new(None);
static CURRENT: Mutex<Option<(u64, String)>> = Mutex::new(None);
static HEALING: Mutex<Option<HealingState>> = Mutex::new(None);
static GUARD_SEQ: AtomicU64 = AtomicU64::new(0);
static GUARD_DAMAGE: Mutex<f64> = Mutex::new(0.);
static GUARD_BREAKS: AtomicU64 = AtomicU64::new(0);
static DAMAGE_EVENTS: Mutex<VecDeque<DamageEvent>> = Mutex::new(VecDeque::new());
static DAMAGE_SEQ: AtomicU64 = AtomicU64::new(0);
static EXPERIENCE_SEQ: AtomicU64 = AtomicU64::new(0);
static EXPERIENCE_TOTAL: AtomicU64 = AtomicU64::new(0);

pub fn record_kill(receipt: &crate::native_damage::Receipt) {
    let Some(c) = CONFIG.get().filter(|c| c.enabled) else {
        return;
    };
    if !receipt.killed
        || receipt.actual_delta <= 0
        || CURRENT.try_lock().ok().is_none_or(|v| v.is_none())
    {
        return;
    }
    let xp = (c.experience.mob_base as f64
        + c.experience.mob_per_native_hp * receipt.max_hp_before as f64)
        .round()
        .max(0.) as u64;
    let xp = xp.min(c.experience.max_per_kill);
    let _ = EXPERIENCE_TOTAL.try_update(Ordering::AcqRel, Ordering::Acquire, |total| {
        Some(total.saturating_add(xp).min(9000000000000000))
    });
    EXPERIENCE_SEQ.fetch_add(1, Ordering::Release);
}

#[derive(Clone, Serialize)]
struct DamageEvent {
    seq: u64,
    raw_damage: f64,
    blocked: bool,
}
fn record_damage(raw: f64, blocked: bool) {
    if let Ok(mut events) = DAMAGE_EVENTS.lock() {
        let seq = DAMAGE_SEQ.fetch_add(1, Ordering::Relaxed) + 1;
        events.push_back(DamageEvent {
            seq,
            raw_damage: raw,
            blocked,
        });
        while events.len() > 64 {
            events.pop_front();
        }
    }
}
fn damage_events() -> Vec<DamageEvent> {
    DAMAGE_EVENTS
        .lock()
        .map(|e| e.iter().cloned().collect())
        .unwrap_or_default()
}
pub fn enabled() -> bool {
    CONFIG.get().is_some_and(|c| c.enabled)
}

pub fn damage_scale() -> Option<f32> {
    CONFIG
        .get()
        .filter(|c| c.enabled)
        .map(|c| c.combat.native_damage_scale)
}
pub fn enemy_damage_multiplier(npc_param_id: i32) -> f64 {
    CONFIG
        .get()
        .filter(|c| c.enabled)
        .map(|c| c.enemy_damage_multiplier(npc_param_id))
        .unwrap_or(1.)
}
#[derive(Clone, Debug, Deserialize)]
pub struct CombatState {
    pub version: u32,
    pub session: u64,
    pub character: String,
    pub timestamp_ms: u64,
    pub armor: f64,
    pub toughness: f64,
    pub guest_max_hp: f64,
    pub shield_ready: bool,
    pub stamina: f64,
    pub using_item: bool,
    #[serde(default)]
    pub guard_seq: u64,
    #[serde(default)]
    pub guard_damage: f64,
    /// Exact native boss instance IDs actually drawn by the Minecraft HUD.
    #[serde(default)]
    pub boss_hud_ids: Vec<String>,
    #[serde(default)]
    pub boss_hud_timestamp_ms: u64,
}
/// Bounded read-only observation for shield contact diagnostics.
pub fn combat_observation() -> Option<CombatState> {
    COMBAT.lock().ok()?.clone()
}

fn admitted_boss_hud_ids(
    state: &CombatState,
    current: Option<&(u64, String)>,
    now: u64,
) -> Option<Vec<String>> {
    if state.version != 1
        || current.is_none_or(|(session, character)| {
            *session != state.session || *character != state.character
        })
        || now < state.timestamp_ms
        || now - state.timestamp_ms > 250
        || (!state.boss_hud_ids.is_empty()
            && (state.boss_hud_timestamp_ms == 0
                || now < state.boss_hud_timestamp_ms
                || now - state.boss_hud_timestamp_ms > 250))
        || state.boss_hud_ids.len() > crate::boss_hud::MAX_BOSSES
        || state
            .boss_hud_ids
            .iter()
            .any(|id| id.is_empty() || id.len() > 160 || id.chars().any(char::is_control))
    {
        return None;
    }
    Some(state.boss_hud_ids.clone())
}

/// A short lease for gauges actually drawn by the current paired guest. The
/// game task still has to resolve every ID to its current native source before
/// suppressing any frontend tag. Missing/old guests retain the native bars.
pub fn boss_hud_ids() -> Vec<String> {
    if !enabled() {
        return Vec::new();
    }
    let Some(state) = COMBAT.try_lock().ok().and_then(|v| v.clone()) else {
        return Vec::new();
    };
    let Some(current) = CURRENT.try_lock().ok().map(|v| v.clone()) else {
        return Vec::new();
    };
    admitted_boss_hud_ids(&state, current.as_ref(), epoch()).unwrap_or_default()
}
#[derive(Clone, Deserialize)]
struct HealingState {
    version: u32,
    session: u64,
    character: String,
    timestamp_ms: u64,
    heal_seq: u64,
    heal_total: f64,
}
/// The fixed conversion prevents Vigor growth from reducing stamina pressure.
pub fn filter_damage(damage: i32, frontal_block: bool) -> Option<i32> {
    let cfg = CONFIG.get().filter(|c| c.enabled)?;
    // These locks only copy scalar/bounded state, and the worker performs its
    // file reads before acquiring COMBAT. Release each before the next lock.
    // A transient publication must not turn a raised shield into an open hit.
    let state = COMBAT.lock().ok()?.clone()?;
    if CURRENT
        .lock()
        .ok()?
        .as_ref()
        .is_none_or(|(session, character)| {
            *session != state.session || *character != state.character
        })
    {
        return None;
    }
    let now = epoch();
    if state.version != 1
        || now < state.timestamp_ms
        || now - state.timestamp_ms > 250
        || !state.armor.is_finite()
        || !state.toughness.is_finite()
        || !(0. ..=100.).contains(&state.armor)
        || !(0. ..=100.).contains(&state.toughness)
        || !state.guest_max_hp.is_finite()
        || state.guest_max_hp <= 0.
        || !state.stamina.is_finite()
        || damage <= 0
    {
        return None;
    }
    let raw =
        damage as f64 * cfg.combat.native_incoming_damage_scale / cfg.native_hp_per_minecraft_hp();
    if frontal_block
        && state.shield_ready
        && state.using_item
        && let Ok(mut sum) = GUARD_DAMAGE.lock()
    {
        let seq = GUARD_SEQ.load(Ordering::Acquire);
        if !guard_ack_valid(state.guard_seq, state.guard_damage, seq, *sum) {
            return None;
        }
        let debt = (seq - state.guard_seq) as f64 * cfg.stamina.guard_base
            + (*sum - state.guard_damage).max(0.) * cfg.stamina.guard_per_damage;
        let absorbed = guard_absorbed(
            state.stamina - debt,
            raw,
            cfg.stamina.guard_base,
            cfg.stamina.guard_per_damage,
        );
        if absorbed > 0. {
            // Charge the whole hit: when stamina could not cover it, Minecraft's
            // account reaches zero and breaks the guard with its feedback.
            *sum += raw;
            GUARD_SEQ.fetch_add(1, Ordering::Release);
            if absorbed >= 1. {
                record_damage(raw, true);
                return Some(0);
            }
            GUARD_BREAKS.fetch_add(1, Ordering::Release);
            let through = raw * (1. - absorbed);
            record_damage(raw * absorbed, true);
            record_damage(through, false);
            return Some(open_damage(through, &state, cfg));
        }
    }
    record_damage(raw, false);
    Some(open_damage(raw, &state, cfg))
}
fn open_damage(raw: f64, state: &CombatState, cfg: &Config) -> i32 {
    (damage_after_armor(raw, state.armor, state.toughness) * cfg.native_hp_per_minecraft_hp())
        .round()
        .max(1.) as i32
}
/// Fraction of a raised-shield hit that the remaining stamina pays for. The
/// rest of the hit goes through as ordinary damage after armor.
fn guard_absorbed(available: f64, raw: f64, base: f64, per_damage: f64) -> f64 {
    if !available.is_finite() || available <= 0. {
        return 0.;
    }
    let cost = base + per_damage * raw.max(0.);
    if cost <= available {
        1.
    } else {
        (available / cost).clamp(0., 1.)
    }
}
/// Count of hits whose block was only partly paid for (guard broken through).
pub fn guard_breaks() -> u64 {
    GUARD_BREAKS.load(Ordering::Acquire)
}
/// Minecraft may acknowledge at most what native published. The cumulative
/// damage crosses JSON twice, so allow rounding noise; otherwise a value one
/// ULP high would reject every later block for the rest of the session.
fn guard_ack_valid(ack_seq: u64, ack_damage: f64, seq: u64, sum: f64) -> bool {
    ack_seq <= seq && ack_damage.is_finite() && ack_damage <= sum + sum.abs() * 1e-12 + 1e-9
}
fn epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Clone)]
struct Captured {
    range: i32,
    handle: FieldInsHandle,
    npc: usize,
    chr: usize,
    serial: u64,
}
#[derive(Clone, Serialize)]
pub struct Snapshot {
    version: u32,
    pid: u32,
    session: u64,
    seq: u64,
    timestamp_ms: u64,
    active: bool,
    dead: bool,
    character: String,
    runes: u32,
    hp: i32,
    max_hp: i32,
    stamina: i32,
    max_stamina: i32,
    defeated: Vec<String>,
    merchant: Option<Merchant>,
    ack: Option<Ack>,
    guard_seq: u64,
    guard_damage: f64,
    heal_seq: u64,
    damage_events: Vec<DamageEvent>,
    experience_seq: u64,
    experience_total: u64,
    bosses_active: Vec<crate::boss_hud::Boss>,
    #[serde(skip)]
    boss_diagnostics: Option<crate::boss_hud::Diagnostics>,
}
enum Work {
    Snapshot(Box<Snapshot>),
    Prepare(Request, u32),
    Finish(Request, Ack),
    Stop,
}
enum Incoming {
    Request(Request),
    Prepared(Request, u32),
    Known(Request, Ack),
    Finished(Request, Ack),
}
#[derive(Clone)]
struct Pending {
    req: Request,
    stamp: u64,
    at: Instant,
    debited: bool,
    native_item_before: Option<(Vec<ItemId>, u64)>,
}
pub struct Driver {
    config: Arc<Config>,
    tx: Sender<Work>,
    rx: Receiver<Incoming>,
    ledger: Ledger,
    session: u64,
    seq: u64,
    character: String,
    merchant: Option<Merchant>,
    pending: Option<Pending>,
    ack: Option<Ack>,
    stamp: Arc<AtomicU64>,
    last_publish: Instant,
    last_flags: Instant,
    heal_seq: u64,
    heal_total: f64,
    heal_baseline: bool,
}
fn atomic_json(path: &Path, value: &impl Serialize) -> std::io::Result<()> {
    let temp = path.with_extension("tmp");
    let bytes = serde_json::to_vec(value)?;
    let mut file = fs::File::create(&temp)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        unsafe extern "system" {
            fn MoveFileExW(from: *const u16, to: *const u16, flags: u32) -> i32;
        }
        let from: Vec<u16> = temp.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe { MoveFileExW(from.as_ptr(), to.as_ptr(), 1 | 8) } == 0 {
            return Err(std::io::Error::last_os_error());
        }
    }
    #[cfg(not(windows))]
    fs::rename(temp, path)?;
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(path: &Path, limit: u64) -> Option<T> {
    let mut text = String::new();
    fs::File::open(path)
        .ok()?
        .take(limit + 1)
        .read_to_string(&mut text)
        .ok()?;
    if text.len() as u64 > limit {
        return None;
    }
    serde_json::from_str(&text).ok()
}
fn save_stamp(path: &Path) -> u64 {
    fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|t| t.as_nanos() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod combat_contention_tests {
    use super::*;

    #[test]
    fn brief_state_publication_contention_preserves_fresh_shield_blocks() {
        CONFIG.get_or_init(|| {
            Arc::new(serde_json::from_str(include_str!("../../config/campaign.json")).unwrap())
        });
        let old_combat = COMBAT.lock().unwrap().clone();
        let old_current = CURRENT.lock().unwrap().clone();
        let old_sum = *GUARD_DAMAGE.lock().unwrap();
        let old_seq = GUARD_SEQ.load(Ordering::Acquire);
        let old_events = DAMAGE_EVENTS.lock().unwrap().clone();
        let old_event_seq = DAMAGE_SEQ.load(Ordering::Acquire);
        *CURRENT.lock().unwrap() = Some((7, "guard-contention-test".into()));
        *GUARD_DAMAGE.lock().unwrap() = 0.;
        GUARD_SEQ.store(0, Ordering::Release);
        DAMAGE_EVENTS.lock().unwrap().clear();
        *COMBAT.lock().unwrap() = Some(CombatState {
            version: 1,
            session: 7,
            character: "guard-contention-test".into(),
            timestamp_ms: epoch(),
            armor: 0.,
            toughness: 0.,
            guest_max_hp: 20.,
            shield_ready: true,
            stamina: 1000.,
            using_item: true,
            guard_seq: 0,
            guard_damage: 0.,
            boss_hud_ids: Vec::new(),
            boss_hud_timestamp_ms: 0,
        });
        fn contend<T: Send + 'static>(mutex: &'static Mutex<T>) {
            COMBAT.lock().unwrap().as_mut().unwrap().timestamp_ms = epoch();
            let held = mutex.lock().unwrap();
            let (ready_tx, ready_rx) = mpsc::channel();
            let (result_tx, result_rx) = mpsc::channel();
            let reader = std::thread::spawn(move || {
                ready_tx.send(()).unwrap();
                result_tx.send(filter_damage(100, true)).unwrap();
            });
            ready_rx.recv_timeout(Duration::from_secs(1)).unwrap();
            let early = result_rx.recv_timeout(Duration::from_millis(20));
            drop(held);
            assert_eq!(early, Err(mpsc::RecvTimeoutError::Timeout));
            assert_eq!(
                result_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
                Some(0)
            );
            reader.join().unwrap();
        }
        contend(&COMBAT);
        contend(&CURRENT);
        contend(&GUARD_DAMAGE);
        assert_eq!(GUARD_SEQ.load(Ordering::Acquire), 3);
        assert_eq!(DAMAGE_EVENTS.lock().unwrap().len(), 3);
        COMBAT.lock().unwrap().as_mut().unwrap().timestamp_ms = epoch().saturating_sub(251);
        assert_eq!(filter_damage(100, true), None);
        *COMBAT.lock().unwrap() = old_combat;
        *CURRENT.lock().unwrap() = old_current;
        *GUARD_DAMAGE.lock().unwrap() = old_sum;
        GUARD_SEQ.store(old_seq, Ordering::Release);
        *DAMAGE_EVENTS.lock().unwrap() = old_events;
        DAMAGE_SEQ.store(old_event_seq, Ordering::Release);
    }
}

#[cfg(test)]
mod boss_hud_receipt_tests {
    use super::*;

    fn state() -> CombatState {
        serde_json::from_value(serde_json::json!({
            "version": 1, "session": 7, "character": "paired", "timestamp_ms": 1000,
            "armor": 0., "toughness": 0., "guest_max_hp": 20.,
            "shield_ready": false, "stamina": 20., "using_item": false,
            "boss_hud_ids": ["actual-source-id"], "boss_hud_timestamp_ms": 950,
        }))
        .unwrap()
    }

    #[test]
    fn boss_receipts_expire_from_actual_draw_and_require_current_pairing() {
        let current = (7, "paired".into());
        let sample = state();
        assert_eq!(
            admitted_boss_hud_ids(&sample, Some(&current), 1200),
            Some(vec!["actual-source-id".into()])
        );
        assert!(admitted_boss_hud_ids(&sample, Some(&current), 1201).is_none());
        assert!(admitted_boss_hud_ids(&sample, Some(&current), 999).is_none());
        assert!(admitted_boss_hud_ids(&sample, None, 1000).is_none());
        assert!(admitted_boss_hud_ids(&sample, Some(&(8, "paired".into())), 1000).is_none());
        assert!(admitted_boss_hud_ids(&sample, Some(&(7, "other".into())), 1000).is_none());
        let mut wrong_version = sample.clone();
        wrong_version.version = 2;
        assert!(admitted_boss_hud_ids(&wrong_version, Some(&current), 1000).is_none());
    }

    #[test]
    fn absent_bounded_and_invalid_boss_receipts_never_hide_native_gauges() {
        let current = (7, "paired".into());
        let mut sample = state();
        sample.boss_hud_timestamp_ms = 0;
        assert!(admitted_boss_hud_ids(&sample, Some(&current), 1000).is_none());
        sample.boss_hud_ids.clear();
        assert_eq!(
            admitted_boss_hud_ids(&sample, Some(&current), 1000),
            Some(vec![])
        );
        sample.boss_hud_timestamp_ms = 1000;
        sample.boss_hud_ids = vec!["id".into(); crate::boss_hud::MAX_BOSSES + 1];
        assert!(admitted_boss_hud_ids(&sample, Some(&current), 1000).is_none());
        for invalid in ["".to_owned(), "bad\nsource".into(), "x".repeat(161)] {
            sample.boss_hud_ids = vec![invalid];
            assert!(admitted_boss_hud_ids(&sample, Some(&current), 1000).is_none());
        }
    }
}
fn default_save() -> Option<PathBuf> {
    let base = PathBuf::from(std::env::var_os("APPDATA")?).join("EldenRing");
    let mut candidates = Vec::new();
    for dir in fs::read_dir(base).ok()?.flatten() {
        let p = dir.path().join("EldenCraft.sl2");
        if p.is_file() {
            candidates.push(p);
        }
    }
    (candidates.len() == 1).then(|| candidates.remove(0))
}
impl Driver {
    /// Bootstrap thread only: configuration failures never permit campaign writes.
    pub fn open(base: &Path) -> Result<Option<Self>, String> {
        let Some(path) = std::env::var_os("ELDENCRAFT_CAMPAIGN_CONFIG") else {
            return Ok(None);
        };
        let config: Config =
            read_json(Path::new(&path), 1024 * 1024).ok_or("cannot parse campaign JSON")?;
        config.validate()?;
        if !config.enabled {
            return Ok(None);
        }
        let config = Arc::new(config);
        let folder = std::env::var_os("ELDENCRAFT_CAMPAIGN_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| base.join("campaign"));
        fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
        let ledger_path = folder.join("native-ledger.json");
        let mut ledger: Ledger = if ledger_path.exists() {
            read_json(&ledger_path, 8 * 1024 * 1024).ok_or("invalid native campaign ledger")?
        } else {
            Ledger::default()
        };
        for ack in ledger.transactions.values_mut() {
            if ack.status == "prepared" {
                ack.status = "uncertain".into();
                ack.reason = "native save interrupted; transaction quarantined".into();
            }
        }
        atomic_json(&ledger_path, &ledger).map_err(|e| e.to_string())?;
        let mut save_path = config
            .native_save_path
            .as_ref()
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
            .or_else(default_save);
        let stamp = Arc::new(AtomicU64::new(
            save_path.as_ref().map(|p| save_stamp(p)).unwrap_or(0),
        ));
        let worker_stamp = stamp.clone();
        let (tx, work) = mpsc::channel();
        let (reply, rx) = mpsc::channel();
        let mut worker_ledger = ledger.clone();
        let worker_config = config.clone();
        std::thread::spawn(move || {
            let mut last_request = String::new();
            let mut discovery = Instant::now() - Duration::from_secs(2);
            let mut last_boss_diagnostics = Instant::now() - Duration::from_secs(2);
            let mut last_active_boss_diagnostic = serde_json::Value::Null;
            let mut last_registered_boss_diagnostic = serde_json::Value::Null;
            loop {
                if save_path.is_none() && discovery.elapsed() > Duration::from_secs(1) {
                    save_path = default_save();
                    discovery = Instant::now();
                }
                if let Some(path) = &save_path {
                    worker_stamp.store(save_stamp(path), Ordering::Release);
                }
                if let Some(req) = read_json::<Request>(&folder.join("campaign-guest.json"), 4096) {
                    let identity = format!("{}:{}:{}", req.session, req.id, req.action);
                    if identity != last_request {
                        last_request = identity;
                        if let Some(ack) = worker_ledger.transactions.get(&req.id) {
                            let matching = worker_ledger.requests.get(&req.id).is_some_and(|old| {
                                old.character == req.character
                                    && old.offer == req.offer
                                    && old.quantity == req.quantity
                                    && old.amount == req.amount
                                    && old.action == req.action
                            });
                            let ack = if matching {
                                ack.clone()
                            } else {
                                Ack {
                                    id: req.id.clone(),
                                    status: "rejected".into(),
                                    amount: 0,
                                    reason: "transaction UUID belongs to another intent".into(),
                                }
                            };
                            let _ = reply.send(Incoming::Known(req, ack));
                        } else {
                            let _ = reply.send(Incoming::Request(req));
                        }
                    }
                }
                if let Some(state) =
                    read_json::<CombatState>(&folder.join("campaign-combat.json"), 4096)
                    && let Ok(mut target) = COMBAT.lock()
                {
                    *target = Some(state);
                }
                if let Some(state) =
                    read_json::<HealingState>(&folder.join("campaign-healing.json"), 4096)
                    && let Ok(mut target) = HEALING.lock()
                {
                    *target = Some(state);
                }
                let message = match work.recv_timeout(Duration::from_millis(20)) {
                    Ok(v) => v,
                    Err(mpsc::RecvTimeoutError::Timeout) => continue,
                    Err(_) => break,
                };
                match message {
                    Work::Snapshot(state) => {
                        if last_boss_diagnostics.elapsed() >= Duration::from_secs(1) {
                            let diagnostic = serde_json::json!({
                                "version": 1, "pid": state.pid, "session": state.session,
                                "seq": state.seq, "timestamp_ms": state.timestamp_ms,
                                "active": state.active, "character": state.character,
                                "published": state.bosses_active.len(), "observation": state.boss_diagnostics,
                            });
                            if state.active {
                                last_active_boss_diagnostic = diagnostic.clone();
                            }
                            if state.boss_diagnostics.as_ref().is_some_and(|d| {
                                d.displays
                                    .iter()
                                    .any(|slot| slot.fmg_id > 0 && slot.source_resolved)
                            }) {
                                last_registered_boss_diagnostic = diagnostic.clone();
                            }
                            let _ = atomic_json(
                                &folder.join("campaign-boss-debug.json"),
                                &serde_json::json!({
                                    "version": 1, "current": diagnostic,
                                    "last_active": last_active_boss_diagnostic,
                                    "last_registered": last_registered_boss_diagnostic,
                                }),
                            );
                            last_boss_diagnostics = Instant::now();
                        }
                        if state.active {
                            let mut candidate = worker_ledger.clone();
                            let ch = candidate
                                .characters
                                .entry(state.character.clone())
                                .or_default();
                            let before = ch.defeated.len();
                            ch.defeated.extend(state.defeated.iter().cloned());
                            if before != ch.defeated.len()
                                && atomic_json(&ledger_path, &candidate).is_ok()
                            {
                                worker_ledger = candidate;
                            }
                        }
                        let _ = atomic_json(&folder.join("campaign-host.json"), &state);
                    }
                    Work::Prepare(req, before) => {
                        let mut candidate = worker_ledger.clone();
                        let ack = Ack {
                            id: req.id.clone(),
                            status: "prepared".into(),
                            amount: req.amount,
                            reason: String::new(),
                        };
                        candidate.transactions.insert(req.id.clone(), ack);
                        candidate.requests.insert(req.id.clone(), req.clone());
                        if atomic_json(&ledger_path, &candidate).is_ok() {
                            worker_ledger = candidate;
                            let _ = reply.send(Incoming::Prepared(req, before));
                        } else {
                            let ack = Ack {
                                id: req.id.clone(),
                                status: "rejected".into(),
                                amount: 0,
                                reason: "cannot persist purchase intent".into(),
                            };
                            let _ = reply.send(Incoming::Known(req, ack));
                        }
                    }
                    Work::Finish(req, ack) => {
                        let mut candidate = worker_ledger.clone();
                        if ack.status == "debited"
                            && let Some(cfg) = worker_config
                                .shops
                                .iter()
                                .find(|s| s.offers.iter().any(|o| o.id == req.offer))
                        {
                            let key = format!("{}/{}", cfg.id, req.offer);
                            *candidate
                                .characters
                                .entry(req.character.clone())
                                .or_default()
                                .stock
                                .entry(key)
                                .or_default() += req.quantity;
                        }
                        candidate.transactions.insert(ack.id.clone(), ack.clone());
                        candidate.requests.insert(req.id.clone(), req.clone());
                        if atomic_json(&ledger_path, &candidate).is_ok() {
                            worker_ledger = candidate;
                            let _ = reply.send(Incoming::Finished(req, ack));
                        } else {
                            let _ = reply.send(Incoming::Known(
                                req,
                                Ack {
                                    id: ack.id,
                                    status: "uncertain".into(),
                                    amount: ack.amount,
                                    reason: "native ledger commit failed".into(),
                                },
                            ));
                        }
                    }
                    Work::Stop => break,
                }
            }
        });
        CONFIG
            .set(config.clone())
            .map_err(|_| "campaign already initialized")?;
        let session = epoch()
            .wrapping_mul(100000)
            .wrapping_add(std::process::id() as u64);
        Ok(Some(Self {
            config,
            tx,
            rx,
            ledger,
            session,
            seq: 0,
            character: String::new(),
            merchant: None,
            pending: None,
            ack: None,
            stamp,
            last_publish: Instant::now() - Duration::from_secs(1),
            last_flags: Instant::now() - Duration::from_secs(1),
            heal_seq: 0,
            heal_total: 0.,
            heal_baseline: true,
        }))
    }
    /// Runs on the verified offline game task, including while Minecraft GUI is open.
    pub unsafe fn tick(&mut self, enabled: bool) {
        let result = unsafe { self.sample(enabled) };
        if let Some(state) = result {
            if self.last_publish.elapsed() >= Duration::from_millis(40) {
                let _ = self.tx.send(Work::Snapshot(Box::new(state)));
                self.last_publish = Instant::now();
            }
        } else {
            self.heal_baseline = true;
            if let Ok(mut current) = CURRENT.lock() {
                *current = None;
            }
            self.close_shop();
            if self.last_publish.elapsed() >= Duration::from_millis(100) {
                self.seq += 1;
                let _ = self.tx.send(Work::Snapshot(Box::new(Snapshot {
                    version: 1,
                    pid: std::process::id(),
                    session: self.session,
                    seq: self.seq,
                    timestamp_ms: epoch(),
                    active: false,
                    dead: unsafe { death_observed(&self.character) },
                    character: if self.character.is_empty() {
                        "unloaded".into()
                    } else {
                        self.character.clone()
                    },
                    runes: 0,
                    hp: 0,
                    max_hp: 1,
                    stamina: 0,
                    max_stamina: 1,
                    defeated: Vec::new(),
                    merchant: None,
                    ack: self.ack.clone(),
                    guard_seq: GUARD_SEQ.load(Ordering::Acquire),
                    guard_damage: GUARD_DAMAGE.lock().map(|v| *v).unwrap_or(0.),
                    heal_seq: self.heal_seq,
                    damage_events: Vec::new(),
                    experience_seq: EXPERIENCE_SEQ.load(Ordering::Acquire),
                    experience_total: EXPERIENCE_TOTAL.load(Ordering::Acquire),
                    bosses_active: Vec::new(),
                    boss_diagnostics: None,
                })));
                self.last_publish = Instant::now();
            }
        }
    }
    unsafe fn sample(&mut self, enabled: bool) -> Option<Snapshot> {
        if !enabled {
            return None;
        }
        let game = unsafe { GameMan::instance_mut() }.ok()?;
        if game.is_in_online_mode || game.warp_requested || game.save_slot < 0 {
            return None;
        }
        let session = unsafe { CSSessionManager::instance() }.ok()?;
        if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None
        {
            return None;
        }
        let player = unsafe { PlayerIns::local_player_mut() }.ok()?;
        if !player.chr_ins.chr_flags1c8.is_active()
            || !player.chr_ins.chr_flags1c8.update_tasks_registered()
            || player.chr_ins.chr_flags1c5.death_flag()
            || player.chr_ins.modules.data.hp <= 0
        {
            return None;
        }
        let data = unsafe { GameDataMan::instance_mut() }.ok()?;
        if !std::ptr::eq(
            player.player_game_data.as_ptr(),
            data.main_player_game_data.as_ref(),
        ) {
            return None;
        }
        let pg = &mut data.main_player_game_data;
        let character = character_identity(game.save_slot, pg);
        if self.character != character {
            if let Some(p) = self.pending.take() {
                let ack = Ack {
                    id: p.req.id.clone(),
                    status: if p.debited { "uncertain" } else { "rejected" }.into(),
                    amount: if p.debited { p.req.amount } else { 0 },
                    reason: "native character changed during purchase".into(),
                };
                let _ = self.tx.send(Work::Finish(p.req, ack));
            }
            self.session = self.session.saturating_add(1).min(i64::MAX as u64).max(1);
            self.character = character.clone();
            self.merchant = None;
            self.ack = None;
            self.last_flags = Instant::now() - Duration::from_secs(1);
            self.heal_seq = 0;
            self.heal_total = 0.;
            self.heal_baseline = true;
            if let Ok(mut events) = DAMAGE_EVENTS.lock() {
                events.clear();
            }
            EXPERIENCE_SEQ.store(0, Ordering::Release);
            EXPERIENCE_TOTAL.store(0, Ordering::Release);
            if let Ok(mut shop) = SHOP.lock() {
                *shop = None;
            }
        }
        if let Ok(mut current) = CURRENT.lock() {
            *current = Some((self.session, character.clone()));
        }
        let ch = self.ledger.characters.entry(character.clone()).or_default();
        if self.last_flags.elapsed() >= Duration::from_millis(500) {
            let flags = unsafe { CSEventFlagMan::instance() }.ok()?;
            for boss in &self.config.bosses {
                if flags.virtual_memory_flag.get_flag(boss.event_flag) {
                    ch.defeated.insert(boss.id.clone());
                }
            }
            self.last_flags = Instant::now();
        }
        let (max_hp, max_stamina) = self.config.capacities(&ch.defeated);
        let position = player.chr_ins.modules.physics.position;
        let stats = &mut player.chr_ins.modules.data;
        // Capacity growth preserves absolute current HP; only ordinary native recovery heals.
        stats.max_hp = max_hp;
        stats.max_uncapped_hp = max_hp;
        stats.base_hp = max_hp;
        stats.hp = stats.hp.min(max_hp);
        if let Some(heal) = HEALING.try_lock().ok().and_then(|h| h.clone()) {
            let now = epoch();
            if heal.version == 1
                && heal.session == self.session
                && heal.character == character
                && now >= heal.timestamp_ms
                && now - heal.timestamp_ms <= 250
                && heal.heal_total.is_finite()
                && heal.heal_total >= 0.
            {
                if !self.heal_baseline
                    && heal.heal_seq > self.heal_seq
                    && heal.heal_total >= self.heal_total
                {
                    let delta = heal.heal_total - self.heal_total;
                    if delta <= 20. {
                        stats.hp = (stats.hp as f64
                            + delta * self.config.native_hp_per_minecraft_hp())
                        .round()
                        .min(max_hp as f64) as i32;
                    }
                }
                self.heal_seq = heal.heal_seq;
                self.heal_total = heal.heal_total;
                self.heal_baseline = false;
            }
        }
        stats.max_stamina = max_stamina;
        stats.base_stamina = max_stamina;
        stats.stamina = stats.stamina.min(max_stamina);
        pg.current_max_hp = max_hp as u32;
        pg.base_max_hp = max_hp as u32;
        pg.current_hp = stats.hp as u32;
        pg.current_max_stamina = max_stamina as u32;
        pg.base_max_stamina = max_stamina as u32;
        if let Some(captured) = SHOP.lock().ok().and_then(|s| s.clone()) {
            let world = unsafe { WorldChrMan::instance() }.ok()?;
            let valid = world
                .chr_ins_by_handle(&captured.handle)
                .is_some_and(|chr| {
                    let p = &chr.modules.physics.position;
                    let q = &position;
                    chr as *const _ as usize == captured.chr
                        && (p.0 - q.0).powi(2) + (p.1 - q.1).powi(2) + (p.2 - q.2).powi(2) <= 64.
                        && chr.modules.data.hp > 0
                });
            if valid {
                if let Some(shop) = self.config.shop(&captured.range.to_string()) {
                    self.merchant = Some(Merchant {
                        id: captured.range.to_string(),
                        name: shop.title.clone(),
                        token: format!("{}-{}", self.session, captured.serial),
                    });
                }
            } else {
                self.close_shop();
            }
        }
        while let Ok(msg) = self.rx.try_recv() {
            match msg {
                Incoming::Known(req, ack) | Incoming::Finished(req, ack) => {
                    if req.character != character || req.session != self.session {
                        continue;
                    }
                    if ack.status == "debited"
                        && !self.ledger.transactions.contains_key(&ack.id)
                        && let Some(s) = self
                            .config
                            .shops
                            .iter()
                            .find(|s| s.offers.iter().any(|o| o.id == req.offer))
                    {
                        *self
                            .ledger
                            .characters
                            .entry(req.character.clone())
                            .or_default()
                            .stock
                            .entry(format!("{}/{}", s.id, req.offer))
                            .or_default() += req.quantity;
                    }
                    self.ledger.transactions.insert(ack.id.clone(), ack.clone());
                    self.pending = None;
                    self.ack = Some(ack);
                }
                Incoming::Request(req) => {
                    if req.session != self.session || req.character != character {
                        continue;
                    }
                    if req.action == "close_shop" {
                        if self
                            .merchant
                            .as_ref()
                            .is_some_and(|m| m.token == req.merchant_token)
                        {
                            self.close_shop();
                        }
                        continue;
                    }
                    if req.action != "purchase" || self.pending.is_some() {
                        continue;
                    }
                    let validation =
                        self.merchant
                            .as_ref()
                            .ok_or("merchant not open")
                            .and_then(|m| {
                                purchase(
                                    &self.config,
                                    &req,
                                    self.ledger.characters.get(&character).unwrap(),
                                    m,
                                    pg.rune_count,
                                )
                            });
                    match validation {
                        Ok(_) => {
                            let _ = self.tx.send(Work::Prepare(req.clone(), pg.rune_count));
                            self.pending = Some(Pending {
                                req,
                                stamp: self.stamp.load(Ordering::Acquire),
                                at: Instant::now(),
                                debited: false,
                                native_item_before: None,
                            });
                        }
                        Err(reason) => {
                            let ack = Ack {
                                id: req.id.clone(),
                                status: "rejected".into(),
                                amount: 0,
                                reason: reason.into(),
                            };
                            let _ = self.tx.send(Work::Finish(req, ack));
                        }
                    }
                }
                Incoming::Prepared(req, before) => {
                    if self.pending.as_ref().is_none_or(|p| p.req.id != req.id) {
                        continue;
                    }
                    let valid = req.session == self.session
                        && req.character == character
                        && pg.rune_count == before
                        && self.merchant.as_ref().is_some_and(|m| {
                            purchase(
                                &self.config,
                                &req,
                                self.ledger.characters.get(&character).unwrap(),
                                m,
                                pg.rune_count,
                            )
                            .is_ok()
                        });
                    let native_lot = self
                        .config
                        .shops
                        .iter()
                        .flat_map(|s| &s.offers)
                        .find(|o| o.id == req.offer)
                        .and_then(|o| o.native_item_lot);
                    let native_valid = native_lot.is_none_or(|lot| unsafe { lot_available(lot) });
                    if valid && native_valid && self.stamp.load(Ordering::Acquire) > 0 {
                        let native_before = native_lot
                            .and_then(|lot| unsafe { native_lot_items(lot) })
                            .map(|ids| {
                                let before = native_quantity(pg, &ids);
                                (ids, before)
                            });
                        pg.rune_count -= req.amount;
                        let awarded = native_lot.is_none_or(|lot| unsafe { award_native_lot(lot) });
                        game.save_requested = true;
                        if let Some(p) = &mut self.pending {
                            p.debited = true;
                            p.at = Instant::now();
                            p.stamp = self.stamp.load(Ordering::Acquire);
                            p.native_item_before = native_before;
                            if !awarded {
                                p.at = Instant::now() - Duration::from_secs(31);
                            }
                        }
                    } else {
                        let ack = Ack {
                            id: req.id.clone(),
                            status: "rejected".into(),
                            amount: 0,
                            reason: "merchant/wallet changed, native lot unavailable, or native save unavailable".into(),
                        };
                        let _ = self.tx.send(Work::Finish(req, ack));
                    }
                }
            }
        }
        if let Some(p) = &self.pending
            && p.debited
        {
            let save_failed = unsafe { CSMenuManImp::instance() }
                .ok()
                .and_then(|m| m.popup_menu)
                .is_some_and(|p| unsafe { p.as_ref().show_failed_to_save });
            let saved = !game.save_requested
                && self.stamp.load(Ordering::Acquire) > p.stamp
                && p.at.elapsed() > Duration::from_millis(1000)
                && p.native_item_before
                    .as_ref()
                    .is_none_or(|(ids, before)| native_quantity(pg, ids) > *before);
            if saved || save_failed || p.at.elapsed() > Duration::from_secs(30) {
                let ack = Ack {
                    id: p.req.id.clone(),
                    status: if saved && !save_failed {
                        "debited"
                    } else {
                        "uncertain"
                    }
                    .into(),
                    amount: p.req.amount,
                    reason: if saved && !save_failed {
                        String::new()
                    } else {
                        "native save did not confirm; transaction quarantined".into()
                    },
                };
                let req = p.req.clone();
                if let Some(p) = &mut self.pending {
                    p.debited = false;
                }
                let _ = self.tx.send(Work::Finish(req, ack));
            }
        }
        self.seq += 1;
        let boss_observation = unsafe { crate::boss_hud::sample() };
        Some(Snapshot {
            version: 1,
            pid: std::process::id(),
            session: self.session,
            seq: self.seq,
            timestamp_ms: epoch(),
            active: true,
            dead: false,
            character,
            runes: pg.rune_count,
            hp: stats.hp,
            max_hp,
            stamina: stats.stamina.min(max_stamina),
            max_stamina,
            defeated: self
                .ledger
                .characters
                .get(&self.character)
                .map(|c| c.defeated.iter().cloned().collect())
                .unwrap_or_default(),
            merchant: self.merchant.clone(),
            ack: self.ack.clone(),
            guard_seq: GUARD_SEQ.load(Ordering::Acquire),
            guard_damage: GUARD_DAMAGE.lock().map(|v| *v).unwrap_or(0.),
            heal_seq: self.heal_seq,
            damage_events: damage_events(),
            experience_seq: EXPERIENCE_SEQ.load(Ordering::Acquire),
            experience_total: EXPERIENCE_TOTAL.load(Ordering::Acquire),
            bosses_active: boss_observation.bosses,
            boss_diagnostics: Some(boss_observation.diagnostics),
        })
    }
    fn close_shop(&mut self) {
        self.merchant = None;
        if let Ok(mut shop) = SHOP.lock()
            && let Some(c) = shop.take()
        {
            // Only touch the talk instance while the same loaded NPC still resolves.
            let alive = unsafe { WorldChrMan::instance() }
                .ok()
                .and_then(|w| w.chr_ins_by_handle(&c.handle))
                .is_some_and(|chr| chr as *const _ as usize == c.chr);
            if alive {
                unsafe {
                    let npc = &mut *(c.npc as *mut eldenring::cs::CSNpcTalkIns);
                    npc.menu_state.current_open_menu = MenuType::None;
                }
            }
        }
    }
}
impl Drop for Driver {
    fn drop(&mut self) {
        let _ = self.tx.send(Work::Stop);
    }
}

// character_id is persistent save data. A zero id fallback distinguishes
// replaced/new slots using saved creation identity fields and name.
fn character_identity(slot: i32, pg: &eldenring::cs::PlayerGameData) -> String {
    if pg.character_id != 0 {
        format!("slot-{slot}-character-{:08x}", pg.character_id)
    } else {
        let mut hash = 14695981039346656037u64;
        for value in pg.character_name.iter().copied().chain([
            pg.archetype as u16,
            pg.gender as u16,
            pg.starting_gift as u16,
        ]) {
            hash ^= value as u64;
            hash = hash.wrapping_mul(1099511628211);
        }
        format!("slot-{slot}-fallback-{hash:016x}")
    }
}
unsafe fn death_observed(character: &str) -> bool {
    if character.is_empty() {
        return false;
    }
    let Ok(game) = (unsafe { GameMan::instance() }) else {
        return false;
    };
    if game.is_in_online_mode || game.warp_requested || game.save_slot < 0 {
        return false;
    }
    let Ok(session) = (unsafe { CSSessionManager::instance() }) else {
        return false;
    };
    if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None {
        return false;
    }
    let Ok(player) = (unsafe { PlayerIns::local_player() }) else {
        return false;
    };
    let Ok(data) = (unsafe { GameDataMan::instance() }) else {
        return false;
    };
    if !std::ptr::eq(
        player.player_game_data.as_ptr(),
        data.main_player_game_data.as_ref(),
    ) || !player.chr_ins.chr_flags1c8.is_active()
        || character_identity(game.save_slot, &data.main_player_game_data) != character
    {
        return false;
    }
    player.chr_ins.chr_flags1c5.death_flag() || player.chr_ins.modules.data.hp <= 0
}
fn native_quantity(pg: &eldenring::cs::PlayerGameData, ids: &[ItemId]) -> u64 {
    let inventory = pg
        .equipment
        .equip_inventory_data
        .items_data
        .items()
        .filter(|i| ids.contains(&i.item_id))
        .map(|i| i.quantity as u64)
        .sum::<u64>();
    inventory
        + pg.storage
            .as_ref()
            .map(|s| {
                s.items_data
                    .items()
                    .filter(|i| ids.contains(&i.item_id))
                    .map(|i| i.quantity as u64)
                    .sum::<u64>()
            })
            .unwrap_or(0)
}
unsafe fn native_lot_items(id: u32) -> Option<Vec<ItemId>> {
    let repo = (unsafe { SoloParamRepository::instance() }).ok()?;
    let row = repo.get::<ItemLotParam_map>(id)?;
    let entries = [
        (
            row.lot_item_id01(),
            row.lot_item_base_point01(),
            row.lot_item_category01(),
        ),
        (
            row.lot_item_id02(),
            row.lot_item_base_point02(),
            row.lot_item_category02(),
        ),
        (
            row.lot_item_id03(),
            row.lot_item_base_point03(),
            row.lot_item_category03(),
        ),
        (
            row.lot_item_id04(),
            row.lot_item_base_point04(),
            row.lot_item_category04(),
        ),
        (
            row.lot_item_id05(),
            row.lot_item_base_point05(),
            row.lot_item_category05(),
        ),
        (
            row.lot_item_id06(),
            row.lot_item_base_point06(),
            row.lot_item_category06(),
        ),
        (
            row.lot_item_id07(),
            row.lot_item_base_point07(),
            row.lot_item_category07(),
        ),
        (
            row.lot_item_id08(),
            row.lot_item_base_point08(),
            row.lot_item_category08(),
        ),
    ];
    let mut items = Vec::new();
    // Native offers support existing goods and keys. Receipt evidence includes
    // category as well as parameter id, so unrelated inventory cannot confirm it.
    for (item, weight, category) in entries {
        if weight > 0 {
            if item < 0
                || category != ItemCategory::Goods as i32
                || repo.get::<EquipParamGoods>(item as u32).is_none()
            {
                return None;
            }
            items.push(ItemId::new(ItemCategory::Goods, item as u32).ok()?);
        }
    }
    (!items.is_empty()).then_some(items)
}
/// Reject missing, exhausted or potentially empty/random lots before debit.
/// Native offers intentionally require an explicit existing map lot in JSON.
unsafe fn lot_available(id: u32) -> bool {
    let Some(row) = (unsafe { SoloParamRepository::instance() })
        .ok()
        .and_then(|r| r.get::<ItemLotParam_map>(id))
    else {
        return false;
    };
    let Some(flags) = (unsafe { CSEventFlagMan::instance() }).ok() else {
        return false;
    };
    let flag_ids = [
        row.get_item_flag_id(),
        row.get_item_flag_id01(),
        row.get_item_flag_id02(),
        row.get_item_flag_id03(),
        row.get_item_flag_id04(),
        row.get_item_flag_id05(),
        row.get_item_flag_id06(),
        row.get_item_flag_id07(),
        row.get_item_flag_id08(),
    ];
    if flag_ids
        .into_iter()
        .any(|f| f != 0 && flags.virtual_memory_flag.get_flag(f))
    {
        return false;
    }
    let entries = [
        (
            row.lot_item_id01(),
            row.lot_item_num01(),
            row.lot_item_base_point01(),
        ),
        (
            row.lot_item_id02(),
            row.lot_item_num02(),
            row.lot_item_base_point02(),
        ),
        (
            row.lot_item_id03(),
            row.lot_item_num03(),
            row.lot_item_base_point03(),
        ),
        (
            row.lot_item_id04(),
            row.lot_item_num04(),
            row.lot_item_base_point04(),
        ),
        (
            row.lot_item_id05(),
            row.lot_item_num05(),
            row.lot_item_base_point05(),
        ),
        (
            row.lot_item_id06(),
            row.lot_item_num06(),
            row.lot_item_base_point06(),
        ),
        (
            row.lot_item_id07(),
            row.lot_item_num07(),
            row.lot_item_base_point07(),
        ),
        (
            row.lot_item_id08(),
            row.lot_item_num08(),
            row.lot_item_base_point08(),
        ),
    ];
    let mut populated = false;
    for (item, count, weight) in entries {
        if weight > 0 {
            if item < 0 || count == 0 {
                return false;
            }
            populated = true;
        }
    }
    populated && unsafe { native_lot_items(id) }.is_some()
}
unsafe fn award_native_lot(id: u32) -> bool {
    let Some(captured) = SHOP.lock().ok().and_then(|s| s.clone()) else {
        return false;
    };
    let alive = (unsafe { WorldChrMan::instance() })
        .ok()
        .and_then(|w| w.chr_ins_by_handle(&captured.handle))
        .is_some_and(|chr| chr as *const _ as usize == captured.chr);
    if !alive {
        return false;
    }
    let npc = unsafe { &*(captured.npc as *const eldenring::cs::CSNpcTalkIns) };
    let mut event = CSEzStateTalkEvent::new(npc.base.talk_id, npc);
    let args = EzStateEvent::from((104, [eldenring::ez_state::EzStateValue::Int32(id as i32)]));
    (event.vftable.invoke)(&mut event, &args);
    true
}

/// Install from the pinned SDK vtable, after complete executable SHA verification.
pub unsafe fn install_talk_hook() -> Result<(), String> {
    install_talk_hook_once(&TALK_HOOK_INSTALLED, || unsafe {
        install_shared_talk_hook()
    })
}
fn install_talk_hook_once(
    installed: &Mutex<bool>,
    install: impl FnOnce() -> Result<(), String>,
) -> Result<(), String> {
    let mut installed = installed
        .lock()
        .map_err(|_| "shared talk-event hook initialization lock poisoned")?;
    if *installed {
        return Ok(());
    }
    install()?;
    *installed = true;
    Ok(())
}
fn dispatch_talk_event(
    interaction: impl FnOnce() -> bool,
    campaign: impl FnOnce() -> bool,
    original: impl FnOnce(),
) {
    let intercepted =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| interaction() || campaign()))
            .unwrap_or(false);
    if !intercepted {
        original();
    }
}
unsafe fn install_shared_talk_hook() -> Result<(), String> {
    // Exact worldwide 2.7.1.0 SDK bundle at pinned commit 59fbd3b, rva_ww.rs.
    // The process SHA has already been verified before this entry point.
    let image = Program::current().image();
    let vmt = 0x2c02e18usize;
    let address = usize::from_le_bytes(
        image
            .get(vmt + 8..vmt + 16)
            .ok_or("shared talk-event vtable outside verified image")?
            .try_into()
            .unwrap(),
    );
    if address < image.as_ptr() as usize || address + 16 > image.as_ptr() as usize + image.len() {
        return Err("shared talk-event hook target outside verified image".into());
    }
    let expected = [
        0x48, 0x8b, 0xc4, 0x55, 0x56, 0x57, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41, 0x57,
    ];
    let target_rva = address - image.as_ptr() as usize;
    if target_rva != 0xea7100
        || image.get(target_rva..target_rva + expected.len()) != Some(expected.as_slice())
    {
        return Err(format!(
            "shared talk-event hook fingerprint mismatch at RVA {target_rva:#x}; target may already be detoured"
        ));
    }
    let callback = |regs: *mut Registers, original: usize| -> usize {
        TALK_EVENT_ORIGINAL.store(original, Ordering::Release);
        // The campaign and interaction UI own one dispatcher detour. Installing
        // a second ilhook at its FF25 jump would decode embedded pointer bytes.
        let event = std::panic::catch_unwind(|| unsafe {
            crate::interaction_runtime::script_event((*regs).rdx as usize)
        })
        .ok()
        .flatten();
        dispatch_talk_event(
            || {
                event.as_ref().is_some_and(|event| unsafe {
                    crate::interaction_runtime::event_intercept(&*regs, event)
                })
            },
            || {
                event
                    .as_ref()
                    .is_some_and(|event| unsafe { intercept(&*regs, event) })
            },
            || {
                let call: unsafe extern "system" fn(*mut CSEzStateTalkEvent, *const EzStateEvent) =
                    unsafe { std::mem::transmute(original) };
                unsafe { call((*regs).rcx as *mut _, (*regs).rdx as *const _) };
            },
        );
        0
    };
    let hook = unsafe {
        crate::hosting::hook(address, |option| {
            hook_closure_retn(address, callback, option, HookFlags::empty())
        })
    }
    .map_err(|e| format!("shared talk-event hook at RVA {target_rva:#x}: {e:?}"))?;
    std::mem::forget(hook);
    Ok(())
}
/// Retire only a proven current native talk-list job during grace recovery.
/// The caller validates owner/rest/session identity immediately before this call.
/// Verified event67 ea89ba -> e9f500 finalizes this owner's NpcMenuState job;
/// event12 is a no-op in this executable and cannot be used to hide its window.
/// The Minecraft shop stands in for this talk instance's RegularShop until
/// Minecraft closes it. A busy lock reports closed, as the native query would.
pub(crate) fn replacement_shop_open(npc: usize) -> bool {
    SHOP.try_lock()
        .ok()
        .is_some_and(|shop| shop.as_ref().is_some_and(|c| c.npc == npc))
}

pub(crate) unsafe fn close_original_talk_menu(npc: &eldenring::cs::CSNpcTalkIns) -> bool {
    let original = TALK_EVENT_ORIGINAL.load(Ordering::Acquire);
    if original == 0
        || npc.menu_state.current_open_menu != MenuType::TalkList
        || npc.menu_state.open_menu_job.finalize_callback_job.is_none()
        || npc
            .menu_state
            .owner
            .is_none_or(|owner| owner.as_ptr() != std::ptr::from_ref(npc).cast_mut())
    {
        return false;
    }
    let mut event = CSEzStateTalkEvent::new(npc.base.talk_id, npc);
    let args = EzStateEvent::from(67);
    let call: unsafe extern "system" fn(*mut CSEzStateTalkEvent, *const EzStateEvent) =
        unsafe { std::mem::transmute(original) };
    unsafe { call(&mut event, &args) };
    true
}

#[cfg(test)]
mod shared_talk_hook_tests {
    use super::*;
    use std::{cell::Cell, ffi::c_void};

    #[test]
    fn campaign_then_interaction_share_one_detour_and_restore_original() {
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn VirtualAlloc(a: *mut c_void, s: usize, t: u32, p: u32) -> *mut c_void;
            fn VirtualFree(a: *mut c_void, s: usize, t: u32) -> i32;
        }
        let page = unsafe { VirtualAlloc(std::ptr::null_mut(), 4096, 0x3000, 0x40) } as usize;
        assert_ne!(page, 0);
        let original = [0x90u8; 32];
        unsafe { std::ptr::copy_nonoverlapping(original.as_ptr(), page as *mut u8, 32) };
        let installed = Mutex::new(false);
        let mut detour = None;
        // Campaign startup installs the shared dispatcher before Driver::init.
        install_talk_hook_once(&installed, || {
            detour = Some(
                unsafe {
                    hook_closure_retn(
                        page,
                        |_, _| 0,
                        ilhook::x64::CallbackOption::None,
                        HookFlags::empty(),
                    )
                }
                .map_err(|e| format!("test detour: {e:?}"))?,
            );
            Ok(())
        })
        .unwrap();
        let patched = unsafe { *(page as *const [u8; 32]) };
        assert!(patched[0] == 0xe9 || patched[..6] == [0xff, 0x25, 0, 0, 0, 0]);
        // The interaction ensure call must not decode the first detour's pointer.
        install_talk_hook_once(&installed, || panic!("duplicate detour attempted")).unwrap();
        assert_eq!(unsafe { *(page as *const [u8; 32]) }, patched);
        drop(detour);
        assert_eq!(unsafe { *(page as *const [u8; 32]) }, original);
        assert_ne!(unsafe { VirtualFree(page as *mut c_void, 0, 0x8000) }, 0);
    }

    #[test]
    fn failed_shared_install_can_retry_without_marking_ready() {
        let installed = Mutex::new(false);
        assert!(install_talk_hook_once(&installed, || Err("installation failed".into())).is_err());
        assert!(!*installed.lock().unwrap());
        install_talk_hook_once(&installed, || Ok(())).unwrap();
        install_talk_hook_once(&installed, || panic!("duplicate install attempted")).unwrap();
    }

    #[test]
    fn shared_dispatcher_forwards_unhandled_once_and_skips_handled_events() {
        for (interaction, campaign) in [(false, false), (true, false), (false, true)] {
            let campaign_calls = Cell::new(0);
            let original_calls = Cell::new(0);
            dispatch_talk_event(
                || interaction,
                || {
                    campaign_calls.set(campaign_calls.get() + 1);
                    campaign
                },
                || original_calls.set(original_calls.get() + 1),
            );
            assert_eq!(campaign_calls.get(), i32::from(!interaction));
            assert_eq!(original_calls.get(), i32::from(!interaction && !campaign));
        }
        let original_calls = Cell::new(0);
        dispatch_talk_event(
            || panic!("interception panic"),
            || panic!("campaign must not run after panic"),
            || original_calls.set(original_calls.get() + 1),
        );
        assert_eq!(original_calls.get(), 1);
    }
}
unsafe fn intercept(
    regs: &Registers,
    event: &crate::interaction_runtime::ScriptInvocation,
) -> bool {
    let Some(config) = CONFIG.get().filter(|c| c.enabled) else {
        return false;
    };
    if regs.rcx == 0 || regs.rdx == 0 {
        return false;
    }
    let Ok(game) = (unsafe { GameMan::instance() }) else {
        return false;
    };
    if game.is_in_online_mode || game.warp_requested {
        return false;
    }
    let Ok(session) = (unsafe { CSSessionManager::instance() }) else {
        return false;
    };
    if session.lobby_state != LobbyState::None || session.protocol_state != ProtocolState::None {
        return false;
    }
    let id = event.id;
    // OpenSoul and ReallocateAttributes are the only native allocation menus.
    if id == 31 || id == 113 {
        return true;
    }
    if id != 22 || event.args.len() < 2 {
        return false;
    }
    let Some(range) = event.args[0] else {
        return false;
    };
    if config.shop(&range.to_string()).is_none() {
        return false;
    }
    let owner = unsafe { &mut *(regs.rcx as *mut CSEzStateTalkEvent) };
    let npc = unsafe { owner.npc_talk_ins.as_mut() };
    let handle = npc.base.field_ins_handle;
    let Some(chr) = (unsafe { WorldChrMan::instance() })
        .ok()
        .and_then(|w| w.chr_ins_by_handle(&handle))
    else {
        return false;
    };
    let captured = Captured {
        range,
        handle,
        npc: npc as *mut _ as usize,
        chr: chr as *const _ as usize,
        serial: SHOP_SERIAL.fetch_add(1, Ordering::Relaxed),
    };
    // ESD's IsMenuOpen sees the replacement shop until Minecraft closes it.
    npc.menu_state.current_open_menu = MenuType::RegularShop;
    if let Ok(mut shop) = SHOP.lock() {
        *shop = Some(captured);
        true
    } else {
        false
    }
}

#[cfg(test)]
mod guard_ack_tests {
    use super::{guard_absorbed, guard_ack_valid};

    #[test]
    fn low_stamina_blocks_only_the_share_it_pays_for() {
        // Shipped rules: 4 base + 1.5 per raw damage.
        assert_eq!(guard_absorbed(200., 100., 4., 1.5), 1.);
        assert_eq!(guard_absorbed(154., 100., 4., 1.5), 1.);
        assert!((guard_absorbed(77., 100., 4., 1.5) - 0.5).abs() < 1e-12);
        assert_eq!(guard_absorbed(0., 100., 4., 1.5), 0.);
        assert_eq!(guard_absorbed(-3., 100., 4., 1.5), 0.);
        assert_eq!(guard_absorbed(f64::NAN, 100., 4., 1.5), 0.);
    }

    #[test]
    fn echoed_cumulative_guard_damage_round_trips_and_tolerates_rounding() {
        // Live failure: after seven blocks the echoed total parsed one ULP
        // above native's sum and every later raised-shield hit went through.
        let mut sum = 0f64;
        let mut seed = 12345u64;
        for seq in 1..=200_000u64 {
            seed = seed
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            sum += (seed >> 11) as f64 / (1u64 << 53) as f64 * 29.0;
            let echoed: f64 = serde_json::from_str(&serde_json::to_string(&sum).unwrap()).unwrap();
            assert_eq!(echoed.to_bits(), sum.to_bits());
            assert!(guard_ack_valid(seq, echoed, seq, sum));
        }
        let ulp_high = f64::from_bits(sum.to_bits() + 1);
        assert!(guard_ack_valid(3, ulp_high, 3, sum));
        // Genuine over-acknowledgement still revokes the guard.
        assert!(!guard_ack_valid(4, sum, 3, sum));
        assert!(!guard_ack_valid(3, sum + 0.01, 3, sum));
        assert!(!guard_ack_valid(3, f64::NAN, 3, sum));
    }
}
