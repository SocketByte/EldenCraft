package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.FlightPolicy;
import dev.eldencraft.bridge.SharedWorldBlocks;
import dev.eldencraft.bridge.client.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
abstract class WorldRocketSafetyMixin {
  @Shadow private int life;

  @Shadow
  private boolean isAttachedToEntity() {
    throw new AssertionError("shadow");
  }

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_flight");
  private static long report;

  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void eldencraft$boundedClientFlight(CallbackInfo ci) {
    var self = (FireworkRocketEntity) (Object) this;
    if (!self.level().dimension().equals(SharedWorldBlocks.DIMENSION)) return;
    var v = self.getDeltaMovement();
    // Before vanilla multiplies1.15XZ, raycasts or scans a swept block box.
    // Server expiry normally occurs in20..52ticks; paused server copies
    // previously left client life/velocity unbounded and hung the renderer.
    if (!FlightPolicy.rocketStep(life, v.x, v.y, v.z, isAttachedToEntity())) {
      self.discard();
      ci.cancel();
      long now = System.nanoTime();
      if (now - report > 5_000_000_000L) {
        report = now;
        LOG.warn(
            "Discarded desynchronized shared-world firework before collision: side={}, life={},"
                + " velocity={}",
            self.level().isClientSide() ? "client" : "server",
            life,
            v);
      }
    }
  }

  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
  private void eldencraft$ownedBoostOnly(
      LivingEntity target, Vec3 velocity, Operation<Void> original) {
    if (WorldFlight.allowRocketBoost(target)) original.call(target, velocity);
  }
}
