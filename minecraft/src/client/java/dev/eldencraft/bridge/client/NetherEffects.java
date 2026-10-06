package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * The Nether as the player hears and feels it: vanilla nether ambience and music, distant ghasts,
 * thunder with a matching sky flash, a heartbeat when something hunts you, ash and lava pops,
 * vanilla's own portal overlay while you stand in the portal, and the ECNH page the compositor
 * paints Elden Ring with. Client thread only; every sound and particle is Minecraft's own.
 */
public final class NetherEffects {
  private static final NetherMailbox MAILBOX = new NetherMailbox();

  /** Nether wastes music, replacing whatever overworld track is playing. */
  public static final Music MUSIC = new Music(SoundEvents.MUSIC_BIOME_NETHER_WASTES, 20, 600, true);

  private static final RandomSource RANDOM = RandomSource.create();
  private static final long PID = ProcessHandle.current().pid();
  private static float amount, dread, lastRadius;
  private static long seenEntered, seenOpened, seenClosed, seenEmerge, seenShake;
  private static long openedMillis, flashMillis, beatMillis, shakeMillis;
  private static float shake;
  private static int tick,
      openedTick = -10000,
      nextAddition,
      nextMood,
      nextThunder,
      thunderIn = -1,
      nextScream,
      nextBeat,
      nextLava;
  private static Loop wastes, deltas;
  private static boolean published;

  private NetherEffects() {}

  public static boolean musicActive() {
    return amount > .5f;
  }

  public static float amount() {
    return amount;
  }

  public static void tick(Minecraft client) {
    var v = WorldNether.view;
    tick++;
    boolean shared = SharedWorldClient.active() && client.level != null && client.player != null;
    // Hell rushes in (one second) and drains away (two and a half).
    float target = shared && v.active() ? 1 : 0;
    amount = target > amount ? Math.min(target, amount + .05f) : Math.max(target, amount - .02f);
    long now = MAILBOX.ready() || MAILBOX.initialize() ? MAILBOX.now() : 0;
    if (v.enteredNanos() != seenEntered) {
      seenEntered = v.enteredNanos();
      if (shared) ui(SoundEvents.PORTAL_TRIGGER, .9f, 1);
    }
    if (v.openedNanos() != seenOpened) {
      seenOpened = v.openedNanos();
      if (shared && v.active()) opened(client, v, now);
    }
    if (v.closedNanos() != seenClosed) {
      seenClosed = v.closedNanos();
      if (shared) closed();
    }
    if (v.emergeNanos() != seenEmerge) {
      seenEmerge = v.emergeNanos();
      if (shared && v.emerge() != null) emerged(client.level, v.emerge());
    }
    if (v.shakeNanos() != seenShake) {
      seenShake = v.shakeNanos();
      shakeMillis = now;
      shake = v.shake();
    }
    if (shared) {
      // Vanilla's own purple portal overlay on the HUD plane while the portal takes hold.
      var p = client.player;
      if (v.warp() > 0) {
        p.portalEffectIntensity = Math.max(p.portalEffectIntensity, v.warp());
        portalParticles(client.level, p.getX(), p.getY(), p.getZ(), v.warp());
      }
      if (amount > 0) {
        dread(client);
        ambience(client, now);
        particles(client, v);
      }
    }
    loops(client);
    publish(v, now, shared);
  }

  // ---------------------------------------------------------------- moments
  private static void opened(Minecraft client, WorldNether.View v, long now) {
    openedMillis = now;
    flashMillis = now;
    beatMillis = now;
    openedTick = tick;
    ui(SoundEvents.PORTAL_TRAVEL, .75f, 1);
    ui(SoundEvents.WITHER_SPAWN, .6f, .8f);
    ui(SoundEvents.ELDER_GUARDIAN_CURSE, .7f, .9f);
    thunderIn = 6;
    nextThunder = tick + 20 * (14 + RANDOM.nextInt(10));
    nextScream = tick + 60;
    nextBeat = tick;
    var c = v.center();
    if (c != null)
      for (int i = 0; i < 90; i++) {
        double a = RANDOM.nextDouble() * Math.PI * 2, r = RANDOM.nextDouble() * 2.5;
        var type =
            i % 3 == 0
                ? ParticleTypes.LARGE_SMOKE
                : i % 3 == 1 ? ParticleTypes.FLAME : ParticleTypes.LAVA;
        client.level.addParticle(
            type,
            c.x() + Math.cos(a) * r,
            c.y() + RANDOM.nextDouble() * 3,
            c.z() + Math.sin(a) * r,
            Math.cos(a) * .15,
            .12 + RANDOM.nextDouble() * .2,
            Math.sin(a) * .15);
      }
  }

