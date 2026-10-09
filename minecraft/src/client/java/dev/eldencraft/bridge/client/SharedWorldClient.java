package dev.eldencraft.bridge.client;

import com.google.gson.*;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.WorldMobAccessor;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

/**
 * Real integrated-server world. Cross-thread data is immutable; all world writes happen on the
 * server tick.
 */
public final class SharedWorldClient implements WorldDamageAuthority.Adapter {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_world");
  private static final SharedWorldClient INSTANCE = new SharedWorldClient();
  private static final WorldMailbox MAILBOX = new WorldMailbox();
  private static final WorldEvents EVENTS = new WorldEvents();

  private record Lease(
      MinecraftServer server,
      UUID player,
      WorldProtocol.Host host,
      long session,
      long readNanos,
      long clock) {}

  private record Damage(
      Lease lease,
      long target,
      long generation,
      String kind,
      UUID source,
      long event,
      WorldOrigin.Vec position,
      float radius,
      float guestMax,
      JsonObject projectile) {}

  private static volatile Lease lease;
  private static volatile Lease completedLease;
  private static volatile Lease kinematicsLease;
  private static volatile WorldOrigin worldOrigin;
  private static volatile boolean serverReady;
  private static volatile boolean terrainDegraded;
  private static volatile JsonObject output;
  private static volatile long outputNanos;
  private static volatile boolean guiOpen;
  private static volatile WorldProtocol.Host lastHost;
  private static volatile Map<Long, UUID> proxyIds = Map.of();
  private MinecraftServer server;
  private WorldOrigins origins;
  private ServerPlayer controlled;
  private boolean savedNoGravity, savedNoPhysics;
  private final Map<Long, CombatProxyEntity> proxies = new LinkedHashMap<>();
  private final Map<Mob, Goal> goals = new IdentityHashMap<>();
  private Set<BlockPos> terrainCells = new HashSet<>();

  /** Chunk sections already cleared of terrain cells saved by earlier sessions. */
  private Set<Long> scrubbedSections = new HashSet<>();

  private AABB coverage;
  private long terrainRevision = -1, terrainEpoch, blocksRevision;
  private Lease terrainLease;
  private WorldTerrainReadiness.Complete completeTerrain;
  private boolean reportedDegraded;
  private final WorldIncoming incoming = new WorldIncoming();
  private final WorldTerrainLighting terrainLighting = new WorldTerrainLighting();
  private String blocksHash = "";

  /** Native wire bound: at most 4096 boxes per publication. */
  private static final int MAX_COLLIDER_BOXES = 4096, MAX_RAW_COLLIDER_BOXES = 131072;

  private Vec3 colliderFeet;
  private long colliderMillis;
  private long errorLogNanos;
  private boolean reportedReady;
  private boolean difficultyConfigured;
  private static Object clientPlayer;
  private static boolean clientGravity, clientPhysics;

  private SharedWorldClient() {}

  public static void initialize() {
    WorldDamageAuthority.adapter = INSTANCE;
    SharedTerrain.projectileShapeBypass =
        e -> WorldProjectiles.tracked(e) || WorldFlight.ownedRocket(e) || WorldNether.flyer(e);
    ServerTickEvents.START_SERVER_TICK.register(INSTANCE::serverTick);
    ServerTickEvents.END_SERVER_TICK.register(WorldFlight::endTick);
    ServerTickEvents.END_SERVER_TICK.register(WorldTorrent::endTick);
    // Campaign menus keep observing the host even when combat leases pause.
    ServerTickEvents.END_SERVER_TICK.register(
        game -> {
          CampaignProgression.serverTick(game);
          if (!CampaignConfig.current().enabled()) return;
          for (var player : game.getPlayerList().getPlayers()) {
            CampaignShops.serverTick(player);
            if (CampaignProgression.characterPermitted(player)
                && player.level().dimension().equals(SharedWorldBlocks.DIMENSION))
              CampaignBridge.publishCombat(
                  CampaignCombat.armorReduction(player),
                  0,
                  player.getMaxHealth(),
                  CampaignCombat.nativeGuardPermitted(player),
                  CampaignCombat.stamina(player),
                  player.isUsingItem(),
                  player.getAbsorptionAmount(),
                  CampaignTotem.held(player),
                  CampaignPotions.resistance(player));
          }
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          WorldProjectiles.clear();
          CampaignMotion.clear();
          CampaignWeapons.clear();
          WorldPotions.clear();
          WorldFlight.serverStopped(server);
          WorldTorrent.serverStopping(server);
          INSTANCE.terrainLighting.clear();
        });
  }

  private static boolean fresh(Lease l) {
    return l != null
        && WorldLeasePolicy.fresh(l.host.millis(), l.clock, l.readNanos, System.nanoTime());
  }

  private static boolean sameContext(Lease a, Lease b) {
    return a != null
        && b != null
        && a.server == b.server
        && a.player.equals(b.player)
        && a.session == b.session
        && a.host.epoch() == b.host.epoch()
        && a.host.map() == b.host.map()
        && a.host.pid() == b.host.pid();
  }

  public static boolean kinematicsActive() {
    var l = lease;
    var k = kinematicsLease;
    return fresh(l) && fresh(k) && sameContext(l, k);
  }

  public static boolean active() {
    var c = Minecraft.getInstance();
    return kinematicsActive()
        && c.level != null
        && c.level.dimension().equals(SharedWorldBlocks.DIMENSION);
  }

  public static boolean serverActive() {
    var l = lease;
    return serverReady
        && fresh(l)
        && sameContext(l, completedLease)
        && System.nanoTime() - outputNanos < 500_000_000L;
  }

  public static boolean terrainDegraded() {
    return terrainDegraded;
  }

  public static boolean combatReady() {
    var l = lease;
    return serverActive() && !terrainDegraded && l != null && l.host.terrainReady() && lease == l;
  }

  public static WorldOrigin origin() {
    return active() ? worldOrigin : null;
  }

  /** One coherent client-side identity for runtime mesh capture; no server objects escape. */
  public record BlockMeshContext(WorldOrigin origin, long hostPid, long session) {}

  public static BlockMeshContext blockMeshContext() {
    var l = lease;
    var o = worldOrigin;
    if (!active()
        || l == null
        || o == null
        || !sameContext(l, kinematicsLease)
        || o.epoch() != l.host.epoch()
        || o.map() != l.host.map()) return null;
    return new BlockMeshContext(o, l.host.pid(), l.session);
  }

