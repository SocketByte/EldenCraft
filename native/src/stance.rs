//! Enemy stance: how much hit damage a native enemy absorbs before it staggers.
//!
//! Elden Ring's own poise never sees a Minecraft hit, because the damage processor is fed a
//! finished HP amount. Each enemy therefore carries a small meter here. A weapon's configured
//! stance damage fills it, the enemy's authored `superArmorDurability` is its capacity, and only a
//! hit that fills the meter requests the native stagger. The meter drains by itself after a quiet
//! spell. Nothing but an accepted hit receipt ever touches it: movement, jumping and nearness
//! cannot, which is why this module has no notion of the player at all.
//!
//! The game keeps a stance value of its own as well, and shows a stagger only once that is spent.
//! `real_stance_after` is the rule that keeps the two in step: a hit that breaks the meter spends
//! the game's value, and any other hit gets back whatever the game's processor took from it.
//! Every decision is also queued as a `Trace` for the log.
use std::{
    collections::{HashMap, VecDeque},
    sync::{Mutex, OnceLock},
    time::Instant,
};

/// More tracked enemies than this are dropped oldest-first, so a long session cannot grow it.
const MAX_TRACKED: usize = 256;

/// Decisions waiting to be written to the log by the existing worker; oldest are dropped first.
const MAX_TRACES: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Reaction {
    /// Every accepted hit staggers, as before stance existed.
    Always,
    Stance {
        /// Stance damage of this one hit.
        damage: f32,
        /// The enemy's stance: authored durability times the configured scale.
        capacity: f32,
        recovery_ms: u64,
    },
}

/// Model c2040, Rennala's scholars (NPC rows 2040xxxx, 30 stance). Her first phase falls only once
/// three glowing scholars have reacted to a hit, so a hit that leaves their stance standing must
/// still reach the game's reaction. Like enemies without authored stance, they stagger on every
/// hit, as all enemies did before stance existed.
const SCRIPTED_REACTION_MODELS: &[i32] = &[2040];

/// Whether this NPC row's encounter script depends on the game's reaction to every hit.
pub fn reacts_to_every_hit(npc_param_id: i32) -> bool {
    npc_param_id >= 0 && SCRIPTED_REACTION_MODELS.contains(&(npc_param_id / 10_000))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// Request the native stagger.
    Staggered,
    /// The hit landed but the enemy keeps its footing.
    Held,
}

struct Meter {
    taken: f32,
    last_ms: u64,
}

#[derive(Default)]
pub struct Meters {
    by_target: HashMap<u64, Meter>,
}

impl Meters {
    #[cfg(test)]
    pub fn hit(&mut self, target: u64, now_ms: u64, reaction: Reaction) -> Outcome {
        self.hit_total(target, now_ms, reaction).0
    }

    /// What `hit` would answer, without recording anything.
    pub fn peek(&self, target: u64, now_ms: u64, reaction: Reaction) -> Outcome {
        let Reaction::Stance {
            damage,
            capacity,
            recovery_ms,
        } = reaction
        else {
            return Outcome::Staggered;
        };
        if !capacity.is_finite() || capacity <= 0. {
            return Outcome::Staggered;
        }
        let damage = if damage.is_finite() {
            damage.max(0.)
        } else {
            0.
        };
        let carried = self
            .by_target
            .get(&target)
            .filter(|m| now_ms.saturating_sub(m.last_ms) <= recovery_ms)
            .map_or(0., |m| m.taken);
        if carried + damage >= capacity {
            Outcome::Staggered
        } else {
            Outcome::Held
        }
    }

