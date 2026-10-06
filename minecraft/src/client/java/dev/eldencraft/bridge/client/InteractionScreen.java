package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.InteractionLayout;
import dev.eldencraft.bridge.InteractionProtocol;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Only the native ESD's currently visible, enabled rows are selectable. */
final class InteractionScreen extends Screen {
  private final long session;
  private InteractionProtocol.Menu menu;
  private List<InteractionProtocol.Choice> choices;
  private InteractionLayout layout;
  private final List<Button> rows = new ArrayList<>();
  private int page, selected;
  private boolean pending, interrupted;
  private String status = "";
  private List<FormattedCharSequence> body = List.of();
  private int bodyOffset;
  private Button mapButton;

  InteractionScreen(long session, InteractionProtocol.Menu menu) {
    super(Component.literal(menu.title()));
    this.session = session;
    this.menu = menu;
    choices = visibleChoices(menu);
  }

  long session() {
    return session;
  }

  long token() {
    return menu.token();
  }

  private static List<InteractionProtocol.Choice> visibleChoices(InteractionProtocol.Menu menu) {
    return menu.choices().stream()
        .filter(c -> !InteractionProtocol.unusedProgression(menu, c.text()))
        .toList();
  }

  void update(InteractionProtocol.Menu value) {
    if (value.equals(menu)) return;
    menu = value;
    choices = visibleChoices(menu);
    selected = Math.clamp(selected, 0, Math.max(0, choices.size() - 1));
    pending = false;
    status = "";
    rebuildWidgets();
  }

  @Override
  protected void init() {
    rows.clear();
    int panelWidth = Math.max(1, Math.min(320, width - 16));
    body =
        menu.kind().equals("dialog")
            ? font.split(Component.literal(menu.title()), Math.max(1, panelWidth - 32))
            : List.of();
    layout = InteractionLayout.create(width, height, choices.size(), body.size(), font.lineHeight);
    bodyOffset = Math.clamp(bodyOffset, 0, Math.max(0, body.size() - bodyRows()));
    page = selected / layout.rows();
    if (width < 100
        || height < 90
        || (!body.isEmpty() && layout.bodyHeight() < font.lineHeight + 8)) return;
    if (menu.kind().equals("grace") && layout.width() >= 180)
      mapButton =
          addRenderableWidget(
              Button.builder(Component.literal("Map"), b -> CampaignInteractions.openMap())
                  .bounds(layout.x() + layout.width() - 56, layout.y() + 8, 44, 20)
                  .build());
    if (mapButton != null) mapButton.active = !pending && !interrupted;
    for (int row = 0; row < layout.rows(); row++) {
      int index = page * layout.rows() + row;
      if (index >= choices.size()) break;
      var choice = choices.get(index);
      String label = MinecraftUi.clipped(font, choice.text(), layout.width() - 36);
      Button button =
          Button.builder(Component.literal(label), b -> choose(index))
              .bounds(layout.x() + 12, layout.rowY(row), layout.width() - 24, 20)
              .build();
      button.active = choice.enabled() && !pending && !interrupted;
      // Only a clipped label needs its full text on hover.
      if (!label.equals(choice.text()))
        button.setTooltip(Tooltip.create(Component.literal(choice.text())));
      rows.add(addRenderableWidget(button));
    }
    int bottom = layout.y() + layout.height() - 28;
    if (layout.pageCount(choices.size()) > 1) {
      addRenderableWidget(
                  Button.builder(Component.literal("<"), b -> changePage(-1))
                      .bounds(layout.x() + 12, bottom, 24, 20)
                      .build())
              .active =
          page > 0;
      addRenderableWidget(
                  Button.builder(Component.literal(">"), b -> changePage(1))
                      .bounds(layout.x() + 40, bottom, 24, 20)
                      .build())
              .active =
          page + 1 < layout.pageCount(choices.size());
    }
    addRenderableWidget(
        Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(layout.x() + layout.width() - 76, bottom, 64, 20)
            .build());
    int visible = selected - page * layout.rows();
    if (visible >= 0 && visible < rows.size() && rows.get(visible).active)
      setInitialFocus(rows.get(visible));
  }

  private void choose(int index) {
    if (pending || interrupted || index < 0 || index >= choices.size()) return;
    var choice = choices.get(index);
    if (!choice.enabled()) return;
    selected = index;
    if (CampaignInteractions.select(menu.token(), choice.id())) {
      pending = true;
      status = "Waiting...";
      for (var button : rows) button.active = false;
      if (mapButton != null) mapButton.active = false;
    } else status = "This interaction is no longer available.";
  }

