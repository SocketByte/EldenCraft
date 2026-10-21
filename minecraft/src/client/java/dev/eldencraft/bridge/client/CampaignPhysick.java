package dev.eldencraft.bridge.client;

import com.google.gson.*;
import dev.eldencraft.bridge.*;
import dev.eldencraft.bridge.PhysickTears.Tear;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Flask of Wondrous Physick on the integrated server. Elden Ring owns which tears exist; the
 * paired player's save owns the mixture and whether the single charge is spent, so every copy of
 * the flask item shares one charge.
 */
public final class CampaignPhysick {
  private static final String MIXTURE = "eldencraft.physick.mixture.";

  /** Player tag holding the spent charge. A respawn after a death does not carry it over. */
  public static final String SPENT = "eldencraft.physick.spent";

  /** Health reported per server tick. Native ignores a heal publication above 20 at once. */
  private static final double HEAL_STEP = 10;

  /** A drink is republished this long so one lost native read cannot drop it. */
  private static final long DRINK_MILLIS = 2000;

  private static final List<Holder<MobEffect>> AILMENTS =
      List.of(
          MobEffects.POISON,
          MobEffects.WITHER,
          MobEffects.SLOWNESS,
          MobEffects.WEAKNESS,
          MobEffects.MINING_FATIGUE,
          MobEffects.NAUSEA,
          MobEffects.BLINDNESS,
          MobEffects.DARKNESS,
          MobEffects.HUNGER);

  /** Native ownership, mirrored from the latest active snapshot of this character. */
  public record Owned(String character, boolean flask, Map<Tear, Integer> tears) {
    public Owned {
      tears = Map.copyOf(tears);
    }
  }

  /** What the mixing screen shows. Written on the server thread, read on the client thread. */
  public record View(Owned owned, List<Tear> mixture, boolean charged) {
    public View {
      mixture = List.copyOf(mixture);
    }
  }

  private record Combo(long tick, int stacks) {}

  private record Drink(String id, List<String> tears, long until) {}

  private static volatile Owned owned;
  private static volatile View view;
  private static volatile String status = "";
  private static volatile Drink drink;
  private static final Map<UUID, Double> HEALS = new HashMap<>();
  private static final Map<UUID, Combo> COMBOS = new HashMap<>();
  private static long restedToken;

  /** Native teleports already seen in {@link #warpSession}; each new one refills the flask. */
  private static long warpSession = -1, warpsSeen;

  private CampaignPhysick() {}

  public static void initialize() {
    WondrousPhysickItem.drinker =
        new WondrousPhysickItem.Drinker() {
          @Override
          public boolean canDrink(ServerPlayer player) {
            String reason = refusal(player);
            if (!reason.isEmpty()) player.sendOverlayMessage(Component.literal(reason));
            return reason.isEmpty();
          }

          @Override
          public void drink(ServerPlayer player) {
            CampaignPhysick.drink(player);
          }
        };
  }

  public static View view() {
    return view;
  }

  public static String status() {
    return status;
  }

  public static void reset() {
    owned = null;
    view = null;
    drink = null;
    status = "";
    HEALS.clear();
    COMBOS.clear();
    restedToken = 0;
    warpSession = -1;
    warpsSeen = 0;
  }

  /** Paired, active campaign tick. Returns true when the player save changed. */
  public static boolean serverTick(ServerPlayer player, CampaignBridge.Snapshot host) {
    if (host.active() && !host.character().isBlank()) {
      try {
        owned = new Owned(host.character(), host.physick(), PhysickTears.counts(host.tears()));
      } catch (IllegalArgumentException invalid) {
        owned = null;
      }
    }
    var own = owned;
    boolean changed = false;
    if (own != null && own.flask() && !hasFlask(player)) changed = grantFlask(player);
    // Fast travel and other teleports arrive at rest, as a grace does: the flask refills.
    if (warpSession != host.session()) {
      warpSession = host.session();
      warpsSeen = host.warp();
    } else if (host.warp() > warpsSeen) {
      warpsSeen = host.warp();
      player.removeTag(SPENT);
    }
    refresh(player);
    heal(player);
    if (has(player, Tear.CRIMSON_BUBBLE)
        && player.getHealth() > 0
        && player.getHealth() < player.getMaxHealth() * PhysickTears.CRIMSON_BUBBLE_BELOW) {
      player.removeEffect(PhysickItems.effect(Tear.CRIMSON_BUBBLE));
      queueHeal(player, player.getMaxHealth() * PhysickTears.CRIMSON_BUBBLE_HEAL);
    }
    if (has(player, Tear.SPECKLED)) cleanse(player);
    var d = drink;
    if (d != null && System.currentTimeMillis() > d.until()) drink = null;
    return changed;
  }

