package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.GoldenAppleAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Consumable.class)
abstract class GoldenAppleConsumeMixin {
  @WrapMethod(method = "onConsume")
  private ItemStack eldencraft$consumedApple(
      Level level, LivingEntity entity, ItemStack stack, Operation<ItemStack> original) {
    Object token =
        entity instanceof ServerPlayer player
            ? GoldenAppleAuthority.begin(player, stack, (Consumable) (Object) this)
            : null;
    ItemStack result = original.call(level, entity, stack);
    if (token != null) GoldenAppleAuthority.consumed(token);
    return result;
  }
}
