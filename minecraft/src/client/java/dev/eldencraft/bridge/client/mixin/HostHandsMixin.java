package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.HostHands;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItems.class)
abstract class HostHandsMixin {
  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void eldencraft$continuousHands(
      LocalPlayer player,
      float partial,
      FirstPersonHandsAndItemsRenderState state,
      CallbackInfo ci) {
    HostHands.extract(player, state);
  }
}
