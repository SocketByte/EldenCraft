package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.WorldMobSpawning;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerLevel.class)
abstract class WorldSpawnLevelMixin {
  @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
  private void eldencraft$admitFreshMob(Entity entity, CallbackInfoReturnable<Boolean> cir) {
    if (!WorldMobSpawning.admit((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
  }

  @ModifyVariable(method = "addWorldGenChunkEntities", at = @At("HEAD"), argsOnly = true)
  private Stream<Entity> eldencraft$worldgenMobs(Stream<Entity> entities) {
    var level = (ServerLevel) (Object) this;
    return WorldMobSpawning.shared(level)
        ? entities.filter(entity -> WorldMobSpawning.admit(level, entity))
        : entities;
  }

  @Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
  private void eldencraft$noPatrolOrPhantomSpawners(boolean spawnEnemies, CallbackInfo ci) {
    if (WorldMobSpawning.suppressNaturalSpawns((ServerLevel) (Object) this)) ci.cancel();
  }
}
