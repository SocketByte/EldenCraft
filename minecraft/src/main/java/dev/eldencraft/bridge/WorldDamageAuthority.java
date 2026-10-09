package dev.eldencraft.bridge;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Explosion;

/** Scoped real server damage; never manufactures a hit from an animation or client claim. */
public final class WorldDamageAuthority {
  public interface Adapter {
    Object begin(ServerLevel level, LivingEntity target, DamageSource source);

    void finish(Object token, LivingEntity target, float actualLoss);

    boolean pause(Entity entity);

    /** Real Minecraft hazard on a paired player or current native enemy proxy. */
    default Object environment(ServerLevel level, LivingEntity target, DamageSource source) {
      return null;
    }

    default Object potionCloud(ServerLevel level, LivingEntity target, DamageSource source) {
      return null;
    }

    default boolean environmentalEffects(Entity entity) {
      return false;
    }
  }

  /**
   * Hazards that become Elden Ring damage, so one health pool decides death. Collision-derived
   * damage stays excluded: Elden Ring owns falls, world bounds and walls, and the invisible terrain
   * approximation must never suffocate, crush or void-kill the player. Minecraft's /kill is also
   * not a physical hazard in the native world.
   */
  public static final List<ResourceKey<DamageType>> ENVIRONMENT =
      List.of(
          DamageTypes.IN_FIRE,
          DamageTypes.CAMPFIRE,
          DamageTypes.LIGHTNING_BOLT,
          DamageTypes.ON_FIRE,
          DamageTypes.LAVA,
          DamageTypes.HOT_FLOOR,
          DamageTypes.SULFUR_CUBE_HOT,
          DamageTypes.DROWN,
          DamageTypes.STARVE,
          DamageTypes.CACTUS,
          DamageTypes.MAGIC,
          DamageTypes.WITHER,
          DamageTypes.SWEET_BERRY_BUSH,
          DamageTypes.FREEZE);

  /** Lava and the fire it lights share one scale, so burning cannot replace a reduced lava hit. */
  public static final List<ResourceKey<DamageType>> LAVA =
      List.of(DamageTypes.LAVA, DamageTypes.ON_FIRE, DamageTypes.IN_FIRE, DamageTypes.CAMPFIRE);

  /**
   * Campaign share of a hazard's damage that reaches a native enemy proxy. Explosions (TNT,
   * creepers) and lava would otherwise outdamage every weapon once converted to native HP.
   */
  public static double enemyHazardScale(DamageSource source) {
    var rules = CampaignConfig.current();
    if (!rules.enabled()) return 1;
    if (blast() != null) return rules.explosionDamageScale;
    return LAVA.stream().anyMatch(source::is) ? rules.lavaDamageScale : 1;
  }

  public static boolean environmental(DamageSource source) {
    return source.getEntity() == null
        && (source.getDirectEntity() == null || source.is(DamageTypes.LIGHTNING_BOLT))
        && ENVIRONMENT.stream().anyMatch(source::is);
  }

  public static Object environment(ServerLevel level, LivingEntity target, DamageSource source) {
    var a = adapter;
    return a == null
        ? null
        : environmental(source)
            ? a.environment(level, target, source)
            : a.potionCloud(level, target, source);
  }

  public static boolean environmentalEffects(Entity entity) {
    var a = adapter;
    return a != null && a.environmentalEffects(entity);
  }

  public static volatile Adapter adapter;

  public record Blast(Explosion explosion, long event) {}

  private static final ThreadLocal<Blast> BLAST = new ThreadLocal<>();
  private static final ThreadLocal<Integer> PLAYER_DAMAGE = ThreadLocal.withInitial(() -> 0);
  private static long eventCounter;

  private WorldDamageAuthority() {}

  public static Blast blast() {
    return BLAST.get();
  }

  public static long nextEvent() {
    return ++eventCounter;
  }

  public static Blast beginExplosion(Explosion explosion) {
    var prior = BLAST.get();
    BLAST.set(new Blast(explosion, nextEvent()));
    return prior;
  }

  public static void endExplosion(Blast prior) {
    if (prior == null) BLAST.remove();
    else BLAST.set(prior);
  }

  public static Object begin(ServerLevel level, LivingEntity target, DamageSource source) {
    var a = adapter;
    return a == null ? null : a.begin(level, target, source);
  }

  public static void finish(Object token, LivingEntity target, float loss) {
    var a = adapter;
    if (token != null && a != null && Float.isFinite(loss) && loss > 0)
      a.finish(token, target, loss);
  }

  public static boolean pause(Entity entity) {
    var a = adapter;
    return a != null && a.pause(entity);
  }

  public static void enterPlayerDamage() {
    PLAYER_DAMAGE.set(PLAYER_DAMAGE.get() + 1);
  }

  public static void leavePlayerDamage() {
    int depth = PLAYER_DAMAGE.get() - 1;
    if (depth <= 0) PLAYER_DAMAGE.remove();
    else PLAYER_DAMAGE.set(depth);
  }

  public static boolean playerDamage() {
    return PLAYER_DAMAGE.get() > 0;
  }
}
