package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.WorldFireworkAccessor;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;

/** Real server glide, creative, fluid and climb travel; native collision owns final position. */
public final class WorldFlight {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_flight");

  private record Input(
      UUID player,
      long pid,
      long map,
      long nanos,
      long presses,
      long creativePresses,
      int buttons,
      float yaw,
      float pitch) {}

  private record Context(
      MinecraftServer server, UUID player, long pid, long epoch, long map, long session) {}

  private record Published(Context context, long nanos, JsonObject json) {}

  private static volatile Input input;
  private static volatile Published published;
  private static long presses, creativePresses;
  // Everything below is integrated-server-thread owned.
  private static Context context;
  private static ServerPlayer controlled;
  private static final FlightPolicy.Edges EDGES = new FlightPolicy.Edges();
  private static final FlightPolicy.CreativeToggle CREATIVE_TOGGLE =
      new FlightPolicy.CreativeToggle();
  private static final FlightPolicy.CollisionFeedback COLLISION =
      new FlightPolicy.CollisionFeedback();
  private static WorldProtocol.Host host;
  private static long readNanos, clock, sequence, physicsTick = -1;
  private static WorldOrigin.Vec previousFeet;
  private static long previousTime;
  private static boolean reported;
  private static String travel = "none";
  private static Vec3 requested = Vec3.ZERO;
  private static boolean motionRecorded;
  private static boolean horizontalBlocked;
  private static String reportedTravel = "none";
  private static boolean creativeAirborne;

  private WorldFlight() {}

  public static void input(
      Minecraft c, HostState.Snapshot frame, int buttons, int pressed, int airPressed) {
    if (frame == null
        || !frame.active()
        || c.player == null
        || c.gui.screen() != null
        || !SharedWorldClient.active()) {
      releaseInput();
      return;
    }
    if ((airPressed & HostState.JUMP) != 0) presses++;
    if ((pressed & HostState.JUMP) != 0) creativePresses++;
    input =
        new Input(
            c.player.getUUID(),
            frame.publisherPid(),
            frame.mapId(),
            System.nanoTime(),
            presses,
            creativePresses,
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
            && !h.nativeLadder()
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
      CREATIVE_TOGGLE.baseline(i.creativePresses);
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
      if (p.isFallFlying() || !travel.equals("none")) {
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
        horizontalBlocked = clipped[0] != v.x || clipped[2] != v.z;
      }
    }
    if (h.millis() != previousTime) {
      previousFeet = h.feet();
      previousTime = h.millis();
    }
    boolean mayFly = p.getAbilities().mayfly && !WorldTorrent.rides(p);
    if (CREATIVE_TOGGLE.take(i.creativePresses, i.nanos, mayFly)) {
      p.getAbilities().flying = !p.getAbilities().flying;
      p.onUpdateAbilities();
      creativeAirborne = false;
    }
    if (p.getAbilities().flying && mayFly) {
      // Ignore the lingering takeoff contact; a later touchdown ends vanilla flight.
      creativeAirborne |= !h.grounded();
      if (creativeAirborne && h.grounded()) {
        p.getAbilities().flying = false;
        p.onUpdateAbilities();
        creativeAirborne = false;
      }
    } else creativeAirborne = false;
    boolean creative = mayFly && p.getAbilities().flying;
    // On Torrent the air press is its double jump, never an elytra opening.
    if (EDGES.take(
        i.presses,
        !creative
            && !h.grounded()
            && !p.isFallFlying()
            && travel.equals("none")
            && !WorldTorrent.rides(p))) {
      p.setDeltaMovement(measured);
      p.tryToStartFallFlying(); // Genuine equipped component, liquid, levitation and passenger
      // checks.
    }
    if (h.grounded() && p.isFallFlying()) p.stopFallFlying();
    if (!creative && !p.isFallFlying() && travel.equals("none")) p.setDeltaMovement(Vec3.ZERO);
    p.setNoGravity(creative || !p.isFallFlying() && travel.equals("none"));
    physicsTick = -1;
    requested = Vec3.ZERO;
    motionRecorded = false;
  }

