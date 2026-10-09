package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Ordinary-kill drops: shipped table, progression gates, deterministic rolls and saved cursor. */
public final class CampaignLootConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Runnable operation, String message) {
    checks++;
    try {
      operation.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError(message);
  }

  private static void rejects(JsonObject campaign, Set<String> bosses, String message) {
    checks++;
    try {
      CampaignLoot.parse(campaign, bosses);
    } catch (IOException expected) {
      return;
    }
    throw new AssertionError(message);
  }

  public static int verify(CampaignConfig rules) throws IOException {
    checks = 0;
    var table = rules.enemyLoot;
    check(
        table.equals(CampaignLoot.parse(new JsonObject(), Set.of())),
        "shipped enemyLoot equals the defaults older campaign files receive");
    check(table.dropOnFloor(), "ordinary drops land on the floor by default");
    check(table.rolls(99) == 0, "critters below the HP floor drop nothing");
    check(table.rolls(219) == 1 && table.rolls(1499) == 1, "ordinary enemy rolls once");
    check(table.rolls(1500) == 2 && table.rolls(2889) == 2, "large enemy rolls twice");
    check(table.rolls(4500) == 3 && table.rolls(Integer.MAX_VALUE) == 3, "rolls are capped");

    var none = Set.<String>of();
    var seed = CampaignLoot.seed("slot-0-character-1", 17, 9);
    check(
        table.roll(none, 2889, seed).equals(table.roll(none, 2889, seed)),
        "a kill's drops are reproducible after reconnecting");
    check(
        seed != CampaignLoot.seed("slot-0-character-1", 17, 10)
            && seed != CampaignLoot.seed("slot-0-character-1", 18, 9)
            && seed != CampaignLoot.seed("slot-1-character-1", 17, 9),
        "every kill, session and character rolls independently");

    // Expected yields per ordinary Limgrave kill before any boss victory.
    var early = sample(table, none, 219);
    double weight = 0;
    for (var entry : table.entries()) if (entry.unlocked(none)) weight += entry.weight();
    near(early.drops, table.dropChance(), .01, "one roll succeeds at the configured chance");
    near(
        early.count("minecraft:golden_apple"),
        table.dropChance() * 4 / weight,
        .0012,
        "golden apples drop about once in 65 early kills");
    near(
        early.count("minecraft:enchanted_golden_apple"),
        table.dropChance() / weight,
        .0006,
        "enchanted golden apples stay a rare early drop");
    check(
        early.count("minecraft:cooked_beef") == 0 && early.count("minecraft:golden_carrot") == 0,
        "better food waits for its material tier");
    check(early.withinBounds, "stack sizes stay within each entry's range");
    var iron = sample(table, Set.of("godrick"), 219);
    check(
        iron.count("minecraft:cooked_beef") > 0 && iron.count("minecraft:golden_carrot") == 0,
        "the iron tier adds cooked beef only");
    var late = sample(table, Set.of("godrick", "morgott"), 2889);
    check(late.count("minecraft:golden_carrot") > 0, "the diamond tier adds golden carrots");
    near(late.drops, 2 * table.dropChance(), .015, "large late enemies roll twice");

    var partial = new JsonObject();
    var override = new JsonObject();
    override.addProperty("dropChance", 0);
    partial.add("enemyLoot", override);
    var disabled = CampaignLoot.parse(partial, none);
    check(
        disabled.rolls(5000) == 0 && disabled.entries().equals(table.entries()),
        "dropChance 0 disables drops and omitted fields keep the defaults");
    var inventory = new JsonObject();
    var toggle = new JsonObject();
    toggle.addProperty("dropOnFloor", false);
    inventory.add("enemyLoot", toggle);
    var inventoryTable = CampaignLoot.parse(inventory, none);
    check(
        !inventoryTable.dropOnFloor() && inventoryTable.entries().equals(table.entries()),
        "dropOnFloor false sends drops to the inventory and keeps the defaults");
    var bosses = Set.of("godrick");
    for (var mutation :
        List.<java.util.function.Consumer<JsonObject>>of(
            o -> o.addProperty("dropChance", 1.5),
            o -> o.addProperty("dropChance", -0.1),
            o -> o.addProperty("maxRolls", 0),
            o -> o.addProperty("maxRolls", 9),
            o -> o.addProperty("dropOnFloor", "yes"),
            o -> o.addProperty("dropOnFloor", 1),
            o -> o.addProperty("minNativeHp", -1),
            o -> o.addProperty("extraRollNativeHp", 1.5),
            o -> o.add("entries", new JsonObject()),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"eldencraft:custom\",\"min\":1,\"max\":1,\"weight\":1}")),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"minecraft:bread\",\"min\":2,\"max\":1,\"weight\":1}")),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"minecraft:bread\",\"min\":0,\"max\":1,\"weight\":1}")),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"minecraft:bread\",\"min\":1,\"max\":65,\"weight\":1}")),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"minecraft:bread\",\"min\":1,\"max\":1,\"weight\":0}")),
            o ->
                o.add(
                    "entries",
                    entries("{\"item\":\"minecraft:bread\",\"min\":1,\"max\":1,\"weight\":-1}")),
            o ->
                o.add(
                    "entries",
                    entries(
                        "{\"item\":\"minecraft:bread\",\"min\":1,\"max\":1,\"weight\":1,"
                            + "\"unlock_any\":[\"misspelled_boss\"]}")))) {
      var campaign = new JsonObject();
      var loot = new JsonObject();
      mutation.accept(loot);
      campaign.add("enemyLoot", loot);
      rejects(campaign, bosses, "invalid enemyLoot rejected: " + loot);
    }
    var bad = new JsonObject();
    bad.addProperty("enemyLoot", 1);
    rejects(bad, bosses, "enemyLoot must be an object");
    var gated = new JsonObject();
    var custom = new JsonObject();
    custom.add(
        "entries",
        entries(
            "{\"item\":\"minecraft:bread\",\"min\":1,\"max\":1,\"weight\":1,"
                + "\"unlock_all\":[\"godrick\"]}"));
    gated.add("enemyLoot", custom);
    var gatedTable = CampaignLoot.parse(gated, bosses);
    check(
        gatedTable.roll(none, 219, 1).isEmpty()
            && gatedTable.entries().getFirst().unlocked(Set.of("godrick")),
        "a table with no unlocked entry drops nothing");

    var baseline = CampaignLoot.advance(null, 17, 9, List.of(new CampaignLoot.Kill(9, 300)));
    check(
        baseline.changed() && baseline.kills().isEmpty() && baseline.cursor().sequence() == 9,
        "attachment ignores kills from before this save");
    var saved = CampaignLoot.parse(baseline.cursor().tag());
    check(saved.equals(baseline.cursor()), "saved cursor round trip");
    check(
        !CampaignLoot.advance(saved, 17, 9, List.of()).changed(),
        "an unchanged sequence performs no save");
    var events =
        List.of(
            new CampaignLoot.Kill(8, 100),
            new CampaignLoot.Kill(9, 200),
            new CampaignLoot.Kill(10, 300),
            new CampaignLoot.Kill(12, 400));
    var kills = CampaignLoot.advance(saved, 17, 12, events);
    check(
        kills.changed()
            && kills.cursor().sequence() == 12
            && kills.kills().equals(List.of(events.get(2), events.get(3))),
        "only kills after the cursor roll; evicted kills are forfeited");
    check(
        CampaignLoot.advance(CampaignLoot.parse(kills.cursor().tag()), 17, 12, events)
            .kills()
            .isEmpty(),
        "a saved reload cannot replay drops");
    var session = CampaignLoot.advance(kills.cursor(), 18, 3, List.of(new CampaignLoot.Kill(3, 1)));
    check(
        session.kills().isEmpty() && session.cursor().session() == 18,
        "a new native session establishes a baseline");
    rejects(() -> CampaignLoot.advance(saved, 17, 8, List.of()), "sequence regression rejected");
    rejects(() -> CampaignLoot.advance(null, 0, 0, List.of()), "zero session rejected");
    rejects(() -> CampaignLoot.advance(null, 1, -1, List.of()), "negative sequence rejected");
    for (String tag :
        List.of(
            "eldencraft.loot.",
            "eldencraft.loot.1",
            "eldencraft.loot.1.2.3",
            "eldencraft.loot.a.2",
            "eldencraft.loot.0.2",
            "eldencraft.xp.1.2.3"))
      rejects(() -> CampaignLoot.parse(tag), "invalid receipt rejected: " + tag);
    return checks;
  }

  private static JsonArray entries(String row) {
    var array = new JsonArray();
    array.add(JsonParser.parseString(row));
    return array;
  }

  private record Sample(double drops, Map<String, Double> perKill, boolean withinBounds) {
    double count(String item) {
      return perKill.getOrDefault(item, 0d);
    }
  }

  private static Sample sample(CampaignLoot.Table table, Set<String> defeated, int maxHp) {
    var bounds = new HashMap<String, CampaignLoot.Entry>();
    for (var entry : table.entries()) bounds.put(entry.item(), entry);
    int kills = 200_000;
    long drops = 0;
    boolean within = true;
    var events = new HashMap<String, Double>();
    for (int i = 0; i < kills; i++)
      for (var drop : table.roll(defeated, maxHp, CampaignLoot.seed("sample", 1, i))) {
        drops++;
        events.merge(drop.item(), 1d / kills, Double::sum);
        var entry = bounds.get(drop.item());
        within &= drop.count() >= entry.min() && drop.count() <= entry.max();
      }
    return new Sample(drops / (double) kills, events, within);
  }

  private static void near(double actual, double expected, double tolerance, String message) {
    check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " vs " + expected);
  }
}
