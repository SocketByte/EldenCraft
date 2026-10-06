package dev.eldencraft.bridge.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
  @Accessor("xpos")
  void eldencraft$setX(double value);

  @Accessor("ypos")
  void eldencraft$setY(double value);
}
