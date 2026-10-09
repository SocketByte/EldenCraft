package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Variant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Torrent, the spectral steed: Y whistles a real vanilla horse under the host-driven player. The
 * integrated server owns the summon rules, the horse and its health; native owns the gait and
 * collision, and rides only while this publishes a fresh mount.
 */
public final class WorldTorrent {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_torrent");

  /** Marks the summoned horse; a saved copy is never loaded back. */
  public static final String TAG = "eldencraft_torrent";

  /** On the player: Torrent fell and needs food to return. Saved with the player. */
  static final String FALLEN_TAG = "eldencraft_torrent_fallen";

  private record Input(UUID player, long pid, long nanos, long presses) {}

  private record Context(MinecraftServer server, UUID player, long pid, long epoch, long session) {}

  private record Published(Context context, long nanos, JsonObject json) {}

  private static volatile Input input;
  private static volatile Published published;
  private static long presses;
  // Read by the client and render threads.
  private static volatile int clientId = -1;
  private static volatile long hurtNanos, menuNanos;
  // Everything below is integrated-server-thread owned.
  private static Context context;
  private static ServerPlayer rider;
  private static Horse horse;
  private static final FlightPolicy.Edges EDGES = new FlightPolicy.Edges();
  private static WorldProtocol.Host host;
  private static long readNanos, clock, sequence, syncedTick = -1, cooldownUntil;
  private static int unsynced;
  private static float riderHp = -1, riderMaxHp = -1;
  private static float storedHealth = TorrentPolicy.MAX_HEALTH;
  private static long storedNanos;

  private WorldTorrent() {}

  /** Client tick: Y presses made in gameplay, never inside a screen or chat. */
  public static void input(Minecraft c, HostState.Snapshot frame, int pressed) {
    // Graces and NPCs are met on foot, as in Elden Ring.
    var interaction = CampaignInteractions.snapshot();
    if (interaction != null && interaction.menu() != null) menuNanos = System.nanoTime();
    if (frame == null
        || !frame.active()
        || c.player == null
        || c.gui.screen() != null
        || !SharedWorldClient.active()) return;
    if ((pressed & HostState.TORRENT) != 0) presses++;
    input = new Input(c.player.getUUID(), frame.publisherPid(), System.nanoTime(), presses);
  }

  /** Server thread, after the stand-in took this tick's host feet and health. */
  public static void beforeSync(
      ServerPlayer p, WorldProtocol.Host h, long session, long read, long ticks) {
    var next = new Context(p.level().getServer(), p.getUUID(), h.pid(), h.epoch(), session);
    var i = input;
    if (!next.equals(context) || rider != p) {
      clear(null);
      context = next;
      rider = p;
      EDGES.baseline(i == null ? 0 : i.presses);
    }
    host = h;
    readNanos = read;
    clock = ticks;
    syncedTick = next.server.getTickCount();
    boolean fresh =
        i != null
            && i.player.equals(p.getUUID())
            && i.pid == h.pid()
            && FlightPolicy.fresh(i.nanos, System.nanoTime());
    if (fresh && EDGES.take(i.presses, true)) {
      if (horse == null) summon(p, h);
      // Like Elden Ring, Torrent is only left on the ground.
      else if (h.grounded()) dismiss(p, "Torrent dismissed.", true);
    }
    long menu = menuNanos;
    if (horse != null && menu != 0 && System.nanoTime() - menu < 250_000_000L) dismiss(p, "", true);
    if (horse != null) ride(p, h);
  }

