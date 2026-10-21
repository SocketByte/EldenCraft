package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.PhysickItems;
import dev.eldencraft.bridge.PhysickTears;
import dev.eldencraft.bridge.PhysickTears.Tear;
import dev.eldencraft.bridge.WondrousPhysickItem;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Mix up to two Crystal Tears into the Flask of Wondrous Physick at a Site of Grace. Tears are a
 * picker over the Elden Ring inventory, not Minecraft items; the server checks the choice again.
 */
final class CampaignPhysickScreen extends Screen {
  private static final int TEXT = 0xffeee7d7, MUTED = 0xffb0aa9c, GOLD_TEXT = 0xffe6c47a;
  private static final int BORDER = 0xff806c45, PANEL = 0xff161a1a, WARNING = 0xffe9a07d;
  private static final int WIDTH = 264, HEIGHT = 204, CELL = 20, COLUMNS = 6;
  private final InteractionScreen parent;
  private final long session, token;
  private final List<Tear> chosen = new ArrayList<>();
  private int left, top;
  private String feedback = "", lastStatus;
  private Button mix, empty;

  CampaignPhysickScreen(InteractionScreen parent, long session, long token) {
    super(Component.literal("Wondrous Physick"));
    this.parent = parent;
    this.session = session;
    this.token = token;
    var view = CampaignPhysick.view();
    if (view != null) chosen.addAll(view.mixture());
    lastStatus = CampaignPhysick.status();
  }

  long session() {
    return session;
  }

  long token() {
    return token;
  }

  @Override
  protected void init() {
    left = (width - WIDTH) / 2;
    top = (height - HEIGHT) / 2;
    if (width < WIDTH || height < HEIGHT) {
      addRenderableWidget(new PanelButton(width / 2 - 36, height / 2 + 14, 72, 18, "Back")).action =
          this::onClose;
      return;
    }
    var tears = Tear.values();
    for (int i = 0; i < tears.length; i++) {
      var tear = tears[i];
      addRenderableWidget(
          new TearButton(left + 10 + (i % COLUMNS) * CELL, top + 40 + (i / COLUMNS) * CELL, tear));
    }
    for (int slot = 0; slot < PhysickTears.SLOTS; slot++)
      addRenderableWidget(new SlotButton(left + 152 + slot * 72, top + 40, slot));
    mix = addRenderableWidget(new PanelButton(left + 142, top + HEIGHT - 28, 54, 18, "Mix"));
    ((PanelButton) mix).action = this::mix;
    empty = addRenderableWidget(new PanelButton(left + 200, top + HEIGHT - 28, 54, 18, "Empty"));
    ((PanelButton) empty).action =
        () -> {
          chosen.clear();
          feedback = "";
        };
    var close = addRenderableWidget(new PanelButton(left + WIDTH - 22, top + 8, 14, 14, "X"));
    close.action = this::onClose;
    close.setTooltip(Tooltip.create(Component.literal("Back to the Site of Grace")));
  }

  @Override
  public void tick() {
    if (mix != null) mix.active = !chosen.isEmpty() && owned() != null;
    if (empty != null) empty.active = !chosen.isEmpty();
    // Show a server result once, when it arrives; local hints may replace it afterwards.
    String status = CampaignPhysick.status();
    if (!status.equals(lastStatus)) {
      lastStatus = status;
      feedback = status;
    }
  }

  private Map<Tear, Integer> owned() {
    var view = CampaignPhysick.view();
    return view == null ? null : view.owned().tears();
  }

  private int count(Tear tear) {
    var owned = owned();
    return owned == null ? 0 : owned.getOrDefault(tear, 0);
  }

  private void pick(Tear tear) {
    if (count(tear) == 0) {
      feedback = tear.displayName + " is not in your Elden Ring inventory yet.";
      return;
    }
    long used = chosen.stream().filter(t -> t == tear).count();
    if (used > 0 && (used >= count(tear) || chosen.size() >= PhysickTears.SLOTS)) {
      chosen.remove(chosen.lastIndexOf(tear));
      feedback = "";
    } else if (chosen.size() < PhysickTears.SLOTS) {
      chosen.add(tear);
      feedback = "";
    } else feedback = "Both slots are full. Click a slot to take a tear out.";
  }

  private void mix() {
    if (chosen.isEmpty()) return;
    CampaignPhysick.requestMix(minecraft, chosen);
    feedback = CampaignPhysick.status();
  }

  void hostInput(int pressed) {
    if ((pressed & CampaignInteractions.CANCEL) != 0) onClose();
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
    minecraft.gui.setScreen(parent);
  }

