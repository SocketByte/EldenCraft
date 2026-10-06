//! Owned ER damage targets for genuine Minecraft mobs, using the pinned SDK.
//!
//! The debug spawn queue clones parameters from a currently loaded small
//! EnemyIns. No parameter rows, code offsets, global AI switches or existing
//! actors are modified. SDK movement/action gates suppress the clone's outputs;
//! they do not claim to stop its AI evaluator. Native hurt geometry and defensive
//! parameters remain those of the selected template, pending live validation.
//! Cleanup requests the engine's documented force_unloaded transition, never a
//! destructor, HP-zero death, or ChrSet::free_chr_list (which destroys others).

use crate::world_wire::Mob;
use eldenring::{
    cs::{
        CSSessionManager, ChrDebugSpawnRequest, ChrIns, ChrSetEntry, ChrType, EnemyIns,
        FieldInsHandle, GameMan, LobbyState, ProtocolState, WorldChrMan,
    },
    position::HavokPosition,
};
use fromsoftware_shared::{FromStatic, Superclass};
use std::collections::HashSet;

pub const MAX_PROXIES: usize = 8;
const FRESH_MS: u64 = 500;
const SPAWN_TIMEOUT_MS: u64 = 5000;
const MAX_DEBUG_CAPACITY: usize = 256;
const MAX_TEMPLATE_SCAN: usize = 256;
const MAX_TEMPLATE_ENTRY_SCAN: usize = 8192;
const TEMPLATE_DIAGNOSTIC_MS: u64 = 5000;
// Exact supported executable: ChrIns's state machine (3f8770, table3f8f44)
// moves entry+8 to4 only after3e8870 succeeds. That helper invokes virtual+50;
// EnemyIns4cf6f0 calls base3e8240 at4cfb0a, which sets activity bits3/4.
// State2 precedes this initialization; the pinned SDK's `Active=2` label is
// not this executable's completed character state. Keep raw enum storage raw.
const INITIALIZED_LOAD_STATUS: u8 = 4;
fn initialized(raw_load: u8, active: bool, tasks: bool) -> bool {
    raw_load == INITIALIZED_LOAD_STATUS && active && tasks
}
fn character_initialized(chr: &ChrIns) -> bool {
    initialized(
        load_status(unsafe { chr.chr_set_entry.as_ref() }),
        chr.chr_flags1c8.is_active(),
        chr.chr_flags1c8.update_tasks_registered(),
    )
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Context {
    pub epoch: u64,
    pub map: u32,
    pub guest_pid: u32,
    pub session: u64,
}
impl Context {
    fn valid(self) -> bool {
        self.epoch > 0 && self.guest_pid > 0 && self.session > 0
    }
}
#[derive(Clone, Debug)]
pub struct Hit {
    pub uuid: String,
    pub damage: f32,
    pub source: FieldInsHandle,
    pub time_ms: u64,
}
#[derive(Clone, Copy, Debug, Default)]
struct HealthSample {
    hp: i32,
    max_hp: i32,
    time: u64,
}
impl HealthSample {
    fn loss(self, hp: i32, max_hp: i32, now: u64) -> i32 {
        if self.time == 0
            || now < self.time
            || now - self.time > FRESH_MS
            || max_hp != self.max_hp
            || self.hp <= 0
            || hp < 0
            || hp >= self.hp
        {
            0
        } else {
            self.hp - hp
        }
    }
}
fn target_health(m: &Mob, scale: f32) -> Option<(i32, i32)> {
    if !m.hp.is_finite()
        || !m.max_hp.is_finite()
        || m.hp <= 0.
        || m.hp > m.max_hp
        || !(0.1..=1000.).contains(&scale)
        || !m.position.iter().all(|x| x.is_finite() && x.abs() < 1e6)
    {
        return None;
    }
    let max = (m.max_hp * scale).round();
    let hp = (m.hp * scale).round();
    if !max.is_finite() || !(1.0..=1_000_000.).contains(&max) {
        return None;
    }
    Some((hp.clamp(1., max) as i32, max as i32))
}
fn havok(m: &Mob, offset: [f64; 3]) -> Option<[f32; 3]> {
    let p = std::array::from_fn(|i| m.position[i] - offset[i]);
    p.iter()
        .all(|x: &f64| x.is_finite() && x.abs() < 1e6)
        .then(|| p.map(|x| x as f32))
}
fn mirror_after_loss(desired: i32, new_loss: i32) -> i32 {
    desired.saturating_sub(new_loss).max(0)
}
fn spawn_position_matches(actual: [f32; 3], requested: [f32; 3]) -> bool {
    actual.iter().chain(requested.iter()).all(|x| x.is_finite())
        && actual
            .into_iter()
            .zip(requested)
            .map(|(a, b)| (a - b).powi(2))
            .sum::<f32>()
            <= 16.
}
// These game-owned enum bytes may contain undocumented values. Never create a
// Rust enum value from their storage. repr(u8)/repr(i32) are pinned SDK contracts.
fn load_status(entry: &ChrSetEntry<ChrIns>) -> u8 {
    unsafe {
        std::ptr::addr_of!(entry.chr_load_status)
            .cast::<u8>()
            .read_unaligned()
    }
}
fn character_type(chr: &ChrIns) -> i32 {
    unsafe {
        std::ptr::addr_of!(chr.chr_type)
            .cast::<i32>()
            .read_unaligned()
    }
}

#[derive(Clone, Copy, Debug)]
struct Template {
    chr: i32,
    npc: i32,
    think: i32,
    init: i32,
}
#[derive(Clone, Copy, Debug)]
struct TemplateCandidate {
    character: u32,
    npc: i32,
    raw_type: i32,
    raw_load: u8,
    active: bool,
    tasks: bool,
    // Loading entries may not have initialized modules. Only sample modules
    // when both documented activity flags are present.
    height: Option<f32>,
    radius: Option<f32>,
    max_hp: Option<i32>,
}
impl std::fmt::Display for TemplateCandidate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "character={}, npc={}, rawType={}, rawLoad={}, active={}, tasks={}, height={:?}, radius={:?}, maxHP={:?}",
            self.character,
            self.npc,
            self.raw_type,
            self.raw_load,
            self.active,
            self.tasks,
            self.height,
            self.radius,
            self.max_hp
        )
    }
}
#[derive(Default, Debug)]
struct TemplateScan {
    selected: Option<Template>,
    sets: usize,
    excluded_sets: usize,
    invalid_sets: usize,
    slots: usize,
    occupied: usize,
    limited: bool,
    // Ordered rejection stages; each occupied entry increments at most one.
    identity: usize,
    status: usize,
    owned: usize,
    kind: usize,
    ids: usize,
    dead: usize,
    shape: usize,
    health: usize,
    rtti: usize,
    first: Option<TemplateCandidate>,
    first_npc: Option<TemplateCandidate>,
}
impl TemplateScan {
    fn message(&self) -> String {
        let first = self
            .first
            .map(|c| c.to_string())
            .unwrap_or_else(|| "none".into());
        let npc = self
            .first_npc
            .map(|c| c.to_string())
            .unwrap_or_else(|| "none".into());
        format!(
            "Mob proxy template scan: sets={}, excludedSets={}, invalidSets={}, slots={}, occupied={}, limited={}, rejected(identity/readiness/owned/type/ids/dead/shape/maxHP/rtti)={}/{}/{}/{}/{}/{}/{}/{}/{}, first=[{}], firstNpc=[{}]",
            self.sets,
            self.excluded_sets,
            self.invalid_sets,
            self.slots,
            self.occupied,
            self.limited,
            self.identity,
            self.status,
            self.owned,
            self.kind,
            self.ids,
            self.dead,
            self.shape,
            self.health,
            self.rtti,
            first,
            npc
        )
    }
}
fn small_template_shape(height: f32, radius: f32) -> bool {
    (1.0..=2.5).contains(&height) && (0.15..=0.6).contains(&radius)
}
fn template_diagnostic_due(last: u64, now: u64) -> bool {
    last == 0 || now < last || now - last >= TEMPLATE_DIAGNOSTIC_MS
}
#[derive(Clone, Copy, Debug)]
struct Ownership {
    handle: FieldInsHandle,
    instance: usize,
    entry: usize,
    world: usize,
}
#[derive(Debug)]
struct Actor {
    uuid: String,
    owner: Ownership,
    baseline: HealthSample,
    retiring: bool,
}
struct Pending {
    uuid: String,
    template: Template,
    world: usize,
    before: HashSet<FieldInsHandle>,
    requested: u64,
    cancelled: bool,
    at: [f32; 3],
}

