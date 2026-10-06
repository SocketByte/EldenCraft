package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.client.mixin.AchievementToastAccessor;
import java.util.*;
import net.minecraft.client.gui.components.toasts.*;

/** Checks real toast type filtering and dedicated-world scope without launching Minecraft. */
public final class AchievementToastsConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static <T extends Toast> T toast(Class<T> type) throws Exception {
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private record Visible(Toast toast, int first, int count) implements AchievementToastAccessor {
    @Override
    public Toast eldencraft$toast() {
      return toast;
    }

    @Override
    public int eldencraft$firstSlotIndex() {
      return first;
    }

    @Override
    public int eldencraft$occupiedSlotCount() {
      return count;
    }
  }

  public static int verify() throws Exception {
    checks = 0;
    // Toast.Visibility loads vanilla UI SoundEvents. Bootstrap the real registries before
    // TutorialToast.hide() touches that enum, as the running client does during startup.
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    check(
        AchievementToasts.active(true, true, "EldenCraft", false),
        "dedicated world is quiet without any native lease or focus requirement");
    check(
        AchievementToasts.active(true, true, "EldenCraft Passthrough Lab", false),
        "legacy dedicated saves stay quiet");
    check(
        AchievementToasts.active(true, true, "Custom campaign", true),
        "shared dimension in a renamed campaign is quiet");
    check(
        !AchievementToasts.active(true, true, "Survival", false),
        "unrelated vanilla saves retain advancement toasts");
    check(
        !AchievementToasts.active(false, true, "EldenCraft", false),
        "title and disconnected sessions retain vanilla behavior");
    check(
        !AchievementToasts.active(true, false, "EldenCraft", true),
        "multiplayer and published worlds retain vanilla behavior");
    var advancement = toast(AdvancementToast.class);
    var recipe = toast(RecipeToast.class);
    var alert = toast(SystemToast.class);
    var tutorial = toast(TutorialToast.class);
    var queued = new ArrayDeque<Toast>(List.of(recipe, advancement, alert, advancement));
    check(
        AchievementToasts.suppress(true, true, false, false, advancement),
        "achievement popup flag suppresses advancements independently");
    check(
        AchievementToasts.suppress(true, false, true, false, recipe),
        "recipe popup flag suppresses recipes independently");
    check(
        !AchievementToasts.suppress(true, true, true, true, alert),
        "system alerts remain visible with all presentation filters enabled");
    check(
        AchievementToasts.suppress(true, false, false, true, tutorial),
        "tutorial flag suppresses movement instructions independently");
    check(
        !AchievementToasts.suppress(true, true, true, false, tutorial),
        "tutorial opt-out keeps movement instructions");
    AchievementToasts.discardQueued(false, true, true, true, queued);
    check(queued.size() == 4, "advancements queued in unrelated sessions are preserved");
    AchievementToasts.discardQueued(true, true, false, false, queued);
    check(
        List.copyOf(queued).equals(List.of(recipe, alert)),
        "queued advancements are removed while recipe and system alerts retain order");
    queued.add(advancement);
    AchievementToasts.discardQueued(true, false, true, false, queued);
    check(
        List.copyOf(queued).equals(List.of(alert, advancement)),
        "recipe filter preserves advancement and system alerts when achievement suppression is"
            + " disabled");
    AchievementToasts.discardQueued(true, false, false, false, queued);
    check(queued.size() == 2, "both opt-outs preserve all toast categories");
    AchievementToasts.discardQueued(true, true, true, true, queued);
    check(
        List.copyOf(queued).equals(List.of(alert)), "both progression filters keep system alerts");
    AchievementToasts.discardQueued(true, true, true, true, queued);
    check(
        queued.size() == 1,
        "repeated host focus/GUI ticks cannot revive suppressed progression popups");
    queued.add(tutorial);
    AchievementToasts.discardQueued(true, false, false, true, queued);
    check(
        List.copyOf(queued).equals(List.of(alert))
            && tutorial.getWantedVisibility() == Toast.Visibility.HIDE,
        "deferred tutorial is hidden and removed while system warning remains");
    var a = new Visible(advancement, 0, 1);
    var r = new Visible(recipe, 1, 1);
    var t = new Visible(tutorial, 2, 2);
    var s = new Visible(alert, 4, 1);
    var visible = new ArrayList<>(List.of(a, r, t, s));
    var slots = new BitSet(5);
    slots.set(0, 5);
    AchievementToasts.discardVisible(false, true, true, true, visible, slots);
    check(
        visible.size() == 4 && slots.cardinality() == 5,
        "visible toasts in unrelated worlds retain all slots");
    AchievementToasts.discardVisible(true, true, false, true, visible, slots);
    check(
        visible.equals(List.of(r, s)),
        "already-visible achievement and indefinite tutorial are removed without removing recipes"
            + " or warnings");
    check(
        !slots.get(0) && slots.get(1) && !slots.get(2) && !slots.get(3) && slots.get(4),
        "visible removal releases exact occupied ranges, including multi-slot tutorial");
    AchievementToasts.discardVisible(true, false, false, false, visible, slots);
    check(
        visible.equals(List.of(r, s)) && slots.cardinality() == 2,
        "disabled categories keep their visible entries and slots");
    AchievementToasts.discardVisible(true, false, true, false, visible, slots);
    check(
        visible.equals(List.of(s)) && slots.cardinality() == 1 && slots.get(4),
        "recipe filter also removes an already-visible recipe while retaining system warning");
    AchievementToasts.discardVisible(true, true, true, true, visible, slots);
    check(
        visible.equals(List.of(s)) && slots.cardinality() == 1,
        "repeated render/update cleanup cannot revive removed tutorials or corrupt remaining"
            + " slots");
    var instance =
        Class.forName("net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance");
    check(
        instance.getDeclaredField("toast").getType() == Toast.class,
        "pinned ToastInstance exposes the exact toast accessor field");
    check(
        instance.getDeclaredField("firstSlotIndex").getType() == int.class
            && instance.getDeclaredField("occupiedSlotCount").getType() == int.class,
        "pinned ToastInstance exposes exact slot range fields");
    return checks;
  }

  public static void main(String[] args) throws Exception {
    System.out.println("AchievementToastsConformance: " + verify() + " checks passed");
  }
}
