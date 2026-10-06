package dev.eldencraft.bridge;

/** Render-rate equivalent of vanilla's half-per-tick hand-angle lag, with wrapped yaw. */
public final class HostHandMotion {
  public record Pose(float yaw, float pitch, float bobYaw, float bobPitch) {}

  private long time;
  private double yaw, pitch, lagYaw, lagPitch;
  private boolean attached;

  public void reset() {
    attached = false;
    time = 0;
    lagYaw = lagPitch = 0;
  }

  public Pose update(long now, float nextYaw, float nextPitch) {
    if (now < 0 || !Float.isFinite(nextYaw) || !Float.isFinite(nextPitch)) {
      reset();
      return null;
    }
    if (!attached || now < time || now - time > 250_000_000L) {
      attached = true;
      time = now;
      yaw = nextYaw;
      pitch = nextPitch;
      lagYaw = lagPitch = 0;
    } else if (now > time) {
      double dt = (now - time) / 1_000_000_000.0, k = Math.log(2) / .05, decay = Math.exp(-k * dt);
      double scale = -Math.expm1(-k * dt) / (k * dt);
      lagYaw = lagYaw * decay + wrapped(nextYaw - yaw) * scale;
      lagPitch = lagPitch * decay + (nextPitch - pitch) * scale;
      time = now;
      yaw = nextYaw;
      pitch = nextPitch;
    }
    return new Pose(nextYaw, nextPitch, (float) (nextYaw - lagYaw), (float) (nextPitch - lagPitch));
  }

  public static double wrapped(double degrees) {
    return degrees - 360 * Math.floor((degrees + 180) / 360);
  }
}
