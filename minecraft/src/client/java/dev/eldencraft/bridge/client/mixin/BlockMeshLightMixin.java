package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.BlockMeshClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class BlockMeshLightMixin {
  @Inject(method = "onLightUpdate", at = @At("HEAD"))
  private void eldencraft$propagatedLight(LightLayer layer, SectionPos section, CallbackInfo ci) {
    BlockMeshClient.lightDirty(section);
  }
}
