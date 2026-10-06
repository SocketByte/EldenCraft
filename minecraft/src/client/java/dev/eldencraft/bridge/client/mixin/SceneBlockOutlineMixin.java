package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.SharedWorldBlocks;
import dev.eldencraft.bridge.client.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.item.BlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Export each vanilla block-outline pixel with the depth of that same line. */
@Mixin(LevelRenderer.class)
abstract class SceneBlockOutlineMixin {
  @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
  private void eldencraft$usefulOutline(
      PoseStack poses, SubmitNodeCollector nodes, LevelRenderState state, CallbackInfo ci) {
    var client = Minecraft.getInstance();
    var outline = state.blockOutlineRenderState;
    if (client.level == null
        || client.player == null
        || outline == null
        || !client.level.dimension().equals(SharedWorldBlocks.DIMENSION)) return;
    if (!client.level.getBlockState(outline.pos()).is(SharedWorldBlocks.TERRAIN)) return;
    // Sampled native ground remains usable as a placement preview, but is
    // not a visible Minecraft block to mine or shoot.
    boolean placing =
        client.player.getMainHandItem().getItem() instanceof BlockItem
            || client.player.getOffhandItem().getItem() instanceof BlockItem;
    if (!placing) ci.cancel();
  }

  // The improved-transparency variant has no depth writes, which leaves an
  // exported outline using background/other-face depth during reprojection.
  // Vanilla's matching depth-writing type keeps its shader, actual shape,
  // width, color and layering. All other line/outline rendering is unchanged.
  @ModifyExpressionValue(
      method = "submitBlockOutline",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/RenderTypes;linesTranslucentNoDepthWrite()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
  private RenderType eldencraft$outlineCarriesDepth(RenderType original) {
    return SceneCapture.active() ? RenderTypes.linesTranslucent() : original;
  }
}
