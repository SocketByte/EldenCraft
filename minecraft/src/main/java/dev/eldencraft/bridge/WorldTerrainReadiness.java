package dev.eldencraft.bridge;

/** Reuses only previously complete local terrain during a brief incomplete replacement. */
public final class WorldTerrainReadiness {
  public static final long HOLD_MILLIS = 1000;

  public record Context(long hostPid, long session, long epoch, long map, long anchor) {}

  public record Complete(Context context, WorldProtocol.Box bounds, long revision, long millis) {}

  private WorldTerrainReadiness() {}

  public static boolean mayReuse(
      Complete complete, Context current, WorldOrigin.Vec feet, long now) {
    if (complete == null
        || current == null
        || feet == null
        || !current.equals(complete.context)
        || current.hostPid <= 0
        || current.session <= 0
        || current.epoch <= 0
        || current.anchor <= 0
        || complete.revision < 0
        || complete.millis < 0
        || now < complete.millis
        || now - complete.millis > HOLD_MILLIS) return false;
    var bounds = complete.bounds;
    // Whole local neighborhood, including the player's body and immediate ground.
    return bounds != null
        && feet.x() - 1.5 >= bounds.min().x()
        && feet.x() + 1.5 <= bounds.max().x()
        && feet.z() - 1.5 >= bounds.min().z()
        && feet.z() + 1.5 <= bounds.max().z()
        && feet.y() - .5 >= bounds.min().y()
        && feet.y() + 2.5 <= bounds.max().y();
  }
}
