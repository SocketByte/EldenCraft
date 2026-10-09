package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.GoldenAppleAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Vanilla regeneration from a totem of undying or an enchanted golden apple. Elden Ring owns the
 * shared health pool and the next host update overwrites Minecraft health, so that effect's actual
 * healing is reported as native healing, exactly like food.
 */
public final class CampaignRegeneration {
  private record Grant(ServerPlayer player, MobEffectInstance effect) {}

  // Integrated-server thread only.
  private static Grant grant;

  private CampaignRegeneration() {}

  /** Vanilla has just applied a totem's or an enchanted golden apple's effects to this player. */
  public static void granted(ServerPlayer player) {
    var effect = player.getEffect(MobEffects.REGENERATION);
    // Vanilla extends a plain apple's running regeneration in place, which ends that apple's
    // receipts; this longer effect now reports the healing instead.
    if (effect != null && GoldenAppleAuthority.owns(player, effect)) GoldenAppleAuthority.clear();
    grant =
        effect == null
                || !CampaignCombat.active(player)
                || player.isCreative()
                || player.isSpectator()
            ? null
            : new Grant(player, effect);
  }

  /** One server tick of that regeneration; its actual healing is native healing too. */
  public static void regeneration(ServerPlayer player, MobEffectInstance effect, float healed) {
    var g = grant;
    if (g == null || g.player() != player) return;
    if (g.effect() != effect
        || player.getEffect(MobEffects.REGENERATION) != effect
        || !CampaignCombat.active(player)) {
      grant = null;
      return;
    }
    if (Float.isFinite(healed) && healed > 0) CampaignBridge.recordHealing(player, healed);
  }

  public static void clear() {
    grant = null;
  }
}
