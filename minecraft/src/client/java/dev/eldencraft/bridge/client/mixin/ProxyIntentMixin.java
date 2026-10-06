package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.ProxyCombatClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs before the normal attack packet is sent, not after the server could already process it. */
@Mixin(MultiPlayerGameMode.class)
abstract class ProxyIntentMixin {
  @Inject(method = "attack", at = @At("HEAD"))
  private void eldencraft$rememberPick(Player player, Entity target, CallbackInfo ci) {
    ProxyCombatClient.rememberAttack(target);
  }
}
