package dev.eldencraft.bridge;

import java.util.OptionalInt;
import java.util.OptionalLong;

/** Pure clock-boundary checks; does not launch or mutate either game. */
public final class HostClockConformance {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) throw new AssertionError("Clock check " + checks);
  }

  public static void main(String[] args) {
    int[][] anchors = {{0, 18000}, {3, 21000}, {6, 0}, {12, 6000}, {18, 12000}, {23, 17000}};
    for (int[] anchor : anchors) {
      check(HostClock.dayTicks(anchor[0] * 3600.0).equals(OptionalInt.of(anchor[1])));
    }
    check(HostClock.dayTicks(Math.nextDown(21_600.0)).equals(OptionalInt.of(23_999)));
    check(HostClock.dayTicks(21_600.0).equals(OptionalInt.of(0)));
    check(HostClock.dayTicks(Math.nextDown(86_400.0)).equals(OptionalInt.of(17_999)));
    for (double invalid :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 86_400.0, 1e20
        }) {
      check(HostClock.dayTicks(invalid).isEmpty());
      check(HostClock.synchronizeTicks(123_456, invalid).isEmpty());
    }
    for (int second = 0; second < 86_400; second++) {
      int actual = HostClock.dayTicks(second).orElseThrow();
      int expected = (int) (((long) second + 64_800) % 86_400 * 24_000 / 86_400);
      if (actual != expected || actual < 0 || actual >= 24_000)
        throw new AssertionError("Clock phase mismatch at second " + second);
    }
    check(true); // Exhaustive integer-second reference comparison.
    check(HostClock.synchronizeTicks(0, 0).equals(OptionalLong.of(18_000)));
    check(HostClock.synchronizeTicks(123_456, 43_200).equals(OptionalLong.of(126_000)));
    check(HostClock.synchronizeTicks(23_999, 21_600).equals(OptionalLong.of(0)));
    check(HostClock.synchronizeTicks(24_000, 0).equals(OptionalLong.of(42_000)));
    check(HostClock.synchronizeTicks(-1, 0).isEmpty());
    long lastDayStart = Long.MAX_VALUE - Long.MAX_VALUE % HostClock.TICKS_PER_DAY;
    check(HostClock.synchronizeTicks(Long.MAX_VALUE, 21_600).equals(OptionalLong.of(lastDayStart)));
    check(HostClock.synchronizeTicks(Long.MAX_VALUE, Math.nextDown(21_600.0)).isEmpty());
    System.out.println(
        "Host clock conformance: " + checks + " checks passed (pure conversion only).");
  }
}
