package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.client.BlockDetails;
import java.util.List;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class BlockDetailsMixin {
  @Inject(method = "submitFeatures", at = @At("HEAD"))
  private void eldencraft$detailBegin(CallbackInfo ci) {
    BlockDetails.begin();
  }

  @Inject(method = "submitFeatures", at = @At("TAIL"))
  private void eldencraft$detailFinish(CallbackInfo ci) {
    BlockDetails.finish();
  }

  @WrapOperation(
      method = "submitBlockDestroyAnimation",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"))
  private void eldencraft$cracks(
      SubmitNodeCollector nodes,
      PoseStack pose,
      List<BlockStateModelPart> parts,
      int stage,
      boolean translucent,
      Operation<Void> original,
      @Local net.minecraft.client.renderer.block.dispatch.BlockStateModel model,
      @Local net.minecraft.client.renderer.state.level.BlockBreakingRenderState state,
      @Local net.minecraft.util.RandomSource random) {
    // Fabric redirects collectParts to a no-op before this call. Capture its
    // emitQuads path, not the deliberately empty legacy list. On failure the
    // chained original operation still reaches Fabric's genuine submission.
    if (!BlockDetails.breaking(pose, parts, stage, model, state, random))
      original.call(nodes, pose, parts, stage, translucent);
  }

  @WrapOperation(
      method = "submitHitOutline",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShapeOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/client/renderer/rendertype/RenderType;IFZ)V"))
  private void eldencraft$outline(
      SubmitNodeCollector nodes,
      PoseStack pose,
      VoxelShape shape,
      RenderType type,
      int color,
      float width,
      boolean after,
      Operation<Void> original) {
    if (!BlockDetails.outline(pose, shape, color, width))
      original.call(nodes, pose, shape, type, color, width, after);
  }
}
