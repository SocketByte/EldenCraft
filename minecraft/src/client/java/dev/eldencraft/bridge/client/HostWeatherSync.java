package dev.eldencraft.bridge.client;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/**
 * Mirrors Elden Ring's weather into the integrated server's weather, as clear, rain or thunder.
 * Minecraft's own precipitation is not drawn over the composed frame; rain still affects lighting,
 * mobs, fire and farmland. Without fresh host weather the natural cycle continues unchanged.
 */
public final class HostWeatherSync {
  /** Re-asserted before Minecraft's own cycle could end the mirrored weather. */
  private static final int DURATION_TICKS = 12_000, RENEW_BELOW_TICKS = 6_000;

  private static boolean enabled = true;
  private static Object world;
  private static long publisher, nextNanos;
  private static volatile long epoch;
  private static int lastWeather = -1;
  private static final AtomicBoolean PENDING = new AtomicBoolean();

  private HostWeatherSync() {}

  public static void configure(boolean value) {
    enabled = value;
  }

  public static void tick(Minecraft client, HostState.Snapshot host) {
    var server = client.getSingleplayerServer();
    boolean active =
        enabled
            && host != null
            && host.active()
            && host.weatherValid()
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
    }
    long now = System.nanoTime();
    if (now < nextNanos || !PENDING.compareAndSet(false, true)) return;
    nextNanos = now + 1_000_000_000L;
    long capturedEpoch = epoch;
    int weather = host.weather();
    server.execute(
        () -> {
          try {
            if (epoch != capturedEpoch
                || System.nanoTime() - now >= 2_000_000_000L
                || server.isPublished()
                || !server.isRunning()) return;
            boolean raining = weather != HostState.CLEAR, thundering = weather == HostState.THUNDER;
            var data = server.getWeatherData();
            int remaining = raining ? data.getRainTime() : data.getClearWeatherTime();
            if (data.isRaining() == raining
                && data.isThundering() == thundering
                && remaining >= RENEW_BELOW_TICKS) return;
            server.setWeatherParameters(
                raining ? 0 : DURATION_TICKS, raining ? DURATION_TICKS : 0, raining, thundering);
            if (weather != lastWeather) {
              lastWeather = weather;
              org.slf4j.LoggerFactory.getLogger("eldencraft_weather")
                  .info(
                      "Host weather synchronized: {}",
                      thundering ? "thunder" : raining ? "rain" : "clear");
            }
          } finally {
            PENDING.set(false);
          }
        });
  }
}
