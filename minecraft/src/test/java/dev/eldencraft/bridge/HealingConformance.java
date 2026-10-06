package dev.eldencraft.bridge;

import java.nio.*;
import java.util.*;

/**
 * Protocol/cadence/lifecycle checks; does not pretend to prove live item consumption or native
 * healing.
 */
public final class HealingConformance {
  private static int checks;

  private static void check(boolean value, String name) {
    checks++;
    if (!value) throw new AssertionError(name);
  }

  private static void rejects(Runnable code, String name) {
    checks++;
    try {
      code.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError(name);
  }

  private static byte[] host() {
    var b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, HealingProtocol.HOST_MAGIC)
        .putInt(4, 1)
        .putLong(8, 2)
        .putLong(16, 20)
        .putLong(24, 1000)
        .putInt(32, 123)
        .putInt(36, 1)
        .putLong(40, 99)
        .putInt(48, 123456)
        .putFloat(52, 500)
        .putFloat(56, 1000);
    return b.array();
  }

  private static HealingProtocol.Host ack(long session, long sequence, int result) {
    var b = ByteBuffer.wrap(host()).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(60, result).putLong(64, session).putLong(72, sequence);
    return HealingProtocol.decodeHost(b.array(), 1000);
  }

  public static void main(String[] args) {
    var h = HealingProtocol.decodeHost(host(), 1000);
    check(h.pid() == 123 && h.epoch() == 99 && h.active() && h.hp() == 500, "host fields");
    check(HealingProtocol.decodeHost(host(), 1250).frame() == 20, "boundary freshness");
    rejects(() -> HealingProtocol.decodeHost(host(), 1251), "stale host");
    rejects(() -> HealingProtocol.decodeHost(host(), 999), "future host");
    rejects(() -> HealingProtocol.decodeHost(new byte[128], 1000), "short host");
    for (int at : new int[] {0, 4, 8, 36, 80, 4095}) {
      byte[] bad = host();
      bad[at] ^= 2;
      rejects(() -> HealingProtocol.decodeHost(bad, 1000), "malformed header " + at);
    }
    for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 1001}) {
      byte[] bad = host();
      ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(52, value);
      rejects(() -> HealingProtocol.decodeHost(bad, 1000), "invalid HP");
    }
    check(ack(7, 0, 0).ackSession() == 7, "observed session before first receipt");
    rejects(() -> ack(7, 0, 1), "partial ack identity");
    rejects(() -> ack(7, 1, 0), "ack without result");
    rejects(() -> ack(7, 1, 3), "unknown ack result");
    var grant = new HealingPolicy.Grant(100);
    check(!grant.pulse(100, 100, 0), "wrong amplifier");
    check(!grant.pulse(100, 99, 1), "not a real effect opportunity");
    for (int i = 0; i < 4; i++) {
      int remaining = 100 - 25 * i;
      long tick = 101 + 25 * i;
      check(grant.pulse(tick, remaining, 1), "real cadence " + remaining);
      check(!grant.pulse(tick, remaining, 1), "duplicate cadence " + remaining);
    }
    check(!grant.pulse(201, 0, 1), "no fifth pulse");
    check(!grant.pulse(202, 100, 1), "effect refresh needs new consumption");
    check(
        !new HealingPolicy.Grant(100).pulse(170, 100, 1),
        "old effect cannot restart with an unrelated tick");
    check(!new HealingPolicy.Grant(100).pulse(99, 100, 1), "tick rollback");
    var out = new HealingPolicy.Outbox();
    check(out.offer(1, 1000, 100, 100, 20), "queue first genuine pulse");
    check(out.batch(1000).equals(out.batch(1001)), "retry preserves exact receipt identity");
    out.acknowledge(7, ack(8, 1, 1));
    check(out.batch(1001).size() == 1, "other session ack ignored");
    out.acknowledge(7, ack(7, 2, 1));
    check(out.batch(1001).size() == 1, "future ack ignored");
    out.acknowledge(7, ack(7, 1, 2));
    check(out.batch(1001).isEmpty(), "rejected receipt consumed, never replayed");
    check(out.offer(1, 1010, 125, 75, 20), "next receipt");
    check(out.batch(2010).size() == 1, "expiry boundary");
    check(out.batch(2011).isEmpty(), "expired receipt dropped");
    for (int i = 0; i < 8; i++)
      check(out.offer(2 + i, 2020, 200 + i, 100, 20), "bounded queue accepts " + i);
    check(!out.offer(20, 2020, 220, 100, 20), "bounded queue rejects overflow");
    out.reset();
    check(out.batch(2020).isEmpty(), "disconnect removes all pending receipts");
    check(out.offer(30, 3000, 300, 100, 20), "new session starts");
    check(out.batch(3000).getFirst().sequence() == 1, "new session counter resets");
    var receipts = out.batch(3000);
    byte[] packet = HealingProtocol.encode(2, 1, 3000, 456, true, 7, h, receipts);
    var b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    check(
        b.getInt(0) == HealingProtocol.RECEIPT_MAGIC && b.getInt(72) == 1 && b.getLong(56) == 99,
        "receipt header ABI");
    check(
        b.getLong(128) == 1
            && b.getLong(136) == 30
            && b.getLong(144) == 3000
            && b.getLong(152) == 300,
        "receipt identity ABI");
    check(
        b.getInt(160) == 1
            && b.getInt(164) == 1
            && b.getFloat(168) == 1
            && b.getFloat(172) == 20
            && b.getInt(176) == 100,
        "receipt effect ABI");
    for (int i = 180; i < 4096; i++) if (packet[i] != 0) throw new AssertionError("reserved bytes");
    checks++;
    rejects(
        () -> HealingProtocol.encode(2, 1, 4001, 456, true, 7, h, receipts),
        "expired packet forbidden");
    rejects(
        () -> HealingProtocol.encode(2, 1, 2999, 456, true, 7, h, receipts),
        "future receipt forbidden");
    rejects(
        () -> HealingProtocol.encode(2, 1, 3000, 456, false, 7, h, receipts),
        "inactive packet cannot heal");
    rejects(
        () ->
            HealingProtocol.encode(
                2, 1, 3000, 456, true, 7, h, List.of(receipts.getFirst(), receipts.getFirst())),
        "duplicate receipt in packet");
    rejects(
        () -> HealingProtocol.validate(new HealingProtocol.Receipt(1, 1, 1, 1, 75, Float.NaN)),
        "nonfinite denominator");
    System.out.println(
        "Golden apple healing conformance: "
            + checks
            + " checks passed (portable model only; live server/native proof separate).");
  }
}
