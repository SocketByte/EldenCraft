package dev.eldencraft.bridge.client.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerGameMode.class)
public interface BlockWorkAccessor {
  @Accessor("isDestroyingBlock")
  boolean eldencraft$destroyingBlock();
}
