package dev.eldencraft.bridge;

/** HUD units only. Campaign capacity, damage and healing keep their original units. */
public final class CampaignHeartScale {
  public static final double START = 20, MAXIMUM = 60;

  private CampaignHeartScale() {}

  public static double maximum(double capacity, double start, double end) {
    if (!Double.isFinite(capacity)
        || !Double.isFinite(start)
        || !Double.isFinite(end)
        || start <= 0
        || end <= start) return START;
    return START + (MAXIMUM - START) * Math.clamp((capacity - start) / (end - start), 0, 1);
  }

  public static float units(double value, double capacity, double displayMaximum) {
    if (!Double.isFinite(value) || !Double.isFinite(capacity) || capacity <= 0) return 0;
    return (float) (Math.max(0, value) * displayMaximum / capacity);
  }
}
