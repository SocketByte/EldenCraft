package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/**
 * Actual vanilla decal generator, six genuine quad orientations and each destroy stage; no game or
 * GPU.
 */
public final class BlockDetailsConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static final float[][][] FACES = {
    {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
    {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
    {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}
  };

  private static final class Cube implements BlockStateModelPart {
    private final Map<Direction, BakedQuad> quads = new EnumMap<>(Direction.class);

    Cube() {
      var material = new BakedQuad.MaterialInfo(null, null, null, null, null, -1, null, 0);
      int i = 0;
      for (var direction : Direction.values()) {
        var v = FACES[i++];
        quads.put(
            direction,
            new BakedQuad(
                new Vector3f(v[0]),
                new Vector3f(v[1]),
                new Vector3f(v[2]),
                new Vector3f(v[3]),
                0,
                0,
                0,
                0,
                direction,
                material));
      }
    }

    public List<BakedQuad> getQuads(Direction side) {
      return side == null ? List.of() : List.of(quads.get(side));
    }

    public boolean useAmbientOcclusion() {
      return false;
    }

    public Material.Baked particleMaterial() {
      return null;
    }

    public int materialFlags() {
      return 0;
    }
  }

  public static void main(String[] args) {
    var parts = List.<BlockStateModelPart>of(new Cube());
    for (int stage = 0; stage < 10; stage++) {
      var pose = new PoseStack();
      pose.translate(-15.75, 3.25, 20.5);
      var vertices = new ArrayList<float[]>();
      BlockCrackGeometry.emit(
          pose, parts, stage, 16, (x, y, z, u, v) -> vertices.add(new float[] {x, y, z, u, v}));
      check(
          vertices.size() == 36,
          "all six model faces emit actual crack triangles at stage " + stage);
      final int current = stage;
      check(
          vertices.stream()
              .allMatch(
                  v ->
                      v[0] >= -15.75001
                          && v[0] <= -14.74999
                          && v[1] >= 3.24999
                          && v[1] <= 4.25001
                          && v[2] >= 20.49999
                          && v[2] <= 21.50001),
          "camera-relative model pose preserved " + stage);
      check(
          vertices.stream()
              .allMatch(
                  v ->
                      v[3] >= .03124
                          && v[3] <= .96876
                          && v[4] > current * 16f / 161
                          && v[4] < (current + 1) * 16f / 161),
          "vanilla sheeted UV stays inside exact packed stage " + stage);
      check(
          vertices.stream().mapToDouble(v -> v[3]).max().orElse(0)
                  - vertices.stream().mapToDouble(v -> v[3]).min().orElse(1)
              > .9,
          "crack faces retain texture extent " + stage);
    }
    var none = new ArrayList<float[]>();
    BlockCrackGeometry.emit(
        new PoseStack(),
        List.of(),
        0,
        16,
        (x, y, z, u, v) -> none.add(new float[] {x, y, z, u, v}));
    check(none.isEmpty(), "empty actual model cannot create fake crack geometry");
    boolean rejected = false;
    try {
      BlockCrackGeometry.emit(new PoseStack(), parts, 10, 16, (x, y, z, u, v) -> {});
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "invalid stage fails before submission");
    System.out.println(
        "Block details conformance: "
            + checks
            + " checks passed (actual vanilla decal geometry; no game/GPU).");
  }
}
