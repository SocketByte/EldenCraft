package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.BlockWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records mining and placing so their swings are not charged as attacks. */
@Mixin(ServerPlayerGameMode.class)
abstract class BlockWorkMixin {
  @Shadow @Final protected ServerPlayer player;

  @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
  private void eldencraft$mining(
      BlockPos pos,
      ServerboundPlayerActionPacket.Action action,
      Direction face,
      int maxBuildHeight,
      int sequence,
      CallbackInfo ci) {
    BlockWork.touched(player);
  }

  @Inject(method = "useItemOn", at = @At("HEAD"))
  private void eldencraft$placing(
      ServerPlayer user,
      Level level,
      ItemStack stack,
      InteractionHand hand,
      BlockHitResult hit,
      CallbackInfoReturnable<?> cir) {
    BlockWork.touched(user);
  }
}
