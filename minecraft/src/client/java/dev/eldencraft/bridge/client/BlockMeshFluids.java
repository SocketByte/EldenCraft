package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Captures vanilla {@link FluidRenderer} quads (section-local positions) as native mesh triangles
 * in the same 28-byte layout as block quads: host position, atlas UV, ARGB colour, packed light.
 * Water joins the translucent pass and lava the solid one, so fluids stay in the native mesh.
 */
final class BlockMeshFluids implements FluidRenderer.Output {
  private final ByteArrayOutputStream[] layers = {
    new ByteArrayOutputStream(), new ByteArrayOutputStream(), new ByteArrayOutputStream()
  };
  private final Layer[] consumers = {new Layer(0), new Layer(1), new Layer(2)};
  private float ox, oy, oz;

  /** Host position of the section origin; fluid vertices are relative to it. */
  void begin(float x, float y, float z) {
    ox = x;
    oy = y;
    oz = z;
    for (var layer : layers) layer.reset();
    for (var consumer : consumers) consumer.count = 0;
  }

  /** Complete triangles captured for a layer since {@link #begin}. */
  byte[] layer(int index) {
    return layers[index].toByteArray();
  }

  static int layerIndex(ChunkSectionLayer layer) {
    return switch (layer) {
      case SOLID -> 0;
      case CUTOUT -> 1;
      case TRANSLUCENT -> 2;
    };
  }

  @Override
  public VertexConsumer getBuilder(ChunkSectionLayer layer) {
    return consumers[layerIndex(layer)];
  }

  private final class Layer implements VertexConsumer {
    private final int index;
    private final float[] x = new float[4], y = new float[4], z = new float[4];
    private final float[] u = new float[4], v = new float[4];
    private final int[] color = new int[4], light = new int[4];
    private final ByteBuffer vertex = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
    int count;

    Layer(int index) {
      this.index = index;
    }

    @Override
    public void addVertex(
        float px,
        float py,
        float pz,
        int argb,
        float pu,
        float pv,
        int overlay,
        int packedLight,
        float nx,
        float ny,
        float nz) {
      x[count] = px;
      y[count] = py;
      z[count] = pz;
      u[count] = pu;
      v[count] = pv;
      color[count] = argb;
      light[count] = packedLight;
      if (++count < 4) return;
      count = 0;
      for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
        vertex.clear();
        vertex
            .putFloat(ox + x[i])
            .putFloat(oy + y[i])
            .putFloat(oz + z[i])
            .putFloat(u[i])
            .putFloat(v[i])
            .putInt(color[i])
            .putInt(light[i]);
        layers[index].writeBytes(vertex.array());
      }
    }

    // FluidRenderer emits whole vertices through the method above; the element-wise
    // builder calls below are unused by it and keep no partial state.
    @Override
    public VertexConsumer addVertex(float px, float py, float pz) {
      return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
      return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
      return this;
    }

    @Override
    public VertexConsumer setUv(float pu, float pv) {
      return this;
    }

    @Override
    public VertexConsumer setUv1(int pu, int pv) {
      return this;
    }

    @Override
    public VertexConsumer setUv2(int pu, int pv) {
      return this;
    }

    @Override
    public VertexConsumer setUv3(float pu, float pv) {
      return this;
    }

    @Override
    public VertexConsumer setNormal(float nx, float ny, float nz) {
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      return this;
    }
  }
}
