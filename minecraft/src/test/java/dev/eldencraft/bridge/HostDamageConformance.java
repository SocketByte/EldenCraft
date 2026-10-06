package dev.eldencraft.bridge;

/** Pure lifecycle tests: these never launch a game, alter HP or play sound. */
public final class HostDamageConformance {
  private static int checks;
  private static final Object PLAYER = new Object(), WORLD = new Object();

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Host damage check " + checks);
  }

  private static int sample(HostDamageState state, long frame, long millis, int hp) {
    return state.observe(PLAYER, WORLD, 7, 42, frame, millis, hp, 100);
  }

  private static HostDamageState baseline() {
    var state = new HostDamageState();
    check(sample(state, 1, 1000, 100) == 0);
    return state;
  }

  public static void main(String[] args) {
    var state = baseline();
    check(sample(state, 2, 1050, 90) == 10);
    check(sample(state, 2, 1050, 90) == 0); // Duplicate frame cannot repeat a sound.
    check(sample(state, 3, 1100, 90) == 0);
    check(sample(state, 4, 1150, 95) == 0); // Healing rebases; it is not damage.
    check(sample(state, 5, 1200, 80) == 15);
    check(sample(state, 6, 1250, 0) == 80); // Visual hit only, never a guest death request.
    check(sample(state, 7, 1300, 100) == 0);
    state.reset();
    check(sample(state, 8, 1350, 50) == 0); // Focus/staleness/disconnect/dead guest gate.
    check(sample(state, 9, 1400, 40) == 10);
    check(
        sample(state, 10, 1650, 20) == 0); // A250ms gap is a new baseline, never accumulated hurt.
    check(sample(state, 11, 1699, 10) == 10);
    check(sample(state, 9, 1700, 5) == 0); // Frame rollback resets the baseline.
    check(sample(state, 10, 1750, 1) == 0);
    check(sample(state, 11, 1749, 0) == 0); // Clock rollback cannot create an event.
    check(sample(state, 12, 1800, 20) == 0);
    check(
        sample(state, 12, 1800, 1) == 0); // Inconsistent duplicate rejected; next frame baselines.
    check(sample(state, 13, 1850, 0) == 0);

    state = baseline();
    check(state.observe(new Object(), WORLD, 7, 42, 2, 1050, 80, 100) == 0);
    check(state.observe(PLAYER, new Object(), 7, 42, 3, 1100, 60, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 42, 4, 1150, 40, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 5, 1200, 20, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 6, 1250, 10, 200) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 7, 1300, 5, 200) == 5);
    check(state.observe(PLAYER, WORLD, 8, 43, 8, 1350, 0, 100) == 0);
    check(state.observe(null, WORLD, 8, 43, 9, 1400, 0, 100) == 0);
    check(state.observe(PLAYER, null, 8, 43, 9, 1400, 0, 100) == 0);
    check(state.observe(PLAYER, WORLD, 0, 43, 9, 1400, 0, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 0, 1400, 0, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 9, -1, 0, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 9, 1400, -1, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 9, 1400, 101, 100) == 0);
    check(state.observe(PLAYER, WORLD, 8, 43, 9, 1400, 0, 0) == 0);
    check(sample(state, 20, 2000, 1) == 0);
    check(sample(state, 21, 2249, 0) == 1); // Just inside the continuity bound.
    System.out.println(
        "Host damage conformance: " + checks + " checks passed (pure feedback policy only).");
  }
}
