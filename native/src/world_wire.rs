//! Bounded world snapshots and acknowledged gameplay events. No native pointers.
use serde::{Deserialize, Serialize};
pub const BYTES: usize = 2 * 1024 * 1024;
pub const HEADER: usize = 64;
pub const HOST_MAGIC: u32 = 0x48574345;
pub const GUEST_MAGIC: u32 = 0x47574345;
pub const VERSION: u32 = 1;
pub const FRESH_MS: u64 = 500;
pub const RECEIPT_MS: u64 = 1000;

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Block {
    pub key: String,
    pub state: String,
    pub boxes: Vec<[f64; 6]>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Mob {
    pub uuid: String,
    pub kind: String,
    pub position: [f64; 3],
    pub velocity: [f64; 3],
    pub hp: f32,
    pub max_hp: f32,
    pub radius: f32,
    pub height: f32,
}
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Event {
    pub seq: u64,
    pub kind: String,
    pub source: String,
    pub event: u64,
    pub time_ms: u64,
    pub observed_frame: u64,
    pub terrain_revision: u64,
    pub position: [f64; 3],
    #[serde(default)]
    pub radius: f32,
    pub target: u64,
    pub generation: u64,
    pub damage: f32,
    #[serde(default = "default_guest_hp")]
    pub guest_max_hp: f32,
    #[serde(default)]
    pub destination: Option<[f64; 3]>,
    #[serde(default)]
    pub projectile: String,
    #[serde(default)]
    pub projectile_kind: String,
    #[serde(default)]
    pub trajectory: Vec<[f64; 3]>,
    #[serde(default)]
    pub launch_frame: u64,
    #[serde(default)]
    pub launch_time_ms: u64,
}
fn default_guest_hp() -> f32 {
    20.0
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Guest {
    pub epoch: u64,
    pub map: u32,
    pub host_pid: u32,
    pub session: u64,
    pub observed_frame: u64,
    #[serde(default)]
    pub player_uuid: Option<String>,
    pub terrain_revision: u64,
    pub blocks_revision: u64,
    #[serde(default)]
    pub blocks: Vec<Block>,
    #[serde(default)]
    pub mobs: Vec<Mob>,
    #[serde(default)]
    pub fluids: Vec<crate::world_fluids::Contact>,
    #[serde(default)]
    pub events: Vec<Event>,
    #[serde(default)]
    pub ack_incoming: u64,
    #[serde(default)]
    pub projectiles: Vec<crate::projectile_flight::Flight>,
    #[serde(default)]
    pub flight: Option<crate::player_flight::Sample>,
    /// Torrent ridden by the paired player; absent or unmounted means on foot.
    #[serde(default)]
    pub torrent: Option<crate::torrent::Sample>,
    #[serde(default)]
    pub kinematics_active: bool,
    #[serde(default)]
    pub combat: Option<crate::combat_effects::Sample>,
    /// The player's actual Minecraft options, so the native camera matches them.
    #[serde(default)]
    pub view: Option<ViewSettings>,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct ViewSettings {
    pub mouse_sensitivity: f64,
    pub invert_x: bool,
    pub invert_y: bool,
    pub bob_view: bool,
    /// Minecraft's damageTiltStrength option (0..1); older guests omit it.
    #[serde(default = "full_tilt")]
    pub damage_tilt: f64,
}
fn full_tilt() -> f64 {
    1.0
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Target {
    pub id: u64,
    pub generation: u64,
    pub min: [f64; 3],
    pub max: [f64; 3],
    pub hp: i32,
    pub max_hp: i32,
    pub team: u8,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Ack {
    pub seq: u64,
    pub result: u32,
    pub delta: i32,
    pub reason: String,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Incoming {
    pub seq: u64,
    pub uuid: String,
    pub damage: f32,
    pub source: String,
    pub time_ms: u64,
}
#[derive(Clone, Debug)]
pub struct Envelope {
    pub frame: u64,
    pub timestamp: u64,
    pub pid: u32,
    pub active: bool,
    pub guest: Guest,
}
pub fn finite_position(p: [f64; 3]) -> bool {
    p.iter().all(|v| v.is_finite() && v.abs() < 1_000_000.)
}
pub fn valid_box(b: [f64; 6]) -> bool {
    b.iter().all(|v| v.is_finite() && v.abs() < 1_000_000.)
        && (0..3).all(|i| b[i] < b[i + 3] && b[i + 3] - b[i] <= 128.)
}
fn valid_identity(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_:-./".contains(&c))
}
impl Guest {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.epoch == 0
            || self.session == 0
            || self.session > i64::MAX as u64
            || self.host_pid == 0
            || self.observed_frame == 0
        {
            return Err("world identity invalid");
        }
        if self
            .player_uuid
            .as_ref()
            .is_some_and(|s| !valid_identity(s))
            || self.kinematics_active && self.player_uuid.is_none()
        {
            return Err("world player UUID invalid");
        }
        if self.flight.is_some_and(|s| !s.valid())
            || self.flight.is_some() && self.player_uuid.is_none()
        {
            return Err("world elytra sample invalid");
        }
        if self.torrent.is_some_and(|s| !s.valid())
            || self.torrent.is_some() && self.player_uuid.is_none()
        {
            return Err("world Torrent sample invalid");
        }
        if self.combat.is_some_and(|s| !s.valid())
            || self.combat.is_some() && (!self.kinematics_active || self.player_uuid.is_none())
        {
            return Err("world combat effects invalid");
        }
        if self.view.is_some_and(|v| {
            !v.mouse_sensitivity.is_finite()
                || !(0.0..=1.0).contains(&v.mouse_sensitivity)
                || !v.damage_tilt.is_finite()
                || !(0.0..=1.0).contains(&v.damage_tilt)
        }) {
            return Err("world view settings invalid");
        }
        if self.blocks.len() > 1024
            || self.mobs.len() > 64
            || self.fluids.len() > 64
            || self.events.len() > 128
            || self.projectiles.len() > 32
        {
            return Err("world snapshot count exceeded");
        }
        let mut fluid_ids = std::collections::HashSet::new();
        if self
            .fluids
            .iter()
            .any(|c| !c.valid() || !fluid_ids.insert(c.id))
            || !self.fluids.is_empty() && self.player_uuid.is_none()
        {
            return Err("world fluid contact invalid");
        }
        let mut projectile_ids = std::collections::HashSet::new();
        for p in &self.projectiles {
            if !valid_identity(&p.projectile)
                || !valid_identity(&p.projectile_kind)
                || !valid_identity(&p.source)
                || !projectile_ids.insert(&p.projectile)
                || p.launch_frame == 0
                || p.launch_time_ms == 0
                || !(2..=128).contains(&p.trajectory.len())
                || !p.trajectory.iter().copied().all(finite_position)
            {
                return Err("world active projectile invalid");
            }
        }
        let mut boxes = 0usize;
        let mut keys = std::collections::HashSet::new();
        for b in &self.blocks {
            boxes = boxes.saturating_add(b.boxes.len());
            if b.key.is_empty()
                || b.key.len() > 128
                || !keys.insert(&b.key)
                || !valid_identity(&b.state)
                || b.boxes.len() > 64
                || boxes > 4096
                || !b.boxes.iter().all(|b| valid_box(*b))
            {
                return Err("world block rejected");
            }
        }
        let mut uuids = std::collections::HashSet::new();
        for m in &self.mobs {
            if !valid_identity(&m.uuid)
                || !valid_identity(&m.kind)
                || !uuids.insert(&m.uuid)
                || !finite_position(m.position)
                || m.velocity.iter().any(|v| !v.is_finite() || v.abs() > 100.)
                || !m.hp.is_finite()
                || !m.max_hp.is_finite()
                || m.hp < 0.
                || !(1.0..=10000.).contains(&m.max_hp)
                || m.hp > m.max_hp
                || !m.radius.is_finite()
                || !(0.01..=16.).contains(&m.radius)
                || !m.height.is_finite()
                || !(0.01..=32.).contains(&m.height)
            {
                return Err("world mob rejected");
            }
        }
        let mut previous = 0;
        for e in &self.events {
            if e.seq == 0
                || e.seq <= previous
                || e.event == 0
                || !valid_identity(&e.source)
                || ![
                    "explosion",
                    "mob_melee",
                    "projectile",
                    "ender_pearl",
                    "environment",
                ]
                .contains(&e.kind.as_str())
                || e.target == 0
                || e.generation == 0
                || e.observed_frame == 0
                || e.time_ms == 0
                || !finite_position(e.position)
                || !e.damage.is_finite()
                || !(if e.kind == "ender_pearl" {
                    0.0..=5.0
                } else {
                    0.00001..=10000.0
                })
                .contains(&e.damage)
                || !e.radius.is_finite()
                || !(0.0..=32.).contains(&e.radius)
            {
                return Err("world event rejected");
            }
            if !e.guest_max_hp.is_finite() || !(1.0..=1024.).contains(&e.guest_max_hp) {
                return Err("world guest max HP invalid");
            }
            if e.kind == "ender_pearl"
                && (e.target != 1
                    || e.projectile_kind != "minecraft:ender_pearl"
                    || e.destination.is_none_or(|p| !finite_position(p)))
            {
                return Err("world pearl destination invalid");
            }
            if e.kind != "ender_pearl" && e.destination.is_some() {
                return Err("world unexpected destination");
            }
            if !e.projectile.is_empty() {
                if !valid_identity(&e.projectile)
                    || !valid_identity(&e.projectile_kind)
                    || e.launch_frame == 0
                    || e.launch_time_ms == 0
                    || !(2..=128).contains(&e.trajectory.len())
                    || !e.trajectory.iter().copied().all(finite_position)
                {
                    return Err("world projectile provenance invalid");
                }
            } else if e.kind == "ender_pearl"
                || !e.trajectory.is_empty()
                || e.launch_frame != 0
                || e.launch_time_ms != 0
                || !e.projectile_kind.is_empty()
            {
                return Err("world projectile provenance missing");
            }
            previous = e.seq;
        }
        Ok(())
    }
}
pub fn decode(bytes: &[u8], now: u64) -> Result<Envelope, &'static str> {
    if bytes.len() < HEADER {
        return Err("world header truncated");
    }
    let u32at = |i| u32::from_le_bytes(bytes[i..i + 4].try_into().unwrap());
    let u64at = |i| u64::from_le_bytes(bytes[i..i + 8].try_into().unwrap());
    let length = u32at(40) as usize;
    if u32at(0) != GUEST_MAGIC
        || u32at(4) != VERSION
        || u64at(8) == 0
        || u64at(8) & 1 != 0
        || u64at(16) == 0
        || u32at(32) == 0
        || u32at(36) & !1 != 0
        || length == 0
        || length > BYTES - HEADER
        || bytes.len() != HEADER + length
        || bytes[44..64].iter().any(|b| *b != 0)
    {
        return Err("world header invalid");
    }
    let timestamp = u64at(24);
    if timestamp == 0 || timestamp > now || now - timestamp > FRESH_MS {
        return Err("world publication stale");
    }
    let guest: Guest =
        serde_json::from_slice(&bytes[HEADER..]).map_err(|_| "world JSON rejected")?;
    guest.validate()?;
    Ok(Envelope {
        frame: u64at(16),
        timestamp,
        pid: u32at(32),
        active: u32at(36) == 1,
        guest,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn guest() -> Guest {
        Guest {
            epoch: 1,
            session: 1,
            host_pid: 1,
            observed_frame: 1,
            ..Guest::default()
        }
    }
    #[test]
    fn fluid_contacts_are_optional_bounded_and_require_paired_identity() {
        let c = crate::world_fluids::Contact {
            id: 10,
            generation: 2,
            medium: crate::world_fluids::Medium::Water,
            speed_scale: 1.,
            attack_bonus: 0.,
            position: [0.; 3],
            time_ms: 1000,
            observed_frame: 1,
        };
        let mut g = Guest {
            player_uuid: Some("owned-player".into()),
            fluids: vec![c],
            ..guest()
        };
        assert!(g.validate().is_ok());
        let decoded: Guest = serde_json::from_slice(&serde_json::to_vec(&g).unwrap()).unwrap();
        assert_eq!(decoded.fluids, vec![c]);
        g.fluids.push(c);
        assert!(g.validate().is_err());
        g.fluids.pop();
        g.player_uuid = None;
        assert!(g.validate().is_err());
        g.fluids.clear();
        assert!(g.validate().is_ok());
        assert!(serde_json::from_str::<Guest>(r#"{"epoch":1,"host_pid":1,"session":1,"observed_frame":1,"map":0,"terrain_revision":0,"blocks_revision":0}"#).unwrap().fluids.is_empty());
        g.fluids = (0..65)
            .map(|i| crate::world_fluids::Contact { id: i + 10, ..c })
            .collect();
        g.player_uuid = Some("owned-player".into());
        assert!(g.validate().is_err());
    }
    #[test]
    fn inactive_world_can_carry_fresh_kinematics_without_authorizing_world_damage() {
        let g = Guest {
            kinematics_active: true,
            player_uuid: Some("owned-player".into()),
            flight: Some(crate::player_flight::Sample {
                sequence: 1,
                time_ms: 1000,
                observed_frame: 1,
                gliding: true,
                travel: crate::player_flight::Travel::None,
                velocity: [1., 0., 0.],
            }),
            ..guest()
        };
        let body = serde_json::to_vec(&g).unwrap();
        let mut b = vec![0; HEADER + body.len()];
        b[0..4].copy_from_slice(&GUEST_MAGIC.to_le_bytes());
        b[4..8].copy_from_slice(&VERSION.to_le_bytes());
        b[8..16].copy_from_slice(&2u64.to_le_bytes());
        b[16..24].copy_from_slice(&1u64.to_le_bytes());
        b[24..32].copy_from_slice(&1000u64.to_le_bytes());
        b[32..36].copy_from_slice(&1u32.to_le_bytes());
        b[40..44].copy_from_slice(&(body.len() as u32).to_le_bytes());
        b[HEADER..].copy_from_slice(&body);
        let e = decode(&b, 1000).unwrap();
        assert!(!e.active);
        assert!(e.guest.kinematics_active);
        assert!(e.guest.flight.is_some());
        assert!(
            Guest {
                player_uuid: None,
                ..g
            }
            .validate()
            .is_err()
        );
        assert!(!guest().kinematics_active);
    }
    #[test]
    fn torrent_sample_requires_identity_and_the_paired_player() {
        let mounted = crate::torrent::Sample {
            sequence: 1,
            time_ms: 1000,
            observed_frame: 1,
            mounted: true,
        };
        let g = Guest {
            kinematics_active: true,
            player_uuid: Some("owned-player".into()),
            torrent: Some(mounted),
            ..guest()
        };
        assert!(g.validate().is_ok());
        let decoded: Guest = serde_json::from_slice(&serde_json::to_vec(&g).unwrap()).unwrap();
        assert_eq!(decoded.torrent, Some(mounted));
        assert!(
            Guest {
                player_uuid: None,
                kinematics_active: false,
                ..g.clone()
            }
            .validate()
            .is_err()
        );
        assert!(
            Guest {
                torrent: Some(crate::torrent::Sample {
                    sequence: 0,
                    ..mounted
                }),
                ..g
            }
            .validate()
            .is_err()
        );
        let legacy: Guest = serde_json::from_str(
            r#"{"epoch":1,"map":0,"host_pid":1,"session":1,"observed_frame":1,"terrain_revision":0,"blocks_revision":0}"#,
        )
        .unwrap();
        assert!(legacy.torrent.is_none());
    }
    #[test]
    fn scene_rejects_nonfinite_shapes_duplicate_entities_and_oversized_snapshots() {
        let mut g = guest();
        assert!(g.validate().is_ok());
        g.blocks.push(Block {
            key: "a".into(),
            state: "minecraft:stone".into(),
            boxes: vec![[0., 0., 0., 1., 1., 1.]],
        });
        assert!(g.validate().is_ok());
        g.blocks[0].boxes[0][3] = f64::NAN;
        assert!(g.validate().is_err());
        g.blocks.clear();
        g.mobs = vec![
            Mob {
                uuid: "same".into(),
                kind: "minecraft:creeper".into(),
                hp: 20.,
                max_hp: 20.,
                radius: 0.3,
                height: 1.7,
                ..Mob::default()
            };
            2
        ];
        assert!(g.validate().is_err());
    }
    #[test]
    fn world_envelope_rejects_stale_future_odd_and_truncated() {
        let body = serde_json::to_vec(&guest()).unwrap();
        let mut b = vec![0; HEADER + body.len()];
        b[0..4].copy_from_slice(&GUEST_MAGIC.to_le_bytes());
        b[4..8].copy_from_slice(&VERSION.to_le_bytes());
        b[8..16].copy_from_slice(&2u64.to_le_bytes());
        b[16..24].copy_from_slice(&1u64.to_le_bytes());
        b[24..32].copy_from_slice(&1000u64.to_le_bytes());
        b[32..36].copy_from_slice(&1u32.to_le_bytes());
        b[40..44].copy_from_slice(&(body.len() as u32).to_le_bytes());
        b[64..].copy_from_slice(&body);
        assert!(decode(&b, 1100).is_ok());
        assert!(decode(&b, 999).is_err());
        assert!(decode(&b, 1501).is_err());
        assert!(decode(&b[..b.len() - 1], 1000).is_err());
        b[8] = 3;
        assert!(decode(&b, 1000).is_err());
    }
    #[test]
    fn pearl_requires_owned_projectile_destination_and_bounded_resolved_self_damage() {
        let mut g = guest();
        g.events.push(Event {
            seq: 1,
            kind: "ender_pearl".into(),
            source: "player".into(),
            event: 1,
            time_ms: 100,
            observed_frame: 1,
            terrain_revision: 1,
            position: [2., 0., 0.],
            target: 1,
            generation: 1,
            damage: 0.,
            guest_max_hp: 20.,
            destination: Some([1.9, 0., 0.]),
            projectile: "pearl-uuid".into(),
            projectile_kind: "minecraft:ender_pearl".into(),
            trajectory: vec![[0., 1.6, 0.], [2., 0., 0.]],
            launch_frame: 1,
            launch_time_ms: 50,
            ..Default::default()
        });
        assert!(g.validate().is_ok());
        g.events[0].damage = 5.;
        assert!(g.validate().is_ok());
        g.events[0].damage = 5.01;
        assert!(g.validate().is_err());
        g.events[0].damage = 5.;
        g.events[0].destination = None;
        assert!(g.validate().is_err());
        g.events[0].destination = Some([1.9, 0., 0.]);
        g.events[0].projectile.clear();
        assert!(g.validate().is_err());
    }
    #[test]
    fn view_settings_are_optional_and_bounded() {
        let mut g = guest();
        assert!(g.validate().is_ok());
        g.view = Some(ViewSettings {
            mouse_sensitivity: 0.25,
            invert_x: false,
            invert_y: true,
            bob_view: true,
            damage_tilt: 1.0,
        });
        assert!(g.validate().is_ok());
        g.view = Some(ViewSettings {
            mouse_sensitivity: 1.5,
            ..g.view.unwrap()
        });
        assert!(g.validate().is_err());
        g.view = Some(ViewSettings {
            mouse_sensitivity: f64::NAN,
            ..g.view.unwrap()
        });
        assert!(g.validate().is_err());
    }
    #[test]
    fn environment_damage_carries_no_projectile_provenance() {
        let mut g = guest();
        g.events.push(Event {
            seq: 1,
            kind: "environment".into(),
            source: "player".into(),
            event: 1,
            time_ms: 100,
            observed_frame: 1,
            terrain_revision: 1,
            position: [0., 0., 0.],
            target: 1,
            generation: 1,
            damage: 4.,
            guest_max_hp: 20.,
            ..Default::default()
        });
        assert!(g.validate().is_ok());
        g.events[0].damage = 0.;
        assert!(g.validate().is_err(), "environment damage must be positive");
        g.events[0].damage = 4.;
        g.events[0].projectile_kind = "minecraft:arrow".into();
        assert!(g.validate().is_err(), "no projectile provenance");
        g.events[0].projectile_kind.clear();
        g.events[0].kind = "weather".into();
        assert!(g.validate().is_err(), "unknown kind");
    }
    #[test]
    fn active_flight_snapshots_bound_counts_points_and_reject_duplicate_ids() {
        let mut g = guest();
        let p = crate::projectile_flight::Flight {
            projectile: "arrow".into(),
            projectile_kind: "minecraft:arrow".into(),
            source: "player".into(),
            launch_frame: 1,
            launch_time_ms: 1,
            trajectory: vec![[0.; 3], [1.; 3]],
        };
        g.projectiles.push(p.clone());
        assert!(g.validate().is_ok());
        g.projectiles.push(p);
        assert!(g.validate().is_err());
        g.projectiles.pop();
        g.projectiles[0].trajectory = vec![[0.; 3]; 129];
        assert!(g.validate().is_err());
    }
}
