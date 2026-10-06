package dev.eldencraft.bridge;

/** Producer age plus time since copying: rereading a heartbeat never renews old authority. */
public final class WorldLeasePolicy {
  public static final long MAX_MILLIS = 500;

  private WorldLeasePolicy() {}

  public static boolean fresh(
      long publishedMillis, long copiedClockMillis, long copiedNanos, long nowNanos) {
    if (publishedMillis < 0
        || copiedClockMillis < publishedMillis
        || copiedNanos < 0
        || nowNanos < copiedNanos) return false;
    long age = copiedClockMillis - publishedMillis;
    return age < MAX_MILLIS && (nowNanos - copiedNanos) / 1_000_000L < MAX_MILLIS - age;
  }

  public static boolean retain(
      long publishedMillis, long nowMillis, boolean transientRead, boolean producerAlive) {
    return transientRead
        && producerAlive
        && publishedMillis >= 0
        && nowMillis >= publishedMillis
        && nowMillis - publishedMillis < MAX_MILLIS;
  }
}
