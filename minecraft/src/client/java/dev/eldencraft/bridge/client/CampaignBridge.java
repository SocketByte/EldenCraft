package dev.eldencraft.bridge.client;

import com.google.gson.*;
import dev.eldencraft.bridge.CampaignLoot;
import dev.eldencraft.bridge.JsonWire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Immutable campaign observations and atomic requests to the offline native game thread. */
public final class CampaignBridge {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_campaign");
  private static final int MAX_BYTES = 131_072;
  private static final long MAX_AGE_MS = 1500;
  private static volatile Snapshot observed;
  private static Path directory;
  private static long nextRead;
  private static String lastError = "";
  private static long healingSession, healingSequence;
  private static String healingCharacter = "";
  private static double healingTotal;

  public record Merchant(String id, String name, String token) {}

  public record Ack(String id, String status, long amount) {}

  public record DamageEvent(long seq, double rawDamage, boolean blocked) {}

  public record Boss(String id, String name, double hp, double maxHp) {}

  public record Snapshot(
      int version,
      long pid,
      long session,
      long seq,
      long timestampMillis,
      boolean active,
      String character,
      long runes,
      double hp,
      double maxHp,
      double stamina,
      double maxStamina,
      Set<String> defeated,
      Merchant merchant,
      Ack ack,
      long guardSeq,
      double guardDamage,
      long healSeq,
      List<DamageEvent> damageEvents,
      boolean dead,
      long experienceSeq,
      long experienceTotal,
      long lootSeq,
      List<CampaignLoot.Kill> lootEvents,
      List<Boss> activeBosses) {
    public Snapshot {
      defeated = Set.copyOf(defeated);
      damageEvents = List.copyOf(damageEvents);
      lootEvents = List.copyOf(lootEvents);
      activeBosses = List.copyOf(activeBosses);
    }
  }

  private CampaignBridge() {}

  public static synchronized void initialize() {
    if (directory != null) return;
    String explicit = System.getenv("ELDENCRAFT_CAMPAIGN_DIR");
    String data = System.getenv("ELDENCRAFT_DATA_DIR");
    var link = CampaignClient.link();
    if ((explicit == null || explicit.isBlank()) && link != null && link.has("directory"))
      explicit = link.get("directory").getAsString();
    directory =
        explicit != null && !explicit.isBlank()
            ? Path.of(explicit)
            : data != null && !data.isBlank()
                ? Path.of(data).resolve("campaign")
                : net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir()
                    .resolve("eldencraft-data/campaign");
  }

  public static Path directory() {
    initialize();
    return directory;
  }

  public static Snapshot snapshot() {
    var value = observed;
    return fresh(value, System.currentTimeMillis())
            && ProcessHandle.of(value.pid()).map(ProcessHandle::isAlive).orElse(false)
        ? value
        : null;
  }

  public static boolean fresh() {
    return snapshot() != null;
  }

  /** Death is explicit; an unloaded character or foreground loss cannot refill stamina. */
  public static Snapshot deathObserved() {
    var value = observed;
    long now = System.currentTimeMillis();
    return value != null
            && value.dead()
            && value.hp() == 0
            && now >= value.timestampMillis()
            && now - value.timestampMillis() < MAX_AGE_MS
            && ProcessHandle.of(value.pid()).map(ProcessHandle::isAlive).orElse(false)
        ? value
        : null;
  }

  static boolean fresh(Snapshot value, long now) {
    return value != null
        && value.active()
        && value.hp() > 0
        && now >= value.timestampMillis()
        && now - value.timestampMillis() < MAX_AGE_MS;
  }

  public static void tick(Minecraft client) {
    initialize();
    long now = System.nanoTime();
    if (now < nextRead) return;
    nextRead = now + 50_000_000L;
    try {
      Path file = directory.resolve("campaign-host.json");
      if (!Files.isRegularFile(file)) {
        observed = null;
        return;
      }
      if (Files.size(file) > MAX_BYTES) throw new IOException("Campaign snapshot exceeds limit");
      Snapshot value = decode(Files.readAllBytes(file));
      // The coherent ECHS publisher must be the same native process. Menus keep
      // reading this channel, so inventory/shop ownership never freezes the wallet.
      var host = HostController.healthSnapshot(client);
      if (host == null || host.publisherPid() != value.pid()) {
        observed = null;
        return;
      }
      var old = observed;
      if (old != null
          && old.pid() == value.pid()
          && old.session() == value.session()
          && value.seq() < old.seq()) throw new IOException("Campaign sequence regressed");
      observed = value;
      lastError = "";
    } catch (IOException | IllegalArgumentException error) {
      observed = null;
      if (!lastError.equals(error.getMessage())) {
        lastError = error.getMessage();
        LOG.warn("Campaign snapshot unavailable: {}", lastError);
      }
    }
  }

