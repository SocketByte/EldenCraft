package dev.eldencraft.bridge;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded shared-world snapshots. This layer has no Minecraft or native dependencies. */
public final class WorldProtocol {
  public static final int SIZE = 2_097_152,
      HEADER = 64,
      HOST_MAGIC = 0x48574345,
      GUEST_MAGIC = 0x47574345;

  public record Box(WorldOrigin.Vec min, WorldOrigin.Vec max) {
    public Box {
      if (min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z())
        throw new IllegalArgumentException("Empty world box");
    }
  }

  public record Target(
      long id,
      long generation,
      WorldOrigin.Vec min,
      WorldOrigin.Vec max,
      float hp,
      float maxHp,
      int team) {}

  public record Incoming(long sequence, UUID uuid, float damage, String source, long millis) {}

  public record Acknowledgement(long sequence, int result, float delta, String reason) {}

  public record ProjectileImpact(
      UUID projectile, int segment, WorldOrigin.Vec point, WorldOrigin.Vec normal, long millis) {}

  public record Host(
      long frame,
      long millis,
      long pid,
      boolean active,
      long epoch,
      long map,
      long sourceMap,
      WorldOrigin.Vec feet,
      WorldOrigin.Vec camera,
      WorldOrigin.Vec forward,
      WorldOrigin.Vec offset,
      WorldOrigin.Vec sourceToRegion,
      boolean grounded,
      float hp,
      float maxHp,
      long playerId,
      long playerGeneration,
      float damageScale,
      long terrainRevision,
      boolean terrainReady,
      Box bounds,
      List<Box> terrain,
      List<Target> targets,
      long guestSession,
      long ack,
      List<Incoming> incoming,
      List<Acknowledgement> acknowledgements,
      List<ProjectileImpact> projectileImpacts,
      /**
       * Havok body material per terrain box (TerrainMaterials.NO_BODY unknown); empty from older
       * hosts.
       */
      List<Integer> terrainMaterials,
      /** Elden Ring hit material under the feet, -1 when airborne or unknown. */
      int groundMaterial,
      /** Learned Havok body material -> hit material. */
      Map<Integer, Integer> materialTable) {}

  private WorldProtocol() {}

  public static Host decode(byte[] bytes, long now) throws IOException {
    try {
      return decodeChecked(bytes, now);
    } catch (RuntimeException e) {
      throw new IOException("Malformed world snapshot", e);
    }
  }

