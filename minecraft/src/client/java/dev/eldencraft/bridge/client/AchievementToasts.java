package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.client.mixin.AchievementToastAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.TutorialToast;

/** Progression presentation is quiet throughout the dedicated world, including host menus. */
public final class AchievementToasts {
  private AchievementToasts() {}

  static boolean active(boolean loaded, boolean offline, String name, boolean sharedDimension) {
    return loaded && offline && (EldenCraftWorld.supportedName(name) || sharedDimension);
  }

  public static boolean active(Minecraft client) {
    var server = client.getSingleplayerServer();
    return active(
        client.level != null && server != null,
        server != null && !server.isPublished(),
        server == null ? null : server.getWorldData().getLevelName(),
        SharedWorldClient.inSharedDimension());
  }

  public static boolean suppress(Minecraft client, Toast toast) {
    var hud = CampaignConfig.current().hud;
    return suppress(
        active(client),
        hud.hideAchievementPopups(),
        hud.hideRecipePopups(),
        hud.hideTutorialPopups(),
        toast);
  }

  static boolean suppress(
      boolean active, boolean achievements, boolean recipes, boolean tutorials, Toast toast) {
    return active
        && ((achievements && toast instanceof AdvancementToast)
            || (recipes && toast instanceof RecipeToast)
            || (tutorials && toast instanceof TutorialToast));
  }

  public static void discardQueued(Minecraft client, Deque<Toast> queued) {
    var hud = CampaignConfig.current().hud;
    discardQueued(
        active(client),
        hud.hideAchievementPopups(),
        hud.hideRecipePopups(),
        hud.hideTutorialPopups(),
        queued);
  }

  static void discardQueued(
      boolean active,
      boolean achievements,
      boolean recipes,
      boolean tutorials,
      Deque<Toast> queued) {
    if (active)
      queued.removeIf(
          toast -> {
            if (!suppress(true, achievements, recipes, tutorials, toast)) return false;
            dismiss(toast);
            return true;
          });
  }

  public static void discardVisible(Minecraft client, List<?> visible, BitSet occupiedSlots) {
    var hud = CampaignConfig.current().hud;
    discardVisible(
        active(client),
        hud.hideAchievementPopups(),
        hud.hideRecipePopups(),
        hud.hideTutorialPopups(),
        visible,
        occupiedSlots);
  }

  static void discardVisible(
      boolean active,
      boolean achievements,
      boolean recipes,
      boolean tutorials,
      List<?> visible,
      BitSet occupiedSlots) {
    if (!active) return;
    var iterator = visible.iterator();
    while (iterator.hasNext()) {
      var entry = (AchievementToastAccessor) iterator.next();
      var toast = entry.eldencraft$toast();
      if (!suppress(true, achievements, recipes, tutorials, toast)) continue;
      int first = entry.eldencraft$firstSlotIndex();
      occupiedSlots.clear(first, first + entry.eldencraft$occupiedSlotCount());
      iterator.remove();
      dismiss(toast);
      toast.onFinishedRendering();
    }
  }

  public static void dismiss(Toast toast) {
    if (toast instanceof TutorialToast tutorial) tutorial.hide();
  }
}
