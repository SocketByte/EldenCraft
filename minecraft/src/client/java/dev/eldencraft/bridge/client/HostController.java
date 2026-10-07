package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.eldencraft.bridge.HostAvatarMotion;
import dev.eldencraft.bridge.HostMiningInput;
import dev.eldencraft.bridge.JsonWire;
import dev.eldencraft.bridge.RangedInput;
import dev.eldencraft.bridge.TorrentMotion;
import dev.eldencraft.bridge.client.mixin.KeyMappingAccessor;
import dev.eldencraft.bridge.client.mixin.MouseHandlerAccessor;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Actual vanilla input routing; only valid foreground offline host publications may drive it. */
public final class HostController {
  private static final Logger LOG = LoggerFactory.getLogger("eldencraft_input");

  public record CameraPose(
      long hostFrame,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float fov,
      boolean firstPerson,
      int viewMode) {}

  public record AvatarPose(
      double x, double y, double z, HostAvatarMotion.Pose motion, boolean crouching) {}

  private static final HostAvatarMotion AVATAR_MOTION = new HostAvatarMotion();
  private static final TorrentMotion TORRENT_MOTION = new TorrentMotion();
  private static final HostState READER = new HostState();
  private static final Set<KeyMapping> DRIVEN = new HashSet<>();
  private static boolean inputEnabled = true, cameraEnabled = true;
  private static boolean configured;
  private static HostState.Snapshot latest;
  private static volatile RangedInput rangedInput;
  private static CameraPose frame;
  private static AvatarPose avatar;
  private static TorrentMotion.Pose torrent;
  private static CameraType savedCameraType;
  private static long mapId;
  private static long pid, lastInputSequence = -1;
  private static int previousButtons, blockedButtons;
  private static Object world;
  private static Object controlledPlayer;
  private static HostState.Vec3 hostAnchor;
  private static Vec3 guestAnchor;
  private static double lastMouseX, lastMouseY;
  private static long lastClickNanos;
  private static int lastClickButton = -1;
  private static Screen pressedScreen;
  private static boolean hostAttached, observedActive;
  private static Object observedPlayer;
  private static String observedScreen = "", observedCarried = "";
  private static final String[] observedSlots = new String[36];
  private static int observedSelected = -1;
  private static boolean observedUsing, observedSwinging, observedDestroying;
  private static long logWindow;
  private static int logCount, suppressedLogs;

  private HostController() {}

  public static void configure(Path file) {
    configured = true;
    HostHealthDisplay.configure(true);
    if (!Files.exists(file)) return;
    try (var stream = Files.newInputStream(file)) {
      byte[] bytes = stream.readNBytes(4097);
      if (bytes.length > 4096) throw new IllegalArgumentException();
      var config = JsonWire.parse(bytes);
      for (String key : config.keySet())
        if (!Set.of("schema", "input", "camera", "health", "time", "weather").contains(key))
          throw new IllegalArgumentException();
      JsonWire.integer(config.get("schema"), 1, 1);
      if (!config.get("input").isJsonPrimitive()
          || !config.getAsJsonPrimitive("input").isBoolean()
          || !config.get("camera").isJsonPrimitive()
          || !config.getAsJsonPrimitive("camera").isBoolean()) throw new IllegalArgumentException();
      inputEnabled = config.get("input").getAsBoolean();
      cameraEnabled = config.get("camera").getAsBoolean();
      if (config.has("health")) {
        if (!config.get("health").isJsonPrimitive()
            || !config.getAsJsonPrimitive("health").isBoolean())
          throw new IllegalArgumentException();
        HostHealthDisplay.configure(config.get("health").getAsBoolean());
      }
      if (config.has("time")) {
        if (!config.get("time").isJsonPrimitive() || !config.getAsJsonPrimitive("time").isBoolean())
          throw new IllegalArgumentException();
        HostTimeSync.configure(config.get("time").getAsBoolean());
      }
      if (config.has("weather")) {
        if (!config.get("weather").isJsonPrimitive()
            || !config.getAsJsonPrimitive("weather").isBoolean())
          throw new IllegalArgumentException();
        HostWeatherSync.configure(config.get("weather").getAsBoolean());
      }
    } catch (Exception failure) {
      configured = false;
      inputEnabled = false;
      cameraEnabled = false;
      HostHealthDisplay.configure(false);
      HostTimeSync.configure(false);
      HostWeatherSync.configure(false);
    }
  }

