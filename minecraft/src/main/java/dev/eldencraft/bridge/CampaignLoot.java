package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/**
 * Weighted drops for confirmed ordinary (non-boss) native kills. The saved cursor shares the
 * vanilla player save with the delivered items, and each roll is seeded by the kill's native
 * identity, so reconnecting can neither replay nor reroll a drop.
 */
public final class CampaignLoot {
  public static final String PREFIX = "eldencraft.loot.";
  public static final int MAX_EVENTS = 64;
  private static final long MAX_COUNTER = 9_000_000_000_000_000L;
  private static final List<String> IRON_TIER = List.of("rennala", "radahn", "morgott", "maliketh");
  private static final List<String> DIAMOND_TIER = List.of("morgott", "maliketh");

  public record Entry(
      String item, int min, int max, double weight, Set<String> unlockAny, Set<String> unlockAll) {
    public Entry {
      unlockAny = Set.copyOf(unlockAny);
      unlockAll = Set.copyOf(unlockAll);
    }

    public boolean unlocked(Set<String> defeated) {
      return defeated.containsAll(unlockAll)
          && (unlockAny.isEmpty() || unlockAny.stream().anyMatch(defeated::contains));
    }
  }

  public record Table(
      double dropChance,
      int minNativeHp,
      int extraRollNativeHp,
      int maxRolls,
      boolean dropOnFloor,
      List<Entry> entries) {
    public Table {
      entries = List.copyOf(entries);
    }

    /** One roll, plus one per {@code extraRollNativeHp} of the enemy's maximum HP. */
    public int rolls(int maxHp) {
      if (maxHp < minNativeHp || dropChance <= 0 || entries.isEmpty()) return 0;
      long extra = extraRollNativeHp > 0 ? maxHp / extraRollNativeHp : 0;
      return (int) Math.min(maxRolls, 1 + extra);
    }

    public List<CampaignConfig.Reward> roll(Set<String> defeated, int maxHp, long seed) {
      var available = entries.stream().filter(e -> e.unlocked(defeated)).toList();
      double total = available.stream().mapToDouble(Entry::weight).sum();
      var random = new SplittableRandom(seed);
      var drops = new ArrayList<CampaignConfig.Reward>();
      for (int i = rolls(maxHp); i > 0 && total > 0; i--) {
        if (random.nextDouble() >= dropChance) continue;
        double pick = random.nextDouble() * total;
        var chosen = available.getLast();
        for (var entry : available) {
          if (pick < entry.weight()) {
            chosen = entry;
            break;
          }
          pick -= entry.weight();
        }
        int count = chosen.min() + random.nextInt(chosen.max() - chosen.min() + 1);
        drops.add(new CampaignConfig.Reward(chosen.item(), count));
      }
      return List.copyOf(drops);
    }
  }

  /**
   * A confirmed ordinary kill. {@code position} is the death point in the shared world's stable
   * region frame of anchor {@code map}, or null when native had no live shared world.
   */
  public record Kill(long seq, int maxHp, long map, WorldOrigin.Vec position) {
    public Kill(long seq, int maxHp) {
      this(seq, maxHp, -1, null);
    }
  }

  public record Cursor(long session, long sequence) {
    public String tag() {
      return PREFIX + session + "." + sequence;
    }
  }

  public record Update(Cursor cursor, List<Kill> kills, boolean changed) {}

  private CampaignLoot() {}

  /** Food, ammunition and crafting junk; better food joins the table with material tiers. */
  public static Table defaults() {
    return new Table(
        .4,
        100,
        1500,
        3,
        true,
        List.of(
            entry("minecraft:bread", 1, 2, 18),
            entry("minecraft:apple", 1, 2, 10),
            entry("minecraft:baked_potato", 1, 2, 6),
            entry("minecraft:rotten_flesh", 1, 3, 14),
            entry("minecraft:bone", 1, 2, 10),
            entry("minecraft:string", 1, 2, 8),
            entry("minecraft:stick", 1, 3, 8),
            entry("minecraft:feather", 1, 2, 7),
            entry("minecraft:flint", 1, 1, 6),
            entry("minecraft:arrow", 2, 4, 8),
            entry("minecraft:leather", 1, 1, 4),
            entry("minecraft:golden_apple", 1, 1, 4),
            entry("minecraft:enchanted_golden_apple", 1, 1, 1),
            new Entry("minecraft:cooked_beef", 1, 2, 14, Set.copyOf(IRON_TIER), Set.of()),
            new Entry("minecraft:golden_carrot", 1, 2, 8, Set.copyOf(DIAMOND_TIER), Set.of())));
  }

  private static Entry entry(String item, int min, int max, double weight) {
    return new Entry(item, min, max, weight, Set.of(), Set.of());
  }

