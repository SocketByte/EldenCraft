//! Bounded, incremental native-ray surface cache. This is sampled collision
//! geometry, not an extracted mesh or a proof that every unhit volume is empty.
//! No game pointers, filesystem access, or native calls live in this module.

use serde::Serialize;
use std::collections::{BTreeMap, VecDeque};

pub const CELL_METRES: f64 = 1.0;
pub const MAX_BOXES: usize = 4096;
pub const RAYS_PER_CELL: usize = 6;
/// Hard bound on native rays per sampling tick; [`RayBudget`] picks the
/// actual count from measured query cost. Whole cells: a multiple of six rays.
pub const MAX_RAYS_PER_TICK: usize = 170 * RAYS_PER_CELL;
pub const MIN_RAYS_PER_TICK: usize = 96;
const GRID_SIZE: [i32; 3] = [32, 12, 32];
const GRID_BELOW: i32 = 4;
const THICKNESS: f64 = 0.0625;
const END_PADDING: f64 = 0.015625;
const REFRESH_MS: u64 = 15_000;
/// Floor detail near the feet: a DETAIL x DETAIL grid of downward rays per
/// 1 m cell (25 cm columns), so slopes, stair steps and ledges stop collapsing
/// into one flat patch at the cell centre. Only cells whose coarse sample found
/// a floor are refined, within DETAIL_RADIUS columns of the player.
pub const DETAIL: usize = 4;
pub const DETAIL_RAYS: usize = DETAIL * DETAIL;
const DETAIL_RAYS_PER_TICK: usize = DETAIL_RAYS * 12;
/// Refined floor columns cover a 7x7 m square around the feet: placed blocks,
/// items and mobs meet 25 cm ground steps instead of 1 m stairs.
const DETAIL_RADIUS: i32 = 3;

/// Rays per sampling tick sized from the measured native query cost, so the
/// cache keeps up with sprinting and Torrent without exceeding a fixed slice of
/// the game task. Starts at the former fixed budget until it has measured.
#[derive(Clone, Copy, Debug)]
pub struct RayBudget {
    per_ray_ns: f64,
}
impl Default for RayBudget {
    fn default() -> Self {
        Self {
            per_ray_ns: Self::TARGET_NS / 192.0,
        }
    }
}
impl RayBudget {
    /// Native query time allowed per sampling tick.
    pub const TARGET_NS: f64 = 1_250_000.0;
    pub fn rays(&self) -> usize {
        ((Self::TARGET_NS / self.per_ray_ns) as usize).clamp(MIN_RAYS_PER_TICK, MAX_RAYS_PER_TICK)
    }
    /// Fold one tick's measurement in; tiny or failed batches carry no cost signal.
    pub fn observe(&mut self, rays: usize, elapsed_ns: u64) {
        if rays < 16 {
            return;
        }
        let sample = (elapsed_ns as f64 / rays as f64).clamp(200.0, 200_000.0);
        self.per_ray_ns = self.per_ray_ns * 0.8 + sample * 0.2;
    }
}

pub type Cell = [i32; 3];
pub type Box6 = [f64; 6];

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Ray {
    pub origin: [f64; 3],
    pub delta: [f64; 3],
}
/// Native ray result. The normal is present when the rich collider query ran.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Hit {
    pub point: [f64; 3],
    pub normal: Option<[f64; 3]>,
    /// Havok material of the hit body (hknpMaterialId), when the body has one
    /// material; per-triangle meshes report none.
    pub material: Option<u16>,
}
impl From<[f64; 3]> for Hit {
    fn from(point: [f64; 3]) -> Self {
        Self {
            point,
            normal: None,
            material: None,
        }
    }
}
/// Published for a surface whose body material is unknown.
pub const NO_MATERIAL: u16 = u16::MAX;
/// A horizontal ray crossing sloped or flat ground used to become a full-height
/// vertical slab inside the slope: invisible Minecraft walls standing above the
/// real ground (floating outlines, blocks and eggs placed onto thin air). Ground
/// that faces up is sampled by the vertical rays; walls stay below this |n.y|.
const WALL_MAX_UP: f64 = 0.7;
fn keeps_hit(direction: usize, normal: Option<[f64; 3]>) -> bool {
    direction / 2 == 1 || normal.is_none_or(|n| n[1].abs() < WALL_MAX_UP)
}

#[derive(Clone, Debug, Serialize)]
pub struct Snapshot {
    pub revision: u64,
    pub ready: bool,
    pub bounds: Box6,
    pub boxes: Vec<Box6>,
    /// Havok body material of each box (NO_MATERIAL when unknown), same order.
    pub materials: Vec<u16>,
    pub scanned_cells: usize,
    pub total_cells: usize,
    pub truncated: bool,
    pub last_rays: usize,
    pub failed_cells: usize,
    /// This explicitly excludes an exact-mesh/volume-clearance guarantee.
    pub sampled_surfaces: bool,
}

#[derive(Clone)]
struct Sample {
    boxes: Vec<Box6>,
    time_ms: u64,
    floor: Option<Box6>,
    materials: Vec<u16>,
}

pub struct Cache {
    origin: Option<Cell>,
    samples: BTreeMap<Cell, Sample>,
    pending: VecDeque<Cell>,
    revision: u64,
    last_rays: usize,
    failed_cells: usize,
    last_refresh: u64,
    player_cell: Cell,
    published: Option<(Cell, Cell)>,
    /// Refined floor columns replacing a cell's coarse floor patch.
    detail: BTreeMap<Cell, Vec<Box6>>,
    detail_materials: BTreeMap<Cell, Vec<u16>>,
    detail_pending: VecDeque<Cell>,
}

