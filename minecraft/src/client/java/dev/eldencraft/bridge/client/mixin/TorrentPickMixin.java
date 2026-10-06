package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldTorrent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The rider's crosshair, sweeps and own projectiles pass through Torrent. */
@Mixin(LivingEntity.class)
abstract class TorrentPickMixin {
  @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
  private void eldencraft$unpickableTorrent(CallbackInfoReturnable<Boolean> cir) {
    if (WorldTorrent.is((LivingEntity) (Object) this)) cir.setReturnValue(false);
  }
}
