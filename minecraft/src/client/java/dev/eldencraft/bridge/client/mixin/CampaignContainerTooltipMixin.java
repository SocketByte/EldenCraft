package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Inventories pass their item lines straight to the tooltip; give them the equipment card. */
@Mixin(AbstractContainerScreen.class)
abstract class CampaignContainerTooltipMixin {
  @WrapOperation(
      method = "extractTooltip",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;Z)V"))
  private void eldencraft$equipmentCard(
      GuiGraphicsExtractor graphics,
      Font font,
      List<Component> lines,
      Optional<TooltipComponent> image,
      int x,
      int y,
      Identifier style,
      boolean focused,
      Operation<Void> original) {
    if (CampaignWeaponTooltips.active(Minecraft.getInstance().player))
      original.call(
          graphics,
          font,
          CampaignTooltipTheme.card(lines),
          image,
          x,
          y,
          CampaignTooltipTheme.STYLE,
          focused);
    else original.call(graphics, font, lines, image, x, y, style, focused);
  }
}
