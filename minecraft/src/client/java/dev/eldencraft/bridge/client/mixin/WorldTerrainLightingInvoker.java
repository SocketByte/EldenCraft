package dev.eldencraft.bridge.client.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface WorldTerrainLightingInvoker {
  @Invoker("setChunkUnsaved")
  void eldencraft$setChunkUnsaved(ChunkPos pos);
}
