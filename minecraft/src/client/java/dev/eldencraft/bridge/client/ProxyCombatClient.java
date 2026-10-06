package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/**
 * Integrated-server proxy lifecycle and client picking. Every entity/item change remains on its
 * owning thread.
 */
public final class ProxyCombatClient implements ProxyCombatAuthority.Adapter {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_proxy");
  private static final ProxyCombatClient INSTANCE = new ProxyCombatClient();
  private static final ProxyMailbox MAILBOX = new ProxyMailbox();
  private static final ProxyReceipts OUTBOX = new ProxyReceipts();

  private record Intent(UUID target, Lease selected) {}

  private static final ProxyEvents<Intent> INTENTS = new ProxyEvents<>();
  private static Lease pickedLease;
  private static UUID pickedTarget;

  private record Lease(
      MinecraftServer server,
      UUID player,
      ResourceKey<Level> dimension,
      ProxyProtocol.Frame host,
      long session,
      long readNanos,
      Vec3 visualBase) {}

  private static volatile Lease lease;
  private static Object clientWorld, clientPlayer;
  private static ProxyProtocol.Frame lastHost;
  private static boolean lastReady;
  private final Map<UUID, CombatProxyEntity> proxies = new HashMap<>();
  private long attackId;

  private ProxyCombatClient() {}

  public static void initialize() {
    EntityRenderers.register(ProxyEntities.TYPE, NoopRenderer::new);
    ProxyCombatAuthority.adapter = INSTANCE;
    ServerTickEvents.START_SERVER_TICK.register(INSTANCE::serverTick);
  }

  public static void tick(Minecraft client) {
    var server = client.getSingleplayerServer();
    var host = MAILBOX.read();
    var control = HostController.combatSnapshot(client);
    boolean valid =
        server != null
            && !server.isPublished()
            && client.level != null
            && client.player != null
            && client.player.isAlive()
            && !client.player.isSpectator()
            && control != null
            && host != null
            && host.ready()
            && host.pid() == control.publisherPid()
            && host.map() == control.mapId()
            && client.gui.screen() == null;
    if (clientWorld != client.level || clientPlayer != client.player) {
      OUTBOX.reset();
      lease = null;
      clientWorld = client.level;
      clientPlayer = client.player;
    }
    OUTBOX.update(valid ? host : null);
    if (host != null) lastHost = host;
    boolean ready = valid && OUTBOX.ready();
    var avatar = HostController.avatar();
    var visualBase =
        avatar == null
            ? (client.player == null ? Vec3.ZERO : client.player.position())
            : new Vec3(avatar.x(), avatar.y(), avatar.z());
    lease =
        ready
            ? new Lease(
                server,
                client.player.getUUID(),
                client.level.dimension(),
                host,
                OUTBOX.session(),
                System.nanoTime(),
                visualBase)
            : null;
    if (!ready) {
      INTENTS.clear();
      pickedLease = null;
      pickedTarget = null;
    }
    ProxyFeedback.tick(client, ready ? OUTBOX.session() : 0);
    MAILBOX.publish(lastHost, valid, OUTBOX.session(), OUTBOX.snapshot());
    if (ready != lastReady) {
      LOG.info(
          "Minecraft-authoritative proxies {} (targetCount={}, session={})",
          ready ? "ready" : "released",
          host == null ? 0 : host.targets().size(),
          OUTBOX.session());
      lastReady = ready;
    }
  }

  private static boolean fresh(Lease l) {
    return l != null
        && System.nanoTime() - l.readNanos >= 0
        && System.nanoTime() - l.readNanos < 250_000_000L
        && OUTBOX.matchesSession(l.session);
  }

  private static boolean sameSession(Lease a, Lease b) {
    return a != null
        && b != null
        && a.session == b.session
        && a.server == b.server
        && a.player.equals(b.player)
        && a.dimension.equals(b.dimension)
        && a.host.pid() == b.host.pid()
        && a.host.epoch() == b.host.epoch()
        && a.host.map() == b.host.map();
  }

