package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.TerrainMining;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes block-break actions so hidden Elden Ring terrain can be mined for resources; vanilla
 * handling continues.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class TerrainMiningMixin {
  @Shadow @Final protected ServerPlayer player;

  @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
  private void eldencraft$terrainMining(
      BlockPos pos,
      ServerboundPlayerActionPacket.Action action,
      Direction face,
      int maxBuildHeight,
      int sequence,
      CallbackInfo ci) {
    TerrainMining.onAction(player, pos, action, face);
  }
}
