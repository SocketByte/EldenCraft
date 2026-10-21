package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * One welcome guide per save: the paired Minecraft world records it on its player, so reloading,
 * respawns, reconnects and bridge gaps never repeat it.
 */
final class CampaignTutorial {
  private static boolean initialized;

  private CampaignTutorial() {}

  static void initialize() {
    if (initialized) return;
    initialized = true;
    ClientTickEvents.END_CLIENT_TICK.register(CampaignTutorial::tick);
  }

  private static void tick(Minecraft client) {
    var server = client.getSingleplayerServer();
    if (!CampaignConfig.current().enabled()
        || client.level == null
        || client.player == null
        || server == null
        || server.isPublished()
        || !SharedWorldClient.inSharedDimension()) return;
    var host = CampaignBridge.snapshot();
    if (host == null) {
      if (CampaignBridge.deathObserved() != null
          && client.gui.screen() instanceof CampaignTutorialScreen) client.gui.setScreen(null);
      return;
    }
    if (!host.active()
        || host.dead()
        || !host.identityReady()
        || !CampaignProgression.characterPermitted(client.player)) return;
    if (!CampaignProgression.guideUnseen()) return;
    // Let native menus, loading screens and boss encounters finish before
    // opening a guide. Dismissal survives focus loss, rest, death and reconnects.
    var interaction = CampaignInteractions.snapshot();
    if (client.gui.screen() != null
        || client.gui.overlay() != null
        || interaction == null
        || interaction.blocking()
        || interaction.menu() != null
        || CampaignEnderChest.pending()
        || host.merchant() != null
        || !host.activeBosses().isEmpty()) return;
    CampaignProgression.guideShown();
    client.gui.setScreen(new CampaignTutorialScreen());
  }
}
