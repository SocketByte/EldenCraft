package dev.eldencraft.bridge;

import java.util.UUID;

/** Immutable authorization for vanilla item-use methods running on the integrated server. */
public record RangedInput(
    long pid, long map, UUID player, long nanos, float yaw, float pitch, boolean unblocked) {
  public boolean permits(long currentPid, long currentMap, UUID currentPlayer, long now) {
    return unblocked
        && pid > 0
        && pid == currentPid
        && map == currentMap
        && player != null
        && player.equals(currentPlayer)
        && now >= nanos
        && now - nanos <= 250_000_000L
        && Float.isFinite(yaw)
        && Float.isFinite(pitch)
        && pitch >= -90
        && pitch <= 90;
  }
}
