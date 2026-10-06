package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** JSON shop definitions shared by the GUI and server purchase authority. */
public final class CampaignShopCatalog {
  public record Offer(
      String id,
      String item,
      int count,
      long price,
      int stock,
      Set<String> unlockAny,
      Set<String> unlockAll,
      int nativeItemLot,
      String nativeName) {
    public boolean nativeGoods() {
      return nativeItemLot > 0;
    }

    public boolean unlocked(Set<String> defeated) {
      return defeated.containsAll(unlockAll)
          && (unlockAny.isEmpty() || unlockAny.stream().anyMatch(defeated::contains));
    }

    public long amount(int quantity) {
      if (quantity < 1 || quantity > 64) throw new IllegalArgumentException("Purchase quantity");
      if (nativeGoods() && quantity != 1)
        throw new IllegalArgumentException("Native goods are purchased one lot at a time");
      return Math.multiplyExact(price, quantity);
    }
  }

  public record Shop(String id, String title, Set<String> merchantIds, List<Offer> offers) {
    public Offer offer(String id) {
      return offers.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }
  }

  private final List<Shop> shops;

  private CampaignShopCatalog(List<Shop> shops) {
    this.shops = List.copyOf(shops);
  }

  public static CampaignShopCatalog parse(JsonObject config) throws IOException {
    JsonElement raw = config.get("shops");
    if (raw == null) return new CampaignShopCatalog(List.of());
    if (!raw.isJsonArray() || raw.getAsJsonArray().size() > 256)
      throw new IOException("shops must be an array with at most 256 entries");
    List<Shop> shops = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<String> offerIds = new HashSet<>();
    for (JsonElement entry : raw.getAsJsonArray()) {
      if (!entry.isJsonObject()) throw new IOException("Shop object required");
      JsonObject shop = entry.getAsJsonObject();
      String id = identifier(shop, "id");
      if (!ids.add(id)) throw new IOException("Duplicate shop id " + id);
      String title = text(shop, "title", id);
      Set<String> merchants = strings(shop, "merchant_ids");
      if (merchants.isEmpty()) throw new IOException("Shop merchant_ids required");
      JsonArray offers = shop.getAsJsonArray("offers");
      if (offers == null || offers.size() > 256) throw new IOException("Shop offers required");
      List<Offer> parsed = new ArrayList<>();
      for (JsonElement value : offers) {
        if (!value.isJsonObject()) throw new IOException("Offer object required");
        JsonObject offer = value.getAsJsonObject();
        String offerId = identifier(offer, "id");
        if (!offerIds.add(offerId)) throw new IOException("Duplicate offer id " + offerId);
        String item = JsonWire.string(offer.get("item"));
        if (!item.matches("minecraft:[a-z0-9_./-]+"))
          throw new IOException("Shop items must be vanilla Minecraft identifiers");
        parsed.add(
            new Offer(
                offerId,
                item,
                number(offer, "count", 1, 1, 64),
                integer(offer, "price", 0, 0, 999_999_999L),
                number(offer, "stock", -1, -1, 1_000_000),
                strings(offer, "unlock_any"),
                strings(offer, "unlock_all"),
                offer.has("native_item_lot")
                    ? number(offer, "native_item_lot", -1, 1, Integer.MAX_VALUE)
                    : -1,
                text(offer, "native_name", "")));
      }
      shops.add(new Shop(id, title, merchants, List.copyOf(parsed)));
    }
    return new CampaignShopCatalog(shops);
  }

  public Shop forMerchant(String merchant) {
    for (Shop shop : shops) if (shop.merchantIds().contains(merchant)) return shop;
    for (Shop shop : shops) if (shop.merchantIds().contains("*")) return shop;
    return null;
  }

  public Shop byId(String id) {
    return shops.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }

  public List<Shop> shops() {
    return shops;
  }

  private static String identifier(JsonObject object, String key) throws IOException {
    String value = JsonWire.string(object.get(key));
    if (!value.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IOException("Invalid " + key);
    return value;
  }

  private static String text(JsonObject object, String key, String fallback) throws IOException {
    if (!object.has(key)) return fallback;
    String value = JsonWire.string(object.get(key));
    if (value.length() > 100 || value.codePoints().anyMatch(c -> c < 32))
      throw new IOException("Invalid " + key);
    return value;
  }

  private static Set<String> strings(JsonObject object, String key) throws IOException {
    if (!object.has(key)) return Set.of();
    JsonElement value = object.get(key);
    if (!value.isJsonArray() || value.getAsJsonArray().size() > 256)
      throw new IOException("Invalid " + key);
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement item : value.getAsJsonArray()) {
      String name = JsonWire.string(item);
      if (name.length() > 100 || name.isEmpty()) throw new IOException("Invalid " + key);
      result.add(name);
    }
    return Set.copyOf(result);
  }

  private static long integer(JsonObject object, String key, long fallback, long min, long max)
      throws IOException {
    return object.has(key) ? JsonWire.integer(object.get(key), min, max) : fallback;
  }

  private static int number(JsonObject object, String key, int fallback, int min, int max)
      throws IOException {
    return (int) integer(object, key, fallback, min, max);
  }
}
