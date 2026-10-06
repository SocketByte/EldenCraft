package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.InteractionFeedHealth;
import dev.eldencraft.bridge.InteractionProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Fresh native interaction observations, replacement screens and game-thread command requests. */
public final class CampaignInteractions {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_interaction");
  static final int CONFIRM = 1,
      CANCEL = 2,
      UP = 4,
      DOWN = 8,
      LEFT = 16,
      RIGHT = 32,
      MAP = 64,
      TAB = 128,
      ZOOM_IN = 256,
      ZOOM_OUT = 512,
      SHIFT = 1024,
      HOME = 2048;
  private static volatile InteractionProtocol.Snapshot observed;
  private static long nextRead,
      nextHeartbeat,
      nextMapSample,
      inputSequence,
      inputSession,
      inputPid,
      requestSequence;
  private static String lastError = "";
  private static long dismissedToken, feedLostAt;
  private static final long MENU_HOLD_NANOS = 3_000_000_000L;
  private static boolean hadMap, closePending;
  private static int inputButtons;
  private static long inputContext;
  private static final InteractionFeedHealth FEED_HEALTH = new InteractionFeedHealth();
  private static String feedReason = "native snapshot missing";

  private CampaignInteractions() {}

  public static void initialize() {
    HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath("eldencraft_bridge", "interactions"),
        InteractionHud::extract);
  }

  public static InteractionProtocol.Snapshot snapshot() {
    var s = observed;
    return s != null && InteractionProtocol.fresh(s, System.currentTimeMillis(), s.pid())
        ? s
        : null;
  }

  static boolean owned(Minecraft client) {
    return client.gui.screen() instanceof InteractionScreen
        || client.gui.screen() instanceof CampaignMapScreen
        || CampaignEnderChest.owned(client);
  }

  /** Custom screens handle extended Cancel themselves; vanilla containers retain ECHS Escape. */
  static boolean consumesEscape(net.minecraft.client.gui.screens.Screen screen) {
    return screen instanceof InteractionScreen || screen instanceof CampaignMapScreen;
  }

  public static boolean ownsInput() {
    var s = snapshot();
    return s != null && (s.blocking() || s.menu() != null || owned(Minecraft.getInstance()));
  }

  public static void tick(Minecraft client) {
    long now = System.nanoTime();
    var host = HostController.healthSnapshot(client);
    boolean ready =
        host != null
            && client.player != null
            && client.level != null
            && client.getSingleplayerServer() != null
            && SharedWorldClient.inSharedDimension();
    if (now >= nextRead) {
      nextRead = now + 50_000_000L;
      try {
        Path file = CampaignBridge.directory().resolve("interaction-host.json");
        if (!ready || !Files.isRegularFile(file)) {
          observed = null;
          feedReason = "native snapshot missing";
        } else {
          byte[] bytes;
          try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(InteractionProtocol.MAX_BYTES + 1);
          }
          var value = InteractionProtocol.decode(bytes);
          if (!InteractionProtocol.fresh(value, System.currentTimeMillis(), host.publisherPid())) {
            observed = null;
            feedReason = "native snapshot stale, inactive or from another publisher";
          } else {
            if (observed != null
                && observed.pid() == value.pid()
                && observed.session() == value.session()
                && value.sequence() < observed.sequence())
              throw new IOException("Interaction sequence regressed");
            observed = value;
          }
        }
        lastError = "";
      } catch (IOException | IllegalArgumentException error) {
        observed = null;
        feedReason = error.getMessage();
        if (!lastError.equals(error.getMessage())) {
          lastError = error.getMessage();
          LOG.warn("Interaction snapshot unavailable: {}", lastError);
        }
      }
      var change =
          FEED_HEALTH.observe(
              host == null ? 0 : host.publisherPid(), ready, snapshot() != null, now);
      if (change == InteractionFeedHealth.Change.UNAVAILABLE)
        LOG.warn(
            "Native interaction feed unavailable ({}): {}",
            feedReason,
            CampaignBridge.directory().resolve("interaction-host.json"));
      else if (change == InteractionFeedHealth.Change.RESTORED)
        LOG.info(
            "Native interaction feed restored (publisher {}, session {}).",
            observed.pid(),
            observed.session());
    }
    var s = snapshot();
    CampaignEnderChest.tick(client, s);
    if (s == null) {
      if (feedLostAt == 0) feedLostAt = now;
      // Native holds an open menu through a short lease gap; keep its screen
      // instead of flickering it closed and reopening it with the same token.
      if (client.gui.screen() instanceof InteractionScreen screen
          && now - feedLostAt < MENU_HOLD_NANOS) {
        screen.interrupted(true);
        return;
      }
      if (owned(client)) client.gui.setScreen(null);
      dismissedToken = inputSequence = inputSession = inputPid = inputContext = 0;
      inputButtons = 0;
      hadMap = false;
    } else {
      feedLostAt = 0;
      if (client.gui.screen() instanceof InteractionScreen screen) screen.interrupted(false);
      if (closePending) {
        closePending = false;
        if (s.menu() != null && s.menu().token() == dismissedToken)
          write("interaction-guest.json", envelope(s, dismissedToken, "close"));
      }
      if (now >= nextMapSample) {
        nextMapSample = now + 500_000_000L;
        CampaignMapScreen.observeTerrain(s, host);
      }
      var menu = s.menu();
      if (client.gui.screen() instanceof InteractionScreen screen) {
        if (menu == null || screen.session() != s.session() || screen.token() != menu.token())
          client.gui.setScreen(null);
        else screen.update(menu);
      }
      if (menu == null) dismissedToken = 0;
      else if (client.gui.screen() == null
          && menu.token() != dismissedToken
          && !CampaignEnderChest.pending())
        client.gui.setScreen(new InteractionScreen(s.session(), menu));
      if (s.mapOpen() && !hadMap && client.gui.screen() == null)
        client.gui.setScreen(new CampaignMapScreen(s));
      hadMap = s.mapOpen();
      routeMenuInput(client, s);
    }
    if (now >= nextHeartbeat) {
      nextHeartbeat = now + 100_000_000L;
      if (s != null) {
        var state = envelope(s, 0, "ui_state");
        state.addProperty("open", owned(client));
        state.addProperty("ready", ready);
        write("interaction-ui.json", state);
      }
    }
  }

  private static void routeMenuInput(Minecraft client, InteractionProtocol.Snapshot s) {
    var input = s.input();
    if (input == null || input.sequence() == 0) return;
    long context =
        client.gui.screen() instanceof InteractionScreen screen
            ? screen.token()
            : client.gui.screen() instanceof CampaignMapScreen ? -1 : 0;
    if (inputPid != s.pid() || inputSession != s.session() || inputContext != context) {
      inputPid = s.pid();
      inputSession = s.session();
      inputContext = context;
      inputSequence = input.sequence();
      inputButtons = input.buttons();
      return;
    }
    if (input.sequence() <= inputSequence) return;
    inputSequence = input.sequence();
    int pressed = input.pressed() | (input.buttons() & ~inputButtons);
    inputButtons = input.buttons();
    if ((pressed & MAP) != 0 && (s.menu() == null || s.menu().kind().equals("grace"))) {
      if (client.gui.screen() instanceof CampaignMapScreen screen) screen.onClose();
      else if (client.gui.screen() == null || client.gui.screen() instanceof InteractionScreen)
        openMap();
      inputContext = client.gui.screen() instanceof CampaignMapScreen ? -1 : 0;
      return;
    }
    if ((pressed & CANCEL) != 0 && consumesEscape(client.gui.screen()))
      HostController.consumeEscape();
    if (client.gui.screen() instanceof InteractionScreen screen)
      screen.hostInput(pressed, input.buttons());
    else if (client.gui.screen() instanceof CampaignMapScreen screen)
      screen.hostInput(pressed, input.buttons());
    else if (client.gui.screen() != null
        && !(client.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen)) {
      // Escape stays on ECHS: sending it here too would close both options and
      // its parent pause screen in one tick. Chat retains its ordered mailbox.
      var screen = client.gui.screen();
      int modifiers = (input.buttons() & SHIFT) != 0 ? 1 : 0;
      int[] bits = {CONFIRM, UP, DOWN, LEFT, RIGHT, TAB};
      int[] keys = {257, 265, 264, 263, 262, 258};
      for (int index = 0; index < bits.length && client.gui.screen() == screen; index++)
        if ((pressed & bits[index]) != 0)
          screen.keyPressed(HostChatKeys.event(keys[index], modifiers));
    }
  }

  static boolean select(long token, int choice) {
    var s = snapshot();
    if (!InteractionProtocol.selectable(s, token, choice)) return false;
    var row = s.menu().choices().stream().filter(c -> c.id() == choice).findFirst().orElseThrow();
    if (InteractionProtocol.unusedProgression(s.menu(), row.text())) return false;
    if (row.action().equals("ender_chest"))
      return CampaignEnderChest.open(Minecraft.getInstance(), s, choice);
    var request = envelope(s, token, "select");
    request.addProperty("choice", choice);
    return write("interaction-guest.json", request);
  }

  static void dismiss(long token) {
    var s = snapshot();
    dismissedToken = token;
    // Closed during a native lease gap: deliver the close once the feed returns,
    // otherwise the script would keep waiting on an invisible menu.
    closePending = s == null;
    if (s != null && s.menu() != null && s.menu().token() == token)
      write("interaction-guest.json", envelope(s, token, "close"));
  }

  static void closeMap() {
    var s = snapshot();
    if (s != null) {
      var state = envelope(s, 0, "ui_state");
      state.addProperty("open", false);
      state.addProperty("ready", true);
      write("interaction-ui.json", state);
    }
  }

  static long travel(int markerId) {
    var s = snapshot();
    if (s == null
        || (s.menu() != null && !s.menu().kind().equals("grace"))
        || s.markers().stream().noneMatch(m -> m.id() == markerId && m.travel())) return 0;
    var request = envelope(s, 0, "travel");
    request.addProperty("choice", markerId);
    return write("interaction-guest.json", request) ? request.get("seq").getAsLong() : 0;
  }

  static void openMap() {
    var s = snapshot();
    if (s != null && (s.menu() == null || s.menu().kind().equals("grace"))) {
      Minecraft.getInstance().gui.setScreen(new CampaignMapScreen(s));
      var state = envelope(s, 0, "ui_state");
      state.addProperty("open", true);
      state.addProperty("ready", true);
      write("interaction-ui.json", state);
    }
  }

  private static JsonObject envelope(InteractionProtocol.Snapshot s, long token, String action) {
    var j = new JsonObject();
    j.addProperty("version", 1);
    j.addProperty("pid", s.pid());
    j.addProperty("session", s.session());
    requestSequence = Math.max(requestSequence + 1, System.currentTimeMillis());
    j.addProperty("seq", requestSequence);
    j.addProperty("timestamp_ms", System.currentTimeMillis());
    j.addProperty("token", token);
    j.addProperty("action", action);
    return j;
  }

  private static boolean write(String name, JsonObject value) {
    try {
      Path directory = CampaignBridge.directory();
      Files.createDirectories(directory);
      Path target = directory.resolve(name), temporary = directory.resolve(name + ".guest.tmp");
      Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (IOException error) {
      LOG.warn("Interaction request unavailable: {}", error.getMessage());
      return false;
    }
  }

  public static void close() {
    CampaignEnderChest.close();
    var s = snapshot();
    if (s != null) {
      var state = envelope(s, 0, "ui_state");
      state.addProperty("open", false);
      state.addProperty("ready", false);
      write("interaction-ui.json", state);
    }
    observed = null;
  }
}
