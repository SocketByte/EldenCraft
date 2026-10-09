package dev.eldencraft.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Verify full-width image orientation and the exact front-only pinned vanilla render hooks. */
public final class PaintingImageConformance {
  private static int checks;
  private static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
  private static final String RENDERER = "net/minecraft/client/renderer/entity/PaintingRenderer";

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static boolean near(float a, float b) {
    return Math.abs(a - b) < .000002f;
  }

  private static ClassNode bytecode(String name) throws Exception {
    var node = new ClassNode();
    try (var stream =
        PaintingImageConformance.class.getClassLoader().getResourceAsStream(name + ".class")) {
      if (stream == null) throw new AssertionError("Missing class: " + name);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  private static List<AbstractInsnNode> before(AbstractInsnNode node, int count) {
    var result = new ArrayList<AbstractInsnNode>();
    for (var previous = node.getPrevious();
        previous != null && result.size() < count;
        previous = previous.getPrevious()) if (previous.getOpcode() >= 0) result.addFirst(previous);
    check(result.size() == count, "complete pinned instruction sequence");
    return result;
  }

  private static boolean variable(AbstractInsnNode node, int opcode, int slot) {
    return node instanceof VarInsnNode variable
        && variable.getOpcode() == opcode
        && variable.var == slot;
  }

  private static Object annotationValue(AnnotationNode annotation, String name) {
    for (int i = 0; i < annotation.values.size(); i += 2)
      if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
    return null;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    // A painting may be one through sixteen blocks wide. Reflection must exchange columns as
    // well as their endpoints; merely swapping each tile's U endpoints would scramble the art.
    for (int width = 1; width <= 16; width++)
      for (int column = 0; column < width; column++) {
        float first = (float) ((double) (width - column) / width);
        float second = (float) ((double) (width - column - 1) / width);
        check(
            near(PaintingImage.frontU(true, first), (float) column / width)
                && near(PaintingImage.frontU(true, second), (float) (column + 1) / width),
            "front image reflects across the entire " + width + "-block painting");
        check(
            PaintingImage.frontU(false, first) == first
                && PaintingImage.frontU(false, second) == second,
            "standalone Minecraft preserves vanilla image coordinates");
        if (column > 0)
          check(
              near(
                  PaintingImage.frontU(true, first),
                  PaintingImage.frontU(
                      true, (float) ((double) (width - (column - 1) - 1) / width))),
              "neighboring mirrored tiles meet at exactly the same image coordinate");
        for (float withinTile : new float[] {.13f, .49f, .87f}) {
          float sourceU = (column + withinTile) / width;
          float vanillaU = (float) ((width - column - withinTile) / width);
          float resultU = PaintingImage.frontU(true, vanillaU);
          int imageColumns = width * 7;
          check(
              (int) (resultU * imageColumns) == (int) (sourceU * imageColumns),
              "asymmetric image column samples retain original order across all tiles");
          if (width > 1) {
            // This incorrect per-tile reversal is deliberately different from reflecting the
            // complete sprite, so the samples would catch that common tile-order regression.
            float perTileFlip = (width - column - 1 + withinTile) / width;
            if (column != width - column - 1)
              check(
                  (int) (perTileFlip * imageColumns) != (int) (sourceU * imageColumns),
                  "column-order assertion distinguishes a per-tile-only reversal");
          }
        }
      }
    // Reflection occurs before atlas interpolation, and cannot sample a neighboring sprite.
    for (float u : new float[] {0, .05f, .25f, .5f, .8f, 1}) {
      float atlasStart = .137f, atlasEnd = .419f;
      float mirrored = atlasStart + (atlasEnd - atlasStart) * PaintingImage.frontU(true, u);
      float expected = atlasEnd - (atlasEnd - atlasStart) * u;
      check(near(mirrored, expected), "front U reflects inside the sprite's atlas rectangle");
      check(
          near(PaintingImage.frontU(true, PaintingImage.frontU(true, u)), u),
          "host screen reflection restores the original image orientation");
    }

    // PaintingRenderer rotates the same local front image for every wall direction. Native
    // screen-right is up cross forward, so its coordinate remains the painting's local +X.
    for (int turn = 0; turn < 4; turn++) {
      var wall = new Matrix4f().rotateY((float) Math.toRadians(turn * 90));
      var forward = wall.transformDirection(new Vector3f(0, 0, 1));
      var screenRight = new Vector3f(0, 1, 0).cross(forward);
      for (float sourceU : new float[] {.07f, .21f, .63f, .94f}) {
        var point = wall.transformPosition(new Vector3f(sourceU * 3 - 1.5f, .3f, 0));
        float hostU = (point.dot(screenRight) + 1.5f) / 3;
        check(
            near(PaintingImage.frontU(true, 1 - sourceU), hostU),
            "three-block painting image follows native screen-right on wall direction " + turn);
      }
    }

    var renderer = bytecode(RENDERER);
    var geometry =
        renderer.methods.stream()
            .filter(method -> method.name.equals("lambda$renderPainting$0"))
            .findFirst()
            .orElseThrow();
    check(
        (geometry.access & Opcodes.ACC_STATIC) != 0
            && geometry.desc.equals(
                "(IIL"
                    + SPRITE
                    + ";[IL"
                    + SPRITE
                    + ";Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
        "render hook matches the pinned deferred painting geometry method");
    int uCalls = 0, vertexCalls = 0;
    for (var instruction : geometry.instructions) {
      if (!(instruction instanceof MethodInsnNode call)) continue;
      if (call.owner.equals(SPRITE) && call.name.equals("getU") && call.desc.equals("(F)F")) {
        if (uCalls == 0) {
          var inputs = before(call, 2);
          check(
              variable(inputs.getFirst(), Opcodes.ALOAD, 2),
              "U ordinal zero samples the back/frame sprite and is left untouched");
        } else {
          var inputs = before(call, uCalls == 1 ? 8 : 10);
          check(
              variable(inputs.getFirst(), Opcodes.ALOAD, 4),
              "U ordinals one and two sample only the front image sprite");
          check(
              variable(inputs.get(1), Opcodes.DLOAD, 22)
                  && variable(inputs.get(2), Opcodes.ILOAD, 0)
                  && variable(inputs.get(3), Opcodes.ILOAD, 26),
              "front U uses whole image width and tile column, independent of wall direction");
        }
        uCalls++;
      }
      if (call.owner.equals(RENDERER) && call.name.equals("vertex")) {
        var inputs = before(call, 11);
        int uSlot = vertexCalls == 0 || vertexCalls == 3 ? 34 : 33;
        boolean frontU =
            variable(inputs.get(4), Opcodes.FLOAD, 33)
                || variable(inputs.get(4), Opcodes.FLOAD, 34);
        check(
            vertexCalls < 4 ? variable(inputs.get(4), Opcodes.FLOAD, uSlot) : !frontU,
            "only the front four vertices consume the reflected image U coordinates");
        vertexCalls++;
      }
    }
    check(
        uCalls == 3 && vertexCalls == 24,
        "the pinned geometry retains one front, one back and four frame faces");

    var mixin = bytecode("dev/eldencraft/bridge/client/mixin/PaintingImageMixin");
    int hookCount = 0;
    var hookOrdinals = new HashSet<Integer>();
    for (var method : mixin.methods) {
      if (method.visibleAnnotations == null) continue;
      for (var annotation : method.visibleAnnotations) {
        if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")) continue;
        var targets = (List<AnnotationNode>) annotationValue(annotation, "at");
        check(targets.size() == 1, "one invocation target per front U hook");
        var at = targets.getFirst();
        int ordinal = (int) annotationValue(at, "ordinal");
        hookOrdinals.add(ordinal);
        check(
            ((List<String>) annotationValue(annotation, "method"))
                    .equals(List.of("lambda$renderPainting$0"))
                && Integer.valueOf(0).equals(annotationValue(annotation, "index"))
                && (ordinal == 1 || ordinal == 2)
                && ("L" + SPRITE + ";getU(F)F").equals(annotationValue(at, "target")),
            "mixin modifies only the two front normalized U arguments");
        boolean sceneGate = false, policy = false;
        for (var instruction : method.instructions) {
          if (!(instruction instanceof MethodInsnNode call)) continue;
          if (call.owner.equals("dev/eldencraft/bridge/client/SceneCapture")
              && call.name.equals("worldPass")) sceneGate = true;
          if (call.owner.equals("dev/eldencraft/bridge/PaintingImage")
              && call.name.equals("frontU")
              && call.desc.equals("(ZF)F")) policy = true;
        }
        check(
            sceneGate && policy,
            "image compensation runs only while shared scene capture is active");
        hookCount++;
      }
    }
    check(
        hookCount == 2 && hookOrdinals.equals(java.util.Set.of(1, 2)),
        "one scoped hook exists for each front image U endpoint");
    System.out.println("Painting image conformance: " + checks + " checks passed.");
  }
}
