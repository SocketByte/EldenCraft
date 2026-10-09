package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.WorldDamageAuthority;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Death ownership and the actual vanilla inventory/respawn contracts, without a live game. */
public final class WorldDeathSafetyConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static ClassNode bytecode(String name) throws Exception {
    var node = new ClassNode();
    try (var stream =
        WorldDeathSafetyConformance.class.getClassLoader().getResourceAsStream(name + ".class")) {
      if (stream == null) throw new AssertionError("Missing class: " + name);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  private static MethodNode method(ClassNode owner, String name) {
    return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
  }

  private static boolean calls(MethodNode method, String name) {
    for (var instruction : method.instructions)
      if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
    return false;
  }

  public static void main(String[] args) throws Exception {
    for (String name : List.of("EldenCraft", "EldenCraft Passthrough Lab")) {
      check(
          WorldStartupSafety.protects(true, false, 0, name, false, false),
          "Dedicated startup is protected before the first host publication");
      check(
          WorldStartupSafety.protects(true, false, 1, name, false, false),
          "Dedicated staging and native death retain protection");
    }
    check(
        WorldStartupSafety.protects(true, false, 1, "New World", true, false),
        "Manual worlds stay protected in shared dimension after lease loss or reconnect");
    check(
        WorldStartupSafety.protects(true, false, 1, "Renamed save", false, true),
        "A paired save retains protection during a dimension transition");
    check(
        !WorldStartupSafety.protects(true, false, 1, "Survival", false, false),
        "An ordinary unpaired Minecraft save is unchanged");
    for (boolean shared : List.of(false, true)) {
      for (boolean paired : List.of(false, true)) {
        check(
            !WorldStartupSafety.protects(false, false, 1, "EldenCraft", shared, paired),
            "No protection on dedicated servers");
        check(
            !WorldStartupSafety.protects(true, true, 1, "EldenCraft", shared, paired),
            "No protection in LAN sessions");
        check(
            !WorldStartupSafety.protects(true, false, 2, "EldenCraft", shared, paired),
            "No protection for multiple players");
      }
    }

    check(
        !WorldDamageAuthority.ENVIRONMENT.contains(DamageTypes.FELL_OUT_OF_WORLD),
        "Translated arena height and native death cannot forward Minecraft void damage");
    check(
        !WorldDamageAuthority.ENVIRONMENT.contains(DamageTypes.GENERIC_KILL),
        "Minecraft /kill cannot kill the native character");
    for (var hazard :
        List.of(
            DamageTypes.LAVA,
            DamageTypes.DROWN,
            DamageTypes.WITHER,
            DamageTypes.FREEZE,
            DamageTypes.CACTUS))
      check(
          WorldDamageAuthority.ENVIRONMENT.contains(hazard),
          "Real Minecraft hazards retain shared-health damage: " + hazard);

    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
    check(!rules.get(GameRules.KEEP_INVENTORY), "Vanilla would drop the inventory by default");
    WorldStartupSafety.preserveInventory(rules, null);
    check(rules.get(GameRules.KEEP_INVENTORY), "Existing vanilla saves acquire keepInventory");
    check(rules.get(GameRules.FIRE_DAMAGE), "Real hazards are not disabled by inventory safety");
    WorldStartupSafety.preserveInventory(rules, null);
    check(
        rules.copy(FeatureFlags.DEFAULT_FLAGS).get(GameRules.KEEP_INVENTORY),
        "Repeated enforcement preserves the copied save rule");
    rules.set(GameRules.KEEP_INVENTORY, false, null);
    WorldStartupSafety.preserveInventory(rules, null);
    check(rules.get(GameRules.KEEP_INVENTORY), "A reset rule is restored on the next safety tick");

    var player = bytecode("net/minecraft/world/entity/player/Player");
    var drop = method(player, "dropEquipment");
    boolean keepRule = false;
    LabelNode keepBranch = null;
    boolean skipped = false;
    int protectedDrops = 0;
    for (var instruction : drop.instructions) {
      if (instruction instanceof FieldInsnNode field && field.name.equals("KEEP_INVENTORY"))
        keepRule = true;
      if (keepRule && instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE)
        keepBranch = jump.label;
      if (instruction == keepBranch) skipped = true;
      if (instruction instanceof MethodInsnNode call
          && (call.name.equals("dropAll") || call.name.equals("destroyVanishingCursedItems"))) {
        check(
            keepBranch != null && !skipped,
            "Vanilla keepInventory bypasses dropping and curse deletion");
        protectedDrops++;
      }
    }
    check(
        protectedDrops == 2 && skipped,
        "Pinned vanilla rule retains both ordinary and vanishing-cursed items");

    var serverPlayer = bytecode("net/minecraft/server/level/ServerPlayer");
    var restore = method(serverPlayer, "restoreFrom");
    check(
        restore.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;Z)V"),
        "Respawn preservation hook matches the exact vanilla lifecycle method");
    boolean restoreRule = false, restoreInventory = false;
    for (var instruction : restore.instructions) {
      if (instruction instanceof FieldInsnNode field && field.name.equals("KEEP_INVENTORY"))
        restoreRule = true;
      if (restoreRule
          && instruction instanceof MethodInsnNode call
          && call.name.equals("transferInventoryXpAndScore")) restoreInventory = true;
    }
    check(restoreInventory, "Fallback respawn restores equipment, inventory, XP and score");
    check(
        !calls(restore, "entityTags") && !calls(restore, "addTag"),
        "Vanilla does not preserve campaign pairing and receipts; explicit copying is required");
    var die = method(serverPlayer, "die");
    check(
        die.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;)V")
            && calls(die, "dropAllDeathLoot"),
        "Universal cancellation intercepts the real server death before its inventory drop");
    var damage = bytecode("dev/eldencraft/bridge/client/mixin/WorldPlayerDamageMixin");
    var guard = method(damage, "eldencraft$nativeOwnsDeath");
    check(
        calls(guard, "protects") && calls(guard, "keepAlive") && calls(guard, "cancel"),
        "Death cancellation also protects out-of-band deaths after bridge loss");
    check(
        calls(method(damage, "eldencraft$retainInventoryOnRespawn"), "preserveInventory"),
        "Vanilla respawn reads enforced rules in every destination dimension");
    var receipts = bytecode("dev/eldencraft/bridge/client/mixin/CampaignShopReceiptMixin");
    check(
        calls(method(receipts, "eldencraft$respawn"), "addTag"),
        "Fallback respawn carries campaign receipts with the retained inventory");
    System.out.println("WorldDeathSafetyConformance: " + checks + " checks passed");
  }
}
