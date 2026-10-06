package dev.eldencraft.bridge;

/** Delayed loss feedback in normalized bar units, driven by elapsed time. */
public final class HudDamageTrail {
  private String identity;
  private double current, trail;
  private long lastNanos, holdUntil;

  public double update(String key, double fraction, long now, double holdSeconds, double speed) {
    if (key == null
        || !Double.isFinite(fraction)
        || fraction < 0
        || fraction > 1
        || !Double.isFinite(holdSeconds)
        || holdSeconds < 0
        || !Double.isFinite(speed)
        || speed <= 0) throw new IllegalArgumentException("Invalid HUD trail observation");
    if (!key.equals(identity) || now < lastNanos || now - lastNanos > 2_000_000_000L) {
      identity = key;
      current = trail = fraction;
      lastNanos = holdUntil = now;
      return trail;
    }
    if (fraction < current) {
      trail = Math.max(trail, current);
      holdUntil = now + (long) (holdSeconds * 1_000_000_000L);
    } else if (fraction > current) {
      trail = fraction;
      holdUntil = now;
    }
    double elapsed = Math.max(0, now - Math.max(lastNanos, holdUntil)) / 1_000_000_000d;
    current = fraction;
    trail = Math.max(current, trail - speed * elapsed);
    lastNanos = now;
    return trail;
  }

  public double current() {
    return current;
  }

  public void clear() {
    identity = null;
    current = trail = 0;
    lastNanos = holdUntil = 0;
  }

  public static int staminaTop(
      int guiHeight,
      double maxHp,
      double currentHp,
      double absorption,
      int barHeight,
      int offsetY) {
    int rows =
        Math.max(
            1,
            (int) Math.ceil((Math.max(maxHp, Math.ceil(currentHp)) + Math.ceil(absorption)) / 20));
    int spacing = Math.max(12 - rows, 3);
    return Math.clamp(
        guiHeight - 49 - (rows - 1) * spacing - barHeight - 3 + offsetY,
        14,
        Math.max(14, guiHeight - barHeight - 24));
  }
}
