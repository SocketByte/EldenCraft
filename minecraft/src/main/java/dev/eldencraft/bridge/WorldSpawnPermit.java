package dev.eldencraft.bridge;

/** Transient admission for a newly constructed Mob; saved mobs enter through vanilla LOAD. */
public interface WorldSpawnPermit {
  boolean eldencraft$spawnPermitted();

  void eldencraft$spawnPermitted(boolean value);
}
