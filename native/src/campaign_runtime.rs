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
static LOOT: Mutex<LootLog> = Mutex::new(LootLog::new());

/// One confirmed ordinary (non-boss) kill. Minecraft rolls its configured drop
/// table once per sequence and saves its cursor with the delivered items.
/// `map`/`position` place the drop where the enemy died, in the shared world's
/// stable region frame; both are absent while that world is not live.
#[derive(Clone, Debug, PartialEq, Serialize)]
struct LootEvent {
    seq: u64,
    max_hp: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    map: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    position: Option<[f64; 3]>,
}
/// Cumulative sequence and the latest bounded events, read together so a
/// publication never carries an event beyond its own `loot_seq`.
struct LootLog {
    seq: u64,
    events: VecDeque<LootEvent>,
}
impl LootLog {
    const fn new() -> Self {
        Self {
            seq: 0,
            events: VecDeque::new(),
        }
    }
    fn record(&mut self, max_hp: i32, at: Option<(u32, [f64; 3])>) {
        if max_hp <= 0 {
            return;
        }
        self.seq += 1;
        self.events.push_back(LootEvent {
            seq: self.seq,
            max_hp,
            map: at.map(|(map, _)| map),
            position: at.map(|(_, position)| position),
        });
        while self.events.len() > 64 {
            self.events.pop_front();
        }
    }
    fn observation(&self) -> (u64, Vec<LootEvent>) {
        (self.seq, self.events.iter().cloned().collect())
    }
}

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
    // Bosses keep their authored first-clear rewards; only ordinary kills drop loot.
    if !receipt.boss
        && let Ok(mut loot) = LOOT.lock()
    {
        loot.record(
            receipt.max_hp_before,
            crate::scene_camera::region_position(receipt.position),
        );
    }
}
fn loot_observation() -> (u64, Vec<LootEvent>) {
    LOOT.lock().map(|l| l.observation()).unwrap_or_default()
}

