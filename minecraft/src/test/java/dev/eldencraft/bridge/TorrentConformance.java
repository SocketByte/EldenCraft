package dev.eldencraft.bridge;

public final class TorrentConformance {
  private static int checks;

  private static void check(boolean v, String text) {
    checks++;
    if (!v) throw new AssertionError(text);
  }

  private static boolean near(double a, double b) {
    return Math.abs(a - b) < .001;
  }

  public static void main(String[] args) {
    check(TorrentPolicy.fresh(1000, 1000), "new mount observation is fresh");
    check(
        TorrentPolicy.fresh(1000, 1000 + TorrentPolicy.FRESH_NANOS),
        "a missed tick retains the original lease");
    check(
        !TorrentPolicy.fresh(1000, 1001 + TorrentPolicy.FRESH_NANOS),
        "mount observation expires without renewal");
    check(!TorrentPolicy.fresh(1001, 1000), "future mount timestamp rejected");
    check(!TorrentPolicy.fresh(0, 1000), "missing timestamp rejected");
    // Native BlockIds observed in host logs: area is the high byte.
    check(TorrentPolicy.allowedArea(0x3c2a2400L), "Limgrave overworld admits the whistle");
    check(TorrentPolicy.allowedArea(0x3d000000L), "Realm of Shadow overworld admits it");
    check(TorrentPolicy.allowedArea(0x0c020000L), "underground (m12) admits it");
    for (long block : new long[] {0x0a010000L, 0x0e000000L, 0x12000000L, 0x1e000000L, 0})
      check(!TorrentPolicy.allowedArea(block), "legacy dungeons and catacombs refuse it");
    check(!TorrentPolicy.allowedArea(-1), "unknown block refused");
    check(!TorrentPolicy.allowedArea(0x1_3c00_0000L), "out-of-range block refused");

    var ok = TorrentPolicy.summon(true, true, false, false, false, false, 0, false);
    check(ok == TorrentPolicy.Refusal.NONE, "grounded open world summon");
    check(
        TorrentPolicy.summon(false, true, false, false, false, false, 20, false)
            == TorrentPolicy.Refusal.AREA,
        "dungeon refusal names the area");
    check(
        TorrentPolicy.summon(true, false, false, false, false, false, 20, false)
            == TorrentPolicy.Refusal.AIRBORNE,
        "no summon mid-air");
    check(
        TorrentPolicy.summon(true, false, true, false, false, false, 20, false)
            == TorrentPolicy.Refusal.GLIDING,
        "gliding is named before airborne");
    check(
        TorrentPolicy.summon(true, true, false, true, false, false, 20, false)
            == TorrentPolicy.Refusal.LIQUID,
        "no summon in liquid");
    check(
        TorrentPolicy.summon(false, false, false, false, true, false, 20, false)
            == TorrentPolicy.Refusal.COOLDOWN,
        "cooldown swallows repeated presses silently");
    check(TorrentPolicy.Refusal.COOLDOWN.message.isEmpty(), "cooldown has no message");
    check(
        TorrentPolicy.summon(true, true, false, false, false, true, 5, false)
            == TorrentPolicy.Refusal.HUNGRY,
        "a fallen Torrent needs food");
    check(
        TorrentPolicy.summon(true, true, false, false, false, true, 6, false)
            == TorrentPolicy.Refusal.NONE,
        "exact revival cost is enough");
    check(
        TorrentPolicy.summon(true, true, false, false, false, true, 0, true)
            == TorrentPolicy.Refusal.NONE,
        "creative revives for free");

    check(
        near(TorrentPolicy.sharedDamage(1000, 800, 1000, 1000), 6),
        "a fifth of the rider's health costs a fifth of Torrent's");
    check(TorrentPolicy.sharedDamage(800, 900, 1000, 1000) == 0, "healing is not damage");
    check(TorrentPolicy.sharedDamage(1000, 900, 1000, 1200) == 0, "a new maximum is not damage");
    check(TorrentPolicy.sharedDamage(-1, 900, -1, 1000) == 0, "no baseline, no damage");
    check(near(TorrentPolicy.sharedDamage(500, -20, 1000, 1000), 15), "overkill clamps at zero");
    check(TorrentPolicy.sharedDamage(500, Float.NaN, 1000, 1000) == 0, "malformed HP ignored");

    check(near(TorrentPolicy.rested(10, 5_000_000_000L), 11), "rest heals while dismissed");
    check(TorrentPolicy.rested(29.9f, 60_000_000_000L) == 30, "rest caps at maximum");
    check(TorrentPolicy.rested(0, 600_000_000_000L) == 0, "a fallen Torrent never rests back");
    check(near(TorrentPolicy.RIDER_LIFT, .84375), "seat height matches native lift");

    var motion = new TorrentMotion();
    var first = motion.update(1, 1000, 0, 0, 90, 0, true);
    check(near(first.yaw(), 90) && first.walkSpeed() == 0, "a summon faces the rider's look");
    check(motion.update(1, 1000, 5, 5, 0, 10, true) == first, "same host frame is not replayed");
    var still = motion.update(2, 1050, .01, 0, -45, 0, true);
    check(near(still.yaw(), 90), "standing and looking around keeps the heading");
    TorrentMotion.Pose run = still;
    for (int n = 3; n < 40; n++)
      run = motion.update(n, 1000 + n * 50L, 0, (n - 2) * .55, 90, 11, true);
    check(Math.abs(run.yaw()) < 1, "galloping +Z turns Torrent to yaw 0");
    check(near(run.walkSpeed(), 1), "a gallop takes full strides");
    var air = run;
    for (int n = 40; n < 60; n++)
      air = motion.update(n, 1000 + n * 50L, 0, 20 + n * .55, 90, 11, false);
    check(air.walkSpeed() < .01, "legs settle in the air");
    var reset = motion.update(61, 9000, 0, 0, 180, 0, true);
    check(near(Math.abs(reset.yaw()), 180), "a long gap restarts from the look");
    System.out.println(
        "Torrent conformance: "
            + checks
            + " checks passed (policy and heading only; no live game).");
  }
}
