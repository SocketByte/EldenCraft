package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.SharedWorldClient;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
abstract class WorldPlayerPhysicsMixin {
  // Vanilla Player.tick overwrites this field with isSpectator() before
  // movement. Preserve only the active host-owned stand-in's collision flag.
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "FIELD",
              target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
              opcode = Opcodes.PUTFIELD))
  private void eldencraft$hostOwnsCollision(
      Player player, boolean value, Operation<Void> original) {
    original.call(player, value || SharedWorldClient.controlsPlayer(player));
  }
}
