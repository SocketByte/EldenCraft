package dev.eldencraft.bridge;

import java.util.Set;
import net.minecraft.world.entity.EntitySpawnReason;

/** Admission policy against every actual pinned Minecraft spawn reason. No game is started. */
public final class WorldSpawnConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    var lifecycle = Set.of(EntitySpawnReason.LOAD, EntitySpawnReason.DIMENSION_TRAVEL);
    var eggs =
        Set.of(
            EntitySpawnReason.SPAWN_ITEM_USE,
            EntitySpawnReason.DISPENSER,
            EntitySpawnReason.BREEDING);
    for (var reason : EntitySpawnReason.values()) {
      check(
          WorldSpawnPolicy.allowed(false, true, reason, false, false),
          "other dimension unchanged: " + reason);
      check(
          WorldSpawnPolicy.allowed(true, false, reason, false, false),
          "non-mob/player/projectile/proxy unchanged: " + reason);
      check(
          WorldSpawnPolicy.allowed(true, true, reason, false, false) == lifecycle.contains(reason),
          "untrusted fresh mob denied: " + reason);
      check(
          WorldSpawnPolicy.allowed(true, true, reason, true, false)
              == (lifecycle.contains(reason) || eggs.contains(reason)),
          "egg cannot authorize unrelated descendants: " + reason);
      check(
          WorldSpawnPolicy.allowed(true, true, reason, false, true)
              == (lifecycle.contains(reason) || reason == EntitySpawnReason.CONVERSION),
          "replacement cannot authorize new population: " + reason);
    }
    check(
        !WorldSpawnPolicy.allowed(true, true, null, true, true),
        "unknown reason denied even inside an authorized scope");
    check(
        WorldSpawnPolicy.allowed(false, true, null, false, false),
        "unknown reason outside shared world unchanged");
    check(
        WorldSpawnPolicy.allowed(true, false, null, false, false),
        "directly constructed combat proxy is unaffected");
    check(
        !WorldSpawnPolicy.allowed(true, true, EntitySpawnReason.BREEDING, false, false),
        "ordinary breeding stays disabled");
    check(
        !WorldSpawnPolicy.allowed(true, true, EntitySpawnReason.DISPENSER, false, false),
        "dispenser without a real egg is not permission");
    check(
        !WorldSpawnPolicy.allowed(true, true, EntitySpawnReason.CONVERSION, false, false),
        "unverified split/conversion cannot introduce a mob");
    System.out.println(
        "World spawn conformance: "
            + checks
            + " checks passed (admission policy only; no live spawning claim).");
  }
}
