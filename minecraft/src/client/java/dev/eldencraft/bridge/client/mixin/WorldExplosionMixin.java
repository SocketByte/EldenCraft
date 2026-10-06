package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.WorldDamageAuthority;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerExplosion.class)
abstract class WorldExplosionMixin {
  @WrapMethod(method = "hurtEntities")
  private void eldencraft$actualExplosion(Operation<Void> original) {
    var prior = WorldDamageAuthority.beginExplosion((Explosion) (Object) this);
    try {
      original.call();
    } finally {
      WorldDamageAuthority.endExplosion(prior);
    }
  }
}
