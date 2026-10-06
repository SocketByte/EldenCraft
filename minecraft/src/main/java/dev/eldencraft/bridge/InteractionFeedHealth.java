package dev.eldencraft.bridge;

/** Report sustained feed loss once, then its recovery; foreground gaps are not failures. */
public final class InteractionFeedHealth {
  public enum Change {
    NONE,
    UNAVAILABLE,
    RESTORED
  }

  private static final long DELAY_NANOS = 2_000_000_000L;
  private long publisher;
  private long unavailableSince = -1;
  private boolean reported;

  public Change observe(long pid, boolean checking, boolean available, long now) {
    if (!checking) {
      unavailableSince = -1;
      return Change.NONE;
    }
    if (publisher != pid) {
      publisher = pid;
      unavailableSince = -1;
      reported = false;
    }
    if (available) {
      unavailableSince = -1;
      if (!reported) return Change.NONE;
      reported = false;
      return Change.RESTORED;
    }
    if (reported) return Change.NONE;
    if (unavailableSince < 0 || now < unavailableSince) {
      unavailableSince = now;
      return Change.NONE;
    }
    if (now - unavailableSince < DELAY_NANOS) return Change.NONE;
    reported = true;
    return Change.UNAVAILABLE;
  }
}
