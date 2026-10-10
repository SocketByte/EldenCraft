//! Shared scene coordinator. Physics owns native actors; MC owns its world.
use crate::{
    combat_targets as targets, mob_proxy, native_damage, world_incoming, world_native,
    world_transport as transport, world_wire as wire,
};
use crate::{
    native_teleport, projectile_flight as flight,
    projectile_path::{CheckError, Path, RayBudget},
    worldterrain,
};
use eldenring::{
    cs::{
        BlockId, CSHavokMan, CSSessionManager, FieldInsHandle, FieldInsSelector, GameMan,
        LobbyState, PlayerIns, ProtocolState,
    },
    position::{HavokPosition, PositionDelta},
};
use fromsoftware_shared::FromStatic;
use std::collections::{HashMap, VecDeque};
const MAX_HISTORY: usize = 32;
const WORLD_RADIUS: f64 = 64.;
// Opaque wire identity: the native local-player handle has block=-1 and does
// not fit Java's positive signed long. Never serialize that native handle.
const PLAYER_ID: u64 = 1;

fn packed(h: targets::Handle) -> u64 {
    u64::from(h.selector) | (u64::from(h.block_id as u32) << 32)
}
fn handle(value: u64) -> FieldInsHandle {
    FieldInsHandle {
        selector: FieldInsSelector(value as u32),
        block_id: BlockId((value >> 32) as i32),
    }
}
fn distance_to_box(p: [f64; 3], min: [f64; 3], max: [f64; 3]) -> f64 {
    (0..3)
        .map(|i| (p[i] - p[i].clamp(min[i], max[i])).powi(2))
        .sum::<f64>()
        .sqrt()
}
fn validate_environment(
    e: &wire::Event,
    historical: &[wire::Target],
    current: &[wire::Target],
    player: u64,
    epoch: u64,
    feet: [f64; 3],
    owner: bool,
) -> Result<(), &'static str> {
    if !owner {
        return Err("world environment damage session owner mismatch");
    }
    if e.target == player {
        if e.generation != epoch || distance(e.position, feet) > 3. {
            return Err("world environment player identity or position mismatch");
        }
    } else {
        let before = historical
            .iter()
            .find(|t| t.id == e.target && t.generation == e.generation)
            .ok_or("world historical environment target missing")?;
        let target = current
            .iter()
            .find(|t| t.id == e.target && t.generation == e.generation && t.hp > 0)
            .ok_or("world current environment target missing")?;
        if distance(before.min, target.min) > 16.
            || distance_to_box(e.position, target.min, target.max) > 3.
        {
            return Err("world environment damage away from target");
        }
    }
    Ok(())
}
fn distance(a: [f64; 3], b: [f64; 3]) -> f64 {
    (0..3).map(|i| (a[i] - b[i]).powi(2)).sum::<f64>().sqrt()
}
#[derive(Clone)]
struct History {
    frame: u64,
    time: u64,
    revision: u64,
    targets: Vec<wire::Target>,
}
#[derive(Clone)]
struct LaunchHistory {
    frame: u64,
    time: u64,
    feet: [f64; 3],
}
struct LandingScan {
    peer: (u32, u64),
    seq: u64,
    epoch: u64,
    map: u32,
    offset: [f64; 3],
    destination: [f64; 3],
    event: wire::Event,
    observed: History,
    cache: worldterrain::Cache,
}
impl LandingScan {
    fn matches(
        &self,
        event: &wire::Event,
        peer: Option<(u32, u64)>,
        epoch: u64,
        map: u32,
        offset: [f64; 3],
    ) -> bool {
        peer == Some(self.peer)
            && self.epoch == epoch
            && self.map == map
            && self.offset == offset
            && self.event == *event
    }
}
#[derive(Clone)]
struct CachedMobs {
    context: mob_proxy::Context,
    produced_ms: u64,
    mobs: Vec<wire::Mob>,
}
impl CachedMobs {
    fn fresh(&self, now: u64, epoch: u64, map: u32, peer: Option<(u32, u64)>) -> bool {
        self.context.epoch == epoch
            && self.context.map == map
            && peer == Some((self.context.guest_pid, self.context.session))
            && now
                .checked_sub(self.produced_ms)
                .is_some_and(|age| age <= 500)
    }
}
#[derive(Default)]
struct Ledger {
    identity: Option<(u32, u64)>,
    ack: u64,
    results: VecDeque<wire::Ack>,
    seen: HashMap<(String, u64, u64), u64>,
    retired: VecDeque<((u32, u64), u64)>,
}
impl Ledger {
    fn observe(&mut self, pid: u32, session: u64, now: u64) -> bool {
        self.retired
            .retain(|(_, time)| now.saturating_sub(*time) < 2000);
        let next = (pid, session);
        if self.identity == Some(next) {
            return true;
        }
        if self.retired.iter().any(|(id, _)| *id == next) {
            return false;
        }
        if let Some(old) = self.identity.replace(next) {
            self.retired.push_back((old, now));
        }
        while self.retired.len() > 64 {
            self.retired.pop_front();
        }
        self.ack = 0;
        self.results.clear();
        self.seen.clear();
        true
    }
    fn consume(&mut self, e: &wire::Event, now: u64) -> Result<(), &'static str> {
        if e.seq <= self.ack {
            return Err("world event sequence already consumed");
        }
        self.ack = e.seq;
        self.seen
            .retain(|_, time| now.saturating_sub(*time) <= 2000);
        let key = (e.source.clone(), e.event, e.target);
        if self.seen.insert(key, now).is_some() {
            return Err("world source event target duplicate");
        }
        Ok(())
    }
    fn outcome(&mut self, seq: u64, result: Result<i32, &'static str>) {
        self.results.push_back(match result {
            Ok(delta) => wire::Ack {
                seq,
                result: 1,
                delta,
                reason: String::new(),
            },
            Err(reason) => wire::Ack {
                seq,
                result: 2,
                delta: 0,
                reason: reason.into(),
            },
        });
        while self.results.len() > 128 {
            self.results.pop_front();
        }
    }
}
pub struct Driver {
    native: world_native::Driver,
    publisher: Option<transport::Publisher>,
    reader: transport::Reader,
    sink: Result<native_damage::Sink, &'static str>,
    epoch: u64,
    generation: u64,
    instances: HashMap<u64, (usize, u64)>,
    history: VecDeque<History>,
    ledger: Ledger,
    mobs: HashMap<String, (wire::Mob, u64)>,
    player_source: Option<(u32, u64, String)>,
    last_tick: u64,
    last_error: Option<&'static str>,
    scale: f32,
    active: bool,
    pub events: VecDeque<String>,
    incoming: world_incoming::Ledger,
    mob_proxies: Option<mob_proxy::Driver>,
    mob_error: Option<&'static str>,
    cached_mobs: Option<CachedMobs>,
    teleporter: Result<native_teleport::Sink, &'static str>,
    landing: Option<LandingScan>,
    relocated: Option<native_teleport::Receipt>,
    launch_history: VecDeque<LaunchHistory>,
    relocation_started: bool,
    flights: flight::Driver,
    flight_rays: Result<crate::native_colliders::Api, &'static str>,
    impacts: Vec<flight::Impact>,
    impacts_ms: u64,
    flight_context: Option<flight::Context>,
    player_flight: crate::player_flight::Latest,
    player_torrent: crate::torrent::Latest,
    player_combat: crate::combat_effects::Latest,
    fluids: Vec<crate::world_fluids::Owned>,
    view: Option<(wire::ViewSettings, u64)>,
}
impl Driver {
    pub fn new() -> Self {
        let scale = crate::campaign_runtime::damage_scale().unwrap_or_else(|| {
            std::env::var("ELDENCRAFT_DAMAGE_SCALE")
                .ok()
                .and_then(|s| s.parse().ok())
                .filter(|v: &f32| v.is_finite() && (0.1..=1000.).contains(v))
                .unwrap_or(50.)
        });
        Self {
            native: world_native::Driver::new(),
            publisher: transport::Publisher::open().ok(),
            reader: transport::Reader::default(),
            sink: native_damage::Sink::resolve(),
            epoch: 0,
            generation: 0,
            instances: HashMap::new(),
            history: VecDeque::new(),
            ledger: Ledger::default(),
            mobs: HashMap::new(),
            player_source: None,
            last_tick: 0,
            last_error: None,
            scale,
            active: false,
            events: VecDeque::new(),
            incoming: world_incoming::Ledger::new(),
            mob_proxies: (std::env::var("ELDENCRAFT_NATIVE_MOBS").ok().as_deref() == Some("1"))
                .then(|| mob_proxy::Driver::new(scale)),
            mob_error: None,
            cached_mobs: None,
            teleporter: native_teleport::Sink::resolve(),
            landing: None,
            relocated: None,
            relocation_started: false,
            launch_history: VecDeque::new(),
            flights: flight::Driver::new(),
            flight_rays: crate::native_colliders::Api::resolve(),
            impacts: Vec::new(),
            impacts_ms: 0,
            flight_context: None,
            player_flight: crate::player_flight::Latest::default(),
            player_torrent: crate::torrent::Latest::default(),
            player_combat: crate::combat_effects::Latest::default(),
            fluids: Vec::new(),
            view: None,
        }
    }
    pub fn player_combat(&self, now: u64) -> Option<crate::combat_effects::Sample> {
        self.player_combat.get(now)
    }
    pub fn player_flight(&self, now: u64) -> Option<crate::player_flight::Sample> {
        self.player_flight.get(now)
    }
    pub fn fluids(&self, now: u64) -> Vec<crate::world_fluids::Owned> {
        self.fluids
            .iter()
            .copied()
            .filter(|c| c.contact.fresh(now))
            .collect()
    }
    /// A fresh mount published for the current pid/session/epoch/map only.
    pub fn player_torrent(&self, now: u64) -> Option<crate::torrent::Sample> {
        self.player_torrent.get(now)
    }
    /// Installed placed-block colliders near the Havok feet, for step-up.
    pub fn step_boxes(&self, feet_havok: [f64; 3]) -> Option<Vec<crate::step_assist::Box6>> {
        if !self.active {
            return None;
        }
        self.native.step_boxes(feet_havok, transport::now())
    }
    /// The guest's mouse/bobbing options from a matching publication of the last 2 s.
    pub fn view_settings(&self, now: u64) -> Option<wire::ViewSettings> {
        self.view
            .filter(|(_, at)| now >= *at && now - at <= 2000)
            .map(|(v, _)| v)
    }
    /// The next tick publishes immediately instead of waiting out the 50ms cadence,
    /// so the guest sees the new source block together with the new ECHS pose.
    pub fn publish_now(&mut self) {
        self.last_tick = 0;
    }
    /// Also revokes movement after a native call whose readback was rejected.
    /// This is a lifecycle notification, not an acceptance/damage receipt.
    pub fn take_relocated(&mut self) -> Option<()> {
        self.relocated = None;
        std::mem::take(&mut self.relocation_started).then_some(())
    }
    pub fn suspend(&mut self) {
        self.native.suspend();
        crate::scene_camera::suspend();
        if let Some(proxies) = self.mob_proxies.as_mut() {
            unsafe {
                proxies.suspend(transport::now());
            }
        }
        if self.active
            && let Some(p) = self.publisher.as_mut()
        {
            let _ = p.publish(false, &serde_json::json!({"epoch":self.epoch}));
        }
        self.active = false;
        self.last_tick = 0;
        self.history.clear();
        self.mobs.clear();
        self.player_source = None;
        self.cached_mobs = None;
        self.landing = None;
        self.launch_history.clear();
        self.flights.reset();
        self.impacts.clear();
        self.impacts_ms = 0;
        self.flight_context = None;
        self.player_flight.reset();
        self.player_torrent.reset();
        self.player_combat.reset();
        self.fluids.clear();
    }
    fn fail(&mut self, error: &'static str) {
        self.suspend();
        if self.last_error != Some(error) {
            self.events
                .push_back(format!("Shared world suspended: {error}"));
            self.last_error = Some(error);
        }
    }
    /// # Safety
    /// Existing PostPhysics offline gameplay gate and exact version check passed.
    pub unsafe fn tick(&mut self, enabled: bool) {
        if !enabled {
            self.suspend();
            return;
        }
        let now = transport::now();
        if self.last_tick != 0 && now.saturating_sub(self.last_tick) < 50 {
            return;
        }
        self.last_tick = now;
        match unsafe { self.update(now) } {
            Ok(()) => self.last_error = None,
            Err(error) => self.fail(error),
        }
        while self.events.len() > 32 {
            self.events.pop_front();
        }
    }
    unsafe fn update(&mut self, now: u64) -> Result<(), &'static str> {
        let mut scene = unsafe { self.native.tick() }?;
        if scene.epoch != self.epoch {
            self.epoch = scene.epoch;
            self.instances.clear();
            self.history.clear();
            self.mobs.clear();
            self.player_source = None;
            self.ledger = Ledger::default();
            self.cached_mobs = None;
            self.landing = None;
            self.launch_history.clear();
            self.flights.reset();
            self.impacts.clear();
            self.impacts_ms = 0;
            self.flight_context = None;
            self.player_flight.reset();
            self.player_torrent.reset();
            self.player_combat.reset();
            self.fluids.clear();
            self.events.push_back(format!(
                "Shared world epoch={} region={} source_map={} mode={:?}",
                scene.epoch, scene.map, scene.source_map, scene.coordinate_mode
            ));
        }
        let mut sampled = unsafe { targets::target_snapshot_with_radius(10., 64.) }?;
        let mut published = self.targets(&sampled, scene.offset)?;
        let player_id = PLAYER_ID;
        let native_player = packed(sampled.player_handle);
        let mut mob_snapshot_active = false;
        let mut desired_mobs = 0;
        if let Some(envelope) = self.reader.poll() {
            // The producer can publish while the seqlock copy is in progress.
            // Sample time after that copy, never label a legitimate newer frame
            // as future relative to the tick's earlier timestamp.
            let now = transport::now();
            let guest = &envelope.guest;
            let matching = guest.host_pid == transport::pid()
                && guest.epoch == scene.epoch
                && guest.map == scene.map
                && self.ledger.observe(envelope.pid, guest.session, now);
            // Session acknowledgement must precede simulation activation: the
            // guest is inactive during its dimension transfer/terrain bootstrap.
            if matching {
                self.incoming
                    .reset_context(envelope.pid, guest.session, scene.epoch)?;
                self.incoming.accept_ack(guest.ack_incoming)?;
            }
            // Pose ownership is independent of sampled terrain completeness.
            // No world damage, mobs or block authority inherits this permission.
            if matching && let Some(view) = guest.view {
                self.view = Some((view, now));
            }
            if matching && guest.kinematics_active && guest.player_uuid.is_some() {
                let observed = guest.flight.is_some_and(|s| {
                    self.history.iter().any(|h| {
                        h.frame == s.observed_frame
                            && h.time <= now
                            && now - h.time <= crate::player_flight::FRESH_MS
                    })
                });
                let context = crate::player_flight::Context {
                    pid: envelope.pid,
                    session: guest.session,
                    epoch: scene.epoch,
                    map: scene.map,
                };
                self.player_flight
                    .observe(context, guest.flight, now, observed);
                let observed = guest.combat.is_some_and(|s| {
                    self.history.iter().any(|h| {
                        h.frame == s.observed_frame
                            && h.time <= now
                            && now - h.time <= crate::combat_effects::FRESH_MS
                    })
                });
                self.player_combat
                    .observe(context, guest.combat, now, observed);
                // The same recent-native-frame witness as the glide: a guest
                // that stopped reading the host cannot keep a mount alive.
                let observed = guest.torrent.is_some_and(|s| {
                    self.history.iter().any(|h| {
                        h.frame == s.observed_frame
                            && h.time <= now
                            && now - h.time <= crate::torrent::FRESH_MS
                    })
                });
                self.player_torrent
                    .observe(context, guest.torrent, now, observed);
            } else {
                self.player_flight.revoke();
                self.player_torrent.revoke();
                self.player_combat.revoke();
            }
            if envelope.active && matching {
                let context = flight::Context {
                    pid: envelope.pid,
                    session: guest.session,
                    epoch: scene.epoch,
                    map: scene.map,
                };
                if self.flight_context != Some(context) {
                    self.flights.reset();
                    self.impacts.clear();
                    self.landing = None;
                    self.flight_context = Some(context);
                }
                self.player_source = guest
                    .player_uuid
                    .as_ref()
                    .map(|uuid| (envelope.pid, guest.session, uuid.clone()));
                if guest
                    .mobs
                    .iter()
                    .any(|m| distance(m.position, scene.feet) > WORLD_RADIUS + 8.)
                    || guest.blocks.iter().flat_map(|b| &b.boxes).any(|b| {
                        distance_to_box(scene.feet, [b[0], b[1], b[2]], [b[3], b[4], b[5]])
                            > WORLD_RADIUS + 8.
                    })
                {
                    return Err("guest world snapshot outside active scene");
                }
                if self.mobs.len() > 256 {
                    self.mobs
                        .retain(|_, (_, time)| now.saturating_sub(*time) < 2000);
                }
                for mob in &guest.mobs {
                    self.mobs.insert(mob.uuid.clone(), (mob.clone(), now));
                }
                self.native.accept_guest_at(guest, envelope.timestamp)?;
                self.fluids.clear();
                if scene.terrain_ready && guest.kinematics_active && guest.player_uuid.is_some() {
                    for contact in &guest.fluids {
                        let witnessed = self.history.iter().any(|h| {
                            h.frame == contact.observed_frame
                                && h.time <= now
                                && now - h.time <= crate::world_fluids::FRESH_MS
                                && h.targets.iter().any(|t| contact.matches(t))
                        });
                        if contact.fresh(now)
                            && witnessed
                            && published.iter().any(|t| contact.matches(t))
                            && let Some(&(instance, generation)) = self.instances.get(&contact.id)
                            && generation == contact.generation
                        {
                            self.fluids.push(crate::world_fluids::Owned {
                                contact: *contact,
                                instance,
                            });
                        }
                    }
                }
                self.cached_mobs = Some(CachedMobs {
                    context: mob_proxy::Context {
                        epoch: scene.epoch,
                        map: scene.map,
                        guest_pid: envelope.pid,
                        session: guest.session,
                    },
                    produced_ms: envelope.timestamp,
                    mobs: guest.mobs.clone(),
                });
                // A creeper can hit many targets, but no guest burst may occupy
                // the physics task indefinitely. Unconsumed events are retried.
                let mut processed = 0;
                let mut ray_budget = RayBudget::new();
                for event in &guest.events {
                    if event.seq <= self.ledger.ack {
                        continue;
                    }
                    if processed == 32 {
                        break;
                    }
                    processed += 1;
                    let policy = self.validate(event, &scene, &published, player_id, now);
                    let path = if !event.projectile.is_empty() {
                        Path::prepare(
                            &event.trajectory,
                            event.position,
                            event.launch_time_ms,
                            event.time_ms,
                            now,
                        )
                        .and_then(|_| {
                            self.flights
                                .remaining_path(&event_flight(event), event.time_ms, now)
                        })
                        .map(Some)
                    } else {
                        Ok(None)
                    };
                    let extra = if event.kind == "ender_pearl" {
                        native_teleport::CLEARANCE_RAYS
                    } else {
                        5
                    };
                    // A pending landing window belongs to this immutable event
                    // and peer; deferred scans never consume its ACK/authority.
                    let mut deferred = false;
                    let landing = if policy.is_ok() && path.is_ok() && event.kind == "ender_pearl" {
                        match unsafe { self.prepare_landing(event, &scene, &mut ray_budget, now) } {
                            Ok(true) => Ok(()),
                            Ok(false) => {
                                deferred = true;
                                Ok(())
                            }
                            Err(e) => Err(e),
                        }
                    } else {
                        Ok(())
                    };
                    if deferred {
                        break;
                    }
                    if policy.is_ok()
                        && path.is_ok()
                        && landing.is_ok()
                        && !ray_budget.can_fit(
                            path.as_ref()
                                .ok()
                                .and_then(|p| p.as_ref())
                                .map_or(0, Path::ray_count)
                                + extra,
                        )
                    {
                        break;
                    }
                    let admitted = self.ledger.consume(event, now).and(policy).and(landing);
                    let result = match admitted {
                        Ok(()) => match path {
                            Err(e) => Err(e),
                            Ok(path) => {
                                let clear = match path {
                                    Some(p) => unsafe {
                                        verify_path(
                                            &p,
                                            &scene,
                                            &mut ray_budget,
                                            self.flight_rays.as_ref().map_err(|e| *e),
                                        )
                                    },
                                    None => Ok(()),
                                };
                                clear.and_then(|_| {
                                    if !ray_budget.reserve(extra) {
                                        return Err("world ray budget unexpectedly exhausted");
                                    }
                                    if event.kind == "ender_pearl" {
                                        unsafe {
                                            self.apply_pearl(
                                                event,
                                                &mut scene,
                                                native_player,
                                                &guest.blocks,
                                                now,
                                            )
                                        }
                                    } else {
                                        unsafe {
                                            native_cover(
                                                event,
                                                &scene,
                                                &published,
                                                self.flight_rays.as_ref().ok(),
                                            )
                                        }
                                        .and_then(
                                            |_| unsafe { self.apply(event, &scene, native_player) },
                                        )
                                    }
                                })
                            }
                        },
                        Err(error) => Err(error),
                    };
                    match &result {
                        Ok(delta) => self.events.push_back(format!(
                            "World {} source={} event={} target={} damage={} native_delta={delta}",
                            event.kind, event.source, event.event, event.target, event.damage
                        )),
                        Err(reason) => {
                            let h = self
                                .history
                                .iter()
                                .find(|h| h.frame == event.observed_frame)
                                .or_else(|| {
                                    self.landing
                                        .as_ref()
                                        .filter(|s| s.seq == event.seq)
                                        .map(|s| &s.observed)
                                });
                            self.events.push_back(format!("World event {} rejected: {reason}; observed_frame={} received_revision={} historical_revision={:?} frame_age_ms={:?} receipt_age_ms={} landing_pending={}",
                                event.seq,event.observed_frame,event.terrain_revision,h.map(|h|h.revision),h.map(|h|now.saturating_sub(h.time)),now.saturating_sub(event.time_ms),self.landing.is_some()));
                        }
                    }
                    self.ledger.outcome(event.seq, result);
                    if event.kind == "ender_pearl" {
                        self.landing = None;
                    }
                    if self.relocation_started && self.relocated.is_none() {
                        return Err(
                            "pearl relocation readback unverified; waiting for native resample",
                        );
                    }
                    // Never process another event against the pre-teleport
                    // target snapshot or move twice in the same physics task.
                    if self.relocation_started {
                        break;
                    }
                }
                let history = &self.launch_history;
                let source = guest.player_uuid.as_deref();
                let rays = self.flight_rays.as_ref().map_err(|e| *e);
                let context = flight::Context {
                    pid: envelope.pid,
                    session: guest.session,
                    epoch: scene.epoch,
                    map: scene.map,
                };
                let offset = scene.offset;
                let events = &mut self.events;
                let report=self.flights.update(context,&guest.projectiles,envelope.timestamp,now,&mut ray_budget,
                    |p|source==Some(p.source.as_str()) && owned_launch(history,p),
                    |projectile,segment|{
                        let api=rays?;let world=unsafe{api.world()}?;
                        // Physical-obstruction policy: resolve the real local
                        // capsule's filter; never guess a layer or fall back to
                        // the terrain diagnostic/query-only mask.
                        let query_filter=unsafe{api.character_filter(world)}?;
                        let origin=std::array::from_fn(|i|segment.origin[i]-offset[i]);let delta=std::array::from_fn(|i|segment.end[i]-segment.origin[i]);
                        let hit=unsafe{api.ray(world,origin,delta,query_filter)}?;
                        if let Some(h)=hit{
                            let body=unsafe{api.body_evidence(world,h.body)}?;
                            events.push_back(format!("Native projectile contact uuid={} body={} filter={} query_filter={query_filter} motion_id={} body_flags={} user_data_present={} shape_key={} segment_start={:?} segment_end={:?} point={:?} normal={:?}",projectile.projectile,h.body,body.filter,body.motion_id,body.flags,body.user_data_present,h.shape_key,origin,std::array::from_fn::<_,3,_>(|i|origin[i]+delta[i]),h.point,h.normal));
                            Ok(Some(flight::Hit{point:std::array::from_fn(|i|h.point[i]+offset[i]),normal:h.normal}))
                        }else{Ok(None)}
                    })?;
                self.impacts = report.impacts;
                self.impacts_ms = envelope.timestamp;
                for (projectile, reason) in report.rejected {
                    self.events
                        .push_back(format!("Native projectile {projectile} rejected: {reason}"));
                }
                // Damage processors can advance native actors. Resample handles
                // and health before ACKs are advertised to the guest.
                if self.relocated.is_none() {
                    sampled = unsafe { targets::target_snapshot_with_radius(10., 64.) }?;
                    published = self.targets(&sampled, scene.offset)?;
                } else {
                    // The native camera is updated in the later DrawParam
                    // phase. Its old eye can legitimately be >10m from the new
                    // player now. Keep copied, still-in-range target observations
                    // for this one publication rather than resetting the scene.
                    retain_nearby(&mut published, scene.feet);
                }
                if let Ok(player) = unsafe { PlayerIns::local_player() } {
                    scene.hp = player.chr_ins.modules.data.hp;
                }
            } else {
                // Explicit inactivity or a changed peer is revocation, unlike
                // one unavailable/torn read of a still-fresh prior publication.
                self.cached_mobs = None;
                self.fluids.clear();
                self.native.revoke_guest();
                self.player_source = None;
                self.landing = None;
                self.flights.reset();
                self.impacts.clear();
                self.impacts_ms = 0;
                self.flight_context = None;
            }
        }
        let proxy_now = transport::now();
        if self.cached_mobs.as_ref().is_some_and(|c| {
            !c.fresh(proxy_now, scene.epoch, scene.map, self.ledger.identity)
                || c.mobs
                    .iter()
                    .any(|m| distance(m.position, scene.feet) > WORLD_RADIUS + 8.)
        }) {
            self.cached_mobs = None;
        }
        self.incoming.snapshot(proxy_now); // Expire before pending HP reconciliation.
        for expired in self.incoming.drain_expired() {
            self.events.push_back(format!(
                "Native mob receipt expired: uuid={} seq={} damage={} age_ms={} reason={}",
                expired.uuid, expired.seq, expired.damage, expired.age_ms, expired.reason
            ));
        }
        if let (Some(cache), Some(proxies)) = (self.cached_mobs.as_ref(), self.mob_proxies.as_mut())
        {
            let mut adjusted = cache.mobs.clone();
            desired_mobs = adjusted.iter().filter(|m| m.hp > 0.).count();
            for mob in &mut adjusted {
                mob.hp = (mob.hp - self.incoming.pending(&mob.uuid)).max(0.);
            }
            if self.incoming.capacity() >= mob_proxy::MAX_PROXIES {
                match unsafe {
                    proxies.tick(
                        cache.context,
                        &adjusted,
                        scene.offset,
                        proxy_now,
                        cache.produced_ms,
                    )
                } {
                    Ok(hits) => {
                        self.mob_error = None;
                        mob_snapshot_active = true;
                        for hit in hits {
                            let source = u64::from(hit.source.selector.0)
                                | (u64::from(hit.source.block_id.0 as u32) << 32);
                            match self.incoming.push(
                                &hit.uuid,
                                hit.damage,
                                &source.to_string(),
                                hit.time_ms,
                            ) {
                                Ok(receipt) => self.events.push_back(format!(
                                    "Native mob hit uuid={} seq={} damage={} source={source}",
                                    hit.uuid, receipt.seq, hit.damage
                                )),
                                Err(e) => {
                                    self.mob_error = Some(e);
                                    self.events
                                        .push_back(format!("Native mob receipt rejected: {e}"));
                                }
                            }
                        }
                    }
                    Err(e) => self.mob_error = Some(e),
                }
            } else {
                self.mob_error = Some("native mob incoming queue full");
            }
        }
        if let Some(proxies) = self.mob_proxies.as_mut() {
            if !mob_snapshot_active {
                unsafe {
                    proxies.suspend(proxy_now);
                }
            }
            scene.features.native_mob_proxies = mob_snapshot_active
                && self.mob_error.is_none()
                && proxies.blocked_reason().is_none()
                && desired_mobs > 0
                && proxies.active_count() >= desired_mobs;
            for event in proxies.events.drain(..) {
                self.events.push_back(event);
            }
            // A clone adopted just now was not owned when the earlier target
            // snapshot ran. Exclude it before publishing an ER-enemy proxy back
            // into Minecraft, avoiding a one-frame duplicate of the same mob.
            published.retain(|t| !proxies.owns(handle(t.id)));
        }
        self.mobs
            .retain(|_, (_, time)| now.saturating_sub(*time) < 2000);
        let mut payload =
            serde_json::to_value(&scene).map_err(|_| "world snapshot serialization failed")?;
        let object = payload
            .as_object_mut()
            .ok_or("world snapshot not an object")?;
        object.insert(
            "targets".into(),
            serde_json::to_value(&published).map_err(|_| "world target serialization failed")?,
        );
        object.insert(
            "target_rejections".into(),
            serde_json::to_value(&sampled.rejected_roles)
                .map_err(|_| "world target rejection serialization failed")?,
        );
        object.insert("player_id".into(), player_id.into());
        object.insert("player_generation".into(), scene.epoch.into());
        object.insert("damage_scale".into(), self.scale.into());
        object.insert(
            "guest_session".into(),
            self.ledger.identity.map_or(0, |(_, s)| s).into(),
        );
        object.insert("ack".into(), self.ledger.ack.into());
        object.insert(
            "acks".into(),
            serde_json::to_value(&self.ledger.results)
                .map_err(|_| "world ACK serialization failed")?,
        );
        if now.saturating_sub(self.impacts_ms) > wire::FRESH_MS {
            self.impacts.clear();
        }
        object.insert(
            "projectile_impacts".into(),
            serde_json::to_value(&self.impacts)
                .map_err(|_| "world projectile serialization failed")?,
        );
        object.insert(
            "incoming".into(),
            serde_json::to_value(self.incoming.snapshot(transport::now()))
                .map_err(|_| "world incoming serialization failed")?,
        );
        if let Some(proxies) = self.mob_proxies.as_ref() {
            object.insert("native_mob_status".into(),serde_json::json!({
            "desired":desired_mobs,"active":proxies.active_count(),"pending":proxies.pending_count(),"retiring":proxies.retiring_count(),
            "limit":mob_proxy::MAX_PROXIES,"blocked":proxies.blocked_reason(),"error":self.mob_error,"hits":proxies.observed_hits}));
        }
        let publisher = self
            .publisher
            .as_mut()
            .ok_or("world host mapping unavailable")?;
        let frame = publisher.publish(true, &payload)?;
        self.launch_history.push_back(LaunchHistory {
            frame,
            time: now,
            feet: scene.feet,
        });
        while self.launch_history.len() > 160
            || self
                .launch_history
                .front()
                .is_some_and(|h| now.saturating_sub(h.time) > 7000)
        {
            self.launch_history.pop_front();
        }
        self.history.push_back(History {
            frame,
            time: now,
            revision: scene.terrain_revision,
            targets: published,
        });
        while self.history.len() > MAX_HISTORY
            || self
                .history
                .front()
                .is_some_and(|h| now.saturating_sub(h.time) > wire::RECEIPT_MS)
        {
            self.history.pop_front();
        }
        crate::scene_camera::set_world(scene.epoch, scene.map, scene.source_map, scene.offset);
        if !self.active {
            self.events.push_back(
                "Shared world transport active; native capabilities reported explicitly.".into(),
            );
        }
        self.active = true;
        Ok(())
    }
    fn targets(
        &mut self,
        s: &targets::TargetSnapshot,
        offset: [f64; 3],
    ) -> Result<Vec<wire::Target>, &'static str> {
        let mut result = Vec::with_capacity(s.candidates.len());
        self.instances
            .retain(|id, _| s.candidates.iter().any(|c| packed(c.handle) == *id));
        for candidate in &s.candidates {
            let id = packed(candidate.handle);
            if id <= PLAYER_ID || id > i64::MAX as u64 {
                continue;
            }
            if self
                .mob_proxies
                .as_ref()
                .is_some_and(|p| p.owns(handle(id)))
            {
                continue;
            }
            let generation = match self.instances.get(&id) {
                Some((ptr, g)) if *ptr == candidate.instance_token => *g,
                _ => {
                    self.generation = self
                        .generation
                        .checked_add(1)
                        .ok_or("world target generation exhausted")?;
                    self.instances
                        .insert(id, (candidate.instance_token, self.generation));
                    self.generation
                }
            };
            result.push(wire::Target {
                id,
                generation,
                min: std::array::from_fn(|i| candidate.proxy_min_havok[i] as f64 + offset[i]),
                max: std::array::from_fn(|i| candidate.proxy_max_havok[i] as f64 + offset[i]),
                hp: candidate.hp,
                max_hp: candidate.max_hp,
                team: candidate.team,
            });
        }
        Ok(result)
    }
    unsafe fn prepare_landing(
        &mut self,
        e: &wire::Event,
        scene: &world_native::Snapshot,
        budget: &mut RayBudget,
        now: u64,
    ) -> Result<bool, &'static str> {
        let destination = e.destination.ok_or("pearl destination missing")?;
        let peer = self.ledger.identity.ok_or("pearl peer unavailable")?;
        let (height, radius) = unsafe { self.teleporter.as_ref().map_err(|e| *e)?.dimensions() }?;
        if self.landing.as_ref().is_none_or(|s| {
            s.peer != peer
                || s.seq != e.seq
                || s.epoch != scene.epoch
                || s.map != scene.source_map
                || s.offset != scene.offset
                || s.destination != destination
        }) {
            let mut cache = worldterrain::Cache::new();
            cache.recenter(destination, now)?;
            let observed = self
                .history
                .iter()
                .find(|h| h.frame == e.observed_frame)
                .cloned()
                .ok_or("pearl admission frame missing")?;
            self.landing = Some(LandingScan {
                peer,
                seq: e.seq,
                epoch: scene.epoch,
                map: scene.source_map,
                offset: scene.offset,
                destination,
                event: e.clone(),
                observed,
                cache,
            });
        }
        let pending = self.landing.as_mut().unwrap();
        pending
            .cache
            .prioritize_bounds(native_teleport::landing_bounds(destination, height, radius))?;
        let before = pending.cache.snapshot();
        if before.ready
            && native_teleport::covers_destination(before.bounds, destination, height, radius)
        {
            return Ok(true);
        }
        let count =
            budget.remaining().min(96) / worldterrain::RAYS_PER_CELL * worldterrain::RAYS_PER_CELL;
        if count == 0 {
            return Ok(false);
        }
        budget.reserve(count);
        pending.cache.tick(now, count, |ray| unsafe {
            self.native.sample_landing_ray(ray, scene.offset)
        });
        let sample = pending.cache.snapshot();
        if sample.failed_cells > 0 {
            return Err("pearl destination native terrain queries failed");
        }
        Ok(sample.ready
            && native_teleport::covers_destination(sample.bounds, destination, height, radius))
    }
    unsafe fn apply_pearl(
        &mut self,
        e: &wire::Event,
        scene: &mut world_native::Snapshot,
        native_player: u64,
        blocks: &[wire::Block],
        now: u64,
    ) -> Result<i32, &'static str> {
        let mut scan = self.landing.take().ok_or("pearl landing scan missing")?;
        let terrain = scan.cache.snapshot();
        let mut checked = scene.clone();
        checked.terrain_ready = terrain.ready;
        checked.terrain_bounds = terrain.bounds;
        checked.terrain_boxes = terrain.boxes;
        let receipt = unsafe {
            self.teleporter.as_ref().map_err(|e| *e)?.apply(
                native_player,
                &checked,
                e,
                blocks,
                &mut self.relocation_started,
            )
        }?;
        // Preserve this notification even if the subsequent HP charge fails:
        // movement/camera lifecycle must respond to the actual relocation.
        self.relocated = Some(receipt);
        self.native
            .adopt_landing(scene, scan.cache, receipt.after, now);
        self.events.push_back(format!(
            "Native pearl relocated before={:?} after={:?} terrain_revision={}",
            receipt.before, receipt.after, scene.terrain_revision
        ));
        if e.damage == 0.0 {
            return Ok(0);
        }
        unsafe { self.apply(e, scene, native_player) }
    }
    fn validate(
        &self,
        e: &wire::Event,
        scene: &world_native::Snapshot,
        current: &[wire::Target],
        player: u64,
        now: u64,
    ) -> Result<(), &'static str> {
        let pending = self.landing.as_ref().filter(|s| s.seq == e.seq);
        if pending.is_some_and(|s| {
            !s.matches(
                e,
                self.ledger.identity,
                scene.epoch,
                scene.source_map,
                scene.offset,
            )
        }) {
            return Err("pearl queued event or context changed");
        }
        let old = pending
            .map(|s| &s.observed)
            .or_else(|| self.history.iter().find(|h| h.frame == e.observed_frame))
            .ok_or("world damage observed frame missing")?;
        validate_observed(e, old, now, pending.is_some())?;
        if !scene.terrain_ready {
            return Err("world damage terrain incomplete");
        }
        let player_source = self
            .player_source
            .as_ref()
            .is_some_and(|(pid, session, uuid)| {
                self.ledger.identity == Some((*pid, *session)) && *uuid == e.source
            });
        if e.kind == "environment" {
            return validate_environment(
                e,
                &old.targets,
                current,
                player,
                scene.epoch,
                scene.feet,
                player_source,
            );
        }
        if player_source {
            if !["projectile", "explosion", "ender_pearl"].contains(&e.kind.as_str()) {
                return Err("world player source effect unsupported");
            }
            if e.kind == "projectile" || e.kind == "ender_pearl" {
                if e.projectile.is_empty() {
                    return Err("world owned projectile provenance missing");
                }
                if !owned_launch(&self.launch_history, &event_flight(e)) {
                    return Err("world projectile launch origin not owned");
                }
            }
        } else {
            let (source, when) = self
                .mobs
                .get(&e.source)
                .ok_or("world damage source not observed")?;
            if now.saturating_sub(*when) > 1500 {
                return Err("world damage source stale");
            }
            let max_origin = if e.kind == "projectile" { 64. } else { 8. };
            if distance(e.position, source.position) > max_origin {
                return Err("world damage origin outside source bounds");
            }
        }
        if distance(e.position, scene.feet) > WORLD_RADIUS {
            return Err("world damage origin outside active scene");
        }
        if e.kind == "ender_pearl" {
            if !player_source
                || e.target != player
                || e.generation != scene.epoch
                || e.projectile_kind != "minecraft:ender_pearl"
                || e.destination
                    .is_none_or(|p| distance(p, scene.feet) > 64. || distance(p, e.position) > 8.05)
            {
                return Err("world pearl source or destination rejected");
            }
            return Ok(());
        }
        let (min, max) = if e.target == player {
            if e.generation != scene.epoch {
                return Err("world player generation mismatch");
            }
            (
                std::array::from_fn(|i| scene.feet[i] - if i == 1 { 0.0 } else { 0.4 }),
                std::array::from_fn(|i| scene.feet[i] + if i == 1 { 1.9 } else { 0.4 }),
            )
        } else {
            let before = old
                .targets
                .iter()
                .find(|t| t.id == e.target && t.generation == e.generation)
                .ok_or("world historical target missing")?;
            let target = current
                .iter()
                .find(|t| t.id == e.target && t.generation == e.generation && t.hp > 0)
                .ok_or("world current target missing")?;
            if distance(before.min, target.min) > 16. {
                return Err("world target moved beyond receipt tolerance");
            }
            (target.min, target.max)
        };
        let reach = match e.kind.as_str() {
            "explosion" => f64::from(e.radius) * 2. + 1.,
            "mob_melee" => 6.,
            "projectile"
                if matches!(
                    e.projectile_kind.as_str(),
                    "minecraft:splash_potion" | "minecraft:lingering_potion"
                ) =>
            {
                5.
            }
            "projectile" => 3.,
            _ => return Err("world damage kind unsupported"),
        };
        if distance_to_box(e.position, min, max) > reach {
            return Err("world damage target outside effect");
        }
        Ok(())
    }
    unsafe fn apply(
        &mut self,
        e: &wire::Event,
        scene: &world_native::Snapshot,
        native_player: u64,
    ) -> Result<i32, &'static str> {
        let amount = if e.target == PLAYER_ID {
            e.damage / e.guest_max_hp * scene.max_hp as f32
        } else {
            e.damage * self.scale
        };
        if !amount.is_finite() || !(0.0..=1_000_000.).contains(&amount) {
            return Err("world damage scale invalid");
        }
        let amount = amount.round().max(1.) as i32;
        if e.target == PLAYER_ID {
            return unsafe { damage_player(native_player, amount, scene.source_map) };
        }
        let sink = self.sink.as_ref().map_err(|e| *e)?;
        let origin = std::array::from_fn(|i| (e.position[i] - scene.offset[i]) as f32);
        // Existing native enemy sink owns death/reaction behavior. The source
        // remains player-attributed until a verified native mob proxy exists.
        let receipt = unsafe {
            sink.apply_world(
                handle(native_player),
                handle(e.target),
                amount,
                Some(origin),
            )
        }?;
        if receipt.applied_delta() > 0 {
            crate::campaign_runtime::record_kill(&receipt);
            Ok(receipt.applied_delta())
        } else {
            Err("native enemy rejected world damage")
        }
    }
}
fn event_flight(e: &wire::Event) -> flight::Flight {
    flight::Flight {
        projectile: e.projectile.clone(),
        projectile_kind: e.projectile_kind.clone(),
        source: e.source.clone(),
        launch_frame: e.launch_frame,
        launch_time_ms: e.launch_time_ms,
        trajectory: e.trajectory.clone(),
    }
}
fn owned_launch(history: &VecDeque<LaunchHistory>, p: &flight::Flight) -> bool {
    history.iter().any(|h| {
        h.frame == p.launch_frame
            && p.launch_time_ms >= h.time
            && p.launch_time_ms - h.time <= 1000
            && p.trajectory
                .first()
                .is_some_and(|v| wire::finite_position(*v) && distance(*v, h.feet) <= 3.0)
    })
}
fn validate_observed(
    e: &wire::Event,
    old: &History,
    now: u64,
    admitted: bool,
) -> Result<(), &'static str> {
    if e.time_ms > now || now.saturating_sub(e.time_ms) > wire::RECEIPT_MS {
        return Err("world damage expired");
    }
    if old.frame != e.observed_frame || old.revision != e.terrain_revision {
        return Err("world event frame/revision mismatch");
    }
    // A staged pearl already proved this exact immutable source frame when it
    // entered the landing queue. Keep that proof for the original receipt's
    // deadline; time spent in our own bounded scanner must not silently shorten
    // it. No new receipt receives an extended frame-age allowance.
    if old.time > now || (!admitted && now - old.time > wire::RECEIPT_MS) {
        return Err("world observed frame expired");
    }
    Ok(())
}
fn retain_nearby(targets: &mut Vec<wire::Target>, feet: [f64; 3]) {
    targets.retain(|t| distance_to_box(feet, t.min, t.max) <= WORLD_RADIUS);
}
unsafe fn verify_path(
    path: &Path,
    scene: &world_native::Snapshot,
    budget: &mut RayBudget,
    api: Result<&crate::native_colliders::Api, &'static str>,
) -> Result<(), &'static str> {
    let api = api?;
    let world = unsafe { api.world() }?;
    let filter = unsafe { api.character_filter(world) }?;
    path.verify(budget, |segment| {
        let p: [f64; 3] = std::array::from_fn(|i| segment.origin[i] - scene.offset[i]);
        let delta: [f64; 3] = std::array::from_fn(|i| segment.end[i] - segment.origin[i]);
        unsafe { api.ray(world, p, delta, filter) }
            .map(|h| h.map(|h| std::array::from_fn(|i| h.point[i] + scene.offset[i])))
    })
    .map_err(|e| match e {
        CheckError::Deferred => "projectile ray budget unexpectedly exhausted",
        CheckError::Rejected(e) => e,
    })
}
/// Minecraft computes explosion exposure and accepted damage. Recheck native
/// cover as well because the streamed terrain is a sampled surface cache.
/// At most five bounded rays are needed for each effect receipt.
unsafe fn native_cover(
    e: &wire::Event,
    scene: &world_native::Snapshot,
    targets: &[wire::Target],
    physical: Option<&crate::native_colliders::Api>,
) -> Result<(), &'static str> {
    let (min, max) = if e.target == PLAYER_ID {
        (
            [scene.feet[0] - 0.4, scene.feet[1], scene.feet[2] - 0.4],
            [
                scene.feet[0] + 0.4,
                scene.feet[1] + 1.9,
                scene.feet[2] + 0.4,
            ],
        )
    } else {
        let target = targets
            .iter()
            .find(|t| t.id == e.target && t.generation == e.generation)
            .ok_or("world cover target missing")?;
        (target.min, target.max)
    };
    if distance_to_box(e.position, min, max) < 0.05 {
        return Ok(());
    }
    let center: [f64; 3] = std::array::from_fn(|i| (min[i] + max[i]) * 0.5);
    let near = std::array::from_fn(|i| e.position[i].clamp(min[i], max[i]));
    let mut points = [near, center, center, center, center];
    points[2][1] = min[1] + (max[1] - min[1]) * 0.9;
    points[3][0] = min[0] + (max[0] - min[0]) * 0.1;
    points[4][0] = min[0] + (max[0] - min[0]) * 0.9;
    let player =
        unsafe { PlayerIns::local_player() }.map_err(|_| "world cover player unavailable")?;
    let havok = unsafe { CSHavokMan::instance() }.map_err(|_| "world cover unavailable")?;
    let physical = if e.kind == "projectile" {
        let api = physical.ok_or("projectile physical cover unavailable")?;
        let world = unsafe { api.world() }?;
        let filter = unsafe { api.character_filter(world) }?;
        Some((api, world, filter))
    } else {
        None
    };
    let origin: [f64; 3] = std::array::from_fn(|i| e.position[i] - scene.offset[i]);
    for p in points {
        let destination = std::array::from_fn(|i| p[i] - scene.offset[i]);
        let delta: [f64; 3] = std::array::from_fn(|i| destination[i] - origin[i]);
        let hit = if let Some((api, world, filter)) = physical {
            unsafe { api.ray(world, origin, delta, filter) }?.map(|h| h.point)
        } else {
            havok
                .phys_world
                .cast_ray(
                    world_native::RAY_FILTER,
                    &HavokPosition::from_xyz(origin[0] as f32, origin[1] as f32, origin[2] as f32),
                    PositionDelta(delta[0] as f32, delta[1] as f32, delta[2] as f32),
                    player,
                )
                .map(|p| [p.0 as f64, p.1 as f64, p.2 as f64])
        };
        if clear_segment(origin, destination, hit) {
            return Ok(());
        }
    }
    Err("world effect blocked by native cover")
}
fn clear_segment(origin: [f64; 3], destination: [f64; 3], hit: Option<[f64; 3]>) -> bool {
    let length = distance(origin, destination);
    if !wire::finite_position(origin) || !wire::finite_position(destination) || length > 66. {
        return false;
    }
    if length < 0.001 {
        return true;
    }
    let Some(hit) = hit else {
        return true;
    };
    if !wire::finite_position(hit) {
        return false;
    }
    let direction: [f64; 3] = std::array::from_fn(|i| (destination[i] - origin[i]) / length);
    let delta: [f64; 3] = std::array::from_fn(|i| hit[i] - origin[i]);
    let along = (0..3).map(|i| delta[i] * direction[i]).sum::<f64>();
    let off_ray = (0..3)
        .map(|i| (delta[i] - along * direction[i]).powi(2))
        .sum::<f64>();
    (-0.05..=length + 0.05).contains(&along) && off_ray <= 0.01 && along + 0.1 >= length
}
/// Exact typed local-player HP adapter; never a pointer supplied by the guest.
unsafe fn damage_player(expected: u64, amount: i32, map: u32) -> Result<i32, &'static str> {
    let game =
        unsafe { GameMan::instance() }.map_err(|_| "world player damage game unavailable")?;
    let session = unsafe { CSSessionManager::instance() }
        .map_err(|_| "world player damage session unavailable")?;
    if game.is_in_online_mode
        || game.warp_requested
        || session.lobby_state != LobbyState::None
        || session.protocol_state != ProtocolState::None
    {
        return Err("world player damage offline gate closed");
    }
    let player =
        unsafe { PlayerIns::local_player_mut() }.map_err(|_| "world player damage unavailable")?;
    let h = player.chr_ins.field_ins_handle;
    if u64::from(h.selector.0) | (u64::from(h.block_id.0 as u32) << 32) != expected
        || player.current_block_id.0 as u32 != map
        || player.chr_ins.chr_flags1c5.death_flag()
        || player.chr_ins.modules.data.hp <= 0
    {
        return Err("world player damage identity/death gate");
    }
    let before = player.chr_ins.modules.data.hp;
    player.chr_ins.modules.data.hp = before.saturating_sub(amount).max(0);
    Ok(before - player.chr_ins.modules.data.hp)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn environment_receipts_match_player_or_live_observed_enemy() {
        let t = wire::Target {
            id: 10,
            generation: 2,
            min: [1., 0., 1.],
            max: [2., 2., 2.],
            hp: 100,
            ..Default::default()
        };
        let targets = vec![t.clone()];
        let e = wire::Event {
            kind: "environment".into(),
            target: 10,
            generation: 2,
            position: [1.5, 0., 1.5],
            ..Default::default()
        };
        assert!(validate_environment(&e, &targets, &targets, 1, 9, [0.; 3], true).is_ok());
        assert!(validate_environment(&e, &targets, &targets, 1, 9, [0.; 3], false).is_err());
        assert!(validate_environment(&e, &[], &targets, 1, 9, [0.; 3], true).is_err());
        for target in [
            wire::Target { hp: 0, ..t.clone() },
            wire::Target {
                generation: 3,
                ..t.clone()
            },
        ] {
            assert!(validate_environment(&e, &targets, &[target], 1, 9, [0.; 3], true).is_err());
        }
        let distant = wire::Event {
            position: [8., 0., 8.],
            ..e.clone()
        };
        assert!(validate_environment(&distant, &targets, &targets, 1, 9, [0.; 3], true).is_err());
        let player = wire::Event {
            target: 1,
            generation: 9,
            position: [0.; 3],
            ..e
        };
        assert!(validate_environment(&player, &[], &[], 1, 9, [0.; 3], true).is_ok());
        assert!(validate_environment(&player, &[], &[], 1, 10, [0.; 3], true).is_err());
    }
    #[test]
    fn staged_pearl_pins_exact_validated_frame_without_extending_receipt_deadline() {
        let h = History {
            frame: 5,
            time: 1000,
            revision: 8,
            targets: Vec::new(),
        };
        let mut e = wire::Event {
            observed_frame: 5,
            terrain_revision: 8,
            time_ms: 1400,
            ..Default::default()
        };
        assert!(validate_observed(&e, &h, 1500, false).is_ok());
        assert_eq!(
            validate_observed(&e, &h, 2200, false),
            Err("world observed frame expired")
        );
        assert!(validate_observed(&e, &h, 2200, true).is_ok());
        assert_eq!(
            validate_observed(&e, &h, 2401, true),
            Err("world damage expired")
        );
        e.terrain_revision = 9;
        assert_eq!(
            validate_observed(&e, &h, 2200, true),
            Err("world event frame/revision mismatch")
        );
    }
    #[test]
    fn staged_pearl_rejects_mutation_peer_map_rebase_and_epoch_changes() {
        let e = wire::Event {
            seq: 1,
            time_ms: 1000,
            destination: Some([1., 2., 3.]),
            ..Default::default()
        };
        let s = LandingScan {
            peer: (2, 3),
            seq: 1,
            epoch: 4,
            map: 5,
            offset: [0.; 3],
            destination: [1., 2., 3.],
            event: e.clone(),
            observed: History {
                frame: 1,
                time: 900,
                revision: 1,
                targets: Vec::new(),
            },
            cache: worldterrain::Cache::new(),
        };
        assert!(s.matches(&e, Some((2, 3)), 4, 5, [0.; 3]));
        let mut changed = e.clone();
        changed.time_ms += 1;
        assert!(!s.matches(&changed, Some((2, 3)), 4, 5, [0.; 3]));
        assert!(!s.matches(&e, Some((2, 9)), 4, 5, [0.; 3]));
        assert!(!s.matches(&e, Some((2, 3)), 9, 5, [0.; 3]));
        assert!(!s.matches(&e, Some((2, 3)), 4, 9, [0.; 3]));
        assert!(!s.matches(&e, Some((2, 3)), 4, 5, [1., 0., 0.]));
    }
    #[test]
    fn relocation_tick_keeps_copied_targets_without_old_camera_validation() {
        let mut targets = vec![
            wire::Target {
                id: 2,
                min: [1., 0., 1.],
                max: [2., 2., 2.],
                ..Default::default()
            },
            wire::Target {
                id: 3,
                min: [100., 0., 0.],
                max: [101., 2., 1.],
                ..Default::default()
            },
        ];
        retain_nearby(&mut targets, [10., 0., 0.]);
        assert_eq!(targets.len(), 1);
        assert_eq!(targets[0].id, 2);
    }
    #[test]
    fn projectile_launch_is_tied_to_exact_native_frame_origin_and_time() {
        let history = VecDeque::from([LaunchHistory {
            frame: 50,
            time: 1000,
            feet: [1., 2., 3.],
        }]);
        let mut f = flight::Flight {
            launch_frame: 50,
            launch_time_ms: 1100,
            trajectory: vec![[1., 3.6, 3.], [2., 4., 3.]],
            ..Default::default()
        };
        assert!(owned_launch(&history, &f));
        f.launch_frame = 49;
        assert!(!owned_launch(&history, &f));
        f.launch_frame = 50;
        f.launch_time_ms = 999;
        assert!(!owned_launch(&history, &f));
        f.launch_time_ms = 2001;
        assert!(!owned_launch(&history, &f));
        f.launch_time_ms = 1100;
        f.trajectory[0] = [5., 3.6, 3.];
        assert!(!owned_launch(&history, &f));
    }
    #[test]
    fn pearl_destination_scan_is_bounded_and_adoption_revision_cannot_reuse_old_window() {
        let destination = [40.2, 2.1, 3.2];
        let mut cache = worldterrain::Cache::new();
        cache.recenter(destination, 1000).unwrap();
        cache
            .prioritize_bounds(native_teleport::landing_bounds(destination, 1.8, 0.3))
            .unwrap();
        let mut ready = false;
        let mut queries = 0;
        for tick in 0..3 {
            let mut n = 0;
            cache.tick(1000 + tick * 50, 96, |_| {
                n += 1;
                Ok(None)
            });
            assert!(n <= 96);
            queries += n;
            let s = cache.snapshot();
            if s.ready && native_teleport::covers_destination(s.bounds, destination, 1.8, 0.3) {
                ready = true;
                break;
            }
        }
        assert!(ready);
        assert!(queries <= 3 * 96);
        cache.advance_revision_after(900);
        assert!(cache.snapshot().revision > 900);
    }
    #[test]
    fn world_events_are_consumed_before_mutation_and_duplicate_targets_rejected() {
        let mut l = Ledger::default();
        assert!(l.observe(1, 2, 100));
        let mut e = wire::Event {
            seq: 1,
            event: 1,
            source: "creeper".into(),
            target: 10,
            ..wire::Event::default()
        };
        assert!(l.consume(&e, 101).is_ok());
        assert_eq!(l.ack, 1);
        assert!(l.consume(&e, 102).is_err());
        e.seq = 2;
        assert!(l.consume(&e, 103).is_err());
        assert_eq!(l.ack, 2);
        e.seq = 3;
        e.target = 11;
        assert!(l.consume(&e, 104).is_ok());
        assert!(l.observe(1, 3, 105));
        assert!(!l.observe(1, 2, 106));
        assert!(l.observe(1, 2, 2206));
    }
    #[test]
    fn effect_distance_respects_large_target_bounds() {
        assert_eq!(
            distance_to_box([2., 1., 2.], [0., 0., 0.], [5., 5., 5.]),
            0.
        );
        assert_eq!(
            distance_to_box([7., 1., 2.], [0., 0., 0.], [5., 5., 5.]),
            2.
        );
    }
    #[test]
    fn effect_native_cover_rejects_wall_and_invalid_ray_results() {
        let a = [0., 0., 0.];
        let b = [0., 0., 6.];
        assert!(clear_segment(a, b, None));
        assert!(clear_segment(a, b, Some([0., 0., 5.95])));
        assert!(!clear_segment(a, b, Some([0., 0., 2.])));
        assert!(!clear_segment(a, b, Some([1., 0., 6.])));
        assert!(!clear_segment(a, b, Some([0., 0., 7.])));
        assert!(!clear_segment(a, b, Some([0., f64::NAN, 6.])));
    }
    #[test]
    fn player_wire_identity_is_positive_and_native_identity_remains_lossless() {
        let native = targets::Handle {
            selector: 0x1000000,
            block_id: -1,
        };
        assert!(packed(native) > i64::MAX as u64);
        assert!(PLAYER_ID > 0 && PLAYER_ID < i64::MAX as u64);
        let decoded = handle(packed(native));
        assert_eq!(decoded.selector.0, native.selector);
        assert_eq!(decoded.block_id.0, native.block_id);
    }
    #[test]
    fn cached_mob_snapshot_survives_transient_read_but_never_refreshes_its_age() {
        let cache = CachedMobs {
            context: mob_proxy::Context {
                epoch: 3,
                map: 4,
                guest_pid: 5,
                session: 6,
            },
            produced_ms: 1000,
            mobs: Vec::new(),
        };
        assert!(cache.fresh(1001, 3, 4, Some((5, 6))));
        assert!(cache.fresh(1500, 3, 4, Some((5, 6))));
        assert!(!cache.fresh(1501, 3, 4, Some((5, 6))));
        assert!(!cache.fresh(999, 3, 4, Some((5, 6))));
        assert!(!cache.fresh(1001, 7, 4, Some((5, 6))));
        assert!(!cache.fresh(1001, 3, 7, Some((5, 6))));
        assert!(!cache.fresh(1001, 3, 4, Some((5, 7))));
        assert!(!cache.fresh(1001, 3, 4, None));
    }
}