  /**
   * Death in Elden Ring: back at a grace with a full flask, a full hunger bar and no effects at
   * all, the tears' own and every other potion, beacon or food effect alike.
   */
  public static void rest(ServerPlayer player) {
    player.removeAllEffects();
    drink = null;
    HEALS.remove(player.getUUID());
    COMBOS.remove(player.getUUID());
    player.removeTag(SPENT);
    sate(player);
    refresh(player);
  }

  /** Resting at a grace or dying leaves the player fed, as a vanilla respawn does. */
  private static void sate(ServerPlayer player) {
    var food = player.getFoodData();
    food.setFoodLevel(20);
    food.setSaturation(5);
  }

  /** A genuine grace menu opened: resting there refills the flask and the hunger bar. */
  static void graceOpened(Minecraft client, InteractionProtocol.Snapshot snapshot) {
    var menu = snapshot == null ? null : snapshot.menu();
    if (menu == null || !menu.kind().equals("grace") || menu.token() == restedToken) return;
    var identity = CampaignBridge.graceIdentity(snapshot);
    var server = client.getSingleplayerServer();
    if (identity == null || server == null || client.player == null) return;
    restedToken = menu.token();
    var id = client.player.getUUID();
    server.execute(
        () -> {
          var player = server.getPlayerList().getPlayer(id);
          if (player != null && CampaignProgression.paired(player, identity.character())) {
            player.removeTag(SPENT);
            sate(player);
            refresh(player);
          }
        });
  }

  /** The grace menu offers mixing when this character owns the flask. */
  static boolean available(InteractionProtocol.Snapshot snapshot) {
    var identity = CampaignBridge.graceIdentity(snapshot);
    var own = owned;
    return identity != null
        && own != null
        && own.flask()
        && own.character().equals(identity.character());
  }

  /** Mix at the open grace. The result arrives in {@link #status()}. */
  static void requestMix(Minecraft client, List<Tear> mixture) {
    var snapshot = CampaignInteractions.snapshot();
    var identity = CampaignBridge.graceIdentity(snapshot);
    var server = client.getSingleplayerServer();
    if (identity == null || server == null || client.player == null) {
      status = "Mixing needs an open Site of Grace.";
      return;
    }
    var id = client.player.getUUID();
    var chosen = List.copyOf(mixture);
    status = "Mixing...";
    server.execute(
        () -> {
          var player = server.getPlayerList().getPlayer(id);
          var current = CampaignBridge.graceIdentity(CampaignInteractions.snapshot());
          var own = owned;
          if (player == null
              || current == null
              || !current.character().equals(identity.character())
              || !CampaignProgression.paired(player, identity.character())
              || own == null
              || !own.flask()
              || !own.character().equals(identity.character())) {
            status = "The Site of Grace is no longer available.";
            return;
          }
          if (!PhysickTears.valid(chosen, own.tears())) {
            status = "Those tears are not in your Elden Ring inventory.";
            return;
          }
          player.entityTags().stream()
              .filter(tag -> tag.startsWith(MIXTURE))
              .toList()
              .forEach(player::removeTag);
          if (!player.addTag(MIXTURE + PhysickTears.encode(chosen))) {
            status = "The mixture cannot be saved: the player's tag storage is full.";
            return;
          }
          player.removeTag(SPENT);
          refresh(player);
          status = "Flask mixed. It refills with this mixture at every grace and teleport.";
        });
  }

