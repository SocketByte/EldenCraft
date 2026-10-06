package dev.eldencraft.bridge;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public final class SharedWorldBlocks {
  public static final ResourceKey<Level> DIMENSION =
      ResourceKey.create(
          Registries.DIMENSION,
          Identifier.fromNamespaceAndPath("eldencraft_bridge", "shared_world"));
  private static final ResourceKey<Block> KEY =
      ResourceKey.create(
          Registries.BLOCK, Identifier.fromNamespaceAndPath("eldencraft_bridge", "shadow_terrain"));
  public static final Block TERRAIN =
      Registry.register(
          BuiltInRegistries.BLOCK,
          KEY,
          new ShadowTerrainBlock(
              BlockBehaviour.Properties.of()
                  .setId(KEY)
                  .strength(-1, 3_600_000)
                  .noLootTable()
                  .noOcclusion()
                  .dynamicShape()
                  .noTerrainParticles()
                  .pushReaction(PushReaction.IMMOVEABLE)
                  .isValidSpawn((state, world, pos, entity) -> false)));

  private SharedWorldBlocks() {}

  public static void initialize() {}
}
