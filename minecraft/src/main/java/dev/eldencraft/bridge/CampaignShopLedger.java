package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One durable purchase in flight per paired native character. No wallet is mirrored here. */
public final class CampaignShopLedger {
  public enum Stage {
    INTENT,
    DEBITED
  }

  public record Pending(
      String id,
      String merchantToken,
      String merchantId,
      String shopId,
      String offerId,
      String item,
      int count,
      int quantity,
      long amount,
      Stage stage,
      int nativeItemLot,
      String potion) {
    public Pending(
        String id,
        String merchantToken,
        String merchantId,
        String shopId,
        String offerId,
        String item,
        int count,
        int quantity,
        long amount,
        Stage stage,
        int nativeItemLot) {
      this(
          id,
          merchantToken,
          merchantId,
          shopId,
          offerId,
          item,
          count,
          quantity,
          amount,
          stage,
          nativeItemLot,
          "");
    }

    public Pending(
        String id,
        String merchantToken,
        String merchantId,
        String shopId,
        String offerId,
        String item,
        int count,
        int quantity,
        long amount,
        Stage stage) {
      this(
          id, merchantToken, merchantId, shopId, offerId, item, count, quantity, amount, stage, -1);
    }

    public Pending debited() {
      return new Pending(
          id,
          merchantToken,
          merchantId,
          shopId,
          offerId,
          item,
          count,
          quantity,
          amount,
          Stage.DEBITED,
          nativeItemLot,
          potion);
    }
  }

  private final Path file;
  private final String character;
  private final Map<String, Integer> spent = new LinkedHashMap<>();
  private Pending pending;

  public CampaignShopLedger(Path file, String character) throws IOException {
    this.file = file;
    this.character = character;
    if (Files.exists(file)) load();
  }

  public Pending pending() {
    return pending;
  }

  private static String key(String shop, String offer) {
    return shop + "/" + offer;
  }

  public int remaining(CampaignShopCatalog.Shop shop, CampaignShopCatalog.Offer offer) {
    return offer.stock() == -1
        ? -1
        : Math.max(0, offer.stock() - spent.getOrDefault(key(shop.id(), offer.id()), 0));
  }

  public void begin(Pending intent) throws IOException {
    if (pending != null || intent.stage() != Stage.INTENT)
      throw new IOException("A purchase is already pending");
    validate(intent);
    pending = intent;
    try {
      save();
    } catch (IOException e) {
      pending = null;
      throw e;
    }
  }

  public void debited(String id) throws IOException {
    if (pending == null || !pending.id().equals(id)) throw new IOException("Unknown purchase");
    Pending old = pending;
    pending = old.debited();
    try {
      save();
    } catch (IOException e) {
      pending = old;
      throw e;
    }
  }

  public void rejected(String id) throws IOException {
    if (pending == null || !pending.id().equals(id) || pending.stage() != Stage.INTENT)
      throw new IOException("Cannot discard a debited purchase");
    Pending old = pending;
    pending = null;
    try {
      save();
    } catch (IOException e) {
      pending = old;
      throw e;
    }
  }

  /** Only call after inventory and its receipt are saved successfully. */
  public void delivered(String id) throws IOException {
    if (pending == null || !pending.id().equals(id) || pending.stage() != Stage.DEBITED)
      throw new IOException("Purchase has not been debited");
    Pending old = pending;
    String key = key(old.shopId(), old.offerId());
    int before = spent.getOrDefault(key, 0);
    int after = Math.addExact(before, old.quantity());
    spent.put(key, after);
    pending = null;
    try {
      save();
    } catch (IOException e) {
      pending = old;
      if (before == 0) spent.remove(key);
      else spent.put(key, before);
      throw e;
    }
  }

