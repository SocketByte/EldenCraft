package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.SceneCapture;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
abstract class SceneWeatherMixin {
  @Inject(
      method = {
        "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        "renderOit"
      },
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$noGuestWeather(CallbackInfo ci) {
    if (SceneCapture.active()) ci.cancel();
  }
}
