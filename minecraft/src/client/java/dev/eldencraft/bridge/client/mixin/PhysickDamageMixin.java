package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.PhysickTears;
import dev.eldencraft.bridge.client.CampaignPhysick;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;

/** Crimsonwhorl and the Opaline Bubbletear against Minecraft hazards and mobs. */
@Mixin(ServerPlayer.class)
abstract class PhysickDamageMixin {
  @WrapMethod(method = "hurtServer")
  private boolean eldencraft$physickDamage(
      ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
    var player = (ServerPlayer) (Object) this;
    if (CampaignPhysick.drains(player, source, amount)) return false;
    if (amount > 0 && CampaignPhysick.bubble(player)) {
      boolean accepted =
          original.call(level, source, amount * (float) PhysickTears.OPALINE_BUBBLE_KEEPS);
      if (accepted) CampaignPhysick.bubbleSpent(player);
      return accepted;
    }
    return original.call(level, source, amount);
  }
}
