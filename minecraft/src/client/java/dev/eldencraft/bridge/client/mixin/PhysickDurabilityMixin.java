package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignPhysick;
import java.util.function.Consumer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cerulean Hidden Tear: equipment takes no wear while it lasts. */
@Mixin(ItemStack.class)
abstract class PhysickDurabilityMixin {
  @Inject(
      method =
          "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$freeDurability(
      int amount, ServerLevel level, ServerPlayer player, Consumer<?> broken, CallbackInfo ci) {
    if (player != null && CampaignPhysick.freeResources(player)) ci.cancel();
  }
}
