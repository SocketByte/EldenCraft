package dev.eldencraft.bridge.client;

import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a successful guard does for an enchanted shield, on the integrated server. A block arrives
 * from two places: native hits, which Elden Ring resolves and reports as blocked damage events, and
 * Minecraft hits, which vanilla resolves in applyItemBlocking. Both end in {@link #blocked}. The
 * numbers live here so the enchantment descriptions can never drift from the effects.
 */
public final class CampaignShields {
  /** Healing restores at most once per this many ticks, so a flurry of hits stays a trickle. */
  public static final long HEAL_COOLDOWN_TICKS = 10;

  /** One blast per this many ticks, so a boss combo cannot chain explosions. */
  public static final long BLAST_COOLDOWN_TICKS = 40;

  /** How long a block keeps the next melee hit empowered. */
  public static final long RIPOSTE_WINDOW_TICKS = 60;

  /** Blocks closer together than this continue Resounding's scale. */
  public static final long CHAIN_WINDOW_TICKS = 60;

  /** Explosion power: a creeper is 3, TNT 4. Both blasts spare the player and the terrain. */
  public static final float EXPLOSIVE_POWER = 1.5f, LAST_HURRAH_POWER = 2.5f;

  /** Pentatonic climb, in semitones from the bell's own pitch; the top note repeats. */
  private static final int[] SCALE = {-7, -5, -3, 0, 2, 5, 7, 9, 12};

  private record Chain(long tick, int count) {}

  private record Riposte(long until, double bonus) {}

  private static final Map<UUID, Long> HEALED = new HashMap<>(), BLASTED = new HashMap<>();
  private static final Map<UUID, Chain> CHAINS = new HashMap<>();
  private static final Map<UUID, Riposte> RIPOSTES = new HashMap<>();
  private static final Map<UUID, Float> PENDING = new HashMap<>();

  private CampaignShields() {}

  public static void reset() {
    HEALED.clear();
    BLASTED.clear();
    CHAINS.clear();
    RIPOSTES.clear();
    PENDING.clear();
  }

  // Pure rules, mirrored by the enchantment descriptions.

  /** Fraction of the configured guard stamina cost still paid. */
  public static double guardCostScale(int level) {
    return 1 - .05 * Math.clamp(level, 0, 3);
  }

  /** Share of maximum health one block restores. */
  public static double healFraction(int level) {
    return level <= 0 ? 0 : new double[] {.01, .015, .02}[Math.clamp(level, 1, 3) - 1];
  }

  /** Share of maximum stamina one block restores. */
  public static double staminaRefund(int level) {
    return level <= 0 ? 0 : new double[] {.02, .04, .06}[Math.clamp(level, 1, 3) - 1];
  }

  /** Extra damage on the first melee hit after a block. */
  public static double riposteBonus(int level) {
    return level <= 0 ? 0 : new double[] {.1, .2, .3}[Math.clamp(level, 1, 3) - 1];
  }

  /** Semitones above the bell's pitch for the {@code chain}th consecutive block, from one. */
  public static int semitones(int chain) {
    return SCALE[Math.clamp(chain - 1, 0, SCALE.length - 1)];
  }

  public static int scaleLength() {
    return SCALE.length;
  }

  /** Vanilla sound pitch; the climb stays within the 0.5 to 2.0 range the engine honours. */
  public static float pitch(int semitones) {
    return (float) Math.clamp(Math.pow(2, semitones / 12d), .5, 2);
  }

  /** Whether a cooldown that started at {@code last} (null for never) has run out. */
  public static boolean ready(Long last, long now, long cooldown) {
    return last == null || now < last || now - last >= cooldown;
  }

  /**
   * Length of Resounding's chain after a block at {@code now}; the previous block's tick is -1 for
   * none.
   */
  public static int chainLength(long previousTick, int previousCount, long now) {
    boolean continued =
        previousTick >= 0 && now >= previousTick && now - previousTick <= CHAIN_WINDOW_TICKS;
    return continued ? previousCount + 1 : 1;
  }

  // Item lookups.

  public static int level(ItemStack shield, String id) {
    return CampaignEnchantments.shield(shield) ? CampaignEnchantments.level(shield, id) : 0;
  }

  /** Stamina cost scale for a guard held with this shield; 1 when it has no Guarding Ability. */
  public static double guardCostScale(ItemStack shield) {
    return guardCostScale(level(shield, "guarding_ability"));
  }

  // Server events.

  /**
   * A hit was blocked by {@code shield}. Called after the hit's stamina has been charged, so a
   * refund is not swallowed by a full bar.
   */
  public static void blocked(ServerPlayer player, ItemStack shield) {
    if (!CampaignEnchantments.shield(shield) || !CampaignCombat.active(player)) return;
    var id = player.getUUID();
    long now = player.level().getGameTime();
    int healing = level(shield, "healing");
    if (healing > 0 && ready(HEALED.get(id), now, HEAL_COOLDOWN_TICKS)) {
      HEALED.put(id, now);
      CampaignPhysick.queueHeal(player, player.getMaxHealth() * healFraction(healing));
    }
    int wind = level(shield, "second_wind");
    if (wind > 0) CampaignCombat.refund(player, staminaRefund(wind));
    int riposte = level(shield, "riposte");
    if (riposte > 0) {
      RIPOSTES.put(id, new Riposte(now + RIPOSTE_WINDOW_TICKS, riposteBonus(riposte)));
      if (player.level() instanceof ServerLevel level)
        level.sendParticles(
            ParticleTypes.CRIT,
            player.getX(),
            player.getY() + 1,
            player.getZ(),
            6,
            .3,
            .3,
            .3,
            .15);
    }
    if (level(shield, "explosive") > 0 && ready(BLASTED.get(id), now, BLAST_COOLDOWN_TICKS)) {
      BLASTED.put(id, now);
      PENDING.merge(id, EXPLOSIVE_POWER, Math::max);
    }
    if (level(shield, "resounding") > 0) ring(player, now);
  }

  /** The guard broke while {@code shield} was raised. */
  public static void broken(ServerPlayer player, ItemStack shield) {
    if (level(shield, "last_hurrah") > 0 && CampaignCombat.active(player))
      PENDING.merge(player.getUUID(), LAST_HURRAH_POWER, Math::max);
  }

  /**
   * End of the campaign tick. Blasts wait for here rather than detonating inside the hit that
   * caused them, so an explosion never starts in the middle of the player's own damage handling.
   */
  public static void tick(ServerPlayer player) {
    var id = player.getUUID();
    var power = PENDING.remove(id);
    if (power != null && player.isAlive() && CampaignCombat.active(player)) blast(player, power);
    var riposte = RIPOSTES.get(id);
    if (riposte != null && player.level().getGameTime() > riposte.until()) RIPOSTES.remove(id);
  }

  /**
   * The multiplier for the player's next melee hit. A pending Riposte is spent by that hit, whether
   * or not the enemy was hurt by it.
   */
  public static double riposte(ServerPlayer player) {
    var armed = RIPOSTES.remove(player.getUUID());
    if (armed == null || player.level().getGameTime() > armed.until()) return 1;
    player
        .level()
        .playSound(
            null,
            player.getX(),
            player.getY(),
            player.getZ(),
            SoundEvents.PLAYER_ATTACK_CRIT,
            SoundSource.PLAYERS,
            1f,
            1.2f);
    return 1 + armed.bonus();
  }

  /** While the guard can actually block, Stalwart tells native to queue no knockback. */
  public static boolean stalwart(ServerPlayer player) {
    return CampaignCombat.nativeGuardPermitted(player)
        && level(player.getUseItem(), "stalwart") > 0;
  }

  private static void ring(ServerPlayer player, long now) {
    var id = player.getUUID();
    var previous = CHAINS.get(id);
    int count = previous == null ? 1 : chainLength(previous.tick(), previous.count(), now);
    CHAINS.put(id, new Chain(now, count));
    int semitones = semitones(count);
    var level = player.level();
    level.playSound(
        null,
        player.getX(),
        player.getY(),
        player.getZ(),
        SoundEvents.NOTE_BLOCK_BELL,
        SoundSource.PLAYERS,
        1.2f,
        pitch(semitones));
    // Past the top of the scale the bell is joined by a chime, as a small fanfare.
    if (count > SCALE.length)
      level.playSound(
          null,
          player.getX(),
          player.getY(),
          player.getZ(),
          SoundEvents.NOTE_BLOCK_CHIME,
          SoundSource.PLAYERS,
          1f,
          pitch(semitones));
    if (level instanceof ServerLevel server)
      // A note particle with no count takes its colour from the first velocity component.
      server.sendParticles(
          ParticleTypes.NOTE,
          player.getX(),
          player.getY() + 2.1,
          player.getZ(),
          0,
          (semitones + 12) / 24d,
          0,
          0,
          1);
  }

  private static void blast(ServerPlayer player, float power) {
    var look = player.getLookAngle();
    var flat = new Vec3(look.x, 0, look.z);
    var forward = flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize();
    var at = player.position().add(0, player.getBbHeight() * .5, 0).add(forward);
    // The source entity is never hurt by its own blast, and NONE leaves every block alone.
    player.level().explode(player, at.x, at.y, at.z, power, Level.ExplosionInteraction.NONE);
  }
}
