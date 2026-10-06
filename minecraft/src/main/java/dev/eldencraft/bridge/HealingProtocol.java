package dev.eldencraft.bridge;

import java.nio.*;
import java.util.List;

/** ECHL/ECHR v1. Local receipts report real server effect opportunities, never client HP writes. */
public final class HealingProtocol {
  public static final int BYTES = 4096, HOST_MAGIC = 0x4c484345, RECEIPT_MAGIC = 0x52484345;
  public static final long MAX = Long.MAX_VALUE, HOST_FRESH_MS = 250, RECEIPT_FRESH_MS = 1000;

  public record Host(
      long frame,
      long millis,
      long pid,
      boolean active,
      long epoch,
      long map,
      float hp,
      float maxHp,
      int ackResult,
      long ackSession,
      long ackSequence) {}

  public record Receipt(
      long sequence,
      long consumption,
      long millis,
      long serverTick,
      int remaining,
      float maxGuestHp) {}

  private HealingProtocol() {}

  private static void require(boolean test, String message) {
    if (!test) throw new IllegalArgumentException(message);
  }

  public static Host decodeHost(byte[] bytes, long now) {
    require(bytes.length == BYTES, "healing host size");
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    require(b.getInt(0) == HOST_MAGIC && b.getInt(4) == 1, "healing host version");
    long seq = b.getLong(8),
        frame = b.getLong(16),
        stamp = b.getLong(24),
        pid = Integer.toUnsignedLong(b.getInt(32));
    int flags = b.getInt(36);
    long epoch = b.getLong(40), map = Integer.toUnsignedLong(b.getInt(48));
    float hp = b.getFloat(52), max = b.getFloat(56);
    int result = b.getInt(60);
    long session = b.getLong(64), ack = b.getLong(72);
    require(
        seq > 0
            && (seq & 1) == 0
            && frame > 0
            && stamp > 0
            && now >= stamp
            && now - stamp <= HOST_FRESH_MS,
        "healing host freshness");
    require(
        pid > 0 && flags >= 0 && flags <= 1 && epoch > 0 && map != 0xffff_ffffL,
        "healing host identity");
    require(
        Float.isFinite(hp)
            && Float.isFinite(max)
            && max > 0
            && max <= 10_000_000
            && hp >= 0
            && hp <= max,
        "healing host health");
    require(
        result >= 0
            && result <= 2
            && session >= 0
            && ack >= 0
            && (ack == 0 || session > 0)
            && (result == 0) == (ack == 0),
        "healing acknowledgement");
    for (int i = 80; i < BYTES; i++) require(bytes[i] == 0, "healing host reserved bytes");
    return new Host(frame, stamp, pid, flags == 1, epoch, map, hp, max, result, session, ack);
  }

  public static void validate(Receipt r) {
    require(
        r.sequence > 0 && r.consumption > 0 && r.millis > 0 && r.serverTick >= 0,
        "healing receipt identity");
    require(
        r.remaining >= 25 && r.remaining <= 100 && r.remaining % 25 == 0,
        "healing regeneration cadence");
    require(
        Float.isFinite(r.maxGuestHp) && r.maxGuestHp >= 1 && r.maxGuestHp <= 1024,
        "healing guest health cap");
  }

  public static byte[] encode(
      long sequence,
      long frame,
      long now,
      long pid,
      boolean active,
      long session,
      Host host,
      List<Receipt> receipts) {
    require(
        sequence > 0
            && (sequence & 1) == 0
            && frame > 0
            && now > 0
            && pid > 0
            && pid <= 0xffff_ffffL
            && session > 0,
        "healing header");
    require(
        host != null
            && host.pid > 0
            && host.epoch > 0
            && receipts.size() <= 8
            && (!active ? receipts.isEmpty() : true),
        "healing packet");
    var b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, RECEIPT_MAGIC)
        .putInt(4, 1)
        .putLong(8, sequence)
        .putLong(16, frame)
        .putLong(24, now)
        .putInt(32, (int) pid)
        .putInt(36, active ? 1 : 0)
        .putLong(40, session)
        .putInt(48, (int) host.pid)
        .putInt(52, (int) host.map)
        .putLong(56, host.epoch)
        .putLong(64, host.frame)
        .putInt(72, receipts.size());
    long previous = 0;
    for (int i = 0; i < receipts.size(); i++) {
      var r = receipts.get(i);
      validate(r);
      require(
          r.sequence > previous && r.millis <= now && now - r.millis <= RECEIPT_FRESH_MS,
          "healing receipt order/freshness");
      previous = r.sequence;
      int at = 128 + i * 64;
      b.putLong(at, r.sequence)
          .putLong(at + 8, r.consumption)
          .putLong(at + 16, r.millis)
          .putLong(at + 24, r.serverTick)
          .putInt(at + 32, 1)
          .putInt(at + 36, 1)
          .putFloat(at + 40, 1)
          .putFloat(at + 44, r.maxGuestHp)
          .putInt(at + 48, r.remaining);
    }
    return b.array();
  }
}
