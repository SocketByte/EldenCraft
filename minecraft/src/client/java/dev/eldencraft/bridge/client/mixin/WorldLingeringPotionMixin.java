package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.WorldPotions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThrownLingeringPotion.class)
abstract class WorldLingeringPotionMixin {
  @WrapOperation(
      method = "onHitAsPotion",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
  private boolean eldencraft$recordActualCloud(
      ServerLevel level, Entity entity, Operation<Boolean> original) {
    boolean added = original.call(level, entity);
    if (added) WorldPotions.created((ThrownLingeringPotion) (Object) this, entity);
    return added;
  }
}