pub struct Driver {
    context: Option<Context>,
    actors: Vec<Actor>,
    pending: Option<Pending>,
    scale: f32,
    last_spawn: u64,
    last_template_diagnostic: u64,
    blocked: Option<&'static str>,
    last_diagnostic: Option<&'static str>,
    dead_pending: HashSet<String>,
    pub spawn_requests: u64,
    pub observed_hits: u64,
    pub dropped_diagnostics: u64,
    pub events: Vec<String>,
}
impl Driver {
    pub fn new(scale: f32) -> Self {
        Self {
            context: None,
            actors: Vec::new(),
            pending: None,
            scale: if scale.is_finite() && (0.1..=1000.).contains(&scale) {
                scale
            } else {
                50.
            },
            last_spawn: 0,
            last_template_diagnostic: 0,
            blocked: None,
            last_diagnostic: None,
            dead_pending: HashSet::new(),
            spawn_requests: 0,
            observed_hits: 0,
            dropped_diagnostics: 0,
            events: Vec::new(),
        }
    }
    pub fn active_count(&self) -> usize {
        self.actors.iter().filter(|a| !a.retiring).count()
    }
    pub fn pending_count(&self) -> usize {
        usize::from(self.pending.is_some())
    }
    pub fn blocked_reason(&self) -> Option<&'static str> {
        self.blocked
    }
    pub fn retiring_count(&self) -> usize {
        self.actors.iter().filter(|a| a.retiring).count()
    }
    pub fn dead_pending_count(&self) -> usize {
        self.dead_pending.len()
    }
    pub fn owns(&self, handle: FieldInsHandle) -> bool {
        self.actors.iter().any(|a| a.owner.handle == handle)
    }
    /// Only call in the parent's exact-build, offline PostPhysics task. Inputs
    /// must be the currently acknowledged guest world snapshot, not cached mobs.
    /// Parent must subtract its unacknowledged native incoming-damage ledger
    /// from each Mob.hp before calling. This method additionally subtracts loss
    /// first observed in this tick, so an old guest frame cannot heal the clone.
    pub unsafe fn tick(
        &mut self,
        context: Context,
        mobs: &[Mob],
        offset: [f64; 3],
        now: u64,
        received_ms: u64,
    ) -> Result<Vec<Hit>, &'static str> {
        if !context.valid()
            || received_ms > now
            || now - received_ms > FRESH_MS
            || mobs.len() > 64
            || !offset.iter().all(|v| v.is_finite() && v.abs() < 1e6)
        {
            unsafe { self.suspend(now) };
            return Err("mob proxy snapshot stale or invalid");
        }
        let game = unsafe { GameMan::instance() }.map_err(|_| "mob proxy game unavailable")?;
        let session =
            unsafe { CSSessionManager::instance() }.map_err(|_| "mob proxy session unavailable")?;
        if game.is_in_online_mode
            || game.warp_requested
            || session.lobby_state != LobbyState::None
            || session.protocol_state != ProtocolState::None
        {
            unsafe { self.suspend(now) };
            return Err("mob proxy offline gate closed");
        }
        if self.context != Some(context) {
            unsafe { self.suspend(now) };
            self.context = Some(context);
            self.dead_pending.clear();
        }
        // Only authoritative absence clears a tombstone in this context; a
        // repeated stale-but-fresh living snapshot cannot respawn a lethal hit.
        self.dead_pending
            .retain(|uuid| mobs.iter().any(|m| m.uuid == *uuid));
        let world =
            unsafe { WorldChrMan::instance_mut() }.map_err(|_| "mob proxy world unavailable")?;
        let team = world
            .main_player
            .as_ref()
            .ok_or("mob proxy local player unavailable")?
            .chr_ins
            .team_type;
        self.maintain(world, now)?;
        let mut hits = Vec::new();
        for actor in &mut self.actors {
            if actor.retiring {
                continue;
            }
            let wanted = mobs.iter().find(|m| m.uuid == actor.uuid);
            let Some((m, (hp, max_hp), position)) =
                wanted.and_then(|m| Some((m, target_health(m, self.scale)?, havok(m, offset)?)))
            else {
                retire(world, actor);
                continue;
            };
            let Some(chr) = owned(world, actor.owner) else {
                actor.retiring = true;
                continue;
            };
            let actual = chr.modules.data.hp;
            let actual_max = chr.modules.data.max_hp;
            let loss = actor.baseline.loss(actual, actual_max, now);
            if loss > 0 {
                self.observed_hits += 1;
                hits.push(Hit {
                    uuid: m.uuid.clone(),
                    damage: loss as f32 / self.scale,
                    source: chr.last_hit_by,
                    time_ms: now,
                });
            }
            let hp = mirror_after_loss(hp, loss);
            if hp == 0 || actual <= 0 || chr.chr_flags1c5.death_flag() {
                self.dead_pending.insert(m.uuid.clone());
                retire(world, actor);
                continue;
            }
            prepare(chr, team, position, hp, max_hp);
            actor.baseline = HealthSample {
                hp,
                max_hp,
                time: now,
            };
        }
        let desired = mobs.iter().find(|m| {
            !self.actors.iter().any(|a| a.uuid == m.uuid)
                && !self.dead_pending.contains(&m.uuid)
                && target_health(m, self.scale).is_some()
                && havok(m, offset).is_some()
        });
        if desired.is_some()
            && self.pending.is_none()
            && self.blocked.is_none()
            && self.actors.len() < MAX_PROXIES
            && (self.last_spawn == 0 || now.saturating_sub(self.last_spawn) >= 1000)
            && !world.debug_chr_creator.spawn
        {
            self.last_spawn = now; // Bound unsuccessful template scans too.
            if let Some(m) = desired {
                let scan = template(world, &self.actors);
                if template_diagnostic_due(self.last_template_diagnostic, now) {
                    self.last_template_diagnostic = now;
                    self.notice(scan.message());
                }
                if let Some(template) = scan.selected {
                    let before = debug_characters(world)?
                        .into_iter()
                        .map(|c| c.field_ins_handle)
                        .collect();
                    let at = havok(m, offset).ok_or("mob proxy position invalid")?;
                    self.pending = Some(Pending {
                        uuid: m.uuid.clone(),
                        template,
                        world: world as *const _ as usize,
                        before,
                        requested: now,
                        cancelled: false,
                        at,
                    });
                    self.spawn_requests += 1;
                    // -1 is the engine's no-event/no-talk sentinel. Never allocate
                    // a user event identity or borrow an existing actor's scripts.
                    world.spawn_debug_character(&ChrDebugSpawnRequest {
                        chr_id: template.chr,
                        chara_init_param_id: template.init,
                        npc_param_id: template.npc,
                        npc_think_param_id: template.think,
                        event_entity_id: -1,
                        talk_id: -1,
                        pos_x: at[0],
                        pos_y: at[1],
                        pos_z: at[2],
                    });
                    self.notice(format!(
                        "Mob proxy requested: uuid={}, character={}, npc={}, think={}",
                        m.uuid, template.chr, template.npc, template.think
                    ));
                    self.diagnostic("native spawn queued");
                } else {
                    self.diagnostic("no active small EnemyIns template; no actor spawned");
                }
            }
        } else if let Some(reason) = self.blocked {
            self.diagnostic(reason);
        } else if desired.is_some() && self.actors.len() >= MAX_PROXIES {
            self.diagnostic("capacity reached (8 including retiring/quarantined actors)");
        } else if desired.is_some() && world.debug_chr_creator.spawn {
            self.diagnostic("native debug spawn queue busy");
        } else if self.pending.is_some() {
            self.diagnostic("awaiting native spawn readiness");
        } else if desired.is_none() {
            self.diagnostic(
                "all requested living mobs represented or awaiting death acknowledgement",
            );
        }
        Ok(hits)
    }
    /// Request cleanup immediately on guest loss, focus loss, map changes or
    /// shutdown. Continue calling while suspended so a delayed spawn is retired.
    pub unsafe fn suspend(&mut self, now: u64) {
        // Keep the last identity and its lethal-hit tombstones through focus
        // loss. Only a genuinely new context or authoritative absence clears them.
        if let Some(p) = &mut self.pending {
            p.cancelled = true;
        }
        // Cleanup follows the same offline boundary as creation. Preserve owned
        // handles/pending cancellation when singletons are unavailable or an
        // actual network session exists; never traverse/write online actors.
        let Ok(game) = (unsafe { GameMan::instance() }) else {
            return;
        };
        let Ok(session) = (unsafe { CSSessionManager::instance() }) else {
            return;
        };
        if game.is_in_online_mode
            || session.lobby_state != LobbyState::None
            || session.protocol_state != ProtocolState::None
        {
            self.diagnostic("native mob cleanup deferred until offline session");
            return;
        }
        if let Ok(world) = unsafe { WorldChrMan::instance_mut() } {
            let _ = self.maintain(world, now);
            for actor in &mut self.actors {
                retire(world, actor);
            }
        }
    }
    fn maintain(&mut self, world: &mut WorldChrMan, now: u64) -> Result<(), &'static str> {
        for actor in &mut self.actors {
            if actor.retiring {
                retire(world, actor);
            }
        }
        let current_world = world as *const _ as usize;
        // A pointer change does not prove destruction. Keep bounded quarantine
        // records, never follow the old pointer or recycle their capacity.
        self.actors
            .retain(|a| a.owner.world != current_world || owned(world, a.owner).is_some());
        if self.actors.iter().any(|a| a.owner.world != current_world) {
            self.blocked = Some("owned actor manager changed; records quarantined");
        }
        let Some(p) = self.pending.as_ref() else {
            return Ok(());
        };
        if p.world != current_world {
            self.pending.as_mut().unwrap().cancelled = true;
            self.blocked = Some("pending spawn manager changed; request quarantined");
            return Ok(());
        }
        let matches: Vec<Ownership> = debug_characters(world)?
            .into_iter()
            .filter(|c| {
                !p.before.contains(&c.field_ins_handle)
                    && c.character_id == p.template.chr as u32
                    && c.npc_param_id == p.template.npc
                    && character_initialized(c)
                    && world
                        .debug_chr_creator
                        .last_created_chr
                        .is_some_and(|q| std::ptr::eq(q.as_ptr(), *c))
            })
            .map(|c| Ownership {
                handle: c.field_ins_handle,
                instance: c as *const _ as usize,
                entry: c.chr_set_entry.as_ptr() as usize,
                world: p.world,
            })
            .collect();
        if matches.len() > 1 {
            self.blocked = Some("ambiguous native mob spawn");
            return Err("ambiguous native mob spawn");
        }
        if let Some(owner) = matches.first().copied() {
            let p = self.pending.take().unwrap();
            let mut actor = Actor {
                uuid: p.uuid.clone(),
                owner,
                baseline: HealthSample::default(),
                retiring: p.cancelled,
            };
            if p.cancelled {
                retire(world, &mut actor);
            } else if let Some(chr) = owned(world, owner) {
                // Hide and suppress autonomous actions immediately at adoption;
                // health/pose readiness is completed in the same tick below.
                chr.chr_flags1c5.set_enable_render(false);
                chr.debug_flags.set_disabled_movement(true);
                chr.debug_flags.set_disabled_secondary_actions(true);
                chr.chr_flags1c6.set_had_dropped_item(true);
                chr.chr_flags1c6.set_has_dropped_runes(true);
                let position = chr.modules.physics.position;
                if !spawn_position_matches([position.0, position.1, position.2], p.at) {
                    actor.retiring = true;
                    self.blocked = Some("native spawn position disagrees with owned request");
                }
            }
            if actor.retiring {
                retire(world, &mut actor);
            }
            self.actors.push(actor);
            self.notice(format!(
                "Mob proxy bound: uuid={}, handle={}",
                p.uuid, owner.handle
            ));
        } else if now.saturating_sub(p.requested) > SPAWN_TIMEOUT_MS {
            // Do not overwrite a still-running native request or forget a clone
            // that may complete later. Bind it only for retirement if it arrives.
            self.pending.as_mut().unwrap().cancelled = true;
            self.blocked = Some("native mob spawn timed out; awaiting cleanup");
        }
        Ok(())
    }
    fn notice(&mut self, message: String) {
        if self.events.len() < 32 {
            self.events.push(message);
        } else {
            self.dropped_diagnostics += 1;
        }
    }
    fn diagnostic(&mut self, reason: &'static str) {
        if self.last_diagnostic != Some(reason) {
            self.last_diagnostic = Some(reason);
            self.notice(format!("Mob proxy status: {reason}; active={}, retiring={}, pending={}, deadPending={}, requests={}, nativeHits={}",
            self.active_count(),self.retiring_count(),self.pending_count(),self.dead_pending_count(),self.spawn_requests,self.observed_hits));
        }
    }
}

