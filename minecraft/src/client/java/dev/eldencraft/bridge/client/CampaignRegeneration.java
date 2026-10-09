package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.GoldenAppleAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Actual vanilla regeneration from combat potions, totems and enchanted golden apples. Elden Ring
 * owns the shared health pool and the next host update overwrites Minecraft health, so that
 * effect's actual healing is reported as native healing, exactly like food.
 */
public final class CampaignRegeneration {
  private CampaignRegeneration() {}

  /** Vanilla has just applied a totem's or an enchanted golden apple's effects to this player. */
  public static void granted(ServerPlayer player) {
    var effect = player.getEffect(MobEffects.REGENERATION);
    // Vanilla extends a plain apple's running regeneration in place, which ends that apple's
    // receipts; this longer effect now reports the healing instead.
    if (effect != null && GoldenAppleAuthority.owns(player, effect)) GoldenAppleAuthority.clear();
  }

  /** One server tick of that regeneration; its actual healing is native healing too. */
  public static void regeneration(ServerPlayer player, MobEffectInstance effect, float healed) {
    // The caller is the actual REGENERATION effect tick. Apples have their own
    // receipts and are excluded at that call site; potions, stew and beacons
    // now use the same genuine-heal path as enchanted apples and totems.
    if (player.getEffect(MobEffects.REGENERATION) != effect
        || !CampaignCombat.active(player)
        || player.isCreative()
        || player.isSpectator()) return;
    if (Float.isFinite(healed) && healed > 0) CampaignBridge.recordHealing(player, healed);
  }

  public static void clear() {
    GoldenAppleAuthority.clear();
  }
}
