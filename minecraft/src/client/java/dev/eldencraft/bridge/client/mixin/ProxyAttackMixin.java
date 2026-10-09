package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.CampaignWeapons;
import java.util.List;
import net.minecraft.core.particles.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class ProxyAttackMixin {
  @Inject(method = "stabAttack", at = @At("HEAD"), cancellable = true)
  private void eldencraft$stab(
      EquipmentSlot slot,
      Entity target,
      float damage,
      boolean damages,
      boolean knockback,
      boolean dismount,
      CallbackInfoReturnable<Boolean> cir) {
    if ((Object) this instanceof ServerPlayer player
        && !CampaignWeapons.permitStab(player, target, damages)) cir.setReturnValue(false);
  }

  @Inject(method = "playServerSideSound", at = @At("HEAD"), cancellable = true)
  private void eldencraft$relocateSound(SoundEvent sound, CallbackInfo ci) {
    if (ProxyCombatAuthority.sound(sound)) ci.cancel();
  }

  @WrapOperation(
      method = {"doSweepAttack", "damageStatsAndHearts"},
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
  private int eldencraft$relocateParticles(
      ServerLevel level,
      ParticleOptions type,
      double x,
      double y,
      double z,
      int count,
      double dx,
      double dy,
      double dz,
      double speed,
      Operation<Integer> original) {
    if (ProxyCombatAuthority.particles(type, x, y, z, count, dx, dy, dz, speed)) return 1;
    return original.call(level, type, x, y, z, count, dx, dy, dz, speed);
  }

  @WrapOperation(
      method = "attackVisualEffects",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;crit(Lnet/minecraft/world/entity/Entity;)V"))
  private void eldencraft$relocateCrit(Player player, Entity target, Operation<Void> original) {
    if (!ProxyCombatAuthority.animation(target, ParticleTypes.CRIT)) original.call(player, target);
  }

  @WrapOperation(
      method = "attackVisualEffects",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;magicCrit(Lnet/minecraft/world/entity/Entity;)V"))
  private void eldencraft$relocateMagic(Player player, Entity target, Operation<Void> original) {
    if (!ProxyCombatAuthority.animation(target, ParticleTypes.ENCHANTED_HIT))
      original.call(player, target);
  }

  @WrapMethod(method = "attack")
  private void eldencraft$vanillaAttack(Entity target, Operation<Void> original) {
    if (!((Object) this instanceof ServerPlayer player)
        || !(target instanceof CombatProxyEntity proxy)) {
      original.call(target);
      return;
    }
    if (!ProxyCombatAuthority.begin(player, proxy)) return;
    var weapon = player.getMainHandItem();
    String id = BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString();
    int before = weapon.getDamageValue(), maximum = weapon.getMaxDamage();
    boolean completed = false;
    try {
      original.call(target);
      completed = true;
    } finally {
      ProxyCombatAuthority.finish(
          id, before, weapon.isEmpty() ? maximum : weapon.getDamageValue(), completed);
    }
  }

  @WrapOperation(
      method = "doSweepAttack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
  private List<?> eldencraft$proxySweep(
      Level level, Class<?> type, AABB box, Operation<List<?>> original) {
    List<?> entities = original.call(level, type, box);
    return ProxyCombatAuthority.proxyAttack()
            && !dev.eldencraft.bridge.client.SharedWorldClient.serverActive()
        ? entities.stream().filter(CombatProxyEntity.class::isInstance).toList()
        : entities;
  }
}
