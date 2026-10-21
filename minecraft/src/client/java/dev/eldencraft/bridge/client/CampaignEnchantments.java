package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.SharedWorldBlocks;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;

/** Campaign-only enchantment migration and native-character affinity admission. */
public final class CampaignEnchantments {
  private static final String PREFIX = "eldencraft_bridge:";
  private static final List<String> STATUSES = List.of("poison", "bleeding", "frostbite");
  private static final List<String> ELEMENTS = List.of("fire", "lightning", "magic", "holy");

  /** Wire nibble order after the status affinities; see PROTOCOL.md. */
  public static final List<String> AFFINITIES =
      List.of("poison", "bleeding", "frostbite", "fire", "lightning", "magic", "holy");

  /** A native key item, its campaign key and the affinities it unlocks. */
  public record Whetblade(String key, String name, List<String> affinities) {}

  /** Elden Ring's own whetblade unlocks for each affinity. */
  public static final List<Whetblade> WHETBLADES =
      List.of(
          new Whetblade("black", "Black Whetblade", List.of("poison", "bleeding")),
          new Whetblade("glintstone", "Glintstone Whetblade", List.of("frostbite", "magic")),
          new Whetblade("red_hot", "Red-Hot Whetblade", List.of("fire")),
          new Whetblade("sanctified", "Sanctified Whetblade", List.of("lightning", "holy")));

  /** Shield enchantments need no whetblade; they act whenever a campaign guard succeeds. */
  public static final List<String> SHIELD_ENCHANTMENTS =
      List.of(
          "guarding_ability",
          "healing",
          "explosive",
          "riposte",
          "second_wind",
          "stalwart",
          "resounding",
          "last_hurrah");

  /** Vanilla gives shields no enchantability, so the campaign grants a sword-like value. */
  public static final int SHIELD_ENCHANTABILITY = 14;

  private static final Set<String> WEAPONS = Set.of("sword", "axe", "spear", "mace");
  private static final Set<String> ALLOWED = allowedIds();
  private static boolean initialized;
  private static EnchantmentMenu previousTable;
  private static Set<String> previousUnlocks = Set.of();

  private CampaignEnchantments() {}

  private static Set<String> allowedIds() {
    var ids =
        new HashSet<>(
            List.of(
                "minecraft:sharpness",
                "minecraft:looting",
                "minecraft:unbreaking",
                "minecraft:protection"));
    for (String id : AFFINITIES) ids.add(PREFIX + id);
    for (String id : SHIELD_ENCHANTMENTS) ids.add(PREFIX + id);
    return Set.copyOf(ids);
  }

