package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.HostHealthDisplay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Hud.class)
abstract class HudHealthMixin {
  @WrapMethod(method = "extractPlayerHealth")
  private void eldencraft$vanillaHostHearts(
      GuiGraphicsExtractor graphics, Operation<Void> original) {
    HostHealthDisplay.extract(() -> original.call(graphics));
  }
}
