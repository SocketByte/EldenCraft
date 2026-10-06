package dev.eldencraft.bridge;

import java.util.concurrent.atomic.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;

/**
 * Only integrated-server consumption and the real regeneration effect tick can produce a receipt.
 */
public final class GoldenAppleAuthority {
  public interface Adapter {
    Object begin(ServerPlayer player);

    boolean valid(Object token, ServerPlayer player);

    void pulse(
        Object token,
        ServerPlayer player,
        long consumption,
        long tick,
        int remaining,
        float maxHealth);
  }

  public static volatile Adapter adapter;

  private record Consumption(
      Object lease, ServerPlayer player, ItemStack stack, int count, long generation) {}

  private record Grant(
      Object lease,
      ServerPlayer player,
      MobEffectInstance effect,
      long id,
      HealingPolicy.Grant cadence) {}

  // Server creates/uses grants; the client can revoke and release all world
  // references without scheduling a task on a server that is shutting down.
  private static final AtomicReference<Grant> GRANT = new AtomicReference<>();
  private static final AtomicLong GENERATION = new AtomicLong();
  private static long nextConsumption = 1;

  private GoldenAppleAuthority() {}

  public static Object begin(ServerPlayer player, ItemStack stack, Consumable consumable) {
    var a = adapter;
    if (a == null
        || !player.isAlive()
        || player.isSpectator()
        || player.getAbilities().instabuild
        || !stack.is(Items.GOLDEN_APPLE)
        || !Consumables.GOLDEN_APPLE.equals(consumable)
        || !consumable.equals(stack.get(DataComponents.CONSUMABLE))) return null;
    long generation = GENERATION.get();
    Object lease = a.begin(player);
    return lease == null || generation != GENERATION.get()
        ? null
        : new Consumption(lease, player, stack, stack.getCount(), generation);
  }

  public static void consumed(Object token) {
    if (!(token instanceof Consumption c)) return;
    var a = adapter;
    if (a == null
        || !a.valid(c.lease, c.player)
        || c.generation != GENERATION.get()
        || c.count < 1
        || c.stack.getCount() != c.count - 1) return;
    var effect = c.player.getEffect(MobEffects.REGENERATION);
    if (effect == null
        || effect.getAmplifier() != 1
        || effect.getDuration() != 100
        || nextConsumption == Long.MAX_VALUE) {
      GRANT.set(null);
      return;
    }
    var grant =
        new Grant(
            c.lease,
            c.player,
            effect,
            nextConsumption++,
            new HealingPolicy.Grant(Integer.toUnsignedLong(c.player.tickCount)));
    GRANT.set(grant);
    // Covers revocation between validation and publication without clearing
    // a newer server grant or retaining the old world after disconnect.
    if (c.generation != GENERATION.get()) {
      GRANT.compareAndSet(grant, null);
      return;
    }
    org.slf4j.LoggerFactory.getLogger("eldencraft_healing")
        .info(
            "Server consumed golden apple: consumption={}, remaining={}, countAfter={}",
            grant.id,
            effect.getDuration(),
            c.stack.getCount());
  }

  public static void regeneration(ServerPlayer player, MobEffectInstance effect) {
    var g = GRANT.get();
    var a = adapter;
    if (g == null || player != g.player) return;
    if (a == null
        || !a.valid(g.lease, player)
        || effect != g.effect
        || player.getEffect(MobEffects.REGENERATION) != effect
        || !player.isAlive()) {
      GRANT.compareAndSet(g, null);
      return;
    }
    long tick = Integer.toUnsignedLong(player.tickCount);
    int remaining = effect.getDuration();
    if (g.cadence.pulse(tick, remaining, effect.getAmplifier()))
      a.pulse(g.lease, player, g.id, tick, remaining, player.getMaxHealth());
    if (remaining <= 25) GRANT.compareAndSet(g, null);
  }

  public static void clear() {
    GENERATION.incrementAndGet();
    GRANT.set(null);
  }
}
