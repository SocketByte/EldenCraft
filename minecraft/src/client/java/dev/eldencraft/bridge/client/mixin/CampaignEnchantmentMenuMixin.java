package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignEnchantments;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(EnchantmentMenu.class)
abstract class CampaignEnchantmentMenuMixin {
  @Shadow @Final private RandomSource random;
  @Shadow @Final private DataSlot enchantmentSeed;
  @Unique private Player eldencraft$owner;

  @Inject(
      method =
          "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
      at = @At("RETURN"))
  private void eldencraft$owner(
      int id, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
    eldencraft$owner = inventory.player;
  }

  @Inject(method = "getEnchantmentList", at = @At("HEAD"), cancellable = true)
  private void eldencraft$campaignEnchantments(
      RegistryAccess registries,
      ItemStack stack,
      int slot,
      int cost,
      CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
    if (!CampaignEnchantments.scoped(eldencraft$owner)) return;
    CampaignEnchantments.tune(stack);
    random.setSeed((long) (enchantmentSeed.get() + slot));
    cir.setReturnValue(
        CampaignEnchantments.table(eldencraft$owner, registries, stack, cost, random));
  }
}
