package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Real catalog validation and progression/stamina boundary checks; no synthetic gameplay claim. */
public final class CampaignConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < .000001, message + ": " + actual);
  }

  private static void rejects(JsonObject json, String message) throws Exception {
    checks++;
    try {
      CampaignConfig.parse(json);
    } catch (IOException | IllegalStateException expected) {
      return;
    }
    throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    checks += CampaignExperienceConformance.verify();
    checks += CampaignHudConformance.verify();
    var rules = CampaignConfig.load(Path.of("../config/campaign.json"));
    check(rules.enabled && !rules.debugKits, "campaign defaults on with free kits off");
    check(
        rules.bosses.stream().filter(CampaignConfig.Boss::remembrance).count() == 15,
        "all fifteen distinct remembrance encounters");
    near(rules.progression.health(0), 20, "starting Minecraft HP");
    near(rules.progression.health(15), 20 * 1900d / 414, "exact 60 Vigor endpoint");
    near(rules.progression.stamina(0), 50, "configured opening stamina endpoint");
    near(rules.progression.stamina(15), 90, "configured final remembrance stamina endpoint");
    near(rules.progression.health(99), rules.progression.health(15), "NG+ has no extra capacity");
    near(rules.progression.health(-1), 20, "lower cap");
    near(rules.progression.health(3), 20 * 652d / 414, "fractional shares avoid rounding loss");
    double last = 0;
    for (int count = 0; count <= 15; count++) {
      double health = rules.progression.health(count);
      check(health > last, "every remembrance grants meaningful HP " + count);
      last = health;
    }
    near(rules.weapons.get("minecraft:netherite_sword").damage(), 18, "material damage default");
    check(
        rules.weapons.get("minecraft:copper_sword").damage()
            > rules.weapons.get("minecraft:stone_sword").damage(),
        "copper reward beats gatherable stone");
    check(
        !rules.mining.allowedBlocks().contains("minecraft:iron_ore")
            && !rules.mining.allowedBlocks().contains("minecraft:oak_log"),
        "ordinary mining cannot bypass boss tiers");
    var account = new CampaignStamina(96);
    check(account.spend(24), "real attack expenditure");
    near(account.current(), 72, "attack cost");
    account.tick(.5, false, rules.stamina);
    near(account.current(), 72, "regeneration delay");
    account.tick(.25, false, rules.stamina);
    near(account.current(), 77, "regen after delay");
    account.tick(1, true, rules.stamina);
    near(account.current(), 77, "ranged load and guard pause regeneration");
    check(!account.spend(78), "insufficient attack denied");
    near(account.current(), 77, "denied attack does not spend");
    account.capacity(130);
    near(account.current(), 77, "capacity reward does not refill stamina");
    // 77 stamina pays for 77 of the 154 a 100-damage guard costs: half is blocked.
    near(
        CampaignStamina.absorbed(account.current(), 100, rules.stamina),
        .5,
        "low stamina absorbs only the share of the hit it can pay for");
    near(CampaignStamina.absorbed(200, 100, rules.stamina), 1, "covered guard blocks fully");
    near(CampaignStamina.absorbed(0, 100, rules.stamina), 0, "empty stamina blocks nothing");
    check(account.guard(100, 1, rules.stamina), "guard break is reported once");
    near(account.current(), 0, "valid guard hit exhausts remaining stamina");
    check(!account.guardReady(), "guard locks after exhaustion");
    check(!account.guard(10, 1, rules.stamina), "an already broken guard does not break again");
    near(
        CampaignStamina.recoverySeconds(rules.stamina),
        .75,
        "guard-break cooldown matches stamina recovery");
    account.tick(.5, false, rules.stamina);
    check(!account.guardReady(), "delay before guard recovery");
    account.tick(.25, false, rules.stamina);
    near(account.current(), 5, "guard threshold regeneration");
    check(account.guardReady(), "guard recovers at fixed threshold");
    account.rest();
    near(account.current(), 130, "rest capacity cap");
    account.guard(4, 2, rules.stamina);
    near(account.current(), 116, "multiple native guard events consume base per hit");
    account.tick(100, false, rules.stamina);
    near(account.current(), 130, "regeneration cannot overflow");
    check(account.spend(130), "spending the last stamina is allowed");
    check(
        !account.guardReady(), "an attack that exhausts stamina cannot buy one free shield block");
    var invalid = rules.raw();
    invalid
        .getAsJsonObject("weapons")
        .getAsJsonObject("minecraft:wooden_sword")
        .addProperty("damage", -1);
    rejects(invalid, "negative damage rejected");
    invalid = rules.raw();
    invalid.getAsJsonObject("progression").addProperty("shares", 14);
    rejects(invalid, "missing/remapped remembrance budget rejected");
    invalid = rules.raw();
    invalid
        .getAsJsonObject("progression")
        .getAsJsonArray("healthCurve")
        .get(1)
        .getAsJsonObject()
        .addProperty("level", 10);
    rejects(invalid, "duplicate curve level rejected");
    invalid = rules.raw();
    invalid
        .getAsJsonArray("bosses")
        .get(1)
        .getAsJsonObject()
        .addProperty(
            "eventFlag",
            invalid.getAsJsonArray("bosses").get(0).getAsJsonObject().get("eventFlag").getAsLong());
    rejects(invalid, "duplicate final completion flag rejected");
    invalid = rules.raw();
    invalid
        .getAsJsonArray("starterItems")
        .get(0)
        .getAsJsonObject()
        .addProperty("item", "eldencraft:custom_sword");
    rejects(invalid, "custom registry items rejected");
    var detached = rules.raw();
    detached.addProperty("enabled", false);
    check(rules.enabled, "immutable config publication");
    var xpOnly = rules.raw();
    var xpBoss = xpOnly.getAsJsonArray("bosses").get(0).getAsJsonObject();
    xpBoss.remove("rewards");
    xpBoss.addProperty("experience", 250);
    var parsedXp = CampaignConfig.parse(xpOnly).bosses.getFirst();
    check(
        parsedXp.rewards().isEmpty() && parsedXp.experience() == 250,
        "XP-only boss rewards do not require inventory items");
    xpBoss.remove("experience");
    check(
        CampaignConfig.parse(xpOnly).bosses.getFirst().experience() == 0,
        "omitted XP preserves existing campaign configurations");
    xpBoss.addProperty("experience", 1_000_000);
    check(
        CampaignConfig.parse(xpOnly).bosses.getFirst().experience() == 1_000_000,
        "bounded maximum XP reward accepted");
    xpBoss.addProperty("experience", -1);
    rejects(xpOnly, "negative XP reward rejected");
    xpBoss.addProperty("experience", 1_000_001);
    rejects(xpOnly, "excessive XP reward rejected");
    xpBoss.addProperty("experience", 1.5);
    rejects(xpOnly, "fractional XP reward rejected");
    var mobXp = rules.raw();
    mobXp.remove("experience");
    var defaults = CampaignConfig.parse(mobXp).experience;
    check(
        defaults.mobBase() == 3
            && defaults.mobPerNativeHp() == .005
            && defaults.maxPerKill() == 100,
        "omitted native XP rules preserve defaults");
    var configured = new JsonObject();
    configured.addProperty("mobBase", 5);
    mobXp.add("experience", configured);
    var partial = CampaignConfig.parse(mobXp).experience;
    check(
        partial.mobBase() == 5 && partial.mobPerNativeHp() == .005 && partial.maxPerKill() == 100,
        "partial native XP rules retain omitted defaults");
    configured.addProperty("maxPerKill", 0);
    check(
        CampaignConfig.parse(mobXp).experience.maxPerKill() == 0,
        "zero native XP cap disables mob awards");
    configured.addProperty("mobBase", -1);
    rejects(mobXp, "negative native XP base rejected");
    configured.addProperty("mobBase", 1.5);
    rejects(mobXp, "fractional native XP base rejected");
    configured.addProperty("mobBase", 3);
    configured.addProperty("mobPerNativeHp", -1);
    rejects(mobXp, "negative native HP XP multiplier rejected");
    configured.addProperty("mobPerNativeHp", .005);
    configured.addProperty("maxPerKill", 1_000_001);
    rejects(mobXp, "excessive native kill XP cap rejected");
    var npcRules = rules.raw();
    var multipliers = new JsonObject();
    npcRules.getAsJsonObject("combat").add("nativeEnemyDamageMultipliers", multipliers);
    multipliers.addProperty("100000", .5);
    check(
        CampaignConfig.parse(npcRules).nativeIncomingDamageScale == rules.nativeIncomingDamageScale,
        "per-NPC native damage factor validates without changing the global baseline");
    multipliers.addProperty("0100000", .5);
    rejects(npcRules, "noncanonical NPC param id rejected");
    multipliers.remove("0100000");
    multipliers.addProperty("100000", 0);
    rejects(npcRules, "zero per-NPC damage multiplier rejected");
    multipliers.addProperty("100000", .5);
    multipliers.addProperty("2147483648", 1);
    rejects(npcRules, "out-of-range NPC param id rejected");
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    dev.eldencraft.bridge.client.CampaignCombat.validateRegistry(rules);
    var unknownItem = rules.raw();
    unknownItem
        .getAsJsonArray("starterItems")
        .get(0)
        .getAsJsonObject()
        .addProperty("item", "minecraft:missing_sword");
    checks++;
    try {
      dev.eldencraft.bridge.client.CampaignCombat.validateRegistry(
          CampaignConfig.parse(unknownItem));
      throw new AssertionError(
          "Unknown item identities must reject the campaign before publication");
    } catch (IOException expected) {
    }
    // 26.3 binds item defaults after building the world registries, as the real resource load does.
    // Bootstrap alone creates item identities but intentionally leaves their components unbound.
    var registries = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
    net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
        .build(registries)
        .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
    checks += CampaignGuardConformance.verify();
    CampaignConfig.install(rules);
    check(rules.armors.size() >= 24, "shipped armor ladder is configurable");
    for (var row : rules.armors.entrySet()) {
      var item =
          net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
              net.minecraft.resources.Identifier.parse(row.getKey()));
      check(
          item != null && item != net.minecraft.world.item.Items.AIR,
          "shipped armor exists " + row.getKey());
      var stack = new net.minecraft.world.item.ItemStack(item);
      var slot = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE).slot();
      stack.setDamageValue(2);
      dev.eldencraft.bridge.client.CampaignCombat.tune(stack);
      var component = stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
      near(
          component.compute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, 0, slot),
          row.getValue().armor(),
          "real armor " + row.getKey());
      near(
          component.compute(
              net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS, 0, slot),
          row.getValue().toughness(),
          "real toughness " + row.getKey());
      near(
          component.compute(
              net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE, 0, slot),
          row.getValue().knockbackResistance(),
          "real knockback resistance " + row.getKey());
      check(stack.getDamageValue() == 2, "armor tuning preserves durability " + row.getKey());
      dev.eldencraft.bridge.client.CampaignCombat.tune(stack);
      check(
          component.equals(
              stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS)),
          "armor tuning is idempotent " + row.getKey());
    }
    var armorTotal =
        new net.minecraft.world.entity.ai.attributes.AttributeInstance(
            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, ignored -> {});
    var toughnessTotal =
        new net.minecraft.world.entity.ai.attributes.AttributeInstance(
            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS, ignored -> {});
    var knockbackTotal =
        new net.minecraft.world.entity.ai.attributes.AttributeInstance(
            net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE,
            ignored -> {});
    for (var suffix : List.of("helmet", "chestplate", "leggings", "boots")) {
      var item =
          net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
              net.minecraft.resources.Identifier.parse("minecraft:netherite_" + suffix));
      var stack = new net.minecraft.world.item.ItemStack(item);
      dev.eldencraft.bridge.client.CampaignCombat.tune(stack);
      var slot = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE).slot();
      stack
          .get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS)
          .forEach(
              slot,
              (attribute, modifier) -> {
                if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR))
                  armorTotal.addTransientModifier(modifier);
                if (attribute.equals(
                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS))
                  toughnessTotal.addTransientModifier(modifier);
                if (attribute.equals(
                    net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE))
                  knockbackTotal.addTransientModifier(modifier);
              });
    }
    near(armorTotal.getValue(), 20, "four netherite pieces stack armor");
    near(toughnessTotal.getValue(), 12, "four netherite pieces stack toughness");
    near(knockbackTotal.getValue(), .4, "four netherite pieces stack knockback resistance");
    for (var row : rules.weapons.entrySet()) {
      var item =
          net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
              net.minecraft.resources.Identifier.parse(row.getKey()));
      check(
          item != null && item != net.minecraft.world.item.Items.AIR,
          "shipped weapon exists " + row.getKey());
      var stack = new net.minecraft.world.item.ItemStack(item);
      stack.setDamageValue(2);
      dev.eldencraft.bridge.client.CampaignCombat.tune(stack);
      var component = stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
      near(
          component.compute(
              net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
              1,
              net.minecraft.world.entity.EquipmentSlot.MAINHAND),
          row.getValue().damage(),
          "real base damage " + row.getKey());
      near(
          component.compute(
              net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
              4,
              net.minecraft.world.entity.EquipmentSlot.MAINHAND),
          row.getValue().attackSpeed(),
          "real cooldown speed " + row.getKey());
      check(stack.getDamageValue() == 2, "tuning preserves durability " + row.getKey());
      dev.eldencraft.bridge.client.CampaignCombat.tune(stack);
      check(
          component.equals(
              stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS)),
          "repeated tuning is idempotent " + row.getKey());
    }
    for (var boss : rules.bosses)
      for (var reward : boss.rewards()) {
        var item =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.parse(reward.item()));
        check(
            item != null && item != net.minecraft.world.item.Items.AIR,
            "shipped boss item exists " + boss.id());
      }
    System.out.println("CampaignConformance: " + checks + " checks passed");
  }
}