  /** Server thread: the stable origin of the current map, or null before the first publication. */
  public static WorldOrigin serverOrigin() {
    return serverActive() ? worldOrigin : null;
  }

  /** Server thread: the simulated (sampled) box; mobs outside it do not tick. */
  public static AABB coverage() {
    return INSTANCE.coverage;
  }

  /**
   * Server thread: re-apply the sampled terrain on the next tick (cells restored after the Nether
   * closed).
   */
  public static void refreshTerrain() {
    INSTANCE.terrainRevision = -1;
  }

  /** Server thread: the offline stand-in currently driven by the host, or null. */
  public static ServerPlayer hostPlayer(MinecraftServer game) {
    var l = lease;
    if (l == null || l.server != game) return null;
    var p = game.getPlayerList().getPlayer(l.player);
    return p != null && controlsPlayer(p) ? p : null;
  }

  /** Latest host HP fraction for presentation (Nether dread), 1 when unknown. */
  public static float hostHealth() {
    var h = lastHost;
    return h == null || h.maxHp() <= 0 ? 1 : (float) Math.max(0, Math.min(1, h.hp() / h.maxHp()));
  }

  public static boolean inSharedDimension() {
    var c = Minecraft.getInstance();
    return c.level != null && c.level.dimension().equals(SharedWorldBlocks.DIMENSION);
  }