  private static void frame(
      GuiGraphicsExtractor gui, int x, int y, int w, int h, int fill, int edge) {
    gui.fill(x, y, x + w, y + h, fill);
    gui.outline(x, y, w, h, edge);
  }

  private static Identifier sprite(Tear tear) {
    return Identifier.fromNamespaceAndPath(PhysickItems.NAMESPACE, "physick/" + tear.id);
  }

  private String clipped(String text, int maximum) {
    return MinecraftUi.clipped(font, text, maximum);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
    gui.fill(0, 0, width, height, 0x99060708);
    if (width < WIDTH || height < HEIGHT) {
      String message = clipped("Resize the window or reduce the GUI scale.", width - 16);
      gui.text(font, message, (width - font.width(message)) / 2, height / 2 - 10, TEXT, true);
      super.extractRenderState(gui, mouseX, mouseY, partialTick);
      return;
    }
    gui.fill(left - 2, top - 2, left + WIDTH + 2, top + HEIGHT + 2, 0xdd080b0b);
    frame(gui, left, top, WIDTH, HEIGHT, PANEL, BORDER);
    gui.fillGradient(left + 1, top + 1, left + WIDTH - 1, top + 30, 0xff2b302a, PANEL);
    gui.horizontalLine(left + 8, left + WIDTH - 9, top + 31, 0xff514a36);
    gui.text(font, "Wondrous Physick", left + 10, top + 11, TEXT, true);
    var owned = owned();
    int found = owned == null ? 0 : owned.size();
    String count = owned == null ? "Waiting for Elden Ring" : found + " / 29 tears";
    gui.text(font, count, left + WIDTH - 28 - font.width(count), top + 11, MUTED, true);
    drawAlembic(gui);
    String status =
        feedback.isBlank()
            ? "Tears stay in your Elden Ring save. Mixing replaces the current mixture."
            : feedback;
    int statusColor =
        feedback.isBlank()
            ? MUTED
            : feedback.startsWith("Flask mixed")
                ? GOLD_TEXT
                : feedback.equals("Mixing...") ? TEXT : WARNING;
    // Under the tear grid, left of the Mix and Empty buttons.
    var lines = font.split(Component.literal(status), COLUMNS * CELL);
    for (int i = 0; i < Math.min(5, lines.size()); i++)
      gui.text(font, lines.get(i), left + 10, top + 146 + i * 10, statusColor, true);
    super.extractRenderState(gui, mouseX, mouseY, partialTick);
  }

