package dev.eldencraft.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.eldencraft.bridge.client.WorldPotions;
import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AreaEffectCloud.class)
abstract class WorldPotionCloudMixin {
  @WrapMethod(method = "tick")
  private void eldencraft$actualCloudTick(Operation<Void> original) {
    WorldPotions.tick((AreaEffectCloud) (Object) this, () -> original.call());
  }
}
