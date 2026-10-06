package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.AchievementToasts;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
abstract class AchievementToastMixin {
  @Shadow @Final private Minecraft minecraft;
  @Shadow @Final private Deque<Toast> queued;
  @Shadow @Final private List<?> visibleToasts;
  @Shadow @Final private BitSet occupiedSlots;

  @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
  private void eldencraft$quietProgressionToast(Toast toast, CallbackInfo ci) {
    // Cancelling enqueue also avoids challenge sounds and reserves no toast display slots.
    if (AchievementToasts.suppress(minecraft, toast)) {
      AchievementToasts.dismiss(toast);
      ci.cancel();
    }
  }

  @Inject(method = "update", at = @At("HEAD"))
  private void eldencraft$discardDeferredProgressionToasts(CallbackInfo ci) {
    // A packet may arrive before the dedicated level is attached. Never show it later on focus.
    eldencraft$discardScopedToasts();
  }

  @Inject(method = "extractRenderState", at = @At("HEAD"))
  private void eldencraft$removeAlreadyShownToasts(GuiGraphicsExtractor graphics, CallbackInfo ci) {
    // Rendering may continue while a paused GUI stops updates; the movement tutorial can be
    // visible indefinitely. Remove its actual instance rather than only refusing new entries.
    eldencraft$discardScopedToasts();
  }

  @Unique
  private void eldencraft$discardScopedToasts() {
    AchievementToasts.discardQueued(minecraft, queued);
    AchievementToasts.discardVisible(minecraft, visibleToasts, occupiedSlots);
  }
}
