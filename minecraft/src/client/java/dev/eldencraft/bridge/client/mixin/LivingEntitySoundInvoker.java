package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes the vanilla sound-only route; it does not create a damage packet or damage event. */
@Mixin(LivingEntity.class)
public interface LivingEntitySoundInvoker {
  @Invoker("playHurtSound")
  void eldencraft$playHurtSound(DamageSource source);
}
