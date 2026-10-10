package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.ColliderShapeAccessor;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Server-thread section cache. Dynamic (world/block-entity dependent) shapes stay live. */
final class PlacedColliders {
  private record Cell(BlockPos pos, boolean dynamic, List<ColliderMerge.Box> boxes) {}

  private static final class Section {
    final LevelChunk chunk;
    final LevelChunkSection section;
    final List<Cell> cells;
    final List<Integer> dynamic = new ArrayList<>();

    Section(LevelChunk chunk, LevelChunkSection section, List<Cell> cells) {
      this.chunk = chunk;
      this.section = section;
      this.cells = cells;
      for (int i = 0; i < cells.size(); i++) if (cells.get(i).dynamic) dynamic.add(i);
    }
  }

  private ServerLevel owner;
  private final Map<Long, Section> sections = new HashMap<>();
  private ColliderSnapshotCache snapshot = new ColliderSnapshotCache();
  private long revision;

  void clear() {
    owner = null;
    sections.clear();
    snapshot = new ColliderSnapshotCache();
  }

  void changed(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
    if (level != owner || before == null || before == after || (!real(before) && !real(after)))
      return;
    if (sections.remove(SectionPos.asLong(pos)) != null) revision++;
  }

  List<ColliderMerge.Box> get(
      ServerLevel level,
      ColliderWindow window,
      double x,
      double y,
      double z,
      int limit,
      int rawLimit) {
    if (owner != level) {
      clear();
      owner = level;
    }
    var visible = new ArrayList<Section>();
    var retained = new HashSet<Long>();
    for (int sx = window.minX() >> 4; sx <= window.maxX() >> 4; sx++)
      for (int sz = window.minZ() >> 4; sz <= window.maxZ() >> 4; sz++) {
        var chunk = level.getChunkSource().getChunkNow(sx, sz);
        for (int sy = window.minY() >> 4; sy <= window.maxY() >> 4; sy++) {
          long key = SectionPos.asLong(sx, sy, sz);
          retained.add(key);
          int index = chunk == null ? -1 : chunk.getSectionIndexFromSectionY(sy);
          var section =
              index >= 0 && index < chunk.getSections().length ? chunk.getSection(index) : null;
          var cached = sections.get(key);
          if (cached == null || cached.chunk != chunk || cached.section != section) {
            cached = new Section(chunk, section, scan(level, section, sx, sy, sz));
            sections.put(key, cached);
            revision++;
          }
          // Moving pistons and other dynamic shapes can change without replacing
          // their block state. Re-query only those cells, never cache their shape.
          for (int i : cached.dynamic) {
            var cell = cached.cells.get(i);
            var boxes = boxes(level, cell.pos, level.getBlockState(cell.pos));
            if (!boxes.equals(cell.boxes)) {
              cached.cells.set(i, new Cell(cell.pos, true, boxes));
              revision++;
            }
          }
          visible.add(cached);
        }
      }
    sections.keySet().retainAll(retained); // Bounded by the current motion-aware window.
    return snapshot.get(
        level,
        window,
        revision,
        x,
        y,
        z,
        limit,
        () -> {
          var raw = new ArrayList<ColliderMerge.Box>();
          for (var section : visible)
            for (var cell : section.cells) {
              var p = cell.pos;
              if (!window.contains(p.getX(), p.getY(), p.getZ())) continue;
              for (var box : cell.boxes) {
                if (raw.size() >= rawLimit) return raw;
                raw.add(box);
              }
            }
          return raw;
        });
  }

  private static List<Cell> scan(
      ServerLevel level, LevelChunkSection section, int sx, int sy, int sz) {
    var cells = new ArrayList<Cell>();
    if (section == null || section.hasOnlyAir() || !section.maybeHas(PlacedColliders::real))
      return cells;
    for (int y = 0; y < 16; y++)
      for (int z = 0; z < 16; z++)
        for (int x = 0; x < 16; x++) {
          var state = section.getBlockState(x, y, z);
          if (!real(state)) continue;
          var pos = new BlockPos((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
          boolean dynamic = ((ColliderShapeAccessor) state.getBlock()).eldencraft$dynamicShape();
          var boxes = boxes(level, pos, state);
          if (dynamic || !boxes.isEmpty()) cells.add(new Cell(pos, dynamic, boxes));
        }
    return cells;
  }

  private static List<ColliderMerge.Box> boxes(ServerLevel level, BlockPos pos, BlockState state) {
    var boxes = new ArrayList<ColliderMerge.Box>();
    for (var box : state.getCollisionShape(level, pos).toAabbs())
      boxes.add(
          new ColliderMerge.Box(
              pos.getX() + box.minX,
              pos.getY() + box.minY,
              pos.getZ() + box.minZ,
              pos.getX() + box.maxX,
              pos.getY() + box.maxY,
              pos.getZ() + box.maxZ));
    return boxes;
  }

  private static boolean real(BlockState state) {
    return !state.isAir() && !state.is(SharedWorldBlocks.TERRAIN);
  }
}
