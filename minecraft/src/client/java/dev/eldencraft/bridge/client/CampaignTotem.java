package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.client.mixin.CampaignTotemInvoker;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Vanilla totems of undying for one shared health pool. Elden Ring owns death: native keeps a
 * lethal hit at one Minecraft health point while a totem is held, then the server spends that totem
 * here exactly as vanilla does. Minecraft's own hazards reach vanilla's death protection directly.
 * Either way the totem's regeneration heals Elden Ring through {@link CampaignRegeneration}, and
 * its absorption is spent by native hits.
 */
public final class CampaignTotem {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_campaign");

  private CampaignTotem() {}

  /** Death-protection items in the hands vanilla checks, as native may spend them. */
  public static int held(ServerPlayer player) {
    if (player.isCreative() || player.isSpectator()) return 0;
    int count = 0;
    for (var hand : InteractionHand.values())
      if (player.getItemInHand(hand).has(DataComponents.DEATH_PROTECTION)) count++;
    return count;
  }

  /** Native saved the player from a lethal hit: vanilla's own totem use, effects and animation. */
  public static void spend(ServerPlayer player) {
    if (((CampaignTotemInvoker) player)
        .eldencraft$checkTotemDeathProtection(player.damageSources().generic()))
      LOG.info("Totem of undying saved the player from a lethal Elden Ring hit");
    else LOG.warn("Elden Ring hit was survived on a totem no longer held; none was spent");
  }
}
