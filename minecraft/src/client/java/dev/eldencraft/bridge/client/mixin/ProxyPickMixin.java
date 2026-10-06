package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.eldencraft.bridge.client.HostPick;
import dev.eldencraft.bridge.client.ProxyCombatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ProxyPickMixin {
  @WrapOperation(
      method = "pick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/player/LocalPlayer;raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;"))
  private HitResult eldencraft$cameraPick(
      LocalPlayer player, float partial, Entity camera, Operation<HitResult> original) {
    return HostPick.pick(camera, () -> original.call(player, partial, camera));
  }

  @Inject(method = "pick", at = @At("TAIL"))
  private void eldencraft$proxyPick(float partial, CallbackInfo ci) {
    ProxyCombatClient.pick((Minecraft) (Object) this);
  }
}
