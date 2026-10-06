package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.SharedWorldBlocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A frame built on sloped Elden Ring ground encloses cells of the invisible terrain cache. They are
 * not a player's blocks, so the portal fills them like air (the cache never overwrites it).
 */
@Mixin(PortalShape.class)
abstract class NetherPortalShapeMixin {
  @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
  private static void eldencraft$terrainIsEmpty(
      BlockState state, CallbackInfoReturnable<Boolean> cir) {
    if (state.is(SharedWorldBlocks.TERRAIN)) cir.setReturnValue(true);
  }
}