impl Default for Cache {
    fn default() -> Self {
        Self::new()
    }
}
impl Cache {
    pub fn new() -> Self {
        Self {
            origin: None,
            samples: BTreeMap::new(),
            pending: VecDeque::new(),
            revision: 0,
            last_rays: 0,
            failed_cells: 0,
            last_refresh: 0,
            player_cell: [0; 3],
            published: None,
            detail: BTreeMap::new(),
            detail_materials: BTreeMap::new(),
            detail_pending: VecDeque::new(),
        }
    }
    pub fn clear(&mut self) {
        *self = Self::new();
    }
    pub fn revision(&self) -> u64 {
        self.revision
    }
    /// Native rays cast by the last tick.
    pub fn last_rays(&self) -> usize {
        self.last_rays
    }
    /// A fully sampled replacement window must invalidate downstream caches
    /// even when its own local revision happens to equal the prior window.
    pub fn advance_revision_after(&mut self, previous: u64) {
        self.revision = self.revision.max(previous.saturating_add(1));
    }
    /// Prioritize a complete local query rectangle, including the bootstrap
    /// neighborhood, without evicting samples or advertising unknown cells.
    /// Landing admission uses this to avoid waiting for unrelated outer cells.
    pub fn prioritize_bounds(&mut self, bounds: Box6) -> Result<(), &'static str> {
        if !valid_box(bounds) {
            return Err("terrain priority bounds invalid");
        }
        let origin = self.origin.ok_or("terrain priority window missing")?;
        let low: Cell =
            std::array::from_fn(|i| (bounds[i].floor() as i32).min(self.player_cell[i] - 1));
        let high: Cell =
            std::array::from_fn(|i| (bounds[i + 3].ceil() as i32).max(self.player_cell[i] + 2));
        if (0..3).any(|i| low[i] < origin[i] || high[i] > origin[i] + GRID_SIZE[i]) {
            return Err("terrain priority outside window");
        }
        let mut urgent = std::collections::BTreeSet::new();
        for x in low[0]..high[0] {
            for y in low[1]..high[1] {
                for z in low[2]..high[2] {
                    let c = [x, y, z];
                    if !self.samples.contains_key(&c) {
                        urgent.insert(c);
                    }
                }
            }
        }
        self.pending.retain(|c| !urgent.contains(c));
        for cell in urgent.into_iter().rev() {
            self.pending.push_front(cell);
        }
        Ok(())
    }
    /// A removed owned collider may have hidden a native surface from refresh.
    /// Prioritize intersecting native cells without deleting already verified
    /// samples or temporarily advertising an empty terrain hole.
    pub fn refresh_boxes(&mut self, boxes: &[Box6]) {
        let Some(origin) = self.origin else {
            return;
        };
        let mut urgent = std::collections::BTreeSet::new();
        for b in boxes.iter().filter(|b| valid_box(**b)) {
            let low = std::array::from_fn::<_, 3, _>(|i| (b[i].floor() as i32 - 1).max(origin[i]));
            let high = std::array::from_fn::<_, 3, _>(|i| {
                (b[i + 3].ceil() as i32 + 1).min(origin[i] + GRID_SIZE[i])
            });
            for x in low[0]..high[0] {
                for y in low[1]..high[1] {
                    for z in low[2]..high[2] {
                        urgent.insert([x, y, z]);
                    }
                }
            }
        }
        // Refined floors in these cells are re-sampled after their coarse cell.
        for cell in &urgent {
            if self.detail.remove(cell).is_some() {
                self.revision = self.revision.saturating_add(1);
            }
        }
        self.detail_pending.retain(|c| !urgent.contains(c));
        self.pending.retain(|c| !urgent.contains(c));
        for c in urgent.into_iter().rev() {
            self.pending.push_front(c);
        }
    }

    /// Keep a stable sampling window until the player approaches its inner
    /// four-metre margin. Preserve overlapping samples when the window moves.
    pub fn recenter(&mut self, feet: [f64; 3], now: u64) -> Result<(), &'static str> {
        let cell = cell_at(feet).ok_or("terrain feet outside finite coordinate bounds")?;
        let player_moved = cell != self.player_cell;
        self.player_cell = cell;
        let move_window = self.origin.is_none_or(|o| {
            cell[0] < o[0] + 4
                || cell[0] >= o[0] + GRID_SIZE[0] - 4
                || cell[2] < o[2] + 4
                || cell[2] >= o[2] + GRID_SIZE[2] - 4
                || cell[1] < o[1] + 2
                || cell[1] >= o[1] + GRID_SIZE[1] - 3
        });
        if move_window {
            let origin = [
                cell[0] - GRID_SIZE[0] / 2,
                cell[1] - GRID_BELOW,
                cell[2] - GRID_SIZE[2] / 2,
            ];
            self.origin = Some(origin);
            self.samples.retain(|key, _| contains(origin, *key));
            self.detail.retain(|key, _| contains(origin, *key));
            self.pending.clear();
            self.enqueue_missing(cell, now, false);
            self.revision = self.revision.saturating_add(1);
        } else if self.pending.is_empty() && now.saturating_sub(self.last_refresh) >= REFRESH_MS {
            self.enqueue_missing(cell, now, true);
            self.last_refresh = now;
        }
        if player_moved && !move_window {
            // A density-limited published rectangle may move before the outer
            // sampling window does. Prioritize unknown local cells without
            // discarding existing samples or increasing the native ray budget.
            let local_low = cell.map(|v| v - 1);
            let local_high = cell.map(|v| v + 2);
            let mut urgent = std::collections::BTreeSet::new();
            for x in local_low[0]..local_high[0] {
                for y in local_low[1]..local_high[1] {
                    for z in local_low[2]..local_high[2] {
                        let key = [x, y, z];
                        if self.origin.is_some_and(|o| contains(o, key))
                            && !self.samples.contains_key(&key)
                        {
                            urgent.insert(key);
                        }
                    }
                }
            }
            self.pending.retain(|c| !urgent.contains(c));
            for c in urgent.into_iter().rev() {
                self.pending.push_front(c);
            }
        }
        if player_moved || move_window {
            self.enqueue_detail();
        }
        Ok(())
    }

    fn near_feet(&self, cell: Cell) -> bool {
        let p = self.player_cell;
        (cell[0] - p[0]).abs() <= DETAIL_RADIUS
            && (cell[2] - p[2]).abs() <= DETAIL_RADIUS
            && (-1..=0).contains(&(cell[1] - p[1]))
    }
    /// Queue refinement for floor cells around the feet; drop distant detail so
    /// the published payload stays near the coarse budget.
    fn enqueue_detail(&mut self) {
        let before = self.detail.len();
        let player = self.player_cell;
        self.detail.retain(|c, _| {
            (c[0] - player[0]).abs() <= DETAIL_RADIUS + 1
                && (c[2] - player[2]).abs() <= DETAIL_RADIUS + 1
                && (c[1] - player[1]).abs() <= 2
        });
        if self.detail.len() != before {
            self.revision = self.revision.saturating_add(1);
        }
        let mut wanted = Vec::new();
        for dx in -DETAIL_RADIUS..=DETAIL_RADIUS {
            for dz in -DETAIL_RADIUS..=DETAIL_RADIUS {
                for dy in [-1, 0] {
                    let cell = [player[0] + dx, player[1] + dy, player[2] + dz];
                    if self.samples.get(&cell).is_some_and(|s| s.floor.is_some())
                        && !self.detail.contains_key(&cell)
                        && !self.detail_pending.contains(&cell)
                    {
                        wanted.push(cell);
                    }
                }
            }
        }
        // The column under the feet first.
        wanted.sort_by_key(|c| {
            (
                (c[0] - player[0]).abs() + (c[2] - player[2]).abs(),
                -(c[1] - player[1]),
            )
        });
        self.detail_pending.extend(wanted);
    }
    /// Refine queued cells within `budget` rays; returns rays used and whether
    /// the published surfaces changed.
    fn tick_detail<F>(&mut self, budget: usize, cast: &mut F) -> (usize, bool)
    where
        F: FnMut(Ray) -> Result<Option<Hit>, &'static str>,
    {
        let mut used = 0;
        let mut changed = false;
        while budget - used >= DETAIL_RAYS {
            let Some(cell) = self.detail_pending.pop_front() else {
                break;
            };
            if !self.near_feet(cell) || !self.samples.get(&cell).is_some_and(|s| s.floor.is_some())
            {
                continue;
            }
            let mut boxes = Vec::with_capacity(DETAIL_RAYS);
            let mut materials = Vec::with_capacity(DETAIL_RAYS);
            let mut valid = true;
            for sub in 0..DETAIL_RAYS {
                let ray = detail_ray(cell, sub);
                used += 1;
                match cast(ray) {
                    Ok(Some(hit)) => match detail_patch(cell, sub, ray, hit.point) {
                        Some(patch) => {
                            boxes.push(patch);
                            materials.push(hit.material.unwrap_or(NO_MATERIAL));
                        }
                        None => {
                            valid = false;
                            break;
                        }
                    },
                    Ok(None) => {}
                    Err(_) => {
                        valid = false;
                        break;
                    }
                }
            }
            if !valid {
                self.detail_pending.push_back(cell);
                break;
            }
            changed |= self.detail.get(&cell) != Some(&boxes)
                || self.detail_materials.get(&cell) != Some(&materials);
            self.detail.insert(cell, boxes);
            self.detail_materials.insert(cell, materials);
        }
        (used, changed)
    }

    fn enqueue_missing(&mut self, player: Cell, now: u64, refresh: bool) {
        let Some(origin) = self.origin else {
            return;
        };
        let mut cells = Vec::new();
        for x in origin[0]..origin[0] + GRID_SIZE[0] {
            for y in origin[1]..origin[1] + GRID_SIZE[1] {
                for z in origin[2]..origin[2] + GRID_SIZE[2] {
                    let key = [x, y, z];
                    if self
                        .samples
                        .get(&key)
                        .is_none_or(|s| refresh && now.saturating_sub(s.time_ms) >= REFRESH_MS)
                    {
                        cells.push(key);
                    }
                }
            }
        }
        // Closest cells become useful first; favor the floor immediately below
        // the feet. Squared distances are bounded by this small cache window.
        cells.sort_by_key(|c| {
            let d = [c[0] - player[0], c[1] - (player[1] - 1), c[2] - player[2]];
            (d[0] * d[0] + d[2] * d[2] + 2 * d[1] * d[1], *c)
        });
        self.pending.extend(cells);
    }

    /// Samples whole cells atomically. On a native-query error the cell remains
    /// unknown (or keeps its previous sample) and is retried on a later tick.
    pub fn tick<F>(&mut self, now: u64, ray_budget: usize, mut cast: F)
    where
        F: FnMut(Ray) -> Result<Option<[f64; 3]>, &'static str>,
    {
        self.tick_hits(now, ray_budget, |ray| {
            cast(ray).map(|hit| hit.map(Hit::from))
        });
    }
    /// Same sampling, with surface normals used to classify horizontal hits.
    pub fn tick_hits<F>(&mut self, now: u64, ray_budget: usize, mut cast: F)
    where
        F: FnMut(Ray) -> Result<Option<Hit>, &'static str>,
    {
        self.last_rays = 0;
        self.failed_cells = 0;
        let budget = ray_budget.min(MAX_RAYS_PER_TICK);
        // Refinement never takes more than half: unknown coarse cells come first.
        let (detail_rays, mut changed) =
            self.tick_detail((budget / 2).min(DETAIL_RAYS_PER_TICK), &mut cast);
        self.last_rays += detail_rays;
        let count = ((budget - detail_rays) / RAYS_PER_CELL).min(self.pending.len());
        for _ in 0..count {
            let Some(cell) = self.pending.pop_front() else {
                break;
            };
            let mut boxes: Vec<(Box6, u16)> = Vec::with_capacity(RAYS_PER_CELL);
            let mut floor = None;
            let mut valid = true;
            for direction in 0..RAYS_PER_CELL {
                let ray = cell_ray(cell, direction);
                self.last_rays += 1;
                match cast(ray) {
                    Ok(Some(hit)) if !keeps_hit(direction, hit.normal) => {}
                    Ok(Some(hit)) => match surface_patch(cell, direction, ray, hit.point) {
                        Some(patch) => {
                            if direction == 3 {
                                floor = Some(patch);
                            }
                            if !boxes.iter().any(|(b, _)| *b == patch) {
                                boxes.push((patch, hit.material.unwrap_or(NO_MATERIAL)));
                            }
                        }
                        None => {
                            valid = false;
                            break;
                        }
                    },
                    Ok(None) => {}
                    Err(_) => {
                        valid = false;
                        break;
                    }
                }
            }
            if valid {
                boxes.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());
                let (boxes, materials): (Vec<Box6>, Vec<u16>) = boxes.into_iter().unzip();
                let moved = self.samples.get(&cell).is_none_or(|old| old.boxes != boxes);
                changed |= self
                    .samples
                    .get(&cell)
                    .is_some_and(|old| old.materials != materials);
                changed |= moved;
                // A changed cell (an opened door, a removed collider) or a lost
                // floor invalidates its refinement; near the feet it is redone.
                if (moved || floor.is_none()) && self.detail.remove(&cell).is_some() {
                    self.detail_materials.remove(&cell);
                    changed = true;
                }
                self.samples.insert(
                    cell,
                    Sample {
                        boxes,
                        time_ms: now,
                        floor,
                        materials,
                    },
                );
            } else {
                self.failed_cells += 1;
                self.pending.push_back(cell);
            }
        }
        if changed {
            self.revision = self.revision.saturating_add(1);
        }
        self.enqueue_detail();
    }

    /// Surfaces published for one sampled cell: coarse patches, with the floor
    /// patch replaced by refined columns where they exist.
    fn cell_shapes<'a>(
        &'a self,
        cell: &Cell,
        sample: &'a Sample,
    ) -> impl Iterator<Item = (&'a Box6, u16)> + 'a {
        let detail = self.detail.get(cell);
        let detail_materials = self.detail_materials.get(cell);
        let skip = detail.and(sample.floor);
        let coarse = sample
            .boxes
            .iter()
            .enumerate()
            .filter(move |(_, b)| Some(**b) != skip)
            .map(move |(i, b)| (b, sample.materials.get(i).copied().unwrap_or(NO_MATERIAL)));
        let refined = detail.into_iter().flatten().enumerate().map(move |(i, b)| {
            (
                b,
                detail_materials
                    .and_then(|m| m.get(i).copied())
                    .unwrap_or(NO_MATERIAL),
            )
        });
        coarse.chain(refined)
    }

    pub fn snapshot(&mut self) -> Snapshot {
        let origin = self.origin.unwrap_or([0; 3]);
        let covered = self.covered_box();
        // The guest applies bounds and surfaces as one terrain revision. A
        // player-centred coverage move can occur with no new ray hit at all.
        if covered != self.published {
            self.revision = self.revision.saturating_add(1);
        }
        self.published = covered;
        let (low, high) =
            covered.unwrap_or((origin, std::array::from_fn(|i| origin[i] + GRID_SIZE[i])));
        let bounds = [
            low[0] as f64,
            low[1] as f64,
            low[2] as f64,
            high[0] as f64,
            high[1] as f64,
            high[2] as f64,
        ];
        let mut boxes = Vec::new();
        let mut materials = Vec::new();
        let mut truncated = false;
        for (cell, sample) in &self.samples {
            if (0..3).any(|i| cell[i] < low[i] || cell[i] >= high[i]) {
                continue;
            }
            for (shape, material) in self.cell_shapes(cell, sample) {
                if boxes.len() == MAX_BOXES {
                    truncated = true;
                    break;
                }
                boxes.push(*shape);
                materials.push(material);
            }
            if truncated {
                break;
            }
        }
        let total = GRID_SIZE.iter().map(|v| *v as usize).product();
        Snapshot {
            revision: self.revision,
            ready: covered.is_some() && !truncated,
            bounds,
            boxes,
            materials,
            scanned_cells: self.samples.len(),
            total_cells: total,
            truncated,
            last_rays: self.last_rays,
            failed_cells: self.failed_cells,
            sampled_surfaces: true,
        }
    }

    /// Publish only an entirely sampled rectangular region around the player.
    /// Growing/refreshing the outer streaming window must not freeze a region
    /// whose cells are already available. Unknown surrounding cells stay out.
    fn covered_box(&self) -> Option<(Cell, Cell)> {
        let origin = self.origin?;
        let limit = std::array::from_fn::<_, 3, _>(|i| origin[i] + GRID_SIZE[i]);
        let local_low = self.player_cell.map(|v| v - 1);
        let local_high = self.player_cell.map(|v| v + 2);
        if (0..3).any(|i| local_low[i] < origin[i] || local_high[i] > limit[i]) {
            return None;
        }
        // Expand the already advertised rectangle. Restarting at the player
        // chose a different greedy rectangle as samples arrived and caused
        // boundaries to retreat even while the player stood still. Refreshes
        // can increase its shape count, however, so retained coverage must
        // still fit the wire budget and contain the current local neighbourhood.
        let previous = self.published.and_then(|(low, high)| {
            let low = std::array::from_fn(|i| low[i].max(origin[i]));
            let high = std::array::from_fn(|i| high[i].min(limit[i]));
            if (0..3).any(|i| low[i] > local_low[i] || high[i] < local_high[i]) {
                return None;
            }
            let count = self.sampled_shape_count(low, high)?;
            (count <= MAX_BOXES).then_some((low, high, count))
        });
        let (mut low, mut high, mut shape_count) = match previous {
            Some(region) => region,
            None => {
                let count = self.sampled_shape_count(local_low, local_high)?;
                if count > MAX_BOXES {
                    return None;
                }
                (local_low, local_high, count)
            }
        };
        loop {
            let mut changed = false;
            for axis in 0..3 {
                if low[axis] > origin[axis] {
                    let mut face_low = low;
                    face_low[axis] -= 1;
                    let mut face_high = high;
                    face_high[axis] = low[axis];
                    if let Some(n) = self.sampled_shape_count(face_low, face_high)
                        && shape_count + n <= MAX_BOXES
                    {
                        low[axis] -= 1;
                        shape_count += n;
                        changed = true;
                    }
                }
                if high[axis] < limit[axis] {
                    let mut face_low = low;
                    face_low[axis] = high[axis];
                    let mut face_high = high;
                    face_high[axis] += 1;
                    if let Some(n) = self.sampled_shape_count(face_low, face_high)
                        && shape_count + n <= MAX_BOXES
                    {
                        high[axis] += 1;
                        shape_count += n;
                        changed = true;
                    }
                }
            }
            if !changed {
                break;
            }
        }
        Some((low, high))
    }
    fn sampled_shape_count(&self, low: Cell, high: Cell) -> Option<usize> {
        let mut count = 0;
        for x in low[0]..high[0] {
            for y in low[1]..high[1] {
                for z in low[2]..high[2] {
                    let cell = [x, y, z];
                    count += self.cell_shapes(&cell, self.samples.get(&cell)?).count();
                }
            }
        }
        Some(count)
    }
}

