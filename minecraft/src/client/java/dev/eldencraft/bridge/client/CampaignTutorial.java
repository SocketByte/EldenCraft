package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** One welcome guide per native save load, independent of respawns and brief bridge gaps. */
final class CampaignTutorial {
  private record LoadedSave(long pid, long session, String character, long load) {
    static LoadedSave of(CampaignBridge.Snapshot host) {
      return new LoadedSave(host.pid(), host.session(), host.character(), host.saveLoad());
    }
  }

  private static LoadedSave shown;
  private static Object loadedServer;
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
    if (server != loadedServer) {
      // Reopening the Minecraft save starts another guide. Reconnecting to
      // the same integrated server or replacing the player after death does not.
      loadedServer = server;
      shown = null;
    }
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
    var save = LoadedSave.of(host);
    if (save.equals(shown)) return;
    if (client.gui.screen() instanceof CampaignTutorialScreen) client.gui.setScreen(null);
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
    shown = save;
    client.gui.setScreen(new CampaignTutorialScreen());
  }
}
