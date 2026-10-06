package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;

/** Presentation settings with defaults for existing campaign rule files. */
public record CampaignHudConfig(
    StaminaBar stamina,
    BossBar bossBar,
    boolean hideAchievementPopups,
    boolean hideRecipePopups,
    boolean hideTutorialPopups) {
  public record StaminaBar(
      boolean enabled,
      int width,
      int height,
      int offsetY,
      boolean showNumbers,
      double trailHoldSeconds,
      double trailPerSecond,
      double recoveryHighlightSeconds,
      int fill,
      int exhausted,
      int trail,
      int border,
      int background,
      int text) {}

  public record BossBar(
      boolean enabled,
      int width,
      int height,
      int top,
      int spacing,
      boolean showNumbers,
      double damageHoldSeconds,
      double damageTrailPerSecond,
      int fillTint,
      int trailTint,
      int border,
      int background,
      int text) {}

  public static CampaignHudConfig defaults() {
    return new CampaignHudConfig(
        new StaminaBar(
            true,
            182,
            9,
            0,
            true,
            .12,
            1.75,
            .12,
            0xff75b946,
            0xffc18b46,
            0xffbcc475,
            0xff726c47,
            0xff172015,
            0xffdbe7cd),
        new BossBar(
            true,
            320,
            10,
            12,
            8,
            false,
            .6,
            .35,
            0xffc2443b,
            0xffffe39a,
            0xffa08a55,
            0xff281916,
            0xffece2c6),
        true,
        true,
        true);
  }

  public static CampaignHudConfig parse(JsonObject campaign) throws IOException {
    var d = defaults();
    var hud = object(campaign, "hud");
    var s = object(hud, "stamina");
    var b = object(hud, "bossBar");
    var ds = d.stamina;
    var db = d.bossBar;
    return new CampaignHudConfig(
        new StaminaBar(
            bool(s, "enabled", ds.enabled),
            integer(s, "width", ds.width, 48, 1024),
            integer(s, "height", ds.height, 5, 32),
            integer(s, "offsetY", ds.offsetY, -256, 256),
            bool(s, "showNumbers", ds.showNumbers),
            number(s, "trailHoldSeconds", ds.trailHoldSeconds, 0, 5),
            number(s, "trailPerSecond", ds.trailPerSecond, .01, 20),
            number(s, "recoveryHighlightSeconds", ds.recoveryHighlightSeconds, 0, 5),
            color(s, "fill", ds.fill),
            color(s, "exhausted", ds.exhausted),
            color(s, "trail", ds.trail),
            color(s, "border", ds.border),
            color(s, "background", ds.background),
            color(s, "text", ds.text)),
        new BossBar(
            bool(b, "enabled", db.enabled),
            integer(b, "width", db.width, 96, 1024),
            integer(b, "height", db.height, 5, 32),
            integer(b, "top", db.top, 10, 256),
            integer(b, "spacing", db.spacing, 2, 64),
            bool(b, "showNumbers", db.showNumbers),
            number(b, "damageHoldSeconds", db.damageHoldSeconds, 0, 5),
            number(b, "damageTrailPerSecond", db.damageTrailPerSecond, .01, 20),
            color(b, "fillTint", db.fillTint),
            color(b, "trailTint", db.trailTint),
            color(b, "border", db.border),
            color(b, "background", db.background),
            color(b, "text", db.text)),
        bool(hud, "hideAchievementPopups", d.hideAchievementPopups),
        bool(hud, "hideRecipePopups", d.hideRecipePopups),
        bool(hud, "hideTutorialPopups", d.hideTutorialPopups));
  }

  private static JsonObject object(JsonObject parent, String key) throws IOException {
    if (!parent.has(key)) return new JsonObject();
    if (!parent.get(key).isJsonObject()) throw new IOException("HUD " + key + " must be an object");
    return parent.getAsJsonObject(key);
  }

  private static boolean bool(JsonObject j, String key, boolean fallback) throws IOException {
    if (!j.has(key)) return fallback;
    var v = j.get(key);
    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
      throw new IOException("HUD " + key + " must be boolean");
    return v.getAsBoolean();
  }

  private static int integer(JsonObject j, String key, int fallback, int min, int max)
      throws IOException {
    return j.has(key) ? (int) JsonWire.integer(j.get(key), min, max) : fallback;
  }

  private static double number(JsonObject j, String key, double fallback, double min, double max)
      throws IOException {
    if (!j.has(key)) return fallback;
    var v = j.get(key);
    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IOException("HUD " + key + " must be numeric");
    double n = v.getAsDouble();
    if (!Double.isFinite(n) || n < min || n > max)
      throw new IOException("HUD " + key + " out of range");
    return n;
  }

  private static int color(JsonObject j, String key, int fallback) throws IOException {
    if (!j.has(key)) return fallback;
    String color = JsonWire.string(j.get(key));
    if (!color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
      throw new IOException("HUD " + key + " must be #RRGGBB or #AARRGGBB");
    long n = Long.parseLong(color.substring(1), 16);
    return (int) (color.length() == 7 ? n | 0xff000000L : n);
  }
}
