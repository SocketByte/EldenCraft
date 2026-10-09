package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.SharedWorldBlocks;
import java.util.ArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.gamerules.GameRules;

/** Protects the dedicated offline stand-in even before the first host publication. */
public final class WorldStartupSafety {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_world");

  private WorldStartupSafety() {}

  static boolean owns(boolean singleplayer, boolean published, int players, String name) {
    return singleplayer && !published && players <= 1 && EldenCraftWorld.supportedName(name);
  }

  public static boolean owns(MinecraftServer game) {
    return game != null
        && owns(
            game.isSingleplayer(),
            game.isPublished(),
            game.getPlayerCount(),
            game.getWorldData().getLevelName());
  }

  /** Damage without a bridge receipt must never kill the offline Minecraft stand-in. */
  public static boolean protects(ServerPlayer player) {
    if (player == null) return false;
    var game = player.level().getServer();
    return protects(
        game.isSingleplayer(),
        game.isPublished(),
        game.getPlayerCount(),
        game.getWorldData().getLevelName(),
        player.level().dimension().equals(SharedWorldBlocks.DIMENSION),
        player.entityTags().stream().anyMatch(tag -> tag.startsWith("eldencraft.character.")));
  }

  static boolean protects(
      boolean singleplayer,
      boolean published,
      int players,
      String name,
      boolean sharedDimension,
      boolean paired) {
    return singleplayer
        && !published
        && players <= 1
        && (EldenCraftWorld.supportedName(name) || sharedDimension || paired);
  }

  /**
   * Independent of bridge freshness: loading, native death, warps and host loss all retain items.
   */
  public static void preserveInventory(MinecraftServer game) {
    preserveInventory(game.getGlobalGameRules(), game);
    for (var level : game.getAllLevels()) preserveInventory(level.getGameRules(), game);
  }

  static void preserveInventory(GameRules rules, MinecraftServer game) {
    if (!rules.get(GameRules.KEEP_INVENTORY)) rules.set(GameRules.KEEP_INVENTORY, true, game);
  }

  /** Repair a zero-HP save or an out-of-band health change before vanilla's death tick. */
  public static void keepAlive(ServerPlayer player) {
    if (!Float.isFinite(player.getHealth()) || player.getHealth() <= 0) player.setHealth(.001f);
    player.deathTime = 0;
  }

  public static boolean staging(ServerLevel level) {
    return level != null
        && !level.dimension().equals(SharedWorldBlocks.DIMENSION)
        && owns(level.getServer());
  }

  /** Existing saves can already contain slimes. Remove staging hostiles before entity ticks. */
  public static void tick(MinecraftServer game) {
    boolean protectedWorld = owns(game);
    for (var player : game.getPlayerList().getPlayers()) {
      if (!protects(player)) continue;
      protectedWorld = true;
      keepAlive(player);
    }
    if (protectedWorld) preserveInventory(game);
    if (!owns(game)) return;
    int removed = 0;
    for (var level : game.getAllLevels()) {
      if (!staging(level)) continue;
      var hostiles = new ArrayList<Entity>();
      for (var entity : level.getAllEntities()) {
        if (entity instanceof Enemy && !entity.isRemoved()) hostiles.add(entity);
      }
      for (var entity : hostiles) {
        entity.discard();
        removed++;
      }
    }
    if (removed > 0) LOG.info("Removed {} hostile mobs from the dedicated staging world.", removed);
  }
}
