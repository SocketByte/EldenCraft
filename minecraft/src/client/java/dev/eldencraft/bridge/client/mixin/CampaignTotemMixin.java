package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignRegeneration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class CampaignTotemMixin {
  // Totems spent for native hits and vanilla's own (Minecraft hazards) both pass here.
  @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"))
  private void eldencraft$totemUsed(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ() && (Object) this instanceof ServerPlayer player)
      CampaignRegeneration.granted(player);
  }
}
