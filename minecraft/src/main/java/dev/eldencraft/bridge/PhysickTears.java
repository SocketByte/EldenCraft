package dev.eldencraft.bridge;

import java.util.*;

/**
 * Base-game Crystal Tears for the Flask of Wondrous Physick and the pure rules for mixing them.
 * Order, Goods IDs, copies and durations come from the installed regulation (11711000); the native
 * side publishes one tear name per owned copy.
 */
public final class PhysickTears {
  /** How much plumbing a tear's Minecraft effect needs. */
  public enum Route {
    /** A vanilla effect or heal EldenCraft already carries into native combat. */
    READY,
    /** Minecraft-side logic only. */
    HOOK,
    /** A multiplier or one-shot read by native combat. */
    BRIDGE,
    /** The tear's own Elden Ring SpEffect, applied to the character. */
    NATIVE
  }

  /** A duration of -1 lasts until death, 0 is instant. */
  public enum Tear {
    CRIMSON(
        "crimson",
        "Crimson Crystal Tear",
        2,
        0,
        Route.READY,
        0xd0313d,
        "Heal 50%",
        "Restores half of max HP.",
        "Heals 50% of max health. Both Crimson tears: a full heal."),
    CRIMSONSPILL(
        "crimsonspill",
        "Crimsonspill Crystal Tear",
        1,
        180,
        Route.READY,
        0xe0505a,
        "Absorption",
        "Raises max HP by 10%.",
        "Absorption hearts worth about 10% of max health."),
    CRIMSONBURST(
        "crimsonburst",
        "Crimsonburst Crystal Tear",
        1,
        180,
        Route.READY,
        0xef4a3a,
        "Regeneration I",
        "Restores HP over time.",
        "Regeneration I."),
    CERULEAN(
        "cerulean",
        "Cerulean Crystal Tear",
        2,
        0,
        Route.HOOK,
        0x3d7be0,
        "Food +10",
        "Restores FP.",
        "Restores 10 food and 5 saturation. Both Cerulean tears: a full food bar."),
    GREENSPILL(
        "greenspill",
        "Greenspill Crystal Tear",
        1,
        180,
        Route.HOOK,
        0x4fbf5a,
        "Stamina +15%",
        "Raises max stamina.",
        "+15% stamina capacity."),
    GREENBURST(
        "greenburst",
        "Greenburst Crystal Tear",
        1,
        180,
        Route.HOOK,
        0x8bd650,
        "Stamina regen +30%",
        "Speeds stamina recovery.",
        "+30% stamina recovery."),
    STRENGTH_KNOT(
        "strength_knot",
        "Strength-knot Crystal Tear",
        1,
        180,
        Route.READY,
        0xd98b4a,
        "Strength I",
        "Raises Strength.",
        "Strength I: +3 attack, carried into native damage."),
    DEXTERITY_KNOT(
        "dexterity_knot",
        "Dexterity-knot Crystal Tear",
        1,
        180,
        Route.READY,
        0xe3cf5a,
        "Haste I",
        "Raises Dexterity.",
        "Haste I: the attack cooldown recharges faster."),
    INTELLIGENCE_KNOT(
        "intelligence_knot",
        "Intelligence-knot Crystal Tear",
        1,
        180,
        Route.BRIDGE,
        0x6d8fe8,
        "Magic & Frost +25%",
        "Raises Intelligence.",
        "+25% Magic damage and Frostbite buildup from affinity weapons."),
    FAITH_KNOT(
        "faith_knot",
        "Faith-knot Crystal Tear",
        1,
        180,
        Route.BRIDGE,
        0xf0d89a,
        "Holy & Fire +25%",
        "Raises Faith.",
        "+25% Holy and Fire damage from affinity weapons."),
    OPALINE_HARD(
        "opaline_hard",
        "Opaline Hardtear",
        1,
        180,
        Route.READY,
        0xcfe3ea,
        "Resistance I",
        "Boosts all damage negation.",
        "Resistance I: 20% less damage, native hits included."),
    SPECKLED(
        "speckled",
        "Speckled Hardtear",
        1,
        180,
        Route.NATIVE,
        0xe6e0cf,
        "Cleanse",
        "Raises resistances and cures ailments.",
        "Cures harmful effects and blocks them; also the tear's own native effect."),
    LEADEN(
        "leaden",
        "Leaden Hardtear",
        1,
        10,
        Route.BRIDGE,
        0x8a8f96,
        "Unflinching",
        "Boosts poise.",
        "No knockback from Minecraft or native hits."),
    MAGIC_SHROUDING(
        "magic_shrouding",
        "Magic-Shrouding Cracked Tear",
        1,
        180,
        Route.BRIDGE,
        0x6f8cf0,
        "Magic +20%",
        "Boosts magic damage.",
        "+20% Magic damage from affinity weapons."),
    FLAME_SHROUDING(
        "flame_shrouding",
        "Flame-Shrouding Cracked Tear",
        1,
        180,
        Route.BRIDGE,
        0xf08a3c,
        "Fire +20%",
        "Boosts fire damage.",
        "+20% Fire damage from affinity weapons."),
    LIGHTNING_SHROUDING(
        "lightning_shrouding",
        "Lightning-Shrouding Cracked Tear",
        1,
        180,
        Route.BRIDGE,
        0xf2dd52,
        "Lightning +20%",
        "Boosts lightning damage.",
        "+20% Lightning damage from affinity weapons."),
    HOLY_SHROUDING(
        "holy_shrouding",
        "Holy-Shrouding Cracked Tear",
        1,
        180,
        Route.BRIDGE,
        0xf6e9b0,
        "Holy +20%",
        "Boosts holy damage.",
        "+20% Holy damage from affinity weapons."),
    STONEBARB(
        "stonebarb",
        "Stonebarb Cracked Tear",
        1,
        30,
        Route.HOOK,
        0xa3927a,
        "Stance break",
        "Attacks break stances more easily.",
        "Knockback +1, and your hits disable Minecraft shields."),
    SPIKED(
        "spiked",
        "Spiked Cracked Tear",
        1,
        180,
        Route.HOOK,
        0xc25b4a,
        "Crits +20%",
        "Enhances charged attacks.",
        "Critical (falling) hits deal +20%."),
    THORNY(
        "thorny",
        "Thorny Cracked Tear",
        1,
        180,
        Route.HOOK,
        0x7d9a4a,
        "Combo up to +20%",
        "Successive attacks grow stronger.",
        "Each hit within 2 s of the last adds +4% damage, up to +20%."),
    TWIGGY(
        "twiggy",
        "Twiggy Cracked Tear",
        1,
        180,
        Route.NATIVE,
        0x9b7a4f,
        "Keep runes",
        "Runes are kept upon death.",
        "The tear's own native effect: runes are not lost on death."),
    WINGED(
        "winged",
        "Winged Crystal Tear",
        1,
        180,
        Route.READY,
        0xbfe8f2,
        "Jump Boost II",
        "Reduces equip load.",
        "Jump Boost II."),
    WINDY(
        "windy",
        "Windy Crystal Tear",
        1,
        180,
        Route.READY,
        0xa8e0b8,
        "Speed I",
        "Enhances dodge rolls.",
        "Speed I."),
    CRIMSON_BUBBLE(
        "crimson_bubble",
        "Crimson Bubbletear",
        1,
        180,
        Route.HOOK,
        0xe04868,
        "Last-stand heal",
        "Restores HP when near death.",
        "Once, below 20% health: heals 30% of max health."),
    CRIMSONWHORL(
        "crimsonwhorl",
        "Crimsonwhorl Bubbletear",
        1,
        15,
        Route.HOOK,
        0x9c1c2c,
        "Elemental drain",
        "Turns non-physical damage taken into HP.",
        "Fire, lava, lightning and magic damage heal you instead."),
    OPALINE_BUBBLE(
        "opaline_bubble",
        "Opaline Bubbletear",
        1,
        180,
        Route.BRIDGE,
        0xa9d8f0,
        "Bubble",
        "Greatly negates the next hit taken.",
        "The next hit, native or Minecraft, loses 90%."),
    CERULEAN_HIDDEN(
        "cerulean_hidden",
        "Cerulean Hidden Tear",
        1,
        10,
        Route.HOOK,
        0x2c48a8,
        "Free resources",
        "Eliminates FP consumption.",
        "No hunger drain, arrow use or durability loss."),
    PURIFYING(
        "purifying",
        "Purifying Crystal Tear",
        1,
        -1,
        Route.NATIVE,
        0xfff6d8,
        "Purified",
        "Purifies Mohg's curse.",
        "The tear's own native effect against Mohg's Nihil."),
    RUPTURED(
        "ruptured",
        "Ruptured Crystal Tear",
        2,
        0,
        Route.HOOK,
        0xff7a2e,
        "Explosion",
        "Makes the concoction explode.",
        "A blast around you that spares you and your blocks. Both Ruptured tears: a bigger"
            + " blast.");
    public final String id, displayName, label, eldenRing, minecraft;
    public final int copies, seconds, color;
    public final Route route;

