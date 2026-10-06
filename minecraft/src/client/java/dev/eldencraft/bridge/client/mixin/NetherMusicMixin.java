package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.NetherEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class NetherMusicMixin {
  @Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
  private void eldencraft$netherMusic(CallbackInfoReturnable<Music> cir) {
    if (NetherEffects.musicActive()) cir.setReturnValue(NetherEffects.MUSIC);
  }
}