    /// The outcome together with the stance this enemy had taken once the hit was counted.
    pub fn hit_total(&mut self, target: u64, now_ms: u64, reaction: Reaction) -> (Outcome, f32) {
        let Reaction::Stance {
            damage,
            capacity,
            recovery_ms,
        } = reaction
        else {
            return (Outcome::Staggered, 0.);
        };
        // Rows without authored stance (-1) never had poise to lose; they keep flinching.
        if !capacity.is_finite() || capacity <= 0. {
            return (Outcome::Staggered, 0.);
        }
        let damage = if damage.is_finite() {
            damage.max(0.)
        } else {
            0.
        };
        if self.by_target.len() >= MAX_TRACKED && !self.by_target.contains_key(&target) {
            self.by_target
                .retain(|_, m| now_ms.saturating_sub(m.last_ms) <= recovery_ms);
            if self.by_target.len() >= MAX_TRACKED
                && let Some(oldest) = self
                    .by_target
                    .iter()
                    .min_by_key(|(_, m)| m.last_ms)
                    .map(|(key, _)| *key)
            {
                self.by_target.remove(&oldest);
            }
        }
        let meter = self.by_target.entry(target).or_insert(Meter {
            taken: 0.,
            last_ms: now_ms,
        });
        if now_ms.saturating_sub(meter.last_ms) > recovery_ms {
            meter.taken = 0.;
        }
        meter.last_ms = now_ms;
        meter.taken += damage;
        let taken = meter.taken;
        if taken >= capacity {
            self.by_target.remove(&target);
            (Outcome::Staggered, taken)
        } else {
            (Outcome::Held, taken)
        }
    }

    /// A defeated or replaced enemy starts over, whatever handle it is reborn with.
    pub fn forget(&mut self, target: u64) {
        self.by_target.remove(&target);
    }
}

static METERS: Mutex<Option<Meters>> = Mutex::new(None);

fn now_ms() -> u64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

/// Applies one accepted hit to the shared meters. Returns the outcome together with the stance
/// the enemy had taken once this hit was counted.
pub fn hit_total(target: u64, reaction: Reaction) -> (Outcome, f32) {
    match METERS.lock() {
        Ok(mut guard) => {
            guard
                .get_or_insert_with(Meters::default)
                .hit_total(target, now_ms(), reaction)
        }
        // A poisoned lock must not take the combat path down; the old behavior is the safe one.
        Err(_) => (Outcome::Staggered, 0.),
    }
}

/// Whether a hit now would break the enemy's stance, without counting it.
pub fn peek(target: u64, reaction: Reaction) -> Outcome {
    match METERS.lock() {
        Ok(guard) => guard.as_ref().map_or_else(
            || Meters::default().peek(target, now_ms(), reaction),
            |meters| meters.peek(target, now_ms(), reaction),
        ),
        Err(_) => Outcome::Staggered,
    }
}

/// The real stance (the game's own poise value) a hit should leave the enemy with.
///
/// The meter alone decides whether a Minecraft hit staggers. The game's damage processor,
/// however, also owns a stance value of its own and only shows a stagger once that is spent, and
/// a Minecraft hit carries no poise damage to spend it. So a hit that breaks the meter spends
/// the real stance in full, and a hit that does not gets back whatever the processor took from
/// it, whatever the player was doing at the time. `None` leaves the value alone.
pub fn real_stance_after(outcome: Outcome, before: f32, current: f32) -> Option<f32> {
    if !before.is_finite() || !current.is_finite() {
        return None;
    }
    match outcome {
        Outcome::Staggered => (current > 0.).then_some(0.),
        Outcome::Held => (current < before).then_some(before),
    }
}

/// Everything the game itself keeps that decides whether an enemy visibly staggers. Copied out of
/// the game for the log; carries no pointers.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Real {
    /// The game's own stance now and at most (`superArmorDurability`).
    pub stance: f32,
    pub stance_max: f32,
    /// The game's toughness module, documented as related to stance break.
    pub toughness: f32,
    /// How hard the last received attack was; decides how far the enemy staggers.
    pub damage_level: u8,
    /// Hyper armor: set while an animation says hits must not flinch the enemy.
    pub hyper_armor: bool,
    /// Set while an animation says any hit breaks the enemy's stance.
    pub forced_break: bool,
    /// The animation the enemy is playing, or -1 when unreadable.
    pub anim: i32,
    /// The stance module's "broken" byte: the game sets it when stance runs out, and its later
    /// update turns that into the stance-break stagger and the reset to full stance.
    pub broken_lock: u8,
}

