package dev.eldencraft.bridge;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Independent geometric expectations for the observed host/guest screen-right mismatch. */
public final class ProxyViewConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  private static boolean near(float a, float b) {
    return Math.abs(a - b) < .00002f;
  }

  public static void main(String[] args) {
    for (double yaw : new double[] {-180, -90, 0, 45, 90, 179})
      for (double pitch : new double[] {-65, 0, 65}) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        var f =
            new ProxyProtocol.Vec(
                -Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        // Independent host basis: right=up_world cross forward; up=forward cross right.
        var forward = new Vector3f((float) f.x(), (float) f.y(), (float) f.z());
        var right = new Vector3f(0, 1, 0).cross(forward).normalize();
        var up = new Vector3f(forward).cross(right).normalize();
        var point =
            new Vector3f(forward)
                .mul(5)
                .add(new Vector3f(right).mul(1.25f))
                .add(new Vector3f(up).mul(.5f));
        var view = ProxyView.rotation(f);
        var eye = view.transformPosition(new Vector3f(point));
        var clip =
            new Matrix4f()
                .perspective((float) Math.toRadians(70), 16f / 9f, .05f, 2048, true)
                .transform(new Vector4f(eye, 1));
        float expectedX = 1.25f / (5f * (float) Math.tan(Math.toRadians(35)) * (16f / 9f));
        check(
            near(eye.x, 1.25f)
                && near(eye.y, .5f)
                && near(eye.z, -5f)
                && near(clip.x / clip.w, expectedX),
            "host right projects right at yaw/pitch " + yaw + "/" + pitch);
      }
    var view = ProxyView.rotation(new ProxyProtocol.Vec(0, 0, 1));
    var right = view.transformPosition(new Vector3f(4, 2, 10));
    var left = view.transformPosition(new Vector3f(-4, 2, 10));
    check(
        near(right.x, 4) && near(left.x, -4) && near(right.y, left.y) && near(right.z, left.z),
        "reflection changes screen side without changing height/depth");
    var a = ProxyView.relative(new ProxyProtocol.Vec(12, 9, 20), new ProxyProtocol.Vec(10, 8, 15));
    var b =
        ProxyView.relative(
            new ProxyProtocol.Vec(1000012, -999991, 2000020),
            new ProxyProtocol.Vec(1000010, -999992, 2000015));
    check(a.equals(b), "common world-anchor translation cancels before float conversion");
    for (var invalid :
        new ProxyProtocol.Vec[] {
          new ProxyProtocol.Vec(0, 0, 0), new ProxyProtocol.Vec(Double.NaN, 0, 1)
        }) {
      try {
        ProxyView.rotation(invalid);
        throw new AssertionError("invalid basis accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
    }
    System.out.println(
        "Proxy view conformance: "
            + checks
            + " checks passed (projection geometry only; no live render claim).");
  }
}
