package dev.eldencraft.bridge.client.mixin;

import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Projectile.class)
public interface WorldProjectileInvoker {
  // Native terrain is often air in the guest. The normal discovery method
  // rejects air before dispatching onHit; this call is only for an already
  // validated real native contact and retains virtual pearl/arrow behavior.
  @Invoker("onHit")
  void eldencraft$nativeImpact(HitResult hit);
}
