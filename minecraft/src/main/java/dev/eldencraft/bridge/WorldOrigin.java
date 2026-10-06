package dev.eldencraft.bridge;

/** Stable region coordinates. Camera-relative rendering must not move the simulation origin. */
public record WorldOrigin(long epoch, long map, long anchorId, Vec hostOrigin, Vec guestOrigin) {
  public record Vec(double x, double y, double z) {
    public Vec {
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || !Double.isFinite(z)
          || Math.abs(x) > 30_000_000
          || Math.abs(y) > 30_000_000
          || Math.abs(z) > 30_000_000)
        throw new IllegalArgumentException("Invalid world coordinate");
    }
  }

  public WorldOrigin {
    if (epoch <= 0
        || map < 0
        || map > 0xffff_ffffL
        || anchorId <= 0
        || hostOrigin == null
        || guestOrigin == null) throw new IllegalArgumentException("Invalid world origin");
  }

  public Vec toGuest(double x, double y, double z) {
    return new Vec(
        guestOrigin.x + x - hostOrigin.x,
        guestOrigin.y + y - hostOrigin.y,
        guestOrigin.z + z - hostOrigin.z);
  }

  public Vec toHost(double x, double y, double z) {
    return new Vec(
        hostOrigin.x + x - guestOrigin.x,
        hostOrigin.y + y - guestOrigin.y,
        hostOrigin.z + z - guestOrigin.z);
  }
}
