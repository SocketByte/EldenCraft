package dev.eldencraft.bridge;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.SpaceProvider;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import dev.eldencraft.bridge.client.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.client.gui.font.providers.GlyphProviderType;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * Actual configured weapon values, vanilla enchant components and pinned screen injection sites.
 */
public final class CampaignWeaponTooltipConformance {
  private static int checks;

  private static void check(boolean result, String message) {
    ++checks;
    if (!result) throw new AssertionError(message);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < 1e-6, message + ": " + actual);
  }

  private static ClassNode node(String type) throws Exception {
    var result = new ClassNode();
    try (var stream =
        CampaignWeaponTooltipConformance.class
            .getClassLoader()
            .getResourceAsStream(type + ".class")) {
      check(stream != null, "pinned class exists: " + type);
      new ClassReader(stream).accept(result, ClassReader.SKIP_FRAMES);
    }
    return result;
  }

  private static void rowHook(String method, String owner, String name, String descriptor)
      throws Exception {
    var node = node("net/minecraft/client/gui/screens/inventory/EnchantmentScreen");
    var target = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
    var calls = new ArrayList<MethodInsnNode>();
    for (var instruction : target.instructions)
      if (instruction instanceof MethodInsnNode call
          && call.owner.equals(owner)
          && call.name.equals(name)
          && call.desc.equals(descriptor)) calls.add(call);
    check(calls.size() == 1, "screen wrap target exists exactly once: " + method + " -> " + name);
    int invocation = target.instructions.indexOf(calls.getFirst());
    check(
        target.localVariables.stream()
            .anyMatch(
                local ->
                    local.index == 8
                        && local.desc.equals("I")
                        && local.name.equals("i")
                        && target.instructions.indexOf(local.start) <= invocation
                        && target.instructions.indexOf(local.end) > invocation),
        "offer row remains int local 8 at " + method + " wrap site");
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var vanilla = VanillaRegistries.createWorldLookup();
    net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
        .build(vanilla)
        .forEach(PendingComponents::apply);
    var config = CampaignConfig.load(Path.of("../config/campaign.json"));
    CampaignConfig.install(config);
    var language =
        JsonParser.parseReader(
                new InputStreamReader(
                    Objects.requireNonNull(
                        CampaignWeaponTooltipConformance.class.getResourceAsStream(
                            "/assets/eldencraft_bridge/lang/en_us.json")),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : config.weapons.entrySet()) {
      var stack = CampaignItems.stack(entry.getKey(), 1, "");
      check(
          CampaignWeaponTooltips.weapon(stack), "every configured weapon has custom descriptions");
      var stats = CampaignWeaponTooltips.values(stack, null);
      near(
          stats.damage(),
          entry.getValue().damage() * config.nativeDamageScale,
          "damage uses actual native scale");
      near(stats.attackSpeed(), entry.getValue().attackSpeed(), "configured attack speed");
      near(
          stats.stamina(),
          config.stamina.cost(CampaignCombat.kind(stack)),
          "attack stamina is the family's percentage of the maximum");
      near(stats.stance(), config.stanceDamage(entry.getKey()), "configured stance damage");
      near(
          CampaignCombat.cost(stack, 200),
          stats.stamina() * 2,
          "combat spends the tooltip's share of whatever the maximum is");
      var lines = new ArrayList<Component>();
      CampaignWeaponTooltips.stats(stack, null, lines::add);
      check(
          lines.getFirst().getContents() instanceof TranslatableContents description
              && language.has(description.getKey()),
          "weapon class description ships");
    }
    var sword = CampaignItems.stack("minecraft:iron_sword", 1, "");
    // WorldLookup's bootstrap holders have no datapack tags yet; fullname queries curse tags.
    var enchantments = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
    vanilla
        .lookupOrThrow(Registries.ENCHANTMENT)
        .listElements()
        .forEach(
            holder ->
                enchantments.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
    // Tooltips read only the identifier and level of a datapack affinity.
    var frostbite =
        ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("eldencraft_bridge:frostbite"));
    var fire =
        ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("eldencraft_bridge:fire"));
    var template =
        vanilla.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS).value();
    enchantments.register(
        frostbite,
        new Enchantment(
            Component.literal("Frostbite"),
            template.definition(),
            template.exclusiveSet(),
            template.effects()),
        RegistrationInfo.BUILT_IN);
    enchantments.register(
        fire,
        new Enchantment(
            Component.literal("Fire"),
            template.definition(),
            template.exclusiveSet(),
            template.effects()),
        RegistrationInfo.BUILT_IN);
    enchantments.bindTags(Map.of());
    enchantments.freeze();
    sword.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
    near(
        CampaignWeaponTooltips.values(sword, null).damage(),
        (config.weapons.get("minecraft:iron_sword").damage() + CampaignEnchantments.sharpness(3))
            * config.nativeDamageScale,
        "Sharpness included before scaling");
    var enchantmentLines = new ArrayList<Component>();
    CampaignWeaponTooltips.enchantments(
        sword, DataComponents.ENCHANTMENTS, TooltipDisplay.DEFAULT, enchantmentLines::add);
    check(enchantmentLines.size() > 1, "enchantment name includes readable effect description");
    enchantmentLines.clear();
    CampaignWeaponTooltips.enchantments(
        sword,
        DataComponents.ENCHANTMENTS,
        TooltipDisplay.DEFAULT.withHidden(DataComponents.ENCHANTMENTS, true),
        enchantmentLines::add);
    check(enchantmentLines.isEmpty(), "explicitly hidden enchantment components stay hidden");
    var frost = CampaignItems.stack("minecraft:iron_sword", 1, "");
    frost.enchant(enchantments.getOrThrow(frostbite), 3);
    var dormant = new ArrayList<Component>();
    CampaignWeaponTooltips.enchantments(
        frost, DataComponents.ENCHANTMENTS, TooltipDisplay.DEFAULT, Set.of("black"), dormant::add);
    check(
        dormant.getLast().getString().contains("Glintstone Whetblade"),
        "frostbite without the Glintstone Whetblade is marked dormant");
    var active = new ArrayList<Component>();
    CampaignWeaponTooltips.enchantments(
        frost,
        DataComponents.ENCHANTMENTS,
        TooltipDisplay.DEFAULT,
        Set.of("glintstone"),
        active::add);
    check(
        active.size() == dormant.size() - 1
            && active.stream().noneMatch(line -> line.getString().contains("Dormant")),
        "an unlocked affinity is not marked dormant");
    var fireSword = CampaignItems.stack("minecraft:iron_sword", 1, "");
    fireSword.enchant(enchantments.getOrThrow(fire), 3);
    double raw = CampaignWeaponTooltips.values(fireSword, null).damage();
    var split = new ArrayList<Component>();
    CampaignWeaponTooltips.stats(fireSword, null, Set.of("red_hot"), split::add);
    String attack =
        split.stream()
            .map(Component::getString)
            .filter(line -> line.contains("Attack power"))
            .findFirst()
            .orElseThrow();
    check(
        attack.contains(Math.round(raw * 80 / 100) + " (+")
            && attack.contains(" (+" + Math.round(raw * 30 / 100) + " "),
        "Fire III shows 80% physical and +30% fire attack power: " + attack);
    var dormantSplit = new ArrayList<Component>();
    CampaignWeaponTooltips.stats(fireSword, null, Set.of("black"), dormantSplit::add);
    check(
        dormantSplit.stream()
            .map(Component::getString)
            .anyMatch(
                line ->
                    line.contains("Attack power")
                        && line.contains(CampaignWeaponTooltips.number(raw))
                        && !line.contains("(+")),
        "a dormant element shows the unsplit damage it actually deals");
    check(
        CampaignWeaponTooltips.weapon(Items.GOLDEN_AXE.getDefaultInstance()),
        "unconfigured vanilla weapon retains a custom description");
    check(
        CampaignWeaponTooltips.weapon(Items.TRIDENT.getDefaultInstance()),
        "tridents also have custom weapon descriptions");
    var trident = CampaignWeaponTooltips.values(Items.TRIDENT.getDefaultInstance(), null);
    near(
        trident.damage(),
        9 * config.nativeDamageScale,
        "trident melee damage retains vanilla base attribute plus player damage");
    near(trident.stamina(), config.stamina.cost("other"), "trident retains fallback stamina cost");
    check(
        !CampaignWeaponTooltips.weapon(Items.IRON_PICKAXE.getDefaultInstance()),
        "mining tool is not advertised as a campaign weapon");
    near(
        CampaignWeaponTooltips.values(Items.BOW.getDefaultInstance(), null).damage(),
        Math.ceil(3 * config.bowBaseDamage) * config.nativeDamageScale,
        "full-draw arrow damage uses velocity and scale");
    near(
        CampaignWeaponTooltips.values(Items.BOW.getDefaultInstance(), null).attackSpeed(),
        1,
        "bow draw cadence");
    near(
        CampaignWeaponTooltips.values(Items.CROSSBOW.getDefaultInstance(), null).damage(),
        Math.ceil(3.15 * config.crossbowBaseDamage) * config.nativeDamageScale,
        "crossbow arrow damage uses velocity and scale");
    near(
        CampaignWeaponTooltips.values(Items.CROSSBOW.getDefaultInstance(), null).attackSpeed(),
        .8,
        "base crossbow reload cadence");
    check(
        CampaignWeaponTooltips.number(456.30000001).equals("456.3"),
        "scaled damage has no floating-point tail");
    rowHook(
        "extractBackground",
        "net/minecraft/client/gui/screens/inventory/EnchantmentNames",
        "getRandomName",
        "(Lnet/minecraft/client/gui/Font;I)Lnet/minecraft/network/chat/FormattedText;");
    rowHook(
        "extractRenderState",
        "net/minecraft/client/gui/GuiGraphicsExtractor",
        "setComponentTooltipForNextFrame",
        "(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V");
    check(
        node("net/minecraft/world/item/ItemStack").methods.stream()
            .anyMatch(
                method ->
                    method.name.equals("addDetailsToTooltip")
                        && method.desc.equals(
                            "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;)V")),
        "exact vanilla tooltip wrapper signature");
    check(
        language
            .get("screen.eldencraft_bridge.enchanting.help")
            .getAsString()
            .contains("Black Whetblade"),
        "opening guide explains whetblade gating");
    // Decode the shipped icon font with Minecraft's actual provider codec, not a fixture.
    var iconFont =
        JsonParser.parseReader(
                new InputStreamReader(
                    Objects.requireNonNull(
                        CampaignWeaponTooltipConformance.class.getResourceAsStream(
                            "/assets/eldencraft_bridge/font/icons.json")),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    var provider =
        BitmapProvider.Definition.CODEC
            .codec()
            .parse(JsonOps.INSTANCE, iconFont.getAsJsonArray("providers").get(0))
            .getOrThrow();
    var image =
        ImageIO.read(
            Objects.requireNonNull(
                CampaignWeaponTooltipConformance.class.getResourceAsStream(
                    "/assets/eldencraft_bridge/textures/font/icons.png")));
    int size = image.getHeight();
    check(
        provider.codepointGrid().length == 1 && size == provider.height(),
        "icon cells match the glyph height, so texels map 1:1 to GUI pixels without resampling");
    check(
        image.getWidth() == provider.codepointGrid()[0].length * size,
        "icon atlas columns match the real provider grid");
    check(
        provider.ascent() <= provider.height() && provider.height() <= 9,
        "icon glyphs fit the vanilla tooltip line height");
    for (var icon : CampaignTooltipTheme.Icon.values()) {
      check(
          provider.codepointGrid()[0][icon.ordinal()] == 0xe000 + icon.ordinal(),
          "every used icon has a shipped glyph: " + icon);
      boolean visible = false, fullAdvance = false;
      for (int x = icon.ordinal() * size; x < (icon.ordinal() + 1) * size; x++)
        for (int y = 0; y < size; y++) {
          int alpha = image.getRGB(x, y) >>> 24;
          // The text shader discards alpha below 0.1; the width marker must stay invisible.
          visible |= alpha >= 26;
          fullAdvance |= x == (icon.ordinal() + 1) * size - 1 && alpha != 0;
        }
      check(visible, "icon bitmap contains visible ink: " + icon);
      check(fullAdvance, "icon advances one full cell so labels align: " + icon);
    }
    var indent =
        (SpaceProvider.Definition)
            GlyphProviderType.SPACE
                .mapCodec()
                .codec()
                .parse(JsonOps.INSTANCE, iconFont.getAsJsonArray("providers").get(1))
                .getOrThrow();
    check(
        indent.advances().get(0xe0ff) == size + 1,
        "detail indent advances exactly one icon and its spacing");
    var styled = Component.empty();
    styled.append(
        CampaignTooltipTheme.row(
            CampaignTooltipTheme.Icon.DAMAGE, "Attack power", "252", CampaignTooltipTheme.GOLD));
    styled.append(CampaignTooltipTheme.detail("Full-strength hit"));
    styled.append(
        CampaignTooltipTheme.detail(
            Component.translatable("tooltip.eldencraft_bridge.bonus.mace")
                .withStyle(style -> style.withColor(CampaignTooltipTheme.MUTED))));
    styled.append(CampaignTooltipTheme.heading("ATTRIBUTES"));
    styled.visit(
        (style, text) -> {
          if (text.isEmpty()) return Optional.empty();
          if (text.codePoints().anyMatch(code -> code >= 0xe000 && code <= 0xe0ff))
            check(
                style
                    .getFont()
                    .equals(new FontDescription.Resource(CampaignTooltipTheme.ICON_FONT)),
                "private-use glyph resolves through the bundled font");
          else
            check(
                style.getFont().equals(FontDescription.DEFAULT),
                "ordinary labels and values do not inherit the icon font");
          return Optional.empty();
        },
        Style.EMPTY);
    check(
        node("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen").methods.stream()
                .filter(method -> method.name.equals("extractTooltip"))
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(
                    instruction ->
                        instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/client/gui/GuiGraphicsExtractor")
                            && call.name.equals("setTooltipForNextFrame")
                            && call.desc.equals(
                                "(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;Z)V"))
                .count()
            == 1,
        "inventory item tooltips keep one wrapped equipment-card call");
    var screen = node("net/minecraft/client/gui/screens/inventory/EnchantmentScreen");
    var background =
        screen.methods.stream()
            .filter(m -> m.name.equals("extractBackground"))
            .findFirst()
            .orElseThrow();
    int panels = 0;
    for (var instruction : background.instructions)
      if (instruction instanceof MethodInsnNode call
          && call.name.equals("blit")
          && call.desc.equals(
              "(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
        panels++;
    check(panels == 1, "the table has exactly one background skin hook");
    int sprites = 0;
    for (var instruction : background.instructions)
      if (instruction instanceof MethodInsnNode call && call.name.equals("blitSprite")) {
        sprites++;
        int invocation = background.instructions.indexOf(call);
        check(
            background.localVariables.stream()
                .anyMatch(
                    local ->
                        local.index == 8
                            && local.desc.equals("I")
                            && background.instructions.indexOf(local.start) <= invocation
                            && background.instructions.indexOf(local.end) > invocation),
            "offer row is available at every engraved sprite hook");
      }
    check(sprites == 6, "all six original offer/level sprite sites are covered");
    System.out.println("Campaign weapon tooltip conformance: " + checks + " checks passed.");
  }
}
