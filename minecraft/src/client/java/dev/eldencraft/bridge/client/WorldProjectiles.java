package dev.eldencraft.bridge.client;

import com.google.gson.*;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.WorldProjectileInvoker;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;

/** Server-thread provenance for actual vanilla launches and their exact, per-target impacts. */
public final class WorldProjectiles {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_projectile");

  private record Flight(
      SharedWorldClient.ProjectileContext launch,
      ProjectileTrace trace,
      String kind,
      UUID owner,
      long launched) {}

  private static final class Impact {
    final Projectile projectile;
    final HitResult hit;
    final JsonObject evidence;
    DamageFeedback feedback;

    Impact(Projectile p, HitResult h, JsonObject e) {
      projectile = p;
      hit = h;
      evidence = e;
    }
  }

  private static final class DamageFeedback {
    final WorldOrigin.Vec position, impact;
    final float loss;
    SoundEvent sound;
    SoundSource source;
    float volume, pitch;

    DamageFeedback(WorldOrigin.Vec position, WorldOrigin.Vec impact, float loss) {
      this.position = position;
      this.impact = impact;
      this.loss = loss;
    }
  }

  private record Pending(long session, long seq, long time) {}

  private record Lodged(SharedWorldClient.ProjectileContext context, long until) {}

  public static final class PearlAttempt {
    final SharedWorldClient.ProjectileContext context;
    final Projectile projectile;
    final JsonObject evidence;
    Vec3 destination;
    float loss;
    boolean teleported;

    PearlAttempt(SharedWorldClient.ProjectileContext c, Projectile p, JsonObject e) {
      context = c;
      projectile = p;
      evidence = e;
    }
  }

  private static final Map<Projectile, Flight> FLIGHTS = new IdentityHashMap<>();
  private static final Map<Projectile, Lodged> LODGED = new IdentityHashMap<>();
  private static final ArrayList<Pending> PENDING = new ArrayList<>();
  private static final ConfirmedFeedback<DamageFeedback> HIT_FEEDBACK = new ConfirmedFeedback<>();
  private static volatile Set<UUID> activeIds = Set.of();
  private static final ThreadLocal<Impact> IMPACT = new ThreadLocal<>();
  private static final ThreadLocal<PearlAttempt> PEARL = new ThreadLocal<>();

  private WorldProjectiles() {}

  private static ProjectileTrace.Context identity(SharedWorldClient.ProjectileContext c) {
    return new ProjectileTrace.Context(
        c.host().pid(),
        c.session(),
        c.host().epoch(),
        c.host().map(),
        c.origin().anchorId(),
        c.player());
  }

  private static long now(SharedWorldClient.ProjectileContext c) {
    return c.clock() + (System.nanoTime() - c.readNanos()) / 1_000_000;
  }

  private static WorldOrigin.Vec canonical(SharedWorldClient.ProjectileContext c, Vec3 p) {
    return c.origin().toHost(p.x, p.y, p.z);
  }

  public static boolean inSharedWorld(Entity entity) {
    return entity != null && entity.level().dimension().equals(SharedWorldBlocks.DIMENSION);
  }

  public static boolean permitItem(LivingEntity entity) {
    if (entity instanceof net.minecraft.world.entity.player.Player player
        && !CampaignProgression.characterPermitted(player)) return false;
    if (!inSharedWorld(entity)) return true;
    // This adapter owns the local stand-in, never a mob's vanilla ranged AI.
    if (!(entity instanceof Player)) return true;
    if (!(entity instanceof ServerPlayer player)) return entity.level().isClientSide();
    var c = SharedWorldClient.projectileContext(player);
    var input = HostController.rangedInput();
    if (c == null
        || input == null
        || !input.permits(
            c.host().pid(), c.host().sourceMap(), player.getUUID(), System.nanoTime()))
      return false;
    // Same interaction convention as the actual client camera, including front view.
    // The item still computes its own vanilla spread, multishot and launch velocity.
    player.setYRot(input.yaw());
    player.setXRot(input.pitch());
    return true;
  }

