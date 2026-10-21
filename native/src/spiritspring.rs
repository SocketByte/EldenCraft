//! Spiritsprings, Elden Ring's small whirlwinds that fling Torrent high into the air.
//!
//! A spring is a `MountJump` region (type 46) in the map's MSB, carrying the leap height. The game
//! tells a mount it is in range by SpEffect 183, but the host character is never natively mounted,
//! so that marker never arrives. Instead the loaded MSB of the rider's map tile is read from
//! memory, its MountJump regions are kept, and the rider's block position is tested against them.
//! The leap itself is taken by the movement model; the landing stays safe through the existing
//! fall-immunity window.
#[cfg(windows)]
use eldenring::cs::PlayerIns;

/// `MSB REGION_PARAM` type of a Spiritspring.
pub const MOUNT_JUMP: u32 = 46;
/// The movement model steps once per Minecraft tick: speed falls by 1.6 m/s, then is damped by
/// 0.98, and the tick's distance is speed times 0.05 s.
const TICK_S: f32 = 0.05;
const GRAVITY_PER_TICK: f32 = 1.6;
const DRAG_PER_TICK: f32 = 0.98;
/// Fall-immune and leap SpEffects the game applies to a mount in a spring (183 to 186). Seeing
/// one on the rider also keeps the landing safe.
pub const LEAP_STATES: std::ops::RangeInclusive<i32> = 183..=186;
/// A m60 overworld tile is 256 m on each side.
const TILE_M: f32 = 256.0;

/// How high a launch at `speed` m/s rises under the movement model.
fn apex(speed: f32) -> f32 {
    let (mut v, mut rise) = (speed, 0.0);
    while v > 0. {
        rise += v * TICK_S;
        v = (v - GRAVITY_PER_TICK) * DRAG_PER_TICK;
    }
    rise
}

/// A leap rises this many times the region's authored height, which felt far too low in play.
pub const LEAP_SCALE: f32 = 3.0;
/// Seconds after a leap before the next one is allowed.
pub const RELAUNCH_S: f32 = 0.6;

