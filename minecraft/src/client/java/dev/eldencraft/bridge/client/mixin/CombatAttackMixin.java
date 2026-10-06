package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.CampaignCombat;
import dev.eldencraft.bridge.client.CombatPublisher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class CombatAttackMixin {
  // Vanilla resets attack strength before swing (including a miss), so capture the pre-attempt
  // charge.
  @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
  private void eldencraft$attempt(CallbackInfoReturnable<Boolean> ci) {
    var client = (Minecraft) (Object) this;
    if (client.hitResult != null
        && client.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
        && !CampaignCombat.canAttackClient(client.player)) {
      ci.setReturnValue(false);
      return;
    }
    CombatPublisher.beginAttack();
  }

  @WrapOperation(
      method = "startAttack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
  private boolean eldencraft$accepted(
      LocalPlayer player,
      InteractionHand hand,
      SwingAnimation animation,
      boolean force,
      Operation<Boolean> original) {
    boolean accepted = original.call(player, hand, animation, force);
    CombatPublisher.completedSwing(accepted);
    return accepted;
  }
}
