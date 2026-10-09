package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.PaintingImage;
import dev.eldencraft.bridge.client.SceneCapture;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keep painting images readable when the captured world is projected into the host screen basis.
 */
@Mixin(PaintingRenderer.class)
abstract class PaintingImageMixin {
  // The pinned renderer's first getU call belongs to the frame. Only the next two calls
  // interpolate the front sprite across its full width; the later vertices remain vanilla.
  @ModifyArg(
      method = "lambda$renderPainting$0",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;getU(F)F",
              ordinal = 1),
      index = 0)
  private static float eldencraft$frontFirstU(float u) {
    return PaintingImage.frontU(SceneCapture.worldPass(), u);
  }

  @ModifyArg(
      method = "lambda$renderPainting$0",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;getU(F)F",
              ordinal = 2),
      index = 0)
  private static float eldencraft$frontSecondU(float u) {
    return PaintingImage.frontU(SceneCapture.worldPass(), u);
  }
}