  /**
   * Only the owned offline stand-in follows host physics; all real MC mobs retain their collision.
   */
  public static boolean controlsPlayer(Player player) {
    var l = lease;
    if (!kinematicsActive()
        || l == null
        || !player.isAlive()
        || player.isSpectator()
        || !player.getUUID().equals(l.player)
        || !player.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return false;
    return player instanceof ServerPlayer p
        ? p.level().getServer() == l.server && !l.server.isPublished()
        : player == Minecraft.getInstance().player;
  }

  public static WorldOrigin.Vec toGuestPhysical(double x, double y, double z) {
    var l = lease;
    var o = origin();
    if (o == null
        || l == null
        || !sameContext(l, kinematicsLease)
        || o.epoch() != l.host.epoch()
        || o.map() != l.host.map()) return null;
    var d = l.host.sourceToRegion();
    return o.toGuest(x + d.x(), y + d.y(), z + d.z());
  }

  public static CombatProxyEntity proxy(long handle) {
    return INSTANCE.proxies.get(handle);
  }

  public static UUID proxyUuid(long handle) {
    return proxyIds.get(handle);
  }

  public static float pendingDamage(long handle) {
    var proxy = INSTANCE.proxies.get(handle);
    return proxy == null ? 0 : EVENTS.pending(handle, proxy.worldGeneration);
  }

  public static boolean owns(CombatProxyEntity proxy) {
    return INSTANCE.proxies.get(proxy.worldHandle) == proxy;
  }

  /** Captured authority for a launch/impact, read only on the integrated-server thread. */
  public record ProjectileContext(
      MinecraftServer server,
      UUID player,
      WorldProtocol.Host host,
      WorldOrigin origin,
      long session,
      long readNanos,
      long clock) {}

  public static ProjectileContext projectileContext(ServerPlayer player) {
    var l = lease;
    var o = worldOrigin;
    if (l == null
        || o == null
        || !combatReady()
        || guiOpen
        || !EVENTS.capacity()
        || !controlsPlayer(player)
        || !player.level().getServer().isSameThread()
        || l.server != player.level().getServer()
        || lease != l) return null;
    return new ProjectileContext(l.server, l.player, l.host, o, l.session, l.readNanos, l.clock);
  }

  public static boolean projectileCurrent(ProjectileContext c) {
    var l = lease;
    var o = worldOrigin;
    return c != null
        && l != null
        && o != null
        && combatReady()
        && !guiOpen
        && EVENTS.capacity()
        && c.server == l.server
        && c.player.equals(l.player)
        && c.session == l.session
        && c.host.pid() == l.host.pid()
        && c.host.epoch() == l.host.epoch()
        && c.host.map() == l.host.map()
        && c.origin.anchorId() == o.anchorId()
        && System.nanoTime() - c.readNanos >= 0
        && System.nanoTime() - c.readNanos < 500_000_000L
        && lease == l;
  }

  public static boolean containsProjectileDestination(AABB box) {
    var area = INSTANCE.coverage;
    return area != null
        && box.minX >= area.minX
        && box.maxX <= area.maxX
        && box.minY >= area.minY
        && box.maxY <= area.maxY
        && box.minZ >= area.minZ
        && box.maxZ <= area.maxZ;
  }

  public static long submitProjectile(ProjectileContext c, JsonObject event) {
    return projectileCurrent(c) ? EVENTS.addSequence(c.session, event) : 0;
  }

  public static void tick(Minecraft client) {
    guiOpen = client.gui.screen() != null;
    var host = MAILBOX.read();
    var game = client.getSingleplayerServer();
    var control = HostController.healthSnapshot(client);
    long now = MAILBOX.now();
    boolean valid =
        host != null
            && host.active()
            && host.hp() > 0
            && game != null
            && !game.isPublished()
            && client.player != null
            && client.player.isAlive()
            && !client.player.isSpectator()
            && client.level != null
            && control != null
            && control.publisherPid() == host.pid()
            && control.mapId() == host.sourceMap();
    var previous = lease;
    // Crossing an open-world tile updates the ECHS block and the world
    // snapshot's source block in separate publications. Keep the existing,
    // unrenewed lease through that short gap instead of resetting the session
    // (which used to release the elytra glide and every pending receipt).
    if (!valid && sourceTileTransition(host, control, previous, game, client)) return;
    if (valid
        && (previous == null
            || previous.server != game
            || !previous.player.equals(client.player.getUUID())
            || previous.host.epoch() != host.epoch()
            || previous.host.pid() != host.pid()
            || previous.host.map() != host.map())) {
      serverReady = false;
      kinematicsLease = null;
      output = null;
      outputNanos = 0;
      if (previous != null
          && (previous.server != game || !previous.player.equals(client.player.getUUID())))
        EVENTS.reset();
    }
    EVENTS.update(valid ? host : null, now);
    if (host != null) lastHost = host;
    lease =
        valid
            ? new Lease(
                game, client.player.getUUID(), host, EVENTS.session(), System.nanoTime(), now)
            : null;
    if (active() && worldOrigin != null) {
      var p = client.player;
      if (clientPlayer != p) {
        restoreClient();
        clientPlayer = p;
        clientGravity = p.isNoGravity();
        clientPhysics = p.noPhysics;
      }
      var at = worldOrigin.toGuest(host.feet().x(), host.feet().y(), host.feet().z());
      p.setNoGravity(true);
      p.noPhysics = true;
      p.setPos(at.x(), at.y(), at.z());
      p.setDeltaMovement(Vec3.ZERO);
      // The server owns creative landing. Client takeoff contact must not send a
      // vanilla ability packet that cancels flight before native sees its first step.
      p.setOnGround(host.grounded() && !p.getAbilities().flying);
    } else restoreClient();
    var snapshot = output;
    var reference = lastHost;
    if (reference != null) {
      boolean matches =
          snapshot != null
              && snapshot.get("epoch").getAsLong() == reference.epoch()
              && snapshot.get("map").getAsLong() == reference.map()
              && snapshot.get("host_pid").getAsLong() == reference.pid()
              && snapshot.get("session").getAsLong() == EVENTS.session();
      var json = matches ? snapshot.deepCopy() : new JsonObject();
      json.addProperty("epoch", reference.epoch());
      json.addProperty("map", reference.map());
      json.addProperty("host_pid", reference.pid());
      json.addProperty("session", EVENTS.session());
      if (!matches) {
        json.addProperty("observed_frame", reference.frame());
        json.addProperty("terrain_revision", reference.terrainRevision());
      }
      if (!json.has("blocks_revision")) json.addProperty("blocks_revision", 0);
      if (!json.has("blocks")) json.add("blocks", new JsonArray());
      if (!json.has("mobs")) json.add("mobs", new JsonArray());
      json.add("events", EVENTS.snapshot());
      if (!matches) json.addProperty("ack_incoming", 0);
      boolean kinematics = valid && kinematicsActive();
      json.addProperty("kinematics_active", kinematics);
      if (!kinematics) json.remove("combat");
      if (kinematics) json.addProperty("player_uuid", client.player.getUUID().toString());
      var flight = kinematics ? WorldFlight.snapshot(reference, EVENTS.session()) : null;
      if (flight != null) json.add("flight", flight);
      var torrent = kinematics ? WorldTorrent.snapshot(reference, EVENTS.session()) : null;
      if (torrent != null) json.add("torrent", torrent);
      else json.remove("torrent");
      // The native camera turns and bobs with the player's own Minecraft options.
      var options = client.options;
      var view = new JsonObject();
      view.addProperty("mouse_sensitivity", Math.max(0, Math.min(1, options.sensitivity().get())));
      view.addProperty("invert_x", options.invertMouseX().get());
      view.addProperty("invert_y", options.invertMouseY().get());
      view.addProperty("bob_view", options.bobView().get());
      view.addProperty("damage_tilt", Math.max(0, Math.min(1, options.damageTiltStrength().get())));
      json.add("view", view);
      MAILBOX.publish(json, valid && serverActive() && matches);
    }
  }

  static final long TILE_TRANSITION_NANOS = 250_000_000L;

  private static boolean sourceTileTransition(
      WorldProtocol.Host host,
      HostState.Snapshot control,
      Lease previous,
      MinecraftServer game,
      Minecraft client) {
    return host != null
        && host.active()
        && host.hp() > 0
        && control != null
        && previous != null
        && game != null
        && !game.isPublished()
        && client.player != null
        && control.publisherPid() == host.pid()
        && control.mapId() != host.sourceMap()
        && previous.server == game
        && previous.player.equals(client.player.getUUID())
        && previous.host.pid() == host.pid()
        && previous.host.epoch() == host.epoch()
        && previous.host.map() == host.map()
        && System.nanoTime() - previous.readNanos < TILE_TRANSITION_NANOS;
  }

  private static void restoreClient() {
    if (clientPlayer instanceof net.minecraft.client.player.LocalPlayer p) {
      p.setNoGravity(clientGravity);
      p.noPhysics = clientPhysics;
    }
    clientPlayer = null;
  }

  private void restoreServer() {
    TerrainMining.cancelJobs();
    kinematicsLease = null;
    if (controlled != null) {
      WorldFlight.clear(controlled);
      WorldTorrent.clear(controlled);
      controlled.fallDistance = 0;
      controlled.setNoGravity(savedNoGravity);
      controlled.noPhysics = savedNoPhysics;
    }
    controlled = null;
  }

  private void serverTick(MinecraftServer game) {
    WorldStartupSafety.tick(game);
    Lease l = lease;
    if (!fresh(l) || l.server != game || game.isPublished()) {
      serverReady = false;
      terrainDegraded = false;
      WorldProjectiles.revoke();
      restoreServer();
      return;
    }
    try {
      if (server != game) {
        restoreServer();
        server = game;
        origins = new WorldOrigins(game);
        proxies.clear();
        terrainCells = new HashSet<>();
        scrubbedSections = new HashSet<>();
        TerrainMaterials.clear();
        TerrainMining.clear();
        BlockWork.clear();
        terrainRevision = -1;
        terrainLease = null;
        completeTerrain = null;
        incoming.clear();
        clearGoals();
        difficultyConfigured = false;
      }
      incoming.enter(l.host.pid(), l.session, l.host.epoch());
      var player = game.getPlayerList().getPlayer(l.player);
      var level = game.getLevel(SharedWorldBlocks.DIMENSION);
      if (player == null || !player.isAlive() || player.isSpectator() || level == null) {
        serverReady = false;
        return;
      }
      if (!CampaignProgression.characterPermitted(player)) {
        serverReady = false;
        restoreServer();
        return;
      }
      configureLabDifficulty(game);
      var next = origins.get(l.host);
      boolean changed =
          worldOrigin == null
              || worldOrigin.epoch() != next.epoch()
              || worldOrigin.map() != next.map();
      if (changed) {
        clearProxies();
        terrainRevision = -1;
        terrainLease = null;
        completeTerrain = null;
      }
      worldOrigin = next;
      // Host physics/pose ownership does not depend on the terrain sampler
      // finishing its next window. Unknown terrain still pauses simulation
      // and damage below, without dropping camera, flight or render identity.
      var at = next.toGuest(l.host.feet().x(), l.host.feet().y(), l.host.feet().z());
      if (player.level() != level)
        player =
            player.teleport(
                new TeleportTransition(
                    level,
                    new Vec3(at.x(), at.y(), at.z()),
                    Vec3.ZERO,
                    player.getYRot(),
                    player.getXRot(),
                    TeleportTransition.DO_NOTHING));
      if (player == null) {
        serverReady = false;
        kinematicsLease = null;
        return;
      }
      if (controlled != player) {
        restoreServer();
        controlled = player;
        savedNoGravity = player.isNoGravity();
        savedNoPhysics = player.noPhysics;
      }
      TerrainMaterials.observeGround(
          l.host.groundMaterial(),
          BlockPos.containing(at.x(), at.y() - .2, at.z()).asLong(),
          l.host.grounded());
      player.noPhysics = true;
      player.setPos(at.x(), at.y(), at.z());
      player.setOnGround(l.host.grounded());
      CampaignMotion.observe(player, l.host);
      player.fallDistance = 0;
      player.setHealth(
          Math.max(
              .001f,
              Math.min(
                  player.getMaxHealth(),
                  l.host.hp() / l.host.maxHp() * player.getMaxHealth()
                      - EVENTS.pending(l.host.playerId(), l.host.playerGeneration()))));
      if (!sameContext(l, lease)) {
        serverReady = false;
        restoreServer();
        return;
      }
      kinematicsLease = l;
      WorldFlight.beforeSync(player, l.host, l.session, l.readNanos, l.clock);
      WorldTorrent.beforeSync(player, l.host, l.session, l.readNanos, l.clock);
      player.connection.resetPosition();
      var terrainContext =
          new WorldTerrainReadiness.Context(
              l.host.pid(), l.session, l.host.epoch(), l.host.map(), next.anchorId());
      if (l.host.terrainReady()) {
        if (!sameContext(l, terrainLease)
            || completeTerrain == null
            || !completeTerrain.bounds().equals(l.host.bounds())
            || terrainEpoch != l.host.epoch()
            || terrainRevision != l.host.terrainRevision()) {
          applyTerrain(level, next, l.host);
          terrainEpoch = l.host.epoch();
          terrainRevision = l.host.terrainRevision();
        }
        // Commit only after the entire shape application succeeds. Repeated reads
        // retain the producer's original timestamp instead of renewing this lease.
        completeTerrain =
            new WorldTerrainReadiness.Complete(
                terrainContext, l.host.bounds(), terrainRevision, l.host.millis());
        terrainLease = l;
        terrainDegraded = false;
      } else {
        long now = l.clock + (System.nanoTime() - l.readNanos) / 1_000_000;
        if (!sameContext(l, terrainLease)
            || !WorldTerrainReadiness.mayReuse(
                completeTerrain, terrainContext, l.host.feet(), now)) {
          serverReady = false;
          terrainDegraded = false;
          return;
        }
        terrainDegraded = true;
      }
      if (terrainDegraded != reportedDegraded) {
        reportedDegraded = terrainDegraded;
        LOG.info(
            "Shared terrain hold: active={}, appliedRevision={}, incomingRevision={}",
            terrainDegraded,
            terrainRevision,
            l.host.terrainRevision());
      }
      terrainLighting.tick(level);
      // Vanilla movement packets retain the client controller's interaction yaw/pitch,
      // including its front-view correction. A final render-camera direction is not body aim.
      // ER owns health; Minecraft resolves armor, shield and absorption against this live mirror.
      updateProxies(level, next, l.host);
      WorldProjectiles.maintain(player);
      if (!sameContext(l, lease)) {
        serverReady = false;
        return;
      }
      if (!terrainDegraded) applyIncoming(level, l);
      var snapshot = snapshot(level, player, l, next);
      snapshot.addProperty("epoch", l.host.epoch());
      snapshot.addProperty("map", l.host.map());
      snapshot.addProperty("host_pid", l.host.pid());
      snapshot.addProperty("observed_frame", l.host.frame());
      snapshot.addProperty("terrain_revision", terrainRevision);
      snapshot.addProperty("terrain_degraded", terrainDegraded);
      if (!sameContext(l, lease)) {
        serverReady = false;
        return;
      }
      output = snapshot;
      outputNanos = System.nanoTime();
      completedLease = l;
      serverReady = true;
      if (!reportedReady) {
        LOG.info(
            "Shared world ready: map={}, origin={}, terrainCells={}, targets={}",
            l.host.map(),
            next.anchorId(),
            terrainCells.size(),
            proxies.size());
        reportedReady = true;
      }
    } catch (Exception failure) {
      serverReady = false;
      terrainDegraded = false;
      completeTerrain = null;
      terrainLease = null;
      terrainRevision = -1;
      restoreServer();
      reportedReady = false;
      if (System.nanoTime() - errorLogNanos > 5_000_000_000L) {
        errorLogNanos = System.nanoTime();
        LOG.warn("Shared world paused", failure);
      }
    }
  }

  private void configureLabDifficulty(MinecraftServer game) {
    if (difficultyConfigured) return;
    difficultyConfigured = true;
    var data = game.getWorldData();
    // Difficulty belongs to a save, not an individual dimension. Change it
    // only in the explicitly named disposable lab, never another save.
    if (!game.isPublished()
        && "EldenCraft Passthrough Lab".equals(data.getLevelName())
        && data.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
      game.setDifficulty(net.minecraft.world.Difficulty.NORMAL, false);
      if (data.getDifficulty() == net.minecraft.world.Difficulty.NORMAL)
        LOG.info(
            "Shared world lab difficulty changed from Peaceful to Normal; vanilla hostile spawn"
                + " eggs are enabled.");
      else
        LOG.warn(
            "Shared world lab remains Peaceful because difficulty is locked; vanilla hostile spawn"
                + " eggs remain unavailable.");
    }
  }

  private void clearProxies() {
    for (var proxy : proxies.values()) proxy.discard();
    proxies.clear();
    proxyIds = Map.of();
  }

  private void clearGoals() {
    for (var entry : goals.entrySet())
      ((WorldMobAccessor) entry.getKey()).eldencraft$worldTargets().removeGoal(entry.getValue());
    goals.clear();
  }

  private void applyTerrain(ServerLevel level, WorldOrigin o, WorldProtocol.Host host) {
    var min = o.toGuest(host.bounds().min().x(), host.bounds().min().y(), host.bounds().min().z());
    var max = o.toGuest(host.bounds().max().x(), host.bounds().max().y(), host.bounds().max().z());
    coverage = new AABB(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    if (coverage.getXsize() > 64 || coverage.getYsize() > 64 || coverage.getZsize() > 64)
      throw new IllegalArgumentException("Terrain coverage too large");
    var pieces = new HashMap<BlockPos, List<AABB>>();
    long work = 0;
    // What each cell is made of: the first known body material, and whether it is a steep face.
    var cellBodies = new HashMap<Long, Integer>();
    var steepVotes = new HashMap<Long, Integer>();
    var materials = host.terrainMaterials();
    int boxIndex = -1;
    for (var box : host.terrain()) {
      boxIndex++;
      int body = materials.isEmpty() ? TerrainMaterials.NO_BODY : materials.get(boxIndex);
      var a = o.toGuest(box.min().x(), box.min().y(), box.min().z());
      var b = o.toGuest(box.max().x(), box.max().y(), box.max().z());
      var shape = new AABB(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()).intersect(coverage);
      if (shape.getXsize() <= 0 || shape.getYsize() <= 0 || shape.getZsize() <= 0) continue;
      work +=
          (long) (Math.ceil(shape.maxX) - Math.floor(shape.minX))
              * (long) (Math.ceil(shape.maxY) - Math.floor(shape.minY))
              * (long) (Math.ceil(shape.maxZ) - Math.floor(shape.minZ));
      if (work > 65536) throw new IllegalArgumentException("Terrain processing budget");
      for (var cursor :
          BlockPos.betweenClosed(
              BlockPos.containing(shape.minX, shape.minY, shape.minZ),
              BlockPos.containing(
                  Math.nextDown(shape.maxX),
                  Math.nextDown(shape.maxY),
                  Math.nextDown(shape.maxZ)))) {
        var pos = cursor.immutable();
        var local = shape.intersect(new AABB(pos)).move(-pos.getX(), -pos.getY(), -pos.getZ());
        var list = pieces.computeIfAbsent(pos, p -> new ArrayList<>());
        if (list.size() < SharedTerrain.MAX_PIECES) list.add(local);
        else throw new IllegalArgumentException("Terrain cell shape limit");
        if (body != TerrainMaterials.NO_BODY) cellBodies.putIfAbsent(pos.asLong(), body);
        boolean wall = Math.min(local.getXsize(), local.getZsize()) < .2 && local.getYsize() > .5;
        steepVotes.merge(pos.asLong(), wall ? 1 : -1, Integer::sum);
        if (pieces.size() > 32768) throw new IllegalArgumentException("Terrain cell budget");
      }
    }
    // The unseen edge is a simulation boundary for mobs and items, never inferred empty
    // traversable space. It is a marked state, not a sampled shape: players never see,
    // target or collide with it, and a placed block may replace it.
    var lo = BlockPos.containing(coverage.minX - 1, coverage.minY - 1, coverage.minZ - 1);
    var hi = BlockPos.containing(coverage.maxX, coverage.maxY, coverage.maxZ);
    var boundary = new HashSet<BlockPos>();
    for (var cursor : BlockPos.betweenClosed(lo, hi))
      if (cursor.getX() == lo.getX()
          || cursor.getX() == hi.getX()
          || cursor.getY() == lo.getY()
          || cursor.getY() == hi.getY()
          || cursor.getZ() == lo.getZ()
          || cursor.getZ() == hi.getZ()) {
        var pos = cursor.immutable();
        boundary.add(pos);
        pieces.remove(pos);
      }
    var shapes = new HashMap<Long, VoxelShape>();
    for (var entry : pieces.entrySet())
      shapes.put(entry.getKey().asLong(), SharedTerrain.shape(entry.getValue()));
    SharedTerrain.publish(shapes);
    var steepCells = new HashSet<Long>();
    steepVotes.forEach(
        (cell, votes) -> {
          if (votes > 0) steepCells.add(cell);
        });
    TerrainMaterials.publishTerrain(cellBodies, steepCells, host.materialTable());
    for (var old : terrainCells)
      if (!pieces.containsKey(old)
          && !boundary.contains(old)
          && level.getBlockState(old).is(SharedWorldBlocks.TERRAIN))
        level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
    var placed = new HashSet<BlockPos>();
    var surface = SharedWorldBlocks.TERRAIN.defaultBlockState();
    var edge = surface.setValue(ShadowTerrainBlock.BOUNDARY, true);
    for (var pos : pieces.keySet()) placeTerrain(level, pos, surface, placed);
    for (var pos : boundary) placeTerrain(level, pos, edge, placed);
    terrainCells = placed;
    scrubStaleTerrain(level, lo, hi, placed);
    terrainLighting.observe(level, placed);
  }

  private static void placeTerrain(
      ServerLevel level, BlockPos pos, BlockState target, Set<BlockPos> placed) {
    var state = level.getBlockState(pos);
    if (!state.isAir() && !state.is(SharedWorldBlocks.TERRAIN))
      return; // Real placed blocks always win.
    if (state != target) level.setBlock(pos, target, 3);
    placed.add(pos);
  }

  /**
   * Terrain cells saved by an earlier session are not in this session's tracking; inside the
   * covered box they would stay as stale obstacles. Clear each loaded chunk section once.
   */
  private void scrubStaleTerrain(ServerLevel level, BlockPos lo, BlockPos hi, Set<BlockPos> keep) {
    for (int sx = lo.getX() >> 4; sx <= hi.getX() >> 4; sx++)
      for (int sz = lo.getZ() >> 4; sz <= hi.getZ() >> 4; sz++) {
        var chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) continue; // Not loaded yet; a later update clears it.
        for (int sy = lo.getY() >> 4; sy <= hi.getY() >> 4; sy++) {
          long key = SectionPos.asLong(sx, sy, sz);
          if (!scrubbedSections.add(key)) continue;
          int index = chunk.getSectionIndexFromSectionY(sy);
          if (index < 0 || index >= chunk.getSections().length) continue;
          var section = chunk.getSection(index);
          if (!section.maybeHas(state -> state.is(SharedWorldBlocks.TERRAIN))) continue;
          int cleared = 0;
          for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
              for (int x = 0; x < 16; x++) {
                if (!section.getBlockState(x, y, z).is(SharedWorldBlocks.TERRAIN)) continue;
                var pos = new BlockPos((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                if (!keep.contains(pos)) {
                  level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                  cleared++;
                }
              }
          if (cleared > 0)
            LOG.info(
                "Cleared {} stale terrain cells saved by an earlier session in section {},{},{}",
                cleared,
                sx,
                sy,
                sz);
        }
      }
  }

  private void updateProxies(ServerLevel level, WorldOrigin o, WorldProtocol.Host host) {
    var retained = new HashSet<Long>();
    for (var t : host.targets()) {
      if (t.hp() <= 0) continue;
      retained.add(t.id());
      var entity = proxies.get(t.id());
      if (entity == null
          || entity.isRemoved()
          || entity.worldGeneration != t.generation()
          || entity.worldEpoch != host.epoch()) {
        if (entity != null) entity.discard();
        entity = ProxyEntities.TYPE.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) continue;
        entity.worldHandle = t.id();
        entity.worldGeneration = t.generation();
        entity.worldEpoch = host.epoch();
        proxies.put(t.id(), entity);
        level.addFreshEntity(entity);
      }
      float max = Math.min(1024, t.maxHp() / host.damageScale());
      entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Math.max(1, max));
      entity.setHealth(
          Math.max(
              .001f,
              Math.min(max, t.hp() / host.damageScale() - EVENTS.pending(t.id(), t.generation()))));
      var min = o.toGuest(t.min().x(), t.min().y(), t.min().z());
      var maxPos = o.toGuest(t.max().x(), t.max().y(), t.max().z());
      entity.setHostBounds(new AABB(min.x(), min.y(), min.z(), maxPos.x(), maxPos.y(), maxPos.z()));
      entity.setDeltaMovement(Vec3.ZERO);
    }
    var it = proxies.entrySet().iterator();
    while (it.hasNext()) {
      var e = it.next();
      if (!retained.contains(e.getKey())) {
        e.getValue().discard();
        it.remove();
      }
    }
    var ids = new HashMap<Long, UUID>();
    proxies.forEach((handle, entity) -> ids.put(handle, entity.getUUID()));
    proxyIds = Map.copyOf(ids);
  }

  /**
   * Collision boxes of real placed blocks (not shadow terrain) in the window, in Minecraft
   * coordinates. Sections without a real block are skipped by their palette.
   */
  private static List<ColliderMerge.Box> placedCollision(ServerLevel level, ColliderWindow w) {
    var boxes = new ArrayList<ColliderMerge.Box>();
    var pos = new BlockPos.MutableBlockPos();
    for (int sx = w.minX() >> 4; sx <= w.maxX() >> 4; sx++)
      for (int sz = w.minZ() >> 4; sz <= w.maxZ() >> 4; sz++) {
        var chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) continue;
        for (int sy = w.minY() >> 4; sy <= w.maxY() >> 4; sy++) {
          int index = chunk.getSectionIndexFromSectionY(sy);
          if (index < 0 || index >= chunk.getSections().length) continue;
          var section = chunk.getSection(index);
          if (section.hasOnlyAir()
              || !section.maybeHas(s -> !s.isAir() && !s.is(SharedWorldBlocks.TERRAIN))) continue;
          for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
              for (int x = 0; x < 16; x++) {
                int bx = (sx << 4) + x, by = (sy << 4) + y, bz = (sz << 4) + z;
                if (!w.contains(bx, by, bz)) continue;
                var state = section.getBlockState(x, y, z);
                if (state.isAir() || state.is(SharedWorldBlocks.TERRAIN)) continue;
                pos.set(bx, by, bz);
                for (var box : state.getCollisionShape(level, pos).toAabbs()) {
                  if (boxes.size() >= MAX_RAW_COLLIDER_BOXES) return boxes;
                  boxes.add(
                      new ColliderMerge.Box(
                          bx + box.minX,
                          by + box.minY,
                          bz + box.minZ,
                          bx + box.maxX,
                          by + box.maxY,
                          bz + box.maxZ));
                }
              }
        }
      }
    return boxes;
  }

