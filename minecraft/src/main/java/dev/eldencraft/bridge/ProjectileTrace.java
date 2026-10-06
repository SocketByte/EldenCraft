package dev.eldencraft.bridge;

import java.util.*;

/** Bounded actual flight samples. Never simplify a curve across potentially solid terrain. */
public final class ProjectileTrace {
  public static final int MAX_POINTS = 128;
  public static final long MAX_FLIGHT_MILLIS = 6000;

  public record Context(long pid, long session, long epoch, long map, long anchor, UUID owner) {}

  private final Context context;
  private final long launched;
  private final ArrayList<WorldOrigin.Vec> points = new ArrayList<>();
  private double length;
  private boolean invalid;

  public ProjectileTrace(Context context, long launched, WorldOrigin.Vec first) {
    this.context = Objects.requireNonNull(context);
    this.launched = launched;
    if (context.pid <= 0
        || context.session <= 0
        || context.epoch <= 0
        || context.anchor <= 0
        || context.owner == null
        || launched < 0
        || first == null) throw new IllegalArgumentException();
    points.add(first);
  }

  public boolean valid(Context current, long now) {
    return !invalid
        && context.equals(current)
        && now >= launched
        && now - launched <= MAX_FLIGHT_MILLIS;
  }

  public boolean append(Context current, long now, WorldOrigin.Vec point) {
    if (!valid(current, now) || point == null) {
      invalid = true;
      return false;
    }
    double distance = distance(points.getLast(), point);
    if (distance < 1e-7) return true;
    if (points.size() >= MAX_POINTS
        || distance > 8
        || distance(points.getFirst(), point) > 64
        || length + distance > 256) {
      invalid = true;
      return false;
    }
    points.add(point);
    length += distance;
    return true;
  }

  public List<WorldOrigin.Vec> snapshot(Context current, long now) {
    return valid(current, now) && points.size() >= 2 ? List.copyOf(points) : List.of();
  }

  public List<WorldOrigin.Vec> impact(Context current, long now, WorldOrigin.Vec point) {
    if (!valid(current, now) || point == null) return List.of();
    var result = new ArrayList<>(points);
    double distance = distance(result.getLast(), point);
    if (distance >= 1e-7) {
      if (result.size() >= MAX_POINTS
          || distance > 8
          || distance(result.getFirst(), point) > 64
          || length + distance > 256) return List.of();
      result.add(point);
    }
    return result.size() >= 2 ? List.copyOf(result) : List.of();
  }

  /** Native contact must lie on an actually exported segment, never an arbitrary destination. */
  public List<WorldOrigin.Vec> contact(
      Context current, long now, int segment, WorldOrigin.Vec point) {
    if (!valid(current, now) || segment < 1 || segment >= points.size() || point == null)
      return List.of();
    var a = points.get(segment - 1);
    var b = points.get(segment);
    double dx = b.x() - a.x(),
        dy = b.y() - a.y(),
        dz = b.z() - a.z(),
        squared = dx * dx + dy * dy + dz * dz;
    if (squared < 1e-14) return List.of();
    double fraction =
        ((point.x() - a.x()) * dx + (point.y() - a.y()) * dy + (point.z() - a.z()) * dz) / squared;
    if (fraction < 0
        || fraction > 1
        || distance(
                new WorldOrigin.Vec(
                    a.x() + fraction * dx, a.y() + fraction * dy, a.z() + fraction * dz),
                point)
            > .025) return List.of();
    var result = new ArrayList<>(points.subList(0, segment));
    result.add(point);
    return List.copyOf(result);
  }

  public static double distance(WorldOrigin.Vec a, WorldOrigin.Vec b) {
    return Math.sqrt(
        Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
  }
}