#[derive(Clone, Serialize)]
struct DamageEvent {
    seq: u64,
    raw_damage: f64,
    blocked: bool,
    /// Minecraft absorption hearts this hit spent, in Minecraft health units.
    #[serde(skip_serializing_if = "is_zero")]
    absorbed: f64,
    /// A held totem of undying saved the player from this hit.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    totem: bool,
}
fn is_zero(value: &f64) -> bool {
    *value == 0.
}
fn record_damage(raw: f64, blocked: bool, absorbed: f64, totem: bool) {
    if let Ok(mut events) = DAMAGE_EVENTS.lock() {
        let seq = DAMAGE_SEQ.fetch_add(1, Ordering::Relaxed) + 1;
        events.push_back(DamageEvent {
            seq,
            raw_damage: raw,
            blocked,
            absorbed,
            totem,
        });
        while events.len() > 64 {
            events.pop_front();
        }
    }
}
/// Absorption and totems that hits after Minecraft's last applied event have
/// already spent: Minecraft's published balance does not include them yet.
fn unacknowledged(ack: u64) -> (f64, u64) {
    DAMAGE_EVENTS
        .lock()
        .map(|events| {
            events
                .iter()
                .filter(|e| e.seq > ack)
                .fold((0., 0), |(absorbed, totems), e| {
                    (absorbed + e.absorbed, totems + u64::from(e.totem))
                })
        })
        .unwrap_or((f64::INFINITY, u64::MAX))
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
    /// Sum of equipped Minecraft armor's damage-reduction percentage points.
    pub armor: f64,
    #[serde(default = "full_resistance")]
    pub resistance: f64,
    /// Retained for compatibility; does not modify percentage mitigation.
    pub toughness: f64,
    pub guest_max_hp: f64,
    pub shield_ready: bool,
    pub stamina: f64,
    pub using_item: bool,
    #[serde(default)]
    pub guard_seq: u64,
    #[serde(default)]
    pub guard_damage: f64,
    /// Last native damage event Minecraft applied (armor wear, absorption, totems).
    #[serde(default)]
    pub damage_ack: u64,
    /// Minecraft absorption hearts, in Minecraft health units.
    #[serde(default)]
    pub absorption: f64,
    /// Death-protection items (totems of undying) in the paired player's hands.
    #[serde(default)]
    pub totems: u32,
    /// Exact native boss instance IDs actually drawn by the Minecraft HUD.
    #[serde(default)]
    pub boss_hud_ids: Vec<String>,
    #[serde(default)]
    pub boss_hud_timestamp_ms: u64,
}
fn full_resistance() -> f64 {
    1.
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
/// The current paired guest's fresh combat publication.
fn fresh_combat() -> Option<(&'static Arc<Config>, CombatState)> {
    let cfg = CONFIG.get().filter(|c| c.enabled)?;
    // These locks only copy scalar/bounded state, and the worker performs its
    // file reads before acquiring COMBAT. Release each before the next lock.
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
        || !state.resistance.is_finite()
        || !(0. ..=1.).contains(&state.resistance)
        || !state.armor.is_finite()
        || !state.toughness.is_finite()
        || !(0. ..=100.).contains(&state.armor)
        || !(0. ..=100.).contains(&state.toughness)
        || !state.guest_max_hp.is_finite()
        || state.guest_max_hp <= 0.
        || !state.stamina.is_finite()
    {
        return None;
    }
    Some((cfg, state))
}
/// The fixed conversion prevents Vigor growth from reducing stamina pressure.
/// `hp`/`max_hp` are the player's native health before this hit.
#[cfg(test)]
pub fn filter_damage(damage: i32, frontal_block: bool, hp: i32, max_hp: i32) -> Option<i32> {
    filter_damage_from(damage, frontal_block, hp, max_hp, 0.)
}
pub fn filter_damage_from(
    damage: i32,
    frontal_block: bool,
    hp: i32,
    max_hp: i32,
    attack_bonus: f64,
) -> Option<i32> {
    // A transient publication must not turn a raised shield into an open hit.
    let (cfg, state) = fresh_combat()?;
    if damage <= 0 {
        return None;
    }
    let raw = modified_attack_damage(
        damage as f64 * cfg.combat.native_incoming_damage_scale / cfg.native_hp_per_minecraft_hp(),
        attack_bonus,
    );
    if raw == 0. {
        return Some(0);
    }
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
                record_damage(raw, true, 0., false);
                return Some(0);
            }
            GUARD_BREAKS.fetch_add(1, Ordering::Release);
            let through = raw * (1. - absorbed);
            record_damage(raw * absorbed, true, 0., false);
            return Some(resolve_open(through, &state, cfg, hp, max_hp));
        }
    }
    Some(resolve_open(raw, &state, cfg, hp, max_hp))
}
fn modified_attack_damage(raw: f64, bonus: f64) -> f64 {
    (raw + bonus.clamp(-20., 15.)).max(0.)
}
/// Applies and records an open hit against Minecraft's current absorption and
/// totems, less what earlier hits Minecraft has not applied yet already spent.
fn resolve_open(raw: f64, state: &CombatState, cfg: &Config, hp: i32, max_hp: i32) -> i32 {
    let (spent_absorption, spent_totems) = unacknowledged(state.damage_ack);
    let absorption = if state.absorption.is_finite() {
        state.absorption.clamp(0., 1_000_000.)
    } else {
        0.
    };
    let hit = open_hit(
        damage_after_armor(raw, state.armor) * state.resistance,
        absorption - spent_absorption,
        u64::from(state.totems.min(2)) > spent_totems,
        cfg.native_hp_per_minecraft_hp(),
        hp,
        one_health(max_hp, state.guest_max_hp),
    );
    record_damage(raw, false, hit.absorbed, hit.totem);
    hit.damage
}
#[derive(Clone, Copy, Debug, PartialEq)]
struct OpenHit {
    damage: i32,
    absorbed: f64,
    totem: bool,
}
/// Vanilla order after armor: absorption hearts take the hit first, the rest is
/// health. A lethal remainder with a totem in hand instead leaves one Minecraft
/// health point, as vanilla death protection sets health to 1.
fn open_hit(
    after_armor: f64,
    absorption: f64,
    totem: bool,
    hp_per_minecraft_hp: f64,
    hp: i32,
    one_health: i32,
) -> OpenHit {
    let absorbed = after_armor.min(absorption).max(0.);
    let damage = ((after_armor - absorbed) * hp_per_minecraft_hp)
        .round()
        .max(0.) as i32;
    if totem && hp > 0 && damage >= hp {
        return OpenHit {
            damage: (hp - one_health).max(0),
            absorbed,
            totem: true,
        };
    }
    OpenHit {
        damage,
        absorbed,
        totem: false,
    }
}
/// One Minecraft health point in native HP, as the guest's health bar shows it.
fn one_health(max_hp: i32, guest_max_hp: f64) -> i32 {
    let value = max_hp as f64 / guest_max_hp;
    if value.is_finite() {
        (value.round() as i32).max(1)
    } else {
        1
    }
}
/// Elden Ring's own hazards (falls, self-inflicted hits) keep their native
/// damage and bypass armor; only a lethal one is caught by a held totem.
/// Returns the reduced native damage when a totem was spent.
pub fn death_protection(damage: i32, hp: i32, max_hp: i32) -> Option<i32> {
    let (_, state) = fresh_combat()?;
    if damage <= 0 || hp <= 0 || damage < hp {
        return None;
    }
    let (_, spent_totems) = unacknowledged(state.damage_ack);
    if u64::from(state.totems.min(2)) <= spent_totems {
        return None;
    }
    record_damage(0., false, 0., true);
    Some((hp - one_health(max_hp, state.guest_max_hp)).max(0))
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

/// Presentation-only load identity. Combat sessions and purchase journals keep
/// their existing identity when the same character returns from the title menu.
#[derive(Default)]
struct SaveLoadState {
    sequence: u64,
    loaded: bool,
}
impl SaveLoadState {
    fn observe_slot(&mut self, slot: i32, warping: bool, online: bool) {
        // Only an explicitly unloaded offline save rearms the welcome guide.
        // Missing objects, focus loss, death, grace and map loads do not.
        if slot < 0 && !warping && !online {
            self.loaded = false;
        }
    }

    fn admit_loaded(&mut self) {
        if !self.loaded {
            self.sequence = self.sequence.saturating_add(1).min(i64::MAX as u64);
            self.loaded = true;
        }
    }
}

#[cfg(test)]
mod save_load_tests {
    use super::SaveLoadState;

    #[test]
    fn same_save_returns_from_title_with_a_new_presentation_identity() {
        let mut state = SaveLoadState::default();
        state.observe_slot(-1, false, false);
        assert_eq!(state.sequence, 0, "an unloaded title is not a save load");
        state.observe_slot(0, false, false);
        state.admit_loaded();
        assert_eq!(state.sequence, 1);
        state.observe_slot(-1, false, false);
        state.observe_slot(-1, false, false);
        assert_eq!(
            state.sequence, 1,
            "a title menu cannot publish another load"
        );
        state.observe_slot(0, false, false);
        state.admit_loaded();
        assert_eq!(state.sequence, 2);
    }

    #[test]
    fn focus_death_grace_and_warp_do_not_rearm_the_guide() {
        let mut state = SaveLoadState::default();
        state.admit_loaded();
        // Observation runs even when gameplay sampling is suspended. A loaded
        // slot during focus loss, death or a grace menu retains the identity.
        for _ in 0..3 {
            state.observe_slot(0, false, false);
            state.admit_loaded();
        }
        state.observe_slot(-1, true, false);
        state.observe_slot(0, false, false);
        state.admit_loaded();
        assert_eq!(state.sequence, 1);
        state.observe_slot(-1, false, true);
        state.admit_loaded();
        assert_eq!(
            state.sequence, 1,
            "online transitions grant no new guide load"
        );
    }
}

#[derive(Clone, Serialize)]
pub struct Snapshot {
    version: u32,
    pid: u32,
    session: u64,
    save_load: u64,
    seq: u64,
    timestamp_ms: u64,
    active: bool,
    identity_ready: bool,
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
    loot_seq: u64,
    loot_events: Vec<LootEvent>,
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
    save_load: SaveLoadState,
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
mod armor_damage_tests {
    use super::*;

    #[test]
    fn percentages_survive_native_hp_conversion_without_toughness_or_a_one_hp_floor() {
        let cfg: Config = serde_json::from_str(include_str!("../../config/campaign.json")).unwrap();
        let mut state: CombatState = serde_json::from_value(serde_json::json!({
            "version": 1, "session": 7, "character": "armor-test", "timestamp_ms": 1000,
            "armor": 15., "toughness": 0., "guest_max_hp": 20.,
            "shield_ready": false, "stamina": 20., "using_item": false,
        }))
        .unwrap();
        let open_damage = |raw: f64, state: &CombatState, cfg: &Config| {
            open_hit(
                damage_after_armor(raw, state.armor) * state.resistance,
                0.,
                false,
                cfg.native_hp_per_minecraft_hp(),
                i32::MAX,
                1,
            )
            .damage
        };
        // Twenty fixed MC HP units are 414 native HP on the opening baseline.
        assert_eq!(open_damage(20., &state, &cfg), 352);
        state.toughness = 30.;
        state.guest_max_hp = 100.;
        assert_eq!(open_damage(20., &state, &cfg), 352);
        // The same reduction applies to the portion left by an exhausted guard.
        assert_eq!(open_damage(10., &state, &cfg), 176);
        state.armor = 100.;
        assert_eq!(open_damage(20., &state, &cfg), 0);
    }

    #[test]
    fn absorption_takes_the_hit_after_armor_before_native_health() {
        let partly = open_hit(6., 4., false, 20., 1000, 50);
        assert_eq!(partly.absorbed, 4.);
        assert_eq!(partly.damage, 40, "only the 2 unabsorbed points reach HP");
        let fully = open_hit(3., 4., false, 20., 1000, 50);
        assert_eq!((fully.absorbed, fully.damage), (3., 0));
        let spent = open_hit(3., -1., false, 20., 1000, 50);
        assert_eq!(
            (spent.absorbed, spent.damage),
            (0., 60),
            "absorption already spent by unapplied hits is not reused"
        );
    }
    #[test]
    fn resistance_reduces_the_open_hit_before_absorption_and_keeps_old_guests_neutral() {
        let state: CombatState = serde_json::from_value(serde_json::json!({
            "version":1,"session":1,"character":"paired","timestamp_ms":1000,
            "armor":20.,"toughness":0.,"guest_max_hp":40.,"shield_ready":false,"stamina":50.,"using_item":false
        })).unwrap();
        assert_eq!(state.resistance, 1.);
        let raw = damage_after_armor(20., state.armor) * 0.4;
        let hit = open_hit(raw, 4., false, 20., 1000, 20);
        assert_eq!(hit.absorbed, 4.);
        assert_eq!(hit.damage, 48, "armor, Resistance III, then absorption");
        assert_eq!(open_hit(0., 4., false, 20., 1000, 20).damage, 0);
    }
    #[test]
    fn weakness_can_cancel_an_attack_and_strength_is_applied_before_mitigation() {
        assert_eq!(modified_attack_damage(3., -4.), 0.);
        assert_eq!(modified_attack_damage(8., -4.), 4.);
        assert_eq!(modified_attack_damage(8., 6.), 14.);
        let mitigated = damage_after_armor(modified_attack_damage(8., 6.), 20.) * 0.4;
        assert!((mitigated - 4.48).abs() < 1e-6);
    }

    #[test]
    fn a_held_totem_leaves_one_minecraft_health_point_on_a_lethal_hit_only() {
        let lethal = open_hit(30., 0., true, 20., 400, 20);
        assert_eq!(
            lethal,
            OpenHit {
                damage: 380,
                absorbed: 0.,
                totem: true
            }
        );
        let survivable = open_hit(10., 0., true, 20., 400, 20);
        assert_eq!(
            survivable,
            OpenHit {
                damage: 200,
                absorbed: 0.,
                totem: false
            }
        );
        let exact = open_hit(20., 0., true, 20., 400, 20);
        assert!(exact.totem, "reaching exactly zero is lethal");
        let none = open_hit(30., 0., false, 20., 400, 20);
        assert_eq!(none.damage, 600, "without a totem the hit kills");
        let low = open_hit(30., 0., true, 20., 10, 20);
        assert_eq!(
            (low.damage, low.totem),
            (0, true),
            "never heals a lower health"
        );
        let absorbed = open_hit(30., 8., true, 20., 1000, 20);
        assert_eq!(
            (absorbed.damage, absorbed.totem),
            (440, false),
            "absorption first, then the remainder decides lethality"
        );
        assert_eq!(one_health(400, 20.), 20);
        assert_eq!(one_health(5, 20.), 1);
        assert_eq!(one_health(400, f64::NAN), 1);
    }
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
            resistance: 1.,
            toughness: 0.,
            guest_max_hp: 20.,
            shield_ready: true,
            stamina: 1000.,
            using_item: true,
            guard_seq: 0,
            guard_damage: 0.,
            damage_ack: 0,
            absorption: 0.,
            totems: 0,
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
                result_tx
                    .send(filter_damage(100, true, 1000, 1000))
                    .unwrap();
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
        assert_eq!(filter_damage(100, true, 1000, 1000), None);
        *COMBAT.lock().unwrap() = old_combat;
        *CURRENT.lock().unwrap() = old_current;
        *GUARD_DAMAGE.lock().unwrap() = old_sum;
        GUARD_SEQ.store(old_seq, Ordering::Release);
        *DAMAGE_EVENTS.lock().unwrap() = old_events;
        DAMAGE_SEQ.store(old_event_seq, Ordering::Release);
    }
}

#[cfg(test)]
mod loot_log_tests {
    use super::*;

    #[test]
    fn ordinary_kills_publish_bounded_increasing_events_within_their_sequence() {
        let mut log = LootLog::new();
        assert_eq!(log.observation(), (0, Vec::new()));
        log.record(0, None);
        log.record(-5, None);
        assert_eq!(
            log.observation().0,
            0,
            "invalid HP never consumes a sequence"
        );
        for hp in 1..=70 {
            log.record(hp * 100, (hp == 70).then_some((7, [1.5, -2., 3.25])));
        }
        let (seq, events) = log.observation();
        assert_eq!(seq, 70);
        assert_eq!(
            events.len(),
            64,
            "publication keeps only the latest 64 kills"
        );
        assert_eq!(events.first().unwrap().seq, 7);
        assert_eq!(
            events.last().unwrap(),
            &LootEvent {
                seq: 70,
                max_hp: 7000,
                map: Some(7),
                position: Some([1.5, -2., 3.25]),
            }
        );
        assert!(events.windows(2).all(|w| w[0].seq + 1 == w[1].seq));
        let json = serde_json::to_value(&events[0]).unwrap();
        assert_eq!(
            json,
            serde_json::json!({"seq": 7, "max_hp": 700}),
            "a kill outside the live shared world carries no position"
        );
        assert_eq!(
            serde_json::to_value(events.last().unwrap()).unwrap(),
            serde_json::json!({"seq": 70, "max_hp": 7000, "map": 7, "position": [1.5, -2.0, 3.25]})
        );
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
            save_load: SaveLoadState::default(),
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
    /// Grace resets revoke gameplay while retaining read-only identity admission.
    pub unsafe fn tick(&mut self, enabled: bool, identity_allowed: bool) {
        // This read precedes the gameplay gate: title transitions must still
        // be observed while focus or compositor ownership is suspended.
        if let Ok(game) = unsafe { GameMan::instance() } {
            self.save_load.observe_slot(
                game.save_slot,
                game.warp_requested,
                game.is_in_online_mode,
            );
        }
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
                    save_load: self.save_load.sequence,
                    seq: self.seq,
                    timestamp_ms: epoch(),
                    active: false,
                    identity_ready: identity_allowed
                        && unsafe { identity_observed(&self.character) },
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
                    loot_seq: loot_observation().0,
                    loot_events: Vec::new(),
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
        self.save_load.admit_loaded();
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
            if let Ok(mut loot) = LOOT.lock() {
                *loot = LootLog::new();
            }
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
                    if req.action == "unlock_all_graces" {
                        let result = if crate::grace_unlock::fresh_request(&req, epoch()) {
                            unsafe { crate::grace_unlock::unlock_all() }
                        } else {
                            Err("Site of Grace debug request expired")
                        };
                        self.ack = Some(match result {
                            Ok(unlocked) => Ack {
                                id: req.id,
                                status: if unlocked.remaining == 0 {
                                    "graces_unlocked"
                                } else {
                                    "graces_partial"
                                }
                                .into(),
                                amount: unlocked.graces,
                                reason: format!(
                                    "{} of {} grace/map reveal flags remain locked",
                                    unlocked.remaining, unlocked.total
                                ),
                            },
                            Err(reason) => Ack {
                                id: req.id,
                                status: "graces_rejected".into(),
                                amount: 0,
                                reason: reason.into(),
                            },
                        });
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
        let (loot_seq, loot_events) = loot_observation();
        Some(Snapshot {
            version: 1,
            pid: std::process::id(),
            session: self.session,
            save_load: self.save_load.sequence,
            seq: self.seq,
            timestamp_ms: epoch(),
            active: true,
            identity_ready: true,
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
            loot_seq,
            loot_events,
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

/// Identity alone remains observable during a grace menu's suspended update
/// tasks. Re-read the healthy offline player and its actual save data; the
/// retained character string alone never grants container ownership.
unsafe fn identity_observed(character: &str) -> bool {
    if character.is_empty() || unsafe { crate::grace_reset::sample() }.is_none() {
        return false;
    }
    let Ok(game) = (unsafe { GameMan::instance() }) else {
        return false;
    };
    let Ok(player) = (unsafe { PlayerIns::local_player() }) else {
        return false;
    };
    let Ok(data) = (unsafe { GameDataMan::instance() }) else {
        return false;
    };
    std::ptr::eq(
        player.player_game_data.as_ptr(),
        data.main_player_game_data.as_ref(),
    ) && character_identity(game.save_slot, &data.main_player_game_data) == character
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
