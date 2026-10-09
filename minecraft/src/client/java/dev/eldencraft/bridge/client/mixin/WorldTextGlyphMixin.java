package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.eldencraft.bridge.client.WorldTextCapture;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
abstract class WorldTextGlyphMixin {
  @WrapOperation(
      method = "acceptRenderable",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/font/TextRenderable;render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V"))
  private void eldencraft$worldGlyph(
      TextRenderable glyph,
      Matrix4fc pose,
      VertexConsumer vertices,
      int light,
      boolean noDepthOffset,
      Operation<Void> original) {
    WorldTextCapture.glyph(
        pose,
        vertices,
        (corrected, wound) -> original.call(glyph, corrected, wound, light, noDepthOffset));
  }
}
