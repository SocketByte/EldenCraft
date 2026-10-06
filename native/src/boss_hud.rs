//! Bounded observations of the native frontend's actual boss health gauges.
//! No target selection, distance tests or enemy classification is involved.
use serde::Serialize;
use std::collections::BTreeSet;

pub const MAX_BOSSES: usize = 3;
const MAX_NAME_UNITS: usize = 256;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Boss {
    pub id: String,
    pub name: String,
    pub hp: i32,
    pub max_hp: i32,
}

#[derive(Clone, Debug, Serialize)]
pub struct Diagnostics {
    pub status: String,
    pub updates_disabled: bool,
    pub hud_state: String,
    pub displays: Vec<DisplayDiagnostic>,
    pub gauges: Vec<GaugeDiagnostic>,
}
#[derive(Clone, Debug, Serialize)]
pub struct DisplayDiagnostic {
    pub slot: usize,
    pub fmg_id: i32,
    pub handle: String,
    pub matching_gauge: Option<usize>,
    pub source_resolved: bool,
    pub source_active: bool,
    pub source_dead: bool,
    pub source_hp: i32,
    pub source_max_hp: i32,
    pub name_source: String,
    pub name_lookup: Option<crate::boss_fmg::LookupDiagnostic>,
    pub outcome: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct GaugeDiagnostic {
    pub slot: usize,
    pub visible: bool,
    pub handle: String,
    pub hp: u32,
    pub hp_max_uncapped: u32,
    pub hp_max_uncapped_difference: u32,
    pub name_available: bool,
}
pub struct Observation {
    pub bosses: Vec<Boss>,
    pub diagnostics: Diagnostics,
    /// Copied identities of gauges backed by the same admitted source as the
    /// published boss. Visibility never participates in source admission.
    pub sources: Vec<ReplacementSource>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceIdentity {
    pub frontend: usize,
    pub display_slot: usize,
    pub gauge_slot: usize,
    pub fmg_id: i32,
    pub handle: eldenring::cs::FieldInsHandle,
    pub source: usize,
}

pub struct ReplacementSource {
    pub id: String,
    pub identity: SourceIdentity,
}
impl Observation {
    fn unavailable(status: &str) -> Self {
        Self {
            bosses: Vec::new(),
            sources: Vec::new(),
            diagnostics: Diagnostics {
                status: status.into(),
                updates_disabled: false,
                hud_state: String::new(),
                displays: Vec::new(),
                gauges: Vec::new(),
            },
        }
    }
}

pub(crate) fn name_from_units(units: &[u16]) -> Option<String> {
    if units.len() > MAX_NAME_UNITS {
        return None;
    }
    let decoded = String::from_utf16(units).ok()?;
    let name = decoded.split_whitespace().collect::<Vec<_>>().join(" ");
    (!name.is_empty() && !name.chars().any(char::is_control)).then_some(name)
}

/// The dedicated native boss registration, not frontend rendering state, owns
/// the encounter. A live current source is still mandatory.
fn admitted(boss: Boss, registered: bool, source_current: bool) -> Option<Boss> {
    (registered
        && source_current
        && !boss.id.is_empty()
        && !boss.name.is_empty()
        && boss.hp > 0
        && boss.max_hp > 0
        && boss.hp <= boss.max_hp)
        .then_some(boss)
}

fn bounded(observations: impl IntoIterator<Item = Boss>) -> Vec<Boss> {
    let mut ids = BTreeSet::new();
    observations
        .into_iter()
        .filter(|boss| ids.insert(boss.id.clone()))
        .take(MAX_BOSSES)
        .collect()
}

fn display_name(native: Option<String>, frontend: Option<String>) -> (String, &'static str) {
    if let Some(name) = native.filter(|name| !name.is_empty()) {
        (name, "msg_repository")
    } else if let Some(name) = frontend.filter(|name| !name.is_empty()) {
        (name, "frontend")
    } else {
        // A missing/unloaded localized archive must never hide a verified
        // native encounter's HP bar. Keep the source identity and health.
        ("Boss".into(), "fallback")
    }
}

#[cfg(windows)]
unsafe fn menu_name(value: &eldenring::cs::MenuString) -> Option<String> {
    if !value.allocated_string.is_empty() {
        if value.allocated_string.len() > MAX_NAME_UNITS {
            return None;
        }
        return name_from_units(value.allocated_string.as_code_units());
    }
    if value.static_string.is_null() || !(value.static_string as usize).is_multiple_of(2) {
        return None;
    }
    // This is the SDK's documented UTF-16 static MenuString, copied on the
    // game task. Bound the scan rather than use its unbounded Display reader.
    let mut units = Vec::new();
    for i in 0..=MAX_NAME_UNITS {
        let unit = unsafe { value.static_string.add(i).read() };
        if unit == 0 {
            return name_from_units(&units);
        }
        units.push(unit);
    }
    None
}

/// Read-only; call on the same verified offline game task as campaign sampling.
#[cfg(windows)]
pub unsafe fn sample() -> Observation {
    use eldenring::cs::{CSFeManImp, WorldChrMan};
    use fromsoftware_shared::FromStatic;
    let Ok(frontend) = (unsafe { CSFeManImp::instance() }) else {
        return Observation::unavailable("frontend_unavailable");
    };
    let Ok(world) = (unsafe { WorldChrMan::instance() }) else {
        return Observation::unavailable("world_unavailable");
    };
    let tags = &frontend.frontend_values.boss_list_tag_data;
    let gauges = tags
        .iter()
        .enumerate()
        .map(|(slot, gauge)| GaugeDiagnostic {
            slot,
            visible: gauge.is_visible,
            handle: gauge.field_ins_handle.to_string(),
            hp: gauge.hp,
            hp_max_uncapped: gauge.hp_max_uncapped,
            hp_max_uncapped_difference: gauge.hp_max_uncapped_difference,
            name_available: !gauge.chr_name.allocated_string.is_empty()
                || !gauge.chr_name.static_string.is_null(),
        })
        .collect();
    let mut diagnostics = Diagnostics {
        status: if frontend.disable_updates {
            "updates_disabled"
        } else {
            "sampled"
        }
        .into(),
        updates_disabled: frontend.disable_updates,
        hud_state: format!("{:?}", frontend.hud_state),
        displays: Vec::new(),
        gauges,
    };
    let mut observations = Vec::new();
    let mut sources = Vec::new();
    for (slot, display) in frontend.boss_health_displays.iter().enumerate() {
        let source = (!display.field_ins_handle.is_empty())
            .then(|| world.chr_ins_by_handle(&display.field_ins_handle))
            .flatten();
        let source_active = source.is_some_and(|chr| chr.chr_flags1c8.is_active());
        let source_dead =
            source.is_some_and(|chr| chr.chr_flags1c5.death_flag() || chr.modules.data.hp <= 0);
        let mut debug = DisplayDiagnostic {
            slot,
            fmg_id: display.fmg_id,
            handle: display.field_ins_handle.to_string(),
            matching_gauge: tags.iter().position(|tag| {
                !display.field_ins_handle.is_empty()
                    && tag.field_ins_handle == display.field_ins_handle
            }),
            source_resolved: source.is_some(),
            source_active,
            source_dead,
            source_hp: source.map(|chr| chr.modules.data.hp).unwrap_or(0),
            source_max_hp: source.map(|chr| chr.modules.data.max_hp).unwrap_or(0),
            name_source: String::new(),
            name_lookup: None,
            outcome: String::new(),
        };
        let result = (|| {
            if display.fmg_id <= 0 || display.field_ins_handle.is_empty() {
                return Err("empty_native_display");
            }
            let source = source.ok_or("source_unresolved")?;
            let current = source.field_ins_handle == display.field_ins_handle
                && source_active
                && !source_dead;
            let hp = source.modules.data.hp;
            let max_hp = source.modules.data.max_hp;
            let native_lookup = unsafe { crate::boss_fmg::npc_name(display.fmg_id) };
            let native_name = native_lookup.name;
            debug.name_lookup = Some(native_lookup.diagnostic);
            let frontend_name = native_name
                .is_none()
                .then(|| {
                    debug
                        .matching_gauge
                        .and_then(|index| unsafe { menu_name(&tags[index].chr_name) })
                })
                .flatten();
            let (name, name_source) = display_name(native_name, frontend_name);
            debug.name_source = name_source.into();
            admitted(
                Boss {
                    id: format!(
                        "{}-{:x}-{}",
                        display.field_ins_handle, source as *const _ as usize, display.fmg_id
                    ),
                    name,
                    hp,
                    max_hp,
                },
                true,
                current,
            )
            .ok_or("source_or_health_invalid")
        })();
        match result {
            Ok(boss) => {
                debug.outcome = "published".into();
                // A native registration can feed more than one frontend tag.
                // Keep each exact tag identity; never suppress unmatched slots.
                for (gauge_slot, tag) in tags.iter().enumerate() {
                    if tag.field_ins_handle == display.field_ins_handle {
                        sources.push(ReplacementSource {
                            id: boss.id.clone(),
                            identity: SourceIdentity {
                                frontend: frontend as *const CSFeManImp as usize,
                                display_slot: slot,
                                gauge_slot,
                                fmg_id: display.fmg_id,
                                handle: display.field_ins_handle,
                                source: source.unwrap() as *const _ as usize,
                            },
                        });
                    }
                }
                observations.push(boss);
            }
            Err(reason) => debug.outcome = reason.into(),
        }
        diagnostics.displays.push(debug);
    }
    let bosses = bounded(observations);
    sources.retain(|source| bosses.iter().any(|boss| boss.id == source.id));
    Observation {
        bosses,
        diagnostics,
        sources,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn boss(id: &str) -> Boss {
        Boss {
            id: id.into(),
            name: "Margit, the Fell Omen".into(),
            hp: 100,
            max_hp: 400,
        }
    }
    #[test]
    fn unregistered_stale_dead_and_inconsistent_sources_do_not_publish() {
        assert!(admitted(boss("live"), true, true).is_some());
        assert!(admitted(boss("unregistered"), false, true).is_none());
        assert!(admitted(boss("stale"), true, false).is_none());
        let mut dead = boss("dead");
        dead.hp = 0;
        assert!(admitted(dead, true, true).is_none());
        let mut inconsistent = boss("invalid");
        inconsistent.hp = 401;
        assert!(admitted(inconsistent, true, true).is_none());
    }
    #[test]
    fn native_slots_are_bounded_and_duplicate_instances_are_not_repeated() {
        let result = bounded([
            boss("phase1"),
            boss("phase1"),
            boss("phase2"),
            boss("other"),
            boss("overflow"),
        ]);
        assert_eq!(
            result.iter().map(|b| b.id.as_str()).collect::<Vec<_>>(),
            ["phase1", "phase2", "other"]
        );
    }
    #[test]
    fn localized_names_are_bounded_and_decoded_without_truncating_utf16_pairs() {
        let units: Vec<_> = "  呪いの王\nMorgott 🐲  ".encode_utf16().collect();
        assert_eq!(
            name_from_units(&units).as_deref(),
            Some("呪いの王 Morgott 🐲")
        );
        assert!(name_from_units(&[0xd800]).is_none());
        assert!(name_from_units(&[65; MAX_NAME_UNITS + 1]).is_none());
        assert!(name_from_units(&[0]).is_none());
    }
    #[test]
    fn serialized_boss_transport_preserves_native_hp_and_phase_identity() {
        let first = boss("native-instance-fmg-1");
        let second = boss("native-instance-fmg-2");
        assert_ne!(first.id, second.id);
        let value = serde_json::to_value(first).unwrap();
        assert_eq!(value["max_hp"], 400);
        assert_eq!(value["hp"], 100);
        assert_eq!(value["name"], "Margit, the Fell Omen");
    }
    #[test]
    fn missing_names_keep_the_registered_live_health_bar_available() {
        let (name, source) = display_name(None, None);
        let mut live = boss("registered-native-source");
        live.name = name;
        assert_eq!(source, "fallback");
        let published = admitted(live, true, true).unwrap();
        assert_eq!(published.name, "Boss");
        assert_eq!(published.hp, 100);
        assert_eq!(published.max_hp, 400);
        assert_eq!(
            display_name(Some("Tree Sentinel".into()), None).1,
            "msg_repository"
        );
        assert_eq!(
            display_name(None, Some("Tree Sentinel".into())).1,
            "frontend"
        );
    }
}
