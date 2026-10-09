package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.SceneCapture;
import dev.eldencraft.bridge.client.WorldTextCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(TextFeatureRenderer.class)
abstract class WorldTextFeatureMixin {
  @WrapOperation(
      method = "buildGroup",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer;renderText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$GlyphRenderer;Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content$Text;)V"))
  private void eldencraft$worldLine(
      Font font,
      @Coerce Object glyphRenderer,
      TextFeatureRenderer.Content.Text text,
      Operation<Void> original) {
    WorldTextCapture.line(
        SceneCapture.worldPass(),
        text.x() + font.width(text.string()) * .5f,
        () -> original.call(font, glyphRenderer, text));
  }
}
