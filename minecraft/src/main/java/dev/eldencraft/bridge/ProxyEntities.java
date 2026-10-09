package dev.eldencraft.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class ProxyEntities implements ModInitializer {
  public static final ResourceKey<EntityType<?>> KEY =
      ResourceKey.create(
          Registries.ENTITY_TYPE,
          Identifier.fromNamespaceAndPath("eldencraft_bridge", "combat_proxy"));
  public static final EntityType<CombatProxyEntity> TYPE =
      Registry.register(
          BuiltInRegistries.ENTITY_TYPE,
          KEY,
          EntityType.Builder.of(CombatProxyEntity::new, MobCategory.MISC)
              .sized(.6f, 1.8f)
              .noSave()
              .noSummon()
              .clientTrackingRange(32)
              .updateInterval(1)
              .build(KEY));

  public void onInitialize() {
    SharedWorldBlocks.initialize();
    VeinwearEasterEgg.initialize();
    FabricDefaultAttributeRegistry.register(
        TYPE,
        LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, 1024)
            .add(Attributes.ARMOR, 0)
            .add(Attributes.ARMOR_TOUGHNESS, 0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1));
  }
}
