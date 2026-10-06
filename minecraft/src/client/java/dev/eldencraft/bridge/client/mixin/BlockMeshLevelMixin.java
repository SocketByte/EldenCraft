package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.client.BlockMeshClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class BlockMeshLevelMixin {
  @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
  private void eldencraft$optionsChanged(CallbackInfo ci) {
    BlockMeshClient.dirty();
  }

  // ViewArea is now current, and features/chunks have not been submitted yet.
  @Inject(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/LevelRenderer;repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
              shift = At.Shift.AFTER))
  private void eldencraft$completeBlockCoverage(CallbackInfo ci) {
    BlockMeshClient.beginFrame();
  }

  // 26.3 immediately submits newly changed block models while GPU sections compile.
  // Skip only submission: vanilla removal queues, expiry and pose-stack cleanup still run.
  @WrapWithCondition(
      method = "submitTransientBlocks",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitMovingBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V"))
  private boolean eldencraft$avoidDuplicateTransientBlocks(
      SubmitNodeCollector collector, PoseStack pose, MovingBlockRenderState state, int outline) {
    return !BlockMeshClient.suppressChunks();
  }
}
