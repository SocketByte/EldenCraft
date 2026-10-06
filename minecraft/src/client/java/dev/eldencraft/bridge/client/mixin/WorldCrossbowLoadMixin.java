package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrossbowItem.class)
abstract class WorldCrossbowLoadMixin {
  @Inject(method = "onUseTick", at = @At("HEAD"), cancellable = true)
  private void eldencraft$cancelLostLoad(
      Level level, LivingEntity player, ItemStack item, int remaining, CallbackInfo ci) {
    if (!WorldProjectiles.permitItem(player)) {
      player.stopUsingItem();
      ci.cancel();
    }
  }
}
