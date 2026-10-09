package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.CampaignMotion;
import dev.eldencraft.bridge.client.CampaignWeapons;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KineticWeapon.class)
abstract class CampaignKineticMixin {
  @Inject(method = "getMotion", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$hostSpeed(Entity entity, CallbackInfoReturnable<Vec3> cir) {
    if (entity instanceof ServerPlayer player && CampaignMotion.owns(player))
      cir.setReturnValue(CampaignMotion.velocity(player).scale(20));
  }

  @WrapMethod(method = "damageEntities")
  private void eldencraft$charge(
      ItemStack stack,
      int ticks,
      LivingEntity player,
      EquipmentSlot slot,
      Operation<Void> original) {
    CampaignWeapons.component(player, slot, true, () -> original.call(stack, ticks, player, slot));
  }
}
