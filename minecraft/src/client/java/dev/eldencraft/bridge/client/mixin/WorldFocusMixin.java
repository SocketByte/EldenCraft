package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldFocus;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class WorldFocusMixin {
  @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
  private void eldencraft$keepBackgroundWorldRunning(boolean pauseOnly, CallbackInfo ci) {
    // Cancel the whole call, including vanilla's stopDestroyBlock side effect, so
    // repeated focus-loss checks cannot interrupt mining driven by the host window.
    if (WorldFocus.suppressPause((Minecraft) (Object) this)) ci.cancel();
  }
}
