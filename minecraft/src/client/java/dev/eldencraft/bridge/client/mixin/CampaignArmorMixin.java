package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class CampaignArmorMixin {
  // Wrap only the formula: vanilla still checks armor-bypassing damage and
  // applies armor durability exactly once before this call.
  @WrapOperation(
      method = "getDamageAfterArmorAbsorb",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/damagesource/CombatRules;getDamageAfterAbsorb(Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/damagesource/DamageSource;FF)F"))
  private float eldencraft$percentageArmor(
      LivingEntity target,
      float raw,
      DamageSource source,
      float vanillaArmor,
      float vanillaToughness,
      Operation<Float> original) {
    if (target instanceof Player player && CampaignCombat.active(player))
      return (float) CampaignCombat.damageAfterArmor(raw, CampaignCombat.armorReduction(player));
    return original.call(target, raw, source, vanillaArmor, vanillaToughness);
  }
}
