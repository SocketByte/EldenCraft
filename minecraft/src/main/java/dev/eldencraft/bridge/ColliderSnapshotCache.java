package dev.eldencraft.bridge;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** A window/revision owns an exact merge; only capped selection depends on the feet. */
public final class ColliderSnapshotCache {
  private Object context;
  private ColliderWindow window;
  private long revision;
  private List<ColliderMerge.Box> merged = List.of(), selected = List.of();
  private double x, y, z;
  private int limit;

  public List<ColliderMerge.Box> get(
      Object context,
      ColliderWindow window,
      long revision,
      double x,
      double y,
      double z,
      int limit,
      Supplier<List<ColliderMerge.Box>> scan) {
    boolean changed =
        this.context != context
            || !Objects.equals(this.window, window)
            || this.revision != revision;
    if (changed) {
      merged = List.copyOf(ColliderMerge.merge(scan.get()));
      this.context = context;
      this.window = window;
      this.revision = revision;
    }
    if (changed
        || this.limit != limit
        || (merged.size() > limit && (this.x != x || this.y != y || this.z != z))) {
      selected =
          merged.size() <= limit
              ? merged
              : List.copyOf(ColliderMerge.nearestMerged(merged, x, y, z, limit));
      this.x = x;
      this.y = y;
      this.z = z;
      this.limit = limit;
    }
    return selected;
  }
}
