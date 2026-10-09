package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.GoldenAppleAuthority;
import dev.eldencraft.bridge.client.CampaignRegeneration;
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
    var effect = (MobEffectInstance) (Object) this;
    var player =
        entity instanceof ServerPlayer p && effect.getEffect().equals(MobEffects.REGENERATION)
            ? p
            : null;
    // An apple's own receipts already carry its healing; never count it twice.
    boolean apple = player != null && GoldenAppleAuthority.owns(player, effect);
    float before = entity.getHealth();
    boolean result = original.call(type, level, entity, amplifier);
    if (result && player != null) {
      GoldenAppleAuthority.regeneration(player, effect);
      if (!apple) CampaignRegeneration.regeneration(player, effect, player.getHealth() - before);
    }
    return result;
  }
}
