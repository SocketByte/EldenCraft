package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.DialogueLayout;
import dev.eldencraft.bridge.InteractionProtocol;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Native interaction prompt and the NPC dialogue box, drawn in Minecraft's font and frame. */
final class InteractionHud {
  private static final int PANEL = 0xe622281f,
      PANEL_EDGE = 0xff786443,
      PANEL_INNER = 0x40d7ba78,
      SPEAKER = 0xffd7ba78,
      TEXT = 0xffffffff,
      HINT = 0xffb5ab8c,
      FADE_NANOS = 140_000_000;

  /** The line on screen and when it appeared, so each new line fades in. */
  private static String shownLine = "";

  private static long shownSince;

  private InteractionHud() {}

  static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    var client = Minecraft.getInstance();
    var s = CampaignInteractions.snapshot();
    if (s == null || client.gui.hud.isHidden() || client.player == null) {
      shownLine = "";
      return;
    }
    int width = gui.guiWidth(), height = gui.guiHeight();
    var font = client.font;
    int maximum = Math.max(16, Math.min(420, width - 32));
    if (client.gui.screen() == null && s.prompt() != null) {
      String text = MinecraftUi.clipped(font, "[R] " + s.prompt().text(), maximum);
      int x = (width - font.width(text)) / 2, y = height - 86;
      gui.fill(x - 6, y - 4, x + font.width(text) + 6, y + font.lineHeight + 4, 0xb0000000);
      gui.text(font, text, x, y, s.prompt().enabled() ? 0xffffffff : 0xffa0a0a0, true);
    }
    if (s.subtitle().isBlank()) {
      shownLine = "";
      return;
    }
    dialogue(gui, s, font, width, height);
  }

  /** The dialogue box; the speaker heads it when the native script names one. */
  private static void dialogue(
      GuiGraphicsExtractor gui,
      InteractionProtocol.Snapshot s,
      net.minecraft.client.gui.Font font,
      int width,
      int height) {
    String key = s.speaker() + '\n' + s.subtitle();
    long now = System.nanoTime();
    if (!key.equals(shownLine)) {
      shownLine = key;
      shownSince = now;
    }
    var lines = font.split(Component.literal(s.subtitle()), DialogueLayout.textWidth(width));
    boolean header = !s.speaker().isEmpty();
    var box = DialogueLayout.create(width, height, font.lineHeight, lines.size(), header);
    int alpha = (int) Math.clamp((now - shownSince) * 255 / FADE_NANOS, 16, 255);
    // Vanilla draws a fully transparent text color opaque, so the fade starts just above zero.
    int shade = alpha << 24;
    gui.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), fade(PANEL, alpha));
    gui.outline(box.x(), box.y(), box.width(), box.height(), fade(PANEL_EDGE, alpha));
    gui.outline(
        box.x() + 2, box.y() + 2, box.width() - 4, box.height() - 4, fade(PANEL_INNER, alpha));
    if (header)
      gui.text(
          font,
          MinecraftUi.clipped(font, s.speaker(), box.textWidth()),
          box.textX(),
          box.headerY(),
          shade | (SPEAKER & 0xffffff),
          true);
    // A speech longer than the box turns pages, each held for its reading time.
    int rows = box.rows();
    int pages = (lines.size() + rows - 1) / rows;
    int[] pageCharacters = new int[Math.max(1, pages)];
    for (int index = 0; index < lines.size(); index++)
      pageCharacters[index / rows] += plain(lines.get(index)).length();
    int page = DialogueLayout.pageAt(pageCharacters, (now - shownSince) / 1_000_000L);
    int first = page * rows;
    for (int row = 0; row < rows && first + row < lines.size(); row++)
      gui.text(
          font,
          lines.get(first + row),
          box.textX(),
          box.rowY(row),
          shade | (TEXT & 0xffffff),
          true);
    if (pages > 1)
      gui.text(
          font,
          (page + 1) + "/" + pages,
          box.textX(),
          box.hintY(),
          shade | (HINT & 0xffffff),
          false);
  }

  private static String plain(net.minecraft.util.FormattedCharSequence line) {
    var text = new StringBuilder();
    line.accept(
        (index, style, codePoint) -> {
          text.appendCodePoint(codePoint);
          return true;
        });
    return text.toString().stripTrailing();
  }

  /** Scales an ARGB color's own alpha by {@code alpha / 255}. */
  private static int fade(int argb, int alpha) {
    return ((argb >>> 24) * alpha / 255) << 24 | (argb & 0xffffff);
  }
}
