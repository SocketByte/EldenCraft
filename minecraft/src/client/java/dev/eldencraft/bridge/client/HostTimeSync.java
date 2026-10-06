package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.HostClock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;

/**
 * Changes only the integrated server's overworld clock phase; never its tick rate or pause state.
 */
public final class HostTimeSync {
  private static boolean enabled = true;
  private static Object world;
  private static long publisher, nextNanos;
  private static volatile long epoch;
  private static final AtomicBoolean PENDING = new AtomicBoolean();
  private static final AtomicLong NEXT_LOG = new AtomicLong();

  private HostTimeSync() {}

  public static void configure(boolean value) {
    enabled = value;
  }

  public static void tick(Minecraft client, HostState.Snapshot host) {
    var server = client.getSingleplayerServer();
    boolean active =
        enabled
            && host != null
            && host.timeValid()
            && client.level != null
            && client.player != null
            && client.hasSingleplayerServer()
            && server != null
            && !server.isPublished();
    if (!active) {
      if (world != null) epoch++;
      world = null;
      nextNanos = 0;
      return;
    }
    if (world != client.level || publisher != host.publisherPid()) {
      world = client.level;
      publisher = host.publisherPid();
      epoch++;
      nextNanos = 0;
      NEXT_LOG.set(0);
    }
    long now = System.nanoTime();
    if (now < nextNanos || !PENDING.compareAndSet(false, true)) return;
    nextNanos = now + 1_000_000_000L;
    long capturedEpoch = epoch;
    float seconds = host.timeSeconds();
    server.execute(
        () -> {
          try {
            if (epoch != capturedEpoch
                || System.nanoTime() - now >= 2_000_000_000L
                || server.isPublished()
                || !server.isRunning()) return;
            var clock =
                server
                    .registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(WorldClocks.OVERWORLD);
            var manager = server.clockManager();
            var target =
                HostClock.synchronizeTicks(manager.getInstance(clock).totalTicks(), seconds);
            if (target.isPresent()) {
              manager.setTotalTicks(clock, target.getAsLong());
              long deadline = NEXT_LOG.get();
              if (now >= deadline && NEXT_LOG.compareAndSet(deadline, now + 10_000_000_000L)) {
                org.slf4j.LoggerFactory.getLogger("eldencraft_time")
                    .info(
                        "Host clock synchronized: seconds={}, targetPhase={}, actualPhase={}",
                        seconds,
                        Math.floorMod(target.getAsLong(), 24000),
                        Math.floorMod(manager.getInstance(clock).totalTicks(), 24000));
              }
            }
          } finally {
            PENDING.set(false);
          }
        });
  }
}
