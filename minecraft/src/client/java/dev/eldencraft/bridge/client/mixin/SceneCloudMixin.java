package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.SceneCapture;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
abstract class SceneCloudMixin {
  @Inject(
      method = {
        "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        "renderOit"
      },
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$noGuestClouds(CallbackInfo ci) {
    if (SceneCapture.active()) ci.cancel();
  }
}
