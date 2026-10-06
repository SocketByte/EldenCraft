package dev.eldencraft.bridge.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Native currency presented with Minecraft's own font in the host's lower-right HUD corner. */
public final class HostRuneHud {
  private static long lastBalance = -1;
  private static String digits = "";

  private HostRuneHud() {}

  public static void initialize() {
    HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath("eldencraft_bridge", "runes"), HostRuneHud::extract);
  }

  private static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    Minecraft client = Minecraft.getInstance();
    if (!SceneCapture.active() || client.gui.hud.isHidden() || client.gui.screen() != null) return;
    var host = HostController.damageSnapshot(client);
    if (host == null || !host.runesValid()) return;
    if (lastBalance != host.runes()) {
      lastBalance = host.runes();
      digits = String.format(java.util.Locale.ROOT, "%,d", lastBalance);
    }
    var font = client.font;
    int width = font.width(digits) + 23;
    int right = gui.guiWidth() - 10, bottom = gui.guiHeight() - 6;
    int left = right - width, top = bottom - 15;
    // One compact text row, sized to the balance like Minecraft's counters.
    gui.fill(left, top, right, bottom, 0xa010100c);
    rune(gui, left + 4, top + 3);
    gui.text(font, digits, right - 4 - font.width(digits), top + 4, 0xffffe18a, true);
  }

  /**
   * Original pixel ornament; the displayed value always comes from the current native save data.
   */
  private static void rune(GuiGraphicsExtractor gui, int x, int y) {
    int dark = 0xff716130, gold = 0xffdbb65a, light = 0xffffdf8c;
    gui.fill(x + 4, y + 1, x + 5, y + 10, dark);
    gui.fill(x + 3, y, x + 4, y + 9, light);
    gui.fill(x + 1, y + 2, x + 6, y + 3, gold);
    gui.fill(x, y + 3, x + 1, y + 6, gold);
    gui.fill(x + 6, y + 3, x + 7, y + 6, dark);
    gui.fill(x + 1, y + 6, x + 6, y + 7, gold);
    gui.fill(x + 2, y + 9, x + 5, y + 10, gold);
  }
}
