package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class CampaignAttackMixin {
  @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
  private void eldencraft$staminaAttack(Entity target, CallbackInfo ci) {
    var player = (Player) (Object) this;
    if (!CampaignCombat.attack(player)) ci.cancel();
  }
}
