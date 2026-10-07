package dev.eldencraft.bridge;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.client.CampaignCombat;
import java.io.IOException;
import java.util.EnumMap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Uses real bound equipment components and the production server's slot-based calculation. */
final class CampaignArmorConformance {
  private static int checks;

  private CampaignArmorConformance() {}

  static int verify(CampaignConfig shipped) throws Exception {
    checks = 0;
    var json = shipped.raw();
    var armors = new JsonObject();
    for (var row :
        new Object[][] {
          {"minecraft:iron_helmet", 5}, {"minecraft:iron_chestplate", 10},
          {"minecraft:iron_leggings", 40}, {"minecraft:iron_boots", 60}
        }) {
      var armor = new JsonObject();
      armor.addProperty("armor", (Number) row[1]);
      armors.add((String) row[0], armor);
    }
    // Old files remain accepted, but toughness cannot change the new formula.
    armors.getAsJsonObject("minecraft:iron_helmet").addProperty("toughness", 30);
    json.add("armors", armors);
    try {
      var rules = CampaignConfig.parse(json);
      CampaignConfig.install(rules);
      check(
          rules.armors.get("minecraft:iron_chestplate").toughness() == 0,
          "armor-only config rows do not need legacy toughness");
      var equipment = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
      equipment.put(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
      equipment.put(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
      java.util.function.Function<EquipmentSlot, ItemStack> worn =
          slot -> equipment.getOrDefault(slot, ItemStack.EMPTY);
      near(CampaignCombat.armorReduction(worn), 15, "5% helmet plus 10% chestplate is 15%");
      for (double raw : new double[] {10, 100, 1000})
        near(
            CampaignCombat.damageAfterArmor(raw, CampaignCombat.armorReduction(worn)),
            raw * .85,
            "the same 15% applies to each hit size");
      equipment.put(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_BOOTS));
      equipment.put(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_LEGGINGS));
      near(CampaignCombat.armorReduction(worn), 15, "carried pieces cannot add protection");
      equipment.put(EquipmentSlot.HEAD, new ItemStack(Items.IRON_CHESTPLATE));
      near(
          CampaignCombat.armorReduction(worn),
          10,
          "a piece in the wrong armor slot does not count");
      equipment.put(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
      near(
          CampaignCombat.armorReduction(worn),
          10,
          "unconfigured pieces add no hidden vanilla armor");
      var helmet = new ItemStack(Items.IRON_HELMET);
      helmet.setDamageValue(helmet.getMaxDamage());
      equipment.put(EquipmentSlot.HEAD, helmet);
      near(CampaignCombat.armorReduction(worn), 10, "broken pieces cannot add protection");
      helmet.setDamageValue(2);
      equipment.put(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
      near(
          CampaignCombat.armorReduction(worn), 55, "totals above Minecraft's 30-point cap survive");
      near(
          CampaignCombat.damageAfterArmor(100, CampaignCombat.armorReduction(worn)),
          45,
          "55 percentage points leave 45% damage");
      equipment.put(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
      near(CampaignCombat.armorReduction(worn), 100, "full-set totals stop at 100%");
      near(
          CampaignCombat.damageAfterArmor(100, CampaignCombat.armorReduction(worn)),
          0,
          "100% mitigation does not cause damage or healing");
      equipment.clear();
      near(CampaignCombat.armorReduction(worn), 0, "removing equipment removes its reduction");
      near(CampaignCombat.damageAfterArmor(100, 0), 100, "no armor leaves full damage");
      armors.getAsJsonObject("minecraft:iron_helmet").addProperty("armor", 5.5);
      CampaignConfig.install(CampaignConfig.parse(json));
      equipment.put(EquipmentSlot.HEAD, helmet);
      equipment.put(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
      near(CampaignCombat.armorReduction(worn), 15.5, "fractional percentages stay precise");
      near(
          CampaignCombat.damageAfterArmor(100, CampaignCombat.armorReduction(worn)),
          84.5,
          "fractional damage reduction is not rounded to vanilla armor points");
      CampaignCombat.tune(helmet);
      var attributes = helmet.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
      near(
          attributes.compute(
              net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS,
              0,
              EquipmentSlot.HEAD),
          0,
          "legacy toughness is removed from equipped modifiers");
      check(helmet.getDamageValue() == 2, "percentage tuning preserves existing durability");
      check(
          CampaignConfig.current()
              .armors
              .get("minecraft:iron_helmet")
              .reductionLabel()
              .equals("5.5% damage reduction"),
          "equipment labels show literal percentages");
      for (double invalid : new double[] {-1, 100.1, Double.NaN, Double.POSITIVE_INFINITY}) {
        armors.getAsJsonObject("minecraft:iron_helmet").addProperty("armor", invalid);
        checks++;
        try {
          CampaignConfig.parse(json);
          throw new AssertionError("Out-of-range or non-finite percentage accepted: " + invalid);
        } catch (IOException expected) {
        }
      }
      armors.getAsJsonObject("minecraft:iron_helmet").addProperty("armor", 100);
      near(
          CampaignConfig.parse(json).armors.get("minecraft:iron_helmet").armor(),
          100,
          "a single piece may specify exactly 100%");
    } finally {
      CampaignConfig.install(shipped);
    }
    return checks;
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < .000001, message + ": " + actual);
  }

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
}
