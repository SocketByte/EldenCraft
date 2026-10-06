package dev.eldencraft.bridge;

/** Bounds and edge handling only: Minecraft itself owns the gliding equation. */
public final class FlightPolicy {
  public static final long FRESH_NANOS = 150_000_000L;
  public static final double MAX_SPEED = 120;

  private FlightPolicy() {}

  public static boolean fresh(long produced, long now) {
    return produced > 0 && now >= produced && now - produced <= FRESH_NANOS;
  }

  public static boolean velocity(double x, double y, double z) {
    return Double.isFinite(x)
        && Double.isFinite(y)
        && Double.isFinite(z)
        && x * x + y * y + z * z <= MAX_SPEED * MAX_SPEED;
  }

  public static boolean rocketStep(int life, double x, double y, double z, boolean attached) {
    if (life < 0 || life >= 200) return false;
    // Unattached vanilla acceleration is applied before its collision query.
    if (!attached) {
      x *= 1.15;
      z *= 1.15;
      y += .04;
    }
    return velocity(x * 20, y * 20, z * 20);
  }

  public static final class Edges {
    private long consumed;

    public boolean take(long presses, boolean eligible) {
      if (presses < consumed) {
        consumed = presses;
        return false;
      }
      boolean next = presses > consumed;
      consumed = presses;
      return next && eligible;
    }

    public void baseline(long presses) {
      consumed = presses;
    }
  }

  /**
   * Native collision owns final travel. Three advancing stationary reports cancel a blocked
   * velocity axis, without treating network latency as a hit.
   */
  public static final class CollisionFeedback {
    private final int[] stopped = new int[3];

    public double[] reconcile(double[] requested, double[] displacement) {
      var result = requested.clone();
      for (int n = 0; n < 3; n++) {
        stopped[n] =
            Math.abs(requested[n]) > .05 && Math.abs(displacement[n]) < .002
                ? Math.min(3, stopped[n] + 1)
                : 0;
        if (stopped[n] >= 3) result[n] = 0;
      }
      return result;
    }

    public void reset() {
      java.util.Arrays.fill(stopped, 0);
    }
  }
}
