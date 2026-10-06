package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.WorldMobSpawning;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
abstract class WorldSpawnTypeMixin {
  @Inject(
      method =
          "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
      at = @At("RETURN"),
      cancellable = true)
  private void eldencraft$createdMob(
      Level level, EntitySpawnRequest request, CallbackInfoReturnable<Entity> cir) {
    if (!WorldMobSpawning.created(level, request.reason(), cir.getReturnValue()))
      cir.setReturnValue(null);
  }

  // This actual overload is used by both normal eggs and dispenser egg behavior.
  @WrapMethod(
      method =
          "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;")
  private Entity eldencraft$eggUse(
      ServerLevel level,
      ItemStack stack,
      LivingEntity owner,
      BlockPos pos,
      EntitySpawnReason reason,
      boolean offset,
      boolean invert,
      Operation<Entity> original) {
    try (var scope = WorldMobSpawning.egg(level, (EntityType<?>) (Object) this, stack)) {
      return original.call(level, stack, owner, pos, reason, offset, invert);
    }
  }
}
