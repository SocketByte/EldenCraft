package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.ShadowTerrainBlock;
import dev.eldencraft.bridge.SharedWorldBlocks;
import dev.eldencraft.bridge.TerrainMaterials;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mining Elden Ring terrain for resources. Holding attack on a hidden terrain cell mines it as the
 * block its material maps to (TerrainMaterials): that block's real hardness, tool speed,
 * correct-tool rule, loot table and tool wear, in survival only. Elden Ring's ground itself never
 * changes; a mined cell is spent for ten minutes. Server thread only.
 */
public final class TerrainMining {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_world");

  /** Ticks before a mined cell yields again (10 minutes). */
  static final long REGROW_TICKS = 20L * 60 * 10;

  private record Job(BlockPos pos, Direction face, BlockState as, long started) {}

  private static final Map<UUID, Job> JOBS = new HashMap<>();
  private static final Map<Long, Long> SPENT = new HashMap<>();
  private static final Map<UUID, Float> PROGRESS = new HashMap<>();

  private TerrainMining() {}

  /**
   * The block a terrain cell mines as, or null (not terrain, border shell, water or unknown id).
   */
  public static BlockState mineAs(ServerLevel level, BlockPos pos) {
    var state = level.getBlockState(pos);
    if (!state.is(SharedWorldBlocks.TERRAIN)
        || ShadowTerrainBlock.boundary(state)
        || !level.dimension().equals(SharedWorldBlocks.DIMENSION)) return null;
    var material = TerrainMaterials.resolve(pos.asLong());
    var id = TerrainMaterials.blockId(material);
    var config = CampaignConfig.current();
    if (config.enabled) {
      var player = SharedWorldClient.hostPlayer(level.getServer());
      var context = player == null ? null : SharedWorldClient.projectileContext(player);
      var resource =
          context == null
              ? null
              : config.mining.resource(context.host().sourceMap(), material.hit());
      if (resource != null) id = resource;
      else if (!config.mining.allowedBlocks().contains(id)) return null;
    }
    if (id == null) return null;
    var block = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(id));
    return block == null || block == Blocks.AIR ? null : block.defaultBlockState();
  }

  /** Pure policy: cells regrow after REGROW_TICKS. */
  static boolean spent(Long minedAt, long now) {
    long regrow =
        CampaignConfig.current().enabled
            ? CampaignConfig.current().mining.regrowTicks()
            : REGROW_TICKS;
    return minedAt != null && now - minedAt < regrow;
  }

  /** ServerPlayerGameMode.handleBlockBreakAction, before vanilla handling. */
  public static void onAction(
      ServerPlayer player,
      BlockPos pos,
      ServerboundPlayerActionPacket.Action action,
      Direction face) {
    var level = player.level();
    switch (action) {
      case START_DESTROY_BLOCK -> {
        JOBS.remove(player.getUUID());
        PROGRESS.remove(player.getUUID());
        if (!player.gameMode.isSurvival() || !SharedWorldClient.controlsPlayer(player)) return;
        var as = mineAs(level, pos);
        if (as == null) return;
        if (spent(SPENT.get(pos.asLong()), level.getGameTime())) {
          level.playSound(null, pos, as.getSoundType().getHitSound(), SoundSource.BLOCKS, .4f, .6f);
          return; // Mined out; it regrows.
        }
        JOBS.put(player.getUUID(), new Job(pos.immutable(), face, as, level.getGameTime()));
      }
      case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
        JOBS.remove(player.getUUID());
        PROGRESS.remove(player.getUUID());
      }
      default -> {}
    }
  }

  /** End of each server tick: advance every mining player. */
  public static void tick(MinecraftServer server) {
    if (JOBS.isEmpty()) return;
    for (var iterator = JOBS.entrySet().iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      var job = entry.getValue();
      var player = server.getPlayerList().getPlayer(entry.getKey());
      if (player == null
          || player.isRemoved()
          || !player.gameMode.isSurvival()
          || !SharedWorldClient.controlsPlayer(player)
          || mineAs(player.level(), job.pos()) == null
          || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(job.pos())) > 64) {
        iterator.remove();
        PROGRESS.remove(entry.getKey());
        continue;
      }
      var level = player.level();
      float progress =
          PROGRESS.getOrDefault(entry.getKey(), 0f)
              + job.as().getDestroyProgress(player, level, job.pos());
      long ticks = level.getGameTime() - job.started();
      var sound = job.as().getSoundType();
      if (ticks % 4 == 0) {
        level.playSound(
            null,
            job.pos(),
            sound.getHitSound(),
            SoundSource.BLOCKS,
            (sound.getVolume() + 1) / 8,
            sound.getPitch() * .5f);
        var c =
            net.minecraft.world.phys.Vec3.atCenterOf(job.pos())
                .add(
                    job.face().getStepX() * .52,
                    job.face().getStepY() * .52,
                    job.face().getStepZ() * .52);
        level.sendParticles(
            new BlockParticleOption(ParticleTypes.BLOCK, job.as()),
            c.x,
            c.y,
            c.z,
            3,
            .2,
            .2,
            .2,
            .05);
      }
      if (progress < 1) {
        PROGRESS.put(entry.getKey(), progress);
        continue;
      }
      iterator.remove();
      PROGRESS.remove(entry.getKey());
      harvest(level, player, job);
    }
  }

  private static void harvest(ServerLevel level, ServerPlayer player, Job job) {
    var tool = player.getMainHandItem();
    level.levelEvent(
        null,
        2001,
        job.pos(),
        Block.getId(job.as())); // Break particles and sound of the mapped block.
    if (player.hasCorrectToolForDrops(job.as())) {
      for (var drop : Block.getDrops(job.as(), level, job.pos(), null, player, tool))
        Block.popResourceFromFace(level, job.pos(), job.face(), drop);
      // Run the mapped vanilla ore's real XP policy too (including Silk Touch), without changing
      // the native surface or the hidden collision cell itself.
      job.as().spawnAfterBreak(level, job.pos(), tool, true);
    }
    tool.mineBlock(level, job.as(), job.pos(), player);
    player.causeFoodExhaustion(.005f);
    SPENT.put(job.pos().asLong(), level.getGameTime());
    if (SPENT.size() > 4096)
      SPENT.entrySet().removeIf(e -> !spent(e.getValue(), level.getGameTime()));
    LOG.info(
        "Mined Elden Ring terrain at {} as {}",
        job.pos(),
        BuiltInRegistries.BLOCK.getKey(job.as().getBlock()));
  }

  public static void clear() {
    cancelJobs();
    SPENT.clear();
  }

  /** Cancels an interrupted mining action while preserving the world's depletion cooldowns. */
  public static void cancelJobs() {
    JOBS.clear();
    PROGRESS.clear();
  }
}
