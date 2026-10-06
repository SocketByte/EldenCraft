package dev.eldencraft.bridge.client.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact pinned ToastInstance fields needed to free only a removed popup's occupied slots. */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public interface AchievementToastAccessor {
  @Accessor("toast")
  Toast eldencraft$toast();

  @Accessor("firstSlotIndex")
  int eldencraft$firstSlotIndex();

  @Accessor("occupiedSlotCount")
  int eldencraft$occupiedSlotCount();
}