    Tear(
        String id,
        String displayName,
        int copies,
        int seconds,
        Route route,
        int color,
        String label,
        String eldenRing,
        String minecraft) {
      this.id = id;
      this.displayName = displayName;
      this.copies = copies;
      this.seconds = seconds;
      this.route = route;
      this.color = color;
      this.label = label;
      this.eldenRing = eldenRing;
      this.minecraft = minecraft;
    }

    public boolean instant() {
      return seconds == 0;
    }

    /** Effect duration in ticks; {@code -1} for an effect that lasts until death. */
    public int ticks() {
      return seconds < 0 ? -1 : seconds * 20;
    }

    public String duration() {
      if (seconds < 0) return "Until death";
      if (seconds == 0) return "Instant";
      return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
  }

  public static final int SLOTS = 2;
  public static final double GREENSPILL_CAPACITY = 1.15, GREENBURST_RECOVERY = 1.3;
  public static final double SPIKED_CRITICAL = 1.2, THORNY_STEP = .04;
  public static final int THORNY_STACKS = 5, THORNY_WINDOW_TICKS = 40;
  public static final double CRIMSON_HEAL = .5, CRIMSON_BUBBLE_BELOW = .2;
  public static final double CRIMSON_BUBBLE_HEAL = .3, OPALINE_BUBBLE_KEEPS = .1;
  public static final double KNOT_AFFINITY = 1.25, SHROUDING_AFFINITY = 1.2;
  public static final int CERULEAN_FOOD = 10;
  public static final float CERULEAN_SATURATION = .25f;
  public static final float RUPTURED_POWER = 2, RUPTURED_PAIR_POWER = 3;

