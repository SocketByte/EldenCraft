package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.SharedWorldBlocks;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Once host-owned, this exact stand-in must not incur a second engine's fall damage in the
 * dedicated dimension, including immediately after lease loss. Other damage, other players and
 * other worlds remain ordinary Minecraft.
 */
public final class WorldFallSafety {
  private static final Set<ServerPlayer> OWNED = Collections.newSetFromMap(new WeakHashMap<>());

  private WorldFallSafety() {}

  public static void claim(ServerPlayer p) {
    if (SharedWorldClient.controlsPlayer(p)) {
      synchronized (OWNED) {
        OWNED.add(p);
      }
      p.resetFallDistance();
    }
  }

  public static boolean owns(ServerPlayer p) {
    if (p == null
        || p.level().getServer().isPublished()
        || !p.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return false;
    synchronized (OWNED) {
      return OWNED.contains(p);
    }
  }
}