  static Snapshot decode(byte[] bytes) throws IOException {
    var j = JsonWire.parse(bytes);
    int version = (int) integer(j, "version", 1, 1);
    long pid = integer(j, "pid", 1, Integer.MAX_VALUE);
    long session = integer(j, "session", 1, Long.MAX_VALUE);
    long seq = integer(j, "seq", 1, Long.MAX_VALUE);
    long timestamp = integer(j, "timestamp_ms", 1, Long.MAX_VALUE);
    String character = text(j, "character", 160);
    long runes = integer(j, "runes", 0, 999_999_999);
    double hp = number(j, "hp", 0, 1_000_000), maxHp = number(j, "max_hp", 1, 1_000_000);
    double stamina = number(j, "stamina", 0, 1_000_000);
    double maxStamina = number(j, "max_stamina", 1, 1_000_000);
    if (hp > maxHp || stamina > maxStamina)
      throw new IOException("Campaign vitals exceed capacity");
    var defeated = new LinkedHashSet<String>();
    if (!j.has("defeated") || !j.get("defeated").isJsonArray())
      throw new IOException("Missing campaign boss state");
    var bosses = j.getAsJsonArray("defeated");
    if (bosses.size() > 512) throw new IOException("Too many campaign bosses");
    for (var boss : bosses) {
      String id = JsonWire.string(boss);
      if (!id.matches("[a-z0-9_:-]{1,80}") || !defeated.add(id))
        throw new IOException("Invalid or duplicate campaign boss");
    }
    Merchant merchant = null;
    if (j.has("merchant") && !j.get("merchant").isJsonNull()) {
      if (!j.get("merchant").isJsonObject()) throw new IOException("Invalid merchant context");
      var m = j.getAsJsonObject("merchant");
      merchant = new Merchant(text(m, "id", 80), text(m, "name", 160), text(m, "token", 160));
    }
    Ack ack = null;
    if (j.has("ack") && !j.get("ack").isJsonNull()) {
      if (!j.get("ack").isJsonObject()) throw new IOException("Invalid purchase acknowledgement");
      var a = j.getAsJsonObject("ack");
      ack =
          new Ack(text(a, "id", 160), text(a, "status", 80), integer(a, "amount", 0, 999_999_999));
    }
    long guardSeq = j.has("guard_seq") ? integer(j, "guard_seq", 0, Long.MAX_VALUE) : 0;
    double guardDamage = j.has("guard_damage") ? number(j, "guard_damage", 0, 1.0e30) : 0;
    long healSeq = j.has("heal_seq") ? integer(j, "heal_seq", 0, Long.MAX_VALUE) : 0;
    boolean dead = j.has("dead") && bool(j, "dead");
    if (dead && hp != 0) throw new IOException("Living campaign snapshot marked dead");
    long experienceSeq =
        j.has("experience_seq") ? integer(j, "experience_seq", 0, Long.MAX_VALUE) : 0;
    long experienceTotal =
        j.has("experience_total") ? integer(j, "experience_total", 0, 9_000_000_000_000_000L) : 0;
    long lootSeq = j.has("loot_seq") ? integer(j, "loot_seq", 0, Long.MAX_VALUE) : 0;
    var lootEvents = new ArrayList<CampaignLoot.Kill>();
    if (j.has("loot_events")) {
      if (!j.get("loot_events").isJsonArray()) throw new IOException("Invalid loot events");
      var events = j.getAsJsonArray("loot_events");
      if (events.size() > CampaignLoot.MAX_EVENTS) throw new IOException("Too many loot events");
      long previous = 0;
      for (var event : events) {
        if (!event.isJsonObject()) throw new IOException("Invalid loot event");
        var e = event.getAsJsonObject();
        // Native publishes its cumulative sequence with the events it still holds.
        long eventSeq = integer(e, "seq", 1, lootSeq);
        if (eventSeq <= previous) throw new IOException("Loot sequence must increase");
        int killMaxHp = (int) integer(e, "max_hp", 1, Integer.MAX_VALUE);
        if (e.has("map") != e.has("position"))
          throw new IOException("Loot position requires its region map");
        if (e.has("position")) {
          var p = e.get("position");
          if (!p.isJsonArray() || p.getAsJsonArray().size() != 3)
            throw new IOException("Invalid loot position");
          var a = p.getAsJsonArray();
          double[] v = new double[3];
          for (int i = 0; i < 3; i++) {
            if (!a.get(i).isJsonPrimitive() || !a.get(i).getAsJsonPrimitive().isNumber())
              throw new IOException("Invalid loot position");
            v[i] = a.get(i).getAsDouble();
          }
          try {
            lootEvents.add(
                new CampaignLoot.Kill(
                    eventSeq,
                    killMaxHp,
                    integer(e, "map", 0, 0xffff_ffffL),
                    new dev.eldencraft.bridge.WorldOrigin.Vec(v[0], v[1], v[2])));
          } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid loot position", invalid);
          }
        } else lootEvents.add(new CampaignLoot.Kill(eventSeq, killMaxHp));
        previous = eventSeq;
      }
    }
    var activeBosses = new ArrayList<Boss>();
    if (j.has("bosses_active")) {
      if (!j.get("bosses_active").isJsonArray() || j.getAsJsonArray("bosses_active").size() > 4)
        throw new IOException("Invalid active boss list");
      var ids = new HashSet<String>();
      for (var value : j.getAsJsonArray("bosses_active")) {
        if (!value.isJsonObject()) throw new IOException("Invalid active boss");
        var b = value.getAsJsonObject();
        String id = text(b, "id", 160), name = text(b, "name", 256);
        double bossHp = number(b, "hp", 0, 10_000_000),
            bossMaxHp = number(b, "max_hp", 1, 10_000_000);
        if (!ids.add(id) || bossHp > bossMaxHp) throw new IOException("Invalid active boss vitals");
        activeBosses.add(new Boss(id, name, bossHp, bossMaxHp));
      }
    }
    var damageEvents = new ArrayList<DamageEvent>();
    if (j.has("damage_events")) {
      if (!j.get("damage_events").isJsonArray()) throw new IOException("Invalid damage events");
      var events = j.getAsJsonArray("damage_events");
      if (events.size() > 64) throw new IOException("Too many damage events");
      long previous = 0;
      for (var event : events) {
        if (!event.isJsonObject()) throw new IOException("Invalid damage event");
        var e = event.getAsJsonObject();
        long eventSeq = integer(e, "seq", 1, Long.MAX_VALUE);
        if (eventSeq <= previous) throw new IOException("Damage sequence must increase");
        damageEvents.add(
            new DamageEvent(eventSeq, number(e, "raw_damage", 0, 1.0e16), bool(e, "blocked")));
        previous = eventSeq;
      }
    }
    return new Snapshot(
        version,
        pid,
        session,
        seq,
        timestamp,
        bool(j, "active"),
        character,
        runes,
        hp,
        maxHp,
        stamina,
        maxStamina,
        defeated,
        merchant,
        ack,
        guardSeq,
        guardDamage,
        healSeq,
        damageEvents,
        dead,
        experienceSeq,
        experienceTotal,
        lootSeq,
        lootEvents,
        activeBosses);
  }

  public static synchronized boolean requestPurchase(
      String id, String merchantToken, String offer, int quantity, long amount) {
    var s = snapshot();
    if (s == null
        || s.merchant() == null
        || !s.merchant().token().equals(merchantToken)
        || quantity < 1
        || quantity > 64
        || amount < 0
        || amount > 999_999_999) return false;
    return publishPurchase(s, id, merchantToken, offer, quantity, amount);
  }

  /** Same-character journal lookup; native checks the UUID before shop-context validity. */
  public static synchronized boolean requestPurchaseRecovery(
      String id, String merchantToken, String offer, int quantity, long amount) {
    var s = snapshot();
    if (s == null || quantity < 1 || quantity > 64 || amount < 0 || amount > 999_999_999)
      return false;
    return publishPurchase(s, id, merchantToken, offer, quantity, amount);
  }

  private static boolean publishPurchase(
      Snapshot s, String id, String merchantToken, String offer, int quantity, long amount) {
    var j = envelope(s);
    j.addProperty("id", id);
    j.addProperty("action", "purchase");
    j.addProperty("merchant", merchantToken);
    j.addProperty("offer", offer);
    j.addProperty("quantity", quantity);
    j.addProperty("amount", amount);
    return write("campaign-guest.json", j);
  }

  public static synchronized boolean closeShop() {
    var s = snapshot();
    if (s == null || s.merchant() == null) return false;
    var j = envelope(s);
    j.addProperty("id", UUID.randomUUID().toString());
    j.addProperty("action", "close_shop");
    j.addProperty("merchant", s.merchant().token());
    return write("campaign-guest.json", j);
  }

  public static synchronized void publishCombat(
      double armor,
      double toughness,
      double guestMaxHp,
      boolean shieldReady,
      double stamina,
      boolean usingItem) {
    var s = snapshot();
    if (s == null) return;
    var j = envelope(s);
    j.addProperty("armor", armor);
    j.addProperty("toughness", toughness);
    j.addProperty("guest_max_hp", guestMaxHp);
    j.addProperty("shield_ready", shieldReady);
    j.addProperty("stamina", stamina);
    j.addProperty("using_item", usingItem);
    j.addProperty("guard_seq", CampaignCombat.guardAcknowledgedSequence());
    j.addProperty("guard_damage", CampaignCombat.guardAcknowledgedDamage());
    j.addProperty("damage_ack", CampaignCombat.damageAcknowledgedSequence());
    var bossHudIds = new JsonArray();
    var bossHud = CampaignBossHud.renderedAcknowledgement(s);
    bossHud.ids().stream().sorted().forEach(bossHudIds::add);
    j.add("boss_hud_ids", bossHudIds);
    j.addProperty("boss_hud_timestamp_ms", bossHud.millis());
    write("campaign-combat.json", j);
    publishHealing(s);
  }

  /** FoodData's actual vanilla heal, recorded once on the integrated-server thread. */
  public static synchronized void recordHealing(
      net.minecraft.server.level.ServerPlayer player, float amount) {
    var s = snapshot();
    if (s == null
        || !CampaignProgression.paired(player, s.character())
        || !Float.isFinite(amount)
        || amount <= 0
        || amount > 10) return;
    prepareHealing(s);
    healingTotal += amount;
    ++healingSequence;
    publishHealing(s);
  }

  private static void prepareHealing(Snapshot s) {
    if (healingSession != s.session() || !healingCharacter.equals(s.character())) {
      healingSession = s.session();
      healingCharacter = s.character();
      healingSequence = 0;
      healingTotal = 0;
    }
  }

  private static void publishHealing(Snapshot s) {
    prepareHealing(s);
    var j = envelope(s);
    // A fresh zero-delta baseline ensures the first genuine food pulse is not
    // confused with queued recovery from a previous attachment.
    j.addProperty("heal_seq", healingSequence);
    j.addProperty("heal_total", healingTotal);
    write("campaign-healing.json", j);
  }

  private static JsonObject envelope(Snapshot s) {
    var j = new JsonObject();
    j.addProperty("version", 1);
    j.addProperty("session", s.session());
    j.addProperty("character", s.character());
    j.addProperty("timestamp_ms", System.currentTimeMillis());
    return j;
  }

  private static boolean write(String name, JsonObject value) {
    try {
      Files.createDirectories(directory());
      Path target = directory.resolve(name), temporary = directory.resolve(name + ".guest.tmp");
      // Force the new bytes before publishing the rename. Native never reads a
      // partially written JSON object, including a crash during the replacement.
      try (var out =
          java.nio.channels.FileChannel.open(
              temporary,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        var bytes = java.nio.ByteBuffer.wrap(value.toString().getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) out.write(bytes);
        out.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (IOException error) {
      LOG.warn("Campaign request not published: {}", error.getMessage());
      return false;
    }
  }

  private static long integer(JsonObject j, String key, long min, long max) throws IOException {
    return JsonWire.integer(j.get(key), min, max);
  }

  private static String text(JsonObject j, String key, int max) throws IOException {
    String s = JsonWire.string(j.get(key));
    if (s.isEmpty() || s.length() > max || s.chars().anyMatch(c -> c < 32))
      throw new IOException("Invalid campaign " + key);
    return s;
  }

  private static double number(JsonObject j, String key, double min, double max)
      throws IOException {
    var value = j.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IOException("Invalid campaign " + key);
    double n = value.getAsDouble();
    if (!Double.isFinite(n) || n < min || n > max)
      throw new IOException("Campaign " + key + " out of range");
    return n;
  }

  private static boolean bool(JsonObject j, String key) throws IOException {
    var value = j.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IOException("Invalid campaign " + key);
    return value.getAsBoolean();
  }

  public static void close() {
    observed = null;
  }
}
