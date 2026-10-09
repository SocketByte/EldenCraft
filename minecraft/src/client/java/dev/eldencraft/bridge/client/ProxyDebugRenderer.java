package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.Shapes;

/** Uses Minecraft's own outline feature to inspect the exact host-published attack boxes. */
public final class ProxyDebugRenderer {
  private record Box(AABB bounds, int color) {}

  private record DebugFrame(ProxyProtocol.Frame host, List<Box> boxes, Vec3 expectedGuestCamera) {}

  private static DebugFrame debug;
  private static ProjectionMatrixBuffer projection;
  private static long observed;
  private static long lastDiagnostic;
  private static boolean wasEnabled;

  private ProxyDebugRenderer() {}

  public static void update(
      Minecraft client, ProxyProtocol.Frame frame, UUID selected, boolean authorityReady) {
    var server = client.getSingleplayerServer();
    var pose = HostController.avatar();
    var control = HostController.combatSnapshot(client);
    boolean valid =
        frame != null
            && (frame.flags() & 1) != 0
            && client.level != null
            && client.player != null
            && pose != null
            && server != null
            && !server.isPublished()
            && control != null
            && control.publisherPid() == frame.pid()
            && control.mapId() == frame.map();
    if (!valid) {
      debug = null;
      observed = 0;
      return;
    }
    boolean enabled = frame.debugBounds();
    if (enabled != wasEnabled) {
      client.gui.hud.setOverlayMessage(
          enabled
              ? Component.literal("F4: ")
                  .append(Component.literal("aim ").withStyle(ChatFormatting.YELLOW))
                  .append(Component.literal("ready ").withStyle(ChatFormatting.GREEN))
                  .append(Component.literal("far ").withStyle(ChatFormatting.GOLD))
                  .append(Component.literal("blocked").withStyle(ChatFormatting.RED))
              : Component.literal("F4 hitboxes off"),
          false);
      org.slf4j.LoggerFactory.getLogger("eldencraft_proxy")
          .info("Published target hitboxes {}", enabled ? "visible" : "hidden");
      wasEnabled = enabled;
    }
    if (!enabled) {
      debug = null;
      return;
    }
    var result = new ArrayList<Box>();
    var item = client.player.getMainHandItem();
    boolean canAttack =
        authorityReady
            && frame.ready()
            && client.player.isAlive()
            && !client.player.isSpectator()
            && client.gui.screen() == null
            && !item.isBroken();
    for (var target : frame.targets()) {
      var min = target.min();
      var max = target.max();
      var relative = new AABB(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
      if (relative.distanceToSqr(Vec3.ZERO) > 16 * 16) continue;
      boolean range =
          client
              .player
              .getAttackRangeWith(item)
              .isInRange(client.player, relative.move(client.player.position()), 0);
      var sharedId = SharedWorldClient.proxyUuid(target.handle());
      int color =
          ProxyDebugColors.classify(
              target.hittable() && target.maxHp() / frame.scale() <= 1024,
              canAttack,
              range,
              (sharedId == null ? target.uuid(frame.epoch()) : sharedId).equals(selected));
      var low = ProxyView.relative(min, frame.camera());
      var high = ProxyView.relative(max, frame.camera());
      result.add(new Box(new AABB(low.x(), low.y(), low.z(), high.x(), high.y(), high.z()), color));
    }
    var eye = frame.camera();
    debug =
        new DebugFrame(
            frame,
            List.copyOf(result),
            new Vec3(pose.x() + eye.x(), pose.y() + eye.y(), pose.z() + eye.z()));
    observed = System.nanoTime();
  }

  public static void render(RenderTarget target) {
    var sample = debug;
    if (sample == null
        || sample.boxes.isEmpty()
        || System.nanoTime() - observed >= 250_000_000L
        || HostController.frame() == null
        || !FrameExporter.exporting()) return;
    var client = Minecraft.getInstance();
    var renderer = client.gameRenderer;
    var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
    var storage = new SubmitNodeStorage();
    var poses = new PoseStack();
    float width = Math.max(1.5f, renderer.gameRenderState().windowRenderState.appropriateLineWidth);
    // Relative vertices and view rotation belong to the same immutable publication.
    for (var box : sample.boxes)
      storage.submitShapeOutline(
          poses, Shapes.create(box.bounds), RenderTypes.lines(), box.color, width, false);
    if (projection == null)
      projection = new ProjectionMatrixBuffer("EldenCraft coherent target projection");
    var previousProjection = RenderSystem.getProjectionMatrixBuffer();
    var previousType = RenderSystem.getProjectionType();
    var modelView = RenderSystem.getModelViewStack();
    modelView.pushMatrix();
    try {
      long now = System.nanoTime();
      if (now - lastDiagnostic >= 1_000_000_000L) {
        var guestForward = new org.joml.Vector3f(0, 0, -1).rotate(camera.orientation);
        var forward = sample.host.forward();
        double agreement =
            guestForward.x * forward.x()
                + guestForward.y * forward.y()
                + guestForward.z * forward.z();
        org.slf4j.LoggerFactory.getLogger("eldencraft_proxy")
            .info(
                "Target debug render: targetFrame={}, poseFrame={}, boxes={}, basisCos={},"
                    + " anchorDelta={}, inheritedIdentity={}",
                sample.host.frame(),
                HostController.frame().hostFrame(),
                sample.boxes.size(),
                agreement,
                sample.expectedGuestCamera.distanceTo(camera.pos),
                modelView.equals(new org.joml.Matrix4f(), .0001f));
        lastDiagnostic = now;
      }
      modelView.set(ProxyView.rotation(sample.host.forward()));
      // Bind the extracted perspective without guest hurt/bob/nausea, then restore it.
      RenderSystem.setProjectionMatrix(
          projection.getBuffer(camera.projectionMatrix), ProjectionType.PERSPECTIVE);
      try (var prepared = renderer.featureRenderDispatcher().prepareFrame(storage)) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        // This diagnostic intentionally shows every published box; unrelated guest terrain must not
        // hide it.
        encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
        try (var pass =
            encoder.createRenderPass(
                () -> "EldenCraft published hitbox debug",
                target.getColorTextureView(),
                Optional.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty())) {
          RenderSystem.bindDefaultUniforms(pass);
          FeatureRenderDispatcher.renderAllFeatures(pass, prepared);
        }
      }
    } finally {
      RenderSystem.setProjectionMatrix(previousProjection, previousType);
      modelView.popMatrix();
    }
  }

  public static void clear() {
    debug = null;
    wasEnabled = false;
    observed = 0;
    lastDiagnostic = 0;
    if (projection != null) {
      projection.close();
      projection = null;
    }
  }
}