  private static Host decodeChecked(byte[] bytes, long now) throws IOException {
    if (bytes.length < HEADER || bytes.length > SIZE) throw new IOException("World frame length");
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int flags = b.getInt(36), length = b.getInt(40);
    if (b.getInt(0) != HOST_MAGIC
        || b.getInt(4) != 1
        || b.getLong(8) <= 0
        || (b.getLong(8) & 1) != 0
        || b.getLong(16) <= 0
        || (flags & ~1) != 0
        || length <= 0
        || length != bytes.length - HEADER
        || b.getLong(24) > now
        || now - b.getLong(24) > 500
        || Integer.toUnsignedLong(b.getInt(32)) == 0) throw new IOException("Invalid world header");
    for (int i = 44; i < 64; i++) if (bytes[i] != 0) throw new IOException("World reserved bytes");
    var json = parse(Arrays.copyOfRange(bytes, 64, bytes.length));
    long epoch = integer(json, "epoch", 1, Long.MAX_VALUE),
        map = integer(json, "map", 0, 0xffff_ffffL);
    WorldOrigin.Vec feet = vec(json.get("feet")),
        camera = vec(json.get("camera")),
        forward = vec(json.get("forward")),
        offset = vec(json.get("offset"));
    double norm = forward.x() * forward.x() + forward.y() * forward.y() + forward.z() * forward.z();
    if (norm < .98 || norm > 1.02) throw new IOException("World forward");
    float max = number(json, "max_hp", 1, 10_000_000), hp = number(json, "hp", 0, max);
    var terrain = new ArrayList<Box>();
    for (var value : array(json, "terrain_boxes", 4096)) terrain.add(box(value));
    var terrainMaterials = new ArrayList<Integer>();
    if (json.has("terrain_materials"))
      for (var value : array(json, "terrain_materials", 4096))
        terrainMaterials.add((int) Math.max(0, Math.min(65535, value.getAsLong())));
    if (!terrainMaterials.isEmpty() && terrainMaterials.size() != terrain.size())
      throw new IOException("World terrain materials");
    int groundMaterial =
        json.has("ground_material")
            ? (int) Math.max(-1, Math.min(1_000_000, json.get("ground_material").getAsLong()))
            : -1;
    var materialTable = new HashMap<Integer, Integer>();
    if (json.has("material_table"))
      for (var value : array(json, "material_table", 256)) {
        var m = value.getAsJsonObject();
        materialTable.put(
            (int) integer(m, "body_material", 0, 65534),
            (int) integer(m, "hit_material", 1, 1_000_000));
      }
    var targets = new ArrayList<Target>();
    var ids = new HashSet<Long>();
    for (var value : array(json, "targets", 32)) {
      var t = value.getAsJsonObject();
      long id = integer(t, "id", 1, Long.MAX_VALUE);
      if (!ids.add(id)) throw new IOException("Duplicate world target");
      WorldOrigin.Vec min = vec(t.get("min")), maxPos = vec(t.get("max"));
      new Box(min, maxPos);
      float maxHealth = number(t, "max_hp", 1, 10_000_000);
      targets.add(
          new Target(
              id,
              integer(t, "generation", 1, Long.MAX_VALUE),
              min,
              maxPos,
              number(t, "hp", 0, maxHealth),
              maxHealth,
              (int) integer(t, "team", 0, 255)));
    }
    var incoming = new ArrayList<Incoming>();
    long prior = 0;
    for (var value : array(json, "incoming", 128)) {
      var i = value.getAsJsonObject();
      long seq = integer(i, "seq", 1, Long.MAX_VALUE);
      if (seq <= prior) throw new IOException("Unordered incoming");
      prior = seq;
      long time = integer(i, "time_ms", 0, Long.MAX_VALUE);
      if (time > now || now - time > 1000) continue;
      incoming.add(
          new Incoming(
              seq,
              UUID.fromString(JsonWire.string(i.get("uuid"))),
              number(i, "damage", .0001f, 1000),
              JsonWire.string(i.get("source")),
              time));
    }
    var acknowledgements = new ArrayList<Acknowledgement>();
    if (json.has("acks"))
      for (var value : array(json, "acks", 128)) {
        var a = value.getAsJsonObject();
        String reason = JsonWire.string(a.get("reason"));
        if (reason.length() > 128) throw new IOException("World ACK reason");
        acknowledgements.add(
            new Acknowledgement(
                integer(a, "seq", 1, Long.MAX_VALUE),
                (int) integer(a, "result", 1, 2),
                number(a, "delta", 0, 10_000_000),
                reason));
      }
    var impacts = new ArrayList<ProjectileImpact>();
    var projectiles = new HashSet<UUID>();
    if (json.has("projectile_impacts"))
      for (var value : array(json, "projectile_impacts", 32)) {
        var a = value.getAsJsonObject();
        var id = UUID.fromString(JsonWire.string(a.get("projectile")));
        if (!projectiles.add(id)) throw new IOException("Duplicate projectile impact");
        long time = integer(a, "time_ms", 0, Long.MAX_VALUE);
        if (time > now || now - time > 1000) continue;
        var normal = vec(a.get("normal"));
        double size = normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z();
        if (size < .98 || size > 1.02) throw new IOException("Projectile contact normal");
        impacts.add(
            new ProjectileImpact(
                id, (int) integer(a, "segment", 1, 127), vec(a.get("point")), normal, time));
      }
    return new Host(
        b.getLong(16),
        b.getLong(24),
        Integer.toUnsignedLong(b.getInt(32)),
        flags == 1,
        epoch,
        map,
        json.has("source_map") ? integer(json, "source_map", 0, 0xffff_ffffL) : map,
        feet,
        camera,
        forward,
        offset,
        json.has("source_to_region")
            ? vec(json.get("source_to_region"))
            : new WorldOrigin.Vec(0, 0, 0),
        bool(json, "grounded"),
        hp,
        max,
        json.has("player_id") ? integer(json, "player_id", 1, Long.MAX_VALUE) : 0,
        json.has("player_generation")
            ? integer(json, "player_generation", 1, Long.MAX_VALUE)
            : epoch,
        json.has("damage_scale") ? number(json, "damage_scale", .1f, 1000) : 50,
        integer(json, "terrain_revision", 0, Long.MAX_VALUE),
        bool(json, "terrain_ready"),
        box(json.get("terrain_bounds")),
        List.copyOf(terrain),
        List.copyOf(targets),
        integer(json, "guest_session", 0, Long.MAX_VALUE),
        integer(json, "ack", 0, Long.MAX_VALUE),
        List.copyOf(incoming),
        List.copyOf(acknowledgements),
        List.copyOf(impacts),
        List.copyOf(terrainMaterials),
        groundMaterial,
        Map.copyOf(materialTable));
  }