fn debug_characters(world: &WorldChrMan) -> Result<Vec<&ChrIns>, &'static str> {
    let set = &world.debug_chr_set;
    let count = set.capacity as usize;
    if count > MAX_DEBUG_CAPACITY {
        return Err("native debug character capacity invalid");
    }
    let mut found = Vec::new();
    for i in 0..count {
        let entry =
            unsafe { set.entries.as_ptr().add(i).as_ref() }.ok_or("debug entry unavailable")?;
        if let Some(ptr) = entry.chr_ins {
            let chr = unsafe { ptr.as_ref() };
            if !std::ptr::eq(chr.chr_set_entry.as_ptr(), entry) {
                continue;
            }
            if world
                .chr_ins_by_handle(&chr.field_ins_handle)
                .is_some_and(|c| std::ptr::eq(c, chr))
            {
                found.push(chr);
            }
        }
    }
    Ok(found)
}
fn owned(world: &mut WorldChrMan, o: Ownership) -> Option<&mut ChrIns> {
    if o.world != world as *const _ as usize {
        return None;
    }
    let chr = world.chr_ins_by_handle_mut(&o.handle)?;
    if chr as *const _ as usize != o.instance || chr.chr_set_entry.as_ptr() as usize != o.entry {
        return None;
    }
    let entry = unsafe { chr.chr_set_entry.as_ref() };
    entry
        .chr_ins
        .is_some_and(|p| std::ptr::eq(p.as_ptr(), chr))
        .then_some(chr)
}
fn retire(world: &mut WorldChrMan, actor: &mut Actor) {
    actor.retiring = true;
    actor.baseline = HealthSample::default();
    let Some(chr) = owned(world, actor.owner) else {
        return;
    };
    chr.chr_flags1c5.set_enable_render(false);
    chr.debug_flags.set_disabled_hit(true);
    chr.debug_flags.set_disabled_movement(true);
    chr.debug_flags.set_disabled_secondary_actions(true);
    chr.debug_flags.set_force_loaded(false);
    chr.debug_flags.set_force_unloaded(true);
    chr.chr_flags1c6.set_had_dropped_item(true);
    chr.chr_flags1c6.set_has_dropped_runes(true);
    // Keep normal engine update/cleanup tasks running; no disabled_updates or
    // character_disabled write, raw entry mutation, death flag or allocator call.
}
fn prepare(chr: &mut ChrIns, team: u8, p: [f32; 3], hp: i32, max_hp: i32) {
    chr.team_type = team;
    chr.chr_flags1c5.set_enable_render(false);
    chr.debug_flags.set_disabled_movement(true);
    chr.debug_flags.set_disabled_secondary_actions(true);
    chr.chr_flags1c4.set_no_gravity(true);
    chr.chr_flags1c6.set_had_dropped_item(true);
    chr.chr_flags1c6.set_has_dropped_runes(true);
    let physics = chr.modules.physics.as_mut();
    physics.position = HavokPosition::from_xyz(p[0], p[1], p[2]);
    physics.last_update_position = physics.position;
    // Pinned SDK explicitly documents this as physics-module -> underlying
    // Havok-character position synchronization. Do not guess the physics bool.
    chr.chr_ctrl
        .chr_proxy_flags
        .set_position_sync_requested(true);
    let data = chr.modules.data.as_mut();
    data.base_hp = max_hp;
    data.max_uncapped_hp = max_hp;
    data.max_hp = max_hp;
    data.hp = hp;
}
fn template(world: &WorldChrMan, actors: &[Actor]) -> TemplateScan {
    // Iterate the bounded map-owned sets, excluding every debug actor, player,
    // summon and ghost. RTTI verifies EnemyIns before its think field is read.
    let mut scan = TemplateScan::default();
    let Some(player) = world.main_player.as_ref() else {
        return scan;
    };
    let player_team = player.chr_ins.team_type;
    for holder in &world.chr_sets {
        let Some(set) = holder.as_ref() else {
            continue;
        };
        scan.sets += 1;
        if std::ptr::eq(set.as_ref(), &world.debug_chr_set)
            || std::ptr::eq(set.as_ref(), &world.summon_buddy_chr_set)
            || std::ptr::eq(set.as_ref(), &world.ghost_chr_set)
        {
            scan.excluded_sets += 1;
            continue;
        }
        if set.capacity as usize > 4096 {
            scan.invalid_sets += 1;
            continue;
        }
        for i in 0..set.capacity as usize {
            if scan.occupied >= MAX_TEMPLATE_SCAN || scan.slots >= MAX_TEMPLATE_ENTRY_SCAN {
                scan.limited = true;
                return scan;
            }
            scan.slots += 1;
            let Some(entry) = (unsafe { set.entries.as_ptr().add(i).as_ref() }) else {
                scan.invalid_sets += 1;
                break;
            };
            let Some(ptr) = entry.chr_ins else {
                continue;
            };
            scan.occupied += 1;
            let chr = unsafe { ptr.as_ref() };
            if !std::ptr::eq(chr.chr_set_entry.as_ptr(), entry)
                || !world
                    .chr_ins_by_handle(&chr.field_ins_handle)
                    .is_some_and(|c| std::ptr::eq(c, chr))
            {
                scan.identity += 1;
                continue;
            }
            let status = load_status(entry);
            let kind = character_type(chr);
            let active = chr.chr_flags1c8.is_active();
            let tasks = chr.chr_flags1c8.update_tasks_registered();
            let can_sample = active && tasks;
            let candidate = TemplateCandidate {
                character: chr.character_id,
                npc: chr.npc_param_id,
                raw_type: kind,
                raw_load: status,
                active,
                tasks,
                height: can_sample.then(|| chr.modules.physics.chr_hit_height),
                radius: can_sample.then(|| chr.modules.physics.chr_hit_radius),
                max_hp: can_sample.then(|| chr.modules.data.max_hp),
            };
            scan.first.get_or_insert(candidate);
            if kind == ChrType::Npc as i32 {
                scan.first_npc.get_or_insert(candidate);
            }
            if !initialized(status, active, tasks) {
                scan.status += 1;
                continue;
            }
            if actors
                .iter()
                .any(|a| a.owner.handle == chr.field_ins_handle)
            {
                scan.owned += 1;
                continue;
            }
            if kind != ChrType::Npc as i32 {
                scan.kind += 1;
                continue;
            }
            if chr.character_id == 0 || chr.character_id > 9999 || chr.npc_param_id < 0 {
                scan.ids += 1;
                continue;
            }
            if chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag() {
                scan.dead += 1;
                continue;
            }
            if crate::combat_targets::target_rejection(chr, player_team).is_some() {
                scan.kind += 1;
                continue;
            }
            let ph = &chr.modules.physics;
            if !small_template_shape(ph.chr_hit_height, ph.chr_hit_radius) {
                scan.shape += 1;
                continue;
            }
            if !(1..=3000).contains(&chr.modules.data.max_hp) {
                scan.health += 1;
                continue;
            }
            let Some(enemy) = chr.as_subclass::<EnemyIns>() else {
                scan.rtti += 1;
                continue;
            };
            scan.selected = Some(Template {
                chr: chr.character_id as i32,
                npc: chr.npc_param_id,
                think: enemy.npc_think_param,
                init: chr.modules.data.chara_init_param_id,
            });
            return scan;
        }
    }
    scan
}