  private void drawAlembic(GuiGraphicsExtractor gui) {
    int x = left + 142;
    int pipeY = top + 72;
    for (int slot = 0; slot < PhysickTears.SLOTS; slot++) {
      int cx = left + 162 + slot * 72;
      int color = slot < chosen.size() ? 0xff000000 | chosen.get(slot).color : 0xff353e36;
      gui.fill(cx - 1, top + 60, cx + 1, pipeY + 1, color);
      int toward = left + 196 + (slot == 0 ? -12 : 12);
      gui.fill(Math.min(cx, toward), pipeY - 1, Math.max(cx, toward) + 1, pipeY + 1, color);
    }
    frame(gui, left + 184, top + 62, 24, 24, 0xff101515, BORDER);
    var preview = new ItemStack(PhysickItems.FLASK);
    preview.set(
        DataComponents.CUSTOM_MODEL_DATA, WondrousPhysickItem.display(!chosen.isEmpty(), chosen));
    gui.item(preview, left + 188, top + 66);
    gui.horizontalLine(x, left + WIDTH - 10, top + 94, 0xff353e36);
    if (chosen.isEmpty()) {
      gui.text(font, "Choose up to two tears", x, top + 102, MUTED, true);
      return;
    }
    for (int i = 0; i < chosen.size(); i++) {
      var tear = chosen.get(i);
      int y = top + 100 + i * 20;
      if (i == 1 && PhysickTears.paired(chosen)) {
        gui.text(font, clipped(pairLabel(tear), WIDTH - 152), x, y + 4, GOLD_TEXT, true);
        continue;
      }
      gui.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(tear), x, y, 16, 16);
      gui.text(font, clipped(tear.label, WIDTH - 172), x + 20, y + 1, TEXT, true);
      gui.text(font, tear.duration(), x + 20, y + 10, MUTED, false);
    }
  }

  private static String pairLabel(Tear tear) {
    return switch (tear) {
      case CRIMSON -> "Paired: full heal";
      case CERULEAN -> "Paired: full food bar";
      case RUPTURED -> "Paired: bigger blast";
      default -> "Paired";
    };
  }

  private List<Component> tooltip(Tear tear) {
    int have = count(tear);
    var lines = new ArrayList<Component>();
    lines.add(CampaignTooltipTheme.text(tear.displayName, CampaignTooltipTheme.IVORY));
    for (String line : CampaignWeaponTooltips.wrap(tear.eldenRing, 34))
      lines.add(CampaignTooltipTheme.detail(line));
    lines.add(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.AFFINITY, "Minecraft", tear.label, tear.color));
    for (String line : CampaignWeaponTooltips.wrap(tear.minecraft, 34))
      lines.add(CampaignTooltipTheme.detail(line));
    lines.add(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.SPEED,
            "Duration",
            tear.duration(),
            CampaignTooltipTheme.IVORY));
    if (have == 0)
      lines.add(
          CampaignTooltipTheme.row(
              CampaignTooltipTheme.Icon.LOCK,
              "Not found",
              "in Elden Ring",
              CampaignTooltipTheme.BLOOD));
    else if (tear.copies > 1)
      lines.add(
          CampaignTooltipTheme.row(
              CampaignTooltipTheme.Icon.FOUND,
              "Copies found",
              have + " / " + tear.copies,
              CampaignTooltipTheme.GREEN));
    return CampaignTooltipTheme.card(lines);
  }

  private class PanelButton extends Button {
    Runnable action = () -> {};

    PanelButton(int x, int y, int w, int h, String text) {
      super(
          x,
          y,
          w,
          h,
          Component.literal(text),
          b -> ((PanelButton) b).action.run(),
          DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
      boolean hover = active && isHoveredOrFocused();
      frame(
          gui,
          getX(),
          getY(),
          width,
          height,
          !active ? 0xff1b2020 : hover ? 0xff434a32 : 0xff2b3229,
          !active ? 0xff454941 : hover ? GOLD_TEXT : BORDER);
      String label = clipped(getMessage().getString(), width - 6);
      gui.text(
          font,
          label,
          getX() + (width - font.width(label)) / 2,
          getY() + (height - font.lineHeight) / 2 + 1,
          !active ? MUTED : hover ? 0xffffe5a1 : TEXT,
          true);
    }
  }

  private final class TearButton extends PanelButton {
    private final Tear tear;

    TearButton(int x, int y, Tear tear) {
      super(x, y, CELL, CELL, tear.displayName);
      this.tear = tear;
      action = () -> pick(tear);
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
      int have = count(tear);
      boolean picked = chosen.contains(tear), hover = isHoveredOrFocused();
      frame(
          gui,
          getX(),
          getY(),
          width,
          height,
          have > 0 ? (hover ? 0xff2c3530 : 0xff1b2420) : 0xff101515,
          picked ? GOLD_TEXT : have > 0 ? 0xff665c40 : 0xff2a302c);
      gui.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(tear), getX() + 2, getY() + 2, 16, 16);
      if (have == 0) {
        gui.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0xc0101515);
        gui.text(
            font,
            CampaignTooltipTheme.icon(CampaignTooltipTheme.Icon.LOCK, CampaignTooltipTheme.MUTED),
            getX() + 10,
            getY() + 1,
            0xffffffff,
            false);
      } else if (have > 1)
        gui.text(font, String.valueOf(have), getRight() - 6, getBottom() - 9, 0xffffffff, true);
      if (hover)
        gui.setComponentTooltipForNextFrame(
            font, tooltip(tear), mouseX, mouseY, CampaignTooltipTheme.STYLE);
    }
  }

  private final class SlotButton extends PanelButton {
    private final int slot;

    SlotButton(int x, int y, int slot) {
      super(x, y, CELL, CELL, slot == 0 ? "First tear" : "Second tear");
      this.slot = slot;
      action =
          () -> {
            if (slot < chosen.size()) chosen.remove(slot);
          };
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
      boolean filled = slot < chosen.size(), hover = isHoveredOrFocused();
      frame(
          gui,
          getX(),
          getY(),
          width,
          height,
          hover && filled ? 0xff2c3530 : 0xff101515,
          filled ? GOLD_TEXT : BORDER);
      if (!filled) return;
      var tear = chosen.get(slot);
      gui.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(tear), getX() + 2, getY() + 2, 16, 16);
      if (hover)
        gui.setComponentTooltipForNextFrame(
            font, tooltip(tear), mouseX, mouseY, CampaignTooltipTheme.STYLE);
    }
  }
}
