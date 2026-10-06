package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import dev.eldencraft.bridge.FramePipeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Hands Minecraft's frame to the host through the shared memory "Local\EldenCraftFrame".
 *
 * <pre>
 * header (4096 bytes, little-endian)
 *   0 int magic "MCPT"   4 int version (1)   8 int header bytes (4096)   12 int slot count (3)
 *   16 long slot stride   24 int max width   28 int max height
 *   32 long publish counter (bumped after each completed slot)   40 int latest slot (-1: none yet)   44 int Minecraft pid
 *   256 + 128 * i: slot i
 *     +0 long seq (odd while being written)   +8 long Minecraft frame   +16 long host frame
 *     +24 int width   +28 int height   +32 float near   +36 float far   +40 float vertical fov (degrees)
 *     +44 int flags: 1 = depth in [0, 1], 2 = rows bottom-up, 4 = reversed Z, 8 = overlay only, 16 = coherent shared scene, 32 = isolated F5 avatar
 *     +48 double camera x, +56 y, +64 z   +72 float yaw   +76 pitch   +80 roll   +84 int first person
 *     +88 long capture time (System.nanoTime)   +96 long publish time
 *     +104 int GUI open (0 or 1; backward-compatible reserved-field extension)
 *     +112 int GPU texture generation, +116 int GPU texture set (flag 64 only, otherwise zero)
 *   1024 + 512 * i: scene extension (flag 16 only), protected by the same slot seqlock; see compositor/include/scene_protocol.hpp
 * slot i data at 4096 + i * stride, each layer width * height * 4 bytes:
 *   world RGBA8, world depth float32, hand/HUD RGBA8, optional avatar RGBA8 and avatar depth float32.
 *   Colours are premultiplied. This producer reserves five-plane stride; legacy three-plane readers must reject it.
 * </pre>
 *
 * The world layer is copied just before the hand is drawn; the colour target is then cleared so
 * what follows (hand, screen effects, GUI) forms the overlay layer, copied at the end of the frame.
 * Readback is asynchronous: a ring of GPU buffers, published only after GPU completion. Late or
 * out-of-order completions are discarded.
 *
 * <p>When the host offers shared textures ({@link GpuTransport}), the same planes are copied GPU to
 * GPU into a free shared set instead and the slot carries flag 64 with no pixels; readback remains
 * the fallback.
 */
public final class FrameExporter {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_frame");
  private static boolean enabled, captureWorld;
  private static long hostCheckNanos;
  private static boolean hostActive, reportedHostActive;

  private record Pose(
      long hostFrame,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float roll,
      float fov,
      boolean firstPerson) {}

  public static void enable() {
    enabled = !"0".equals(System.getenv("ELDENCRAFT_FRAME_EXPORT"));
    captureWorld = "1".equals(System.getenv("ELDENCRAFT_CAPTURE_WORLD"));
  }

  public static boolean enabled() {
    return enabled;
  }

  public static boolean activeHost() {
    if (!enabled) return false;
    long now = System.nanoTime();
    if (now - hostCheckNanos >= 10_000_000L) {
      hostCheckNanos = now;
      hostActive = HostController.damageSnapshot(Minecraft.getInstance()) != null;
      if (hostActive != reportedHostActive) {
        LOG.info(
            "frame activity: host={}, AFK/iconified override={}, capture={}",
            hostActive,
            hostActive,
            captureWorld ? "world+overlay" : "overlay only");
        reportedHostActive = hostActive;
      }
    }
    return hostActive;
  }

  private static Pose guestPose() {
    Minecraft client = Minecraft.getInstance();
    var server = client.getSingleplayerServer();
    if (!enabled
        || client.level == null
        || client.player == null
        || !client.hasSingleplayerServer()
        || server == null
        || server.isPublished()) return null;
    var camera = client.gameRenderer.mainCamera();
    if (!camera.isInitialized()) return null;
    var p = camera.position();
    var host = HostController.frame();
    return new Pose(
        host == null ? 0 : host.hostFrame(),
        p.x,
        p.y,
        p.z,
        camera.yRot(),
        camera.xRot(),
        0,
        camera.getFov(),
        !camera.isDetached());
  }