  public static void maintain(ServerPlayer player) {
    if (player.isUsingItem() && ranged(player.getUseItem()) && !permitItem(player))
      player.stopUsingItem();
    var c = SharedWorldClient.projectileContext(player);
    if (c == null) {
      revoke();
      return;
    }
    long time = now(c);
    var id = identity(c);
    for (var iterator = FLIGHTS.entrySet().iterator(); iterator.hasNext(); ) {
      var e = iterator.next();
      if (e.getKey().isRemoved()) {
        iterator.remove();
        continue;
      }
      if (!e.getValue().trace.valid(id, time)) {
        if (e.getKey() instanceof ThrownEnderpearl)
          notice(
              player, "Ender pearl expired: flight exceeded the bridge's time or distance bounds.");
        e.getKey().discard();
        iterator.remove();
      }
    }
    for (var iterator = LODGED.entrySet().iterator(); iterator.hasNext(); ) {
      var e = iterator.next();
      if (e.getKey().isRemoved()) {
        iterator.remove();
        continue;
      }
      if (time > e.getValue().until || !identity(e.getValue().context).equals(id)) {
        e.getKey().discard();
        iterator.remove();
        continue;
      }
      if (e.getKey() instanceof AbstractArrow arrow && arrow.shakeTime > 0) arrow.shakeTime--;
    }
    processResults(player, c, time);
    applyNativeImpacts(player, c, time);
    publishIds();
  }

  public static boolean ranged(ItemStack item) {
    return item.is(Items.BOW) || item.is(Items.CROSSBOW) || item.is(Items.ENDER_PEARL);
  }

