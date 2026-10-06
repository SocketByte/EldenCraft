package dev.eldencraft.bridge;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /** Minecraft hazard on the bridged player itself; null when not bridged. */
    default Object environment(ServerLevel level, ServerPlayer player, DamageSource source) {
      return null;
    }
  }

  /**
   * Hazards that become Elden Ring damage, so one health pool decides death. Collision-derived
   * damage stays excluded: Elden Ring owns falls and walls, and the invisible terrain approximation
   * must never suffocate or crush the player.
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
          DamageTypes.FELL_OUT_OF_WORLD,
          DamageTypes.MAGIC,
          DamageTypes.WITHER,
          DamageTypes.SWEET_BERRY_BUSH,
          DamageTypes.FREEZE,
          DamageTypes.GENERIC_KILL);

  public static boolean environmental(DamageSource source) {
    return source.getEntity() == null
        && (source.getDirectEntity() == null || source.is(DamageTypes.LIGHTNING_BOLT))
        && ENVIRONMENT.stream().anyMatch(source::is);
  }

  public static Object environment(ServerLevel level, ServerPlayer player, DamageSource source) {
    var a = adapter;
    return a == null || !environmental(source) ? null : a.environment(level, player, source);
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
