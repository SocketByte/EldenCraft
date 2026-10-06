package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class CampaignGuardMixin {
  @Inject(method = "applyItemBlocking", at = @At("HEAD"), cancellable = true)
  private void eldencraft$exhaustedGuard(
      ServerLevel level, DamageSource source, float raw, CallbackInfoReturnable<Float> cir) {
    if ((Object) this instanceof Player player
        && CampaignCombat.active(player)
        && !CampaignCombat.shieldReady(player)) {
      player.stopUsingItem();
      cir.setReturnValue(0f);
    }
  }

  @Inject(method = "applyItemBlocking", at = @At("RETURN"))
  private void eldencraft$guardCost(
      ServerLevel level, DamageSource source, float raw, CallbackInfoReturnable<Float> cir) {
    if ((Object) this instanceof Player player && cir.getReturnValue() > 0) {
      // Remaining stamina absorbs what it can pay for; the rest of the hit goes through.
      double absorbed = CampaignCombat.guard(player, raw);
      if (absorbed < 1) cir.setReturnValue((float) (cir.getReturnValue() * absorbed));
    }
  }
}
