package dev.eldencraft.bridge.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A readable, dismissible guide that paginates its text at the current GUI scale. */
final class CampaignTutorialScreen extends Screen {
  private record Chapter(String title, List<String> paragraphs) {}

  private record Page(String title, List<FormattedCharSequence> lines) {}

  private static final List<Chapter> CHAPTERS =
      List.of(
          new Chapter(
              "Welcome to EldenCraft",
              List.of(
                  "EldenCraft is an alpha. Expect bugs, unfinished features and balance changes.",
                  "If you encounter a game-breaking bug, press F6 to disable the Minecraft"
                      + " compositor and return to Elden Ring's view. Press F6 again to restore"
                      + " it.",
                  "Read this guide with Next and Back, or the left/right arrows. You can close it"
                      + " at any time with Escape or Skip.")),
          new Chapter(
              "Progress through bosses",
              List.of(
                  "Explore, fight and earn stronger Minecraft equipment. Wooden weapons and your"
                      + " shield are indestructible.",
                  "Margit awards a stone sword and opens crafting access. Godrick awards an iron"
                      + " sword. Later victories provide diamond equipment and netherite upgrades.",
                  "The first defeat of each unique remembrance boss automatically increases maximum"
                      + " health and stamina. You begin with 10 full hearts and can reach 30. There"
                      + " is no manual character leveling.")),
          new Chapter(
              "Prepare at merchants",
              List.of(
                  "Press R to interact with a merchant, then choose Shop. Purchases use your Elden"
                      + " Ring character's real rune wallet.",
                  "Stock up on food, golden apples, arrows and other supplies before a difficult"
                      + " fight. Stronger equipment and crafting materials unlock after the"
                      + " relevant boss victories.",
                  "Check each item's rune price, remaining stock and unlock requirement. Talk and"
                      + " quest dialogue still work; selling items is currently unavailable.")),
          new Chapter(
              "Fight and recover",
              List.of(
                  "Minecraft attack cooldowns still matter. Melee attacks, bows and crossbows spend"
                      + " stamina, including missed swings. Let stamina recover between attacks.",
                  "Hold right mouse with a shield to block attacks from the front. Blocking spends"
                      + " stamina; exhausting it breaks your guard and can let damage through."
                      + " Lower the shield to recover.",
                  "Eat food to restore hunger and regenerate health. Golden apples provide healing"
                      + " and absorption. Hold a totem of undying in either hand to survive a"
                      + " lethal hit.")),
          new Chapter(
              "Explore and use checkpoints",
              List.of(
                  "Press R for doors, ladders, pickups, levers and Sites of Grace. At a grace, use"
                      + " the Ender Chest to store your Minecraft equipment.",
                  "Press E for your inventory. Left mouse attacks or mines; right mouse uses an"
                      + " item or places a block. Sprinting, jumping, mining and building do not"
                      + " spend combat stamina.",
                  "Press M or G for Elden Ring's map and fast travel. Press Y to summon or dismiss"
                      + " Torrent where riding is allowed. F5 changes the camera view.")));

  private final List<Page> pages = new ArrayList<>();
  private int page, panelX, panelY, panelWidth, panelHeight, rowHeight;
  private boolean usable;

  CampaignTutorialScreen() {
    super(Component.literal("EldenCraft guide"));
  }

  @Override
  protected void init() {
    panelWidth = Math.max(1, Math.min(390, width - 16));
    panelHeight = Math.max(1, Math.min(248, height - 16));
    panelX = (width - panelWidth) / 2;
    panelY = (height - panelHeight) / 2;
    rowHeight = font.lineHeight + 2;
    usable = panelWidth >= 210 && panelHeight >= 118;
    pages.clear();
    if (!usable) return;
    int rows = Math.max(1, (panelHeight - 90) / rowHeight);
    for (var chapter : CHAPTERS) {
      var lines = new ArrayList<FormattedCharSequence>();
      for (var paragraph : chapter.paragraphs()) {
        if (!lines.isEmpty()) lines.add(Component.literal(" ").getVisualOrderText());
        lines.addAll(font.split(Component.literal(paragraph), panelWidth - 32));
      }
      for (int start = 0; start < lines.size(); start += rows)
        pages.add(
            new Page(
                chapter.title() + (start == 0 ? "" : " (continued)"),
                List.copyOf(lines.subList(start, Math.min(lines.size(), start + rows)))));
    }
    page = Math.clamp(page, 0, pages.size() - 1);
    int bottom = panelY + panelHeight - 30;
    var previous =
        addRenderableWidget(
            Button.builder(Component.literal("Back"), b -> changePage(-1))
                .bounds(panelX + 12, bottom, 64, 20)
                .build());
    previous.active = page > 0;
    var next =
        addRenderableWidget(
            Button.builder(
                    Component.literal(page + 1 == pages.size() ? "Done" : "Next"),
                    b -> {
                      if (page + 1 == pages.size()) onClose();
                      else changePage(1);
                    })
                .bounds(panelX + panelWidth - 76, bottom, 64, 20)
                .build());
    if (page + 1 < pages.size())
      addRenderableWidget(
          Button.builder(Component.literal("Skip"), b -> onClose())
              .bounds(panelX + panelWidth - 132, bottom, 50, 20)
              .build());
    setInitialFocus(next);
  }

  private void changePage(int direction) {
    int changed = Math.clamp(page + direction, 0, Math.max(0, pages.size() - 1));
    if (changed == page) return;
    page = changed;
    rebuildWidgets();
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == 263 || event.key() == 262) {
      changePage(event.key() == 263 ? -1 : 1);
      return true;
    }
    return super.keyPressed(event);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (vertical != 0) {
      changePage(vertical > 0 ? -1 : 1);
      return true;
    }
    return super.mouseScrolled(x, y, horizontal, vertical);
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
    minecraft.gui.setScreen(null);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
    gui.fill(0, 0, width, height, 0x99000000);
    if (!usable) {
      gui.text(
          font,
          MinecraftUi.clipped(font, "Reduce GUI scale to read the guide.", Math.max(0, width - 16)),
          8,
          Math.max(0, height / 2 - 12),
          0xffffffff,
          true);
      gui.text(
          font,
          MinecraftUi.clipped(
              font, "Escape closes. F6 disables composition.", Math.max(0, width - 16)),
          8,
          Math.max(0, height / 2),
          0xffffffff,
          true);
      return;
    }
    MinecraftUi.panel(gui, panelX, panelY, panelWidth, panelHeight);
    var current = pages.get(page);
    gui.text(
        font,
        MinecraftUi.clipped(font, current.title(), panelWidth - 32),
        panelX + 16,
        panelY + 14,
        MinecraftUi.TEXT,
        false);
    gui.text(font, "ELDENCRAFT / ALPHA", panelX + 16, panelY + 29, MinecraftUi.MUTED, false);
    String position = (page + 1) + " / " + pages.size();
    gui.text(
        font,
        position,
        panelX + panelWidth - 16 - font.width(position),
        panelY + 29,
        MinecraftUi.MUTED,
        false);
    MinecraftUi.slot(gui, panelX + 12, panelY + 44, panelWidth - 24, panelHeight - 83, 0xffb5b5b5);
    for (int row = 0; row < current.lines().size(); row++)
      gui.text(
          font,
          current.lines().get(row),
          panelX + 16,
          panelY + 49 + row * rowHeight,
          MinecraftUi.TEXT,
          false);
    super.extractRenderState(gui, mouseX, mouseY, partialTick);
  }
}
