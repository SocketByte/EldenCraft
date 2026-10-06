package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.HostPick;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class HostPickEntityMixin {
  @Inject(
      method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$componentPickEye(CallbackInfoReturnable<Vec3> ci) {
    var v = HostPick.eye((Entity) (Object) this);
    if (v != null) ci.setReturnValue(v);
  }

  @Inject(
      method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$pickEye(float partial, CallbackInfoReturnable<Vec3> ci) {
    var v = HostPick.eye((Entity) (Object) this);
    if (v != null) ci.setReturnValue(v);
  }

  @Inject(method = "getViewVector", at = @At("HEAD"), cancellable = true)
  private void eldencraft$pickDirection(float partial, CallbackInfoReturnable<Vec3> ci) {
    var v = HostPick.forward((Entity) (Object) this);
    if (v != null) ci.setReturnValue(v);
  }

  @Inject(method = "getHeadLookAngle", at = @At("HEAD"), cancellable = true)
  private void eldencraft$componentPickDirection(CallbackInfoReturnable<Vec3> ci) {
    var v = HostPick.forward((Entity) (Object) this);
    if (v != null) ci.setReturnValue(v);
  }

  @Inject(method = "getBoundingBox", at = @At("HEAD"), cancellable = true)
  private void eldencraft$pickBounds(CallbackInfoReturnable<AABB> ci) {
    var v = HostPick.box((Entity) (Object) this);
    if (v != null) ci.setReturnValue(v);
  }
}
