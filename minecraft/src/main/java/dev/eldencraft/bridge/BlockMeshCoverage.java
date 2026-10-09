package dev.eldencraft.bridge;

/** A mesh must cover the entire vanilla render-area window before any chunk pass is suppressed. */
public record BlockMeshCoverage(int centerX, int centerZ, int radius, int minY, int maxY) {
  /** Vanilla's 32-chunk render distance plus the streaming margin. */
  public static final int MAX_RADIUS = 32 + 2;

  /** Prebuilt chunks beyond the vanilla view: crossing this many borders keeps the mesh. */
  public static final int STREAMING_MARGIN = 2;

  /** Palette checks per complete build (a 69x69-chunk, 24-section overworld fits). */
  public static final long MAX_SECTIONS = 131072;

  public BlockMeshCoverage {
    long width = 2L * radius + 1, height = (long) maxY - minY + 1;
    if (radius < 0
        || radius > MAX_RADIUS
        || height < 1
        || height > 64
        || width * width * height > MAX_SECTIONS
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

  /**
   * A prebuilt border gives streaming time without suppressing any uncovered section. Every render
   * distance keeps the full margin while the section budget allows it.
   */
  public BlockMeshCoverage withStreamingMargin() {
    long height = (long) maxY - minY + 1;
    for (int margin = STREAMING_MARGIN; margin > 0; margin--) {
      long width = 2L * (radius + margin) + 1;
      if (radius + margin <= MAX_RADIUS && width * width * height <= MAX_SECTIONS)
        return new BlockMeshCoverage(centerX, centerZ, radius + margin, minY, maxY);
    }
    return this;
  }

  public boolean contains(BlockMeshCoverage other) {
    return other != null
        && minY <= other.minY
        && maxY >= other.maxY
        && Math.abs((long) centerX - other.centerX) + other.radius <= radius
        && Math.abs((long) centerZ - other.centerZ) + other.radius <= radius;
  }
}
