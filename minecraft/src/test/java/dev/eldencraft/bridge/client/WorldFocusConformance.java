package dev.eldencraft.bridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Exercises focus handoff using vanilla screens without opening a window or launching a game. */
public final class WorldFocusConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static <T extends Screen> T screen(Class<T> type) throws Exception {
    // Screen constructors need a running client's font. This fixture exercises
    // real screen-type dispatch and GUI replacement without creating a window.
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static final class Inventory extends Screen {
    private Inventory() {
      super(null);
    }
  }

  private static final class Gui {
    Screen screen;
    int changes;

    Gui(Screen screen) {
      this.screen = screen;
    }

    void update(WorldFocus.Session session) {
      WorldFocus.resumeBackground(
          session,
          screen,
          replacement -> {
            screen = replacement;
            changes++;
          });
    }
  }

  public static void main(String[] args) throws Exception {
    check(
        !CampaignInteractions.consumesEscape(
            screen(net.minecraft.client.gui.screens.inventory.ContainerScreen.class)),
        "Vanilla Ender Chest keeps imported Escape so it closes through its normal container"
            + " protocol");
    check(
        CampaignInteractions.consumesEscape(screen(InteractionScreen.class))
            && CampaignInteractions.consumesEscape(screen(CampaignMapScreen.class)),
        "Custom grace and map screens consume Escape exactly once");
    check(
        Minecraft.class.getMethod("pauseGame", boolean.class).getReturnType() == void.class,
        "The pinned client exposes the cancellable pause entry point");
    check(
        net.minecraft.client.gui.Gui.class
                .getMethod("setPauseScreen", boolean.class, boolean.class)
                .getReturnType()
            == void.class,
        "Imported Escape uses the pinned vanilla pause GUI entry point");
    var focused = new WorldFocus.Session(true, true, true, "EldenCraft", false);
    var background = new WorldFocus.Session(true, true, false, "EldenCraft", false);
    check(!focused.background(), "Manual Escape while Minecraft is focused remains available");
    check(
        background.background(),
        "Alt-Tab suppresses automatic pause without requiring a host lease");

    var pause = screen(PauseScreen.class);
    var gui = new Gui(pause);
    gui.update(focused);
    check(
        gui.screen == pause && gui.changes == 0,
        "A deliberately opened local pause menu stays open");
    gui.update(background);
    check(
        gui.screen == null && gui.changes == 1, "Focus handoff closes an already-open pause menu");
    gui.update(background);
    gui.update(focused);
    check(gui.screen == null && gui.changes == 1, "Returning focus does not reopen the pause menu");

    var inventory = screen(Inventory.class);
    var container = new Gui(inventory);
    container.update(background);
    check(
        container.screen == inventory && container.changes == 0,
        "Inventory remains usable through the host view");
    var chat = screen(ChatScreen.class);
    var chatting = new Gui(chat);
    chatting.update(background);
    check(chatting.screen == chat && chatting.changes == 0, "Focus handoff preserves chat input");

    var title = screen(TitleScreen.class);
    var starting = new Gui(title);
    starting.update(new WorldFocus.Session(false, false, false, null, false));
    check(starting.screen == title, "Startup and disconnected menus are left alone");
    check(
        !new WorldFocus.Session(false, true, false, "EldenCraft", false).background(),
        "World loading and player absence do not authorize the override");

    var ordinaryPause = screen(PauseScreen.class);
    var ordinary = new Gui(ordinaryPause);
    ordinary.update(new WorldFocus.Session(true, true, false, "Survival", false));
    check(
        ordinary.screen == ordinaryPause && ordinary.changes == 0,
        "Other saves retain vanilla pause behavior");
    var published = new Gui(ordinaryPause);
    published.update(new WorldFocus.Session(true, false, false, "EldenCraft", true));
    check(
        published.screen == ordinaryPause,
        "Published and multiplayer sessions do not acquire the override");

    var legacy = new Gui(screen(PauseScreen.class));
    legacy.update(new WorldFocus.Session(true, true, false, "EldenCraft Passthrough Lab", false));
    check(legacy.screen == null, "Earlier dedicated saves support the same focus handoff");
    var shared = new Gui(screen(PauseScreen.class));
    shared.update(new WorldFocus.Session(true, true, false, "Survival", true));
    check(shared.screen == null, "An explicitly active shared dimension stays running as well");

    Object player = new Object(), world = new Object();
    var importedScreen = screen(PauseScreen.class);
    var imported = new WorldFocus.HostPause(importedScreen, player, world, 123, 456);
    check(
        imported.matches(background, player, world, 123, 456),
        "A fresh host-owned pause belongs to its exact foreground publisher, map, player and"
            + " world");
    var importedGui = new Gui(importedScreen);
    WorldFocus.resumeBackground(
        background,
        importedGui.screen,
        imported.matches(background, player, world, 123, 456)
            && importedGui.screen == imported.screen(),
        replacement -> importedGui.screen = replacement);
    check(
        importedGui.screen == importedScreen,
        "Imported Escape keeps its own pause menu through the host view");
    for (boolean current :
        new boolean[] {
          imported.matches(background, player, world, 0, 456),
          imported.matches(background, player, world, 124, 456),
          imported.matches(background, player, world, 123, 457),
          imported.matches(background, new Object(), world, 123, 456),
          imported.matches(background, player, new Object(), 123, 456),
          imported.matches(
              new WorldFocus.Session(true, false, false, "EldenCraft", true),
              player,
              world,
              123,
              456),
          imported.matches(
              new WorldFocus.Session(false, true, false, "EldenCraft", true),
              player,
              world,
              123,
              456),
          imported.matches(
              new WorldFocus.Session(true, true, false, "Survival", false), player, world, 123, 456)
        })
      check(!current, "Host pause ownership expires on foreground loss or context replacement");
    var unrelated = new Gui(screen(PauseScreen.class));
    WorldFocus.resumeBackground(
        background,
        unrelated.screen,
        unrelated.screen == imported.screen(),
        replacement -> unrelated.screen = replacement);
    check(unrelated.screen == null, "The imported lease cannot preserve another pause object");
    WorldFocus.resumeBackground(
        background, importedGui.screen, false, replacement -> importedGui.screen = replacement);
    check(
        importedGui.screen == null,
        "Expired host ownership returns to normal background auto-resume");
    System.out.println("WorldFocusConformance: " + checks + " checks passed");
  }
}
