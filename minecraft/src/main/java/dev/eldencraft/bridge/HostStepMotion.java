package dev.eldencraft.bridge;

/** Fresh, continuous host displacement for vanilla's sound-only movement emission. */
public final class HostStepMotion {
  public record Context(long pid, long map, long epoch, long anchor) {}

  public record Sample(
      Context context, long frame, long millis, WorldOrigin.Vec feet, boolean grounded) {}

  private Sample prior;

  public WorldOrigin.Vec update(Sample current) {
    if (current == null) {
      clear();
      return null;
    }
    var p = prior;
    if (p != null && p.context.equals(current.context) && current.frame == p.frame) return null;
    prior = current;
    if (p == null
        || !p.context.equals(current.context)
        || current.frame <= p.frame
        || current.millis <= p.millis
        || current.millis - p.millis > 250
        || !current.grounded
        || !p.grounded) return null;
    double x = current.feet.x() - p.feet.x(),
        y = current.feet.y() - p.feet.y(),
        z = current.feet.z() - p.feet.z();
    double distance = Math.sqrt(x * x + z * z),
        limit = 16 * (current.millis - p.millis) / 1000.0 + .15;
    if (!Double.isFinite(distance) || !Double.isFinite(y) || distance > limit || Math.abs(y) > 1)
      return null;
    return distance > .0001 ? new WorldOrigin.Vec(x, 0, z) : null;
  }

  public void clear() {
    prior = null;
  }
}
