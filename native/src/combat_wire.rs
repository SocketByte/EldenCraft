//! Bounded Minecraft-authoritative melee protocol; no game references or damage writes.
pub const BYTES: usize = 4096;
pub const TARGET_MAGIC: u32 = 0x47544345;
pub const DAMAGE_MAGIC: u32 = 0x4d444345;
pub const ACTIVE: u32 = 1;
pub const DAMAGE_READY: u32 = 2;
pub const GROUNDED: u32 = 4;
pub const DEBUG_BOUNDS: u32 = 8;
pub const FRESH_MS: u64 = 250;
pub const MAX_TARGETS: usize = 16;
pub const MAX_RECEIPTS: usize = 32;

#[derive(Clone, Debug, Default)]
pub struct Target {
    pub handle: u64,
    pub generation: u64,
    pub min: [f32; 3],
    pub max: [f32; 3],
    pub hp: f32,
    pub max_hp: f32,
    pub flags: u32,
    pub team: u32,
}
#[derive(Clone, Debug)]
pub struct Targets {
    pub flags: u32,
    pub epoch: u64,
    pub map: u32,
    pub camera: [f32; 3],
    pub forward: [f32; 3],
    pub yaw: f32,
    pub pitch: f32,
    pub obstruction: f32,
    pub damage_scale: f32,
    pub ack_session: u64,
    pub ack_receipt: u64,
    pub ack_result: u32,
    pub targets: Vec<Target>,
}
impl Default for Targets {
    fn default() -> Self {
        Self {
            flags: 0,
            epoch: 1,
            map: 0,
            camera: [0.; 3],
            forward: [0., 0., 1.],
            yaw: 0.,
            pitch: 0.,
            obstruction: 16.,
            damage_scale: 50.,
            ack_session: 0,
            ack_receipt: 0,
            ack_result: 0,
            targets: vec![],
        }
    }
}
fn put32(b: &mut [u8], at: usize, n: u32) {
    b[at..at + 4].copy_from_slice(&n.to_le_bytes());
}
fn put64(b: &mut [u8], at: usize, n: u64) {
    b[at..at + 8].copy_from_slice(&n.to_le_bytes());
}
fn putf(b: &mut [u8], at: usize, n: f32) {
    put32(b, at, n.to_bits());
}
fn n32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn n64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}
fn nf(b: &[u8], at: usize) -> f32 {
    f32::from_bits(n32(b, at))
}
fn finite(v: f32, lo: f32, hi: f32) -> bool {
    v.is_finite() && (lo..=hi).contains(&v)
}
fn zero(b: &[u8]) -> bool {
    b.iter().all(|n| *n == 0)
}
fn item_id(id: &str) -> bool {
    let Some((namespace, path)) = id.split_once(':') else {
        return false;
    };
    let basic = |n: u8| n.is_ascii_lowercase() || n.is_ascii_digit() || b"_.-".contains(&n);
    !namespace.is_empty()
        && !path.is_empty()
        && namespace.bytes().all(basic)
        && path.bytes().all(|n| basic(n) || n == b'/')
}
fn empty_handle(handle: u64) -> bool {
    handle as u32 == u32::MAX
}

