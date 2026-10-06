package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Pixel shield beside the crosshair while a shield is held: faint when available, filling while
 * vanilla's raise delay runs, solid once it blocks, and red while a broken guard recovers.
 */
public final class ShieldIndicator {
  // o = outline, x = fillable interior. Nine interior rows fill bottom-up.
  private static final String[] SHAPE = {
    "ooooooooo",
    "oxxxxxxxo",
    "oxxxxxxxo",
    "oxxxxxxxo",
    "oxxxxxxxo",
    "oxxxxxxxo",
    ".oxxxxxo.",
    ".oxxxxxo.",
    "..oxxxo..",
    "...oxo...",
    "....o....",
  };
  private static final int INTERIOR_ROWS = SHAPE.length - 2;
  private static final long POP_NANOS = 250_000_000L;

  private enum State {
    IDLE,
    RAISING,
    RAISED,
    BROKEN
  }

  private static State previous = State.IDLE;
  private static long popAt;

  private ShieldIndicator() {}

  public static void initialize() {
    HudElementRegistry.attachElementAfter(
        VanillaHudElements.CROSSHAIR,
        Identifier.fromNamespaceAndPath("eldencraft_bridge", "shield_indicator"),
        ShieldIndicator::extract);
  }

  private static ItemStack shield(Player player) {
    if (player.isUsingItem() && player.getUseItem().has(DataComponents.BLOCKS_ATTACKS))
      return player.getUseItem();
    for (var stack : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()})
      if (!stack.isEmpty() && !stack.isBroken() && stack.has(DataComponents.BLOCKS_ATTACKS))
        return stack;
    return ItemStack.EMPTY;
  }

  private static void extract(GuiGraphicsExtractor gui, DeltaTracker delta) {
    var client = Minecraft.getInstance();
    var player = client.player;
    if (player == null
        || !CampaignCombat.active(player)
        || client.gui.hud.isHidden()
        || client.gui.screen() != null
        || player.isSpectator()) {
      previous = State.IDLE;
      return;
    }
    var stack = shield(player);
    if (stack.isEmpty()) {
      previous = State.IDLE;
      return;
    }
    float partial = delta.getGameTimeDeltaPartialTick(true);
    var settings = CampaignConfig.current().hud.stamina();
    var rules = CampaignConfig.current().stamina;
    double cooldown = player.getCooldowns().getCooldownPercent(stack, partial);
    boolean guard = CampaignCombat.shieldReady(player);
    boolean using = player.isUsingItem() && player.getUseItem() == stack;
    State state;
    double progress;
    if (cooldown > 0 || !guard) {
      state = State.BROKEN;
      double recovery =
          rules.guardRecovery() > 0
              ? CampaignCombat.stamina(player) / rules.guardRecovery()
              : guard ? 1 : 0;
      progress = cooldown > 0 ? 1 - cooldown : recovery;
    } else if (using && player.isBlocking()) {
      state = State.RAISED;
      progress = 1;
    } else if (using) {
      state = State.RAISING;
      var blocks = stack.get(DataComponents.BLOCKS_ATTACKS);
      int delay = blocks == null ? 0 : blocks.blockDelayTicks();
      progress = delay <= 0 ? 1 : player.getTicksUsingItem(partial) / delay;
    } else {
      state = State.IDLE;
      progress = 0;
    }
    long now = System.nanoTime();
    // Flash when the shield starts blocking or a broken guard becomes usable again.
    if ((state == State.RAISED && previous != State.RAISED)
        || (previous == State.BROKEN && state != State.BROKEN)) popAt = now;
    previous = state;
    double pop = now - popAt < POP_NANOS ? 1 - (double) (now - popAt) / POP_NANOS : 0;

    int fill =
        switch (state) {
          case BROKEN -> settings.exhausted();
          case RAISING -> dim(settings.fill(), .7);
          default -> settings.fill();
        };
    int empty = state == State.IDLE ? 0x40000000 | (settings.fill() & 0xffffff) : 0x90101010;
    int outline = state == State.IDLE ? 0x90000000 : 0xe0000000;
    int filledRows = (int) Math.round(Math.clamp(progress, 0, 1) * INTERIOR_ROWS);
    int x = gui.guiWidth() / 2 + 9, y = gui.guiHeight() / 2 - SHAPE.length / 2;
    for (int row = 0; row < SHAPE.length; row++) {
      String line = SHAPE[row];
      boolean filled = row >= 1 && row <= INTERIOR_ROWS && row > INTERIOR_ROWS - filledRows;
      for (int column = 0; column < line.length(); column++) {
        char pixel = line.charAt(column);
        if (pixel == '.') continue;
        int color = pixel == 'o' ? outline : filled ? fill : empty;
        if (pop > 0 && pixel == 'x') color = brighten(color, pop);
        gui.fill(x + column, y + row, x + column + 1, y + row + 1, color);
      }
    }
  }

  private static int dim(int color, double amount) {
    int r = (int) (((color >>> 16) & 255) * amount);
    int g = (int) (((color >>> 8) & 255) * amount);
    int b = (int) ((color & 255) * amount);
    return (color & 0xff000000) | (r << 16) | (g << 8) | b;
  }

  private static int brighten(int color, double amount) {
    int a = Math.max((color >>> 24) & 255, (int) (255 * amount));
    int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
    r += (int) ((255 - r) * amount * .6);
    g += (int) ((255 - g) * amount * .6);
    b += (int) ((255 - b) * amount * .6);
    return (a << 24) | (r << 16) | (g << 8) | b;
  }
}
