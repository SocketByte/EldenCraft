package dev.eldencraft.bridge;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real 26.3 chat-message admission, resource codecs and saved painting-item components. */
public final class VeinwearConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var vanilla = VanillaRegistries.createWorldLookup();
    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(vanilla).forEach(PendingComponents::apply);

    for (var text : new String[] {"veinwear", "VEINWEAR", " VeInWeAr ", "\tveinwear\n"})
      check(VeinwearEasterEgg.matches(text), "exact case-insensitive keyword: " + text);
    for (var text :
        new String[] {"", "vein", "veinwear!", "give veinwear", "/veinwear", "vein wear"})
      check(!VeinwearEasterEgg.matches(text), "other input cannot grant: " + text);
    check(!VeinwearEasterEgg.matches((String) null), "missing input cannot grant");

    var sender = UUID.fromString("0147ab0d-77da-40af-9f26-0f6b88529d49");
    var chatTypes = vanilla.lookupOrThrow(Registries.CHAT_TYPE);
    var chat =
        new ChatType.Bound(
            chatTypes.getOrThrow(ChatType.CHAT), Component.literal("local"), Optional.empty());
    var command =
        new ChatType.Bound(
            chatTypes.getOrThrow(ChatType.SAY_COMMAND),
            Component.literal("local"),
            Optional.empty());
    check(
        VeinwearEasterEgg.matches(PlayerChatMessage.unsigned(sender, "veinwear"), chat),
        "ordinary chat is accepted through the server message seam");
    check(
        !VeinwearEasterEgg.matches(PlayerChatMessage.unsigned(sender, "veinwear"), command),
        "command-generated messages cannot produce a second grant");
    check(
        !VeinwearEasterEgg.matches(PlayerChatMessage.system("veinwear"), chat),
        "system text cannot grant");
    check(
        !VeinwearEasterEgg.matches(
            PlayerChatMessage.unsigned(sender, "other")
                .withUnsignedContent(Component.literal("veinwear")),
            chat),
        "only the submitted text is matched, independent of chat decoration");

    PaintingVariant variant;
    try (var input =
        VeinwearConformance.class.getResourceAsStream(
            "/data/eldencraft_bridge/painting_variant/veinwear.json")) {
      check(input != null, "painting variant ships in the mod resources");
      var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      variant =
          PaintingVariant.DIRECT_CODEC
              .parse(vanilla.createSerializationContext(JsonOps.INSTANCE), json)
              .getOrThrow();
    }
    check(variant.width() == 3 && variant.height() == 2, "whole artwork uses a 3 by 2 wall area");
    check(
        variant.assetId().equals(VeinwearEasterEgg.VARIANT.identifier()),
        "variant resolves its texture");
    check(
        variant.title().isPresent() && variant.title().get().getString().equals("Veinwear"),
        "painting tooltip carries its title");
    try (var input =
        VeinwearConformance.class.getResourceAsStream(
            "/assets/eldencraft_bridge/textures/painting/veinwear.png")) {
      check(input != null, "painting texture ships in the mod resources");
      var texture = ImageIO.read(input);
      check(
          texture != null && texture.getWidth() == 384 && texture.getHeight() == 256,
          "texture matches wall aspect ratio");
    }

    var paintings =
        new MappedRegistry<PaintingVariant>(Registries.PAINTING_VARIANT, Lifecycle.stable());
    vanilla
        .lookupOrThrow(Registries.PAINTING_VARIANT)
        .listElements()
        .forEach(h -> paintings.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
    var holder = paintings.register(VeinwearEasterEgg.VARIANT, variant, RegistrationInfo.BUILT_IN);
    paintings.freeze();
    var registries =
        HolderLookup.Provider.create(
            Stream.concat(
                vanilla.listRegistries().filter(r -> !r.key().equals(Registries.PAINTING_VARIANT)),
                Stream.of(paintings)));
    var stack = VeinwearEasterEgg.painting(holder);
    check(stack.is(Items.PAINTING) && stack.getCount() == 1, "one actual painting is granted");
    check(
        VeinwearEasterEgg.isVeinwear(stack.get(DataComponents.PAINTING_VARIANT)),
        "item uses the registered painting variant component");
    check(stack.has(DataComponents.CUSTOM_NAME), "item retains its custom display name");
    check(
        !VeinwearEasterEgg.isVeinwear(
            vanilla
                .lookupOrThrow(Registries.PAINTING_VARIANT)
                .listElements()
                .findFirst()
                .orElseThrow()),
        "ordinary paintings remain distinct");
    check(!VeinwearEasterEgg.isVeinwear(null), "missing variants remain distinct");

    // The same registry-backed item codec stores player inventories and dropped-item stacks.
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    var encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
    check(
        encoded.toString().contains("eldencraft_bridge:veinwear"),
        "save records the variant registry identity");
    var restored = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
    check(
        ItemStack.matches(stack, restored),
        "item count, variant and name survive a real codec round trip");
    check(
        VeinwearEasterEgg.isVeinwear(restored.get(DataComponents.PAINTING_VARIANT)),
        "restored inventory item still places Veinwear");

    // A null player exposes any accidental attempt to enter add()'s no-space Creative branch.
    var inventory = new Inventory(null, new EntityEquipment());
    var incoming = stack.copy();
    check(
        VeinwearEasterEgg.insert(inventory, incoming) && incoming.isEmpty(),
        "free slot accepts exactly one painting");
    check(
        ItemStack.matches(inventory.getItem(0), stack),
        "inventory receives the named fixed variant");
    incoming = stack.copy();
    check(
        VeinwearEasterEgg.insert(inventory, incoming) && inventory.getItem(0).getCount() == 2,
        "matching painting stack receives one more item");
    for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
      inventory.setItem(slot, new ItemStack(Items.STONE, 64));
    incoming = stack.copy();
    check(
        !VeinwearEasterEgg.insert(inventory, incoming),
        "full inventory takes the drop path without destructive add");
    check(ItemStack.matches(incoming, stack), "full inventory leaves one intact painting to drop");
    inventory.setItem(2, new ItemStack(Items.PAINTING, 1));
    incoming = stack.copy();
    check(
        !VeinwearEasterEgg.insert(inventory, incoming),
        "ordinary painting cannot absorb the secret variant");
    inventory.setItem(2, stack.copyWithCount(63));
    incoming = stack.copy();
    check(
        VeinwearEasterEgg.insert(inventory, incoming) && inventory.getItem(2).getCount() == 64,
        "full inventory with one matching space accepts exactly one painting");
    System.out.println("Veinwear conformance: " + checks + " checks passed");
  }
}
