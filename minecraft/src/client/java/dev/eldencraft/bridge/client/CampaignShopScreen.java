package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Vanilla items and authoritative purchases, presented as a compact pixel merchant inventory. */
public final class CampaignShopScreen extends Screen {
  private static final int TEXT = 0xffeee7d7, MUTED = 0xffb0aa9c, GOLD = 0xffe6c47a;
  private static final int BORDER = 0xff806c45, PANEL = 0xff161a1a, CARD = 0xff202625;
  private static final int SELECTED = 0xff343b2b, WARNING = 0xffe9a07d;
  private final String token, character;
  private final CampaignShopCatalog.Shop shop;
  private CampaignShopLayout layout;
  private int page, selected, priceColumnWidth;
  private Button buyOne, buyStack, previous, next;
  private final List<OfferButton> offerButtons = new ArrayList<>();

  CampaignShopScreen(CampaignShops.View view) {
    super(
        Component.literal(
            view.merchantName().isBlank() ? view.shop().title() : view.merchantName()));
    token = view.token();
    character = view.character();
    shop = view.shop();
  }

  public String token() {
    return token;
  }

  public String character() {
    return character;
  }

  public boolean catalogMatches(CampaignShopCatalog.Shop current) {
    return shop.equals(current);
  }

  @Override
  protected void init() {
    offerButtons.clear();
    if (!CampaignShopLayout.usable(width, height)) {
      layout = null;
      if (width >= 80 && height >= 65)
        addRenderableWidget(
            new ShopButton((width - 72) / 2, height / 2 + 18, 72, 18, "Close", b -> onClose()));
      return;
    }
    layout = CampaignShopLayout.create(width, height);
    priceColumnWidth =
        shop.offers().stream().mapToInt(offer -> font.width(number(offer.price()))).max().orElse(0);
    selected = Math.clamp(selected, 0, Math.max(0, shop.offers().size() - 1));
    page = selected / layout.rows();
    for (int i = 0; i < layout.rows(); i++) {
      int row = i;
      offerButtons.add(
          addRenderableWidget(
              new OfferButton(
                  layout.row(i),
                  row,
                  b -> {
                    selected = page * layout.rows() + row;
                    updateButtons();
                  })));
    }
    previous =
        addRenderableWidget(new ShopButton(layout.pageButton(false), "<", b -> changePage(-1)));
    next = addRenderableWidget(new ShopButton(layout.pageButton(true), ">", b -> changePage(1)));
    buyOne = addRenderableWidget(new ShopButton(layout.purchaseButton(false), "Buy", b -> buy(1)));
    buyStack =
        addRenderableWidget(
            new ShopButton(
                layout.purchaseButton(true),
                "Bulk",
                b -> {
                  var offer = selectedOffer();
                  var view = currentView();
                  if (offer != null && view != null) buy(bulkQuantity(offer, view));
                }));
    addRenderableWidget(new ShopButton(layout.closeButton(), "X", b -> onClose()))
        .setTooltip(Tooltip.create(Component.literal("Close shop")));
    updateButtons();
  }

  private CampaignShops.View currentView() {
    var view = CampaignShops.view();
    return view != null && token.equals(view.token()) && character.equals(view.character())
        ? view
        : null;
  }

  private CampaignShopCatalog.Offer selectedOffer() {
    return selected >= 0 && selected < shop.offers().size() ? shop.offers().get(selected) : null;
  }

  private int pages() {
    return Math.max(1, (shop.offers().size() + layout.rows() - 1) / layout.rows());
  }

