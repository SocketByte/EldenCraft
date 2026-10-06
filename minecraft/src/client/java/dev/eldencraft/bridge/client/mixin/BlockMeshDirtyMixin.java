package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.BlockMeshClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class BlockMeshDirtyMixin {
  @Inject(method = "setBlocksDirty", at = @At("HEAD"))
  private void eldencraft$stateChanged(
      BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
    BlockMeshClient.dirty(pos, oldState, newState);
  }

  @Inject(method = "onChunkLoaded", at = @At("TAIL"))
  private void eldencraft$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
    BlockMeshClient.chunkChanged(pos);
  }

  @Inject(method = "unload", at = @At("TAIL"))
  private void eldencraft$chunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
    BlockMeshClient.chunkChanged(chunk.getPos());
  }

  @Inject(method = "clearTintCaches", at = @At("HEAD"))
  private void eldencraft$tintChanged(CallbackInfo ci) {
    BlockMeshClient.tintDirty();
  }
}
