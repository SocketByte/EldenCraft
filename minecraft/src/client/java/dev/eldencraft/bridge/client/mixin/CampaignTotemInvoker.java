package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface CampaignTotemInvoker {
  @Invoker("checkTotemDeathProtection")
  boolean eldencraft$checkTotemDeathProtection(DamageSource source);
}
