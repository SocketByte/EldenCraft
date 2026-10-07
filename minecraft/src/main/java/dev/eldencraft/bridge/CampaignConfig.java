package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared, strictly validated campaign rules. Reload replaces one immutable configuration. */
public final class CampaignConfig {
  public record Weapon(double damage, double attackSpeed) {}

  public record Armor(double armor, double toughness, double knockbackResistance) {}

  public record Point(double level, double value) {}

  public record Reward(String item, int count) {}

  public record Experience(int mobBase, double mobPerNativeHp, int maxPerKill) {}

  public record Boss(
      String id, long eventFlag, boolean remembrance, List<Reward> rewards, int experience) {
    public Boss(String id, long eventFlag, boolean remembrance, List<Reward> rewards) {
      this(id, eventFlag, remembrance, rewards, 0);
    }
  }

  public record Progression(
      int shares,
      double startVigor,
      double endVigor,
      double startEndurance,
      double endEndurance,
      double baseMinecraftHealth,
      List<Point> healthCurve,
      List<Point> staminaCurve) {
    public double health(int victories) {
      double level = startVigor + (endVigor - startVigor) * fraction(victories);
      return baseMinecraftHealth
          * interpolate(healthCurve, level)
          / interpolate(healthCurve, startVigor);
    }

    public double stamina(int victories) {
      return interpolate(
          staminaCurve, startEndurance + (endEndurance - startEndurance) * fraction(victories));
    }

    private double fraction(int victories) {
      return Math.clamp(victories, 0, shares) / (double) shares;
    }
  }

  public record Stamina(
      Map<String, Double> costs,
      double regenPerSecond,
      double regenDelaySeconds,
      double guardBase,
      double guardPerDamage,
      double guardRecovery) {
    public double cost(String kind) {
      return costs.getOrDefault(kind, costs.getOrDefault("other", 12d));
    }
  }

  public record ResourceZone(long sourceMap, Set<Integer> hitMaterials, String block) {}

  public record Mining(
      Set<String> allowedBlocks, long regrowTicks, List<ResourceZone> resourceZones) {
    public String resource(long sourceMap, int hitMaterial) {
      for (var zone : resourceZones)
        if (zone.sourceMap == sourceMap
            && (zone.hitMaterials.contains(hitMaterial)
                || (hitMaterial >= 0 && zone.hitMaterials.contains(hitMaterial % 100))))
          return zone.block;
      return null;
    }
  }

  private static volatile CampaignConfig current = disabled();
  private final JsonObject raw;
  public final boolean enabled, debugKits;
  public final Map<String, Weapon> weapons;
  public final Map<String, Armor> armors;
  public final Progression progression;
  public final Stamina stamina;
  public final Mining mining;
  public final List<Reward> starterItems;
  public final List<Boss> bosses;
  public final Experience experience;
  public final CampaignLoot.Table enemyLoot;
  public final CampaignHudConfig hud;
  public final double bowBaseDamage,
      crossbowBaseDamage,
      nativeDamageScale,
      nativeIncomingDamageScale;

  private CampaignConfig(
      JsonObject raw,
      boolean enabled,
      boolean debugKits,
      Map<String, Weapon> weapons,
      Map<String, Armor> armors,
      Progression progression,
      Stamina stamina,
      Mining mining,
      List<Reward> starterItems,
      List<Boss> bosses,
      double bowBaseDamage,
      double crossbowBaseDamage,
      double nativeDamageScale,
      double nativeIncomingDamageScale,
      Experience experience,
      CampaignLoot.Table enemyLoot,
      CampaignHudConfig hud) {
    this.raw = raw.deepCopy();
    this.enabled = enabled;
    this.debugKits = debugKits;
    this.weapons = Map.copyOf(weapons);
    this.armors = Map.copyOf(armors);
    this.progression = progression;
    this.stamina = stamina;
    this.mining = mining;
    this.starterItems = List.copyOf(starterItems);
    this.bosses = List.copyOf(bosses);
    this.crossbowBaseDamage = crossbowBaseDamage;
    this.bowBaseDamage = bowBaseDamage;
    this.nativeDamageScale = nativeDamageScale;
    this.nativeIncomingDamageScale = nativeIncomingDamageScale;
    this.experience = experience;
    this.enemyLoot = enemyLoot;
    this.hud = hud;
  }

  public static CampaignConfig current() {
    return current;
  }

  public boolean enabled() {
    return enabled;
  }

  public boolean debugKits() {
    return debugKits;
  }

  public static void install(CampaignConfig config) {
    current = Objects.requireNonNull(config);
  }

  public JsonObject raw() {
    return raw.deepCopy();
  }

