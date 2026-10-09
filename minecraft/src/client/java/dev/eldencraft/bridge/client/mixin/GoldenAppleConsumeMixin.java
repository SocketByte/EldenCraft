package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.GoldenAppleAuthority;
import dev.eldencraft.bridge.client.CampaignRegeneration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Consumable.class)
abstract class GoldenAppleConsumeMixin {
  @WrapMethod(method = "onConsume")
  private ItemStack eldencraft$consumedApple(
      Level level, LivingEntity entity, ItemStack stack, Operation<ItemStack> original) {
    var consumable = (Consumable) (Object) this;
    var player = entity instanceof ServerPlayer p ? p : null;
    Object token = player != null ? GoldenAppleAuthority.begin(player, stack, consumable) : null;
    // The stack may be empty once eaten, so identify the enchanted apple first.
    boolean enchanted =
        player != null
            && stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            && Consumables.ENCHANTED_GOLDEN_APPLE.equals(consumable);
    ItemStack result = original.call(level, entity, stack);
    if (token != null) GoldenAppleAuthority.consumed(token);
    if (enchanted) CampaignRegeneration.granted(player);
    return result;
  }
}