  private static void closed() {
    ui(SoundEvents.PORTAL_TRAVEL, .55f, 1.3f);
    ui(SoundEvents.BEACON_DEACTIVATE, .8f, .6f);
  }

  private static void emerged(ClientLevel level, WorldOrigin.Vec at) {
    level.playLocalSound(
        at.x(), at.y(), at.z(), SoundEvents.GHAST_SCREAM, SoundSource.HOSTILE, 4, .6f, false);
    for (int i = 0; i < 50; i++)
      level.addParticle(
          i % 2 == 0 ? ParticleTypes.PORTAL : ParticleTypes.LARGE_SMOKE,
          at.x() + (RANDOM.nextDouble() - .5) * 4,
          at.y() + RANDOM.nextDouble() * 4,
          at.z() + (RANDOM.nextDouble() - .5) * 4,
          0,
          .05,
          0);
  }

  // ---------------------------------------------------------------- the soundscape
  /** 0..1: something hostile close by, low Elden Ring HP, or the first seconds in hell. */
  private static void dread(Minecraft client) {
    var p = client.player;
    double nearest = 99;
    for (var e :
        client.level.getEntitiesOfClass(
            LivingEntity.class,
            p.getBoundingBox().inflate(20),
            e -> e instanceof Enemy && e.isAlive())) nearest = Math.min(nearest, e.distanceTo(p));
    float hunted = (float) Math.max(0, Math.min(1, 1 - (nearest - 4) / 16));
    float wounded = Math.max(0, Math.min(1, (.45f - SharedWorldClient.hostHealth()) / .3f));
    float arrival = Math.max(0, 1 - (tick - openedTick) / 240f);
    float want = Math.max(Math.max(hunted, wounded), arrival) * amount;
    dread += (want - dread) * .1f;
  }

  private static void ambience(Minecraft client, long now) {
    var p = client.player;
    var level = client.level;
    if (tick >= nextAddition) {
      nextAddition = tick + 60 + RANDOM.nextInt(120);
      var additions =
          new SoundEvent[] {
            SoundEvents.AMBIENT_NETHER_WASTES_ADDITIONS.value(),
            SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS.value(),
            SoundEvents.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS.value(),
            SoundEvents.AMBIENT_CRIMSON_FOREST_ADDITIONS.value()
          };
      client
          .getSoundManager()
          .play(
              SimpleSoundInstance.forAmbientAddition(additions[RANDOM.nextInt(additions.length)]));
    }
    if (tick >= nextMood) {
      nextMood = tick + 20 * (25 + RANDOM.nextInt(35));
      client
          .getSoundManager()
          .play(
              SimpleSoundInstance.forAmbientMood(
                  SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value(),
                  RANDOM,
                  p.getX(),
                  p.getY(),
                  p.getZ()));
    }
    // Lightning: the sky flashes now, the thunder arrives a moment later from far away.
    if (tick >= nextThunder) {
      nextThunder = tick + 20 * (18 + RANDOM.nextInt(24));
      flashMillis = now;
      thunderIn = 6 + RANDOM.nextInt(18);
    }
    if (thunderIn >= 0 && thunderIn-- == 0)
      around(
          level,
          p,
          60,
          SoundEvents.LIGHTNING_BOLT_THUNDER,
          SoundSource.WEATHER,
          8,
          .55f + RANDOM.nextFloat() * .2f);
    // Ghasts wailing somewhere out in the haze, more than the ones you can see.
    if (tick >= nextScream) {
      nextScream = tick + 20 * (12 + RANDOM.nextInt(18));
      around(
          level,
          p,
          34,
          RANDOM.nextInt(3) == 0 ? SoundEvents.GHAST_SCREAM : SoundEvents.GHAST_AMBIENT,
          SoundSource.HOSTILE,
          3,
          .45f + RANDOM.nextFloat() * .25f);
    }
    // The heartbeat quickens with dread and drives the compositor's vignette pulse.
    if (dread > .25f && tick >= nextBeat) {
      nextBeat = tick + Math.max(13, (int) (30 - dread * 16));
      beatMillis = now;
      client
          .getSoundManager()
          .play(SimpleSoundInstance.forUI(SoundEvents.WARDEN_HEARTBEAT, 1, .35f + .55f * dread));
    }
  }

