package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eldencraft.bridge.TorrentPolicy;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HorseRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Align the real local-player world render to the same host sample as the camera. */
public final class HostAvatarRenderer {
  private static ProjectionMatrixBuffer projection;
  private static boolean failed;
  // Render thread: the Torrent state most recently aligned, to keep it out of the world layer.
  private static EntityRenderState torrentState;

  private HostAvatarRenderer() {}

  public static boolean isolated() {
    var client = Minecraft.getInstance();
    var camera = HostController.frame();
    return !failed
        && SceneCapture.active()
        && FrameExporter.exporting()
        && camera != null
        && !camera.firstPerson()
        && HostController.avatar() != null
        && client.player != null
        && client.player.isAlive();
  }

  public static boolean belongsToIsolatedLayer(EntityRenderState state) {
    var player = Minecraft.getInstance().player;
    return isolated()
        && (state instanceof AvatarRenderState avatar
                && player != null
                && avatar.id == player.getId()
            || state == torrentState);
  }

  /** Real vanilla avatar/equipment in a single coherent camera-relative layer. */
  public static void render(RenderTarget target) {
    if (!isolated()) return;
    var client = Minecraft.getInstance();
    var renderer = client.gameRenderer;
    var previousProjection = RenderSystem.getProjectionMatrixBuffer();
    var previousType = RenderSystem.getProjectionType();
    var modelView = RenderSystem.getModelViewStack();
    modelView.pushMatrix();
    try {
      var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
      var dispatcher = client.getEntityRenderDispatcher();
      var state = dispatcher.extractEntity(client.player, camera.cameraEntityPartialTicks);
      if (!(state instanceof AvatarRenderState avatar)) return;
      // Explicit extraction bypasses world/frustum selection of the 20 Hz stand-in.
      // Keep real invisibility/spectator flags; never synthesize them from camera distance.
      align(client.player, avatar);
      var storage = new SubmitNodeStorage();
      var poses = new PoseStack();
      dispatcher.submit(
          state,
          camera,
          state.x - camera.pos.x,
          state.y - camera.pos.y,
          state.z - camera.pos.z,
          poses,
          storage);
      // Torrent shares the rider's host sample and depth, never the 20 Hz world copy.
      var steed = WorldTorrent.clientTorrent();
      if (steed != null && HostController.torrent() != null) {
        var mount = dispatcher.extractEntity(steed, camera.cameraEntityPartialTicks);
        dispatcher.submit(
            mount,
            camera,
            mount.x - camera.pos.x,
            mount.y - camera.pos.y,
            mount.z - camera.pos.z,
            poses,
            storage);
      }
      if (projection == null)
        projection = new ProjectionMatrixBuffer("EldenCraft coherent avatar projection");
      modelView.set(camera.viewRotationMatrix);
      var lens = new Matrix4f(camera.projectionMatrix);
      RenderSystem.setProjectionMatrix(projection.getBuffer(lens), ProjectionType.PERSPECTIVE);
      try (var prepared = renderer.featureRenderDispatcher().prepareFrame(storage)) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
        try (var pass =
            encoder.createRenderPass(
                () -> "EldenCraft real F5 avatar",
                target.getColorTextureView(),
                Optional.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty())) {
          RenderSystem.bindDefaultUniforms(pass);
          FeatureRenderDispatcher.renderAllFeatures(pass, prepared);
        }
      }
      FrameExporter.captureAvatar(target, new Matrix4f(lens).invert());
    } catch (RuntimeException failure) {
      failed = true;
      org.slf4j.LoggerFactory.getLogger("eldencraft_avatar")
          .error("Coherent avatar layer disabled", failure);
    } finally {
      RenderSystem.setProjectionMatrix(previousProjection, previousType);
      modelView.popMatrix();
      // Hand/HUD must never contain a second copy of the avatar.
      var encoder = RenderSystem.getDevice().createCommandEncoder();
      // JOML's zero-argument vector has w=1: spell alpha out for transparency.
      encoder.clearColorTexture(target.getColorTexture(), new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
      encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
    }
  }

  /** Torrent drawn at the host feet with its own heading and stride, like the avatar. */
  public static void alignTorrent(Entity entity, HorseRenderState state) {
    var camera = HostController.frame();
    var avatar = HostController.avatar();
    var torrent = HostController.torrent();
    if (!SceneCapture.active()
        || camera == null
        || avatar == null
        || torrent == null
        || !WorldTorrent.is(entity)) return;
    state.x = avatar.x();
    state.y = avatar.y();
    state.z = avatar.z();
    state.bodyRot = torrent.yaw();
    state.yRot = 0;
    state.xRot = 0;
    state.walkAnimationPos = torrent.walkPhase();
    state.walkAnimationSpeed = torrent.walkSpeed();
    state.variant = Variant.BLACK;
    state.markings = Markings.NONE;
    state.isRidden = true;
    state.standAnimation = 0;
    state.eatAnimation = 0;
    state.feedingAnimation = 0;
    state.hasRedOverlay = WorldTorrent.recentlyHurt();
    state.nameTag = null;
    state.scoreText = null;
    state.shadowRadius = 0;
    state.shadowPieces.clear();
    double dx = state.x - camera.x(), dy = state.y - camera.y(), dz = state.z - camera.z();
    state.distanceToCameraSq = dx * dx + dy * dy + dz * dz;
    torrentState = state;
  }

  private static float wrap(float angle) {
    return (float) (angle - Math.floor((angle + 180) / 360) * 360);
  }

  public static void close() {
    torrentState = null;
    if (projection != null) {
      projection.close();
      projection = null;
    }
    failed = false;
  }

  public static void align(Avatar entity, AvatarRenderState state) {
    var client = Minecraft.getInstance();
    var camera = HostController.frame();
    var avatar = HostController.avatar();
    if (!SceneCapture.active()
        || entity != client.player
        || camera == null
        || camera.firstPerson()
        || avatar == null
        || state.isSpectator
        || !entity.isAlive()) return;

    // Simulation follows 20 Hz server snapshots; rendering follows the 60 Hz host camera.
    // Correct only the extracted state. Never move the server/player to animate a model.
    state.x = avatar.x();
    state.y = avatar.y();
    state.z = avatar.z();
    var motion = avatar.motion();
    state.bodyRot = motion.bodyYaw();
    state.yRot = motion.headYaw();
    state.xRot = motion.pitch();
    state.walkAnimationPos = motion.walkPhase();
    state.walkAnimationSpeed = motion.walkSpeed();
    state.isCrouching = avatar.crouching();
    var torrent = HostController.torrent();
    if (torrent != null && WorldTorrent.clientTorrent() != null) {
      // Seated on the saddle facing Torrent's heading; the head keeps the rider's look.
      float look = motion.bodyYaw() + motion.headYaw();
      state.y += TorrentPolicy.RIDER_LIFT;
      state.isPassenger = true;
      state.isCrouching = false;
      state.bodyRot = torrent.yaw();
      state.yRot = Math.clamp(wrap(look - torrent.yaw()), -90, 90);
      state.walkAnimationPos = 0;
      state.walkAnimationSpeed = 0;
    }
    state.nameTag = null;
    state.scoreText = null;
    // Cached shadow terrain is not an accurate receiving surface for a vanilla blob shadow.
    state.shadowRadius = 0;
    state.shadowPieces.clear();
    double dx = state.x - camera.x(), dy = state.y - camera.y(), dz = state.z - camera.z();
    state.distanceToCameraSq = dx * dx + dy * dy + dz * dz;
  }
}
