package dev.eldencraft.bridge;

/** A fresh continuous host HP decrease is feedback, never a request to change guest health. */
public final class HostDamageState {
  public static final long MAX_GAP_MILLIS = 250;
  private Object player, world;
  private long pid, mapId, frame, timestamp;
  private int hp, maxHp;
  private boolean bound;

  public void reset() {
    bound = false;
    player = world = null;
  }

  /** Caller supplies only active, live-publisher snapshots younger than MAX_GAP_MILLIS. */
  public int observe(
      Object nextPlayer,
      Object nextWorld,
      long nextPid,
      long nextMap,
      long nextFrame,
      long nextTimestamp,
      int nextHp,
      int nextMaxHp) {
    if (nextPlayer == null
        || nextWorld == null
        || nextPid <= 0
        || nextFrame <= 0
        || nextTimestamp < 0
        || nextMaxHp <= 0
        || nextHp < 0
        || nextHp > nextMaxHp) {
      reset();
      return 0;
    }
    boolean same =
        bound
            && player == nextPlayer
            && world == nextWorld
            && pid == nextPid
            && mapId == nextMap
            && maxHp == nextMaxHp;
    if (same && nextFrame == frame) {
      // A repeated publication cannot generate feedback, including an inconsistent repeated frame.
      if (nextTimestamp != timestamp || nextHp != hp) reset();
      return 0;
    }
    if (same && (nextFrame < frame || nextTimestamp < timestamp)) {
      reset();
      return 0;
    }
    int damage = same && nextTimestamp - timestamp < MAX_GAP_MILLIS ? Math.max(0, hp - nextHp) : 0;
    player = nextPlayer;
    world = nextWorld;
    pid = nextPid;
    mapId = nextMap;
    frame = nextFrame;
    timestamp = nextTimestamp;
    hp = nextHp;
    maxHp = nextMaxHp;
    bound = true;
    return damage;
  }
}