  /** Affinity kinds in the native combat wire's order. */
  public static final List<String> AFFINITIES =
      List.of("poison", "bleeding", "frostbite", "fire", "lightning", "magic", "holy");

  private static final Map<String, Tear> BY_ID = new HashMap<>();

  static {
    for (var tear : Tear.values()) BY_ID.put(tear.id, tear);
  }

  private PhysickTears() {}

  public static Tear byId(String id) {
    return id == null ? null : BY_ID.get(id);
  }

  /** Owned copies per tear, refusing names or counts the native catalogue cannot publish. */
  public static Map<Tear, Integer> counts(Collection<String> names) {
    var counts = new EnumMap<Tear, Integer>(Tear.class);
    for (String name : names) {
      var tear = byId(name);
      if (tear == null) throw new IllegalArgumentException("Unknown Crystal Tear: " + name);
      int count = counts.merge(tear, 1, Integer::sum);
      if (count > tear.copies)
        throw new IllegalArgumentException("Too many copies of " + tear.displayName);
    }
    return counts;
  }

  /**
   * A mixture holds one or two tears the character owns. The same tear fills both slots only when
   * both of its native copies are owned.
   */
  public static boolean valid(List<Tear> mixture, Map<Tear, Integer> owned) {
    if (mixture == null || mixture.isEmpty() || mixture.size() > SLOTS) return false;
    var used = new EnumMap<Tear, Integer>(Tear.class);
    for (var tear : mixture) {
      if (tear == null) return false;
      if (used.merge(tear, 1, Integer::sum) > owned.getOrDefault(tear, 0)) return false;
    }
    return true;
  }

