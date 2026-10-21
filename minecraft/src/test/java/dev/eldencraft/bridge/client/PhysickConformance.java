package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.PhysickTears.Tear;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real tear catalogue, mixing rules, registrations, assets and pinned vanilla hooks. */
public final class PhysickConformance {
  private static int checks;
  private static final Path ASSETS = Path.of("src/main/resources/assets/eldencraft_bridge");

  private static void check(boolean result, String message) {
    ++checks;
    if (!result) throw new AssertionError(message);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < 1e-9, message + ": " + actual);
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    // Register the real item and effects during bootstrap, before vanilla freezes the
    // registries, as Fabric's initializer would. Mirrors ExplosionConformance.
    var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
    bootstrapped.setAccessible(true);
    bootstrapped.setBoolean(null, true);
    try {
      PhysickItems.initialize();
    } finally {
      bootstrapped.setBoolean(null, false);
    }
    Bootstrap.bootStrap();
    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
        .build(VanillaRegistries.createWorldLookup())
        .forEach(PendingComponents::apply);
    catalogue();
    mixing();
    combat();
    registrations();
    assets();
    bridge();
    graceRow();
    stamina();
    hooks();
    System.out.println("Physick conformance passed " + checks + " checks.");
  }

  private static void catalogue() {
    var tears = Tear.values();
    check(tears.length == 29, "29 distinct base-game Crystal Tears");
    check(
        Arrays.stream(tears).mapToInt(t -> t.copies).sum() == 32,
        "32 native copies, matching native PHYSICK_TEARS (Goods 11000-11031)");
    for (var tear : tears) {
      check(PhysickTears.byId(tear.id) == tear, "unique tear ID: " + tear.id);
      check(tear.copies == 1 || tear.instant(), "only instant tears have two copies");
      check(!tear.label.isBlank() && !tear.minecraft.isBlank(), "every tear explains itself");
    }
    for (var paired : List.of(Tear.CRIMSON, Tear.CERULEAN, Tear.RUPTURED))
      check(paired.copies == 2 && paired.instant(), paired.id + " has two copies");
    // Durations read from SpEffectParam (endurance) in regulation 11711000.
    check(Tear.LEADEN.seconds == 10, "Leaden Hardtear lasts 10 s");
    check(Tear.CERULEAN_HIDDEN.seconds == 10, "Cerulean Hidden Tear lasts 10 s");
    check(Tear.CRIMSONWHORL.seconds == 15, "Crimsonwhorl Bubbletear lasts 15 s");
    check(Tear.STONEBARB.seconds == 30, "Stonebarb Cracked Tear lasts 30 s");
    check(Tear.PURIFYING.ticks() == -1, "Purifying lasts until death");
    check(Tear.STRENGTH_KNOT.ticks() == 3600, "three minutes in ticks");
    check(Tear.WINDY.duration().equals("3:00"), "duration label");
    check(PhysickTears.byId("arcane_knot") == null, "Arcane-knot is a DLC tear");
  }

  private static void mixing() {
    var owned = PhysickTears.counts(List.of("crimson", "crimson", "strength_knot", "twiggy"));
    check(owned.get(Tear.CRIMSON) == 2, "both Crimson copies counted");
    expectInvalid(() -> PhysickTears.counts(List.of("strength_knot", "strength_knot")));
    expectInvalid(() -> PhysickTears.counts(List.of("arcane_knot")));
    check(PhysickTears.valid(List.of(Tear.STRENGTH_KNOT), owned), "a single tear mixes");
    check(
        PhysickTears.valid(List.of(Tear.STRENGTH_KNOT, Tear.TWIGGY), owned), "two owned tears mix");
    check(PhysickTears.valid(List.of(Tear.CRIMSON, Tear.CRIMSON), owned), "both Crimson copies");
    check(!PhysickTears.valid(List.of(), owned), "an empty mixture is refused");
    check(
        !PhysickTears.valid(List.of(Tear.TWIGGY, Tear.TWIGGY), owned),
        "one copy cannot fill both slots");
    check(!PhysickTears.valid(List.of(Tear.SPIKED), owned), "an unowned tear is refused");
    check(
        !PhysickTears.valid(List.of(Tear.CRIMSON, Tear.CRIMSON, Tear.CRIMSON), owned),
        "a mixture holds at most two tears");
    check(
        PhysickTears.paired(List.of(Tear.CRIMSON, Tear.CRIMSON))
            && PhysickTears.distinct(List.of(Tear.CRIMSON, Tear.CRIMSON)).size() == 1,
        "a pair applies its stronger effect once");
    var mixture = List.of(Tear.OPALINE_BUBBLE, Tear.FAITH_KNOT);
    check(PhysickTears.decode(PhysickTears.encode(mixture)).equals(mixture), "mixture round trip");
    for (String bad : List.of("", "unknown", "a+b+c", "twiggy+twiggy", "crimson+", "+crimson"))
      check(PhysickTears.decode(bad).isEmpty(), "malformed stored mixture is empty: " + bad);
    check(PhysickTears.decode("ruptured+ruptured").size() == 2, "a two-copy pair survives storage");
  }

  private static void expectInvalid(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      ++checks;
      return;
    }
    throw new AssertionError("invalid tear list accepted");
  }

  private static void combat() {
    check(
        PhysickTears.AFFINITIES.equals(
            List.of("poison", "bleeding", "frostbite", "fire", "lightning", "magic", "holy")),
        "affinity order matches the native combat wire");
    var none = PhysickTears.affinityScales(EnumSet.noneOf(Tear.class));
    check(Arrays.stream(none).allMatch(v -> v == 1), "no tears, no change");
    var intelligence = PhysickTears.affinityScales(EnumSet.of(Tear.INTELLIGENCE_KNOT));
    near(intelligence[5], 1.25, "Intelligence-knot raises magic");
    near(intelligence[2], 1.25, "Intelligence-knot raises frostbite buildup");
    near(intelligence[3], 1, "Intelligence-knot leaves fire");
    var faith = PhysickTears.affinityScales(EnumSet.of(Tear.FAITH_KNOT, Tear.FLAME_SHROUDING));
    near(faith[3], 1.5, "Faith-knot and Flame-Shrouding stack on fire");
    near(faith[6], 1.25, "Faith-knot raises holy");
    var all = PhysickTears.affinityScales(EnumSet.allOf(Tear.class));
    check(
        Arrays.stream(all).allMatch(v -> v >= 1 && v <= 1.5 + 1e-9),
        "every scale stays inside the native bound of 1.5");
    near(PhysickTears.meleeScale(true, true, 0), 1.2, "Spiked raises critical hits");
    near(PhysickTears.meleeScale(false, true, 0), 1, "Spiked ignores ordinary hits");
    near(PhysickTears.meleeScale(false, false, 9), 1.2, "Thorny caps at +20%");
    near(PhysickTears.meleeScale(true, true, 5), 1.44, "both multiply");
    check(PhysickTears.thornyStacks(-1, 0, 100) == 0, "the first hit starts a combo");
    check(PhysickTears.thornyStacks(100, 0, 140) == 1, "a hit within 2 s continues it");
    check(PhysickTears.thornyStacks(100, 4, 120) == 5, "stacks grow");
    check(PhysickTears.thornyStacks(100, 5, 120) == 5, "stacks cap at five");
    check(PhysickTears.thornyStacks(100, 4, 141) == 0, "a pause resets the combo");
    check(PhysickTears.absorptionAmplifier(20) == 0, "start: Absorption I");
    check(PhysickTears.absorptionAmplifier(92) == 1, "full capacity: Absorption II");
    check(PhysickTears.absorptionAmplifier(Double.NaN) == 0, "invalid health is bounded");
  }

  private static void registrations() {
    check(
        BuiltInRegistries.ITEM
            .getKey(PhysickItems.FLASK)
            .equals(PhysickItems.FLASK_KEY.identifier()),
        "the flask is registered under its key");
    var stack = new ItemStack(PhysickItems.FLASK);
    check(stack.getMaxStackSize() == 1, "one flask per slot");
    var consumable = stack.get(DataComponents.CONSUMABLE);
    check(
        consumable != null
            && consumable.animation() == ItemUseAnimation.DRINK
            && consumable.consumeTicks() == 32,
        "drunk like a potion in 1.6 s");
    check(!WondrousPhysickItem.filled(stack), "a new flask is empty");
    var display = WondrousPhysickItem.display(true, List.of(Tear.STRENGTH_KNOT, Tear.WINDY));
    stack.set(DataComponents.CUSTOM_MODEL_DATA, display);
    check(WondrousPhysickItem.filled(stack), "the filled flag selects the filled model");
    check(
        WondrousPhysickItem.mixture(stack).equals(List.of("strength_knot", "windy")),
        "the stack shows the mixture");
    check(
        display.getColor(0) == (0xff000000 | Tear.STRENGTH_KNOT.color)
            && display.getColor(1) == (0xff000000 | Tear.WINDY.color),
        "each liquid half takes its tear's colour");
    var single = WondrousPhysickItem.display(true, List.of(Tear.CRIMSON));
    check(single.getColor(0).equals(single.getColor(1)), "one tear fills both halves");
    int custom = 0;
    for (var tear : Tear.values()) {
      var vanilla = PhysickItems.vanilla(tear);
      var effect = PhysickItems.effect(tear);
      if (tear.instant()) check(vanilla == null && effect == null, tear.id + " is instant");
      else check((vanilla == null) != (effect == null), tear.id + " has exactly one effect");
      if (tear.route == PhysickTears.Route.READY && !tear.instant())
        check(vanilla != null, tear.id + " uses an already bridged vanilla effect");
      if (effect != null) {
        custom++;
        check(
            BuiltInRegistries.MOB_EFFECT
                .getKey(effect.value())
                .equals(Identifier.fromNamespaceAndPath("eldencraft_bridge", tear.id)),
            "effect ID is the tear ID: " + tear.id);
      }
    }
    check(custom == 19, "19 EldenCraft tear effects");
  }

  private static void assets() throws IOException {
    var lang = Files.readString(ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8);
    check(
        lang.contains("\"item.eldencraft_bridge.wondrous_physick\": \"Flask of Wondrous Physick\""),
        "item name");
    for (var file :
        List.of(
            "items/wondrous_physick.json",
            "models/item/wondrous_physick.json",
            "models/item/wondrous_physick_empty.json",
            "textures/item/wondrous_physick.png",
            "textures/item/wondrous_physick_empty.png",
            "textures/item/wondrous_physick_left.png",
            "textures/item/wondrous_physick_right.png"))
      check(Files.isRegularFile(ASSETS.resolve(file)), "flask asset: " + file);
    for (var tear : Tear.values()) {
      check(
          Files.isRegularFile(ASSETS.resolve("textures/gui/sprites/physick/" + tear.id + ".png")),
          "mixing screen sprite: " + tear.id);
      if (PhysickItems.effect(tear) != null) {
        check(
            Files.isRegularFile(ASSETS.resolve("textures/mob_effect/" + tear.id + ".png")),
            "status icon: " + tear.id);
        check(
            lang.contains(
                "\"effect.eldencraft_bridge." + tear.id + "\": \"" + tear.displayName + "\""),
            "effect name: " + tear.id);
      }
    }
  }

  private static final String HOST =
      """
      {"version":1,"pid":123,"session":456,"seq":1,"timestamp_ms":1000,
       "active":true,"character":"slot-0-character-1","runes":0,
       "hp":414,"max_hp":414,"stamina":96,"max_stamina":96,"defeated":[]}
      """;

  private static void bridge() throws IOException {
    var old = CampaignBridge.decode(HOST.getBytes(StandardCharsets.UTF_8));
    check(!old.physick() && old.tears().isEmpty(), "older hosts own no physick");
    var owned =
        CampaignBridge.decode(
            HOST.replace(
                    "\"defeated\":[]",
                    "\"defeated\":[],\"physick\":true,\"tears\":[\"crimson\",\"crimson\",\"twiggy\"]")
                .getBytes(StandardCharsets.UTF_8));
    check(owned.physick() && owned.tears().size() == 3, "native ownership is published");
    for (String bad : List.of("[\"twiggy\",\"twiggy\"]", "[\"arcane_knot\"]", "\"twiggy\"", "[1]"))
      rejectHost(HOST.replace("\"defeated\":[]", "\"defeated\":[],\"tears\":" + bad));
    var bubble =
        CampaignBridge.decode(
            HOST.replace(
                    "\"defeated\":[]",
                    "\"defeated\":[],\"damage_events\":[{\"seq\":1,\"raw_damage\":8,\"blocked\":false,\"bubble\":true}]")
                .getBytes(StandardCharsets.UTF_8));
    check(bubble.damageEvents().getFirst().bubble(), "a spent bubble reaches Minecraft");
    rejectHost(
        HOST.replace(
            "\"defeated\":[]",
            "\"defeated\":[],\"damage_events\":[{\"seq\":1,\"raw_damage\":8,\"blocked\":true,\"bubble\":true}]"));
  }

  private static void rejectHost(String text) {
    try {
      CampaignBridge.decode(text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException expected) {
      ++checks;
      return;
    }
    throw new AssertionError("malformed host accepted: " + text);
  }

  private static void graceRow() {
    var sort = new InteractionProtocol.Choice(3, "Sort chest", true, "ender_chest");
    var grace =
        new InteractionProtocol.Menu(
            9,
            "grace",
            "Stormhill Shack",
            List.of(
                new InteractionProtocol.Choice(1, "Pass time", true, ""),
                new InteractionProtocol.Choice(2, "Flasks", true, ""),
                sort,
                new InteractionProtocol.Choice(4, "Leave", true, "")));
    var rows = InteractionScreen.visibleChoices(grace, true);
    check(
        rows.stream()
            .map(InteractionProtocol.Choice::text)
            .toList()
            .equals(List.of("Pass time", "Sort chest", "Mix Wondrous Physick", "Leave")),
        "the Physick row follows Sort chest and native flask rows stay hidden: " + rows);
    check(
        rows.get(2).action().equals(InteractionScreen.PHYSICK) && rows.get(2).enabled(),
        "the row is EldenCraft's own");
    check(
        InteractionScreen.visibleChoices(grace, false).stream()
            .noneMatch(c -> c.action().equals(InteractionScreen.PHYSICK)),
        "no flask, no row");
    var npc =
        new InteractionProtocol.Menu(
            9, "npc", "Kale", List.of(new InteractionProtocol.Choice(1, "Talk", true, "")));
    check(InteractionScreen.visibleChoices(npc, true).size() == 1, "only graces offer mixing");
  }

  private static void stamina() {
    var rules = new CampaignConfig.Stamina(Map.of(), 20, 0, 0, 0, 10);
    var plain = new CampaignStamina(100);
    var burst = new CampaignStamina(100);
    plain.spend(60);
    burst.spend(60);
    plain.tick(1, false, rules);
    burst.tick(1, false, rules, PhysickTears.GREENBURST_RECOVERY);
    near(plain.current(), 60, "ordinary recovery");
    near(burst.current(), 66, "Greenburst recovers 30% faster");
    var capped = new CampaignStamina(100);
    capped.spend(90);
    capped.tick(1, false, rules, 50);
    near(capped.current(), 50, "recovery scale is bounded at 2");
  }

  private static ClassNode node(String type) throws Exception {
    var node = new ClassNode();
    try (var stream =
        PhysickConformance.class.getClassLoader().getResourceAsStream(type + ".class")) {
      check(stream != null, "pinned class exists: " + type);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  private static void hook(String type, String method, String descriptor) throws Exception {
    check(
        node(type).methods.stream()
            .anyMatch(m -> m.name.equals(method) && m.desc.equals(descriptor)),
        "exact vanilla hook: " + type + "." + method + descriptor);
  }

  private static void calls(String type, String method, String owner, String name, String desc)
      throws Exception {
    boolean found = false;
    for (var m : node(type).methods)
      if (m.name.equals(method))
        for (var i : m.instructions)
          if (i instanceof MethodInsnNode call
              && call.owner.equals(owner)
              && call.name.equals(name)
              && call.desc.equals(desc)) found = true;
    check(found, "actual hook invocation: " + type + "." + method + " -> " + name);
  }

  private static void hooks() throws Exception {
    String player = "net/minecraft/world/entity/player/Player";
    calls(player, "attack", player, "canCriticalAttack", "(Lnet/minecraft/world/entity/Entity;)Z");
    calls(
        player,
        "attack",
        "net/minecraft/world/entity/Entity",
        "hurtOrSimulate",
        "(Lnet/minecraft/world/damagesource/DamageSource;F)Z");
    // A shield's Riposte also empowers spear jabs, which vanilla resolves in stabAttack.
    calls(
        player,
        "stabAttack",
        "net/minecraft/world/entity/Entity",
        "hurtOrSimulate",
        "(Lnet/minecraft/world/damagesource/DamageSource;F)Z");
    hook(player, "causeFoodExhaustion", "(F)V");
    hook(
        "net/minecraft/server/level/ServerPlayer",
        "hurtServer",
        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z");
    hook(
        "net/minecraft/world/item/ItemStack",
        "hurtAndBreak",
        "(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V");
    calls(
        "net/minecraft/world/item/ProjectileWeaponItem",
        "useAmmo",
        "net/minecraft/world/entity/LivingEntity",
        "hasInfiniteMaterials",
        "()Z");
  }
}
