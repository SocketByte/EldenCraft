package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.client.mixin.BlockWorkAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Mining and placing blocks never cost stamina. Holding attack on a block makes the client send a
 * Punch with every swing, which the stamina rules would otherwise charge as a missed attack.
 * Server thread only.
 */
public final class BlockWork {
  /** Covers swings that arrive just after an instant break, the last hit or a placement. */
  static final long GRACE_TICKS = 10;

  private static final Map<UUID, Long> LAST = new HashMap<>();

  private BlockWork() {}

  public static void touched(ServerPlayer player) {
    LAST.put(player.getUUID(), player.level().getGameTime());
  }

  public static boolean active(ServerPlayer player) {
    if (((BlockWorkAccessor) player.gameMode).eldencraft$destroyingBlock()
        || TerrainMining.mining(player)) return true;
    Long last = LAST.get(player.getUUID());
    return last != null && recent(last, player.level().getGameTime());
  }

  static boolean recent(long last, long now) {
    return now >= last && now - last <= GRACE_TICKS;
  }

  public static void clear() {
    LAST.clear();
  }
}
