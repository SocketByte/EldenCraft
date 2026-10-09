package dev.eldencraft.bridge;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Run the actual vanilla blast-ray calculation in a bounded, chunk-free level fixture. */
public final class ExplosionConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  // Only the overridden reads and Level.random are used by calculateExplodedPositions.
  // Unsafe avoids starting a server, loading chunks or touching saves.
  private static final class BlastLevel extends ServerLevel {
    BlockState floor;

    private BlastLevel() {
      super(null, null, null, null, null, null, false, 0, List.of(), false);
      throw new AssertionError("fixture constructor must never run");
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      if (pos.getY() == 0) return floor;
      return pos.equals(new BlockPos(1, 1, 0))
          ? Blocks.TNT.defaultBlockState()
          : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public boolean isInWorldBounds(BlockPos pos) {
      return Math.abs(pos.getX()) < 16 && Math.abs(pos.getY()) < 16 && Math.abs(pos.getZ()) < 16;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
      throw new AssertionError("explosion must never alter shadow terrain");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<BlockPos> reached(BlastLevel level, Vec3 origin) throws Exception {
    var explosion =
        new ServerExplosion(
            level,
            null,
            new DamageSource(Holder.direct(new DamageType("explosion", 0))),
            new ExplosionDamageCalculator(),
            origin,
            4,
            false,
            Explosion.BlockInteraction.DESTROY);
    var method = ServerExplosion.class.getDeclaredMethod("calculateExplodedPositions");
    method.setAccessible(true);
    return (List<BlockPos>) method.invoke(explosion);
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    // Register the real mod block during bootstrap, before vanilla freezes the registry.
    // This standalone JVM has no Fabric initializer; mirror bootstrap's entry guard,
    // then let vanilla finish normally. Never thaw a registry or modify a running game.
    var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
    bootstrapped.setAccessible(true);
    bootstrapped.setBoolean(null, true);
    try {
      SharedWorldBlocks.initialize();
    } finally {
      bootstrapped.setBoolean(null, false);
    }
    Bootstrap.bootStrap();
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    var unsafe = (sun.misc.Unsafe) field.get(null);
    var level = (BlastLevel) unsafe.allocateInstance(BlastLevel.class);
    var random = net.minecraft.world.level.Level.class.getDeclaredField("random");
    random.setAccessible(true);
    random.set(level, RandomSource.create(123));
    var origin = new Vec3(.5, .55, .5); // Primed TNT resting on a partial floor in cell y=0.
    var target = new BlockPos(1, 1, 0);
    level.floor = Blocks.BARRIER.defaultBlockState();
    check(
        !reached(level, origin).contains(target),
        "blast-proof origin reproduces lost chain reaction");
    var terrain = (ShadowTerrainBlock) SharedWorldBlocks.TERRAIN;
    level.floor = terrain.defaultBlockState();
    SharedTerrain.publish(Map.of(BlockPos.ZERO.asLong(), Shapes.box(0, 0, 0, 1, .5, 1)));
    check(
        level.floor.getCollisionShape(level, BlockPos.ZERO, CollisionContext.empty()).bounds().maxY
            == .5,
        "fixture reproduces a partial floor occupying the blast origin's cell");
    check(terrain.getExplosionResistance() == 0, "collision cache cannot absorb blast energy");
    check(
        reached(level, origin).contains(target),
        "vanilla rays reach neighboring TNT from partial floor cell");
    SharedTerrain.publish(Map.of());
    check(
        reached(level, origin).contains(target),
        "stale collision fallback cannot stop chain reactions");
    level.floor = level.floor.setValue(ShadowTerrainBlock.BOUNDARY, true);
    check(
        reached(level, origin).contains(target),
        "invisible boundary shell cannot stop chain reactions");
    terrain.onExplosionHit(
        level.floor,
        level,
        BlockPos.ZERO,
        null,
        (stack, pos) -> {
          throw new AssertionError("shadow terrain must never drop items");
        });
    check(!terrain.dropFromExplosion(null), "terrain survives without explosion drops");
    check(level.floor.getDestroySpeed(level, BlockPos.ZERO) < 0, "terrain remains unmineable");
    level.floor = Blocks.OBSIDIAN.defaultBlockState();
    check(
        !reached(level, origin).contains(target),
        "real blast-resistant blocks still stop vanilla rays");
    System.out.println(
        "Explosion conformance: "
            + checks
            + " checks passed (actual vanilla blast rays; no live gameplay claim).");
  }
}
