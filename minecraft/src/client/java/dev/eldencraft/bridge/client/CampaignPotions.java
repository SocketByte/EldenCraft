package dev.eldencraft.bridge.client;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;

/**
 * Values come from actual, server-ticked vanilla effects; expiration and milk need no second clock.
 */
public final class CampaignPotions {
  private CampaignPotions() {}

  private static int level(LivingEntity entity, Holder<MobEffect> type) {
    var effect = entity.getEffect(type);
    return effect == null ? 0 : Math.clamp(effect.getAmplifier() + 1, 0, 10);
  }

  public static double resistance(LivingEntity entity) {
    return Math.max(0, 1 - .2 * level(entity, MobEffects.RESISTANCE));
  }

  public static double speed(LivingEntity entity) {
    // Speed and Slowness are ADD_MULTIPLIED_TOTAL modifiers in vanilla.
    return Math.clamp(
        (1 + .2 * level(entity, MobEffects.SPEED))
            * Math.max(0, 1 - .15 * level(entity, MobEffects.SLOWNESS)),
        0,
        3);
  }

  public static double attackBonus(LivingEntity entity) {
    return Math.clamp(
        3 * level(entity, MobEffects.STRENGTH) - 4 * level(entity, MobEffects.WEAKNESS), -20, 15);
  }

  public static double jumpBonus(LivingEntity entity) {
    return Math.min(10, .1 * 20 * level(entity, MobEffects.JUMP_BOOST));
  }

  public static boolean slowFalling(LivingEntity entity) {
    return entity.hasEffect(MobEffects.SLOW_FALLING);
  }
}
