package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.HostDamageState;
import dev.eldencraft.bridge.client.mixin.LivingEntitySoundInvoker;
import net.minecraft.client.Minecraft;

/**
 * Vanilla visual/audio hurt feedback only. Never calls hurtTo, handleDamageEvent, heal or
 * setHealth.
 */
public final class HostDamageFeedback {
  private static final HostDamageState STATE = new HostDamageState();
  private static long nextLogNanos;

  private HostDamageFeedback() {}

  public static void tick(Minecraft client) {
    var host = HostController.damageSnapshot(client);
    var player = client.player;
    if (host == null
        || player == null
        || !player.isAlive()
        || player.isDeadOrDying()
        || player.deathTime > 0) {
      STATE.reset();
      return;
    }
    int damage =
        STATE.observe(
            player,
            client.level,
            host.publisherPid(),
            host.mapId(),
            host.hostFrame(),
            host.timestampMillis(),
            host.hp(),
            host.maxHp());
    if (damage == 0) return;
    // ECHS has no damage direction. Zero is an explicit neutral visual direction, not inferred hit
    // metadata.
    player.animateHurt(0);
    ((LivingEntitySoundInvoker) player).eldencraft$playHurtSound(player.damageSources().generic());
    long now = System.nanoTime();
    if (now >= nextLogNanos) {
      nextLogNanos = now + 1_000_000_000L;
      org.slf4j.LoggerFactory.getLogger("eldencraft_damage")
          .info(
              "Vanilla host hurt feedback: hostHpLoss={}, genuineGuestHealth={}",
              damage,
              player.getHealth());
    }
  }

  public static void close() {
    STATE.reset();
  }
}
