package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.WorldFireworkAccessor;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Real server gliding/durability and real attached rockets; native collision owns final position.
 */
public final class WorldFlight {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_flight");

  private record Input(
      UUID player,
      long pid,
      long map,
      long nanos,
      long presses,
      int buttons,
      float yaw,
      float pitch) {}

  private record Context(
      MinecraftServer server, UUID player, long pid, long epoch, long map, long session) {}

  private record Published(Context context, long nanos, JsonObject json) {}

  private static volatile Input input;
  private static volatile Published published;
  private static long presses;
  // Everything below is integrated-server-thread owned.
  private static Context context;
  private static ServerPlayer controlled;
  private static final FlightPolicy.Edges EDGES = new FlightPolicy.Edges();
  private static final FlightPolicy.CollisionFeedback COLLISION =
      new FlightPolicy.CollisionFeedback();
  private static WorldProtocol.Host host;
  private static long readNanos, clock, sequence, physicsTick = -1;
  private static WorldOrigin.Vec previousFeet;
  private static long previousTime;
  private static boolean reported;

  private WorldFlight() {}

  public static void input(Minecraft c, HostState.Snapshot frame, int buttons, int pressed) {
    if (frame == null
        || !frame.active()
        || c.player == null
        || c.gui.screen() != null
        || !SharedWorldClient.active()) {
      releaseInput();
      return;
    }
    if ((pressed & HostState.JUMP) != 0) presses++;
    input =
        new Input(
            c.player.getUUID(),
            frame.publisherPid(),
            frame.mapId(),
            System.nanoTime(),
            presses,
            buttons,
            c.player.getYRot(),
            c.player.getXRot());
  }

  public static void releaseInput() {
    input = null;
    published = null;
  }

  private static boolean matches(Input i, Context c, WorldProtocol.Host h) {
    return i != null
        && c != null
        && i.player.equals(c.player)
        && i.pid == c.pid
        && i.map == h.sourceMap()
        && FlightPolicy.fresh(i.nanos, System.nanoTime());
  }

  public static void beforeSync(
      ServerPlayer p, WorldProtocol.Host h, long session, long read, long ticks) {
    WorldFallSafety.claim(p);
    var next =
        new Context(p.level().getServer(), p.getUUID(), h.pid(), h.epoch(), h.map(), session);
    var i = input;
    boolean allowed =
        matches(i, next, h)
            && !p.level().getServer().isPublished()
            && p.isAlive()
            && !p.isSpectator()
            && p.level().dimension().equals(SharedWorldBlocks.DIMENSION);
    if (!allowed) {
      clear(null);
      p.setDeltaMovement(Vec3.ZERO);
      p.setNoGravity(true);
      return;
    }
    if (!next.equals(context) || controlled != p) {
      clear(controlled);
      context = next;
      controlled = p;
      EDGES.baseline(i.presses);
    }
    host = h;
    readNanos = read;
    clock = ticks;
    p.setYRot(i.yaw);
    p.setXRot(i.pitch);
    Vec3 measured = Vec3.ZERO;
    if (previousFeet != null && h.millis() > previousTime && h.millis() - previousTime <= 250) {
      double scale = 50.0 / (h.millis() - previousTime);
      measured =
          new Vec3(
              (h.feet().x() - previousFeet.x()) * scale,
              (h.feet().y() - previousFeet.y()) * scale,
              (h.feet().z() - previousFeet.z()) * scale);
      if (!FlightPolicy.velocity(measured.x * 20, measured.y * 20, measured.z * 20))
        measured = Vec3.ZERO;
      if (p.isFallFlying()) {
        var v = p.getDeltaMovement();
        var clipped =
            COLLISION.reconcile(
                new double[] {v.x, v.y, v.z},
                new double[] {
                  h.feet().x() - previousFeet.x(),
                  h.feet().y() - previousFeet.y(),
                  h.feet().z() - previousFeet.z()
                });
        p.setDeltaMovement(clipped[0], clipped[1], clipped[2]);
      }
    }
    if (h.millis() != previousTime) {
      previousFeet = h.feet();
      previousTime = h.millis();
    }
    // On Torrent the air press is its double jump, never an elytra opening.
    if (EDGES.take(i.presses, !h.grounded() && !p.isFallFlying() && !WorldTorrent.rides(p))) {
      p.setDeltaMovement(measured);
      p.tryToStartFallFlying(); // Genuine equipped component, liquid, levitation and passenger
      // checks.
    }
    if (h.grounded() && p.isFallFlying()) p.stopFallFlying();
    if (!p.isFallFlying()) p.setDeltaMovement(Vec3.ZERO);
    p.setNoGravity(!p.isFallFlying());
    physicsTick = -1;
  }

