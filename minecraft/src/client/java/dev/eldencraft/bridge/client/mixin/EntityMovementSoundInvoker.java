package dev.eldencraft.bridge.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityMovementSoundInvoker {
  @Invoker("applyMovementEmissionAndPlaySound")
  void eldencraft$movementSound(
      Entity.MovementEmission emission, Vec3 displacement, BlockPos position, BlockState state);
}
