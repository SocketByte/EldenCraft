package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.security.SecureRandom;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

/** Fresh host lease plus genuine integrated-server consumption/effect provenance. */
public final class HealingClient {
  private static final Object LOCK = new Object();
  private static final HealingMailbox MAILBOX = new HealingMailbox();
  private static final HealingPolicy.Outbox OUTBOX = new HealingPolicy.Outbox();
  private static final SecureRandom RANDOM = new SecureRandom();

  private record Lease(
      IntegratedServer server,
      UUID player,
      Object clientPlayer,
      Object world,
      long session,
      HealingProtocol.Host host,
      long deadline) {}

  private static Lease lease;
  private static HealingProtocol.Host lastHost;
  private static long lastSession, lastAck;

  private HealingClient() {}

  public static void initialize() {
    GoldenAppleAuthority.adapter =
        new GoldenAppleAuthority.Adapter() {
          public Object begin(ServerPlayer player) {
            synchronized (LOCK) {
              return valid(lease, player) ? lease : null;
            }
          }

          public boolean valid(Object token, ServerPlayer player) {
            synchronized (LOCK) {
              return token instanceof Lease captured && current(captured, player);
            }
          }

          public void pulse(
              Object token,
              ServerPlayer player,
              long consumption,
              long tick,
              int remaining,
              float maximum) {
            synchronized (LOCK) {
              if (!(token instanceof Lease captured) || !current(captured, player)) return;
              long now = MAILBOX.now();
              if (now <= 0 || !Float.isFinite(maximum) || maximum < 1 || maximum > 1024) return;
              if (OUTBOX.offer(consumption, now, tick, remaining, maximum))
                org.slf4j.LoggerFactory.getLogger("eldencraft_healing")
                    .info(
                        "Server golden apple regeneration: consumption={}, tick={}, remaining={},"
                            + " amount=1/{}",
                        consumption,
                        tick,
                        remaining,
                        maximum);
            }
          }
        };
  }

  private static boolean valid(Lease value, ServerPlayer player) {
    return value != null
        && System.nanoTime() < value.deadline
        && player.level().getServer() == value.server
        && !value.server.isPublished()
        && player.getUUID().equals(value.player)
        && player.isAlive()
        && !player.isSpectator();
  }

  private static boolean current(Lease captured, ServerPlayer player) {
    return valid(lease, player)
        && captured.session == lease.session
        && captured.server == lease.server
        && captured.player.equals(lease.player)
        && captured.host.pid() == lease.host.pid()
        && captured.host.epoch() == lease.host.epoch()
        && captured.host.map() == lease.host.map();
  }

  public static void tick(Minecraft client) {
    var host = MAILBOX.read();
    var server = client.getSingleplayerServer();
    var control = HostController.combatSnapshot(client);
    boolean active =
        host != null
            && host.active()
            && host.hp() > 0
            && server != null
            && !server.isPublished()
            && client.hasSingleplayerServer()
            && client.level != null
            && client.player != null
            && client.player.isAlive()
            && !client.player.isSpectator()
            && control != null
            && control.publisherPid() == host.pid()
            && control.mapId() == host.map();
    long session;
    List<HealingProtocol.Receipt> receipts;
    synchronized (LOCK) {
      if (!active) {
        lease = null;
        OUTBOX.reset();
        GoldenAppleAuthority.clear();
        MAILBOX.publish(lastHost, false, lastSession, List.of());
        return;
      }
      boolean changed =
          lease == null
              || lease.server != server
              || lease.clientPlayer != client.player
              || lease.world != client.level
              || lease.host.pid() != host.pid()
              || lease.host.epoch() != host.epoch()
              || lease.host.map() != host.map();
      if (changed) {
        OUTBOX.reset();
        GoldenAppleAuthority.clear();
        do {
          lastSession = RANDOM.nextLong() & Long.MAX_VALUE;
        } while (lastSession == 0);
        lastAck = 0;
      }
      session = lastSession;
      lease =
          new Lease(
              server,
              client.player.getUUID(),
              client.player,
              client.level,
              session,
              host,
              System.nanoTime() + 250_000_000L);
      lastHost = host;
      OUTBOX.acknowledge(session, host);
      receipts = OUTBOX.batch(MAILBOX.now());
      if (host.ackSession() == session && host.ackSequence() > lastAck) {
        lastAck = host.ackSequence();
        org.slf4j.LoggerFactory.getLogger("eldencraft_healing")
            .info(
                "Host healing acknowledgement: seq={}, result={}, hp={}/{}",
                lastAck,
                host.ackResult(),
                host.hp(),
                host.maxHp());
      }
    }
    MAILBOX.publish(host, true, session, receipts);
  }

  public static void close() {
    synchronized (LOCK) {
      lease = null;
      OUTBOX.reset();
      GoldenAppleAuthority.adapter = null;
      GoldenAppleAuthority.clear();
      MAILBOX.publish(lastHost, false, lastSession, List.of());
      MAILBOX.close();
    }
  }
}
