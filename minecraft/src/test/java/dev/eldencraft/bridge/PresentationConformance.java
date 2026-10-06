package dev.eldencraft.bridge;

import java.util.List;

/** Lifecycle checks for presentation only: neither sounds nor particles may create authority. */
public final class PresentationConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static WorldProtocol.Acknowledgement ack(long seq, int result, float delta) {
    return new WorldProtocol.Acknowledgement(seq, result, delta, "test");
  }

  private static final HostStepMotion.Context CONTEXT = new HostStepMotion.Context(1, 2, 3, 4);

  private static HostStepMotion.Sample step(long frame, long time, double x, boolean ground) {
    return new HostStepMotion.Sample(CONTEXT, frame, time, new WorldOrigin.Vec(x, 0, 0), ground);
  }

  public static void main(String[] args) {
    var feedback = new ConfirmedFeedback<String>();
    check(feedback.offer(1, 1, 1000, "first"), "queued real receipt");
    check(feedback.resolve(1, 1100, List.of()).isEmpty(), "no optimistic feedback");
    check(
        feedback.resolve(1, 1200, List.of(ack(1, 1, 5))).equals(List.of("first")),
        "positive accepted HP loss presents once");
    check(feedback.resolve(1, 1250, List.of(ack(1, 1, 5))).isEmpty(), "duplicate ACK is silent");
    feedback.offer(1, 2, 1300, "denied");
    check(feedback.resolve(1, 1400, List.of(ack(2, 2, 5))).isEmpty(), "denied hit is silent");
    check(
        feedback.resolve(1, 1450, List.of(ack(2, 1, 5))).isEmpty(),
        "terminal rejection cannot replay as success");
    feedback.offer(1, 3, 1500, "zero");
    check(
        feedback.resolve(1, 1550, List.of(ack(3, 1, 0))).isEmpty(),
        "accepted without actual HP loss is silent");
    feedback.offer(1, 4, 1600, "expired");
    check(feedback.resolve(1, 2601, List.of(ack(4, 1, 3))).isEmpty(), "stale success is silent");
    feedback.offer(1, 5, 3000, "old session");
    check(
        feedback.resolve(2, 3100, List.of(ack(5, 1, 3))).isEmpty(),
        "reconnect invalidates presentation");
    feedback.offer(2, 6, 3200, "clock");
    check(
        feedback.resolve(2, 3199, List.of(ack(6, 1, 3))).isEmpty(),
        "clock rollback invalidates feedback");
    for (int i = 0; i < 96; i++) feedback.offer(2, 10 + i, 3300, "bounded");
    check(!feedback.offer(2, 200, 3300, "overflow"), "bounded queue");
    feedback.clear();
    check(feedback.resolve(2, 3350, List.of(ack(10, 1, 3))).isEmpty(), "loss clears presentation");

    var motion = new HostStepMotion();
    check(motion.update(step(1, 1000, 0, true)) == null, "initial host pose is a baseline");
    var delta = motion.update(step(2, 1050, .2, true));
    check(
        delta != null && Math.abs(delta.x() - .2) < 1e-9 && delta.y() == 0,
        "actual continuous host displacement");
    check(
        motion.update(step(2, 1050, .2, true)) == null, "duplicate frame cannot repeat footsteps");
    check(motion.update(step(3, 1100, .4, false)) == null, "takeoff emits no step");
    check(motion.update(step(4, 1150, .6, true)) == null, "landing establishes grounded baseline");
    check(motion.update(step(5, 1200, .8, true)) != null, "grounded travel resumes");
    check(motion.update(step(6, 1600, 1, true)) == null, "stale gap has no catch-up sound");
    check(motion.update(step(7, 1650, 30, true)) == null, "teleport has no footsteps");
    check(
        motion.update(step(8, 1700, 30.2, true)) != null,
        "movement after teleport gets fresh delta");
    check(motion.update(step(2, 1750, 30.4, true)) == null, "frame rollback resets");
    check(
        motion.update(
                new HostStepMotion.Sample(
                    new HostStepMotion.Context(9, 2, 3, 4),
                    3,
                    1800,
                    new WorldOrigin.Vec(30.6, 0, 0),
                    true))
            == null,
        "publisher replacement resets");
    check(motion.update(null) == null, "loss releases baseline");
    check(motion.update(step(9, 1900, 31, true)) == null, "reacquisition does not replay travel");
    check(motion.update(step(10, 1950, 31, true)) == null, "stationary host emits no movement");
    var hands = new HostHandMotion();
    var first = hands.update(0, 179, 0);
    check(first.yaw() == first.bobYaw(), "hand attachment starts without a false swing");
    var wrap = hands.update(16_666_667, -179, 0);
    check(
        Math.abs(wrap.yaw() - wrap.bobYaw()) < 2.1, "yaw wrap cannot rotate hands by 360 degrees");
    var reverse = hands.update(33_333_334, 179, 0);
    check(Math.abs(reverse.yaw() - reverse.bobYaw()) < 2.1, "reverse wrap takes shortest turn");
    var thirty = new HostHandMotion();
    var sixty = new HostHandMotion();
    thirty.update(0, 0, 0);
    sixty.update(0, 0, 0);
    HostHandMotion.Pose a = null, b = null;
    for (int i = 1; i <= 30; i++) a = thirty.update(Math.round(i * 1e9 / 30), i * 3, 0);
    for (int i = 1; i <= 60; i++) b = sixty.update(Math.round(i * 1e9 / 60), i * 1.5f, 0);
    check(
        Math.abs(a.bobYaw() - b.bobYaw()) < .001,
        "continuous camera turn has equal sway at30/60FPS");
    var settled = sixty.update(1_050_000_000L, 90, 0);
    check(
        Math.abs((settled.yaw() - settled.bobYaw()) / (b.yaw() - b.bobYaw()) - .5) < .001,
        "vanilla half-per50ms settling");
    var gap = sixty.update(2_000_000_000L, -90, 30);
    check(
        gap.yaw() == gap.bobYaw() && gap.pitch() == gap.bobPitch(),
        "stale presentation gap does not replay camera sway");
    var rollback = sixty.update(1, -80, 10);
    check(rollback.yaw() == rollback.bobYaw(), "clock rollback resets hand lag");
    check(sixty.update(2, Float.NaN, 0) == null, "nonfinite camera releases hand presentation");
    check(sixty.update(3, 10, 0).bobYaw() == 10, "fresh reacquisition has clean baseline");
    sixty.reset();
    check(sixty.update(4, -160, 0).bobYaw() == -160, "identity reset does not retain old turn");
    System.out.println(
        "Presentation conformance: "
            + checks
            + " checks passed (feedback and movement lifecycle only).");
  }
}
