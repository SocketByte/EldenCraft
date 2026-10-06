package dev.eldencraft.bridge.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
  @Accessor("clickCount")
  int eldencraft$getClickCount();

  @Accessor("clickCount")
  void eldencraft$setClickCount(int value);

  @Accessor("key")
  InputConstants.Key eldencraft$getKey();
}
