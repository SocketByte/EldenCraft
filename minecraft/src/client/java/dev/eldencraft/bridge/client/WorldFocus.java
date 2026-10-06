package dev.eldencraft.bridge.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/** Keeps the dedicated offline world running when its window hands focus to Elden Ring. */
public final class WorldFocus {
  record Session(
      boolean loaded, boolean offline, boolean focused, String name, boolean sharedDimension) {
    boolean hostWorld() {
      return loaded && offline && (EldenCraftWorld.supportedName(name) || sharedDimension);
    }

    boolean background() {
      return hostWorld() && !focused;
    }
  }

  record HostPause(PauseScreen screen, Object player, Object world, long pid, long map) {
    boolean matches(
        Session session, Object currentPlayer, Object currentWorld, long hostPid, long hostMap) {
      return session.hostWorld()
          && screen != null
          && player != null
          && world != null
          && player == currentPlayer
          && world == currentWorld
          && pid > 0
          && pid == hostPid
          && map == hostMap;
    }
  }

  private static HostPause hostPause;

  private static Session session(Minecraft client) {
    var server = client.getSingleplayerServer();
    return new Session(
        client.level != null && client.player != null && server != null,
        server != null && !server.isPublished(),
        client.isWindowActive(),
        server == null ? null : server.getWorldData().getLevelName(),
        SharedWorldClient.inSharedDimension());
  }

  public static boolean suppressPause(Minecraft client) {
    return session(client).background();
  }

  static void resumeBackground(Session session, Screen screen, Consumer<Screen> setScreen) {
    resumeBackground(session, screen, false, setScreen);
  }

  static void resumeBackground(
      Session session, Screen screen, boolean hostOwned, Consumer<Screen> setScreen) {
    if (session.background() && screen instanceof PauseScreen && !hostOwned) setScreen.accept(null);
  }

  /** The ordinary vanilla pause menu, explicitly opened through fresh imported Escape. */
  static void openHostPause(Minecraft client, HostState.Snapshot host) {
    var current = session(client);
    if (!current.hostWorld() || host == null || !host.active() || client.gui.screen() != null)
      return;
    // pauseGame is intentionally suppressed for automatic background focus loss.
    // Use its actual GUI entry point and retain its stop-mining behavior here.
    client.gui.setPauseScreen(false, true);
    if (client.gameMode != null) client.gameMode.stopDestroyBlock();
    if (client.gui.screen() instanceof PauseScreen screen)
      hostPause =
          new HostPause(screen, client.player, client.level, host.publisherPid(), host.mapId());
  }

  public static void tick(Minecraft client) {
    // Independent of the host's input lease: a stale or blocked host must not leave its
    // exported view stuck behind Minecraft's pause screen. Inventory and chat stay open.
    var current = session(client);
    var screen = client.gui.screen();
    if (hostPause != null) {
      // The 250 ms foreground lease also expires while options are open, so
      // returning from a submenu cannot revive an old publisher's pause menu.
      var host = HostController.damageSnapshot(client);
      if (screen == null
          || host == null
          || !hostPause.matches(
              current, client.player, client.level, host.publisherPid(), host.mapId()))
        hostPause = null;
    }
    resumeBackground(
        current, screen, hostPause != null && screen == hostPause.screen(), client.gui::setScreen);
  }

  private WorldFocus() {}
}
