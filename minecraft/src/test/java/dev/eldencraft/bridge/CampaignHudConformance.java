package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;

/** Boss phase/health recovery, frame-rate-independent trails and bounded presentation settings. */
public final class CampaignHudConformance {
  private static int checks;

  public static int verify() throws Exception {
    checks = 0;
    var trail = new HudDamageTrail();
    near(trail.update("boss-a", 1, 0, .6, .35), 1, "initial bar has no fabricated loss");
    near(
        trail.update("boss-a", .8, 100_000_000L, .6, .35),
        1,
        "damage preserves previous HP in yellow");
    near(trail.update("boss-a", .8, 600_000_000L, .6, .35), 1, "loss segment holds briefly");
    near(
        trail.update("boss-a", .8, 800_000_000L, .6, .35),
        .965,
        "only elapsed time after hold drains");
    near(
        trail.update("boss-a", .8, 1_800_000_000L, .6, .35),
        .8,
        "trail never drains below current HP");
    near(
        trail.update("boss-a", .95, 1_900_000_000L, .6, .35),
        .95,
        "healing resets old damage feedback");
    near(
        trail.update("boss-b", .5, 2_000_000_000L, .6, .35),
        .5,
        "boss/phase replacement resets history");
    trail.update("boss-b", .3, 2_100_000_000L, .6, .35);
    near(
        trail.update("boss-b", .3, 4_200_000_001L, .6, .35),
        .3,
        "stale render gap cannot replay old loss");
    near(trail.update("boss-b", .1, 1L, .6, .35), .1, "clock reset establishes a baseline");
    near(
        simulate(25_000_000L),
        simulate(250_000_000L),
        "trail duration is independent of frame rate");
    trail.clear();
    near(trail.update("boss-a", .4, 1L, .6, .35), .4, "hidden HUD clears feedback");
    check(HudDamageTrail.staminaTop(240, 20, 20, 0, 9, 0) == 179, "one-row heart layout");
    check(
        HudDamageTrail.staminaTop(240, 92, 70, 0, 9, 0) == 151,
        "five heart rows use compressed spacing");
    check(
        HudDamageTrail.staminaTop(240, 20, 20, 20, 9, 0) == 169, "absorption occupies another row");
    check(
        HudDamageTrail.staminaTop(240, 92, 70, 0, 32, 0) + 32 + 1 == 161,
        "tall stamina frame preserves clearance above armor at y=163");
    check(
        HudDamageTrail.staminaTop(64, 1024, 1024, 0, 9, 0) == 14,
        "tiny viewport keeps stamina visible");
    var defaults = CampaignHudConfig.parse(new JsonObject());
    check(
        defaults.bossBar().enabled()
            && defaults.hideAchievementPopups()
            && defaults.hideRecipePopups()
            && defaults.hideTutorialPopups(),
        "older JSON enables requested HUD");
    var custom =
        JsonParser.parseString(
                "{\"hud\":{\"stamina\":{\"fill\":\"#123456\"},\"bossBar\":{\"damageHoldSeconds\":0},\"hideAchievementPopups\":false}}")
            .getAsJsonObject();
    var parsed = CampaignHudConfig.parse(custom);
    check(
        parsed.stamina().fill() == 0xff123456
            && parsed.bossBar().damageHoldSeconds() == 0
            && !parsed.hideAchievementPopups(),
        "partial overrides preserve other defaults");
    for (String invalid :
        new String[] {
          "{\"stamina\":{\"width\":1}}",
          "{\"bossBar\":{\"damageTrailPerSecond\":0}}",
          "{\"stamina\":{\"fill\":\"#zzzzzz\"}}",
          "{\"bossBar\":{\"height\":5.5}}",
          "{\"hideAchievementPopups\":1}",
          "{\"bossBar\":{\"damageHoldSeconds\":1e309}}"
        }) {
      try {
        CampaignHudConfig.parse(
            JsonParser.parseString("{\"hud\":" + invalid + "}").getAsJsonObject());
        throw new AssertionError("Invalid HUD configuration accepted");
      } catch (IOException expected) {
        checks++;
      }
    }
    return checks;
  }

  private static double simulate(long step) {
    var trail = new HudDamageTrail();
    trail.update("boss", 1, 0, .6, .35);
    double value = trail.update("boss", .4, 100_000_000L, .6, .35);
    for (long time = 100_000_000L; time < 1_700_000_000L; ) {
      time = Math.min(1_700_000_000L, time + step);
      value = trail.update("boss", .4, time, .6, .35);
    }
    return value;
  }

  private static void near(double a, double b, String message) {
    check(Math.abs(a - b) < 1e-6, message);
  }

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
}
