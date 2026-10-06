package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.SharedWorldBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nothing leaves the shared world through a portal: the Nether comes out of it instead
 * (WorldNether). A real dimension change would detach the bridged player from Elden Ring.
 */
@Mixin(Entity.class)
abstract class NetherTravelMixin {
  @Inject(
      method =
          "setAsInsidePortal(Lnet/minecraft/world/level/block/Portal;Lnet/minecraft/core/BlockPos;)V",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$noTravel(Portal portal, BlockPos pos, CallbackInfo ci) {
    var level = ((Entity) (Object) this).level();
    if (!level.isClientSide() && level.dimension().equals(SharedWorldBlocks.DIMENSION)) ci.cancel();
  }
}
