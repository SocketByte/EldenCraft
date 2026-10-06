package dev.eldencraft.bridge;

/** Pure state for a scoped client HUD mirror. It never represents server health authority. */
public final class HealthDisplayState {
  private Object player, world;
  private boolean packetKnown, mirrored;
  private float genuineHealth, writtenHealth;
  private float previousHealth;
  private long packetRevision, appliedRevision;

  /** A replacement player or world must receive its own genuine health packet before mirroring. */
  public void bind(Object player, Object world) {
    if (this.player != player || this.world != world) {
      this.player = player;
      this.world = world;
      packetKnown = false;
      mirrored = false;
    }
  }

  public void packet(Object player, Object world, float health) {
    if (this.player == player
        && this.world == world
        && player != null
        && world != null
        && Float.isFinite(health)
        && health >= 0
        && health <= 2048) {
      genuineHealth = health;
      packetKnown = true;
      packetRevision++;
    }
  }

  public Float begin(
      Object player,
      Object world,
      float currentHealth,
      float maxHealth,
      boolean genuinelyAlive,
      boolean hostEligible,
      int hostHealth,
      int hostMaximum) {
    bind(player, world);
    if (mirrored
        || player == null
        || world == null
        || !packetKnown
        || genuineHealth <= 0
        || !genuinelyAlive
        || !hostEligible
        || !Float.isFinite(maxHealth)
        || maxHealth <= 0
        || maxHealth > 2048
        || !Float.isFinite(currentHealth)
        || currentHealth <= 0
        || currentHealth > 2048
        || hostMaximum <= 0
        || hostHealth < 0
        || hostHealth > hostMaximum) return null;
    previousHealth = currentHealth;
    appliedRevision = packetRevision;
    writtenHealth = (float) (maxHealth * ((double) hostHealth / hostMaximum));
    mirrored = true;
    return writtenHealth;
  }

  /** Restore only the same identity and a value still owned by this mirror. */
  public Float end(Object player, Object world, float currentHealth) {
    if (this.player != player || this.world != world || !mirrored) return null;
    mirrored = false;
    return Float.compare(currentHealth, writtenHealth) == 0 && packetKnown
        ? (packetRevision == appliedRevision ? previousHealth : genuineHealth)
        : null;
  }

  public void reset() {
    player = null;
    world = null;
    packetKnown = false;
    mirrored = false;
  }
}
