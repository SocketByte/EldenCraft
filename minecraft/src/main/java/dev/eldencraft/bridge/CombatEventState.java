package dev.eldencraft.bridge;

/**
 * Accepts only a completed vanilla swing from a fully charged eligible attempt in the same
 * lifecycle.
 */
public final class CombatEventState {
  private long sequence;
  private boolean attempted;
  private float attemptCharge, acceptedCharge;

  public void reset() {
    sequence = 0;
    attempted = false;
    attemptCharge = acceptedCharge = 0;
  }

  public void begin(boolean active, boolean usableMelee, float charge) {
    attempted = active && usableMelee && Float.isFinite(charge) && charge >= 1 && charge <= 1.001f;
    attemptCharge = attempted ? Math.min(1, charge) : 0;
  }

  public boolean complete(boolean vanillaAccepted) {
    boolean accepted = attempted && vanillaAccepted;
    attempted = false;
    if (accepted) {
      sequence++;
      acceptedCharge = attemptCharge;
    }
    return accepted;
  }

  public long sequence() {
    return sequence;
  }

  public float charge() {
    return acceptedCharge;
  }
}
