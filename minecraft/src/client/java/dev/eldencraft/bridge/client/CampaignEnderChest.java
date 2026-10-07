package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.InteractionContainerLease;
import dev.eldencraft.bridge.InteractionProtocol;
import dev.eldencraft.bridge.SharedWorldBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;

/** Opens the paired player's actual Ender Chest through vanilla's server menu protocol. */
final class CampaignEnderChest {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_interaction");
  private static volatile Request request;

  private static final class Request {
    final InteractionContainerLease owner;
    final MinecraftServer server;
    final Object clientPlayer, clientWorld;
    final String character;
    final long campaignSession, map, deadline;
    final int choice;
    volatile InteractionContainerLease opened;
    volatile ChestMenu serverMenu;
    volatile boolean cancelled;
    volatile String failure = "";
    AbstractContainerMenu clientMenu;
    Screen clientScreen;

    Request(
        Minecraft client,
        InteractionProtocol.Snapshot snapshot,
        CampaignBridge.Identity campaign,
        HostState.Snapshot host,
        int choice) {
      owner =
          new InteractionContainerLease(
              snapshot.pid(),
              snapshot.session(),
              snapshot.menu().token(),
              client.player.getUUID(),
              -1);
      server = client.getSingleplayerServer();
      clientPlayer = client.player;
      clientWorld = client.level;
      character = campaign.character();
      campaignSession = campaign.session();
      map = host.mapId();
      deadline = System.nanoTime() + 2_000_000_000L;
      this.choice = choice;
    }
  }

  private CampaignEnderChest() {}

  static boolean open(Minecraft client, InteractionProtocol.Snapshot snapshot, int choice) {
    var campaign = CampaignBridge.graceIdentity(snapshot);
    var host = HostController.healthSnapshot(client);
    var server = client.getSingleplayerServer();
    if (request != null
        || snapshot == null
        || snapshot.menu() == null
        || campaign == null
        || host == null
        || campaign.pid() != snapshot.pid()
        || host.publisherPid() != snapshot.pid()
        || server == null
        || client.player == null
        || client.level == null
        || !(client.gui.screen() instanceof InteractionScreen screen)
        || screen.token() != snapshot.menu().token()
        || screen.session() != snapshot.session()) return false;
    var owner =
        new InteractionContainerLease(
            snapshot.pid(),
            snapshot.session(),
            snapshot.menu().token(),
            client.player.getUUID(),
            -1);
    if (!owner.canOpen(
        snapshot,
        client.player.getUUID(),
        choice,
        new InteractionContainerLease.OpenContext(
            !server.isPublished() && server.isSingleplayer(),
            EldenCraftWorld.supportedName(server.getWorldData().getLevelName()),
            SharedWorldClient.inSharedDimension(),
            client.player.isAlive() && !client.player.isSpectator(),
            // Character pairing tags live in the authoritative server player save.
            // The queued server operation checks them before opening any inventory.
            true,
            client.player.containerMenu == client.player.inventoryMenu),
        System.currentTimeMillis())) return false;
    var pending = new Request(client, snapshot, campaign, host, choice);
    request = pending;
    server.execute(() -> openOnServer(pending));
    return true;
  }

  private static boolean campaignMatches(Request pending, InteractionProtocol.Snapshot snapshot) {
    var campaign = CampaignBridge.graceIdentity(snapshot);
    return campaign != null
        && campaign.pid() == pending.owner.pid()
        && campaign.session() == pending.campaignSession
        && campaign.character().equals(pending.character);
  }

  private static void openOnServer(Request pending) {
    var player = pending.server.getPlayerList().getPlayer(pending.owner.player());
    var snapshot = CampaignInteractions.snapshot();
    var host = HostController.healthSnapshot(Minecraft.getInstance());
    if (pending.cancelled
        || request != pending
        || player == null
        || host == null
        || host.publisherPid() != pending.owner.pid()
        || host.mapId() != pending.map
        || !campaignMatches(pending, snapshot)
        || !pending.owner.canOpen(
            snapshot,
            player.getUUID(),
            pending.choice,
            new InteractionContainerLease.OpenContext(
                pending.server.isSingleplayer() && !pending.server.isPublished(),
                EldenCraftWorld.supportedName(pending.server.getWorldData().getLevelName()),
                player.level().dimension().equals(SharedWorldBlocks.DIMENSION),
                player.isAlive()
                    && !player.isRemoved()
                    && player.gameMode.isSurvival()
                    && !player.isSpectator(),
                CampaignProgression.paired(player, pending.character),
                player.containerMenu == player.inventoryMenu
                    && pending.server.getPlayerCount() == 1),
            System.currentTimeMillis())) {
      pending.failure = "The Ender Chest is no longer available.";
      return;
    }
    try {
      var inventory = player.getEnderChestInventory();
      inventory.setActiveChest(null);
      var opened =
          player.openMenu(
              new SimpleMenuProvider(
                  (syncId, playerInventory, ignored) ->
                      ChestMenu.threeRows(syncId, playerInventory, inventory),
                  Component.translatable("container.enderchest")));
      if (opened.isEmpty()
          || !(player.containerMenu instanceof ChestMenu chest)
          || chest.containerId != opened.getAsInt()
          || chest.getContainer() != inventory) {
        pending.failure = "The Ender Chest could not be opened.";
        return;
      }
      pending.serverMenu = chest;
      pending.opened = pending.owner.opened(opened.getAsInt());
      if (pending.cancelled || request != pending) closeOnServer(pending);
    } catch (RuntimeException error) {
      pending.failure = "The Ender Chest could not be opened.";
      LOG.warn("Vanilla Ender Chest opening failed: {}", error.toString());
    }
  }

