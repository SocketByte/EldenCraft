package dev.eldencraft.bridge.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EldenCraftClient implements ClientModInitializer {
  private static final Logger LOG = LoggerFactory.getLogger("eldencraft_bridge");

  @Override
  public void onInitializeClient() {
    FrameExporter.enable();
    HostController.configure(
        FabricLoader.getInstance().getConfigDir().resolve("eldencraft-host.json"));
    CampaignClient.initialize();
    CampaignHud.initialize();
    ShieldIndicator.initialize();
    CampaignBossHud.initialize();
    SharedWorldClient.initialize();
    LabCommands.initialize();
    ProxyCombatClient.initialize();
    HealingClient.initialize();
    HostRuneHud.initialize();
    CampaignInteractions.initialize();
    ClientTickEvents.START_CLIENT_TICK.register(WorldFocus::tick);
    ClientTickEvents.START_CLIENT_TICK.register(GuestResolution::tick);
    ClientTickEvents.START_CLIENT_TICK.register(EldenCraftWorld::tick);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
        TerrainMining::tick);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
        WorldNether::serverTick);
    // Put every Nether block back before the save is written.
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
        WorldNether::serverStopping);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ALLOW_LOAD.register(
        WorldNether::allowLoad);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
        WorldNether::loaded);
    // Torrent is summoned, never saved: discard it before the save and refuse a stale copy.
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
        WorldTorrent::serverStopping);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ALLOW_LOAD.register(
        WorldTorrent::allowLoad);
    MaterialConfig.load();
    ClientTickEvents.START_CLIENT_TICK.register(CampaignInteractions::tick);
    ClientTickEvents.START_CLIENT_TICK.register(HostController::tick);
    ClientTickEvents.START_CLIENT_TICK.register(CampaignBridge::tick);
    ClientTickEvents.START_CLIENT_TICK.register(SharedWorldClient::tick);
    ClientTickEvents.START_CLIENT_TICK.register(HealingClient::tick);
    ClientTickEvents.START_CLIENT_TICK.register(HostDamageFeedback::tick);
    ClientTickEvents.START_CLIENT_TICK.register(ProxyCombatClient::tick);
    ClientTickEvents.END_CLIENT_TICK.register(HostController::observe);
    ClientTickEvents.END_CLIENT_TICK.register(HostFootsteps::tick);
    ClientTickEvents.END_CLIENT_TICK.register(CombatPublisher::publish);
    ClientTickEvents.END_CLIENT_TICK.register(NetherEffects::tick);
    ClientTickEvents.END_CLIENT_TICK.register(CampaignShops::clientTick);
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          FrameExporter.close();
          HostController.close();
          HostHealthDisplay.close();
          HostDamageFeedback.close();
          HostFootsteps.close();
          CombatPublisher.close();
          ProxyCombatClient.close();
          HealingClient.close();
          SharedWorldClient.close();
          NetherEffects.close();
          CampaignShops.close();
          CampaignInteractions.close();
          CampaignBridge.close();
        });
    LOG.info("EldenCraft shared-memory and GPU bridge ready for the offline singleplayer world.");
  }
}
