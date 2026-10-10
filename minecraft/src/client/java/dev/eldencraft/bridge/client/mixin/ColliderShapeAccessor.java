package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockBehaviour.class)
public interface ColliderShapeAccessor {
  @Accessor("dynamicShape")
  boolean eldencraft$dynamicShape();
}
