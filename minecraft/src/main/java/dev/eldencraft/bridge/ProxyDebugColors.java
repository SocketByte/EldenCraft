package dev.eldencraft.bridge;

/** ARGB status colors for the actual published boxes; inspection never grants attack permission. */
public final class ProxyDebugColors {
  public static final int SELECTED = 0xffffff30,
      READY = 0xff40ff60,
      OUT_OF_REACH = 0xffff9630,
      BLOCKED = 0xffff4040;

  private ProxyDebugColors() {}

  public static int classify(
      boolean visibleAndHittable, boolean authorityReady, boolean withinReach, boolean selected) {
    if (!visibleAndHittable || !authorityReady) return BLOCKED;
    if (!withinReach) return OUT_OF_REACH;
    return selected ? SELECTED : READY;
  }
}