  public static List<Tear> mixture(ServerPlayer player) {
    for (String tag : player.entityTags())
      if (tag.startsWith(MIXTURE)) return PhysickTears.decode(tag.substring(MIXTURE.length()));
    return List.of();
  }

  private static String refusal(ServerPlayer player) {
    var own = owned;
    if (!CampaignCombat.active(player)
        || own == null
        || !CampaignProgression.paired(player, own.character()))
      return "The flask stirs only while your Elden Ring character is in the world.";
    if (mixture(player).isEmpty()) return "Mix the flask at a Site of Grace first.";
    if (player.entityTags().contains(SPENT))
      return "The flask is empty. Rest at a Site of Grace or fast travel to refill it.";
    if (!PhysickTears.valid(mixture(player), own.tears()))
      return "This mixture holds tears your character does not have.";
    return "";
  }

  private static void drink(ServerPlayer player) {
    if (!refusal(player).isEmpty() || !player.addTag(SPENT)) return;
    var mixture = mixture(player);
    boolean paired = PhysickTears.paired(mixture);
    var natives = new ArrayList<String>();
    for (var tear : PhysickTears.distinct(mixture)) {
      apply(player, tear, paired);
      if (tear.route == PhysickTears.Route.NATIVE) natives.add(tear.id);
    }
    if (!natives.isEmpty())
      drink =
          new Drink(
              UUID.randomUUID().toString(), natives, System.currentTimeMillis() + DRINK_MILLIS);
    refresh(player);
  }

  static void apply(ServerPlayer player, Tear tear, boolean paired) {
    switch (tear) {
      case CRIMSON ->
          queueHeal(player, player.getMaxHealth() * (paired ? 1 : PhysickTears.CRIMSON_HEAL));
      case CERULEAN ->
          player
              .getFoodData()
              .eat(
                  paired ? 20 : PhysickTears.CERULEAN_FOOD,
                  paired ? .5f : PhysickTears.CERULEAN_SATURATION);
      case RUPTURED ->
          player
              .level()
              .explode(
                  player,
                  player.getX(),
                  player.getY() + .5,
                  player.getZ(),
                  paired ? PhysickTears.RUPTURED_PAIR_POWER : PhysickTears.RUPTURED_POWER,
                  Level.ExplosionInteraction.NONE);
      default -> {
        var vanilla = PhysickItems.vanilla(tear);
        int amplifier =
            tear == Tear.WINGED
                ? 1
                : tear == Tear.CRIMSONSPILL
                    ? PhysickTears.absorptionAmplifier(player.getMaxHealth())
                    : 0;
        int ticks = tear.ticks() < 0 ? MobEffectInstance.INFINITE_DURATION : tear.ticks();
        player.addEffect(
            new MobEffectInstance(
                vanilla != null ? vanilla : PhysickItems.effect(tear),
                ticks,
                amplifier,
                false,
                true,
                true));
        if (tear == Tear.SPECKLED) cleanse(player);
      }
    }
  }

  /** Heal through the same genuine-heal path as potions, a few points per tick. */
  static void queueHeal(ServerPlayer player, double amount) {
    double missing = player.getMaxHealth() - player.getHealth();
    double queued = HEALS.getOrDefault(player.getUUID(), 0d);
    double total = Math.min(queued + Math.max(0, amount), missing);
    if (total > 0) HEALS.put(player.getUUID(), total);
  }

  private static void heal(ServerPlayer player) {
    var remaining = HEALS.get(player.getUUID());
    if (remaining == null) return;
    double step = Math.min(remaining, HEAL_STEP);
    float before = player.getHealth();
    player.heal((float) step);
    float healed = player.getHealth() - before;
    if (healed > 0 && CampaignCombat.active(player)) CampaignBridge.recordHealing(player, healed);
    if (healed <= 0 || remaining - step <= 1e-6) HEALS.remove(player.getUUID());
    else HEALS.put(player.getUUID(), remaining - step);
  }

  private static void cleanse(ServerPlayer player) {
    for (var ailment : AILMENTS) if (player.hasEffect(ailment)) player.removeEffect(ailment);
  }

