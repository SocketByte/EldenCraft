package dev.eldencraft.bridge;

/** Bounds and edge handling only: Minecraft itself owns the travel equations. */
public final class FlightPolicy {
  public static final long FRESH_NANOS = 150_000_000L;
  public static final double MAX_SPEED = 120;
  public static final long CREATIVE_TOGGLE_NANOS = 350_000_000L;

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

  /** Vanilla's seven-tick double jump window, without buffering held or stale input. */
  public static final class CreativeToggle {
    private long consumed;
    private long firstPress;

    public boolean take(long presses, long nanos, boolean mayFly) {
      if (presses <= consumed || !mayFly) {
        if (presses < consumed || !mayFly) firstPress = 0;
        consumed = presses;
        return false;
      }
      consumed = presses;
      if (firstPress > 0 && nanos >= firstPress && nanos - firstPress <= CREATIVE_TOGGLE_NANOS) {
        firstPress = 0;
        return true;
      }
      firstPress = nanos;
      return false;
    }

    public void baseline(long presses) {
      consumed = presses;
      firstPress = 0;
    }
  }

  /** LocalPlayer adds this impulse before Player.travel applies vertical flight drag. */
  public static double creativeLift(boolean jump, boolean sneak, float flyingSpeed) {
    return ((jump ? 1 : 0) - (sneak ? 1 : 0)) * flyingSpeed * 3;
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
