package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.client.CampaignCombat;
import dev.eldencraft.bridge.client.WorldProjectiles;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
abstract class WorldProjectileMixin {
  @Inject(
      method =
          "spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)Lnet/minecraft/world/entity/projectile/Projectile;",
      at = @At("RETURN"))
  private static <T extends Projectile> void eldencraft$vanillaLaunch(
      T projectile,
      ServerLevel level,
      ItemStack stack,
      Consumer<T> setup,
      CallbackInfoReturnable<T> cir) {
    WorldProjectiles.launched(cir.getReturnValue(), level, stack);
    if (projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow
        && projectile.getOwner() instanceof net.minecraft.world.entity.player.Player player
        && CampaignCombat.active(player)
        && arrow.getWeaponItem() != null) {
      var config = CampaignConfig.current();
      double target =
          arrow.getWeaponItem().is(net.minecraft.world.item.Items.CROSSBOW)
              ? config.crossbowBaseDamage
              : arrow.getWeaponItem().is(net.minecraft.world.item.Items.BOW)
                  ? config.bowBaseDamage
                  : 2;
      // Retune the vanilla base before velocity/critical calculation, preserving any later
      // vanilla enchantment additions rather than multiplying the complete hit.
      arrow.setBaseDamage(
          ((CampaignArrowAccessor) arrow).eldencraft$campaignBaseDamage() + target - 2);
    }
  }

  @WrapMethod(method = "hitTargetOrDeflectSelf")
  private ProjectileDeflection eldencraft$exactImpact(
      HitResult hit, Operation<ProjectileDeflection> original) {
    var previous = WorldProjectiles.enterImpact((Projectile) (Object) this, hit);
    try {
      return original.call(hit);
    } finally {
      WorldProjectiles.leaveImpact(previous);
    }
  }
}
