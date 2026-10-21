package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.CampaignPhysick;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cerulean Hidden Tear: bows and crossbows draw arrows as creative players do. */
@Mixin(ProjectileWeaponItem.class)
abstract class PhysickAmmoMixin {
  @WrapOperation(
      method = "useAmmo",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/entity/LivingEntity;hasInfiniteMaterials()Z"))
  private static boolean eldencraft$freeAmmo(LivingEntity shooter, Operation<Boolean> original) {
    return original.call(shooter) || CampaignPhysick.freeResources(shooter);
  }
}
