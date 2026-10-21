package dev.eldencraft.bridge.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A frozen game leaves no crash report: Windows only says the window stopped responding. When the
 * client or the integrated server stops ticking for {@link #STALL_NANOS}, this writes every
 * thread's complete stack and lock state to {@code logs/eldencraft-stall-*.txt} and the log, once
 * per stall, so the next freeze shows where it is stuck.
 */
final class StallWatchdog {
  private static final Logger LOG = LoggerFactory.getLogger("eldencraft_stall");
  private static final long STALL_NANOS = 5_000_000_000L;
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private static volatile long clientBeat, serverBeat;

  /**
   * The server is not expected to tick: none is running, it is still preparing or saving the world,
   * or the pause menu stopped it.
   */
  private static volatile boolean serverIdle = true;

  /** The running server has ticked at least once, so world preparation is over. */
  private static volatile boolean serverTicked;

  private StallWatchdog() {}

  static void initialize() {
    clientBeat = serverBeat = System.nanoTime();
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          long now = System.nanoTime();
          clientBeat = now;
          var server = client.getSingleplayerServer();
          if (server == null) serverTicked = false;
          boolean idle =
              server == null
                  || !server.isRunning()
                  || !serverTicked
                  || client.level == null
                  || client.isPaused();
          serverIdle = idle;
          if (idle) serverBeat = now;
        });
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          serverBeat = System.nanoTime();
          serverTicked = true;
        });
    var thread = new Thread(StallWatchdog::run, "EldenCraft stall watchdog");
    thread.setDaemon(true);
    thread.start();
  }

  private static void run() {
    boolean clientReported = false, serverReported = false;
    while (true) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException stop) {
        return;
      }
      long now = System.nanoTime();
      boolean clientStalled = now - clientBeat > STALL_NANOS;
      boolean serverStalled = !serverIdle && now - serverBeat > STALL_NANOS;
      if (clientStalled && !clientReported) report("Render thread", now - clientBeat);
      else if (serverStalled && !serverReported && !clientStalled)
        report("Server thread", now - serverBeat);
      clientReported = clientStalled;
      serverReported = serverStalled;
    }
  }

  private static void report(String stalled, long nanos) {
    try {
      var text = new StringBuilder();
      var runtime = Runtime.getRuntime();
      text.append(stalled)
          .append(" has not ticked for ")
          .append(nanos / 1_000_000)
          .append(" ms. Heap used ")
          .append((runtime.totalMemory() - runtime.freeMemory()) >> 20)
          .append(" MB of ")
          .append(runtime.maxMemory() >> 20)
          .append(" MB.\n");
      for (var gc : ManagementFactory.getGarbageCollectorMXBeans())
        text.append("GC ")
            .append(gc.getName())
            .append(": ")
            .append(gc.getCollectionCount())
            .append(" collections, ")
            .append(gc.getCollectionTime())
            .append(" ms\n");
      var threads = ManagementFactory.getThreadMXBean();
      long[] deadlocked = threads.findDeadlockedThreads();
      if (deadlocked != null)
        text.append("Deadlocked threads: ").append(deadlocked.length).append('\n');
      for (var info : threads.dumpAllThreads(true, true)) append(text, info);
      var directory = FabricLoader.getInstance().getGameDir().resolve("logs");
      var file =
          directory.resolve("eldencraft-stall-" + LocalDateTime.now().format(STAMP) + ".txt");
      Files.createDirectories(directory);
      Files.writeString(file, text, StandardCharsets.UTF_8);
      LOG.warn("{} stalled; thread dump written to {}\n{}", stalled, file, text);
    } catch (Throwable error) {
      LOG.warn("Stall report failed", error);
    }
  }

  /** {@link ThreadInfo#toString()} stops at eight frames; a stall needs the whole stack. */
  private static void append(StringBuilder text, ThreadInfo info) {
    text.append('\n')
        .append('"')
        .append(info.getThreadName())
        .append("\" ")
        .append(info.getThreadState());
    if (info.getLockName() != null) text.append(" on ").append(info.getLockName());
    if (info.getLockOwnerName() != null)
      text.append(" owned by \"").append(info.getLockOwnerName()).append('"');
    text.append('\n');
    var stack = info.getStackTrace();
    var monitors = info.getLockedMonitors();
    for (int i = 0; i < stack.length; i++) {
      text.append("\tat ").append(stack[i]).append('\n');
      for (var monitor : monitors)
        if (monitor.getLockedStackDepth() == i)
          text.append("\t- locked ").append(monitor).append('\n');
    }
    for (var lock : info.getLockedSynchronizers())
      text.append("\t- holds ").append(lock).append('\n');
  }
}
