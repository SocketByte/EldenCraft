package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.BlocksAttacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The campaign's shield cast time replaces the item's raise delay while the campaign is active. */
@Mixin(LivingEntity.class)
abstract class CampaignShieldRaiseMixin {
  @WrapOperation(
      method = "getItemBlockingWith",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/item/component/BlocksAttacks;blockDelayTicks()I"))
  private int eldencraft$raiseDelay(BlocksAttacks blocks, Operation<Integer> original) {
    int ticks = original.call(blocks);
    return (Object) this instanceof Player player
        ? CampaignCombat.shieldRaiseTicks(player, ticks)
        : ticks;
  }
}
