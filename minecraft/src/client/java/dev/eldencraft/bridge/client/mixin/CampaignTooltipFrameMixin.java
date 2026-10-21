package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignTooltipTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TooltipRenderUtil.class)
abstract class CampaignTooltipFrameMixin {
  @Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$engravedFrame(
      GuiGraphicsExtractor graphics,
      int x,
      int y,
      int width,
      int height,
      Identifier style,
      CallbackInfo ci) {
    if (!CampaignTooltipTheme.STYLE.equals(style)) return;
    CampaignTooltipTheme.frame(graphics, x - 7, y - 7, width + 14, height + 14);
    ci.cancel();
  }
}
