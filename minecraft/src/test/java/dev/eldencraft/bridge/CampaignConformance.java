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

  /** Attack stamina is a share of the player's maximum by weapon family; stance rises by tier. */
  private static void verifyWeaponStaminaAndStance(CampaignConfig rules) {
    for (var family : List.of("sword", "spear", "axe"))
      near(
          rules.stamina.fraction(family),
          rules.stamina.cost(family) / 100,
          family + " cost is a percentage of the maximum");
    near(rules.stamina.cost("stick"), rules.stamina.cost("other"), "unlisted items use 'other'");
    for (var weapon : rules.weapons.entrySet())
      check(
          !rules.raw().getAsJsonObject("weapons").getAsJsonObject(weapon.getKey()).has("stamina"),
          "no weapon carries its own stamina field");
    near(rules.stanceDamage("minecraft:stick"), rules.stance.fallback(), "fallback stance");
    check(
        rules.stance.enabled() && rules.stance.recoverySeconds() > 0,
        "stance is on in the shipped campaign");
    check(
        rules.stanceDamage("minecraft:mace") > rules.stanceDamage("minecraft:netherite_axe"),
        "the mace is the heaviest stance hitter");
  }

  private static void verifyGearStages(
      CampaignConfig rules, Map<String, CampaignShopCatalog.Offer> offers) {
    var stages =
        List.of(
            Set.<String>of(),
            Set.of("margit"),
            Set.of("godrick"),
            Set.of("rennala"),
            Set.of("radahn"),
            Set.of("morgott"),
            Set.of("maliketh"));
    int[] tiers = {0, 1, 2, 3, 3, 4, 5};
    var materials = List.of("wooden", "stone", "copper", "iron", "diamond", "netherite");
    var milestoneSwords =
        Map.of(
            "margit",
            "stone",
            "godrick",
            "copper",
            "rennala",
            "iron",
            "radahn",
            "iron",
            "morgott",
            "diamond",
            "maliketh",
            "netherite");
    for (var milestone : milestoneSwords.entrySet()) {
      var boss =
          rules.bosses.stream()
              .filter(b -> b.id().equals(milestone.getKey()))
              .findFirst()
              .orElseThrow();
      var rewards = CampaignGearTiers.cap(boss.rewards(), Set.of(boss.id()));
      check(
          rewards.stream()
              .anyMatch(r -> r.item().equals("minecraft:" + milestone.getValue() + "_sword")),
          "each milestone immediately grants its sword even on an alternate route: " + boss.id());
    }
    for (int i = 0; i < stages.size(); i++) {
      var defeated = stages.get(i);
      int tier = tiers[i];
      check(
          CampaignGearTiers.unlocked(defeated) == tier, "independent milestone unlock " + defeated);
      for (var row :
          Map.of("copper_sword", 2, "iron_sword", 3, "diamond_sword", 4, "netherite_sword", 5)
              .entrySet())
        check(
            offers.get(row.getKey()).unlocked(defeated) == (tier >= row.getValue()),
            "shop tier " + row.getKey() + " " + defeated);
      for (var row :
          Map.of(
                  "copper_ingots",
                  2,
                  "iron_ingots",
                  3,
                  "diamonds",
                  4,
                  "netherite_ingot",
                  5,
                  "netherite_template",
                  5)
              .entrySet())
        check(
            offers.get(row.getKey()).unlocked(defeated) == (tier >= row.getValue()),
            "recipe ingredient tier " + row.getKey() + " " + defeated);
      check(
          offers.get("crafting_table").unlocked(defeated) == (tier >= 1),
          "crafting fallback on every route " + defeated);
      check(
          offers.get("oak_logs").unlocked(defeated) == (tier >= 1),
          "raw wood cannot bypass stone unlock " + defeated);
      for (var boss : rules.bosses) {
        var rewards = CampaignGearTiers.cap(boss.rewards(), defeated);
        check(
            rewards.size() == boss.rewards().size(), "optional rewards are retained " + boss.id());
        for (int j = 0; j < rewards.size(); j++) {
          var reward = rewards.get(j);
          check(
              reward.count() == boss.rewards().get(j).count(),
              "reward quantities are preserved " + boss.id());
          for (int forbidden = tier + 1; forbidden < materials.size(); forbidden++)
            check(
                !reward.item().startsWith("minecraft:" + materials.get(forbidden) + "_")
                    && !reward.item().equals("minecraft:" + materials.get(forbidden)),
                "no optional boss bypass at " + defeated + ": " + boss.id() + " " + reward.item());
          if (tier < 3)
            check(
                !reward.item().startsWith("minecraft:chainmail_"),
                "chainmail waits for iron-tier armor");
        }
      }
    }
    var optionalOnly =
        Set.of(
            "rykard",
            "astel",
            "fortissax",
            "mohg",
            "malenia",
            "placidusax",
            "fire_giant",
            "godskin_duo");
    check(
        CampaignGearTiers.unlocked(optionalOnly) == 0,
        "non-milestone bosses cannot unlock material tiers");
    for (var id : List.of("copper_ingots", "iron_ingots", "diamonds", "netherite_ingot"))
      check(
          !offers.get(id).unlocked(optionalOnly),
          "optional route cannot unlock shop materials " + id);
    for (var utility :
        List.of(
            "ender_pearl",
            "obsidian",
            "smithing_table",
            "crafting_table",
            "oak_log",
            "crossbow",
            "golden_apple"))
      check(
          CampaignGearTiers.capItem("minecraft:" + utility, 0).equals("minecraft:" + utility),
          "utility reward remains usable " + utility);
    check(
        CampaignGearTiers.capItem("minecraft:netherite_upgrade_smithing_template", 4)
            .equals("minecraft:diamond"),
        "pre-Maliketh templates become diamond supplies");
    check(
        CampaignGearTiers.capItem("minecraft:netherite_chestplate", 1)
            .equals("minecraft:leather_chestplate"),
        "stone progression never produces nonexistent stone armor");
    check(
        CampaignGearTiers.capItem("minecraft:iron_sword", 5).equals("minecraft:iron_sword"),
        "older-region rewards keep their authored maximum tier");
    check(
        CampaignGearTiers.unlocked(Set.of("margit", "maliketh")) == 5,
        "out-of-order victory retains highest earned tier");
  }

  /** As in Elden Ring: any stamina left pays for one more attack, and emptying it waits longer. */
  private static void verifyExhaustion() {
    var rules = new CampaignConfig.Stamina(Map.of("other", 20d), 20, .5, 4, 1.5, 5, .25, 2, .25);
    near(rules.exhaustedDelaySeconds(), 1, "the exhausted delay multiplies the ordinary one");
    var account = new CampaignStamina(100);
    check(account.spend(90) && !account.exhausted(), "an affordable attack does not exhaust");
    check(account.canSpend(40), "any stamina left allows one more attack");
    check(account.spend(40), "the overdrawn attack goes through");
    check(
        account.current() == 0 && account.exhausted() && !account.guardReady(),
        "the overdrawn attack empties the bar, exhausts it and lowers the guard");
    check(!account.canSpend(1) && !account.spend(1), "an empty bar allows no further attack");
    near(account.current(), 0, "a denied attack spends nothing");
    account.tick(.75, false, rules);
    near(account.current(), 0, "recovery waits longer than the ordinary delay");
    check(account.exhausted(), "exhaustion lasts for the whole longer delay");
    account.tick(.5, false, rules);
    near(account.current(), 5, "recovery resumes once the exhausted delay has passed");
    check(!account.exhausted() && account.guardReady(), "the longer delay covers one wait");
    check(
        account.spend(1) && !account.exhausted(), "an attack that leaves stamina does not exhaust");
    account.tick(.5, false, rules);
    near(account.current(), 4, "the ordinary delay applies again");
    account.tick(.25, false, rules);
    near(account.current(), 9, "and recovery follows it");
    var exact = new CampaignStamina(50);
    check(exact.spend(50) && exact.exhausted(), "spending exactly the rest also exhausts");
    exact.rest();
    check(!exact.exhausted() && exact.current() == 50, "resting clears exhaustion");
    var guarded = new CampaignStamina(10);
    guarded.guard(100, 1, rules);
    check(
        guarded.current() == 0 && !guarded.exhausted(),
        "a broken guard keeps its own recovery and ordinary delay");
    var sliver = new CampaignStamina(1);
    sliver.spend(1 - 1e-12);
    check(
        sliver.current() == 0 && sliver.exhausted() && !sliver.canSpend(.5),
        "a rounding sliver is an empty bar");
    check(
        CampaignStamina.allows(.01, 50) && !CampaignStamina.allows(0, 50),
        "the client predicts attacks with the same rule");
  }

  public static void main(String[] args) throws Exception {
    checks += CampaignExperienceConformance.verify();
    checks += CampaignHudConformance.verify();
    var rules = CampaignConfig.load(Path.of("../config/campaign.json"));
    check(rules.enabled && !rules.debugKits, "campaign defaults on with free kits off");
    check(
        rules.bosses.stream().filter(CampaignConfig.Boss::remembrance).count() == 15,
        "all fifteen distinct remembrance encounters");
    near(rules.progression.health(0), 40, "starting Minecraft HP");
    near(rules.progression.health(15), 40 * 2314d / 828, "exact 60 Vigor endpoint");
    near(rules.progression.health(99), rules.progression.health(15), "NG+ has no extra capacity");
    near(rules.progression.health(-1), 40, "lower cap");
    near(rules.progression.health(3), 40 * 1066d / 828, "fractional shares avoid rounding loss");
    near(
        828 / rules.progression.health(0),
        414 / 20d,
        "the opening HP bonus keeps native HP per Minecraft HP fixed");
    double last = 0;
    for (int count = 0; count <= 15; count++) {
      double health = rules.progression.health(count);
      check(health > last, "every remembrance grants meaningful HP " + count);
      last = health;
    }
    checks += CampaignLootConformance.verify(rules);
    var offers = new HashMap<String, CampaignShopCatalog.Offer>();
    for (var shop : CampaignShopCatalog.parse(rules.raw()).shops())
      for (var offer : shop.offers()) offers.put(offer.id(), offer);
    var unit = new HashMap<String, Double>();
    for (var row :
        Map.of("copper", "copper_ingots", "iron", "iron_ingots", "diamond", "diamonds").entrySet())
      unit.put(
          row.getKey(),
          offers.get(row.getValue()).price() / (double) offers.get(row.getValue()).count());
    double upgrade =
        offers.get("netherite_ingot").price() + offers.get("netherite_template").price();
    var materials =
        Map.of(
            "sword",
            2,
            "axe",
            3,
            "pickaxe",
            3,
            "shovel",
            1,
            "helmet",
            5,
            "chestplate",
            8,
            "leggings",
            7,
            "boots",
            4);
    for (var material : List.of("copper", "iron", "diamond", "netherite"))
      for (var piece : materials.entrySet()) {
        var offer = offers.get(material + "_" + piece.getKey());
        if (offer == null) continue;
        double crafted =
            material.equals("netherite")
                ? unit.get("diamond") * piece.getValue() + upgrade
                : unit.get(material) * piece.getValue();
        check(
            offer.price() >= crafted,
            "ready-made " + offer.id() + " never undercuts its ingredients or upgrade");
      }
    check(
        offers.values().stream().noneMatch(o -> o.item().startsWith("minecraft:chainmail_")),
        "chainmail remains an optional-boss reward");
    near(rules.weapons.get("minecraft:netherite_sword").damage(), 15.6, "material damage default");
    near(
        rules.explosionDamageScale, .595, "TNT and creepers receive the increased explosion share");
    near(rules.lavaDamageScale, .075, "lava and fire keep a small share against native enemies");
    var older = rules.raw();
    older.getAsJsonObject("combat").remove("explosionDamageScale");
    older.getAsJsonObject("combat").remove("lavaDamageScale");
    var olderRules = CampaignConfig.parse(older);
    check(
        olderRules.explosionDamageScale == CampaignConfig.DEFAULT_EXPLOSION_SCALE
            && olderRules.lavaDamageScale == CampaignConfig.DEFAULT_LAVA_SCALE,
        "older campaign files receive the reduced hazard defaults");
    for (var bossId : List.of("margit", "godrick")) {
      String sword = bossId.equals("margit") ? "minecraft:stone_sword" : "minecraft:copper_sword";
      var boss = rules.bosses.stream().filter(b -> b.id().equals(bossId)).findFirst().orElseThrow();
      check(
          boss.eventFlag() == (bossId.equals("margit") ? 10000850L : 10000800L),
          bossId + " uses its encounter completion flag, avoiding ambiguous global boss flags");
      check(
          boss.rewards().stream().anyMatch(r -> r.item().equals(sword) && r.count() == 1)
              && boss.rewards().stream().filter(r -> r.item().endsWith("_sword")).count() == 1,
          bossId + " grants exactly its intended sword tier");
    }
    verifyGearStages(rules, offers);
    check(
        offers.get("totem_of_undying").unlockAny().isEmpty()
            && offers.get("totem_of_undying").stock() == -1,
        "totems of undying are sold from the start");
    check(
        rules.weapons.get("minecraft:copper_sword").damage()
            > rules.weapons.get("minecraft:stone_sword").damage(),
        "Godrick's copper reward beats Margit's gatherable stone");
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
    double regen = rules.stamina.regenPerSecond();
    double held = 72 + regen * .25;
    near(account.current(), held, "regen after delay");
    account.tick(1, true, rules.stamina);
    near(account.current(), held, "ranged load and guard pause regeneration");
    verifyExhaustion();
    account.capacity(130);
    near(account.current(), held, "capacity reward does not refill stamina");
    // Stamina pays for its own share of what a 100-damage guard costs; the rest gets through.
    double guardCost = rules.stamina.guardBase() + rules.stamina.guardPerDamage() * 100;
    near(
        CampaignStamina.absorbed(account.current(), 100, rules.stamina),
        Math.min(1, held / guardCost),
        "low stamina absorbs only the share of the hit it can pay for");
    near(CampaignStamina.absorbed(200, 100, rules.stamina), 1, "covered guard blocks fully");
    near(CampaignStamina.absorbed(0, 100, rules.stamina), 0, "empty stamina blocks nothing");
    check(account.guard(100, 1, rules.stamina), "guard break is reported once");
    near(account.current(), 0, "valid guard hit exhausts remaining stamina");
    check(!account.guardReady(), "guard locks after exhaustion");
    check(!account.guard(10, 1, rules.stamina), "an already broken guard does not break again");
    near(
        CampaignStamina.recoverySeconds(rules.stamina),
        rules.stamina.regenDelaySeconds()
            + rules.stamina.guardRecovery() / regen
            + rules.stamina.brokenShieldDelaySeconds(),
        "guard-break cooldown matches stamina recovery and the shield delay");
    account.tick(.5, false, rules.stamina);
    check(!account.guardReady(), "delay before guard recovery");
    account.tick(.25, false, rules.stamina);
    near(account.current(), regen * .25, "guard threshold regeneration");
    boolean recovered = regen * .25 >= rules.stamina.guardRecovery();
    check(
        account.guardReady() == (recovered && rules.stamina.brokenShieldDelaySeconds() <= 0),
        "a guard broken by a hit stays down through the shield delay after recovery");
    if (recovered) {
      account.tick(rules.stamina.brokenShieldDelaySeconds(), false, rules.stamina);
      check(account.guardReady(), "the shield can be raised once the shield delay has run");
    }
    var attacked = new CampaignStamina(10);
    attacked.spend(10);
    attacked.tick(rules.stamina.exhaustedDelaySeconds() + 1, false, rules.stamina);
    check(attacked.guardReady(), "an exhausting attack adds no broken-guard shield delay");
    account.rest();
    near(account.current(), 130, "rest capacity cap");
    account.guard(4, 2, rules.stamina);
    near(
        account.current(),
        130 - (rules.stamina.guardBase() * 2 + rules.stamina.guardPerDamage() * 4),
        "multiple native guard events consume base per hit");
    account.tick(100, false, rules.stamina);
    near(account.current(), 130, "regeneration cannot overflow");
    check(account.spend(130), "spending the last stamina is allowed");
    check(
        !account.guardReady(), "an attack that exhausts stamina cannot buy one free shield block");
    verifyWeaponStaminaAndStance(rules);
    var invalid = rules.raw();
    invalid.getAsJsonObject("stamina").getAsJsonObject("costs").addProperty("sword", 101);
    rejects(invalid, "a stamina share above 100 percent rejected");
    invalid = rules.raw();
    invalid.getAsJsonObject("stamina").getAsJsonObject("costs").addProperty("sword", -1);
    rejects(invalid, "a negative stamina share rejected");
    var raise = rules.raw();
    raise.getAsJsonObject("stamina").remove("shieldRaiseSeconds");
    check(
        CampaignConfig.parse(raise).stamina.shieldRaiseTicks() == 0,
        "an older file raises shields instantly");
    raise.getAsJsonObject("stamina").remove("brokenShieldDelaySeconds");
    near(
        CampaignConfig.parse(raise).stamina.brokenShieldDelaySeconds(),
        CampaignConfig.Stamina.DEFAULT_BROKEN_SHIELD_DELAY_SECONDS,
        "an older file takes the default broken-guard shield delay");
    raise.getAsJsonObject("stamina").addProperty("brokenShieldDelaySeconds", 6);
    rejects(raise, "broken-guard shield delay 6 rejected");
    raise.getAsJsonObject("stamina").addProperty("brokenShieldDelaySeconds", -.1);
    rejects(raise, "negative broken-guard shield delay rejected");
    raise.getAsJsonObject("stamina").addProperty("brokenShieldDelaySeconds", .1);
    raise.getAsJsonObject("stamina").addProperty("shieldRaiseSeconds", 0);
    check(
        CampaignConfig.parse(raise).stamina.shieldRaiseTicks() == 0,
        "a zero shield cast time blocks at once");
    raise.getAsJsonObject("stamina").addProperty("shieldRaiseSeconds", .5);
    check(
        CampaignConfig.parse(raise).stamina.shieldRaiseTicks() == 10,
        "the shield cast time rounds to ticks as vanilla does");
    for (double outside : new double[] {-.05, 5.5}) {
      raise.getAsJsonObject("stamina").addProperty("shieldRaiseSeconds", outside);
      rejects(raise, "shield cast time " + outside + " rejected");
    }
    var exhaustion = rules.raw();
    exhaustion.getAsJsonObject("stamina").remove("exhaustedDelayMultiplier");
    check(
        CampaignConfig.parse(exhaustion).stamina.exhaustedDelayMultiplier()
            == CampaignConfig.Stamina.DEFAULT_EXHAUSTED_DELAY_MULTIPLIER,
        "an older file doubles the delay after an exhausting attack");
    exhaustion.getAsJsonObject("stamina").addProperty("exhaustedDelayMultiplier", 1);
    check(
        CampaignConfig.parse(exhaustion).stamina.exhaustedDelaySeconds()
            == CampaignConfig.parse(exhaustion).stamina.regenDelaySeconds(),
        "a multiplier of 1 keeps the ordinary delay");
    for (double outside : new double[] {.5, 11}) {
      exhaustion.getAsJsonObject("stamina").addProperty("exhaustedDelayMultiplier", outside);
      rejects(exhaustion, "exhausted delay multiplier " + outside + " rejected");
    }
    invalid = rules.raw();
    invalid
        .getAsJsonObject("weapons")
        .getAsJsonObject("minecraft:wooden_sword")
        .addProperty("stance", -1);
    rejects(invalid, "negative weapon stance rejected");
    invalid = rules.raw();
    invalid.getAsJsonObject("combat").getAsJsonObject("stance").addProperty("recoverySeconds", 0);
    rejects(invalid, "stance recovery must be positive");
    invalid = rules.raw();
    invalid.getAsJsonObject("combat").getAsJsonObject("stance").addProperty("capacityScale", 0);
    rejects(invalid, "stance capacity scale must be positive");
    invalid = rules.raw();
    invalid
        .getAsJsonObject("weapons")
        .getAsJsonObject("minecraft:wooden_sword")
        .addProperty("damage", -1);
    rejects(invalid, "negative damage rejected");
    invalid = rules.raw();
    invalid.getAsJsonObject("combat").addProperty("explosionDamageScale", -.1);
    rejects(invalid, "negative explosion scale rejected");
    invalid = rules.raw();
    invalid.getAsJsonObject("combat").addProperty("lavaDamageScale", "low");
    rejects(invalid, "non-numeric lava scale rejected");
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
    checks += CampaignArmorConformance.verify(rules);
    checks += CampaignDurabilityConformance.verify(rules);
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
          0,
          "legacy toughness cannot add mitigation " + row.getKey());
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
    // Vanilla's display attribute caps at 30; campaign mitigation sums the
    // equipped percentages independently (verified in CampaignArmorConformance).
    near(armorTotal.getValue(), 30, "vanilla armor display attribute retains its cap");
    near(toughnessTotal.getValue(), 0, "percentage armor has no toughness modifier");
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
