package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.*;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/**
 * Minecraft's 1 m grid never matches Elden Ring's ground. Vanilla puts a block on top of the
 * clicked surface cell, floating up to a block above the visible ground. When the sampled surface
 * lies in the lower 70% of its cell, the block takes that cell instead and sinks into the ground
 * (NetherRules.SINK), so it is never more than 0.3 m proud of it. A hidden-terrain cell with no
 * fresh surface is empty space for placement too.
 */
@Mixin(BlockPlaceContext.class)
abstract class TerrainPlacementMixin {
  @Shadow protected boolean replaceClicked;

  @Inject(
      method =
          "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V",
      at = @At("TAIL"))
  private void eldencraft$sinkIntoGround(
      Level level,
      Player player,
      InteractionHand hand,
      ItemStack stack,
      BlockHitResult hit,
      CallbackInfo ci) {
    if (replaceClicked || level == null || hit.getDirection() != Direction.UP) return;
    var pos = hit.getBlockPos();
    var state = level.getBlockState(pos);
    if (!state.is(SharedWorldBlocks.TERRAIN) || ShadowTerrainBlock.boundary(state)) return;
    var shape = SharedTerrain.outline(pos);
    if (!shape.isEmpty() && shape.max(Direction.Axis.Y) < NetherRules.SINK) replaceClicked = true;
  }

  @Inject(method = "canPlace", at = @At("RETURN"), cancellable = true)
  private void eldencraft$emptyTerrain(CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ()) return;
    var context = (BlockPlaceContext) (Object) this;
    var pos = context.getClickedPos();
    if (context.getLevel().getBlockState(pos).is(SharedWorldBlocks.TERRAIN)
        && SharedTerrain.outline(pos).isEmpty()) cir.setReturnValue(true);
  }
}
