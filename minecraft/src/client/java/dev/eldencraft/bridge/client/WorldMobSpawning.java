package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

/**
 * Integrated-server admission. Scope tokens authorize one exact egg/replacement entity, not
 * descendants.
 */
public final class WorldMobSpawning {
  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private WorldMobSpawning() {}

  public static boolean shared(Level level) {
    return level != null
        && !level.isClientSide()
        && level.dimension().equals(SharedWorldBlocks.DIMENSION);
  }

  public static boolean suppressNaturalSpawns(Level level) {
    return shared(level)
        || level instanceof ServerLevel serverLevel
            && WorldStartupSafety.owns(serverLevel.getServer());
  }

  public static final class Scope implements AutoCloseable {
    final Scope prior;
    final Level level;
    final EntityType<?> type;
    final boolean egg, replacement, summoned;
    boolean used;

    Scope(Level level, EntityType<?> type, boolean egg, boolean replacement) {
      this(level, type, egg, replacement, false);
    }

    Scope(Level level, EntityType<?> type, boolean egg, boolean replacement, boolean summoned) {
      prior = CURRENT.get();
      this.level = level;
      this.type = type;
      this.egg = egg;
      this.replacement = replacement;
      this.summoned = summoned;
      CURRENT.set(this);
    }

    @Override
    public void close() {
      if (prior == null) CURRENT.remove();
      else CURRENT.set(prior);
    }
  }

  public static Scope egg(Level level, EntityType<?> type, ItemStack stack) {
    return new Scope(
        level,
        type,
        shared(level)
            && stack != null
            && stack.getItem() instanceof SpawnEggItem
            && SpawnEggItem.getType(stack) == type,
        false);
  }

  /**
   * One Nether wave mob of exactly this type, created with EntitySpawnReason.EVENT on the server
   * thread.
   */
  public static Scope summon(Level level, EntityType<?> type) {
    return new Scope(level, type, false, false, shared(level));
  }

  public static Scope replacement(
      Mob original, EntityType<?> type, ConversionParams params, EntitySpawnReason reason) {
    boolean replacing =
        shared(original.level())
            && reason == EntitySpawnReason.CONVERSION
            && params.type() == ConversionType.SINGLE
            && !original.isRemoved()
            && original.level() instanceof ServerLevel level
            && level.getEntity(original.getUUID()) == original;
    return new Scope(original.level(), type, false, replacing);
  }

  public static boolean created(Level level, EntitySpawnReason reason, Entity entity) {
    if (entity == null || !shared(level) || !(entity instanceof Mob)) return true;
    var scope = CURRENT.get();
    boolean exact =
        scope != null && !scope.used && scope.level == level && scope.type == entity.getType();
    boolean allowed =
        WorldSpawnPolicy.allowed(
            true,
            true,
            reason,
            exact && scope.egg,
            exact && scope.replacement,
            exact && scope.summoned);
    if (allowed) {
      ((WorldSpawnPermit) entity).eldencraft$spawnPermitted(true);
      if (exact) scope.used = true;
    }
    return allowed;
  }

  public static boolean admit(ServerLevel level, Entity entity) {
    return !shared(level)
        || !(entity instanceof Mob)
        || entity instanceof WorldSpawnPermit permit && permit.eldencraft$spawnPermitted();
  }
}
