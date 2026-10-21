package dev.eldencraft.bridge.client;

import static dev.eldencraft.bridge.client.CampaignTooltipTheme.*;

import dev.eldencraft.bridge.client.CampaignEnchantments.Whetblade;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.player.Player;

/** Compact cards and accessible text share the same live whetblade state. */
public final class CampaignEnchantmentGuide {
  private static final String PREFIX = "eldencraft_bridge:";
  private static final int CARD = 30, GAP = 4;
  public static final int WIDTH = 192, HEIGHT = 280;

  private static final Map<String, String> SHIELD_BLURBS =
      Map.of(
          "guarding_ability", "cheaper blocks",
          "healing", "mend on block",
          "explosive", "a blast on block",
          "riposte", "a harder next strike",
          "second_wind", "stamina on block",
          "stalwart", "no knockback",
          "resounding", "blocks ring",
          "last_hurrah", "a blast on break");

  private CampaignEnchantmentGuide() {}

  public static List<Component> tooltip(Player player) {
    var lines = new ArrayList<Component>();
    lines.add(heading("WHETBLADE INFUSIONS"));
    lines.add(text("Knowledge carved into steel.", MUTED).withStyle(s -> s.withItalic(true)));
    lines.add(Component.empty());
    for (var blade : CampaignEnchantments.WHETBLADES) {
      lines.add(whetblade(player, blade));
      lines.add(detail(affinities(blade, true)));
    }
    lines.add(Component.empty());
    lines.add(icon(Icon.AFFINITY, GOLD).append(text(" One affinity per weapon", IVORY)));
    lines.add(text("Statuses build toward native resistance.", MUTED));
    lines.add(text("Elements add damage, scaled by absorption.", MUTED));
    lines.add(text("Higher tiers are stronger.", MUTED));
    lines.add(Component.empty());
    lines.add(icon(Icon.DAMAGE, GOLD).append(text(" Sharpness · stronger strikes", MUTED)));
    lines.add(icon(Icon.RUNES, GOLD).append(text(" Looting · more runes", MUTED)));
    lines.add(icon(Icon.UNBREAKING, GOLD).append(text(" Unbreaking · less wear", MUTED)));
    lines.add(icon(Icon.PROTECTION, GOLD).append(text(" Protection · less damage taken", MUTED)));
    lines.add(Component.empty());
    lines.add(heading("SHIELD ENCHANTMENTS"));
    lines.add(text("No whetblade needed. They act when a guard holds.", MUTED));
    for (String id : CampaignEnchantments.SHIELD_ENCHANTMENTS) lines.add(shieldLine(id));
    lines.add(Component.empty());
    lines.add(lapis());
    return lines;
  }

  /** A shield enchantment's icon, name and a few words on what it does. */
  private static MutableComponent shieldLine(String id) {
    String full = PREFIX + id;
    return icon(enchantmentIcon(full), color(full))
        .append(text(" " + CampaignEnchantments.displayName(full), IVORY))
        .append(text(" · " + SHIELD_BLURBS.get(id), MUTED));
  }

  public static Component help(Player player) {
    var result = Component.empty();
    for (Component line : tooltip(player)) {
      if (!result.getSiblings().isEmpty()) result.append("\n");
      result.append(line);
    }
    return result;
  }

  private static boolean unlocked(Player player, Whetblade blade) {
    return CampaignEnchantments.unlocked(player, PREFIX + blade.affinities().getFirst());
  }

  public static Component whetblade(Player player, Whetblade blade) {
    boolean found = unlocked(player, blade);
    return icon(found ? Icon.FOUND : Icon.LOCK, found ? GOLD : MUTED)
        .append(text(" " + blade.name(), found ? IVORY : MUTED));
  }

  /** Each affinity with its own icon and colour; locked ones keep the icon but are muted. */
  private static MutableComponent affinities(Whetblade blade, boolean found) {
    var line = Component.empty();
    for (int i = 0; i < blade.affinities().size(); i++) {
      String id = PREFIX + blade.affinities().get(i);
      int color = found ? color(id) : MUTED;
      if (i > 0) line.append(text("  ·  ", MUTED));
      line.append(icon(enchantmentIcon(id), color))
          .append(text(" " + CampaignEnchantments.displayName(id), color));
    }
    return line;
  }

  private static MutableComponent lapis() {
    return icon(Icon.LAPIS, FROST)
        .append(text(" Lapis", IVORY))
        .append(text("  +  ", MUTED))
        .append(icon(Icon.EXPERIENCE, GREEN))
        .append(text(" Experience", IVORY));
  }

  public static void panel(GuiGraphicsExtractor g, Font font, Player player, int x, int y) {
    frame(g, x, y, WIDTH, HEIGHT);
    g.centeredText(font, heading("WHETBLADE INFUSIONS"), x + WIDTH / 2, y + 12, 0xffffffff);
    g.centeredText(
        font,
        text("Knowledge carved into steel.", MUTED).withStyle(s -> s.withItalic(true)),
        x + WIDTH / 2,
        y + 27,
        0xffffffff);
    rule(g, x + 12, y + 43, WIDTH - 24);
    g.textWithWordWrap(
        font,
        text("Find whetblades in the Lands Between to unlock native affinities.", MUTED),
        x + 12,
        y + 52,
        WIDTH - 24,
        0xffffffff,
        false);
    int cardY = y + 85;
    for (var blade : CampaignEnchantments.WHETBLADES) {
      card(g, font, player, x + 10, cardY, blade);
      cardY += CARD + GAP;
    }
    g.text(
        font,
        icon(Icon.AFFINITY, GOLD).append(text(" One affinity per weapon", IVORY)),
        x + 12,
        y + 224,
        0xffffffff,
        false);
    g.text(
        font,
        icon(Icon.PROTECTION, GOLD).append(text(" Shields · guard enchantments", IVORY)),
        x + 12,
        y + 236,
        0xffffffff,
        false);
    g.text(
        font,
        text("Hover an offer to read its effect.", MUTED),
        x + 12,
        y + 248,
        0xffffffff,
        false);
    rule(g, x + 12, y + 260, WIDTH - 24);
    g.text(font, lapis(), x + 12, y + 266, 0xffffffff, false);
  }

  private static void card(
      GuiGraphicsExtractor g, Font font, Player player, int x, int y, Whetblade blade) {
    boolean found = unlocked(player, blade);
    g.fill(x, y, x + WIDTH - 20, y + CARD, found ? 0xff252c22 : 0xff1c201e);
    g.outline(x, y, WIDTH - 20, CARD, found ? 0xff655d3f : 0xff393e35);
    g.text(font, whetblade(player, blade), x + 6, y + 5, 0xffffffff, false);
    g.text(font, affinities(blade, found), x + 16, y + 18, 0xffffffff, false);
  }
}