  /** A short native lease gap: keep the rows visible but not selectable. */
  void interrupted(boolean value) {
    if (interrupted == value) return;
    interrupted = value;
    if (!pending) status = value ? "Waiting for Elden Ring..." : "";
    for (int i = 0; i < rows.size(); i++) {
      int index = page * layout.rows() + i;
      rows.get(i).active =
          !value && !pending && index < choices.size() && choices.get(index).enabled();
    }
    if (mapButton != null) mapButton.active = !value && !pending;
  }

  void containerOpenFailed(String message) {
    pending = false;
    status = message;
    rebuildWidgets();
  }

  private int bodyRows() {
    return Math.max(1, (layout.bodyHeight() - 8) / font.lineHeight);
  }

  private void scrollBody(int lines) {
    bodyOffset = Math.clamp(bodyOffset + lines, 0, Math.max(0, body.size() - bodyRows()));
  }

  private void changePage(int delta) {
    int next = Math.clamp(page + delta, 0, layout.pageCount(choices.size()) - 1);
    if (next == page) return;
    page = next;
    selected = page * layout.rows();
    rebuildWidgets();
  }

  void hostInput(int pressed, int held) {
    if ((pressed & CampaignInteractions.CANCEL) != 0) {
      onClose();
      return;
    }
    int direction =
        (pressed & CampaignInteractions.UP) != 0
            ? -1
            : (pressed & CampaignInteractions.DOWN) != 0
                ? 1
                : (pressed & CampaignInteractions.TAB) != 0
                    ? ((held & CampaignInteractions.SHIFT) != 0 ? -1 : 1)
                    : 0;
    if (direction != 0 && !choices.isEmpty() && !pending) {
      for (int attempt = 0; attempt < choices.size(); attempt++) {
        selected = Math.floorMod(selected + direction, choices.size());
        if (choices.get(selected).enabled()) break;
      }
      rebuildWidgets();
    }
    if ((pressed & CampaignInteractions.LEFT) != 0) {
      if (!body.isEmpty()) scrollBody(-bodyRows());
      else changePage(-1);
    }
    if ((pressed & CampaignInteractions.RIGHT) != 0) {
      if (!body.isEmpty()) scrollBody(bodyRows());
      else changePage(1);
    }
    if ((pressed & CampaignInteractions.CONFIRM) != 0) choose(selected);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (vertical != 0) {
      if (!body.isEmpty()) scrollBody(vertical > 0 ? -2 : 2);
      else changePage(vertical > 0 ? -1 : 1);
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
    CampaignInteractions.dismiss(menu.token());
    minecraft.gui.setScreen(null);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
    gui.fill(0, 0, width, height, 0x88000000);
    MinecraftUi.panel(gui, layout.x(), layout.y(), layout.width(), layout.height());
    String heading =
        MinecraftUi.clipped(
            font,
            menu.kind().equals("dialog") ? "Interaction" : menu.title(),
            layout.width() - (menu.kind().equals("grace") && layout.width() >= 180 ? 84 : 24));
    gui.text(font, heading, layout.x() + 12, layout.y() + 12, MinecraftUi.TEXT, false);
    if (width < 100
        || height < 90
        || (!body.isEmpty() && layout.bodyHeight() < font.lineHeight + 8)) {
      gui.text(font, "Reduce GUI scale to view choices.", 8, height / 2, 0xffffffff, true);
      return;
    }
    if (!body.isEmpty()) {
      MinecraftUi.slot(
          gui,
          layout.x() + 12,
          layout.y() + 28,
          layout.width() - 24,
          layout.bodyHeight(),
          0xffb5b5b5);
      for (int row = 0; row < bodyRows() && bodyOffset + row < body.size(); row++)
        gui.text(
            font,
            body.get(bodyOffset + row),
            layout.x() + 16,
            layout.y() + 32 + row * font.lineHeight,
            MinecraftUi.TEXT,
            false);
    }
    if (choices.isEmpty())
      gui.text(
          font,
          "No choices available.",
          layout.x() + 12,
          layout.rowY(0) + 5,
          MinecraftUi.MUTED,
          false);
    if (!status.isBlank())
      gui.text(
          font,
          MinecraftUi.clipped(font, status, layout.width() - 104),
          layout.x() + 12,
          layout.y() + layout.height() - 22,
          MinecraftUi.MUTED,
          false);
    else if (layout.pageCount(choices.size()) > 1)
      gui.text(
          font,
          (page + 1) + " / " + layout.pageCount(choices.size()),
          layout.x() + 72,
          layout.y() + layout.height() - 22,
          MinecraftUi.MUTED,
          false);
    else if (body.size() > bodyRows())
      gui.text(
          font,
          "Scroll to read",
          layout.x() + 12,
          layout.y() + layout.height() - 22,
          MinecraftUi.MUTED,
          false);
    super.extractRenderState(gui, mouseX, mouseY, partialTick);
  }
}
