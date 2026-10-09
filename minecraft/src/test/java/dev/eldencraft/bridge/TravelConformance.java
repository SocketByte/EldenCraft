package dev.eldencraft.bridge;

import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Movement bounds and actual pinned vanilla injection sites; no live gameplay claim. */
public final class TravelConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void near(double actual, double expected, String message) {
    check(Math.abs(actual - expected) < 1e-7, message);
  }

  public static void main(String[] args) throws Exception {
    var walk = TravelPolicy.input(true, false, false, false, false, false);
    near(walk[0], 0, "forward has no strafe");
    near(walk[1], .98, "forward uses vanilla travel impulse");
    var cancel = TravelPolicy.input(true, true, true, true, false, false);
    near(Math.hypot(cancel[0], cancel[1]), 0, "opposing keys cancel");
    var diagonal = TravelPolicy.input(true, false, true, false, false, false);
    near(Math.hypot(diagonal[0], diagonal[1]), 1, "diagonal input stays bounded");
    var sneak = TravelPolicy.input(true, false, true, false, true, false);
    near(sneak[0], .294, "diagonal sneak preserves strafe speed");
    near(sneak[1], .294, "diagonal sneak preserves forward speed");
    var use = TravelPolicy.input(false, true, false, true, true, true);
    near(use[0], -.0588, "item use and sneak combine");
    near(use[1], -.0588, "backward input retains its sign");
    var east = TravelPolicy.horizontal(0, 1, -90);
    near(east[0], 1, "ladder contact probe follows east-facing forward input");
    near(east[1], 0, "east-facing probe has no south component");
    var south = TravelPolicy.horizontal(0, 1, 0);
    near(south[1], 1, "south-facing probe follows forward input");
    check(TravelPolicy.velocity(0, 30, 0), "combined speed bound is inclusive");
    for (double[] v :
        List.of(
            new double[] {30, .1, 0},
            new double[] {Double.NaN, 0, 0},
            new double[] {0, Double.POSITIVE_INFINITY, 0},
            new double[] {31, 0, 0}))
      check(!TravelPolicy.velocity(v[0], v[1], v[2]), "invalid or excessive travel is refused");

    // Loading the bytecode resource does not initialize a game or a world.
    var node = new ClassNode();
    try (var stream =
        TravelConformance.class
            .getClassLoader()
            .getResourceAsStream("net/minecraft/world/entity/LivingEntity.class")) {
      if (stream == null) throw new AssertionError("missing pinned vanilla LivingEntity");
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    for (String name :
        List.of(
            "travelFallFlying",
            "travelInWater",
            "travelInLava",
            "handleRelativeFrictionAndCalculateMovement")) {
      var method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
      int calls = 0;
      for (var insn : method.instructions) {
        if (insn instanceof MethodInsnNode call
            && call.name.equals("move")
            && call.owner.equals("net/minecraft/world/entity/LivingEntity")
            && call.desc.equals(
                "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
          calls++;
      }
      check(calls == 1, "exactly one deferred position call in " + name);
    }
    var ai = node.methods.stream().filter(m -> m.name.equals("aiStep")).findFirst().orElseThrow();
    boolean afterAi = false, found = false;
    for (var insn : ai.instructions) {
      if (insn instanceof MethodInsnNode call && call.name.equals("serverAiStep")) afterAi = true;
      if (insn instanceof FieldInsnNode field
          && field.getOpcode() == Opcodes.GETFIELD
          && field.name.equals("jumping")) {
        check(afterAi, "input handoff occurs after server AI and before jump");
        found = true;
        break;
      }
    }
    check(found, "vanilla jump hook exists");
    var climb =
        node.methods.stream()
            .filter(m -> m.name.equals("handleRelativeFrictionAndCalculateMovement"))
            .findFirst()
            .orElseThrow();
    boolean moved = false, lateClimb = false;
    for (var insn : climb.instructions) {
      if (insn instanceof MethodInsnNode call && call.name.equals("move")) moved = true;
      if (insn instanceof LdcInsnNode constant && Double.valueOf(.2).equals(constant.cst))
        lateClimb = moved;
    }
    check(lateClimb, "vanilla climb-up impulse is created after move; return hook must carry it");
    var player = new ClassNode();
    try (var stream =
        TravelConformance.class
            .getClassLoader()
            .getResourceAsStream("net/minecraft/world/entity/player/Player.class")) {
      new ClassReader(stream).accept(player, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    var playerTravel =
        player.methods.stream().filter(m -> m.name.equals("travel")).findFirst().orElseThrow();
    boolean flightBranch = false, flightTravel = false, verticalDrag = false;
    for (var insn : playerTravel.instructions) {
      if (insn instanceof FieldInsnNode field
          && field.owner.equals("net/minecraft/world/entity/player/Abilities")
          && field.name.equals("flying")) flightBranch = true;
      if (flightBranch
          && insn instanceof MethodInsnNode call
          && call.getOpcode() == Opcodes.INVOKESPECIAL
          && call.name.equals("travel")) flightTravel = true;
      if (flightTravel
          && insn instanceof LdcInsnNode constant
          && Double.valueOf(.6).equals(constant.cst)) verticalDrag = true;
    }
    check(flightTravel, "creative flight reaches the same deferred LivingEntity travel hooks");
    check(verticalDrag, "Player applies vanilla vertical flight drag after the captured move");
    var flyingSpeed =
        player.methods.stream()
            .filter(m -> m.name.equals("getFlyingSpeed"))
            .findFirst()
            .orElseThrow();
    boolean abilitySpeed = false, sprintSpeed = false;
    for (var insn : flyingSpeed.instructions) {
      if (insn instanceof MethodInsnNode call
          && call.owner.equals("net/minecraft/world/entity/player/Abilities")
          && call.name.equals("getFlyingSpeed")) abilitySpeed = true;
      if (abilitySpeed && insn.getOpcode() == Opcodes.FCONST_2) sprintSpeed = true;
    }
    check(
        abilitySpeed && sprintSpeed, "vanilla creative acceleration uses ability speed and sprint");
    var entity = new ClassNode();
    try (var stream =
        TravelConformance.class
            .getClassLoader()
            .getResourceAsStream("net/minecraft/world/entity/Entity.class")) {
      new ClassReader(stream).accept(entity, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    var affected =
        entity.methods.stream()
            .filter(m -> m.name.equals("isAffectedByBlocks"))
            .findFirst()
            .orElseThrow();
    boolean suppressed = false;
    for (var insn : affected.instructions) {
      if (insn instanceof FieldInsnNode field
          && field.name.equals("noPhysics")
          && field.getOpcode() == Opcodes.GETFIELD) suppressed = true;
    }
    check(
        suppressed,
        "native position ownership also suppresses vanilla block hazards unless scoped override"
            + " runs");
    var effects =
        entity.methods.stream()
            .filter(
                m ->
                    m.name.equals("applyEffectsFromBlocks") && m.desc.equals("(Ljava/util/List;)V"))
            .findFirst()
            .orElseThrow();
    boolean gated = false;
    for (var insn : effects.instructions) {
      if (insn instanceof MethodInsnNode call && call.name.equals("isAffectedByBlocks"))
        gated = true;
    }
    check(gated, "actual block effect dispatch uses the overridden hazard gate");
    System.out.println(
        "Travel conformance: " + checks + " checks passed (input/bounds/vanilla hook sites). ");
  }
}