fn contains(origin: Cell, cell: Cell) -> bool {
    (0..3).all(|i| cell[i] >= origin[i] && cell[i] < origin[i] + GRID_SIZE[i])
}
pub fn cell_at(point: [f64; 3]) -> Option<Cell> {
    point
        .iter()
        .all(|v| v.is_finite() && v.abs() <= 1_000_000.0)
        .then(|| point.map(|v| (v / CELL_METRES).floor() as i32))
}
pub fn valid_box(b: Box6) -> bool {
    b.iter().all(|v| v.is_finite() && v.abs() <= 1_000_000.0)
        && (0..3).all(|i| b[i] < b[i + 3] && b[i + 3] - b[i] <= 128.0)
}
fn cell_ray(cell: Cell, direction: usize) -> Ray {
    let axis = direction / 2;
    let positive = direction.is_multiple_of(2);
    let mut origin = cell.map(|v| v as f64 + 0.5);
    origin[axis] = cell[axis] as f64
        + if positive {
            -END_PADDING
        } else {
            1.0 + END_PADDING
        };
    let mut delta = [0.0; 3];
    delta[axis] = (1.0 + END_PADDING * 2.0) * if positive { 1.0 } else { -1.0 };
    Ray { origin, delta }
}
fn detail_ray(cell: Cell, sub: usize) -> Ray {
    let step = 1.0 / DETAIL as f64;
    let origin = [
        cell[0] as f64 + ((sub % DETAIL) as f64 + 0.5) * step,
        cell[1] as f64 + 1.0 + END_PADDING,
        cell[2] as f64 + ((sub / DETAIL) as f64 + 0.5) * step,
    ];
    Ray {
        origin,
        delta: [0.0, -(1.0 + END_PADDING * 2.0), 0.0],
    }
}
/// One refined floor column: the measured top surface over a 25 cm footprint.
fn detail_patch(cell: Cell, sub: usize, ray: Ray, hit: [f64; 3]) -> Option<Box6> {
    if !hit.iter().all(|v| v.is_finite()) {
        return None;
    }
    let t = (hit[1] - ray.origin[1]) / ray.delta[1];
    if !(-0.002..=1.002).contains(&t)
        || (hit[0] - ray.origin[0]).abs() > 0.02
        || (hit[2] - ray.origin[2]).abs() > 0.02
    {
        return None;
    }
    let step = 1.0 / DETAIL as f64;
    let x = cell[0] as f64 + (sub % DETAIL) as f64 * step;
    let z = cell[2] as f64 + (sub / DETAIL) as f64 * step;
    let h = (hit[1] * 256.0).round() / 256.0;
    let b = [x, h - THICKNESS, z, x + step, h, z + step];
    valid_box(b).then_some(b)
}
fn surface_patch(cell: Cell, direction: usize, ray: Ray, hit: [f64; 3]) -> Option<Box6> {
    if !hit.iter().all(|v| v.is_finite()) {
        return None;
    }
    let axis = direction / 2;
    let t = (hit[axis] - ray.origin[axis]) / ray.delta[axis];
    if !(-0.002..=1.002).contains(&t)
        || (0..3).any(|i| i != axis && (hit[i] - ray.origin[i]).abs() > 0.02)
    {
        return None;
    }
    let mut b = [
        cell[0] as f64,
        cell[1] as f64,
        cell[2] as f64,
        cell[0] as f64 + 1.0,
        cell[1] as f64 + 1.0,
        cell[2] as f64 + 1.0,
    ];
    // Only the queried axis is constrained by the ray. The other two axes are
    // a cell-sized conservative patch, not a claimed native surface normal.
    let h = (hit[axis] * 256.0).round() / 256.0;
    if direction.is_multiple_of(2) {
        b[axis] = h;
        b[axis + 3] = h + THICKNESS;
    } else {
        b[axis] = h - THICKNESS;
        b[axis + 3] = h;
    }
    valid_box(b).then_some(b)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fill_window(c: &mut Cache, dense: bool) {
        let origin = c.origin.unwrap();
        for x in origin[0]..origin[0] + GRID_SIZE[0] {
            for y in origin[1]..origin[1] + GRID_SIZE[1] {
                for z in origin[2]..origin[2] + GRID_SIZE[2] {
                    let cell = [x, y, z];
                    let center = cell.map(|v| v as f64 + 0.5);
                    let boxes = if dense {
                        (0..RAYS_PER_CELL)
                            .map(|d| surface_patch(cell, d, cell_ray(cell, d), center).unwrap())
                            .collect()
                    } else {
                        Vec::new()
                    };
                    c.samples.insert(
                        cell,
                        Sample {
                            boxes,
                            time_ms: 1,
                            floor: None,
                            materials: Vec::new(),
                        },
                    );
                }
            }
        }
        c.pending.clear();
    }
    fn assert_local_complete(c: &Cache, s: &Snapshot) {
        assert!(s.ready);
        assert!(!s.truncated);
        assert!(s.boxes.len() <= MAX_BOXES);
        for axis in 0..3 {
            assert!(s.bounds[axis] <= f64::from(c.player_cell[axis] - 1));
            assert!(s.bounds[axis + 3] >= f64::from(c.player_cell[axis] + 2));
        }
        let (low, high) = c.published.unwrap();
        assert_eq!(c.sampled_shape_count(low, high), Some(s.boxes.len()));
    }
    #[test]
    fn horizontal_rays_keep_walls_but_not_sloped_ground() {
        let mut c = Cache::new();
        c.recenter([0.5, 0.5, 0.5], 1).unwrap();
        let slope = [0.3, 0.95, 0.0];
        let wall = [-1.0, 0.0, 0.0];
        let mut normal = slope;
        c.tick_hits(2, RAYS_PER_CELL, |r: Ray| {
            Ok(Some(Hit {
                point: std::array::from_fn(|i| r.origin[i] + r.delta[i] * 0.5),
                normal: Some(normal),
                material: None,
            }))
        });
        let cell = *c.samples.keys().next().unwrap();
        let boxes = &c.samples[&cell].boxes;
        assert_eq!(
            boxes.len(),
            2,
            "only the floor and ceiling of a sloped surface remain"
        );
        assert!(
            boxes.iter().all(|b| b[4] - b[1] <= THICKNESS + 1e-9),
            "no full-height slab inside the slope"
        );
        normal = wall;
        c.refresh_boxes(&[[
            cell[0] as f64,
            cell[1] as f64,
            cell[2] as f64,
            cell[0] as f64 + 1.,
            cell[1] as f64 + 1.,
            cell[2] as f64 + 1.,
        ]]);
        c.pending.retain(|p| *p == cell);
        c.tick_hits(3, RAYS_PER_CELL, |r: Ray| {
            Ok(Some(Hit {
                point: std::array::from_fn(|i| r.origin[i] + r.delta[i] * 0.5),
                normal: Some(normal),
                material: None,
            }))
        });
        assert_eq!(
            c.samples[&cell].boxes.len(),
            6,
            "steep walls and the column's vertical hits are kept"
        );
        assert!(
            keeps_hit(0, None),
            "point-only fallback rays keep their previous behaviour"
        );
    }
    /// A 45-degree ramp rising along +X through the player's floor cell.
    fn ramp(r: Ray) -> Result<Option<Hit>, &'static str> {
        if r.delta[1] >= 0.0 {
            return Ok(None);
        }
        let x = r.origin[0];
        let surface = -1.0 + (x - x.floor()) * 0.5;
        // Only a ray whose segment reaches the surface hits it.
        let t = (surface - r.origin[1]) / r.delta[1];
        Ok((0.0..=1.0).contains(&t).then_some(Hit {
            point: [x, surface, r.origin[2]],
            normal: Some([-0.45, 0.89, 0.0]),
            material: Some(7),
        }))
    }
    #[test]
    fn floor_detail_follows_a_ramp_near_the_feet_and_replaces_the_coarse_patch() {
        let mut c = Cache::new();
        c.recenter([0.5, 0.0, 0.5], 1).unwrap();
        for frame in 0..80 {
            c.tick_hits(frame, MAX_RAYS_PER_TICK, ramp);
        }
        let cell = [0, -1, 0];
        let coarse = c.samples[&cell].floor.expect("coarse floor");
        let detail = c
            .detail
            .get(&cell)
            .expect("refined floor under the feet")
            .clone();
        assert_eq!(detail.len(), DETAIL_RAYS);
        let heights: Vec<f64> = detail.iter().map(|b| b[4]).collect();
        assert!(
            heights.iter().cloned().fold(f64::MIN, f64::max)
                - heights.iter().cloned().fold(f64::MAX, f64::min)
                > 0.3,
            "columns follow the slope"
        );
        let s = c.snapshot();
        assert!(!s.boxes.contains(&coarse), "coarse floor replaced");
        assert!(detail.iter().all(|b| s.boxes.contains(b)));
        assert_eq!(
            s.materials.len(),
            s.boxes.len(),
            "one material per published box"
        );
        assert!(
            s.boxes
                .iter()
                .zip(&s.materials)
                .filter(|(b, _)| detail.contains(b))
                .all(|(_, m)| *m == 7),
            "refined floor keeps its body material"
        );
        let (low, high) = c.published.unwrap();
        assert_eq!(
            c.sampled_shape_count(low, high),
            Some(s.boxes.len()),
            "truncated={} bounds={:?} low={low:?} high={high:?}",
            s.truncated,
            s.bounds
        );
        assert!(
            c.detail
                .keys()
                .all(|k| (k[0]).abs() <= DETAIL_RADIUS + 1 && (k[2]).abs() <= DETAIL_RADIUS + 1)
        );
    }
    #[test]
    fn detail_is_dropped_when_its_cell_changes_and_stays_within_budget() {
        let mut c = Cache::new();
        c.recenter([0.5, 0.0, 0.5], 1).unwrap();
        for frame in 0..80 {
            c.tick_hits(frame, MAX_RAYS_PER_TICK, ramp);
            assert!(c.last_rays <= MAX_RAYS_PER_TICK);
        }
        let cell = [0, -1, 0];
        assert!(c.detail.contains_key(&cell));
        c.refresh_boxes(&[[0., -1., 0., 1., 0., 1.]]);
        assert!(
            !c.detail.contains_key(&cell),
            "door/collider change discards refinement"
        );
        for frame in 80..120 {
            c.tick_hits(frame, MAX_RAYS_PER_TICK, ramp);
        }
        assert!(
            c.detail.contains_key(&cell),
            "refined again after the coarse cell"
        );
        c.recenter([20.5, 0.0, 0.5], 200).unwrap();
        assert!(
            !c.detail.contains_key(&cell),
            "distant refinement is released"
        );
    }
    #[test]
    fn ray_budget_follows_measured_cost_within_hard_bounds() {
        let mut b = RayBudget::default();
        assert_eq!(b.rays(), 192, "starts at the former fixed budget");
        for _ in 0..40 {
            b.observe(400, 400 * 2_000); // 2 us per ray
        }
        assert!((600..=630).contains(&b.rays()), "{}", b.rays());
        for _ in 0..40 {
            b.observe(400, 400 * 1_000_000); // a pathological 1 ms ray
        }
        assert_eq!(
            b.rays(),
            MIN_RAYS_PER_TICK,
            "slow queries keep a useful floor"
        );
        for _ in 0..80 {
            b.observe(1000, 1000 * 100);
        }
        assert_eq!(
            b.rays(),
            MAX_RAYS_PER_TICK,
            "cheap queries stop at the hard cap"
        );
        let before = b.rays();
        b.observe(3, 999_999_999);
        assert_eq!(b.rays(), before, "a tiny batch carries no cost signal");
    }
    #[test]
    fn negative_positions_keep_floor_cell_identity() {
        assert_eq!(cell_at([-0.01, -1.0, 1.99]), Some([-1, -1, 1]));
        assert!(cell_at([f64::NAN, 0.0, 0.0]).is_none());
    }
    #[test]
    fn floor_and_ceiling_keep_the_measured_height() {
        let floor = surface_patch([0, 0, 0], 3, cell_ray([0, 0, 0], 3), [0.5, 0.25, 0.5]).unwrap();
        assert_eq!(floor, [0.0, 0.1875, 0.0, 1.0, 0.25, 1.0]);
        let ceiling =
            surface_patch([0, 0, 0], 2, cell_ray([0, 0, 0], 2), [0.5, 0.75, 0.5]).unwrap();
        assert_eq!(ceiling, [0.0, 0.75, 0.0, 1.0, 0.8125, 1.0]);
    }
    #[test]
    fn walls_are_sampled_on_both_horizontal_axes() {
        let x = surface_patch([0, 0, 0], 0, cell_ray([0, 0, 0], 0), [0.25, 0.5, 0.5]).unwrap();
        let z = surface_patch([0, 0, 0], 5, cell_ray([0, 0, 0], 5), [0.5, 0.5, 0.75]).unwrap();
        assert_eq!((x[0], x[3]), (0.25, 0.3125));
        assert_eq!((z[2], z[5]), (0.6875, 0.75));
    }
    #[test]
    fn rejects_hit_outside_ray_and_nonfinite_geometry() {
        assert!(surface_patch([0; 3], 0, cell_ray([0; 3], 0), [2.0, 0.5, 0.5]).is_none());
        assert!(surface_patch([0; 3], 0, cell_ray([0; 3], 0), [0.5, 0.8, 0.5]).is_none());
        assert!(!valid_box([0.0, 0.0, 0.0, f64::INFINITY, 1.0, 1.0]));
    }
    #[test]
    fn query_budget_is_hard_bounded_and_cell_commits_atomic() {
        let mut c = Cache::new();
        c.recenter([0.0; 3], 1).unwrap();
        let mut calls = 0;
        c.tick(2, usize::MAX, |_| {
            calls += 1;
            Ok(None)
        });
        assert_eq!(calls, MAX_RAYS_PER_TICK);
        assert_eq!(
            c.snapshot().scanned_cells,
            MAX_RAYS_PER_TICK / RAYS_PER_CELL
        );
        let before = c.snapshot();
        c.tick(3, 6, |_| Err("native unavailable"));
        assert_eq!(c.snapshot().scanned_cells, before.scanned_cells);
        assert_eq!(
            c.snapshot().ready,
            before.ready,
            "a failed query changes nothing"
        );
        // A cold window is never ready before its local neighbourhood is sampled.
        let mut cold = Cache::new();
        cold.recenter([0.0; 3], 1).unwrap();
        cold.tick(2, RAYS_PER_CELL * 4, |_| Ok(None));
        assert!(!cold.snapshot().ready);
        cold.tick(3, 6, |_| Err("native unavailable"));
        assert!(!cold.snapshot().ready);
    }
    #[test]
    fn full_empty_sampling_is_ready_but_explicitly_not_exact_mesh() {
        let mut c = Cache::new();
        c.recenter([0.0; 3], 1).unwrap();
        for _ in 0..400 {
            c.tick(2, MAX_RAYS_PER_TICK, |_| Ok(None));
        }
        let s = c.snapshot();
        assert!(s.ready);
        assert!(s.sampled_surfaces);
        assert_eq!(s.scanned_cells, 12_288);
        c.recenter([0.5, 0.0, 0.5], 3).unwrap();
        assert!(c.snapshot().ready);
        c.recenter([14.0, 0.0, 0.0], 4).unwrap();
        assert!(c.snapshot().ready);
        assert!(c.snapshot().scanned_cells > 0);
        assert!(c.snapshot().bounds[3] <= 16.0); // Never advertise unknown new cells.
    }
    #[test]
    fn local_coverage_becomes_ready_before_outer_window_finishes() {
        let mut c = Cache::new();
        c.recenter([0.0; 3], 1).unwrap();
        for _ in 0..8 {
            c.tick(2, MAX_RAYS_PER_TICK, |_| Ok(None));
        }
        let s = c.snapshot();
        assert!(s.ready);
        assert!(s.scanned_cells < s.total_cells);
        assert!(s.bounds[0] <= -1.0 && s.bounds[3] >= 2.0);
    }
    #[test]
    fn published_coverage_never_shrinks_as_stationary_window_streams() {
        let mut c = Cache::new();
        c.recenter([14.6777, 337.2499, -79.2850], 1).unwrap();
        let mut previous: Option<Box6> = None;
        for frame in 0..400 {
            c.tick(frame, MAX_RAYS_PER_TICK, |_| Ok(None));
            let s = c.snapshot();
            if !s.ready {
                continue;
            }
            if let Some(p) = previous {
                for i in 0..3 {
                    assert!(s.bounds[i] <= p[i]);
                    assert!(s.bounds[i + 3] >= p[i + 3]);
                }
            }
            previous = Some(s.bounds);
        }
        assert!(previous.is_some());
    }
    #[test]
    fn owned_block_removal_refreshes_native_cells_without_erasing_coverage() {
        let mut c = Cache::new();
        c.recenter([0.; 3], 1).unwrap();
        for _ in 0..400 {
            c.tick(1, MAX_RAYS_PER_TICK, |_| Ok(None));
        }
        let before = c.snapshot();
        c.refresh_boxes(&[[0., 0., 0., 1., 1., 1.]]);
        assert_eq!(c.snapshot().bounds, before.bounds);
        assert!(c.snapshot().ready);
        let mut points = Vec::new();
        c.tick(2, MAX_RAYS_PER_TICK, |r| {
            points.push(r.origin);
            Ok(None)
        });
        assert!(!points.is_empty());
        assert!(
            points
                .iter()
                .all(|p| p.iter().all(|v| *v >= -1.1 && *v <= 2.1))
        );
    }
    #[test]
    fn dense_outer_geometry_stops_growth_before_wire_shape_cap() {
        let mut c = Cache::new();
        c.recenter([0.; 3], 1).unwrap();
        let origin = c.origin.unwrap();
        for x in origin[0]..origin[0] + GRID_SIZE[0] {
            for y in origin[1]..origin[1] + GRID_SIZE[1] {
                for z in origin[2]..origin[2] + GRID_SIZE[2] {
                    c.samples.insert(
                        [x, y, z],
                        Sample {
                            boxes: vec![
                                [
                                    x as f64,
                                    y as f64,
                                    z as f64,
                                    x as f64 + 1.,
                                    y as f64 + 1.,
                                    z as f64 + 1.
                                ];
                                6
                            ],
                            time_ms: 1,
                            floor: None,
                            materials: Vec::new(),
                        },
                    );
                }
            }
        }
        let s = c.snapshot();
        assert!(s.ready);
        assert!(!s.truncated);
        assert!(s.boxes.len() <= MAX_BOXES);
        assert!(s.bounds[0] > origin[0] as f64 || s.bounds[3] < (origin[0] + GRID_SIZE[0]) as f64);
    }
    #[test]
    fn sparse_to_dense_periodic_refresh_reselects_complete_local_coverage() {
        let mut c = Cache::new();
        c.recenter([0.; 3], 1).unwrap();
        fill_window(&mut c, false);
        let sparse = c.snapshot();
        assert_local_complete(&c, &sparse);
        assert!(sparse.boxes.is_empty());
        let full_bounds = sparse.bounds;
        c.recenter([0.; 3], REFRESH_MS + 2).unwrap();
        for frame in 0..40 {
            c.tick(REFRESH_MS + 3 + frame, MAX_RAYS_PER_TICK, |r| {
                Ok(Some(std::array::from_fn(|i| {
                    r.origin[i] + r.delta[i] * 0.5
                })))
            });
            let s = c.snapshot();
            assert_local_complete(&c, &s);
            assert!(s.last_rays <= MAX_RAYS_PER_TICK);
        }
        // More than4096 surfaces now exist in the old advertised bounds. The
        // new payload is complete within smaller bounds, never truncated.
        let origin = c.origin.unwrap();
        let high = std::array::from_fn(|i| origin[i] + GRID_SIZE[i]);
        assert!(c.sampled_shape_count(origin, high).unwrap() > MAX_BOXES);
        let dense = c.snapshot();
        assert_ne!(dense.bounds, full_bounds);
        assert!(dense.revision > sparse.revision);
        assert_local_complete(&c, &dense);
    }
    #[test]
    fn budget_limited_coverage_tracks_player_and_revises_without_new_samples() {
        let mut c = Cache::new();
        c.recenter([0.; 3], 1).unwrap();
        fill_window(&mut c, true);
        let old = c.snapshot();
        assert_local_complete(&c, &old);
        let sampling_origin = c.origin;
        let samples = c.samples.len();
        let revision = c.revision();
        c.recenter([10., 0., 0.], 2).unwrap();
        assert_eq!(c.origin, sampling_origin);
        assert_eq!(c.revision(), revision);
        let moved = c.snapshot();
        assert_local_complete(&c, &moved);
        assert_ne!(moved.bounds, old.bounds);
        assert!(moved.revision > old.revision);
        assert_eq!(c.samples.len(), samples);
        assert_eq!(c.snapshot().revision, moved.revision);
    }
    #[test]
    fn movement_prioritizes_unknown_local_cells_without_claiming_them_clear() {
        let mut c = Cache::new();
        c.recenter([0.; 3], 1).unwrap();
        for _ in 0..8 {
            c.tick(2, MAX_RAYS_PER_TICK, |_| Ok(None));
        }
        let initial = c.snapshot();
        assert_local_complete(&c, &initial);
        let sampling_origin = c.origin;
        c.recenter([10., 0., 0.], 3).unwrap();
        assert_eq!(c.origin, sampling_origin);
        assert!(!c.snapshot().ready); // The old complete rectangle is not local.
        let mut calls = 0;
        c.tick(4, usize::MAX, |_| {
            calls += 1;
            Ok(None)
        });
        assert_eq!(calls, MAX_RAYS_PER_TICK);
        let local = c.snapshot();
        assert_local_complete(&c, &local);
        assert!(local.scanned_cells < local.total_cells);
    }
}
