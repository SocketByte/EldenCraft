package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
abstract class CampaignPotionHealingMixin {
  @WrapOperation(
      method = {"applyEffectTick", "applyInstantaneousEffect"},
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;heal(F)V"))
  private void eldencraft$actualPotionHeal(
      LivingEntity entity, float amount, Operation<Void> original) {
    float before = entity.getHealth();
    original.call(entity, amount);
    float healed = entity.getHealth() - before;
    if (entity instanceof ServerPlayer player
        && CampaignCombat.active(player)
        && Float.isFinite(healed)
        && healed > 0) CampaignBridge.recordHealing(player, healed);
  }
}
