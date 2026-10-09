package dev.eldencraft.bridge;

import dev.eldencraft.bridge.client.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Actual vanilla components and hook contracts, with no running game or native actor. */
public final class CombatItemsConformance {
  private static int checks;

  private static void check(boolean result, String message) {
    ++checks;
    if (!result) throw new AssertionError(message);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < 1e-6, message + ": " + actual);
  }

  private static ClassNode node(String type) throws Exception {
    var node = new ClassNode();
    try (var stream =
        CombatItemsConformance.class.getClassLoader().getResourceAsStream(type + ".class")) {
      check(stream != null, "pinned class exists: " + type);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  private static void hook(String type, String method, String descriptor) throws Exception {
    check(
        node(type).methods.stream()
            .anyMatch(m -> m.name.equals(method) && m.desc.equals(descriptor)),
        "exact vanilla hook: " + type + "." + method);
  }

  private static void calls(String type, String method, String owner, String name)
      throws Exception {
    boolean found = false;
    for (var m : node(type).methods)
      if (m.name.equals(method))
        for (var i : m.instructions)
          if (i instanceof MethodInsnNode call
              && call.owner.equals(owner)
              && call.name.equals(name)) found = true;
    check(found, "actual hook invocation: " + type + "." + method + " -> " + name);
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var vanilla = VanillaRegistries.createWorldLookup();
    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(vanilla).forEach(PendingComponents::apply);
    var rules = CampaignConfig.load(Path.of("../config/campaign.json"));
    CampaignCombat.validateRegistry(rules);
    var shops = CampaignShopCatalog.parse(rules.raw());
    for (var tier :
        List.of("wooden", "stone", "copper", "golden", "iron", "diamond", "netherite")) {
      var id = "minecraft:" + tier + "_spear";
      var stack = CampaignItems.stack(id, 1, "");
      check(
          stack.has(DataComponents.PIERCING_WEAPON) && stack.has(DataComponents.KINETIC_WEAPON),
          "spear retains both actual attack components");
      var use = stack.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT);
      check(
          use.canSprint() && use.speedMultiplier() > .2,
          "spear charges permit sprinting with vanilla use movement");
      check(CampaignCombat.kind(stack).equals("spear"), "spear stamina class");
      check(
          shops.shops().stream()
              .flatMap(s -> s.offers().stream())
              .anyMatch(o -> o.item().equals(id)),
          "each tier sold");
      check(
          rules.enemyLoot.entries().stream().anyMatch(e -> e.item().equals(id)), "each tier drops");
    }
    check(
        CampaignCombat.kind(CampaignItems.stack("minecraft:mace", 1, "")).equals("mace"),
        "mace stamina class");
    for (var shop : shops.shops())
      for (var offer : shop.offers()) {
        var stack = CampaignItems.stack(offer.item(), offer.count(), offer.potion());
        if (!offer.potion().isEmpty()) {
          var contents = stack.get(DataComponents.POTION_CONTENTS);
          check(
              contents != null
                  && contents
                      .potion()
                      .orElseThrow()
                      .unwrapKey()
                      .orElseThrow()
                      .identifier()
                      .toString()
                      .equals(offer.potion()),
              "shop potion preserves registered variant");
          check(
              contents.getAllEffects().iterator().hasNext(),
              "combat potion has actual vanilla effects");
          check(
              stack.getCount() <= stack.getMaxStackSize(),
              "configured potion pack fits a vanilla stack");
        }
      }
    var healing = CampaignItems.stack("minecraft:potion", 1, "minecraft:healing");
    var capped =
        CampaignGearTiers.cap(
            List.of(
                new CampaignConfig.Reward("minecraft:potion", 1, "minecraft:strong_healing"),
                new CampaignConfig.Reward("minecraft:netherite_spear", 1)),
            Set.of());
    check(
        capped.getFirst().potion().equals("minecraft:strong_healing"),
        "reward tier adjustment preserves potion contents");
    check(
        capped.getLast().item().equals("minecraft:wooden_spear"),
        "optional spear rewards respect earned equipment tiers");
    var strength = CampaignItems.stack("minecraft:potion", 1, "minecraft:strength");
    check(
        !ItemStack.isSameItemSameComponents(healing, strength),
        "inventory capacity distinguishes variants");
    var directory = Files.createTempDirectory("eldencraft-combat-items-");
    var file = directory.resolve("ledger.json");
    try {
      var ledger = new CampaignShopLedger(file, "character");
      String purchase = UUID.randomUUID().toString();
      ledger.begin(
          new CampaignShopLedger.Pending(
              purchase,
              "token",
              "merchant",
              "campaign",
              "potion_healing",
              "minecraft:potion",
              1,
              2,
              700,
              CampaignShopLedger.Stage.INTENT,
              -1,
              "minecraft:healing"));
      ledger.debited(purchase);
      var saved = new CampaignShopLedger(file, "character").pending();
      check(
          saved.stage() == CampaignShopLedger.Stage.DEBITED
              && saved.potion().equals("minecraft:healing"),
          "paid purchase retains exact potion across restart");
      check(
          ItemStack.isSameItemSameComponents(
              healing, CampaignItems.stack(saved.item(), 1, saved.potion())),
          "recovered delivery recreates paid contents");
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
    }

    // A constructor-free carrier exposes only LivingEntity's real effect map.
    var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    var carrier = (Zombie) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Zombie.class);
    var effectField = LivingEntity.class.getDeclaredField("activeEffects");
    effectField.setAccessible(true);
    var effects = new HashMap<net.minecraft.core.Holder<MobEffect>, MobEffectInstance>();
    effectField.set(carrier, effects);
    effects.put(MobEffects.SPEED, new MobEffectInstance(MobEffects.SPEED, 100, 1));
    near(CampaignPotions.speed(carrier), 1.4, "Speed II mirrors vanilla modifier");
    effects.put(MobEffects.SLOWNESS, new MobEffectInstance(MobEffects.SLOWNESS, 100, 5));
    near(
        CampaignPotions.speed(carrier),
        .14,
        "strong turtle master Slowness VI combines with Speed II");
    effects.put(MobEffects.RESISTANCE, new MobEffectInstance(MobEffects.RESISTANCE, 100, 3));
    near(CampaignPotions.resistance(carrier), .2, "Resistance IV mitigation");
    effects.put(MobEffects.WEAKNESS, new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
    near(CampaignPotions.attackBonus(carrier), -4, "Weakness lowers native enemy damage");
    effects.put(MobEffects.STRENGTH, new MobEffectInstance(MobEffects.STRENGTH, 100, 1));
    near(CampaignPotions.attackBonus(carrier), 2, "Strength II and Weakness combine");
    effects.put(MobEffects.JUMP_BOOST, new MobEffectInstance(MobEffects.JUMP_BOOST, 100, 1));
    near(CampaignPotions.jumpBonus(carrier), 4, "Leaping II adds actual takeoff velocity");
    effects.clear();
    near(CampaignPotions.speed(carrier), 1, "expiration or milk releases slowdown");
    near(CampaignPotions.resistance(carrier), 1, "expiration releases mitigation");
    MobEffects.SLOWNESS
        .value()
        .createModifiers(
            5,
            (attribute, modifier) -> {
              if (attribute.equals(Attributes.MOVEMENT_SPEED)) {
                near(modifier.amount(), -.9, "actual Slowness VI attribute");
                check(
                    modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                    "vanilla movement modifiers multiply totals");
              }
            });

    var motion = new CombatMotion();
    motion.observe("world", 1, 1000, new WorldOrigin.Vec(0, 10, 0), false, true);
    motion.observe("world", 2, 1050, new WorldOrigin.Vec(0, 8, 0), false, true);
    motion.observe("world", 3, 1100, new WorldOrigin.Vec(0, 5, 0), false, true);
    near(motion.fallDistance(), 5, "continuous native fall earns smash damage");
    near(motion.velocity().y(), -3, "kinetic motion is blocks per vanilla tick");
    motion.consumeFall();
    near(motion.fallDistance(), 0, "successful smash consumes accumulated fall");
    motion.observe("world", 4, 1450, new WorldOrigin.Vec(0, 3, 0), false, true);
    near(motion.fallDistance(), 0, "bridge gap resets fall");
    motion.observe("other", 5, 1500, new WorldOrigin.Vec(0, 1, 0), false, true);
    near(motion.fallDistance(), 0, "map change cannot earn fall");
    motion.observe("other", 6, 1550, new WorldOrigin.Vec(0, -20, 0), false, true);
    near(motion.fallDistance(), 0, "teleport cannot earn fall");
    motion.observe("other", 7, 1600, new WorldOrigin.Vec(0, -21, 0), true, true);
    near(motion.fallDistance(), 0, "landing ends smash eligibility");

    String living = "net/minecraft/world/entity/LivingEntity",
        entity = "net/minecraft/world/entity/Entity";
    hook(
        "net/minecraft/world/item/component/PiercingWeapon",
        "attack",
        "(L" + living + ";Lnet/minecraft/world/entity/EquipmentSlot;)V");
    hook(
        "net/minecraft/world/item/component/KineticWeapon",
        "damageEntities",
        "(Lnet/minecraft/world/item/ItemStack;IL"
            + living
            + ";Lnet/minecraft/world/entity/EquipmentSlot;)V");
    hook(
        "net/minecraft/world/entity/player/Player",
        "stabAttack",
        "(Lnet/minecraft/world/entity/EquipmentSlot;L" + entity + ";FZZZ)Z");
    calls("net/minecraft/world/effect/HealOrHarmMobEffect", "applyEffectTick", living, "heal");
    calls(
        "net/minecraft/world/effect/HealOrHarmMobEffect",
        "applyInstantaneousEffect",
        living,
        "heal");
    calls(
        "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownLingeringPotion",
        "onHitAsPotion",
        "net/minecraft/server/level/ServerLevel",
        "addFreshEntity");
    hook("net/minecraft/world/entity/AreaEffectCloud", "tick", "()V");
    hook(
        "net/minecraft/world/item/ThrowablePotionItem",
        "use",
        "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;");
    System.out.println(
        "Combat items conformance: "
            + checks
            + " checks passed (actual components and hook contracts; no live game claim).");
  }
}
