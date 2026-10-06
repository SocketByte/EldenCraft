//! Learns which Havok body material corresponds to which Elden Ring hit material.
//!
//! Elden Ring reports the hit material under the player's feet (HitMtrlParam row,
//! `ChrPhysicsMaterialInfo::hit_material`); terrain rays only report the hit body's
//! Havok material. While the player stands on a single-material body, the pair is
//! observed; repeated agreement makes it a table entry the guest can use for
//! surfaces nobody stood on (rock outcrops, cliffs, wooden structures).
use serde::Serialize;
use std::collections::BTreeMap;

/// Consistent observations before a pair is published.
pub const CONFIRMATIONS: u32 = 3;
const MAX_ENTRIES: usize = 256;

#[derive(Debug, Default)]
pub struct Learner {
    entries: BTreeMap<u16, (i32, u32)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct Pair {
    pub body_material: u16,
    pub hit_material: i32,
}

impl Learner {
    /// One standing observation. A contradicting hit material restarts the count.
    pub fn observe(&mut self, body_material: u16, hit_material: i32) {
        if body_material == crate::worldterrain::NO_MATERIAL || hit_material <= 0 {
            return;
        }
        if !self.entries.contains_key(&body_material) && self.entries.len() >= MAX_ENTRIES {
            return;
        }
        let entry = self
            .entries
            .entry(body_material)
            .or_insert((hit_material, 0));
        if entry.0 == hit_material {
            entry.1 = entry.1.saturating_add(1);
        } else {
            *entry = (hit_material, 1);
        }
    }
    pub fn table(&self) -> Vec<Pair> {
        self.entries
            .iter()
            .filter(|(_, (_, n))| *n >= CONFIRMATIONS)
            .map(|(m, (h, _))| Pair {
                body_material: *m,
                hit_material: *h,
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn publishes_only_confirmed_pairs_and_restarts_on_contradiction() {
        let mut l = Learner::default();
        for _ in 0..2 {
            l.observe(12, 2);
        }
        assert!(l.table().is_empty());
        l.observe(12, 2);
        assert_eq!(
            l.table(),
            vec![Pair {
                body_material: 12,
                hit_material: 2
            }]
        );
        l.observe(12, 5);
        assert!(
            l.table().is_empty(),
            "a contradiction restarts confirmation"
        );
        l.observe(crate::worldterrain::NO_MATERIAL, 2);
        l.observe(13, 0);
        assert!(l.table().is_empty(), "unknown materials are ignored");
    }
}
