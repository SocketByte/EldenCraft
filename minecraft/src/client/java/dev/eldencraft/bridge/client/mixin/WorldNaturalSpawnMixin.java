package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldMobSpawning;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
abstract class WorldNaturalSpawnMixin {
  @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$noNaturalPopulation(
      ServerLevel level,
      LevelChunk chunk,
      NaturalSpawner.SpawnState state,
      List<MobCategory> categories,
      CallbackInfo ci) {
    if (WorldMobSpawning.shared(level)) ci.cancel();
  }

  @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$noWorldgenPopulation(
      ServerLevelAccessor level,
      BlockPos pos,
      ChunkPos chunk,
      RandomSource random,
      CallbackInfo ci) {
    if (WorldMobSpawning.shared(level.getLevel())) ci.cancel();
  }
}
