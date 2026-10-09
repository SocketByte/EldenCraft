package dev.eldencraft.bridge;

/** Compensate the host screen handedness in image UVs, without moving the painting. */
public final class PaintingImage {
  private PaintingImage() {}

  public static float frontU(boolean sharedScene, float u) {
    // Reflect the entire sprite, rather than reversing each one-block tile independently.
    return sharedScene ? 1 - u : u;
  }
}
