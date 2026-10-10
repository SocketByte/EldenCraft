//! Authored body/health-owner relationships, never inferred from shared models.
//!
//! Encounter identities and original event instructions:
//! https://github.com/thefifthmatt/SoulsRandomizers/blob/master/diste/Base/enemy.txt
//! https://github.com/thefifthmatt/SoulsRandomizers/blob/master/diste/Base/events.txt
//! Base-game NPC parameters also agree with gracenotes' enemy dataset.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    /// A disabled later phase owns the continuous health pool. Preserve 1 HP
    /// until it activates; the normal native processor owns its eventual death.
    Phase,
    /// A live controller owns health independently of its visible body.
    Pool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Actor {
    pub entity: u32,
    pub model: i32,
    pub param: i32,
    pub block: i32,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Link {
    #[allow(dead_code)] // Human-readable encounter name for the audit/regression inventory.
    pub name: &'static str,
    pub blocks: &'static [i32],
    pub body_entity: u32,
    pub body_model: i32,
    pub body_param: Option<i32>,
    pub owner_entity: u32,
    pub owner_model: i32,
    pub owner_param: Option<i32>,
    pub gauges: &'static [i32],
    pub kind: Kind,
    /// These bodies are explicitly immortal in the authored setup. Their live
    /// native immortality must also be observed before supplementing a clamp.
    pub immortal_body: bool,
}

impl Link {
    pub fn body_matches(self, body: Actor) -> bool {
        self.blocks.contains(&body.block)
            && body.entity == self.body_entity
            && body.model == self.body_model
            && self.body_param.is_none_or(|param| param == body.param)
    }

    pub fn owner_matches(self, body_block: i32, owner: Actor, gauge: i32) -> bool {
        owner.block == body_block
            && owner.entity == self.owner_entity
            && owner.model == self.owner_model
            && self.owner_param.is_none_or(|param| param == owner.param)
            && self.gauges.contains(&gauge)
    }
}

// Open-field characters can use the source tile or its containing 2x/4x block.
// Each permitted block names this same authored encounter, not a whole region.
const GIANT_BLOCKS: &[i32] = &[0x3c34_3400, 0x3c1a_1a01, 0x3c0d_0d02];
const AVATAR_BLOCKS: &[i32] = &[0x3d32_3000, 0x3d19_1801, 0x3d0c_0c02];

pub(crate) const LINKS: &[Link] = &[
    Link {
        name: "Godfrey / Hoarah Loux",
        blocks: &[0x0b05_0000],
        body_entity: 11050801,
        body_model: 4720,
        body_param: Some(47200070),
        owner_entity: 11050800,
        owner_model: 4721,
        owner_param: Some(47210070),
        gauges: &[904720000, 904720001],
        kind: Kind::Phase,
        immortal_body: true,
    },
    Link {
        name: "Beast Clergyman / Maliketh",
        blocks: &[0x0d00_0000],
        body_entity: 13000801,
        body_model: 2110,
        body_param: Some(21100072),
        owner_entity: 13000800,
        owner_model: 2110,
        owner_param: Some(21101072),
        gauges: &[902110000, 902110001],
        kind: Kind::Phase,
        immortal_body: true,
    },
    Link {
        name: "Fire Giant",
        blocks: GIANT_BLOCKS,
        body_entity: 1052520801,
        body_model: 4760,
        body_param: Some(47600050),
        owner_entity: 1052520800,
        owner_model: 4760,
        owner_param: Some(47601050),
        gauges: &[904760000],
        kind: Kind::Phase,
        immortal_body: true,
    },
    Link {
        name: "Promised Consort Radahn",
        blocks: &[0x1401_0000],
        body_entity: 20010801,
        body_model: 5220,
        body_param: None,
        owner_entity: 20010800,
        owner_model: 5220,
        owner_param: None,
        gauges: &[905220000, 905220001],
        kind: Kind::Phase,
        immortal_body: true,
    },
    Link {
        name: "Messmer",
        blocks: &[0x1501_0000],
        body_entity: 21010801,
        body_model: 5130,
        body_param: None,
        owner_entity: 21010800,
        owner_model: 5130,
        owner_param: None,
        gauges: &[905130000, 905130001],
        kind: Kind::Phase,
        immortal_body: true,
    },
    Link {
        name: "Dragonkin Soldier of Nokstella",
        blocks: &[0x0c01_0000],
        body_entity: 12010801,
        body_model: 4650,
        body_param: Some(46500060),
        owner_entity: 12010800,
        owner_model: 4650,
        owner_param: Some(46500960),
        gauges: &[904650000],
        kind: Kind::Pool,
        immortal_body: true,
    },
    Link {
        name: "Dragonkin Soldier of Nokstella",
        blocks: &[0x0c01_0000],
        body_entity: 12010802,
        body_model: 4650,
        body_param: Some(46500160),
        owner_entity: 12010800,
        owner_model: 4650,
        owner_param: Some(46500960),
        gauges: &[904650000],
        kind: Kind::Pool,
        immortal_body: true,
    },
    Link {
        name: "Godskin Duo (Apostle)",
        blocks: &[0x0d00_0000],
        body_entity: 13000851,
        body_model: 3560,
        body_param: None,
        owner_entity: 13000850,
        owner_model: 3560,
        owner_param: None,
        gauges: &[903575000],
        kind: Kind::Pool,
        immortal_body: false,
    },
    Link {
        name: "Godskin Duo (Noble)",
        blocks: &[0x0d00_0000],
        body_entity: 13000852,
        body_model: 3570,
        body_param: None,
        owner_entity: 13000850,
        owner_model: 3560,
        owner_param: None,
        gauges: &[903575000],
        kind: Kind::Pool,
        immortal_body: false,
    },
    Link {
        name: "Base Serpent Messmer",
        blocks: &[0x1501_0000],
        body_entity: 21010810,
        body_model: 5140,
        body_param: None,
        owner_entity: 21010800,
        owner_model: 5130,
        owner_param: None,
        gauges: &[905130001],
        kind: Kind::Pool,
        immortal_body: true,
    },
    Link {
        name: "Putrescent Knight",
        blocks: &[0x1600_0000],
        body_entity: 22000801,
        body_model: 5020,
        body_param: None,
        owner_entity: 22000800,
        owner_model: 5020,
        owner_param: None,
        gauges: &[905020000],
        kind: Kind::Pool,
        immortal_body: false,
    },
    // The Avatar switches among three separately registered phase gauges.
    // The event's initial pair is 0802 -> 0812; its alternate bar setup selects
    // 0811 for body 0801 and 0810 for body 0800. Never cross these phase slots.
    Link {
        name: "Scadutree Avatar (opening)",
        blocks: AVATAR_BLOCKS,
        body_entity: 2050480802,
        body_model: 5230,
        body_param: None,
        owner_entity: 2050480812,
        owner_model: 5230,
        owner_param: None,
        gauges: &[905230001],
        kind: Kind::Pool,
        immortal_body: true,
    },
    Link {
        name: "Scadutree Avatar (middle)",
        blocks: AVATAR_BLOCKS,
        body_entity: 2050480801,
        body_model: 5230,
        body_param: None,
        owner_entity: 2050480811,
        owner_model: 5230,
        owner_param: None,
        gauges: &[905230002],
        kind: Kind::Pool,
        immortal_body: true,
    },
    Link {
        name: "Scadutree Avatar (final)",
        blocks: AVATAR_BLOCKS,
        body_entity: 2050480800,
        body_model: 5230,
        body_param: None,
        owner_entity: 2050480810,
        owner_model: 5230,
        owner_param: None,
        gauges: &[905230000],
        kind: Kind::Pool,
        immortal_body: false,
    },
];

pub(crate) fn body_link(body: Actor) -> Option<Link> {
    LINKS.iter().copied().find(|link| link.body_matches(body))
}

/// An invulnerable active boss is not a dormant phase. Neither hit-disable nor
/// a bad delta-time sample authorizes direct changes to its health pool.
pub(crate) fn dormant_phase(rejection: Option<&str>) -> bool {
    matches!(
        rejection,
        Some("target_inactive" | "target_tasks_unregistered" | "target_character_disabled")
    )
}

fn unique_pool_health<H: PartialEq>(
    kind: Kind,
    mut owners: impl Iterator<Item = (H, i32, i32)>,
) -> Option<(i32, i32)> {
    if kind != Kind::Pool {
        return None;
    }
    let first = owners.next()?;
    (first.1 > 0 && first.2 >= first.1 && owners.all(|other| other == first))
        .then_some((first.1, first.2))
}

#[cfg(windows)]
pub(crate) fn actor(chr: &eldenring::cs::ChrIns) -> Actor {
    Actor {
        entity: chr.event_entity_id,
        model: chr.npc_id,
        param: chr.npc_param_id,
        block: chr.field_ins_handle.block_id.0,
    }
}

/// Read only on the authorized native task. Exact live registration proves a
/// linked body's boss role even when that body is not itself in the bar roster.
#[cfg(windows)]
pub(crate) fn registered_body(chr: &eldenring::cs::ChrIns) -> bool {
    use eldenring::cs::{CSFeManImp, WorldChrMan};
    use fromsoftware_shared::FromStatic;
    let Some(link) = body_link(actor(chr)) else {
        return false;
    };
    let Ok(frontend) = (unsafe { CSFeManImp::instance() }) else {
        return false;
    };
    let Ok(world) = (unsafe { WorldChrMan::instance() }) else {
        return false;
    };
    frontend.boss_health_displays.iter().any(|display| {
        display.field_ins_handle != chr.field_ins_handle
            && world
                .chr_ins_by_handle(&display.field_ins_handle)
                .is_some_and(|owner| {
                    owner.field_ins_handle == display.field_ins_handle
                        && !owner.chr_flags1c5.death_flag()
                        && link.owner_matches(
                            chr.field_ins_handle.block_id.0,
                            actor(owner),
                            display.fmg_id,
                        )
                })
    })
}

/// Shared-health bodies publish the live controller's health to Minecraft.
/// Otherwise an immortal body at 1 HP would cap every vanilla damage receipt
/// at 1 HP even while its real pool is healthy. Spatial identity stays the body.
#[cfg(windows)]
pub(crate) fn published_health(chr: &eldenring::cs::ChrIns) -> Option<(i32, i32)> {
    use eldenring::cs::{CSFeManImp, WorldChrMan};
    use fromsoftware_shared::FromStatic;
    let link = body_link(actor(chr))?;
    if link.kind != Kind::Pool {
        return None;
    }
    let frontend = unsafe { CSFeManImp::instance() }.ok()?;
    let world = unsafe { WorldChrMan::instance() }.ok()?;
    let matched = frontend.boss_health_displays.iter().filter_map(|display| {
        let owner = world.chr_ins_by_handle(&display.field_ins_handle)?;
        if owner.field_ins_handle != display.field_ins_handle
            || owner.field_ins_handle == chr.field_ins_handle
            || !link.owner_matches(
                chr.field_ins_handle.block_id.0,
                actor(owner),
                display.fmg_id,
            )
            || crate::combat_targets::readiness_rejection(owner).is_some()
        {
            return None;
        }
        Some((
            owner.field_ins_handle,
            owner.modules.data.hp,
            owner.modules.data.max_hp,
        ))
    });
    unique_pool_health(link.kind, matched)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn body(link: Link, block: i32) -> Actor {
        Actor {
            entity: link.body_entity,
            model: link.body_model,
            param: link.body_param.unwrap_or(0),
            block,
        }
    }
    fn owner(link: Link, block: i32) -> Actor {
        Actor {
            entity: link.owner_entity,
            model: link.owner_model,
            param: link.owner_param.unwrap_or(0),
            block,
        }
    }

    #[test]
    fn every_authored_link_requires_its_exact_body_owner_block_and_gauge() {
        for link in LINKS.iter().copied() {
            for &block in link.blocks {
                let body = body(link, block);
                let owner = owner(link, block);
                assert_eq!(body_link(body).unwrap().name, link.name);
                for &gauge in link.gauges {
                    assert!(link.owner_matches(block, owner, gauge));
                }
                assert!(
                    body_link(Actor {
                        entity: body.entity + 100,
                        ..body
                    })
                    .is_none()
                );
                assert!(
                    body_link(Actor {
                        model: body.model + 100,
                        ..body
                    })
                    .is_none()
                );
                assert!(body_link(Actor { block: -1, ..body }).is_none());
                if link.body_param.is_some() {
                    assert!(
                        body_link(Actor {
                            param: body.param + 1,
                            ..body
                        })
                        .is_none()
                    );
                }
                for unrelated in [
                    Actor {
                        entity: owner.entity + 100,
                        ..owner
                    },
                    Actor {
                        model: owner.model + 100,
                        ..owner
                    },
                    Actor {
                        block: block + 100,
                        ..owner
                    },
                ] {
                    assert!(!link.owner_matches(block, unrelated, link.gauges[0]));
                }
                assert!(!link.owner_matches(block, owner, 1));
                if link.owner_param.is_some() {
                    assert!(!link.owner_matches(
                        block,
                        Actor {
                            param: owner.param + 1,
                            ..owner
                        },
                        link.gauges[0]
                    ));
                }
            }
        }
    }

    #[test]
    fn independent_phase_bars_and_same_actor_transformations_never_share_health() {
        for (block, entity, model) in [
            (0x0a00_0000, 10000800, 4750),   // Godrick: same actor.
            (0x0b00_0000, 11000800, 2130),   // Morgott.
            (0x3c0d_0902, 1052380800, 4730), // Starscourge Radahn.
            (0x0c05_0000, 12050800, 4800),   // Mohg.
            (0x0c03_0000, 12030850, 4511),   // Fortissax.
            (0x0d00_0000, 13000830, 4520),   // Placidusax.
            (0x0c09_0000, 12090800, 4670),   // Regal Ancestor Spirit.
            (0x3c33_3900, 1051570800, 3050), // Commander Niall.
            (0x0e00_0000, 14000801, 2030),   // Rennala: independent phase bars.
            (0x0e00_0000, 14000800, 2031),
            (0x1000_0000, 16000801, 4710), // Serpent / Rykard: independent bars.
            (0x1000_0000, 16000800, 4710),
            (0x1300_0000, 19000810, 2190), // Radagon / Beast: independent bars.
            (0x1300_0000, 19000800, 2200),
            (0x0f00_0000, 15000800, 2120), // Malenia: same actor, native HP reset.
            (0x0f00_0000, 15000801, 2120), // Scarlet phantom is not a pool owner.
            (0x0b00_0000, 11000850, 4720), // Golden shade.
        ] {
            assert!(
                body_link(Actor {
                    block,
                    entity,
                    model,
                    param: 0
                })
                .is_none()
            );
        }
    }

    #[test]
    fn invulnerability_death_and_stale_samples_never_count_as_dormant_phases() {
        for reason in [
            "target_inactive",
            "target_tasks_unregistered",
            "target_character_disabled",
        ] {
            assert!(dormant_phase(Some(reason)));
        }
        for reason in [
            "target_dead",
            "target_invincible",
            "target_hit_disabled",
            "target_delta_time_invalid",
            "target_health_invalid",
        ] {
            assert!(!dormant_phase(Some(reason)));
        }
        assert!(!dormant_phase(None));
    }

    #[test]
    fn shared_pool_publication_uses_live_owner_health_instead_of_the_body_one_hp_floor() {
        assert_eq!(
            unique_pool_health(Kind::Pool, [(92, 12000, 16000)].into_iter()),
            Some((12000, 16000))
        );
        assert_eq!(
            unique_pool_health(Kind::Pool, [(92, 12000, 16000); 2].into_iter()),
            Some((12000, 16000))
        );
        assert_eq!(
            unique_pool_health(Kind::Phase, [(92, 12000, 16000)].into_iter()),
            None
        );
        for owners in [
            vec![],
            vec![(92, 0, 16000)],
            vec![(92, -1, 16000)],
            vec![(92, 16001, 16000)],
            vec![(92, 12000, 16000), (93, 12000, 16000)],
            vec![(92, 12000, 16000), (92, 11000, 16000)],
            vec![(92, 12000, 16000), (92, 12000, 17000)],
        ] {
            assert_eq!(unique_pool_health(Kind::Pool, owners.into_iter()), None);
        }
    }
}
