package dev.eldencraft.bridge;

/** Server-owned stamina account, independent of health units and native stat allocation. */
public final class CampaignStamina {
  private double current, maximum;
  private double sinceSpent;
  private boolean guardBroken;

  public CampaignStamina(double maximum) {
    if (!Double.isFinite(maximum) || maximum <= 0)
      throw new IllegalArgumentException("stamina capacity");
    this.maximum = maximum;
    current = maximum;
  }

  public double current() {
    return current;
  }

  public double maximum() {
    return maximum;
  }

  public boolean guardReady() {
    return !guardBroken && current > 0;
  }

  public void capacity(double value) {
    if (!Double.isFinite(value) || value <= 0)
      throw new IllegalArgumentException("stamina capacity");
    maximum = value;
    current = Math.min(current, value);
  }

  public boolean canSpend(double cost) {
    return cost >= 0 && Double.isFinite(cost) && current >= cost;
  }

  public boolean spend(double cost) {
    if (!canSpend(cost)) return false;
    current -= cost;
    if (current == 0) guardBroken = true;
    sinceSpent = 0;
    return true;
  }

  /**
   * Charges guard hits that native already resolved. Returns true when this charge broke a ready
   * guard, so the caller can signal it.
   */
  public boolean guard(double rawDamage, long hits, CampaignConfig.Stamina rules) {
    if (!Double.isFinite(rawDamage) || rawDamage < 0 || hits < 0) return false;
    double cost = rules.guardBase() * hits + rules.guardPerDamage() * rawDamage;
    if (cost <= 0) return false;
    boolean ready = guardReady();
    current = Math.max(0, current - cost);
    sinceSpent = 0;
    if (current == 0) guardBroken = true;
    return ready && guardBroken;
  }

  /**
   * Fraction of one hit the remaining stamina can absorb: all of it when stamina covers the guard
   * cost, otherwise the proportion stamina pays for. The rest of the hit goes through.
   */
  public static double absorbed(double available, double rawDamage, CampaignConfig.Stamina rules) {
    if (!Double.isFinite(available) || available <= 0) return 0;
    if (!Double.isFinite(rawDamage) || rawDamage <= 0) return 1;
    double cost = rules.guardBase() + rules.guardPerDamage() * rawDamage;
    return cost <= available ? 1 : Math.clamp(available / cost, 0, 1);
  }

  /** Seconds until a broken guard can be raised again at the configured regeneration. */
  public static double recoverySeconds(CampaignConfig.Stamina rules) {
    double regen = rules.regenPerSecond();
    return rules.regenDelaySeconds() + (regen > 0 ? rules.guardRecovery() / regen : 0);
  }

  public void tick(double seconds, boolean usingItem, CampaignConfig.Stamina rules) {
    if (!Double.isFinite(seconds) || seconds < 0) return;
    double before = sinceSpent;
    sinceSpent += seconds;
    if (!usingItem) {
      double elapsed = Math.max(0, sinceSpent - Math.max(before, rules.regenDelaySeconds()));
      current = Math.min(maximum, current + rules.regenPerSecond() * elapsed);
    }
    if (guardBroken && current >= rules.guardRecovery()) guardBroken = false;
  }

  public void rest() {
    current = maximum;
    guardBroken = false;
    sinceSpent = 0;
  }
}
