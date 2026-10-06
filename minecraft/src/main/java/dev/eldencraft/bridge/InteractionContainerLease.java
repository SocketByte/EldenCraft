package dev.eldencraft.bridge;

import java.util.UUID;

/** Exact native grace and vanilla container ownership; ordinary menus cannot inherit this lease. */
public record InteractionContainerLease(
    long pid, long session, long token, UUID player, int syncId) {
  public record OpenContext(
      boolean offline,
      boolean dedicated,
      boolean shared,
      boolean alive,
      boolean paired,
      boolean inventoryIdle) {
    public boolean permitted() {
      return offline && dedicated && shared && alive && paired && inventoryIdle;
    }
  }

  public InteractionContainerLease {
    if (pid <= 0 || session <= 0 || token <= 0 || player == null || syncId < -1 || syncId == 0)
      throw new IllegalArgumentException("Interaction container owner");
  }

  public boolean current(
      InteractionProtocol.Snapshot snapshot, UUID currentPlayer, int choice, long now) {
    return InteractionProtocol.fresh(snapshot, now, pid)
        && snapshot.session() == session
        && player.equals(currentPlayer)
        && snapshot.menu() != null
        && snapshot.menu().kind().equals("grace")
        && snapshot.menu().token() == token
        && snapshot.menu().choices().stream()
            .anyMatch(
                row -> row.id() == choice && row.enabled() && row.action().equals("ender_chest"));
  }

  public boolean canOpen(
      InteractionProtocol.Snapshot snapshot,
      UUID currentPlayer,
      int choice,
      OpenContext context,
      long now) {
    return syncId == -1
        && context != null
        && context.permitted()
        && current(snapshot, currentPlayer, choice, now);
  }

  public InteractionContainerLease opened(int id) {
    if (syncId != -1 || id <= 0) throw new IllegalArgumentException("Container already opened");
    return new InteractionContainerLease(pid, session, token, player, id);
  }

  public boolean owns(UUID currentPlayer, int currentSyncId) {
    return syncId > 0 && player.equals(currentPlayer) && syncId == currentSyncId;
  }
}
