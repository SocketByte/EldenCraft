package dev.eldencraft.bridge.client;

/** Saved alongside vanilla player inventory, so interrupted grants can be reconciled. */
public interface CampaignShopReceipt {
  String eldencraft$shopReceipt();

  void eldencraft$shopReceipt(String id);
}