  public static final String NAME = "Local\\EldenCraftFrame";
  private static final int MAGIC = 0x5450434D;
  private static final int VERSION = 1;
  private static final int HEADER = 4096;
  private static final int SLOTS = 3;
  private static final int SLOT_DESC = 256;
  private static final int SLOT_DESC_BYTES = 128;
  private static final int MAX_W = 1920;
  private static final int MAX_H = 1080;
  private static final long LAYER_MAX = (long) MAX_W * MAX_H * 4;
  private static final long STRIDE = LAYER_MAX * 5;
  private static final int RING = 3;
  private static final Vector4f TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
  private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
  private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;
  private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
  private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED;

  /** Near/far planes of the frame being rendered (the camera sets far each frame). */
  private static final float NEAR = 0.05F;

  private static float far = 1024.0F;

  private static SharedMemory shm;
  private static boolean failed;
  private static boolean warnedSize;
  private static final Capture[] ring = new Capture[RING];
  private static int ringNext;
  private static int slotNext;
  private static long frameCounter;
  private static long publishCounter;
  private static long lastPublishedFrame;
  private static long telemetryStart,
      telemetryCaptures,
      telemetryPublished,
      telemetryBusy,
      telemetryDropped;
  private static long telemetryLatency, telemetryCopy, telemetryGpu;
  private static int telemetryPlanes = 1;
  private static Capture current;

  private FrameExporter() {}

  private static final class Capture {
    GpuBuffer color;
    GpuBuffer depth;
    GpuBuffer overlay;
    GpuBuffer avatarColor, avatarDepth;
    int width;
    int height;
    long generation;
    boolean busy;
    boolean worldReady, avatarReady, aborted;
    long busySince;
    Pose pose;
    float far;
    long frame;
    long captureNanos;
    boolean guiOpen;
    boolean fullWorld;
    SceneCapture.Snapshot scene;
    float[] avatarInverse;
    int avatarMode;
    int viewMode;
    Object level, player;

    /** Shared texture set holding this capture, or -1 for GPU readback buffers. */
    int gpuSet = -1;

    void allocate(final int w, final int h, boolean full) {
      this.free();
      long n = (long) w * h * 4;
      int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
      if (full) {
        this.color =
            RenderSystem.getDevice().createBuffer(() -> "passthrough world colour", usage, n);
        this.depth =
            RenderSystem.getDevice().createBuffer(() -> "passthrough world depth", usage, n);
      }
      this.overlay = RenderSystem.getDevice().createBuffer(() -> "passthrough overlay", usage, n);
      this.width = w;
      this.height = h;
    }

    void free() {
      for (GpuBuffer b :
          new GpuBuffer[] {
            this.color, this.depth, this.overlay, this.avatarColor, this.avatarDepth
          }) {
        if (b != null) {
          b.close();
        }
      }

      this.color = this.depth = this.overlay = this.avatarColor = this.avatarDepth = null;
    }
  }

  public static void setFar(final float depthFar) {
    far = depthFar;
  }

  public static boolean exporting() {
    return shm != null;
  }

  private static boolean ensureShm() {
    if (shm != null) {
      return true;
    }

    if (failed) {
      return false;
    }

    try {
      shm = SharedMemory.create(NAME, HEADER + STRIDE * SLOTS);
      MemorySegment m = shm.segment;
      m.asSlice(0, HEADER).fill((byte) 0);
      m.set(INT, 0, MAGIC);
      m.set(INT, 4, VERSION);
      m.set(INT, 8, HEADER);
      m.set(INT, 12, SLOTS);
      m.set(LONG, 16, STRIDE);
      m.set(INT, 24, MAX_W);
      m.set(INT, 28, MAX_H);
      m.set(INT, 40, -1);
      m.set(INT, 44, (int) ProcessHandle.current().pid());
      LOG.info("frame export: shared memory {} ({} MB)", NAME, (HEADER + STRIDE * SLOTS) >> 20);
      return true;
    } catch (Throwable t) {
      failed = true;
      LOG.error(
          "Frame export disabled: could not create the local Win32 mapping. Check Java native"
              + " access and the dedicated profile.");
      return false;
    }
  }

