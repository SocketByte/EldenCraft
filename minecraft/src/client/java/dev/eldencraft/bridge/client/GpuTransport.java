package dev.eldencraft.bridge.client;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import org.lwjgl.opengl.ARBCopyImage;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Zero-copy frame transport (ECGT v1, see compositor/include/gpu_transport.hpp).
 *
 * <p>The host owns three sets of five shared D3D12 textures and a shared fence. Minecraft imports
 * them through GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32, copies its planes into a free
 * set on the GPU, signals the fence with its frame number and publishes an MCPT descriptor with
 * flag 64. No pixel crosses the CPU. Any failure leaves the readback path.
 *
 * <p>All methods run on the render thread. Raw GL state touched here is saved and restored, so the
 * backend's state cache stays truthful.
 */
final class GpuTransport {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_frame");
  static final String NAME = "Local\\EldenCraftGpuFrame";
  static final int FLAG = 64;
  private static final int BYTES = 4096,
      MAGIC = 0x54474345,
      REQUEST_MAGIC = 0x51474345,
      VERSION = 1;
  static final int SETS = 3, PLANES = 5;

  /** Shared textures carry frames up to 4K; shared-memory planes stop at 1080p. */
  static final int MAX_W = 3840, MAX_H = 2160;

  private static final int PREFERRED = 96;
  private static boolean importFailed;
  private static final int REQUEST = 128, ACKS = 256, STATUS_IMPORTED = 1, STATUS_FAILED = 2;
  private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
  private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

  private static SharedMemory control;
  private static boolean disabled, checked, copyImage;
  private static int generation, width, height, failedGeneration;
  private static final int[][] memory = new int[SETS][PLANES], texture = new int[SETS][PLANES];
  private static final int[] depthTarget = new int[SETS * PLANES];
  private static final long[] written = new long[SETS];
  private static int semaphore, program, vao, readFbo, drawFbo, setNext;
  private static long lastRequestNanos;

  private GpuTransport() {}

  static boolean depthPlane(int plane) {
    return plane == 1 || plane == 4;
  }

  private static boolean available() {
    if (disabled) return false;
    if (!checked) {
      checked = true;
      if ("0".equals(System.getenv("ELDENCRAFT_GPU_TRANSPORT"))) {
        disabled = true;
        LOG.info("GPU transport disabled by ELDENCRAFT_GPU_TRANSPORT=0");
        return false;
      }
      var caps = GL.getCapabilities();
      boolean supported =
          caps.GL_EXT_memory_object
              && caps.GL_EXT_memory_object_win32
              && caps.GL_EXT_semaphore
              && caps.GL_EXT_semaphore_win32;
      copyImage = caps.OpenGL43 || caps.GL_ARB_copy_image;
      LOG.info("GPU transport: memory/semaphore interop {}, copy_image {}", supported, copyImage);
      if (!supported) {
        disabled = true;
        return false;
      }
    }
    if (control == null) {
      try {
        control = SharedMemory.create(NAME, BYTES);
      } catch (Throwable failure) {
        disabled = true;
        LOG.warn("GPU transport control mapping unavailable; readback frames remain.", failure);
        return false;
      }
    }
    return true;
  }

  /**
   * Asks for textures of this size, imports a new host generation when one is published and returns
   * a set that is safe to overwrite, or -1 to use the readback path for this frame.
   */
  static int acquire(int w, int h, boolean depthNeeded) {
    if (!available()) return -1;
    MemorySegment m = control.segment;
    long now = System.nanoTime();
    if (m.get(INT, REQUEST + 4) != w
        || m.get(INT, REQUEST + 8) != h
        || now - lastRequestNanos > 1_000_000_000L) {
      lastRequestNanos = now;
      m.set(INT, REQUEST + 4, w);
      m.set(INT, REQUEST + 8, h);
      m.set(INT, REQUEST + 12, (int) ProcessHandle.current().pid());
      VarHandle.fullFence();
      m.set(INT, REQUEST, REQUEST_MAGIC);
    }
    VarHandle.fullFence();
    if (m.get(INT, 0) != MAGIC
        || m.get(INT, 4) != VERSION
        || m.get(INT, 24) != SETS
        || m.get(INT, 28) != PLANES) return -1;
    int next = m.get(INT, 12);
    if (m.get(INT, 16) != w || m.get(INT, 20) != h || next == 0 || next == failedGeneration)
      return -1;
    if (next != generation && !importGeneration(m, next, w, h)) return -1;
    if (depthNeeded && program == 0) return -1;
    long newest = 0;
    for (int s = 0; s < SETS; s++) newest = Math.max(newest, m.get(LONG, ACKS + s * 8L));
    int set = dev.eldencraft.bridge.FramePipeline.gpuSet(written, newest, setNext);
    if (set >= 0) setNext = (set + 1) % SETS;
    return set;
  }

