package dev.eldencraft.bridge;

/** Elden Ring's Spectral Steed rules in Minecraft terms. The native host owns Torrent's gait. */
public final class TorrentPolicy {
  /** Rider feet above the horse: Horse passenger attachment minus the player's vehicle one. */
  public static final double RIDER_LIFT = 1.44375 - 0.6;

  public static final float MAX_HEALTH = 30;

  /** Food points that call a fallen Torrent back, as a flask of crimson tears does. */
  public static final int REVIVE_FOOD = 6;

  /** Health regained per second while Torrent is not summoned. */
  public static final float REST_PER_SECOND = .2f;

  public static final long COOLDOWN_NANOS = 1_000_000_000L;

  /** A mount survives this long without a host sync (menus, loads) before it is dismissed. */
  public static final int UNSYNCED_TICKS = 20;

  public enum Refusal {
    NONE(""),
    AREA("Torrent cannot be summoned here."),
    AIRBORNE("Torrent cannot be summoned in the air."),
    GLIDING("Torrent cannot be summoned while gliding."),
    LIQUID("Torrent cannot be summoned in water or lava."),
    COOLDOWN(""),
    HUNGRY("Torrent has fallen. Calling him back costs " + REVIVE_FOOD / 2 + " shanks of food.");

    public final String message;

    Refusal(String message) {
      this.message = message;
    }
  }

  private TorrentPolicy() {}

  /**
   * The whistle works in the open world (m60, and m61 of the Realm of Shadow) and the underground
   * (m12), never in legacy dungeons, catacombs, caves or arenas. {@code block} is the native
   * BlockId, whose high byte is the area.
   */
  public static boolean allowedArea(long block) {
    if (block < 0 || block > 0xffff_ffffL) return false;
    int area = (int) (block >>> 24);
    return area == 60 || area == 61 || area == 12;
  }

  public static Refusal summon(
      boolean area,
      boolean grounded,
      boolean gliding,
      boolean liquid,
      boolean cooling,
      boolean fallen,
      int food,
      boolean creative) {
    if (cooling) return Refusal.COOLDOWN;
    if (!area) return Refusal.AREA;
    if (gliding) return Refusal.GLIDING;
    if (!grounded) return Refusal.AIRBORNE;
    if (liquid) return Refusal.LIQUID;
    if (fallen && !creative && food < REVIVE_FOOD) return Refusal.HUNGRY;
    return Refusal.NONE;
  }

  /**
   * Native hits land on the rider's capsule. Torrent loses the same share of its health as the
   * rider loses of the host's; a changed maximum (a remembrance) is not damage.
   */
  public static float sharedDamage(float hpBefore, float hpAfter, float maxBefore, float maxAfter) {
    if (!(maxBefore > 0)
        || maxBefore != maxAfter
        || !(hpAfter < hpBefore)
        || hpBefore > maxBefore
        || !Float.isFinite(hpAfter)) return 0;
    return MAX_HEALTH * (hpBefore - Math.max(0, hpAfter)) / maxBefore;
  }

  /** Health after resting {@code nanos} while dismissed. A fallen Torrent stays fallen. */
  public static float rested(float health, long nanos) {
    if (!(health > 0) || nanos <= 0) return Math.max(0, Math.min(MAX_HEALTH, health));
    return Math.min(MAX_HEALTH, health + REST_PER_SECOND * (nanos / 1e9f));
  }
}
