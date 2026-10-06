package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.eldencraft.bridge.client.HostPick;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
abstract class HostPickRangeMixin {
  // Camera orbit distance affects search only; HostPick rechecks real player
  // reach and visibility after the unmodified vanilla selection algorithms.
  @ModifyExpressionValue(
      method = "raycastHitResult",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/player/LocalPlayer;blockInteractionRange()D"))
  private double eldencraft$cameraBlockSearch(double value) {
    return HostPick.range(value);
  }

  @ModifyExpressionValue(
      method = "raycastHitResult",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/player/LocalPlayer;entityInteractionRange()D"))
  private double eldencraft$cameraEntitySearch(double value) {
    return HostPick.range(value);
  }
}
