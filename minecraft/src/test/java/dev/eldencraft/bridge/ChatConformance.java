package dev.eldencraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public final class ChatConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("chat check " + checks);
  }

  private static void reject(Runnable task) {
    checks++;
    try {
      task.run();
      throw new AssertionError("accepted invalid chat input");
    } catch (IllegalArgumentException expected) {
    }
  }

  private static byte[] fixture() {
    var b = ByteBuffer.allocate(ChatProtocol.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, ChatProtocol.MAGIC)
        .putInt(4, 1)
        .putLong(8, 2)
        .putLong(16, 1)
        .putLong(24, 1000)
        .putInt(32, 42)
        .putInt(36, 7)
        .putLong(40, 9)
        .putLong(48, 1)
        .putInt(56, 2)
        .putInt(60, 1);
    b.putLong(64, 1).putLong(72, 990).putInt(80, 2);
    b.putLong(96, 2).putLong(104, 1000).putInt(112, 3).putInt(116, 'ł');
    return b.array();
  }

  public static void main(String[] args) {
    check(dev.eldencraft.bridge.client.HostChatKeys.event(257, 0).isConfirmation());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(256, 0).isEscape());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(258, 0).isCycleFocus());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(262, 0).isRight());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(263, 0).isLeft());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(264, 0).isDown());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(265, 0).isUp());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(65, 2).isSelectAll());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(67, 2).isCopy());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(86, 2).isPaste());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(88, 2).isCut());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(259, 3).hasShiftDown());
    check(dev.eldencraft.bridge.client.HostChatKeys.event(259, 3).hasControlDown());
    check(
        dev.eldencraft.bridge.client.HostChatKeys.event(266, 0).shortcutKey()
            == org.lwjgl.sdl.SDLKeycode.SDLK_PAGEUP);
    check(
        dev.eldencraft.bridge.client.HostChatKeys.event(267, 0).shortcutKey()
            == org.lwjgl.sdl.SDLKeycode.SDLK_PAGEDOWN);
    var packet = ChatProtocol.decode(fixture(), 1001);
    check(packet.active());
    check(packet.events().get(1).code() == 'ł');
    for (int c : new int[] {32, 0x141, 0x6f22, 0x1f600, 0x10ffff})
      check(ChatProtocol.valid(3, c, 0));
    for (int c : new int[] {0, 31, 127, 0xd800, 0xdfff, 0x110000})
      check(!ChatProtocol.valid(3, c, 0));
    for (int k :
        new int[] {256, 257, 258, 259, 261, 262, 263, 264, 265, 266, 267, 268, 269, 65, 67, 86, 88})
      check(ChatProtocol.valid(4, k, 3));
    check(!ChatProtocol.valid(4, 87, 0));
    check(!ChatProtocol.valid(3, 97, 16));
    reject(() -> ChatProtocol.decode(fixture(), 999));
    reject(() -> ChatProtocol.decode(fixture(), 1250));
    for (int offset : new int[] {0, 4, 8, 56, 60, 92, 128}) {
      var bad = fixture();
      bad[offset] = (byte) 0xff;
      reject(() -> ChatProtocol.decode(bad, 1001));
    }
    var cursor = new ChatProtocol.Cursor();
    check(cursor.accept(packet, 42, 7, 1001).size() == 2);
    check(cursor.accept(packet, 42, 7, 1002).isEmpty());
    var e = new ChatProtocol.Event(3, 1010, 4, 257, 0);
    var next = new ChatProtocol.Packet(4, 42, 7, 9, true, List.of(e));
    check(cursor.accept(next, 42, 7, 1011).size() == 1);
    cursor.cancel();
    check(cursor.accept(next, 42, 7, 1012).isEmpty());
    var renewed =
        new ChatProtocol.Packet(
            6, 42, 7, 10, true, List.of(new ChatProtocol.Event(1, 1020, 1, 0, 0)));
    check(cursor.accept(renewed, 42, 7, 1021).size() == 1);
    var gap =
        new ChatProtocol.Packet(
            8, 42, 7, 10, true, List.of(new ChatProtocol.Event(3, 1022, 4, 257, 0)));
    reject(() -> cursor.accept(gap, 42, 7, 1023));
    check(cursor.accept(renewed, 42, 7, 1024).isEmpty());
    var stale = new ChatProtocol.Cursor();
    reject(() -> stale.accept(packet, 42, 7, 1500));
    check(new ChatProtocol.Cursor().accept(packet, 43, 7, 1001).isEmpty());
    check(new ChatProtocol.Cursor().accept(packet, 42, 8, 1001).isEmpty());
    var replacement =
        new ChatProtocol.Packet(
            8, 42, 7, 11, true, List.of(new ChatProtocol.Event(50, 1030, 3, 97, 0)));
    reject(() -> new ChatProtocol.Cursor().accept(replacement, 42, 7, 1031));
    var mapCursor = new ChatProtocol.Cursor();
    mapCursor.accept(packet, 42, 7, 1001);
    check(
        mapCursor
            .accept(new ChatProtocol.Packet(4, 42, 8, 9, true, List.of(e)), 42, 8, 1011)
            .isEmpty());
    // A sign editor's session starts with the text-screen attach event.
    check(ChatProtocol.valid(ChatProtocol.TEXT_SCREEN, 0, 0));
    check(!ChatProtocol.valid(ChatProtocol.TEXT_SCREEN, 97, 0));
    check(!ChatProtocol.valid(6, 0, 0));
    var sign =
        new ChatProtocol.Packet(
            10,
            42,
            7,
            12,
            true,
            List.of(
                new ChatProtocol.Event(1, 1040, ChatProtocol.TEXT_SCREEN, 0, 0),
                new ChatProtocol.Event(2, 1040, 3, 'h', 0)));
    check(new ChatProtocol.Cursor().accept(sign, 42, 7, 1041).size() == 2);
    System.out.println("Chat conformance: " + checks + " checks passed");
  }
}