  public static JsonObject parse(byte[] bytes) throws IOException {
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    var reader = new JsonReader(new StringReader(text));
    reader.setStrictness(Strictness.STRICT);
    JsonElement value = read(reader, 0, new int[] {0});
    if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
      throw new IOException("World JSON");
    return value.getAsJsonObject();
  }

  private static JsonElement read(JsonReader r, int depth, int[] count) throws IOException {
    if (depth > 12 || ++count[0] > 150_000) throw new IOException("World JSON complexity");
    return switch (r.peek()) {
      case BEGIN_OBJECT -> {
        var o = new JsonObject();
        r.beginObject();
        while (r.hasNext()) {
          String k = r.nextName();
          if (k.length() > 128 || o.has(k)) throw new IOException("Duplicate world key");
          o.add(k, read(r, depth + 1, count));
        }
        r.endObject();
        yield o;
      }
      case BEGIN_ARRAY -> {
        var a = new JsonArray();
        r.beginArray();
        while (r.hasNext()) a.add(read(r, depth + 1, count));
        r.endArray();
        yield a;
      }
      case STRING -> {
        String s = r.nextString();
        if (s.length() > 2048) throw new IOException("World string");
        yield new JsonPrimitive(s);
      }
      case NUMBER -> {
        String s = r.nextString();
        var n = new java.math.BigDecimal(s);
        if (!Double.isFinite(n.doubleValue())) throw new IOException("World number");
        yield new JsonPrimitive(n);
      }
      case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
      case NULL -> {
        r.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new IOException("World token");
    };
  }

  public static JsonArray vector(double x, double y, double z) {
    return JsonWire.vector(x, y, z);
  }

  public static JsonArray vector(WorldOrigin.Vec v) {
    return vector(v.x(), v.y(), v.z());
  }

  public static long integer(JsonObject o, String key, long min, long max) throws IOException {
    try {
      var v = o.get(key);
      if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
        throw new IOException("World integer");
      long n = v.getAsBigDecimal().longValueExact();
      if (n < min || n > max) throw new IOException("World integer bounds");
      return n;
    } catch (ArithmeticException | IllegalStateException e) {
      throw new IOException("World integer", e);
    }
  }

  private static float number(JsonObject o, String key, float min, float max) throws IOException {
    var v = o.get(key);
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IOException("World numeric field");
    float n = v.getAsFloat();
    if (!Float.isFinite(n) || n < min || n > max) throw new IOException("World numeric bounds");
    return n;
  }

  private static boolean bool(JsonObject o, String key) throws IOException {
    var v = o.get(key);
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
      throw new IOException("World boolean");
    return v.getAsBoolean();
  }

  private static JsonArray array(JsonObject o, String key, int max) throws IOException {
    var v = o.get(key);
    if (v == null || !v.isJsonArray() || v.getAsJsonArray().size() > max)
      throw new IOException("World array");
    return v.getAsJsonArray();
  }

  private static WorldOrigin.Vec vec(JsonElement e) throws IOException {
    if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3)
      throw new IOException("World vector");
    var a = e.getAsJsonArray();
    for (var component : a)
      if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber())
        throw new IOException("World vector number");
    try {
      return new WorldOrigin.Vec(
          a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    } catch (RuntimeException ex) {
      throw new IOException("World vector", ex);
    }
  }

  private static Box box(JsonElement e) throws IOException {
    if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 6)
      throw new IOException("World box");
    var a = e.getAsJsonArray();
    for (var component : a)
      if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber())
        throw new IOException("World box number");
    try {
      return new Box(
          new WorldOrigin.Vec(
              a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()),
          new WorldOrigin.Vec(
              a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble()));
    } catch (RuntimeException ex) {
      throw new IOException("World box", ex);
    }
  }
}
