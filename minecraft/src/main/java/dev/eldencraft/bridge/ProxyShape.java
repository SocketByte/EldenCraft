package dev.eldencraft.bridge;

/** Entity dimensions and particle sampling share the exact validated host-box extents. */
public record ProxyShape(double x, double height, double z) {
  public ProxyShape {
    if (!valid(x) || !valid(height) || !valid(z))
      throw new IllegalArgumentException("proxy extents");
  }

  private static boolean valid(double extent) {
    return Double.isFinite(extent) && extent > 0 && extent <= 64;
  }

  public float width() {
    return (float) Math.max(x, z);
  }

  public float entityHeight() {
    return (float) height;
  }

  public double sampleX(double center, double offset) {
    return center + x * offset;
  }

  public double sampleY(double feet, double fraction) {
    return feet + height * fraction;
  }

  public double sampleZ(double center, double offset) {
    return center + z * offset;
  }
}
