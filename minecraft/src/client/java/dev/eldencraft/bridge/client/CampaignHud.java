package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.CampaignHudConfig;
import dev.eldencraft.bridge.HudDamageTrail;
import dev.eldencraft.bridge.client.mixin.HudHeartLayoutAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** The Minecraft stamina overlay uses the same units as boss capacity progression. */
public final class CampaignHud {
  private static final HudDamageTrail TRAIL = new HudDamageTrail();
  private static long recoveryUntil;
  private static final long GUARD_BREAK_NANOS = 900_000_000L;

  private CampaignHud() {}

  public static void initialize() {
    HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath("eldencraft_bridge", "stamina"), CampaignHud::extract);
  }

  private static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    var client = Minecraft.getInstance();
    var settings = CampaignConfig.current().hud.stamina();
    if (!CampaignCombat.active(client.player)
        || client.gui.hud.isHidden()
        || client.gui.screen() != null
        || !settings.enabled()) {
      TRAIL.clear();
      recoveryUntil = 0;
      return;
    }
    double value = CampaignCombat.stamina(client.player),
        maximum = CampaignCombat.maximum(client.player);
    if (maximum <= 0 || gui.guiWidth() < 48 || gui.guiHeight() < 64) return;
    int width = Math.min(settings.width(), gui.guiWidth() - 24), height = settings.height();
    int left = (gui.guiWidth() - width) / 2;
    int top = staminaTop(client, gui, settings);
    double fraction = Math.clamp(value / maximum, 0, 1);
    long now = System.nanoTime();
    if (fraction > TRAIL.current())
      recoveryUntil = now + (long) (settings.recoveryHighlightSeconds() * 1_000_000_000L);
    else if (fraction < TRAIL.current()) recoveryUntil = 0;
    boolean recovering = now < recoveryUntil;
    var host = CampaignBridge.snapshot();
    if (host == null) {
      TRAIL.clear();
      recoveryUntil = 0;
      return;
    }
    double trail =
        TRAIL.update(
            host.session() + ":" + client.player.getUUID() + ":" + maximum,
            fraction,
            now,
            settings.trailHoldSeconds(),
            settings.trailPerSecond());
    // A guard break shakes the bar and flashes it in the exhausted color.
    long broken = CampaignCombat.guardBreakNanos(), sinceBreak = now - broken;
    double flash =
        broken != 0 && sinceBreak >= 0 && sinceBreak < GUARD_BREAK_NANOS
            ? 1 - (double) sinceBreak / GUARD_BREAK_NANOS
            : 0;
    if (flash > 0) left += (int) Math.round(Math.sin(sinceBreak / 1e9 * 55) * 3 * flash);
    frame(
        gui,
        left,
        top,
        width,
        height,
        flash > 0 ? blend(settings.border(), settings.exhausted(), flash) : settings.border(),
        settings.background());
    int inner = width - 4, innerHeight = height - 4;
    int trailWidth = (int) Math.round(inner * trail),
        fillWidth = (int) Math.round(inner * fraction);
    if (trailWidth > 0)
      gui.fill(left + 2, top + 2, left + 2 + trailWidth, top + 2 + innerHeight, settings.trail());
    int fill = CampaignCombat.shieldReady(client.player) ? settings.fill() : settings.exhausted();
    if (fillWidth > 0)
      gui.fillGradient(
          left + 2,
          top + 2,
          left + 2 + fillWidth,
          top + 2 + innerHeight,
          tint(fill, recovering ? 1.28 : 1.13),
          tint(fill, .72));
    if (flash > 0) {
      int alpha = (int) Math.round(flash * 0x90);
      gui.fill(
          left + 2,
          top + 2,
          left + 2 + inner,
          top + 2 + innerHeight,
          (alpha << 24) | (settings.exhausted() & 0xffffff));
      String label = "Guard broken";
      int textAlpha = (int) Math.round(Math.max(.25, flash) * 255);
      gui.text(
          client.font,
          label,
          left + (width - client.font.width(label)) / 2,
          top - (settings.showNumbers() ? 19 : 10),
          (textAlpha << 24) | (settings.exhausted() & 0xffffff),
          true);
    }
    // A small pixel lightning mark identifies stamina without a full-width label.
    int icon = CampaignCombat.shieldReady(client.player) ? settings.fill() : settings.exhausted();
    gui.fill(left - 6, top + 1, left - 3, top + 2, icon);
    gui.fill(left - 7, top + 2, left - 4, top + 4, icon);
    gui.fill(left - 6, top + 4, left - 2, top + 5, icon);
    gui.fill(left - 4, top + 5, left - 2, top + 7, icon);
    gui.fill(left - 5, top + 7, left - 3, top + 8, icon);
    if (settings.showNumbers()) {
      String text = Math.round(value) + " / " + Math.round(maximum);
      float scale = Math.min(.75f, (width - 8f) / Math.max(1, client.font.width(text)));
      gui.pose().pushMatrix();
      gui.pose().scale(scale, scale);
      gui.text(
          client.font,
          text,
          (int) ((left + (width - client.font.width(text) * scale) / 2) / scale),
          (int) ((top - 9) / scale),
          settings.text(),
          true);
      gui.pose().popMatrix();
    }
  }

  static int staminaTop(
      Minecraft client, GuiGraphicsExtractor gui, CampaignHudConfig.StaminaBar settings) {
    return HudDamageTrail.staminaTop(
        gui.guiHeight(),
        client.player.getMaxHealth(),
        Math.max(
            client.player.getHealth(),
            ((HudHeartLayoutAccessor) client.gui.hud).eldencraft$displayHealth()),
        client.player.getAbsorptionAmount(),
        settings.enabled() ? settings.height() : 0,
        settings.enabled() ? settings.offsetY() : 0);
  }

  static void frame(
      GuiGraphicsExtractor gui, int x, int y, int width, int height, int border, int background) {
    gui.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xc00c0c0a);
    gui.fill(x, y, x + width, y + height, background);
    gui.outline(x, y, width, height, border);
    gui.fill(x, y, x + 1, y + 1, 0xff242319);
    gui.fill(x + width - 1, y, x + width, y + 1, 0xff242319);
    gui.fill(x, y + height - 1, x + 1, y + height, 0xff242319);
    gui.fill(x + width - 1, y + height - 1, x + width, y + height, 0xff242319);
  }

  private static int blend(int from, int to, double amount) {
    int out = 0;
    for (int shift = 0; shift < 32; shift += 8) {
      int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
      out |= ((int) Math.round(a + (b - a) * amount) & 255) << shift;
    }
    return out;
  }

  private static int tint(int color, double brightness) {
    int r = (int) Math.min(255, ((color >>> 16) & 255) * brightness);
    int g = (int) Math.min(255, ((color >>> 8) & 255) * brightness);
    int b = (int) Math.min(255, (color & 255) * brightness);
    return (color & 0xff000000) | (r << 16) | (g << 8) | b;
  }
}
