package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Player.class)
public interface CampaignArmorInvoker {
  @Invoker("hurtArmor")
  void eldencraft$campaignArmorWear(DamageSource source, float damage);
}
