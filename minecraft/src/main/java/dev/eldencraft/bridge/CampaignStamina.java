package dev.eldencraft.bridge;

/**
 * Server-owned stamina account, independent of health units and native stat allocation. As in Elden
 * Ring, any stamina left lets one more action through even when it costs more; that action empties
 * the bar and recovery then waits the longer exhausted delay.
 */
public final class CampaignStamina {
  /** Below this the bar is empty: rounding must not leave a sliver that buys another action. */
  private static final double EMPTY = 1e-9;

  private double current, maximum;
  private double sinceSpent;
  private boolean guardBroken;

  /** A blocked hit broke the guard: once it recovers, the shield stays down for the delay. */
  private boolean brokenByHit;

  /** Seconds left before a recovered broken guard can be raised. */
  private double shieldDelay;

  /** An action emptied the bar; recovery waits the exhausted delay. */
  private boolean exhausted;

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
    return !guardBroken && shieldDelay <= 0 && current > 0;
  }

  /** An action emptied the bar and the longer regeneration delay is still running. */
  public boolean exhausted() {
    return exhausted;
  }

  public void capacity(double value) {
    if (!Double.isFinite(value) || value <= 0)
      throw new IllegalArgumentException("stamina capacity");
    maximum = value;
    current = Math.min(current, value);
  }

  public boolean canSpend(double cost) {
    return allows(current, cost);
  }

  /** Any stamina left pays for one more action, however much it costs; an empty bar pays none. */
  public static boolean allows(double available, double cost) {
    return cost >= 0 && Double.isFinite(cost) && (available >= cost || available > EMPTY);
  }

  /** Spends {@code cost}, or whatever is left of it. An action that empties the bar exhausts it. */
  public boolean spend(double cost) {
    if (!canSpend(cost)) return false;
    current = Math.max(0, current - cost);
    if (current <= EMPTY) current = 0;
    if (current == 0) {
      guardBroken = true;
      if (cost > 0) exhausted = true;
    }
    sinceSpent = 0;
    return true;
  }

  /**
   * Charges guard hits that native already resolved. Returns true when this charge broke a ready
   * guard, so the caller can signal it.
   */
  public boolean guard(double rawDamage, long hits, CampaignConfig.Stamina rules) {
    return guard(rawDamage, hits, rules, 1);
  }

  /** {@code scale} multiplies the cost, as a shield's Guarding Ability does. */
  public boolean guard(double rawDamage, long hits, CampaignConfig.Stamina rules, double scale) {
    if (!Double.isFinite(rawDamage) || rawDamage < 0 || hits < 0) return false;
    double cost = (rules.guardBase() * hits + rules.guardPerDamage() * rawDamage) * scale(scale);
    if (cost <= 0) return false;
    boolean ready = guardReady();
    current = Math.max(0, current - cost);
    sinceSpent = 0;
    if (current == 0) guardBroken = true;
    if (!ready || !guardBroken) return false;
    brokenByHit = true;
    return true;
  }

  /**
   * Fraction of one hit the remaining stamina can absorb: all of it when stamina covers the guard
   * cost, otherwise the proportion stamina pays for. The rest of the hit goes through.
   */
  public static double absorbed(double available, double rawDamage, CampaignConfig.Stamina rules) {
    return absorbed(available, rawDamage, rules, 1);
  }

  public static double absorbed(
      double available, double rawDamage, CampaignConfig.Stamina rules, double scale) {
    if (!Double.isFinite(available) || available <= 0) return 0;
    if (!Double.isFinite(rawDamage) || rawDamage <= 0) return 1;
    double cost = (rules.guardBase() + rules.guardPerDamage() * rawDamage) * scale(scale);
    return cost <= available ? 1 : Math.clamp(available / cost, 0, 1);
  }

  /** Enchantments only discount a guard; a malformed scale never exceeds the configured cost. */
  private static double scale(double scale) {
    return Double.isFinite(scale) ? Math.clamp(scale, 0, 1) : 1;
  }

  /** Gives stamina back without ending the regeneration delay; a broken guard stays broken. */
  public void restore(double amount) {
    if (!Double.isFinite(amount) || amount <= 0 || !guardReady()) return;
    current = Math.min(maximum, current + amount);
  }

  /**
   * Seconds until a guard broken by a blocked hit can be raised again at the configured
   * regeneration, including the shield delay that follows its recovery.
   */
  public static double recoverySeconds(CampaignConfig.Stamina rules) {
    double regen = rules.regenPerSecond();
    return rules.regenDelaySeconds()
        + (regen > 0 ? rules.guardRecovery() / regen : 0)
        + rules.brokenShieldDelaySeconds();
  }

  public void tick(double seconds, boolean usingItem, CampaignConfig.Stamina rules) {
    tick(seconds, usingItem, rules, 1);
  }

  /** {@code recovery} scales regeneration after the delay, as Greenburst does. */
  public void tick(
      double seconds, boolean usingItem, CampaignConfig.Stamina rules, double recovery) {
    if (!Double.isFinite(seconds) || seconds < 0) return;
    double scale = Double.isFinite(recovery) ? Math.clamp(recovery, 1, 2) : 1;
    double before = sinceSpent, delay = regenDelay(rules);
    sinceSpent += seconds;
    if (!usingItem) {
      double elapsed = Math.max(0, sinceSpent - Math.max(before, delay));
      current = Math.min(maximum, current + rules.regenPerSecond() * scale * elapsed);
    }
    // The longer wait covers one delay; later expenditures wait the ordinary one again.
    if (sinceSpent >= delay) exhausted = false;
    shieldDelay = Math.max(0, shieldDelay - seconds);
    if (guardBroken && current >= rules.guardRecovery()) {
      guardBroken = false;
      if (brokenByHit) shieldDelay = rules.brokenShieldDelaySeconds();
      brokenByHit = false;
    }
  }

  /** The wait after the latest expenditure before recovery starts. */
  public double regenDelay(CampaignConfig.Stamina rules) {
    return exhausted ? rules.exhaustedDelaySeconds() : rules.regenDelaySeconds();
  }

  /**
   * Stamina per second {@link #tick} is recovering right now: 0 while busy, inside the delay after
   * an expenditure, or full.
   */
  public double recoveryRate(boolean usingItem, CampaignConfig.Stamina rules, double recovery) {
    if (usingItem || current >= maximum || sinceSpent < regenDelay(rules)) return 0;
    double scale = Double.isFinite(recovery) ? Math.clamp(recovery, 1, 2) : 1;
    return rules.regenPerSecond() * scale;
  }

  public void rest() {
    current = maximum;
    guardBroken = brokenByHit = false;
    shieldDelay = 0;
    exhausted = false;
    sinceSpent = 0;
  }
}
