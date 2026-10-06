package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.WorldSpawnPermit;
import dev.eldencraft.bridge.client.WorldMobSpawning;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.*;

@Mixin(Mob.class)
abstract class WorldSpawnMobMixin implements WorldSpawnPermit {
  @Unique private boolean eldencraft$permitted;

  @Override
  public boolean eldencraft$spawnPermitted() {
    return eldencraft$permitted;
  }

  @Override
  public void eldencraft$spawnPermitted(boolean value) {
    eldencraft$permitted = value;
  }

  @WrapMethod(
      method =
          "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;")
  private Mob eldencraft$preserveReplacement(
      EntityType<?> type,
      ConversionParams params,
      EntitySpawnReason reason,
      ConversionParams.AfterConversion<?> after,
      Operation<Mob> original) {
    try (var scope = WorldMobSpawning.replacement((Mob) (Object) this, type, params, reason)) {
      return original.call(type, params, reason, after);
    }
  }
}
