package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.SharedWorldBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shadow terrain is invisible, lets all light through and occludes nothing, exactly like air. The
 * boundary shell that follows the player rewrites hundreds of such cells at every step, and almost
 * every tick on Torrent; recompiling their chunk sections cannot change a pixel. Any change that
 * involves a real block still marks its sections, and the model check of setBlocksDirty is kept.
 */
@Mixin(ClientLevel.class)
abstract class ShadowTerrainRenderMixin {
  @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
  private void eldencraft$invisibleTerrain(
      BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
    if (eldencraft$invisible(before) && eldencraft$invisible(after)) ci.cancel();
  }

  @Unique
  private static boolean eldencraft$invisible(BlockState state) {
    return state.isAir() || state.is(SharedWorldBlocks.TERRAIN);
  }
}
