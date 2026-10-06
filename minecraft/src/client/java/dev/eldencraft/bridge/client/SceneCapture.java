package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.WorldOrigin;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Metadata belongs to the exact vanilla world projection submitted for this frame. */
public final class SceneCapture {
  public record Snapshot(
      long epoch,
      long map,
      long anchor,
      long pid,
      WorldOrigin.Vec camera,
      float[] projection,
      float[] inverse,
      long meshRevision,
      long atlasRevision,
      long meshSession) {}

  private static Matrix4f projection;

  private SceneCapture() {}

  public static boolean active() {
    return FrameExporter.enabled() && SharedWorldClient.active();
  }

  public static dev.eldencraft.bridge.FramePipeline.SceneKey key() {
    var origin = SharedWorldClient.origin();
    var host = HostController.damageSnapshot(Minecraft.getInstance());
    return !active() || origin == null || host == null
        ? null
        : new dev.eldencraft.bridge.FramePipeline.SceneKey(
            host.publisherPid(), origin.epoch(), origin.map(), origin.anchorId());
  }

  public static void projection(Matrix4f value) {
    projection = active() ? new Matrix4f(value) : null;
  }

  public static Snapshot snapshot() {
    var origin = SharedWorldClient.origin();
    var client = Minecraft.getInstance();
    var host = HostController.damageSnapshot(client);
    if (!active() || origin == null || host == null || projection == null || client.player == null)
      return null;
    var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    var full = new Matrix4f(projection).mul(camera.viewRotationMatrix);
    if (!full.isFinite() || Math.abs(full.determinant()) < 1e-10f) return null;
    var inverse = new Matrix4f(full).invert();
    if (!inverse.isFinite()) return null;
    var excluded = BlockMeshClient.exclusion();
    return new Snapshot(
        origin.epoch(),
        origin.map(),
        origin.anchorId(),
        host.publisherPid(),
        origin.toHost(camera.pos.x, camera.pos.y, camera.pos.z),
        rows(full),
        rows(inverse),
        excluded.mesh(),
        excluded.atlas(),
        excluded.session());
  }

  static float[] rows(Matrix4f matrix) {
    float[] result = new float[16];
    for (int row = 0; row < 4; row++)
      for (int column = 0; column < 4; column++) result[row * 4 + column] = matrix.get(column, row);
    return result;
  }
}
