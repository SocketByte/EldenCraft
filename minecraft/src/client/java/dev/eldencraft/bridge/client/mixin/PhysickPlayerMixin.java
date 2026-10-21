package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.CampaignPhysick;
import dev.eldencraft.bridge.client.CampaignShields;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spiked, Thorny and Stonebarb on vanilla melee, a shield's Riposte on melee and spear attacks, and
 * Cerulean Hidden's free hunger.
 */
@Mixin(Player.class)
abstract class PhysickPlayerMixin {
  @Unique private boolean eldencraft$critical;

  @Inject(method = "attack", at = @At("HEAD"))
  private void eldencraft$resetCritical(Entity target, CallbackInfo ci) {
    eldencraft$critical = false;
  }

  @WrapOperation(
      method = "attack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;canCriticalAttack(Lnet/minecraft/world/entity/Entity;)Z"))
  private boolean eldencraft$physickCritical(
      Player player, Entity target, Operation<Boolean> original) {
    boolean critical = original.call(player, target);
    eldencraft$critical = critical;
    return critical;
  }

  @WrapOperation(
      method = "attack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
  private boolean eldencraft$physickMelee(
      Entity target, DamageSource source, float damage, Operation<Boolean> original) {
    if (!((Object) this instanceof ServerPlayer player))
      return original.call(target, source, damage);
    boolean blocking = target instanceof LivingEntity living && living.isBlocking();
    float scaled = CampaignPhysick.melee(player, damage, eldencraft$critical);
    boolean hit = original.call(target, source, scaled * (float) CampaignShields.riposte(player));
    CampaignPhysick.struck(player, target, blocking);
    return hit;
  }

  /** A spear's jab or charge is its own attack path; Riposte empowers it too. */
  @WrapOperation(
      method = "stabAttack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
  private boolean eldencraft$riposteStab(
      Entity target, DamageSource source, float damage, Operation<Boolean> original) {
    if (!((Object) this instanceof ServerPlayer player))
      return original.call(target, source, damage);
    return original.call(target, source, damage * (float) CampaignShields.riposte(player));
  }

  @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
  private void eldencraft$freeHunger(float amount, CallbackInfo ci) {
    if (CampaignPhysick.freeResources((Player) (Object) this)) ci.cancel();
  }
}
