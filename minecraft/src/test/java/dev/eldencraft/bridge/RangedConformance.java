package dev.eldencraft.bridge;

import com.google.gson.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RangedConformance {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static WorldOrigin.Vec p(double x, double y, double z) {
    return new WorldOrigin.Vec(x, y, z);
  }

  private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final ProjectileTrace.Context CONTEXT =
      new ProjectileTrace.Context(7, 8, 9, 10, 11, OWNER);

  private static ProjectileTrace trace() {
    return new ProjectileTrace(CONTEXT, 1000, p(0, 0, 0));
  }

  private static JsonObject host() {
    var o = new JsonObject();
    o.addProperty("epoch", 1);
    o.addProperty("map", 100);
    o.add("feet", JsonWire.vector(0, 0, 0));
    o.add("camera", JsonWire.vector(0, 1.62, 0));
    o.add("forward", JsonWire.vector(0, 0, 1));
    o.add("offset", JsonWire.vector(0, 0, 0));
    o.addProperty("grounded", true);
    o.addProperty("hp", 500);
    o.addProperty("max_hp", 1000);
    o.addProperty("terrain_revision", 1);
    o.addProperty("terrain_ready", true);
    o.add("terrain_bounds", JsonWire.vector(-8, -4, -8, 8, 8, 8));
    o.add("terrain_boxes", new JsonArray());
    o.add("targets", new JsonArray());
    o.addProperty("guest_session", 8);
    o.addProperty("ack", 1);
    o.add("incoming", new JsonArray());
    return o;
  }

  private static WorldProtocol.Host decode(JsonObject o) throws Exception {
    byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
    var b = ByteBuffer.allocate(64 + body.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, WorldProtocol.HOST_MAGIC)
        .putInt(4, 1)
        .putLong(8, 2)
        .putLong(16, 1)
        .putLong(24, 1000)
        .putInt(32, 7)
        .putInt(36, 1)
        .putInt(40, body.length);
    b.position(64);
    b.put(body);
    return WorldProtocol.decode(b.array(), 1000);
  }

  private static void invalid(JsonObject host, String reason) {
    try {
      decode(host);
      throw new AssertionError(reason);
    } catch (java.io.IOException expected) {
      checks++;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  public static void main(String[] args) throws Exception {
    var t = trace();
    check(t.snapshot(CONTEXT, 1000).isEmpty(), "launch alone is not a traveled segment");
    check(t.append(CONTEXT, 1050, p(2, 1, 0)), "actual tick sample");
    check(t.append(CONTEXT, 1100, p(4, 1.95, 0)), "actual arc is retained");
    var first = t.impact(CONTEXT, 1110, p(5, 2.2, 0));
    var piercing = t.impact(CONTEXT, 1110, p(6, 2.4, 0));
    check(first.size() == 4 && first.getLast().x() == 5, "primary exact impact");
    check(piercing.size() == 4 && piercing.getLast().x() == 6, "piercing exact later impact");
    check(t.snapshot(CONTEXT, 1110).size() == 3, "per-hit receipts do not mutate exported prefix");
    check(first.getLast().x() == 5, "later piercing hit cannot rewrite earlier receipt");
    check(t.append(CONTEXT, 1150, p(6, 2.4, 0)), "next sample appends to immutable prefix");
    check(
        t.contact(CONTEXT, 1150, 1, p(1, .5, 0)).size() == 2,
        "native earlier contact truncates late visual overshoot");
    check(
        t.contact(CONTEXT, 1150, 1, p(1, 2, 0)).isEmpty(), "arbitrary native destination rejected");
    check(t.contact(CONTEXT, 1150, 0, p(0, 0, 0)).isEmpty(), "zero segment rejected");
    check(t.contact(CONTEXT, 1150, 127, p(1, 0, 0)).isEmpty(), "unexported segment rejected");
    check(
        t.valid(CONTEXT, 7000) && !t.valid(CONTEXT, 7001),
        "flight clock separate from fresh receipt clock");
    check(!t.valid(CONTEXT, 999), "future launch rejected");
    for (var context :
        List.of(
            new ProjectileTrace.Context(70, 8, 9, 10, 11, OWNER),
            new ProjectileTrace.Context(7, 80, 9, 10, 11, OWNER),
            new ProjectileTrace.Context(7, 8, 90, 10, 11, OWNER),
            new ProjectileTrace.Context(7, 8, 9, 100, 11, OWNER),
            new ProjectileTrace.Context(7, 8, 9, 10, 110, OWNER),
            new ProjectileTrace.Context(7, 8, 9, 10, 11, UUID.randomUUID())))
      check(!t.valid(context, 1100), "complete launch identity prevents reconnect replay");
    var tooFast = trace();
    check(!tooFast.append(CONTEXT, 1050, p(8.01, 0, 0)), "oversized segment rejected");
    check(!tooFast.append(CONTEXT, 1060, p(1, 0, 0)), "invalid flight cannot recover");
    var range = trace();
    for (int i = 1; i <= 8; i++)
      check(range.append(CONTEXT, 1000 + i * 50, p(i * 8, 0, 0)), "bounded range prefix");
    check(!range.append(CONTEXT, 1500, p(65, 0, 0)), "distance radius bound");
    var limit = trace();
    for (int i = 1; i < 128; i++) limit.append(CONTEXT, 1000 + i, p(i * .1, 0, 0));
    check(limit.snapshot(CONTEXT, 1200).size() == 128, "128 points retained without decimation");
    check(!limit.append(CONTEXT, 1201, p(12.8, 0, 0)), "point budget expires flight");
    var duplicate = trace();
    check(
        duplicate.append(CONTEXT, 1001, p(0, 0, 0)) && duplicate.snapshot(CONTEXT, 1001).isEmpty(),
        "stationary sample does not invent motion");
    var arc = trace();
    for (int i = 1; i <= 32; i++)
      check(arc.append(CONTEXT, 1000 + i, p((i % 2) * 8, 0, 0)), "arc length prefix");
    check(!arc.append(CONTEXT, 1050, p(8, 0, 0)), "total arc budget");
    var input = new RangedInput(7, 10, OWNER, 1_000_000_000, 30, -20, true);
    check(input.permits(7, 10, OWNER, 1_250_000_000), "fresh normal release remains authorized");
    check(!input.permits(7, 10, OWNER, 1_250_000_001), "stale release cannot consume ammo");
    check(!input.permits(7, 10, OWNER, 999_999_999), "clock rollback cannot authorize");
    check(!input.permits(8, 10, OWNER, 1_000_000_001), "publisher replacement cannot fire");
    check(!input.permits(7, 11, OWNER, 1_000_000_001), "map replacement cannot fire");
    check(
        !input.permits(7, 10, UUID.randomUUID(), 1_000_000_001),
        "new player cannot inherit charge");
    check(
        !new RangedInput(7, 10, OWNER, 1_000_000_000, 30, -20, false)
            .permits(7, 10, OWNER, 1_000_000_001),
        "held-through-reacquire blocked");
    check(
        !new RangedInput(7, 10, OWNER, 1_000_000_000, Float.NaN, 0, true)
            .permits(7, 10, OWNER, 1_000_000_001),
        "invalid aim rejected");
    check(
        !new RangedInput(7, 10, OWNER, 1_000_000_000, 0, 91, true)
            .permits(7, 10, OWNER, 1_000_000_001),
        "invalid pitch rejected");
    var h = host();
    check(decode(h).projectileImpacts().isEmpty(), "older host has no contact authority");
    var hit = new JsonObject();
    hit.addProperty("projectile", OWNER.toString());
    hit.addProperty("segment", 1);
    hit.addProperty("time_ms", 1000);
    hit.add("point", JsonWire.vector(1, 0, 0));
    hit.add("normal", JsonWire.vector(-1, 0, 0));
    var hits = new JsonArray();
    hits.add(hit);
    h.add("projectile_impacts", hits);
    check(
        decode(h).projectileImpacts().getFirst().normal().x() == -1,
        "real normal and contact decoded");
    var bad = h.deepCopy();
    bad.getAsJsonArray("projectile_impacts").get(0).getAsJsonObject().addProperty("segment", 0);
    invalid(bad, "zero contact segment");
    bad = h.deepCopy();
    bad.getAsJsonArray("projectile_impacts")
        .get(0)
        .getAsJsonObject()
        .add("normal", JsonWire.vector(0, 0, 0));
    invalid(bad, "invalid contact normal");
    bad = h.deepCopy();
    bad.getAsJsonArray("projectile_impacts").add(hit.deepCopy());
    invalid(bad, "duplicate contact replay");
    bad = h.deepCopy();
    bad.getAsJsonArray("projectile_impacts").get(0).getAsJsonObject().addProperty("time_ms", 1001);
    check(decode(bad).projectileImpacts().isEmpty(), "future contact not applied");
    var ack = new JsonObject();
    ack.addProperty("seq", 1);
    ack.addProperty("result", 2);
    ack.addProperty("delta", 0);
    ack.addProperty("reason", "unsafe landing");
    var acks = new JsonArray();
    acks.add(ack);
    h.add("acks", acks);
    check(
        decode(h).acknowledgements().getFirst().result() == 2 && decode(h).guestSession() == 8,
        "rejection paired with exact session for feedback");
    bad = h.deepCopy();
    bad.getAsJsonArray("acks").get(0).getAsJsonObject().addProperty("result", 3);
    invalid(bad, "unknown result");
    System.out.println("Ranged conformance: " + checks + " checks passed");
  }
}
