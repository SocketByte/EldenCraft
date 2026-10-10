package dev.eldencraft.bridge;

/** One continuous host HP drain is one feedback episode, never a guest health change. */
public final class HostDamageState {
  public static final long MAX_GAP_MILLIS = 250;
  public static final long QUIET_MILLIS = 500;
  private Object player, world;
  private long pid, mapId, frame, timestamp;
  private int hp, maxHp;
  private boolean bound;
  private long lastDrop;
  private boolean draining;

  public void reset() {
    bound = false;
    player = world = null;
    draining = false;
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
    boolean continuous = same && nextTimestamp - timestamp < MAX_GAP_MILLIS;
    if (!continuous) draining = false;
    int damage = continuous ? Math.max(0, hp - nextHp) : 0;
    if (damage > 0) {
      boolean startsEpisode = !draining || nextTimestamp - lastDrop >= QUIET_MILLIS;
      lastDrop = nextTimestamp;
      draining = true;
      if (!startsEpisode) damage = 0;
    }
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
