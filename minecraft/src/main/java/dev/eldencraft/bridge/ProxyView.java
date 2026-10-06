package dev.eldencraft.bridge;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Render-only conversion from coherent ECTG world coordinates to the host's screen basis. */
public final class ProxyView {
  private ProxyView() {}

  public static ProxyProtocol.Vec relative(ProxyProtocol.Vec point, ProxyProtocol.Vec camera) {
    return new ProxyProtocol.Vec(
        point.x() - camera.x(), point.y() - camera.y(), point.z() - camera.z());
  }

  public static Matrix4f rotation(ProxyProtocol.Vec forward) {
    double length =
        Math.sqrt(
            forward.x() * forward.x() + forward.y() * forward.y() + forward.z() * forward.z());
    if (!Double.isFinite(length) || Math.abs(length - 1) > .001)
      throw new IllegalArgumentException("invalid debug view direction");
    float yaw = (float) Math.atan2(-forward.x(), forward.z());
    float pitch = (float) -Math.asin(Math.clamp(forward.y(), -1, 1));
    // Vanilla Camera.setRotation/getViewRotationMatrix, followed by a VIEW-space X
    // reflection. ER and Minecraft disagree on screen-right for unchanged world XYZ.
    // Do not reflect gameplay coordinates or the hand/HUD render pass.
    var rotation = new Quaternionf().rotationYXZ((float) Math.PI - yaw, -pitch, 0).conjugate();
    return new Matrix4f().scaling(-1, 1, 1).rotate(rotation);
  }
}
