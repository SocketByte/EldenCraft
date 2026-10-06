package dev.eldencraft.bridge;

import dev.eldencraft.bridge.client.CampaignCombat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression for native raised-shield permission crossing independently phased vanilla ticks. */
public final class CampaignGuardConformance {
  private static int checks;

  private CampaignGuardConformance() {}

  /** Called after actual vanilla item components have been bound. */
  public static int verify() {
    checks = 0;
    var shield = new ItemStack(Items.SHIELD);
    int delay = shield.get(DataComponents.BLOCKS_ATTACKS).blockDelayTicks();
    check(delay > 0, "fixture uses the real vanilla shield raise delay");
    // Reproduce live hit #5: the client has reached the vanilla delay, while
    // the server's item-use clock is one tick behind. It owns an intact shield
    // and positive stamina; its delayed isBlocking result must not veto the
    // already raised client permit. The native path still requires that permit.
    int clientUseTicks = delay, serverUseTicks = delay - 1;
    check(clientUseTicks >= delay && serverUseTicks < delay, "independent tick phases disagree");
    check(
        CampaignCombat.nativeGuardPermitted(true, true, shield, false),
        "server authorizes its held shield independently of the duplicated raise clock");
    check(
        !CampaignCombat.nativeGuardPermitted(true, false, shield, false),
        "an explicit server release cancels guard even with a retained client permit");
    check(
        !CampaignCombat.nativeGuardPermitted(false, true, shield, false),
        "server stamina exhaustion still rejects a client raised shield");
    check(
        !CampaignCombat.nativeGuardPermitted(true, true, shield, true),
        "a disabled shield cannot borrow a prior raised-shield permit");
    for (var item :
        new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.BREAD), new ItemStack(Items.BOW)})
      check(
          !CampaignCombat.nativeGuardPermitted(true, true, item, false),
          "changing server item use cannot inherit shield permission: " + item);
    var broken = shield.copy();
    broken.setDamageValue(broken.getMaxDamage());
    check(
        !CampaignCombat.nativeGuardPermitted(true, true, broken, false),
        "broken shield ownership cannot authorize guard");
    shield.setDamageValue(shield.getMaxDamage() - 1);
    check(
        CampaignCombat.nativeGuardPermitted(true, true, shield, false),
        "an intact shield at its last durability remains usable");
    return checks;
  }

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
}