  private static void around(
      ClientLevel level,
      net.minecraft.world.entity.player.Player p,
      double distance,
      SoundEvent sound,
      SoundSource source,
      float volume,
      float pitch) {
    double a = RANDOM.nextDouble() * Math.PI * 2;
    level.playLocalSound(
        p.getX() + Math.cos(a) * distance,
        p.getY() + 6 + RANDOM.nextDouble() * 10,
        p.getZ() + Math.sin(a) * distance,
        sound,
        source,
        volume,
        pitch,
        false);
  }

  private static void ui(SoundEvent sound, float volume, float pitch) {
    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
  }

  /** The two looping beds of the Nether, faded with the hell amount. */
  private static void loops(Minecraft client) {
    if (amount > 0) {
      if (wastes == null || wastes.isStopped()) {
        wastes = new Loop(SoundEvents.AMBIENT_NETHER_WASTES_LOOP.value(), .9f);
        client.getSoundManager().play(wastes);
      }
      if (deltas == null || deltas.isStopped()) {
        deltas = new Loop(SoundEvents.AMBIENT_BASALT_DELTAS_LOOP.value(), .5f);
        client.getSoundManager().play(deltas);
      }
    }
  }

  private static final class Loop extends AbstractTickableSoundInstance {
    private final float gain;

    Loop(SoundEvent sound, float gain) {
      super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
      this.gain = gain;
      looping = true;
      delay = 0;
      relative = true;
      attenuation = SoundInstance.Attenuation.NONE;
      volume = .0001f;
    }

    @Override
    public void tick() {
      volume = Math.max(.0001f, gain * amount);
      if (amount <= 0) stop();
    }
  }

  // ---------------------------------------------------------------- particles
  private static void portalParticles(ClientLevel level, double x, double y, double z, float warp) {
    for (int i = 0; i < (int) (2 + warp * 8); i++)
      level.addParticle(
          ParticleTypes.PORTAL,
          x + (RANDOM.nextDouble() - .5) * 2,
          y + RANDOM.nextDouble() * 2,
          z + (RANDOM.nextDouble() - .5) * 2,
          (RANDOM.nextDouble() - .5) * 2,
          -RANDOM.nextDouble(),
          (RANDOM.nextDouble() - .5) * 2);
  }

