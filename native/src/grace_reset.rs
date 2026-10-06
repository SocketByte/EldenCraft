//! Bounded read-only recognition of a native Site of Grace character reset.
//! This never grants gameplay input, camera/HUD writes, travel, or an active
//! host publication. It only permits retaining passive ESD source observations
//! while the same healthy player briefly loses its activity/update-task bits.

pub const LEASE_MS: u64 = 1500;
const MAX_DISPLACEMENT_SQUARED: f32 = 1.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rest {
    pub script: usize,
    pub bonfire: u32,
}

#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub player: usize,
    pub entry: usize,
    pub block: i32,
    pub feet: [f32; 3],
    pub activity_ready: bool,
    pub rest: Option<Rest>,
}

/// Exact read-only gate failure; unlike `sample().is_some()`, this distinguishes
/// a missing object from a pointer mismatch or a world/session transition.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Rejection {
    GameUnavailable,
    SessionUnavailable,
    Online,
    Warp,
    SaveSlot(i32),
    Lobby,
    Protocol,
    PlayerUnavailable,
    Dead,
    NoHealth(i32),
    EntryMismatch {
        player: usize,
        entry: usize,
        entry_owner: usize,
    },
    PhysicsOwnerMismatch {
        player: usize,
        owner: usize,
    },
    BlockUninitialized,
    FeetNonfinite,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RestRejection {
    LuaUnavailable,
    NotRequested,
    EndPending,
    StandingUp,
    ReturnTitle,
    LoadWait,
    LobbyClient,
    NetMessage,
    ScriptUnavailable,
    MapReentry,
    BonfireInvalid,
}

#[derive(Clone, Copy, Debug)]
pub struct RestCheck {
    pub flags: u32,
    pub load_wait: bool,
    pub lobby_client: bool,
    pub net_message: bool,
    pub script: usize,
    pub map_reentry: bool,
    pub bonfire: u32,
    pub rejection: Option<RestRejection>,
}

#[derive(Clone, Copy, Debug)]
pub struct CheckedSample {
    pub sample: Sample,
    pub active: bool,
    pub tasks_registered: bool,
    pub rest_check: RestCheck,
}

impl std::fmt::Display for CheckedSample {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let rest = &self.rest_check;
        write!(
            f,
            "player={:x} entry={:x} block={:08x} active={} tasks={} feet={:?} rest={:?} flags={:x} load_wait={} lobby_client={} net_message={} script={:x} reentry={} bonfire={}",
            self.sample.player,
            self.sample.entry,
            self.sample.block as u32,
            self.active,
            self.tasks_registered,
            self.sample.feet,
            rest.rejection,
            rest.flags,
            rest.load_wait,
            rest.lobby_client,
            rest.net_message,
            rest.script,
            rest.map_reentry,
            rest.bonfire,
        )
    }
}

impl Sample {
    fn valid(&self) -> bool {
        self.player != 0
            && self.entry != 0
            && self.block != -1
            && self.feet.iter().all(|v| v.is_finite())
    }
    fn same_player(&self, other: &Self) -> bool {
        self.player == other.player && self.entry == other.entry && self.block == other.block
    }
    fn nearby(&self, other: &Self) -> bool {
        self.feet
            .iter()
            .zip(other.feet)
            .map(|(a, b)| (a - b).powi(2))
            .sum::<f32>()
            <= MAX_DISPLACEMENT_SQUARED
    }
}

#[derive(Clone, Copy)]
struct Stable {
    at: u64,
    sample: Sample,
}

#[derive(Default)]
pub struct Lease {
    stable: Option<Stable>,
    reset: Option<Rest>,
}

impl Lease {
    pub fn clear(&mut self) {
        self.stable = None;
        self.reset = None;
    }
    /// Call only after a complete, valid gameplay snapshot. Inactive reads may
    /// never refresh the deadline or replace the last stable position.
    pub fn record_ready(&mut self, now: u64, sample: Sample) {
        if sample.valid() && sample.activity_ready {
            self.stable = Some(Stable { at: now, sample });
            self.reset = None;
        } else {
            self.clear();
        }
    }
    /// Permit passive source retention for this exact activity-bit loss only.
    /// Failure revokes the whole lease, so later values cannot revive it without
    /// a newly validated ready snapshot.
    pub fn hold(&mut self, now: u64, sample: Sample) -> bool {
        let admitted = (|| {
            let stable = self.stable?;
            let rest = sample.rest?;
            let age = now.checked_sub(stable.at)?;
            (sample.valid()
                && !sample.activity_ready
                && age < LEASE_MS
                && stable.sample.same_player(&sample)
                && stable.sample.nearby(&sample)
                && stable.sample.rest.is_none_or(|known| known == rest)
                && self.reset.is_none_or(|known| known == rest))
            .then_some(rest)
        })();
        if let Some(rest) = admitted {
            self.reset = Some(rest);
            true
        } else {
            self.clear();
            false
        }
    }
}

