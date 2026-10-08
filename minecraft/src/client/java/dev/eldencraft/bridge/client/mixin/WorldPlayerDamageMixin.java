package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.WorldDamageAuthority;
import dev.eldencraft.bridge.client.WorldStartupSafety;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class WorldPlayerDamageMixin {
  @WrapMethod(method = "hurtServer")
  private boolean eldencraft$actualMobDamage(
      ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
    var player = (ServerPlayer) (Object) this;
    Object token = WorldDamageAuthority.begin(level, player, source);
    if (token == null) token = WorldDamageAuthority.environment(level, player, source);
    if (token == null)
      return WorldStartupSafety.protects(player) ? false : original.call(level, source, amount);
    float before = player.getHealth();
    WorldDamageAuthority.enterPlayerDamage();
    try {
      boolean accepted = original.call(level, source, amount);
      if (accepted) WorldDamageAuthority.finish(token, player, before - player.getHealth());
      return accepted;
    } finally {
      if (player.getHealth() <= 0) player.setHealth(.001f);
      WorldDamageAuthority.leavePlayerDamage();
    }
  }

  @Inject(method = "die", at = @At("HEAD"), cancellable = true)
  private void eldencraft$nativeOwnsDeath(DamageSource source, CallbackInfo ci) {
    if (WorldDamageAuthority.playerDamage()) ci.cancel();
  }
}
