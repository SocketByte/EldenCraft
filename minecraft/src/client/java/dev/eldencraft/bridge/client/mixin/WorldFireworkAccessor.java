package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FireworkRocketEntity.class)
public interface WorldFireworkAccessor {
  @Accessor("attachedToEntity")
  LivingEntity eldencraft$attachedToEntity();
}
