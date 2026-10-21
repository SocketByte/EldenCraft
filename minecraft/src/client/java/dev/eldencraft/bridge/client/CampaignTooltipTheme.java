package dev.eldencraft.bridge.client;

import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;

/**
 * Shared colours, bundled icon glyphs and engraved frames for campaign equipment. Icons are 9x9
 * pixel art drawn at the font's native scale, and every icon advances the same width, so labels and
 * indented details line up.
 */
public final class CampaignTooltipTheme {
  public static final int GOLD = 0xd7ba78, IVORY = 0xece3cf, MUTED = 0xa79f8e;
  public static final int GREEN = 0x98bb80, BLOOD = 0xd77c79, FROST = 0x99c9db;
  public static final int EMBER = 0xe59a5f, STORM = 0xe6d470, ARCANE = 0x93a9ee, RADIANT = 0xf2e3ad;
  public static final Identifier STYLE = Identifier.parse("eldencraft_bridge:equipment");
  public static final Identifier ICON_FONT = Identifier.parse("eldencraft_bridge:icons");

  public enum Icon {
    DAMAGE,
    SPEED,
    STAMINA,
    RUNES,
    POISON,
    BLEEDING,
    FROSTBITE,
    PROTECTION,
    UNBREAKING,
    WHETBLADE,
    LOCK,
    FOUND,
    AFFINITY,
    ORNAMENT,
    LAPIS,
    EXPERIENCE,
    ORNAMENT_END,
    FIRE,
    LIGHTNING,
    MAGIC,
    HOLY,
    HEALING,
    BLAST,
    BELL
  }

  /** A blank icon-font glyph that advances exactly one icon. */
  private static final char INDENT = (char) 0xe0ff;

  private CampaignTooltipTheme() {}

  public static MutableComponent text(String text, int color) {
    return Component.literal(text)
        .withStyle(style -> style.withColor(color).withFont(FontDescription.DEFAULT));
  }

  public static MutableComponent icon(Icon icon, int color) {
    return Component.literal(String.valueOf((char) (0xe000 + icon.ordinal())))
        .withStyle(
            style -> style.withFont(new FontDescription.Resource(ICON_FONT)).withColor(color));
  }

  /** Equipment cards title the item in ivory; {@link #STYLE} selects the engraved frame. */
  public static List<Component> card(List<Component> lines) {
    var card = new ArrayList<>(lines);
    if (!card.isEmpty())
      card.set(0, card.getFirst().copy().withStyle(style -> style.withColor(IVORY).withBold(true)));
    return card;
  }

  /** Text that starts where a label after an icon starts. */
  public static MutableComponent detail(Component content) {
    // Siblings of an empty root inherit nothing from the indent's icon font.
    return Component.empty()
        .append(
            Component.literal(String.valueOf(INDENT))
                .withStyle(style -> style.withFont(new FontDescription.Resource(ICON_FONT))))
        .append(text(" ", MUTED))
        .append(content);
  }

  public static MutableComponent detail(String line) {
    return detail(text(line, MUTED));
  }

  public static MutableComponent heading(String title) {
    return icon(Icon.ORNAMENT, GOLD)
        .append(text(" " + title + " ", GOLD))
        .append(icon(Icon.ORNAMENT_END, GOLD));
  }

  public static MutableComponent row(Icon icon, String label, String value, int color) {
    return icon(icon, color)
        .append(text(" " + label + "  ", MUTED))
        .append(text(value, color).withStyle(style -> style.withBold(true)));
  }

  /** Elden Ring style attack power: physical, then the element's share in its colour. */
  public static MutableComponent split(
      String label, String physical, String element, Icon elementIcon, int color) {
    return icon(Icon.DAMAGE, IVORY)
        .append(text(" " + label + "  ", MUTED))
        .append(text(physical, IVORY).withStyle(style -> style.withBold(true)))
        .append(text(" (+" + element + " ", color).withStyle(style -> style.withBold(true)))
        .append(icon(elementIcon, color))
        .append(text(")", color).withStyle(style -> style.withBold(true)));
  }

  public static int color(String id) {
    if (id.endsWith(":poison")) return GREEN;
    if (id.endsWith(":bleeding")) return BLOOD;
    if (id.endsWith(":frostbite")) return FROST;
    if (id.endsWith(":fire")) return EMBER;
    if (id.endsWith(":lightning")) return STORM;
    if (id.endsWith(":magic")) return ARCANE;
    if (id.endsWith(":holy")) return RADIANT;
    if (id.endsWith(":healing")) return GREEN;
    if (id.endsWith(":explosive") || id.endsWith(":last_hurrah")) return EMBER;
    if (id.endsWith(":riposte")) return BLOOD;
    if (id.endsWith(":second_wind")) return FROST;
    if (id.endsWith(":resounding")) return ARCANE;
    if (id.endsWith(":stalwart")) return IVORY;
    return GOLD;
  }

