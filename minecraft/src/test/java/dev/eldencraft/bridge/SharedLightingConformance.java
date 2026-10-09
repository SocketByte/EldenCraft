package dev.eldencraft.bridge;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Real 26.3 block-state lighting behavior, without a world, renderer or game process. */
public final class SharedLightingConformance {
  private static int checks;

  private static void check(boolean value, String reason) {
    checks++;
    if (!value) throw new AssertionError(reason);
  }

  public static void main(String[] args) throws ReflectiveOperationException {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    // Standalone vanilla bootstrap freezes intrusive block registries. Test
    // only these stateless overrides on a constructor-free instance, using
    // the real cached barrier state as their reference argument. Never thaw
    // registries or add a fixture block to a running game. None of the tested
    // overrides reads an uninitialized inherited instance field.
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    var block =
        (ShadowTerrainBlock)
            ((sun.misc.Unsafe) field.get(null)).allocateInstance(ShadowTerrainBlock.class);
    var state = Blocks.BARRIER.defaultBlockState();
    var world = EmptyBlockGetter.INSTANCE;
    check(
        block.getRenderShape(state) == RenderShape.INVISIBLE,
        "collision cache override remains invisible");
    check(
        !state.canOcclude() && !state.isSolidRender(),
        "vanilla barrier reference does not hide visible faces");
    check(
        block.propagatesSkylightDown(state),
        "an invisible simulation ceiling override must pass direct sky");
    check(
        block.getLightDampening(state) == 0,
        "stacked collision cells must not attenuate sky or torch light");
    check(
        !state.useShapeForLightOcclusion(),
        "vanilla barrier reference does not use collision for light occlusion");
    for (var pos :
        new BlockPos[] {BlockPos.ZERO, new BlockPos(-17, 63, -1), new BlockPos(16, 100, 17)}) {
      SharedTerrain.publish(Map.of());
      check(
          block
              .getCollisionShape(state, world, pos, CollisionContext.empty())
              .equals(Shapes.block()),
          "unavailable collision cache remains conservatively solid " + pos);
      check(
          block.getShape(state, world, pos, CollisionContext.empty()).isEmpty(),
          "a cell missing from the fresh snapshot is never an outline or placement target " + pos);
      check(
          SharedTerrain.collision(pos, true, CollisionContext.empty()).equals(Shapes.block()),
          "the border shell stays solid for mobs and items " + pos);
      check(SharedTerrain.outline(pos).isEmpty(), "absent cells are empty for players " + pos);
      check(
          block.getShadeBrightness(state, world, pos) == state.getShadeBrightness(world, pos),
          "fallback collision uses vanilla barrier ambient shade " + pos);
      SharedTerrain.publish(Map.of(pos.asLong(), Shapes.box(0, 0, 0, 1, .5, 1)));
      check(
          block.getCollisionShape(state, world, pos, CollisionContext.empty()).bounds().maxY == .5,
          "partial sampled collision remains unchanged " + pos);
      check(
          block.getShape(state, world, pos, CollisionContext.empty()).bounds().maxY == .5,
          "fresh sampled surface is still a usable placement preview " + pos);
      check(
          block.getShadeBrightness(state, world, pos) == 1,
          "partial hidden geometry also cannot add artificial ambient shade " + pos);
    }
    SharedTerrain.publish(Map.of());
    check(
        Blocks.STONE.defaultBlockState().getLightDampening() == 15,
        "genuine opaque player blocks retain real light blocking");
    check(
        Blocks.STONE.defaultBlockState().getShadeBrightness(world, BlockPos.ZERO) < 1,
        "genuine opaque player blocks retain vanilla ambient shade");
    check(
        Blocks.AIR.defaultBlockState().getLightDampening() == 0,
        "sky policy agrees with real empty space");
    // The light repair routes the light thread's unsaved report through these.
    check(
        !java.lang.reflect.Modifier.isStatic(
            net.minecraft.server.level.ChunkMap.class
                .getDeclaredMethod("setChunkUnsaved", net.minecraft.world.level.ChunkPos.class)
                .getModifiers()),
        "pinned ChunkMap unsaved report for the server-thread light listener");
    check(
        java.lang.reflect.Modifier.isPublic(
            net.minecraft.world.level.chunk.LevelChunk.class
                .getMethod(
                    "setUnsavedListener",
                    net.minecraft.world.level.chunk.LevelChunk.UnsavedListener.class)
                .getModifiers()),
        "loaded chunks accept a replacement unsaved listener");
    System.out.println(
        "Shared lighting conformance: "
            + checks
            + " checks passed (actual overrides and vanilla reference states; no live lighting"
            + " claim).");
  }
}
