package dev.eldencraft.bridge.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.eldencraft.bridge.client.BlockMeshClient;
import dev.eldencraft.bridge.client.FrameExporter;
import dev.eldencraft.bridge.client.HostAvatarRenderer;
import dev.eldencraft.bridge.client.HostController;
import dev.eldencraft.bridge.client.ProxyDebugRenderer;
import dev.eldencraft.bridge.client.ProxyFeedback;
import dev.eldencraft.bridge.client.SceneCapture;
import dev.eldencraft.bridge.client.WorldFocus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Split the real Minecraft world and the real hand/HUD without replacing either renderer. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
  @Shadow @Final private RenderTarget mainRenderTarget;

  @Inject(method = "renderLevel", at = @At("HEAD"))
  private void eldencraft$resetBlockHandoff(CallbackInfo ci) {
    SceneCapture.beginWorld();
    BlockMeshClient.resetFrame();
  }

  @Inject(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
  private void eldencraft$captureWorld(CallbackInfo ci) {
    if (SceneCapture.active()) ProxyFeedback.render(mainRenderTarget);
    FrameExporter.captureWorld(mainRenderTarget);
    SceneCapture.endWorld();
    HostAvatarRenderer.render(mainRenderTarget);
    if (!SceneCapture.active()) ProxyFeedback.render(mainRenderTarget);
    ProxyDebugRenderer.render(mainRenderTarget);
  }

  @Inject(method = "renderLevel", at = @At("TAIL"))
  private void eldencraft$finishWorld(CallbackInfo ci) {
    SceneCapture.endWorld();
  }

  @ModifyArg(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
      index = 0)
  private Matrix4f eldencraft$actualSceneProjection(Matrix4f projection) {
    SceneCapture.projection(projection);
    return projection;
  }

  // Screen input before this frame's GUI renders, so hover and drags use the newest host cursor.
  @Inject(method = "render", at = @At("HEAD"))
  private void eldencraft$screenInput(CallbackInfo ci) {
    SceneCapture.endWorld();
    Minecraft client = Minecraft.getInstance();
    WorldFocus.tick(client);
    HostController.frame(client);
  }

  @Inject(method = "render", at = @At("TAIL"))
  private void eldencraft$captureOverlay(CallbackInfo ci) {
    FrameExporter.captureOverlay(mainRenderTarget);
  }
}
