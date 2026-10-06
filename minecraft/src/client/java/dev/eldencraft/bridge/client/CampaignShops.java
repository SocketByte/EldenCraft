package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;

/** Custom merchant screens; all inventory and stock mutations run on the integrated server. */
public final class CampaignShops {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_campaign");
  private static final ConcurrentLinkedQueue<Request> REQUESTS = new ConcurrentLinkedQueue<>();
  private static volatile View view;
  private static volatile String status = "";
  private static String lastError = "";
  private static CampaignConfig parsedConfig;
  private static CampaignShopCatalog catalog;
  private static CampaignShopLedger ledger;
  private static MinecraftServer ledgerServer;
  private static String ledgerCharacter;
  private static UUID ledgerPlayer;
  private static long nextSend, nextSave;
  private static String dismissedToken = "";

  private record Request(String character, String token, String offer, int quantity) {}

  public record View(
      String character,
      String token,
      String merchantName,
      CampaignShopCatalog.Shop shop,
      long runes,
      Set<String> defeated,
      Map<String, Integer> remaining,
      boolean pending,
      String status) {
    public View {
      defeated = Set.copyOf(defeated);
      remaining = Map.copyOf(remaining);
    }

    public int remaining(CampaignShopCatalog.Offer offer) {
      return remaining.getOrDefault(offer.id(), offer.stock());
    }
  }

  private CampaignShops() {}

  public static void initialize() {}

  public static View view() {
    return view;
  }

  public static String status() {
    return status;
  }

  static void buy(String token, String offer, int quantity) {
    var s = CampaignBridge.snapshot();
    if (s != null
        && s.merchant() != null
        && s.merchant().token().equals(token)
        && REQUESTS.isEmpty()
        && quantity >= 1
        && quantity <= 64) REQUESTS.add(new Request(s.character(), token, offer, quantity));
  }

  static void dismiss(String token) {
    dismissedToken = token;
    var s = CampaignBridge.snapshot();
    if (s != null && s.merchant() != null && token.equals(s.merchant().token()))
      CampaignBridge.closeShop();
  }

  public static void clientTick(Minecraft client) {
    var s = CampaignBridge.snapshot();
    if (!CampaignConfig.current().enabled()
        || s == null
        || client.player == null
        || client.getSingleplayerServer() == null
        || s.merchant() == null) {
      if (client.gui.screen() instanceof CampaignShopScreen) client.gui.setScreen(null);
      if (s == null || s.merchant() == null) dismissedToken = "";
      return;
    }
    if (client.gui.screen() instanceof CampaignShopScreen screen) {
      if (!screen.token().equals(s.merchant().token()) || !screen.character().equals(s.character()))
        client.gui.setScreen(null);
      else if (view != null && !screen.catalogMatches(view.shop()))
        client.gui.setScreen(new CampaignShopScreen(view));
      return;
    }
    View v = view;
    if (client.gui.screen() == null
        && !dismissedToken.equals(s.merchant().token())
        && v != null
        && v.character().equals(s.character())
        && v.token().equals(s.merchant().token())) client.gui.setScreen(new CampaignShopScreen(v));
  }

