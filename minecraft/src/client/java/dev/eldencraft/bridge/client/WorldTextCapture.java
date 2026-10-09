package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Counter the shared world's screen parity for whole text lines, preserving glyph face winding. */
public final class WorldTextCapture {
  private static final ThreadLocal<Float> CENTER = new ThreadLocal<>();

  @FunctionalInterface
  public interface Draw {
    void render(Matrix4fc pose, VertexConsumer vertices);
  }

  private WorldTextCapture() {}

  public static void line(boolean worldPass, float center, Runnable draw) {
    var previous = CENTER.get();
    if (worldPass) CENTER.set(center);
    else CENTER.remove();
    try {
      draw.run();
    } finally {
      if (previous == null) CENTER.remove();
      else CENTER.set(previous);
    }
  }

  public static Matrix4f reflect(Matrix4fc pose, float center) {
    return new Matrix4f(pose).translate(2 * center, 0, 0).scale(-1, 1, 1);
  }

  public static void glyph(Matrix4fc pose, VertexConsumer vertices, Draw draw) {
    var center = CENTER.get();
    if (center == null) {
      draw.render(pose, vertices);
      return;
    }
    var reversed = new ReversedQuads(vertices);
    draw.render(reflect(pose, center), reversed);
    reversed.finish();
  }

  /** Font pipelines are culled. Reverse the reflected quad, carrying each vertex's own UV/color. */
  private static final class ReversedQuads implements VertexConsumer {
    private static final int COLOR = 1, UV = 2, UV1 = 4, UV2 = 8, UV3 = 16, NORMAL = 32, WIDTH = 64;
    private static final int[] ORDER = {0, 3, 2, 1};
    private final VertexConsumer target;
    private final Vertex[] quad = {new Vertex(), new Vertex(), new Vertex(), new Vertex()};
    private int count;

    private static final class Vertex {
      float x, y, z, u, v, u3, v3, nx, ny, nz, width;
      int color, u1, v1, u2, v2, fields;
    }

    ReversedQuads(VertexConsumer target) {
      this.target = target;
    }

    private Vertex current() {
      if (count == 0) throw new IllegalStateException("Glyph attribute before vertex");
      return quad[count - 1];
    }

    private void flush() {
      if (count == 0) return;
      if (count != 4) throw new IllegalStateException("Font emitted an incomplete quad");
      for (int index : ORDER) {
        var v = quad[index];
        var out = target.addVertex(v.x, v.y, v.z);
        if ((v.fields & COLOR) != 0) out.setColor(v.color);
        if ((v.fields & UV) != 0) out.setUv(v.u, v.v);
        if ((v.fields & UV1) != 0) out.setUv1(v.u1, v.v1);
        if ((v.fields & UV2) != 0) out.setUv2(v.u2, v.v2);
        if ((v.fields & UV3) != 0) out.setUv3(v.u3, v.v3);
        if ((v.fields & NORMAL) != 0) out.setNormal(v.nx, v.ny, v.nz);
        if ((v.fields & WIDTH) != 0) out.setLineWidth(v.width);
      }
      count = 0;
    }

    void finish() {
      flush();
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      if (count == 4) flush();
      var v = quad[count++];
      v.x = x;
      v.y = y;
      v.z = z;
      v.fields = 0;
      return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      return setColor((alpha << 24) | (red << 16) | (green << 8) | blue);
    }

    @Override
    public VertexConsumer setColor(int color) {
      current().color = color;
      current().fields |= COLOR;
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      current().u = u;
      current().v = v;
      current().fields |= UV;
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      current().u1 = u;
      current().v1 = v;
      current().fields |= UV1;
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      current().u2 = u;
      current().v2 = v;
      current().fields |= UV2;
      return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
      current().u3 = u;
      current().v3 = v;
      current().fields |= UV3;
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      current().nx = x;
      current().ny = y;
      current().nz = z;
      current().fields |= NORMAL;
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      current().width = width;
      current().fields |= WIDTH;
      return this;
    }
  }
}