pub fn encode_targets(
    s: &Targets,
    sequence: u64,
    frame: u64,
    time: u64,
    pid: u32,
) -> Result<[u8; BYTES], &'static str> {
    if sequence == 0
        || sequence & 1 != 0
        || frame == 0
        || pid == 0
        || s.epoch == 0
        || s.flags & !15 != 0
        || s.flags & DAMAGE_READY != 0 && s.flags & ACTIVE == 0
        || s.targets.len() > MAX_TARGETS
        || s.ack_result > 2
        || !finite(s.obstruction, 0., 16.)
        || !finite(s.damage_scale, 0.001, 10000.)
        || !finite(s.yaw, -360., 360.)
        || !finite(s.pitch, -90., 90.)
        || s.camera.iter().any(|v| !finite(*v, -64., 64.))
        || s.forward.iter().any(|v| !finite(*v, -1., 1.))
        || (s.forward.iter().map(|v| v * v).sum::<f32>() - 1.).abs() > 0.001
    {
        return Err("invalid target header");
    }
    let mut b = [0; BYTES];
    for (at, n) in [
        (0, TARGET_MAGIC),
        (4, 1),
        (32, pid),
        (36, s.flags),
        (48, s.map),
        (52, s.targets.len() as u32),
        (112, s.ack_result),
    ] {
        put32(&mut b, at, n);
    }
    for (at, n) in [
        (8, sequence),
        (16, frame),
        (24, time),
        (40, s.epoch),
        (96, s.ack_session),
        (104, s.ack_receipt),
    ] {
        put64(&mut b, at, n);
    }
    for i in 0..3 {
        putf(&mut b, 56 + i * 4, s.camera[i]);
        putf(&mut b, 68 + i * 4, s.forward[i]);
    }
    for (at, n) in [
        (80, s.yaw),
        (84, s.pitch),
        (88, s.obstruction),
        (92, s.damage_scale),
    ] {
        putf(&mut b, at, n);
    }
    let mut identities = std::collections::HashSet::new();
    for (i, t) in s.targets.iter().enumerate() {
        if empty_handle(t.handle)
            || t.generation == 0
            || !identities.insert(t.handle)
            || t.flags & !3 != 0
            || !finite(t.hp, 0., 10_000_000.)
            || !finite(t.max_hp, 1., 10_000_000.)
            || t.hp > t.max_hp
            || (0..3).any(|j| {
                !finite(t.min[j], -64., 64.) || !finite(t.max[j], -64., 64.) || t.min[j] >= t.max[j]
            })
        {
            return Err("invalid target entry");
        }
        let at = 128 + i * 80;
        put64(&mut b, at, t.handle);
        put64(&mut b, at + 8, t.generation);
        for j in 0..3 {
            putf(&mut b, at + 16 + j * 4, t.min[j]);
            putf(&mut b, at + 28 + j * 4, t.max[j]);
        }
        putf(&mut b, at + 40, t.hp);
        putf(&mut b, at + 44, t.max_hp);
        put32(&mut b, at + 48, t.flags);
        put32(&mut b, at + 52, t.team);
    }
    Ok(b)
}

