package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.WorldFlight;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class WorldFlightMixin {
  @Inject(method = "onClimbable", at = @At("RETURN"), cancellable = true)
  private void eldencraft$ladderContact(CallbackInfoReturnable<Boolean> cir) {
    if (!cir.getReturnValueZ() && WorldFlight.climbContact((LivingEntity) (Object) this))
      cir.setReturnValue(true);
  }

  @Inject(method = "handleRelativeFrictionAndCalculateMovement", at = @At("RETURN"))
  private void eldencraft$climbImpulse(
      Vec3 input, float friction, CallbackInfoReturnable<Vec3> cir) {
    WorldFlight.climbRequested((LivingEntity) (Object) this, cir.getReturnValue());
  }

  // The pinned vanilla travel methods integrate position at these calls.
  // Defer only the paired stand-in's move; fluid/climb equations, glide physics,
  // equipment wear and attached rocket boost remain vanilla.
  @WrapOperation(
      method = {
        "travelFallFlying",
        "travelInWater",
        "travelInLava",
        "handleRelativeFrictionAndCalculateMovement"
      },
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
  private void eldencraft$nativeCollision(
      LivingEntity self, MoverType type, Vec3 movement, Operation<Void> original) {
    if (!WorldFlight.delegatesPosition(self)) original.call(self, type, movement);
    else WorldFlight.requested(self, movement);
  }

  @Inject(method = "travel", at = @At("RETURN"))
  private void eldencraft$computed(Vec3 input, CallbackInfo ci) {
    WorldFlight.computed((LivingEntity) (Object) this);
  }

  @Inject(
      method = "aiStep",
      at =
          @At(
              value = "FIELD",
              target = "Lnet/minecraft/world/entity/LivingEntity;jumping:Z",
              opcode = Opcodes.GETFIELD,
              ordinal = 0))
  private void eldencraft$travelControls(CallbackInfo ci) {
    WorldFlight.prepareTravel((LivingEntity) (Object) this);
  }
}
