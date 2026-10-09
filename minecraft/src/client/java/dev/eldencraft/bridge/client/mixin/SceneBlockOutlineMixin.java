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
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
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
    // Sampled native ground is not a visible Minecraft block to mine or shoot,
    // and its slope-following boxes are not a block shape. While a block is held
    // it previews the plain cube that block will fill.
    var target = eldencraft$placement(client, outline.pos());
    if (target == null) {
      ci.cancel();
      return;
    }
    state.blockOutlineRenderState =
        new BlockOutlineRenderState(
            target, outline.isTranslucent(), outline.highContrast(), Shapes.block());
  }

  /** Where a right click would put the held block: vanilla's own placement context. */
  private static BlockPos eldencraft$placement(Minecraft client, BlockPos clicked) {
    if (!(client.hitResult instanceof BlockHitResult hit)
        || hit.getType() != HitResult.Type.BLOCK
        || !hit.getBlockPos().equals(clicked)) return null;
    for (var hand : InteractionHand.values()) {
      var stack = client.player.getItemInHand(hand);
      if (!(stack.getItem() instanceof BlockItem item)) continue;
      // Includes the sink-into-ground and empty-cell rules of TerrainPlacementMixin.
      var context =
          item.updatePlacementContext(new BlockPlaceContext(client.player, hand, stack, hit));
      return context != null && context.canPlace() ? context.getClickedPos() : null;
    }
    return null;
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
