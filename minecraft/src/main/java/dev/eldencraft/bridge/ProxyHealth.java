package dev.eldencraft.bridge;

/** Bounded vanilla damage recipients; native HP and outgoing damage keep their original scale. */
public record ProxyHealth(float maximum, float remaining) {
  public static ProxyHealth fromNative(
      double hp, double maxHp, double scale, double pendingDamage) {
    if (!Double.isFinite(hp)
        || !Double.isFinite(maxHp)
        || !Double.isFinite(scale)
        || !Double.isFinite(pendingDamage)
        || hp < 0
        || maxHp <= 0
        || hp > maxHp
        || scale <= 0
        || pendingDamage < 0) throw new IllegalArgumentException("proxy health");
    float maximum = (float) Math.max(1, Math.min(1024, maxHp / scale));
    // Pending loss applies after the cap: a large native HP pool must not refill
    // unacknowledged vanilla damage each time its proxy is synchronized.
    float remaining = (float) Math.max(0, Math.min(maximum, hp / scale) - pendingDamage);
    return new ProxyHealth(maximum, remaining);
  }
}
