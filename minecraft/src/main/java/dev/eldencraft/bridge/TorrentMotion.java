package dev.eldencraft.bridge;

/**
 * Render-only Torrent heading and gait, advanced once per host sample. Torrent faces where it
 * travels, not where the rider looks; standing still keeps the last heading.
 */
public final class TorrentMotion {
  public record Pose(float yaw, float walkPhase, float walkSpeed) {}

  /** Slower than this (m/s) is standing: the heading holds instead of following jitter. */
  static final float TURN_SPEED = .5f;

  private long frame = -1, timestamp;
  private double x, z;
  private float yaw, phase, speed;
  private Pose pose;

  public Pose update(
      long nextFrame,
      long millis,
      double nextX,
      double nextZ,
      float lookYaw,
      float metresPerSecond,
      boolean grounded) {
    if (pose != null && nextFrame == frame) return pose;
    boolean reset =
        pose == null || nextFrame < frame || millis < timestamp || millis - timestamp > 250;
    float seconds = reset ? 0 : Math.clamp((millis - timestamp) / 1000f, 0, .1f);
    if (reset) {
      yaw = wrap(lookYaw);
      phase = 0;
      speed = 0;
    } else if (seconds > 0) {
      double dx = nextX - x, dz = nextZ - z;
      if (Math.hypot(dx, dz) / seconds >= TURN_SPEED) {
        float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
        yaw = wrap(yaw + wrap(heading - yaw) * (1 - (float) Math.exp(-10 * seconds)));
      }
    }
    // Vanilla horse legs: full stride from 5 m/s, converging 40% per 20 Hz tick.
    float target = grounded ? Math.clamp(metresPerSecond * .2f, 0, 1) : 0;
    speed += (target - speed) * (1 - (float) Math.pow(.6, seconds * 20));
    phase += speed * seconds * 20;
    x = nextX;
    z = nextZ;
    frame = nextFrame;
    timestamp = millis;
    pose = new Pose(yaw, phase, speed);
    return pose;
  }

  public void reset() {
    frame = -1;
    timestamp = 0;
    pose = null;
  }

  static float wrap(float angle) {
    return (float) (angle - Math.floor((angle + 180) / 360) * 360);
  }
}