  static int generation() {
    return generation;
  }

  /** True once shared textures proved unusable in this process (unsupported or failed import). */
  static boolean failed() {
    return disabled || importFailed;
  }

  /** The host back-buffer size it publishes every frame, or null when unknown. */
  static int[] preferredSize() {
    if (!available()) return null;
    int w = control.segment.get(INT, PREFERRED), h = control.segment.get(INT, PREFERRED + 4);
    return w > 0 && h > 0 && w <= MAX_W && h <= MAX_H ? new int[] {w, h} : null;
  }

  private static boolean importGeneration(MemorySegment m, int next, int w, int h) {
    destroy();
    while (GL11C.glGetError() != 0) {}
    int oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
    int oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    int pid = m.get(INT, 8);
    try (MemoryStack stack = MemoryStack.stackPush()) {
      String prefix =
          "Local\\EldenCraftGpu_"
              + Integer.toUnsignedString(pid)
              + "_"
              + Integer.toUnsignedString(next);
      for (int s = 0; s < SETS; s++)
        for (int p = 0; p < PLANES; p++) {
          int object = EXTMemoryObject.glCreateMemoryObjectsEXT();
          memory[s][p] = object;
          EXTMemoryObject.glMemoryObjectParameteriEXT(
              object, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11C.GL_TRUE);
          EXTMemoryObjectWin32.glImportMemoryWin32NameEXT(
              object,
              m.get(LONG, 32 + p * 8L),
              EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,
              MemoryUtil.memAddress(stack.UTF16(prefix + "_" + s + "_" + p)));
          check("import plane");
          int id = GL11C.glGenTextures();
          texture[s][p] = id;
          GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
          EXTMemoryObject.glTexStorageMem2DEXT(
              GL11C.GL_TEXTURE_2D,
              1,
              depthPlane(p) ? GL30C.GL_R32F : GL11C.GL_RGBA8,
              w,
              h,
              object,
              0L);
          GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
          GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
          check("texture storage");
          if (depthPlane(p)) {
            int fbo = GL30C.glGenFramebuffers();
            depthTarget[s * PLANES + p] = fbo;
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(
                GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, id, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER)
                != GL30C.GL_FRAMEBUFFER_COMPLETE)
              throw new IllegalStateException("shared depth target incomplete");
          }
        }
      semaphore = EXTSemaphore.glGenSemaphoresEXT();
      EXTSemaphoreWin32.glImportSemaphoreWin32NameEXT(
          semaphore,
          EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT,
          MemoryUtil.memAddress(stack.UTF16(prefix + "_ready")));
      check("import fence");
      if (program == 0) program = depthProgram();
      if (vao == 0) vao = GL30C.glGenVertexArrays();
      if (!copyImage) {
        readFbo = GL30C.glGenFramebuffers();
        drawFbo = GL30C.glGenFramebuffers();
      }
      generation = next;
      width = w;
      height = h;
      java.util.Arrays.fill(written, 0L);
      status(m, STATUS_IMPORTED, next);
      LOG.info(
          "GPU transport active: {}x{} generation {}; no pixel readback",
          w,
          h,
          Integer.toUnsignedString(next));
      return true;
    } catch (Throwable failure) {
      failedGeneration = next;
      importFailed = true;
      destroy();
      status(m, STATUS_FAILED, next);
      LOG.warn("GPU transport import failed; readback frames remain.", failure);
      return false;
    } finally {
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
    }
  }

  private static void status(MemorySegment m, int value, int forGeneration) {
    m.set(INT, REQUEST + 20, forGeneration);
    VarHandle.fullFence();
    m.set(INT, REQUEST + 16, value);
  }

  private static void check(String step) {
    int error = GL11C.glGetError();
    if (error != 0)
      throw new IllegalStateException(step + ": GL error 0x" + Integer.toHexString(error));
  }

  private static int depthProgram() {
    int vertex =
        shader(
            GL20C.GL_VERTEX_SHADER,
            "#version 330 core\n"
                + "void main(){vec2"
                + " p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}");
    int fragment =
        shader(
            GL20C.GL_FRAGMENT_SHADER,
            "#version 330 core\n"
                + "uniform sampler2D sourceDepth;out float value;void"
                + " main(){value=texelFetch(sourceDepth,ivec2(gl_FragCoord.xy),0).r;}");
    int linked = GL20C.glCreateProgram();
    GL20C.glAttachShader(linked, vertex);
    GL20C.glAttachShader(linked, fragment);
    GL20C.glLinkProgram(linked);
    GL20C.glDeleteShader(vertex);
    GL20C.glDeleteShader(fragment);
    if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == 0) {
      String log = GL20C.glGetProgramInfoLog(linked);
      GL20C.glDeleteProgram(linked);
      throw new IllegalStateException(log);
    }
    return linked;
  }

  private static int shader(int type, String source) {
    int id = GL20C.glCreateShader(type);
    GL20C.glShaderSource(id, source);
    GL20C.glCompileShader(id);
    if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
      String log = GL20C.glGetShaderInfoLog(id);
      GL20C.glDeleteShader(id);
      throw new IllegalStateException(log);
    }
    return id;
  }

  private static int glId(GpuTexture source) {
    return source instanceof GlTexture gl ? gl.glId() : 0;
  }

  /** Copies a colour target into a shared plane. False if the backend is not OpenGL. */
  static boolean copyColor(GpuTexture source, int set, int plane) {
    int id = glId(source);
    if (id == 0) return false;
    if (copyImage) {
      ARBCopyImage.glCopyImageSubData(
          id,
          GL11C.GL_TEXTURE_2D,
          0,
          0,
          0,
          0,
          texture[set][plane],
          GL11C.GL_TEXTURE_2D,
          0,
          0,
          0,
          0,
          width,
          height,
          1);
      return true;
    }
    int oldRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
        oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    try {
      GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
      GL30C.glFramebufferTexture2D(
          GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, id, 0);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
      GL30C.glFramebufferTexture2D(
          GL30C.GL_DRAW_FRAMEBUFFER,
          GL30C.GL_COLOR_ATTACHMENT0,
          GL11C.GL_TEXTURE_2D,
          texture[set][plane],
          0);
      GL30C.glBlitFramebuffer(
          0, 0, width, height, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
    } finally {
      if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, oldRead);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
    }
    return true;
  }

  /**
   * Writes a depth texture's raw window depth into a shared R32F plane with one fullscreen
   * triangle.
   */
  static boolean copyDepth(GpuTexture source, int set, int plane) {
    int id = glId(source);
    if (id == 0 || program == 0) return false;
    int oldProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
        oldVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
    int oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
        oldActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    int oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
        oldSampler = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
    int[] viewport = new int[4];
    GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
    boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
        blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
    boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
        cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
    int[] mask = new int[4];
    GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, mask);
    int compare;
    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
    compare = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE);
    try {
      GL11C.glDisable(GL11C.GL_DEPTH_TEST);
      GL11C.glDisable(GL11C.GL_BLEND);
      GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
      GL11C.glDisable(GL11C.GL_CULL_FACE);
      GL11C.glColorMask(true, true, true, true);
      GL33C.glBindSampler(0, 0);
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, depthTarget[set * PLANES + plane]);
      GL11C.glViewport(0, 0, width, height);
      GL20C.glUseProgram(program);
      GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "sourceDepth"), 0);
      GL30C.glBindVertexArray(vao);
      GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    } finally {
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, compare);
      GL30C.glBindVertexArray(oldVao);
      GL20C.glUseProgram(oldProgram);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
      GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      GL11C.glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
      if (depth) GL11C.glEnable(GL11C.GL_DEPTH_TEST);
      if (blend) GL11C.glEnable(GL11C.GL_BLEND);
      if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
      if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE);
      GL33C.glBindSampler(0, oldSampler);
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
      GL13C.glActiveTexture(oldActive);
    }
    return true;
  }

  /** Makes every write to this set visible to the host and marks it as holding `frame`. */
  static void signal(int set, long frame) {
    int[] textures = texture[set].clone();
    int[] layouts = new int[PLANES];
    java.util.Arrays.fill(layouts, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
    EXTSemaphore.glSemaphoreParameterui64EXT(
        semaphore, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, frame);
    EXTSemaphore.glSignalSemaphoreEXT(semaphore, new int[0], textures, layouts);
    GL11C.glFlush();
    written[set] = frame;
  }

  /** The capture was abandoned before signalling; nothing of it can be published. */
  static void discard(int set) {
    if (set >= 0 && set < SETS) written[set] = 0;
  }

  private static void destroy() {
    for (int s = 0; s < SETS; s++)
      for (int p = 0; p < PLANES; p++) {
        int fbo = depthTarget[s * PLANES + p];
        if (fbo != 0) GL30C.glDeleteFramebuffers(fbo);
        if (texture[s][p] != 0) GL11C.glDeleteTextures(texture[s][p]);
        if (memory[s][p] != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(memory[s][p]);
        depthTarget[s * PLANES + p] = texture[s][p] = memory[s][p] = 0;
      }
    if (semaphore != 0) EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
    semaphore = 0;
    generation = 0;
    java.util.Arrays.fill(written, 0L);
  }

  static void close() {
    destroy();
    if (control != null) {
      control.close();
      control = null;
    }
  }
}
