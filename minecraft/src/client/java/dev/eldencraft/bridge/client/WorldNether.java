package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Nether comes to Elden Ring. Standing in a lit nether portal for two seconds opens it: real
 * netherrack, magma, nylium, soul sand and fire slowly replace the sampled terrain around the
 * portal (sunk into the ground, with native collision), ghasts, blazes, wither skeletons and magma
 * cubes come out of it, and painted lava (NetherRules) burns. The compositor turns the rest of
 * Elden Ring to hell from {@link #view}. Walking back through the portal, {@code /eldencraft nether
 * close} or stopping the server puts every changed block back and removes every Nether mob,
 * including ones that were saved in unloaded chunks (by their tag). Integrated-server thread only.
 */
public final class WorldNether {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_nether");

  /** Saved with the entity, so a Nether mob that comes back with its chunk is recognised. */
  public static final String TAG = "eldencraft_nether";

  private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE,
      MAX_GHASTS = 2,
      MAX_FIGHTERS = 4;

  /**
   * A tracked mob not loaded for this long is forgotten; if its chunk loads later it is removed.
   */
  private static final long MISSING_NANOS = 20_000_000_000L;

  /** What the client and compositor need, published once per server tick. Guest coordinates. */
  public record View(
      boolean active,
      float warp,
      long enteredNanos,
      long openedNanos,
      long closedNanos,
      long anchor,
      WorldOrigin.Vec corner,
      WorldOrigin.Vec center,
      long shakeNanos,
      float shake,
      long emergeNanos,
      WorldOrigin.Vec emerge) {
    static final View NONE = new View(false, 0, 0, 0, 0, 0, null, null, 0, 0, 0, null);

    public double seconds(long now) {
      return active ? (now - openedNanos) / 1e9 : 0;
    }
  }

  public static volatile View view = View.NONE;

  private record Portal(
      BlockPos corner,
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int bottomY,
      int topY,
      Direction.Axis axis,
      long anchor) {
    double cx() {
      return (minX + maxX + 1) / 2.0;
    }

    double cz() {
      return (minZ + maxZ + 1) / 2.0;
    }
  }

  private record Change(BlockState before, BlockState after) {}

  private record Wave(double at, EntityType<?> type, boolean portal) {}

  private static final class Tracked {
    final EntityType<?> type;
    long seen;
    int outside;

    Tracked(EntityType<?> type, long seen) {
      this.type = type;
      this.seen = seen;
    }
  }

  private static final List<Wave> WAVES =
      List.of(
          new Wave(3, EntityTypes.GHAST, true),
          new Wave(10, EntityTypes.BLAZE, true),
          new Wave(18, EntityTypes.ZOMBIFIED_PIGLIN, true),
          new Wave(32, EntityTypes.WITHER_SKELETON, true),
          new Wave(50, EntityTypes.MAGMA_CUBE, false));
  private static final List<EntityType<?>> FIGHTERS =
      List.of(
          EntityTypes.BLAZE,
          EntityTypes.WITHER_SKELETON,
          EntityTypes.MAGMA_CUBE,
          EntityTypes.ZOMBIFIED_PIGLIN);
  private static final RandomSource RANDOM = RandomSource.create();
  private static final Map<BlockPos, Change> changed = new LinkedHashMap<>();
  private static final List<int[]> columns = new ArrayList<>();
  private static final ArrayDeque<int[]> retry = new ArrayDeque<>();

  /** Ground surface height (guest y) of every column already turned: the next one follows it. */
  private static final Map<Long, Double> heights = new HashMap<>();

  private static final Map<UUID, Tracked> mobs = new HashMap<>();
  private static final List<Entity> doomed = new ArrayList<>();
  private static final Map<UUID, Vec3> fireballs = new HashMap<>();
  private static MinecraftServer server;
  private static Portal portal;
  private static boolean active, awaitingExit;
  private static int inside, nextColumn, nextWave;
  private static long ticks, enteredNanos, openedNanos, closedNanos, shakeNanos, emergeNanos;
  private static double nextGhast, nextFighter;
  private static float shake;
  private static WorldOrigin.Vec emerge;

  private WorldNether() {}

  public static boolean active() {
    return active;
  }

  /** Nether ghasts fly free of the hidden terrain and keep ticking outside the sampled box. */
  public static boolean flyer(Entity entity) {
    return entity instanceof Ghast && entity.entityTags().contains(TAG);
  }

