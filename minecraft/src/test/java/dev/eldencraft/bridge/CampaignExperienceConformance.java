package dev.eldencraft.bridge;

/**
 * Saved checkpoint recovery against cumulative confirmed kills, independent of the game process.
 */
public final class CampaignExperienceConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Runnable operation, String message) {
    checks++;
    try {
      operation.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError(message);
  }

  public static int verify() {
    checks = 0;
    var baseline = CampaignExperience.advance(null, 17, 9, 120);
    check(baseline.award() == 0 && baseline.changed(), "attachment ignores historical native XP");
    var saved = CampaignExperience.parse(baseline.cursor().tag());
    check(saved.equals(baseline.cursor()), "saved checkpoint round trip");
    var repeated = CampaignExperience.advance(saved, 17, 9, 120);
    check(
        !repeated.changed() && repeated.award() == 0,
        "duplicate snapshot performs no save or award");
    var kills = CampaignExperience.advance(saved, 17, 12, 146);
    check(
        kills.award() == 26 && kills.changed(),
        "batched confirmed kills award cumulative difference");
    check(
        kills.cursor().sequence() == 12 && kills.cursor().total() == 146, "batch receipt advances");
    var recovered = CampaignExperience.parse(kills.cursor().tag());
    check(
        CampaignExperience.advance(recovered, 17, 12, 146).award() == 0,
        "saved reload cannot replay XP");
    var zero = CampaignExperience.advance(recovered, 17, 13, 146);
    check(zero.award() == 0 && zero.changed(), "zero-value kill still acknowledges its sequence");
    var newSession = CampaignExperience.advance(recovered, 18, 3, 50);
    check(
        newSession.award() == 0 && newSession.cursor().session() == 18,
        "new native session establishes baseline");
    var newKill = CampaignExperience.advance(newSession.cursor(), 18, 4, 58);
    check(newKill.award() == 8, "kills after new session baseline are rewarded");
    var large =
        CampaignExperience.advance(new CampaignExperience.Cursor(17, 0, 0), 17, 5, 2_500_040);
    check(
        large.award() == 1_000_000 && large.cursor().total() == 1_000_000,
        "large pending batch is bounded");
    check(large.cursor().sequence() == 0, "partial batch retains recovery sequence");
    var partialReload = CampaignExperience.parse(large.cursor().tag());
    var second = CampaignExperience.advance(partialReload, 17, 5, 2_500_040);
    check(second.award() == 1_000_000, "saved partial batch continues without replay");
    var third = CampaignExperience.advance(second.cursor(), 17, 5, 2_500_040);
    check(
        third.award() == 500_040 && third.cursor().sequence() == 5,
        "complete batch advances sequence exactly once");
    check(
        large.award() + second.award() + third.award() == 2_500_040,
        "bounded installments lose no earned XP");
    check(
        !CampaignExperience.advance(third.cursor(), 17, 5, 2_500_040).changed(),
        "completed batch is idempotent");
    rejects(() -> CampaignExperience.advance(saved, 17, 8, 130), "sequence regression rejected");
    rejects(() -> CampaignExperience.advance(saved, 17, 10, 119), "cumulative regression rejected");
    rejects(
        () -> CampaignExperience.advance(saved, 17, 9, 121),
        "XP change without confirmed kill rejected");
    rejects(() -> CampaignExperience.advance(null, 0, 0, 0), "zero session rejected");
    rejects(() -> CampaignExperience.advance(null, 1, -1, 0), "negative sequence rejected");
    rejects(() -> CampaignExperience.advance(null, 1, 0, -1), "negative total rejected");
    rejects(
        () -> CampaignExperience.advance(null, 1, 0, 9_000_000_000_000_001L),
        "excessive counter rejected");
    rejects(
        () -> CampaignExperience.parse("eldencraft.xp.17.9.120.0"),
        "extra receipt fields rejected");
    rejects(
        () -> CampaignExperience.parse("eldencraft.xp.17.invalid.120"),
        "malformed receipt rejected");
    rejects(() -> CampaignExperience.parse("foreign.17.9.120"), "foreign marker rejected");
    return checks;
  }

  public static void main(String[] args) {
    System.out.println("CampaignExperienceConformance: " + verify() + " checks passed");
  }
}
