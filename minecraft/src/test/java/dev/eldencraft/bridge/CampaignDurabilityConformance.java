package dev.eldencraft.bridge;

import dev.eldencraft.bridge.client.CampaignCombat;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Checks the actual vanilla durability path used by attacks, mining and native shield receipts. */
final class CampaignDurabilityConformance {
  private static int checks;

  private CampaignDurabilityConformance() {}

  static int verify(CampaignConfig shipped) throws Exception {
    checks = 0;
    for (var item : List.of(Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_SPEAR, Items.SHIELD)) {
      var stack = new ItemStack(item);
      stack.setDamageValue(stack.getMaxDamage() - 1);
      CampaignCombat.tune(stack);
      check(stack.has(DataComponents.UNBREAKABLE), "starting weapon and shield are unbreakable");
      check(!stack.isDamageableItem() && !stack.isBroken(), "worn equipment remains usable");
      int damage = stack.getDamageValue();
      // The real path exits on UNBREAKABLE before requiring a live level or player.
      stack.hurtAndBreak(
          Integer.MAX_VALUE,
          (ServerLevel) null,
          null,
          ignored -> {
            throw new AssertionError("unbreakable equipment invoked its break callback");
          });
      check(stack.getCount() == 1 && stack.getDamageValue() == damage, "wear cannot consume the item");
      var saved = stack.getComponents();
      CampaignCombat.tune(stack);
      check(saved.equals(stack.getComponents()), "repeated tuning preserves all components");
    }
    for (var item : List.of(Items.STONE_SWORD, Items.IRON_SWORD, Items.WOODEN_PICKAXE, Items.BOW)) {
      var stack = new ItemStack(item);
      CampaignCombat.tune(stack);
      check(!stack.has(DataComponents.UNBREAKABLE) && stack.isDamageableItem(), "other gear still wears");
    }
    var disabled = shipped.raw();
    disabled.addProperty("enabled", false);
    try {
      CampaignConfig.install(CampaignConfig.parse(disabled));
      var shield = new ItemStack(Items.SHIELD);
      CampaignCombat.tune(shield);
      check(shield.isDamageableItem(), "disabled campaigns preserve vanilla shield durability");
    } finally {
      CampaignConfig.install(shipped);
    }
    return checks;
  }

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
}