  private void serverTick(MinecraftServer server) {
    Lease l = lease;
    if (!fresh(l) || l.server != server || !server.isSingleplayer() || server.isPublished()) {
      clear(server);
      return;
    }
    var outbox = OUTBOX.view();
    if (!outbox.ready() || outbox.session() != l.session || outbox.hostFrame() != l.host.frame()) {
      clear(server);
      return;
    }
    ServerPlayer player = server.getPlayerList().getPlayer(l.player);
    if (player == null
        || !player.isAlive()
        || player.isSpectator()
        || !player.level().dimension().equals(l.dimension)) {
      clear(server);
      return;
    }
    var retained = new HashSet<UUID>();
    for (var target : l.host.targets()) {
      float maximum = target.maxHp() / l.host.scale(),
          health =
              target.hp() / l.host.scale()
                  - outbox.pendingDamage(target.handle(), target.generation())
                  - SharedWorldClient.pendingDamage(target.handle());
      if (!target.hittable() || maximum > 1024 || health <= 0 || !Float.isFinite(health)) continue;
      UUID id = target.uuid(l.host.epoch());
      retained.add(id);
      CombatProxyEntity proxy = proxies.get(id);
      var shared = SharedWorldClient.proxy(target.handle());
      if (shared != null && !shared.isRemoved()) {
        if (proxy != null && proxy != shared && !SharedWorldClient.owns(proxy)) proxy.discard();
        proxy = shared;
        proxies.put(id, proxy);
        proxy.hostHandle = target.handle();
        proxy.hostGeneration = target.generation();
        proxy.hostEpoch = l.host.epoch();
      }
      if (proxy == null
          || proxy.isRemoved()
          || proxy.isDeadOrDying()
          || proxy.level() != player.level()) {
        if (proxy != null && !SharedWorldClient.owns(proxy)) proxy.discard();
        proxy = ProxyEntities.TYPE.create(player.level(), EntitySpawnReason.COMMAND);
        if (proxy == null) continue;
        proxy.setUUID(id);
        proxy.hostHandle = target.handle();
        proxy.hostGeneration = target.generation();
        proxy.hostEpoch = l.host.epoch();
        updateBounds(proxy, player.position(), target);
        proxy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Math.max(1, maximum));
        proxy.setHealth(health);
        player.level().addFreshEntity(proxy);
        proxies.put(id, proxy);
      }
      proxy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Math.max(1, maximum));
      proxy.setHealth(Math.min(health, proxy.getMaxHealth()));
      updateBounds(proxy, player.position(), target);
      proxy.setDeltaMovement(Vec3.ZERO);
    }
    var iterator = proxies.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (!retained.contains(entry.getKey())) {
        if (!SharedWorldClient.owns(entry.getValue())) entry.getValue().discard();
        iterator.remove();
      }
    }
  }

  private void clear(MinecraftServer server) {
    var it = proxies.values().iterator();
    while (it.hasNext()) {
      var p = it.next();
      if (p.level().getServer() == server) {
        if (!SharedWorldClient.owns(p)) p.discard();
        it.remove();
      }
    }
  }

  private static AABB bounds(Vec3 base, ProxyProtocol.Target target) {
    return new AABB(
        base.x + target.min().x(),
        base.y + target.min().y(),
        base.z + target.min().z(),
        base.x + target.max().x(),
        base.y + target.max().y(),
        base.z + target.max().z());
  }

  private static void updateBounds(
      CombatProxyEntity entity, Vec3 base, ProxyProtocol.Target target) {
    entity.setHostBounds(bounds(base, target));
  }

  private static ProxyProtocol.Target target(Lease l, CombatProxyEntity proxy) {
    if (proxy.hostEpoch != l.host.epoch()) return null;
    for (var t : l.host.targets())
      if (t.handle() == proxy.hostHandle && t.generation() == proxy.hostGeneration) return t;
    return null;
  }

  private static boolean melee(ItemStack item) {
    // Vanilla Player.attack also supports fists and ordinary non-weapon held items.
    return !item.isBroken()
        && !item.has(DataComponents.PIERCING_WEAPON)
        && !item.has(DataComponents.KINETIC_WEAPON)
        && BuiltInRegistries.ITEM.getKey(item.getItem()).toString().length() <= 40;
  }

  @Override
  public boolean permit(Object token, ServerPlayer player, CombatProxyEntity proxy) {
    if (player.level().dimension().equals(SharedWorldBlocks.DIMENSION)
        && !SharedWorldClient.combatReady()) return false;
    if (!(token instanceof Lease l) || !sameSession(l, lease)) return false;
    if (!fresh(l)
        || l.server != player.level().getServer()
        || l.server.isPublished()
        || !player.getUUID().equals(l.player)
        || !player.isAlive()
        || player.isSpectator()
        || !player.level().dimension().equals(l.dimension)
        || !OUTBOX.capacity()) return false;
    // Airborne host movement must not suppress ordinary attacks. Vanilla alone evaluates the
    // genuine server player's ground/fall state for criticals and sweeps; no host state is
    // invented.
    if (!melee(player.getMainHandItem())) return false;
    var current = lease;
    var t = target(l, proxy);
    var validation = current != null && current.host.frame() > l.host.frame() ? current : l;
    var live = target(validation, proxy);
    return t != null
        && t.hittable()
        && proxy.level() == player.level()
        && !proxy.isRemoved()
        && live != null
        && live.hittable()
        && player.isWithinAttackRange(player.getMainHandItem(), bounds(player.position(), live), 0);
  }

  @Override
  public Object begin(ServerPlayer player, CombatProxyEntity proxy) {
    var intent = INTENTS.poll(OUTBOX.session(), System.nanoTime());
    if (intent == null
        || !intent.target.equals(proxy.getUUID())
        || !permit(intent.selected, player, proxy)) return null;
    // The actual client-selected frame wins; moving the mouse while the packet travels must not
    // re-aim it.
    return intent.selected;
  }

  public static void rememberAttack(Entity entity) {
    if (entity instanceof CombatProxyEntity
        && entity.getUUID().equals(pickedTarget)
        && fresh(pickedLease))
      INTENTS.offer(pickedLease.session, System.nanoTime(), new Intent(pickedTarget, pickedLease));
  }

  @Override
  public void finished(
      Object token,
      ServerPlayer player,
      CombatProxyEntity primary,
      Map<CombatProxyEntity, Float> losses,
      String item,
      int before,
      int after,
      ProxyCombatAuthority.Feedback feedback) {
    if (player.level().dimension().equals(SharedWorldBlocks.DIMENSION)
        && !SharedWorldClient.combatReady()) return;
    if (!(token instanceof Lease l)
        || !sameSession(l, lease)
        || !fresh(l)
        || l.server != player.level().getServer()) return;
    ProxyFeedback.enqueue(l.session, l.host, l.visualBase, feedback);
    long id = ++attackId, now = MAILBOX.now();
    if (now < 0) return;
    // Native checks the primary crosshair hit before admitting its bounded sweep secondaries.
    var ordered = new LinkedHashMap<CombatProxyEntity, Float>();
    if (losses.containsKey(primary)) ordered.put(primary, losses.get(primary));
    losses.forEach(ordered::putIfAbsent);
    for (var entry : ordered.entrySet()) {
      var t = target(l, entry.getKey());
      float damage = entry.getValue();
      if (t == null || damage <= 0 || damage > 1000 || !Float.isFinite(damage)) continue;
      boolean queued =
          OUTBOX.add(
              l.session,
              new ProxyProtocol.Receipt(
                  0,
                  t.handle(),
                  t.generation(),
                  l.host.frame(),
                  now,
                  id,
                  damage,
                  before,
                  after,
                  player.getMaxHealth(),
                  item));
      LOG.info(
          "Vanilla proxy damage: queued={}, attack={}, target={}, damage={}, item={}, durability={}"
              + " -> {}",
          queued,
          id,
          Long.toUnsignedString(t.handle()),
          damage,
          item,
          before,
          after);
    }
  }

  private record Pick(ProxyProtocol.Target target, Vec3 hit, double distance) {}

  private static Pick pick(
      ProxyProtocol.Frame frame, Vec3 base, ItemStack item, LivingEntity player) {
    var c = frame.camera();
    var f = frame.forward();
    var start = base.add(c.x(), c.y(), c.z());
    var end = start.add(f.x() * 16, f.y() * 16, f.z() * 16);
    Pick closest = null;
    for (var target : frame.targets()) {
      if (!target.hittable() || target.maxHp() / frame.scale() > 1024) continue;
      var box = bounds(base, target);
      if (!player.getAttackRangeWith(item).isInRange(player, box, 0)) continue;
      var hit = box.contains(start) ? Optional.of(start) : box.clip(start, end);
      if (hit.isEmpty()) continue;
      double distance = start.distanceTo(hit.get());
      if (distance > frame.obstruction() + .001 || closest != null && distance >= closest.distance)
        continue;
      closest = new Pick(target, hit.get(), distance);
    }
    return closest;
  }

  public static void pick(Minecraft client) {
    pickedLease = null;
    pickedTarget = null;
    Lease l = lease;
    var observed = MAILBOX.read();
    try {
      if (fresh(l)) {
        // The camera renders faster than the 20 Hz Minecraft tick. Observe the same recent host aim
        // for picking; receipt ACKs and server entity updates still run only on their owning ticks.
        if (observed != null) {
          if (!observed.ready()
              || observed.pid() != l.host.pid()
              || observed.map() != l.host.map()
              || observed.epoch() != l.host.epoch()
              || observed.ackSession() != l.session
              || observed.frame() < l.host.frame()) l = null;
          else {
            var avatar = HostController.avatar();
            var visual =
                avatar == null ? l.visualBase : new Vec3(avatar.x(), avatar.y(), avatar.z());
            l =
                new Lease(
                    l.server,
                    l.player,
                    l.dimension,
                    observed,
                    l.session,
                    System.nanoTime(),
                    visual);
          }
        }
      }
      if (!fresh(l)
          || client.player == null
          || client.level == null
          || client.gui.screen() != null
          || !client.player.getUUID().equals(l.player)
          || !client.level.dimension().equals(l.dimension)) {
        if (client.hitResult instanceof EntityHitResult hit
            && hit.getEntity() instanceof CombatProxyEntity) {
          client.hitResult =
              BlockHitResult.miss(
                  hit.getLocation(), Direction.NORTH, BlockPos.containing(hit.getLocation()));
          client.crosshairPickEntity = null;
        }
        return;
      }
      var selected =
          pick(l.host, client.player.position(), client.player.getMainHandItem(), client.player);
      if (selected != null) {
        UUID sharedId = SharedWorldClient.proxyUuid(selected.target.handle());
        var entity =
            client.level.getEntity(
                sharedId == null ? selected.target.uuid(l.host.epoch()) : sharedId);
        if (entity instanceof CombatProxyEntity proxy) {
          updateBounds(proxy, client.player.position(), selected.target);
          pickedLease = l;
          pickedTarget = proxy.getUUID();
          client.hitResult = new EntityHitResult(proxy, selected.hit);
          client.crosshairPickEntity = proxy;
          return;
        }
      }
      // A coherent shared world uses real vanilla block/item/entity picking when no ER proxy was
      // selected.
      if (SharedWorldClient.active()) return;
      var f = l.host.forward();
      var end = client.player.getEyePosition().add(f.x() * 16, f.y() * 16, f.z() * 16);
      client.hitResult = BlockHitResult.miss(end, Direction.NORTH, BlockPos.containing(end));
      client.crosshairPickEntity = null;
    } finally {
      ProxyDebugRenderer.update(client, observed, pickedTarget, fresh(l));
    }
  }

  public static void close() {
    lease = null;
    INTENTS.clear();
    ProxyFeedback.clear();
    ProxyDebugRenderer.clear();
    OUTBOX.reset();
    MAILBOX.close();
  }
}
