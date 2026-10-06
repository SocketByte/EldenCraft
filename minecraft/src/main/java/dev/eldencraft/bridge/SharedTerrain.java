package dev.eldencraft.bridge;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

/** Immutable collision geometry shared by the two threads of the integrated server. */
public final class SharedTerrain {
  private static volatile Map<Long, VoxelShape> shapes = Map.of();
  public static java.util.function.Predicate<Entity> projectileShapeBypass = entity -> false;

  private SharedTerrain() {}

  public static VoxelShape shape(BlockPos position) {
    // Saved shadow blocks without a fresh cache remain solid instead of becoming holes.
    return shapes.getOrDefault(position.asLong(), Shapes.block());
  }

  // Shadow geometry is a bounded approximation for mob simulation, never the
  // collision oracle for tracked projectiles. Their true native contacts are
  // returned by the native flight raycaster. Real Minecraft blocks never use
  // this helper and retain all vanilla collision behavior.
  /** Fresh sampled surface only: absent cells are empty, never a floating cube. */
  public static VoxelShape outline(BlockPos position) {
    return shapes.getOrDefault(position.asLong(), Shapes.empty());
  }

  /**
   * Mobs and items keep the conservative fallback (border shell and stale cells solid), so they
   * never walk into unsampled space. Players collide only with fresh surfaces: a cell the client
   * still holds a tick after the server removed it must not stop the player.
   */
  public static VoxelShape collision(
      BlockPos position, boolean boundary, CollisionContext context) {
    var entity = context instanceof EntityCollisionContext e ? e.getEntity() : null;
    if (entity != null && projectileShapeBypass.test(entity)) return Shapes.empty();
    if (entity instanceof net.minecraft.world.entity.player.Player)
      return boundary ? Shapes.empty() : outline(position);
    return boundary ? Shapes.block() : shape(position);
  }

  public static Map<Long, VoxelShape> snapshot() {
    return shapes;
  }

  public static void publish(Map<Long, VoxelShape> next) {
    shapes = Map.copyOf(next);
  }

  /**
   * Pieces one block may hold: refined 25 cm floor columns from up to four host cells can share a
   * block.
   */
  public static final int MAX_PIECES = 96;

  public static VoxelShape shape(List<AABB> pieces) {
    if (pieces.size() > MAX_PIECES) throw new IllegalArgumentException("Terrain shape complexity");
    VoxelShape out = Shapes.empty();
    for (var box : pieces) {
      if (box.minX < 0
          || box.minY < 0
          || box.minZ < 0
          || box.maxX > 1
          || box.maxY > 1
          || box.maxZ > 1
          || box.getXsize() <= 0
          || box.getYsize() <= 0
          || box.getZsize() <= 0) throw new IllegalArgumentException("Terrain shape bounds");
      out = Shapes.or(out, Shapes.create(box));
    }
    return out.optimize();
  }
}