  public static void initialize() {
    if (initialized) return;
    initialized = true;
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          if (!server.isSingleplayer() || server.isPublished()) return;
          for (var player : server.getPlayerList().getPlayers()) {
            if (!scoped(player)) continue;
            // A shield only becomes enchantable once tuned; refresh a table that was just fed one.
            if (player.containerMenu instanceof EnchantmentMenu input
                && shield(input.getSlot(0).getItem())
                && !input.getSlot(0).getItem().has(DataComponents.ENCHANTABLE)) {
              tune(input.getSlot(0).getItem());
              input.slotsChanged(input.getSlot(0).container);
            }
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) tune(inventory.getItem(i));
            for (var slot : player.containerMenu.slots) tune(slot.getItem());
            tune(player.containerMenu.getCarried());
            if (player.containerMenu instanceof AnvilMenu anvil) {
              if (!permitted(player, anvil.getSlot(2).getItem())) anvil.createResult();
            }
            if (!(player.containerMenu instanceof EnchantmentMenu table)) {
              previousTable = null;
              continue;
            }
            var host = CampaignBridge.snapshot();
            var unlocked =
                host != null && CampaignProgression.paired(player, host.character())
                    ? host.whetblades()
                    : Set.<String>of();
            if (previousTable != table || !previousUnlocks.equals(unlocked)) {
              previousTable = table;
              previousUnlocks = Set.copyOf(unlocked);
              table.slotsChanged(table.getSlot(0).container);
            }
          }
        });
  }

  /** A paired campaign retains the policy while its native observation is temporarily stale. */
  public static boolean scoped(Player player) {
    return player != null
        && CampaignConfig.current().enabled()
        && player.level().dimension().equals(SharedWorldBlocks.DIMENSION)
        && (CampaignCombat.active(player)
            || player.entityTags().stream()
                .anyMatch(tag -> tag.startsWith("eldencraft.character.")));
  }

  private static String canonical(String id) {
    if (id == null) return "";
    if (id.indexOf(':') >= 0) return id;
    boolean ours = AFFINITIES.contains(id) || SHIELD_ENCHANTMENTS.contains(id);
    return (ours ? PREFIX : "minecraft:") + id;
  }

  public static String id(Holder<Enchantment> enchantment) {
    return enchantment.unwrapKey().map(key -> key.identifier().toString()).orElse("");
  }

  public static boolean allowed(String id) {
    return ALLOWED.contains(canonical(id));
  }

  /** A native affinity: the only enchantments that a whetblade gates. */
  public static boolean custom(String id) {
    String value = canonical(id);
    return value.startsWith(PREFIX)
        && ALLOWED.contains(value)
        && AFFINITIES.contains(value.substring(PREFIX.length()));
  }

  /** One of this mod's shield enchantments, such as Guarding Ability or Explosive. */
  public static boolean shieldEnchantment(String id) {
    String value = canonical(id);
    return value.startsWith(PREFIX)
        && ALLOWED.contains(value)
        && SHIELD_ENCHANTMENTS.contains(value.substring(PREFIX.length()));
  }

  public static boolean weapon(ItemStack stack) {
    return !stack.isEmpty() && WEAPONS.contains(CampaignCombat.kind(stack));
  }

  public static boolean shield(ItemStack stack) {
    return stack != null && stack.is(Items.SHIELD);
  }

  /** Highest level this enchantment is offered at; mirrors the shipped definitions. */
  public static int maxLevel(String id) {
    String value = canonical(id);
    if (custom(value)) return 3;
    return switch (value) {
      case PREFIX + "guarding_ability",
          PREFIX + "healing",
          PREFIX + "riposte",
          PREFIX + "second_wind" ->
          3;
      case PREFIX + "explosive",
          PREFIX + "stalwart",
          PREFIX + "resounding",
          PREFIX + "last_hurrah" ->
          1;
      case "minecraft:sharpness" -> 5;
      case "minecraft:protection" -> 4;
      case "minecraft:looting", "minecraft:unbreaking" -> 3;
      default -> 0;
    };
  }

  /** Single-level enchantments read as just their name, as in vanilla. */
  public static boolean singleLevel(String id) {
    return shieldEnchantment(id) && maxLevel(id) == 1;
  }

  public static boolean unlocked(Player player, String id) {
    String value = canonical(id);
    if (!allowed(value)) return false;
    if (!custom(value)) return true;
    var host = CampaignBridge.snapshot();
    return scoped(player)
        && host != null
        && (player instanceof ServerPlayer
            ? CampaignProgression.paired(player, host.character())
            : CampaignCombat.active(player))
        && unlocked(host.whetblades(), value);
  }

  /** Native flags represent real key-item ownership and are never written by Minecraft. */
  public static boolean unlocked(Set<String> whetblades, String id) {
    if (!custom(id)) return allowed(id);
    var blade = whetbladeOf(id);
    return blade != null && whetblades.contains(blade.key());
  }

  /** The native key item that unlocks a custom affinity, or null for vanilla enchantments. */
  public static Whetblade whetbladeOf(String id) {
    String value = canonical(id);
    for (var blade : WHETBLADES)
      if (blade.affinities().stream().anyMatch(affinity -> value.equals(PREFIX + affinity)))
        return blade;
    return null;
  }

  public static String whetblade(String id) {
    var blade = whetbladeOf(id);
    return blade == null ? "" : blade.name();
  }

  /** Poison, Bleeding and Frostbite build native status; the others are elements. */
  public static boolean status(String id) {
    String value = canonical(id);
    return STATUSES.stream().anyMatch(affinity -> value.equals(PREFIX + affinity));
  }

  public static boolean element(String id) {
    String value = canonical(id);
    return ELEMENTS.stream().anyMatch(affinity -> value.equals(PREFIX + affinity));
  }

  /** The campaign pays Sharpness this many times what vanilla does. */
  public static final double SHARPNESS_BUFF = 1.5;

  /** Vanilla's Sharpness bonus in Minecraft damage: 1 at level I, then 0.5 per level more. */
  public static double vanillaSharpness(int level) {
    return level <= 0 ? 0 : .5 + .5 * Math.clamp(level, 1, 5);
  }

  /** What Sharpness adds to a full-strength hit in the campaign, before the native damage scale. */
  public static double sharpness(int level) {
    return SHARPNESS_BUFF * vanillaSharpness(level);
  }

  /**
   * As in Elden Ring, an element converts part of the physical damage. Percent of the raw hit kept
   * as physical damage by level; mirrors native ELEMENT_SPLITS.
   */
  public static int physicalShare(int level) {
    return level <= 0 ? 100 : new int[] {90, 85, 80}[Math.clamp(level, 1, 3) - 1];
  }

  /** Percent of the raw hit dealt as the element, before the enemy's absorption. */
  public static int elementShare(int level) {
    return level <= 0 ? 0 : new int[] {15, 22, 30}[Math.clamp(level, 1, 3) - 1];
  }

  public static int level(ItemStack stack, String id) {
    String value = canonical(id);
    if (!allowed(value)) return 0;
    for (var entry :
        stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet())
      if (id(entry.getKey()).equals(value))
        return Math.clamp(
            entry.getIntValue(), 0, custom(value) ? 3 : entry.getKey().value().getMaxLevel());
    return 0;
  }

  public static String affinity(ItemStack stack) {
    if (!weapon(stack)) return "";
    String strongest = "";
    int maximum = 0;
    for (String affinity : AFFINITIES) {
      int level = level(stack, affinity);
      if (level > maximum) {
        strongest = affinity;
        maximum = level;
      }
    }
    return strongest;
  }

  public static int affinityLevel(ItemStack stack) {
    return level(stack, affinity(stack));
  }

  /** Removes retired enchants from real items and stored books, including legacy inventories. */
  public static void tune(ItemStack stack) {
    if (stack.isEmpty() || !CampaignConfig.current().enabled()) return;
    if (shield(stack) && !stack.has(DataComponents.ENCHANTABLE))
      stack.set(DataComponents.ENCHANTABLE, new Enchantable(SHIELD_ENCHANTABILITY));
    tune(stack, DataComponents.ENCHANTMENTS, false);
    tune(stack, DataComponents.STORED_ENCHANTMENTS, true);
  }

  private static void tune(
      ItemStack stack, DataComponentType<ItemEnchantments> type, boolean stored) {
    var old = stack.get(type);
    if (old == null || old.isEmpty()) return;
    String strongest = "";
    int maximum = 0;
    for (String affinity : AFFINITIES)
      for (var entry : old.entrySet())
        if (id(entry.getKey()).equals(PREFIX + affinity) && entry.getIntValue() > maximum) {
          strongest = PREFIX + affinity;
          maximum = entry.getIntValue();
        }
    var result = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
    for (var entry : old.entrySet()) {
      String id = id(entry.getKey());
      if (!allowed(id)) continue;
      if (custom(id) && ((!stored && !weapon(stack)) || !id.equals(strongest))) continue;
      if (shieldEnchantment(id) && !stored && !shield(stack)) continue;
      result.set(
          entry.getKey(),
          Math.min(entry.getIntValue(), custom(id) ? 3 : entry.getKey().value().getMaxLevel()));
    }
    var updated = result.toImmutable();
    if (!old.equals(updated)) stack.set(type, updated);
  }

  /** Used again at result pickup; refreshing a preview cannot authorize a stale native unlock. */
  public static boolean permitted(Player player, ItemStack stack) {
    for (var type : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS))
      for (var entry : stack.getOrDefault(type, ItemEnchantments.EMPTY).entrySet())
        if (!allowed(id(entry.getKey())) || !unlocked(player, id(entry.getKey()))) return false;
    return true;
  }

  /** Same seeded vanilla selection, with the candidate pool constrained before rolling. */
  public static List<EnchantmentInstance> table(
      Player player, RegistryAccess registries, ItemStack stack, int cost, RandomSource random) {
    // A shield is unbreakable in the campaign, so only its guard enchantments are worth offering.
    boolean shield = shield(stack);
    var candidates =
        registries
            .lookupOrThrow(Registries.ENCHANTMENT)
            .listElements()
            .map(holder -> (Holder<Enchantment>) holder)
            .filter(
                holder ->
                    allowed(id(holder))
                        && unlocked(player, id(holder))
                        && (!shield || shieldEnchantment(id(holder))));
    var result = EnchantmentHelper.selectEnchantment(random, stack, cost, candidates);
    if (stack.is(Items.BOOK) && result.size() > 1) result.remove(random.nextInt(result.size()));
    return result;
  }

  private static String percent(double fraction) {
    return CampaignWeaponTooltips.number(100 * fraction);
  }

  public static int buildup(int level) {
    return level <= 0 ? 0 : 20 + 15 * Math.clamp(level, 1, 3);
  }

  public static String displayName(String id) {
    return switch (canonical(id)) {
      case "minecraft:sharpness" -> "Sharpness";
      case "minecraft:looting" -> "Looting";
      case "minecraft:unbreaking" -> "Unbreaking";
      case "minecraft:protection" -> "Protection";
      case PREFIX + "poison" -> "Poison";
      case PREFIX + "bleeding" -> "Bleeding";
      case PREFIX + "frostbite" -> "Frostbite";
      case PREFIX + "fire" -> "Fire";
      case PREFIX + "lightning" -> "Lightning";
      case PREFIX + "magic" -> "Magic";
      case PREFIX + "holy" -> "Holy";
      case PREFIX + "guarding_ability" -> "Guarding Ability";
      case PREFIX + "healing" -> "Healing";
      case PREFIX + "explosive" -> "Explosive";
      case PREFIX + "riposte" -> "Riposte";
      case PREFIX + "second_wind" -> "Second Wind";
      case PREFIX + "stalwart" -> "Stalwart";
      case PREFIX + "resounding" -> "Resounding";
      case PREFIX + "last_hurrah" -> "Last Hurrah";
      default -> "";
    };
  }

  public static String description(String id, int level) {
    int bounded = Math.clamp(level, 1, custom(id) || shieldEnchantment(id) ? maxLevel(id) : 5);
    return switch (canonical(id)) {
      case "minecraft:sharpness" ->
          "Adds "
              + CampaignWeaponTooltips.number(
                  sharpness(bounded) * CampaignConfig.current().nativeDamageScale)
              + " Elden Ring damage to full-strength melee hits.";
      case "minecraft:looting" ->
          "+" + (10 * Math.min(bounded, 3)) + "% runes from defeated enemies.";
      case "minecraft:unbreaking" -> "Reduces equipment durability loss.";
      case "minecraft:protection" ->
          "Reduces incoming damage by " + (4 * Math.min(bounded, 4)) + "%; stacks with worn armor.";
      case PREFIX + "poison" ->
          buildup(bounded) + " poison buildup per melee hit; triggers damage over time.";
      case PREFIX + "bleeding" ->
          buildup(bounded)
              + " blood loss buildup per melee hit; triggers a maximum-HP damage burst.";
      case PREFIX + "frostbite" ->
          buildup(bounded)
              + " frost buildup per melee hit; triggers a burst and 20% more damage taken.";
      case PREFIX + "fire", PREFIX + "lightning", PREFIX + "magic", PREFIX + "holy" ->
          "Each melee hit deals "
              + physicalShare(bounded)
              + "% physical plus "
              + elementShare(bounded)
              + "% as "
              + displayName(id).toLowerCase(Locale.ROOT)
              + " damage, scaled by the enemy's "
              + displayName(id).toLowerCase(Locale.ROOT)
              + " absorption.";
      case PREFIX + "guarding_ability" ->
          "Blocking costs "
              + percent(1 - CampaignShields.guardCostScale(bounded))
              + "% less stamina.";
      case PREFIX + "healing" ->
          "Each block restores "
              + percent(CampaignShields.healFraction(bounded))
              + "% of your maximum health, at most every "
              + CampaignWeaponTooltips.number(CampaignShields.HEAL_COOLDOWN_TICKS / 20d)
              + " s.";
      case PREFIX + "explosive" ->
          "Blocking sets off a small blast in front of you that hurts nearby enemies, never you"
              + " or the terrain.";
      case PREFIX + "riposte" ->
          "After a block, your next melee hit within "
              + CampaignShields.RIPOSTE_WINDOW_TICKS / 20
              + " seconds deals +"
              + percent(CampaignShields.riposteBonus(bounded))
              + "% damage.";
      case PREFIX + "second_wind" ->
          "Each block restores "
              + percent(CampaignShields.staminaRefund(bounded))
              + "% of your maximum stamina.";
      case PREFIX + "stalwart" -> "While your guard is raised, hits cannot knock you back.";
      case PREFIX + "resounding" ->
          "Blocks ring your shield like a bell. Chain them to climb the scale.";
      case PREFIX + "last_hurrah" ->
          "When your guard breaks, the shield detonates, hurting nearby enemies.";
      default -> "";
    };
  }
}
