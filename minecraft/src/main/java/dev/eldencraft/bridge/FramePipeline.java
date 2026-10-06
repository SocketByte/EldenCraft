package dev.eldencraft.bridge;

/** Bounded asynchronous frame policy; contains no render device or mutable game state. */
public final class FramePipeline {
  public record SceneKey(long pid, long epoch, long map, long anchor) {}

  public static final long MAX_AGE_NANOS = 250_000_000L;

  private FramePipeline() {}

  public static int hostLimit(int configured) {
    return Math.min(Math.max(configured, 1), 60);
  }

  /**
   * ECGT: a shared texture set is reusable once the host copied that frame or any newer one (it
   * copies newest-first, in order).
   */
  public static boolean gpuSetReusable(long writtenFrame, long newestAck) {
    return writtenFrame == 0 || newestAck >= writtenFrame;
  }

  /** Picks the next reusable shared set after {@code start}, or -1 to fall back to readback. */
  public static int gpuSet(long[] written, long newestAck, int start) {
    for (int i = 0; i < written.length; i++) {
      int s = (start + i) % written.length;
      if (gpuSetReusable(written[s], newestAck)) return s;
    }
    return -1;
  }

  public static int freeSlot(boolean[] busy, int start) {
    if (busy.length != 3 || start < 0 || start >= busy.length)
      throw new IllegalArgumentException("frame ring");
    for (int i = 0; i < busy.length; i++) {
      int slot = (start + i) % busy.length;
      if (!busy[slot]) return slot;
    }
    return -1;
  }

  public static boolean publishable(
      long frame, long lastPublished, long captureNanos, long now, boolean sameWorld) {
    return sameWorld
        && frame > lastPublished
        && captureNanos >= 0
        && now >= captureNanos
        && now - captureNanos < MAX_AGE_NANOS;
  }

  public static boolean samePresentation(
      SceneKey captured, SceneKey current, int capturedMode, int currentMode) {
    return capturedMode == currentMode && java.util.Objects.equals(captured, current);
  }
}
