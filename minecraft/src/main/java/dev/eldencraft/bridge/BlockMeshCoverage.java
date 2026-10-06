package dev.eldencraft.bridge;

/** A mesh must cover the entire vanilla render-area window before any chunk pass is suppressed. */
public record BlockMeshCoverage(int centerX, int centerZ, int radius, int minY, int maxY) {
  public BlockMeshCoverage {
    long width = 2L * radius + 1, height = (long) maxY - minY + 1;
    if (radius < 0
        || radius > 16
        || height < 1
        || height > 64
        || width * width * height > 65536
        || Math.abs((long) centerX) > 1875000
        || Math.abs((long) centerZ) > 1875000)
      throw new IllegalArgumentException("Render-area coverage exceeds mesh budget");
  }

  public boolean contains(int x, int y, int z) {
    return x >= (long) centerX - radius
        && x <= (long) centerX + radius
        && z >= (long) centerZ - radius
        && z <= (long) centerZ + radius
        && y >= minY
        && y <= maxY;
  }

  public boolean intersectsChunkNeighborhood(int x, int z) {
    return x >= (long) centerX - radius - 1
        && x <= (long) centerX + radius + 1
        && z >= (long) centerZ - radius - 1
        && z <= (long) centerZ + radius + 1;
  }

  /** A prebuilt border gives streaming time without suppressing any uncovered section. */
  public BlockMeshCoverage withStreamingMargin() {
    if (radius == 16 || (2L * radius + 3) * (2L * radius + 3) * ((long) maxY - minY + 1) > 65536)
      return this;
    return new BlockMeshCoverage(centerX, centerZ, radius + 1, minY, maxY);
  }

  public boolean contains(BlockMeshCoverage other) {
    return other != null
        && minY <= other.minY
        && maxY >= other.maxY
        && Math.abs((long) centerX - other.centerX) + other.radius <= radius
        && Math.abs((long) centerZ - other.centerZ) + other.radius <= radius;
  }
}
