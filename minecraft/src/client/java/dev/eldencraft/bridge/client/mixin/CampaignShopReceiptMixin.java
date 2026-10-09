package dev.eldencraft.bridge.client.mixin;

import dev.eldencraft.bridge.client.CampaignShopReceipt;
import dev.eldencraft.bridge.client.WorldStartupSafety;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class CampaignShopReceiptMixin implements CampaignShopReceipt {
  @Unique private String eldencraft$shopReceipt = "";

  @Override
  public String eldencraft$shopReceipt() {
    return eldencraft$shopReceipt;
  }

  @Override
  public void eldencraft$shopReceipt(String id) {
    eldencraft$shopReceipt = id;
  }

  @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
  private void eldencraft$save(ValueOutput output, CallbackInfo ci) {
    if (!eldencraft$shopReceipt.isEmpty())
      output.putString("EldenCraftShopReceipt", eldencraft$shopReceipt);
  }

  @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
  private void eldencraft$load(ValueInput input, CallbackInfo ci) {
    eldencraft$shopReceipt = input.getStringOr("EldenCraftShopReceipt", "");
  }

  @Inject(method = "restoreFrom", at = @At("TAIL"))
  private void eldencraft$respawn(ServerPlayer previous, boolean keepInventory, CallbackInfo ci) {
    if (previous instanceof CampaignShopReceipt receipt)
      eldencraft$shopReceipt = receipt.eldencraft$shopReceipt();
    // Vanilla restoreFrom copies inventory and the ender chest, but not Entity's saved tags.
    // Keep pairing, boss receipts and loot cursors with their items across a fallback respawn.
    if (WorldStartupSafety.protects(previous)) {
      var player = (ServerPlayer) (Object) this;
      for (var tag : previous.entityTags()) if (tag.startsWith("eldencraft.")) player.addTag(tag);
    }
  }
}
