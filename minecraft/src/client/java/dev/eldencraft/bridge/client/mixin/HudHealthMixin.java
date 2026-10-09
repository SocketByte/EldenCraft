package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.HostHealthDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
abstract class HudHealthMixin {
  @Shadow private int lastHealth;
  @Shadow private int displayHealth;
  @Unique private Player eldencraft$heartPlayer;
  @Unique private boolean eldencraft$compact;

  @WrapMethod(method = "extractPlayerHealth")
  private void eldencraft$vanillaHostHearts(
      GuiGraphicsExtractor graphics, Operation<Void> original) {
    var player = Minecraft.getInstance().player;
    boolean compact = player != null && HostHealthDisplay.compact(player);
    if (player != eldencraft$heartPlayer || compact != eldencraft$compact) {
      // Vanilla's blink trail belongs to the old HUD units/player. Never use
      // an unscaled trail to expand the new character's row count for a second.
      lastHealth =
          displayHealth =
              player == null
                  ? 0
                  : (int) Math.ceil(HostHealthDisplay.units(player, player.getHealth()));
      eldencraft$heartPlayer = player;
      eldencraft$compact = compact;
    }
    HostHealthDisplay.extract(() -> original.call(graphics));
  }

  @WrapOperation(
      method = "extractPlayerHealth",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"))
  private float eldencraft$compactHealth(Player player, Operation<Float> original) {
    return HostHealthDisplay.units(player, original.call(player));
  }

  @WrapOperation(
      method = "extractPlayerHealth",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
  private double eldencraft$compactMaximum(
      Player player, Holder<Attribute> attribute, Operation<Double> original) {
    return HostHealthDisplay.maximum(player, original.call(player, attribute));
  }

  @WrapOperation(
      method = "extractPlayerHealth",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/entity/player/Player;getAbsorptionAmount()F"))
  private float eldencraft$compactAbsorption(Player player, Operation<Float> original) {
    return HostHealthDisplay.units(player, original.call(player));
  }
}