  public static void launched(Projectile projectile, ServerLevel level, ItemStack source) {
    if (!(projectile.getOwner() instanceof ServerPlayer player) || !inSharedWorld(projectile))
      return;
    boolean arrow =
        projectile instanceof AbstractArrow a
            && a.getWeaponItem() != null
            && (a.getWeaponItem().is(Items.BOW) || a.getWeaponItem().is(Items.CROSSBOW));
    boolean pearl = projectile instanceof ThrownEnderpearl && source.is(Items.ENDER_PEARL);
    var c = SharedWorldClient.projectileContext(player);
    if ((!arrow && !pearl)
        || c == null
        || !permitItem(player)
        || level.getEntity(projectile.getUUID()) != projectile) return;
    if (FLIGHTS.size() >= 32) {
      notice(player, "Projectile bridge busy: too many active shots.");
      return;
    }
    long time = now(c);
    var first = canonical(c, projectile.position());
    FLIGHTS.put(
        projectile,
        new Flight(
            c,
            new ProjectileTrace(identity(c), time, first),
            BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString(),
            player.getUUID(),
            time));
    publishIds();
    LOG.info(
        "Vanilla projectile launched: kind={}, projectile={}, hostFrame={}",
        BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()),
        projectile.getUUID(),
        c.host().frame());
  }

  public static void sample(Projectile projectile) {
    if (projectile.level().isClientSide()) return;
    var f = FLIGHTS.get(projectile);
    if (f == null) return;
    var c =
        projectile.getOwner() instanceof ServerPlayer p
            ? SharedWorldClient.projectileContext(p)
            : null;
    if (c == null || !f.trace.append(identity(c), now(c), canonical(c, projectile.position()))) {
      if (projectile instanceof ThrownEnderpearl && projectile.getOwner() instanceof ServerPlayer p)
        notice(p, "Ender pearl expired: flight context, time or range limit.");
      projectile.discard();
      FLIGHTS.remove(projectile);
      publishIds();
    }
  }

  /** Scoped around Projectile.hitTargetOrDeflectSelf, called separately for every piercing hit. */
  public static Object enterImpact(Projectile projectile, HitResult hit) {
    var previous = IMPACT.get();
    if (projectile.level().isClientSide()) return previous;
    JsonObject evidence = null;
    var f = FLIGHTS.get(projectile);
    var c =
        projectile.getOwner() instanceof ServerPlayer p
            ? SharedWorldClient.projectileContext(p)
            : null;
    if (f != null && c != null && f.owner.equals(c.player())) {
      var points = f.trace.impact(identity(c), now(c), canonical(c, hit.getLocation()));
      if (!points.isEmpty()) evidence = evidence(projectile, f, points);
    }
    IMPACT.set(new Impact(projectile, hit, evidence));
    return previous;
  }

  public static void leaveImpact(Object previous) {
    var current = IMPACT.get();
    if (current != null
        && current.hit.getType() == HitResult.Type.BLOCK
        && current.projectile instanceof AbstractArrow) {
      FLIGHTS.remove(current.projectile);
      publishIds();
    }
    if (previous instanceof Impact i) IMPACT.set(i);
    else IMPACT.remove();
  }

  public static JsonObject evidence(Projectile projectile) {
    var hit = IMPACT.get();
    return hit != null && hit.projectile == projectile && hit.evidence != null
        ? hit.evidence.deepCopy()
        : null;
  }

  /** Called inside actual proxy hurtServer, before vanilla onHitEntity emits its hit sound. */
  public static void damageQueued(
      long session, long sequence, JsonObject receipt, LivingEntity target, float loss) {
    var impact = IMPACT.get();
    if (impact == null
        || impact.evidence == null
        || !(impact.projectile instanceof AbstractArrow)
        || !(target instanceof CombatProxyEntity)
        || !(impact.hit instanceof EntityHitResult hit)
        || hit.getEntity() != target) return;
    var c =
        impact.projectile.getOwner() instanceof ServerPlayer p
            ? SharedWorldClient.projectileContext(p)
            : null;
    if (c == null || c.session() != session || !receipt.has("projectile")) return;
    var visual = canonical(c, new Vec3(target.getX(), target.getY(.5), target.getZ()));
    var feedback = new DamageFeedback(visual, canonical(c, impact.hit.getLocation()), loss);
    if (HIT_FEEDBACK.offer(session, sequence, now(c), feedback)) impact.feedback = feedback;
  }

  /** Suppress the speculative proxy sound even when its receipt could not be queued. */
  public static boolean captureHitSound(
      AbstractArrow arrow, SoundEvent sound, float volume, float pitch) {
    var impact = IMPACT.get();
    if (impact == null
        || impact.projectile != arrow
        || !(impact.hit instanceof EntityHitResult hit)
        || !(hit.getEntity() instanceof CombatProxyEntity)
        || arrow.level().isClientSide()) return false;
    if (impact.feedback != null) {
      impact.feedback.sound = sound;
      impact.feedback.source = arrow.getSoundSource();
      impact.feedback.volume = volume;
      impact.feedback.pitch = pitch;
    }
    return true;
  }

  public static Vec3 impact(Projectile projectile) {
    var hit = IMPACT.get();
    return hit != null && hit.projectile == projectile
        ? hit.hit.getLocation()
        : projectile.position();
  }

  public static PearlAttempt beginPearl(ThrownEnderpearl pearl) {
    if (!(pearl.getOwner() instanceof ServerPlayer player)) return null;
    var c = SharedWorldClient.projectileContext(player);
    var proof = evidence(pearl);
    var attempt = c == null || proof == null ? null : new PearlAttempt(c, pearl, proof);
    PEARL.set(attempt);
    if (attempt == null && inSharedWorld(player))
      notice(
          player, "Ender pearl cancelled: host context or verified flight is no longer available.");
    return attempt;
  }

  public static boolean permitPearlTeleport(PearlAttempt a, ServerPlayer player, Vec3 destination) {
    boolean valid =
        a != null
            && SharedWorldClient.projectileCurrent(a.context)
            && player.getUUID().equals(a.context.player())
            && clearMinecraftDestination(
                player, player.getBoundingBox().move(destination.subtract(player.position())));
    if (!valid && a != null)
      notice(
          player,
          "Ender pearl cancelled: landing overlaps a Minecraft block or an unloaded chunk.");
    return valid;
  }

  public static void teleported(PearlAttempt a, ServerPlayer player) {
    if (a != null && player != null) {
      a.destination = player.position();
      a.teleported = true;
    }
  }

  public static void pearlLoss(PearlAttempt a, float loss) {
    if (a != null && Float.isFinite(loss) && loss >= 0 && loss <= 5.001f) a.loss = loss;
  }

  public static boolean pearlDamage() {
    return PEARL.get() != null;
  }

  public static void endPearl(PearlAttempt a) {
    PEARL.remove();
    if (a == null || !a.teleported || !SharedWorldClient.projectileCurrent(a.context)) return;
    var c = a.context;
    var e = a.evidence.deepCopy();
    e.addProperty("kind", "ender_pearl");
    e.addProperty("source", c.player().toString());
    e.addProperty("event", WorldDamageAuthority.nextEvent());
    e.addProperty("time_ms", now(c));
    e.addProperty("observed_frame", c.host().frame());
    e.addProperty("terrain_revision", c.host().terrainRevision());
    e.add("position", WorldProtocol.vector(canonical(c, impact(a.projectile))));
    e.add("destination", WorldProtocol.vector(canonical(c, a.destination)));
    e.addProperty("radius", 0);
    e.addProperty("target", c.host().playerId());
    e.addProperty("generation", c.host().playerGeneration());
    e.addProperty("damage", a.loss);
    var player = c.server().getPlayerList().getPlayer(c.player());
    if (player == null) return;
    e.addProperty("guest_max_hp", player.getMaxHealth());
    long sequence = SharedWorldClient.submitProjectile(c, e);
    if (sequence > 0) {
      PENDING.add(new Pending(c.session(), sequence, now(c)));
      LOG.info(
          "Vanilla ender pearl landed: projectile={}, damage={}, destination={}",
          a.projectile.getUUID(),
          a.loss,
          e.get("destination"));
    } else
      notice(
          player, "Ender pearl cancelled: host context changed before landing was acknowledged.");
  }

  private static JsonObject evidence(
      Projectile projectile, Flight f, List<WorldOrigin.Vec> points) {
    var e = new JsonObject();
    e.addProperty("projectile", projectile.getUUID().toString());
    e.addProperty("projectile_kind", f.kind);
    e.addProperty("source", f.owner.toString());
    e.addProperty("launch_frame", f.launch.host().frame());
    e.addProperty("launch_time_ms", f.launched);
    var path = new JsonArray();
    for (var point : points) path.add(WorldProtocol.vector(point));
    e.add("trajectory", path);
    return e;
  }

  public static JsonArray snapshot(ServerPlayer player) {
    var result = new JsonArray();
    var c = SharedWorldClient.projectileContext(player);
    if (c == null) return result;
    for (var entry : FLIGHTS.entrySet())
      if (!entry.getKey().isRemoved()) {
        var path = entry.getValue().trace.snapshot(identity(c), now(c));
        if (!path.isEmpty()) result.add(evidence(entry.getKey(), entry.getValue(), path));
      }
    return result;
  }

  private static void applyNativeImpacts(
      ServerPlayer player, SharedWorldClient.ProjectileContext c, long time) {
    if (c.host().guestSession() != c.session()) return;
    for (var hit : c.host().projectileImpacts()) {
      var projectile =
          FLIGHTS.keySet().stream()
              .filter(p -> p.getUUID().equals(hit.projectile()))
              .findFirst()
              .orElse(null);
      if (projectile == null
          || projectile.isRemoved()
          || time < hit.millis()
          || time - hit.millis() > 1000) continue;
      var f = FLIGHTS.get(projectile);
      var path = f.trace.contact(identity(c), time, hit.segment(), hit.point());
      if (path.isEmpty()) continue;
      var point = c.origin().toGuest(hit.point().x(), hit.point().y(), hit.point().z());
      var start = path.get(path.size() - 2);
      var old = c.origin().toGuest(start.x(), start.y(), start.z());
      var at = new Vec3(point.x(), point.y(), point.z());
      var direction =
          Direction.getApproximateNearest(hit.normal().x(), hit.normal().y(), hit.normal().z());
      var impact = new BlockHitResult(at, direction, BlockPos.containing(at), false);
      // Replay the true native contact through vanilla's collision path at the original
      // segment start, rather than teleporting to a late, already overshot position.
      var from = new Vec3(old.x(), old.y(), old.z());
      projectile.setOldPosAndRot(from, projectile.getYRot(), projectile.getXRot());
      projectile.setPos(at);
      projectile.setDeltaMovement(at.subtract(from));
      // The native ray already discovered a real collision. Bypass only
      // vanilla's air-state discovery guard, then invoke its virtual onHit.
      // Scope the exact native proof explicitly because this call does
      // not pass through the ordinary hitTargetOrDeflectSelf wrapper.
      var previous = IMPACT.get();
      IMPACT.set(new Impact(projectile, impact, evidence(projectile, f, path)));
      try {
        ((WorldProjectileInvoker) projectile).eldencraft$nativeImpact(impact);
      } finally {
        if (previous == null) IMPACT.remove();
        else IMPACT.set(previous);
        FLIGHTS.remove(projectile);
      }
      if (projectile instanceof AbstractArrow && !projectile.isRemoved()) {
        if (LODGED.size() >= 128) {
          var oldest =
              LODGED.entrySet().stream()
                  .min(Comparator.comparingLong(e -> e.getValue().until))
                  .orElseThrow();
          oldest.getKey().discard();
          LODGED.remove(oldest.getKey());
        }
        LODGED.put(projectile, new Lodged(c, time + 60_000));
      }
      LOG.info(
          "Vanilla projectile native contact: kind={}, projectile={}, segment={}, removed={}",
          f.kind,
          projectile.getUUID(),
          hit.segment(),
          projectile.isRemoved());
    }
  }

  private static void processResults(
      ServerPlayer player, SharedWorldClient.ProjectileContext c, long time) {
    var accepted =
        HIT_FEEDBACK.resolve(
            c.session(),
            time,
            c.host().guestSession() == c.session() ? c.host().acknowledgements() : List.of());
    for (var hit : accepted) {
      var at = c.origin().toGuest(hit.position.x(), hit.position.y(), hit.position.z());
      var contact = c.origin().toGuest(hit.impact.x(), hit.impact.y(), hit.impact.z());
      if (hit.sound != null)
        player
            .level()
            .playSound(
                null,
                contact.x(),
                contact.y(),
                contact.z(),
                hit.sound,
                hit.source,
                hit.volume,
                hit.pitch);
      // Same vanilla DAMAGE_INDICATOR provider and damage/count/distribution
      // used by Player.damageStatsAndHearts, delayed until actual host HP loss.
      if (hit.loss > 2)
        player
            .level()
            .sendParticles(
                ParticleTypes.DAMAGE_INDICATOR,
                at.x(),
                at.y(),
                at.z(),
                Math.min(64, (int) (hit.loss * .5)),
                .1,
                0,
                .1,
                .2);
      LOG.info(
          "Confirmed vanilla projectile feedback: sound={}, resolvedMinecraftDamage={}",
          hit.sound != null,
          hit.loss);
    }
    for (var it = PENDING.iterator(); it.hasNext(); ) {
      var p = it.next();
      if (p.session != c.session()) {
        it.remove();
        continue;
      }
      var result =
          c.host().guestSession() == p.session
              ? c.host().acknowledgements().stream()
                  .filter(a -> a.sequence() == p.seq)
                  .findFirst()
                  .orElse(null)
              : null;
      if (result != null) {
        notice(
            player,
            result.result() == 1
                ? "Ender pearl: host teleport accepted."
                : "Ender pearl cancelled: " + result.reason() + ".");
        it.remove();
      } else if (time - p.time > 1000) {
        notice(player, "Ender pearl expired: host did not accept the landing in time.");
        it.remove();
      }
    }
  }

  private static void publishIds() {
    var ids = new HashSet<UUID>();
    for (var projectile : FLIGHTS.keySet())
      if (!projectile.isRemoved()) ids.add(projectile.getUUID());
    activeIds = Set.copyOf(ids);
  }

  public static boolean tracked(Entity entity) {
    return entity instanceof Projectile && activeIds.contains(entity.getUUID());
  }

  public static boolean lodged(Entity entity) {
    return !entity.level().isClientSide() && LODGED.containsKey(entity);
  }

  private static boolean clearMinecraftDestination(ServerPlayer player, AABB box) {
    var level = player.level();
    for (var position :
        BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(
                Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
      // Outside build height is void air, as before; inside, the chunk must be loaded.
      if (!level.isOutsideBuildHeight(position) && !level.isLoaded(position)) return false;
      var state = level.getBlockState(position);
      if (state.isAir() || state.is(SharedWorldBlocks.TERRAIN)) continue;
      for (var shape :
          state
              .getCollisionShape(
                  level, position, net.minecraft.world.phys.shapes.CollisionContext.of(player))
              .toAabbs()) if (shape.move(position).intersects(box)) return false;
    }
    return true;
  }

  /** Called only on the integrated-server thread; no old-world entity references survive loss. */
  public static void revoke() {
    for (var p : FLIGHTS.keySet()) p.discard();
    for (var p : LODGED.keySet()) p.discard();
    clear();
  }

  public static void clear() {
    FLIGHTS.clear();
    LODGED.clear();
    PENDING.clear();
    HIT_FEEDBACK.clear();
    activeIds = Set.of();
    IMPACT.remove();
    PEARL.remove();
  }

  private static void notice(ServerPlayer player, String message) {
    player.sendOverlayMessage(Component.literal(message));
    LOG.info(message);
  }
}
