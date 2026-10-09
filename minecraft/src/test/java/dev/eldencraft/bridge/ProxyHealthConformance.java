package dev.eldencraft.bridge;

/** Large native health pools stay attackable without compressing vanilla damage receipts. */
public final class ProxyHealthConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    var ordinary = ProxyHealth.fromNative(360, 720, 36, 0);
    check(ordinary.maximum() == 20 && ordinary.remaining() == 10, "ordinary enemy HP scale");
    check(
        ProxyHealth.fromNative(360, 720, 36, 4).remaining() == 6,
        "ordinary pending damage is retained");
    var giant = ProxyHealth.fromNative(42363, 42363, 36, 0);
    check(giant.maximum() == 1024 && giant.remaining() == 1024, "large boss has a live recipient");
    check(
        ProxyHealth.fromNative(42363, 42363, 36, 9).remaining() == 1015,
        "pending sword loss is not refilled by a saturated native HP pool");
    check(
        ProxyHealth.fromNative(42363, 42363, 36, 9 + 7).remaining() == 1008,
        "melee and ranged pending losses share the same bounded recipient");
    float vanillaLoss = giant.remaining() - ProxyHealth.fromNative(42363, 42363, 36, 9).remaining();
    check(vanillaLoss * 36 == 324, "nine vanilla damage still dispatches 324 native HP");
    check(
        ProxyHealth.fromNative(42363 - 324, 42363, 36, 0).remaining() == 1024,
        "acknowledged damage renews the invisible recipient while native HP stays authoritative");
    var wounded = ProxyHealth.fromNative(360, 42363, 36, 0);
    check(
        wounded.remaining() == 10 && wounded.maximum() == 1024,
        "wounded boss keeps native HP scale");
    check(
        ProxyHealth.fromNative(360, 42363, 36, 9).remaining() == 1,
        "pending loss near death cannot be hidden by the health cap");
    check(
        ProxyHealth.fromNative(360, 42363, 36, 10).remaining() == 0,
        "pending lethal loss does not admit another melee hit");
    check(
        ProxyHealth.fromNative(0, 42363, 36, 0).remaining() == 0, "dead native target stays dead");
    check(
        ProxyHealth.fromNative(18, 18, 36, 0).remaining() == .5f,
        "minimum attribute capacity does not inflate low native HP");
    for (double[] bad :
        new double[][] {
          {-1, 10, 1, 0},
          {11, 10, 1, 0},
          {1, 0, 1, 0},
          {1, 10, 0, 0},
          {1, 10, 1, -1},
          {Double.NaN, 10, 1, 0},
          {1, Double.POSITIVE_INFINITY, 1, 0},
          {1, 10, Double.NaN, 0},
          {1, 10, 1, Double.POSITIVE_INFINITY}
        }) {
      try {
        ProxyHealth.fromNative(bad[0], bad[1], bad[2], bad[3]);
        throw new AssertionError("invalid proxy health accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
    }
    System.out.println("Proxy health conformance: " + checks + " checks passed.");
  }
}
