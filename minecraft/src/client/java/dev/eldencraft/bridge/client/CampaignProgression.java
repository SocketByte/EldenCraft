package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;

/** Boss receipts and granted items share the same vanilla player save, including crash recovery. */
public final class CampaignProgression {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_campaign");
  private static final String PAIR = "eldencraft.character.";
  private static final String CLAIM = "eldencraft.reward.";
  private static long lastNotice = Long.MIN_VALUE;
  private static MinecraftServer lastServer;
  private static boolean deathHandled;
  private static long deathSession;
  private static String deathCharacter = "";

  private CampaignProgression() {}

  public static boolean paired(Player player, String character) {
    if (player == null || character == null || character.isBlank()) return false;
    String expected = PAIR + characterKey(character);
    return player.entityTags().contains(expected);
  }

  public static String characterKey(String character) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(character.getBytes(StandardCharsets.UTF_8)),
              0,
              16);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static boolean characterPermitted(Player player) {
    if (player == null) return false;
    if (!CampaignConfig.current().enabled) return true;
    var host = CampaignBridge.snapshot();
    if (host == null) return true; // Existing freshness gates own inactive-host actions.
    var tags = player.entityTags();
    return tags.stream().noneMatch(tag -> tag.startsWith(PAIR)) || paired(player, host.character());
  }

  public static void serverTick(MinecraftServer server) {
    if (server != lastServer) {
      CampaignCombat.reset();
      lastServer = server;
      lastNotice = Long.MIN_VALUE;
      deathHandled = false;
    }
    var config = CampaignConfig.current();
    var host = CampaignBridge.snapshot();
    if (!config.enabled
        || !CampaignBridge.fresh()
        || host == null
        || !host.active()
        || !server.isSingleplayer()
        || server.isPublished()
        || server.getPlayerCount() != 1
        || host.character().isBlank()) {
      var death = CampaignBridge.deathObserved();
      if (config.enabled
          && death != null
          && server.isSingleplayer()
          && !server.isPublished()
          && server.getPlayerCount() == 1) {
        var player = server.getPlayerList().getPlayers().getFirst();
        if (player.level().dimension().equals(SharedWorldBlocks.DIMENSION)
            && paired(player, death.character())
            && (!deathHandled
                || deathSession != death.session()
                || !deathCharacter.equals(death.character()))) {
          CampaignCombat.rest(player);
          deathHandled = true;
          deathSession = death.session();
          deathCharacter = death.character();
        }
      }
      CampaignCombat.clear();
      return;
    }
    var player = server.getPlayerList().getPlayers().getFirst();
    if (!player.level().dimension().equals(SharedWorldBlocks.DIMENSION)) {
      CampaignCombat.clear();
      return;
    }
    deathHandled = false;
    String pair = PAIR + characterKey(host.character());
    boolean existingPair = player.entityTags().stream().anyMatch(tag -> tag.startsWith(PAIR));
    if (existingPair && !player.entityTags().contains(pair)) {
      CampaignCombat.clear();
      notice(
          player,
          "This Minecraft world is paired with a different Elden Ring character. Open that"
              + " character or use a separate world.");
      return;
    }
    boolean changed = false;
    if (!existingPair) {
      if (!player.addTag(pair)) {
        CampaignCombat.clear();
        notice(
            player,
            "Campaign cannot save the character pairing: the player's tag storage is full.");
        return;
      }
      changed = true;
    }
    int victories = 0;
    for (var boss : config.bosses)
      if (boss.remembrance() && host.defeated().contains(boss.id())) victories++;
    double health = config.progression.health(victories);
    var maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
    if (maxHealth != null && maxHealth.getBaseValue() != health) {
      // Preserve absolute HP on an upgrade. Host rest/healing is still authoritative.
      float before = player.getHealth();
      maxHealth.setBaseValue(health);
      player.setHealth((float) Math.min(before, health));
    }
    CampaignCombat.tick(player, config.progression.stamina(victories), host);
    changed |= grantMobExperience(player, host);
    changed |= grant(player, "starter", config.starterItems, 0);
    for (var boss : config.bosses)
      if (host.defeated().contains(boss.id()))
        changed |= grant(player, boss.id(), boss.rewards(), boss.experience());
    if (changed) {
      // Integrated singleplayer loads its owner from level.dat. Save its Player data as well as
      // playerdata/<uuid>.dat, keeping every receipt and the corresponding inventory together.
      server.saveEverything(true, true, false);
    }
  }

  private static void notice(ServerPlayer player, String text) {
    long now = player.level().getGameTime();
    if (lastNotice == Long.MIN_VALUE || now - lastNotice >= 200) {
      player.sendOverlayMessage(Component.literal(text));
      lastNotice = now;
    }
  }

  private static boolean grant(
      ServerPlayer player, String id, List<CampaignConfig.Reward> rewards, int experience) {
    String tag = CLAIM + id;
    if (player.entityTags().contains(tag)) return false;
    var stacks = new ArrayList<ItemStack>();
    for (var reward : rewards) {
      var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(reward.item()));
      if (item == null || item == Items.AIR) {
        LOG.error("Campaign reward {} has unknown item {}", id, reward.item());
        return false;
      }
      stacks.add(new ItemStack(item, reward.count()));
    }
    if (!fits(player, stacks)) {
      notice(player, "Boss rewards are waiting. Make room in your inventory to receive them.");
      return false;
    }
    if (!player.addTag(tag)) {
      notice(player, "Boss rewards are waiting: the player's receipt storage is full.");
      return false;
    }
    for (var stack : stacks) {
      CampaignCombat.tune(stack);
      if (!player.getInventory().add(stack) || !stack.isEmpty())
        throw new IllegalStateException("Campaign inventory changed during server-thread delivery");
    }
    // The first-clear receipt, XP and inventory are written by one vanilla player save.
    // XP-only rewards need no free inventory slot and never become native character levels.
    if (experience > 0) player.giveExperiencePoints(experience);
    if (!id.equals("starter"))
      player.sendSystemMessage(Component.literal("Boss rewards received: " + id.replace('_', ' ')));
    return true;
  }

  private static boolean grantMobExperience(ServerPlayer player, CampaignBridge.Snapshot host) {
    List<String> markers =
        player.entityTags().stream()
            .filter(tag -> tag.startsWith(CampaignExperience.PREFIX))
            .toList();
    if (markers.size() > 1) {
      notice(player, "Native XP rewards are paused: multiple saved XP receipts.");
      return false;
    }
    String old = markers.isEmpty() ? null : markers.getFirst();
    try {
      var previous = old == null ? null : CampaignExperience.parse(old);
      var update =
          CampaignExperience.advance(
              previous, host.session(), host.experienceSeq(), host.experienceTotal());
      if (!update.changed()) return false;
      if (old != null) player.removeTag(old);
      if (!player.addTag(update.cursor().tag())) {
        if (old != null) player.addTag(old);
        notice(player, "Native XP rewards are waiting: the player's receipt storage is full.");
        return false;
      }
      if (update.award() > 0) player.giveExperiencePoints(update.award());
      return true;
    } catch (IllegalArgumentException error) {
      notice(player, "Native XP rewards are paused: " + error.getMessage());
      return false;
    }
  }

  public static boolean fits(ServerPlayer player, List<ItemStack> incoming) {
    var slots = new ArrayList<ItemStack>();
    for (var item : player.getInventory().getNonEquipmentItems()) slots.add(item.copy());
    for (var source : incoming) {
      var stack = source.copy();
      CampaignCombat.tune(stack);
      for (var slot : slots) {
        if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
        int take = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
        if (take > 0) {
          slot.grow(take);
          stack.shrink(take);
        }
      }
      for (int i = 0; i < slots.size() && !stack.isEmpty(); i++) {
        if (!slots.get(i).isEmpty()) continue;
        int take = Math.min(stack.getCount(), stack.getMaxStackSize());
        slots.set(i, stack.copyWithCount(take));
        stack.shrink(take);
      }
      if (!stack.isEmpty()) return false;
    }
    return true;
  }
}
