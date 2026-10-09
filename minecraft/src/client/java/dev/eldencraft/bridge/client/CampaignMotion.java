package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.*;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Observed movement drives vanilla weapon maths; only real smash impulses are sent back. */
public final class CampaignMotion {
  private record Context(UUID player, long epoch, long map) {}

  private static CombatMotion MOTION = new CombatMotion();
  private static Context context;
  private static ServerPlayer controlled;
  private static long deadline, sequence, impulse, protectUntil;
  private static float verticalImpulse;

  private CampaignMotion() {}

  public static void observe(ServerPlayer player, WorldProtocol.Host host) {
    Context next = new Context(player.getUUID(), host.epoch(), host.map());
    boolean changed = controlled != player || !next.equals(context);
    if (changed) {
      clear();
      controlled = player;
      context = next;
    }
    MOTION.observe(
        next,
        host.frame(),
        host.millis(),
        host.feet(),
        host.grounded(),
        !player.isFallFlying()
            && !CampaignPotions.slowFalling(player)
            && !player.isInWater()
            && !player.isInLava()
            && !WorldTorrent.rides(player));
    deadline = System.nanoTime() + 250_000_000L;
    if (host.grounded()) protectUntil = Math.min(protectUntil, host.millis() + 750);
  }

  public static boolean owns(ServerPlayer player) {
    return player == controlled
        && System.nanoTime() < deadline
        && SharedWorldClient.controlsPlayer(player);
  }

  public static Vec3 velocity(ServerPlayer player) {
    var v = MOTION.velocity();
    return owns(player) ? new Vec3(v.x(), v.y(), v.z()) : Vec3.ZERO;
  }

  public static void maceAttack(ServerPlayer player, Runnable vanilla) {
    if (!owns(player) || !player.getMainHandItem().is(Items.MACE)) {
      vanilla.run();
      return;
    }
    double prior = player.fallDistance;
    Vec3 motion = player.getDeltaMovement();
    double fall = MOTION.fallDistance();
    player.fallDistance = fall;
    player.setDeltaMovement(velocity(player));
    try {
      vanilla.run();
      if (fall > 1.5 && player.fallDistance == 0) {
        MOTION.consumeFall();
        verticalImpulse = (float) Math.clamp(player.getDeltaMovement().y * 20, 0, 40);
        impulse++;
        var c = SharedWorldClient.projectileContext(player);
        if (c != null)
          protectUntil = c.clock() + (System.nanoTime() - c.readNanos()) / 1_000_000 + 8000;
      }
    } finally {
      player.fallDistance = prior;
      player.setDeltaMovement(motion);
    }
  }

  public static JsonObject snapshot(ServerPlayer player, WorldProtocol.Host host, long clock) {
    if (!owns(player)) return null;
    var json = new JsonObject();
    json.addProperty("sequence", ++sequence);
    json.addProperty("time_ms", clock);
    json.addProperty("observed_frame", host.frame());
    json.addProperty("speed", CampaignPotions.speed(player));
    json.addProperty("jump_bonus", CampaignPotions.jumpBonus(player));
    json.addProperty("slow_falling", CampaignPotions.slowFalling(player));
    json.addProperty("impulse_sequence", impulse);
    json.addProperty("vertical_impulse", verticalImpulse);
    json.addProperty("protect_fall", clock < protectUntil || CampaignPotions.slowFalling(player));
    var use =
        player
            .getUseItem()
            .getOrDefault(
                net.minecraft.core.component.DataComponents.USE_EFFECTS,
                net.minecraft.world.item.component.UseEffects.DEFAULT);
    json.addProperty("use_speed", player.isUsingItem() ? use.speedMultiplier() : 1);
    json.addProperty("use_sprint", player.isUsingItem() && use.canSprint());
    return json;
  }

  public static void clear() {
    MOTION = new CombatMotion();
    context = null;
    controlled = null;
    deadline = 0;
    verticalImpulse = 0;
    protectUntil = 0;
    // Both counters stay monotonic across server-player replacement. Native context
    // changes establish a baseline and cannot replay the prior player's impulse.
  }
}