  public static Icon enchantmentIcon(String id) {
    return switch (id.substring(id.indexOf(':') + 1)) {
      case "poison" -> Icon.POISON;
      case "bleeding" -> Icon.BLEEDING;
      case "frostbite" -> Icon.FROSTBITE;
      case "fire" -> Icon.FIRE;
      case "lightning" -> Icon.LIGHTNING;
      case "magic" -> Icon.MAGIC;
      case "holy" -> Icon.HOLY;
      case "looting" -> Icon.RUNES;
      case "unbreaking" -> Icon.UNBREAKING;
      case "protection", "guarding_ability" -> Icon.PROTECTION;
      case "healing" -> Icon.HEALING;
      case "explosive", "last_hurrah" -> Icon.BLAST;
      case "second_wind" -> Icon.STAMINA;
      case "stalwart" -> Icon.UNBREAKING;
      case "resounding" -> Icon.BELL;
      default -> Icon.DAMAGE;
    };
  }

  public static String roman(int level) {
    return switch (level) {
      case 1 -> "I";
      case 2 -> "II";
      case 3 -> "III";
      case 4 -> "IV";
      case 5 -> "V";
      default -> Integer.toString(level);
    };
  }

  /** The enchantment's name, with its level unless it only has one, as in vanilla. */
  public static String title(String id, int level) {
    String name = CampaignEnchantments.displayName(id);
    return CampaignEnchantments.singleLevel(id) ? name : name + " " + roman(level);
  }

  public static MutableComponent enchantment(String id, int level) {
    int color = color(id);
    return icon(enchantmentIcon(id), color).append(text(" " + title(id, level), color));
  }

  /** The paired character lacks this affinity's whetblade, so hits carry no effect. */
  public static MutableComponent dormant(String id) {
    return icon(Icon.LOCK, BLOOD)
        .append(text(" Dormant: needs " + CampaignEnchantments.whetblade(id), BLOOD));
  }

  public static List<Component> effect(String id, int level) {
    var lines = new ArrayList<Component>();
    lines.add(enchantment(id, level));
    // Attack power already shows the element's share; the name says the rest.
    if (CampaignEnchantments.element(id)) {
      lines.add(detail(CampaignEnchantments.displayName(id) + " damage"));
    } else if (CampaignEnchantments.custom(id)) {
      lines.add(
          row(
              Icon.AFFINITY,
              "Status buildup",
              Integer.toString(CampaignEnchantments.buildup(level)),
              color(id)));
      String effect =
          switch (id.substring(id.indexOf(':') + 1)) {
            case "poison" -> "Resistance overcome: lingering poison.";
            case "bleeding" -> "Resistance overcome: blood loss burst.";
            default -> "Burst damage. Victim takes +20% damage.";
          };
      for (String line : CampaignWeaponTooltips.wrap(effect, 35)) lines.add(detail(line));
    } else {
      for (String line :
          CampaignWeaponTooltips.wrap(CampaignEnchantments.description(id, level), 35))
        lines.add(detail(line));
    }
    return lines;
  }

  /** All dimensions are GUI pixels; corners stay crisp at every GUI scale. */
  public static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
    graphics.fill(x - 2, y - 2, x + width + 3, y + height + 3, 0x65000000);
    graphics.fillGradient(x, y, x + width, y + height, 0xf51e211d, 0xf5101312);
    graphics.outline(x, y, width, height, 0xff786443);
    graphics.outline(x + 2, y + 2, width - 4, height - 4, 0xff34392e);
    for (int cornerX : new int[] {x, x + width - 1})
      for (int cornerY : new int[] {y, y + height - 1}) {
        int dx = cornerX == x ? 1 : -1, dy = cornerY == y ? 1 : -1;
        graphics.fill(
            Math.min(cornerX, cornerX + dx * 7),
            cornerY,
            Math.max(cornerX, cornerX + dx * 7) + 1,
            cornerY + 1,
            0xffd7ba78);
        graphics.fill(
            cornerX,
            Math.min(cornerY, cornerY + dy * 7),
            cornerX + 1,
            Math.max(cornerY, cornerY + dy * 7) + 1,
            0xffd7ba78);
        graphics.fill(
            cornerX + dx * 3,
            cornerY + dy * 3,
            cornerX + dx * 3 + 1,
            cornerY + dy * 3 + 1,
            0xffb19762);
      }
  }

  public static void rule(GuiGraphicsExtractor graphics, int x, int y, int width) {
    graphics.fill(x, y, x + width, y + 1, 0xff65573e);
    graphics.fill(x + width / 2 - 2, y - 1, x + width / 2 + 3, y + 2, 0xffb19762);
  }
}
