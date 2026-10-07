//! Minecraft owns melee calculation; this adapter admits bounded, fresh hit receipts.
//! The native sink never asks the host player to perform an attack animation.
use crate::{combat_targets as targets, combat_transport, combat_wire as wire, native_damage};
use eldenring::cs::{BlockId, FieldInsHandle, FieldInsSelector, PlayerIns};
use std::collections::{HashMap, HashSet, VecDeque};
const PLAYER_REACH_CEILING_M: f32 = 6.0;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetTickCount64() -> u64;
    fn GetCurrentProcessId() -> u32;
}

#[derive(Clone, Debug, Default)]
pub struct Status {
    pub ready: bool,
    pub epoch: u64,
    pub nearby: usize,
    pub sources_scanned: usize,
    pub sources_available: usize,
    /// At most eight copied diagnostics, including raw type and encounter role;
    /// no pointers or SDK refs retained.
    pub target_rejections: Vec<targets::RejectedTarget>,
    pub nearest: Option<u64>,
    pub obstruction: Option<f32>,
    pub ack: u64,
    pub applied: u64,
    pub rejected: u64,
    pub last: Option<String>,
    pub reason: Option<&'static str>,
}
#[derive(Clone)]
struct Published {
    frame: u64,
    time: u64,
    targets: Vec<wire::Target>,
    nearest: Option<u64>,
}
pub struct Driver {
    publisher: Option<combat_transport::Publisher>,
    reader: combat_transport::Reader,
    sink: Result<native_damage::Sink, &'static str>,
    ack: wire::Acknowledgments,
    identity: Option<(usize, i32)>,
    epoch: u64,
    generation: u64,
    instances: HashMap<u64, (usize, u64)>,
    history: VecDeque<Published>,
    attacks: HashSet<u64>,
    hit_targets: HashSet<(u64, u64)>,
    last_attack: u64,
    guest_identity: Option<(u32, u64)>,
    scale: f32,
    lab_enabled: bool,
    debug_bounds: bool,
    status: Status,
}
fn packed(h: targets::Handle) -> u64 {
    h.selector as u64 | ((h.block_id as u32 as u64) << 32)
}
fn handle(h: u64) -> FieldInsHandle {
    FieldInsHandle {
        selector: FieldInsSelector(h as u32),
        block_id: BlockId((h >> 32) as i32),
    }
}
fn relative(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|i| a[i] - b[i])
}
/// Same axis-aligned proxy as Minecraft. Using a capsule here rejects legitimate
/// corner hits which Minecraft's EntityHitResult accepted.
fn entry(origin: [f32; 3], dir: [f32; 3], min: [f32; 3], max: [f32; 3], reach: f32) -> Option<f32> {
    let mut near = 0.0f32;
    let mut far = reach;
    for i in 0..3 {
        if dir[i].abs() < 1e-8 {
            if origin[i] < min[i] || origin[i] > max[i] {
                return None;
            }
        } else {
            let a = (min[i] - origin[i]) / dir[i];
            let b = (max[i] - origin[i]) / dir[i];
            near = near.max(a.min(b));
            far = far.min(a.max(b));
        }
    }
    (near <= far && far >= 0.0 && near <= reach).then_some(near)
}
fn nearby_box(camera: [f32; 3], t: &wire::Target, reach: f32) -> bool {
    (0..3)
        .map(|i| (camera[i] - camera[i].clamp(t.min[i], t.max[i])).powi(2))
        .sum::<f32>()
        <= reach * reach
}
impl Driver {
    pub fn new() -> Self {
        let scale = crate::campaign_runtime::damage_scale().unwrap_or_else(|| {
            std::env::var("ELDENCRAFT_DAMAGE_SCALE")
                .ok()
                .and_then(|v| v.parse::<f32>().ok())
                .filter(|v| v.is_finite() && (0.1..=1000.).contains(v))
                .unwrap_or(50.)
        });
        Self {
            publisher: combat_transport::Publisher::open().ok(),
            reader: combat_transport::Reader::default(),
            sink: native_damage::Sink::resolve(),
            ack: wire::Acknowledgments::default(),
            identity: None,
            epoch: 1,
            generation: 0,
            instances: HashMap::new(),
            history: VecDeque::new(),
            attacks: HashSet::new(),
            hit_targets: HashSet::new(),
            last_attack: 0,
            guest_identity: None,
            scale,
            lab_enabled: std::env::var("ELDENCRAFT_MELEE_LAB").is_ok_and(|v| v == "1"),
            debug_bounds: false,
            status: Status::default(),
        }
    }
    pub fn set_debug_bounds(&mut self, enabled: bool) {
        self.debug_bounds = enabled;
    }
    pub fn suspend(&mut self) {
        if self.identity.take().is_some() {
            self.epoch = self.epoch.saturating_add(1);
        }
        self.reset_transactions();
        self.guest_identity = None;
        self.instances.clear();
        self.status.ready = false;
        self.status.epoch = self.epoch;
        self.status.sources_scanned = 0;
        self.status.sources_available = 0;
        self.status.target_rejections.clear();
        if let Some(p) = self.publisher.as_mut() {
            let _ = p.publish(&wire::Targets {
                epoch: self.epoch,
                ..wire::Targets::default()
            });
        }
    }
    pub fn fail(&mut self, reason: &'static str) -> Status {
        self.suspend();
        self.status.reason = Some(reason);
        self.status.clone()
    }
    fn reset_transactions(&mut self) {
        self.ack.reset();
        self.history.clear();
        self.attacks.clear();
        self.hit_targets.clear();
        self.last_attack = 0;
    }
    fn observe_guest(&mut self, message: &wire::Damage, host_pid: u32, map: u32) {
        let identity = (message.flags & wire::ACTIVE != 0
            && message.host_pid == host_pid
            && message.epoch == self.epoch
            && message.map == map)
            .then_some((message.pid, message.session));
        if self.guest_identity != identity {
            self.reset_transactions();
            self.guest_identity = identity;
        }
    }
    fn record_native_outcome(&mut self, actual_delta: Option<i32>) -> bool {
        if actual_delta.is_some_and(|delta| delta > 0) {
            self.ack.applied();
            self.status.applied += 1;
            true
        } else {
            self.status.rejected += 1;
            false
        }
    }
    /// # Safety
    /// Run only after native input suppression under the current offline foreground
    /// player permit, on the game task. No SDK references may survive this call.
    pub unsafe fn tick(&mut self, player_token: usize, map: i32) -> Result<Status, &'static str> {
        if self.identity != Some((player_token, map)) {
            self.suspend();
            self.identity = Some((player_token, map));
        }
        // Camera rays include the third-person orbit. Player reach is checked
        // separately; Minecraft's actual item range remains authoritative.
        let mut snapshot = unsafe { targets::target_snapshot(targets::MAX_REACH_M) }?;
        let mut publication = self.make_publication(&snapshot)?;
        let mut changed = false;
        if let Some(message) = self.reader.poll() {
            let host_pid = unsafe { GetCurrentProcessId() };
            self.observe_guest(&message, host_pid, map as u32);
            let receipts = self.ack.admit(&message, host_pid, self.epoch, map as u32);
            for receipt in receipts {
                self.ack.consume(receipt.sequence); // consume BEFORE any native call, including failure
                let rejection = self.validate(receipt, &publication, unsafe { GetTickCount64() });
                if let Err(reason) = rejection {
                    self.status.rejected += 1;
                    self.status.last =
                        Some(format!("receipt {} rejected: {reason}", receipt.sequence));
                    continue;
                }
                let hp = (receipt.damage * self.scale).round();
                if !hp.is_finite() || !(1.0..=1_000_000.).contains(&hp) {
                    self.status.rejected += 1;
                    self.status.last = Some("scaled Minecraft damage outside native bounds".into());
                    continue;
                }
                self.last_attack = self.last_attack.max(receipt.attack);
                self.attacks.insert(receipt.attack);
                self.hit_targets.insert((receipt.attack, receipt.target));
                // No reference to a sampled ChrIns survives this dispatch. Sink resolves
                // both handles again and checks concrete native module identity.
                // An error can occur AFTER native mutation (for example readback
                // loses the target). Always resample before publishing its ack.
                changed = true;
                let result = match self.sink.as_mut() {
                    Ok(sink) => unsafe {
                        sink.apply(
                            handle(packed(snapshot.player_handle)),
                            handle(receipt.target),
                            hp as i32,
                        )
                    },
                    Err(reason) => Err(*reason),
                };
                match result {
                    Ok(result) => {
                        crate::campaign_runtime::record_kill(&result);
                        if self.record_native_outcome(Some(result.actual_delta)) {
                            self.status.last = Some(format!(
                                "receipt {} attack {} item {} mc_damage {} durability {}->{} native {:?}",
                                receipt.sequence,
                                receipt.attack,
                                receipt.item,
                                receipt.damage,
                                receipt.durability_before,
                                receipt.durability_after,
                                result
                            ));
                        } else {
                            self.status.last = Some(format!(
                                "native receipt {} rejected: no HP decrease ({result:?})",
                                receipt.sequence
                            ));
                        }
                    }
                    Err(reason) => {
                        self.record_native_outcome(None);
                        self.status.last = Some(format!(
                            "native receipt {} rejected: {reason}",
                            receipt.sequence
                        ));
                    }
                }
            }
        }
        // Publish post-dispatch health together with its acknowledgement. Otherwise
        // the guest could restore old health after clearing an in-flight receipt.
        if changed {
            snapshot = unsafe { targets::target_snapshot(targets::MAX_REACH_M) }?;
            publication = self.make_publication(&snapshot)?;
        }
        publication.ack_session = self.ack.session;
        publication.ack_receipt = self.ack.receipt;
        publication.ack_result = self.ack.result;
        let p = self
            .publisher
            .as_mut()
            .ok_or("target transport unavailable")?;
        let frame = p.publish(&publication)?;
        let now = unsafe { GetTickCount64() };
        self.history.push_back(Published {
            frame,
            time: now,
            nearest: self.status.nearest,
            targets: publication.targets.clone(),
        });
        while self
            .history
            .front()
            .is_some_and(|s| now.saturating_sub(s.time) > wire::FRESH_MS)
            || self.history.len() > 40
        {
            self.history.pop_front();
        }
        // Attack counters never reset inside a guest session. Keep only recent IDs;
        // older counters remain rejected by last_attack even after pruning.
        if self.attacks.len() > 64 {
            let newest = self.last_attack;
            self.attacks.retain(|n| newest.saturating_sub(*n) <= 32);
            self.hit_targets
                .retain(|(n, _)| newest.saturating_sub(*n) <= 32);
        }
        self.status.ack = self.ack.receipt;
        Ok(self.status.clone())
    }
    fn make_publication(
        &mut self,
        s: &targets::TargetSnapshot,
    ) -> Result<wire::Targets, &'static str> {
        let camera = relative(s.camera_origin_havok, s.player_havok);
        let ray_ok = matches!(
            s.ray.status,
            targets::RayStatus::Miss | targets::RayStatus::Hit
        );
        let ready = self.lab_enabled && self.sink.is_ok() && ray_ok;
        if self.status.ready && !ready {
            let identity = self.identity;
            self.suspend();
            self.identity = identity;
        }
        let mut out = wire::Targets {
            flags: wire::ACTIVE | if ready { wire::DAMAGE_READY } else { 0 },
            epoch: self.epoch,
            map: s.current_block_id as u32,
            camera,
            forward: s.forward,
            yaw: (-s.forward[0]).atan2(s.forward[2]).to_degrees(),
            pitch: (-s.forward[1]).asin().to_degrees(),
            obstruction: s.ray.hit_distance_m.unwrap_or(16.),
            damage_scale: self.scale,
            ..wire::Targets::default()
        };
        // Grounded is diagnostic until all vanilla movement/critical-hit predicates
        // are synchronized; the guest deliberately does not invent airborne crits.
        if unsafe { PlayerIns::local_player() }.is_ok_and(|p| {
            p.chr_ins.modules.physics.standing_on_solid_ground
                || p.chr_ins.modules.physics.touching_solid_ground
        }) {
            out.flags |= wire::GROUNDED;
        }
        if self.debug_bounds {
            out.flags |= wire::DEBUG_BOUNDS;
        }
        let mut present = HashSet::new();
        for c in s.candidates.iter().take(wire::MAX_TARGETS) {
            let id = packed(c.handle);
            present.insert(id);
            let generation = match self.instances.get(&id) {
                Some((token, g)) if *token == c.instance_token => *g,
                _ => {
                    self.generation = self
                        .generation
                        .checked_add(1)
                        .ok_or("target generation exhausted")?;
                    self.instances
                        .insert(id, (c.instance_token, self.generation));
                    self.generation
                }
            };
            let min = relative(c.proxy_min_havok, s.player_havok);
            let max = relative(c.proxy_max_havok, s.player_havok);
            let visible = c.los_clear == Some(true);
            out.targets.push(wire::Target {
                handle: id,
                generation,
                min,
                max,
                hp: c.hp as f32,
                max_hp: c.max_hp as f32,
                flags: if ready && visible { 3 } else { 0 },
                team: c.team as u32,
            });
        }
        self.instances.retain(|id, _| present.contains(id));
        self.status.ready = ready;
        self.status.epoch = self.epoch;
        self.status.nearby = out.targets.len();
        self.status.sources_scanned = s.scanned;
        self.status.sources_available = s.source_count;
        self.status.target_rejections = s.rejected_roles.iter().take(8).cloned().collect();
        self.status.nearest = out
            .targets
            .iter()
            .filter(|t| t.flags == 3)
            .filter_map(|t| {
                entry(camera, s.forward, t.min, t.max, targets::MAX_REACH_M)
                    .filter(|d| *d <= out.obstruction + 0.05)
                    .map(|d| (d, t.handle))
            })
            .min_by(|a, b| a.0.total_cmp(&b.0))
            .map(|(_, h)| h);
        self.status.obstruction = s.ray.hit_distance_m;
        self.status.reason = if !self.lab_enabled {
            Some("native melee lab verification is not enabled")
        } else if let Err(e) = self.sink.as_ref() {
            Some(*e)
        } else if !ray_ok {
            Some("native obstruction query unavailable")
        } else {
            None
        };
        Ok(out)
    }
    fn validate(
        &self,
        r: &wire::Receipt,
        current: &wire::Targets,
        now: u64,
    ) -> Result<(), &'static str> {
        if current.flags & wire::DAMAGE_READY == 0 {
            return Err("native damage route is not ready");
        }
        if self.hit_targets.contains(&(r.attack, r.target)) {
            return Err("target already consumed this attack");
        }
        if r.attack < self.last_attack {
            return Err("old attack counter");
        }
        if r.timestamp > now || now - r.timestamp > wire::FRESH_MS {
            return Err("receipt expired");
        }
        let old = self
            .history
            .iter()
            .find(|s| s.frame == r.host_frame && now.saturating_sub(s.time) <= wire::FRESH_MS)
            .ok_or("target frame expired")?;
        let before = old
            .targets
            .iter()
            .find(|t| t.handle == r.target && t.generation == r.generation && t.flags == 3)
            .ok_or("observed target is not hittable")?;
        let target = current
            .targets
            .iter()
            .find(|t| {
                t.handle == r.target
                    && t.generation == before.generation
                    && t.flags == 3
                    && t.hp > 0.
            })
            .ok_or("target changed or obscured")?;
        // Boxes are relative to host feet. Measuring from the orbit camera made
        // valid rear-view hits fail even while the player's real reach passed.
        if !nearby_box([0.0; 3], target, PLAYER_REACH_CEILING_M) {
            return Err("target left host melee ceiling");
        }
        if !self.attacks.contains(&r.attack) {
            if r.attack <= self.last_attack {
                return Err("old attack counter");
            }
            // Aim belongs to the frame Minecraft actually attacked. A later
            // mouse movement must not erase an already accepted vanilla hit.
            // Current identity, reach and cover are still checked above.
            if old.nearest != Some(r.target) {
                return Err("target is not the crosshair selection");
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn driver() -> Driver {
        // No process singleton lookup, mapping creation or native sink resolution.
        Driver {
            publisher: None,
            reader: combat_transport::Reader::default(),
            sink: Err("test sink disabled"),
            ack: wire::Acknowledgments::default(),
            identity: None,
            epoch: 2,
            generation: 0,
            instances: HashMap::new(),
            history: VecDeque::new(),
            attacks: HashSet::new(),
            hit_targets: HashSet::new(),
            last_attack: 0,
            guest_identity: None,
            scale: 50.,
            lab_enabled: false,
            debug_bounds: false,
            status: Status::default(),
        }
    }
    fn message(pid: u32, session: u64) -> wire::Damage {
        wire::Damage {
            sequence: 2,
            frame: 1,
            timestamp: 1000,
            pid,
            flags: wire::ACTIVE,
            session,
            host_pid: 30,
            map: 4,
            epoch: 2,
            receipts: vec![],
        }
    }
    fn target(id: u64) -> wire::Target {
        wire::Target {
            handle: id,
            generation: 8,
            min: [-0.3, 0., 2.],
            max: [0.3, 2., 2.6],
            hp: 100.,
            max_hp: 100.,
            flags: 3,
            team: 6,
        }
    }
    fn publication() -> wire::Targets {
        wire::Targets {
            flags: wire::ACTIVE | wire::DAMAGE_READY,
            epoch: 2,
            map: 4,
            camera: [0., 1.6, 0.],
            targets: vec![target(7), target(9)],
            ..wire::Targets::default()
        }
    }
    fn receipt(attack: u64, target: u64) -> wire::Receipt {
        wire::Receipt {
            sequence: 1,
            target,
            generation: 8,
            host_frame: 3,
            timestamp: 999,
            attack,
            damage: 6.,
            durability_before: 0,
            durability_after: 1,
            guest_max_hp: 20.,
            item: "minecraft:iron_sword".into(),
        }
    }
    fn history(d: &mut Driver) {
        d.history.push_back(Published {
            frame: 3,
            time: 998,
            targets: publication().targets,
            nearest: Some(7),
        });
        d.status.nearest = Some(7);
    }
    #[test]
    fn minecraft_aabb_corner_and_range_are_consistent() {
        assert_eq!(
            entry(
                [0., 1.6, 0.],
                [0., 0., 1.],
                [-0.3, 0., 2.],
                [0.3, 2., 2.6],
                3.
            ),
            Some(2.)
        );
        assert_eq!(
            entry(
                [0., 1.6, 0.],
                [0., 0., 1.],
                [-0.3, 0., 3.1],
                [0.3, 2., 3.7],
                3.
            ),
            None
        );
        assert!(
            entry(
                [0.29, 1.99, 0.],
                [0., 0., 1.],
                [-0.3, 0., 2.],
                [0.3, 2., 2.6],
                3.
            )
            .is_some()
        );
    }
    #[test]
    fn handles_roundtrip_including_signed_block() {
        let h = targets::Handle {
            selector: 0x10000342,
            block_id: -230,
        };
        let raw = handle(packed(h));
        assert_eq!(raw.selector.0, h.selector);
        assert_eq!(raw.block_id.0, h.block_id);
    }
    #[test]
    fn guest_session_and_pid_changes_clear_attack_watermarks_and_history() {
        for next in [message(20, 6), message(21, 5)] {
            let mut d = driver();
            d.observe_guest(&message(20, 5), 30, 4);
            d.ack.admit(&message(20, 5), 30, 2, 4);
            d.last_attack = 100;
            d.attacks.insert(100);
            d.hit_targets.insert((100, 7));
            history(&mut d);
            d.ack.consume(40);
            d.observe_guest(&next, 30, 4);
            assert_eq!(d.last_attack, 0);
            assert!(d.attacks.is_empty());
            assert!(d.hit_targets.is_empty());
            assert!(d.history.is_empty());
            assert_eq!(d.ack.session, 0);
            assert_eq!(d.ack.receipt, 0);
            assert_eq!(
                d.validate(&receipt(1, 7), &publication(), 1000),
                Err("target frame expired")
            );
            d.ack.admit(&next, 30, 2, 4);
            history(&mut d);
            assert!(d.validate(&receipt(1, 7), &publication(), 1000).is_ok());
        }
    }
    #[test]
    fn same_guest_preserves_watermarks_but_inactive_context_clears_them() {
        let mut d = driver();
        let mut m = message(20, 5);
        d.observe_guest(&m, 30, 4);
        d.last_attack = 10;
        history(&mut d);
        d.observe_guest(&m, 30, 4);
        assert_eq!(d.last_attack, 10);
        assert_eq!(d.history.len(), 1);
        m.flags = 0;
        d.observe_guest(&m, 30, 4);
        assert_eq!(d.last_attack, 0);
        assert!(d.history.is_empty());
        assert_eq!(d.guest_identity, None);
    }
    #[test]
    fn first_hit_requires_crosshair_sweeps_require_current_visibility_and_cannot_reopen_old_attacks()
     {
        let mut d = driver();
        history(&mut d);
        let current = publication();
        assert!(d.validate(&receipt(1, 7), &current, 1000).is_ok());
        assert_eq!(
            d.validate(&receipt(1, 9), &current, 1000),
            Err("target is not the crosshair selection")
        );
        d.attacks.insert(1);
        d.last_attack = 1;
        d.hit_targets.insert((1, 7));
        assert!(d.validate(&receipt(1, 9), &current, 1000).is_ok());
        assert_eq!(
            d.validate(&receipt(1, 7), &current, 1000),
            Err("target already consumed this attack")
        );
        let mut blocked = current.clone();
        blocked.targets[1].flags = 1;
        assert_eq!(
            d.validate(&receipt(1, 9), &blocked, 1000),
            Err("target changed or obscured")
        );
        d.attacks.insert(2);
        d.last_attack = 2;
        assert_eq!(
            d.validate(&receipt(1, 9), &current, 1000),
            Err("old attack counter")
        );
    }
    #[test]
    fn stale_history_changed_generation_and_expired_receipt_fail_closed() {
        let mut d = driver();
        history(&mut d);
        let mut current = publication();
        assert_eq!(
            d.validate(&receipt(1, 7), &current, 1250),
            Err("receipt expired")
        );
        let mut fresh = receipt(1, 7);
        fresh.timestamp = 1250;
        assert_eq!(
            d.validate(&fresh, &current, 1250),
            Err("target frame expired")
        );
        current.targets[0].generation = 9;
        assert_eq!(
            d.validate(&receipt(1, 7), &current, 1000),
            Err("target changed or obscured")
        );
    }
    #[test]
    fn turning_after_a_valid_swing_does_not_cancel_it_but_new_cover_does() {
        let mut d = driver();
        history(&mut d);
        let mut current = publication();
        d.status.nearest = None;
        current.forward = [1., 0., 0.];
        assert!(d.validate(&receipt(1, 7), &current, 1040).is_ok());
        current.targets[0].flags = 1;
        assert_eq!(
            d.validate(&receipt(1, 7), &current, 1040),
            Err("target changed or obscured")
        );
    }
    #[test]
    fn zero_or_uncertain_native_damage_is_rejected_without_retry() {
        let mut d = driver();
        let m = message(20, 5);
        d.observe_guest(&m, 30, 4);
        d.ack.admit(&m, 30, 2, 4);
        for (seq, result) in [(1, Some(0)), (2, None), (3, Some(-1))] {
            d.ack.consume(seq);
            assert!(!d.record_native_outcome(result));
            assert_eq!(d.ack.result, 2);
            assert_eq!(d.ack.receipt, seq);
        }
        assert_eq!(d.status.applied, 0);
        assert_eq!(d.status.rejected, 3);
        d.ack.consume(4);
        assert!(d.record_native_outcome(Some(1)));
        assert_eq!(d.ack.result, 1);
        assert_eq!(d.status.applied, 1);
    }
    #[test]
    fn rear_camera_does_not_shorten_player_reach_or_allow_remote_damage() {
        let mut d = driver();
        history(&mut d);
        let mut current = publication();
        current.camera = [0., 1.62, -4.];
        assert!(d.validate(&receipt(1, 7), &current, 1040).is_ok());
        current.targets[0].min[2] = 6.1;
        current.targets[0].max[2] = 7.;
        assert_eq!(
            d.validate(&receipt(1, 7), &current, 1040),
            Err("target left host melee ceiling")
        );
    }
}