  private static boolean leftover(Entity entity) {
    return entity.entityTags().contains(TAG) && (!active || !mobs.containsKey(entity.getUUID()));
  }

  /**
   * ServerEntityEvents.ALLOW_LOAD: a Nether mob saved in a chunk never comes back without its
   * Nether.
   */
  public static boolean allowLoad(
      Entity entity, ServerLevel level, EntitySpawnReason reason, boolean fresh) {
    return !leftover(entity);
  }

  /** ServerEntityEvents.ENTITY_LOAD: the same check for any route ALLOW_LOAD does not cover. */
  public static void loaded(Entity entity, ServerLevel level) {
    if (leftover(entity)) doomed.add(entity);
  }

  public static void serverTick(MinecraftServer game) {
    if (server != game) {
      forget();
      server = game;
    }
    // Removal is deferred out of the load callback.
    for (var e : doomed) if (!e.isRemoved()) e.discard();
    doomed.clear();
    var level = game.getLevel(SharedWorldBlocks.DIMENSION);
    var player = SharedWorldClient.hostPlayer(game);
    var origin = SharedWorldClient.serverOrigin();
    if (level == null || player == null || origin == null || !SharedWorldClient.serverActive()) {
      inside = Math.max(0, inside - 2);
      publish();
      return;
    }
    try {
      travel(level, player, origin);
      if (active) {
        ticks++;
        spread(level, origin);
        if (ticks % 20 == 0) {
          track(level, player);
          waves(level, player, origin);
          leash(level, player);
        }
        hazards(level, player, origin);
        fireballs(level, player);
      }
    } catch (RuntimeException failure) {
      LOG.warn("Nether tick failed; closing it", failure);
      close(level, "failure");
    }
    publish();
  }

  // ---------------------------------------------------------------- portal travel
  private static void travel(ServerLevel level, ServerPlayer player, WorldOrigin origin) {
    var in = portalAt(level, player.getBoundingBox());
    if (in == null) {
      inside = Math.max(0, inside - 2);
      awaitingExit = false;
      return;
    }
    if (awaitingExit) return;
    if (inside == 0) enteredNanos = System.nanoTime();
    if (++inside < NetherRules.TRAVEL_TICKS) return;
    inside = 0;
    awaitingExit = true;
    if (active) close(level, "walked back through the portal");
    else open(level, in, origin);
  }

