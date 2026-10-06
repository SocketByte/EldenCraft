package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.WorldDamageAuthority;
import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThrownEnderpearl.class)
abstract class WorldPearlMixin {
  @Unique private WorldProjectiles.PearlAttempt eldencraft$attempt;

  @WrapMethod(method = "onHit")
  private void eldencraft$realPearl(HitResult hit, Operation<Void> original) {
    var pearl = (ThrownEnderpearl) (Object) this;
    if (pearl.level().isClientSide() || !WorldProjectiles.inSharedWorld(pearl)) {
      original.call(hit);
      return;
    }
    eldencraft$attempt = WorldProjectiles.beginPearl(pearl);
    try {
      original.call(hit);
    } finally {
      WorldProjectiles.endPearl(eldencraft$attempt);
      eldencraft$attempt = null;
    }
  }

  @WrapOperation(
      method = "onHit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;"))
  private ServerPlayer eldencraft$acceptedTeleport(
      ServerPlayer player, TeleportTransition transition, Operation<ServerPlayer> original) {
    if (!WorldProjectiles.inSharedWorld(player)) return original.call(player, transition);
    if (!WorldProjectiles.permitPearlTeleport(eldencraft$attempt, player, transition.position()))
      return null;
    var moved = original.call(player, transition);
    WorldProjectiles.teleported(eldencraft$attempt, moved);
    return moved;
  }

  @WrapOperation(
      method = "onHit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerPlayer;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
  private boolean eldencraft$resolvedPearlDamage(
      ServerPlayer player,
      ServerLevel level,
      DamageSource source,
      float damage,
      Operation<Boolean> original) {
    if (!WorldProjectiles.inSharedWorld(player))
      return original.call(player, level, source, damage);
    float before = player.getHealth();
    WorldDamageAuthority.enterPlayerDamage();
    try {
      boolean result = original.call(player, level, source, damage);
      WorldProjectiles.pearlLoss(eldencraft$attempt, Math.max(0, before - player.getHealth()));
      return result;
    } finally {
      if (player.getHealth() <= 0) player.setHealth(.001f);
      WorldDamageAuthority.leavePlayerDamage();
    }
  }
}
