package dev.eldencraft.bridge;

import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.Consumable;

/** The flask item and one status effect per tear that has no vanilla equivalent. */
public final class PhysickItems {
  public static final String NAMESPACE = "eldencraft_bridge";
  public static final ResourceKey<Item> FLASK_KEY =
      ResourceKey.create(
          Registries.ITEM, Identifier.fromNamespaceAndPath(NAMESPACE, "wondrous_physick"));
  public static final Item FLASK =
      Registry.register(
          BuiltInRegistries.ITEM,
          FLASK_KEY,
          new WondrousPhysickItem(
              new Item.Properties()
                  .setId(FLASK_KEY)
                  .stacksTo(1)
                  .fireResistant()
                  .rarity(Rarity.RARE)
                  .component(
                      DataComponents.CUSTOM_MODEL_DATA,
                      WondrousPhysickItem.display(false, List.of()))
                  .component(
                      DataComponents.CONSUMABLE,
                      Consumable.builder()
                          .consumeSeconds(1.6f)
                          .animation(ItemUseAnimation.DRINK)
                          .sound(SoundEvents.GENERIC_DRINK)
                          .hasConsumeParticles(false)
                          .build())));

  private static final Map<PhysickTears.Tear, Holder<MobEffect>> CUSTOM =
      new EnumMap<>(PhysickTears.Tear.class);

  static {
    for (var tear : PhysickTears.Tear.values()) {
      if (vanilla(tear) != null || tear.instant()) continue;
      var effect = new PhysickEffect(tear.color);
      var id = Identifier.fromNamespaceAndPath(NAMESPACE, tear.id);
      if (tear == PhysickTears.Tear.LEADEN)
        effect.addAttributeModifier(
            Attributes.KNOCKBACK_RESISTANCE, id, 1, AttributeModifier.Operation.ADD_VALUE);
      if (tear == PhysickTears.Tear.STONEBARB)
        effect.addAttributeModifier(
            Attributes.ATTACK_KNOCKBACK, id, 1, AttributeModifier.Operation.ADD_VALUE);
      CUSTOM.put(tear, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id, effect));
    }
  }

  private PhysickItems() {}

  /** Class loading registers the flask and the tear effects during mod initialization. */
  public static void initialize() {}

  /** The vanilla effect a Ready tear grants, if any. */
  public static Holder<MobEffect> vanilla(PhysickTears.Tear tear) {
    return switch (tear) {
      case CRIMSONSPILL -> MobEffects.ABSORPTION;
      case CRIMSONBURST -> MobEffects.REGENERATION;
      case STRENGTH_KNOT -> MobEffects.STRENGTH;
      case DEXTERITY_KNOT -> MobEffects.HASTE;
      case OPALINE_HARD -> MobEffects.RESISTANCE;
      case WINGED -> MobEffects.JUMP_BOOST;
      case WINDY -> MobEffects.SPEED;
      default -> null;
    };
  }

  /** The EldenCraft effect for a tear without a vanilla equivalent; null for instant tears. */
  public static Holder<MobEffect> effect(PhysickTears.Tear tear) {
    return CUSTOM.get(tear);
  }

  public static Map<PhysickTears.Tear, Holder<MobEffect>> effects() {
    return Collections.unmodifiableMap(CUSTOM);
  }

  /** A status effect whose behaviour lives in the campaign code that checks for it. */
  public static final class PhysickEffect extends MobEffect {
    public PhysickEffect(int color) {
      super(MobEffectCategory.BENEFICIAL, color);
    }
  }
}
