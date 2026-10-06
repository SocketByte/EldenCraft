package dev.eldencraft.bridge;

/** Separate face/coverage changes from shading work on an otherwise complete mesh. */
public final class BlockMeshChanges {
  public record Version(long geometry, long lighting) {}

  private long geometry = 1, lighting = 1;

  public Version version() {
    return new Version(geometry, lighting);
  }

  public void geometryChanged() {
    geometry++;
  }

  public void lightingChanged() {
    lighting++;
  }

  public boolean current(Version version) {
    return version != null && version.geometry == geometry && version.lighting == lighting;
  }

  public boolean geometryCurrent(Version version) {
    return version != null && version.geometry == geometry;
  }
}