/// Takeoff speed whose apex is `LEAP_SCALE` times the authored `height` metres under the movement
/// model.
pub fn launch_speed(height: f32) -> f32 {
    if !height.is_finite() {
        return 0.;
    }
    let height = (height * LEAP_SCALE).clamp(2., 300.);
    let (mut low, mut high) = (1.0f32, 800.0f32);
    for _ in 0..40 {
        let mid = (low + high) / 2.;
        if apex(mid) < height {
            low = mid;
        } else {
            high = mid;
        }
    }
    high
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Shape {
    Point,
    Circle(f32),
    Sphere(f32),
    Cylinder(f32, f32),
    Rect(f32, f32),
    Box(f32, f32, f32),
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spring {
    pub position: [f32; 3],
    /// Degrees about the vertical axis.
    pub yaw: f32,
    pub shape: Shape,
    /// Apex of the leap in metres.
    pub height: f32,
}

/// Slack around every region, so a rider's feet just outside the authored edge still count.
const SLACK_M: f32 = 0.75;
/// Regions are volumes standing on their position; a rider this far above is out of reach.
const REACH_ABOVE_M: f32 = 3.0;

impl Spring {
    /// Whether a rider whose feet are at `at` (the region's block-local frame) is inside.
    pub fn contains(&self, at: [f32; 3]) -> bool {
        let dx = at[0] - self.position[0];
        let dy = at[1] - self.position[1];
        let dz = at[2] - self.position[2];
        let horizontal = (dx * dx + dz * dz).sqrt();
        let yaw = self.yaw.to_radians();
        // Rotate into the region's frame.
        let (sin, cos) = yaw.sin_cos();
        let local_x = dx * cos - dz * sin;
        let local_z = dx * sin + dz * cos;
        let low = -SLACK_M;
        match self.shape {
            Shape::Point => dx * dx + dy * dy + dz * dz <= 2.0 * 2.0,
            Shape::Circle(r) => {
                horizontal <= r + SLACK_M && (low..=REACH_ABOVE_M + 8.).contains(&dy)
            }
            Shape::Sphere(r) => dx * dx + dy * dy + dz * dz <= (r + SLACK_M) * (r + SLACK_M),
            Shape::Cylinder(r, h) => {
                horizontal <= r + SLACK_M && (low..=h + REACH_ABOVE_M).contains(&dy)
            }
            Shape::Rect(w, d) => {
                local_x.abs() <= w / 2. + SLACK_M
                    && local_z.abs() <= d / 2. + SLACK_M
                    && (low..=REACH_ABOVE_M + 8.).contains(&dy)
            }
            Shape::Box(w, d, h) => {
                local_x.abs() <= w / 2. + SLACK_M
                    && local_z.abs() <= d / 2. + SLACK_M
                    && (low..=h + REACH_ABOVE_M).contains(&dy)
            }
        }
    }
}

/// Random-access view of one loaded MSB image. Offsets are file offsets.
pub trait Bytes {
    fn read(&self, offset: usize, out: &mut [u8]) -> bool;
    /// What a file offset becomes if the loader rewrote offsets as addresses.
    fn absolute(&self, offset: usize) -> i64 {
        offset as i64
    }
}

/// Where a UTF-16 `needle` first occurs in the readable start of the file.
fn find_text(src: &impl Bytes, needle: &str) -> Option<usize> {
    let wide: Vec<u8> = needle
        .encode_utf16()
        .flat_map(|u| u.to_le_bytes())
        .collect();
    const CHUNK: usize = 4096;
    let mut window = vec![0u8; CHUNK + wide.len()];
    for chunk in 0..2048 {
        let base = chunk * CHUNK;
        // Overlap by the needle so a hit across a chunk boundary is found.
        // The last readable stretch may be shorter than a chunk, so settle for what can be read.
        let len = [window.len(), CHUNK, 2048, 1024, 512, 256, 128, 64]
            .into_iter()
            .find(|&len| len >= wide.len() && src.read(base, &mut window[..len]))?;
        if let Some(at) = window[..len]
            .windows(wide.len())
            .position(|w| w == wide.as_slice())
        {
            return Some(base + at);
        }
    }
    None
}

fn i32_at(src: &impl Bytes, offset: usize) -> Option<i32> {
    let mut b = [0u8; 4];
    src.read(offset, &mut b).then(|| i32::from_le_bytes(b))
}
fn f32_at(src: &impl Bytes, offset: usize) -> Option<f32> {
    i32_at(src, offset)
        .map(|v| f32::from_bits(v as u32))
        .filter(|v| v.is_finite())
}
fn i64_at(src: &impl Bytes, offset: usize) -> Option<i64> {
    let mut b = [0u8; 8];
    src.read(offset, &mut b).then(|| i64::from_le_bytes(b))
}
/// A short NUL-terminated UTF-16 name.
fn name_at(src: &impl Bytes, offset: usize) -> Option<String> {
    let mut units = Vec::new();
    for i in 0..48 {
        let mut b = [0u8; 2];
        if !src.read(offset + i * 2, &mut b) {
            return None;
        }
        let unit = u16::from_le_bytes(b);
        if unit == 0 {
            return String::from_utf16(&units).ok();
        }
        units.push(unit);
    }
    None
}

#[derive(Debug, Default, PartialEq)]
pub struct Parsed {
    pub springs: Vec<Spring>,
    /// Every region type in the file with its count, for the diagnostic log.
    pub types: Vec<(u32, usize)>,
    /// Hex of the first MountJump regions that could not be read, to learn their real layout.
    pub failures: Vec<String>,
}

/// Hex of up to `len` bytes at `offset`, for diagnostics.
pub fn hex(src: &impl Bytes, offset: usize, len: usize) -> String {
    let mut bytes = vec![0u8; len];
    if !src.read(offset, &mut bytes) {
        return "unreadable".into();
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Reads the MountJump regions of an MSBE file (`MSB ` header, then chained params).
pub fn parse(src: &impl Bytes) -> Result<Parsed, &'static str> {
    let mut magic = [0u8; 4];
    if !src.read(0, &mut magic) || &magic != b"MSB " {
        return Err("not an MSB image");
    }
    match parse_chain(src) {
        Ok(parsed) => Ok(parsed),
        // If the chain of params is not where it was expected, find the POINT_PARAM_ST name in
        // the file and take the header that points at it.
        Err(reason) => parse_by_search(src).ok_or(reason),
    }
}

fn parse_chain(src: &impl Bytes) -> Result<Parsed, &'static str> {
    let mut at = 0x10usize;
    for _ in 0..24 {
        let count = i32_at(src, at + 4).ok_or("MSB param header unreadable")?;
        let name_offset = i64_at(src, at + 8).ok_or("MSB param header unreadable")?;
        if !(1..=200_000).contains(&count) || name_offset <= 0 {
            return Err("MSB param header invalid");
        }
        let name = name_at(src, name_offset as usize).ok_or("MSB param name unreadable")?;
        if name == "POINT_PARAM_ST" {
            return Ok(point_entries(src, at, count as usize - 1));
        }
        let next =
            i64_at(src, at + 16 + 8 * (count as usize - 1)).ok_or("MSB param chain unreadable")?;
        if next <= at as i64 {
            break;
        }
        at = next as usize;
    }
    Err("MSB has no POINT_PARAM_ST")
}

fn parse_by_search(src: &impl Bytes) -> Option<Parsed> {
    let name_at = find_text(src, "POINT_PARAM_ST")?;
    let targets = [name_at as i64, src.absolute(name_at)];
    // The header holds the name's offset eight bytes in.
    for slot in (8..name_at).step_by(8) {
        let value = i64_at(src, slot)?;
        if !targets.contains(&value) {
            continue;
        }
        let at = slot - 8;
        let count = i32_at(src, at + 4)?;
        if (1..=200_000).contains(&count) {
            return Some(point_entries(src, at, count as usize - 1));
        }
    }
    None
}

/// The regions of a POINT_PARAM_ST whose header sits at `at` and has `entries` offsets.
fn point_entries(src: &impl Bytes, at: usize, entries: usize) -> Parsed {
    let mut parsed = Parsed::default();
    for index in 0..entries {
        let Some(start) = i64_at(src, at + 16 + 8 * index) else {
            continue;
        };
        if start <= 0 {
            continue;
        }
        let start = start as usize;
        let Some(kind) = i32_at(src, start + 8) else {
            continue;
        };
        let kind = kind as u32;
        match parsed.types.iter_mut().find(|(k, _)| *k == kind) {
            Some((_, n)) => *n += 1,
            None => parsed.types.push((kind, 1)),
        }
        if kind != MOUNT_JUMP {
            continue;
        }
        match spring_at(src, start) {
            Some(spring) => parsed.springs.push(spring),
            None if parsed.failures.len() < 3 => {
                let at = |slot: usize| {
                    i64_at(src, start + slot)
                        .and_then(|value| target(start, value))
                        .unwrap_or(0)
                };
                parsed.failures.push(format!(
                    "region {}; shape data {}; type data {}",
                    hex(src, start, 0x68),
                    hex(src, at(0x48), 0x18),
                    hex(src, at(0x58), 0x10)
                ));
            }
            None => {}
        }
    }
    parsed.types.sort_unstable();
    parsed
}

/// One MountJump region: base fields at +0x00..0x64, shape data and type data by offset.
fn spring_at(src: &impl Bytes, start: usize) -> Option<Spring> {
    let shape_kind = i32_at(src, start + 0x10)?;
    let position = [
        f32_at(src, start + 0x14)?,
        f32_at(src, start + 0x18)?,
        f32_at(src, start + 0x1c)?,
    ];
    let yaw = f32_at(src, start + 0x20 + 4)?;
    let shape_at = target(start, i64_at(src, start + 0x48)?)?;
    let type_at = target(start, i64_at(src, start + 0x58)?)?;
    let dim = |n: usize| f32_at(src, shape_at + 4 * n).filter(|v| (0. ..=2000.).contains(v));
    // MSB.ShapeType: Point 0, Circle 1, Sphere 2, Cylinder 3, Rect 4, Box 5.
    let shape = match shape_kind {
        0 => Shape::Point,
        1 => Shape::Circle(dim(0)?),
        2 => Shape::Sphere(dim(0)?),
        3 => Shape::Cylinder(dim(0)?, dim(1)?),
        4 => Shape::Rect(dim(0)?, dim(1)?),
        5 => Shape::Box(dim(0)?, dim(1)?, dim(2)?),
        _ => return None,
    };
    let height = f32_at(src, type_at)?;
    (height.is_finite() && (1. ..=500.).contains(&height)).then_some(Spring {
        position,
        yaw,
        shape,
        height,
    })
}

/// Where a region's shape or type data lives. The loader turns the file's offsets (relative to the
/// region) into absolute addresses in place; a small value is still a relative offset.
fn target(start: usize, value: i64) -> Option<usize> {
    if value > 0x1000_0000 {
        usize::try_from(value).ok()
    } else if (1..0x10000).contains(&value) {
        Some(start + value as usize)
    } else {
        None
    }
}

/// `m60_42_36_00` from a block id such as 0x3c2a2400.
pub fn block_name(block: i32) -> String {
    let b = (block as u32).to_be_bytes();
    format!("m{:02}_{:02}_{:02}_{:02}", b[0], b[1], b[2], b[3])
}

/// Overworld tiles whose regions may lie within reach of `block`, as (block id, shift of its
/// frame relative to this one, in metres). The tile itself comes first.
pub fn neighbouring_blocks(block: i32) -> Vec<(i32, [f32; 2])> {
    let b = (block as u32).to_be_bytes();
    let mut out = vec![(block, [0., 0.])];
    if matches!(b[0], 60 | 61) && b[3] == 0 {
        for dx in -1i32..=1 {
            for dz in -1i32..=1 {
                if dx == 0 && dz == 0 {
                    continue;
                }
                let (Some(x), Some(z)) = (
                    u8::try_from(i32::from(b[1]) + dx).ok(),
                    u8::try_from(i32::from(b[2]) + dz).ok(),
                ) else {
                    continue;
                };
                out.push((
                    i32::from_be_bytes([b[0], x, z, 0]),
                    [dx as f32 * TILE_M, dz as f32 * TILE_M],
                ));
            }
        }
    }
    out
}

/// Sorted ids of every SpEffect on the local player.
///
/// # Safety
/// Game task only; no outstanding references to the player.
#[cfg(windows)]
pub unsafe fn effects() -> Vec<i32> {
    let mut ids: Vec<i32> = unsafe { PlayerIns::local_player() }
        .map(|player| {
            player
                .chr_ins
                .special_effect
                .entries()
                .take(128)
                .map(|effect| effect.param_id)
                .collect()
        })
        .unwrap_or_default();
    ids.sort_unstable();
    ids.dedup();
    ids
}

/// The game is running (or about to run) a spring leap of its own.
pub fn leaping(effects: &[i32]) -> bool {
    effects.iter().any(|id| LEAP_STATES.contains(id))
}

#[cfg(windows)]
pub use live::Tracker;

#[cfg(windows)]
mod live {
    use super::*;
    use eldenring::cs::MsbRepository;
    use fromsoftware_shared::FromStatic;
    use std::collections::HashMap;
    use std::time::{Duration, Instant};

    #[repr(C)]
    struct MemoryInfo {
        base: usize,
        allocation_base: usize,
        allocation_protect: u32,
        partition: u16,
        size: usize,
        state: u32,
        protect: u32,
        kind: u32,
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn VirtualQuery(address: usize, info: *mut MemoryInfo, length: usize) -> usize;
    }
    const COMMITTED: u32 = 0x1000;
    const READABLE: u32 = 0x02 | 0x04 | 0x08 | 0x20 | 0x40 | 0x80;
    const GUARDED: u32 = 0x100 | 0x01;

    /// Whether `[address, address + length)` is committed, readable memory.
    fn readable(address: usize, length: usize) -> bool {
        let Some(end) = address.checked_add(length) else {
            return false;
        };
        let mut at = address;
        while at < end {
            let mut info = MemoryInfo {
                base: 0,
                allocation_base: 0,
                allocation_protect: 0,
                partition: 0,
                size: 0,
                state: 0,
                protect: 0,
                kind: 0,
            };
            let got = unsafe { VirtualQuery(at, &mut info, std::mem::size_of::<MemoryInfo>()) };
            if got == 0
                || info.state != COMMITTED
                || info.protect & READABLE == 0
                || info.protect & GUARDED != 0
            {
                return false;
            }
            at = info.base.saturating_add(info.size);
        }
        true
    }

    /// A loaded MSB, copied page by page as the parser reaches it. The readable extent after the
    /// image is measured once; parsing a tile touches a few pages of it, where copying the whole
    /// extent took several milliseconds of the game thread for every tile read.
    /// Its offsets may have been turned into absolute addresses in place; no real file offset
    /// comes near 256 MB, so a larger one is read as an address.
    struct Snapshot {
        base: usize,
        /// Readable bytes after `base`, up to the limit.
        len: usize,
        pages: std::cell::RefCell<HashMap<usize, Box<[u8]>>>,
    }
    /// A tile MSB is a few megabytes at most.
    const SNAPSHOT_LIMIT: usize = 24 << 20;
    const PAGE: usize = 4096;

    impl Snapshot {
        /// Measures the readable memory that follows `base`, up to the limit.
        fn take(base: usize) -> Snapshot {
            let mut len = 0;
            let mut at = base;
            while len < SNAPSHOT_LIMIT {
                let mut info = MemoryInfo {
                    base: 0,
                    allocation_base: 0,
                    allocation_protect: 0,
                    partition: 0,
                    size: 0,
                    state: 0,
                    protect: 0,
                    kind: 0,
                };
                let got = unsafe { VirtualQuery(at, &mut info, std::mem::size_of::<MemoryInfo>()) };
                if got == 0
                    || info.state != COMMITTED
                    || info.protect & READABLE == 0
                    || info.protect & GUARDED != 0
                {
                    break;
                }
                let end = info.base.saturating_add(info.size);
                let take = end.saturating_sub(at).min(SNAPSHOT_LIMIT - len);
                if take == 0 {
                    break;
                }
                len += take;
                at += take;
            }
            Snapshot {
                base,
                len,
                pages: Default::default(),
            }
        }

        /// Bytes copied so far.
        fn copied(&self) -> usize {
            self.pages.borrow().values().map(|page| page.len()).sum()
        }
    }
    impl Bytes for Snapshot {
        fn read(&self, offset: usize, out: &mut [u8]) -> bool {
            let offset = if offset > 0x1000_0000 {
                match offset.checked_sub(self.base) {
                    Some(relative) => relative,
                    None => return false,
                }
            } else {
                offset
            };
            if offset
                .checked_add(out.len())
                .is_none_or(|end| end > self.len)
            {
                return false;
            }
            let mut pages = self.pages.borrow_mut();
            let mut done = 0;
            while done < out.len() {
                let at = offset + done;
                let first = at / PAGE * PAGE;
                let page = pages.entry(first).or_insert_with(|| {
                    let size = PAGE.min(self.len - first);
                    let mut bytes = vec![0u8; size].into_boxed_slice();
                    unsafe {
                        std::ptr::copy_nonoverlapping(
                            (self.base + first) as *const u8,
                            bytes.as_mut_ptr(),
                            size,
                        )
                    };
                    bytes
                });
                let from = at - first;
                let count = (page.len() - from).min(out.len() - done);
                out[done..done + count].copy_from_slice(&page[from..from + count]);
                done += count;
            }
            true
        }
        fn absolute(&self, offset: usize) -> i64 {
            (self.base + offset) as i64
        }
    }

    fn is_msb(address: usize) -> bool {
        address != 0 && readable(address, 4) && unsafe { *(address as *const [u8; 4]) } == *b"MSB "
    }

    /// The file image behind a loaded MSB file cap, found by looking for an `MSB ` header at the
    /// pointers in the cap and one level beneath them. Returns the image and where it was found.
    unsafe fn find_image(cap: usize, size: usize) -> Option<(usize, String)> {
        let words = |at: usize, len: usize| -> Vec<usize> {
            if !readable(at, len) {
                return Vec::new();
            }
            (0..len / 8)
                .map(|i| unsafe { *((at + i * 8) as *const usize) })
                .collect()
        };
        let direct = words(cap, size);
        for (i, &p) in direct.iter().enumerate() {
            if is_msb(p) {
                return Some((p, format!("cap+{:#x}", i * 8)));
            }
        }
        for (i, &p) in direct.iter().enumerate() {
            if p < 0x10000 || p % 8 != 0 {
                continue;
            }
            for (j, &q) in words(p, 0x100).iter().enumerate() {
                if is_msb(q) {
                    return Some((q, format!("cap+{:#x}->+{:#x}", i * 8, j * 8)));
                }
            }
        }
        None
    }

    /// What one tile's MSB yielded, kept so a rider hovering on a tile edge does not rescan.
    struct Tile {
        springs: Vec<Spring>,
        at: Instant,
    }

    /// Keeps the MountJump regions around the rider's tile and reports the one the rider is in.
    #[derive(Default)]
    pub struct Tracker {
        tiles: HashMap<i32, Tile>,
        block: i32,
        springs: Vec<(Spring, [f32; 2])>,
        last_in: Option<usize>,
    }

    impl Tracker {
        pub fn reset(&mut self) {
            *self = Self::default();
        }

        /// The leap height (metres) of the spring the local player stands in, if any.
        ///
        /// # Safety
        /// Game task only; no outstanding references to the player or the MSB repository.
        pub unsafe fn update(&mut self, events: &mut Vec<String>) -> Option<f32> {
            let player = unsafe { PlayerIns::local_player() }.ok()?;
            let block = player.current_block_id.0;
            let at = [
                player.block_position.x,
                player.block_position.y,
                player.block_position.z,
            ];
            if block == -1 || at.iter().any(|v| !v.is_finite()) {
                return None;
            }
            // At most one tile is read per call, the rider's own first, so the game thread never
            // stalls on a burst of map files. Empty results are retried now and then in case the
            // file had not finished loading.
            let wanted = neighbouring_blocks(block);
            let due = |tile: &Tile| {
                tile.at.elapsed()
                    > if tile.springs.is_empty() {
                        Duration::from_secs(60)
                    } else {
                        Duration::from_secs(300)
                    }
            };
            let mut changed = block != self.block;
            if let Some((id, _)) = wanted
                .iter()
                .find(|(id, _)| self.tiles.get(id).is_none_or(due))
            {
                let mut notes = Vec::new();
                let springs = unsafe { read_tile(*id, &mut notes) };
                let known = self.tiles.get(id).map(|t| t.springs.len());
                // A tile that keeps coming back the same is reported only the first time.
                if known != Some(springs.len()) {
                    events.extend(notes);
                }
                self.tiles.insert(
                    *id,
                    Tile {
                        springs,
                        at: Instant::now(),
                    },
                );
                changed = true;
            }
            if changed {
                self.block = block;
                self.springs.clear();
                self.last_in = None;
                for (id, shift) in &wanted {
                    if let Some(tile) = self.tiles.get(id) {
                        self.springs
                            .extend(tile.springs.iter().map(|spring| (*spring, *shift)));
                    }
                }
                if self.tiles.len() > 64 {
                    self.tiles
                        .retain(|_, t| t.at.elapsed() < Duration::from_secs(300));
                }
            }
            let found = self.springs.iter().position(|(spring, shift)| {
                spring.contains([at[0] - shift[0], at[1], at[2] - shift[1]])
            });
            if found != self.last_in {
                if let Some(index) = found {
                    let (spring, _) = self.springs[index];
                    events.push(format!(
                        "Spiritspring entered at {:?}: {:?} leap {} m.",
                        at, spring.shape, spring.height
                    ));
                }
                self.last_in = found;
            }
            found.map(|index| self.springs[index].0.height)
        }
    }

    /// Reads one tile's springs out of the loaded MSB repository, logging what was found.
    unsafe fn read_tile(id: i32, events: &mut Vec<String>) -> Vec<Spring> {
        let name = block_name(id);
        let Ok(repo) = (unsafe { MsbRepository::instance() }) else {
            events.push("Spiritsprings: MSB repository unavailable.".into());
            return Vec::new();
        };
        let Some(cap) = repo.res_cap_holder.entries().find(|cap| {
            let loaded = cap.file_cap.res_cap.name.to_string();
            loaded.eq_ignore_ascii_case(&name) || loaded.to_ascii_lowercase().starts_with(&name)
        }) else {
            return Vec::new();
        };
        let Some((image, via)) = (unsafe {
            find_image(
                cap as *const _ as usize,
                std::mem::size_of::<eldenring::cs::MsbFileCap>() + 0x40,
            )
        }) else {
            events.push(format!("Spiritsprings {name}: file image not found."));
            return Vec::new();
        };
        let started = Instant::now();
        let image = Snapshot::take(image);
        match parse(&image) {
            Ok(parsed) => {
                events.push(format!(
                    "Spiritsprings {name}: {} springs via {via}, {} bytes read in {} ms; region types {:?}.",
                    parsed.springs.len(),
                    image.copied(),
                    started.elapsed().as_millis(),
                    parsed.types
                ));
                for spring in parsed.springs.iter().take(8) {
                    events.push(format!(
                        "Spiritsprings {name}: spring at {:?} {:?} leap {} m.",
                        spring.position, spring.shape, spring.height
                    ));
                }
                for failure in &parsed.failures {
                    events.push(format!("Spiritsprings {name}: unread MountJump {failure}."));
                }
                parsed.springs
            }
            Err(reason) => {
                let name_offset = {
                    let mut b = [0u8; 8];
                    image
                        .read(0x18, &mut b)
                        .then(|| u64::from_le_bytes(b) as usize)
                };
                events.push(format!(
                    "Spiritsprings {name}: {reason} via {via}; {} bytes; head {}; at name offset {:?}: {}.",
                    image.copied(),
                    hex(&image, 0, 0x30),
                    name_offset,
                    name_offset.map_or("-".into(), |o| hex(&image, o, 0x20))
                ));
                Vec::new()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Where a test file pretends to be loaded, to exercise absolute in-place pointers.
    const BASE: usize = 0x7ff4_a27f_0000;
    struct File(Vec<u8>);
    impl Bytes for File {
        fn read(&self, offset: usize, out: &mut [u8]) -> bool {
            let offset = if offset > 0x1000_0000 {
                match offset.checked_sub(BASE) {
                    Some(relative) => relative,
                    None => return false,
                }
            } else {
                offset
            };
            self.0
                .get(offset..offset + out.len())
                .map(|b| out.copy_from_slice(b))
                .is_some()
        }
        fn absolute(&self, offset: usize) -> i64 {
            (BASE + offset) as i64
        }
    }

    fn put(file: &mut Vec<u8>, at: usize, bytes: &[u8]) {
        if file.len() < at + bytes.len() {
            file.resize(at + bytes.len(), 0);
        }
        file[at..at + bytes.len()].copy_from_slice(bytes);
    }
    fn utf16(text: &str) -> Vec<u8> {
        text.encode_utf16()
            .chain([0])
            .flat_map(|u| u.to_le_bytes())
            .collect()
    }

    /// A tiny MSBE: a MODEL param, then POINT_PARAM_ST with a MountJump cylinder, a sound region
    /// and a MountJump box.
    fn msb() -> File {
        let mut f = vec![0u8; 0x10];
        f[..4].copy_from_slice(b"MSB ");
        f[4] = 1;
        f[8] = 0x10;
        // Section 1 at 0x10: MODEL_PARAM_ST with no entries.
        put(&mut f, 0x10, &52i32.to_le_bytes());
        put(&mut f, 0x14, &1i32.to_le_bytes());
        put(&mut f, 0x18, &0x200i64.to_le_bytes());
        put(&mut f, 0x20, &0x40i64.to_le_bytes()); // next section
        put(&mut f, 0x200, &utf16("MODEL_PARAM_ST"));
        // Section 2 at 0x40: POINT_PARAM_ST with three entries.
        put(&mut f, 0x40, &52i32.to_le_bytes());
        put(&mut f, 0x44, &4i32.to_le_bytes());
        put(&mut f, 0x48, &0x240i64.to_le_bytes());
        for (i, entry) in [0x400i64, 0x500, 0x600].iter().enumerate() {
            put(&mut f, 0x50 + 8 * i, &entry.to_le_bytes());
        }
        put(&mut f, 0x68, &0i64.to_le_bytes());
        put(&mut f, 0x240, &utf16("POINT_PARAM_ST"));
        let region = |f: &mut Vec<u8>,
                      at: usize,
                      kind: u32,
                      shape: i32,
                      pos: [f32; 3],
                      yaw: f32,
                      dims: &[f32],
                      extra: Option<f32>| {
            put(f, at + 8, &kind.to_le_bytes());
            put(f, at + 0x10, &shape.to_le_bytes());
            for (i, v) in pos.iter().enumerate() {
                put(f, at + 0x14 + 4 * i, &v.to_le_bytes());
            }
            put(f, at + 0x24, &yaw.to_le_bytes());
            put(f, at + 0x48, &0x80i64.to_le_bytes());
            put(f, at + 0x58, &0xa0i64.to_le_bytes());
            for (i, v) in dims.iter().enumerate() {
                put(f, at + 0x80 + 4 * i, &v.to_le_bytes());
            }
            if let Some(h) = extra {
                put(f, at + 0xa0, &h.to_le_bytes());
            }
        };
        region(
            &mut f,
            0x400,
            MOUNT_JUMP,
            3,
            [10., 5., 20.],
            0.,
            &[2., 6.],
            Some(24.),
        );
        region(&mut f, 0x500, 4, 2, [0., 0., 0.], 0., &[3.], None);
        region(
            &mut f,
            0x600,
            MOUNT_JUMP,
            5,
            [100., 0., 100.],
            90.,
            &[10., 2., 4.],
            Some(45.),
        );
        File(f)
    }

    #[test]
    fn mount_jump_regions_are_read_from_a_chained_msb() {
        let parsed = parse(&msb()).unwrap();
        assert_eq!(parsed.types, vec![(4, 1), (46, 2)]);
        assert_eq!(parsed.springs.len(), 2);
        assert_eq!(parsed.springs[0].shape, Shape::Cylinder(2., 6.));
        assert_eq!(parsed.springs[0].position, [10., 5., 20.]);
        assert_eq!(parsed.springs[0].height, 24.);
        assert_eq!(parsed.springs[1].shape, Shape::Box(10., 2., 4.));
        assert_eq!(parsed.springs[1].height, 45.);
    }

    /// The shipped game rewrites every offset as an address: this is a MountJump sphere exactly as
    /// the running game held it (offsets 0x30 and up are pointers into the same image).
    #[test]
    fn regions_whose_offsets_became_absolute_pointers_are_read() {
        let mut f = vec![0u8; 0x1000];
        f[..4].copy_from_slice(b"MSB ");
        f[4] = 1;
        f[8] = 0x10;
        let abs = |offset: usize| ((BASE + offset) as i64).to_le_bytes();
        // MODEL_PARAM_ST with no entries, then POINT_PARAM_ST with one, all pointers absolute.
        put(&mut f, 0x10, &52i32.to_le_bytes());
        put(&mut f, 0x14, &1i32.to_le_bytes());
        put(&mut f, 0x18, &abs(0x200));
        put(&mut f, 0x20, &abs(0x40));
        put(&mut f, 0x200, &utf16("MODEL_PARAM_ST"));
        put(&mut f, 0x40, &52i32.to_le_bytes());
        put(&mut f, 0x44, &2i32.to_le_bytes());
        put(&mut f, 0x48, &abs(0x240));
        put(&mut f, 0x50, &abs(0x400));
        put(&mut f, 0x58, &0i64.to_le_bytes());
        put(&mut f, 0x240, &utf16("POINT_PARAM_ST"));
        let at = 0x400;
        put(&mut f, at, &abs(0x500));
        put(&mut f, at + 8, &MOUNT_JUMP.to_le_bytes());
        put(&mut f, at + 0x10, &2i32.to_le_bytes());
        for (i, v) in [47.32f32, 221.0, -42.1].iter().enumerate() {
            put(&mut f, at + 0x14 + 4 * i, &v.to_le_bytes());
        }
        put(&mut f, at + 0x2c, &144i32.to_le_bytes());
        put(&mut f, at + 0x44, &(-1i32).to_le_bytes());
        put(&mut f, at + 0x48, &abs(at + 0x68));
        put(&mut f, at + 0x50, &abs(at + 0x6c));
        put(&mut f, at + 0x58, &abs(at + 0x78));
        put(&mut f, at + 0x68, &6.5f32.to_le_bytes());
        put(&mut f, at + 0x78, &26.0f32.to_le_bytes());
        let parsed = parse(&File(f)).unwrap();
        assert_eq!(parsed.springs.len(), 1, "{:?}", parsed.failures);
        assert_eq!(parsed.springs[0].shape, Shape::Sphere(6.5));
        assert_eq!(parsed.springs[0].height, 26.);
        assert_eq!(parsed.springs[0].position, [47.32, 221.0, -42.1]);
    }

    #[test]
    fn damaged_or_foreign_images_are_rejected_without_panicking() {
        assert!(parse(&File(b"NOPE".to_vec())).is_err());
        let mut truncated = msb().0;
        truncated.truncate(0x60);
        assert!(parse(&File(truncated)).is_err());
        // A broken chain is recovered by finding the POINT_PARAM_ST header directly.
        let mut bad_chain = msb().0;
        put(&mut bad_chain, 0x14, &(-5i32).to_le_bytes());
        assert_eq!(parse(&File(bad_chain)).unwrap().springs.len(), 2);
        // With nothing to find, a damaged file is an error.
        let mut no_point = msb().0;
        put(&mut no_point, 0x14, &(-5i32).to_le_bytes());
        put(&mut no_point, 0x240, &utf16("POINT_PARAM_XX"));
        assert!(parse(&File(no_point)).is_err());
        // A garbage height skips that one region only.
        let mut garbage = msb().0;
        put(&mut garbage, 0x400 + 0xa0, &f32::NAN.to_le_bytes());
        assert_eq!(parse(&File(garbage)).unwrap().springs.len(), 1);
    }

    #[test]
    fn regions_contain_a_rider_standing_in_them() {
        let cylinder = Spring {
            position: [10., 5., 20.],
            yaw: 0.,
            shape: Shape::Cylinder(2., 6.),
            height: 24.,
        };
        assert!(cylinder.contains([10., 5., 20.]));
        assert!(cylinder.contains([11.5, 6., 20.]));
        assert!(!cylinder.contains([14., 5., 20.]));
        assert!(!cylinder.contains([10., 30., 20.]));
        // Rotated box 10 wide (local x) and 2 deep: at 90 degrees its long side runs along z.
        let long = Spring {
            position: [0., 0., 0.],
            yaw: 90.,
            shape: Shape::Box(10., 2., 4.),
            height: 45.,
        };
        assert!(long.contains([0., 1., 4.]) || long.contains([4., 1., 0.]));
        assert!(!(long.contains([0., 1., 4.]) && long.contains([4., 1., 0.])));
        assert!(!long.contains([0., 1., 9.]) && !long.contains([9., 1., 0.]));
        assert!(
            Spring {
                shape: Shape::Sphere(3.),
                ..cylinder
            }
            .contains([10., 7., 20.])
        );
    }

    #[test]
    fn block_ids_name_their_msb_and_overworld_tiles_have_neighbours() {
        assert_eq!(block_name(0x3c2a2400), "m60_42_36_00");
        assert_eq!(block_name(0x0b0a0000), "m11_10_00_00");
        let tiles = neighbouring_blocks(0x3c2a2400);
        assert_eq!(tiles.len(), 9);
        assert_eq!(tiles[0], (0x3c2a2400, [0., 0.]));
        assert!(tiles.contains(&(0x3c2b2500, [256., 256.])));
        assert!(tiles.contains(&(0x3c292300, [-256., -256.])));
        // Dungeons and edge tiles have no (or fewer) neighbours.
        assert_eq!(neighbouring_blocks(0x0b0a0000).len(), 1);
        assert_eq!(neighbouring_blocks(0x3c000000).len(), 4);
    }

    #[test]
    fn launch_speed_reaches_the_authored_height() {
        for height in [24., 26., 45.] {
            let v = launch_speed(height);
            assert!(
                (apex(v) - height * LEAP_SCALE).abs() < 0.1,
                "{height}: {}",
                apex(v)
            );
        }
        // The model's own 12 m/s jump rises about two metres, so the scale is right.
        assert!((1.5..3.0).contains(&apex(12.)), "{}", apex(12.));
        assert_eq!(launch_speed(f32::NAN), 0.);
        assert!(launch_speed(1e9) < 400.);
    }

    #[test]
    fn leap_states_are_recognised() {
        assert!(leaping(&[184]) && leaping(&[10, 186]) && !leaping(&[10, 187]));
    }
}
