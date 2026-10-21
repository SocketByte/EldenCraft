package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.CampaignEnchantments;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(AnvilMenu.class)
abstract class CampaignAnvilMenuMixin extends ItemCombinerMenu {
  private CampaignAnvilMenuMixin(
      MenuType<?> type,
      int id,
      Inventory inventory,
      ContainerLevelAccess access,
      ItemCombinerMenuSlotDefinition slots) {
    super(type, id, inventory, access, slots);
  }

  @Inject(method = "createResult", at = @At("HEAD"))
  private void eldencraft$migrateInputs(CallbackInfo ci) {
    if (!CampaignEnchantments.scoped(player)) return;
    CampaignEnchantments.tune(inputSlots.getItem(0));
    CampaignEnchantments.tune(inputSlots.getItem(1));
  }

  @WrapOperation(
      method = "createResult",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
  private void eldencraft$gateResult(
      ResultContainer slots, int index, ItemStack result, Operation<Void> original) {
    if (CampaignEnchantments.scoped(player)) {
      CampaignEnchantments.tune(result);
      if (!CampaignEnchantments.permitted(player, result)) result = ItemStack.EMPTY;
    }
    original.call(slots, index, result);
  }

  @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
  private void eldencraft$gatePickup(
      Player player, boolean hasStack, CallbackInfoReturnable<Boolean> cir) {
    if (CampaignEnchantments.scoped(player)
        && !CampaignEnchantments.permitted(player, resultSlots.getItem(0)))
      cir.setReturnValue(false);
  }
}
