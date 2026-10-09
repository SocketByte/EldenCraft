//! Fresh Minecraft fluid contacts for the exact live native enemy generation.
use crate::world_wire::{Target, finite_position};
use serde::{Deserialize, Serialize};
pub const FRESH_MS: u64 = 150;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Medium {
    None,
    Water,
    Lava,
}
impl Medium {
    pub fn horizontal_scale(self) -> f32 {
        match self {
            Self::None => 1.,
            Self::Water => 0.5,
            Self::Lava => 0.25,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Contact {
    pub id: u64,
    pub generation: u64,
    pub medium: Medium,
    pub position: [f64; 3],
    pub time_ms: u64,
    pub observed_frame: u64,
    #[serde(default = "full_speed")]
    pub speed_scale: f32,
    #[serde(default)]
    pub attack_bonus: f32,
}
fn full_speed() -> f32 {
    1.
}
impl Contact {
    pub fn valid(self) -> bool {
        self.id > 1
            && self.id <= i64::MAX as u64
            && self.generation > 0
            && self.time_ms > 0
            && self.observed_frame > 0
            && finite_position(self.position)
            && self.speed_scale.is_finite()
            && (0. ..=3.).contains(&self.speed_scale)
            && self.attack_bonus.is_finite()
            && (-20. ..=15.).contains(&self.attack_bonus)
    }
    pub fn fresh(self, now: u64) -> bool {
        self.valid() && self.time_ms <= now && now - self.time_ms <= FRESH_MS
    }
    pub fn matches(self, target: &Target) -> bool {
        self.id == target.id
            && self.generation == target.generation
            && target.hp > 0
            && (0..3).all(|i| {
                self.position[i] >= target.min[i] - 1. && self.position[i] <= target.max[i] + 1.
            })
    }
}
/// A copied identity token is compared with the current stage's owner. It is
/// never dereferenced and never transported to Minecraft.
#[derive(Clone, Copy, Debug)]
pub struct Owned {
    pub contact: Contact,
    pub instance: usize,
}
impl Owned {
    pub fn scale(self, now: u64, id: u64, instance: usize) -> Option<f32> {
        (self.contact.fresh(now)
            && self.contact.id == id
            && self.instance == instance
            && instance != 0)
            .then(|| self.contact.medium.horizontal_scale() * self.contact.speed_scale)
    }
}
static EFFECTS: std::sync::Mutex<Vec<Owned>> = std::sync::Mutex::new(Vec::new());
pub fn publish(contacts: &[Owned]) {
    if let Ok(mut current) = EFFECTS.try_lock() {
        *current = contacts.iter().copied().take(64).collect();
    }
}
pub fn attack_bonus(now: u64, id: u64, instance: usize) -> f64 {
    EFFECTS
        .try_lock()
        .ok()
        .and_then(|contacts| {
            contacts
                .iter()
                .find(|c| c.scale(now, id, instance).is_some())
                .map(|c| c.contact.attack_bonus as f64)
        })
        .unwrap_or(0.)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn contact() -> Contact {
        Contact {
            id: 10,
            generation: 2,
            medium: Medium::Water,
            position: [0.; 3],
            time_ms: 1000,
            observed_frame: 3,
            speed_scale: 1.,
            attack_bonus: 0.,
        }
    }
    #[test]
    fn freshness_and_actor_identity_release_slowdown() {
        let c = contact();
        let o = Owned {
            contact: c,
            instance: 20,
        };
        assert_eq!(o.scale(1150, 10, 20), Some(0.5));
        for (now, id, ptr) in [
            (999, 10, 20),
            (1151, 10, 20),
            (1000, 11, 20),
            (1000, 10, 21),
            (1000, 10, 0),
        ] {
            assert_eq!(o.scale(now, id, ptr), None);
        }
        assert_eq!(Medium::Lava.horizontal_scale(), 0.25);
    }
    #[test]
    fn contact_requires_alive_matching_generation_and_position() {
        let c = contact();
        let t = Target {
            id: 10,
            generation: 2,
            min: [-0.3, 0., -0.3],
            max: [0.3, 1.8, 0.3],
            hp: 100,
            ..Target::default()
        };
        assert!(c.matches(&t));
        for t in [
            Target {
                generation: 3,
                ..t.clone()
            },
            Target { hp: 0, ..t.clone() },
            Target {
                min: [5.; 3],
                max: [6.; 3],
                ..t
            },
        ] {
            assert!(!c.matches(&t));
        }
        assert!(
            !Contact {
                position: [f64::NAN, 0., 0.],
                ..c
            }
            .valid()
        );
        assert!(!Contact { id: 1, ..c }.valid());
    }
    #[test]
    fn debuffs_combine_with_fluids_and_release_with_the_exact_actor_lease() {
        let c = Contact {
            speed_scale: 0.4,
            attack_bonus: -4.,
            ..contact()
        };
        let owned = Owned {
            contact: c,
            instance: 20,
        };
        assert_eq!(owned.scale(1000, 10, 20), Some(0.2));
        publish(&[owned]);
        assert_eq!(attack_bonus(1000, 10, 20), -4.);
        assert_eq!(attack_bonus(1151, 10, 20), 0.);
        assert_eq!(attack_bonus(1000, 10, 21), 0.);
        publish(&[]);
        assert_eq!(attack_bonus(1000, 10, 20), 0.);
        for c in [
            Contact {
                speed_scale: f32::NAN,
                ..c
            },
            Contact {
                attack_bonus: -21.,
                ..c
            },
        ] {
            assert!(!c.valid());
        }
    }
}
