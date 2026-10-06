package dev.eldencraft.bridge;

public final class CombatEventConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Combat check " + checks);
  }

  public static void main(String[] args) {
    var state = new CombatEventState();
    check(!state.complete(true));
    for (float charge : new float[] {0, .9f, .999f, Float.NaN, Float.POSITIVE_INFINITY, -1, 2}) {
      state.begin(true, true, charge);
      check(!state.complete(true) && state.sequence() == 0);
    }
    state.begin(false, true, 1);
    check(!state.complete(true));
    state.begin(true, false, 1);
    check(!state.complete(true));
    state.begin(true, true, 1);
    check(!state.complete(false) && state.sequence() == 0);
    state.begin(true, true, 1);
    check(state.complete(true) && state.sequence() == 1 && state.charge() == 1);
    check(!state.complete(true) && state.sequence() == 1);
    state.begin(true, true, 1);
    state.reset();
    check(!state.complete(true) && state.sequence() == 0 && state.charge() == 0);
    state.begin(true, true, 1);
    state.begin(true, false, 1);
    check(!state.complete(true));
    System.out.println(
        "Combat event conformance: " + checks + " checks passed (pure acceptance model only).");
  }
}
