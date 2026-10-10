package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.SharedWorldClient;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class ColliderChunkMixin {
  @Inject(method = "setBlockState", at = @At("RETURN"))
  private void eldencraft$collisionChanged(
      BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
    if (((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level)
      SharedWorldClient.collisionChanged(level, pos, cir.getReturnValue(), state);
  }
}