  private static void validate(Pending p) throws IOException {
    CampaignItems.validate(p.item(), p.potion());
    try {
      UUID.fromString(p.id());
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid purchase UUID", e);
    }
    if (p.merchantToken().isEmpty()
        || p.merchantToken().length() > 160
        || p.merchantId().isEmpty()
        || p.merchantId().length() > 100
        || !p.shopId().matches("[a-zA-Z0-9_.-]{1,80}")
        || !p.offerId().matches("[a-zA-Z0-9_.-]{1,80}")
        || !p.item().matches("minecraft:[a-z0-9_./-]+")
        || p.count() < 1
        || p.count() > 64
        || p.quantity() < 1
        || p.quantity() > 64
        || p.count() * p.quantity() > 4096
        || p.amount() < 0
        || p.amount() > 2_147_483_647L
        || p.nativeItemLot() == 0
        || p.nativeItemLot() < -1
        || (p.nativeItemLot() > 0 && (p.quantity() != 1 || !p.potion().isEmpty())))
      throw new IOException("Invalid pending purchase");
  }

  private void load() throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(file)) {
      bytes = input.readNBytes(131073);
    }
    JsonObject data = JsonWire.parse(bytes);
    if (JsonWire.integer(data.get("version"), 1, 1) != 1
        || !character.equals(JsonWire.string(data.get("character"))))
      throw new IOException("Shop ledger belongs to another character");
    JsonObject entries = data.getAsJsonObject("spent");
    if (entries == null || entries.size() > 4096) throw new IOException("Shop ledger stock");
    for (var entry : entries.entrySet()) {
      if (!entry.getKey().matches("[a-zA-Z0-9_.-]{1,80}/[a-zA-Z0-9_.-]{1,80}"))
        throw new IOException("Invalid stock key");
      spent.put(entry.getKey(), (int) JsonWire.integer(entry.getValue(), 0, 1_000_000_000));
    }
    if (data.has("pending") && !data.get("pending").isJsonNull()) {
      JsonObject p = data.getAsJsonObject("pending");
      try {
        pending =
            new Pending(
                JsonWire.string(p.get("id")),
                JsonWire.string(p.get("merchant_token")),
                JsonWire.string(p.get("merchant_id")),
                JsonWire.string(p.get("shop_id")),
                JsonWire.string(p.get("offer_id")),
                JsonWire.string(p.get("item")),
                (int) JsonWire.integer(p.get("count"), 1, 64),
                (int) JsonWire.integer(p.get("quantity"), 1, 64),
                JsonWire.integer(p.get("amount"), 0, 2_147_483_647L),
                Stage.valueOf(JsonWire.string(p.get("stage"))),
                p.has("native_item_lot")
                    ? (int) JsonWire.integer(p.get("native_item_lot"), 1, Integer.MAX_VALUE)
                    : -1,
                p.has("potion") ? JsonWire.string(p.get("potion")) : "");
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid purchase stage", e);
      }
      validate(pending);
    }
  }

  private void save() throws IOException {
    JsonObject data = new JsonObject();
    data.addProperty("version", 1);
    data.addProperty("character", character);
    JsonObject entries = new JsonObject();
    spent.forEach(entries::addProperty);
    data.add("spent", entries);
    if (pending != null) {
      JsonObject p = new JsonObject();
      p.addProperty("id", pending.id());
      p.addProperty("merchant_token", pending.merchantToken());
      p.addProperty("merchant_id", pending.merchantId());
      p.addProperty("shop_id", pending.shopId());
      p.addProperty("offer_id", pending.offerId());
      p.addProperty("item", pending.item());
      if (!pending.potion().isEmpty()) p.addProperty("potion", pending.potion());
      p.addProperty("count", pending.count());
      p.addProperty("quantity", pending.quantity());
      p.addProperty("amount", pending.amount());
      p.addProperty("stage", pending.stage().name());
      if (pending.nativeItemLot() > 0) p.addProperty("native_item_lot", pending.nativeItemLot());
      data.add("pending", p);
    }
    byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 131072) throw new IOException("Shop ledger capacity exceeded");
    Files.createDirectories(file.getParent());
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    try {
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