  private static BlockPos portalAt(ServerLevel level, AABB box) {
    for (var cursor :
        BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
      if (level.getBlockState(cursor).is(Blocks.NETHER_PORTAL)) return cursor.immutable();
    return null;
  }

  private static void open(ServerLevel level, BlockPos seed, WorldOrigin origin) {
    var state = level.getBlockState(seed);
    if (!state.is(Blocks.NETHER_PORTAL)) return;
    int minX = seed.getX(),
        maxX = minX,
        minY = seed.getY(),
        maxY = minY,
        minZ = seed.getZ(),
        maxZ = minZ;
    var todo = new ArrayDeque<BlockPos>(List.of(seed));
    var seen = new HashSet<BlockPos>();
    while (!todo.isEmpty() && seen.size() < 441) {
      var p = todo.poll();
      if (!seen.add(p) || !level.getBlockState(p).is(Blocks.NETHER_PORTAL)) continue;
      minX = Math.min(minX, p.getX());
      maxX = Math.max(maxX, p.getX());
      minY = Math.min(minY, p.getY());
      maxY = Math.max(maxY, p.getY());
      minZ = Math.min(minZ, p.getZ());
      maxZ = Math.max(maxZ, p.getZ());
      for (var d : Direction.values()) todo.add(p.relative(d));
    }
    portal =
        new Portal(
            new BlockPos(minX, minY, minZ),
            minX,
            maxX,
            minZ,
            maxZ,
            minY,
            maxY,
            state.getValue(NetherPortalBlock.AXIS),
            origin.anchorId());
    active = true;
    ticks = 0;
    nextWave = 0;
    openedNanos = System.nanoTime();
    nextGhast = 60;
    nextFighter = 70;
    planColumns();
    LOG.info(
        "Nether opened at portal {}..{} (axis {}, anchor {})",
        portal.corner,
        new BlockPos(maxX, maxY, maxZ),
        portal.axis,
        portal.anchor);
  }

  /**
   * Every block it changed goes back (unless the player changed it since), its mobs and fires go.
   */
  static void close(ServerLevel level, String reason) {
    if (!active && changed.isEmpty() && mobs.isEmpty()) return;
    int restored = 0, removed = 0;
    if (level != null) {
      var order = new ArrayList<>(changed.entrySet());
      order.sort(
          (a, b) ->
              Integer.compare(
                  b.getKey().getY(),
                  a.getKey().getY())); // Top down: decorations before their ground.
      for (var e : order)
        if (level.getBlockState(e.getKey()) == e.getValue().after) {
          level.setBlock(e.getKey(), e.getValue().before, FLAGS);
          restored++;
        }
      if (portal != null)
        extinguish(
            level,
            new BlockPos(
                (int) Math.floor(portal.cx()), portal.bottomY, (int) Math.floor(portal.cz())));
      var player = server == null ? null : SharedWorldClient.hostPlayer(server);
      if (player != null) extinguish(level, player.blockPosition());
      // Every loaded Nether mob, tracked or not; unloaded ones are removed when their chunk loads.
      var nether = new ArrayList<Entity>();
      for (var e : level.getAllEntities()) if (e.entityTags().contains(TAG)) nether.add(e);
      for (var e : nether)
        if (!e.isRemoved()) {
          e.discard();
          removed++;
        }
    }
    mobs.clear();
    fireballs.clear();
    changed.clear();
    columns.clear();
    retry.clear();
    heights.clear();
    if (active) closedNanos = System.nanoTime();
    active = false;
    SharedWorldClient.refreshTerrain(); // Restored cache cells rejoin the tracked terrain.
    LOG.info("Nether closed ({}): {} blocks restored, {} mobs removed", reason, restored, removed);
  }

  /**
   * Fire started by ghast fireballs is not in the change list; clear it near the portal and the
   * player.
   */
  private static void extinguish(ServerLevel level, BlockPos at) {
    for (var cursor : BlockPos.betweenClosed(at.offset(-28, -12, -28), at.offset(28, 16, 28))) {
      var s = level.getBlockState(cursor);
      if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE))
        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS);
    }
  }

  public static void serverStopping(MinecraftServer game) {
    if (server == game) close(game.getLevel(SharedWorldBlocks.DIMENSION), "server stopping");
    forget();
  }

  /** Command entry (server thread): close now. */
  public static boolean closeNow(MinecraftServer game) {
    boolean was = active;
    close(game.getLevel(SharedWorldBlocks.DIMENSION), "command");
    return was;
  }

  private static void forget() {
    active = false;
    portal = null;
    changed.clear();
    columns.clear();
    retry.clear();
    heights.clear();
    mobs.clear();
    doomed.clear();
    fireballs.clear();
    inside = 0;
    awaitingExit = false;
    view = View.NONE;
  }

  // ---------------------------------------------------------------- the ground
  private static void planColumns() {
    columns.clear();
    retry.clear();
    heights.clear();
    nextColumn = 0;
    int r = NetherRules.REAL_RADIUS + 1,
        cx = (int) Math.floor(portal.cx()),
        cz = (int) Math.floor(portal.cz());
    float gx = (float) (portal.cx() - portal.corner.getX()),
        gz = (float) (portal.cz() - portal.corner.getZ());
    for (int x = cx - r; x <= cx + r; x++)
      for (int z = cz - r; z <= cz + r; z++) {
        float ragged =
            NetherRules.ragged(x - portal.corner.getX(), z - portal.corner.getZ(), gx, gz);
        if (ragged <= NetherRules.REAL_RADIUS)
          columns.add(new int[] {x, z, Math.round(ragged * 1000)});
      }
    columns.sort(Comparator.comparingInt(c -> c[2]));
  }

  private static void spread(ServerLevel level, WorldOrigin origin) {
    if (origin.anchorId() != portal.anchor)
      return; // The real ground lives on the portal's map only.
    double reach = NetherRules.realRadius((System.nanoTime() - openedNanos) / 1e9) * 1000;
    int work = 0;
    while (nextColumn < columns.size()
        && columns.get(nextColumn)[2] <= reach
        && work < NetherRules.COLUMNS_PER_TICK
        && changed.size() < NetherRules.MAX_REAL_BLOCKS) {
      var c = columns.get(nextColumn++);
      work++;
      if (!convert(level, c[0], c[1])) retry.add(new int[] {c[0], c[1], 0});
    }
    // Columns that were not sampled when the front passed get five more chances, every two seconds.
    if (ticks % 40 == 0)
      for (int i = retry.size(); i > 0 && changed.size() < NetherRules.MAX_REAL_BLOCKS; i--) {
        var c = retry.poll();
        if (!convert(level, c[0], c[1]) && ++c[2] < 5) retry.add(c);
      }
  }

  /**
   * Top of the floor-like samples in a hidden-terrain cell (thin horizontal patches), or NaN.
   * Walls, pillars and slabs of sloped ground are not ground to build on.
   */
  private static double floorTop(BlockPos pos) {
    double top = Double.NaN;
    for (var b : SharedTerrain.outline(pos).toAabbs())
      if (b.getXsize() * b.getZsize() >= .04 && b.getYsize() <= .25)
        top = Double.isNaN(top) ? b.maxY : Math.max(top, b.maxY);
    return top;
  }

  /** The expected ground height of a column: its turned neighbours, else the portal's footing. */
  private static double reference(int x, int z) {
    for (int r = 1; r <= 3; r++) {
      double sum = 0;
      int n = 0;
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          var h = heights.get(BlockPos.asLong(x + dx, 0, z + dz));
          if (h != null) {
            sum += h;
            n++;
          }
        }
      if (n > 0) return sum / n;
    }
    return portal.bottomY
        - .5; // The frame's bottom row stands in the ground cell below the portal.
  }

  /**
   * One column of sampled Elden Ring ground turns. The surface is the floor sample nearest the
   * neighbours' height (never a wall top, a ceiling or a branch above it), and the block sinks into
   * it (NetherRules.surfaceOffset). False when the terrain there is not sampled yet.
   */
  private static boolean convert(ServerLevel level, int x, int z) {
    if (x >= portal.minX - 1
        && x <= portal.maxX + 1
        && z >= portal.minZ - 1
        && z <= portal.maxZ + 1) return true; // Keep the portal's own footing.
    double ref = reference(x, z), surface = Double.NaN, best = 1.6;
    var cursor = new BlockPos.MutableBlockPos();
    for (int y = (int) Math.floor(ref) + 2; y >= (int) Math.floor(ref) - 3; y--) {
      cursor.set(x, y, z);
      var s = level.getBlockState(cursor);
      if (!s.is(SharedWorldBlocks.TERRAIN)) {
        if (!s.isAir()) return true;
        continue;
      } // A build or an earlier Nether block: leave it.
      if (ShadowTerrainBlock.boundary(s)) continue;
      double top = floorTop(cursor);
      if (Double.isNaN(top)) continue;
      double d = Math.abs(y + top - ref);
      if (d < best) {
        best = d;
        surface = y + top;
      }
    }
    if (Double.isNaN(surface)) return false;
    heights.put(BlockPos.asLong(x, 0, z), surface);
    int cell = (int) Math.floor(surface - 1e-6);
    double fraction = surface - cell;
    var ground = new BlockPos(x, cell + NetherRules.surfaceOffset(fraction), z);
    if (!level.getBlockState(ground).is(SharedWorldBlocks.TERRAIN))
      ground = new BlockPos(x, cell, z);
    var above = ground.above();
    var aboveState = level.getBlockState(above);
    boolean open =
        aboveState.isAir()
            || aboveState.is(SharedWorldBlocks.TERRAIN) && Double.isNaN(floorTop(above))
            || above.getY() == cell;
    int bx = x - portal.corner.getX(), bz = z - portal.corner.getZ();
    float cx = (float) (portal.cx() - portal.corner.getX()),
        cz = (float) (portal.cz() - portal.corner.getZ());
    float roll = NetherRules.hash01(bx, bz, 41);
    switch (NetherRules.ground(bx, bz, true, cx, cz)) {
      case NetherRules
              .GROUND_LAVA -> {} // Painted lava; NetherRules makes it burn. Fluids would flow
      // through the terrain cache.
      case NetherRules.GROUND_LAVA_RIM, NetherRules.GROUND_MAGMA ->
          set(level, ground, Blocks.MAGMA_BLOCK.defaultBlockState());
      case NetherRules.GROUND_CRIMSON -> {
        set(level, ground, Blocks.CRIMSON_NYLIUM.defaultBlockState());
        if (open && roll < .12f) set(level, above, Blocks.CRIMSON_ROOTS.defaultBlockState());
        else if (open && roll < .15f) set(level, above, Blocks.CRIMSON_FUNGUS.defaultBlockState());
      }
      case NetherRules.GROUND_SOUL -> {
        set(level, ground, Blocks.SOUL_SAND.defaultBlockState());
        if (open && roll < .05f) set(level, above, Blocks.SOUL_FIRE.defaultBlockState());
      }
      default -> {
        set(level, ground, Blocks.NETHERRACK.defaultBlockState());
        if (open && roll < .04f)
          set(
              level,
              above,
              Blocks.FIRE.defaultBlockState()); // Netherrack keeps it burning forever.
      }
    }
    return true;
  }

  private static void set(ServerLevel level, BlockPos pos, BlockState state) {
    var p = pos.immutable();
    var before = level.getBlockState(p);
    if (before == state) return;
    var prior = changed.get(p);
    changed.put(p, new Change(prior == null ? before : prior.before, state));
    level.setBlock(p, state, FLAGS);
  }

  // ---------------------------------------------------------------- what comes out of it
  /** Once a second: which tracked mobs are still alive somewhere. */
  private static void track(ServerLevel level, ServerPlayer player) {
    long now = System.nanoTime();
    var it = mobs.entrySet().iterator();
    while (it.hasNext()) {
      var e = it.next();
      var entity = level.getEntity(e.getKey());
      if (entity != null) {
        if (!entity.isAlive()) {
          it.remove();
          continue;
        }
        e.getValue().seen = now;
      } else if (now - e.getValue().seen > MISSING_NANOS)
        it.remove(); // Killed or unloaded long enough: no longer counts.
    }
  }

  private static int count(boolean ghasts) {
    int n = 0;
    for (var t : mobs.values()) if ((t.type == EntityTypes.GHAST) == ghasts) n++;
    return n;
  }

  private static void waves(ServerLevel level, ServerPlayer player, WorldOrigin origin) {
    double seconds = (System.nanoTime() - openedNanos) / 1e9;
    while (nextWave < WAVES.size() && WAVES.get(nextWave).at <= seconds) {
      var w = WAVES.get(nextWave++);
      spawn(level, player, origin, w.type, w.portal);
    }
    if (seconds >= nextGhast) {
      nextGhast = seconds + 60;
      if (count(true) < MAX_GHASTS) spawn(level, player, origin, EntityTypes.GHAST, false);
    }
    if (seconds >= nextFighter) {
      nextFighter = seconds + 40;
      if (count(false) < MAX_FIGHTERS)
        spawn(
            level,
            player,
            origin,
            FIGHTERS.get(RANDOM.nextInt(FIGHTERS.size())),
            RANDOM.nextBoolean());
    }
  }

  private static void spawn(
      ServerLevel level,
      ServerPlayer player,
      WorldOrigin origin,
      EntityType<?> type,
      boolean fromPortal) {
    boolean ghast = type == EntityTypes.GHAST;
    if (count(ghast) >= (ghast ? MAX_GHASTS : MAX_FIGHTERS)) return;
    var near =
        portal != null
            && origin.anchorId() == portal.anchor
            && player.distanceToSqr(portal.cx(), portal.bottomY, portal.cz()) < 40 * 40;
    Vec3 at = null;
    if (ghast) {
      // Out of the sky above the portal (or the player), only where four blocks of air are free.
      if (fromPortal && near)
        at = airPoint(level, new Vec3(portal.cx(), portal.topY, portal.cz()), 4, 10, 6, 10);
      if (at == null) at = airPoint(level, player.position(), 18, 30, 10, 16);
    } else if (fromPortal && near) {
      int side = RANDOM.nextBoolean() ? 1 : -1;
      double along =
          portal.axis == Direction.Axis.X
              ? portal.minX + RANDOM.nextInt(portal.maxX - portal.minX + 1) + .5
              : portal.minZ + RANDOM.nextInt(portal.maxZ - portal.minZ + 1) + .5;
      at =
          portal.axis == Direction.Axis.X
              ? new Vec3(along, portal.bottomY, portal.minZ + .5 + side * 1.5)
              : new Vec3(portal.minX + .5 + side * 1.5, portal.bottomY, along);
    } else at = groundNear(level, player);
    if (at == null) return;
    try (var scope = WorldMobSpawning.summon(level, type)) {
      var entity = type.create(level, EntitySpawnReason.EVENT);
      if (!(entity instanceof Mob mob)) return;
      mob.snapTo(at.x, at.y, at.z, RANDOM.nextFloat() * 360, 0);
      mob.finalizeSpawn(
          level,
          level.getCurrentDifficultyAt(BlockPos.containing(at)),
          EntitySpawnReason.EVENT,
          null);
      mob.setPersistenceRequired();
      mob.addTag(TAG);
      // Tracked before it is added: the load callback must not take it for a leftover.
      mobs.put(mob.getUUID(), new Tracked(type, System.nanoTime()));
      if (!level.addFreshEntity(mob)) {
        mobs.remove(mob.getUUID());
        return;
      }
      if (ghast) {
        emergeNanos = System.nanoTime();
        emerge = new WorldOrigin.Vec(at.x, at.y, at.z);
        mob.setTarget(player);
      }
    }
  }

  /** A ghast-sized pocket of free air around {@code center}; never inside sampled ground. */
  private static Vec3 airPoint(
      ServerLevel level, Vec3 center, double minR, double maxR, double minUp, double maxUp) {
    for (int attempt = 0; attempt < 12; attempt++) {
      double a = RANDOM.nextDouble() * Math.PI * 2, r = minR + RANDOM.nextDouble() * (maxR - minR);
      double x = center.x + Math.cos(a) * r,
          y = center.y + minUp + RANDOM.nextDouble() * (maxUp - minUp),
          z = center.z + Math.sin(a) * r;
      if (clear(level, new AABB(x - 2.5, y - 1, z - 2.5, x + 2.5, y + 5, z + 2.5)))
        return new Vec3(x, y, z);
    }
    return null;
  }

  private static boolean clear(ServerLevel level, AABB box) {
    for (var cursor :
        BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
      if (!free(level, cursor)) return false;
    return true;
  }

  /** A floor near the player: the first sampled surface or real block with two free cells above. */
  private static Vec3 groundNear(ServerLevel level, ServerPlayer player) {
    var cursor = new BlockPos.MutableBlockPos();
    for (int attempt = 0; attempt < 8; attempt++) {
      double a = RANDOM.nextDouble() * Math.PI * 2, r = 10 + RANDOM.nextDouble() * 8;
      int x = (int) Math.floor(player.getX() + Math.cos(a) * r),
          z = (int) Math.floor(player.getZ() + Math.sin(a) * r),
          y0 = (int) Math.floor(player.getY());
      for (int y = y0 + 4; y >= y0 - 6; y--) {
        cursor.set(x, y, z);
        var s = level.getBlockState(cursor);
        double top =
            s.is(SharedWorldBlocks.TERRAIN)
                ? ShadowTerrainBlock.boundary(s) ? Double.NaN : floorTop(cursor)
                : !s.isAir() && !s.getCollisionShape(level, cursor).isEmpty() ? 1 : Double.NaN;
        if (Double.isNaN(top)) continue;
        if (free(level, cursor.above()) && free(level, cursor.above(2)))
          return new Vec3(x + .5, y + top + .05, z + .5);
        break;
      }
    }
    return null;
  }

  private static boolean free(ServerLevel level, BlockPos pos) {
    var s = level.getBlockState(pos);
    return s.isAir() || s.is(SharedWorldBlocks.TERRAIN) && SharedTerrain.outline(pos).isEmpty();
  }

  /**
   * Ghasts fly free (no terrain collision, never paused) and are steered, never teleported: they
   * loom 10-24 m above the player, 12-36 m away, and hunt them. Far strays and walkers that stay
   * outside the simulated box are dismissed; the waves replace them later.
   */
  private static void leash(ServerLevel level, ServerPlayer player) {
    var area = SharedWorldClient.coverage();
    for (var e : new ArrayList<>(mobs.entrySet())) {
      var entity = level.getEntity(e.getKey());
      if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
      double dx = mob.getX() - player.getX(),
          dz = mob.getZ() - player.getZ(),
          dy = mob.getY() - player.getY(),
          h = Math.sqrt(dx * dx + dz * dz);
      if (h > 96) {
        mob.discard();
        mobs.remove(e.getKey());
        continue;
      }
      if (mob instanceof Ghast ghast) {
        if (h > 36 || h < 12 || dy < 10 || dy > 24) {
          var p = airPoint(level, player.position(), 16, 28, 12, 18);
          if (p == null)
            p =
                new Vec3(
                    player.getX() - dx / Math.max(h, 1) * 20,
                    player.getY() + 14,
                    player.getZ() - dz / Math.max(h, 1) * 20);
          ghast.getMoveControl().setWantedPosition(p.x, p.y, p.z, 1);
        }
        // Vanilla targeting only sees players within 4 blocks of the ghast's height; this one
        // hunts.
        if (h < 64 && player.isAlive() && !player.isSpectator()) ghast.setTarget(player);
      } else if (h > 48 || area != null && !area.intersects(mob.getBoundingBox())) {
        if ((e.getValue().outside += 20) > 200) {
          mob.discard();
          mobs.remove(e.getKey());
        }
      } else e.getValue().outside = 0;
    }
  }

  // ---------------------------------------------------------------- what hurts
  /** Grid for NetherRules on the current map: the portal's corner there, else this map's origin. */
  static BlockPos grid(WorldOrigin origin) {
    if (portal != null && origin.anchorId() == portal.anchor) return portal.corner;
    var g = origin.guestOrigin();
    return BlockPos.containing(g.x(), g.y(), g.z());
  }

  static boolean centred(WorldOrigin origin) {
    return portal != null && origin.anchorId() == portal.anchor;
  }

  private static void hazards(ServerLevel level, ServerPlayer player, WorldOrigin origin) {
    if (!player.onGround()) return;
    var g = grid(origin);
    boolean centred = centred(origin);
    int bx = (int) Math.floor(player.getX()) - g.getX(),
        bz = (int) Math.floor(player.getZ()) - g.getZ();
    float cx = centred ? (float) (portal.cx() - g.getX()) : 0,
        cz = centred ? (float) (portal.cz() - g.getZ()) : 0;
    if (centred
        && NetherRules.ragged(bx, bz, cx, cz)
            > NetherRules.radius((System.nanoTime() - openedNanos) / 1e9))
      return; // Not reached yet.
    var under =
        level.getBlockState(BlockPos.containing(player.getX(), player.getY() - .2, player.getZ()));
    if (!under.isAir() && !under.is(SharedWorldBlocks.TERRAIN) && !under.is(Blocks.MAGMA_BLOCK))
      return; // A real block decides.
    int kind = NetherRules.ground(bx, bz, centred, cx, cz);
    if (NetherRules.deepLava(bx, bz, centred, cx, cz)) {
      player.lavaHurt();
      // Vanilla lava sets 15 s of fire, which Elden Ring cannot put out; keep 4.
      if (player.getRemainingFireTicks() > 80) player.setRemainingFireTicks(80);
    } else if ((kind == NetherRules.GROUND_LAVA
            || kind == NetherRules.GROUND_LAVA_RIM
            || under.is(Blocks.MAGMA_BLOCK))
        && !player.isSteppingCarefully())
      player.hurtServer(level, level.damageSources().hotFloor(), 1);
  }

  /**
   * A ghast fireball that vanished near the player exploded there: the compositor shakes the view.
   */
  private static void fireballs(ServerLevel level, ServerPlayer player) {
    var seen = new HashSet<UUID>();
    for (var f :
        level.getEntitiesOfClass(LargeFireball.class, player.getBoundingBox().inflate(64))) {
      fireballs.put(f.getUUID(), f.position());
      seen.add(f.getUUID());
    }
    var it = fireballs.entrySet().iterator();
    while (it.hasNext()) {
      var e = it.next();
      if (seen.contains(e.getKey())) continue;
      it.remove();
      double d = e.getValue().distanceTo(player.position());
      if (d < 32) {
        shakeNanos = System.nanoTime();
        shake = (float) Math.max(.15, 1 - d / 32);
      }
    }
  }

  private static void publish() {
    var p = portal;
    view =
        new View(
            active,
            Math.min(1, inside / (float) NetherRules.TRAVEL_TICKS),
            enteredNanos,
            openedNanos,
            closedNanos,
            p == null ? 0 : p.anchor,
            p == null
                ? null
                : new WorldOrigin.Vec(p.corner.getX(), p.corner.getY(), p.corner.getZ()),
            p == null ? null : new WorldOrigin.Vec(p.cx(), p.bottomY, p.cz()),
            shakeNanos,
            shake,
            emergeNanos,
            emerge);
  }
}
