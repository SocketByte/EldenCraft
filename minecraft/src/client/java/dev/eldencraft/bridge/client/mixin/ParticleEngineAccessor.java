package dev.eldencraft.bridge.client.mixin;

import net.minecraft.client.particle.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
  @Accessor("resourceManager")
  ParticleResources eldencraft$resources();
}
