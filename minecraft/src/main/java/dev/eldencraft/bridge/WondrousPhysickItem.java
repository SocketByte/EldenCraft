package dev.eldencraft.bridge;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

/**
 * The Flask of Wondrous Physick. The charge and mixture belong to the paired player on the server;
 * the stack only carries a display copy (filled flag, tear IDs and liquid colours).
 */
public final class WondrousPhysickItem extends Item {
  /** Server-side campaign rules, installed by the client entrypoint that owns the campaign. */
  public interface Drinker {
    /** Whether the server lets this player start drinking now; may explain why not. */
    boolean canDrink(ServerPlayer player);

    void drink(ServerPlayer player);
  }

  public static volatile Drinker drinker;

  public WondrousPhysickItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResult use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    if (!filled(stack)) return InteractionResult.FAIL;
    if (player instanceof ServerPlayer server) {
      var rules = drinker;
      if (rules == null || !rules.canDrink(server)) return InteractionResult.FAIL;
    }
    return super.use(level, player, hand);
  }

  @Override
  public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
    // Never consumed: the vanilla Consumable only supplies the drinking animation and sound.
    var rules = drinker;
    if (entity instanceof ServerPlayer player && rules != null) rules.drink(player);
    return stack;
  }

  public static boolean filled(ItemStack stack) {
    var data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
    return data != null && Boolean.TRUE.equals(data.getBoolean(0));
  }

  /** Tear IDs of the displayed mixture. */
  public static List<String> mixture(ItemStack stack) {
    var data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
    return data == null ? List.of() : data.strings();
  }

  /**
   * The model's two liquid layers are tinted from the colours; a single-tear mixture fills both
   * halves with its colour.
   */
  public static CustomModelData display(boolean filled, List<PhysickTears.Tear> mixture) {
    int first = mixture.isEmpty() ? 0xc8e1f0 : mixture.getFirst().color;
    int second = mixture.size() < 2 ? first : mixture.get(1).color;
    return new CustomModelData(
        List.of(),
        List.of(filled),
        mixture.stream().map(t -> t.id).toList(),
        List.of(0xff000000 | first, 0xff000000 | second));
  }
}
