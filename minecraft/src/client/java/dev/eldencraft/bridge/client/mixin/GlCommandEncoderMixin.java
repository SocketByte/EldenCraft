package dev.eldencraft.bridge.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reading back a depth texture sets the read framebuffer's read buffer to GL_NONE and never
 * restores it, so every later colour readback through the same framebuffer fails ("No color
 * buffer"). Vanilla never reads depth back; the frame export does every frame. Put the read buffer
 * back before the framebuffer is released.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMixin {
  private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

  @Inject(
      method =
          "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glFramebufferTexture2D(IIIII)V"))
  private void eldencraft$restoreReadBuffer(
      final GpuTexture source,
      final GpuBuffer destination,
      final long offset,
      final Runnable callback,
      final int mipLevel,
      final int x,
      final int y,
      final int width,
      final int height,
      final CallbackInfo ci) {
    if (source.getFormat().hasDepthAspect()) {
      GlStateManager._glReadBuffer(GL_COLOR_ATTACHMENT0);
    }
  }
}
