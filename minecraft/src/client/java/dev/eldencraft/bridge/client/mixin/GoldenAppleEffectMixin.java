package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.GoldenAppleAuthority;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MobEffectInstance.class)
abstract class GoldenAppleEffectMixin {
  @WrapOperation(
      method = "tickServer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/effect/MobEffect;applyEffectTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;I)Z"))
  private boolean eldencraft$serverRegeneration(
      MobEffect type,
      ServerLevel level,
      LivingEntity entity,
      int amplifier,
      Operation<Boolean> original) {
    boolean result = original.call(type, level, entity, amplifier);
    var effect = (MobEffectInstance) (Object) this;
    if (result
        && entity instanceof ServerPlayer player
        && effect.getEffect().equals(MobEffects.REGENERATION))
      GoldenAppleAuthority.regeneration(player, effect);
    return result;
  }
}
