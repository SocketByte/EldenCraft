package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.client.HostAvatarRenderer;
import dev.eldencraft.bridge.client.SceneCapture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the dedicated simulation's vanilla geometry, but not its sky background. */
@Mixin(LevelRenderer.class)
abstract class SceneLevelRendererMixin {
  @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
  private Vector4f eldencraft$transparentBackground(Vector4f original) {
    return SceneCapture.active() ? new Vector4f(0.0f, 0.0f, 0.0f, 0.0f) : original;
  }

  @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
  private void eldencraft$noGuestSky(CallbackInfo ci) {
    if (SceneCapture.active()) ci.cancel();
  }

  @WrapWithCondition(
      method = "submitEntities",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
  private boolean eldencraft$separateLocalAvatar(
      EntityRenderDispatcher dispatcher,
      EntityRenderState state,
      CameraRenderState camera,
      double x,
      double y,
      double z,
      PoseStack poses,
      SubmitNodeCollector nodes) {
    return !HostAvatarRenderer.belongsToIsolatedLayer(state);
  }
}
