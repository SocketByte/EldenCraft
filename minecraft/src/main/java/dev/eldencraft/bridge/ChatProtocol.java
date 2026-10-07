package dev.eldencraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** ECCH v1: bounded ordered text, never executable commands or permission grants. */
public final class ChatProtocol {
  public static final int BYTES = 4160, MAX_EVENTS = 128, MAGIC = 0x48434345;

  public record Event(long sequence, long time, int kind, int code, int modifiers) {}

  public record Packet(
      long sequence, long pid, long map, long session, boolean active, List<Event> events) {}

  public static Packet decode(byte[] bytes, long now) {
    require(bytes.length == BYTES, "chat size");
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    require(b.getInt() == MAGIC && b.getInt() == 1, "chat version");
    long sequence = b.getLong(8), stamp = b.getLong(24), pid = Integer.toUnsignedLong(b.getInt(32));
    long map = Integer.toUnsignedLong(b.getInt(36)), session = b.getLong(40), first = b.getLong(48);
    int count = b.getInt(56), flags = b.getInt(60);
    require(sequence > 0 && (sequence & 1) == 0 && b.getLong(16) > 0 && pid != 0, "chat identity");
    require(stamp > 0 && stamp <= now && now - stamp < 250, "chat freshness");
    require(
        session >= 0 && count >= 0 && count <= MAX_EVENTS && (flags == 0 || flags == 1),
        "chat fields");
    require(flags == 0 ? count == 0 : session > 0 && count > 0 && first > 0, "chat session");
    var events = new ArrayList<Event>(count);
    long previousTime = 0;
    for (int i = 0; i < count; i++) {
      int p = 64 + i * 32;
      long seq = b.getLong(p), time = b.getLong(p + 8);
      int kind = b.getInt(p + 16), code = b.getInt(p + 20), mods = b.getInt(p + 24);
      require(
          seq == first + i
              && time > 0
              && time <= stamp
              && time >= previousTime
              && b.getInt(p + 28) == 0,
          "chat event order");
      require(valid(kind, code, mods), "chat event");
      previousTime = time;
      events.add(new Event(seq, time, kind, code, mods));
    }
    for (int p = 64 + count * 32; p < BYTES; p++) require(bytes[p] == 0, "chat padding");
    return new Packet(sequence, pid, map, session, flags == 1, List.copyOf(events));
  }

  /** Attach to a text screen Minecraft already shows, such as a sign editor. */
  public static final int TEXT_SCREEN = 5;

  /** Chat, command or text-screen sessions start with their opening event. */
  public static boolean opens(int kind) {
    return kind == 1 || kind == 2 || kind == TEXT_SCREEN;
  }

  public static boolean valid(int kind, int code, int mods) {
    if ((mods & ~15) != 0) return false;
    return switch (kind) {
      case 1, 2, TEXT_SCREEN -> code == 0 && mods == 0;
      case 3 ->
          code >= 32
              && code != 127
              && Character.isValidCodePoint(code)
              && !(code >= 0xd800 && code <= 0xdfff);
      case 4 -> code >= 256 && code <= 269 || code == 65 || code == 67 || code == 86 || code == 88;
      default -> false;
    };
  }

  /** Rejected session stays rejected: a surviving mapping cannot reopen canceled text. */
  public static final class Cursor {
    private long pid, session, map, last;
    private boolean blocked;

    public void cancel() {
      blocked = true;
    }

    public List<Event> accept(Packet packet, long expectedPid, long map, long now) {
      if (packet == null
          || !packet.active()
          || packet.pid() != expectedPid
          || packet.map() != map) {
        cancel();
        return List.of();
      }
      if (pid != packet.pid() || session != packet.session()) {
        pid = packet.pid();
        session = packet.session();
        this.map = map;
        last = 0;
        blocked = false;
      }
      if (this.map != map) {
        cancel();
        return List.of();
      }
      if (blocked) return List.of();
      var next = packet.events().stream().filter(e -> e.sequence() > last).toList();
      if (next.isEmpty()) return next;
      if (next.getFirst().sequence() != last + 1
          || last == 0 && !opens(next.getFirst().kind())
          || next.stream().anyMatch(e -> now < e.time() || now - e.time() >= 500)) {
        cancel();
        throw new IllegalArgumentException("chat input lost, stale, or out of order");
      }
      last = next.getLast().sequence();
      return next;
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  private ChatProtocol() {}
}
