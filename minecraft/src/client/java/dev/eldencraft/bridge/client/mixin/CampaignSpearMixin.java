package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.CampaignWeapons;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.component.PiercingWeapon;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PiercingWeapon.class)
abstract class CampaignSpearMixin {
  @WrapMethod(method = "attack")
  private void eldencraft$jab(LivingEntity player, EquipmentSlot slot, Operation<Void> original) {
    CampaignWeapons.component(player, slot, false, () -> original.call(player, slot));
  }
}
