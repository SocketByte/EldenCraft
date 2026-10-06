package dev.eldencraft.bridge;

/** Permission for the mouse-grab gate only; vanilla retains every mining/gameplay check. */
public final class HostMiningInput {
  public static final long MAX_AGE_MILLIS = 250;
  private static final int ATTACK = 1;

  public record Input(long publisherPid, long mapId, long sequence, int buttons) {}

  private HostMiningInput() {}

  /** Loss must re-arm the existing release-before-reacquire rule, even between normal ticks. */
  public static boolean lostContext(Input applied, Input observed) {
    return applied == null
        || observed == null
        || applied.publisherPid() <= 0
        || applied.publisherPid() != observed.publisherPid()
        || applied.mapId() != observed.mapId()
        || applied.sequence() < 0
        || observed.sequence() < applied.sequence();
  }

  /** observed must come from a fresh, active, live-publisher read; null revokes permission. */
  public static boolean mayContinue(
      boolean ownsHeldKey, boolean guiOpen, Input applied, Input observed, int blockedButtons) {
    return ownsHeldKey
        && !guiOpen
        && !lostContext(applied, observed)
        && (blockedButtons & ATTACK) == 0
        && (applied.buttons() & ATTACK) != 0
        && (observed.buttons() & ATTACK) != 0;
  }
}
