package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.ProxyFeedback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirect only a scoped replay of an actual combat packet/emitter into its vanilla overlay
 * particle engine.
 */
@Mixin(ClientLevel.class)
abstract class ProxyParticleMixin {
  @Inject(
      method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$emitter(
      ParticleOptions type,
      double x,
      double y,
      double z,
      double dx,
      double dy,
      double dz,
      CallbackInfo ci) {
    if (ProxyFeedback.route(type, x, y, z, dx, dy, dz)) ci.cancel();
  }

  @Inject(
      method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
      at = @At("HEAD"),
      cancellable = true)
  private void eldencraft$packet(
      ParticleOptions type,
      boolean override,
      boolean always,
      double x,
      double y,
      double z,
      double dx,
      double dy,
      double dz,
      CallbackInfo ci) {
    if (ProxyFeedback.route(type, x, y, z, dx, dy, dz)) ci.cancel();
  }
}
