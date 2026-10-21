package dev.eldencraft.bridge;

import com.google.gson.*;
import com.mojang.serialization.*;
import dev.eldencraft.bridge.client.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Parses shipped affinity definitions and exercises real vanilla inventory components. */
public final class CampaignEnchantmentsConformance {
  private static int checks;

  private static void check(boolean result, String message) {
    ++checks;
    if (!result) throw new AssertionError(message);
  }

  private static JsonObject resource(String path) throws Exception {
    try (var stream = CampaignEnchantmentsConformance.class.getResourceAsStream(path)) {
      check(stream != null, "resource ships: " + path);
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static List<Holder<Item>> items(String id, MappedRegistry<Item> registry)
      throws Exception {
    var identifier = Identifier.parse(id);
    var tag =
        resource(
            "/data/" + identifier.getNamespace() + "/tags/item/" + identifier.getPath() + ".json");
    var result = new ArrayList<Holder<Item>>();
    for (var value : tag.getAsJsonArray("values")) {
      String entry = value.getAsString();
      if (entry.startsWith("#")) result.addAll(items(entry.substring(1), registry));
      else result.add(registry.get(Identifier.parse(entry)).orElseThrow());
    }
    return result;
  }

  private static void hook(String type, String method, String descriptor) throws Exception {
    var node = new ClassNode();
    try (var stream =
        CampaignEnchantmentsConformance.class
            .getClassLoader()
            .getResourceAsStream(type + ".class")) {
      check(stream != null, "pinned class exists: " + type);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    check(
        node.methods.stream().anyMatch(m -> m.name.equals(method) && m.desc.equals(descriptor)),
        "exact vanilla menu hook: " + method);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < 1e-9, message + " (" + actual + " vs " + expected + ")");
  }

  /** A fraction as the enchantment descriptions print it. */
  private static String percent(double fraction) {
    return CampaignWeaponTooltips.number(100 * fraction) + "%";
  }

  /** Shield enchantments: migration, table pool, descriptions and the numbers behind them. */
  private static void verifyShields(
      Map<String, Holder<Enchantment>> guards,
      Map<String, Holder<Enchantment>> affinities,
      RegistryAccess access,
      RegistryOps<JsonElement> saveOps,
      Holder<Enchantment> mending,
      Holder<Enchantment> sharpness,
      Holder<Item> shieldItem,
      List<Holder<Item>> weapons)
      throws Exception {
    for (var first : guards.values())
      for (var second : guards.values())
        if (first != second)
          check(Enchantment.areCompatible(first, second), "shield enchantments stack together");
    check(
        Enchantment.areCompatible(guards.get("healing"), sharpness),
        "a shield enchantment does not clash with vanilla");

    var plain = new ItemStack(shieldItem);
    check(!plain.isEnchantable(), "vanilla gives a shield no enchantability");
    CampaignEnchantments.tune(plain);
    check(
        plain.isEnchantable()
            && plain.get(DataComponents.ENCHANTABLE).value()
                == CampaignEnchantments.SHIELD_ENCHANTABILITY,
        "the campaign makes a shield enchantable at the table");
    check(CampaignEnchantments.shield(plain) && !CampaignEnchantments.shield(null), "shield test");

    var values = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
    values.set(guards.get("guarding_ability"), 7);
    values.set(guards.get("explosive"), 5);
    values.set(affinities.get("poison"), 2);
    values.set(mending, 1);
    var buckler = new ItemStack(Items.SHIELD);
    buckler.set(DataComponents.ENCHANTMENTS, values.toImmutable());
    CampaignEnchantments.tune(buckler);
    check(
        buckler.getEnchantments().size() == 2,
        "a shield keeps its guard enchantments and loses affinities and retired enchants");
    check(
        CampaignEnchantments.level(buckler, "guarding_ability") == 3
            && CampaignEnchantments.level(buckler, "eldencraft_bridge:explosive") == 1,
        "levels clamp to each shipped maximum");
    check(CampaignEnchantments.level(buckler, "poison") == 0, "no affinity on a shield");
    check(
        CampaignEnchantments.permitted(null, buckler),
        "shield enchantments need no paired host or whetblade");
    var encoded = ItemStack.CODEC.encodeStart(saveOps, buckler).getOrThrow();
    var restored = ItemStack.CODEC.parse(saveOps, encoded).getOrThrow();
    check(
        ItemStack.matches(buckler, restored)
            && CampaignEnchantments.level(restored, "guarding_ability") == 3,
        "real shield codec preserves guard enchantments across saves");

    var mixed = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
    mixed.set(guards.get("healing"), 2);
    mixed.set(affinities.get("poison"), 1);
    var blade = new ItemStack(Items.DIAMOND_SWORD);
    blade.set(DataComponents.ENCHANTMENTS, mixed.toImmutable());
    CampaignEnchantments.tune(blade);
    check(
        CampaignEnchantments.level(blade, "healing") == 0
            && CampaignEnchantments.level(blade, "poison") == 1,
        "shield enchantments are stripped from weapons");
    var book = new ItemStack(Items.ENCHANTED_BOOK);
    book.set(DataComponents.STORED_ENCHANTMENTS, mixed.toImmutable());
    CampaignEnchantments.tune(book);
    check(
        book.get(DataComponents.STORED_ENCHANTMENTS).size() == 2,
        "a book can carry a shield enchantment to an anvil");

    for (String id : CampaignEnchantments.SHIELD_ENCHANTMENTS) {
      check(CampaignEnchantments.unlocked(Set.of(), id), id + " has no whetblade requirement");
      for (int level = 1; level <= CampaignEnchantments.maxLevel(id); level++)
        check(
            !CampaignEnchantments.description(id, level).isBlank(),
            id + " " + level + " describes its effect");
    }
    check(
        !CampaignEnchantments.allowed("other:healing") && !CampaignEnchantments.allowed("healing2"),
        "foreign mod identity cannot claim a shield enchantment");
    check(
        CampaignEnchantments.singleLevel("explosive")
            && !CampaignEnchantments.singleLevel("healing")
            && !CampaignEnchantments.singleLevel("sharpness"),
        "only one-level enchantments drop their numeral");
    check(
        CampaignTooltipTheme.title("eldencraft_bridge:explosive", 1).equals("Explosive")
            && CampaignTooltipTheme.title("eldencraft_bridge:healing", 3).equals("Healing III"),
        "tooltip titles show levels only where there are several");
    for (String id : CampaignEnchantments.SHIELD_ENCHANTMENTS)
      check(
          CampaignTooltipTheme.enchantmentIcon(id) != CampaignTooltipTheme.Icon.DAMAGE
              || id.equals("riposte"),
          id + " has its own tooltip icon");

    // The table offers a shield only its own enchantments, and no weapon gets one.
    var offered = new HashSet<String>();
    for (int seed = 0; seed < 400; seed++) {
      for (var entry :
          CampaignEnchantments.table(null, access, plain, 30, RandomSource.create(seed))) {
        String id = CampaignEnchantments.id(entry.enchantment());
        check(CampaignEnchantments.shieldEnchantment(id), "a shield is offered only guard magic");
        check(
            entry.level() >= 1 && entry.level() <= CampaignEnchantments.maxLevel(id),
            "offered shield levels are within the maximum");
        offered.add(id);
      }
      for (var entry :
          CampaignEnchantments.table(
              null, access, new ItemStack(weapons.getFirst()), 30, RandomSource.create(seed)))
        check(
            !CampaignEnchantments.shieldEnchantment(CampaignEnchantments.id(entry.enchantment())),
            "a sword is never offered a shield enchantment");
    }
    check(
        offered.contains("eldencraft_bridge:guarding_ability")
            && offered.contains("eldencraft_bridge:healing")
            && offered.size() >= 5,
        "the table rolls a variety of shield enchantments: " + offered);

    // Descriptions come from the same numbers the effects use.
    check(
        CampaignEnchantments.description("guarding_ability", 1)
                .equals(
                    "Blocking costs "
                        + percent(1 - CampaignShields.guardCostScale(1))
                        + " less stamina.")
            && CampaignEnchantments.description("guarding_ability", 3)
                .equals(
                    "Blocking costs "
                        + percent(1 - CampaignShields.guardCostScale(3))
                        + " less stamina."),
        "Guarding Ability describes its discount");
    check(
        CampaignEnchantments.description("healing", 3)
            .contains(percent(CampaignShields.healFraction(3)) + " of your maximum health"),
        "Healing describes the share it restores");
    check(
        CampaignEnchantments.description("riposte", 2)
                .contains("+" + percent(CampaignShields.riposteBonus(2)) + " damage")
            && CampaignEnchantments.description("second_wind", 3)
                .contains(percent(CampaignShields.staminaRefund(3)) + " of your maximum"),
        "Riposte and Second Wind describe their numbers");
    check(
        CampaignEnchantments.description("healing", 99)
            .equals(CampaignEnchantments.description("healing", 3)),
        "descriptions clamp excessive levels");

    near(CampaignShields.guardCostScale(0), 1, "no Guarding Ability costs the configured amount");
    check(
        CampaignShields.guardCostScale(1) < 1 && CampaignShields.guardCostScale(3) > 0,
        "Guarding Ability discounts without making guards free");
    near(
        CampaignShields.guardCostScale(99),
        CampaignShields.guardCostScale(3),
        "Guarding Ability clamps");
    near(CampaignShields.guardCostScale(new ItemStack(Items.SHIELD)), 1, "plain shield");
    near(
        CampaignShields.guardCostScale(buckler),
        CampaignShields.guardCostScale(3),
        "enchanted shield");
    near(CampaignShields.guardCostScale(ItemStack.EMPTY), 1, "empty hand");
    near(CampaignShields.guardCostScale((ItemStack) null), 1, "no shield");
    near(CampaignShields.healFraction(0), 0, "no Healing");
    check(
        CampaignShields.healFraction(1) > 0
            && CampaignShields.staminaRefund(1) > 0
            && CampaignShields.riposteBonus(1) > 0,
        "every shield enchantment does something at level I");
    near(CampaignShields.riposteBonus(-4), 0, "no Riposte");
    for (int level = 1; level < 3; level++)
      check(
          CampaignShields.healFraction(level) < CampaignShields.healFraction(level + 1)
              && CampaignShields.staminaRefund(level) < CampaignShields.staminaRefund(level + 1)
              && CampaignShields.riposteBonus(level) < CampaignShields.riposteBonus(level + 1)
              && CampaignShields.guardCostScale(level) > CampaignShields.guardCostScale(level + 1),
          "every shield enchantment grows with its level");

    check(
        CampaignShields.semitones(1) < CampaignShields.semitones(2)
            && CampaignShields.semitones(CampaignShields.scaleLength())
                == CampaignShields.semitones(CampaignShields.scaleLength() + 50),
        "Resounding climbs, then holds the top note");
    for (int chain = -3; chain < 40; chain++) {
      float pitch = CampaignShields.pitch(CampaignShields.semitones(chain));
      check(pitch >= .5f && pitch <= 2f, "Resounding stays inside the engine's pitch range");
    }
    check(
        CampaignShields.pitch(0) == 1f && CampaignShields.pitch(12) == 2f,
        "an octave doubles the pitch");
    check(
        CampaignShields.ready(null, 5, 10)
            && !CampaignShields.ready(100L, 105, 10)
            && CampaignShields.ready(100L, 110, 10)
            && CampaignShields.ready(100L, 3, 10),
        "cooldowns open on time, and when the clock runs backwards");
    check(
        CampaignShields.chainLength(-1, 0, 50) == 1
            && CampaignShields.chainLength(50, 1, 50 + CampaignShields.CHAIN_WINDOW_TICKS) == 2
            && CampaignShields.chainLength(50, 4, 51 + CampaignShields.CHAIN_WINDOW_TICKS + 50) == 1
            && CampaignShields.chainLength(50, 4, 10) == 1,
        "Resounding continues inside its window and restarts outside it");

    // Guarding Ability and Second Wind change only the stamina cost, never the configured rule.
    var rules = CampaignConfig.current().stamina;
    var full = new CampaignStamina(100);
    var cheap = new CampaignStamina(100);
    full.guard(10, 1, rules);
    cheap.guard(10, 1, rules, CampaignShields.guardCostScale(3));
    near(
        100 - cheap.current(),
        (100 - full.current()) * CampaignShields.guardCostScale(3),
        "the scale discounts the guard cost");
    var unchanged = new CampaignStamina(100);
    unchanged.guard(10, 1, rules, Double.NaN);
    near(unchanged.current(), full.current(), "a malformed scale charges the configured cost");
    unchanged.rest();
    unchanged.guard(10, 1, rules, 7);
    near(unchanged.current(), full.current(), "a scale cannot surcharge a guard");
    // Just enough stamina for the discounted cost, but not for the full one.
    double affordable =
        (rules.guardBase() + rules.guardPerDamage() * 10)
            * (1 + CampaignShields.guardCostScale(3))
            / 2;
    check(
        CampaignStamina.absorbed(affordable, 10, rules) < 1
            && CampaignStamina.absorbed(affordable, 10, rules, CampaignShields.guardCostScale(3))
                == 1,
        "a discount can turn a partial block into a full one");
    check(
        CampaignStamina.absorbed(5, 10, rules, .55) > CampaignStamina.absorbed(5, 10, rules),
        "a discount raises the share of a hit that stamina pays for");
    var winded = new CampaignStamina(100);
    winded.spend(60);
    winded.restore(10);
    near(winded.current(), 50, "Second Wind restores stamina");
    winded.restore(1000);
    near(winded.current(), 100, "restoration stops at the maximum");
    winded.restore(Double.NaN);
    winded.restore(-5);
    near(winded.current(), 100, "malformed restoration is ignored");
    var spent = new CampaignStamina(100);
    spent.spend(100);
    spent.restore(50);
    check(spent.current() == 0 && !spent.guardReady(), "a broken guard is not mended by a refund");
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var vanilla = VanillaRegistries.createWorldLookup();
    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(vanilla).forEach(PendingComponents::apply);
    CampaignConfig.install(CampaignConfig.load(Path.of("../config/campaign.json")));

    var items = new MappedRegistry<Item>(Registries.ITEM, Lifecycle.stable());
    BuiltInRegistries.ITEM
        .listElements()
        .forEach(
            h ->
                items
                    .register(h.key(), h.value(), RegistrationInfo.BUILT_IN)
                    .bindComponents(h.components()));
    var weaponTag =
        TagKey.create(Registries.ITEM, Identifier.parse("eldencraft_bridge:affinity_weapons"));
    var weapons = items("eldencraft_bridge:affinity_weapons", items);
    var shieldTag = TagKey.create(Registries.ITEM, Identifier.parse("eldencraft_bridge:shields"));
    var shields = items("eldencraft_bridge:shields", items);
    items.bindTags(Map.of(weaponTag, weapons, shieldTag, shields));
    items.freeze();
    check(
        shields.size() == 1 && shields.getFirst().value() == Items.SHIELD,
        "the shield tag holds exactly the vanilla shield");
    check(weapons.size() == 22, "seven sword, axe and spear tiers plus mace support affinities");
    check(
        weapons.stream().anyMatch(h -> h.value() == Items.COPPER_SPEAR),
        "new copper spear supported");
    check(weapons.stream().noneMatch(h -> h.value() == Items.BOW), "ranged weapons excluded");

    var enchantments = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
    vanilla
        .lookupOrThrow(Registries.ENCHANTMENT)
        .listElements()
        .forEach(h -> enchantments.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
    var affinityTag =
        TagKey.create(
            Registries.ENCHANTMENT, Identifier.parse("eldencraft_bridge:exclusive_set/affinity"));
    enchantments.bindTags(Map.of(affinityTag, List.of()));
    // Decode through the same pre-freeze registration lookup that vanilla's datapack loader uses.
    var registeringEnchantments = enchantments.createRegistrationLookup();
    var ops =
        RegistryOps.create(
            JsonOps.INSTANCE,
            new RegistryOps.RegistryInfoLookup() {
              @Override
              @SuppressWarnings("unchecked")
              public <T> Optional<HolderGetter<T>> lookup(
                  ResourceKey<? extends Registry<? extends T>> registry) {
                if (registry.equals(Registries.ENCHANTMENT))
                  return Optional.of((HolderGetter<T>) registeringEnchantments);
                if (registry.equals(Registries.ITEM)) return Optional.of((HolderGetter<T>) items);
                return vanilla.lookup(registry).map(value -> (HolderGetter<T>) value);
              }
            });
    var custom = new LinkedHashMap<String, Holder<Enchantment>>();
    for (String id : CampaignEnchantments.AFFINITIES) {
      var json = resource("/data/eldencraft_bridge/enchantment/" + id + ".json");
      var enchantment = Enchantment.DIRECT_CODEC.parse(ops, json).getOrThrow();
      var holder =
          enchantments.register(
              ResourceKey.create(
                  Registries.ENCHANTMENT, Identifier.parse("eldencraft_bridge:" + id)),
              enchantment,
              RegistrationInfo.BUILT_IN);
      custom.put(id, holder);
      check(enchantment.getMaxLevel() == 3, id + " caps at III");
      check(
          enchantment.description().getString().equals(CampaignEnchantments.displayName(id)),
          id + " has a human-readable table clue");
      check(
          enchantment.effects().isEmpty(),
          id + " delegates its effect to native combat, without vanilla effects");
      check(
          enchantment.getSupportedItems().contains(weapons.getFirst()),
          id + " resolves shipped item tag");
    }
    var guards = new LinkedHashMap<String, Holder<Enchantment>>();
    for (String id : CampaignEnchantments.SHIELD_ENCHANTMENTS) {
      var json = resource("/data/eldencraft_bridge/enchantment/" + id + ".json");
      var enchantment = Enchantment.DIRECT_CODEC.parse(ops, json).getOrThrow();
      guards.put(
          id,
          enchantments.register(
              ResourceKey.create(
                  Registries.ENCHANTMENT, Identifier.parse("eldencraft_bridge:" + id)),
              enchantment,
              RegistrationInfo.BUILT_IN));
      check(
          enchantment.getMaxLevel() == CampaignEnchantments.maxLevel(id),
          id + " caps where the descriptions and migration expect");
      check(
          enchantment.description().getString().equals(CampaignEnchantments.displayName(id)),
          id + " has a human-readable table clue");
      check(
          enchantment.effects().isEmpty(), id + " acts through the campaign, not vanilla effects");
      // The tag holds this registry's own holders, so the stacks must carry them too.
      var shield = new ItemStack(shields.getFirst());
      check(
          enchantment.isSupportedItem(shield) && enchantment.isPrimaryItem(shield),
          id + " can be rolled at a table and combined at an anvil for a shield");
      check(
          weapons.stream().noneMatch(weapon -> enchantment.getSupportedItems().contains(weapon)),
          id + " is offered to nothing but shields");
      check(
          CampaignEnchantments.shieldEnchantment(id),
          id + " is recognised as a shield enchantment");
      check(
          !CampaignEnchantments.custom(id) && CampaignEnchantments.whetbladeOf(id) == null,
          id + " is not whetblade-gated");
      check(CampaignEnchantments.allowed(id), id + " survives campaign migration");
    }
    check(guards.size() == 8, "eight shield enchantments ship");
    var exclusive =
        resource("/data/eldencraft_bridge/tags/enchantment/exclusive_set/affinity.json");
    var exclusiveHolders = new ArrayList<Holder<Enchantment>>();
    for (var id : exclusive.getAsJsonArray("values"))
      exclusiveHolders.add(enchantments.get(Identifier.parse(id.getAsString())).orElseThrow());
    enchantments.bindTags(Map.of(affinityTag, exclusiveHolders));
    enchantments.freeze();
    for (var first : custom.values())
      for (var second : custom.values())
        check(!Enchantment.areCompatible(first, second), "only one affinity can be applied");

    var sharpness = enchantments.get(Identifier.parse("minecraft:sharpness")).orElseThrow();
    var mending = enchantments.get(Identifier.parse("minecraft:mending")).orElseThrow();
    check(
        Enchantment.areCompatible(sharpness, custom.get("bleeding")),
        "sharpness stacks with affinity");
    var values = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
    values.set(sharpness, 255);
    values.set(mending, 1);
    values.set(custom.get("poison"), 1);
    values.set(custom.get("bleeding"), 7);
    values.set(custom.get("frostbite"), 2);
    var weapon = new ItemStack(Items.DIAMOND_SWORD);
    weapon.set(DataComponents.ENCHANTMENTS, values.toImmutable());
    CampaignEnchantments.tune(weapon);
    check(
        weapon.getEnchantments().size() == 2,
        "migration removes retired enchants and stacked affinities");
    check(CampaignEnchantments.level(weapon, "sharpness") == 5, "migration clamps vanilla maximum");
    check(
        CampaignEnchantments.affinity(weapon).equals("bleeding"),
        "strongest affinity retained deterministically");
    check(CampaignEnchantments.affinityLevel(weapon) == 3, "migration clamps affinity III");
    check(
        !CampaignEnchantments.permitted(null, weapon),
        "missing paired host blocks existing affinity application");
    var saveOps =
        HolderLookup.Provider.create(
                Stream.concat(
                    vanilla.listRegistries().filter(r -> !r.key().equals(Registries.ENCHANTMENT)),
                    Stream.of(enchantments)))
            .createSerializationContext(JsonOps.INSTANCE);
    var encoded = ItemStack.CODEC.encodeStart(saveOps, weapon).getOrThrow();
    var restored = ItemStack.CODEC.parse(saveOps, encoded).getOrThrow();
    check(ItemStack.matches(weapon, restored), "real weapon codec preserves affinity across saves");
    check(
        CampaignEnchantments.affinity(restored).equals("bleeding"),
        "restored item retains native affinity identity");

    var book = new ItemStack(Items.ENCHANTED_BOOK);
    book.set(DataComponents.STORED_ENCHANTMENTS, values.toImmutable());
    CampaignEnchantments.tune(book);
    check(
        book.get(DataComponents.STORED_ENCHANTMENTS).size() == 2,
        "stored-book migration applies same policy");
    check(
        CampaignEnchantments.affinity(book).isEmpty(),
        "stored books do not act as enchanted weapons");
    var bow = new ItemStack(Items.BOW);
    bow.set(DataComponents.ENCHANTMENTS, values.toImmutable());
    CampaignEnchantments.tune(bow);
    check(
        bow.getEnchantments().size() == 1, "status affinities removed from unsupported equipment");
    check(CampaignEnchantments.weapon(new ItemStack(Items.MACE)), "mace is an affinity weapon");
    check(CampaignEnchantments.weapon(new ItemStack(Items.IRON_AXE)), "axe is an affinity weapon");
    check(
        CampaignEnchantments.weapon(new ItemStack(Items.WOODEN_SPEAR)),
        "spear is an affinity weapon");
    check(
        !CampaignEnchantments.weapon(new ItemStack(Items.IRON_PICKAXE)),
        "mining tools are not affinity weapons");

    check(!CampaignEnchantments.unlocked(Set.of(), "poison"), "poison starts locked");
    check(
        CampaignEnchantments.unlocked(Set.of("black"), "poison"), "black whetblade unlocks poison");
    check(
        CampaignEnchantments.unlocked(Set.of("black"), "eldencraft_bridge:bleeding"),
        "black whetblade unlocks bleeding");
    check(
        !CampaignEnchantments.unlocked(Set.of("black"), "frostbite"),
        "black does not unlock frostbite");
    check(
        CampaignEnchantments.unlocked(Set.of("glintstone"), "frostbite"),
        "glintstone whetblade unlocks frostbite");
    check(
        !CampaignEnchantments.unlocked(Set.of("glintstone"), "bleeding"),
        "glintstone does not unlock bleeding");
    for (var expected :
        Map.of(
                "fire", "red_hot",
                "lightning", "sanctified",
                "holy", "sanctified",
                "magic", "glintstone")
            .entrySet()) {
      check(
          CampaignEnchantments.unlocked(Set.of(expected.getValue()), expected.getKey()),
          expected.getValue() + " whetblade unlocks " + expected.getKey());
      check(
          !CampaignEnchantments.unlocked(Set.of("black", "iron"), expected.getKey()),
          expected.getKey() + " is not unlocked by unrelated whetblades");
      check(
          CampaignEnchantments.element(expected.getKey())
              && !CampaignEnchantments.status(expected.getKey()),
          expected.getKey() + " is an element");
      check(
          CampaignEnchantments.description(expected.getKey(), 3)
              .contains(expected.getKey() + " absorption"),
          expected.getKey() + " describes its absorption scaling");
    }
    check(
        CampaignEnchantments.physicalShare(0) == 100
            && CampaignEnchantments.elementShare(0) == 0
            && CampaignEnchantments.physicalShare(1) == 90
            && CampaignEnchantments.elementShare(1) == 15
            && CampaignEnchantments.physicalShare(2) == 85
            && CampaignEnchantments.elementShare(2) == 22
            && CampaignEnchantments.physicalShare(3) == 80
            && CampaignEnchantments.elementShare(3) == 30
            && CampaignEnchantments.elementShare(9) == 30,
        "elemental splits mirror native ELEMENT_SPLITS");
    var covered = new HashSet<String>();
    for (var blade : CampaignEnchantments.WHETBLADES) covered.addAll(blade.affinities());
    check(
        covered.equals(Set.copyOf(CampaignEnchantments.AFFINITIES))
            && covered.size() == CampaignEnchantments.AFFINITIES.size(),
        "every affinity has exactly one whetblade");
    for (String id : List.of("sharpness", "looting", "unbreaking", "protection"))
      check(CampaignEnchantments.unlocked(Set.of(), id), id + " has no whetblade requirement");
    check(
        !CampaignEnchantments.allowed("mending") && !CampaignEnchantments.allowed("fire_aspect"),
        "retired vanilla enchantments excluded");
    check(
        !CampaignEnchantments.allowed("other:poison"),
        "foreign mod identity cannot claim affinity");
    check(
        CampaignEnchantments.buildup(1) == 35
            && CampaignEnchantments.buildup(2) == 50
            && CampaignEnchantments.buildup(3) == 65,
        "bounded native buildup tiers");
    check(
        CampaignEnchantments.description("sharpness", 1)
            .equals(
                "Adds "
                    + CampaignWeaponTooltips.number(
                        CampaignEnchantments.sharpness(1)
                            * CampaignConfig.current().nativeDamageScale)
                    + " Elden Ring damage to full-strength melee hits."),
        "sharpness description uses configured Elden Ring scale");
    // The campaign pays more than vanilla at every level, and vanilla's own curve is intact.
    near(CampaignEnchantments.vanillaSharpness(1), 1, "vanilla Sharpness I");
    near(CampaignEnchantments.vanillaSharpness(5), 3, "vanilla Sharpness V");
    near(CampaignEnchantments.vanillaSharpness(0), 0, "no Sharpness");
    near(CampaignEnchantments.sharpness(0), 0, "no campaign Sharpness");
    for (int level = 1; level <= 5; level++) {
      check(
          CampaignEnchantments.sharpness(level) > CampaignEnchantments.vanillaSharpness(level),
          "the campaign buffs Sharpness " + level);
      near(
          CampaignEnchantments.sharpness(level),
          CampaignEnchantments.vanillaSharpness(level) * CampaignEnchantments.SHARPNESS_BUFF,
          "the buff is one flat multiplier at level " + level);
      if (level > 1)
        check(
            CampaignEnchantments.sharpness(level) > CampaignEnchantments.sharpness(level - 1),
            "Sharpness still grows with level");
    }
    near(
        CampaignEnchantments.sharpness(255),
        CampaignEnchantments.sharpness(5),
        "Sharpness clamps excessive levels");
    check(
        CampaignEnchantments.description("sharpness", 255)
            .equals(CampaignEnchantments.description("sharpness", 5)),
        "sharpness description clamps excessive levels");

    // With no native player/unlocks, the exact vanilla roll sees only ungated campaign candidates.
    var vanillaAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(enchantments));
    for (int seed = 0; seed < 100; seed++) {
      var result =
          CampaignEnchantments.table(
              null, vanillaAccess, new ItemStack(Items.BOOK), 30, RandomSource.create(seed));
      for (var entry : result) {
        check(
            CampaignEnchantments.allowed(CampaignEnchantments.id(entry.enchantment())),
            "table roll excludes retired enchantments");
        check(
            !CampaignEnchantments.custom(CampaignEnchantments.id(entry.enchantment())),
            "table roll excludes locked affinities");
      }
    }
    verifyShields(
        guards, custom, vanillaAccess, saveOps, mending, sharpness, shields.getFirst(), weapons);
    check(CampaignCombat.combinedReduction(25, 4) == 37, "protection composes with percent armor");
    check(
        CampaignCombat.combinedReduction(0, 16) == 64,
        "four Protection IV pieces mitigate 64 percent");
    check(CampaignCombat.combinedReduction(100, 16) == 100, "full armor reduction stays bounded");
    check(CampaignCombat.combinedReduction(0, 1000) == 80, "malicious protection total caps at 20");
    check(
        CampaignCombat.combinedReduction(-20, -1) == 0, "negative reduction and protection clamp");

    hook(
        "net/minecraft/world/inventory/EnchantmentMenu",
        "getEnchantmentList",
        "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/item/ItemStack;II)Ljava/util/List;");
    hook(
        "net/minecraft/world/entity/LivingEntity",
        "applyItemBlocking",
        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)F");
    hook(
        "net/minecraft/world/entity/player/Player",
        "stabAttack",
        "(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/entity/Entity;FZZZ)Z");
    hook(
        "net/minecraft/world/item/enchantment/EnchantmentHelper",
        "modifyDamage",
        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;F)F");
    hook("net/minecraft/world/inventory/AnvilMenu", "createResult", "()V");
    hook(
        "net/minecraft/world/inventory/AnvilMenu",
        "mayPickup",
        "(Lnet/minecraft/world/entity/player/Player;Z)Z");
    System.out.println("Campaign enchantment conformance: " + checks + " checks passed");
  }
}