/// Read typed context without requiring the transient activity/update-task bits.
/// A returned sample is healthy and offline; only its optional rest field proves
/// native bonfire begin/loop state. Missing Lua data grants no rest exception.
///
/// # Safety
/// Validated executable, game task thread only; no outstanding SDK references.
#[cfg(windows)]
pub unsafe fn sample() -> Option<Sample> {
    unsafe { sample_checked() }
        .ok()
        .map(|checked| checked.sample)
}

/// Read the same admission sample, retaining the exact rejection and raw rest
/// context for diagnosis. This does not relax any gate or perform native writes.
///
/// # Safety
/// Same requirements as [`sample`].
#[cfg(windows)]
pub unsafe fn sample_checked() -> Result<CheckedSample, Rejection> {
    use eldenring::cs::{
        CSLuaEventManImp, CSSessionManager, GameMan, LobbyState, PlayerIns, ProtocolState,
    };
    use fromsoftware_shared::FromStatic;

    let game = unsafe { GameMan::instance() }.map_err(|_| Rejection::GameUnavailable)?;
    let session =
        unsafe { CSSessionManager::instance() }.map_err(|_| Rejection::SessionUnavailable)?;
    if game.is_in_online_mode {
        return Err(Rejection::Online);
    }
    if game.warp_requested {
        return Err(Rejection::Warp);
    }
    if game.save_slot < 0 {
        return Err(Rejection::SaveSlot(game.save_slot));
    }
    if session.lobby_state != LobbyState::None {
        return Err(Rejection::Lobby);
    }
    if session.protocol_state != ProtocolState::None {
        return Err(Rejection::Protocol);
    }
    let player = unsafe { PlayerIns::local_player() }.map_err(|_| Rejection::PlayerUnavailable)?;
    let chr = &player.chr_ins;
    let entry = unsafe { chr.chr_set_entry.as_ref() };
    if chr.chr_flags1c5.death_flag() {
        return Err(Rejection::Dead);
    }
    if chr.modules.data.hp <= 0 {
        return Err(Rejection::NoHealth(chr.modules.data.hp));
    }
    if !entry.chr_ins.is_some_and(|p| std::ptr::eq(p.as_ptr(), chr)) {
        return Err(Rejection::EntryMismatch {
            player: chr as *const _ as usize,
            entry: chr.chr_set_entry.as_ptr() as usize,
            entry_owner: entry.chr_ins.map_or(0, |p| p.as_ptr() as usize),
        });
    }
    if !std::ptr::eq(chr.modules.physics.owner.as_ptr(), chr) {
        return Err(Rejection::PhysicsOwnerMismatch {
            player: chr as *const _ as usize,
            owner: chr.modules.physics.owner.as_ptr() as usize,
        });
    }
    let active = chr.chr_flags1c8.is_active();
    let tasks_registered = chr.chr_flags1c8.update_tasks_registered();
    let mut sample = Sample {
        player: player as *const _ as usize,
        entry: chr.chr_set_entry.as_ptr() as usize,
        block: player.current_block_id.0,
        feet: [
            player.block_position.x,
            player.block_position.y,
            player.block_position.z,
        ],
        activity_ready: active && tasks_registered,
        rest: None,
    };
    if sample.block == -1 {
        return Err(Rejection::BlockUninitialized);
    }
    if !sample.feet.iter().all(|v| v.is_finite()) {
        return Err(Rejection::FeetNonfinite);
    }
    let mut rest_check = RestCheck {
        flags: 0,
        load_wait: false,
        lobby_client: false,
        net_message: false,
        script: 0,
        map_reentry: false,
        bonfire: 0,
        rejection: Some(RestRejection::LuaUnavailable),
    };
    if let Ok(events) = unsafe { CSLuaEventManImp::instance() } {
        let proxy = &events.lua_event_proxy;
        let flags = proxy.control_flags;
        let script = events.lua_event_script_imitation.as_ref();
        rest_check = RestCheck {
            flags: flags.0,
            load_wait: proxy.is_load_wait,
            lobby_client: proxy.is_lobby_state_client,
            net_message: proxy.is_net_message,
            script: script.map_or(0, |s| s.as_ptr() as usize),
            map_reentry: script.is_some_and(|s| s.is_wait_reentry_to_map),
            bonfire: script.map_or(0, |s| s.bonfire_entity_id),
            rejection: if !(flags.bonfire_loop_begin_requested()
                || flags.bonfire_sitting_loop_active())
            {
                Some(RestRejection::NotRequested)
            } else if flags.bonfire_end_pending() {
                Some(RestRejection::EndPending)
            } else if flags.bonfire_stand_up_in_progress() {
                Some(RestRejection::StandingUp)
            } else if flags.return_title_requested() {
                Some(RestRejection::ReturnTitle)
            } else if proxy.is_load_wait {
                Some(RestRejection::LoadWait)
            } else if proxy.is_lobby_state_client {
                Some(RestRejection::LobbyClient)
            } else if proxy.is_net_message {
                Some(RestRejection::NetMessage)
            } else if script.is_none() {
                Some(RestRejection::ScriptUnavailable)
            } else if script.is_some_and(|s| s.is_wait_reentry_to_map) {
                Some(RestRejection::MapReentry)
            } else if script
                .is_some_and(|s| s.bonfire_entity_id < 1000 || s.bonfire_entity_id == u32::MAX)
            {
                Some(RestRejection::BonfireInvalid)
            } else {
                None
            },
        };
        if rest_check.rejection.is_none() {
            sample.rest = Some(Rest {
                script: rest_check.script,
                bonfire: rest_check.bonfire,
            });
        }
    }
    Ok(CheckedSample {
        sample,
        active,
        tasks_registered,
        rest_check,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ready() -> Sample {
        Sample {
            player: 11,
            entry: 22,
            block: 33,
            feet: [10., 20., 30.],
            activity_ready: true,
            rest: None,
        }
    }
    fn resetting() -> Sample {
        Sample {
            activity_ready: false,
            rest: Some(Rest {
                script: 44,
                bonfire: 100_001_951,
            }),
            ..ready()
        }
    }
    #[test]
    fn rest_reset_retains_sources_and_ready_reacquisition_starts_a_new_baseline() {
        let mut lease = Lease::default();
        lease.record_ready(1000, ready());
        assert!(lease.hold(1016, resetting()));
        assert!(lease.hold(1400, resetting()));
        let restored = Sample {
            activity_ready: true,
            ..resetting()
        };
        lease.record_ready(1416, restored);
        assert!(lease.hold(1432, resetting()));
    }
    #[test]
    fn repeated_inactive_samples_never_extend_expiry() {
        let mut lease = Lease::default();
        lease.record_ready(1000, ready());
        for now in [1016, 1200, 2000, 2499] {
            assert!(lease.hold(now, resetting()));
        }
        assert!(!lease.hold(2500, resetting()));
        assert!(!lease.hold(2501, resetting()));
    }
    #[test]
    fn respawn_or_movement_cannot_borrow_a_rest_lease() {
        for changed in [
            Sample {
                player: 12,
                ..resetting()
            },
            Sample {
                entry: 23,
                ..resetting()
            },
            Sample {
                block: 34,
                ..resetting()
            },
            Sample {
                feet: [12., 20., 30.],
                ..resetting()
            },
            Sample {
                rest: None,
                ..resetting()
            },
        ] {
            let mut lease = Lease::default();
            lease.record_ready(1000, ready());
            assert!(!lease.hold(1016, changed));
            assert!(
                !lease.hold(1017, resetting()),
                "failed identity cannot revive"
            );
        }
    }
    #[test]
    fn bonfire_or_script_replacement_revokes_captured_source_identity() {
        for rest in [
            Rest {
                script: 45,
                bonfire: 100_001_951,
            },
            Rest {
                script: 44,
                bonfire: 100_002_951,
            },
        ] {
            let mut lease = Lease::default();
            lease.record_ready(1000, ready());
            assert!(lease.hold(1016, resetting()));
            assert!(!lease.hold(
                1032,
                Sample {
                    rest: Some(rest),
                    ..resetting()
                }
            ));
        }
    }
    #[test]
    fn invalid_or_inactive_baselines_and_clock_regression_grant_nothing() {
        for baseline in [
            resetting(),
            Sample {
                feet: [f32::NAN, 20., 30.],
                ..ready()
            },
            Sample {
                block: -1,
                ..ready()
            },
        ] {
            let mut lease = Lease::default();
            lease.record_ready(1000, baseline);
            assert!(!lease.hold(1016, resetting()));
        }
        let mut lease = Lease::default();
        lease.record_ready(1000, ready());
        assert!(!lease.hold(999, resetting()));
    }
    #[test]
    fn normal_focus_warp_or_death_release_requires_a_new_ready_snapshot() {
        let mut lease = Lease::default();
        lease.record_ready(1000, ready());
        assert!(lease.hold(1016, resetting()));
        lease.clear();
        assert!(!lease.hold(1032, resetting()));
    }
}