  /**
   * Optional top-level {@code enemyLoot}; omitted fields keep the defaults, so existing campaign
   * files gain drops on upgrade. Explicit entries may only gate on configured bosses.
   */
  public static Table parse(JsonObject campaign, Set<String> bossIds) throws IOException {
    var d = defaults();
    if (!campaign.has("enemyLoot")) return d;
    var value = campaign.get("enemyLoot");
    if (!value.isJsonObject()) throw new IOException("Expected object: enemyLoot");
    var loot = value.getAsJsonObject();
    double chance = loot.has("dropChance") ? number(loot, "dropChance", 0, 1) : d.dropChance();
    int minHp =
        loot.has("minNativeHp")
            ? (int) JsonWire.integer(loot.get("minNativeHp"), 0, 100_000_000)
            : d.minNativeHp();
    int extra =
        loot.has("extraRollNativeHp")
            ? (int) JsonWire.integer(loot.get("extraRollNativeHp"), 0, 100_000_000)
            : d.extraRollNativeHp();
    int rolls =
        loot.has("maxRolls") ? (int) JsonWire.integer(loot.get("maxRolls"), 1, 8) : d.maxRolls();
    boolean floor = d.dropOnFloor();
    if (loot.has("dropOnFloor")) {
      var flag = loot.get("dropOnFloor");
      if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean())
        throw new IOException("Expected boolean: dropOnFloor");
      floor = flag.getAsBoolean();
    }
    var entries = d.entries();
    if (loot.has("entries")) {
      var rows = loot.get("entries");
      if (!rows.isJsonArray() || rows.getAsJsonArray().size() > 256)
        throw new IOException("enemyLoot.entries must be an array with at most 256 entries");
      var parsed = new ArrayList<Entry>();
      for (var row : rows.getAsJsonArray()) {
        if (!row.isJsonObject()) throw new IOException("Loot entry object required");
        var e = row.getAsJsonObject();
        String item = JsonWire.string(e.get("item"));
        if (!item.matches("minecraft:[a-z0-9_/.]+"))
          throw new IOException("Loot items must be vanilla Minecraft ids");
        int min = (int) JsonWire.integer(e.get("min"), 1, 64);
        int max = (int) JsonWire.integer(e.get("max"), min, 64);
        var any = bosses(e, "unlock_any", bossIds);
        var all = bosses(e, "unlock_all", bossIds);
        parsed.add(new Entry(item, min, max, number(e, "weight", 0, 1_000_000), any, all));
      }
      entries = parsed;
    }
    for (var entry : entries)
      if (!(entry.weight() > 0)) throw new IOException("Loot weights must be positive");
    return new Table(chance, minHp, extra, rolls, floor, entries);
  }

  public static Cursor parse(String tag) {
    if (!tag.startsWith(PREFIX)) throw new IllegalArgumentException("Not a campaign loot receipt");
    String[] fields = tag.substring(PREFIX.length()).split("\\.", -1);
    if (fields.length != 2) throw new IllegalArgumentException("Invalid campaign loot receipt");
    try {
      var value = new Cursor(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
      validate(value);
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Invalid campaign loot receipt", error);
    }
  }

  /**
   * Kills after the saved cursor, from the native bounded event list. A new native session
   * establishes a baseline; kills already evicted from the 64-event list are forfeited.
   */
  public static Update advance(Cursor previous, long session, long latest, List<Kill> events) {
    var observed = new Cursor(session, latest);
    validate(observed);
    if (previous == null || previous.session() != session)
      return new Update(observed, List.of(), true);
    validate(previous);
    if (latest < previous.sequence())
      throw new IllegalArgumentException("Native loot sequence regressed");
    if (latest == previous.sequence()) return new Update(previous, List.of(), false);
    var kills =
        events.stream()
            .filter(kill -> kill.seq() > previous.sequence() && kill.seq() <= latest)
            .toList();
    return new Update(observed, kills, true);
  }

  public static long seed(String character, long session, long sequence) {
    return mix(mix(mix(character.hashCode()) ^ session) ^ sequence);
  }

  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  private static void validate(Cursor value) {
    if (value.session() < 1 || value.sequence() < 0 || value.sequence() > MAX_COUNTER)
      throw new IllegalArgumentException("Campaign loot receipt outside bounds");
  }

  private static Set<String> bosses(JsonObject entry, String key, Set<String> bossIds)
      throws IOException {
    if (!entry.has(key)) return Set.of();
    var value = entry.get(key);
    if (!value.isJsonArray() || value.getAsJsonArray().size() > 256)
      throw new IOException("Invalid loot " + key);
    var out = new LinkedHashSet<String>();
    for (var id : value.getAsJsonArray()) {
      String boss = JsonWire.string(id);
      if (!bossIds.contains(boss)) throw new IOException("Loot " + key + " names unknown boss");
      out.add(boss);
    }
    return out;
  }

  private static double number(JsonObject json, String key, double min, double max)
      throws IOException {
    var value = json.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IOException("Expected number: " + key);
    double n = value.getAsDouble();
    if (!Double.isFinite(n) || n < min || n > max) throw new IOException("Out of range: " + key);
    return n;
  }
}
