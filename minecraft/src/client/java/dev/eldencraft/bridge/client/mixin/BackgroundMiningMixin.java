package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.bridge.client.HostController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class BackgroundMiningMixin {
  // A background guest cannot grab the OS mouse. Only this held-mining gate accepts
  // host ownership; continueAttack still handles target, reach, GUI, tool and item use.
  @WrapOperation(
      method = "handleKeybinds",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
  private boolean eldencraft$hostHeldMining(MouseHandler mouse, Operation<Boolean> original) {
    return original.call(mouse) || HostController.ownsHeldAttack((Minecraft) (Object) this);
  }
}
