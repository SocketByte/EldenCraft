package dev.eldencraft.bridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Check the real vanilla drop/placement/save contracts without starting either game. */
public final class VeinwearPaintingConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static ClassNode vanilla(String name) throws Exception {
    var node = new ClassNode();
    try (var stream =
        VeinwearPaintingConformance.class.getClassLoader().getResourceAsStream(name + ".class")) {
      if (stream == null) throw new AssertionError("Missing vanilla class: " + name);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  public static void main(String[] args) throws Exception {
    String paintingName = "net/minecraft/world/entity/decoration/painting/Painting";
    var painting = vanilla(paintingName);
    var drop =
        painting.methods.stream()
            .filter(method -> method.name.equals("dropItem"))
            .findFirst()
            .orElseThrow();
    boolean entityDrops = false, creative = false, customName = false;
    int spawnCalls = 0;
    for (var instruction : drop.instructions) {
      if (instruction instanceof FieldInsnNode field) {
        if (field.name.equals("ENTITY_DROPS")) entityDrops = true;
        if (field.name.equals("CUSTOM_NAME")) customName = true;
      }
      if (instruction instanceof MethodInsnNode call) {
        if (call.name.equals("hasInfiniteMaterials")) creative = true;
        if (call.name.equals("spawnAtLocation")) {
          spawnCalls++;
          check(
              call.owner.equals(paintingName)
                  && call.desc.equals(
                      "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/item/ItemEntity;"),
              "drop hook matches the exact vanilla item spawn call");
          check(
              entityDrops && creative,
              "the drop hook follows vanilla game-rule and creative checks");
          check(customName, "vanilla custom name is applied before retaining the variant");
        }
      }
    }
    check(spawnCalls == 1, "one vanilla stack is dropped without a duplicate item");

    var pick =
        painting.methods.stream()
            .filter(method -> method.name.equals("getPickResult"))
            .findFirst()
            .orElseThrow();
    check(
        pick.desc.equals("()Lnet/minecraft/world/item/ItemStack;"),
        "creative pick hook modifies the exact vanilla painting stack result");
    int pickReturns = 0;
    boolean paintingItem = false;
    for (var instruction : pick.instructions) {
      if (instruction instanceof FieldInsnNode field
          && field.owner.equals("net/minecraft/world/item/Items")
          && field.name.equals("PAINTING")) paintingItem = true;
      if (instruction.getOpcode() == org.objectweb.asm.Opcodes.ARETURN) pickReturns++;
    }
    check(paintingItem && pickReturns == 1, "creative pick returns one ordinary painting stack");

    var components =
        painting.methods.stream()
            .filter(method -> method.name.equals("applyImplicitComponents"))
            .findFirst()
            .orElseThrow();
    boolean fixedVariant = false;
    for (var instruction : components.instructions) {
      if (instruction instanceof FieldInsnNode field && field.name.equals("PAINTING_VARIANT"))
        fixedVariant = true;
      if (instruction instanceof MethodInsnNode call
          && call.name.equals("applyImplicitComponentIfPresent"))
        check(fixedVariant, "the dropped item's variant is reapplied to the placed painting");
    }
    check(fixedVariant, "vanilla painting accepts the fixed variant item component");
    for (String methodName : new String[] {"addAdditionalSaveData", "readAdditionalSaveData"}) {
      var method =
          painting.methods.stream()
              .filter(candidate -> candidate.name.equals(methodName))
              .findFirst()
              .orElseThrow();
      boolean variantSaved = false;
      for (var instruction : method.instructions) {
        if (instruction instanceof MethodInsnNode call
            && call.owner.equals("net/minecraft/world/entity/variant/VariantUtils")
            && call.name.equals(
                methodName.equals("addAdditionalSaveData") ? "writeVariant" : "readVariant"))
          variantSaved = true;
      }
      check(variantSaved, "placed painting retains registry variant across " + methodName);
    }
    var hangingItem = vanilla("net/minecraft/world/item/HangingEntityItem");
    var use =
        hangingItem.methods.stream()
            .filter(method -> method.name.equals("useOn"))
            .findFirst()
            .orElseThrow();
    boolean componentApplied = false, checkedSupport = false;
    for (var instruction : use.instructions) {
      if (!(instruction instanceof MethodInsnNode call)) continue;
      if (call.owner.equals("net/minecraft/world/entity/PostSpawnProcessor")
          && call.name.equals("apply")) componentApplied = true;
      if (call.name.equals("survives")) {
        check(
            componentApplied,
            "fixed variant is applied before checking the actual painting's support");
        checkedSupport = true;
      }
      if (call.name.equals("addFreshEntity"))
        check(checkedSupport, "vanilla support is checked before the painting is added");
    }
    check(checkedSupport, "placement still requires vanilla wall support and free space");
    System.out.println("Veinwear painting conformance: " + checks + " checks passed.");
  }
}
