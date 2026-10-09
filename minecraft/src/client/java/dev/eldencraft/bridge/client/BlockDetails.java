package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import dev.eldencraft.bridge.*;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Actual vanilla crack models/UVs and selection edges, kept separate from immutable terrain meshes.
 */
public final class BlockDetails {
  private static final int MAX_VERTICES = 16380;
  private static final BlockMeshMailbox MAILBOX =
      new BlockMeshMailbox(
          "EldenCraftBlockDetail",
          BlockMeshProtocol.DETAIL_MESH_BYTES,
          BlockMeshProtocol.DETAIL_ATLAS_BYTES,
          false);
  private static final ByteArrayOutputStream cracks = new ByteArrayOutputStream(),
      lines = new ByteArrayOutputStream();
  private static final ByteBuffer vertexBytes =
      ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_details");
  private static BlockMeshProtocol.Identity identity;
  private static WorldOrigin origin;
  private static net.minecraft.world.phys.Vec3 camera;
  private static Object models;
  private static byte[] pixels, previous;
  private static int size, height, previousCracks;
  private static long atlasRevision, revision, report, diagnosticReport;
  private static int peakSource,
      peakSubmissions,
      peakCracks,
      peakOutlines,
      frameSubmissions,
      peakParts,
      peakFabricQuads,
      emptyModels;
  private static boolean initialized, active, atlasPublished;

  private BlockDetails() {}

