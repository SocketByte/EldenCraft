package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.BlockWork;
import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CampaignPunchMixin {
  @WrapOperation(
      method = "handlePunch",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
  private boolean eldencraft$staminaMiss(
      ServerPlayer player,
      InteractionHand hand,
      SwingAnimation animation,
      boolean force,
      Operation<Boolean> original) {
    // Mining and placing are free: let the swing through without charging it.
    if (BlockWork.active(player)) return original.call(player, hand, animation, force);
    boolean charged = CampaignCombat.consumeAttackPunch(player);
    if (!charged && !CampaignCombat.punch(player)) return false;
    boolean accepted = original.call(player, hand, animation, force);
    if (!charged) CampaignCombat.punched(player, accepted);
    return accepted;
  }
}