#[derive(Clone, Debug, PartialEq)]
pub struct Receipt {
    pub sequence: u64,
    pub target: u64,
    pub generation: u64,
    pub host_frame: u64,
    pub timestamp: u64,
    pub attack: u64,
    pub damage: f32,
    pub durability_before: i32,
    pub durability_after: i32,
    pub guest_max_hp: f32,
    pub item: String,
}
#[derive(Clone, Debug)]
pub struct Damage {
    pub sequence: u64,
    pub frame: u64,
    pub timestamp: u64,
    pub pid: u32,
    pub flags: u32,
    pub session: u64,
    pub host_pid: u32,
    pub map: u32,
    pub epoch: u64,
    pub receipts: Vec<Receipt>,
}
pub fn decode_damage(b: &[u8], now: u64) -> Result<Damage, &'static str> {
    if b.len() != BYTES || n32(b, 0) != DAMAGE_MAGIC || n32(b, 4) != 1 {
        return Err("damage header mismatch");
    }
    let sequence = n64(b, 8);
    let frame = n64(b, 16);
    let timestamp = n64(b, 24);
    let pid = n32(b, 32);
    let flags = n32(b, 36);
    let session = n64(b, 40);
    let host_pid = n32(b, 48);
    let map = n32(b, 52);
    let epoch = n64(b, 56);
    let count = n32(b, 64) as usize;
    if sequence == 0
        || sequence & 1 != 0
        || frame == 0
        || pid == 0
        || session == 0
        || host_pid == 0
        || epoch == 0
        || flags & !ACTIVE != 0
        || now < timestamp
        || now - timestamp > FRESH_MS
        || count > MAX_RECEIPTS
        || !zero(&b[68..128])
        || !zero(&b[128 + count * 112..])
    {
        return Err("damage publication stale or malformed");
    }
    let publication_timestamp = timestamp;
    let mut receipts = Vec::with_capacity(count);
    let mut previous = 0;
    for i in 0..count {
        let at = 128 + i * 112;
        let receipt = n64(b, at);
        let target = n64(b, at + 8);
        let generation = n64(b, at + 16);
        let host_frame = n64(b, at + 24);
        let timestamp = n64(b, at + 32);
        let attack = n64(b, at + 40);
        let damage = nf(b, at + 48);
        let before = n32(b, at + 52) as i32;
        let after = n32(b, at + 56) as i32;
        let maximum = nf(b, at + 60);
        let length = n32(b, at + 64) as usize;
        // Expired entries remain decodable so the host can reject and acknowledge them.
        if receipt <= previous
            || empty_handle(target)
            || generation == 0
            || host_frame == 0
            || attack == 0
            || timestamp > publication_timestamp
            || !finite(damage, f32::MIN_POSITIVE, 1000.)
            || before < 0
            || after < 0
            || before > 1_000_000
            || after > 1_000_000
            || !finite(maximum, 1., 2048.)
            || length == 0
            || length > 40
            || !zero(&b[at + 68..at + 72])
            || !zero(&b[at + 72 + length..at + 112])
        {
            return Err("damage receipt rejected");
        }
        let item = std::str::from_utf8(&b[at + 72..at + 72 + length])
            .map_err(|_| "damage item encoding rejected")?;
        if !item_id(item) {
            return Err("damage item ID rejected");
        }
        receipts.push(Receipt {
            sequence: receipt,
            target,
            generation,
            host_frame,
            timestamp,
            attack,
            damage,
            durability_before: before,
            durability_after: after,
            guest_max_hp: maximum,
            item: item.to_owned(),
        });
        previous = receipt;
    }
    Ok(Damage {
        sequence,
        frame,
        timestamp,
        pid,
        flags,
        session,
        host_pid,
        map,
        epoch,
        receipts,
    })
}

