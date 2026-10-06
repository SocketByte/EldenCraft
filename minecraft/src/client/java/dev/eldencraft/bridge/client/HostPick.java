package dev.eldencraft.bridge.client;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;

/** Scope the genuine vanilla raycaster to the camera actually rendered. No entity is moved. */
public final class HostPick {
  private record Scope(Entity entity, Vec3 eye, Vec3 forward, AABB box, double extension) {}

  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private HostPick() {}

  public static Vec3 eye(Entity e) {
    var s = CURRENT.get();
    return s != null && s.entity == e ? s.eye : null;
  }

  public static Vec3 forward(Entity e) {
    var s = CURRENT.get();
    return s != null && s.entity == e ? s.forward : null;
  }

  public static AABB box(Entity e) {
    var s = CURRENT.get();
    return s != null && s.entity == e ? s.box : null;
  }

  public static double range(double vanilla) {
    var s = CURRENT.get();
    return s == null ? vanilla : vanilla + s.extension;
  }

  public static HitResult pick(Entity entity, Supplier<HitResult> vanilla) {
    var mc = Minecraft.getInstance();
    var pose = HostController.frame();
    if (entity != mc.player
        || pose == null
        || !SharedWorldClient.active()
        || HostController.damageSnapshot(mc) == null) return vanilla.get();
    Vec3 oldEye = entity.getEyePosition(1), eye = new Vec3(pose.x(), pose.y(), pose.z());
    var forward = Vec3.directionFromRotation(pose.pitch(), pose.yaw());
    if (eye.distanceToSqr(oldEye) > 64) return vanilla.get();
    var scope =
        new Scope(
            entity,
            eye,
            forward,
            entity.getBoundingBox().move(eye.subtract(oldEye)),
            eye.distanceTo(oldEye));
    var prior = CURRENT.get();
    HitResult hit;
    try {
      CURRENT.set(scope);
      hit = vanilla.get();
    } finally {
      if (prior == null) CURRENT.remove();
      else CURRENT.set(prior);
    }
    // The displayed origin cannot grant extra interaction range. The server
    // still applies its own normal reach/line-of-sight and item authority.
    if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
      var obstruction =
          mc.level.clip(
              new net.minecraft.world.level.ClipContext(
                  oldEye,
                  hit.getLocation(),
                  net.minecraft.world.level.ClipContext.Block.OUTLINE,
                  net.minecraft.world.level.ClipContext.Fluid.NONE,
                  entity));
      if (oldEye.distanceToSqr(hit.getLocation()) > Math.pow(mc.player.blockInteractionRange(), 2)
          || obstruction.getType() == HitResult.Type.BLOCK
              && !obstruction.getBlockPos().equals(block.getBlockPos()))
        return BlockHitResult.miss(hit.getLocation(), block.getDirection(), block.getBlockPos());
    }
    if (hit instanceof EntityHitResult target
        && !mc.player
            .getAttackRangeWith(mc.player.getActiveItem())
            .isInRange(mc.player, target.getEntity().getBoundingBox(), 0))
      return BlockHitResult.miss(
          hit.getLocation(),
          Direction.getApproximateNearest(forward),
          BlockPos.containing(hit.getLocation()));
    return hit;
  }
}