  private static void summon(ServerPlayer p, WorldProtocol.Host h) {
    boolean fallen = p.entityTags().contains(FALLEN_TAG);
    var refusal =
        TorrentPolicy.summon(
            TorrentPolicy.allowedArea(h.sourceMap()),
            h.grounded(),
            p.isFallFlying(),
            p.isInWater() || p.isInLava(),
            System.nanoTime() - cooldownUntil < 0,
            fallen,
            p.getFoodData().getFoodLevel(),
            p.isCreative());
    if (refusal != TorrentPolicy.Refusal.NONE) {
      if (!refusal.message.isEmpty()) p.sendOverlayMessage(Component.literal(refusal.message));
      return;
    }
    var level = p.level();
    Horse steed;
    try (var scope = WorldMobSpawning.summon(level, EntityTypes.HORSE)) {
      steed = EntityTypes.HORSE.create(level, EntitySpawnReason.EVENT);
      if (steed == null) return;
      steed.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
      steed.setComponent(DataComponents.HORSE_VARIANT, Variant.BLACK);
      steed.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
      steed.setDropChance(EquipmentSlot.SADDLE, 0);
      steed.setTamed(true);
      steed.setNoAi(true);
      steed.setNoGravity(true);
      steed.noPhysics = true;
      // Native hits reach Torrent through the rider (sharedDamage), never vanilla damage.
      steed.setPermanentlyInvulnerable(true);
      steed.setCustomName(Component.literal("Torrent"));
      steed.setCustomNameVisible(false);
      steed.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TorrentPolicy.MAX_HEALTH);
      float health =
          fallen
              ? TorrentPolicy.MAX_HEALTH
              : TorrentPolicy.rested(storedHealth, System.nanoTime() - storedNanos);
      steed.setHealth(Math.max(1, health));
      steed.addTag(TAG);
      horse = steed; // Before adding: the load callback must not take it for a leftover.
      if (!level.addFreshEntity(steed)) {
        horse = null;
        return;
      }
    }
    if (fallen) {
      p.removeTag(FALLEN_TAG);
      if (!p.isCreative())
        p.getFoodData().setFoodLevel(p.getFoodData().getFoodLevel() - TorrentPolicy.REVIVE_FOOD);
      p.sendOverlayMessage(Component.literal("Torrent returns."));
    }
    clientId = steed.getId();
    riderHp = h.hp();
    riderMaxHp = h.maxHp();
    unsynced = 0;
    level.sendParticles(
        ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + .8, p.getZ(), 40, .6, .6, .6, .02);
    level.playSound(
        null,
        p.getX(),
        p.getY(),
        p.getZ(),
        SoundEvents.NOTE_BLOCK_FLUTE,
        SoundSource.PLAYERS,
        .8f,
        1.6f);
    level.playSound(
        null, p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_AMBIENT, SoundSource.NEUTRAL, 1, 1);
    LOG.info(
        "Torrent summoned in region {} ({} health)",
        Long.toHexString(h.sourceMap()),
        steed.getHealth());
  }

  private static void ride(ServerPlayer p, WorldProtocol.Host h) {
    if (horse.isRemoved() || horse.isDeadOrDying() || horse.level() != p.level()) {
      // Only a command can kill an invulnerable horse; treat it as Torrent falling.
      fall(p);
      return;
    }
    if (!TorrentPolicy.allowedArea(h.sourceMap())) {
      dismiss(p, "Torrent cannot be ridden here.", true);
      return;
    }
    if (!p.isAlive() || p.isFallFlying() || p.isUnderWater() || p.isInLava()) {
      dismiss(p, "", true);
      return;
    }
    float damage = TorrentPolicy.sharedDamage(riderHp, h.hp(), riderMaxHp, h.maxHp());
    riderHp = h.hp();
    riderMaxHp = h.maxHp();
    if (damage > 0) {
      if (horse.getHealth() - damage <= 0) {
        fall(p);
        return;
      }
      horse.setHealth(horse.getHealth() - damage);
      hurtNanos = System.nanoTime();
      p.level()
          .playSound(
              null,
              horse.getX(),
              horse.getY(),
              horse.getZ(),
              SoundEvents.HORSE_HURT,
              SoundSource.NEUTRAL,
              1,
              1);
    }
    // The stand-in already holds this tick's host feet. Rendering aligns to the host frame.
    horse.snapTo(p.getX(), p.getY(), p.getZ(), horse.getYRot(), 0);
    horse.setDeltaMovement(Vec3.ZERO);
    horse.fallDistance = 0;
  }

  /** Torrent's health ran out: thrown off, and the next summon costs food. */
  private static void fall(ServerPlayer p) {
    var steed = horse;
    if (steed != null && p.level() == steed.level()) {
      p.level()
          .sendParticles(
              ParticleTypes.SOUL,
              steed.getX(),
              steed.getY() + .8,
              steed.getZ(),
              30,
              .6,
              .5,
              .6,
              .03);
      p.level()
          .playSound(
              null,
              steed.getX(),
              steed.getY(),
              steed.getZ(),
              SoundEvents.HORSE_DEATH,
              SoundSource.NEUTRAL,
              1,
              1);
    }
    remove();
    storedHealth = 0;
    storedNanos = System.nanoTime();
    p.addTag(FALLEN_TAG);
    p.sendOverlayMessage(Component.literal("Torrent has fallen."));
    LOG.info("Torrent fell");
  }

  private static void dismiss(ServerPlayer p, String message, boolean effects) {
    var steed = horse;
    if (steed == null) return;
    if (effects && p.level() == steed.level()) {
      p.level()
          .sendParticles(
              ParticleTypes.SOUL_FIRE_FLAME,
              steed.getX(),
              steed.getY() + .8,
              steed.getZ(),
              25,
              .6,
              .5,
              .6,
              .01);
      p.level()
          .playSound(
              null,
              steed.getX(),
              steed.getY(),
              steed.getZ(),
              SoundEvents.HORSE_BREATHE,
              SoundSource.NEUTRAL,
              1,
              1);
    }
    if (!message.isEmpty()) p.sendOverlayMessage(Component.literal(message));
    remove();
    LOG.info("Torrent dismissed");
  }

  /** Discards the horse, keeping its health for the next summon. Never posts a message. */
  private static void remove() {
    var steed = horse;
    horse = null;
    clientId = -1;
    published = null;
    riderHp = riderMaxHp = -1;
    if (steed == null) return;
    cooldownUntil = System.nanoTime() + TorrentPolicy.COOLDOWN_NANOS;
    if (steed.isAlive()) {
      storedHealth = steed.getHealth();
      storedNanos = System.nanoTime();
    }
    if (!steed.isRemoved()) steed.discard();
  }

  public static void endTick(MinecraftServer server) {
    var c = context;
    if (c == null || c.server != server) {
      published = null;
      return;
    }
    if (syncedTick != server.getTickCount()) {
      // Keep the last observation with its original timestamp through a missed
      // server sync. The client/native freshness checks still expire it.
      if (++unsynced > TorrentPolicy.UNSYNCED_TICKS) {
        var p = rider;
        if (p != null && !p.isRemoved()) dismiss(p, "", false);
        else remove();
      }
      return;
    }
    unsynced = 0;
    var h = host;
    var json = new JsonObject();
    json.addProperty("sequence", ++sequence);
    // This is the actual native clock copied with the host lease. Extrapolating
    // it with nanoTime can put a genuine observation ahead of GetTickCount64,
    // making the native consumer reject it and drop both gait and saddle lift.
    json.addProperty("time_ms", clock);
    json.addProperty("observed_frame", h.frame());
    json.addProperty("mounted", horse != null);
    published = new Published(c, System.nanoTime(), json);
  }

  /** Guest mount state, including explicit dismounts; null without a fresh observation. */
  public static JsonObject snapshot(WorldProtocol.Host h, long session) {
    var p = published;
    if (p == null
        || !TorrentPolicy.fresh(p.nanos, System.nanoTime())
        || p.context.pid != h.pid()
        || p.context.epoch != h.epoch()
        || p.context.session != session) return null;
    return p.json.deepCopy();
  }

  /** Server thread: elytra cannot open while Torrent carries the player. */
  public static boolean rides(ServerPlayer p) {
    return horse != null && rider == p;
  }

  /** Either side: Torrent is never picked, pushed or published as a native mob. */
  public static boolean is(Entity e) {
    return e.level().isClientSide() ? e.getId() == clientId : e == horse;
  }

  /** Client/render thread: the client copy of the summoned horse, or null. */
  public static AbstractHorse clientTorrent() {
    int id = clientId;
    var level = Minecraft.getInstance().level;
    if (id < 0 || level == null) return null;
    return level.getEntity(id) instanceof AbstractHorse steed ? steed : null;
  }

  public static boolean recentlyHurt() {
    long at = hurtNanos;
    return at != 0 && System.nanoTime() - at < 400_000_000L;
  }

  /** ServerEntityEvents.ALLOW_LOAD: a Torrent written by an autosave never returns. */
  public static boolean allowLoad(
      Entity entity, ServerLevel level, EntitySpawnReason reason, boolean fresh) {
    return !entity.entityTags().contains(TAG) || entity == horse;
  }

  /** Removes the horse without effects (lost host, server stop, other player). */
  public static void clear(ServerPlayer player) {
    if (player == null || rider == player) {
      remove();
      published = null;
      if (player == null) {
        context = null;
        rider = null;
        host = null;
      }
    }
  }

  public static void serverStopping(MinecraftServer server) {
    if (context == null || context.server == server) clear(null);
  }
}
