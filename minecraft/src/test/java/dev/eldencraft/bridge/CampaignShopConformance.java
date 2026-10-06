package dev.eldencraft.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Purchase gates and durable stock/transaction recovery without starting either game. */
public final class CampaignShopConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Shop conformance check " + checks);
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  private static void rejects(Checked action) throws Exception {
    try {
      action.run();
    } catch (IOException | IllegalArgumentException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Unsafe shop operation accepted");
  }

  private static CampaignShopCatalog catalog(String shops) throws IOException {
    return CampaignShopCatalog.parse(
        JsonWire.parse(("{\"shops\":" + shops + "}").getBytes(StandardCharsets.UTF_8)));
  }

  public static void main(String[] args) throws Exception {
    checks += CampaignShopLayoutConformance.verify();
    var catalog =
        catalog(
            """
            [{"id":"general","title":"Merchant","merchant_ids":["*"],"offers":[
              {"id":"arrows","item":"minecraft:arrow","count":16,"price":40,"stock":-1},
              {"id":"iron","item":"minecraft:iron_sword","price":1000,"stock":2,
               "unlock_any":["godrick","rennala"],"unlock_all":["margit"]}]},
             {"id":"special","title":"Special","merchant_ids":["100"],"offers":[]}]
            """);
    var shop = catalog.byId("general");
    var arrow = shop.offer("arrows");
    var iron = shop.offer("iron");
    check(catalog.forMerchant("100").id().equals("special"));
    check(catalog.forMerchant("200").id().equals("general"));
    check(arrow.unlocked(Set.of()));
    check(!iron.unlocked(Set.of("godrick")));
    check(!iron.unlocked(Set.of("margit")));
    check(iron.unlocked(Set.of("margit", "rennala")));
    check(iron.amount(3) == 3000);
    var nativeCatalog =
        catalog(
            """
            [{"id":"keys","merchant_ids":["*"],"offers":[
              {"id":"key","item":"minecraft:tripwire_hook","price":2000,
               "native_item_lot":1234,"native_name":"Stonesword Key","stock":3}]}]
            """);
    var nativeOffer = nativeCatalog.byId("keys").offer("key");
    check(nativeOffer.nativeGoods());
    check(nativeOffer.nativeName().equals("Stonesword Key"));
    check(nativeOffer.amount(1) == 2000);
    rejects(() -> nativeOffer.amount(2));
    rejects(() -> iron.amount(0));
    rejects(() -> iron.amount(65));
    rejects(
        () ->
            catalog(
                "[{\"id\":\"a\",\"merchant_ids\":[\"*\"],\"offers\":["
                    + "{\"id\":\"a\",\"item\":\"other:custom\",\"price\":1}]}]"));
    rejects(
        () ->
            catalog(
                "[{\"id\":\"a\",\"merchant_ids\":[\"*\"],\"offers\":["
                    + "{\"id\":\"a\",\"item\":\"minecraft:arrow\",\"price\":-1}]}]"));
    rejects(
        () ->
            catalog(
                "[{\"id\":\"a\",\"merchant_ids\":[\"*\"],\"offers\":["
                    + "{\"id\":\"a\",\"item\":\"minecraft:arrow\",\"price\":1.5}]}]"));
    rejects(() -> catalog("[{\"id\":\"a\",\"merchant_ids\":[],\"offers\":[]}]"));
    rejects(
        () ->
            catalog(
                """
                [{"id":"a","merchant_ids":["100"],"offers":[
                  {"id":"same","item":"minecraft:arrow","price":100}]},
                 {"id":"b","merchant_ids":["200"],"offers":[
                  {"id":"same","item":"minecraft:bread","price":200}]}]
                """));
    rejects(
        () ->
            catalog(
                """
                [{"id":"a","merchant_ids":["*"],"offers":[
                  {"id":"expensive","item":"minecraft:arrow","price":1000000000}]}]
                """));
    Path directory = Files.createTempDirectory("eldencraft-shop-conformance-");
    try {
      Path file = directory.resolve("ledger.json");
      var ledger = new CampaignShopLedger(file, "native-character-1");
      String id = UUID.randomUUID().toString();
      var intent =
          new CampaignShopLedger.Pending(
              id,
              "merchant-token",
              "200",
              shop.id(),
              iron.id(),
              iron.item(),
              1,
              1,
              1000,
              CampaignShopLedger.Stage.INTENT);
      check(ledger.remaining(shop, iron) == 2);
      ledger.begin(intent);
      check(Files.isRegularFile(file));
      var reopened = new CampaignShopLedger(file, "native-character-1");
      check(reopened.pending().id().equals(id));
      check(reopened.pending().stage() == CampaignShopLedger.Stage.INTENT);
      check(reopened.remaining(shop, iron) == 2); // A request does not consume stock.
      rejects(() -> reopened.begin(intent));
      rejects(() -> reopened.delivered(id)); // No free inventory grant before debit.
      rejects(() -> reopened.debited(UUID.randomUUID().toString()));
      reopened.debited(id);
      var paid = new CampaignShopLedger(file, "native-character-1");
      check(paid.pending().stage() == CampaignShopLedger.Stage.DEBITED);
      rejects(() -> paid.rejected(id)); // Never discard a paid purchase.
      paid.delivered(id);
      var completed = new CampaignShopLedger(file, "native-character-1");
      check(completed.pending() == null);
      check(completed.remaining(shop, iron) == 1);
      String nativeId = UUID.randomUUID().toString();
      completed.begin(
          new CampaignShopLedger.Pending(
              nativeId,
              "token3",
              "200",
              "keys",
              "key",
              "minecraft:tripwire_hook",
              1,
              1,
              2000,
              CampaignShopLedger.Stage.INTENT,
              1234));
      completed.debited(nativeId);
      var nativePaid = new CampaignShopLedger(file, "native-character-1");
      check(nativePaid.pending().nativeItemLot() == 1234);
      nativePaid.delivered(nativeId);
      check(nativePaid.remaining(nativeCatalog.byId("keys"), nativeOffer) == 2);
      check(completed.remaining(shop, arrow) == -1);
      rejects(() -> completed.delivered(id)); // Replay does not consume stock again.
      rejects(() -> new CampaignShopLedger(file, "another-native-character"));
      String rejectedId = UUID.randomUUID().toString();
      nativePaid.begin(
          new CampaignShopLedger.Pending(
              rejectedId,
              "token2",
              "200",
              shop.id(),
              arrow.id(),
              arrow.item(),
              16,
              2,
              80,
              CampaignShopLedger.Stage.INTENT));
      nativePaid.rejected(rejectedId);
      check(new CampaignShopLedger(file, "native-character-1").pending() == null);
      check(completed.remaining(shop, iron) == 1);
      Files.writeString(
          file,
          "{\"version\":1,\"character\":\"native-character-1\","
              + "\"spent\":{\"general/iron\":-1}}");
      rejects(() -> new CampaignShopLedger(file, "native-character-1"));
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    System.out.println("Campaign shop conformance: " + checks + " checks passed");
  }
}
