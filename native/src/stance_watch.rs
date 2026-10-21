//! Diagnostic only: what the game itself does to a registered boss around the player's jumps.
//!
//! Every frame it looks at each boss with a health bar and writes a log line whenever something
//! that decides a stagger changes: the game's own stance dropping, its toughness dropping, the
//! damage level of the last attack, the forced-break flag, or the animation it is playing. Each line
//! carries the player's height speed and ground contact beside it, so a stagger that happens when
//! the player jumps shows up with its cause. It reads and never writes, and it is bounded to a few
//! dozen lines a second.
use crate::stance::Real;
use eldenring::cs::{CSFeManImp, PlayerIns, WorldChrMan};
use fromsoftware_shared::FromStatic;
use std::{collections::HashMap, sync::Mutex, time::Instant};

/// Bosses further away than this are not worth a line.
const RADIUS_M: f32 = 60.;
const MAX_LINES_PER_SECOND: u32 = 40;

#[derive(Default)]
struct Watch {
    bosses: HashMap<u64, Real>,
    /// Height, ground contact and time of the previous sample of the player.
    player: Option<(f32, u32, u64)>,
    /// Whole second the line budget belongs to, lines written in it, lines dropped since.
    budget: (u64, u32, u32),
}

static WATCH: Mutex<Option<Watch>> = Mutex::new(None);

fn now_ms() -> u64 {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

/// What changed between two looks at the same boss that could matter for a stagger.
fn changes(before: &Real, after: &Real) -> Vec<&'static str> {
    let mut changed = Vec::new();
    // Regeneration raises these every frame, which is no news; only drops and the break are.
    if before.stance - after.stance >= 1. || (before.stance > 0.) != (after.stance > 0.) {
        changed.push("stance");
    }
    if before.toughness - after.toughness >= 1. {
        changed.push("toughness");
    }
    if before.damage_level != after.damage_level {
        changed.push("damage_level");
    }
    if before.forced_break != after.forced_break {
        changed.push("forced_break");
    }
    if before.anim != after.anim {
        changed.push("anim");
    }
    if before.broken_lock != after.broken_lock {
        changed.push("broken_lock");
    }
    changed
}

fn describe(changed: &[&str], real: &Real) -> String {
    format!(
        "changed={changed:?} stance {:.1}/{:.1} toughness {:.1} damage_level {} hyper_armor {} \
         forced_break {} anim {} broken_lock {}",
        real.stance,
        real.stance_max,
        real.toughness,
        real.damage_level,
        real.hyper_armor,
        real.forced_break,
        real.anim,
        real.broken_lock
    )
}

/// Takes one line from this second's budget, or counts it as dropped.
fn admit(budget: &mut (u64, u32, u32), now: u64) -> Option<u32> {
    let second = now / 1000;
    let mut dropped = 0;
    if budget.0 != second {
        dropped = std::mem::take(&mut budget.2);
        *budget = (second, 0, 0);
    }
    if budget.1 >= MAX_LINES_PER_SECOND {
        budget.2 += 1;
        return None;
    }
    budget.1 += 1;
    Some(dropped)
}

