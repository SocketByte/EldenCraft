package dev.eldencraft.bridge;

import dev.eldencraft.bridge.HostMiningInput.Input;

public final class HostMiningConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  private static boolean allow(Input applied, Input observed, int blocked) {
    return HostMiningInput.mayContinue(true, false, applied, observed, blocked);
  }

  public static void main(String[] args) {
    var held = new Input(41, 7, 100, 1);
    var laterHeld = new Input(41, 7, 101, 1);
    check(allow(held, held, 0), "one fresh publication can sustain the held action");
    check(allow(held, laterHeld, 0), "advancing held publication continues mining");
    check(
        !allow(held, null, 0),
        "stale, inactive or dead publisher revokes permission before the next input tick");
    check(
        !allow(held, new Input(41, 7, 101, 0), 0),
        "fresh release cancels even while applied key is still down");
    check(
        !allow(new Input(41, 7, 101, 0), laterHeld, 0),
        "fresh press cannot precede key ownership/application");
    check(
        !allow(held, new Input(42, 7, 101, 1), 0), "publisher replacement cannot inherit old hold");
    check(!allow(held, new Input(41, 8, 101, 1), 0), "map replacement cannot inherit old hold");
    check(!allow(held, new Input(41, 7, 99, 1), 0), "sequence rollback cannot reuse applied hold");
    check(!allow(null, laterHeld, 0), "no applied owner cannot mine");
    check(!allow(new Input(41, 7, -1, 1), laterHeld, 0), "released owner sentinel cannot mine");
    check(
        !HostMiningInput.mayContinue(false, false, held, laterHeld, 0),
        "ordinary unowned key does not bypass mouse grab");
    check(
        !HostMiningInput.mayContinue(true, true, held, laterHeld, 0),
        "GUI prevents override despite a held attack");
    check(!allow(held, laterHeld, 1), "hold present during reacquisition remains blocked");
    check(
        allow(held, laterHeld, 2),
        "unrelated blocked use button does not block an acquired attack");
    check(
        !allow(new Input(41, 7, 102, 0), new Input(41, 7, 102, 0), 0),
        "reacquired hold must first release");
    check(
        allow(new Input(41, 7, 103, 1), new Input(41, 7, 103, 1), 0),
        "new applied press after release is eligible");
    check(
        !allow(new Input(41, 7, 104, 2), new Input(41, 7, 104, 2), 0),
        "use alone never grants held mining");
    check(!allow(new Input(0, 7, 1, 1), new Input(0, 7, 1, 1), 0), "unbound publisher is rejected");
    check(
        HostMiningInput.lostContext(held, null),
        "strict freshness loss requests reacquisition even during broader camera lease");
    check(
        HostMiningInput.lostContext(held, new Input(42, 7, 101, 1)),
        "publisher loss re-arms held-button blocking");
    check(
        HostMiningInput.lostContext(held, new Input(41, 8, 101, 1)),
        "map loss re-arms held-button blocking");
    check(
        HostMiningInput.lostContext(held, new Input(41, 7, 99, 1)),
        "rollback requests reacquisition");
    check(
        !HostMiningInput.lostContext(held, new Input(41, 7, 101, 0)),
        "ordinary release does not lose the host context");
    check(
        !HostMiningInput.lostContext(held, laterHeld),
        "advancing held input retains the host context");
    System.out.println(
        "Host mining conformance: " + checks + " checks passed (ownership policy only).");
  }
}
