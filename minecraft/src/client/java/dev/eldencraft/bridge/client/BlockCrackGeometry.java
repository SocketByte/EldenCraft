package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.vertex.*;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Uses the same baked quads and sheeted decal generator as vanilla's breaking-block feature. */
public final class BlockCrackGeometry {
  private static final Direction[] SIDES = Direction.values();
  private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

  @FunctionalInterface
  public interface Sink {
    void vertex(float x, float y, float z, float u, float v);
  }

  private BlockCrackGeometry() {}

  /** Fallback for custom Fabric models; returns the number of quads the model emitted. */
  public static int emitFabric(
      PoseStack pose,
      FabricBlockStateModel model,
      BlockPos pos,
      BlockState state,
      RandomSource random,
      Renderer renderer,
      int stage,
      int size,
      Sink output) {
    if (stage < 0 || stage > 9 || size < 1 || size > 256)
      throw new IllegalArgumentException("Destroy atlas bounds");
    var sink = new Quads(stage, size, output);
    var vanilla = new SheetedDecalTextureGenerator(sink, pose.last(), 1);
    int[] quads = {0};
    // This is Fabric LevelRendererMixin's actual breaking-model route: the
    // EMPTY tint getter and an always-false culling predicate preserve all faces.
    var emitter =
        renderer.quadEmitter(
            quad -> {
              quads[0]++;
              quad.buffer(
                  net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                  pose.last(),
                  vanilla);
            });
    model.emitQuads(emitter, BlockAndTintGetter.EMPTY, pos, state, random, side -> false);
    sink.finish();
    return quads[0];
  }

  public static void emit(
      PoseStack pose, List<BlockStateModelPart> parts, int stage, int size, Sink output) {
    if (stage < 0 || stage > 9 || size < 1 || size > 256)
      throw new IllegalArgumentException("Destroy atlas bounds");
    var sink = new Quads(stage, size, output);
    var vanilla = new SheetedDecalTextureGenerator(sink, pose.last(), 1);
    var instance = new QuadInstance();
    instance.setLightCoords(15728880);
    instance.setOverlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    for (var part : parts) {
      for (var side : SIDES)
        for (var quad : part.getQuads(side)) vanilla.putBakedQuad(pose.last(), quad, instance);
      for (var quad : part.getQuads(null)) vanilla.putBakedQuad(pose.last(), quad, instance);
    }
    sink.finish();
  }

  private static final class Quads implements VertexConsumer {
    private final int stage, size, height;
    private final Sink output;
    private final float[][] vertices = new float[4][5];
    private int index = -1;

    Quads(int stage, int size, Sink output) {
      this.stage = stage;
      this.size = size;
      this.height = size * 10 + 1;
      this.output = output;
    }

    public VertexConsumer addVertex(float x, float y, float z) {
      if (index == 3) {
        emit();
        index = -1;
      }
      var v = vertices[++index];
      v[0] = x;
      v[1] = y;
      v[2] = z;
      return this;
    }

    public VertexConsumer setUv(float u, float v) {
      vertices[index][3] = u;
      vertices[index][4] = v;
      return this;
    }

    void finish() {
      if (index == 3) emit();
      else if (index != -1) throw new IllegalArgumentException("Incomplete crack quad");
      index = -1;
    }

    private void emit() {
      float minU = Float.POSITIVE_INFINITY, minV = minU;
      for (var p : vertices) {
        minU = Math.min(minU, p[3]);
        minV = Math.min(minV, p[4]);
      }
      float baseU = (float) Math.floor(minU + 1e-5), baseV = (float) Math.floor(minV + 1e-5);
      for (int i : TRIANGLES) {
        var p = vertices[i];
        float u = p[3] - baseU, v = p[4] - baseV;
        if (!Float.isFinite(u)
            || !Float.isFinite(v)
            || u < -.001
            || u > 1.001
            || v < -.001
            || v > 1.001) throw new IllegalArgumentException("Destroy UV repeat exceeds tile");
        output.vertex(
            p[0],
            p[1],
            p[2],
            (.5f + Math.clamp(u, 0, 1) * (size - 1)) / size,
            (stage * size + .5f + Math.clamp(v, 0, 1) * (size - 1)) / height);
      }
    }

    public VertexConsumer setColor(int r, int g, int b, int a) {
      return this;
    }

    public VertexConsumer setColor(int c) {
      return this;
    }

    public VertexConsumer setUv1(int u, int v) {
      return this;
    }

    public VertexConsumer setUv2(int u, int v) {
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
