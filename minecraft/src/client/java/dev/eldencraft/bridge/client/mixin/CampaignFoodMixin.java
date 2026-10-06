package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.CampaignBridge;
import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Emit only genuine vanilla food regeneration, without also observing apple effect healing. */
@Mixin(FoodData.class)
abstract class CampaignFoodMixin {
  @WrapOperation(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V"))
  private void eldencraft$foodHealing(ServerPlayer player, float amount, Operation<Void> original) {
    float before = player.getHealth();
    original.call(player, amount);
    float healed = player.getHealth() - before;
    if (CampaignCombat.active(player) && Float.isFinite(healed) && healed > 0)
      CampaignBridge.recordHealing(player, healed);
  }
}
