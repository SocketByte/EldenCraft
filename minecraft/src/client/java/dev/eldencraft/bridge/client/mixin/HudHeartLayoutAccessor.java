package dev.eldencraft.bridge.client.mixin;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudHeartLayoutAccessor {
  @Accessor("displayHealth")
  int eldencraft$displayHealth();
}
