package dev.eldencraft.bridge;

import java.nio.*;
import java.util.*;
import java.util.function.Consumer;

/** Portable adversarial wire/lifecycle checks; no game launch or simulated gameplay claims. */
public final class ProxyConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static ByteBuffer packet() {
    var b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, 0x47544345)
        .putInt(4, 1)
        .putLong(8, 2)
        .putLong(16, 1)
        .putLong(24, 1000)
        .putInt(32, 1234);
    b.putInt(36, 7).putLong(40, 42).putInt(48, 55).putInt(52, 1);
    b.putFloat(60, 1.62f).putFloat(76, 1).putFloat(88, 16).putFloat(92, 50);
    b.putLong(128, 0x123400005678L).putLong(136, 9);
    b.putFloat(144, -.5f).putFloat(148, 0).putFloat(152, 2);
    b.putFloat(156, .5f).putFloat(160, 2).putFloat(164, 3);
    b.putFloat(168, 500).putFloat(172, 1000).putInt(176, 3);
    return b;
  }

  private static void reject(Consumer<ByteBuffer> mutation, String message) {
    var b = packet();
    mutation.accept(b);
    try {
      ProxyProtocol.decodeTargets(b.array(), 1000);
      throw new AssertionError(message);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static ProxyProtocol.Frame frame(
      long number, long session, long ack, int flags, long epoch) {
    var b = packet();
    b.putLong(16, number)
        .putLong(96, session)
        .putLong(104, ack)
        .putInt(36, flags)
        .putLong(40, epoch);
    return ProxyProtocol.decodeTargets(b.array(), 1000);
  }

  private static ProxyProtocol.Receipt receipt(long n) {
    return new ProxyProtocol.Receipt(
        n, 0x123400005678L, 9, 1, 1000, 1, 3.25f, 0, 1, 20, "minecraft:iron_sword");
  }

  private static void rejectEncode(List<ProxyProtocol.Receipt> receipts, String label) {
    try {
      ProxyProtocol.encodeReceipts(2, 1, 1000, 1234, true, 9, 555, 55, 42, receipts);
      throw new AssertionError(label);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  public static void main(String[] args) {
    var valid = ProxyProtocol.decodeTargets(packet().array(), 1000);
    check(valid.ready() && valid.targets().getFirst().hittable(), "ready and visible");
    check(
        valid.targets().getFirst().uuid(42).equals(valid.targets().getFirst().uuid(42)),
        "stable proxy identity");
    check(
        !valid.targets().getFirst().uuid(42).equals(valid.targets().getFirst().uuid(43)),
        "epoch changes entity identity");
    check(!frame(1, 0, 0, 1, 42).ready(), "inspection flag cannot enable damage");
    check(ProxyProtocol.decodeTargets(packet().array(), 1249).ready(), "last fresh millisecond");
    try {
      ProxyProtocol.decodeTargets(packet().array(), 1250);
      throw new AssertionError("stale");
    } catch (IllegalArgumentException ok) {
      checks++;
    }
    try {
      ProxyProtocol.decodeTargets(packet().array(), 999);
      throw new AssertionError("future");
    } catch (IllegalArgumentException ok) {
      checks++;
    }
    reject(b -> b.putInt(0, 0), "magic");
    reject(b -> b.putInt(4, 2), "version");
    reject(b -> b.putLong(8, 3), "odd sequence");
    reject(b -> b.putLong(16, 0), "frame");
    reject(b -> b.putInt(32, 0), "pid");
    reject(b -> b.putLong(40, 0), "epoch");
    reject(b -> b.putInt(36, 31), "unknown flags");
    reject(b -> b.putInt(52, 17), "count");
    check(frame(1, 0, 0, 15, 42).debugBounds(), "debug flag accepted");
    check(!frame(1, 0, 0, 9, 42).ready(), "debug inspection cannot grant damage readiness");
    check(frame(1, 0, 0, 3, 42).ready(), "airborne ordinary melee retains damage readiness");
    var airborne = new ProxyReceipts();
    airborne.update(frame(1, 0, 0, 7, 42));
    long airborneSession = airborne.session();
    airborne.update(frame(2, airborneSession, 0, 7, 42));
    airborne.add(airborneSession, receipt(0));
    airborne.update(frame(3, airborneSession, 0, 3, 42));
    check(
        airborne.ready()
            && airborne.session() == airborneSession
            && airborne.snapshot().size() == 1,
        "leaving ground preserves session and pending vanilla damage");
    airborne.update(frame(4, airborneSession, 0, 7, 42));
    check(
        airborne.ready() && airborne.snapshot().getFirst().sequence() == 1,
        "landing does not replay or drop pending damage");
    check(
        ProxyDebugColors.classify(true, true, true, true) == ProxyDebugColors.SELECTED,
        "selected eligible box");
    check(
        ProxyDebugColors.classify(true, true, true, false) == ProxyDebugColors.READY,
        "eligible unselected box");
    check(
        ProxyDebugColors.classify(true, true, false, true) == ProxyDebugColors.OUT_OF_REACH,
        "selection cannot override reach");
    check(
        ProxyDebugColors.classify(true, false, true, true) == ProxyDebugColors.BLOCKED,
        "selection cannot override sink gate");
    check(
        ProxyDebugColors.classify(false, true, true, true) == ProxyDebugColors.BLOCKED,
        "selection cannot override visibility");
    reject(b -> b.putFloat(56, Float.NaN), "camera NaN");
    reject(b -> b.putFloat(76, 0), "zero direction");
    reject(b -> b.putFloat(84, 91), "pitch");
    reject(b -> b.putFloat(88, -1), "negative obstruction");
    reject(b -> b.putFloat(88, Float.POSITIVE_INFINITY), "infinite obstruction");
    reject(b -> b.putFloat(92, 0), "zero scale");
    reject(b -> b.putLong(104, 1), "ACK without session");
    reject(b -> b.putInt(112, 3), "unknown ACK result");
    reject(b -> b.put(116, (byte) 1), "reserved header");
    reject(b -> b.putLong(128, 0), "zero handle");
    reject(b -> b.putLong(136, 0), "zero generation");
    reject(b -> b.putFloat(144, 1), "inverted box");
    reject(b -> b.putFloat(144, -33), "remote box");
    reject(b -> b.putFloat(168, -1), "negative hp");
    reject(b -> b.putFloat(168, 1001), "over maximum hp");
    reject(b -> b.putFloat(172, Float.NaN), "NaN hp");
    reject(b -> b.putInt(176, 7), "unknown target flag");
    reject(b -> b.put(184, (byte) 1), "reserved target");
    reject(b -> b.put(4095, (byte) 1), "unused packet bytes");
    reject(
        b -> {
          b.putInt(52, 2);
          for (int i = 0; i < 80; i++) b.put(208 + i, b.get(128 + i));
        },
        "duplicate handle");
    var encoded =
        ByteBuffer.wrap(
                ProxyProtocol.encodeReceipts(
                    2, 1, 1000, 1234, true, 9, 555, 55, 42, List.of(receipt(1))))
            .order(ByteOrder.LITTLE_ENDIAN);
    check(
        encoded.getInt(0) == 0x4d444345 && encoded.getInt(64) == 1 && encoded.getLong(128) == 1,
        "receipt layout");
    check(
        encoded.getFloat(176) == 3.25f && encoded.getInt(184) == 1,
        "actual damage and item wear preserved");
    check(encoded.getInt(192) == 20 && encoded.get(4095) == 0, "item length and unused zero");
    rejectEncode(List.of(receipt(2), receipt(1)), "out of order");
    rejectEncode(Collections.nCopies(33, receipt(1)), "queue bound");
    rejectEncode(
        List.of(
            new ProxyProtocol.Receipt(
                1, 1, 1, 1, 1000, 1, Float.NaN, 0, 1, 20, "minecraft:iron_sword")),
        "NaN damage");
    rejectEncode(
        List.of(
            new ProxyProtocol.Receipt(1, 1, 1, 1, 1000, 1, 0, 0, 1, 20, "minecraft:iron_sword")),
        "zero damage");
    rejectEncode(
        List.of(
            new ProxyProtocol.Receipt(1, 1, 1, 1, 1000, 1, 1001, 0, 1, 20, "minecraft:iron_sword")),
        "damage bound");
    rejectEncode(
        List.of(
            new ProxyProtocol.Receipt(1, 1, 1, 1, 1000, 1, 1, -1, 1, 20, "minecraft:iron_sword")),
        "invalid wear");
    rejectEncode(
        List.of(new ProxyProtocol.Receipt(1, 1, 1, 1, 1000, 1, 1, 0, 1, 20, "minecraft:wrong id")),
        "invalid item");
    var q = new ProxyReceipts();
    long original = q.session();
    check(!q.ready() && !q.add(original, receipt(0)), "no attack before handshake");
    q.update(frame(1, 0, 0, 7, 42));
    long session = q.session();
    check(session != original && !q.ready() && q.snapshot().isEmpty(), "new epoch starts empty");
    q.update(frame(2, session, 0, 7, 42));
    check(q.ready() && q.capacity() && q.matches(session, 2), "host ACK enables queue");
    check(
        q.add(session, receipt(0)) && q.snapshot().getFirst().sequence() == 1, "assigned sequence");
    check(q.pendingDamage(receipt(1).handle(), 9) == 3.25f, "unacknowledged damage retained");
    check(q.pendingDamage(receipt(1).handle(), 10) == 0, "generation isolates pending damage");
    var oldView = q.view();
    q.update(frame(3, session, 1, 7, 42));
    check(
        q.snapshot().isEmpty() && oldView.pending().size() == 1,
        "ACK consumption and immutable view");
    check(
        q.add(session, receipt(0)) && q.snapshot().getFirst().sequence() == 2,
        "ACK never reuses receipt sequence");
    q.update(null);
    long lost = q.session();
    check(
        lost != session && !q.ready() && q.snapshot().isEmpty(),
        "disconnect clears without replay");
    check(!q.add(session, receipt(0)), "in-flight old session cannot enqueue after loss");
    q.update(frame(4, session, 2, 7, 42));
    long resumed = q.session();
    check(!q.ready(), "old acknowledgment cannot enable resumed session");
    q.update(frame(5, resumed, 0, 7, 42));
    check(q.ready(), "new empty session handshake");
    q.update(frame(6, resumed, 1, 7, 42));
    check(!q.ready() && q.snapshot().isEmpty(), "acknowledgment cannot invent accepted damage");
    q.update(frame(7, 0, 0, 7, 42));
    session = q.session();
    q.update(frame(8, session, 0, 7, 42));
    for (int i = 0; i < 17; i++) check(q.add(session, receipt(0)), "bounded queue item " + i);
    check(!q.capacity(), "reserve capacity for complete sixteen-target sweep");
    for (int i = 17; i < 32; i++) q.add(session, receipt(0));
    check(!q.add(session, receipt(0)) && q.snapshot().size() == 32, "hard queue bound");
    q.update(frame(9, session, 0, 1, 42));
    check(!q.ready() && q.snapshot().isEmpty(), "sink readiness loss clears queue");
    q.update(frame(10, 0, 0, 7, 42));
    session = q.session();
    q.update(frame(11, session, 0, 7, 42));
    q.update(frame(12, session, 0, 7, 43));
    check(!q.ready() && q.session() != session, "host epoch replacement");
    session = q.session();
    q.update(frame(13, session, 0, 7, 43));
    q.update(frame(12, session, 0, 7, 43));
    check(!q.ready(), "frame rollback resets authority");
    var events = new ProxyEvents<String>();
    check(!events.offer(0, 100, "invalid"), "event needs a session");
    check(!events.offer(1, 100, null), "event needs a payload");
    check(events.offer(1, 100, "selected-frame-41"), "capture clicked frame");
    check(
        "selected-frame-41".equals(events.poll(1, 200)),
        "later aim does not replace captured payload");
    check(events.poll(1, 200) == null, "event consumed exactly once");
    events.offer(1, 100, "old");
    events.offer(2, 110, "new");
    check("new".equals(events.poll(2, 120)), "new session skips old events");
    events.offer(2, 100, "expired");
    check(events.poll(2, 250_000_100) == null, "250 ms expiry is strict");
    events.offer(2, 100, "fresh");
    check("fresh".equals(events.poll(2, 250_000_099)), "last fresh nanosecond");
    events.offer(2, 100, "future");
    check(events.poll(2, 99) == null, "future event rejected");
    events.offer(2, 100, "first");
    events.offer(2, 101, "second");
    check(
        "first".equals(events.poll(2, 102)) && "second".equals(events.poll(2, 102)),
        "packet ordering retained");
    for (int i = 0; i < 32; i++) check(events.offer(2, 100, "bounded"), "bounded event " + i);
    check(!events.offer(2, 100, "overflow"), "intent/feedback queue hard limit");
    events.clear();
    check(events.poll(2, 102) == null, "focus loss clears pending feedback and intents");
    System.out.println(
        "Proxy conformance: " + checks + " checks passed (wire and policy only; no game launched)");
  }
}