  private static void particles(Minecraft client, WorldNether.View v) {
    var level = client.level;
    var p = client.player;
    var origin = SharedWorldClient.origin();
    if (origin == null) return;
    // Ash and spores drifting everywhere, as in the Nether's own biomes.
    for (int i = (int) (6 * amount); i > 0; i--) {
      float roll = RANDOM.nextFloat();
      ParticleOptions type =
          roll < .55f
              ? ParticleTypes.ASH
              : roll < .85f ? ParticleTypes.WHITE_ASH : ParticleTypes.CRIMSON_SPORE;
      level.addParticle(
          type,
          p.getX() + (RANDOM.nextDouble() - .5) * 28,
          p.getY() - 2 + RANDOM.nextDouble() * 12,
          p.getZ() + (RANDOM.nextDouble() - .5) * 28,
          0,
          0,
          0);
    }
    // Lava pops from the painted pools the compositor draws on Elden Ring's ground.
    boolean centred = v.corner() != null && origin.anchorId() == v.anchor();
    var g = centred ? v.corner() : origin.guestOrigin();
    int gx = (int) Math.floor(g.x()), gz = (int) Math.floor(g.z());
    float cx = centred ? (float) (v.center().x() - gx) : 0,
        cz = centred ? (float) (v.center().z() - gz) : 0,
        radius =
            centred ? NetherRules.radius(v.seconds(System.nanoTime())) : NetherRules.EVERYWHERE;
    for (int i = 0; i < 3; i++) {
      int x = (int) Math.floor(p.getX() + (RANDOM.nextDouble() - .5) * 24),
          z = (int) Math.floor(p.getZ() + (RANDOM.nextDouble() - .5) * 24);
      int bx = x - gx, bz = z - gz;
      if (centred && NetherRules.ragged(bx, bz, cx, cz) > radius) continue;
      if (NetherRules.ground(bx, bz, centred, cx, cz) != NetherRules.GROUND_LAVA) continue;
      double y = surface(level, x, z, (int) Math.floor(p.getY()));
      if (Double.isNaN(y)) continue;
      level.addParticle(
          ParticleTypes.LAVA, x + RANDOM.nextDouble(), y + .05, z + RANDOM.nextDouble(), 0, 0, 0);
      if (tick >= nextLava) {
        nextLava = tick + 30 + RANDOM.nextInt(50);
        level.playLocalSound(
            x + .5,
            y,
            z + .5,
            RANDOM.nextBoolean() ? SoundEvents.LAVA_POP : SoundEvents.LAVA_AMBIENT,
            SoundSource.BLOCKS,
            .6f,
            .8f + RANDOM.nextFloat() * .3f,
            false);
      }
    }
  }

  /** Height of the ground at a column near y: sampled terrain or a real block; NaN if none. */
  private static double surface(ClientLevel level, int x, int z, int y0) {
    var cursor = new BlockPos.MutableBlockPos();
    for (int y = y0 + 4; y >= y0 - 8; y--) {
      cursor.set(x, y, z);
      var s = level.getBlockState(cursor);
      if (s.is(SharedWorldBlocks.TERRAIN)) {
        var shape = SharedTerrain.outline(cursor);
        if (!shape.isEmpty()) return y + shape.max(Direction.Axis.Y);
      } else if (!s.isAir() && !s.getCollisionShape(level, cursor).isEmpty()) return y + 1;
    }
    return Double.NaN;
  }

  // ---------------------------------------------------------------- compositor page
  private static void publish(WorldNether.View v, long now, boolean shared) {
    var ctx = SharedWorldClient.blockMeshContext();
    boolean live = shared && ctx != null && now > 0 && (amount > 0 || v.warp() > 0);
    if (!live) {
      if (published) {
        MAILBOX.clear();
        published = false;
      }
      return;
    }
    var o = ctx.origin();
    boolean centred = v.corner() != null && v.anchor() == o.anchorId();
    var g = centred ? v.corner() : o.guestOrigin();
    var grid = o.toHost(Math.floor(g.x()), Math.floor(g.y()), Math.floor(g.z()));
    var center = centred ? o.toHost(v.center().x(), v.center().y(), v.center().z()) : grid;
    // While draining after a close, the painted ground stays where it had spread.
    if (v.active())
      lastRadius =
          centred ? NetherRules.radius(v.seconds(System.nanoTime())) : NetherRules.EVERYWHERE;
    try {
      MAILBOX.publish(
          NetherProtocol.encode(
              new NetherProtocol.State(
                  PID,
                  ctx.hostPid(),
                  o.epoch(),
                  o.map(),
                  o.anchorId(),
                  true,
                  centred,
                  grid,
                  center,
                  amount,
                  Math.max(1.5f, lastRadius),
                  Math.min(1, v.warp()),
                  Math.min(1, shake),
                  Math.min(1, dread),
                  now,
                  openedMillis,
                  flashMillis,
                  beatMillis,
                  shakeMillis)));
      published = true;
    } catch (IllegalArgumentException invalid) {
      MAILBOX.clear();
      published = false;
    }
  }

  public static void close() {
    MAILBOX.close();
  }
}
