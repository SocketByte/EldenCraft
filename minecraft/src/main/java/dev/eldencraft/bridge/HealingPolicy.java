package dev.eldencraft.bridge;

import java.util.*;

/** Portable provenance/cadence and bounded ACK/retry policy. No game objects or health setters. */
public final class HealingPolicy {
  private HealingPolicy() {}

  public static final class Grant {
    private final long consumedTick;
    private int lastRemaining = 125, pulses;
    private long lastTick = -1;

    public Grant(long consumedTick) {
      if (consumedTick < 0) throw new IllegalArgumentException();
      this.consumedTick = consumedTick;
    }

    public boolean pulse(long tick, int remaining, int amplifier) {
      if (amplifier != 1
          || remaining < 25
          || remaining > 100
          || remaining % 25 != 0
          || remaining >= lastRemaining
          || pulses >= 4
          || tick <= lastTick
          || tick < consumedTick
          || Math.abs((tick - consumedTick) - (100 - remaining)) > 2) return false;
      lastRemaining = remaining;
      lastTick = tick;
      pulses++;
      return true;
    }
  }

  public static final class Outbox {
    private final ArrayDeque<HealingProtocol.Receipt> pending = new ArrayDeque<>();
    private long next = 1, lastAck;

    public synchronized boolean offer(
        long consumption, long millis, long tick, int remaining, float maxHp) {
      expire(millis);
      if (pending.size() >= 8 || next == Long.MAX_VALUE) return false;
      var receipt = new HealingProtocol.Receipt(next, consumption, millis, tick, remaining, maxHp);
      HealingProtocol.validate(receipt);
      pending.add(receipt);
      next++;
      return true;
    }

    public synchronized void acknowledge(long ownSession, HealingProtocol.Host host) {
      if (host.ackSession() != ownSession
          || host.ackSequence() <= lastAck
          || host.ackSequence() >= next
          || host.ackResult() == 0) return;
      lastAck = host.ackSequence();
      while (!pending.isEmpty() && pending.peek().sequence() <= lastAck) pending.remove();
    }

    private void expire(long now) {
      while (!pending.isEmpty()
          && (now < pending.peek().millis()
              || now - pending.peek().millis() > HealingProtocol.RECEIPT_FRESH_MS))
        pending.remove();
    }

    public synchronized List<HealingProtocol.Receipt> batch(long now) {
      expire(now);
      return List.copyOf(pending);
    }

    public synchronized void reset() {
      pending.clear();
      next = 1;
      lastAck = 0;
    }
  }
}
