package dev.eldencraft.bridge;

/** Timing/angle regressions for the F5 local avatar; real rendering still needs a live check. */
public final class HostAvatarConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  public static void main(String[] args) {
    var motion = new HostAvatarMotion();
    var initial = motion.update(10, 1000, 179, 20, 4.3f, true);
    check(initial.walkPhase() == 0 && initial.walkSpeed() == 0, "attach has no catch-up walk");
    var turning = motion.update(11, 1050, -179, 25, 4.3f, true);
    check(
        Math.abs(turning.headYaw()) < 2 && turning.pitch() == 25,
        "body takes short path across yaw wrap");
    check(turning.walkSpeed() > 0 && turning.walkPhase() > 0, "actual movement animates legs");
    var duplicate = motion.update(11, 1050, -179, 25, 4.3f, true);
    check(duplicate.equals(turning), "tick plus render cannot advance the same host frame twice");
    var jump = motion.update(12, 1100, -179, -30, 15, false);
    check(jump.walkSpeed() < turning.walkSpeed(), "airborne velocity cannot run the legs faster");
    var reverse = motion.update(13, 1150, 1, 0, 4.3f, true);
    check(Math.abs(reverse.headYaw()) <= 60, "large camera turn cannot twist head through body");
    var lost = motion.update(14, 3000, 90, 0, 0, true);
    check(
        lost.walkPhase() == 0 && lost.walkSpeed() == 0 && lost.bodyYaw() == 90,
        "stale gap resets animation");
    var restarted = motion.update(1, 3016, 0, 0, 5, true);
    check(
        restarted.walkPhase() == 0 && restarted.bodyYaw() == 0, "restarted publisher resets pose");
    motion.reset();
    check(
        motion.update(1, 3016, 45, 0, 0, true).bodyYaw() == 45, "new world cannot reuse old pose");
    var fast = new HostAvatarMotion();
    var slow = new HostAvatarMotion();
    fast.update(0, 1000, 0, 0, 4, true);
    slow.update(0, 1000, 0, 0, 4, true);
    HostAvatarMotion.Pose fastPose = null, slowPose = null;
    for (int i = 1; i <= 100; i++) fastPose = fast.update(i, 1000 + 10L * i, 0, 0, 4, true);
    for (int i = 1; i <= 20; i++) slowPose = slow.update(i, 1000 + 50L * i, 0, 0, 4, true);
    check(
        Math.abs(fastPose.walkSpeed() - slowPose.walkSpeed()) < .0001,
        "walking converges equally at 20 and 100 FPS");
    check(
        Math.abs(fastPose.walkPhase() - slowPose.walkPhase()) < .4,
        "walking cadence stays stable across render rates");
    System.out.println("Host avatar conformance: " + checks + " checks passed.");
  }
}
