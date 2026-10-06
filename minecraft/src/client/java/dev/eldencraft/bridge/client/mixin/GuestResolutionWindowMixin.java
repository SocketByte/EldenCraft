package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.Window;
import dev.eldencraft.bridge.client.GuestResolution;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The size Minecraft renders at follows the host back buffer while GuestResolution is active.
 * GameRenderer resizes its main target from these getters every frame; the window surface uses
 * Window.queryFramebufferSize and screen-space input uses getScreenWidth, both unchanged.
 */
@Mixin(Window.class)
abstract class GuestResolutionWindowMixin {
  @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
  private void eldencraft$hostWidth(CallbackInfoReturnable<Integer> cir) {
    if (GuestResolution.active()) cir.setReturnValue(GuestResolution.width());
  }

  @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
  private void eldencraft$hostHeight(CallbackInfoReturnable<Integer> cir) {
    if (GuestResolution.active()) cir.setReturnValue(GuestResolution.height());
  }

  // Automatic GUI scale is computed from the framebuffer fields directly.
  @ModifyExpressionValue(
      method = "calculateScale",
      at =
          @At(
              value = "FIELD",
              target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I",
              opcode = Opcodes.GETFIELD))
  private int eldencraft$scaleWidth(int original) {
    return GuestResolution.active() ? GuestResolution.width() : original;
  }

  @ModifyExpressionValue(
      method = "calculateScale",
      at =
          @At(
              value = "FIELD",
              target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I",
              opcode = Opcodes.GETFIELD))
  private int eldencraft$scaleHeight(int original) {
    return GuestResolution.active() ? GuestResolution.height() : original;
  }

  // The GUI-scaled size (HUD layout, crosshair, screens) is also computed from the fields. Left
  // at the window size, the GUI was laid out for the window but projected over the host-sized
  // target, so it shrank into the top-left corner whenever the two sizes differed.
  @ModifyExpressionValue(
      method = "setGuiScale",
      at =
          @At(
              value = "FIELD",
              target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I",
              opcode = Opcodes.GETFIELD))
  private int eldencraft$guiWidth(int original) {
    return GuestResolution.active() ? GuestResolution.width() : original;
  }

  @ModifyExpressionValue(
      method = "setGuiScale",
      at =
          @At(
              value = "FIELD",
              target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I",
              opcode = Opcodes.GETFIELD))
  private int eldencraft$guiHeight(int original) {
    return GuestResolution.active() ? GuestResolution.height() : original;
  }
}