  private void applyIncoming(ServerLevel level, Lease l) {
    if (l.host.guestSession() != l.session) return;
    long now = l.clock + (System.nanoTime() - l.readNanos) / 1_000_000;
    for (var hit : l.host.incoming()) {
      if (!incoming.consume(hit.sequence())) continue;
      if (hit.millis() > now || now - hit.millis() > 1000) continue;
      var target = level.getEntity(hit.uuid());
      if (!(target instanceof LivingEntity living)
          || target instanceof Player
          || target instanceof CombatProxyEntity
          || !living.isAlive()) continue;
      if (coverage == null || !coverage.intersects(living.getBoundingBox())) continue;
      var source = level.damageSources().generic();
      var attacker = proxies.get(WorldIncoming.nativeHandle(hit.source()));
      if (attacker != null
          && !attacker.isRemoved()
          && attacker.isAlive()
          && attacker.level() == level
          && attacker.worldEpoch == l.host.epoch())
        source = new DamageSource(source.typeHolder(), attacker);
      living.hurtServer(level, source, hit.damage());
    }
  }

  private JsonObject snapshot(ServerLevel level, ServerPlayer player, Lease l, WorldOrigin o) {
    var json = new JsonObject();
    json.addProperty("player_uuid", player.getUUID().toString());
    json.addProperty("session", l.session);
    // ACK and post-application HP are one immutable publication. Reading an
    // independent high-water on the client could pair a new ACK with old HP.
    json.addProperty("ack_incoming", incoming.ack(l.host.pid(), l.session, l.host.epoch()));
    var mobs = new JsonArray();
    var blocks = new JsonArray();
    var expiredGoals = goals.entrySet().iterator();
    while (expiredGoals.hasNext()) {
      var entry = expiredGoals.next();
      if (entry.getKey().isRemoved() || entry.getKey().level() != level) {
        ((WorldMobAccessor) entry.getKey()).eldencraft$worldTargets().removeGoal(entry.getValue());
        expiredGoals.remove();
      }
    }
    for (var entity : level.getEntitiesOfClass(LivingEntity.class, coverage.inflate(1))) {
      if (entity instanceof Player
          || entity instanceof CombatProxyEntity
          || WorldTorrent.is(entity)
          || !entity.isAlive()) continue;
      if (mobs.size() >= 64) break;
      if (entity instanceof net.minecraft.world.entity.monster.Monster mob
          && !(mob instanceof NeutralMob)
          && !goals.containsKey(mob)
          && !mob.getGoalSelector().getAvailableGoals().isEmpty()) {
        var goal = new BossTargetGoal(mob);
        goals.put(mob, goal);
        ((WorldMobAccessor) mob).eldencraft$worldTargets().addGoal(0, goal);
        mob.setPersistenceRequired();
      }
      var m = new JsonObject();
      m.addProperty("uuid", entity.getUUID().toString());
      m.addProperty("kind", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
      m.add(
          "position", WorldProtocol.vector(o.toHost(entity.getX(), entity.getY(), entity.getZ())));
      m.add(
          "velocity",
          WorldProtocol.vector(
              entity.getDeltaMovement().x,
              entity.getDeltaMovement().y,
              entity.getDeltaMovement().z));
      m.addProperty("hp", entity.getHealth());
      m.addProperty("max_hp", entity.getMaxHealth());
      m.addProperty("radius", entity.getBbWidth() / 2);
      m.addProperty("height", entity.getBbHeight());
      mobs.add(m);
    }
    // Native colliders: every placed block's collision near the player and ahead of
    // their motion, merged into seamless boxes and published nearest first.
    var feet = player.position();
    long millis = l.host.millis();
    double vx = 0, vy = 0, vz = 0;
    if (colliderFeet != null && millis > colliderMillis && millis - colliderMillis <= 250) {
      double seconds = (millis - colliderMillis) / 1000.0;
      vx = (feet.x - colliderFeet.x) / seconds;
      vy = (feet.y - colliderFeet.y) / seconds;
      vz = (feet.z - colliderFeet.z) / seconds;
    }
    colliderFeet = feet;
    colliderMillis = millis;
    var window = ColliderWindow.around(feet.x, feet.y, feet.z, vx, vy, vz);
    var merged =
        ColliderMerge.nearest(
            placedCollision(level, window), feet.x, feet.y, feet.z, MAX_COLLIDER_BOXES);
    for (int start = 0; start < merged.size(); start += 64) {
      var boxes = new JsonArray();
      for (var box : merged.subList(start, Math.min(merged.size(), start + 64))) {
        var min = o.toHost(box.minX(), box.minY(), box.minZ());
        var max = o.toHost(box.maxX(), box.maxY(), box.maxZ());
        boxes.add(JsonWire.vector(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
      }
      var b = new JsonObject();
      b.addProperty("key", "merged:" + (start / 64));
      b.addProperty("state", "eldencraft:merged_collision");
      b.add("boxes", boxes);
      blocks.add(b);
    }
    String hash = blocks.toString();
    if (!hash.equals(blocksHash)) {
      blocksHash = hash;
      blocksRevision++;
    }
    json.addProperty("blocks_revision", blocksRevision);
    json.add("blocks", blocks);
    json.add("mobs", mobs);
    var fluids = new JsonArray();
    for (var proxy : proxies.values()) {
      if (!owns(proxy) || !proxy.isAlive() || proxy.level() != level) continue;
      String medium = proxy.fluidContact();
      double speed = CampaignPotions.speed(proxy), attack = CampaignPotions.attackBonus(proxy);
      if (medium.equals("none") && speed == 1 && attack == 0) continue;
      var contact = new JsonObject();
      contact.addProperty("id", proxy.worldHandle);
      contact.addProperty("generation", proxy.worldGeneration);
      contact.addProperty("medium", medium);
      contact.addProperty("speed_scale", speed);
      contact.addProperty("attack_bonus", attack);
      contact.addProperty("time_ms", l.clock + (System.nanoTime() - l.readNanos) / 1_000_000);
      contact.addProperty("observed_frame", l.host.frame());
      contact.add(
          "position", WorldProtocol.vector(o.toHost(proxy.getX(), proxy.getY(), proxy.getZ())));
      fluids.add(contact);
    }
    json.add("fluids", fluids);
    json.add("projectiles", WorldProjectiles.snapshot(player));
    long timestamp = l.clock + (System.nanoTime() - l.readNanos) / 1_000_000;
    var combat = CampaignMotion.snapshot(player, l.host, timestamp);
    if (combat != null) json.add("combat", combat);
    return json;
  }

  @Override
  public Object begin(ServerLevel level, LivingEntity target, DamageSource source) {
    Lease l = lease;
    if (!combatReady()
        || !fresh(l)
        || lease != l
        || !l.host.terrainReady()
        || guiOpen
        || !EVENTS.capacity()
        || l.server != level.getServer()
        || !level.dimension().equals(SharedWorldBlocks.DIMENSION)) return null;
    Entity owner = source.getEntity();
    if (owner instanceof Projectile projectile) owner = projectile.getOwner();
    var blast = WorldDamageAuthority.blast();
    boolean projectile = source.getDirectEntity() instanceof Projectile;
    boolean playerSource =
        owner instanceof ServerPlayer p
            && p.getUUID().equals(l.player)
            && (projectile || blast != null);
    if ((!(owner instanceof Mob) && !playerSource)
        || owner instanceof CombatProxyEntity
        || owner.level() != level) return null;
    // The sampled box is only about 12 m tall; a Nether ghast looming above it still hits the
    // player.
    if (coverage == null
        || !coverage.intersects(owner.getBoundingBox()) && !WorldNether.flyer(owner)) return null;
    long id, generation;
    if (target instanceof CombatProxyEntity proxy) {
      id = proxy.worldHandle;
      generation = proxy.worldGeneration;
      if (proxy.worldEpoch != l.host.epoch() || !owns(proxy)) return null;
    } else if (target instanceof ServerPlayer player && player.getUUID().equals(l.player)) {
      id = l.host.playerId();
      generation = l.host.playerGeneration();
      if (id <= 0) return null;
    } else return null;
    String kind = blast != null ? "explosion" : projectile ? "projectile" : "mob_melee";
    JsonObject proof = null;
    if (playerSource && projectile && blast == null) {
      proof = WorldProjectiles.evidence((Projectile) source.getDirectEntity());
      if (proof == null) return null;
    }
    Vec3 point =
        blast == null
            ? (source.getDirectEntity() instanceof Projectile p
                ? WorldProjectiles.impact(p)
                : source.getDirectEntity() == null
                    ? owner.position()
                    : source.getDirectEntity().position())
            : blast.explosion().center();
    float radius = blast == null ? 0 : blast.explosion().radius();
    var pos = worldOrigin.toHost(point.x, point.y, point.z);
    return new Damage(
        l,
        id,
        generation,
        kind,
        owner.getUUID(),
        blast == null ? WorldDamageAuthority.nextEvent() : blast.event(),
        pos,
        radius,
        target.getMaxHealth(),
        proof);
  }

  @Override
  public boolean environmentalEffects(Entity entity) {
    Lease l = lease;
    if (!(entity.level() instanceof ServerLevel level)
        || !combatReady()
        || !fresh(l)
        || lease != l
        || !l.host.terrainReady()
        || guiOpen
        || l.server != level.getServer()
        || !level.dimension().equals(SharedWorldBlocks.DIMENSION)
        || !entity.isAlive()) return false;
    return entity instanceof ServerPlayer p && p.getUUID().equals(l.player) && controlsPlayer(p)
        || entity instanceof CombatProxyEntity proxy
            && proxy.worldEpoch == l.host.epoch()
            && owns(proxy);
  }

  @Override
  public Object environment(ServerLevel level, LivingEntity target, DamageSource source) {
    Lease l = lease;
    if (!combatReady()
        || !fresh(l)
        || lease != l
        || !l.host.terrainReady()
        || guiOpen
        || !EVENTS.capacity()
        || l.server != level.getServer()
        || !level.dimension().equals(SharedWorldBlocks.DIMENSION)
        || !environmentalEffects(target)) return null;
    long id, generation;
    if (target instanceof CombatProxyEntity proxy) {
      id = proxy.worldHandle;
      generation = proxy.worldGeneration;
    } else {
      id = l.host.playerId();
      generation = l.host.playerGeneration();
    }
    if (id <= 0) return null;
    var pos = worldOrigin.toHost(target.getX(), target.getY(), target.getZ());
    // Session ownership comes from the paired player; the hazard position is
    // checked against this exact live target and its observed generation.
    return new Damage(
        l,
        id,
        generation,
        "environment",
        l.player,
        WorldDamageAuthority.nextEvent(),
        pos,
        0,
        target.getMaxHealth(),
        null);
  }

  @Override
  public Object potionCloud(ServerLevel level, LivingEntity target, DamageSource source) {
    return WorldPotions.permits(target, source)
        ? environment(level, target, level.damageSources().magic())
        : null;
  }

  @Override
  public void finish(Object token, LivingEntity target, float loss) {
    var current = lease;
    if (!(token instanceof Damage d)
        || !fresh(d.lease)
        || current == null
        || !combatReady()
        || !current.host.terrainReady()
        || !sameContext(current, d.lease)
        || lease != current
        || loss > 1000) return;
    long time = d.lease.clock + (System.nanoTime() - d.lease.readNanos) / 1_000_000;
    var e = d.projectile == null ? new JsonObject() : d.projectile.deepCopy();
    e.addProperty("kind", d.kind);
    e.addProperty("source", d.source.toString());
    e.addProperty("event", d.event);
    e.addProperty("time_ms", time);
    e.addProperty("observed_frame", d.lease.host.frame());
    e.addProperty("terrain_revision", d.lease.host.terrainRevision());
    e.add("position", WorldProtocol.vector(d.position));
    e.addProperty("radius", d.radius);
    e.addProperty("target", d.target);
    e.addProperty("generation", d.generation);
    e.addProperty("damage", loss);
    e.addProperty("guest_max_hp", d.guestMax);
    long sequence = EVENTS.addSequence(d.lease.session, e);
    if (sequence > 0) {
      WorldProjectiles.damageQueued(d.lease.session, sequence, e, target, loss);
      LOG.info("World {}: source={}, target={}, actualDamage={}", d.kind, d.source, d.target, loss);
    }
  }

  @Override
  public boolean pause(Entity entity) {
    if (!entity.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return false;
    // Vanilla rocket expiry is server-only. Pausing that lifetime while the
    // client ticks its exponential acceleration can create an unbounded ray.
    if (entity instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity) return false;
    if (entity instanceof Player player && controlsPlayer(player)) return false;
    // Nether ghasts fly over unsampled ground too; freezing them at the box edge stranded them.
    if (WorldNether.flyer(entity)) return !serverActive() || guiOpen;
    if (WorldProjectiles.lodged(entity)) return true;
    if (WorldProjectiles.tracked(entity)) return !combatReady() || guiOpen;
    return !serverActive()
        || (guiOpen || terrainDegraded) && !(entity instanceof Player)
        || coverage == null
        || !coverage.intersects(entity.getBoundingBox());
  }

  private final class BossTargetGoal extends Goal {
    private final Mob creeper;
    private CombatProxyEntity target;

    BossTargetGoal(Mob creeper) {
      this.creeper = creeper;
      setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
      if (!serverReady || !fresh(lease)) return false;
      target =
          proxies.values().stream()
              .filter(e -> e.isAlive() && !e.isRemoved() && creeper.distanceToSqr(e) < 32 * 32)
              .min(Comparator.comparingDouble(creeper::distanceToSqr))
              .orElse(null);
      return target != null;
    }

    @Override
    public boolean canContinueToUse() {
      return target != null
          && target.isAlive()
          && !target.isRemoved()
          && serverReady
          && fresh(lease)
          && creeper.distanceToSqr(target) < 40 * 40;
    }

    @Override
    public void start() {
      creeper.setTarget(target);
    }

    @Override
    public void tick() {
      creeper.setTarget(target);
    }

    @Override
    public void stop() {
      if (creeper.getTarget() == target) creeper.setTarget(null);
      target = null;
    }
  }

  public static void close() {
    lease = null;
    kinematicsLease = null;
    serverReady = false;
    terrainDegraded = false;
    restoreClient();
    EVENTS.reset();
    MAILBOX.close();
  }
}
