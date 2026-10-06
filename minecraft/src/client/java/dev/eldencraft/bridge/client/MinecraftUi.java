package dev.eldencraft.bridge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Vanilla inventory colors and square, two-pixel bevels shared by campaign screens. */
final class MinecraftUi {
  static final int TEXT = 0xff404040, MUTED = 0xff606060, LIGHT = 0xffffffff;

  private MinecraftUi() {}

  static void panel(GuiGraphicsExtractor gui, int x, int y, int w, int h) {
    gui.fill(x, y, x + w, y + h, 0xff000000);
    gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xffc6c6c6);
    gui.fill(x + 2, y + 2, x + w - 2, y + 4, 0xffffffff);
    gui.fill(x + 2, y + 2, x + 4, y + h - 2, 0xffffffff);
    gui.fill(x + 3, y + h - 4, x + w - 2, y + h - 2, 0xff555555);
    gui.fill(x + w - 4, y + 3, x + w - 2, y + h - 2, 0xff555555);
  }

  static void slot(GuiGraphicsExtractor gui, int x, int y, int w, int h, int fill) {
    gui.fill(x, y, x + w, y + h, 0xff8b8b8b);
    gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    gui.fill(x, y, x + w - 1, y + 1, 0xff373737);
    gui.fill(x, y, x + 1, y + h - 1, 0xff373737);
    gui.fill(x + 1, y + h - 1, x + w, y + h, 0xffffffff);
    gui.fill(x + w - 1, y + 1, x + w, y + h, 0xffffffff);
  }

  static String clipped(Font font, String value, int maximum) {
    if (maximum <= 0) return "";
    if (font.width(value) <= maximum) return value;
    String suffix = font.width("...") <= maximum ? "..." : "";
    return font.plainSubstrByWidth(value, maximum - font.width(suffix)) + suffix;
  }
}