  public static void serverTick(ServerPlayer player) {
    var s = CampaignBridge.snapshot();
    if (!CampaignConfig.current().enabled()
        || s == null
        || player.isRemoved()
        || !player.gameMode.isSurvival()
        || !player.level().getServer().isSingleplayer()
        || player.level().getServer().isPublished()
        || player.level().getServer().getPlayerCount() != 1
        || !CampaignProgression.paired(player, s.character())
        || !player.level().dimension().equals(SharedWorldBlocks.DIMENSION)) {
      view = null;
      return;
    }
    try {
      CampaignConfig config = CampaignConfig.current();
      if (parsedConfig != config) {
        catalog = CampaignShopCatalog.parse(config.raw());
        parsedConfig = config;
      }
      MinecraftServer server = player.level().getServer();
      if (ledger == null
          || ledgerServer != server
          || !s.character().equals(ledgerCharacter)
          || !player.getUUID().equals(ledgerPlayer)) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        ledger =
            new CampaignShopLedger(
                root.resolve(
                    "data/eldencraft-shop-"
                        + hash(s.character())
                        + "-"
                        + player.getUUID()
                        + ".json"),
                s.character());
        ledgerServer = server;
        ledgerCharacter = s.character();
        ledgerPlayer = player.getUUID();
        REQUESTS.clear();
        nextSend = nextSave = 0;
        status = "";
      }
      recover(player, s);
      Request request;
      while ((request = REQUESTS.poll()) != null) {
        if (ledger.pending() != null) {
          status = "A purchase is still pending.";
          continue;
        }
        if (!request.character().equals(s.character())
            || s.merchant() == null
            || !request.token().equals(s.merchant().token())) {
          status = "Merchant context changed. Open Purchase again.";
          continue;
        }
        var shop = catalog.forMerchant(s.merchant().id());
        var offer = shop == null ? null : shop.offer(request.offer());
        if (offer == null || !offer.unlocked(s.defeated())) {
          status = "This item is locked.";
          continue;
        }
        int remaining = ledger.remaining(shop, offer);
        long amount = offer.amount(request.quantity());
        int count = offer.count() * request.quantity();
        Item item = item(offer.item());
        if (item == Items.AIR
            || amount > 999_999_999L
            || amount > s.runes()
            || (remaining != -1 && remaining < request.quantity())) {
          status = "Not enough runes or stock.";
          continue;
        }
        if (!offer.nativeGoods() && !fits(player, item, count)) {
          status = "Make room in your inventory first.";
          continue;
        }
        ledger.begin(
            new CampaignShopLedger.Pending(
                UUID.randomUUID().toString(),
                request.token(),
                s.merchant().id(),
                shop.id(),
                offer.id(),
                offer.item(),
                offer.count(),
                request.quantity(),
                amount,
                CampaignShopLedger.Stage.INTENT,
                offer.nativeItemLot()));
        status = "Saving rune purchase...";
        nextSend = 0;
        recover(player, s);
      }
      var merchant = s.merchant();
      var shop = merchant == null ? null : catalog.forMerchant(merchant.id());
      if (shop == null) {
        view = null;
        return;
      }
      var remaining = new LinkedHashMap<String, Integer>();
      for (var offer : shop.offers()) remaining.put(offer.id(), ledger.remaining(shop, offer));
      view =
          new View(
              s.character(),
              merchant.token(),
              merchant.name(),
              shop,
              s.runes(),
              s.defeated(),
              remaining,
              ledger.pending() != null,
              status);
      lastError = "";
    } catch (IOException | RuntimeException error) {
      view = null;
      status = "Purchase paused: " + error.getMessage();
      if (!lastError.equals(status)) {
        lastError = status;
        LOG.warn("Campaign shop unavailable: {}", error.getMessage());
      }
    }
  }

  private static void recover(ServerPlayer player, CampaignBridge.Snapshot s) throws IOException {
    var p = ledger.pending();
    if (p == null) return;
    long now = System.currentTimeMillis();
    if (p.stage() == CampaignShopLedger.Stage.INTENT) {
      var ack = s.ack();
      if (ack != null && ack.id().equals(p.id())) {
        switch (ack.status()) {
          case "debited" -> {
            if (ack.amount() != p.amount()) throw new IOException("Rune debit amount mismatch");
            ledger.debited(p.id());
            p = ledger.pending();
            status = "Delivering purchase...";
          }
          case "rejected" -> {
            ledger.rejected(p.id());
            status = "Purchase declined. No item was delivered.";
            return;
          }
          case "uncertain" -> {
            status = "Native save could not confirm this purchase. It is held for recovery.";
            return;
          }
          default -> {}
        }
      }
      if (p.stage() == CampaignShopLedger.Stage.INTENT) {
        if (now >= nextSend) {
          CampaignBridge.requestPurchaseRecovery(
              p.id(), p.merchantToken(), p.offerId(), p.quantity(), p.amount());
          nextSend = now + 1000;
        }
        return;
      }
    }
    if (!(player instanceof CampaignShopReceipt receipt))
      throw new IOException("Player purchase receipt persistence is unavailable");
    if (!receipt.eldencraft$shopReceipt().equals(p.id())) {
      if (p.nativeItemLot() > 0) {
        // Native keys are already in the native save that confirmed the debit.
        // The Minecraft icon is display only; no substitute key/token is granted.
        receipt.eldencraft$shopReceipt(p.id());
      } else {
        Item item = item(p.item());
        int count = p.count() * p.quantity();
        if (item == Items.AIR) throw new IOException("Purchased item is unavailable");
        if (!fits(player, item, count)) {
          status = "Purchase paid. Make room in your inventory to receive it.";
          return;
        }
        var inventory = player.getInventory();
        List<ItemStack> before = new ArrayList<>();
        for (var stack : inventory.getNonEquipmentItems()) before.add(stack.copy());
        int left = count;
        while (left > 0) {
          ItemStack stack =
              new ItemStack(item, Math.min(left, item.getDefaultInstance().getMaxStackSize()));
          CampaignCombat.tune(stack);
          int delivered = stack.getCount();
          if (!inventory.add(stack) || !stack.isEmpty()) {
            for (int i = 0; i < before.size(); i++) inventory.setItem(i, before.get(i));
            throw new IOException("Inventory changed during delivery");
          }
          left -= delivered;
        }
        receipt.eldencraft$shopReceipt(p.id());
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
      }
    }
    if (now < nextSave) return;
    nextSave = now + 2000;
    // Both the inventory and its receipt must be durable before stock is committed.
    // On a restart the receipt loaded with inventory makes this operation idempotent.
    ledgerServer.saveEverything(true, true, false);
    verifySavedReceipt(ledgerServer, player, p.id());
    ledger.delivered(p.id());
    status =
        p.nativeItemLot() > 0
            ? "Native item purchase saved."
            : "Purchased " + (p.count() * p.quantity()) + " item(s).";
  }

  static boolean fits(ServerPlayer player, Item item, int count) {
    ItemStack sample = item.getDefaultInstance();
    CampaignCombat.tune(sample);
    int capacity = 0;
    for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
      if (stack.isEmpty()) capacity += sample.getMaxStackSize();
      else if (ItemStack.isSameItemSameComponents(stack, sample))
        capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
      if (capacity >= count) return true;
    }
    return false;
  }

  static Item item(String id) {
    Identifier key = Identifier.tryParse(id);
    return key == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
  }

  private static void verifySavedReceipt(MinecraftServer server, ServerPlayer player, String id)
      throws IOException {
    Path root = server.getWorldPath(LevelResource.ROOT);
    Path data = root.resolve("playerdata/" + player.getUUID() + ".dat");
    if (!Files.isRegularFile(data)) throw new IOException("Player inventory save is missing");
    var tag = NbtIo.readCompressed(data, NbtAccounter.create(16 * 1024 * 1024));
    if (!id.equals(tag.getStringOr("EldenCraftShopReceipt", "")))
      throw new IOException("Player inventory save is not confirmed");
    Path level = root.resolve("level.dat");
    if (Files.isRegularFile(level)) {
      var world = NbtIo.readCompressed(level, NbtAccounter.create(32 * 1024 * 1024));
      var embedded = world.getCompoundOrEmpty("Data").getCompound("Player");
      if (embedded.isPresent()
          && !id.equals(embedded.get().getStringOr("EldenCraftShopReceipt", "")))
        throw new IOException("Singleplayer inventory save is not confirmed");
    }
  }

  private static String hash(String text) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes).substring(0, 24);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static void close() {
    REQUESTS.clear();
    view = null;
    ledger = null;
    ledgerServer = null;
    parsedConfig = null;
    dismissedToken = "";
    status = "";
    lastError = "";
  }
}
