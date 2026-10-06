package dev.eldencraft.bridge.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Native prompt and dialogue text in Minecraft's font and translucent subtitle boxes. */
final class InteractionHud {
  private InteractionHud() {}

  static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    var client = Minecraft.getInstance();
    var s = CampaignInteractions.snapshot();
    if (s == null || client.gui.hud.isHidden() || client.player == null) return;
    int width = gui.guiWidth(), height = gui.guiHeight();
    var font = client.font;
    int maximum = Math.max(16, Math.min(420, width - 32));
    if (client.gui.screen() == null && s.prompt() != null) {
      String text = MinecraftUi.clipped(font, "[R] " + s.prompt().text(), maximum);
      int x = (width - font.width(text)) / 2, y = height - 86;
      gui.fill(x - 6, y - 4, x + font.width(text) + 6, y + font.lineHeight + 4, 0xb0000000);
      gui.text(font, text, x, y, s.prompt().enabled() ? 0xffffffff : 0xffa0a0a0, true);
    }
    if (!s.subtitle().isBlank()) {
      var lines = font.split(Component.literal(s.subtitle()), maximum);
      int count = Math.min(5, lines.size());
      int bottom = client.gui.screen() == null ? height - 106 : height - 12;
      int y = Math.max(8, bottom - count * (font.lineHeight + 2));
      for (int index = 0; index < count; index++) {
        var line = lines.get(index);
        int lineWidth = font.width(line), x = (width - lineWidth) / 2;
        gui.fill(x - 5, y - 2, x + lineWidth + 5, y + font.lineHeight + 2, 0xb0000000);
        gui.text(font, line, x, y, 0xffffffff, true);
        y += font.lineHeight + 2;
      }
    }
  }
}
