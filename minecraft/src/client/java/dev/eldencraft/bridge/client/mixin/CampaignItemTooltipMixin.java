package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.CampaignWeaponTooltips;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.TooltipDisplay;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStack.class)
abstract class CampaignItemTooltipMixin {
  @WrapMethod(method = "addDetailsToTooltip")
  private void eldencraft$campaignDetails(
      Item.TooltipContext context,
      TooltipDisplay display,
      Player player,
      TooltipFlag flag,
      Consumer<Component> output,
      Operation<Void> original) {
    CampaignWeaponTooltips.details(
        (ItemStack) (Object) this,
        display,
        player,
        output,
        adjusted -> original.call(context, adjusted, player, flag, output));
  }
}
