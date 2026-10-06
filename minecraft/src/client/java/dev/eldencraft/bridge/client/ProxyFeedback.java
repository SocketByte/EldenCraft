package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.ParticleEngineAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Relocates actual vanilla attack feedback into the visible host coordinate frame, without
 * predicting damage.
 */
public final class ProxyFeedback {
  private record Event(
      ProxyProtocol.Frame frame, Vec3 visualBase, ProxyCombatAuthority.Feedback feedback) {}

  private static final ProxyEvents<Event> EVENTS = new ProxyEvents<>();
  private static final ThreadLocal<ParticleEngine> ROUTE = new ThreadLocal<>();
  private static ParticleEngine particles;
  private static Object world;
  private static long session;
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_proxy");

  private ProxyFeedback() {}

  public static void enqueue(
      long session,
      ProxyProtocol.Frame frame,
      Vec3 visualBase,
      ProxyCombatAuthority.Feedback feedback) {
    EVENTS.offer(session, System.nanoTime(), new Event(frame, visualBase, feedback));
  }

  public static boolean route(
      ParticleOptions type, double x, double y, double z, double dx, double dy, double dz) {
    var engine = ROUTE.get();
    if (engine == null) return false;
    engine.createParticle(type, x, y, z, dx, dy, dz);
    return true;
  }

  public static void tick(Minecraft client, long activeSession) {
    if (activeSession == 0
        || client.level == null
        || client.player == null
        || client.getConnection() == null) {
      clear();
      return;
    }
    if (world != client.level || session != activeSession) {
      if (particles != null) particles.clearParticles();
      particles =
          new ParticleEngine(
              client.level,
              ((ParticleEngineAccessor) client.particleEngine).eldencraft$resources());
      world = client.level;
      session = activeSession;
    }
    ROUTE.set(particles);
    try {
      Event event;
      int consumed = 0;
      while (consumed++ < 32 && (event = EVENTS.poll(activeSession, System.nanoTime())) != null) {
        var feedback = event.feedback;
        var offset = event.visualBase.subtract(feedback.origin());
        for (var sound : feedback.sounds()) {
          // The original server-position sound was captured, so this is its single audible
          // emission.
          client
              .getSoundManager()
              .play(
                  new SimpleSoundInstance(
                      sound,
                      SoundSource.PLAYERS,
                      1,
                      1,
                      particles.getRandom(),
                      event.visualBase.x,
                      event.visualBase.y,
                      event.visualBase.z));
        }
        for (var packet : feedback.particles()) {
          var translated =
              new ClientboundLevelParticlesPacket(
                  packet.particle(),
                  packet.overrideLimiter(),
                  packet.alwaysShow(),
                  packet.x() + offset.x,
                  packet.y() + offset.y,
                  packet.z() + offset.z,
                  packet.xDist(),
                  packet.yDist(),
                  packet.zDist(),
                  packet.xMaxSpeed(),
                  packet.yMaxSpeed(),
                  packet.zMaxSpeed(),
                  packet.count(),
                  packet.randomizationType());
          // Keep the actual game's packet distribution and actual sprite providers/renderers.
          client.getConnection().handleParticleEvent(translated);
        }
        for (var animation : feedback.animations()) {
          for (var target : event.frame.targets()) {
            var sharedId = SharedWorldClient.proxyUuid(target.handle());
            if (!(sharedId == null ? target.uuid(event.frame.epoch()) : sharedId)
                .equals(animation.target())) continue;
            // A detached visual recipient gives vanilla TrackingEmitter the translated bounds.
            var visual = new CombatProxyEntity(ProxyEntities.TYPE, client.level);
            var min = target.min();
            var max = target.max();
            var base = event.visualBase;
            visual.setHostBounds(
                new net.minecraft.world.phys.AABB(
                    base.x + min.x(),
                    base.y + min.y(),
                    base.z + min.z(),
                    base.x + max.x(),
                    base.y + max.y(),
                    base.z + max.z()));
            particles.createTrackingEmitter(visual, animation.particle());
            break;
          }
        }
        if (!feedback.sounds().isEmpty()
            || !feedback.particles().isEmpty()
            || !feedback.animations().isEmpty())
          LOG.info(
              "Vanilla proxy feedback: sounds={}, particlePackets={}, animations={}",
              feedback.sounds().size(),
              feedback.particles().size(),
              feedback.animations().size());
      }
      particles.tick();
    } finally {
      ROUTE.remove();
    }
  }

  public static void render(RenderTarget target) {
    var pose = HostController.frame();
    if (particles == null || pose == null || !FrameExporter.exporting()) return;
    var client = Minecraft.getInstance();
    var renderer = client.gameRenderer;
    var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
    var state = new ParticlesRenderState();
    particles.extract(
        state, camera.cullFrustum, renderer.mainCamera(), camera.cameraEntityPartialTicks);
    if (state.particles.isEmpty()) return;
    var storage = new SubmitNodeStorage();
    state.submit(storage, camera);
    var modelView = RenderSystem.getModelViewStack();
    modelView.pushMatrix();
    try {
      modelView.mul(camera.viewRotationMatrix);
      try (var prepared = renderer.featureRenderDispatcher().prepareFrame(storage)) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (pose.firstPerson() && !SceneCapture.active())
          encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
        try (var pass =
            encoder.createRenderPass(
                () -> "EldenCraft vanilla combat particles",
                target.getColorTextureView(),
                Optional.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty())) {
          RenderSystem.bindDefaultUniforms(pass);
          FeatureRenderDispatcher.renderAllFeatures(pass, prepared);
        }
      }
    } finally {
      modelView.popMatrix();
      state.reset();
    }
  }

  public static void clear() {
    EVENTS.clear();
    if (particles != null) particles.clearParticles();
    particles = null;
    world = null;
    session = 0;
  }
}