  static boolean has(LivingEntity entity, Tear tear) {
    var effect = PhysickItems.effect(tear);
    return effect != null && entity.hasEffect(effect);
  }

  /** Native combat fields: affinity multipliers, an unspent bubble and the latest drink. */
  public static JsonObject combat(ServerPlayer player) {
    var active = EnumSet.noneOf(Tear.class);
    for (var entry : PhysickItems.effects().entrySet())
      if (player.hasEffect(entry.getValue())) active.add(entry.getKey());
    var json = new JsonObject();
    var affinity = new JsonArray();
    for (double scale : PhysickTears.affinityScales(active)) affinity.add(scale);
    json.add("affinity", affinity);
    json.addProperty("bubble", active.contains(Tear.OPALINE_BUBBLE));
    // Leaden Hardtear, or a Stalwart shield while the guard can actually block.
    json.addProperty(
        "unflinching", active.contains(Tear.LEADEN) || CampaignShields.stalwart(player));
    var d = drink;
    if (d != null && System.currentTimeMillis() <= d.until()) {
      var value = new JsonObject();
      value.addProperty("id", d.id());
      var tears = new JsonArray();
      d.tears().forEach(tears::add);
      value.add("tears", tears);
      json.add("drink", value);
    }
    return json;
  }

  /** Native spent the bubble on one of its hits. */
  public static void bubbleSpent(ServerPlayer player) {
    var effect = PhysickItems.effect(Tear.OPALINE_BUBBLE);
    if (effect != null) player.removeEffect(effect);
  }

  /** Stamina capacity scale while Greenspill is active. */
  public static double staminaCapacity(LivingEntity player) {
    return has(player, Tear.GREENSPILL) ? PhysickTears.GREENSPILL_CAPACITY : 1;
  }

  /** Stamina recovery scale while Greenburst is active. */
  public static double staminaRecovery(LivingEntity player) {
    return has(player, Tear.GREENBURST) ? PhysickTears.GREENBURST_RECOVERY : 1;
  }

  /** Cerulean Hidden Tear: no hunger, ammunition or durability is spent. */
  public static boolean freeResources(Entity entity) {
    return entity instanceof ServerPlayer player && has(player, Tear.CERULEAN_HIDDEN);
  }

  /** Spiked and Thorny scale one landed melee hit; Thorny counts it toward the next. */
  public static float melee(ServerPlayer player, float damage, boolean critical) {
    boolean thorny = has(player, Tear.THORNY), spiked = has(player, Tear.SPIKED);
    int stacks = 0;
    if (thorny) {
      long tick = player.level().getGameTime();
      var previous = COMBOS.get(player.getUUID());
      stacks =
          PhysickTears.thornyStacks(
              previous == null ? -1 : previous.tick(),
              previous == null ? 0 : previous.stacks(),
              tick);
      COMBOS.put(player.getUUID(), new Combo(tick, stacks));
    } else COMBOS.remove(player.getUUID());
    return (float) (damage * PhysickTears.meleeScale(critical, spiked, stacks));
  }

  /** Stonebarb: a hit on a raised Minecraft shield disables it, as an axe does. */
  public static void struck(ServerPlayer player, Entity target, boolean wasBlocking) {
    if (!wasBlocking
        || !has(player, Tear.STONEBARB)
        || !(target instanceof LivingEntity living)
        || target instanceof CombatProxyEntity) return;
    var stack = living.getItemBlockingWith();
    var blocks = stack == null ? null : stack.get(DataComponents.BLOCKS_ATTACKS);
    if (blocks != null) blocks.disable(player.level(), living, 5, stack);
  }

  /** Crimsonwhorl: fire, lava, lightning and magic heal instead of hurting. */
  public static boolean drains(ServerPlayer player, DamageSource source, float amount) {
    if (amount <= 0 || !has(player, Tear.CRIMSONWHORL)) return false;
    boolean elemental =
        source.is(DamageTypeTags.IS_FIRE)
            || source.is(DamageTypeTags.IS_LIGHTNING)
            || source.is(DamageTypes.MAGIC)
            || source.is(DamageTypes.INDIRECT_MAGIC);
    if (elemental) queueHeal(player, amount);
    return elemental;
  }

