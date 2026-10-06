package dev.eldencraft.bridge;

/** Convert the stable simulation region into the current native block's local map coordinates. */
public record InteractionMapCoordinates(
    WorldOrigin origin, long publisherPid, long sourceMap, WorldOrigin.Vec sourceToRegion) {
  public InteractionMapCoordinates {
    if (origin == null
        || publisherPid <= 0
        || sourceMap < 0
        || sourceMap > 0xffff_ffffL
        || sourceToRegion == null) throw new IllegalArgumentException("Map terrain identity");
  }

  public boolean matches(long pid, long map) {
    return publisherPid == pid && sourceMap == map;
  }

  public WorldOrigin.Vec toSource(double guestX, double guestY, double guestZ) {
    var region = origin.toHost(guestX, guestY, guestZ);
    return new WorldOrigin.Vec(
        region.x() - sourceToRegion.x(),
        region.y() - sourceToRegion.y(),
        region.z() - sourceToRegion.z());
  }
}
