package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.BlockMeshClient;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the vanilla chunk groups move to the ACK-confirmed native mesh pass. */
@Mixin(ChunkSectionsToRender.class)
abstract class BlockMeshChunksMixin {
  @Inject(
      method = {"renderGroup", "renderOit"},
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$uploadedBlockGeometry(CallbackInfo ci) {
    if (BlockMeshClient.suppressChunks()) ci.cancel();
  }
}
