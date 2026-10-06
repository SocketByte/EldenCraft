package dev.eldencraft.bridge;

import java.nio.*;
import java.util.Objects;

/** Bounded runtime-only Minecraft block mesh transport; no extracted assets are persisted. */
public final class BlockMeshProtocol {
  public static final int HEADER = 128,
      MESH_MAGIC = 0x424d4345,
      ATLAS_MAGIC = 0x41424345,
      ACK_MAGIC = 0x414d4345;
  public static final int MESH_BYTES = 8 * 1024 * 1024,
      ATLAS_BYTES = HEADER + 4096 * 4096 * 4,
      STRIDE = 24,
      MAX_VERTICES = 262144;

  public record Identity(
      long producer, long host, long epoch, long map, long session, long anchor) {
    public Identity {
      if (producer <= 0
          || producer > 0xffffffffL
          || host <= 0
          || host > 0xffffffffL
          || epoch <= 0
          || map < 0
          || map > 0xffffffffL
          || session <= 0
          || anchor <= 0) throw new IllegalArgumentException("Mesh identity");
    }
  }

  public static byte[] header(
      int magic,
      Identity id,
      long stamp,
      long revision,
      long atlas,
      int a,
      int b,
      int bytes,
      int solid,
      int cutout,
      int translucent,
      boolean active) {
    if (stamp < 0 || revision <= 0 || atlas <= 0)
      throw new IllegalArgumentException("Mesh revision/time");
    if (magic == MESH_MAGIC) {
      if (a < 0
          || a > MAX_VERTICES
          || a % 3 != 0
          || b != STRIDE
          || bytes != a * STRIDE
          || bytes > MESH_BYTES - HEADER
          || solid < 0
          || cutout < 0
          || translucent < 0
          || solid % 3 != 0
          || cutout % 3 != 0
          || translucent % 3 != 0
          || (long) solid + cutout + translucent != a)
        throw new IllegalArgumentException("Mesh bounds");
    } else if (magic == ATLAS_MAGIC) {
      if (a < 1
          || a > 4096
          || b < 1
          || b > 4096
          || bytes != (long) a * b * 4
          || solid != 0
          || cutout != 0
          || translucent != 0) throw new IllegalArgumentException("Atlas bounds");
    } else throw new IllegalArgumentException("Mesh magic");
    var out = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0, magic)
        .putInt(4, 1)
        .putInt(16, (int) id.producer)
        .putInt(20, (int) id.host)
        .putLong(24, id.epoch)
        .putInt(32, (int) id.map)
        .putInt(36, active ? 1 : 0)
        .putLong(40, id.session)
        .putLong(48, stamp)
        .putLong(56, revision)
        .putLong(64, atlas)
        .putInt(72, a)
        .putInt(76, b)
        .putInt(80, bytes)
        .putInt(84, solid)
        .putInt(88, cutout)
        .putInt(92, translucent)
        .putLong(96, id.anchor);
    return out.array();
  }

  public static BlockMeshHandoff.Revision acknowledgement(byte[] bytes, Identity id, long now) {
    if (bytes == null || bytes.length != HEADER || id == null || now < 0) return null;
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    long seq = b.getLong(8), time = b.getLong(48);
    if (b.getInt(0) != ACK_MAGIC
        || b.getInt(4) != 1
        || seq <= 0
        || (seq & 1) != 0
        || b.getInt(36) != 1
        || time < 0
        || now < time
        || now - time > 250
        || Integer.toUnsignedLong(b.getInt(16)) != id.producer
        || Integer.toUnsignedLong(b.getInt(20)) != id.host
        || b.getLong(24) != id.epoch
        || Integer.toUnsignedLong(b.getInt(32)) != id.map
        || b.getLong(40) != id.session
        || b.getLong(56) <= 0
        || b.getLong(64) <= 0
        || b.getLong(96) != id.anchor) return null;
    for (int i = 72; i < 96; i++) if (bytes[i] != 0) return null;
    for (int i = 104; i < 128; i++) if (bytes[i] != 0) return null;
    return new BlockMeshHandoff.Revision(b.getLong(56), b.getLong(64), id.session);
  }

  public static boolean acknowledged(
      byte[] bytes, Identity id, long revision, long atlas, long now) {
    var ack = acknowledgement(bytes, id, now);
    return ack != null && ack.mesh() == revision && ack.atlas() == atlas;
  }

  /** Retry only transient seqlock contention; never extend the producer's original lease. */
  public static final class AcknowledgementCache {
    private byte[] coherent;
    private Identity identity;

    public BlockMeshHandoff.Revision accept(byte[] bytes, Identity id, long now) {
      clear();
      var result = acknowledgement(bytes, id, now);
      if (result != null) {
        coherent = bytes.clone();
        identity = id;
      }
      return result;
    }

    public BlockMeshHandoff.Revision contended(Identity id, long now) {
      if (!Objects.equals(identity, id)) {
        clear();
        return null;
      }
      var result = acknowledgement(coherent, id, now);
      if (result == null) clear();
      return result;
    }

    public void clear() {
      coherent = null;
      identity = null;
    }
  }

  /** 26.3 lightmap.fsh writes alpha=1 for every texel, including genuinely dark light. */
  public static boolean renderedLightmap(byte[] map) {
    if (map == null || map.length != 1024) return false;
    for (int i = 3; i < map.length; i += 4) if ((map[i] & 255) != 255) return false;
    return true;
  }

  /** Vanilla16x16 lightmap sampling at uv/256 + half a texel, clamped. */
  public static int litRgba(int argb, int light, byte[] map) {
    if (map.length != 1024) throw new IllegalArgumentException("Lightmap size");
    float u = Math.clamp((light & 65535) / 16f, 0, 15), v = Math.clamp((light >>> 16) / 16f, 0, 15);
    int x = (int) u, y = (int) v, x1 = Math.min(15, x + 1), y1 = Math.min(15, y + 1), out = 0;
    for (int c = 0; c < 4; c++) {
      float lo =
          (map[(y * 16 + x) * 4 + c] & 255) * (1 - (u - x))
              + (map[(y * 16 + x1) * 4 + c] & 255) * (u - x);
      float hi =
          (map[(y1 * 16 + x) * 4 + c] & 255) * (1 - (u - x))
              + (map[(y1 * 16 + x1) * 4 + c] & 255) * (u - x);
      int base = (argb >>> (c == 0 ? 16 : c == 1 ? 8 : c == 2 ? 0 : 24)) & 255;
      out |=
          Math.clamp(Math.round(base * (lo * (1 - (v - y)) + hi * (v - y)) / 255f), 0, 255)
              << (c * 8);
    }
    return out;
  }

  private BlockMeshProtocol() {}
}
