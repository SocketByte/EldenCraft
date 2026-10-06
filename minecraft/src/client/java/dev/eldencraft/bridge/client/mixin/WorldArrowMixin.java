package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.SharedWorldBlocks;
import dev.eldencraft.bridge.client.WorldProjectiles;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractArrow.class)
abstract class WorldArrowMixin {
  // Unlike its later ray clip, vanilla's initial in-ground probe has no entity
  // collision context. Without this narrow exemption it embeds tracked arrows
  // in invisible sampled terrain before the native flight ray can run.
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
  private VoxelShape eldencraft$realArrowGround(
      BlockState state, BlockGetter level, BlockPos position, Operation<VoxelShape> original) {
    return state.is(SharedWorldBlocks.TERRAIN)
            && WorldProjectiles.tracked((AbstractArrow) (Object) this)
        ? Shapes.empty()
        : original.call(state, level, position);
  }

  @WrapOperation(
      method = "onHitEntity",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
  private void eldencraft$confirmedHitSound(
      AbstractArrow arrow, SoundEvent sound, float volume, float pitch, Operation<Void> original) {
    if (!WorldProjectiles.captureHitSound(arrow, sound, volume, pitch))
      original.call(arrow, sound, volume, pitch);
  }
}
