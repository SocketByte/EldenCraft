package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.HostAvatarRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HorseRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
abstract class HostAvatarMixin {
  // createRenderState extracts the entity first, then finalizeRenderState
  // builds its shadow. Apply our copied host pose after both have completed.
  @Inject(
      method =
          "finalizeRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
      at = @At("TAIL"))
  private void eldencraft$alignAvatar(Entity entity, EntityRenderState state, CallbackInfo ci) {
    if (entity instanceof Avatar avatar && state instanceof AvatarRenderState rendered)
      HostAvatarRenderer.align(avatar, rendered);
    else if (state instanceof HorseRenderState torrent)
      HostAvatarRenderer.alignTorrent(entity, torrent);
  }
}
