package dev.eldencraft.bridge;

import java.util.*;

/** Responsive hit regions, inventory ordering and honest bulk labels at vanilla GUI scales. */
public final class CampaignShopLayoutConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static boolean inside(CampaignShopLayout.Rect inner, CampaignShopLayout.Rect outer) {
    return inner.x() >= outer.x()
        && inner.y() >= outer.y()
        && inner.right() <= outer.right()
        && inner.bottom() <= outer.bottom();
  }

  private static boolean overlap(CampaignShopLayout.Rect a, CampaignShopLayout.Rect b) {
    return a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y();
  }

  public static int verify() {
    checks = 0;
    check(
        !CampaignShopLayout.usable(319, 240) && !CampaignShopLayout.usable(320, 239),
        "small windows require the noninteractive resize fallback");
    check(
        !CampaignShopLayout.usable(0, 0) && CampaignShopLayout.usable(320, 240),
        "zero-sized boundaries cannot enter full shop geometry but the standard minimum can");
    check(
        !CampaignShopLayout.usable(320, 180),
        "forced Unicode at supported framebuffer sizes uses the readable resize fallback");
    check(
        !CampaignShopLayout.usable(160, 120),
        "forced Unicode at the smallest window cannot enter unsupported shop geometry");
    for (int[] size :
        new int[][] {
          {320, 240}, {360, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}, {1920, 1080}
        }) {
      var layout = CampaignShopLayout.create(size[0], size[1]);
      var panel = layout.panel();
      check(
          inside(panel, new CampaignShopLayout.Rect(0, 0, size[0], size[1])),
          "centered panel stays inside GUI");
      for (var section :
          List.of(layout.catalog(), layout.details(), layout.feedback(), layout.inventory()))
        check(inside(section, panel), "every section stays within panel");
      check(
          !overlap(layout.catalog(), layout.details())
              && layout.catalog().bottom() < layout.feedback().y()
              && layout.feedback().bottom() < layout.inventory().y(),
          "catalog, details, feedback and inventory do not overlap");
      check(
          layout.details().width() >= 116 && layout.details().height() >= 70,
          "smallest supported GUI retains readable details and purchase controls");
      var previous = layout.pageButton(false);
      var next = layout.pageButton(true);
      var buy = layout.purchaseButton(false);
      var bulk = layout.purchaseButton(true);
      check(
          previous.y() == buy.y()
              && next.y() == bulk.y()
              && previous.centeredTextY(9, 1) == buy.centeredTextY(9, 1)
              && layout.pageLabel().centeredTextY(9, 1) == buy.centeredTextY(9, 1),
          "pager, page label and purchase controls share one pixel baseline");
      check(
          inside(previous, layout.catalog())
              && inside(next, layout.catalog())
              && inside(layout.pageLabel(), layout.catalog())
              && inside(buy, layout.details())
              && inside(bulk, layout.details()),
          "footer controls remain inside their columns");
      check(
          buy.x() - layout.details().x() == layout.details().right() - bulk.right()
              && bulk.x() - buy.right() == 4
              && Math.abs(buy.width() - bulk.width()) <= 1,
          "odd purchase widths keep equal outer margins and a four-pixel gap");
      check(
          layout.title().x() == layout.catalog().x()
              && layout.title().centeredTextY(9, 1) == layout.closeButton().centeredTextY(9, 1)
              && layout.title().right() + 8 == layout.closeButton().x(),
          "merchant title shares the catalog edge and close-label baseline with a safe gap");
      check(
          inside(layout.wallet(), panel)
              && layout.wallet().x() >= layout.catalog().right() + 8
              && layout.wallet().right() == layout.details().right() - 8,
          "wallet has a separate bounded column aligned with detail controls");
      var detailIcon = layout.detailIcon();
      check(
          inside(detailIcon, layout.details())
              && detailIcon.bottom() + 8 <= buy.y()
              && detailIcon.inset(2).width() == 16,
          "selected-item icon has a symmetric inset and cannot cover purchase controls");
      for (int i = 0; i < layout.rows(); i++) {
        var row = layout.row(i);
        check(
            inside(row, layout.catalog()) && row.bottom() <= previous.y() - 2,
            "offer hit regions cannot cover page controls");
        check(
            row.contains(row.x(), row.y()) && !row.contains(row.right(), row.bottom()),
            "hover regions use consistent exclusive edges");
        check(
            inside(layout.offerIcon(i), row.inset(1)) && layout.offerIcon(i).height() == 16,
            "offer icons are centered within row borders");
        for (int lines = 1; lines <= 2; lines++) {
          int textHeight = lines * 9 + lines - 1;
          check(
              inside(
                  new CampaignShopLayout.Rect(
                      row.x() + 26, row.centeredTextY(9, lines), 1, textHeight),
                  row.inset(1)),
              "single-line and subtitle offers stay vertically inside their borders");
        }
        for (int priceWidth : new int[] {0, 6, 68, 128, 10000}) {
          var price = layout.offerPrice(i, priceWidth);
          var name = layout.offerName(i, priceWidth);
          check(
              inside(price, row)
                  && inside(name, row)
                  && name.width() >= 0
                  && price.right() == row.right() - 6
                  && price.x() >= layout.offerIcon(i).right() + 5
                  && (name.width() == 0 || name.right() + 8 <= price.x()),
              "large prices cannot spill into item icons or create negative name budgets");
        }
      }
      var slots = new ArrayList<CampaignShopLayout.Rect>();
      for (int i = 0; i < 36; i++) {
        var slot = layout.inventorySlot(i);
        check(inside(slot, layout.inventory()), "all 36 item icons remain in inventory section");
        var icon = layout.inventoryIcon(i);
        check(
            slot.width() == 18
                && slot.height() == 18
                && icon.width() == 16
                && icon.height() == 16
                && icon.x() - slot.x() == slot.right() - icon.right()
                && icon.y() - slot.y() == slot.bottom() - icon.bottom(),
            "vanilla-sized icons have equal padding on all four sides");
        slots.add(slot);
      }
      boolean disjoint = true;
      for (int i = 0; i < 36; i++)
        for (int j = i + 1; j < 36; j++) disjoint &= !overlap(slots.get(i), slots.get(j));
      check(disjoint, "each inventory item has a distinct tooltip hit region");
      check(
          slots.get(0).y() > slots.get(27).bottom() && slots.get(9).y() < slots.get(18).y(),
          "main inventory precedes separate hotbar in vanilla slot order");
      check(
          slots.get(0).x() == layout.inventory().x()
              && slots.get(8).right() == layout.inventory().right()
              && slots.get(8).bottom() == layout.inventory().bottom(),
          "inventory grid occupies its full centered rectangle without a trailing pixel");
      check(
          panel.width() <= 452 && panel.height() <= 316, "large resolutions keep a compact panel");
    }
    check(
        CampaignShopLayout.create(320, 240).rows() == 2,
        "minimum GUI paginates rather than overlapping controls");
    check(
        CampaignShopLayout.bulkQuantity(64, 16, -1, 1000, 40, false) == 4,
        "arrow bulk label describes a complete stack of four bundles");
    check(
        CampaignShopLayout.bulkQuantity(64, 16, -1, 90, 40, false) == 2,
        "bulk label reflects affordable bundles");
    check(
        CampaignShopLayout.bulkQuantity(64, 16, 1, 1000, 40, false) == 1,
        "finite stock cannot advertise unavailable bulk items");
    check(
        CampaignShopLayout.bulkQuantity(1, 1, -1, 1000, 40, false) == 1,
        "unstackable weapons have no duplicate bulk action");
    check(
        CampaignShopLayout.bulkQuantity(64, 1, -1, 1000, 40, true) == 1,
        "native lots remain single purchases");
    check(
        CampaignShopLayout.bulkQuantity(64, 16, -1, 0, 40, false) == 0
            && CampaignShopLayout.bulkQuantity(64, 16, 0, 1000, 40, false) == 0,
        "empty wallet and sold-out stock show no purchase quantity");
    check(
        CampaignShopLayout.bulkQuantity(64, 16, -1, 0, 0, false) == 4,
        "free configured bundles preserve the item stack limit");
    return checks;
  }

  public static void main(String[] args) {
    System.out.println("CampaignShopLayoutConformance: " + verify() + " checks passed");
  }
}