  /** Both copies of one tear make the stronger paired effect (Crimson, Cerulean, Ruptured). */
  public static boolean paired(List<Tear> mixture) {
    return mixture.size() == 2 && mixture.get(0) == mixture.get(1);
  }

  /** Each distinct tear applies once; a pair applies its stronger effect once. */
  public static List<Tear> distinct(List<Tear> mixture) {
    return mixture.stream().distinct().toList();
  }

  /** Native combat multipliers by affinity kind for the tears now in effect. */
  public static double[] affinityScales(Set<Tear> active) {
    double[] scales = new double[AFFINITIES.size()];
    Arrays.fill(scales, 1);
    if (active.contains(Tear.INTELLIGENCE_KNOT)) {
      scales[AFFINITIES.indexOf("magic")] *= KNOT_AFFINITY;
      scales[AFFINITIES.indexOf("frostbite")] *= KNOT_AFFINITY;
    }
    if (active.contains(Tear.FAITH_KNOT)) {
      scales[AFFINITIES.indexOf("holy")] *= KNOT_AFFINITY;
      scales[AFFINITIES.indexOf("fire")] *= KNOT_AFFINITY;
    }
    if (active.contains(Tear.MAGIC_SHROUDING))
      scales[AFFINITIES.indexOf("magic")] *= SHROUDING_AFFINITY;
    if (active.contains(Tear.FLAME_SHROUDING))
      scales[AFFINITIES.indexOf("fire")] *= SHROUDING_AFFINITY;
    if (active.contains(Tear.LIGHTNING_SHROUDING))
      scales[AFFINITIES.indexOf("lightning")] *= SHROUDING_AFFINITY;
    if (active.contains(Tear.HOLY_SHROUDING))
      scales[AFFINITIES.indexOf("holy")] *= SHROUDING_AFFINITY;
    return scales;
  }

  /** Melee damage multiplier from Spiked (critical hits) and Thorny (consecutive hits). */
  public static double meleeScale(boolean critical, boolean spiked, int thornyStacks) {
    double scale = critical && spiked ? SPIKED_CRITICAL : 1;
    return scale * (1 + THORNY_STEP * Math.clamp(thornyStacks, 0, THORNY_STACKS));
  }

  /** Thorny stacks after a landed hit at {@code tick}, given the previous hit. */
  public static int thornyStacks(long previousTick, int previousStacks, long tick) {
    boolean chained =
        previousTick >= 0 && tick >= previousTick && tick - previousTick <= THORNY_WINDOW_TICKS;
    return chained ? Math.min(THORNY_STACKS, previousStacks + 1) : 0;
  }

  /** Vanilla Absorption amplifier closest to 10% of max health (4 health per level). */
  public static int absorptionAmplifier(double maxHealth) {
    if (!Double.isFinite(maxHealth) || maxHealth <= 0) return 0;
    return (int) Math.clamp(Math.round(maxHealth * .1 / 4) - 1, 0, 4);
  }

  /** The mixture as stored on the player: tear IDs joined by '+'. */
  public static String encode(List<Tear> mixture) {
    return String.join("+", mixture.stream().map(t -> t.id).toList());
  }

  /** A stored mixture, or an empty list when it is malformed. */
  public static List<Tear> decode(String value) {
    if (value == null || value.isBlank()) return List.of();
    var parts = value.split("\\+", -1);
    if (parts.length > SLOTS) return List.of();
    var mixture = new ArrayList<Tear>();
    for (String part : parts) {
      var tear = byId(part);
      if (tear == null) return List.of();
      mixture.add(tear);
    }
    if (paired(mixture) && mixture.getFirst().copies < 2) return List.of();
    return List.copyOf(mixture);
  }
}