  public static CampaignConfig load(Path path) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(131073);
    }
    return parse(JsonWire.parse(bytes));
  }

  public static CampaignConfig parse(String text) throws IOException {
    return parse(JsonWire.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  public static CampaignConfig parse(JsonObject json) throws IOException {
    if (integer(json, "schemaVersion", 1, 1) != 1) throw new IOException("Campaign schemaVersion");
    boolean enabled = bool(json, "enabled", true), debug = bool(json, "debugKits", false);
    var weapons = new LinkedHashMap<String, Weapon>();
    var weaponRows = object(json, "weapons");
    for (var row : weaponRows.entrySet()) {
      String item = itemId(row.getKey());
      var w = row.getValue().getAsJsonObject();
      weapons.put(
          item, new Weapon(number(w, "damage", .1, 1000), number(w, "attackSpeed", .1, 20)));
    }
    var armors = new LinkedHashMap<String, Armor>();
    if (json.has("armors"))
      for (var row : object(json, "armors").entrySet()) {
        var a = row.getValue().getAsJsonObject();
        armors.put(
            itemId(row.getKey()),
            new Armor(
                number(a, "armor", 0, 30),
                number(a, "toughness", 0, 30),
                number(a, "knockbackResistance", 0, 1)));
      }
    var p = object(json, "progression");
    var hp = curve(p, "healthCurve");
    var sp = curve(p, "staminaCurve");
    var progression =
        new Progression(
            (int) integer(p, "shares", 1, 1024),
            number(p, "startVigor", 1, 99),
            number(p, "endVigor", 1, 99),
            number(p, "startEndurance", 1, 99),
            number(p, "endEndurance", 1, 99),
            number(p, "baseMinecraftHealth", 1, 1024),
            hp,
            sp);
    if (progression.endVigor < progression.startVigor
        || progression.endEndurance < progression.startEndurance
        || hp.getFirst().level > progression.startVigor
        || hp.getLast().level < progression.endVigor
        || sp.getFirst().level > progression.startEndurance
        || sp.getLast().level < progression.endEndurance
        || progression.health(progression.shares) > 1024)
      throw new IOException("Campaign progression curve coverage/range");
    var s = object(json, "stamina");
    var costs = new HashMap<String, Double>();
    for (var row : object(s, "costs").entrySet())
      costs.put(row.getKey(), bounded(row.getValue(), 0, 10000, "stamina cost"));
    var stamina =
        new Stamina(
            Map.copyOf(costs),
            number(s, "regenPerSecond", 0, 10000),
            number(s, "regenDelaySeconds", 0, 60),
            number(s, "guardBase", 0, 10000),
            number(s, "guardPerDamage", 0, 10000),
            number(s, "guardRecovery", 0, 10000));
    if (stamina.guardRecovery > progression.stamina(0))
      throw new IOException("guardRecovery exceeds starting stamina");
    var m = object(json, "mining");
    var allowed = new LinkedHashSet<String>();
    for (var value : array(m, "allowedBlocks", 256)) allowed.add(itemId(JsonWire.string(value)));
    var resources = new ArrayList<ResourceZone>();
    if (m.has("resourceZones"))
      for (var value : array(m, "resourceZones", 256)) {
        var zone = value.getAsJsonObject();
        var materials = new LinkedHashSet<Integer>();
        for (var hit : array(zone, "hitMaterials", 256))
          materials.add((int) JsonWire.integer(hit, 0, 65535));
        if (materials.isEmpty()) throw new IOException("Resource zone requires hit materials");
        resources.add(
            new ResourceZone(
                integer(zone, "sourceMap", 0, 0xffffffffL),
                Set.copyOf(materials),
                itemId(JsonWire.string(zone.get("block")))));
      }
    var mining =
        new Mining(
            Set.copyOf(allowed),
            integer(m, "regrowTicks", 1, Integer.MAX_VALUE),
            List.copyOf(resources));
    var bosses = new ArrayList<Boss>();
    var ids = new HashSet<String>();
    var flags = new HashSet<Long>();
    int shares = 0;
    for (var value : array(json, "bosses", 1024)) {
      var b = value.getAsJsonObject();
      String id = JsonWire.string(b.get("id"));
      if (!id.matches("[a-z0-9_-]{1,64}") || !ids.add(id))
        throw new IOException("Duplicate/invalid boss id");
      long flag = integer(b, "eventFlag", 1, 0xffffffffL);
      if (!flags.add(flag)) throw new IOException("Duplicate boss eventFlag");
      boolean remembrance = bool(b, "remembrance", false);
      if (remembrance) shares++;
      bosses.add(
          new Boss(
              id,
              flag,
              remembrance,
              b.has("rewards") ? rewards(b, "rewards") : List.of(),
              b.has("experience") ? (int) integer(b, "experience", 0, 1_000_000) : 0));
    }
    if (shares != progression.shares)
      throw new IOException("Remembrance bosses must match progression.shares");
    var combat = object(json, "combat");
    double crossbow = number(combat, "crossbowBaseDamage", .1, 1000);
    double bow = combat.has("bowBaseDamage") ? number(combat, "bowBaseDamage", .1, 1000) : 2;
    double nativeScale = number(combat, "nativeDamageScale", .1, 100000);
    double incomingScale =
        combat.has("nativeIncomingDamageScale")
            ? number(combat, "nativeIncomingDamageScale", .1, 1000)
            : 1;
    if (combat.has("nativeEnemyDamageMultipliers")) {
      var multipliers = object(combat, "nativeEnemyDamageMultipliers");
      if (multipliers.size() > 512)
        throw new IOException("At most 512 native enemy damage multipliers are allowed");
      for (var row : multipliers.entrySet()) {
        String id = row.getKey();
        if (!id.matches("0|[1-9][0-9]{0,9}") || Long.parseLong(id) > Integer.MAX_VALUE)
          throw new IOException("Native enemy damage multiplier requires a canonical NPC param id");
        bounded(row.getValue(), .01, 1000, "native enemy damage multiplier");
      }
    }
    CampaignShopCatalog.parse(json);
    var xp = json.has("experience") ? object(json, "experience") : new JsonObject();
    var experience =
        new Experience(
            xp.has("mobBase") ? (int) integer(xp, "mobBase", 0, 1_000_000) : 3,
            xp.has("mobPerNativeHp") ? number(xp, "mobPerNativeHp", 0, 1000) : .005,
            xp.has("maxPerKill") ? (int) integer(xp, "maxPerKill", 0, 1_000_000) : 100);
    return new CampaignConfig(
        json,
        enabled,
        debug,
        weapons,
        armors,
        progression,
        stamina,
        mining,
        rewards(json, "starterItems"),
        bosses,
        bow,
        crossbow,
        nativeScale,
        incomingScale,
        experience,
        CampaignLoot.parse(json, ids),
        CampaignHudConfig.parse(json));
  }

  private static CampaignConfig disabled() {
    var p =
        new Progression(
            15,
            10,
            60,
            10,
            30,
            20,
            List.of(new Point(10, 414), new Point(60, 1900)),
            List.of(new Point(10, 100), new Point(30, 130)));
    return new CampaignConfig(
        new JsonObject(),
        false,
        false,
        Map.of(),
        Map.of(),
        p,
        new Stamina(Map.of("other", 12d), 20, .5, 4, 1.5, 5),
        new Mining(Set.of(), 12000, List.of()),
        List.of(),
        List.of(),
        2,
        4,
        25,
        1,
        new Experience(3, .005, 100),
        new CampaignLoot.Table(0, 0, 0, 1, false, List.of()),
        CampaignHudConfig.defaults());
  }

  public static double interpolate(List<Point> curve, double level) {
    if (level <= curve.getFirst().level) return curve.getFirst().value;
    for (int i = 1; i < curve.size(); i++) {
      Point a = curve.get(i - 1), b = curve.get(i);
      if (level <= b.level)
        return a.value + (b.value - a.value) * (level - a.level) / (b.level - a.level);
    }
    return curve.getLast().value;
  }

  private static List<Point> curve(JsonObject json, String key) throws IOException {
    var out = new ArrayList<Point>();
    double last = -1, value = 0;
    for (var element : array(json, key, 99)) {
      var p = element.getAsJsonObject();
      double level = number(p, "level", 1, 99), next = number(p, "value", 1, 100000);
      if (level <= last || next < value)
        throw new IOException("Curve must increase in level and not decrease in value");
      out.add(new Point(level, next));
      last = level;
      value = next;
    }
    if (out.size() < 2) throw new IOException("Curve requires two points");
    return List.copyOf(out);
  }

  private static List<Reward> rewards(JsonObject json, String key) throws IOException {
    var out = new ArrayList<Reward>();
    for (var value : array(json, key, 256)) {
      var r = value.getAsJsonObject();
      out.add(
          new Reward(itemId(JsonWire.string(r.get("item"))), (int) integer(r, "count", 1, 4096)));
    }
    return List.copyOf(out);
  }

  private static String itemId(String value) throws IOException {
    if (!value.matches("minecraft:[a-z0-9_/.]+"))
      throw new IOException("Campaign items must be vanilla Minecraft ids");
    return value;
  }

  private static JsonObject object(JsonObject json, String key) throws IOException {
    var value = json.get(key);
    if (value == null || !value.isJsonObject()) throw new IOException("Expected object: " + key);
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject json, String key, int maximum) throws IOException {
    var value = json.get(key);
    if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum)
      throw new IOException("Expected bounded array: " + key);
    return value.getAsJsonArray();
  }

  private static boolean bool(JsonObject json, String key, boolean fallback) throws IOException {
    var value = json.get(key);
    if (value == null) return fallback;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IOException("Expected boolean: " + key);
    return value.getAsBoolean();
  }

  private static long integer(JsonObject json, String key, long min, long max) throws IOException {
    return JsonWire.integer(json.get(key), min, max);
  }

  private static double number(JsonObject json, String key, double min, double max)
      throws IOException {
    return bounded(json.get(key), min, max, key);
  }

  private static double bounded(JsonElement value, double min, double max, String key)
      throws IOException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IOException("Expected number: " + key);
    double n = value.getAsDouble();
    if (!Double.isFinite(n) || n < min || n > max) throw new IOException("Out of range: " + key);
    return n;
  }
}
