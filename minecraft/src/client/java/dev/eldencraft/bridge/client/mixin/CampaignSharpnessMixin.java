package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignEnchantments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla resolves every weapon's enchantment damage here, whether it is a swing, a spear stab or a
 * mace smash, and scales the bonus by attack strength afterwards. Adding the campaign's extra
 * Sharpness at this one point keeps it in step with all of them.
 */
@Mixin(EnchantmentHelper.class)
abstract class CampaignSharpnessMixin {
  @Inject(method = "modifyDamage", at = @At("RETURN"), cancellable = true)
  private static void eldencraft$sharperCampaignBlades(
      ServerLevel level,
      ItemStack weapon,
      Entity victim,
      DamageSource source,
      float damage,
      CallbackInfoReturnable<Float> cir) {
    if (!(source.getEntity() instanceof Player player) || !CampaignEnchantments.scoped(player))
      return;
    int sharpness = CampaignEnchantments.level(weapon, "minecraft:sharpness");
    if (sharpness <= 0) return;
    double extra =
        CampaignEnchantments.sharpness(sharpness)
            - CampaignEnchantments.vanillaSharpness(sharpness);
    cir.setReturnValue(cir.getReturnValueF() + (float) extra);
  }
}
