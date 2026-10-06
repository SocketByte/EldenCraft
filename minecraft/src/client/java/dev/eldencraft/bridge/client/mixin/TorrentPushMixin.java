package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldTorrent;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Torrent follows the host feet only; entity pushing never moves it. */
@Mixin(AbstractHorse.class)
abstract class TorrentPushMixin {
  @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
  private void eldencraft$unpushableTorrent(CallbackInfoReturnable<Boolean> cir) {
    if (WorldTorrent.is((AbstractHorse) (Object) this)) cir.setReturnValue(false);
  }
}
