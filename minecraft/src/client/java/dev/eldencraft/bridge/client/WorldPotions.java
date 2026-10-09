package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;

/** Lingering damage only from an actual cloud created by a verified vanilla potion impact. */
public final class WorldPotions {
  private record Origin(ServerPlayer owner, long session, long epoch, long map, long created) {}

  private static final Map<AreaEffectCloud, Origin> CLOUDS = new IdentityHashMap<>();
  private static final ThreadLocal<AreaEffectCloud> TICK = new ThreadLocal<>();

  private WorldPotions() {}

  public static void created(ThrownLingeringPotion potion, Entity entity) {
    if (!(entity instanceof AreaEffectCloud cloud)
        || !(potion.getOwner() instanceof ServerPlayer player)
        || WorldProjectiles.evidence(potion) == null
        || cloud.getOwner() != player) return;
    var c = SharedWorldClient.projectileContext(player);
    if (c == null) return;
    CLOUDS
        .entrySet()
        .removeIf(
            e ->
                e.getKey().isRemoved()
                    || player.level().getGameTime() - e.getValue().created > 640);
    if (CLOUDS.size() < 32)
      CLOUDS.put(
          cloud,
          new Origin(
              player, c.session(), c.host().epoch(), c.host().map(), player.level().getGameTime()));
  }

  public static void tick(AreaEffectCloud cloud, Runnable vanilla) {
    var prior = TICK.get();
    TICK.set(cloud);
    try {
      vanilla.run();
    } finally {
      if (prior == null) TICK.remove();
      else TICK.set(prior);
    }
  }

  public static boolean permits(LivingEntity target, DamageSource source) {
    if (!(source.getDirectEntity() instanceof AreaEffectCloud cloud)
        || TICK.get() != cloud
        || !source.is(DamageTypes.INDIRECT_MAGIC)
        || cloud.isRemoved()
        || cloud.level() != target.level()) return false;
    var origin = CLOUDS.get(cloud);
    if (origin == null || cloud.getOwner() != origin.owner || source.getEntity() != origin.owner)
      return false;
    var c = SharedWorldClient.projectileContext(origin.owner);
    long age = target.level().getGameTime() - origin.created;
    return c != null
        && c.session() == origin.session
        && c.host().epoch() == origin.epoch
        && c.host().map() == origin.map
        && age >= 0
        && age <= 640
        && cloud.getRadius() >= .5
        && cloud.getRadius() <= 3.1
        && cloud.distanceToSqr(target) <= 16;
  }

  public static void clear() {
    CLOUDS.clear();
    TICK.remove();
  }
}
