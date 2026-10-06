package dev.eldencraft.bridge;

public final class FlightConformance {
  private static int checks;

  private static void check(boolean v, String text) {
    checks++;
    if (!v) throw new AssertionError(text);
  }

  public static void main(String[] args) {
    var edges = new FlightPolicy.Edges();
    edges.baseline(4);
    check(!edges.take(4, true), "held input at reacquisition cannot start a glide");
    check(!edges.take(5, false), "grounded press is consumed without buffering");
    check(!edges.take(5, true), "grounded jump cannot later auto-start glide");
    check(edges.take(6, true), "fresh airborne press admits vanilla eligibility check");
    check(!edges.take(6, true), "one press cannot start twice");
    check(!edges.take(1, true), "input counter rollback rebaselines");
    check(edges.take(2, true), "new press after baseline is accepted");
    check(FlightPolicy.fresh(100, 150_000_100), "exact lease boundary is valid");
    check(!FlightPolicy.fresh(100, 150_000_101), "stale velocity cannot be renewed by a read");
    check(!FlightPolicy.fresh(100, 99), "future timestamp rejected");
    check(!FlightPolicy.fresh(0, 100), "unpublished input rejected");
    check(FlightPolicy.velocity(30, -20, 40), "ordinary glide vector accepted");
    check(FlightPolicy.velocity(120, 0, 0), "speed cap inclusive");
    check(!FlightPolicy.velocity(120, 1, 0), "combined magnitude bounded, not each axis alone");
    for (double value :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 121, -121})
      check(!FlightPolicy.velocity(value, 0, 0), "malformed/overbudget velocity rejected");
    var collision = new FlightPolicy.CollisionFeedback();
    var requested = new double[] {1, -.2, 2};
    check(
        collision.reconcile(requested, new double[] {0, -.2, 2})[0] == 1,
        "one delayed sample does not erase momentum");
    check(
        collision.reconcile(requested, new double[] {0, -.2, 2})[0] == 1,
        "two delayed samples do not erase momentum");
    var clipped = collision.reconcile(requested, new double[] {0, -.2, 2});
    check(
        clipped[0] == 0 && clipped[1] == -.2 && clipped[2] == 2,
        "blocked axis stops while actual free axes survive");
    check(
        collision.reconcile(requested, new double[] {.5, -.2, 2})[0] == 1,
        "advancing native movement clears blocked history");
    collision.reset();
    check(
        collision.reconcile(requested, new double[] {0, 0, 0})[0] == 1,
        "new context starts without stale collision");
    double x = .001, y = .05, z = .001;
    for (int age = 0; age < 32; age++) {
      check(
          FlightPolicy.rocketStep(age, x, y, z, false),
          "normal duration-one rocket remains vanilla through its maximum lifetime");
      x *= 1.15;
      z *= 1.15;
      y += .04;
    }
    int age = 32;
    while (FlightPolicy.rocketStep(age, x, y, z, false)) {
      x *= 1.15;
      z *= 1.15;
      y += .04;
      age++;
      if (age > 200) throw new AssertionError("unbounded rocket simulation");
    }
    check(age < 100, "paused-server client acceleration is stopped before huge broadphase extent");
    check(
        FlightPolicy.rocketStep(199, 1, 1, 1, true),
        "ordinary attached boost uses its genuine velocity");
    check(
        !FlightPolicy.rocketStep(200, 0, 0, 0, true),
        "orphaned attached rocket has a bounded lifetime too");
    check(
        !FlightPolicy.rocketStep(1, Double.NaN, 0, 0, false),
        "invalid rocket stops before ray or collision traversal");
    check(
        !FlightPolicy.rocketStep(1, 6, 0, 0, false),
        "prospective vanilla acceleration is bounded before it occurs");
    check(
        FlightPolicy.rocketStep(1, 6, 0, 0, true),
        "attached branch is not spuriously multiplied by unattached acceleration");
    System.out.println(
        "Flight conformance: "
            + checks
            + " checks passed (lease/edge/bounds; physics remains vanilla).");
  }
}
