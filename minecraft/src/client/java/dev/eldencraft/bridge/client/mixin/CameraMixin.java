package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.FrameExporter;
import dev.eldencraft.bridge.client.HostController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional calibrated host camera, plus the actual guest projection metadata. */
@Mixin(Camera.class)
abstract class CameraMixin {
  @Shadow private float depthFar;
  @Shadow @Final private static Vector3fc FORWARDS;
  @Shadow @Final private static Vector3fc UP;
  @Shadow @Final private static Vector3fc LEFT;
  @Shadow @Final private Vector3f forwards;
  @Shadow @Final private Vector3f up;
  @Shadow @Final private Vector3f left;
  @Shadow @Final private Quaternionf rotation;
  @Shadow private float xRot;
  @Shadow private float yRot;
  @Shadow private boolean detached;
  @Shadow private int matrixPropertiesDirty;

  @Shadow
  protected abstract void setPosition(double x, double y, double z);

  @Inject(method = "update", at = @At("HEAD"))
  private void eldencraft$hostFrame(DeltaTracker tracker, CallbackInfo ci) {
    HostController.beginFrame();
  }

  @Inject(method = "alignWithEntity", at = @At("TAIL"))
  private void eldencraft$hostCamera(float partialTick, CallbackInfo ci) {
    var pose = HostController.frame();
    if (pose == null) return;
    float radians = (float) (Math.PI / 180.0);
    xRot = pose.pitch();
    yRot = pose.yaw();
    rotation.rotationYXZ((float) Math.PI - yRot * radians, -xRot * radians, 0);
    FORWARDS.rotate(rotation, forwards);
    UP.rotate(rotation, up);
    LEFT.rotate(rotation, left);
    matrixPropertiesDirty |= 3;
    setPosition(pose.x(), pose.y(), pose.z());
    detached = !pose.firstPerson();
  }

  @Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
  private void eldencraft$hostFov(float partialTick, CallbackInfoReturnable<Float> cir) {
    var pose = HostController.frame();
    if (pose != null) cir.setReturnValue(pose.fov());
  }

  @Inject(method = "update", at = @At("TAIL"))
  private void eldencraft$planes(DeltaTracker tracker, CallbackInfo ci) {
    FrameExporter.setFar(depthFar);
  }
}
