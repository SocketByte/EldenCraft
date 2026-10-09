package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/** One genuine spear component invocation owns all its piercing hits and one stamina charge. */
public final class CampaignWeapons {
  private static final class Attack {
    final ServerPlayer player;
    final boolean kinetic;
    boolean charged;

    Attack(ServerPlayer player, boolean kinetic) {
      this.player = player;
      this.kinetic = kinetic;
    }
  }

  private static final ThreadLocal<Attack> CURRENT = new ThreadLocal<>();
  private static final Map<UUID, Long> LAST_CHARGE = new HashMap<>();

  private CampaignWeapons() {}

  public static boolean inComponent(ServerPlayer player) {
    var a = CURRENT.get();
    return a != null && a.player == player;
  }

  public static boolean permitStab(ServerPlayer player, Entity target, boolean damage) {
    var a = CURRENT.get();
    if (a == null || a.player != player) return !(target instanceof CombatProxyEntity);
    if (damage && !a.charged) {
      long tick = player.level().getGameTime();
      Long last = LAST_CHARGE.get(player.getUUID());
      if (a.kinetic && last != null && tick >= last && tick - last < 10) return false;
      if (a.kinetic && !CampaignCombat.spend(player, "spear")) return false;
      if (a.kinetic) LAST_CHARGE.put(player.getUUID(), tick);
      a.charged = true;
    }
    return !(target instanceof CombatProxyEntity proxy)
        || ProxyCombatAuthority.beginStab(player, proxy);
  }

  public static void component(
      LivingEntity entity, EquipmentSlot slot, boolean kinetic, Runnable vanilla) {
    if (!(entity instanceof ServerPlayer player) || !CampaignCombat.active(player)) {
      vanilla.run();
      return;
    }
    if (CURRENT.get() != null
        || ProxyCombatAuthority.proxyAttack()
        || slot != EquipmentSlot.MAINHAND
        || !WorldProjectiles.permitItem(player)) return;
    if (!kinetic && !CampaignCombat.attack(player)) return;
    var weapon = player.getItemBySlot(slot);
    String item = BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString();
    int before = weapon.getDamageValue(), maximum = weapon.getMaxDamage();
    Vec3 velocity = player.getDeltaMovement();
    player.setDeltaMovement(CampaignMotion.velocity(player));
    var attack = new Attack(player, kinetic);
    attack.charged = !kinetic;
    CURRENT.set(attack);
    boolean completed = false;
    try {
      vanilla.run();
      completed = true;
    } finally {
      CURRENT.remove();
      player.setDeltaMovement(velocity);
      ProxyCombatAuthority.finish(
          item, before, weapon.isEmpty() ? maximum : weapon.getDamageValue(), completed);
    }
  }

  public static void clear() {
    LAST_CHARGE.clear();
  }
}
