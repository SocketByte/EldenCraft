package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.HostHealthDisplay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class HealthPacketMixin {
  // TAIL runs only after vanilla's client-thread scheduling guard and health application.
  @Inject(method = "handleSetHealth", at = @At("TAIL"))
  private void eldencraft$genuineHealth(ClientboundSetHealthPacket packet, CallbackInfo ci) {
    HostHealthDisplay.receivedHealth(packet.getHealth());
  }
}
