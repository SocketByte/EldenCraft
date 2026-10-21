package dev.eldencraft.bridge.client;

import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;

/** A small readable guide seal, with the same frame as the infusion cards. */
public final class CampaignGuideButton extends Button {
  private final Font font;

  public CampaignGuideButton(Font font, int x, int y, OnPress press) {
    super(
        x,
        y,
        48,
        12,
        CampaignTooltipTheme.text("Guide", CampaignTooltipTheme.GOLD),
        press,
        DEFAULT_NARRATION);
    this.font = font;
  }

  @Override
  protected void extractContents(
      GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
    boolean hover = isHoveredOrFocused();
    g.fill(
        getX(), getY(), getX() + getWidth(), getY() + getHeight(), hover ? 0xff3a402b : 0xff22281f);
    g.outline(getX(), getY(), getWidth(), getHeight(), hover ? 0xffd7ba78 : 0xff786443);
    g.text(
        font,
        CampaignTooltipTheme.icon(CampaignTooltipTheme.Icon.WHETBLADE, CampaignTooltipTheme.GOLD)
            .append(CampaignTooltipTheme.text(" Guide", CampaignTooltipTheme.GOLD)),
        getX() + 4,
        getY() + 2,
        0xffffffff,
        false);
  }
}
