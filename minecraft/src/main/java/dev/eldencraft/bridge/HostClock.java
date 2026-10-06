package dev.eldencraft.bridge;

import java.util.OptionalInt;
import java.util.OptionalLong;

/** Pure game-clock conversion; never reads OS time or changes a server itself. */
public final class HostClock {
  public static final long TICKS_PER_DAY = 24_000L;
  public static final double SECONDS_PER_DAY = 86_400.0;

  private HostClock() {}

  /** Minecraft day phase starts at 06:00: noon=6000, midnight=18000. */
  public static OptionalInt dayTicks(double secondsSinceMidnight) {
    if (!Double.isFinite(secondsSinceMidnight)
        || secondsSinceMidnight < 0.0
        || secondsSinceMidnight >= SECONDS_PER_DAY) {
      return OptionalInt.empty();
    }
    double phaseSeconds = secondsSinceMidnight - 21_600.0;
    if (phaseSeconds < 0.0) phaseSeconds += SECONDS_PER_DAY;
    // Clamp only rounding at the upper floating-point boundary; never wrap
    // a pre-dawn sample to the beginning of the next Minecraft day.
    int phase = (int) Math.floor(phaseSeconds * TICKS_PER_DAY / SECONDS_PER_DAY);
    return OptionalInt.of(Math.min(phase, (int) TICKS_PER_DAY - 1));
  }

  /**
   * Replace only the within-day phase of a nonnegative Minecraft clock. The caller must run on the
   * integrated server thread after freshness, offline/world identity and host TIME_VALID checks.
   * Normal vanilla time progression owns the day count; this helper does not infer a rollover.
   */
  public static OptionalLong synchronizeTicks(long currentTicks, double secondsSinceMidnight) {
    if (currentTicks < 0) return OptionalLong.empty();
    OptionalInt phase = dayTicks(secondsSinceMidnight);
    if (phase.isEmpty()) return OptionalLong.empty();
    long dayStart = currentTicks - currentTicks % TICKS_PER_DAY;
    try {
      return OptionalLong.of(Math.addExact(dayStart, phase.getAsInt()));
    } catch (ArithmeticException overflow) {
      return OptionalLong.empty();
    }
  }
}