  /** An unspent Opaline Bubbletear for a Minecraft hit. */
  public static boolean bubble(ServerPlayer player) {
    return has(player, Tear.OPALINE_BUBBLE);
  }

  /** The flask's card: its mixture, the single charge and how to refill it. */
  static void tooltip(ItemStack stack, java.util.function.Consumer<Component> output) {
    var mixture = PhysickTears.decode(String.join("+", WondrousPhysickItem.mixture(stack)));
    boolean filled = WondrousPhysickItem.filled(stack);
    output.accept(CampaignTooltipTheme.heading("MIXTURE"));
    if (mixture.isEmpty())
      output.accept(CampaignTooltipTheme.detail("No tears. Mix them at a Site of Grace."));
    boolean paired = PhysickTears.paired(mixture);
    for (var tear : PhysickTears.distinct(mixture)) {
      output.accept(
          CampaignTooltipTheme.icon(CampaignTooltipTheme.Icon.AFFINITY, tear.color)
              .append(
                  CampaignTooltipTheme.text(
                      " " + tear.displayName + (paired ? " x2" : ""), CampaignTooltipTheme.IVORY)));
      output.accept(
          CampaignTooltipTheme.detail(
              (paired ? "Paired: stronger effect" : tear.label) + "  " + tear.duration()));
    }
    output.accept(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.FOUND,
            "Charges",
            filled ? "1 / 1" : "0 / 1",
            filled ? CampaignTooltipTheme.GOLD : CampaignTooltipTheme.MUTED));
    output.accept(
        CampaignTooltipTheme.detail(
            filled ? "Hold use to drink." : "Rest at a grace or fast travel to refill."));
  }

  /** Copy the player's charge and mixture to every flask they carry, and to the screen. */
  private static void refresh(ServerPlayer player) {
    var mixture = mixture(player);
    boolean charged = !mixture.isEmpty() && !player.entityTags().contains(SPENT);
    var display = WondrousPhysickItem.display(charged, mixture);
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) show(inventory.getItem(i), display);
    for (var slot : player.containerMenu.slots) show(slot.getItem(), display);
    show(player.containerMenu.getCarried(), display);
    var own = owned;
    if (own != null && CampaignProgression.paired(player, own.character()))
      view = new View(own, mixture, charged);
  }

  private static void show(ItemStack stack, net.minecraft.world.item.component.CustomModelData d) {
    if (stack.is(PhysickItems.FLASK) && !d.equals(stack.get(DataComponents.CUSTOM_MODEL_DATA)))
      stack.set(DataComponents.CUSTOM_MODEL_DATA, d);
  }

  private static boolean hasFlask(ServerPlayer player) {
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++)
      if (inventory.getItem(i).is(PhysickItems.FLASK)) return true;
    var ender = player.getEnderChestInventory();
    for (int i = 0; i < ender.getContainerSize(); i++)
      if (ender.getItem(i).is(PhysickItems.FLASK)) return true;
    for (var slot : player.containerMenu.slots)
      if (slot.getItem().is(PhysickItems.FLASK)) return true;
    for (var slot : player.inventoryMenu.slots)
      if (slot.getItem().is(PhysickItems.FLASK)) return true;
    return player.containerMenu.getCarried().is(PhysickItems.FLASK);
  }

  /** Elden Ring's flask cannot be lost, so a missing Minecraft flask is replaced. */
  private static boolean grantFlask(ServerPlayer player) {
    var stack = new ItemStack(PhysickItems.FLASK);
    if (!player.getInventory().add(stack)) {
      player.sendOverlayMessage(
          Component.literal("Make room in your inventory for the Flask of Wondrous Physick."));
      return false;
    }
    player.sendSystemMessage(
        Component.literal(
            "Flask of Wondrous Physick received. Mix Crystal Tears at a Site of Grace."));
    return true;
  }
}
