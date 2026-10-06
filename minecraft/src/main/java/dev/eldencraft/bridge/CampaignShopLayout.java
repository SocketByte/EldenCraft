package dev.eldencraft.bridge;

/** Pixel geometry and visible purchase quantities for the vanilla merchant screen. */
public record CampaignShopLayout(
    Rect panel, Rect catalog, Rect details, Rect feedback, Rect inventory, int rows) {
  public static final int ROW_HEIGHT = 24;
  public static final int CONTROL_HEIGHT = 18;

  public record Rect(int x, int y, int width, int height) {
    public int right() {
      return x + width;
    }

    public int bottom() {
      return y + height;
    }

    public boolean contains(double px, double py) {
      return px >= x && px < right() && py >= y && py < bottom();
    }

    public Rect inset(int padding) {
      return new Rect(x + padding, y + padding, width - padding * 2, height - padding * 2);
    }

    public int centeredTextY(int lineHeight, int lines) {
      int textHeight = lines * lineHeight + Math.max(0, lines - 1);
      return y + (height - textHeight) / 2;
    }
  }

  public static CampaignShopLayout create(int width, int height) {
    if (!usable(width, height))
      throw new IllegalArgumentException("Minecraft shop requires vanilla's minimum GUI size");
    int w = Math.min(452, width - 16), h = Math.min(316, height - 12);
    var panel = new Rect((width - w) / 2, (height - h) / 2, w, h);
    var inventory = new Rect(panel.x + (w - 162) / 2, panel.bottom() - 84, 162, 76);
    var feedback = new Rect(panel.x + 8, inventory.y - 31, w - 16, 16);
    int bodyY = panel.y + 37, bodyHeight = feedback.y - 6 - bodyY;
    int catalogWidth = (w - 24) * 58 / 100;
    var catalog = new Rect(panel.x + 8, bodyY, catalogWidth, bodyHeight);
    var details = new Rect(catalog.right() + 8, bodyY, w - 24 - catalogWidth, bodyHeight);
    return new CampaignShopLayout(
        panel,
        catalog,
        details,
        feedback,
        inventory,
        Math.max(1, Math.min(6, (bodyHeight - CONTROL_HEIGHT - 4) / ROW_HEIGHT)));
  }

  public static boolean usable(int width, int height) {
    return width >= 320 && height >= 240;
  }

  public Rect row(int index) {
    if (index < 0 || index >= rows) throw new IllegalArgumentException("Offer row");
    return new Rect(catalog.x, catalog.y + index * ROW_HEIGHT, catalog.width, ROW_HEIGHT - 2);
  }

  public Rect offerIcon(int index) {
    var row = row(index);
    return new Rect(row.x + 5, row.y + (row.height - 16) / 2, 16, 16);
  }

  /** The price column is bounded even for arbitrary localized numerals or font packs. */
  public Rect offerPrice(int index, int measuredWidth) {
    var row = row(index);
    int priceWidth = Math.clamp(measuredWidth, 0, row.width - 32);
    return new Rect(row.right() - 6 - priceWidth, row.y, priceWidth, row.height);
  }

  public Rect offerName(int index, int measuredPriceWidth) {
    var row = row(index);
    var price = offerPrice(index, measuredPriceWidth);
    return new Rect(row.x + 26, row.y, Math.max(0, price.x - 8 - row.x - 26), row.height);
  }

  public Rect pageButton(boolean next) {
    return new Rect(
        next ? catalog.right() - 20 : catalog.x,
        catalog.bottom() - CONTROL_HEIGHT - 4,
        20,
        CONTROL_HEIGHT);
  }

  public Rect pageLabel() {
    var button = pageButton(false);
    return new Rect(button.right() + 4, button.y, catalog.width - 48, button.height);
  }

  /** Odd inner widths are shared between the buttons without changing either outer margin. */
  public Rect purchaseButton(boolean bulk) {
    int available = details.width - 20, firstWidth = available / 2;
    return new Rect(
        details.x + 8 + (bulk ? firstWidth + 4 : 0),
        details.bottom() - CONTROL_HEIGHT - 4,
        bulk ? available - firstWidth : firstWidth,
        CONTROL_HEIGHT);
  }

  public Rect detailIcon() {
    return new Rect(details.x + 8, details.y + 20, 20, 20);
  }

  public Rect title() {
    return new Rect(panel.x + 8, panel.y + 6, panel.width - 39, 15);
  }

  public Rect closeButton() {
    return new Rect(panel.right() - 23, panel.y + 6, 15, 15);
  }

  public Rect wallet() {
    return new Rect(details.x + 8, panel.y + 22, details.width - 16, 11);
  }

  public Rect inventorySlot(int index) {
    if (index < 0 || index >= 36) throw new IllegalArgumentException("Inventory slot");
    int row = index < 9 ? 3 : index / 9 - 1;
    return new Rect(
        inventory.x + index % 9 * 18, inventory.y + row * 18 + (row == 3 ? 4 : 0), 18, 18);
  }

  public Rect inventoryIcon(int index) {
    return inventorySlot(index).inset(1);
  }

  /** Stock is counted in configured bundles, while labels show the delivered vanilla item count. */
  public static int bulkQuantity(
      int stackSize, int count, int stock, long runes, long price, boolean nativeGoods) {
    if (count < 1 || stackSize < 1 || price < 0 || runes < 0 || stock == 0) return 0;
    int quantity = nativeGoods ? 1 : Math.max(1, Math.min(64, stackSize / count));
    if (stock >= 0) quantity = Math.min(quantity, stock);
    if (price > 0) quantity = (int) Math.min(quantity, runes / price);
    return quantity;
  }
}
