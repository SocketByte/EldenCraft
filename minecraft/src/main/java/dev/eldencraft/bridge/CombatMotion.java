package dev.eldencraft.bridge;

/**
 * Fresh, continuous host displacement; teleports, gaps and context changes never earn smash damage.
 */
public final class CombatMotion {
  private Object context;
  private long frame, time;
  private WorldOrigin.Vec feet;
  private WorldOrigin.Vec velocity = new WorldOrigin.Vec(0, 0, 0);
  private double fall;

  public void observe(
      Object next,
      long nextFrame,
      long millis,
      WorldOrigin.Vec at,
      boolean grounded,
      boolean falling) {
    if (!next.equals(context)
        || feet == null
        || nextFrame <= frame
        || millis <= time
        || millis - time > 250) {
      if (next.equals(context) && nextFrame == frame) return;
      reset(next, nextFrame, millis, at);
      return;
    }
    double dx = at.x() - feet.x(), dy = at.y() - feet.y(), dz = at.z() - feet.z();
    double dt = (millis - time) / 50.0;
    if (!Double.isFinite(dx + dy + dz) || dx * dx + dy * dy + dz * dz > 36) {
      reset(next, nextFrame, millis, at);
      return;
    }
    velocity = new WorldOrigin.Vec(dx / dt, dy / dt, dz / dt);
    if (grounded || !falling || dy > .002) fall = 0;
    else if (dy < 0) fall = Math.min(128, fall - dy);
    feet = at;
    frame = nextFrame;
    time = millis;
  }

  private void reset(Object next, long nextFrame, long millis, WorldOrigin.Vec at) {
    context = next;
    frame = nextFrame;
    time = millis;
    feet = at;
    velocity = new WorldOrigin.Vec(0, 0, 0);
    fall = 0;
  }

  public WorldOrigin.Vec velocity() {
    return velocity;
  }

  public double fallDistance() {
    return fall;
  }

  public void consumeFall() {
    fall = 0;
  }
}