  private static boolean singleplayer(Minecraft client) {
    var server = client.getSingleplayerServer();
    return configured
        && client.level != null
        && client.player != null
        && client.hasSingleplayerServer()
        && server != null
        && !server.isPublished();
  }

  public static void beginFrame() {
    Minecraft client = Minecraft.getInstance();
    latest = singleplayer(client) ? READER.poll() : null;
    if (latest == null) {
      hostAttached = false;
      frame = null;
      avatar = null;
      torrent = null;
      hostAnchor = null;
      guestAnchor = null;
      AVATAR_MOTION.reset();
      TORRENT_MOTION.reset();
      restoreView(client);
      return;
    }
    if (!hostAttached
        || world != client.level
        || controlledPlayer != client.player
        || pid != latest.publisherPid()
        || mapId != latest.mapId()) {
      release(client);
      world = client.level;
      pid = latest.publisherPid();
      hostAnchor = null;
      guestAnchor = null;
      controlledPlayer = client.player;
      mapId = latest.mapId();
      AVATAR_MOTION.reset();
      previousButtons = 0;
      blockedButtons = latest.buttonsDown();
      lastInputSequence = latest.inputSequence();
      hostAttached = true;
    }
    if (!cameraEnabled) {
      frame = null;
      avatar = null;
      torrent = null;
      restoreView(client);
      return;
    }
    if (savedCameraType == null) savedCameraType = client.options.getCameraType();
    // The host already supplied the final front/rear camera; never invert it again here.
    client.options.setCameraType(
        latest.firstPerson() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
    var currentFeet = latest.feet();
    if (hostAnchor == null
        || Math.abs(currentFeet.x() - hostAnchor.x()) >= 32
        || Math.abs(currentFeet.y() - hostAnchor.y()) >= 32
        || Math.abs(currentFeet.z() - hostAnchor.z()) >= 32) {
      hostAnchor = currentFeet;
      guestAnchor = client.player.position();
    }
    var forward = latest.forward();
    float yaw = (float) Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
    float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(forward.y(), -1.0, 1.0)));
    var camera = latest.camera();
    double x = guestAnchor.x + camera.x() - hostAnchor.x(),
        y = guestAnchor.y + camera.y() - hostAnchor.y(),
        z = guestAnchor.z + camera.z() - hostAnchor.z();
    var stableCamera = SharedWorldClient.toGuestPhysical(camera.x(), camera.y(), camera.z());
    if (stableCamera != null) {
      x = stableCamera.x();
      y = stableCamera.y();
      z = stableCamera.z();
    } else if (Math.abs(x - guestAnchor.x) > 64
        || Math.abs(y - guestAnchor.y) > 64
        || Math.abs(z - guestAnchor.z) > 64) {
      frame = null;
      return;
    }
    frame =
        new CameraPose(
            latest.hostFrame(),
            x,
            y,
            z,
            yaw,
            pitch,
            latest.verticalFovDegrees(),
            latest.firstPerson(),
            latest.viewMode());
    // Front-view camera faces the avatar. Its interaction ray must still face out from the avatar.
    float interactionYaw = latest.viewMode() == 2 ? yaw + 180 : yaw;
    float interactionPitch = latest.viewMode() == 2 ? -pitch : pitch;
    var motion =
        AVATAR_MOTION.update(
            latest.hostFrame(),
            latest.timestampMillis(),
            interactionYaw,
            interactionPitch,
            latest.movementSpeed(),
            latest.grounded());
    var feet = latest.feet();
    var stableFeet = SharedWorldClient.toGuestPhysical(feet.x(), feet.y(), feet.z());
    double feetX = stableFeet != null ? stableFeet.x() : guestAnchor.x + feet.x() - hostAnchor.x();
    double feetY = stableFeet != null ? stableFeet.y() : guestAnchor.y + feet.y() - hostAnchor.y();
    double feetZ = stableFeet != null ? stableFeet.z() : guestAnchor.z + feet.z() - hostAnchor.z();
    avatar = new AvatarPose(feetX, feetY, feetZ, motion, latest.held(HostState.SNEAK));
    if (WorldTorrent.clientTorrent() == null) {
      TORRENT_MOTION.reset();
      torrent = null;
    } else
      torrent =
          TORRENT_MOTION.update(
              latest.hostFrame(),
              latest.timestampMillis(),
              feetX,
              feetZ,
              interactionYaw,
              latest.movementSpeed(),
              latest.grounded());
    client.player.setYRot(interactionYaw);
    client.player.setXRot(interactionPitch);
    client.player.yRotO = interactionYaw;
    client.player.xRotO = interactionPitch;
    client.player.yHeadRot = client.player.yHeadRotO = interactionYaw;
  }

  public static CameraPose frame() {
    return frame;
  }

  public static AvatarPose avatar() {
    return avatar;
  }

  /** Torrent's render heading and stride for the current host sample, or null on foot. */
  public static TorrentMotion.Pose torrent() {
    return torrent;
  }

  private static void restoreView(Minecraft client) {
    if (savedCameraType != null) {
      client.options.setCameraType(savedCameraType);
      savedCameraType = null;
    }
  }

  public static HostState.Snapshot healthSnapshot(Minecraft client) {
    return singleplayer(client) ? READER.poll() : null;
  }

  public static HostState.Snapshot damageSnapshot(Minecraft client) {
    return singleplayer(client)
        ? READER.poll(dev.eldencraft.bridge.HostDamageState.MAX_GAP_MILLIS)
        : null;
  }

  public static HostState.Snapshot combatSnapshot(Minecraft client) {
    return inputEnabled ? healthSnapshot(client) : null;
  }

  public static RangedInput rangedInput() {
    return rangedInput;
  }

  /** A replacement menu consumed Escape before the tick imports the same physical key. */
  static void consumeEscape() {
    blockedButtons |= HostState.ESCAPE;
    previousButtons &= ~HostState.ESCAPE;
  }

  /** Read-only permission for vanilla's held-mining mouse-grab gate. Never grabs the OS cursor. */
  public static boolean ownsHeldAttack(Minecraft client) {
    if (!inputEnabled
        || !hostAttached
        || !singleplayer(client)
        || world != client.level
        || controlledPlayer != client.player) return false;
    boolean ownsKey =
        DRIVEN.contains(client.options.keyAttack) && client.options.keyAttack.isDown();
    if (!ownsKey || client.gui.screen() != null) return false;
    var observed = READER.poll(HostMiningInput.MAX_AGE_MILLIS);
    var applied = new HostMiningInput.Input(pid, mapId, lastInputSequence, previousButtons);
    var current =
        observed == null
            ? null
            : new HostMiningInput.Input(
                observed.publisherPid(),
                observed.mapId(),
                observed.inputSequence(),
                observed.buttonsDown());
    if (HostMiningInput.lostContext(applied, current)) {
      // beginFrame's broader camera lease may still be fresh. Force the next input
      // acquisition to release owned keys and block buttons held through this loss.
      hostAttached = false;
      return false;
    }
    return HostMiningInput.mayContinue(ownsKey, false, applied, current, blockedButtons);
  }

  public static void tick(Minecraft client) {
    beginFrame();
    HostTimeSync.tick(client, latest);
    HostWeatherSync.tick(client, latest);
    boolean active = inputEnabled && latest != null && singleplayer(client);
    var inputFrame = active ? READER.poll(250) : null;
    if (inputFrame == null || inputFrame.publisherPid() != pid || inputFrame.mapId() != mapId) {
      active = false;
      rangedInput = null;
      hostAttached = false;
    }
    if (active != observedActive) {
      diagnostic("Host input {} ({}).", active ? "active" : "released", READER.status());
      observedActive = active;
    }
    if (!active) {
      release(client);
      return;
    }
    if (HostChat.tick(client, inputFrame)) {
      releaseKeys();
      rangedInput = null;
      WorldFlight.releaseInput();
      // A text screen's mouse runs in frame(), which needs these edges itself.
      if (!HostChat.ownsTextScreen()) {
        previousButtons = latest.buttonsDown();
        lastInputSequence = latest.inputSequence();
      }
      return;
    }
    int raw = latest.buttonsDown();
    blockedButtons &= raw;
    int buttons = raw & ~blockedButtons;
    int pressed = buttons & ~previousButtons;
    int released = previousButtons & ~buttons;
    WorldFlight.input(client, inputFrame, buttons, pressed);
    WorldTorrent.input(client, inputFrame, pressed);
    var screen = client.gui.screen();
    if (screen == null && CampaignInteractions.ownsInput()) {
      releaseKeys();
      rangedInput = null;
      WorldFlight.releaseInput();
      previousButtons = buttons;
      lastInputSequence = latest.inputSequence();
      return;
    }
    rangedInput =
        screen == null
            ? new RangedInput(
                pid,
                mapId,
                client.player.getUUID(),
                System.nanoTime(),
                client.player.getYRot(),
                client.player.getXRot(),
                (blockedButtons & HostState.USE) == 0)
            : null;
    logEdges(pressed, released, screen);
    if (screen != null) {
      routeScreen(client, screen, latest, buttons, pressed, released, HostChat.textScreen(screen));
      return;
    }
    if ((pressed & HostState.ESCAPE) != 0) {
      releaseKeys();
      rangedInput = null;
      WorldFlight.releaseInput();
      WorldFocus.openHostPause(client, inputFrame);
      previousButtons = buttons;
      lastInputSequence = inputFrame.inputSequence();
      return;
    }
    pressedScreen = null;
    apply(client.options.keyAttack, buttons, pressed, 0);
    apply(client.options.keyUse, buttons, pressed, 1);
    apply(client.options.keyInventory, buttons, pressed, 2);
    apply(client.options.keyDrop, buttons, pressed, 20);
    apply(client.options.keySwapOffhand, buttons, pressed, 21);
    for (int slot = 0; slot < 9; slot++)
      if ((pressed & (1 << (slot + 4))) != 0) client.player.getInventory().setSelectedSlot(slot);
    if (latest.inputSequence() != lastInputSequence && latest.wheelDelta() != 0)
      client
          .player
          .getInventory()
          .setSelectedSlot(
              Math.floorMod(
                  client.player.getInventory().getSelectedSlot() - latest.wheelDelta(), 9));
    previousButtons = buttons;
    lastInputSequence = latest.inputSequence();
  }

  /**
   * Every rendered frame while a screen is open, so hover, drags and clicks follow the host cursor
   * instead of waiting for the next 20 Hz tick. World input stays on the tick, and only a tick that
   * already accepted this host attachment lets frames route input.
   */
  public static void frame(Minecraft client) {
    if (!inputEnabled
        || !hostAttached
        || world != client.level
        || controlledPlayer != client.player
        || !singleplayer(client)) return;
    var screen = client.gui.screen();
    // Chat routes its own input; a sign editor takes typing from it but its mouse from here.
    if (screen == null || HostChat.owns() && !HostChat.textScreen(screen)) return;
    boolean typing = HostChat.owns() || HostChat.textScreen(screen);
    var input = READER.poll(250);
    if (input == null || input.publisherPid() != pid || input.mapId() != mapId) return;
    int raw = input.buttonsDown();
    blockedButtons &= raw;
    int buttons = raw & ~blockedButtons;
    int pressed = buttons & ~previousButtons, released = previousButtons & ~buttons;
    logEdges(pressed, released, screen);
    routeScreen(client, screen, input, buttons, pressed, released, typing);
  }

  private static void logEdges(int pressed, int released, Screen screen) {
    if ((pressed | released) != 0)
      diagnostic(
          "Host input edges: pressed={}, released={}, screen={}",
          Integer.toHexString(pressed),
          Integer.toHexString(released),
          screen == null ? "none" : screen.getClass().getSimpleName());
  }

  /**
   * Vanilla screen input with the host's Shift/Ctrl as event modifiers (shift-click quick move,
   * Ctrl+Q).
   */
  private static void routeScreen(
      Minecraft client,
      Screen screen,
      HostState.Snapshot input,
      int buttons,
      int pressed,
      int released,
      boolean typing) {
    // While typing, Escape and E arrive as text-screen keys; only the mouse is routed here.
    if (!typing && (pressed & (HostState.ESCAPE | HostState.INVENTORY)) != 0) {
      screen.onClose();
      releaseKeys();
      previousButtons = buttons;
      lastInputSequence = input.inputSequence();
      return;
    }
    releaseKeys();
    int modifiers =
        ((buttons & HostState.SNEAK) != 0 ? InputConstants.MOD_SHIFT : 0)
            | ((buttons & HostState.SPRINT) != 0 ? InputConstants.MOD_CONTROL : 0);
    double x = input.cursorX() * screen.width, y = input.cursorY() * screen.height;
    var mouse = (MouseHandlerAccessor) client.mouseHandler;
    mouse.eldencraft$setX(input.cursorX() * client.getWindow().getScreenWidth());
    mouse.eldencraft$setY(input.cursorY() * client.getWindow().getScreenHeight());
    screen.mouseMoved(x, y);
    for (int bit = 0; bit < 2; bit++) {
      int button = bit == 0 ? InputConstants.MOUSE_BUTTON_LEFT : InputConstants.MOUSE_BUTTON_RIGHT;
      MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers));
      if ((pressed & (1 << bit)) != 0) {
        boolean handled = screen.mouseClicked(event, doubleClick(button));
        pressedScreen = screen;
        diagnostic(
            "Host GUI mouse down: button={}, x={}, y={}, modifiers={}, handled={}, carried={}",
            button,
            Math.round(x),
            Math.round(y),
            modifiers,
            handled,
            item(client.player.containerMenu.getCarried()));
      }
      if ((released & (1 << bit)) != 0) {
        boolean handled = screen.mouseReleased(event);
        diagnostic(
            "Host GUI mouse up: button={}, x={}, y={}, handled={}, carried={}",
            button,
            Math.round(x),
            Math.round(y),
            handled,
            item(client.player.containerMenu.getCarried()));
      }
      if ((buttons & (1 << bit)) != 0
          && (pressed & (1 << bit)) == 0
          && pressedScreen == screen
          && (x != lastMouseX || y != lastMouseY))
        screen.mouseDragged(event, x - lastMouseX, y - lastMouseY);
    }
    if (input.inputSequence() != lastInputSequence && input.wheelDelta() != 0)
      screen.mouseScrolled(x, y, 0, input.wheelDelta());
    // Vanilla container keys act on the hovered slot: hotbar swap, drop (Ctrl: whole stack),
    // offhand swap.
    if (!typing && client.gui.screen() == screen) {
      for (int slot = 0; slot < 9; slot++)
        if ((pressed & (HostState.HOTBAR_1 << slot)) != 0)
          screenKey(screen, client.options.keyHotbarSlots[slot], modifiers);
      if ((pressed & HostState.DROP) != 0) screenKey(screen, client.options.keyDrop, modifiers);
      if ((pressed & HostState.SWAP_HANDS) != 0)
        screenKey(screen, client.options.keySwapOffhand, modifiers);
    }
    lastMouseX = x;
    lastMouseY = y;
    previousButtons = buttons;
    lastInputSequence = input.inputSequence();
  }

  /**
   * Vanilla MouseHandler: the same button again within 250 ms (container screens collect all on
   * it).
   */
  private static boolean doubleClick(int button) {
    long now = System.nanoTime();
    boolean repeated = button == lastClickButton && now - lastClickNanos < 250_000_000L;
    lastClickButton = button;
    lastClickNanos = now;
    return repeated;
  }

  /**
   * The player's own binding as a key event, so KeyMapping.matches recognizes it inside the screen.
   */
  private static void screenKey(Screen screen, KeyMapping mapping, int modifiers) {
    var key = ((KeyMappingAccessor) mapping).eldencraft$getKey();
    if (mapping.isUnbound() || key.getType() != InputConstants.Type.KEYBOARD) return;
    screen.keyPressed(new KeyEvent(key.getValue(), 0, modifiers));
  }

  private static void apply(KeyMapping key, int buttons, int pressed, int bit) {
    boolean down = (buttons & (1 << bit)) != 0;
    if (down && (pressed & (1 << bit)) != 0) {
      var access = (KeyMappingAccessor) key;
      access.eldencraft$setClickCount(Math.min(1, access.eldencraft$getClickCount() + 1));
    }
    if (down || DRIVEN.contains(key)) {
      key.setDown(down);
      DRIVEN.add(key);
    }
  }

  private static void releaseKeys() {
    for (KeyMapping key : DRIVEN) {
      key.setDown(false);
      ((KeyMappingAccessor) key).eldencraft$setClickCount(0);
    }
    DRIVEN.clear();
  }

  private static void release(Minecraft client) {
    HostChat.release(client);
    WorldFlight.releaseInput();
    rangedInput = null;
    // Stop the real use state without invoking releaseUsing: focus loss must not fire a bow.
    if (client.player != null
        && SharedWorldClient.inSharedDimension()
        && client.player.isUsingItem()
        && WorldProjectiles.ranged(client.player.getUseItem())) client.player.stopUsingItem();
    releaseKeys();
    if (pressedScreen != null && client.gui.screen() == pressedScreen)
      for (int button :
          new int[] {InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT})
        pressedScreen.mouseReleased(
            new MouseButtonEvent(lastMouseX, lastMouseY, new MouseButtonInfo(button, 0)));
    pressedScreen = null;
    previousButtons = 0;
    lastInputSequence = -1;
    frame = null;
  }

  /** End-tick evidence observes actual vanilla state changes, never fabricates completion. */
  public static void observe(Minecraft client) {
    if (!singleplayer(client)) {
      observedPlayer = null;
      return;
    }
    String screen =
        client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName();
    String carried = item(client.player.containerMenu.getCarried());
    int selected = client.player.getInventory().getSelectedSlot();
    boolean destroying = client.gameMode != null && client.gameMode.isDestroying();
    boolean baseline = observedPlayer != client.player;
    if (baseline) {
      observedPlayer = client.player;
      diagnostic(
          "Vanilla input baseline: screen={}, selected={}, carried={}", screen, selected, carried);
    } else {
      if (!screen.equals(observedScreen))
        diagnostic("Vanilla screen changed: {} -> {}", observedScreen, screen);
      if (!carried.equals(observedCarried))
        diagnostic("Vanilla carried item changed: {} -> {}", observedCarried, carried);
      if (selected != observedSelected)
        diagnostic("Vanilla selected slot changed: {} -> {}", observedSelected, selected);
      if (client.player.isUsingItem() != observedUsing)
        diagnostic("Vanilla item use {}", client.player.isUsingItem() ? "started" : "stopped");
      if (client.player.isSwinging() != observedSwinging)
        diagnostic("Vanilla hand swing {}", client.player.isSwinging() ? "started" : "stopped");
      if (destroying != observedDestroying)
        diagnostic(
            "Vanilla block destroying {} (host attack held={}).",
            destroying ? "started" : "stopped",
            (previousButtons & HostState.ATTACK) != 0);
    }
    for (int slot = 0; slot < 36; slot++) {
      String value = item(client.player.getInventory().getItem(slot));
      if (!baseline && !value.equals(observedSlots[slot]))
        diagnostic("Vanilla inventory slot {} changed: {} -> {}", slot, observedSlots[slot], value);
      observedSlots[slot] = value;
    }
    observedScreen = screen;
    observedCarried = carried;
    observedSelected = selected;
    observedUsing = client.player.isUsingItem();
    observedSwinging = client.player.isSwinging();
    observedDestroying = destroying;
  }

  private static String item(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
  }

  private static void diagnostic(String format, Object... arguments) {
    long now = System.nanoTime();
    if (now - logWindow >= 1_000_000_000L) {
      if (suppressedLogs > 0)
        LOG.info("Input diagnostics coalesced {} additional changes.", suppressedLogs);
      logWindow = now;
      logCount = 0;
      suppressedLogs = 0;
    }
    if (logCount++ < 16) LOG.info(format, arguments);
    else suppressedLogs++;
  }

  public static void close() {
    release(Minecraft.getInstance());
    HostChat.close(Minecraft.getInstance());
    READER.close();
  }
}