  public static void begin() {
    active = false;
    cracks.reset();
    lines.reset();
    frameSubmissions = 0;
    var mc = Minecraft.getInstance();
    var next = BlockMeshClient.presentationIdentity();
    if (next == null || !SceneCapture.active()) {
      pause();
      return;
    }
    try {
      if (!initialized) {
        MAILBOX.initialize();
        initialized = true;
      }
      var nextModels = mc.getModelManager().getBlockStateModelSet();
      if (!next.equals(identity) || models != nextModels) {
        identity = next;
        models = nextModels;
        pixels = null;
        atlasPublished = false;
        previous = null;
      }
      if (pixels == null) loadTextures(mc);
      origin = SharedWorldClient.origin();
      camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
      if (origin == null
          || origin.epoch() != identity.epoch()
          || origin.map() != identity.map()
          || origin.anchorId() != identity.anchor()) {
        pause();
        return;
      }
      if (!atlasPublished) {
        MAILBOX.atlas(
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC,
                identity,
                MAILBOX.now(),
                atlasRevision,
                atlasRevision,
                size,
                height,
                pixels.length,
                0,
                0,
                0,
                true),
            ByteBuffer.wrap(pixels));
        atlasPublished = true;
      }
      peakSource =
          Math.max(
              peakSource,
              mc.gameRenderer.gameRenderState().levelRenderState.blockBreakingRenderStates.size());
      active = true;
    } catch (Throwable error) {
      pixels = null;
      failure(error);
    }
  }

  private static void loadTextures(Minecraft mc) throws Exception {
    var images = new ArrayList<NativeImage>();
    try {
      for (int stage = 0; stage < 10; stage++) {
        var name =
            Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png");
        try (var stream = mc.getResourceManager().getResourceOrThrow(name).open()) {
          images.add(NativeImage.read(stream));
        }
      }
      size = images.getFirst().getWidth();
      height = size * 10 + 1;
      if (size < 1 || size > 256)
        throw new IllegalArgumentException("Unsupported destroy texture dimensions");
      pixels = new byte[size * height * 4];
      for (int stage = 0; stage < 10; stage++) {
        var image = images.get(stage);
        if (image.getWidth() != size || image.getHeight() != size)
          throw new IllegalArgumentException("Destroy textures differ in size");
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            int argb = image.getPixel(x, y), at = ((stage * size + y) * size + x) * 4;
            pixels[at] = (byte) (argb >>> 16);
            pixels[at + 1] = (byte) (argb >>> 8);
            pixels[at + 2] = (byte) argb;
            pixels[at + 3] = (byte) (argb >>> 24);
          }
      }
      Arrays.fill(pixels, size * size * 10 * 4, pixels.length, (byte) 255);
      atlasRevision++;
    } finally {
      images.forEach(NativeImage::close);
    }
  }

  public static boolean breaking(
      PoseStack pose,
      List<BlockStateModelPart> parts,
      int stage,
      net.minecraft.client.renderer.block.dispatch.BlockStateModel model,
      net.minecraft.client.renderer.state.level.BlockBreakingRenderState state,
      net.minecraft.util.RandomSource random) {
    if (!active) return false;
    try {
      frameSubmissions++;
      int before = cracks.size();
      BlockCrackGeometry.Sink sink = (x, y, z, u, v) -> vertex(cracks, x, y, z, u, v, -1);
      // Fabric redirects LevelRenderer's collectParts to a no-op, so the
      // list handed to this call is empty. Collect the vanilla parts with
      // vanilla's own per-position seed and use the baked-quad route the
      // conformance suite exercises against the real decal generator.
      var collected = new ArrayList<BlockStateModelPart>(parts);
      if (collected.isEmpty()) {
        random.setSeed(state.blockState().getSeed(state.blockPos()));
        model.collectParts(random, collected);
      }
      peakParts = Math.max(peakParts, collected.size());
      BlockCrackGeometry.emit(pose, collected, stage, size, sink);
      if (cracks.size() == before) {
        // Custom Fabric models may expose quads only through emitQuads.
        random.setSeed(state.blockState().getSeed(state.blockPos()));
        int quads =
            BlockCrackGeometry.emitFabric(
                pose,
                (net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel) model,
                state.blockPos(),
                state.blockState(),
                random,
                net.fabricmc.fabric.api.client.renderer.v1.Renderer.get(),
                stage,
                size,
                sink);
        peakFabricQuads = Math.max(peakFabricQuads, quads);
      }
      // No geometry from either route: let the genuine vanilla/Fabric
      // submission draw the crack rather than silently dropping it.
      if (cracks.size() == before) {
        emptyModels++;
        return false;
      }
      return true;
    } catch (RuntimeException error) {
      failure(error);
      return false;
    }
  }

  public static boolean outline(PoseStack pose, VoxelShape shape, int argb, float width) {
    if (!active) return false;
    try {
      shape.forAllEdges(
          (x0, y0, z0, x1, y1, z1) -> {
            var a =
                pose.last()
                    .pose()
                    .transformPosition((float) x0, (float) y0, (float) z0, new Vector3f());
            var b =
                pose.last()
                    .pose()
                    .transformPosition((float) x1, (float) y1, (float) z1, new Vector3f());
            var mc = Minecraft.getInstance();
            var frame = HostController.frame();
            if (frame == null) throw new IllegalArgumentException("No presentation camera");
            double distance = Math.max(.1, (a.length() + b.length()) * .5);
            float half =
                (float)
                    Math.clamp(
                        distance
                            * Math.tan(Math.toRadians(frame.fov()) * .5)
                            * width
                            / Math.max(1, mc.gameRenderer.mainRenderTarget().height),
                        .00025,
                        .05);
            // Preserve the exact vanilla edge. Two crossed strips give stable
            // raster thickness without a camera-facing world-space offset.
            var delta = new Vector3f(b).sub(a).normalize();
            var first =
                new Vector3f(Math.abs(delta.y) < .9 ? 0 : 1, Math.abs(delta.y) < .9 ? 1 : 0, 0)
                    .cross(delta)
                    .normalize()
                    .mul(half);
            var second = new Vector3f(delta).cross(first).normalize().mul(half);
            strip(a, b, first, argb);
            strip(a, b, second, argb);
          });
      return true;
    } catch (RuntimeException error) {
      failure(error);
      return false;
    }
  }

  private static void strip(Vector3f a, Vector3f b, Vector3f side, int argb) {
    Vector3f[] v = {
      new Vector3f(a).sub(side),
      new Vector3f(a).add(side),
      new Vector3f(b).add(side),
      new Vector3f(b).sub(side)
    };
    for (int index : new int[] {0, 1, 2, 0, 2, 3})
      vertex(lines, v[index].x, v[index].y, v[index].z, .5f, (height - .5f) / height, argb);
  }

  private static void vertex(
      ByteArrayOutputStream out, float x, float y, float z, float u, float v, int argb) {
    if ((cracks.size() + lines.size()) / 24 >= MAX_VERTICES)
      throw new IllegalArgumentException("Detail vertex limit");
    var p = origin.toHost(camera.x + x, camera.y + y, camera.z + z);
    var bytes = vertexBytes;
    bytes.clear();
    bytes
        .putFloat((float) p.x())
        .putFloat((float) p.y())
        .putFloat((float) p.z())
        .putFloat(u)
        .putFloat(v)
        .put((byte) (argb >>> 16))
        .put((byte) (argb >>> 8))
        .put((byte) argb)
        .put((byte) (argb >>> 24));
    out.writeBytes(bytes.array());
  }

  public static void finish() {
    if (!active) return;
    try {
      byte[] data = new byte[cracks.size() + lines.size()];
      System.arraycopy(cracks.toByteArray(), 0, data, 0, cracks.size());
      System.arraycopy(lines.toByteArray(), 0, data, cracks.size(), lines.size());
      int count = cracks.size() / 24;
      peakSubmissions = Math.max(peakSubmissions, frameSubmissions);
      peakCracks = Math.max(peakCracks, count);
      peakOutlines = Math.max(peakOutlines, lines.size() / 24);
      long now = System.nanoTime();
      if (now - diagnosticReport > 5_000_000_000L) {
        diagnosticReport = now;
        LOG.info(
            "Block detail interval peaks: source breaking states={}, submitted models={}, vanilla"
                + " parts={}, fabric fallback quads={}, empty models={}, crack vertices={}, outline"
                + " vertices={}",
            peakSource,
            peakSubmissions,
            peakParts,
            peakFabricQuads,
            emptyModels,
            peakCracks,
            peakOutlines);
        peakSource =
            peakSubmissions =
                peakCracks = peakOutlines = peakParts = peakFabricQuads = emptyModels = 0;
      }
      if (!Arrays.equals(previous, data) || count != previousCracks) {
        MAILBOX.mesh(
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC,
                identity,
                MAILBOX.now(),
                ++revision,
                atlasRevision,
                data.length / 24,
                24,
                data.length,
                count,
                0,
                lines.size() / 24,
                true),
            data);
        previous = data;
        previousCracks = count;
      }
      MAILBOX.heartbeat(true);
    } catch (Throwable error) {
      failure(error);
    }
  }

  private static void pause() {
    active = false;
    try {
      if (initialized) MAILBOX.heartbeat(false);
    } catch (Throwable ignored) {
    }
  }

  private static void failure(Throwable error) {
    if (error instanceof VirtualMachineError fatal) throw fatal;
    pause();
    long now = System.nanoTime();
    if (now - report > 5_000_000_000L) {
      report = now;
      LOG.warn("Native block details unavailable; vanilla fallback remains: {}", error.toString());
    }
  }

  public static void close() {
    pause();
    MAILBOX.close();
    pixels = previous = null;
  }
}