/// One stance decision, logged by the existing worker; carries no game pointers.
#[derive(Clone, Debug)]
pub struct Trace {
    pub target: u64,
    /// `None` when the hit used the legacy every-hit stagger.
    pub damage: Option<f32>,
    pub capacity: Option<f32>,
    pub taken: f32,
    pub outcome: Outcome,
    /// The game's own stance before the hit, after the processor, and what we left it at.
    pub real_before: Option<f32>,
    pub real_processed: Option<f32>,
    pub real_left: Option<f32>,
    /// The rest of the game's state before the hit and after its processor.
    pub state_before: Option<Real>,
    pub state_processed: Option<Real>,
    /// Hyper armor and forced break as they were just before the stagger was requested.
    pub flags_at_stagger: Option<(bool, bool)>,
    /// The game's stance read once it had been asked to stagger and told of the hit, before
    /// anything of ours is written: below what it was means the game spent the request's poise.
    pub real_after_react: Option<f32>,
    /// Whether the game's own stance routine was called for this hit, and the state straight
    /// after it.
    pub spent: bool,
    pub state_after_spend: Option<Real>,
}

/// Lines from the enemy watcher, waiting for the existing worker to write them.
static WATCH: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

pub fn record_watch(line: String) {
    if let Ok(mut lines) = WATCH.try_lock() {
        lines.push_back(line);
        while lines.len() > MAX_TRACES * 2 {
            lines.pop_front();
        }
    }
}

pub fn take_watch() -> Vec<String> {
    WATCH
        .try_lock()
        .map(|mut lines| lines.drain(..).collect())
        .unwrap_or_default()
}

static TRACES: Mutex<VecDeque<Trace>> = Mutex::new(VecDeque::new());

fn retain_trace(traces: &mut VecDeque<Trace>, trace: Trace) {
    traces.push_back(trace);
    while traces.len() > MAX_TRACES {
        traces.pop_front();
    }
}

pub fn record_trace(trace: Trace) {
    if let Ok(mut traces) = TRACES.try_lock() {
        retain_trace(&mut traces, trace);
    }
}

pub fn take_traces() -> Vec<Trace> {
    TRACES
        .try_lock()
        .map(|mut traces| traces.drain(..).collect())
        .unwrap_or_default()
}

