package dev.eldencraft.bridge;

import java.util.ArrayList;
import java.util.List;

/** Seamless merged native colliders and the motion-aware publication window. */
public final class ColliderConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static ColliderMerge.Box box(
      double x0, double y0, double z0, double x1, double y1, double z1) {
    return new ColliderMerge.Box(x0, y0, z0, x1, y1, z1);
  }

  private static double volume(List<ColliderMerge.Box> boxes) {
    double sum = 0;
    for (var b : boxes)
      sum += (b.maxX() - b.minX()) * (b.maxY() - b.minY()) * (b.maxZ() - b.minZ());
    return sum;
  }

  private static boolean covered(List<ColliderMerge.Box> boxes, double x, double y, double z) {
    for (var b : boxes)
      if (x > b.minX()
          && x < b.maxX()
          && y > b.minY()
          && y < b.maxY()
          && z > b.minZ()
          && z < b.maxZ()) return true;
    return false;
  }

  public static void main(String[] args) {
    // A 10x10 floor of full blocks is one seamless body, not 100 boxes with internal edges.
    var floor = new ArrayList<ColliderMerge.Box>();
    for (int x = 0; x < 10; x++)
      for (int z = 0; z < 10; z++) floor.add(box(x, 0, z, x + 1, 1, z + 1));
    var merged = ColliderMerge.merge(floor);
    check(merged.size() == 1, "flat floor merges into one box: " + merged);
    check(merged.getFirst().equals(box(0, 0, 0, 10, 1, 10)), "merged floor keeps exact bounds");

    // A wall and a floor stay exact; their union volume never grows.
    var house = new ArrayList<ColliderMerge.Box>(floor);
    for (int x = 0; x < 10; x++)
      for (int y = 1; y < 4; y++) house.add(box(x, y, 0, x + 1, y + 1, 1));
    var walls = ColliderMerge.merge(house);
    check(walls.size() <= 3, "floor plus wall merges to a handful of boxes: " + walls.size());
    check(Math.abs(volume(walls) - volume(house)) < 1e-9, "merging is exact (same volume)");
    for (int x = 0; x < 10; x++)
      for (int y = 1; y < 4; y++)
        check(covered(walls, x + .5, y + .5, .5), "every wall block is still solid");
    check(!covered(walls, 5.5, 1.5, 5.5), "air above the floor stays open");

    // Slabs at different heights, stairs and an L shape never fill their gaps.
    var steps =
        List.of(
            box(0, 0, 0, 1, .5, 1),
            box(1, 0, 0, 2, .5, 1),
            box(2, 0, 0, 3, 1, 1),
            box(0, 0, 1, 1, .5, 2));
    var stairs = ColliderMerge.merge(steps);
    check(Math.abs(volume(stairs) - volume(steps)) < 1e-9, "slab and step volume preserved");
    check(!covered(stairs, 1.5, .25, 1.5), "L-shaped slabs keep their inside corner empty");
    check(covered(stairs, .5, .25, .5) && covered(stairs, 1.5, .25, .5), "adjacent slabs merge");
    check(!covered(stairs, .5, .75, .5), "slab tops stay at half height");

    // Fence posts (non-touching) never bridge their gap; -0.0 joins 0.0.
    var posts =
        ColliderMerge.merge(
            List.of(box(.375, 0, .375, .625, 1.5, .625), box(1.375, 0, .375, 1.625, 1.5, .625)));
    check(posts.size() == 2, "separated posts stay separate");
    var zero = ColliderMerge.merge(List.of(box(-1, -0.0, 0, 0, 1, 1), box(0, 0, 0, 1, 1, 1)));
    check(zero.size() == 1, "negative zero cross-section still merges");

    // Output is deterministic regardless of input order (stable native keys).
    var reversed = new ArrayList<>(house);
    java.util.Collections.reverse(reversed);
    check(ColliderMerge.merge(reversed).equals(walls), "merge order is deterministic");

    // A capped publication keeps the boxes nearest the player.
    var scattered = new ArrayList<ColliderMerge.Box>();
    for (int i = 0; i < 20; i++) scattered.add(box(i * 3, 0, 0, i * 3 + 1, 1, 1));
    var nearest = ColliderMerge.nearest(scattered, 0, 1, 0, 5);
    check(nearest.size() == 5, "cap honoured");
    for (var b : nearest) check(b.minX() < 15, "nearest boxes are kept, far ones dropped: " + b);

    // The window covers the player and the point they will reach while moving.
    var still = ColliderWindow.around(100.5, 64, -20.5, 0, 0, 0);
    check(still.contains(100, 64, -21) && still.contains(112, 56, -33), "radius around the feet");
    check(!still.contains(100, 40, -21), "vertical bound below");
    var dash = ColliderWindow.around(100.5, 64, -20.5, 16, 0, 0);
    check(
        dash.contains(100 + 12 + 12, 64, -21),
        "a 16 m/s dash publishes 12 blocks ahead plus radius");
    check(dash.minX() == still.minX(), "motion extends the window ahead, not behind");
    var fall = ColliderWindow.around(0, 200, 0, 0, -40, 0);
    check(fall.contains(0, 200 - 24 - 8, 0), "a fast fall publishes the landing below");
    check(
        still.minX() % ColliderWindow.SNAP == 0 && (still.maxX() + 1) % ColliderWindow.SNAP == 0,
        "bounds snap so short walks do not reshape the edge boxes");
    var nudged = ColliderWindow.around(101.4, 64, -20.5, 0, 0, 0);
    check(nudged.equals(still), "walking within a snap step keeps the same window");
    var wild = ColliderWindow.around(0, 0, 0, Double.NaN, 1e9, -1e9);
    check(
        wild.maxY() <= ColliderWindow.ABOVE + ColliderWindow.MAX_LOOKAHEAD + ColliderWindow.SNAP,
        "non-finite and huge velocities stay bounded");
    try {
      ColliderMerge.merge(List.of(box(0, 0, 0, 0, 1, 1)));
      check(false, "degenerate boxes are rejected");
    } catch (IllegalArgumentException expected) {
      check(true, "degenerate boxes are rejected");
    }
    var cache = new ColliderSnapshotCache();
    Object owner = new Object();
    int[] scans = {0};
    java.util.function.Supplier<List<ColliderMerge.Box>> scan =
        () -> {
          scans[0]++;
          return floor;
        };
    var cached = cache.get(owner, still, 1, 100, 64, -20, 4096, scan);
    check(
        cache.get(owner, nudged, 1, 101, 64, -20, 4096, scan) == cached && scans[0] == 1,
        "moving within the window reuses exact collision without scanning or merging");
    cache.get(owner, still, 2, 101, 64, -20, 4096, scan);
    check(scans[0] == 2, "block or dynamic-shape revision invalidates the merge");
    cache.get(owner, dash, 2, 101, 64, -20, 4096, scan);
    check(scans[0] == 3, "entering coverage scans new collision");
    cache.get(new Object(), dash, 2, 101, 64, -20, 4096, scan);
    check(scans[0] == 4, "replacement world cannot reuse an old merge");
    var capped = new ColliderSnapshotCache();
    Object cappedOwner = new Object();
    int[] cappedScans = {0};
    java.util.function.Supplier<List<ColliderMerge.Box>> cappedScan =
        () -> {
          cappedScans[0]++;
          return scattered;
        };
    var nearStart = capped.get(cappedOwner, still, 1, 0, 1, 0, 5, cappedScan);
    var nearEnd = capped.get(cappedOwner, still, 1, 57, 1, 0, 5, cappedScan);
    check(
        cappedScans[0] == 1 && !nearStart.equals(nearEnd),
        "capped selection follows feet without rescanning");
    check(
        nearEnd.equals(ColliderMerge.nearest(scattered, 57, 1, 0, 5)),
        "cached selection equals exact reference");
    System.out.println("ColliderConformance: " + checks + " checks passed");
  }
}
