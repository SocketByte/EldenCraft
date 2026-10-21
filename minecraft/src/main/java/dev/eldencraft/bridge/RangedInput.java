package dev.eldencraft.bridge;

import java.util.UUID;

/** Immutable authorization for vanilla item-use methods running on the integrated server. */
public record RangedInput(
    long pid, long map, UUID player, long nanos, float yaw, float pitch, boolean unblocked) {
  /** How fresh host input must be to start a draw, throw or shot. */
  public static final long START_NANOS = 250_000_000L;

  /** A draw already under way survives a host hitch this long before it is cancelled. */
  public static final long HELD_NANOS = 1_000_000_000L;

  public boolean permits(long currentPid, long currentMap, UUID currentPlayer, long now) {
    return permits(currentPid, currentMap, currentPlayer, now, START_NANOS);
  }

  public boolean permits(
      long currentPid, long currentMap, UUID currentPlayer, long now, long maxAgeNanos) {
    return unblocked
        && pid > 0
        && pid == currentPid
        && map == currentMap
        && player != null
        && player.equals(currentPlayer)
        && now >= nanos
        && now - nanos <= maxAgeNanos
        && Float.isFinite(yaw)
        && Float.isFinite(pitch)
        && pitch >= -90
        && pitch <= 90;
  }
}
