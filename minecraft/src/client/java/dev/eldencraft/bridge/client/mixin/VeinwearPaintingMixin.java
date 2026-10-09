package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.VeinwearEasterEgg;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep this granted painting's art when vanilla drops its ordinary painting item. */
@Mixin(Painting.class)
abstract class VeinwearPaintingMixin {
  @WrapOperation(
      method = "dropItem",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/decoration/painting/Painting;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/item/ItemEntity;"))
  private ItemEntity eldencraft$keepVeinwearVariant(
      Painting painting, ServerLevel level, ItemStack stack, Operation<ItemEntity> original) {
    var variant = painting.getVariant();
    if (VeinwearEasterEgg.isVeinwear(variant)) stack.set(DataComponents.PAINTING_VARIANT, variant);
    return original.call(painting, level, stack);
  }

  @ModifyReturnValue(method = "getPickResult", at = @At("RETURN"))
  private ItemStack eldencraft$pickVeinwearVariant(ItemStack stack) {
    var variant = ((Painting) (Object) this).getVariant();
    if (VeinwearEasterEgg.isVeinwear(variant)) stack.set(DataComponents.PAINTING_VARIANT, variant);
    return stack;
  }
}
