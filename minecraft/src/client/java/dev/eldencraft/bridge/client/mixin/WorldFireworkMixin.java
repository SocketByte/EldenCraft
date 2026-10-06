package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldFlight;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireworkRocketItem.class)
abstract class WorldFireworkMixin {
  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void eldencraft$ownedBoost(
      Level level,
      Player player,
      InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (!WorldFlight.permitRocket(player)) cir.setReturnValue(InteractionResult.FAIL);
  }
}
