package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.client.mixin.CampaignArmorInvoker;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** All expenditure is on the integrated server; the client only displays its published balance. */
public final class CampaignCombat {
  private static final Map<UUID, CampaignStamina> ACCOUNTS = new HashMap<>();

  private record PunchCredit(long tick, int count) {}

  private static final Map<UUID, PunchCredit> PUNCH_CREDITS = new HashMap<>();
  private static volatile UUID activePlayer;
  private static volatile double displayedStamina, displayedMaximum;
  private static volatile boolean displayedGuard;
  private static volatile long guardBreakNanos;
  private static long guardSession, guardSequence;
  private static double guardDamage;

  private record GuardEquipment(ItemStack stack, InteractionHand hand) {}

  private static GuardEquipment previousGuard;
  private static long damageSession, damageSequence;

  private CampaignCombat() {}

  public static void validateRegistry(CampaignConfig config) throws IOException {
    var ids = new HashSet<String>();
    ids.addAll(config.weapons.keySet());
    ids.addAll(config.armors.keySet());
    for (var reward : config.starterItems) ids.add(reward.item());
    for (var boss : config.bosses) for (var reward : boss.rewards()) ids.add(reward.item());
    for (var entry : config.enemyLoot.entries()) ids.add(entry.item());
    for (var shop : CampaignShopCatalog.parse(config.raw()).shops())
      for (var offer : shop.offers()) ids.add(offer.item());
    for (String id : ids) {
      var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
      if (item == null || item == Items.AIR)
        throw new IOException("Unknown Minecraft item in campaign JSON: " + id);
    }
    var blocks = new HashSet<>(config.mining.allowedBlocks());
    for (var zone : config.mining.resourceZones()) blocks.add(zone.block());
    for (String id : blocks) {
      var block = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(id));
      if (block == null || block == net.minecraft.world.level.block.Blocks.AIR)
        throw new IOException("Unknown Minecraft block in campaign mining JSON: " + id);
    }
  }

  public static long guardAcknowledgedSequence() {
    return guardSequence;
  }

  public static double guardAcknowledgedDamage() {
    return guardDamage;
  }

  public static long damageAcknowledgedSequence() {
    return damageSequence;
  }

  public static boolean active(Player player) {
    return player != null
        && CampaignConfig.current().enabled()
        && player.getUUID().equals(activePlayer)
        && player.level().dimension().equals(SharedWorldBlocks.DIMENSION)
        && CampaignBridge.fresh();
  }

  public static double stamina(Player player) {
    if (!(player instanceof ServerPlayer)) return displayedStamina;
    var account = ACCOUNTS.get(player.getUUID());
    return account == null ? 0 : account.current();
  }

  public static double maximum(Player player) {
    if (!(player instanceof ServerPlayer)) return displayedMaximum;
    var account = ACCOUNTS.get(player.getUUID());
    return account == null ? 0 : account.maximum();
  }

  public static boolean shieldReady(Player player) {
    if (!active(player)) return false;
    if (!(player instanceof ServerPlayer)) return displayedGuard;
    var account = ACCOUNTS.get(player.getUUID());
    return account != null && account.guardReady();
  }

  /**
   * Server permission for the native shield path. The client mailbox already waits for vanilla's
   * actual raise delay; checking server isBlocking again races the two independent tick phases.
   * Keep server stamina, held-item ownership, durability and cooldown as separate requirements.
   */
  public static boolean nativeGuardPermitted(ServerPlayer player) {
    var used = player.getUseItem();
    return nativeGuardPermitted(
        shieldReady(player), player.isUsingItem(), used, player.getCooldowns().isOnCooldown(used));
  }

  public static boolean nativeGuardPermitted(
      boolean staminaReady, boolean usingItem, ItemStack used, boolean cooldown) {
    return staminaReady
        && usingItem
        && !used.isEmpty()
        && !used.isBroken()
        && used.has(DataComponents.BLOCKS_ATTACKS)
        && !cooldown;
  }

  public static void tick(ServerPlayer player, double maximum, CampaignBridge.Snapshot host) {
    if (activePlayer != null && !activePlayer.equals(player.getUUID())) clear();
    activePlayer = player.getUUID();
    var account = ACCOUNTS.computeIfAbsent(activePlayer, id -> new CampaignStamina(maximum));
    account.capacity(maximum);
    var config = CampaignConfig.current();
    var blocking = player.getItemBlockingWith();
    if (blocking != null && !blocking.isEmpty())
      previousGuard = new GuardEquipment(blocking, player.getUsedItemHand());
    if (damageSession != host.session()) {
      damageSession = host.session();
      damageSequence =
          host.damageEvents().stream().mapToLong(CampaignBridge.DamageEvent::seq).max().orElse(0);
    } else {
      for (var event : host.damageEvents()) {
        if (event.seq() <= damageSequence) continue;
        if (event.blocked()) {
          if (previousGuard != null && owns(player, previousGuard.stack())) {
            var blocks = previousGuard.stack().get(DataComponents.BLOCKS_ATTACKS);
            if (blocks != null)
              blocks.hurtBlockingItem(
                  player.level(),
                  previousGuard.stack(),
                  player,
                  previousGuard.hand(),
                  (float) event.rawDamage());
          }
        } else {
          ((CampaignArmorInvoker) player)
              .eldencraft$campaignArmorWear(
                  player.damageSources().generic(), (float) event.rawDamage());
        }
        damageSequence = event.seq();
      }
    }
    if (guardSession != host.session()) {
      guardSession = host.session();
      guardSequence = host.guardSeq();
      guardDamage = host.guardDamage();
    } else if (host.guardSeq() >= guardSequence && host.guardDamage() >= guardDamage) {
      if (account.guard(
          host.guardDamage() - guardDamage, host.guardSeq() - guardSequence, config.stamina))
        guardBroken(player);
      guardSequence = host.guardSeq();
      guardDamage = host.guardDamage();
    } else {
      guardSequence = host.guardSeq();
      guardDamage = host.guardDamage();
    }
    if (!account.guardReady() && player.isBlocking()) player.stopUsingItem();
    boolean busy =
        player.isBlocking()
            || (player.isUsingItem()
                && (player.getUseItem().is(Items.BOW) || player.getUseItem().is(Items.CROSSBOW)));
    account.tick(.05, busy, config.stamina);
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) tune(inventory.getItem(i));
    for (var slot : player.containerMenu.slots) tune(slot.getItem());
    publish(account);
  }

  public static void clear() {
    activePlayer = null;
    displayedStamina = displayedMaximum = 0;
    displayedGuard = false;
  }

  public static void rest(Player player) {
    if (player == null) return;
    var account = ACCOUNTS.get(player.getUUID());
    if (account != null) {
      account.rest();
      publish(account);
    }
  }

  public static void reset() {
    clear();
    ACCOUNTS.clear();
    PUNCH_CREDITS.clear();
    guardSession = guardSequence = 0;
    guardDamage = 0;
    damageSession = damageSequence = 0;
    previousGuard = null;
    guardBreakNanos = 0;
  }

  private static void publish(CampaignStamina account) {
    displayedStamina = account.current();
    displayedMaximum = account.maximum();
    displayedGuard = account.guardReady();
  }

  private static boolean owns(ServerPlayer player, ItemStack stack) {
    if (stack.isEmpty()) return false;
    var inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getContainerSize(); slot++)
      if (inventory.getItem(slot) == stack) return true;
    return false;
  }

  public static String kind(ItemStack stack) {
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    if (id.endsWith("_sword")) return "sword";
    if (id.endsWith("_axe")) return "axe";
    if (stack.is(Items.BOW)) return "bow";
    if (stack.is(Items.CROSSBOW)) return "crossbow";
    return "other";
  }

  public static boolean canAttackClient(Player player) {
    return CampaignProgression.characterPermitted(player)
        && (!active(player)
            || displayedStamina
                >= CampaignConfig.current().stamina.cost(kind(player.getMainHandItem())));
  }

  /** Includes misses. The subsequent vanilla Punch packet must not charge a landed attack twice. */
  public static boolean attack(Player player) {
    if (!CampaignProgression.characterPermitted(player)) return false;
    if (!active(player) || !(player instanceof ServerPlayer)) return true;
    if (!spend(player, kind(player.getMainHandItem()))) return false;
    long tick = player.level().getGameTime();
    var old = PUNCH_CREDITS.get(player.getUUID());
    int count = old != null && tick >= old.tick() && tick - old.tick() <= 10 ? old.count() : 0;
    PUNCH_CREDITS.put(player.getUUID(), new PunchCredit(tick, Math.min(16, count + 1)));
    return true;
  }

  public static boolean consumeAttackPunch(ServerPlayer player) {
    var credit = PUNCH_CREDITS.get(player.getUUID());
    if (credit == null) return false;
    long age = player.level().getGameTime() - credit.tick();
    if (age < 0 || age > 10) {
      PUNCH_CREDITS.remove(player.getUUID());
      return false;
    }
    if (credit.count() == 1) PUNCH_CREDITS.remove(player.getUUID());
    else PUNCH_CREDITS.put(player.getUUID(), new PunchCredit(credit.tick(), credit.count() - 1));
    return true;
  }

  public static boolean punch(ServerPlayer player) {
    if (!active(player)) return true;
    var account = ACCOUNTS.get(player.getUUID());
    return account != null
        && account.canSpend(CampaignConfig.current().stamina.cost(kind(player.getMainHandItem())));
  }

  public static void punched(ServerPlayer player, boolean accepted) {
    if (accepted && active(player)) spend(player, kind(player.getMainHandItem()));
  }

  public static boolean spend(Player player, String kind) {
    if (!active(player) || !(player instanceof ServerPlayer)) return true;
    var account = ACCOUNTS.get(player.getUUID());
    boolean accepted =
        account != null && account.spend(CampaignConfig.current().stamina.cost(kind));
    if (account != null) publish(account);
    return accepted;
  }

  /**
   * Charges a vanilla blocked hit. Returns the fraction of it the remaining stamina absorbed; the
   * caller lets the rest through.
   */
  public static double guard(Player player, double damage) {
    if (!active(player) || !(player instanceof ServerPlayer server)) return 1;
    var account = ACCOUNTS.get(player.getUUID());
    if (account == null) return 1;
    var rules = CampaignConfig.current().stamina;
    double absorbed = CampaignStamina.absorbed(account.current(), damage, rules);
    if (account.guard(damage, 1, rules)) guardBroken(server);
    if (!account.guardReady()) player.stopUsingItem();
    publish(account);
    return absorbed;
  }

  /** Vanilla's shield-disable feedback: break sound, lowered shield and a hotbar cooldown. */
  private static void guardBroken(ServerPlayer player) {
    guardBreakNanos = System.nanoTime();
    var seconds =
        (float)
            Math.clamp(CampaignStamina.recoverySeconds(CampaignConfig.current().stamina), .5, 5);
    var stack = player.getItemBlockingWith();
    if ((stack == null || stack.isEmpty()) && previousGuard != null)
      stack = owns(player, previousGuard.stack()) ? previousGuard.stack() : null;
    var blocks = stack == null ? null : stack.get(DataComponents.BLOCKS_ATTACKS);
    if (blocks != null && blocks.disableSound().isPresent()) {
      blocks.disable(player.level(), player, seconds, stack);
    } else {
      player.stopUsingItem();
      player
          .level()
          .playSound(
              null,
              player.getX(),
              player.getY(),
              player.getZ(),
              SoundEvents.SHIELD_BREAK,
              SoundSource.PLAYERS,
              1f,
              .8f + player.getRandom().nextFloat() * .4f);
    }
  }

  /** Client HUD: nanoTime of the latest guard break, or 0. */
  public static long guardBreakNanos() {
    return guardBreakNanos;
  }

  public static double armorReduction(Player player) {
    return armorReduction(player::getItemBySlot);
  }

  /** Sum the configured percentages of intact pieces worn in their actual armor slots. */
  public static double armorReduction(
      java.util.function.Function<EquipmentSlot, ItemStack> equipment) {
    double total = 0;
    var rules = CampaignConfig.current().armors;
    for (var slot :
        List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
      var stack = equipment.apply(slot);
      if (stack.isEmpty() || stack.isBroken()) continue;
      var equippable = stack.get(DataComponents.EQUIPPABLE);
      if (equippable == null || equippable.slot() != slot) continue;
      var armor = rules.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
      if (armor != null) total += armor.armor();
    }
    // Read config directly: Minecraft's armor attribute caps at 30 and cannot
    // carry percentage totals up to 100 without losing mitigation.
    return Math.clamp(total, 0, 100);
  }

  public static double damageAfterArmor(double raw, double reductionPercent) {
    return raw * (1 - Math.clamp(reductionPercent, 0, 100) / 100);
  }

  public static void tune(ItemStack stack) {
    if (stack.isEmpty()) return;
    var config = CampaignConfig.current();
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    var rule = config.weapons.get(id);
    var armor = config.armors.get(id);
    var equippable = stack.get(DataComponents.EQUIPPABLE);
    if (rule == null && (armor == null || equippable == null)) return;
    var old = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
    var builder = ItemAttributeModifiers.builder();
    for (var entry : old.modifiers()) {
      if (rule != null
          && (entry.attribute().equals(Attributes.ATTACK_DAMAGE)
              || entry.attribute().equals(Attributes.ATTACK_SPEED))) continue;
      if (armor != null
          && equippable != null
          && (entry.attribute().equals(Attributes.ARMOR)
              || entry.attribute().equals(Attributes.ARMOR_TOUGHNESS)
              || entry.attribute().equals(Attributes.KNOCKBACK_RESISTANCE))) continue;
      builder.add(entry.attribute(), entry.modifier(), entry.slot(), entry.display());
    }
    if (rule != null) {
      builder.add(
          Attributes.ATTACK_DAMAGE,
          new AttributeModifier(
              Identifier.fromNamespaceAndPath("minecraft", "base_attack_damage"),
              rule.damage() - 1,
              AttributeModifier.Operation.ADD_VALUE),
          EquipmentSlotGroup.MAINHAND);
      builder.add(
          Attributes.ATTACK_SPEED,
          new AttributeModifier(
              Identifier.fromNamespaceAndPath("minecraft", "base_attack_speed"),
              rule.attackSpeed() - 4,
              AttributeModifier.Operation.ADD_VALUE),
          EquipmentSlotGroup.MAINHAND);
    }
    if (armor != null && equippable != null) {
      var slot = EquipmentSlotGroup.bySlot(equippable.slot());
      // Distinct identifiers per equipment slot let all four armor pieces stack normally.
      var modifierId =
          Identifier.fromNamespaceAndPath(
              "eldencraft_bridge", "campaign_armor." + equippable.slot().getName());
      builder.add(
          Attributes.ARMOR,
          new AttributeModifier(modifierId, armor.armor(), AttributeModifier.Operation.ADD_VALUE),
          slot,
          ItemAttributeModifiers.Display.override(Component.literal(armor.reductionLabel())));
      builder.add(
          Attributes.KNOCKBACK_RESISTANCE,
          new AttributeModifier(
              modifierId, armor.knockbackResistance(), AttributeModifier.Operation.ADD_VALUE),
          slot);
    }
    var modifiers = builder.build();
    if (!modifiers.equals(old)) stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
  }
}
