package dev.eldencraft.bridge;

/** Render-only vanilla-scale walking and body turning, advanced once per host sample. */
public final class HostAvatarMotion {
  public record Pose(float bodyYaw, float headYaw, float pitch, float walkPhase, float walkSpeed) {}

  private long frame = -1, timestamp;
  private float bodyYaw, phase, speed;
  private Pose pose;

  public Pose update(
      long nextFrame,
      long millis,
      float yaw,
      float pitch,
      float metresPerSecond,
      boolean grounded) {
    if (pose != null && nextFrame == frame) return pose;
    boolean reset =
        pose == null || nextFrame < frame || millis < timestamp || millis - timestamp > 250;
    float seconds = reset ? 0 : Math.clamp((millis - timestamp) / 1000f, 0, .1f);
    if (reset) {
      bodyYaw = yaw;
      phase = 0;
      speed = 0;
    }
    float targetSpeed = grounded ? Math.clamp(metresPerSecond * .2f, 0, 1) : 0;
    // Vanilla walking converges by 40% each 20 Hz tick. Keep it stable at any render rate.
    speed += (targetSpeed - speed) * (1 - (float) Math.pow(.6, seconds * 20));
    phase += speed * seconds * 20;
    float turn = wrap(yaw - bodyYaw);
    bodyYaw += turn * (1 - (float) Math.exp(-12 * seconds));
    bodyYaw = yaw - Math.clamp(wrap(yaw - bodyYaw), -60, 60);
    pose = new Pose(wrap(bodyYaw), wrap(yaw - bodyYaw), Math.clamp(pitch, -90, 90), phase, speed);
    frame = nextFrame;
    timestamp = millis;
    return pose;
  }

  public void reset() {
    frame = -1;
    timestamp = 0;
    pose = null;
  }

  private static float wrap(float angle) {
    return (float) (angle - Math.floor((angle + 180) / 360) * 360);
  }
}
