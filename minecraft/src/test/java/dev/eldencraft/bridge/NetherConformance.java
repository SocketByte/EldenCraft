package dev.eldencraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Nether layout rules and the ECNH page, without starting a game. The golden values are shared with
 * the compositor's nether_rules test, which runs the effect's HLSL copy of these rules on a
 * software D3D11 device: together they keep painted and real blocks in the same places.
 */
public final class NetherConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  /**
   * FNV-1a over every block kind in a 400x400 area around a centred portal (and an uncentred map).
   */
  static long layoutChecksum() {
    long h = 0xcbf29ce484222325L;
    for (int centred = 0; centred < 2; centred++)
      for (int z = -200; z < 200; z++)
        for (int x = -200; x < 200; x++) {
          int kind =
              NetherRules.ground(x, z, centred == 1, 1.5f, .5f)
                  + (NetherRules.deepLava(x, z, centred == 1, 1.5f, .5f) ? 8 : 0);
          h = (h ^ kind) * 0x100000001b3L;
        }
    return h;
  }

  public static void main(String[] args) {
    // Golden integer hashes: the HLSL EcHellHash must reproduce these bit for bit.
    check(NetherRules.hash(0, 0, 0) == 0, "hash origin");
    check(
        NetherRules.hash(1, 0, 0) == GOLDEN_HASH_1_0_0,
        "hash(1,0,0) golden: " + Integer.toUnsignedString(NetherRules.hash(1, 0, 0)));
    check(
        NetherRules.hash(-7, 13, 5) == GOLDEN_HASH_M7_13_5,
        "hash(-7,13,5) golden: " + Integer.toUnsignedString(NetherRules.hash(-7, 13, 5)));
    check(
        layoutChecksum() == GOLDEN_LAYOUT,
        "layout checksum golden: " + Long.toUnsignedString(layoutChecksum()));
    int lava = 0, deep = 0, rim = 0, crimson = 0, soul = 0, magma = 0, total = 0;
    for (int x = -150; x < 150; x++)
      for (int z = -150; z < 150; z++) {
        float n = NetherRules.noise(x, z, 7, 5);
        check(n >= 0 && n < 1, "noise range");
        int kind = NetherRules.ground(x, z, true, .5f, .5f);
        total++;
        switch (kind) {
          case NetherRules.GROUND_LAVA -> lava++;
          case NetherRules.GROUND_LAVA_RIM -> rim++;
          case NetherRules.GROUND_CRIMSON -> crimson++;
          case NetherRules.GROUND_SOUL -> soul++;
          case NetherRules.GROUND_MAGMA -> magma++;
          default -> {}
        }
        if (NetherRules.deepLava(x, z, true, .5f, .5f)) {
          deep++;
          check(kind == NetherRules.GROUND_LAVA, "deep lava is painted lava");
          for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
              check(
                  NetherRules.ground(x + dx, z + dz, true, .5f, .5f) >= NetherRules.GROUND_LAVA,
                  "deep lava has a lava/rim margin");
        }
        float dx = x + .5f - .5f, dz = z + .5f - .5f;
        if (dx * dx + dz * dz < 36) {
          check(kind < NetherRules.GROUND_LAVA, "no lava or rim within 6 blocks of the portal");
          check(!NetherRules.deepLava(x, z, true, .5f, .5f), "safe arrival");
        }
      }
    double f = lava / (double) total;
    check(f > .03 && f < .09, "lava covers a dangerous but navigable share: " + f);
    check(deep > 0 && deep < lava, "burning lava is the inside of the pools");
    check(
        rim > 0 && crimson > total / 20 && soul > total / 50 && magma > 0,
        "every ground kind appears");
    // The front: starts at the portal, accelerates, reaches everything; real blocks stop at their
    // radius.
    check(
        NetherRules.radius(0) == 1.5f
            && NetherRules.radius(-1) == 1.5f
            && NetherRules.radius(Double.NaN) == 1.5f,
        "radius starts at the portal");
    float last = 0;
    for (double t = 0; t < 700; t += .5) {
      float r = NetherRules.radius(t);
      check(r >= last, "radius never shrinks");
      last = r;
      check(
          NetherRules.realRadius(t) <= NetherRules.REAL_RADIUS + .5f,
          "real blocks stay near the portal");
    }
    check(NetherRules.radius(600) == NetherRules.EVERYWHERE, "spread finishes everywhere");
    check(
        NetherRules.radius(30) < 14 && NetherRules.radius(60) < 26,
        "a slow creep for the first minute");
    check(
        Math.abs(NetherRules.radius(59.99) - NetherRules.radius(60.01)) < .05f
            && Math.abs(NetherRules.radius(60) - 25.5f) < .01f,
        "continuous at the acceleration");
    float r0 = NetherRules.ragged(10, 0, 0, 0);
    check(r0 > 6 && r0 < 15, "ragged distance stays near the true distance");
    check(
        NetherRules.surfaceOffset(.7) == 0
            && NetherRules.surfaceOffset(.99) == 0
            && NetherRules.surfaceOffset(.69) == -1
            && NetherRules.surfaceOffset(0) == -1,
        "blocks sink unless the surface is in the top 30%");
    // ECNH encoding.
    var grid = new WorldOrigin.Vec(1024.25, 64, -512.5);
    var center = new WorldOrigin.Vec(1025.75, 64, -511);
    var state =
        new NetherProtocol.State(
            1234,
            5678,
            9,
            0x3c272800L,
            3,
            true,
            true,
            grid,
            center,
            .5f,
            42,
            .25f,
            .75f,
            1,
            1000,
            900,
            950,
            990,
            995);
    var b = ByteBuffer.wrap(NetherProtocol.encode(state)).order(ByteOrder.LITTLE_ENDIAN);
    check(
        b.capacity() == 256 && b.getInt(0) == 0x484e4345 && b.getInt(4) == 1 && b.getLong(8) == 0,
        "header; the writer owns the sequence");
    check(
        b.getInt(16) == 1234
            && b.getInt(20) == 5678
            && b.getLong(24) == 9
            && Integer.toUnsignedLong(b.getInt(32)) == 0x3c272800L
            && b.getInt(36) == 3
            && b.getLong(40) == 1000
            && b.getLong(48) == 3,
        "identity");
    check(
        b.getDouble(56) == 1024.25
            && b.getDouble(64) == 64
            && b.getDouble(72) == -512.5
            && b.getDouble(80) == 1025.75
            && b.getDouble(96) == -511,
        "positions");
    check(
        b.getFloat(104) == .5f
            && b.getFloat(108) == 42
            && b.getFloat(112) == .25f
            && b.getFloat(116) == .75f
            && b.getFloat(120) == 1
            && b.getInt(124) == 0,
        "factors");
    check(
        b.getLong(128) == 900
            && b.getLong(136) == 950
            && b.getLong(144) == 990
            && b.getLong(152) == 995,
        "event times");
    for (int i = 160; i < 256; i++) check(b.get(i) == 0, "reserved tail is zero");
    var flags =
        ByteBuffer.wrap(
                NetherProtocol.encode(
                    new NetherProtocol.State(
                        1,
                        1,
                        1,
                        0,
                        1,
                        false,
                        false,
                        grid,
                        grid,
                        0,
                        NetherRules.EVERYWHERE,
                        0,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        0)))
            .order(ByteOrder.LITTLE_ENDIAN);
    check(
        flags.getInt(36) == 0 && flags.getFloat(108) == NetherRules.EVERYWHERE,
        "inactive/uncentred flags and the everywhere radius");
    rejects(
        () ->
            new NetherProtocol.State(
                0, 1, 1, 0, 1, true, true, grid, grid, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0),
        "producer pid");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 0, 0, 1, true, true, grid, grid, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0),
        "epoch");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 1, 0, 1, true, true, grid, grid, 1.5f, 1, 0, 0, 0, 1, 0, 0, 0, 0),
        "amount above one");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 1, 0, 1, true, true, grid, grid, Float.NaN, 1, 0, 0, 0, 1, 0, 0, 0, 0),
        "NaN amount");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 1, 0, 1, true, true, grid, grid, 0, -1, 0, 0, 0, 1, 0, 0, 0, 0),
        "negative radius");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 1, 0, 1, true, true, grid, grid, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0),
        "missing heartbeat");
    rejects(
        () ->
            new NetherProtocol.State(
                1, 1, 1, 0, 1, true, true, null, grid, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0),
        "missing grid");
    // Spawn admission: only an exact Nether summon may use EVENT, never another reason.
    check(
        WorldSpawnPolicy.allowed(
            true, true, net.minecraft.world.entity.EntitySpawnReason.EVENT, false, false, true),
        "Nether wave admitted");
    check(
        !WorldSpawnPolicy.allowed(
            true, true, net.minecraft.world.entity.EntitySpawnReason.EVENT, false, false, false),
        "EVENT without the scope denied");
    for (var reason : net.minecraft.world.entity.EntitySpawnReason.values())
      if (reason != net.minecraft.world.entity.EntitySpawnReason.EVENT)
        check(
            WorldSpawnPolicy.allowed(true, true, reason, false, false, true)
                == WorldSpawnPolicy.allowed(true, true, reason, false, false),
            "summon scope grants nothing else: " + reason);
    System.out.println(
        "Nether conformance: "
            + checks
            + " checks passed (layout lava="
            + String.format("%.3f", f)
            + ", wire, spawn scope; no live game).");
  }

  private static void rejects(Runnable r, String what) {
    try {
      r.run();
    } catch (IllegalArgumentException expected) {
      checks++;
      return;
    }
    throw new AssertionError("accepted invalid " + what);
  }

  // Shared with compositor/tests/nether_rules_test.cpp.
  static final int GOLDEN_HASH_1_0_0 = 0xb0eedb37, GOLDEN_HASH_M7_13_5 = 0x8fb3ed57;
  static final long GOLDEN_LAYOUT = 0x5b4596180ac16fe1L;
}
