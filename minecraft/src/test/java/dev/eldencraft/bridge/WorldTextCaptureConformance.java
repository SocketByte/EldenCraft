package dev.eldencraft.bridge;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.eldencraft.bridge.client.WorldTextCapture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.StandingSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Real glyph vertices and pinned sign/nameplate paths, including culling and scene/HUD isolation.
 */
public final class WorldTextCaptureConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void near(float actual, float expected, String message) {
    check(Math.abs(actual - expected) < .0002f, message + ": " + actual + " / " + expected);
  }

  private static void nearLocal(
      double actual,
      double expected,
      Matrix4d inverse,
      Vertex a,
      Vertex b,
      int axis,
      String message) {
    // GPU vertices are floats. A .025-block nameplate pixel magnifies their world-space
    // quantization by 40 when translated vertices are returned to font coordinates. Use a
    // double inverse to avoid adding cancellation error, then allow two float ULPs per input
    // coordinate, mapped through that inverse. Keep even this bound below 1/1000 font pixel.
    double x = 2 * ((double) Math.ulp(a.position.x) + Math.ulp(b.position.x));
    double y = 2 * ((double) Math.ulp(a.position.y) + Math.ulp(b.position.y));
    double z = 2 * ((double) Math.ulp(a.position.z) + Math.ulp(b.position.z));
    double tolerance =
        Math.max(
            1e-6,
            switch (axis) {
              case 0 ->
                  Math.abs(inverse.m00()) * x
                      + Math.abs(inverse.m10()) * y
                      + Math.abs(inverse.m20()) * z;
              case 1 ->
                  Math.abs(inverse.m01()) * x
                      + Math.abs(inverse.m11()) * y
                      + Math.abs(inverse.m21()) * z;
              case 2 ->
                  Math.abs(inverse.m02()) * x
                      + Math.abs(inverse.m12()) * y
                      + Math.abs(inverse.m22()) * z;
              default -> throw new AssertionError("invalid local axis");
            });
    check(tolerance < .001, "float vertex precision remains below 1/1000 font pixel");
    check(
        Math.abs(actual - expected) <= tolerance,
        message + ": " + actual + " / " + expected + " (float vertex bound " + tolerance + ")");
  }

  private static ClassNode vanilla(String name) throws Exception {
    var node = new ClassNode();
    try (var stream =
        WorldTextCaptureConformance.class.getClassLoader().getResourceAsStream(name + ".class")) {
      if (stream == null) throw new AssertionError("Missing vanilla class " + name);
      new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return node;
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var originalPose = new Matrix4f().translation(10, 20, 30);
    var originalVertices = new Vertices();
    WorldTextCapture.line(
        false,
        10,
        () ->
            WorldTextCapture.glyph(
                originalPose,
                originalVertices,
                (pose, vertices) ->
                    check(
                        pose == originalPose && vertices == originalVertices,
                        "ordinary rendering is an exact passthrough")));
    try {
      WorldTextCapture.line(
          true,
          10,
          () -> {
            throw new IllegalStateException("fixture");
          });
      throw new AssertionError("expected failed scoped draw");
    } catch (IllegalStateException expected) {
      WorldTextCapture.glyph(
          originalPose,
          originalVertices,
          (pose, vertices) ->
              check(
                  pose == originalPose && vertices == originalVertices,
                  "failed world draw restores the scope before HUD text"));
    }

    // The actual vanilla glyph emits all UV, color, light and bold/italic geometry into the
    // wrapper.
    var sheet =
        new BakedSheetGlyph(GlyphInfo.simple(6), null, null, .125f, .375f, .25f, .75f, 0, 5, 0, 8);
    var plain = sheet.createGlyph(-10, 0, 0xff51a7e3, 0, Style.EMPTY, 1, 1);
    var styled =
        sheet.createGlyph(2, 0, 0xffa36217, 0, Style.EMPTY.withBold(true).withItalic(true), 1, 1);
    for (var transformations :
        List.of(StandingSignRenderer.TRANSFORMATIONS, HangingSignRenderer.TRANSFORMATIONS)) {
      for (var direction : Direction.Plane.HORIZONTAL)
        faces(transformations.wallTransformation(direction), plain, styled);
      for (int rotation = 0; rotation < 16; rotation++)
        faces(transformations.freeTransformations(rotation), plain, styled);
    }
    for (float yaw : new float[] {0, .4f, 1.5f, 3.14f})
      verify(
          new Matrix4f().translation(20, 4, -10).rotateY(yaw).scale(.025f, -.025f, .025f),
          plain,
          styled);

    // Reflection is around the whole line's center, including the half-pixel center of odd widths.
    var shifted = WorldTextCapture.reflect(originalPose, .5f);
    var left = shifted.transformPosition(new Vector3f(-7, 2, .03f));
    var right = shifted.transformPosition(new Vector3f(8, 2, .03f));
    near((left.x + right.x) * .5f, 10.5f, "odd-width text keeps its original horizontal center");
    near(left.y, 22, "text baseline is unchanged");
    near(left.z, 30.03f, "glyph depth offset is unchanged");
    check(
        originalPose.equals(new Matrix4f().translation(10, 20, 30)),
        "submitted scene matrix is never mutated");
    WorldTextCapture.line(
        true,
        0,
        () -> {
          WorldTextCapture.line(
              true,
              3,
              () ->
                  WorldTextCapture.glyph(
                      new Matrix4f(),
                      new Vertices(),
                      (pose, vertices) ->
                          near(pose.m30(), 6, "nested text scope uses its own center")));
          WorldTextCapture.glyph(
              new Matrix4f(),
              new Vertices(),
              (pose, vertices) ->
                  near(pose.m30(), 0, "nested text scope restores its outer center"));
          WorldTextCapture.line(
              false,
              9,
              () ->
                  WorldTextCapture.glyph(
                      originalPose,
                      originalVertices,
                      (pose, vertices) ->
                          check(
                              pose == originalPose && vertices == originalVertices,
                              "nested ordinary text masks world compensation")));
          try {
            WorldTextCapture.line(
                false,
                9,
                () -> {
                  throw new IllegalStateException("nested fixture");
                });
            throw new AssertionError("expected nested failed draw");
          } catch (IllegalStateException expected) {
            WorldTextCapture.glyph(
                new Matrix4f(),
                new Vertices(),
                (pose, vertices) ->
                    near(
                        pose.m30(),
                        0,
                        "failed nested ordinary text restores its outer world scope"));
          }
        });

    var text = vanilla("net/minecraft/client/renderer/feature/TextFeatureRenderer");
    var build =
        text.methods.stream().filter(m -> m.name.equals("buildGroup")).findFirst().orElseThrow();
    long lineCalls = 0;
    for (var insn : build.instructions)
      if (insn instanceof MethodInsnNode call && call.name.equals("renderText")) {
        check(
            call.desc.equals(
                "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$GlyphRenderer;Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content$Text;)V"),
            "exact shared world-line hook signature");
        lineCalls++;
      }
    check(lineCalls == 1, "one shared hook covers both ordinary and outlined text");
    var glyph = vanilla("net/minecraft/client/renderer/feature/TextFeatureRenderer$GlyphRenderer");
    var accept =
        glyph.methods.stream()
            .filter(m -> m.name.equals("acceptRenderable"))
            .findFirst()
            .orElseThrow();
    int draws = 0;
    for (var insn : accept.instructions)
      if (insn instanceof MethodInsnNode call
          && call.owner.equals("net/minecraft/client/gui/font/TextRenderable")
          && call.name.equals("render")) {
        check(
            call.desc.equals("(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V"),
            "exact vanilla glyph emission hook signature");
        draws++;
      }
    check(draws == 1, "each real glyph passes the compensation once");
    var signs = vanilla("net/minecraft/client/renderer/blockentity/AbstractSignRenderer");
    var submit =
        signs.methods.stream()
            .filter(
                m ->
                    m.name.equals("submit")
                        && m.desc.startsWith(
                            "(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;"))
            .findFirst()
            .orElseThrow();
    int faces = 0;
    for (var insn : submit.instructions)
      if (insn instanceof MethodInsnNode call && call.name.equals("submitSignText")) faces++;
    check(
        faces == 2,
        "standing and hanging signs submit both vanilla text faces through the shared path");
    var edit = vanilla("net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen");
    for (var method : edit.methods)
      for (var insn : method.instructions)
        if (insn instanceof MethodInsnNode call)
          check(
              !call.owner.equals("net/minecraft/client/renderer/feature/TextFeatureRenderer"),
              "sign editor uses its ordinary GUI path");
    System.out.println("World text capture conformance: " + checks + " checks passed");
  }

  private static void faces(
      SignRenderState.SignTransformations transformations,
      TextRenderable plain,
      TextRenderable styled) {
    for (var transform : List.of(transformations.frontText(), transformations.backText()))
      verify(new Matrix4f(transform.getMatrix()), plain, styled);
  }

  private static void verify(Matrix4f pose, TextRenderable... glyphs) {
    var before = new Vertices();
    var after = new Vertices();
    for (var glyph : glyphs) {
      glyph.render(pose, before, 0x00f000e0, false);
      WorldTextCapture.line(
          true,
          0,
          () ->
              WorldTextCapture.glyph(
                  pose,
                  after,
                  (corrected, vertices) -> glyph.render(corrected, vertices, 0x00f000e0, false)));
    }
    check(
        before.data.size() == after.data.size() && after.data.size() >= 8,
        "all vanilla glyph vertices survive");
    var inverse = new Matrix4d(pose).invert();
    var center = pose.transformPosition(new Vector3f());
    var normal = pose.transformDirection(new Vector3f(0, 0, 1)).normalize();
    var eye = new Vector3f(center).add(new Vector3f(normal).mul(4)).add(0, .7f, 0);
    var forward = new Vector3f(center).sub(eye).normalize();
    var hostRight = new Vector3f(0, 1, 0).cross(forward).normalize();
    for (int quad = 0; quad < before.data.size(); quad += 4) {
      var originalNormal = winding(before.data, quad);
      var correctedNormal = winding(after.data, quad);
      check(
          originalNormal.dot(correctedNormal) > 0, "quad reversal retains the visible culled face");
      float oldU0 = 0, oldU1 = 0, newU0 = 0, newU1 = 0;
      for (int vertex = 0; vertex < 4; vertex++) {
        var b = before.data.get(quad + vertex);
        var a = after.data.get(quad + new int[] {0, 3, 2, 1}[vertex]);
        var localB = inverse.transformPosition(new Vector3d(b.position));
        var localA = inverse.transformPosition(new Vector3d(a.position));
        nearLocal(localA.x, -localB.x, inverse, a, b, 0, "whole line counter-reflects local X");
        nearLocal(localA.y, localB.y, inverse, a, b, 1, "glyph vertical layout is preserved");
        nearLocal(localA.z, localB.z, inverse, a, b, 2, "glyph depth is preserved");
        check(
            a.color == b.color && a.light == b.light && a.u == b.u && a.v == b.v,
            "UV, style color and light stay on their original glyph corner");
        float xBefore = new Vector3f(b.position).sub(eye).dot(hostRight);
        float xAfter = new Vector3f(a.position).sub(eye).dot(hostRight);
        if (b.u == .125f) {
          oldU0 += xBefore;
          newU0 += xAfter;
        } else if (b.u == .375f) {
          oldU1 += xBefore;
          newU1 += xAfter;
        }
      }
      check(
          oldU0 > oldU1 && newU0 < newU1,
          "asymmetric glyph atlas reads left-to-right in the native camera on either face");
    }
  }

  private static Vector3f winding(List<Vertex> vertices, int at) {
    return new Vector3f(vertices.get(at + 1).position)
        .sub(vertices.get(at).position)
        .cross(new Vector3f(vertices.get(at + 2).position).sub(vertices.get(at).position));
  }

  private static final class Vertex {
    final Vector3f position;
    int color, light;
    float u, v;

    Vertex(float x, float y, float z) {
      position = new Vector3f(x, y, z);
    }
  }

  private static final class Vertices implements VertexConsumer {
    final List<Vertex> data = new ArrayList<>();

    Vertex current() {
      return data.getLast();
    }

    public VertexConsumer addVertex(float x, float y, float z) {
      data.add(new Vertex(x, y, z));
      return this;
    }

    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      return setColor((alpha << 24) | (red << 16) | (green << 8) | blue);
    }

    public VertexConsumer setColor(int color) {
      current().color = color;
      return this;
    }

    public VertexConsumer setUv(float u, float v) {
      current().u = u;
      current().v = v;
      return this;
    }

    public VertexConsumer setUv1(int u, int v) {
      return this;
    }

    public VertexConsumer setUv2(int u, int v) {
      current().light = (v << 16) | (u & 0xffff);
      return this;
    }

    public VertexConsumer setUv3(float u, float v) {
      return this;
    }

    public VertexConsumer setNormal(float x, float y, float z) {
      return this;
    }

    public VertexConsumer setLineWidth(float width) {
      return this;
    }
  }
}
