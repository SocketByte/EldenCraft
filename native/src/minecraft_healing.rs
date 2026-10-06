//! Apply only acknowledged, server-authored golden apple regeneration to the local offline player.
use crate::{healing_transport, healing_wire as wire};
use eldenring::cs::{CSSessionManager, GameMan, LobbyState, PlayerIns, ProtocolState};
use fromsoftware_shared::FromStatic;
use std::collections::VecDeque;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetTickCount64() -> u64;
    fn GetCurrentProcessId() -> u32;
}
pub struct Driver {
    publisher: Option<healing_transport::Publisher>,
    reader: healing_transport::Reader,
    admission: wire::Admission,
    epoch: u64,
    identity: Option<(usize, i32)>,
    history: VecDeque<(u64, u64)>,
    error: Option<&'static str>,
    pub event: Option<String>,
}
impl Driver {
    pub fn new() -> Self {
        Self {
            publisher: healing_transport::Publisher::open().ok(),
            reader: healing_transport::Reader::default(),
            admission: wire::Admission::default(),
            epoch: unsafe { GetTickCount64() }.max(1),
            identity: None,
            history: VecDeque::new(),
            error: None,
            event: None,
        }
    }
    pub fn fail(&mut self, reason: &'static str) {
        self.suspend();
        if self.error != Some(reason) {
            self.event = Some(format!("Golden apple healing suspended: {reason}"));
            self.error = Some(reason);
        }
    }
    pub fn suspend(&mut self) {
        if self.identity.take().is_some() {
            self.epoch = self.epoch.saturating_add(1);
        }
        self.history.clear();
        if let Some(p) = self.publisher.as_mut() {
            let _ = p.publish(&wire::Host {
                epoch: self.epoch,
                ..wire::Host::default()
            });
        }
    }
    /// Called on the validated offline foreground game task. Reacquires the local
    /// player for each mutation, never retaining an SDK reference across calls.
    pub unsafe fn tick(&mut self, enabled: bool, map: i32) -> Result<Option<i32>, &'static str> {
        if !enabled {
            self.suspend();
            return Ok(None);
        }
        let identity = unsafe { identity(map) }?;
        if self.identity != Some(identity) {
            self.suspend();
            self.identity = Some(identity);
        }
        let now = unsafe { GetTickCount64() };
        let mut changed = None;
        if let Some(m) = self.reader.poll()
            && m.flags == 1
            && m.host_pid == unsafe { GetCurrentProcessId() }
            && m.epoch == self.epoch
            && m.map == map as u32
            && self.admission.observe(m.pid, m.session, now)
        {
            let frame_valid = self
                .history
                .iter()
                .any(|(f, t)| *f == m.host_frame && now.saturating_sub(*t) <= wire::FRESH_MS);
            for r in &m.receipts {
                if r.sequence <= self.admission.sequence {
                    continue;
                }
                let admission = self.admission.consume(r);
                let result = if !frame_valid {
                    Err("healing observation expired")
                } else {
                    admission.and_then(|_| unsafe { apply(identity, r) })
                };
                match result {
                    Ok((before, after)) => {
                        self.admission.result = 1;
                        changed = Some(after);
                        self.event = Some(format!(
                            "Golden apple regeneration: session={} receipt={} consumption={} remaining={} hp={before}->{after}",
                            m.session, r.sequence, r.consumption, r.remaining
                        ));
                    }
                    Err(reason) => {
                        self.event = Some(format!(
                            "Golden apple regeneration rejected: receipt={} {reason}",
                            r.sequence
                        ));
                    }
                }
            }
        }
        let (hp, max_hp) = unsafe { health(identity) }?;
        let frame = self
            .publisher
            .as_mut()
            .ok_or("healing publisher unavailable")?
            .publish(&wire::Host {
                flags: 1,
                epoch: self.epoch,
                map: map as u32,
                hp: hp as f32,
                max_hp: max_hp as f32,
                ack_result: self.admission.result,
                ack_session: self.admission.session,
                ack_sequence: self.admission.sequence,
            })?;
        self.history.push_back((frame, now));
        while self.history.len() > 90
            || self
                .history
                .front()
                .is_some_and(|(_, t)| now.saturating_sub(*t) > wire::FRESH_MS)
        {
            self.history.pop_front();
        }
        self.error = None;
        Ok(changed)
    }
}
unsafe fn identity(map: i32) -> Result<(usize, i32), &'static str> {
    let session =
        unsafe { CSSessionManager::instance() }.map_err(|_| "healing session unavailable")?;
    let game = unsafe { GameMan::instance() }.map_err(|_| "healing game manager unavailable")?;
    if game.is_in_online_mode
        || game.warp_requested
        || session.lobby_state != LobbyState::None
        || session.protocol_state != ProtocolState::None
    {
        return Err("healing requires offline session");
    }
    let player =
        unsafe { PlayerIns::local_player() }.map_err(|_| "healing local player unavailable")?;
    if player.current_block_id.0 != map
        || player.chr_ins.modules.data.hp <= 0
        || player.chr_ins.chr_flags1c5.death_flag()
    {
        return Err("healing player changed or dead");
    }
    if player.chr_ins.modules.data.owner.as_ptr() != std::ptr::addr_of!(player.chr_ins).cast_mut() {
        return Err("healing data owner mismatch");
    }
    Ok((player as *const PlayerIns as usize, map))
}
unsafe fn health(expected: (usize, i32)) -> Result<(i32, i32), &'static str> {
    if unsafe { identity(expected.1) }? != expected {
        return Err("healing identity changed");
    }
    let p = unsafe { PlayerIns::local_player() }.map_err(|_| "healing player unavailable")?;
    Ok((p.chr_ins.modules.data.hp, p.chr_ins.modules.data.max_hp))
}
unsafe fn apply(expected: (usize, i32), r: &wire::Receipt) -> Result<(i32, i32), &'static str> {
    let (before, maximum) = unsafe { health(expected) }?;
    let after = wire::restored_hp(before, maximum, r)?;
    // The pinned SDK's current CSChrDataModule HP is the same live field used by
    // host HUD/readback and the native damage processor. Max HP is never modified.
    let p = unsafe { PlayerIns::local_player_mut() }.map_err(|_| "healing player unavailable")?;
    if (p as *const PlayerIns as usize, p.current_block_id.0) != expected
        || p.chr_ins.modules.data.hp != before
    {
        return Err("healing identity or health changed");
    }
    p.chr_ins.modules.data.hp = after;
    Ok((before, p.chr_ins.modules.data.hp))
}