  /** After vanilla fluid contact/server AI, before jump and travel in LivingEntity.aiStep. */
  public static void prepareTravel(LivingEntity entity) {
    if (entity != controlled) return;
    var p = controlled;
    var i = input;
    if (!SharedWorldClient.controlsPlayer(p) || !matches(i, context, host)) {
      p.xxa = p.zza = 0;
      p.setJumping(false);
      p.setNoGravity(true);
      return;
    }
    String next =
        WorldTorrent.rides(p)
            ? "none"
            : p.getAbilities().mayfly && p.getAbilities().flying
                ? "creative"
                : p.isInWater()
                    ? "water"
                    : p.isInLava() ? "lava" : p.onClimbable() ? "climb" : "none";
    if (!next.equals(travel)) {
      // Mode changes cannot retain an earlier climb/swim command.
      p.setDeltaMovement(Vec3.ZERO);
      COLLISION.reset();
      horizontalBlocked = false;
    }
    travel = next;
    p.setShiftKeyDown((i.buttons & HostState.SNEAK) != 0);
    if (!travel.equals("none") && p.isFallFlying()) p.stopFallFlying();
    boolean special = !travel.equals("none");
    boolean creative = travel.equals("creative");
    p.setNoGravity(creative || !special && !p.isFallFlying());
    if (!special) {
      p.setJumping(false);
      p.setSprinting(false);
      p.xxa = p.zza = 0;
      return;
    }
    var axis =
        TravelPolicy.input(
            (i.buttons & HostState.FORWARD) != 0,
            (i.buttons & HostState.BACKWARD) != 0,
            (i.buttons & HostState.LEFT) != 0,
            (i.buttons & HostState.RIGHT) != 0,
            !creative && (i.buttons & HostState.SNEAK) != 0,
            p.isUsingItem());
    p.xxa = (float) axis[0];
    p.zza = (float) axis[1];
    p.setJumping(!creative && (i.buttons & HostState.JUMP) != 0);
    p.setShiftKeyDown((i.buttons & HostState.SNEAK) != 0);
    p.setSprinting((i.buttons & HostState.SPRINT) != 0 && p.zza > 0 && !p.isShiftKeyDown());
    if (creative) {
      // LocalPlayer normally supplies this impulse, but the host owns its keys and position.
      // Keep vanilla Player.travel's acceleration and drag, and send the captured move to native.
      p.setOnGround(false);
      p.setDeltaMovement(
          p.getDeltaMovement()
              .add(
                  0,
                  FlightPolicy.creativeLift(
                      (i.buttons & HostState.JUMP) != 0,
                      (i.buttons & HostState.SNEAK) != 0,
                      p.getAbilities().getFlyingSpeed()),
                  0));
    }
    // The stand-in never calls Entity.move: feed native wall contact back into
    // vanilla's climb-up/step-out tests instead of using its stale noPhysics flags.
    var direction = TravelPolicy.horizontal(axis[0], axis[1], p.getYRot());
    boolean pressingSurface =
        travel.equals("climb")
            && Math.hypot(direction[0], direction[1]) > 0
            && !p.level()
                .noCollision(p, p.getBoundingBox().move(direction[0] * .2, 0, direction[1] * .2));
    p.horizontalCollision = horizontalBlocked || pressingSurface;
  }

  /** Vanilla center-cell detection misses a ladder just outside Havok's contact skin. */
  public static boolean climbContact(LivingEntity entity) {
    if (entity != controlled || !ownsTravelContext(entity) || WorldTorrent.rides(controlled))
      return false;
    var box = entity.getBoundingBox().inflate(.05, 0, .05);
    // Feet contact only: a ladder above the player's head must not catch them.
    for (var pos :
        BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.minY + .05, box.maxZ))) {
      if (entity.level().getBlockState(pos).is(BlockTags.CLIMBABLE)) return true;
    }
    return false;
  }

  private static boolean ownsTravelContext(LivingEntity e) {
    return e == controlled
        && context != null
        && SharedWorldClient.controlsPlayer(controlled)
        && matches(input, context, host);
  }

  public static void climbRequested(LivingEntity e, Vec3 movement) {
    if (ownsTravel(e) && travel.equals("climb") && motionRecorded) {
      // Vanilla generates the upward ladder impulse AFTER its move call.
      // Carry that impulse now: native collision, rather than a deferred guest
      // move on the following tick, is the consumer of this step.
      requested = movement;
    }
  }

  private static boolean ownsTravel(LivingEntity e) {
    return e == controlled
        && context != null
        && SharedWorldClient.controlsPlayer(controlled)
        && matches(input, context, host)
        && (controlled.isFallFlying() || !travel.equals("none"));
  }

  public static void requested(LivingEntity e, Vec3 movement) {
    if (ownsTravel(e)) {
      requested = movement;
      motionRecorded = true;
    }
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
    // Native ladders intentionally clear this travel driver, but the shared
    // stand-in must still follow the host instead of integrating a second move.
    return e instanceof ServerPlayer p && (e == controlled || SharedWorldClient.controlsPlayer(p));
  }

  public static void computed(LivingEntity e) {
    if (ownsTravel(e) && motionRecorded) physicsTick = ((ServerPlayer) e).level().getGameTime();
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
    var motion = requested.scale(20);
    boolean special =
        !travel.equals("none")
            && physicsTick == p.level().getGameTime()
            && TravelPolicy.velocity(motion.x, motion.y, motion.z);
    if (!travel.equals("none") && !special) p.setDeltaMovement(Vec3.ZERO);
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
    json.addProperty("travel", special ? travel : "none");
    json.add(
        "velocity",
        WorldProtocol.vector(
            glide
                ? new WorldOrigin.Vec(v.x, v.y, v.z)
                : special
                    ? new WorldOrigin.Vec(motion.x, motion.y, motion.z)
                    : new WorldOrigin.Vec(0, 0, 0)));
    published = new Published(c, System.nanoTime(), json);
    String mode = special ? travel : "none";
    if (!mode.equals(reportedTravel)) {
      reportedTravel = mode;
      LOG.info("Vanilla fluid/climb travel {}: velocity={} m/s", mode, motion);
    }
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
      controlled.setJumping(false);
      controlled.setShiftKeyDown(false);
      controlled.setSprinting(false);
      controlled.xxa = controlled.zza = 0;
      controlled = null;
      context = null;
      host = null;
      previousFeet = null;
      previousTime = 0;
      physicsTick = -1;
      published = null;
      COLLISION.reset();
      travel = "none";
      requested = Vec3.ZERO;
      motionRecorded = false;
      horizontalBlocked = false;
      reportedTravel = "none";
      creativeAirborne = false;
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
