package dev.eldencraft.bridge;

/**
 * Block range whose collision is published to the native host. It surrounds the player and the
 * point they will reach in {@link #LOOKAHEAD_SECONDS}, so a sprint, a Torrent dash or a glide finds
 * colliders already built. Bounds snap to {@link #SNAP}-block steps: walking a few blocks does not
 * reshape merged boxes at the window edge on every step.
 */
public record ColliderWindow(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public static final int RADIUS = 12, BELOW = 8, ABOVE = 12, SNAP = 4;
  public static final double LOOKAHEAD_SECONDS = 0.75, MAX_LOOKAHEAD = 24;

  public ColliderWindow {
    if (minX > maxX || minY > maxY || minZ > maxZ)
      throw new IllegalArgumentException("Collider window");
  }

  /** Feet position and velocity (blocks, blocks per second) in Minecraft coordinates. */
  public static ColliderWindow around(
      double x, double y, double z, double vx, double vy, double vz) {
    if (!Double.isFinite(x + y + z)) throw new IllegalArgumentException("Collider window centre");
    double dx = lead(vx), dy = lead(vy), dz = lead(vz);
    return new ColliderWindow(
        snapDown(Math.floor(Math.min(x, x + dx)) - RADIUS),
        snapDown(Math.floor(Math.min(y, y + dy)) - BELOW),
        snapDown(Math.floor(Math.min(z, z + dz)) - RADIUS),
        snapUp(Math.floor(Math.max(x, x + dx)) + RADIUS),
        snapUp(Math.floor(Math.max(y, y + dy)) + ABOVE),
        snapUp(Math.floor(Math.max(z, z + dz)) + RADIUS));
  }

  private static double lead(double velocity) {
    if (!Double.isFinite(velocity)) return 0;
    return Math.clamp(velocity * LOOKAHEAD_SECONDS, -MAX_LOOKAHEAD, MAX_LOOKAHEAD);
  }

  private static int snapDown(double value) {
    return Math.floorDiv((int) value, SNAP) * SNAP;
  }

  private static int snapUp(double value) {
    return Math.floorDiv((int) value, SNAP) * SNAP + SNAP - 1;
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }
}
