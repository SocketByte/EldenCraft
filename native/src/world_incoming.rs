//! Bounded native-to-Minecraft damage receipts. This module never applies HP.
//!
//! The caller authenticates the peer and matches PID/session/epoch before an
//! ACK is accepted. Call `snapshot(now)` before deriving native proxy health
//! from `guest.hp - pending(uuid)`, so expired receipts cannot reserve HP.
use crate::world_wire::{Incoming, RECEIPT_MS};
use std::collections::VecDeque;

pub const MAX_PENDING: usize = 128;
const MAX_SEQUENCE: u64 = i64::MAX as u64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Context {
    pub peer_pid: u32,
    pub session: u64,
    pub epoch: u64,
}

#[derive(Clone, Debug)]
pub struct Expired {
    pub seq: u64,
    pub uuid: String,
    pub damage: f32,
    pub age_ms: u64,
    pub reason: &'static str,
}

#[derive(Default)]
pub struct Ledger {
    context: Option<Context>,
    last_issued: u64,
    acknowledged: u64,
    now: u64,
    pending: VecDeque<Incoming>,
    expired: VecDeque<Expired>,
}

impl Ledger {
    pub fn new() -> Self {
        Self::default()
    }

    /// A new peer incarnation discards all old reservations and sequence state.
    /// Re-observing the same context preserves unacknowledged receipts.
    pub fn reset_context(
        &mut self,
        peer_pid: u32,
        session: u64,
        epoch: u64,
    ) -> Result<bool, &'static str> {
        if peer_pid == 0
            || session == 0
            || epoch == 0
            || session > MAX_SEQUENCE
            || epoch > MAX_SEQUENCE
        {
            return Err("incoming context invalid");
        }
        let next = Context {
            peer_pid,
            session,
            epoch,
        };
        if self.context == Some(next) {
            return Ok(false);
        }
        *self = Self {
            context: Some(next),
            ..Self::default()
        };
        Ok(true)
    }

    /// Revoke on a real disconnect. A temporary missing publication need not
    /// reset the context; the one-second receipt deadline still applies.
    #[cfg(test)]
    fn clear(&mut self) {
        *self = Self::default();
    }
    #[cfg(test)]
    fn context(&self) -> Option<Context> {
        self.context
    }
    #[cfg(test)]
    fn last_issued(&self) -> u64 {
        self.last_issued
    }
    #[cfg(test)]
    fn acknowledged(&self) -> u64 {
        self.acknowledged
    }
    pub fn capacity(&self) -> usize {
        MAX_PENDING - self.pending.len()
    }

    /// ACK is a consume high-water, including rejected/expired damage, not a
    /// claim about the resulting HP. Older duplicate publications are harmless.
    pub fn accept_ack(&mut self, seq: u64) -> Result<(), &'static str> {
        if self.context.is_none() {
            return Err("incoming peer unavailable");
        }
        if seq > self.last_issued {
            return Err("incoming ACK exceeds issued sequence");
        }
        if seq <= self.acknowledged {
            return Ok(());
        }
        self.acknowledged = seq;
        self.pending.retain(|hit| hit.seq > seq);
        Ok(())
    }

    /// Each genuine native hit gets its own sequence. Equal-valued hits are not
    /// coalesced: vanilla immunity frames must evaluate them independently.
    pub fn push(
        &mut self,
        uuid: &str,
        damage: f32,
        source: &str,
        time_ms: u64,
    ) -> Result<Incoming, &'static str> {
        if self.context.is_none() {
            return Err("incoming peer unavailable");
        }
        if !valid_uuid(uuid)
            || !damage.is_finite()
            || !(0.0001..=1000.).contains(&damage)
            || source.is_empty()
            || source.len() > 128
            || !source
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"_:-./".contains(&c))
            || time_ms == 0
            || time_ms > MAX_SEQUENCE
        {
            return Err("incoming damage invalid");
        }
        let now = self.now.max(time_ms);
        self.prune(now);
        if now - time_ms > RECEIPT_MS {
            return Err("incoming damage already expired");
        }
        if self.capacity() == 0 {
            return Err("incoming damage queue full");
        }
        if self.last_issued == MAX_SEQUENCE {
            return Err("incoming sequence exhausted");
        }
        self.last_issued += 1;
        let hit = Incoming {
            seq: self.last_issued,
            uuid: uuid.into(),
            damage,
            source: source.into(),
            time_ms,
        };
        self.pending.push_back(hit.clone());
        Ok(hit)
    }

    /// Reserving pending damage prevents the latest pre-ACK guest HP from
    /// resurrecting the native proxy. The caller clamps its final HP at zero.
    pub fn pending(&self, uuid: &str) -> f32 {
        self.pending
            .iter()
            .filter(|hit| hit.uuid == uuid)
            .map(|hit| hit.damage)
            .sum()
    }

    pub fn snapshot(&mut self, now: u64) -> Vec<Incoming> {
        self.prune(now);
        self.pending.iter().cloned().collect()
    }

    /// Bounded diagnostics are drained by the caller's existing log path.
    /// Expired entries are never retried under a fresh sequence.
    pub fn drain_expired(&mut self) -> Vec<Expired> {
        self.expired.drain(..).collect()
    }

    fn prune(&mut self, now: u64) {
        self.now = now;
        let mut kept = VecDeque::with_capacity(self.pending.len());
        while let Some(hit) = self.pending.pop_front() {
            let age = now.checked_sub(hit.time_ms);
            if age.is_some_and(|age| age <= RECEIPT_MS) {
                kept.push_back(hit);
                continue;
            }
            self.expired.push_back(Expired {
                seq: hit.seq,
                uuid: hit.uuid,
                damage: hit.damage,
                age_ms: age.unwrap_or(0),
                reason: if age.is_none() {
                    "incoming timestamp ahead of clock"
                } else {
                    "incoming ACK deadline expired"
                },
            });
            while self.expired.len() > MAX_PENDING {
                self.expired.pop_front();
            }
        }
        self.pending = kept;
    }
}

