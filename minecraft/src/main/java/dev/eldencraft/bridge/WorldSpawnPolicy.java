package dev.eldencraft.bridge;

import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Only explicit eggs (and the Nether's own scoped summons) may increase the shared dimension's mob
 * population.
 */
public final class WorldSpawnPolicy {
  private WorldSpawnPolicy() {}

  public static boolean allowed(
      boolean shared, boolean mob, EntitySpawnReason reason, boolean egg, boolean replacement) {
    return allowed(shared, mob, reason, egg, replacement, false);
  }

  public static boolean allowed(
      boolean shared,
      boolean mob,
      EntitySpawnReason reason,
      boolean egg,
      boolean replacement,
      boolean summoned) {
    if (!shared || !mob) return true;
    if (reason == null) return false;
    return switch (reason) {
      case LOAD, DIMENSION_TRAVEL -> true; // Preserve existing entities; this is not a purge.
      case SPAWN_ITEM_USE, DISPENSER, BREEDING -> egg;
      case CONVERSION -> replacement;
      case EVENT -> summoned; // One exact Nether wave mob, never its descendants.
      default -> false;
    };
  }
}
