package dev.eldencraft.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Greedy box merging for the native block colliders. Elden Ring's character capsule catches on the
 * internal edges between separate coplanar bodies; one box per merged run has no seams, and far
 * fewer bodies reach Havok. Only boxes with an identical cross-section that touch or overlap along
 * an axis are joined, so the union is always exact.
 */
public final class ColliderMerge {
  /** Integer and 1/16 block fractions are exact in binary; this only absorbs host round-off. */
  private static final double EPSILON = 1e-7;

  public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Box {
      if (!(minX < maxX && minY < maxY && minZ < maxZ)
          || !Double.isFinite(minX + minY + minZ + maxX + maxY + maxZ))
        throw new IllegalArgumentException("Collider box");
    }

    double min(int axis) {
      return axis == 0 ? minX : axis == 1 ? minY : minZ;
    }

    double max(int axis) {
      return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
    }

    /** Squared distance from a point to this box (zero inside). */
    public double distanceSquared(double x, double y, double z) {
      double dx = Math.max(0, Math.max(minX - x, x - maxX));
      double dy = Math.max(0, Math.max(minY - y, y - maxY));
      double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
      return dx * dx + dy * dy + dz * dz;
    }

    private Box extend(int axis, double to) {
      return switch (axis) {
        case 0 -> new Box(minX, minY, minZ, to, maxY, maxZ);
        case 1 -> new Box(minX, minY, minZ, maxX, to, maxZ);
        default -> new Box(minX, minY, minZ, maxX, maxY, to);
      };
    }
  }

  private record CrossSection(long a, long b, long c, long d) {}

  private ColliderMerge() {}

  /** Merged boxes in a deterministic order. The input is not modified. */
  public static List<Box> merge(List<Box> boxes) {
    var current = new ArrayList<>(boxes);
    // X runs, then Z rows, then Y stacks; repeat while anything still joins.
    for (int pass = 0; pass < 4; pass++) {
      int before = current.size();
      for (int axis : new int[] {0, 2, 1}) current = mergeAlong(current, axis);
      if (current.size() == before) break;
    }
    current.sort(ORDER);
    return current;
  }

  /** Merge, then keep the {@code limit} boxes nearest to the point (nearest first). */
  public static List<Box> nearest(List<Box> boxes, double x, double y, double z, int limit) {
    return nearestMerged(merge(boxes), x, y, z, limit);
  }

  /** Select from a cached exact merge; a changing player position never re-merges the world. */
  public static List<Box> nearestMerged(List<Box> boxes, double x, double y, double z, int limit) {
    var merged = new ArrayList<>(boxes);
    if (merged.size() <= limit) return merged;
    merged.sort(
        Comparator.comparingDouble((Box b) -> b.distanceSquared(x, y, z)).thenComparing(ORDER));
    var kept = new ArrayList<>(merged.subList(0, limit));
    kept.sort(ORDER);
    return kept;
  }

  private static final Comparator<Box> ORDER =
      Comparator.comparingDouble(Box::minY)
          .thenComparingDouble(Box::minZ)
          .thenComparingDouble(Box::minX)
          .thenComparingDouble(Box::maxY)
          .thenComparingDouble(Box::maxZ)
          .thenComparingDouble(Box::maxX);

  private static long bits(double value) {
    // Normalize -0.0 so equal cross-sections always share a group.
    return Double.doubleToLongBits(value == 0 ? 0 : value);
  }

  private static ArrayList<Box> mergeAlong(List<Box> boxes, int axis) {
    int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
    var groups = new HashMap<CrossSection, List<Box>>();
    for (var box : boxes)
      groups
          .computeIfAbsent(
              new CrossSection(
                  bits(box.min(u)), bits(box.max(u)), bits(box.min(v)), bits(box.max(v))),
              k -> new ArrayList<>())
          .add(box);
    var out = new ArrayList<Box>(boxes.size());
    for (var group : groups.values()) {
      group.sort(Comparator.comparingDouble((Box b) -> b.min(axis)));
      Box run = null;
      for (var box : group) {
        if (run != null && box.min(axis) <= run.max(axis) + EPSILON) {
          if (box.max(axis) > run.max(axis)) run = run.extend(axis, box.max(axis));
        } else {
          if (run != null) out.add(run);
          run = box;
        }
      }
      if (run != null) out.add(run);
    }
    return out;
  }
}
