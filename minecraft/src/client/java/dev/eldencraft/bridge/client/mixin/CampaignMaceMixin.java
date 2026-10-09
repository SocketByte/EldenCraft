package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.CampaignMotion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Player.class)
abstract class CampaignMaceMixin {
  @WrapMethod(method = "attack")
  private void eldencraft$observedFall(Entity target, Operation<Void> original) {
    if ((Object) this instanceof ServerPlayer player)
      CampaignMotion.maceAttack(player, () -> original.call(target));
    else original.call(target);
  }
}
