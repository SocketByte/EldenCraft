package dev.eldencraft.bridge;

/** Pure lifecycle checks. No game health or live shared memory is modified. */
public final class HealthDisplayConformance {
  private static int checks;

  private static void equal(Float actual, Float expected) {
    checks++;
    if (!java.util.Objects.equals(actual, expected))
      throw new AssertionError("Health check " + checks + ": " + actual + " != " + expected);
  }

  public static void main(String[] args) {
    HealthDisplayState state = new HealthDisplayState();
    Object player = new Object(), world = new Object();
    state.bind(player, world);
    equal(
        state.begin(player, world, 20, 20, true, true, 50, 100),
        null); // Join waits for genuine health.
    state.packet(player, world, 20);
    equal(state.begin(player, world, 20, 20, true, true, 50, 100), 10f);
    equal(state.end(player, world, 10), 20f);
    equal(state.begin(player, world, 20, 20, true, false, 50, 100), null); // Host/focus loss.
    state.packet(player, world, 14);
    equal(state.begin(player, world, 14, 20, true, true, 10, 100), 2f);
    equal(state.end(player, world, 2), 14f);
    equal(state.begin(player, world, 14, 20, true, true, 60, 100), 12f);
    state.packet(
        player, world, 12); // New packet equals the display; restore must not resurrect old14.
    equal(state.end(player, world, 12), 12f);
    equal(state.begin(player, world, 12, 20, true, true, 50, 100), 10f);
    equal(state.end(player, world, 7), null); // A newer vanilla value always wins.
    equal(state.begin(player, world, 9, 20, true, true, 50, 100), 10f);
    equal(
        state.end(player, world, 10),
        9f); // Preserve actual pre-scope value even if packet metadata is older.
    Object replacement = new Object();
    state.bind(replacement, world);
    state.packet(player, world, 20); // Delayed old-player packet cannot arm the replacement.
    equal(state.begin(replacement, world, 20, 20, true, true, 50, 100), null);
    state.packet(replacement, world, 16);
    equal(state.begin(replacement, world, 16, 20, true, true, 25, 100), 5f);
    equal(state.end(replacement, world, 5), 16f);
    Object newWorld = new Object();
    state.bind(replacement, newWorld);
    state.packet(replacement, world, 20);
    equal(state.begin(replacement, newWorld, 16, 20, true, true, 50, 100), null);
    state.packet(replacement, newWorld, 0);
    equal(state.begin(replacement, newWorld, 0, 20, false, true, 100, 100), null);
    equal(
        state.begin(replacement, newWorld, 20, 20, true, true, 100, 100),
        null); // Death packet cannot be healed by a host value.
    state.packet(replacement, newWorld, 20);
    equal(state.begin(replacement, newWorld, 0, 20, false, true, 100, 100), null);
    equal(
        state.begin(replacement, newWorld, 20, 20, false, true, 100, 100),
        null); // Respawn/death flags still gate.
    equal(state.begin(replacement, newWorld, 20, Float.NaN, true, true, 50, 100), null);
    equal(state.begin(replacement, newWorld, 20, 20, true, true, 50, 0), null);
    equal(state.begin(replacement, newWorld, 20, 20, true, true, -1, 100), null);
    equal(state.begin(replacement, newWorld, 20, 20, true, true, 101, 100), null);
    equal(state.begin(replacement, newWorld, 20, 20, true, true, 0, 100), 0f);
    equal(state.end(replacement, newWorld, 0), 20f); // Zero is visible only within the HUD scope.
    equal(state.begin(replacement, newWorld, 20, 20, true, true, 25, 100), 5f);
    state.bind(player, world);
    equal(
        state.end(player, world, 20),
        null); // Never restore stale health into a replacement identity.
    state.packet(player, world, 40);
    equal(state.begin(player, world, 40, 40, true, true, 25, 100), 10f);
    equal(state.end(player, world, 10), 40f);
    state.reset();
    equal(state.begin(player, world, 20, 20, true, true, 50, 100), null);
    compactHearts();
    System.out.println(
        "Health display conformance: " + checks + " checks passed (pure lifecycle model only).");
  }

  private static void compactHearts() {
    var progression = CampaignConfig.current().progression;
    double start = progression.health(0), end = progression.health(progression.shares());
    equal((float) CampaignHeartScale.maximum(start, start, end), 20f); // Ten full hearts.
    equal((float) CampaignHeartScale.maximum(end, start, end), 60f); // Three full rows.
    equal((float) CampaignHeartScale.maximum(end * 2, start, end), 60f);
    double previous = 0;
    for (int victories = 0; victories <= progression.shares(); victories++) {
      double capacity = progression.health(victories);
      double maximum = CampaignHeartScale.maximum(capacity, start, end);
      if (maximum < previous || maximum < 20 || maximum > 60)
        throw new AssertionError("Heart capacity must grow within one to three rows");
      previous = maximum;
      for (double fraction : new double[] {0, .1, .5, 1}) {
        equal(
            CampaignHeartScale.units(capacity * fraction, capacity, maximum),
            (float) (maximum * fraction));
      }
      // Identical damage/healing/absorption shares, even as the display unit changes.
      equal(CampaignHeartScale.units(8, capacity, maximum), (float) (8 * maximum / capacity));
    }
    equal(CampaignHeartScale.units(Double.NaN, start, 20), 0f);
    equal(CampaignHeartScale.units(10, 0, 20), 0f);
  }
}
