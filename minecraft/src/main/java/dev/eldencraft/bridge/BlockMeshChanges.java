package dev.eldencraft.bridge;

import java.util.ArrayDeque;

/**
 * Separate face/coverage changes from shading work on an otherwise complete mesh, and measure how
 * long each geometry change has waited for a displayed snapshot. A mesh that keeps up with
 * continuous edits (flowing water, fire, redstone) is never stale for long, so it is never replaced
 * by the RGB-D fallback; an edit that is not displayed within the grace still is.
 */
public final class BlockMeshChanges {
  public record Version(long geometry, long lighting) {}

  /** An undisplayed placement or removal is visible as stale geometry for at most this long. */
  public static final long GRACE_NANOS = 1_000_000_000L;

  private static final int MAX_PENDING = 1024;

  private long geometry = 1, lighting = 1;
  // (geometry generation, time) of changes not yet known to be displayed, oldest first.
  private final ArrayDeque<long[]> pending = new ArrayDeque<>();

  public Version version() {
    return new Version(geometry, lighting);
  }

  public void geometryChanged() {
    geometryChanged(System.nanoTime());
  }

  public void geometryChanged(long now) {
    geometry++;
    if (pending.size() >= MAX_PENDING) {
      // Fold into the newest entry, keeping its older time: staleness is never understated.
      pending.peekLast()[0] = geometry;
    } else pending.addLast(new long[] {geometry, now});
  }

  public void lightingChanged() {
    lighting++;
  }

  public boolean current(Version version) {
    return version != null && version.geometry == geometry && version.lighting == lighting;
  }

  public boolean geometryCurrent(Version version) {
    return version != null && version.geometry == geometry;
  }

  /** How long the oldest geometry change missing from {@code version} has waited; 0 if none. */
  public long staleNanos(Version version, long now) {
    if (version == null) return Long.MAX_VALUE;
    for (var change : pending)
      if (change[0] > version.geometry) return Math.max(0, now - change[1]);
    return 0;
  }

  public boolean withinGrace(Version version, long now) {
    return staleNanos(version, now) <= GRACE_NANOS;
  }

  /** The displayed snapshot contains every change up to its version; forget those. */
  public void displayed(Version version) {
    if (version == null) return;
    while (!pending.isEmpty() && pending.peekFirst()[0] <= version.geometry) pending.removeFirst();
  }
}
