package dev.eldencraft.bridge;

import java.util.*;

/**
 * What Elden Ring terrain is made of, as Minecraft blocks.
 *
 * <p>Elden Ring reports the hit material (HitMtrlParam row) under the player's feet; terrain rays
 * report each surface's Havok body material, and the host learns which body material goes with
 * which hit material. A hidden terrain cell resolves, in order: a footprint on that cell, its body
 * material through the learned table, the nearest footprint within three blocks, then its shape
 * (steep faces are rock, the rest dirt). Overrides from config/eldencraft-materials.json map hit or
 * body materials to any block (for example a tree trunk's body material to a log).
 *
 * <p>Writers: the integrated server thread. Readers: server and client threads (immutable
 * snapshots).
 */
public final class TerrainMaterials {
  public static final int UNKNOWN = -1, NO_BODY = 65535;
  public static final int ROCK = 2, DIRT = 5;

  /** HitMtrlParam row names (Paramdex, soulsmods/Paramdex ER/Names/HitMtrlParam.txt). */
  private static final Map<Integer, String> NAMES =
      Map.ofEntries(
          Map.entry(0, "None"),
          Map.entry(1, "Stock"),
          Map.entry(2, "Rock"),
          Map.entry(3, "Sand"),
          Map.entry(4, "Wood"),
          Map.entry(5, "Dirt"),
          Map.entry(6, "Ore"),
          Map.entry(7, "Lava"),
          Map.entry(8, "Scarlet Tree"),
          Map.entry(11, "Iron Grate"),
          Map.entry(12, "Scarlet Mushroom"),
          Map.entry(14, "Bone"),
          Map.entry(21, "Water"),
          Map.entry(23, "Shallow Poison Swamp"),
          Map.entry(24, "Poison Swamp"),
          Map.entry(44, "Rain Dirt"),
          Map.entry(46, "Scarlet Swamp"),
          Map.entry(49, "Lake of Rot"));

  /** Default Minecraft block per hit material; null = not mineable (water, unknown none). */
  private static final Map<Integer, String> BLOCKS =
      Map.ofEntries(
          Map.entry(1, "minecraft:stone_bricks"),
          Map.entry(2, "minecraft:stone"),
          Map.entry(3, "minecraft:sand"),
          Map.entry(4, "minecraft:oak_log"),
          Map.entry(5, "minecraft:dirt"),
          Map.entry(6, "minecraft:iron_ore"),
          Map.entry(7, "minecraft:magma_block"),
          Map.entry(8, "minecraft:crimson_stem"),
          Map.entry(11, "minecraft:iron_bars"),
          Map.entry(12, "minecraft:red_mushroom_block"),
          Map.entry(14, "minecraft:bone_block"),
          Map.entry(23, "minecraft:mud"),
          Map.entry(24, "minecraft:mud"),
          Map.entry(44, "minecraft:coarse_dirt"),
          Map.entry(46, "minecraft:mud"),
          Map.entry(49, "minecraft:mud"));

  private static final int FOOTPRINTS = 8192, NEARBY = 3;

  public record Resolution(int hit, int body, String source) {}

  private static volatile Map<Long, Integer> bodies = Map.of();
  private static volatile Set<Long> steep = Set.of();
  private static volatile Map<Integer, Integer> learned = Map.of();
  private static volatile int ground = UNKNOWN;
  private static volatile Map<Integer, String> hitOverrides = Map.of(), bodyOverrides = Map.of();
  private static final LinkedHashMap<Long, Integer> footprints =
      new LinkedHashMap<>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
          return size() > FOOTPRINTS;
        }
      };

  private TerrainMaterials() {}

  /** Exact row first, then the row modulo 100 (variants such as 1xx share a base material). */
  static <T> T byHit(Map<Integer, T> table, int hit) {
    if (hit < 0) return null;
    var exact = table.get(hit);
    return exact != null ? exact : table.get(hit % 100);
  }

  public static String name(int hit) {
    var n = byHit(NAMES, hit);
    return n == null ? "Unnamed" : n;
  }

  /**
   * Server thread, per terrain update: body material and steepness of each cell, plus the learned
   * table.
   */
  public static void publishTerrain(
      Map<Long, Integer> cellBodies, Set<Long> steepCells, Map<Integer, Integer> table) {
    bodies = Map.copyOf(cellBodies);
    steep = Set.copyOf(steepCells);
    learned = Map.copyOf(table);
  }

  /** Server thread, per host update: the material under the feet and the cell it marks. */
  public static void observeGround(int hit, long cellBelowFeet, boolean grounded) {
    ground = grounded ? hit : UNKNOWN;
    if (grounded && hit > 0)
      synchronized (footprints) {
        footprints.put(cellBelowFeet, hit);
      }
  }

  public static int ground() {
    return ground;
  }

  public static void overrides(Map<Integer, String> byHit, Map<Integer, String> byBody) {
    hitOverrides = Map.copyOf(byHit);
    bodyOverrides = Map.copyOf(byBody);
  }

  public static void clear() {
    bodies = Map.of();
    steep = Set.of();
    learned = Map.of();
    ground = UNKNOWN;
    synchronized (footprints) {
      footprints.clear();
    }
  }

  public static Resolution resolve(long cell) {
    int body = bodies.getOrDefault(cell, NO_BODY);
    Integer stepped;
    synchronized (footprints) {
      stepped = footprints.get(cell);
    }
    if (stepped != null) return new Resolution(stepped, body, "footprint");
    if (body != NO_BODY && bodyOverrides.containsKey(body))
      return new Resolution(UNKNOWN, body, "body override");
    var hit = body == NO_BODY ? null : learned.get(body);
    if (hit != null) return new Resolution(hit, body, "learned body material");
    int x = unpackX(cell), y = unpackY(cell), z = unpackZ(cell);
    Integer best = null;
    int bestDistance = Integer.MAX_VALUE;
    synchronized (footprints) {
      for (int dx = -NEARBY; dx <= NEARBY; dx++)
        for (int dy = -NEARBY; dy <= NEARBY; dy++)
          for (int dz = -NEARBY; dz <= NEARBY; dz++) {
            var near = footprints.get(pack(x + dx, y + dy, z + dz));
            int d = dx * dx + dy * dy + dz * dz;
            if (near != null && d < bestDistance) {
              best = near;
              bestDistance = d;
            }
          }
    }
    if (best != null) return new Resolution(best, body, "nearby footprint");
    return new Resolution(steep.contains(cell) ? ROCK : DIRT, body, "shape");
  }

  /** The block id a resolution mines as, or null when it is not mineable (water, none). */
  public static String blockId(Resolution r) {
    if (r.body() != NO_BODY) {
      var b = bodyOverrides.get(r.body());
      if (b != null) return b;
    }
    var h = byHit(hitOverrides, r.hit());
    if (h != null) return h;
    return byHit(BLOCKS, r.hit());
  }

  // BlockPos.asLong layout (26 bits X, 12 bits Y, 26 bits Z), without depending on BlockPos here.
  public static long pack(int x, int y, int z) {
    return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
  }

  static int unpackX(long v) {
    return (int) (v >> 38);
  }

  static int unpackY(long v) {
    return (int) (v << 52 >> 52);
  }

  static int unpackZ(long v) {
    return (int) (v << 26 >> 38);
  }
}
