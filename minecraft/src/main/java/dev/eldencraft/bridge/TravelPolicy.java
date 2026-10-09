package dev.eldencraft.bridge;

/** Input and bounds for vanilla creative/fluid/climb travel; Minecraft supplies the equations. */
public final class TravelPolicy {
  private TravelPolicy() {}

  public static boolean velocity(double x, double y, double z) {
    return FlightPolicy.velocity(x, y, z) && x * x + y * y + z * z <= 30 * 30;
  }

  public static double[] horizontal(double x, double z, float yaw) {
    double angle = Math.toRadians(yaw);
    return new double[] {
      x * Math.cos(angle) - z * Math.sin(angle), z * Math.cos(angle) + x * Math.sin(angle)
    };
  }

  public static double[] input(
      boolean forward,
      boolean back,
      boolean left,
      boolean right,
      boolean sneak,
      boolean usingItem) {
    double x = (left ? 1 : 0) - (right ? 1 : 0);
    double z = (forward ? 1 : 0) - (back ? 1 : 0);
    double length = Math.hypot(x, z);
    if (length == 0) return new double[] {0, 0};
    // Supplied after serverAiStep, where vanilla has already scaled the input.
    double scale = .98 * (sneak ? .3 : 1) * (usingItem ? .2 : 1);
    // LocalPlayer stretches input toward the unit square before travel clamps
    // its length. Sneaking diagonally keeps the intended speed on both axes.
    double stretched = Math.min(1, scale * length);
    return new double[] {x / length * stretched, z / length * stretched};
  }
}
