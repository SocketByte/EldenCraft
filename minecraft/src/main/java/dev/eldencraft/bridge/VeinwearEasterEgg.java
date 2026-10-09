package dev.eldencraft.bridge;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A server-owned secret shared by vanilla chat and the native host's vanilla chat screen. */
public final class VeinwearEasterEgg {
  public static final ResourceKey<PaintingVariant> VARIANT =
      ResourceKey.create(
          Registries.PAINTING_VARIANT,
          Identifier.fromNamespaceAndPath("eldencraft_bridge", "veinwear"));

  private VeinwearEasterEgg() {}

  public static void initialize() {
    // Both input paths reach this single broadcast event, so neither needs a second client grant.
    ServerMessageEvents.CHAT_MESSAGE.register(VeinwearEasterEgg::onChat);
  }

  private static void onChat(PlayerChatMessage message, ServerPlayer player, ChatType.Bound type) {
    if (!matches(message, type)) return;
    var server = player.level().getServer();
    if (!localOwner(server, player)) return;
    server.execute(() -> grant(server, player));
  }

  static boolean matches(PlayerChatMessage message, ChatType.Bound type) {
    return message != null
        && !message.isSystem()
        && type != null
        && type.chatType().is(ChatType.CHAT)
        && matches(message.signedContent());
  }

  static boolean matches(String text) {
    return text != null && text.strip().equalsIgnoreCase("veinwear");
  }

  private static boolean localOwner(MinecraftServer server, ServerPlayer player) {
    return server != null
        && server.isSingleplayer()
        && !server.isDedicatedServer()
        && !server.isPublished()
        && server.isSingleplayerOwner(player.nameAndId());
  }

  private static void grant(MinecraftServer server, ServerPlayer player) {
    // A queued message cannot grant into a disconnected or newly shared session.
    if (!localOwner(server, player) || server.getPlayerList().getPlayer(player.getUUID()) != player)
      return;
    var holder =
        server.registryAccess().lookup(Registries.PAINTING_VARIANT).flatMap(r -> r.get(VARIANT));
    if (holder.isEmpty()) return;
    var stack = painting(holder.get());
    if (insert(player.getInventory(), stack)) return;
    var dropped = player.createItemStackToDrop(stack, false, false);
    if (dropped != null) {
      dropped.setNoPickUpDelay();
      dropped.setTarget(player.getUUID());
      dropped.level().addFreshEntity(dropped);
    }
  }

  static boolean insert(Inventory inventory, ItemStack stack) {
    // Vanilla add() discards a non-fitting stack in Creative. Leave it intact for the drop instead.
    if (inventory.getFreeSlot() < 0 && inventory.getSlotWithRemainingSpace(stack) < 0) return false;
    return inventory.add(stack) && stack.isEmpty();
  }

  static ItemStack painting(Holder<PaintingVariant> variant) {
    var stack = new ItemStack(Items.PAINTING);
    stack.set(DataComponents.PAINTING_VARIANT, variant);
    stack.set(
        DataComponents.CUSTOM_NAME,
        Component.translatable("item.eldencraft_bridge.veinwear_painting"));
    return stack;
  }

  public static boolean isVeinwear(Holder<PaintingVariant> variant) {
    return variant != null && variant.is(VARIANT);
  }
}