  public static boolean ownsMotion(LivingEntity e) {
    return e == controlled
        && e instanceof ServerPlayer p
        && p.isFallFlying()
        && context != null
        && SharedWorldClient.controlsPlayer(p)
        && matches(input, context, host);
  }

  // A loss arriving between START tick and vanilla travel must not briefly
  // turn the still-owned stand-in into a second position producer. END tick
  // stops gliding/clears this identity; it does not replay stale velocity.
  public static boolean delegatesPosition(LivingEntity e) {
    return e == controlled && e instanceof ServerPlayer;
  }

  public static void computed(LivingEntity e) {
    if (ownsMotion(e)) physicsTick = ((ServerPlayer) e).level().getGameTime();
  }

  public static boolean ownedRocket(Entity entity) {
    if (!(entity instanceof FireworkRocketEntity rocket)
        || controlled == null
        || !ownsMotion(controlled)) return false;
    return ((WorldFireworkAccessor) rocket).eldencraft$attachedToEntity() == controlled;
  }

  public static boolean allowRocketBoost(LivingEntity target) {
    if (!target.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return true;
    if (target instanceof ServerPlayer p && WorldFallSafety.owns(p)) return ownsMotion(p);
    if (target.level().isClientSide() && target == Minecraft.getInstance().player) {
      var i = input;
      return SharedWorldClient.kinematicsActive()
          && i != null
          && FlightPolicy.fresh(i.nanos, System.nanoTime());
    }
    return true;
  }

  public static boolean permitRocket(Player player) {
    if (!player.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return true;
    var i = input;
    return i != null
        && FlightPolicy.fresh(i.nanos, System.nanoTime())
        && (i.buttons & HostState.USE) != 0
        && i.player.equals(player.getUUID())
        && SharedWorldClient.controlsPlayer(player)
        && (!(player instanceof ServerPlayer) || ownsMotion(player));
  }

  public static void endTick(MinecraftServer server) {
    var p = controlled;
    var c = context;
    var h = host;
    if (p == null || c == null || c.server != server) return;
    if (!SharedWorldClient.controlsPlayer(p) || !matches(input, c, h)) {
      clear(p);
      return;
    }
    var v = p.getDeltaMovement().scale(20);
    boolean glide =
        p.isFallFlying()
            && physicsTick == p.level().getGameTime()
            && FlightPolicy.velocity(v.x, v.y, v.z);
    if (p.isFallFlying() && !glide) {
      p.stopFallFlying();
      p.setDeltaMovement(Vec3.ZERO);
    }
    long timestamp = clock + (System.nanoTime() - readNanos) / 1_000_000;
    var json = new JsonObject();
    json.addProperty("sequence", ++sequence);
    json.addProperty("time_ms", timestamp);
    json.addProperty("observed_frame", h.frame());
    json.addProperty("gliding", glide);
    json.add(
        "velocity",
        WorldProtocol.vector(
            glide ? new WorldOrigin.Vec(v.x, v.y, v.z) : new WorldOrigin.Vec(0, 0, 0)));
    published = new Published(c, System.nanoTime(), json);
    if (glide != reported) {
      reported = glide;
      LOG.info("Vanilla elytra {}: velocity={} m/s", glide ? "started" : "stopped", v);
    }
  }

  public static JsonObject snapshot(WorldProtocol.Host h, long session) {
    var p = published;
    if (p == null
        || input == null
        || !FlightPolicy.fresh(p.nanos, System.nanoTime())
        || p.context.pid != h.pid()
        || p.context.epoch != h.epoch()
        || p.context.map != h.map()
        || p.context.session != session) return null;
    return p.json.deepCopy();
  }

  public static void clear(ServerPlayer player) {
    if (controlled != null && (player == null || controlled == player)) {
      if (controlled.isFallFlying()) controlled.stopFallFlying();
      controlled.setDeltaMovement(Vec3.ZERO);
      controlled.setNoGravity(true);
      controlled = null;
      context = null;
      host = null;
      previousFeet = null;
      previousTime = 0;
      physicsTick = -1;
      published = null;
      COLLISION.reset();
      if (reported) {
        reported = false;
        LOG.info("Vanilla elytra released");
      }
    }
  }

  public static void serverStopped(MinecraftServer server) {
    if (context != null && context.server == server) clear(null);
  }
}