  /**
   * GameRenderer.renderLevel, just before the 3D HUD (hand): copy the world layer, then clear
   * colour for the overlay.
   */
  public static void captureWorld(final RenderTarget target) {
    if (current != null) abort(current);
    current = null;
    Pose pose = guestPose();
    if (pose == null || !ensureShm()) {
      invalidate();
      return;
    }

    int w = target.width;
    int h = target.height;
    // Above 1080p only shared GPU textures can carry the frame (host resolution mode).
    boolean large = w > MAX_W || h > MAX_H;
    if (w <= 0
        || h <= 0
        || w > GpuTransport.MAX_W
        || h > GpuTransport.MAX_H
        || (large && GpuTransport.failed())) {
      if (!warnedSize) {
        warnedSize = true;
        LOG.warn("frame export: {}x{} is bigger than {}x{}, not exporting", w, h, MAX_W, MAX_H);
      }

      invalidate();
      return;
    }

    long now = System.nanoTime();
    var scene = SceneCapture.snapshot();
    boolean full = captureWorld || scene != null;
    boolean[] busy = new boolean[RING];
    for (int i = 0; i < RING; i++) busy[i] = ring[i] != null && ring[i].busy;
    int chosen = FramePipeline.freeSlot(busy, ringNext);
    if (chosen < 0) {
      telemetryBusy++;
      report(now);
      return;
    }
    ringNext = chosen;
    Capture c = ring[chosen];
    if (c == null) {
      c = ring[chosen] = new Capture();
    }
    c.gpuSet = GpuTransport.acquire(w, h, full);
    if (c.gpuSet < 0 && large) {
      telemetryBusy++;
      report(now);
      return;
    } // Waiting for host textures of this size.
    if (c.gpuSet >= 0
        && !(GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 0)
            && (!full || GpuTransport.copyDepth(target.getDepthTexture(), c.gpuSet, 1)))) {
      GpuTransport.discard(c.gpuSet);
      c.gpuSet = -1;
    }
    if (c.gpuSet >= 0) {
      // Readback buffers of another size must not survive into a later fallback frame.
      if (c.overlay != null && (c.width != w || c.height != h)) c.free();
      c.width = w;
      c.height = h;
    } else if (c.width != w || c.height != h || c.overlay == null || (full && c.color == null)) {
      c.allocate(w, h, full);
    }

