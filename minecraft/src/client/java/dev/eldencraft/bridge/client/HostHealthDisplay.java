package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.CampaignHeartScale;
import dev.eldencraft.bridge.HealthDisplayState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Only vanilla HUD extraction sees mirrored health; every scope restores before game code resumes.
 */
public final class HostHealthDisplay {
  private static final HealthDisplayState STATE = new HealthDisplayState();
  private static boolean enabled;

  private HostHealthDisplay() {}

  public static void configure(boolean value) {
    enabled = value;
  }

  public static void receivedHealth(float health) {
    Minecraft client = Minecraft.getInstance();
    if (!offline(client)) {
      STATE.reset();
      return;
    }
    STATE.bind(client.player, client.level);
    STATE.packet(client.player, client.level, health);
  }

  public static void extract(Runnable vanilla) {
    Minecraft client = Minecraft.getInstance();
    if (!enabled || !offline(client)) {
      vanilla.run();
      return;
    }
    var player = client.player;
    var world = client.level;
    STATE.bind(player, world);
    var host = HostController.healthSnapshot(client);
    Float display =
        STATE.begin(
            player,
            world,
            player.getHealth(),
            player.getMaxHealth(),
            player.isAlive() && !player.isDeadOrDying() && player.deathTime == 0,
            host != null,
            host == null ? 0 : host.hp(),
            host == null ? 0 : host.maxHp());
    if (display == null) {
      vanilla.run();
      return;
    }
    try {
      player.setHealth(display);
      vanilla.run();
    } finally {
      // Player/world replacement or a newer actual health update always wins over this display
      // value.
      if (client.player == player && client.level == world) {
        Float restore = STATE.end(player, world, player.getHealth());
        if (restore != null) player.setHealth(restore);
      } else STATE.reset();
    }
  }

  /** Only HUD reads use compact units; no player attribute or absorption pool is written. */
  public static float maximum(Player player, double actual) {
    if (!compact(player)) return (float) actual;
    var progression = CampaignConfig.current().progression;
    return (float)
        CampaignHeartScale.maximum(
            actual, progression.health(0), progression.health(progression.shares()));
  }

  public static float units(Player player, float actual) {
    if (!compact(player)) return actual;
    double capacity = player.getMaxHealth();
    return CampaignHeartScale.units(actual, capacity, maximum(player, capacity));
  }

  public static boolean compact(Player player) {
    var client = Minecraft.getInstance();
    return enabled && offline(client) && player == client.player && CampaignCombat.active(player);
  }

  private static boolean offline(Minecraft client) {
    var server = client.getSingleplayerServer();
    return client.player != null
        && client.level != null
        && client.hasSingleplayerServer()
        && server != null
        && !server.isPublished();
  }

  public static void close() {
    enabled = false;
    STATE.reset();
  }
}
