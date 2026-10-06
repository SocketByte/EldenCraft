package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({BowItem.class, CrossbowItem.class, EnderpearlItem.class})
abstract class WorldRangedUseMixin {
  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void eldencraft$freshUse(
      Level level,
      Player player,
      InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (!WorldProjectiles.permitItem(player)) cir.setReturnValue(InteractionResult.FAIL);
  }
}
