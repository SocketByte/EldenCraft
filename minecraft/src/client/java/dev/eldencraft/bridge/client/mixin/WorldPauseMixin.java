package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.WorldDamageAuthority;
import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class WorldPauseMixin {
  @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
  private void eldencraft$pauseSceneEntity(Entity entity, CallbackInfo ci) {
    if (WorldDamageAuthority.pause(entity)) ci.cancel();
  }

  @Inject(method = "tickNonPassenger", at = @At("RETURN"))
  private void eldencraft$sampleFlight(Entity entity, CallbackInfo ci) {
    if (entity instanceof Projectile p) WorldProjectiles.sample(p);
  }
}