/// # Safety
/// Game task, with no concurrent game writer and no retained SDK references.
pub unsafe fn sample() {
    let Ok(mut guard) = WATCH.try_lock() else {
        return;
    };
    if crate::campaign_runtime::stance_rules().is_none() {
        *guard = None;
        crate::hit_watch::set_enabled(false);
        return;
    }
    crate::hit_watch::set_enabled(true);
    let watch = guard.get_or_insert_with(Watch::default);
    let (Ok(player), Ok(world), Ok(frontend)) = (
        unsafe { PlayerIns::local_player() },
        unsafe { WorldChrMan::instance() },
        unsafe { CSFeManImp::instance() },
    ) else {
        return;
    };
    let now = now_ms();
    let position = player.chr_ins.modules.physics.position;
    let ground = player.chr_ins.modules.behavior.ground_touch_state;
    let mut vertical_speed = 0.;
    if let Some((height, previous_ground, then)) = watch.player {
        let seconds = now.saturating_sub(then) as f32 / 1000.;
        if seconds > 0.004 {
            vertical_speed = (position.1 - height) / seconds;
        }
        if previous_ground != ground
            && let Some(dropped) = admit(&mut watch.budget, now)
        {
            crate::stance::record_watch(format!(
                "Enemy watch: player ground contact {previous_ground} -> {ground}, \
                 vertical speed {vertical_speed:.1} m/s, height {:.2} ({dropped} lines dropped)",
                position.1
            ));
        }
    }
    watch.player = Some((position.1, ground, now));
    let mut seen = Vec::with_capacity(3);
    for display in frontend.boss_health_displays.iter() {
        let handle = display.field_ins_handle;
        if display.fmg_id <= 0 || handle.is_empty() {
            continue;
        }
        let Some(chr) = world.chr_ins_by_handle(&handle) else {
            continue;
        };
        if chr.field_ins_handle != handle {
            continue;
        }
        let at = chr.modules.physics.position;
        let distance = ((at.0 - position.0).powi(2) + (at.2 - position.2).powi(2)).sqrt();
        if !distance.is_finite() || distance > RADIUS_M {
            continue;
        }
        let key = crate::native_damage::packed_handle(handle);
        seen.push(key);
        let Some(real) =
            (unsafe { crate::native_damage::game_inspect(0, chr as *const _ as usize) })
        else {
            continue;
        };
        let npc_param = chr.npc_param_id;
        let previous = watch.bosses.insert(key, real);
        let Some(previous) = previous else {
            if let Some(dropped) = admit(&mut watch.budget, now) {
                crate::stance::record_watch(format!(
                    "Enemy watch: boss {key:#x} param {npc_param} (character {:#x}) first seen at {distance:.1} m: {} ({dropped} lines dropped)",
                    chr as *const _ as usize,
                    describe(&[], &real)
                ));
            }
            continue;
        };
        let changed = changes(&previous, &real);
        if changed.is_empty() {
            continue;
        }
        if let Some(dropped) = admit(&mut watch.budget, now) {
            crate::stance::record_watch(format!(
                "Enemy watch: boss {key:#x} param {npc_param} at {distance:.1} m, player height \
                 speed {vertical_speed:.1} m/s, ground contact {ground}: {} ({dropped} lines \
                 dropped)",
                describe(&changed, &real)
            ));
        }
    }
    watch.bosses.retain(|key, _| seen.contains(key));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn real() -> Real {
        Real {
            stance: 80.,
            stance_max: 80.,
            toughness: 20.,
            damage_level: 0,
            hyper_armor: false,
            forced_break: false,
            anim: 3000,
            broken_lock: 0,
        }
    }

    #[test]
    fn only_drops_and_flag_changes_are_news() {
        let base = real();
        assert!(changes(&base, &base).is_empty());
        // Regeneration and a flag that animations flip every attack are not reported by themselves.
        let regen = Real {
            stance: 90.,
            ..base
        };
        assert!(
            changes(
                &Real {
                    stance: 40.,
                    ..base
                },
                &regen
            )
            .is_empty()
        );
        let armored = Real {
            hyper_armor: true,
            ..base
        };
        assert!(changes(&base, &armored).is_empty());
        assert_eq!(
            changes(
                &base,
                &Real {
                    stance: 60.,
                    ..base
                }
            ),
            ["stance"]
        );
        assert_eq!(changes(&base, &Real { stance: 0., ..base }), ["stance"]);
        assert_eq!(
            changes(
                &base,
                &Real {
                    toughness: 5.,
                    ..base
                }
            ),
            ["toughness"]
        );
        assert_eq!(
            changes(
                &base,
                &Real {
                    damage_level: 2,
                    forced_break: true,
                    anim: 8001,
                    ..base
                }
            ),
            ["damage_level", "forced_break", "anim"]
        );
    }

    #[test]
    fn the_line_budget_is_bounded_per_second_and_reports_what_it_dropped() {
        let mut budget = (0, 0, 0);
        let mut written = 0;
        for _ in 0..100 {
            if admit(&mut budget, 5_200).is_some() {
                written += 1;
            }
        }
        assert_eq!(written, MAX_LINES_PER_SECOND);
        // The next second opens with a count of what the last one could not say.
        assert_eq!(admit(&mut budget, 6_100), Some(100 - MAX_LINES_PER_SECOND));
        assert_eq!(admit(&mut budget, 6_200), Some(0));
    }
}
