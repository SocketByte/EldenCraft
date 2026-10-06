package dev.eldencraft.bridge.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.eldencraft.bridge.FramePipeline;
import dev.eldencraft.bridge.client.FrameExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Host play is real activity even though the guest window receives no native mouse events. */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitMixin {
  @Shadow private int framerateLimit;

  @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
  private void eldencraft$activeHost(
      CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
    if (FrameExporter.activeHost())
      cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
  }

  @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
  private void eldencraft$boundedHostRate(CallbackInfoReturnable<Integer> cir) {
    if (FrameExporter.activeHost()) cir.setReturnValue(FramePipeline.hostLimit(framerateLimit));
  }
}
