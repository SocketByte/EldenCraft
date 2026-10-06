package dev.eldencraft.bridge;

/** North-up map projection. Zoom preserves the point beneath the cursor. */
public final class InteractionMapView {
  private double x, z, scale = 3;

  public double x() {
    return x;
  }

  public double z() {
    return z;
  }

  public double scale() {
    return scale;
  }

  public void center(double x, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(z))
      throw new IllegalArgumentException("Map position");
    this.x = x;
    this.z = z;
  }

  public void pan(double pixelsX, double pixelsZ) {
    if (!Double.isFinite(pixelsX) || !Double.isFinite(pixelsZ)) return;
    x = Math.clamp(x - pixelsX / scale, -30_000_000, 30_000_000);
    z = Math.clamp(z - pixelsZ / scale, -30_000_000, 30_000_000);
  }

  public void zoom(double amount, double cursorOffsetX, double cursorOffsetZ) {
    if (!Double.isFinite(amount)
        || !Double.isFinite(cursorOffsetX)
        || !Double.isFinite(cursorOffsetZ)) return;
    double old = scale;
    scale = Math.clamp(scale * Math.pow(1.25, amount), .25, 12);
    if (scale == old) return;
    x = Math.clamp(x + cursorOffsetX / old - cursorOffsetX / scale, -30_000_000, 30_000_000);
    z = Math.clamp(z + cursorOffsetZ / old - cursorOffsetZ / scale, -30_000_000, 30_000_000);
  }

  public double worldX(double pixels) {
    return x + pixels / scale;
  }

  public double worldZ(double pixels) {
    return z + pixels / scale;
  }

  public int pixelX(double world) {
    return (int) Math.round((world - x) * scale);
  }

  public int pixelZ(double world) {
    return (int) Math.round((world - z) * scale);
  }
}
