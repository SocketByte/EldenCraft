//! ECHL/ECHR v1: bounded regeneration receipts from the integrated Minecraft server.
pub const BYTES: usize = 4096;
pub const HOST_MAGIC: u32 = 0x4c484345;
pub const RECEIPT_MAGIC: u32 = 0x52484345;
pub const FRESH_MS: u64 = 1000;
#[derive(Clone, Debug, Default)]
pub struct Host {
    pub flags: u32,
    pub epoch: u64,
    pub map: u32,
    pub hp: f32,
    pub max_hp: f32,
    pub ack_result: u32,
    pub ack_session: u64,
    pub ack_sequence: u64,
}
#[derive(Clone, Debug)]
pub struct Receipt {
    pub sequence: u64,
    pub consumption: u64,
    pub timestamp: u64,
    pub server_tick: u64,
    pub source: u32,
    pub amplifier: u32,
    pub amount: f32,
    pub max_guest_hp: f32,
    pub remaining: u32,
}
#[derive(Clone, Debug)]
pub struct Message {
    pub pid: u32,
    pub flags: u32,
    pub session: u64,
    pub host_pid: u32,
    pub map: u32,
    pub epoch: u64,
    pub host_frame: u64,
    pub receipts: Vec<Receipt>,
}
fn u32_at(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}
fn u64_at(b: &[u8], o: usize) -> u64 {
    u64::from_le_bytes(b[o..o + 8].try_into().unwrap())
}
fn f32_at(b: &[u8], o: usize) -> f32 {
    f32::from_bits(u32_at(b, o))
}
fn put32(b: &mut [u8], o: usize, v: u32) {
    b[o..o + 4].copy_from_slice(&v.to_le_bytes());
}
fn put64(b: &mut [u8], o: usize, v: u64) {
    b[o..o + 8].copy_from_slice(&v.to_le_bytes());
}
pub fn encode_host(
    s: &Host,
    seq: u64,
    frame: u64,
    time: u64,
    pid: u32,
) -> Result<[u8; BYTES], &'static str> {
    if seq == 0
        || seq & 1 != 0
        || frame == 0
        || pid == 0
        || s.flags & !1 != 0
        || s.ack_result > 2
        || !s.hp.is_finite()
        || !s.max_hp.is_finite()
        || s.hp < 0.
        || s.hp > s.max_hp
        || s.max_hp > 10_000_000.
        || (s.flags == 1 && (s.epoch == 0 || s.max_hp <= 0.))
    {
        return Err("invalid healing host");
    }
    let mut b = [0; BYTES];
    put32(&mut b, 0, HOST_MAGIC);
    put32(&mut b, 4, 1);
    put64(&mut b, 8, seq);
    put64(&mut b, 16, frame);
    put64(&mut b, 24, time);
    put32(&mut b, 32, pid);
    put32(&mut b, 36, s.flags);
    put64(&mut b, 40, s.epoch);
    put32(&mut b, 48, s.map);
    put32(&mut b, 52, s.hp.to_bits());
    put32(&mut b, 56, s.max_hp.to_bits());
    put32(&mut b, 60, s.ack_result);
    put64(&mut b, 64, s.ack_session);
    put64(&mut b, 72, s.ack_sequence);
    Ok(b)
}
pub fn decode(b: &[u8], now: u64) -> Result<Message, &'static str> {
    if b.len() != BYTES {
        return Err("healing message size");
    }
    let timestamp = u64_at(b, 24);
    let count = u32_at(b, 72) as usize;
    if u32_at(b, 0) != RECEIPT_MAGIC
        || u32_at(b, 4) != 1
        || u64_at(b, 8) == 0
        || u64_at(b, 8) & 1 != 0
        || u64_at(b, 16) == 0
        || timestamp > now
        || now - timestamp > FRESH_MS
        || u32_at(b, 32) == 0
        || u32_at(b, 36) & !1 != 0
        || u64_at(b, 40) == 0
        || count > 8
        || b[76..128].iter().any(|v| *v != 0)
        || b[128 + count * 64..].iter().any(|v| *v != 0)
    {
        return Err("invalid healing header");
    }
    let mut receipts = Vec::with_capacity(count);
    let mut previous = 0;
    for n in 0..count {
        let at = 128 + n * 64;
        let r = Receipt {
            sequence: u64_at(b, at),
            consumption: u64_at(b, at + 8),
            timestamp: u64_at(b, at + 16),
            server_tick: u64_at(b, at + 24),
            source: u32_at(b, at + 32),
            amplifier: u32_at(b, at + 36),
            amount: f32_at(b, at + 40),
            max_guest_hp: f32_at(b, at + 44),
            remaining: u32_at(b, at + 48),
        };
        if r.sequence <= previous
            || r.consumption == 0
            || r.timestamp > timestamp
            || r.timestamp > now
            || now - r.timestamp > FRESH_MS
            || r.source != 1
            || r.amplifier != 1
            || r.amount != 1.
            || !r.max_guest_hp.is_finite()
            || !(1.0..=1024.).contains(&r.max_guest_hp)
            || !matches!(r.remaining, 25 | 50 | 75 | 100)
            || b[at + 52..at + 64].iter().any(|v| *v != 0)
        {
            return Err("invalid healing receipt");
        }
        previous = r.sequence;
        receipts.push(r);
    }
    Ok(Message {
        pid: u32_at(b, 32),
        flags: u32_at(b, 36),
        session: u64_at(b, 40),
        host_pid: u32_at(b, 48),
        map: u32_at(b, 52),
        epoch: u64_at(b, 56),
        host_frame: u64_at(b, 64),
        receipts,
    })
}
/// Admission state survives inactive frames. Epoch changes invalidate old receipts
/// without forgetting retired guest identities and accidentally replaying them.
#[derive(Default)]
pub struct Admission {
    pub session: u64,
    pub sequence: u64,
    pub result: u32,
    guest_pid: u32,
    retired: Vec<(u32, u64, u64)>,
    consumption: u64,
    last_tick: u64,
    last_remaining: u32,
    pulses: u32,
}
impl Admission {
    pub fn observe(&mut self, pid: u32, session: u64, now: u64) -> bool {
        if self.guest_pid == pid && self.session == session {
            return true;
        }
        self.retired.retain(|(_, _, until)| now < *until);
        if self
            .retired
            .iter()
            .any(|(p, s, _)| *p == pid && *s == session)
            || self.retired.len() >= 64
        {
            return false;
        }
        if self.session != 0 {
            self.retired.push((
                self.guest_pid,
                self.session,
                now.saturating_add(FRESH_MS * 2),
            ));
        }
        self.guest_pid = pid;
        self.session = session;
        self.sequence = 0;
        self.result = 0;
        self.consumption = 0;
        self.last_tick = 0;
        self.last_remaining = 0;
        self.pulses = 0;
        true
    }
    pub fn consume(&mut self, r: &Receipt) -> Result<(), &'static str> {
        if r.sequence <= self.sequence {
            return Err("healing receipt already consumed");
        }
        self.sequence = r.sequence;
        self.result = 2; // consume before any native mutation
        if r.consumption < self.consumption {
            return Err("retired apple consumption");
        }
        if r.consumption > self.consumption {
            self.consumption = r.consumption;
            self.last_tick = 0;
            self.last_remaining = 101;
            self.pulses = 0;
        }
        if self.pulses >= 4
            || r.remaining >= self.last_remaining
            || (self.pulses > 0 && r.server_tick.saturating_sub(self.last_tick) < 25)
        {
            return Err("regeneration cadence or budget");
        }
        self.last_tick = r.server_tick;
        self.last_remaining = r.remaining;
        self.pulses += 1;
        Ok(())
    }
}
pub fn restored_hp(hp: i32, max_hp: i32, r: &Receipt) -> Result<i32, &'static str> {
    if hp <= 0
        || max_hp <= 0
        || hp > max_hp
        || max_hp > 10_000_000
        || r.amount != 1.
        || !r.max_guest_hp.is_finite()
        || !(1.0..=1024.).contains(&r.max_guest_hp)
    {
        return Err("invalid live healing health");
    }
    let amount = ((r.amount / r.max_guest_hp) * max_hp as f32)
        .round()
        .max(1.) as i32;
    Ok(hp.saturating_add(amount).min(max_hp))
}
#[cfg(test)]
mod tests {
    use super::*;
    fn r(seq: u64, tick: u64, remaining: u32) -> Receipt {
        Receipt {
            sequence: seq,
            consumption: 1,
            timestamp: 1000,
            server_tick: tick,
            source: 1,
            amplifier: 1,
            amount: 1.,
            max_guest_hp: 20.,
            remaining,
        }
    }
    fn fixture() -> [u8; BYTES] {
        let mut b = [0; BYTES];
        put32(&mut b, 0, RECEIPT_MAGIC);
        put32(&mut b, 4, 1);
        put64(&mut b, 8, 2);
        put64(&mut b, 16, 1);
        put64(&mut b, 24, 1000);
        put32(&mut b, 32, 20);
        put32(&mut b, 36, 1);
        put64(&mut b, 40, 4);
        put32(&mut b, 48, 10);
        put32(&mut b, 52, 1);
        put64(&mut b, 56, 2);
        put64(&mut b, 64, 2);
        put32(&mut b, 72, 1);
        put64(&mut b, 128, 1);
        put64(&mut b, 136, 1);
        put64(&mut b, 144, 1000);
        put64(&mut b, 152, 100);
        put32(&mut b, 160, 1);
        put32(&mut b, 164, 1);
        put32(&mut b, 168, 1f32.to_bits());
        put32(&mut b, 172, 20f32.to_bits());
        put32(&mut b, 176, 100);
        b
    }
    #[test]
    fn abi_and_freshness() {
        let b = fixture();
        let m = decode(&b, 1001).unwrap();
        assert_eq!(
            (m.pid, m.host_pid, m.session, m.epoch, m.host_frame),
            (20, 10, 4, 2, 2)
        );
        assert!(decode(&b, 999).is_err());
        assert!(decode(&b, 2001).is_err());
        assert!(decode(&b[..2048], 1000).is_err());
        let mut bad = b;
        bad[180] = 1;
        assert!(decode(&bad, 1000).is_err());
    }
    #[test]
    fn rejects_unbounded_or_non_golden_regeneration() {
        for (offset, value) in [
            (72, 9),
            (164, 0),
            (168, f32::NAN.to_bits()),
            (168, 2f32.to_bits()),
            (172, 0),
            (176, 101),
            (160, 2),
        ] {
            let mut b = fixture();
            put32(&mut b, offset, value);
            assert!(decode(&b, 1000).is_err());
        }
    }
    #[test]
    fn four_real_pulses_once_each() {
        let mut a = Admission::default();
        assert!(a.observe(20, 4, 1000));
        for n in 0..4 {
            let pulse = r(n + 1, 100 + n * 25, 100 - n as u32 * 25);
            assert!(a.consume(&pulse).is_ok());
            assert!(a.consume(&pulse).is_err());
        }
        assert!(a.consume(&r(5, 200, 1)).is_err());
    }
    #[test]
    fn cadence_replay_and_retired_session_rejected() {
        let mut a = Admission::default();
        a.observe(20, 4, 1000);
        a.consume(&r(1, 100, 100)).unwrap();
        assert!(a.consume(&r(2, 101, 75)).is_err());
        assert!(a.consume(&r(3, 125, 100)).is_err());
        assert!(a.observe(20, 5, 1000));
        assert!(!a.observe(20, 4, 1001));
    }
    #[test]
    fn retired_session_storage_expires_after_receipts() {
        let mut a = Admission::default();
        for n in 1..200 {
            assert!(a.observe(20, n, 1000 * n));
            assert!(a.retired.len() <= 3);
        }
    }
    #[test]
    fn normalization_clamps_and_never_resurrects() {
        let pulse = r(1, 1, 100);
        assert_eq!(restored_hp(600, 1200, &pulse).unwrap(), 660);
        assert_eq!(restored_hp(1190, 1200, &pulse).unwrap(), 1200);
        assert!(restored_hp(0, 1200, &pulse).is_err());
        assert!(restored_hp(1300, 1200, &pulse).is_err());
    }
    #[test]
    fn host_abi() {
        let b = encode_host(
            &Host {
                flags: 1,
                epoch: 9,
                map: 3,
                hp: 600.,
                max_hp: 1200.,
                ack_result: 1,
                ack_session: 4,
                ack_sequence: 2,
            },
            2,
            19,
            1000,
            10,
        )
        .unwrap();
        assert_eq!(u32_at(&b, 0), HOST_MAGIC);
        assert_eq!(f32_at(&b, 52), 600.);
        assert_eq!(u64_at(&b, 72), 2);
        assert!(b[80..].iter().all(|v| *v == 0));
    }
}