pub fn forget(target: u64) {
    if let Ok(mut guard) = METERS.lock()
        && let Some(meters) = guard.as_mut()
    {
        meters.forget(target);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stance(damage: f32, capacity: f32) -> Reaction {
        Reaction::Stance {
            damage,
            capacity,
            recovery_ms: 6000,
        }
    }

    #[test]
    fn legacy_reaction_staggers_every_hit() {
        let mut meters = Meters::default();
        for t in 0..4 {
            assert_eq!(meters.hit(1, t, Reaction::Always), Outcome::Staggered);
        }
    }

    #[test]
    fn only_rennalas_scholars_react_to_every_hit() {
        for scholar in [20400000, 20400024, 20402224, 20404024] {
            assert!(reacts_to_every_hit(scholar));
        }
        // Rennala herself, the other scholar model and an ordinary soldier keep the meter.
        for other in [20300024, 20310024, 20410040, 10000024, 2040, -20400024] {
            assert!(!reacts_to_every_hit(other));
        }
    }

    #[test]
    fn stagger_comes_only_when_accumulated_stance_reaches_capacity() {
        let mut meters = Meters::default();
        // 30-stance soldier against a 10-stance sword: two light hits hold, the third breaks.
        assert_eq!(meters.hit(7, 0, stance(10., 30.)), Outcome::Held);
        assert_eq!(meters.hit(7, 500, stance(10., 30.)), Outcome::Held);
        assert_eq!(meters.hit(7, 900, stance(10., 30.)), Outcome::Staggered);
        // A broken enemy starts over rather than staying broken.
        assert_eq!(meters.hit(7, 1000, stance(10., 30.)), Outcome::Held);
    }

    #[test]
    fn a_heavier_weapon_breaks_the_same_enemy_sooner() {
        let hits = |damage: f32| {
            let mut meters = Meters::default();
            (0..20).position(|n| meters.hit(1, n * 100, stance(damage, 80.)) == Outcome::Staggered)
        };
        assert_eq!(hits(10.), Some(7));
        assert_eq!(hits(38.), Some(2));
        // A single mace blow breaks a 30-stance soldier outright.
        assert_eq!(
            Meters::default().hit(3, 0, stance(55., 30.)),
            Outcome::Staggered
        );
    }

    #[test]
    fn stance_recovers_after_a_quiet_spell_but_not_between_quick_hits() {
        let mut meters = Meters::default();
        assert_eq!(meters.hit(2, 0, stance(25., 30.)), Outcome::Held);
        // Still inside the window: the second hit breaks it.
        assert_eq!(meters.hit(2, 5000, stance(25., 30.)), Outcome::Staggered);
        assert_eq!(meters.hit(2, 6000, stance(25., 30.)), Outcome::Held);
        // Left alone for longer than the recovery, the enemy is whole again.
        assert_eq!(meters.hit(2, 12001, stance(25., 30.)), Outcome::Held);
    }

    #[test]
    fn each_enemy_keeps_its_own_meter() {
        let mut meters = Meters::default();
        assert_eq!(meters.hit(1, 0, stance(20., 30.)), Outcome::Held);
        assert_eq!(meters.hit(2, 10, stance(20., 30.)), Outcome::Held);
        assert_eq!(meters.hit(1, 20, stance(20., 30.)), Outcome::Staggered);
        assert_eq!(meters.hit(2, 30, stance(5., 30.)), Outcome::Held);
    }

    #[test]
    fn enemies_without_authored_stance_keep_flinching_and_bad_values_are_inert() {
        let mut meters = Meters::default();
        assert_eq!(meters.hit(1, 0, stance(1., -1.)), Outcome::Staggered);
        assert_eq!(meters.hit(1, 0, stance(1., 0.)), Outcome::Staggered);
        assert_eq!(meters.hit(1, 0, stance(1., f32::NAN)), Outcome::Staggered);
        // Zero, negative and non-finite damage never fill the meter.
        for damage in [0., -50., f32::NAN, f32::INFINITY] {
            assert_eq!(meters.hit(4, 0, stance(damage, 30.)), Outcome::Held);
        }
        // An effectively immune row (9999) is not broken by any weapon.
        assert_eq!(meters.hit(5, 0, stance(55., 9999.)), Outcome::Held);
    }

    #[test]
    fn forgetting_an_enemy_resets_it_and_nothing_else_changes_a_meter() {
        // The model has no input other than hit() and the reset below.
        let mut meters = Meters::default();
        assert_eq!(meters.hit(1, 0, stance(25., 30.)), Outcome::Held);
        meters.forget(1);
        assert_eq!(meters.hit(1, 10, stance(25., 30.)), Outcome::Held);
    }

    #[test]
    fn peek_agrees_with_hit_and_never_records_anything() {
        let mut meters = Meters::default();
        for t in [0, 500, 900, 1000, 1500, 2000, 2500, 20_000] {
            let predicted = meters.peek(7, t, stance(10., 30.));
            // A peek twice in a row changes nothing, and the real hit then answers the same.
            assert_eq!(predicted, meters.peek(7, t, stance(10., 30.)));
            assert_eq!(predicted, meters.hit(7, t, stance(10., 30.)), "at {t} ms");
        }
        assert_eq!(meters.peek(9, 0, Reaction::Always), Outcome::Staggered);
        assert_eq!(meters.peek(9, 0, stance(1., -1.)), Outcome::Staggered);
        assert_eq!(meters.peek(9, 0, stance(55., 30.)), Outcome::Staggered);
        assert!(!meters.by_target.contains_key(&9));
    }

    #[test]
    fn hit_total_reports_what_the_enemy_has_taken() {
        let mut meters = Meters::default();
        assert_eq!(
            meters.hit_total(1, 0, stance(22., 80.)),
            (Outcome::Held, 22.)
        );
        assert_eq!(
            meters.hit_total(1, 400, stance(22., 80.)),
            (Outcome::Held, 44.)
        );
        assert_eq!(
            meters.hit_total(1, 800, stance(22., 80.)),
            (Outcome::Held, 66.)
        );
        assert_eq!(
            meters.hit_total(1, 1200, stance(22., 80.)),
            (Outcome::Staggered, 88.)
        );
        assert_eq!(
            meters.hit_total(1, 1600, stance(22., 80.)),
            (Outcome::Held, 22.)
        );
    }

    #[test]
    fn the_games_real_stance_follows_the_meter() {
        // A broken meter spends the real stance entirely, so the game shows the stagger.
        assert_eq!(real_stance_after(Outcome::Staggered, 80., 80.), Some(0.));
        assert_eq!(real_stance_after(Outcome::Staggered, 80., 35.), Some(0.));
        // Already spent: nothing to write.
        assert_eq!(real_stance_after(Outcome::Staggered, 80., 0.), None);
        // A held hit gives back anything the processor spent, whatever caused it...
        assert_eq!(real_stance_after(Outcome::Held, 80., 0.), Some(80.));
        assert_eq!(real_stance_after(Outcome::Held, 80., 79.5), Some(80.));
        // ...but never raises stance the game has recovered or gained meanwhile.
        assert_eq!(real_stance_after(Outcome::Held, 80., 80.), None);
        assert_eq!(real_stance_after(Outcome::Held, 80., 95.), None);
        // Unreadable values are left strictly alone.
        for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            for outcome in [Outcome::Held, Outcome::Staggered] {
                assert_eq!(real_stance_after(outcome, bad, 10.), None);
                assert_eq!(real_stance_after(outcome, 10., bad), None);
            }
        }
    }

    #[test]
    fn the_trace_queue_is_bounded_and_keeps_the_newest() {
        let mut traces = VecDeque::new();
        for target in 0..(MAX_TRACES as u64 + 10) {
            retain_trace(
                &mut traces,
                Trace {
                    target,
                    damage: Some(1.),
                    capacity: Some(80.),
                    taken: 1.,
                    outcome: Outcome::Held,
                    real_before: Some(80.),
                    real_processed: Some(80.),
                    real_left: None,
                    state_before: None,
                    state_processed: None,
                    flags_at_stagger: None,
                    real_after_react: None,
                    spent: false,
                    state_after_spend: None,
                },
            );
        }
        assert_eq!(traces.len(), MAX_TRACES);
        assert_eq!(traces.front().unwrap().target, 10);
        assert_eq!(traces.back().unwrap().target, MAX_TRACES as u64 + 9);
    }

    #[test]
    fn tracking_is_bounded_and_drops_the_stalest_enemy() {
        let mut meters = Meters::default();
        for target in 0..(MAX_TRACKED as u64 + 40) {
            meters.hit(target, target, stance(1., 30.));
        }
        assert!(meters.by_target.len() <= MAX_TRACKED);
        assert!(!meters.by_target.contains_key(&0));
        assert!(meters.by_target.contains_key(&(MAX_TRACKED as u64 + 39)));
    }
}