  private void changePage(int delta) {
    int changed = Math.clamp(page + delta, 0, pages() - 1);
    if (changed != page) {
      page = changed;
      selected = page * layout.rows();
      updateButtons();
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
    if (layout != null && layout.catalog().contains(mouseX, mouseY) && vertical != 0) {
      changePage(vertical > 0 ? -1 : 1);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
  }

  private void buy(int quantity) {
    var offer = selectedOffer();
    var view = currentView();
    if (offer == null || view == null) return;
    int remaining = view.remaining(offer);
    if (remaining >= 0) quantity = Math.min(quantity, remaining);
    if (offer.price() > 0) quantity = (int) Math.min(quantity, view.runes() / offer.price());
    if (quantity > 0) CampaignShops.buy(token, offer.id(), quantity);
  }

  private int bulkQuantity(CampaignShopCatalog.Offer offer, CampaignShops.View view) {
    return CampaignShopLayout.bulkQuantity(
        CampaignItems.stack(offer.item(), 1, offer.potion()).getMaxStackSize(),
        offer.count(),
        view.remaining(offer),
        view.runes(),
        offer.price(),
        offer.nativeGoods());
  }

  private static String number(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  private static String name(CampaignShopCatalog.Offer offer) {
    return offer.nativeGoods() && !offer.nativeName().isBlank()
        ? offer.nativeName()
        : CampaignItems.stack(offer.item(), 1, offer.potion()).getHoverName().getString();
  }

  private String disabledReason(CampaignShopCatalog.Offer offer, CampaignShops.View view) {
    if (view == null) return "Waiting for the merchant.";
    if (view.pending()) return "Finishing your current purchase...";
    if (offer == null) return "Select an item to purchase.";
    if (!offer.unlocked(view.defeated())) return "Defeat " + gates(offer) + " to unlock this item.";
    if (view.remaining(offer) == 0) return "This item is sold out.";
    if (view.runes() < offer.price())
      return "You need " + number(offer.price() - view.runes()) + " more runes.";
    return "";
  }

  private static String gates(CampaignShopCatalog.Offer offer) {
    var all = offer.unlockAll().stream().sorted().map(id -> id.replace('_', ' ')).toList();
    var any = offer.unlockAny().stream().sorted().map(id -> id.replace('_', ' ')).toList();
    String required = String.join(" + ", all), alternative = String.join(" or ", any);
    return required.isBlank()
        ? alternative
        : alternative.isBlank() ? required : required + " + (" + alternative + ")";
  }

  private void updateButtons() {
    if (layout == null) return;
    var view = currentView();
    for (int i = 0; i < offerButtons.size(); i++) {
      int index = page * layout.rows() + i;
      var button = offerButtons.get(i);
      button.visible = index < shop.offers().size();
      button.active = view != null;
      if (!button.visible) continue;
      var offer = shop.offers().get(index);
      button.setMessage(Component.literal(name(offer) + ", " + number(offer.price()) + " runes"));
    }
    previous.active = page > 0;
    next.active = page < pages() - 1;
    var offer = selectedOffer();
    String reason = disabledReason(offer, view);
    boolean canBuy = reason.isEmpty();
    buyOne.active = canBuy;
    buyOne.setMessage(Component.literal(offer == null ? "Buy" : "Buy " + offer.count()));
    buyOne.setTooltip(
        Tooltip.create(
            Component.literal(
                canBuy
                    ? "Purchase "
                        + offer.count()
                        + " item(s) for "
                        + number(offer.price())
                        + " runes."
                    : reason)));
    int quantity = offer == null || view == null ? 0 : bulkQuantity(offer, view);
    buyStack.active = canBuy && offer != null && !offer.nativeGoods() && quantity > 1;
    buyStack.setMessage(
        Component.literal(quantity > 1 ? "Buy " + quantity * offer.count() : "Bulk"));
    String bulkReason =
        !canBuy
            ? reason
            : offer.nativeGoods()
                ? "Native items are purchased one lot at a time."
                : quantity <= 1
                    ? "Stock, runes or item size allow only one bundle."
                    : "Purchase "
                        + quantity * offer.count()
                        + " item(s) for "
                        + number(offer.price() * quantity)
                        + " runes.";
    buyStack.setTooltip(Tooltip.create(Component.literal(bulkReason)));
  }

  @Override
  public void tick() {
    updateButtons();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean isInGameUi() {
    return true;
  }

  @Override
  public void onClose() {
    CampaignShops.dismiss(token);
    minecraft.gui.setScreen(null);
  }

  private String clipped(String text, int maximum) {
    if (maximum <= 0) return "";
    if (font.width(text) <= maximum) return text;
    String suffix = font.width("...") <= maximum ? "..." : font.width(".") <= maximum ? "." : "";
    return font.plainSubstrByWidth(text, maximum - font.width(suffix)) + suffix;
  }

  private void text(GuiGraphicsExtractor gui, String text, int x, int y, int width, int color) {
    gui.text(font, clipped(text, width), x, y, color, true);
  }

  private static void frame(
      GuiGraphicsExtractor gui, CampaignShopLayout.Rect box, int background, int border) {
    gui.fill(box.x(), box.y(), box.right(), box.bottom(), background);
    gui.outline(box.x(), box.y(), box.width(), box.height(), border);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
    if (layout == null) {
      gui.fill(0, 0, width, height, 0xcc101515);
      String message = clipped("Resize the window to view the shop.", Math.max(0, width - 16));
      String hint = clipped("Or reduce the GUI scale. Escape closes.", Math.max(0, width - 16));
      gui.text(
          font,
          message,
          Math.max(0, (width - font.width(message)) / 2),
          Math.max(0, height / 2 - 12),
          TEXT,
          true);
      gui.text(
          font,
          hint,
          Math.max(0, (width - font.width(hint)) / 2),
          Math.max(0, height / 2),
          MUTED,
          true);
      super.extractRenderState(gui, mouseX, mouseY, partialTick);
      return;
    }
    var panel = layout.panel();
    var view = currentView();
    gui.fill(0, 0, width, height, 0x99060708);
    gui.fill(panel.x() - 2, panel.y() - 2, panel.right() + 2, panel.bottom() + 2, 0xdd080b0b);
    frame(gui, panel, PANEL, BORDER);
    gui.fillGradient(
        panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 32, 0xff2b302a, PANEL);
    gui.horizontalLine(panel.x() + 8, panel.right() - 9, panel.y() + 33, 0xff514a36);
    var titleBox = layout.title();
    text(
        gui,
        title.getString(),
        titleBox.x(),
        titleBox.centeredTextY(font.lineHeight, 1),
        titleBox.width(),
        TEXT);
    if (titleBox.contains(mouseX, mouseY) && font.width(title.getString()) > titleBox.width())
      gui.setTooltipForNextFrame(title, mouseX, mouseY);
    var walletBox = layout.wallet();
    int headerY = walletBox.centeredTextY(font.lineHeight, 1);
    text(gui, "MERCHANDISE", layout.catalog().x(), headerY, layout.catalog().width(), MUTED);
    gui.text(font, "RUNES", walletBox.x(), headerY, MUTED, true);
    String wallet = view == null ? "--" : number(view.runes());
    String visibleWallet = clipped(wallet, walletBox.width() - font.width("RUNES") - 6);
    gui.text(
        font, visibleWallet, walletBox.right() - font.width(visibleWallet), headerY, GOLD, true);
    if (walletBox.contains(mouseX, mouseY))
      gui.setTooltipForNextFrame(Component.literal("Runes: " + wallet), mouseX, mouseY);
    frame(gui, layout.details(), 0xff1c2220, 0xff4f4b3b);
    drawDetails(gui, view, mouseX, mouseY);
    var feedback = layout.feedback();
    frame(gui, feedback, 0xff101716, 0xff353e36);
    var offer = selectedOffer();
    String reason = disabledReason(offer, view);
    String status =
        view != null && !view.status().isBlank()
            ? view.status()
            : reason.isBlank() ? "Select an item. Purchases use your runes." : reason;
    int feedbackColor = view != null && view.pending() ? GOLD : reason.isBlank() ? MUTED : WARNING;
    text(
        gui,
        status,
        feedback.x() + 6,
        feedback.centeredTextY(font.lineHeight, 1),
        feedback.width() - 12,
        feedbackColor);
    if (feedback.contains(mouseX, mouseY) && !status.isBlank())
      gui.setTooltipForNextFrame(Component.literal(status), mouseX, mouseY);
    drawInventory(gui, mouseX, mouseY);
    super.extractRenderState(gui, mouseX, mouseY, partialTick);
    var catalog = layout.catalog();
    var pageBox = layout.pageLabel();
    String position = (page + 1) + " / " + pages();
    gui.text(
        font,
        position,
        pageBox.x() + (pageBox.width() - font.width(position)) / 2,
        pageBox.centeredTextY(font.lineHeight, 1),
        MUTED,
        true);
    if (shop.offers().isEmpty())
      text(
          gui, "No goods available", catalog.x() + 5, catalog.y() + 8, catalog.width() - 10, MUTED);
  }

  private void drawDetails(
      GuiGraphicsExtractor gui, CampaignShops.View view, int mouseX, int mouseY) {
    var box = layout.details();
    var offer = selectedOffer();
    if (offer == null) return;
    ItemStack stack = CampaignItems.stack(offer.item(), offer.count(), offer.potion());
    text(
        gui,
        name(offer),
        box.x() + 8,
        layout.row(0).centeredTextY(font.lineHeight, 1),
        box.width() - 16,
        TEXT);
    var icon = layout.detailIcon();
    frame(gui, icon, 0xff101515, BORDER);
    gui.item(stack, icon.x() + 2, icon.y() + 2);
    gui.itemDecorations(font, stack, icon.x() + 2, icon.y() + 2);
    int infoX = icon.right() + 8, infoWidth = box.right() - 8 - infoX;
    int infoY = icon.centeredTextY(font.lineHeight, 2) + 1;
    String price = number(offer.price());
    if (font.width(price + " runes") <= infoWidth) price += " runes";
    text(gui, price, infoX, infoY, infoWidth, GOLD);
    int stock = view == null ? offer.stock() : view.remaining(offer);
    text(
        gui,
        "Stock: " + (stock < 0 ? "unlimited" : stock),
        infoX,
        infoY + font.lineHeight + 1,
        infoWidth,
        MUTED);
    int statsY = icon.bottom() + 8;
    if (statsY + font.lineHeight * 2 + 3 <= layout.purchaseButton(false).y() - 5) {
      int y = statsY;
      if (offer.nativeGoods())
        text(gui, "Elden Ring inventory", box.x() + 8, y, box.width() - 16, MUTED);
      else {
        var weapon = CampaignConfig.current().weapons.get(offer.item());
        var armor = CampaignConfig.current().armors.get(offer.item());
        if (weapon != null) {
          text(gui, "Damage: " + weapon.damage(), box.x() + 8, y, box.width() - 16, TEXT);
          text(
              gui,
              "Attack speed: " + weapon.attackSpeed(),
              box.x() + 8,
              y + font.lineHeight + 3,
              box.width() - 16,
              MUTED);
        } else if (armor != null) {
          text(gui, armor.reductionLabel(), box.x() + 8, y, box.width() - 16, TEXT);
          text(
              gui,
              "Stacks with other equipped armor",
              box.x() + 8,
              y + font.lineHeight + 3,
              box.width() - 16,
              MUTED);
        } else
          text(
              gui,
              "Receive " + offer.count() + " item(s)",
              box.x() + 8,
              y,
              box.width() - 16,
              MUTED);
      }
    }
    if (mouseX >= box.x() + 8
        && mouseX < box.right() - 8
        && mouseY >= box.y() + 4
        && mouseY < icon.bottom()) offerTooltip(gui, offer, view, mouseX, mouseY);
  }

  private void drawInventory(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
    var inventory = layout.inventory();
    gui.text(font, "INVENTORY", inventory.x(), inventory.y() - 12, MUTED, true);
    gui.horizontalLine(
        layout.panel().x() + 8, layout.panel().right() - 9, inventory.y() - 16, 0xff353e36);
    if (minecraft.player == null) return;
    for (int index = 0; index < 36; index++) {
      var slot = layout.inventorySlot(index);
      var icon = layout.inventoryIcon(index);
      frame(gui, slot, index < 9 ? 0xff242c29 : 0xff111818, index < 9 ? 0xff665c40 : 0xff424b45);
      var stack = minecraft.player.getInventory().getItem(index);
      if (slot.contains(mouseX, mouseY))
        gui.fill(slot.x() + 1, slot.y() + 1, slot.right() - 1, slot.bottom() - 1, 0xff39453a);
      gui.item(stack, icon.x(), icon.y());
      gui.itemDecorations(font, stack, icon.x(), icon.y());
      if (!stack.isEmpty() && slot.contains(mouseX, mouseY))
        gui.setTooltipForNextFrame(font, stack, mouseX, mouseY);
    }
  }

  private void offerTooltip(
      GuiGraphicsExtractor gui,
      CampaignShopCatalog.Offer offer,
      CampaignShops.View view,
      int mouseX,
      int mouseY) {
    var lines = new ArrayList<Component>();
    lines.add(Component.literal(name(offer)));
    lines.add(
        Component.literal(number(offer.price()) + " runes for " + offer.count() + " item(s)"));
    if (!offer.potion().isEmpty()) {
      var stack = CampaignItems.stack(offer.item(), 1, offer.potion());
      var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
      float scale =
          stack.getOrDefault(net.minecraft.core.component.DataComponents.POTION_DURATION_SCALE, 1f);
      net.minecraft.world.item.alchemy.PotionContents.addPotionTooltip(
          contents.getAllEffects(), lines::add, scale, 20);
    }
    int stock = view == null ? offer.stock() : view.remaining(offer);
    lines.add(Component.literal("Stock: " + (stock < 0 ? "unlimited" : stock)));
    var weapon = CampaignConfig.current().weapons.get(offer.item());
    var armor = CampaignConfig.current().armors.get(offer.item());
    if (weapon != null)
      lines.add(
          Component.literal(
              "Damage " + weapon.damage() + "  /  Attack speed " + weapon.attackSpeed()));
    if (armor != null) lines.add(Component.literal(armor.reductionLabel()));
    if (offer.nativeGoods()) lines.add(Component.literal("Delivered to Elden Ring inventory"));
    String reason = disabledReason(offer, view);
    if (!reason.isBlank()) lines.add(Component.literal(reason));
    gui.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
  }

  private class ShopButton extends Button {
    ShopButton(CampaignShopLayout.Rect box, String text, OnPress pressed) {
      this(box.x(), box.y(), box.width(), box.height(), text, pressed);
    }

    ShopButton(int x, int y, int w, int h, String text, OnPress pressed) {
      super(x, y, w, h, Component.literal(text), pressed, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
      boolean hover = active && isHoveredOrFocused();
      frame(
          gui,
          new CampaignShopLayout.Rect(getX(), getY(), width, height),
          !active ? 0xff1b2020 : hover ? 0xff434a32 : 0xff2b3229,
          !active ? 0xff454941 : hover ? GOLD : BORDER);
      String label = clipped(getMessage().getString(), width - 6);
      gui.text(
          font,
          label,
          getX() + (width - font.width(label)) / 2,
          getY() + (height - font.lineHeight) / 2,
          !active ? MUTED : hover ? 0xffffe5a1 : TEXT,
          true);
    }
  }

  private final class OfferButton extends ShopButton {
    private final int row;

    OfferButton(CampaignShopLayout.Rect box, int row, OnPress pressed) {
      super(box.x(), box.y(), box.width(), box.height(), "", pressed);
      this.row = row;
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
      int index = page * layout.rows() + row;
      if (index >= shop.offers().size()) return;
      var offer = shop.offers().get(index);
      var view = currentView();
      boolean chosen = index == selected, hover = active && isHoveredOrFocused();
      frame(
          gui,
          new CampaignShopLayout.Rect(getX(), getY(), width, height),
          chosen ? SELECTED : hover ? 0xff2c3530 : CARD,
          chosen || hover ? BORDER : 0xff303933);
      if (chosen) gui.fill(getX() + 1, getY() + 1, getX() + 3, getBottom() - 1, GOLD);
      ItemStack stack = CampaignItems.stack(offer.item(), offer.count(), offer.potion());
      var icon = layout.offerIcon(row);
      gui.item(stack, icon.x(), icon.y());
      gui.itemDecorations(font, stack, icon.x(), icon.y());
      String state =
          view == null
              ? "Unavailable"
              : !offer.unlocked(view.defeated())
                  ? "Locked"
                  : view.remaining(offer) == 0
                      ? "Sold out"
                      : offer.count() > 1 ? "Bundle of " + offer.count() : "";
      int textY = layout.row(row).centeredTextY(font.lineHeight, state.isBlank() ? 1 : 2);
      var priceBox = layout.offerPrice(row, priceColumnWidth);
      var nameBox = layout.offerName(row, priceColumnWidth);
      String price = clipped(number(offer.price()), priceBox.width());
      text(gui, name(offer), nameBox.x(), textY, nameBox.width(), TEXT);
      gui.text(font, price, priceBox.right() - font.width(price), textY, GOLD, true);
      if (!state.isBlank())
        text(
            gui,
            state,
            nameBox.x(),
            textY + font.lineHeight + 1,
            width - 33,
            state.equals("Locked") || state.equals("Sold out") ? WARNING : MUTED);
      if (isHoveredOrFocused()) offerTooltip(gui, offer, view, mouseX, mouseY);
    }
  }
}
