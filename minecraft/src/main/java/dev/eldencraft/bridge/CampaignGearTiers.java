package dev.eldencraft.bridge;

import java.util.List;
import java.util.Set;

/** Boss equipment follows earned milestones even when optional regions are visited out of order. */
public final class CampaignGearTiers {
  private static final List<String> MATERIALS =
      List.of("wooden", "stone", "copper", "iron", "diamond", "netherite");
  private static final Set<String> TOOLS =
      Set.of("sword", "axe", "spear", "pickaxe", "shovel", "hoe");
  private static final Set<String> ARMOR = Set.of("helmet", "chestplate", "leggings", "boots");

  private CampaignGearTiers() {}

  public static int unlocked(Set<String> defeated) {
    if (defeated.contains("maliketh")) return 5;
    if (defeated.contains("morgott")) return 4;
    if (defeated.contains("rennala") || defeated.contains("radahn")) return 3;
    if (defeated.contains("godrick")) return 2;
    if (defeated.contains("margit")) return 1;
    return 0;
  }

  /** Preserve quantities and utilities; only equipment and its upgrade materials are capped. */
  public static List<CampaignConfig.Reward> cap(
      List<CampaignConfig.Reward> rewards, Set<String> defeated) {
    int tier = unlocked(defeated);
    return rewards.stream()
        .map(r -> new CampaignConfig.Reward(capItem(r.item(), tier), r.count(), r.potion()))
        .toList();
  }

  public static String capItem(String item, int tier) {
    if (!item.startsWith("minecraft:")) return item;
    String name = item.substring("minecraft:".length());
    if ((name.equals("netherite_ingot")
            || name.equals("netherite_scrap")
            || name.equals("netherite_upgrade_smithing_template"))
        && tier < 5) return ingredient(tier);
    if (name.equals("diamond") && tier < 4) return ingredient(tier);
    if (name.equals("iron_ingot") && tier < 3) return ingredient(tier);
    if (name.equals("copper_ingot") && tier < 2) return ingredient(tier);

    int separator = name.indexOf('_');
    if (separator < 0) return item;
    String material = name.substring(0, separator), piece = name.substring(separator + 1);
    int original = material.equals("chainmail") ? 3 : MATERIALS.indexOf(material);
    if (original <= tier || original < 0) return item;
    if (TOOLS.contains(piece)) return "minecraft:" + MATERIALS.get(tier) + "_" + piece;
    // Vanilla has no wooden or stone armor. Leather fills those opening tiers.
    if (ARMOR.contains(piece))
      return "minecraft:" + (tier < 2 ? "leather" : MATERIALS.get(tier)) + "_" + piece;
    return item;
  }

  private static String ingredient(int tier) {
    return "minecraft:"
        + switch (tier) {
          case 0 -> "stick";
          case 1 -> "cobblestone";
          case 2 -> "copper_ingot";
          case 3 -> "iron_ingot";
          case 4 -> "diamond";
          default -> "netherite_ingot";
        };
  }
}
