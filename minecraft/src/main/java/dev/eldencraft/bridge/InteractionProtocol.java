package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Bounded observations of native ESD choices. Row IDs never become arbitrary game commands. */
public final class InteractionProtocol {
  public static final int MAX_BYTES = 131_072;
  public static final long MAX_AGE_MILLIS = 500;

  public record Prompt(long token, int textId, String text, boolean enabled) {}

  public record Choice(int id, String text, boolean enabled, String action) {}

  public record Menu(long token, String kind, String title, List<Choice> choices) {
    public Menu {
      choices = List.copyOf(choices);
    }
  }

  public record Snapshot(
      long pid,
      long session,
      long sequence,
      long millis,
      boolean active,
      Prompt prompt,
      Menu menu,
      String subtitle,
      boolean mapOpen,
      boolean blocking,
      Input input,
      List<Marker> markers,
      Ack ack) {
    public Snapshot {
      markers = List.copyOf(markers);
    }
  }

  public record Input(long sequence, int buttons, int pressed) {}

  public record Marker(int id, String text, double x, double z, int block, boolean travel) {}

  public record Ack(long sequence, String action, String status, String message) {}

  private InteractionProtocol() {}

  public static Snapshot decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Interaction snapshot exceeds limit");
    var j = JsonWire.parse(bytes);
    integer(j, "version", 1, 1);
    long pid = integer(j, "pid", 1, Integer.MAX_VALUE);
    long session = integer(j, "session", 1, Long.MAX_VALUE);
    long seq = integer(j, "seq", 1, Long.MAX_VALUE);
    long millis = integer(j, "timestamp_ms", 1, Long.MAX_VALUE);
    Prompt prompt = null;
    if (present(j, "prompt")) {
      var p = object(j, "prompt");
      prompt =
          new Prompt(
              integer(p, "token", 1, Long.MAX_VALUE),
              (int) integer(p, "text_id", Integer.MIN_VALUE, Integer.MAX_VALUE),
              text(p, "text", 4096, true).replaceAll("\\s+", " "),
              bool(p, "enabled"));
    }
    Menu menu = null;
    if (present(j, "menu")) {
      var m = object(j, "menu");
      String kind = text(m, "kind", 32, false);
      if (!Set.of("npc", "grace", "dialog").contains(kind))
        throw new IOException("Unknown interaction menu kind");
      var values = m.get("choices");
      if (values == null || !values.isJsonArray() || values.getAsJsonArray().size() > 64)
        throw new IOException("Invalid interaction choices");
      var choices = new ArrayList<Choice>();
      var ids = new HashSet<Integer>();
      for (var value : values.getAsJsonArray()) {
        if (!value.isJsonObject()) throw new IOException("Invalid interaction choice");
        var c = value.getAsJsonObject();
        int id = (int) integer(c, "id", Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (!ids.add(id)) throw new IOException("Duplicate interaction choice ID");
        String action = present(c, "action") ? text(c, "action", 32, false) : "";
        if (!action.isEmpty() && (!action.equals("ender_chest") || !kind.equals("grace")))
          throw new IOException("Unknown interaction choice action");
        choices.add(
            new Choice(
                id,
                text(c, "text", 4096, true).replaceAll("\\s+", " "),
                bool(c, "enabled"),
                action));
      }
      menu =
          new Menu(
              integer(m, "token", 1, Long.MAX_VALUE), kind, text(m, "title", 4096, true), choices);
    }
    String subtitle = "";
    if (present(j, "subtitle")) subtitle = text(object(j, "subtitle"), "text", 4096, true);
    boolean mapOpen = j.has("map_open") && bool(j, "map_open");
    boolean blocking = j.has("blocking") && bool(j, "blocking");
    Input input = null;
    if (present(j, "input")) {
      var i = object(j, "input");
      input =
          new Input(
              integer(i, "seq", 0, Long.MAX_VALUE),
              (int) integer(i, "buttons", 0, 0xfff),
              (int) integer(i, "pressed", 0, 0xfff));
    }
    var markers = new ArrayList<Marker>();
    if (present(j, "markers")) {
      var values = j.get("markers");
      if (!values.isJsonArray() || values.getAsJsonArray().size() > 2048)
        throw new IOException("Invalid map markers");
      var ids = new HashSet<Integer>();
      for (var value : values.getAsJsonArray()) {
        if (!value.isJsonObject()) throw new IOException("Invalid map marker");
        var m = value.getAsJsonObject();
        int id = (int) integer(m, "id", 0, Integer.MAX_VALUE);
        if (!ids.add(id)) throw new IOException("Duplicate map marker");
        markers.add(
            new Marker(
                id,
                text(m, "text", 4096, true).replaceAll("\\s+", " "),
                number(m, "x"),
                number(m, "z"),
                (int) integer(m, "block", Integer.MIN_VALUE, Integer.MAX_VALUE),
                bool(m, "travel")));
      }
    }
    Ack ack = null;
    if (present(j, "ack")) {
      var a = object(j, "ack");
      String action = text(a, "action", 32, false), status = text(a, "status", 32, false);
      if (!action.equals("travel") || !Set.of("accepted", "rejected").contains(status))
        throw new IOException("Invalid travel acknowledgement");
      ack =
          new Ack(
              integer(a, "seq", 1, Long.MAX_VALUE),
              action,
              status,
              text(a, "message", 1024, true).replaceAll("\\s+", " "));
    }
    return new Snapshot(
        pid,
        session,
        seq,
        millis,
        bool(j, "active"),
        prompt,
        menu,
        subtitle,
        mapOpen,
        blocking,
        input,
        markers,
        ack);
  }

