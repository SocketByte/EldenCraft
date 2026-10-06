package dev.eldencraft.bridge;

import java.util.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/**
 * Server-thread scope around vanilla Player.attack; no receipt exists for a merely animated client
 * swing.
 */
public final class ProxyCombatAuthority {
  public interface Adapter {
    Object begin(ServerPlayer player, CombatProxyEntity target);

    boolean permit(Object token, ServerPlayer player, CombatProxyEntity target);

    void finished(
        Object token,
        ServerPlayer player,
        CombatProxyEntity primary,
        Map<CombatProxyEntity, Float> losses,
        String item,
        int wearBefore,
        int wearAfter,
        Feedback feedback);
  }

  public record Animation(UUID target, ParticleOptions particle) {}

  public record Feedback(
      Vec3 origin,
      List<SoundEvent> sounds,
      List<ClientboundLevelParticlesPacket> particles,
      List<Animation> animations) {}

  public static volatile Adapter adapter;

  private record Context(
      Object token,
      ServerPlayer player,
      CombatProxyEntity primary,
      Map<CombatProxyEntity, Float> losses,
      Vec3 origin,
      List<SoundEvent> sounds,
      List<ClientboundLevelParticlesPacket> particles,
      List<Animation> animations) {}

  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

  private ProxyCombatAuthority() {}

  public static boolean begin(ServerPlayer player, CombatProxyEntity target) {
    Adapter a = adapter;
    if (a == null || CURRENT.get() != null) return false;
    Object token = a.begin(player, target);
    if (token == null) return false;
    CURRENT.set(
        new Context(
            token,
            player,
            target,
            new LinkedHashMap<>(),
            player.position(),
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>()));
    return true;
  }

  public static boolean permitDamage(CombatProxyEntity proxy, DamageSource source) {
    Context c = CURRENT.get();
    Adapter a = adapter;
    return c != null
        && a != null
        && source.getEntity() == c.player
        && a.permit(c.token, c.player, proxy);
  }

  public static void record(CombatProxyEntity proxy, float loss) {
    Context c = CURRENT.get();
    if (c != null && c.losses.size() < 16) c.losses.merge(proxy, loss, Float::sum);
  }

  public static boolean proxyAttack() {
    return CURRENT.get() != null;
  }

  public static boolean sound(SoundEvent sound) {
    Context c = CURRENT.get();
    if (c == null) return false;
    if (c.sounds.size() < 8) c.sounds.add(sound);
    return true;
  }

  public static boolean particles(
      ParticleOptions particle,
      double x,
      double y,
      double z,
      int count,
      double dx,
      double dy,
      double dz,
      double speed) {
    Context c = CURRENT.get();
    if (c == null) return false;
    if (c.particles.size() < 32)
      c.particles.add(
          new ClientboundLevelParticlesPacket(
              particle,
              false,
              false,
              x,
              y,
              z,
              (float) dx,
              (float) dy,
              (float) dz,
              (float) speed,
              Math.clamp(count, 0, 64)));
    return true;
  }

  public static boolean animation(Entity target, ParticleOptions particle) {
    Context c = CURRENT.get();
    if (c == null) return false;
    if (c.animations.size() < 16) c.animations.add(new Animation(target.getUUID(), particle));
    return true;
  }

  public static void finish(String item, int before, int after, boolean completed) {
    Context c = CURRENT.get();
    CURRENT.remove();
    Adapter a = adapter;
    if (c != null && completed && a != null)
      a.finished(
          c.token,
          c.player,
          c.primary,
          Collections.unmodifiableMap(new LinkedHashMap<>(c.losses)),
          item,
          before,
          after,
          new Feedback(
              c.origin,
              List.copyOf(c.sounds),
              List.copyOf(c.particles),
              List.copyOf(c.animations)));
  }
}
