package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.eldencraft.bridge.client.*;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Readable offers and a guide that reflects this character's actual whetblade pickups. */
@Mixin(EnchantmentScreen.class)
abstract class CampaignEnchantingScreenMixin extends AbstractContainerScreen<EnchantmentMenu> {
  @Unique private boolean eldencraft$guide = true;
  @Unique private long eldencraft$opened;

  private CampaignEnchantingScreenMixin(
      EnchantmentMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
  }

  @Inject(method = "init", at = @At("TAIL"))
  private void eldencraft$guideButton(CallbackInfo ci) {
    if (!CampaignWeaponTooltips.active(minecraft.player)) return;
    eldencraft$opened = System.nanoTime();
    addRenderableWidget(
        new CampaignGuideButton(
            font,
            leftPos + imageWidth - 54,
            topPos + 2,
            button -> {
              eldencraft$guide = !eldencraft$guide;
              eldencraft$opened = eldencraft$guide ? System.nanoTime() : 0;
            }));
  }

  @WrapOperation(
      method = "extractBackground",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/screens/inventory/EnchantmentNames;getRandomName(Lnet/minecraft/client/gui/Font;I)Lnet/minecraft/network/chat/FormattedText;"))
  private FormattedText eldencraft$readableOffers(
      EnchantmentNames names,
      Font font,
      int width,
      Operation<FormattedText> original,
      @Local(index = 8) int row) {
    if (!CampaignWeaponTooltips.active(minecraft.player) || minecraft.level == null)
      return original.call(names, font, width);
    var enchantment =
        minecraft
            .level
            .registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(menu.enchantClue[row]);
    if (enchantment.isEmpty())
      return Component.translatable("screen.eldencraft_bridge.enchanting.empty");
    String id = enchantment.get().unwrapKey().map(key -> key.identifier().toString()).orElse("");
    boolean affordable =
        minecraft.player.hasInfiniteMaterials()
            || (menu.getGoldCount() >= row + 1
                && minecraft.player.experienceLevel >= menu.costs[row]);
    if (affordable) return CampaignTooltipTheme.enchantment(id, menu.levelClue[row]);
    return CampaignTooltipTheme.icon(
            CampaignTooltipTheme.enchantmentIcon(id), CampaignTooltipTheme.MUTED)
        .append(
            CampaignTooltipTheme.text(
                " " + CampaignTooltipTheme.title(id, menu.levelClue[row]),
                CampaignTooltipTheme.MUTED));
  }

  @WrapOperation(
      method = "extractRenderState",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"))
  private void eldencraft$effectTooltip(
      GuiGraphicsExtractor graphics,
      Font font,
      List<Component> lines,
      int mouseX,
      int mouseY,
      Operation<Void> original,
      @Local(index = 8) int row) {
    if (!CampaignWeaponTooltips.active(minecraft.player) || minecraft.level == null) {
      original.call(graphics, font, lines, mouseX, mouseY);
      return;
    }
    var extended = new ArrayList<Component>();
    var enchantment =
        minecraft
            .level
            .registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(menu.enchantClue[row]);
    extended.add(CampaignTooltipTheme.heading("REVEALED EFFECT"));
    if (enchantment.isPresent()) {
      String id = enchantment.get().unwrapKey().map(key -> key.identifier().toString()).orElse("");
      extended.addAll(CampaignTooltipTheme.effect(id, menu.levelClue[row]));
      extended.add(Component.empty());
      var blade = CampaignEnchantments.whetbladeOf(id);
      if (blade != null) extended.add(CampaignEnchantmentGuide.whetblade(minecraft.player, blade));
    }
    extended.add(
        CampaignTooltipTheme.text(
            "Other enchantments may also appear.", CampaignTooltipTheme.MUTED));
    extended.add(Component.empty());
    extended.add(CampaignTooltipTheme.heading("REQUIRED OFFERING"));
    if (lines.size() > 1) extended.addAll(lines.subList(1, lines.size()));
    graphics.setComponentTooltipForNextFrame(
        font, extended, mouseX, mouseY, CampaignTooltipTheme.STYLE);
  }

  @WrapOperation(
      method = "extractBackground",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
  private void eldencraft$stoneTable(
      GuiGraphicsExtractor g,
      RenderPipeline pipeline,
      Identifier texture,
      int x,
      int y,
      float u,
      float v,
      int w,
      int h,
      int tw,
      int th,
      Operation<Void> original) {
    if (!CampaignWeaponTooltips.active(minecraft.player)) {
      original.call(g, pipeline, texture, x, y, u, v, w, h, tw, th);
      return;
    }
    CampaignTooltipTheme.frame(g, x, y, w, h);
    g.fill(x + 7, y + 18, x + 55, y + 44, 0xff242a22);
    CampaignTooltipTheme.rule(g, x + 8, y + 77, w - 16);
    for (var slot : menu.slots) {
      int sx = x + slot.x - 1, sy = y + slot.y - 1;
      g.fill(sx, sy, sx + 18, sy + 18, 0xff0e1312);
      g.outline(sx, sy, 18, 18, 0xff4c5142);
      g.fill(sx + 1, sy + 1, sx + 17, sy + 2, 0xff252b23);
    }
  }

  @WrapOperation(
      method = "extractBackground",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
  private void eldencraft$engravedOffer(
      GuiGraphicsExtractor g,
      RenderPipeline pipeline,
      Identifier sprite,
      int x,
      int y,
      int w,
      int h,
      Operation<Void> original,
      @Local(index = 8) int row) {
    if (!CampaignWeaponTooltips.active(minecraft.player)) {
      original.call(g, pipeline, sprite, x, y, w, h);
      return;
    }
    if (w == 108) {
      boolean hover = sprite.getPath().contains("highlighted");
      boolean disabled = sprite.getPath().contains("disabled");
      g.fillGradient(
          x, y, x + w, y + h, hover ? 0xff41452f : 0xff293024, disabled ? 0xff171c18 : 0xff1b221b);
      g.outline(x, y, w, h, hover ? 0xffd7ba78 : disabled ? 0xff414637 : 0xff7b704e);
      g.fill(x + 18, y + 3, x + 19, y + h - 3, 0xff514f39);
    } else {
      g.centeredText(
          font,
          CampaignTooltipTheme.text(
              CampaignTooltipTheme.roman(row + 1),
              sprite.getPath().contains("disabled")
                  ? CampaignTooltipTheme.MUTED
                  : CampaignTooltipTheme.GOLD),
          x + 8,
          y + 4,
          0xffffffff);
    }
  }

  @Override
  protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    if (!CampaignWeaponTooltips.active(minecraft.player)) {
      super.extractLabels(g, mouseX, mouseY);
      return;
    }
    g.text(
        font,
        CampaignTooltipTheme.text("Infuse armament", CampaignTooltipTheme.GOLD),
        titleLabelX,
        titleLabelY,
        0xffffffff,
        false);
    g.text(
        font,
        CampaignTooltipTheme.text("Inventory", CampaignTooltipTheme.MUTED),
        inventoryLabelX,
        inventoryLabelY,
        0xffffffff,
        false);
  }

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void eldencraft$explainInfusions(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
    if (!CampaignWeaponTooltips.active(minecraft.player)) return;
    if (isHovering(imageWidth - 54, 2, 48, 12, mouseX, mouseY)) {
      graphics.setComponentTooltipForNextFrame(
          font,
          CampaignEnchantmentGuide.tooltip(minecraft.player),
          mouseX,
          mouseY,
          CampaignTooltipTheme.STYLE);
      return;
    }
    if (!eldencraft$guide) return;
    int x = leftPos + imageWidth + 10;
    if (width - x >= CampaignEnchantmentGuide.WIDTH + 8
        && height >= CampaignEnchantmentGuide.HEIGHT + 16) {
      int y = Math.clamp(topPos, 8, height - CampaignEnchantmentGuide.HEIGHT - 8);
      CampaignEnchantmentGuide.panel(graphics, font, minecraft.player, x, y);
    } else if (eldencraft$opened != 0
        && System.nanoTime() - eldencraft$opened < 5_000_000_000L
        && hoveredSlot == null
        && !isHovering(60, 14, 108, 57, mouseX, mouseY)) {
      graphics.setComponentTooltipForNextFrame(
          font,
          CampaignEnchantmentGuide.tooltip(minecraft.player),
          leftPos + imageWidth - 16,
          topPos + 12,
          CampaignTooltipTheme.STYLE);
    }
  }
}
