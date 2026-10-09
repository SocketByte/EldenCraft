package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.WorldDamageAuthority;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class WorldEnvironmentMixin {
  // noPhysics still prevents a second collision/movement producer. Vanilla
  // also uses it to skip fire/lava/block contact, which must remain active for
  // the paired server stand-in and live world enemy proxies.
  @Inject(method = "isAffectedByBlocks", at = @At("RETURN"), cancellable = true)
  private void eldencraft$hazards(CallbackInfoReturnable<Boolean> cir) {
    if (!cir.getReturnValueZ() && WorldDamageAuthority.environmentalEffects((Entity) (Object) this))
      cir.setReturnValue(true);
  }
}