    c.generation++;
    c.busy = true;
    c.fullWorld = full;
    c.scene = scene;
    telemetryPlanes = full ? 3 : 1;
    c.worldReady = !full;
    c.avatarReady = true;
    c.avatarInverse = null;
    c.avatarMode = 0;
    c.aborted = false;
    c.busySince = now;
    c.pose = pose;
    var hostPose = HostController.frame();
    c.viewMode = hostPose == null ? (pose.firstPerson() ? 0 : 1) : hostPose.viewMode();
    c.far = far;
    c.frame = ++frameCounter;
    c.captureNanos = now;
    c.level = Minecraft.getInstance().level;
    c.player = Minecraft.getInstance().player;
    telemetryCaptures++;
    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
    if (full && c.gpuSet >= 0) c.worldReady = true;
    else if (full) {
      final Capture pending = c;
      final long generation = c.generation;
      encoder.copyTextureToBuffer(target.getColorTexture(), c.color, 0L, () -> {}, 0);
      encoder.copyTextureToBuffer(
          target.getDepthTexture(),
          c.depth,
          0L,
          () -> {
            if (pending.generation == generation) {
              pending.worldReady = true;
              if (pending.aborted && pending.avatarReady) release(pending);
            }
          },
          0);
    }
    encoder.clearColorTexture(target.getColorTexture(), TRANSPARENT);
    current = c;
  }

  /** Copy the complete real F5 body/equipment before hand and GUI drawing starts. */
  public static void captureAvatar(RenderTarget target, Matrix4f inverse) {
    var c = current;
    var pose = HostController.frame();
    if (c == null
        || !c.fullWorld
        || c.scene == null
        || pose == null
        || pose.firstPerson()
        || pose.hostFrame() != c.pose.hostFrame()
        || target.width != c.width
        || target.height != c.height
        || !inverse.isFinite()) return;
    if (c.gpuSet >= 0) {
      if (!GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 3)
          || !GpuTransport.copyDepth(target.getDepthTexture(), c.gpuSet, 4)) return;
      c.avatarInverse = SceneCapture.rows(inverse);
      c.avatarMode = pose.viewMode();
      c.avatarReady = true;
      telemetryPlanes = 5;
      return;
    }
    if (c.avatarColor == null) {
      long bytes = (long) c.width * c.height * 4;
      int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
      var color =
          RenderSystem.getDevice().createBuffer(() -> "passthrough coherent avatar", usage, bytes);
      try {
        c.avatarDepth =
            RenderSystem.getDevice().createBuffer(() -> "passthrough avatar depth", usage, bytes);
      } catch (RuntimeException failure) {
        color.close();
        throw failure;
      }
      c.avatarColor = color;
    }
    c.avatarInverse = SceneCapture.rows(inverse);
    c.avatarMode = pose.viewMode();
    c.avatarReady = false;
    telemetryPlanes = 5;
    long generation = c.generation;
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    encoder.copyTextureToBuffer(target.getColorTexture(), c.avatarColor, 0L, () -> {}, 0);
    encoder.copyTextureToBuffer(
        target.getDepthTexture(),
        c.avatarDepth,
        0L,
        () -> {
          if (c.generation == generation) {
            c.avatarReady = true;
            if (c.aborted && c.worldReady) release(c);
          }
        },
        0);
  }

  /** End of GameRenderer.render: the overlay (hand, screen effects, GUI) is complete. */
  public static void captureOverlay(final RenderTarget target) {
    Capture c = current;
    current = null;
    if (c == null) {
      if (guestPose() == null) invalidate();
      return;
    }

    if (target.width != c.width || target.height != c.height) {
      abort(c);
      return;
    }

    long generation = c.generation;
    c.guiOpen = Minecraft.getInstance().gui.screen() != null;
    if (c.gpuSet >= 0) {
      if (!GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 2)) {
        abort(c);
        return;
      }
      // Fence value = this capture's frame; the host copies only once the GPU reached it.
      GpuTransport.signal(c.gpuSet, c.frame);
      ringNext = (ringNext + 1) % RING;
      publish(c);
      return;
    }
    RenderSystem.getDevice()
        .createCommandEncoder()
        .copyTextureToBuffer(
            target.getColorTexture(),
            c.overlay,
            0L,
            () -> {
              if (c.generation == generation && c.busy) {
                publish(c);
              }
            },
            0);
    ringNext = (ringNext + 1) % RING;
  }

  private static void abort(Capture capture) {
    capture.aborted = true;
    if (capture.gpuSet >= 0) {
      GpuTransport.discard(capture.gpuSet);
      capture.gpuSet = -1;
    }
    // A resize/render abort must not release a buffer while an earlier GPU copy still owns it.
    if (capture.worldReady && capture.avatarReady) release(capture);
  }

  private static void release(Capture capture) {
    capture.gpuSet = -1;
    capture.busy = false;
    capture.level = capture.player = null;
    capture.scene = null;
    capture.avatarInverse = null;
  }

  private static void publish(final Capture c) {
    boolean published = false;
    try {
      var currentPose = guestPose();
      if (shm == null || currentPose == null) return;
      long now = System.nanoTime();
      var client = Minecraft.getInstance();
      var hostPose = HostController.frame();
      int currentMode =
          hostPose == null ? (currentPose.firstPerson() ? 0 : 1) : hostPose.viewMode();
      var key =
          c.scene == null
              ? null
              : new FramePipeline.SceneKey(
                  c.scene.pid(), c.scene.epoch(), c.scene.map(), c.scene.anchor());
      if (!FramePipeline.publishable(
              c.frame,
              lastPublishedFrame,
              c.captureNanos,
              now,
              c.level == client.level && c.player == client.player)
          || !FramePipeline.samePresentation(key, SceneCapture.key(), c.viewMode, currentMode)) {
        telemetryDropped++;
        report(now);
        return;
      }
      MemorySegment m = shm.segment;
      int slot = slotNext;
      slotNext = (slotNext + 1) % SLOTS;
      long desc = SLOT_DESC + (long) SLOT_DESC_BYTES * slot;
      long seq = m.get(LONG, desc);
      if ((seq & 1L) != 0L) {
        seq++;
      }

      m.set(LONG, desc, seq + 1L);
      VarHandle.fullFence();
      long base = HEADER + STRIDE * slot;
      long n = (long) c.width * c.height * 4;
      long copyStart = System.nanoTime();
      boolean gpu = c.gpuSet >= 0;
      if (!gpu) {
        if (c.fullWorld) {
          copy(c.color, m, base, n);
          copy(c.depth, m, base + n, n);
        }
        copy(c.overlay, m, base + 2 * n, n);
        if (c.avatarInverse != null) {
          copy(c.avatarColor, m, base + 3 * n, n);
          copy(c.avatarDepth, m, base + 4 * n, n);
        }
      } else telemetryGpu++;
      Pose p = c.pose;
      m.set(LONG, desc + 8, c.frame);
      m.set(LONG, desc + 16, p.hostFrame());
      m.set(INT, desc + 24, c.width);
      m.set(INT, desc + 28, c.height);
      m.set(FLOAT, desc + 32, NEAR);
      m.set(FLOAT, desc + 36, c.far);
      m.set(FLOAT, desc + 40, p.fov());
      m.set(
          INT,
          desc + 44,
          (RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0)
              | 2
              | 4
              | (c.fullWorld ? 0 : 8)
              | (c.scene != null ? 16 : 0)
              | (c.avatarInverse != null ? 32 : 0));
      m.set(DOUBLE, desc + 48, p.x());
      m.set(DOUBLE, desc + 56, p.y());
      m.set(DOUBLE, desc + 64, p.z());
      m.set(FLOAT, desc + 72, p.yaw());
      m.set(FLOAT, desc + 76, p.pitch());
      m.set(FLOAT, desc + 80, p.roll());
      m.set(INT, desc + 84, p.firstPerson() ? 1 : 0);
      m.set(LONG, desc + 88, c.captureNanos);
      m.set(LONG, desc + 96, System.nanoTime());
      m.set(INT, desc + 104, c.guiOpen ? 1 : 0);
      m.set(INT, desc + 112, gpu ? GpuTransport.generation() : 0);
      m.set(INT, desc + 116, gpu ? c.gpuSet : 0);
      if (gpu) m.set(INT, desc + 44, m.get(INT, desc + 44) | GpuTransport.FLAG);
      // Extension stays inside the existing 4096-byte header and shares this slot's seqlock.
      long extra = 1024L + slot * 512L;
      m.asSlice(extra, 512).fill((byte) 0);
      if (c.scene != null) {
        var s = c.scene;
        m.set(INT, extra, 0x53464345);
        m.set(INT, extra + 4, 1);
        m.set(LONG, extra + 8, s.epoch());
        m.set(LONG, extra + 16, s.anchor());
        m.set(INT, extra + 24, (int) s.map());
        m.set(INT, extra + 28, (int) s.pid());
        m.set(DOUBLE, extra + 32, s.camera().x());
        m.set(DOUBLE, extra + 40, s.camera().y());
        m.set(DOUBLE, extra + 48, s.camera().z());
        for (int i = 0; i < 16; i++) {
          m.set(FLOAT, extra + 64 + i * 4, s.projection()[i]);
          m.set(FLOAT, extra + 128 + i * 4, s.inverse()[i]);
        }
        m.set(LONG, extra + 192, s.meshRevision());
        m.set(LONG, extra + 200, s.atlasRevision());
        m.set(LONG, extra + 208, s.meshSession());
        if (c.avatarInverse != null) {
          for (int i = 0; i < 16; i++) m.set(FLOAT, extra + 216 + i * 4, c.avatarInverse[i]);
          m.set(INT, extra + 280, c.avatarMode);
        }
      }
      VarHandle.fullFence();
      m.set(LONG, desc, seq + 2L);
      m.set(INT, 40, slot);
      VarHandle.fullFence();
      m.set(LONG, 32, ++publishCounter);
      published = true;
      lastPublishedFrame = c.frame;
      telemetryPublished++;
      telemetryLatency += System.nanoTime() - c.captureNanos;
      telemetryCopy += System.nanoTime() - copyStart;
      report(System.nanoTime());
    } catch (RuntimeException e) {
      LOG.warn("frame export failed", e);
    } finally {
      // A GPU set that never reached a descriptor is never read by the host.
      if (!published && c.gpuSet >= 0) GpuTransport.discard(c.gpuSet);
      release(c);
    }
  }

  private static void invalidate() {
    if (shm != null) {
      shm.segment.set(INT, 40, -1);
      VarHandle.fullFence();
    }
  }

  private static void report(long now) {
    if (telemetryStart == 0) {
      telemetryStart = now;
      return;
    }
    long elapsed = now - telemetryStart;
    if (elapsed < 5_000_000_000L) return;
    long oldest = 0;
    int outstanding = 0;
    for (var capture : ring)
      if (capture != null && capture.busy) {
        outstanding++;
        oldest = Math.max(oldest, now - capture.busySince);
      }
    LOG.info(
        "frame performance: captureFps={}, publishFps={}, completionMs={}, copyMs={}, busyDrops={},"
            + " staleDrops={}, pending={}, oldestMs={}, planes={}, gpuShared={}",
        telemetryCaptures * 1e9 / elapsed,
        telemetryPublished * 1e9 / elapsed,
        telemetryPublished == 0 ? 0 : telemetryLatency / 1e6 / telemetryPublished,
        telemetryPublished == 0 ? 0 : telemetryCopy / 1e6 / telemetryPublished,
        telemetryBusy,
        telemetryDropped,
        outstanding,
        oldest / 1e6,
        telemetryPlanes,
        telemetryGpu);
    telemetryStart = now;
    telemetryCaptures =
        telemetryPublished =
            telemetryBusy = telemetryDropped = telemetryLatency = telemetryCopy = telemetryGpu = 0;
  }

  public static void close() {
    BlockDetails.close();
    BlockMeshClient.close();
    HostAvatarRenderer.close();
    enabled = false;
    current = null;
    invalidate();
    GpuTransport.close();
    for (var capture : ring) if (capture != null) capture.level = capture.player = null;
    if (shm != null) {
      shm.close();
      shm = null;
    }
    // Render device shutdown owns outstanding GPU work and buffers.
  }

  private static void copy(
      final GpuBuffer buffer, final MemorySegment dst, final long offset, final long n) {
    try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
      MemorySegment.copy(MemorySegment.ofBuffer(view.data()), 0L, dst, offset, n);
    }
  }
}