  static boolean owned(Minecraft client) {
    var pending = request;
    return pending != null
        && !pending.cancelled
        && pending.clientMenu != null
        && client.player == pending.clientPlayer
        && client.level == pending.clientWorld
        && client.player.containerMenu == pending.clientMenu
        && client.gui.screen() == pending.clientScreen;
  }

  static boolean pending() {
    return request != null;
  }

  static void tick(Minecraft client, InteractionProtocol.Snapshot snapshot) {
    var pending = request;
    if (pending == null) return;
    var host = HostController.healthSnapshot(client);
    boolean valid =
        client.player == pending.clientPlayer
            && client.level == pending.clientWorld
            && client.getSingleplayerServer() == pending.server
            && !pending.server.isPublished()
            && host != null
            && host.publisherPid() == pending.owner.pid()
            && host.mapId() == pending.map
            && client.player != null
            && client.player.isAlive()
            && !client.player.isSpectator()
            && SharedWorldClient.inSharedDimension()
            && campaignMatches(pending, snapshot)
            && pending.owner.current(
                snapshot, client.player.getUUID(), pending.choice, System.currentTimeMillis());
    if (!valid || !pending.failure.isBlank()) {
      cancel(client, pending, pending.failure);
      return;
    }
    if (pending.opened != null
        && client.player.containerMenu != client.player.inventoryMenu
        && pending.opened.owns(client.player.getUUID(), client.player.containerMenu.containerId)
        && client.player.containerMenu instanceof ChestMenu chest
        && chest.getRowCount() == 3
        && client.gui.screen() instanceof AbstractContainerScreen<?> screen
        && screen.getMenu() == chest) {
      if (pending.clientMenu == null) {
        pending.clientMenu = chest;
        pending.clientScreen = screen;
      } else if (pending.clientMenu != chest || pending.clientScreen != screen)
        cancel(client, pending, "");
      return;
    }
    if (pending.clientMenu != null) {
      // Vanilla Escape closes the real container; the unchanged native grace rows reopen next tick.
      cancel(client, pending, "");
    } else if (System.nanoTime() >= pending.deadline) {
      cancel(client, pending, "The Ender Chest did not open. Try again.");
    } else if (client.gui.screen() != null && !(client.gui.screen() instanceof InteractionScreen)) {
      // An unrelated vanilla screen/menu took over before our open packet arrived.
      cancel(client, pending, "");
    }
  }

  private static void cancel(Minecraft client, Request pending, String failure) {
    pending.cancelled = true;
    if (request == pending) request = null;
    if (client.player == pending.clientPlayer
        && client.level == pending.clientWorld
        && pending.opened != null
        && pending.clientMenu != null
        && client.player.containerMenu == pending.clientMenu
        && pending.opened.owns(client.player.getUUID(), pending.clientMenu.containerId)) {
      Screen keep = client.gui.screen() == pending.clientScreen ? null : client.gui.screen();
      client.player.closeContainer();
      if (keep != null) client.gui.setScreen(keep);
    }
    pending.server.execute(() -> closeOnServer(pending));
    if (!failure.isBlank()
        && client.gui.screen() instanceof InteractionScreen screen
        && screen.token() == pending.owner.token()
        && screen.session() == pending.owner.session()) screen.containerOpenFailed(failure);
  }

  private static void closeOnServer(Request pending) {
    ServerPlayer player = pending.server.getPlayerList().getPlayer(pending.owner.player());
    if (player != null
        && pending.opened != null
        && pending.serverMenu != null
        && player.containerMenu == pending.serverMenu
        && pending.opened.owns(player.getUUID(), pending.serverMenu.containerId))
      player.closeContainer();
  }

  static void close() {
    var pending = request;
    if (pending != null) cancel(Minecraft.getInstance(), pending, "");
  }
}
