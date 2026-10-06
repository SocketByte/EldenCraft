package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.ChatProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Routes into the real chat screen. Submission, suggestions, history and permission checks stay
 * vanilla.
 */
final class HostChat {
  private static final ChatMailbox MAILBOX = new ChatMailbox();
  private static final ChatProtocol.Cursor CURSOR = new ChatProtocol.Cursor();
  private static ChatScreen owned;
  private static Object player, level;
  private static int buttons;
  private static long inputSequence = -1;

  static boolean tick(Minecraft client, HostState.Snapshot host) {
    boolean wasOwned = owned != null;
    if (player != client.player || level != client.level) {
      release(client);
      player = client.player;
      level = client.level;
    }
    var packet = MAILBOX.read(host.publisherPid());
    try {
      if (packet == null || !packet.active() || packet.map() != host.mapId()) {
        release(client);
        return wasOwned;
      }
      var events = CURSOR.accept(packet, host.publisherPid(), host.mapId(), MAILBOX.now());
      if (owned != null && client.gui.screen() != owned) {
        release(client);
        return true;
      }
      for (var event : events) {
        if (event.kind() == 1 || event.kind() == 2) {
          if (owned != null || client.gui.screen() != null) {
            release(client);
            return true;
          }
          client.gui.openChatScreen(
              event.kind() == 1
                  ? ChatComponent.ChatMethod.MESSAGE
                  : ChatComponent.ChatMethod.COMMAND);
          if (!(client.gui.screen() instanceof ChatScreen screen)) {
            release(client);
            return true;
          }
          owned = screen;
          buttons = host.buttonsDown();
          inputSequence = host.inputSequence();
          wasOwned = true;
        } else {
          if (owned == null) {
            CURSOR.cancel();
            return wasOwned;
          }
          if (event.kind() == 3) owned.charTyped(new CharacterEvent(event.code()));
          else owned.keyPressed(HostChatKeys.event(event.code(), event.modifiers()));
          if (client.gui.screen() != owned) {
            owned = null;
            CURSOR.cancel();
            return true;
          }
        }
      }
      if (owned != null) {
        double x = host.cursorX() * owned.width, y = host.cursorY() * owned.height;
        owned.mouseMoved(x, y);
        int pressed = host.buttonsDown() & ~buttons, released = buttons & ~host.buttonsDown();
        for (int button = 0; button < 2; button++) {
          var event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
          if ((pressed & (1 << button)) != 0) owned.mouseClicked(event, false);
          if ((released & (1 << button)) != 0) owned.mouseReleased(event);
        }
        if (inputSequence != host.inputSequence() && host.wheelDelta() != 0)
          owned.mouseScrolled(x, y, 0, host.wheelDelta());
        inputSequence = host.inputSequence();
        buttons = host.buttonsDown();
        return true;
      }
      return wasOwned;
    } catch (IllegalArgumentException rejected) {
      release(client);
      return wasOwned;
    }
  }

  /** The host chat currently owns the open chat screen and its input. */
  static boolean owns() {
    return owned != null;
  }

  static void release(Minecraft client) {
    CURSOR.cancel();
    // ChatScreen.removed preserves INTERRUPTED drafts in 26.3. Clear this
    // owned draft explicitly after removal, independent of saveChatDrafts.
    if (owned != null && client.gui.screen() == owned) {
      client.gui.setScreen(null);
      client.gui.hud.getChat().discardDraft();
    }
    owned = null;
    buttons = 0;
    inputSequence = -1;
  }

  static void close(Minecraft client) {
    release(client);
    MAILBOX.close();
  }

  private HostChat() {}
}
