package dev.eldencraft.bridge;

import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.*;

/** An invisible, non-destructible cache of ER terrain, not a Minecraft building material. */
public final class ShadowTerrainBlock extends Block {
  /**
   * A cell of the closed shell around the sampled area: solid for mobs and items so they cannot
   * walk into unsampled space, but never an outline, a placement target, an obstacle for the player
   * or something a placed block cannot replace.
   */
  public static final BooleanProperty BOUNDARY = BooleanProperty.create("boundary");

  public ShadowTerrainBlock(BlockBehaviour.Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(BOUNDARY, false));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BOUNDARY);
  }

  public static boolean boundary(BlockState state) {
    return state.getValueOrElse(BOUNDARY, false);
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.INVISIBLE;
  }

  // Like vanilla BarrierBlock, collision-only geometry must not become an
  // invisible roof or an ambient-occlusion source. noOcclusion alone does not
  // override these methods: the defaults inspect our full collision fallback.
  @Override
  protected boolean propagatesSkylightDown(BlockState state) {
    return true;
  }

  @Override
  protected int getLightDampening(BlockState state) {
    return 0;
  }

  @Override
  protected float getShadeBrightness(BlockState state, BlockGetter world, BlockPos pos) {
    return 1;
  }

  // Outlines, picking and placement use only fresh sampled surfaces: a cell the
  // current snapshot does not describe (just removed, saved by an earlier session,
  // or the border shell) never appears as a floating cube.
  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
    return boundary(state) ? Shapes.empty() : SharedTerrain.outline(pos);
  }

  @Override
  protected VoxelShape getCollisionShape(
      BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
    return SharedTerrain.collision(pos, boundary(state), context);
  }

  @Override
  protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
    return boundary(state);
  }

  @Override
  protected boolean isPathfindable(BlockState state, PathComputationType type) {
    return false;
  }

  @Override
  protected void onExplosionHit(
      BlockState state,
      ServerLevel level,
      BlockPos pos,
      Explosion explosion,
      BiConsumer<ItemStack, BlockPos> drops) {}

  @Override
  public boolean dropFromExplosion(Explosion explosion) {
    return false;
  }
}
