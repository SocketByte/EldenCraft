package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.SharedWorldClient;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class WorldMovementPacketMixin {
  @Shadow public ServerPlayer player;

  @ModifyVariable(method = "handleMovePlayer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
  private ServerboundMovePlayerPacket eldencraft$hostOwnsPosition(
      ServerboundMovePlayerPacket packet) {
    // The first invocation can be on the connection thread. Let vanilla
    // schedule that packet; only its server-thread invocation reads state.
    if (!player.level().getServer().isSameThread() || !SharedWorldClient.controlsPlayer(player))
      return packet;
    // Keep ordinary rotation handling and the rest of vanilla's packet
    // lifecycle. Its delayed client position cannot displace the host pose.
    return new ServerboundMovePlayerPacket.PosRot(
        player.position(),
        packet.getYRot(player.getYRot()),
        packet.getXRot(player.getXRot()),
        player.onGround(),
        false);
  }
}
