package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldFallSafety;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class WorldFallDamageMixin {
  // Exact26.3 Player.causeFallDamage(double,float,DamageSource) precedes the
  // actual health mutation and fall statistic; this is not a generic hurt hook.
  @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
  private void eldencraft$singleFallAuthority(
      double distance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> ci) {
    if ((Object) this instanceof ServerPlayer p && WorldFallSafety.owns(p)) {
      p.resetFallDistance();
      ci.setReturnValue(false);
    }
  }
}