  public static boolean fresh(Snapshot s, long now, long hostPid) {
    return s != null
        && s.active()
        && s.pid() == hostPid
        && now >= s.millis()
        && now - s.millis() < MAX_AGE_MILLIS;
  }

  public static boolean selectable(Snapshot s, long token, int id) {
    return s != null
        && s.menu() != null
        && s.menu().token() == token
        && s.menu().choices().stream().anyMatch(c -> c.id() == id && c.enabled());
  }

  public static Ack travelAcknowledgement(
      Snapshot s, long pid, long session, long requestSequence, long now) {
    return fresh(s, now, pid)
            && s.session() == session
            && requestSequence > 0
            && s.ack() != null
            && s.ack().action().equals("travel")
            && s.ack().sequence() == requestSequence
        ? s.ack()
        : null;
  }

  /** Choices removed from EldenCraft's progression cannot be executed from the replacement UI. */
  public static boolean unusedProgression(Menu menu, String label) {
    return menu != null && menu.kind().equals("grace") && unusedProgression(label);
  }

  public static boolean unusedProgression(String label) {
    String value = label.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    return value.equals("level up")
        || value.equals("leveling")
        || value.equals("levelling")
        || value.equals("memorize spell")
        || value.equals("memorise spell")
        || value.contains("flask")
        || value.equals("rebirth")
        || value.equals("reallocate attributes");
  }

  private static boolean present(JsonObject j, String key) {
    return j.has(key) && !j.get(key).isJsonNull();
  }

  private static double number(JsonObject j, String key) throws IOException {
    var v = j.get(key);
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IOException("Invalid map coordinate");
    double number = v.getAsDouble();
    if (!Double.isFinite(number) || Math.abs(number) > 30_000_000)
      throw new IOException("Map coordinate out of bounds");
    return number;
  }

  private static JsonObject object(JsonObject j, String key) throws IOException {
    var v = j.get(key);
    if (v == null || !v.isJsonObject()) throw new IOException("Invalid interaction " + key);
    return v.getAsJsonObject();
  }

  private static long integer(JsonObject j, String key, long min, long max) throws IOException {
    return JsonWire.integer(j.get(key), min, max);
  }

  private static boolean bool(JsonObject j, String key) throws IOException {
    var value = j.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IOException("Invalid interaction " + key);
    return value.getAsBoolean();
  }

  private static String text(JsonObject j, String key, int max, boolean multiline)
      throws IOException {
    String text = JsonWire.string(j.get(key));
    if (text.isBlank()
        || text.codePointCount(0, text.length()) > max
        || text.chars().anyMatch(c -> c < 32 && !(multiline && (c == 10 || c == 13 || c == 9))))
      throw new IOException("Invalid interaction " + key);
    return text;
  }
}
