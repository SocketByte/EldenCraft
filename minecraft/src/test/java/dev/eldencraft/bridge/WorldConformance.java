package dev.eldencraft.bridge;

import com.google.gson.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class WorldConformance {
  private static int checks;

  private static void check(boolean value, String name) {
    checks++;
    if (!value) throw new AssertionError(name);
  }

  private static JsonObject fixture() {
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
    o.addProperty("guest_session", 0);
    o.addProperty("ack", 0);
    o.add("incoming", new JsonArray());
    return o;
  }

  private static byte[] bytes(JsonObject o) {
    byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
    var b = ByteBuffer.allocate(64 + body.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, WorldProtocol.HOST_MAGIC)
        .putInt(4, 1)
        .putLong(8, 2)
        .putLong(16, 1)
        .putLong(24, 1000)
        .putInt(32, 1)
        .putInt(36, 1)
        .putInt(40, body.length);
    b.position(64);
    b.put(body);
    return b.array();
  }

  private static WorldProtocol.Host host(JsonObject o) throws Exception {
    return WorldProtocol.decode(bytes(o), 1000);
  }

  private static void invalid(byte[] b, long time, String name) {
    try {
      WorldProtocol.decode(b, time);
      throw new AssertionError(name);
    } catch (java.io.IOException | IllegalArgumentException expected) {
      checks++;
    }
  }

  public static void main(String[] args) throws Exception {
    var base = fixture();
    var h = host(base);
    check(h.active() && h.terrainReady() && h.targets().isEmpty(), "valid snapshot");
    check(h.damageScale() == 50 && h.sourceMap() == 100, "compatible defaults");
    check(!h.nativeLadder(), "older hosts default to no native ladder handoff");
    var ladder = fixture();
    ladder.addProperty("native_ladder", true);
    check(host(ladder).nativeLadder(), "native ladder stops guest travel ownership");
    var region = fixture();
    region.add("offset", JsonWire.vector(-500, 20, 700));
    region.add("source_to_region", JsonWire.vector(100, 0, -200));
    region.addProperty("source_map", 101);
    check(
        host(region).sourceToRegion().equals(new WorldOrigin.Vec(100, 0, -200))
            && host(region).sourceMap() == 101,
        "source block conversion distinct from Havok offset");
    invalid(bytes(base), 1501, "stale");
    invalid(bytes(base), 999, "future");
    for (int offset : new int[] {0, 4, 8, 36, 40, 44}) {
      var bad = bytes(base);
      bad[offset] = (byte) (bad[offset] ^ 0x7f);
      invalid(bad, 1000, "header " + offset);
    }
    var zeroPid = bytes(base);
    ByteBuffer.wrap(zeroPid).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0);
    invalid(zeroPid, 1000, "zero pid");
    for (String key :
        new String[] {
          "epoch", "map", "hp", "max_hp", "grounded", "terrain_bounds", "targets", "incoming"
        }) {
      var bad = fixture();
      bad.remove(key);
      invalid(bytes(bad), 1000, "missing " + key);
    }
    var bad = fixture();
    bad.add("forward", JsonWire.vector(1, 1, 1));
    invalid(bytes(bad), 1000, "nonunit forward");
    bad = fixture();
    bad.add("terrain_bounds", JsonWire.vector(1, 0, 0, 0, 1, 1));
    invalid(bytes(bad), 1000, "inverted box");
    bad = fixture();
    bad.addProperty("hp", 1001);
    invalid(bytes(bad), 1000, "overheal");
    bad = fixture();
    var strings = new JsonArray();
    strings.add("0");
    strings.add(0);
    strings.add(1);
    bad.add("forward", strings);
    invalid(bytes(bad), 1000, "numeric strings rejected");
    bad = fixture();
    var many = new JsonArray();
    for (int i = 0; i < 4097; i++) many.add(JsonWire.vector(0, 0, 0, 1, 1, 1));
    bad.add("terrain_boxes", many);
    invalid(bytes(bad), 1000, "box count");
    try {
      WorldProtocol.parse("{\"epoch\":1,\"epoch\":2}".getBytes(StandardCharsets.UTF_8));
      throw new AssertionError("duplicates");
    } catch (java.io.IOException expected) {
      checks++;
    }
    var origin =
        new WorldOrigin(
            2, 100, 1, new WorldOrigin.Vec(100, -30, 200), new WorldOrigin.Vec(4096, 64, 0));
    var p = origin.toGuest(101, -28, 203);
    check(p.equals(new WorldOrigin.Vec(4097, 66, 3)), "stable guest coordinate");
    check(
        origin.toHost(p.x(), p.y(), p.z()).equals(new WorldOrigin.Vec(101, -28, 203)),
        "inverse transform");
    check(origin.toGuest(500, -28, 203).x() == 4496, "movement does not reanchor");
    var queue = new WorldEvents();
    queue.update(h, 1000);
    check(!queue.ready(), "requires host handshake");
    long session = queue.session();
    base.addProperty("guest_session", session);
    queue.update(host(base), 1000);
    check(queue.ready(), "handshake");
    var event = new JsonObject();
    event.addProperty("time_ms", 1000);
    event.addProperty("target", 7);
    event.addProperty("generation", 2);
    event.addProperty("damage", 3);
    check(queue.add(session, event), "accepted authoritative event");
    check(queue.pending(7, 2) == 3, "pending avoids health resurrection");
    check(queue.snapshot().get(0).getAsJsonObject().get("seq").getAsLong() == 1, "sequence");
    base.addProperty("ack", 1);
    queue.update(host(base), 1000);
    check(queue.snapshot().isEmpty(), "ack consumes");
    check(!queue.add(session + 1, event), "old session rejected");
    queue.update(null, 1000);
    check(!queue.ready() && queue.session() != session, "disconnect invalidates");
    base.addProperty("ack", 0);
    base.addProperty("guest_session", queue.session());
    queue.update(host(base), 1000);
    base.addProperty("guest_session", queue.session());
    queue.update(host(base), 1000);
    check(queue.add(queue.session(), event), "new session event");
    queue.update(host(base), 2001);
    check(queue.snapshot().isEmpty(), "event expiry");
    var incoming = new WorldIncoming();
    check(!incoming.consume(1), "incoming requires context");
    incoming.enter(1, 2, 3);
    check(incoming.consume(1) && incoming.ack(1, 2, 3) == 1, "incoming consume before mutation");
    check(!incoming.consume(1) && !incoming.consume(0), "incoming duplicate ignored");
    incoming.enter(1, 2, 3);
    check(incoming.ack(1, 2, 3) == 1, "same incoming context retains ack");
    check(incoming.ack(1, 4, 3) == 0, "different session cannot publish old ack");
    incoming.enter(1, 4, 3);
    check(incoming.consume(1), "new guest session accepts sequence one");
    incoming.enter(2, 4, 3);
    check(incoming.consume(1), "new host PID accepts sequence one");
    incoming.enter(2, 4, 5);
    check(incoming.consume(1), "new epoch accepts sequence one");
    incoming.clear();
    check(!incoming.consume(2), "incoming close revokes");
    check(
        WorldIncoming.nativeHandle("4334478807377440785") == 4334478807377440785L,
        "packed source handle retained");
    for (String source :
        new String[] {"0", "-1", "+1", "18446744073709551615", "native", " 1", null})
      check(WorldIncoming.nativeHandle(source) == 0, "unknown source has generic fallback");
    terrainHold();
    leaseContinuity();
    var columns = new java.util.ArrayList<net.minecraft.world.phys.AABB>();
    for (int i = 0; i < SharedTerrain.MAX_PIECES; i++) {
      double x = (i % 10) * .1, z = (i / 10) * .1;
      columns.add(
          new net.minecraft.world.phys.AABB(
              x, .4 + (i % 7) * .05, z, x + .1, .45 + (i % 7) * .05, z + .1));
    }
    check(
        !SharedTerrain.shape(columns).isEmpty(),
        "a block holds refined floor columns from neighbouring host cells");
    columns.add(new net.minecraft.world.phys.AABB(0, 0, 0, 1, .1, 1));
    try {
      SharedTerrain.shape(columns);
      check(false, "terrain piece cap enforced");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    long cell = TerrainMaterials.pack(-17, 63, 1234);
    check(
        cell == new net.minecraft.core.BlockPos(-17, 63, 1234).asLong(),
        "material cells use BlockPos packing");
    TerrainMaterials.clear();
    check(
        TerrainMaterials.resolve(cell).hit() == TerrainMaterials.DIRT,
        "an unknown flat cell defaults to dirt");
    TerrainMaterials.publishTerrain(
        java.util.Map.of(cell, 57),
        java.util.Set.of(TerrainMaterials.pack(0, 0, 0)),
        java.util.Map.of());
    check(
        TerrainMaterials.resolve(TerrainMaterials.pack(0, 0, 0)).hit() == TerrainMaterials.ROCK,
        "a steep unknown face defaults to rock");
    TerrainMaterials.publishTerrain(
        java.util.Map.of(cell, 57), java.util.Set.of(), java.util.Map.of(57, 4));
    check(
        TerrainMaterials.resolve(cell).hit() == 4
            && "minecraft:oak_log".equals(TerrainMaterials.blockId(TerrainMaterials.resolve(cell))),
        "a learned Wood body mines as logs");
    TerrainMaterials.observeGround(3, TerrainMaterials.pack(-16, 63, 1234), true);
    check(
        TerrainMaterials.resolve(TerrainMaterials.pack(-15, 63, 1234)).hit() == 3,
        "nearby footprint");
    TerrainMaterials.observeGround(2, cell, true);
    check(
        TerrainMaterials.resolve(cell).hit() == 2 && TerrainMaterials.ground() == 2,
        "a footprint on the cell wins");
    check(
        "minecraft:stone"
            .equals(
                TerrainMaterials.blockId(
                    new TerrainMaterials.Resolution(102, TerrainMaterials.NO_BODY, "t"))),
        "variant rows share their base material");
    check(
        TerrainMaterials.blockId(new TerrainMaterials.Resolution(21, TerrainMaterials.NO_BODY, "t"))
            == null,
        "water is not mineable");
    check(
        "Rock".equals(TerrainMaterials.name(2)) && "Unnamed".equals(TerrainMaterials.name(99)),
        "Paramdex names");
    TerrainMaterials.overrides(
        java.util.Map.of(2, "minecraft:deepslate"), java.util.Map.of(57, "minecraft:spruce_log"));
    check(
        "minecraft:deepslate"
            .equals(
                TerrainMaterials.blockId(
                    new TerrainMaterials.Resolution(2, TerrainMaterials.NO_BODY, "t"))),
        "hit override");
    check(
        "minecraft:spruce_log".equals(TerrainMaterials.blockId(TerrainMaterials.resolve(cell))),
        "a body override beats a hit override");
    check(
        "minecraft:spruce_log"
            .equals(TerrainMaterials.blockId(new TerrainMaterials.Resolution(-1, 57, "t"))),
        "body override maps e.g. a tree trunk");
    TerrainMaterials.overrides(java.util.Map.of(), java.util.Map.of());
    TerrainMaterials.clear();
    var hazards = WorldDamageAuthority.ENVIRONMENT;
    check(
        hazards.contains(net.minecraft.world.damagesource.DamageTypes.LAVA)
            && hazards.contains(net.minecraft.world.damagesource.DamageTypes.DROWN),
        "Minecraft hazards reach the shared health pool");
    for (var collision :
        java.util.List.of(
            net.minecraft.world.damagesource.DamageTypes.FALL,
            net.minecraft.world.damagesource.DamageTypes.IN_WALL,
            net.minecraft.world.damagesource.DamageTypes.FLY_INTO_WALL,
            net.minecraft.world.damagesource.DamageTypes.CRAMMING,
            net.minecraft.world.damagesource.DamageTypes.STALAGMITE))
      check(
          !hazards.contains(collision),
          "collision-derived damage stays with Elden Ring: " + collision);
    System.out.println("World conformance: " + checks + " checks passed");
  }

  private static void leaseContinuity() {
    check(
        WorldLeasePolicy.retain(1000, 1001, true, true),
        "torn copy retains original immutable host frame");
    check(
        WorldLeasePolicy.retain(1000, 1499, true, true),
        "retention remains within original producer deadline");
    check(
        !WorldLeasePolicy.retain(1000, 1500, true, true),
        "copy retries cannot extend retention deadline");
    check(!WorldLeasePolicy.retain(1000, 999, true, true), "future retained host rejected");
    check(
        !WorldLeasePolicy.retain(1000, 1001, false, true),
        "explicit inactive or malformed state never retained");
    check(!WorldLeasePolicy.retain(1000, 1001, true, false), "dead producer never retained");
    check(
        WorldLeasePolicy.fresh(1000, 1100, 1_000_000, 400_000_000), "copy age plus299ms is fresh");
    check(
        !WorldLeasePolicy.fresh(1000, 1100, 1_000_000, 401_000_000),
        "producer100ms plus elapsed400ms expires");
    check(
        WorldLeasePolicy.fresh(1000, 1499, 500_000_000, 500_000_000),
        "late copy has only original1ms left");
    check(
        !WorldLeasePolicy.fresh(1000, 1499, 500_000_000, 501_000_000),
        "late copy does not renew500ms authority");
    check(!WorldLeasePolicy.fresh(1000, 999, 100, 100), "future producer lease rejected");
    check(!WorldLeasePolicy.fresh(1000, 1001, 100, 99), "backward monotonic clock rejected");
  }

  private static void terrainHold() {
    var context = new WorldTerrainReadiness.Context(11, 22, 3, 44, 5);
    var bounds =
        new WorldProtocol.Box(new WorldOrigin.Vec(-8, -4, -8), new WorldOrigin.Vec(8, 8, 8));
    var complete = new WorldTerrainReadiness.Complete(context, bounds, 6, 1000);
    var feet = new WorldOrigin.Vec(0, 0, 0);
    check(
        WorldTerrainReadiness.mayReuse(complete, context, feet, 1000),
        "same complete terrain supports local incomplete replacement");
    check(
        WorldTerrainReadiness.mayReuse(complete, context, feet, 2000),
        "last allowed1s terrain hold instant");
    check(
        !WorldTerrainReadiness.mayReuse(complete, context, feet, 2001),
        "incomplete publications cannot renew complete snapshot lease");
    var replay =
        new WorldTerrainReadiness.Complete(
            complete.context(), complete.bounds(), complete.revision(), complete.millis());
    check(
        WorldTerrainReadiness.mayReuse(replay, context, feet, 1999),
        "same complete host frame replay keeps its original deadline");
    check(
        !WorldTerrainReadiness.mayReuse(replay, context, feet, 2001),
        "re-reading a complete frame cannot restart hold freshness");
    check(
        !WorldTerrainReadiness.mayReuse(complete, context, feet, 999),
        "future complete timestamp rejected");
    check(
        !WorldTerrainReadiness.mayReuse(null, context, feet, 1000),
        "first truncated snapshot cannot create coverage");
    check(
        !WorldTerrainReadiness.mayReuse(complete, null, feet, 1000),
        "missing current identity cannot use old terrain");
    for (var other :
        new WorldTerrainReadiness.Context[] {
          new WorldTerrainReadiness.Context(12, 22, 3, 44, 5),
              new WorldTerrainReadiness.Context(11, 23, 3, 44, 5),
          new WorldTerrainReadiness.Context(11, 22, 4, 44, 5),
              new WorldTerrainReadiness.Context(11, 22, 3, 45, 5),
          new WorldTerrainReadiness.Context(11, 22, 3, 44, 6)
        })
      check(
          !WorldTerrainReadiness.mayReuse(complete, other, feet, 1000),
          "terrain identity change rejects reuse");
    check(
        WorldTerrainReadiness.mayReuse(complete, context, new WorldOrigin.Vec(6.5, 5.5, 6.5), 1000),
        "whole body and neighborhood exactly inside complete boundary");
    for (var outside :
        new WorldOrigin.Vec[] {
          new WorldOrigin.Vec(6.5001, 0, 0),
          new WorldOrigin.Vec(-6.5001, 0, 0),
          new WorldOrigin.Vec(0, 0, 6.5001),
          new WorldOrigin.Vec(0, 0, -6.5001),
          new WorldOrigin.Vec(0, 5.5001, 0),
          new WorldOrigin.Vec(0, -3.5001, 0)
        })
      check(
          !WorldTerrainReadiness.mayReuse(complete, context, outside, 1000),
          "partial neighborhood outside completed terrain is unknown");
    check(
        complete.revision() == 6 && complete.bounds().equals(bounds) && complete.millis() == 1000,
        "hold never advertises incoming revision or bounds");
    var refreshed = new WorldTerrainReadiness.Complete(context, bounds, 7, 1800);
    check(
        WorldTerrainReadiness.mayReuse(refreshed, context, feet, 2100),
        "new actually complete snapshot may establish a new lease");
    check(
        !WorldTerrainReadiness.mayReuse(
            new WorldTerrainReadiness.Complete(context, bounds, 6, -1), context, feet, 0),
        "negative complete timestamp rejected");
  }
}
