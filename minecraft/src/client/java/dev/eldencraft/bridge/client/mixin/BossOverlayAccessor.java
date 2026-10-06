package dev.eldencraft.bridge.client.mixin;

import java.util.Map;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BossHealthOverlay.class)
public interface BossOverlayAccessor {
  @Accessor("events")
  Map<?, ?> eldencraft$events();
}
