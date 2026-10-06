package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.WorldFlight;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class WorldFlightMixin {
  // Actual26.3 private travelFallFlying invokes this after the genuine glide equation.
  // Only its position integration is delegated; gravity, equipment wear and rocket boost remain
  // vanilla.
  @WrapOperation(
      method = "travelFallFlying",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
  private void eldencraft$nativeCollision(
      LivingEntity self, MoverType type, Vec3 movement, Operation<Void> original) {
    if (!WorldFlight.delegatesPosition(self)) original.call(self, type, movement);
  }

  @Inject(method = "travelFallFlying", at = @At("RETURN"))
  private void eldencraft$computed(Vec3 input, CallbackInfo ci) {
    WorldFlight.computed((LivingEntity) (Object) this);
  }
}