#[cfg(test)]
mod tests {
    use super::*;
    fn mob() -> Mob {
        Mob {
            uuid: "test-mob".into(),
            kind: "minecraft:creeper".into(),
            hp: 20.,
            max_hp: 20.,
            position: [1., 2., 3.],
            ..Mob::default()
        }
    }
    #[test]
    fn native_loss_is_positive_fresh_and_same_maximum_only() {
        let s = HealthSample {
            hp: 1000,
            max_hp: 1000,
            time: 1000,
        };
        assert_eq!(s.loss(750, 1000, 1050), 250);
        assert_eq!(s.loss(1000, 1000, 1050), 0);
        assert_eq!(s.loss(1100, 1000, 1050), 0);
        assert_eq!(s.loss(-1, 1000, 1050), 0);
        assert_eq!(s.loss(0, 1000, 1050), 1000);
        assert_eq!(s.loss(750, 1200, 1050), 0);
        assert_eq!(s.loss(750, 1000, 999), 0);
        assert_eq!(s.loss(750, 1000, 1501), 0);
        assert_eq!(HealthSample::default().loss(0, 1000, 1000), 0);
    }
    #[test]
    fn health_scaling_never_accepts_dead_invalid_or_unbounded_mobs() {
        let mut m = mob();
        assert_eq!(target_health(&m, 50.), Some((1000, 1000)));
        for hp in [0., -1., 21., f32::NAN, f32::INFINITY] {
            m.hp = hp;
            assert!(target_health(&m, 50.).is_none());
        }
        m = mob();
        for scale in [0., -1., 1001., f32::NAN, f32::INFINITY] {
            assert!(target_health(&m, scale).is_none());
        }
        m.max_hp = 10001.;
        assert!(target_health(&m, 1000.).is_none());
    }
    #[test]
    fn canonical_to_havok_keeps_offsets_out_of_persisted_mob_positions() {
        let m = mob();
        assert_eq!(havok(&m, [100., 200., 300.]), Some([-99., -198., -297.]));
        assert!(havok(&m, [f64::NAN, 0., 0.]).is_none());
        assert_eq!(m.position, [1., 2., 3.]);
    }
    #[test]
    fn lifecycle_identity_rejects_zero_epochs_processes_and_sessions() {
        let c = Context {
            epoch: 1,
            map: 0,
            guest_pid: 2,
            session: 3,
        };
        assert!(c.valid());
        assert!(!Context { epoch: 0, ..c }.valid());
        assert!(!Context { guest_pid: 0, ..c }.valid());
        assert!(!Context { session: 0, ..c }.valid());
        assert_ne!(c, Context { session: 4, ..c });
        assert_ne!(c, Context { map: 1, ..c });
    }
    #[test]
    fn just_observed_damage_is_not_healed_by_the_same_guest_snapshot() {
        assert_eq!(mirror_after_loss(1000, 250), 750);
        assert_eq!(mirror_after_loss(200, 250), 0);
        assert_eq!(mirror_after_loss(750, 0), 750);
        let after = HealthSample {
            hp: 750,
            max_hp: 1000,
            time: 1000,
        };
        assert_eq!(after.loss(750, 1000, 1050), 0);
        assert_eq!(after.loss(650, 1000, 1050), 100);
    }
    #[test]
    fn adoption_rejects_nonfinite_or_different_coordinate_frames() {
        assert!(spawn_position_matches([100., 10., -20.], [100., 10., -20.]));
        assert!(!spawn_position_matches([100., 10., -20.], [0., 10., -20.]));
        assert!(!spawn_position_matches(
            [f32::NAN, 10., -20.],
            [100., 10., -20.]
        ));
    }
    #[test]
    fn template_shape_rejects_large_small_and_nonfinite_geometry() {
        assert!(small_template_shape(1., 0.15));
        assert!(small_template_shape(2.5, 0.6));
        for (height, radius) in [
            (0.99, 0.3),
            (2.51, 0.3),
            (1.8, 0.14),
            (1.8, 0.61),
            (f32::NAN, 0.3),
            (1.8, f32::INFINITY),
        ] {
            assert!(!small_template_shape(height, radius));
        }
    }
    #[test]
    fn template_diagnostics_are_periodic_and_handle_clock_reset() {
        assert!(template_diagnostic_due(0, 100));
        assert!(!template_diagnostic_due(100, 5099));
        assert!(template_diagnostic_due(100, 5100));
        assert!(template_diagnostic_due(100, 99));
    }
    #[test]
    fn template_diagnostics_preserve_unknown_numeric_values_without_enum_conversion() {
        let sample = TemplateCandidate {
            character: 1234,
            npc: 2345,
            raw_type: 271,
            raw_load: 255,
            active: false,
            tasks: false,
            height: None,
            radius: None,
            max_hp: None,
        };
        let scan = TemplateScan {
            status: 3,
            first: Some(sample),
            ..TemplateScan::default()
        };
        let text = scan.message();
        assert!(text.contains("rawType=271, rawLoad=255"));
        assert!(text.contains("height=None, radius=None, maxHP=None"));
        assert!(text.contains("firstNpc=[none]"));
    }
    #[test]
    fn initialized_native_state_requires_completed_transition_and_both_activity_bits() {
        for status in 0..=u8::MAX {
            assert_eq!(initialized(status, true, true), status == 4);
            assert!(!initialized(status, false, true));
            assert!(!initialized(status, true, false));
            assert!(!initialized(status, false, false));
        }
        // The SDK's Active=2 is before virtual initialization in this build;
        // unknown states must never be promoted merely because flags survived.
        assert!(!initialized(2, true, true));
        assert!(!initialized(255, true, true));
    }
}
