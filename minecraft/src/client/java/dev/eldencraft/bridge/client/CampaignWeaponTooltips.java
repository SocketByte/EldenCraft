package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.SharedWorldBlocks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.*;

/** Campaign tooltips use the same damage, native scale and stamina rules as combat. */
public final class CampaignWeaponTooltips {
  /** {@code stamina} is the percentage of maximum stamina one attack costs. */
  public record Stats(double damage, double attackSpeed, double stamina, double stance) {}

  private CampaignWeaponTooltips() {}

  public static boolean active(Player player) {
    return CampaignConfig.current().enabled()
        && player != null
        && player.level().dimension().equals(SharedWorldBlocks.DIMENSION);
  }

  public static boolean weapon(ItemStack stack) {
    return CampaignConfig.current().weapons.containsKey(id(stack))
        || !CampaignCombat.kind(stack).equals("other");
  }

  /** Keep vanilla lore, durability, potion contents, charged ammunition and advanced metadata. */
  public static void details(
      ItemStack stack,
      TooltipDisplay display,
      Player player,
      Consumer<Component> output,
      Consumer<TooltipDisplay> vanilla) {
    if (!active(player)) {
      vanilla.accept(display);
      return;
    }
    if (stack.is(dev.eldencraft.bridge.PhysickItems.FLASK)) {
      CampaignPhysick.tooltip(stack, output);
      vanilla.accept(display);
      return;
    }
    boolean weapon = weapon(stack);
    var host = CampaignBridge.snapshot();
    var whetblades = host == null ? null : host.whetblades();
    if (weapon) stats(stack, player, whetblades, output);
    enchantments(stack, DataComponents.STORED_ENCHANTMENTS, display, whetblades, output);
    enchantments(stack, DataComponents.ENCHANTMENTS, display, whetblades, output);
    var hidden =
        display
            .withHidden(DataComponents.ENCHANTMENTS, true)
            .withHidden(DataComponents.STORED_ENCHANTMENTS, true);
    vanilla.accept(weapon ? hidden.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true) : hidden);
  }

  public static void stats(ItemStack stack, Player player, Consumer<Component> output) {
    stats(stack, player, null, output);
  }

  /**
   * An active elemental affinity splits attack power into physical and elemental parts. With the
   * paired character's whetblades, a dormant element shows the unsplit damage it actually deals.
   */
  public static void stats(
      ItemStack stack, Player player, Set<String> whetblades, Consumer<Component> output) {
    String kind = CampaignCombat.kind(stack);
    var config = CampaignConfig.current();
    var stats = values(stack, player);
    output.accept(
        Component.translatable("tooltip.eldencraft_bridge.weapon." + kind)
            .withStyle(style -> style.withColor(CampaignTooltipTheme.MUTED).withItalic(true)));
    output.accept(Component.empty());
    output.accept(CampaignTooltipTheme.heading("ATTRIBUTES"));
    boolean ranged = stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
    String element = "eldencraft_bridge:" + CampaignEnchantments.affinity(stack);
    int level = CampaignEnchantments.affinityLevel(stack);
    if (!ranged
        && CampaignEnchantments.element(element)
        && (whetblades == null || CampaignEnchantments.unlocked(whetblades, element)))
      output.accept(
          CampaignTooltipTheme.split(
              "Attack power",
              Long.toString(
                  Math.round(stats.damage() * CampaignEnchantments.physicalShare(level) / 100)),
              Long.toString(
                  Math.round(stats.damage() * CampaignEnchantments.elementShare(level) / 100)),
              CampaignTooltipTheme.enchantmentIcon(element),
              CampaignTooltipTheme.color(element)));
    else
      output.accept(
          CampaignTooltipTheme.row(
              CampaignTooltipTheme.Icon.DAMAGE,
              "Attack power",
              (ranged ? "~" : "") + number(stats.damage()),
              CampaignTooltipTheme.IVORY));
    output.accept(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.SPEED,
            ranged ? (stack.is(Items.BOW) ? "Draw speed" : "Reload speed") : "Attack speed",
            number(stats.attackSpeed()) + (ranged ? " shots/s" : " /s"),
            CampaignTooltipTheme.IVORY));
    output.accept(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.STAMINA,
            "Stamina cost",
            staminaLabel(stats),
            CampaignTooltipTheme.GREEN));
    if (config.stance.enabled())
      output.accept(
          CampaignTooltipTheme.row(
              CampaignTooltipTheme.Icon.DAMAGE,
              "Stance damage",
              number(stats.stance()),
              CampaignTooltipTheme.GOLD));
    if (ranged || kind.equals("mace") || kind.equals("spear"))
      output.accept(
          CampaignTooltipTheme.detail(
              Component.translatable(
                      ranged
                          ? "tooltip.eldencraft_bridge.arrow_variation"
                          : "tooltip.eldencraft_bridge.bonus." + kind)
                  .withStyle(style -> style.withColor(CampaignTooltipTheme.MUTED))));
    output.accept(Component.empty());
  }

  public static Stats values(ItemStack stack, Player player) {
    var config = CampaignConfig.current();
    String kind = CampaignCombat.kind(stack);
    var configured = config.weapons.get(id(stack));
    double damage, speed;
    if (stack.is(Items.BOW) || stack.is(Items.CROSSBOW)) {
      boolean bow = stack.is(Items.BOW);
      double base = bow ? config.bowBaseDamage : config.crossbowBaseDamage;
      // Vanilla arrows resolve ceil(base damage * impact speed), then add critical damage.
      damage = Math.ceil(base * (bow ? 3 : 3.15)) * config.nativeDamageScale;
      speed =
          bow
              ? 20d / BowItem.MAX_DRAW_DURATION
              : 20d / (player == null ? 25 : CrossbowItem.getChargeDuration(stack, player));
    } else {
      damage =
          configured == null ? attribute(stack, Attributes.ATTACK_DAMAGE, 1) : configured.damage();
      speed =
          configured == null
              ? attribute(stack, Attributes.ATTACK_SPEED, 4)
              : configured.attackSpeed();
      int sharpness = CampaignEnchantments.level(stack, "minecraft:sharpness");
      damage += CampaignEnchantments.sharpness(sharpness);
      damage *= config.nativeDamageScale;
    }
    double stance =
        stack.is(Items.BOW) || stack.is(Items.CROSSBOW)
            ? config.stance.projectile()
            : config.stanceDamage(id(stack));
    return new Stats(damage, speed, config.stamina.cost(kind), stance);
  }

  private static double attribute(
      ItemStack stack, net.minecraft.core.Holder<Attribute> attribute, double base) {
    var additions = new double[2];
    var totalMultiplier = new double[] {1};
    stack.forEachModifier(
        EquipmentSlot.MAINHAND,
        (type, modifier) -> {
          if (!type.equals(attribute)) return;
          switch (modifier.operation()) {
            case ADD_VALUE -> additions[0] += modifier.amount();
            case ADD_MULTIPLIED_BASE -> additions[1] += modifier.amount();
            case ADD_MULTIPLIED_TOTAL -> totalMultiplier[0] *= 1 + modifier.amount();
          }
        });
    double value = base + additions[0];
    return (value + value * additions[1]) * totalMultiplier[0];
  }

  public static void enchantments(
      ItemStack stack,
      DataComponentType<ItemEnchantments> type,
      TooltipDisplay display,
      Consumer<Component> output) {
    enchantments(stack, type, display, null, output);
  }

  /**
   * With the paired character's whetblades, an affinity it cannot use is marked dormant: combat
   * sends no buildup for it, so the tooltip must not imply that it applies.
   */
  public static void enchantments(
      ItemStack stack,
      DataComponentType<ItemEnchantments> type,
      TooltipDisplay display,
      Set<String> whetblades,
      Consumer<Component> output) {
    if (!display.shows(type)) return;
    var entries =
        stack.getOrDefault(type, ItemEnchantments.EMPTY).entrySet().stream()
            .filter(entry -> CampaignEnchantments.allowed(enchantmentId(entry.getKey())))
            .sorted(Comparator.comparing(entry -> enchantmentId(entry.getKey())))
            .toList();
    if (!entries.isEmpty())
      output.accept(
          CampaignTooltipTheme.heading(
              type == DataComponents.STORED_ENCHANTMENTS ? "INSCRIBED KNOWLEDGE" : "ENCHANTMENTS"));
    for (var entry : entries) {
      String id = enchantmentId(entry.getKey());
      for (Component line : CampaignTooltipTheme.effect(id, entry.getIntValue()))
        output.accept(line);
      if (whetblades != null
          && CampaignEnchantments.custom(id)
          && !CampaignEnchantments.unlocked(whetblades, id))
        output.accept(CampaignTooltipTheme.dormant(id));
    }
  }

  public static List<String> wrap(String text, int width) {
    var result = new ArrayList<String>();
    var line = new StringBuilder();
    for (String word : text.split("\\s+")) {
      if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
        result.add(line.toString());
        line.setLength(0);
      }
      if (!line.isEmpty()) line.append(' ');
      line.append(word);
    }
    if (!line.isEmpty()) result.add(line.toString());
    return result;
  }

  private static String enchantmentId(net.minecraft.core.Holder<Enchantment> enchantment) {
    return enchantment.unwrapKey().map(key -> key.identifier().toString()).orElse("");
  }

  private static String id(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  /** "20%", and what that is in points when the player's maximum is known: "20% (21)". */
  public static String staminaLabel(Stats stats) {
    double maximum = CampaignCombat.displayedMaximum();
    String percent = number(stats.stamina()) + "%";
    return maximum > 0 ? percent + " (" + number(stats.stamina() / 100 * maximum) + ")" : percent;
  }

  public static String number(double number) {
    return BigDecimal.valueOf(number)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }
}
