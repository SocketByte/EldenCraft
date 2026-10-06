package dev.eldencraft.bridge;

public final class FramePipelineConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  public static void main(String[] args) {
    check(FramePipeline.hostLimit(30) == 30, "preserve lower user limit");
    check(FramePipeline.hostLimit(60) == 60, "normal host target");
    check(FramePipeline.hostLimit(260) == 60, "never unlimited host capture");
    check(
        FramePipeline.freeSlot(new boolean[] {true, false, true}, 0) == 1,
        "busy head cannot block another free slot");
    check(FramePipeline.freeSlot(new boolean[] {false, true, true}, 2) == 0, "ring wraps");
    check(
        FramePipeline.freeSlot(new boolean[] {true, true, true}, 1) == -1,
        "full ring drops without overwrite");
    check(
        FramePipeline.freeSlot(new boolean[] {false, false, false}, 2) == 2,
        "available preferred slot");
    check(FramePipeline.publishable(3, 2, 100, 200, true), "fresh complete frame");
    check(
        !FramePipeline.publishable(2, 3, 100, 200, true),
        "late completion never rolls picture backward");
    check(!FramePipeline.publishable(3, 3, 100, 200, true), "duplicate completion not republished");
    check(
        !FramePipeline.publishable(4, 3, 100, 200, false),
        "old world cannot publish into new world");
    check(!FramePipeline.publishable(4, 3, 100, 99, true), "future capture rejected");
    check(FramePipeline.publishable(4, 3, 100, 250_000_099, true), "last fresh nanosecond");
    check(
        !FramePipeline.publishable(4, 3, 100, 250_000_100, true),
        "250 ms delayed completion discarded");
    var key = new FramePipeline.SceneKey(10, 20, 30, 40);
    check(
        FramePipeline.samePresentation(key, new FramePipeline.SceneKey(10, 20, 30, 40), 1, 1),
        "same scene may complete after a later camera sample");
    check(
        !FramePipeline.samePresentation(key, key, 1, 0),
        "pending F5 pixels cannot leak into first person");
    check(
        !FramePipeline.samePresentation(key, key, 1, 2),
        "rear pixels cannot publish after front-view switch");
    check(
        !FramePipeline.samePresentation(key, new FramePipeline.SceneKey(11, 20, 30, 40), 1, 1),
        "new host process rejects previous avatar");
    check(
        !FramePipeline.samePresentation(key, new FramePipeline.SceneKey(10, 21, 30, 40), 1, 1),
        "epoch transition rejects old world capture");
    check(
        !FramePipeline.samePresentation(key, new FramePipeline.SceneKey(10, 20, 31, 40), 1, 1),
        "map transition rejects old world capture");
    check(
        !FramePipeline.samePresentation(key, new FramePipeline.SceneKey(10, 20, 30, 41), 1, 1),
        "anchor transition rejects old world capture");
    check(
        !FramePipeline.samePresentation(key, null, 1, 1),
        "lost scene invalidates delayed body capture");
    check(
        FramePipeline.samePresentation(null, null, 0, 0),
        "ordinary non-scene overlay remains supported");
    check(FramePipeline.gpuSetReusable(0, 0), "never-written GPU set is free");
    check(!FramePipeline.gpuSetReusable(12, 11), "unacknowledged GPU set stays busy");
    check(
        FramePipeline.gpuSetReusable(12, 12) && FramePipeline.gpuSetReusable(12, 40),
        "copied or superseded GPU set is reusable");
    check(
        FramePipeline.gpuSet(new long[] {5, 9, 0}, 8, 0) == 0,
        "superseded set reused first from start");
    check(FramePipeline.gpuSet(new long[] {5, 9, 0}, 4, 0) == 2, "busy sets skipped");
    check(
        FramePipeline.gpuSet(new long[] {5, 9, 7}, 4, 1) == -1, "all busy falls back to readback");
    check(
        FramePipeline.gpuSet(new long[] {5, 9, 7}, 9, 2) == 2,
        "rotation starts at the preferred set");
    System.out.println(
        "Frame pipeline conformance: " + checks + " checks passed (bounded scheduling only).");
  }
}
