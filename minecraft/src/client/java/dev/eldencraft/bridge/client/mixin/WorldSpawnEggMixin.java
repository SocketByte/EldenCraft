package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.WorldMobSpawning;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SpawnEggItem.class)
abstract class WorldSpawnEggMixin {
  // Egg-on-adult uses getBreedOffspring (BREEDING), unlike ordinary egg placement.
  @WrapMethod(method = "spawnOffspringFromSpawnEgg")
  private static Optional<Mob> eldencraft$eggBaby(
      Player player,
      Mob parent,
      EntityType<? extends Mob> type,
      ServerLevel level,
      Vec3 pos,
      ItemStack stack,
      Operation<Optional<Mob>> original) {
    try (var scope = WorldMobSpawning.egg(level, type, stack)) {
      return original.call(player, parent, type, level, pos, stack);
    }
  }
}
