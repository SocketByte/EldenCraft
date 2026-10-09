package dev.eldencraft.bridge;

import java.nio.*;
import java.util.*;

/** Portable bounds, identity, coverage and real-lightmap conversion checks. No game launch. */
public final class BlockMeshConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  private static void rejects(Runnable run, String label) {
    try {
      run.run();
      throw new AssertionError(label);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static final BlockMeshProtocol.Identity ID =
      new BlockMeshProtocol.Identity(11, 22, 3, 0xffffffffL, 4, 5);

  private static byte[] ack() {
    var b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, BlockMeshProtocol.ACK_MAGIC)
        .putInt(4, 1)
        .putLong(8, 2)
        .putInt(16, 11)
        .putInt(20, 22)
        .putLong(24, 3)
        .putInt(32, -1)
        .putInt(36, 1)
        .putLong(40, 4)
        .putLong(48, 1000)
        .putLong(56, 6)
        .putLong(64, 7)
        .putLong(96, 5);
    return b.array();
  }

  public static void main(String[] args) {
    var h =
        ByteBuffer.wrap(
                BlockMeshProtocol.header(
                    BlockMeshProtocol.MESH_MAGIC, ID, 1000, 6, 7, 9, 24, 216, 3, 6, 0, true))
            .order(ByteOrder.LITTLE_ENDIAN);
    check(
        h.getInt(80) == 216 && h.getLong(96) == 5 && h.getInt(32) == -1,
        "header exact packed fields and unsigned map");
    check(
        BlockMeshProtocol.header(BlockMeshProtocol.MESH_MAGIC, ID, 0, 1, 1, 0, 24, 0, 0, 0, 0, true)
                .length
            == 128,
        "empty complete mesh supports last-block removal");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC, ID, 0, 1, 1, 4, 24, 96, 4, 0, 0, true),
        "partial triangles rejected");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC, ID, 0, 1, 1, 6, 24, 144, 3, 0, 0, true),
        "layer coverage must equal total");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC,
                ID,
                0,
                1,
                1,
                BlockMeshProtocol.MAX_VERTICES + 3,
                24,
                (BlockMeshProtocol.MAX_VERTICES + 3) * 24,
                BlockMeshProtocol.MAX_VERTICES + 3,
                0,
                0,
                true),
        "bounded vertices");
    check(
        BlockMeshProtocol.header(
                    BlockMeshProtocol.MESH_MAGIC,
                    ID,
                    0,
                    1,
                    1,
                    BlockMeshProtocol.MAX_VERTICES - 2,
                    24,
                    (BlockMeshProtocol.MAX_VERTICES - 2) * 24,
                    BlockMeshProtocol.MAX_VERTICES - 2,
                    0,
                    0,
                    true)
                .length
            == 128,
        "a large build (two million vertices) fits one mesh");
    check(
        BlockMeshProtocol.maxMips(2048, 2048) == 12
            && BlockMeshProtocol.maxMips(2, 3) == 2
            && BlockMeshProtocol.mipExtent(3, 1) == 1
            && BlockMeshProtocol.mipOffset(2, 3, 2) == 28,
        "mip chain arithmetic matches the compositor");
    var mipped =
        BlockMeshProtocol.header(
            BlockMeshProtocol.ATLAS_MAGIC,
            ID,
            0,
            1,
            1,
            2048,
            2048,
            (int) BlockMeshProtocol.mipOffset(2048, 2048, 5),
            0,
            0,
            0,
            true,
            5);
    check(
        ByteBuffer.wrap(mipped).order(ByteOrder.LITTLE_ENDIAN).getInt(104) == 5,
        "vanilla 2048x2048 atlas publishes its five levels");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC, ID, 0, 1, 1, 2, 3, 24, 0, 0, 0, true, 2),
        "mip chain bytes must include every level");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC, ID, 0, 1, 1, 2, 3, 32, 0, 0, 0, true, 3),
        "more levels than the size allows");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC, ID, 0, 1, 1, 3, 24, 72, 3, 0, 0, true, 2),
        "only an atlas carries mip levels");
    var animation =
        BlockMeshProtocol.header(
            BlockMeshProtocol.ANIM_MAGIC, ID, 0, 9, 1, 2, 0, 32 + 64, 0, 0, 0, true);
    var anim = ByteBuffer.wrap(animation).order(ByteOrder.LITTLE_ENDIAN);
    check(
        anim.getInt(0) == BlockMeshProtocol.ANIM_MAGIC
            && anim.getLong(56) == 9
            && anim.getLong(64) == 1
            && anim.getInt(72) == 2
            && anim.getInt(104) == 0,
        "animation names its tick, atlas and region count");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ANIM_MAGIC, ID, 0, 9, 1, 3, 0, 32, 0, 0, 0, true),
        "region table must fit the animation payload");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ANIM_MAGIC,
                ID,
                0,
                9,
                1,
                1,
                0,
                BlockMeshProtocol.ANIM_BYTES,
                0,
                0,
                0,
                true),
        "animation payload bounded by its mapping");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.MESH_MAGIC, ID, 0, 1, 1, 3, 28, 72, 3, 0, 0, true),
        "wire stride fixed");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC, ID, 0, 1, 1, 4097, 1, 4097 * 4, 0, 0, 0, true),
        "atlas dimension bound");
    rejects(
        () ->
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC,
                ID,
                0,
                1,
                1,
                4096,
                4096,
                4096 * 4096 * 4 - 4,
                0,
                0,
                0,
                true),
        "atlas exact bytes");
    check(
        BlockMeshProtocol.header(
                    BlockMeshProtocol.ATLAS_MAGIC,
                    ID,
                    0,
                    1,
                    1,
                    4096,
                    4096,
                    4096 * 4096 * 4,
                    0,
                    0,
                    0,
                    true)
                .length
            == 128,
        "largest atlas header without allocating pixels");
    check(BlockMeshProtocol.acknowledged(ack(), ID, 6, 7, 1250), "last fresh ACK millisecond");
    check(!BlockMeshProtocol.acknowledged(ack(), ID, 6, 7, 1251), "stale ACK retains chunks");
    check(!BlockMeshProtocol.acknowledged(ack(), ID, 6, 7, 999), "future ACK retains chunks");
    check(
        !BlockMeshProtocol.acknowledged(ack(), ID, 7, 7, 1000),
        "new geometry requires fresh upload");
    check(
        !BlockMeshProtocol.acknowledged(ack(), ID, 6, 8, 1000), "new atlas requires fresh upload");
    for (int offset :
        new int[] {0, 4, 16, 20, 24, 32, 36, 40, 56, 64, 72, 76, 80, 84, 88, 92, 96, 104, 127}) {
      var changed = ack();
      changed[offset] ^= 2;
      check(
          !BlockMeshProtocol.acknowledged(changed, ID, 6, 7, 1000),
          "reject identity/reserved byte " + offset);
    }
    var odd = ack();
    odd[8] = 3;
    check(!BlockMeshProtocol.acknowledged(odd, ID, 6, 7, 1000), "torn ACK");
    check(!BlockMeshProtocol.acknowledged(new byte[127], ID, 6, 7, 1000), "truncated ACK");
    var white = new byte[1024];
    Arrays.fill(white, (byte) 255);
    check(BlockMeshProtocol.renderedLightmap(white), "actual opaque lightmap is ready");
    check(
        !BlockMeshProtocol.renderedLightmap(new byte[1024]),
        "unrendered zeroed lightmap cannot make initial blocks black");
    var dark = new byte[1024];
    for (int i = 3; i < dark.length; i += 4) dark[i] = (byte) 255;
    check(
        BlockMeshProtocol.renderedLightmap(dark),
        "genuine opaque darkness is preserved rather than artificially brightened");
    dark[511] = 0;
    check(
        !BlockMeshProtocol.renderedLightmap(dark),
        "partial lightmap render cannot seed a new mesh");
    check(
        BlockMeshProtocol.litRgba(0xff123456, 0, white) == 0xff563412,
        "ARGB to LE RGBA keeps actual tint/shade");
    var ramp = new byte[1024];
    for (int y = 0; y < 16; y++)
      for (int x = 0; x < 16; x++) {
        int p = (y * 16 + x) * 4;
        ramp[p] = (byte) (x * 16);
        ramp[p + 1] = (byte) (y * 16);
        ramp[p + 2] = (byte) 255;
        ramp[p + 3] = (byte) 255;
      }
    check(
        BlockMeshProtocol.litRgba(0xffffffff, 8 | (24 << 16), ramp) == 0xffff1808,
        "vanilla lightmap bilinear coordinates");
    check(
        BlockMeshProtocol.litRgba(0xffffffff, 65535 | (65535 << 16), ramp) == 0xfffff0f0,
        "lightmap sampling clamps edge");
    rejects(() -> BlockMeshProtocol.litRgba(-1, 0, new byte[16]), "lightmap size");
    var area = new BlockMeshCoverage(-2, 3, 8, -4, 19);
    check(
        area.contains(-10, -4, -5) && area.contains(6, 19, 11),
        "all render-area corners including negative coordinates");
    check(
        !area.contains(-11, 0, 0) && !area.contains(0, 20, 0),
        "no suppression outside full coverage");
    check(new BlockMeshCoverage(0, 0, 32, -4, 19).radius() == 32, "32-chunk render distance");
    rejects(
        () -> new BlockMeshCoverage(0, 0, 35, -4, 19), "radius beyond the margin cap falls back");
    rejects(() -> new BlockMeshCoverage(0, 0, 32, 0, 63), "section work cap falls back");
    rejects(() -> new BlockMeshCoverage(0, 0, 8, 24, 23), "invalid vertical range");
    handoff();
    geometryHandoff();
    lightingAndStreaming();
    prebuiltStreamingCoverage();
    ackContention();
    System.out.println(
        "Block mesh conformance: "
            + checks
            + " checks passed (wire/coverage/lightmap/handoff only; no live renderer claim).");
  }

  private static void lightingAndStreaming() {
    var changes = new BlockMeshChanges();
    var complete = changes.version();
    var policy = new BlockMeshHandoff();
    var first = new BlockMeshHandoff.Revision(30, 3, 10);
    policy.geometry(first);
    policy.select(first);
    // A slow propagated-light rebuild lasts longer than the structural grace.
    // Its existing complete faces remain visible while colors are recomputed.
    for (int update = 1; update <= 6; update++) {
      changes.lightingChanged();
      check(!changes.current(complete), "propagated lighting still schedules refresh " + update);
      check(
          changes.withinGrace(complete, update * 600_000_000L),
          "lighting alone cannot expire complete resident geometry " + update);
      check(
          first.equals(policy.select(first)),
          "fresh resident ACK remains usable during light work " + update);
    }
    var lit = changes.version();
    check(changes.current(lit), "new shaded snapshot catches latest lighting");
    changes.geometryChanged(4_000_000_000L);
    check(
        !changes.geometryCurrent(lit) && !changes.current(lit),
        "mining still invalidates complete faces");
    check(changes.withinGrace(lit, 4_000_000_000L), "actual edit starts its own deadline");
    changes.lightingChanged();
    check(
        changes.withinGrace(lit, 4_000_000_000L + BlockMeshChanges.GRACE_NANOS)
            && !changes.withinGrace(lit, 4_000_000_001L + BlockMeshChanges.GRACE_NANOS),
        "lighting during mining cannot prolong an undisplayed edit");
    // Continuous edits (flowing water, fire) that keep being displayed never expire.
    var streaming = new BlockMeshChanges();
    var shown = streaming.version();
    for (long t = 0; t < 10_000_000_000L; t += 50_000_000L) {
      streaming.geometryChanged(t);
      check(streaming.withinGrace(shown, t + 40_000_000L), "kept-up edit stays within grace " + t);
      if ((t / 50_000_000L) % 2 == 1) {
        shown = streaming.version();
        streaming.displayed(shown);
      }
    }
    check(
        streaming.staleNanos(shown, 20_000_000_000L) == 0, "displayed snapshot has no stale edit");
    var lagging = new BlockMeshChanges();
    var old = lagging.version();
    for (long t = 0; t <= 1_500_000_000L; t += 50_000_000L) lagging.geometryChanged(t);
    check(
        !lagging.withinGrace(old, 1_500_000_000L),
        "a mesh that never catches up still falls back after the grace");
    lagging.displayed(lagging.version());
    check(lagging.withinGrace(lagging.version(), 99_000_000_000L), "catching up clears all debt");
    var folded = new BlockMeshChanges();
    var base = folded.version();
    for (long t = 0; t < 5000; t++) folded.geometryChanged(t);
    check(folded.staleNanos(base, 10_000) == 10_000, "overflowed queue keeps the oldest time");
    check(folded.staleNanos(null, 0) == Long.MAX_VALUE, "missing snapshot is never fresh");
    policy.invalidate();
    check(
        policy.select(first) == null, "resource/world invalidation still revokes shaded residents");
    var rebuilt = changes.version();
    check(
        changes.current(rebuilt) && changes.geometryCurrent(rebuilt),
        "rebuilt block snapshot catches both revisions");
    var area = new BlockMeshCoverage(-2, 3, 8, -4, 19);
    check(
        area.intersectsChunkNeighborhood(-11, -6) && area.intersectsChunkNeighborhood(7, 12),
        "adjacent streamed chunks can change border faces");
    check(
        !area.intersectsChunkNeighborhood(-12, 3) && !area.intersectsChunkNeighborhood(-2, 13),
        "unrelated chunk streaming cannot force a complete rebuild");
    var limit = new BlockMeshCoverage(1875000, -1875000, 34, 0, 0);
    check(
        !limit.intersectsChunkNeighborhood(Integer.MIN_VALUE, Integer.MAX_VALUE),
        "streaming coverage arithmetic cannot overflow near coordinate limits");
  }

  private static void prebuiltStreamingCoverage() {
    var view = new BlockMeshCoverage(-2, 3, 8, -4, 19);
    var built = view.withStreamingMargin();
    check(
        built.radius() == 8 + BlockMeshCoverage.STREAMING_MARGIN && built.contains(view),
        "bounded prebuilt border covers original vanilla view");
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        check(
            built.contains(
                new BlockMeshCoverage(
                    view.centerX() + dx,
                    view.centerZ() + dz,
                    view.radius(),
                    view.minY(),
                    view.maxY())),
            "two-section streaming steps retain complete coverage " + dx + "," + dz);
    check(
        !built.contains(new BlockMeshCoverage(1, 3, 8, -4, 19)),
        "three-section move cannot hide an uncovered entering strip");
    for (int distance : new int[] {2, 12, 16, 24, 32}) {
      var vanilla = new BlockMeshCoverage(40, -7, distance, -4, 19);
      var prebuilt = vanilla.withStreamingMargin();
      check(
          prebuilt.radius() == distance + BlockMeshCoverage.STREAMING_MARGIN,
          "render distance " + distance + " keeps the full streaming margin");
      check(
          prebuilt.contains(new BlockMeshCoverage(41, -8, distance, -4, 19)),
          "render distance " + distance + " survives a diagonal chunk crossing");
    }
    check(
        !built.contains(new BlockMeshCoverage(-2, 3, 8, -5, 19)),
        "new lower world sections require coverage");
    check(
        !built.contains(new BlockMeshCoverage(-2, 3, 8, -4, 20)),
        "new upper world sections require coverage");
    check(
        !built.contains(new BlockMeshCoverage(-2, 3, 11, -4, 19)),
        "larger render distance cannot borrow insufficient margin");
    check(!built.contains(null), "missing coverage grants no suppression");
    var cap = new BlockMeshCoverage(0, 0, BlockMeshCoverage.MAX_RADIUS, -4, 19);
    check(cap.withStreamingMargin().equals(cap), "maximum supported radius stays bounded");
    var heightCap = new BlockMeshCoverage(0, 0, 21, 0, 63);
    var reduced = heightCap.withStreamingMargin();
    check(
        reduced.radius() == 22
            && (2L * reduced.radius() + 1) * (2L * reduced.radius() + 1) * 64
                <= BlockMeshCoverage.MAX_SECTIONS,
        "margin shrinks rather than exceeding total section work budget");
    var opposite = new BlockMeshCoverage(1875000, -1875000, 0, 0, 0);
    check(
        !opposite.contains(new BlockMeshCoverage(-1875000, 1875000, 0, 0, 0)),
        "distant relocation cannot overflow containment arithmetic");
    var next = new BlockMeshCoverage(-1, 3, 8, -4, 19).withStreamingMargin();
    check(
        next.contains(new BlockMeshCoverage(1, 3, 8, -4, 19))
            && !built.contains(new BlockMeshCoverage(1, 3, 8, -4, 19)),
        "acknowledged next border advances coverage rather than granting indefinite old ownership");
  }

  private static void handoff() {
    var policy = new BlockMeshHandoff();
    var first = new BlockMeshHandoff.Revision(6, 7, 4);
    var second = new BlockMeshHandoff.Revision(7, 7, 4);
    var third = new BlockMeshHandoff.Revision(8, 7, 4);
    check(
        policy.select(first) == null && !policy.canPublishColor(),
        "unsolicited upload ACK cannot establish coverage");
    policy.geometry(first);
    check(
        policy.select(null) == null && !policy.canPublishColor(),
        "new geometry waits for its own ACK");
    check(
        first.equals(policy.select(BlockMeshProtocol.acknowledgement(ack(), ID, 1000)))
            && policy.canPublishColor(),
        "validated bootstrap ACK establishes resident");
    rejects(
        () -> policy.color(new BlockMeshHandoff.Revision(7, 8, 4)),
        "new atlas cannot be a recoloring");
    rejects(
        () -> policy.color(new BlockMeshHandoff.Revision(7, 7, 5)),
        "new world/session cannot be a recoloring");
    rejects(() -> policy.color(first), "revision cannot repeat");
    policy.color(second);
    check(!policy.canPublishColor(), "one pending upload bounds the candidate queue");
    rejects(() -> policy.color(third), "coalesce further colors until candidate ACK");
    for (int frame = 0; frame < 3; frame++)
      check(
          first.equals(policy.select(first)),
          "resident remains displayed throughout color upload frame " + frame);
    check(
        second.equals(policy.select(second)) && policy.canPublishColor(),
        "candidate ACK promotes atomically without a missing scene");
    byte[] published = {1, 2, 3}, latest = {1, 5, 3};
    check(
        !BlockMeshHandoff.payloadChanged(published, published.clone()),
        "equal vertex colors skip publication despite lightmap changes");
    check(
        BlockMeshHandoff.payloadChanged(published, latest),
        "latest coalesced colors require one upload");
    policy.color(third);
    check(
        second.equals(policy.select(second)),
        "coalesced upload still retains newly promoted resident");
    check(third.equals(policy.select(third)), "next color candidate promotes normally");
    check(
        policy.select(BlockMeshProtocol.acknowledgement(ack(), ID, 1251)) == null
            && !policy.canPublishColor(),
        "expired ACK stops suppression and new color uploads");
    check(third.equals(policy.select(third)), "fresh latest ACK can recover after loss");
    check(
        policy.select(new BlockMeshHandoff.Revision(99, 7, 4)) == null,
        "unknown revision cannot preserve suppression");
    check(third.equals(policy.select(third)), "exact latest ACK recovers from unknown revision");
    var newGeometry = new BlockMeshHandoff.Revision(9, 7, 4);
    policy.geometry(newGeometry);
    check(policy.select(third) == null, "old geometry ACK cannot hide a placement or removal");
    check(
        newGeometry.equals(policy.select(newGeometry)),
        "replacement geometry needs its own exact ACK");
    policy.invalidate();
    check(
        policy.select(newGeometry) == null && !policy.canPublishColor(),
        "context/resource invalidation discards all prior ownership");
    for (int offset : new int[] {56, 64}) {
      var malformed = ack();
      ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, 0);
      check(
          BlockMeshProtocol.acknowledgement(malformed, ID, 1000) == null,
          "nonpositive ACK revision rejected at " + offset);
    }
    var inactive = ack();
    ByteBuffer.wrap(inactive).order(ByteOrder.LITTLE_ENDIAN).putInt(36, 0);
    check(
        BlockMeshProtocol.acknowledgement(inactive, ID, 1000) == null,
        "inactive ACK does not retain a resident");
    var negativeTime = ack();
    ByteBuffer.wrap(negativeTime).order(ByteOrder.LITTLE_ENDIAN).putLong(48, -1);
    check(
        BlockMeshProtocol.acknowledgement(negativeTime, ID, 0) == null,
        "negative publisher time rejected");
    rejects(() -> new BlockMeshHandoff.Revision(0, 7, 4), "invalid policy revision rejected");
  }

  private static void geometryHandoff() {
    var policy = new BlockMeshHandoff();
    var before = new BlockMeshHandoff.Revision(20, 2, 9);
    var removed = new BlockMeshHandoff.Revision(21, 2, 9);
    var replaced = new BlockMeshHandoff.Revision(22, 2, 9);
    var changes = new BlockMeshChanges();
    policy.geometry(before);
    policy.select(before);
    var shown = changes.version();
    changes.geometryChanged(100);
    check(changes.withinGrace(shown, 100), "same-area removal starts bounded old-geometry grace");
    policy.update(removed);
    check(
        before.equals(policy.select(before)),
        "resident remains visible while removed-block mesh uploads");
    check(!policy.canPublishColor(), "rapid placement waits behind the one removal candidate");
    rejects(
        () -> policy.update(replaced),
        "second geometry candidate cannot overwrite unacknowledged removal");
    changes.geometryChanged(400_000_100L);
    check(changes.withinGrace(shown, 400_000_100L), "rapid edits retain original grace deadline");
    check(removed.equals(policy.select(removed)), "removed geometry is promoted by its exact ACK");
    policy.update(replaced);
    check(
        removed.equals(policy.select(removed)),
        "coalesced replacement keeps the acknowledged removal mesh");
    long deadline = 100 + BlockMeshChanges.GRACE_NANOS;
    check(changes.withinGrace(shown, deadline), "last allowed stale geometry instant");
    check(
        !changes.withinGrace(shown, deadline + 1),
        "an edit never displayed cannot keep old geometry past the grace");
    policy.invalidate();
    check(policy.select(replaced) == null, "timeout fallback discards pending ownership");
    policy.geometry(replaced);
    policy.select(replaced);
    shown = changes.version();
    changes.displayed(shown);
    check(
        changes.withinGrace(shown, deadline + 2), "current displayed geometry clears old deadline");
    changes.geometryChanged(deadline + 3);
    check(
        changes.withinGrace(shown, deadline + 3 + BlockMeshChanges.GRACE_NANOS),
        "later independent edit starts a new bounded window");
    policy.invalidate();
    check(
        !policy.hasLatest() && policy.select(replaced) == null,
        "coverage or session replacement cannot retain old geometry");
    var empty =
        BlockMeshProtocol.header(
            BlockMeshProtocol.MESH_MAGIC, ID, 1000, 23, 2, 0, 24, 0, 0, 0, 0, true);
    check(
        ByteBuffer.wrap(empty).order(ByteOrder.LITTLE_ENDIAN).getInt(72) == 0,
        "final block removal publishes complete zero-vertex candidate");
    var finalRemoval = new BlockMeshHandoff.Revision(23, 2, 9);
    policy.geometry(replaced);
    policy.select(replaced);
    policy.update(finalRemoval);
    check(
        finalRemoval.equals(policy.select(finalRemoval)),
        "zero-vertex snapshot uses the same atomic replacement ACK");
    byte[] geometry = {1, 2, 3, 4};
    check(
        !BlockMeshHandoff.payloadChanged(geometry, geometry.clone()) && policy.canPublishColor(),
        "redundant propagated-light snapshot skips upload while keeping current resident");
  }

  private static void ackContention() {
    var cache = new BlockMeshProtocol.AcknowledgementCache();
    check(
        cache.contended(ID, 1000) == null,
        "contention without coherent history grants no ownership");
    var expected = new BlockMeshHandoff.Revision(6, 7, 4);
    byte[] sample = ack();
    check(
        expected.equals(cache.accept(sample, ID, 1000)),
        "fresh coherent ACK establishes cached lease");
    sample[36] = 0;
    check(
        expected.equals(cache.contended(ID, 1100)),
        "cache retains immutable bytes across transient writer contention");
    check(
        expected.equals(cache.contended(ID, 1250)),
        "last allowed cached ACK instant uses original stamp");
    check(cache.contended(ID, 1251) == null, "repeated contention cannot refresh a250ms lease");
    cache.accept(ack(), ID, 1000);
    check(
        cache.accept(sample, ID, 1001) == null && cache.contended(ID, 1002) == null,
        "coherent inactive ACK revokes cache immediately");
    cache.accept(ack(), ID, 1000);
    var invalid = ack();
    invalid[104] = 1;
    check(
        cache.accept(invalid, ID, 1001) == null && cache.contended(ID, 1002) == null,
        "coherent invalid ACK revokes cache immediately");
    cache.accept(ack(), ID, 1000);
    var wrong = ack();
    wrong[20] = 33;
    check(
        cache.accept(wrong, ID, 1001) == null && cache.contended(ID, 1002) == null,
        "coherent identity mismatch revokes cache immediately");
    cache.accept(ack(), ID, 1000);
    var other = new BlockMeshProtocol.Identity(11, 22, 3, 0xffffffffL, 5, 5);
    check(
        cache.contended(other, 1001) == null && cache.contended(ID, 1002) == null,
        "context replacement clears old cached ownership");
    cache.accept(ack(), ID, 1000);
    cache.clear();
    check(cache.contended(ID, 1001) == null, "close or explicit invalidation clears cached lease");
    cache.accept(ack(), ID, 1000);
    check(cache.contended(ID, 999) == null, "clock rollback cannot reuse future-dated cache");
  }
}