fn valid_uuid(uuid: &str) -> bool {
    uuid.len() == 36
        && uuid.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    const A: &str = "d282ed3c-a5f3-4372-bd3f-40b96b753ea4";
    const B: &str = "7e50d73d-1252-48bd-b9ce-65119f6b7661";
    fn ledger() -> Ledger {
        let mut l = Ledger::new();
        l.reset_context(7, 11, 3).unwrap();
        l
    }

    #[test]
    fn retransmission_keeps_sequence_and_ack_is_idempotent() {
        let mut l = ledger();
        let one = l.push(A, 3., "123", 100).unwrap();
        assert_eq!(l.snapshot(101)[0].seq, one.seq);
        assert_eq!(l.snapshot(102)[0].seq, one.seq);
        assert_eq!(l.pending(A), 3.);
        l.accept_ack(one.seq).unwrap();
        l.accept_ack(one.seq).unwrap();
        l.accept_ack(0).unwrap();
        assert!(l.snapshot(103).is_empty());
        assert_eq!(l.pending(A), 0.);
        assert_eq!(l.acknowledged(), 1);
        assert_eq!(l.push(A, 3., "123", 104).unwrap().seq, 2);
    }

    #[test]
    fn future_ack_cannot_erase_a_reservation() {
        let mut l = ledger();
        l.push(A, 2., "123", 100).unwrap();
        assert!(l.accept_ack(2).is_err());
        assert_eq!(l.pending(A), 2.);
        assert_eq!(l.acknowledged(), 0);
    }

    #[test]
    fn identical_hits_stay_independent_and_capacity_is_bounded() {
        let mut l = ledger();
        for seq in 1..=MAX_PENDING {
            assert_eq!(l.push(A, 1., "123", 100).unwrap().seq, seq as u64);
        }
        assert_eq!(l.capacity(), 0);
        assert!(l.push(A, 1., "123", 100).is_err());
        assert_eq!(l.last_issued(), 128);
        assert_eq!(l.pending(A), 128.);
        l.accept_ack(1).unwrap();
        assert_eq!(l.capacity(), 1);
        assert_eq!(l.push(B, 2., "123", 101).unwrap().seq, 129);
        assert_eq!(l.pending(A), 127.);
        assert_eq!(l.pending(B), 2.);
    }

    #[test]
    fn expiry_releases_hp_and_never_replays_or_reuses_sequence() {
        let mut l = ledger();
        l.push(A, 3., "123", 100).unwrap();
        assert_eq!(l.snapshot(1100).len(), 1);
        assert!(l.snapshot(1101).is_empty());
        assert_eq!(l.pending(A), 0.);
        assert_eq!(l.capacity(), 128);
        let expired = l.drain_expired();
        assert_eq!(expired.len(), 1);
        assert_eq!(expired[0].seq, 1);
        assert_eq!(expired[0].age_ms, 1001);
        assert!(l.snapshot(1102).is_empty());
        assert!(l.drain_expired().is_empty());
        l.accept_ack(1).unwrap();
        assert_eq!(l.push(A, 1., "123", 1103).unwrap().seq, 2);
        assert!(l.push(A, 1., "123", 100).is_err());
    }

    #[test]
    fn pending_health_tracks_per_mob_until_atomic_guest_ack_snapshot() {
        let mut l = ledger();
        l.push(A, 4., "123", 100).unwrap();
        l.push(B, 2., "456", 101).unwrap();
        assert_eq!((20. - l.pending(A)).max(0.), 16.);
        // MC has applied A and advertises HP=16 with ACK=1 in that same snapshot.
        l.accept_ack(1).unwrap();
        assert_eq!((16. - l.pending(A)).max(0.), 16.);
        assert_eq!((20. - l.pending(B)).max(0.), 18.);
        l.push(B, 1000., "456", 102).unwrap();
        assert_eq!((20. - l.pending(B)).max(0.), 0.);
    }

    #[test]
    fn context_changes_clear_old_receipts_but_same_context_does_not() {
        let mut l = ledger();
        l.push(A, 3., "123", 100).unwrap();
        assert!(!l.reset_context(7, 11, 3).unwrap());
        assert_eq!(l.pending(A), 3.);
        for context in [(7, 12, 3), (8, 12, 3), (8, 12, 4)] {
            assert!(l.reset_context(context.0, context.1, context.2).unwrap());
            assert_eq!(l.pending(A), 0.);
            assert!(l.accept_ack(1).is_err());
            assert_eq!(l.push(A, 3., "123", 100).unwrap().seq, 1);
        }
        l.clear();
        assert!(l.push(A, 3., "123", 100).is_err());
        assert!(l.accept_ack(0).is_err());
    }

    #[test]
    fn invalid_inputs_and_sequence_exhaustion_do_not_issue_receipts() {
        let mut l = ledger();
        for damage in [0., -1., 1001., f32::NAN, f32::INFINITY] {
            assert!(l.push(A, damage, "123", 100).is_err());
        }
        assert!(l.push("not-a-uuid", 1., "123", 100).is_err());
        assert!(l.push(A, 1., "", 100).is_err());
        assert!(l.push(A, 1., "123", 0).is_err());
        assert!(l.reset_context(0, 1, 1).is_err());
        assert_eq!(l.last_issued(), 0);
        assert_eq!(l.context().unwrap().peer_pid, 7);
        l.last_issued = MAX_SEQUENCE;
        assert!(l.push(A, 1., "123", 100).is_err());
        assert_eq!(l.capacity(), 128);
    }

    #[test]
    fn future_clock_receipts_are_removed_with_bounded_diagnostics() {
        let mut l = ledger();
        l.push(A, 1., "123", 100).unwrap();
        assert!(l.snapshot(99).is_empty());
        assert_eq!(
            l.drain_expired()[0].reason,
            "incoming timestamp ahead of clock"
        );
        for index in 0..300 {
            let time = 1000 + index * 1002;
            l.push(A, 1., "123", time).unwrap();
            l.snapshot(time + 1001);
        }
        assert_eq!(l.drain_expired().len(), MAX_PENDING);
    }
}
