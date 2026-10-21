package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignCombat;
import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(BowItem.class)
abstract class CampaignBowMixin {
  /**
   * An empty bar cannot loose an arrow, so it cannot draw one either: otherwise a full draw ends in
   * a release that silently fires nothing.
   */
  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void eldencraft$exhaustedDraw(
      Level level,
      Player player,
      InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (!level.isClientSide()
        && CampaignCombat.active(player)
        && !CampaignCombat.canSpend(player, "bow")) cir.setReturnValue(InteractionResult.FAIL);
  }

  @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
  private void eldencraft$bowStamina(
      ItemStack stack,
      Level level,
      LivingEntity entity,
      int remaining,
      CallbackInfoReturnable<Boolean> cir) {
    if (entity instanceof Player player
        && !level.isClientSide()
        && CampaignCombat.active(player)
        && WorldProjectiles.permitHeldItem(player)
        && !player.getProjectile(stack).isEmpty()
        && BowItem.getPowerForTime(stack.getUseDuration(entity) - remaining) >= .1f
        && !CampaignCombat.spend(player, "bow")) cir.setReturnValue(false);
  }
}

@Mixin(CrossbowItem.class)
abstract class CampaignCrossbowMixin {
  @Inject(method = "performShooting", at = @At("HEAD"), cancellable = true)
  private void eldencraft$crossbowStamina(
      Level level,
      LivingEntity entity,
      InteractionHand hand,
      ItemStack stack,
      float velocity,
      float spread,
      LivingEntity target,
      CallbackInfo ci) {
    if (entity instanceof Player player
        && !level.isClientSide()
        && CampaignCombat.active(player)
        && CrossbowItem.isCharged(stack)
        && !CampaignCombat.spend(player, "crossbow")) ci.cancel();
  }
}
