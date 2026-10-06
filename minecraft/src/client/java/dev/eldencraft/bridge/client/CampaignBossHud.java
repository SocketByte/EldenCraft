package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.HudDamageTrail;
import dev.eldencraft.bridge.client.mixin.BossOverlayAccessor;
import java.util.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The genuine native boss gauges, rendered with Minecraft's boss sprites and font. */
public final class CampaignBossHud {
  private static final Map<String, HudDamageTrail> TRAILS = new HashMap<>();
  private static final Identifier HEALTH =
      Identifier.fromNamespaceAndPath("minecraft", "boss_bar/white_progress");
  private static final Identifier YELLOW =
      Identifier.fromNamespaceAndPath("minecraft", "boss_bar/yellow_progress");

  private record Rendered(
      long pid, long session, String character, long nanos, long millis, Set<String> ids) {}

  record RenderAck(Set<String> ids, long millis) {}

  private static volatile Rendered rendered;

  private CampaignBossHud() {}

  public static void initialize() {
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.BOSS_BAR,
        Identifier.fromNamespaceAndPath("eldencraft_bridge", "native_bosses"),
        CampaignBossHud::extract);
  }

  private static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    var client = Minecraft.getInstance();
    var settings = CampaignConfig.current().hud.bossBar();
    var host = CampaignBridge.snapshot();
    if (!CampaignCombat.active(client.player)
        || host == null
        || client.gui.hud.isHidden()
        || client.gui.screen() != null
        || !settings.enabled()
        || host.activeBosses().isEmpty()
        || gui.guiWidth() < 48
        || gui.guiHeight() < 64) {
      TRAILS.clear();
      rendered = null;
      return;
    }
    int width = Math.min(settings.width(), gui.guiWidth() - 32), height = settings.height();
    int left = (gui.guiWidth() - width) / 2;
    // Preserve ordinary Minecraft boss events and reserve their normal top rows.
    int vanillaRows =
        ((BossOverlayAccessor) client.gui.hud.getBossOverlay()).eldencraft$events().size();
    int top = settings.top() + vanillaRows * 19;
    var stamina = CampaignConfig.current().hud.stamina();
    int lowerHudTop = CampaignHud.staminaTop(client, gui, stamina);
    int bottom =
        Math.min(
            gui.guiHeight() - 24,
            lowerHudTop - (stamina.enabled() && stamina.showNumbers() ? 11 : 3));
    Set<String> retained = new HashSet<>();
    long now = System.nanoTime();
    for (var boss : host.activeBosses()) {
      if (top + height + 12 > bottom) break;
      retained.add(boss.id());
      var trail = TRAILS.computeIfAbsent(boss.id(), id -> new HudDamageTrail());
      double fraction = boss.hp() / boss.maxHp();
      double delayed =
          trail.update(
              host.session() + ":" + boss.id() + ":" + boss.maxHp(),
              fraction,
              now,
              settings.damageHoldSeconds(),
              settings.damageTrailPerSecond());
      String title = boss.name();
      if (settings.showNumbers())
        title += "  " + Math.round(boss.hp()) + " / " + Math.round(boss.maxHp());
      if (client.font.width(title) > width)
        title = client.font.plainSubstrByWidth(title, width - client.font.width("...")) + "...";
      gui.text(
          client.font,
          title,
          (gui.guiWidth() - client.font.width(title)) / 2,
          top,
          settings.text(),
          true);
      int y = top + 11;
      CampaignHud.frame(gui, left, y, width, height, settings.border(), settings.background());
      int innerWidth = width - 4, innerHeight = height - 4;
      sprite(gui, YELLOW, left + 2, y + 2, innerWidth, innerHeight, delayed, settings.trailTint());
      sprite(gui, HEALTH, left + 2, y + 2, innerWidth, innerHeight, fraction, settings.fillTint());
      int middle = y + height / 2;
      gui.fill(left - 3, middle - 1, left - 1, middle + 1, settings.border());
      gui.fill(left + width + 1, middle - 1, left + width + 3, middle + 1, settings.border());
      top += height + 11 + settings.spacing();
    }
    TRAILS.keySet().retainAll(retained);
    rendered =
        new Rendered(
            host.pid(),
            host.session(),
            host.character(),
            now,
            System.currentTimeMillis(),
            Set.copyOf(retained));
  }

  /** Only replacement bars actually retained in a recent extraction may hide native gauges. */
  static RenderAck renderedAcknowledgement(CampaignBridge.Snapshot host) {
    var value = rendered;
    var client = Minecraft.getInstance();
    long now = System.nanoTime();
    if (value == null
        || host == null
        || value.pid() != host.pid()
        || value.session() != host.session()
        || !value.character().equals(host.character())
        || now < value.nanos()
        || now - value.nanos() >= 250_000_000L
        || client.gui.screen() != null
        || client.gui.hud.isHidden()
        || !CampaignConfig.current().hud.bossBar().enabled()) return new RenderAck(Set.of(), 0);
    var ids = new HashSet<>(value.ids());
    ids.retainAll(host.activeBosses().stream().map(CampaignBridge.Boss::id).toList());
    return new RenderAck(Set.copyOf(ids.stream().sorted().limit(3).toList()), value.millis());
  }

  private static void sprite(
      GuiGraphicsExtractor gui,
      Identifier sprite,
      int x,
      int y,
      int width,
      int height,
      double fraction,
      int color) {
    int fill = (int) Math.round(width * Math.clamp(fraction, 0, 1));
    if (fill > 0)
      gui.blitSprite(
          RenderPipelines.GUI_TEXTURED, sprite, width, height, 0, 0, x, y, fill, height, color);
  }
}
