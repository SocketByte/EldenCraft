package dev.eldencraft.bridge;

import com.google.gson.JsonObject;
import java.io.IOException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** A campaign item includes its potion contents, not just its registry identity. */
public final class CampaignItems {
  private CampaignItems() {}

  public static String potion(JsonObject row, String item) throws IOException {
    String potion = row.has("potion") ? JsonWire.string(row.get("potion")) : "";
    validate(item, potion);
    return potion;
  }

  public static void validate(String item, String potion) throws IOException {
    if (potion.isEmpty()) return;
    if (!potion.matches("minecraft:[a-z0-9_/.]+")
        || !java.util.Set.of(
                "minecraft:potion",
                "minecraft:splash_potion",
                "minecraft:lingering_potion",
                "minecraft:tipped_arrow")
            .contains(item))
      throw new IOException("Potion contents require a vanilla potion or tipped arrow: " + item);
  }

  public static ItemStack stack(String item, int count, String potion) {
    validateRegistry(item, potion);
    var value = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElse(Items.AIR);
    if (value == Items.AIR) throw new IllegalArgumentException("Unknown campaign item: " + item);
    var stack = new ItemStack(value, count);
    if (!potion.isEmpty()) {
      var holder =
          BuiltInRegistries.POTION
              .get(Identifier.parse(potion))
              .orElseThrow(
                  () -> new IllegalArgumentException("Unknown campaign potion: " + potion));
      stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder));
    }
    return stack;
  }

  public static void validateRegistry(String item, String potion) {
    var value = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElse(Items.AIR);
    if (value == Items.AIR) throw new IllegalArgumentException("Unknown campaign item: " + item);
    if (!potion.isEmpty() && BuiltInRegistries.POTION.get(Identifier.parse(potion)).isEmpty())
      throw new IllegalArgumentException("Unknown campaign potion: " + potion);
  }
}
