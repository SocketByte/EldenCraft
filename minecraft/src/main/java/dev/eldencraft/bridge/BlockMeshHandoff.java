package dev.eldencraft.bridge;

import java.util.Arrays;
import java.util.Objects;

/**
 * Render-thread policy: one uploaded resident and at most one pending same-coverage update. How
 * long stale geometry may stay displayed is measured per change by {@link BlockMeshChanges}.
 */
public final class BlockMeshHandoff {
  public record Revision(long mesh, long atlas, long session) {
    public Revision {
      if (mesh <= 0 || atlas <= 0 || session <= 0)
        throw new IllegalArgumentException("Mesh handoff revision");
    }
  }

  private Revision latest, resident;

  /** Bootstrap after coverage, atlas or world identity invalidation; no old ownership survives. */
  public void geometry(Revision revision) {
    latest = Objects.requireNonNull(revision);
    resident = null;
  }

  public void invalidate() {
    latest = resident = null;
  }

  public boolean hasLatest() {
    return latest != null;
  }

  public boolean canPublishColor() {
    return latest != null && latest.equals(resident);
  }

  /** Caller has established identical positions, UVs, layer counts and atlas identity. */
  public void color(Revision revision) {
    update(revision);
  }

  /** Same-coverage replacement; identity/atlas changes still require full invalidation. */
  public void update(Revision revision) {
    if (!canPublishColor()
        || revision == null
        || revision.mesh <= latest.mesh
        || revision.atlas != latest.atlas
        || revision.session != latest.session)
      throw new IllegalArgumentException("Mesh handoff requires one acknowledged revision");
    latest = revision;
  }

  /** Supply exactly one coherent, identity-checked and fresh ACK sample for this frame. */
  public Revision select(Revision acknowledgement) {
    if (acknowledgement != null && acknowledgement.equals(latest)) resident = latest;
    else if (acknowledgement == null || !acknowledgement.equals(resident)) resident = null;
    return resident;
  }

  /** Changes to unused lightmap texels need no publication or native upload. */
  public static boolean payloadChanged(byte[] previous, byte[] recolored) {
    return !Arrays.equals(previous, recolored);
  }
}
