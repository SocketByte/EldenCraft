package dev.eldencraft.bridge.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.client.BlockMeshClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animated blocks left out of the native mesh (fire, magma, the portal) still render, live, in the
 * scene capture.
 */
@Mixin(LevelRenderer.class)
abstract class BlockMeshLiveMixin {
  @Inject(method = "submitTransientBlocks", at = @At("TAIL"))
  private void eldencraft$liveBlocks(
      PoseStack pose, SubmitNodeCollector collector, LevelRenderState state, CallbackInfo ci) {
    BlockMeshClient.submitLiveBlocks(pose, collector, state.cameraRenderState.pos);
  }
}
