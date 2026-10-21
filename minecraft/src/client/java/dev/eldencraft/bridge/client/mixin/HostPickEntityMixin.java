package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.eldencraft.bridge.client.HostPick;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

// getBoundingBox runs for every entity in every collision and lookup on both threads. Returning
// through these hooks allocates nothing, unlike a cancellable inject.
@Mixin(Entity.class)
abstract class HostPickEntityMixin {
  @ModifyReturnValue(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
  private Vec3 eldencraft$componentPickEye(Vec3 vanilla) {
    var v = HostPick.eye((Entity) (Object) this);
    return v != null ? v : vanilla;
  }

  @ModifyReturnValue(
      method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
      at = @At("RETURN"))
  private Vec3 eldencraft$pickEye(Vec3 vanilla) {
    var v = HostPick.eye((Entity) (Object) this);
    return v != null ? v : vanilla;
  }

  @ModifyReturnValue(method = "getViewVector", at = @At("RETURN"))
  private Vec3 eldencraft$pickDirection(Vec3 vanilla) {
    var v = HostPick.forward((Entity) (Object) this);
    return v != null ? v : vanilla;
  }

  @ModifyReturnValue(method = "getHeadLookAngle", at = @At("RETURN"))
  private Vec3 eldencraft$componentPickDirection(Vec3 vanilla) {
    var v = HostPick.forward((Entity) (Object) this);
    return v != null ? v : vanilla;
  }

  @ModifyReturnValue(method = "getBoundingBox", at = @At("RETURN"))
  private AABB eldencraft$pickBounds(AABB vanilla) {
    var v = HostPick.box((Entity) (Object) this);
    return v != null ? v : vanilla;
  }
}
