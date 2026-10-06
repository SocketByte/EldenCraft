package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.SharedWorldBlocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla lights nether portals only in the overworld and the Nether; the shared world is Elden
 * Ring's overworld.
 */
@Mixin(BaseFireBlock.class)
abstract class NetherPortalDimensionMixin {
  @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$sharedWorldPortals(
      Level level, CallbackInfoReturnable<Boolean> cir) {
    if (level.dimension().equals(SharedWorldBlocks.DIMENSION)) cir.setReturnValue(true);
  }
}
