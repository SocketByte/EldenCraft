package dev.eldencraft.bridge.client;

import net.minecraft.client.Minecraft;

/**
 * Renders Minecraft at the host's back-buffer size instead of its own window size, so the
 * composited layer is as sharp as Elden Ring around it. Only the size Minecraft renders at changes
 * (Window.getWidth/getHeight and GUI scale, see GuestResolutionWindowMixin); the real window, its
 * surface and screen-space cursor mapping are untouched. Active only while the host runs and shared
 * GPU textures are usable (they carry frames above the 1080p shared-memory planes). {@code
 * ELDENCRAFT_GUEST_RESOLUTION=window} keeps the window size.
 */
public final class GuestResolution {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_frame");
  private static volatile int width, height;
  private static final boolean DISABLED =
      "window".equalsIgnoreCase(System.getenv("ELDENCRAFT_GUEST_RESOLUTION"));

  private GuestResolution() {}

  public static boolean active() {
    return width > 0 && height > 0;
  }

  public static int width() {
    return width;
  }

  public static int height() {
    return height;
  }

  /** Pure policy: accept a host size only within the shared-texture bounds and a usable minimum. */
  static boolean acceptable(int w, int h) {
    return w >= 640 && h >= 360 && w <= 3840 && h <= 2160;
  }

  /** Client tick, render thread. */
  public static void tick(Minecraft client) {
    int w = 0, h = 0;
    if (!DISABLED && FrameExporter.activeHost() && !GpuTransport.failed()) {
      int[] preferred = GpuTransport.preferredSize();
      if (preferred != null && acceptable(preferred[0], preferred[1])) {
        w = preferred[0];
        h = preferred[1];
      }
    }
    if (w == width && h == height) return;
    width = w;
    height = h;
    LOG.info(
        w > 0 ? "Rendering at the host resolution {}x{}" : "Rendering at the window size again{}{}",
        w > 0 ? w : "",
        h > 0 ? h : "");
    client.resizeGui();
  }
}