/// Consumes receipts before dispatch. A failed native call may never be retried as damage.
#[derive(Default, Debug)]
pub struct Acknowledgments {
    connection: Option<(u32, u64, u32, u64, u32)>,
    last_frame: u64,
    pub session: u64,
    pub receipt: u64,
    pub result: u32,
}
impl Acknowledgments {
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    pub fn admit<'a>(
        &mut self,
        s: &'a Damage,
        host_pid: u32,
        epoch: u64,
        map: u32,
    ) -> Vec<&'a Receipt> {
        if s.flags & ACTIVE == 0 || s.host_pid != host_pid || s.epoch != epoch || s.map != map {
            self.reset();
            return vec![];
        }
        let connection = (s.pid, s.session, host_pid, epoch, map);
        if self.connection != Some(connection) {
            self.reset();
            if !s.receipts.is_empty() {
                return vec![];
            }
            self.connection = Some(connection);
            self.session = s.session;
            self.last_frame = s.frame;
            return vec![];
        }
        if s.frame < self.last_frame {
            self.reset();
            return vec![];
        }
        if s.frame == self.last_frame {
            return vec![];
        }
        self.last_frame = s.frame;
        s.receipts
            .iter()
            .filter(|r| r.sequence > self.receipt)
            .collect()
    }
    pub fn consume(&mut self, receipt: u64) {
        self.receipt = receipt;
        self.result = 2;
    }
    pub fn applied(&mut self) {
        self.result = 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn wire() -> [u8; BYTES] {
        let mut b = [0; BYTES];
        for (at, n) in [
            (0, DAMAGE_MAGIC),
            (4, 1),
            (32, 20),
            (36, ACTIVE),
            (48, 30),
            (52, 4),
            (64, 1),
        ] {
            put32(&mut b, at, n);
        }
        for (at, n) in [
            (8, 2),
            (16, 1),
            (24, 1000),
            (40, 5),
            (56, 6),
            (128, 1),
            (136, 7),
            (144, 8),
            (152, 9),
            (160, 999),
            (168, 1),
        ] {
            put64(&mut b, at, n);
        }
        putf(&mut b, 176, 6.);
        putf(&mut b, 188, 20.);
        put32(&mut b, 184, 1);
        put32(&mut b, 192, 20);
        b[200..220].copy_from_slice(b"minecraft:iron_sword");
        b
    }
    #[test]
    fn wire_bounds_and_reserved_bytes() {
        let b = wire();
        assert_eq!(decode_damage(&b, 1000).unwrap().receipts[0].damage, 6.);
        for at in [68, 199, 220, 4095] {
            let mut c = b;
            c[at] = 1;
            assert!(decode_damage(&c, 1000).is_err());
        }
        let mut c = b;
        put32(&mut c, 64, 33);
        assert!(decode_damage(&c, 1000).is_err());
        assert!(decode_damage(&b, 1251).is_err());
        assert!(decode_damage(&b[..128], 1000).is_err());
    }
    #[test]
    fn future_nan_and_unordered_receipts_rejected() {
        let b = wire();
        for (at, n) in [(176, f32::NAN.to_bits()), (176, 0), (192, 41)] {
            let mut c = b;
            put32(&mut c, at, n);
            assert!(decode_damage(&c, 1000).is_err());
        }
        let mut c = b;
        put64(&mut c, 160, 1001);
        assert!(decode_damage(&c, 1000).is_err());
        let mut c = b;
        put32(&mut c, 64, 2);
        c.copy_within(128..240, 240);
        assert!(decode_damage(&c, 1000).is_err());
    }
    #[test]
    fn session_handshake_and_consumption_never_replay() {
        let mut s = decode_damage(&wire(), 1000).unwrap();
        let mut a = Acknowledgments::default();
        assert!(a.admit(&s, 30, 6, 4).is_empty());
        let r = s.receipts.clone();
        s.receipts.clear();
        a.admit(&s, 30, 6, 4);
        assert_eq!(a.session, 5);
        s.receipts = r;
        s.frame += 1;
        assert_eq!(a.admit(&s, 30, 6, 4).len(), 1);
        a.consume(1);
        assert_eq!(a.result, 2);
        assert!(a.admit(&s, 30, 6, 4).is_empty());
        a.applied();
        assert!(a.admit(&s, 30, 6, 4).is_empty());
        assert!(a.admit(&s, 30, 7, 4).is_empty());
        assert_eq!(a.session, 0);
        assert!(a.admit(&s, 30, 6, 4).is_empty());
    }
    #[test]
    fn target_encoder_requires_unique_bounded_valid_hitboxes() {
        let mut s = Targets::default();
        let mut t = Target {
            handle: 7,
            generation: 1,
            min: [-1., 0., -1.],
            max: [1., 2., 1.],
            hp: 10.,
            max_hp: 10.,
            flags: 3,
            team: 6,
        };
        s.targets.push(t.clone());
        assert!(encode_targets(&s, 2, 1, 1000, 20).is_ok());
        s.targets.push(t.clone());
        assert!(encode_targets(&s, 2, 1, 1000, 20).is_err());
        s.targets.pop();
        t.min[0] = f32::NAN;
        s.targets[0] = t;
        assert!(encode_targets(&s, 2, 1, 1000, 20).is_err());
    }
    #[test]
    fn debug_flag_does_not_authorize_damage() {
        let mut s = Targets {
            flags: ACTIVE | DEBUG_BOUNDS,
            ..Targets::default()
        };
        let b = encode_targets(&s, 2, 1, 1000, 20).unwrap();
        assert_eq!(n32(&b, 36), 9);
        assert_eq!(n32(&b, 36) & DAMAGE_READY, 0);
        s.flags |= 16;
        assert!(encode_targets(&s, 2, 1, 1000, 20).is_err());
    }
    #[test]
    fn item_identifiers_require_namespace_and_path() {
        for id in ["minecraft:iron_sword", "mod-name:path/to.item_1"] {
            assert!(item_id(id));
        }
        for id in [
            "",
            ":",
            "minecraft:",
            ":iron_sword",
            "a:b:c",
            "a/b:c",
            "Minecraft:sword",
            "a:é",
        ] {
            assert!(!item_id(id), "accepted {id}");
            let mut b = wire();
            b[200..240].fill(0);
            put32(&mut b, 192, id.len() as u32);
            b[200..200 + id.len()].copy_from_slice(id.as_bytes());
            assert!(decode_damage(&b, 1000).is_err());
        }
    }
    #[test]
    fn receipt_cannot_postdate_its_publication() {
        let mut b = wire();
        put64(&mut b, 160, 1001);
        assert!(decode_damage(&b, 1002).is_err()); // Not future relative to now, but impossible for this publication.
        put64(&mut b, 160, 1000);
        assert!(decode_damage(&b, 1002).is_ok());
        put64(&mut b, 160, 1);
        assert!(decode_damage(&b, 1002).is_ok()); // Expired receipt still decodes for rejection/ack.
    }
    #[test]
    fn empty_selector_rejected_independent_of_block() {
        for handle in [u64::MAX, 0xffff_ffff, 0x1234_5678_ffff_ffff] {
            let mut b = wire();
            put64(&mut b, 136, handle);
            assert!(decode_damage(&b, 1000).is_err());
            let mut s = Targets::default();
            s.targets.push(Target {
                handle,
                generation: 1,
                min: [0.; 3],
                max: [1.; 3],
                hp: 1.,
                max_hp: 1.,
                ..Target::default()
            });
            assert!(encode_targets(&s, 2, 1, 1000, 20).is_err());
        }
    }
    #[test]
    fn changed_host_context_requires_empty_handshake_before_receipts() {
        for (host_pid, epoch, map) in [(31, 6, 4), (30, 7, 4), (30, 6, 5)] {
            let mut s = decode_damage(&wire(), 1000).unwrap();
            let receipts = s.receipts.clone();
            s.receipts.clear();
            let mut a = Acknowledgments::default();
            a.admit(&s, 30, 6, 4);
            s.host_pid = host_pid;
            s.epoch = epoch;
            s.map = map;
            s.frame += 1;
            s.receipts = receipts.clone();
            assert!(a.admit(&s, host_pid, epoch, map).is_empty());
            assert_eq!(a.session, 0);
            s.receipts.clear();
            s.frame += 1;
            a.admit(&s, host_pid, epoch, map);
            assert_eq!(a.session, s.session);
            s.receipts = receipts;
            s.frame += 1;
            assert_eq!(a.admit(&s, host_pid, epoch, map).len(), 1);
        }
    }
    #[test]
    fn same_frame_cannot_add_or_replace_receipts() {
        let mut s = decode_damage(&wire(), 1000).unwrap();
        let receipts = s.receipts.clone();
        s.receipts.clear();
        let mut a = Acknowledgments::default();
        a.admit(&s, 30, 6, 4);
        s.receipts = receipts;
        assert!(a.admit(&s, 30, 6, 4).is_empty());
        s.frame += 1;
        assert_eq!(a.admit(&s, 30, 6, 4).len(), 1);
        a.consume(1);
        s.receipts[0].sequence = 2;
        assert!(a.admit(&s, 30, 6, 4).is_empty());
        assert_eq!(a.receipt, 1);
        s.frame += 1;
        assert_eq!(a.admit(&s, 30, 6, 4).len(), 1);
    }
}
