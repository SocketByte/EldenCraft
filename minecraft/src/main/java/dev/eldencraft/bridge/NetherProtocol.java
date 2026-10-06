package dev.eldencraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ECNH v1: the Nether state the compositor paints over Elden Ring. See PROTOCOL.md. 256 bytes at
 * {@code Local\EldenCraftNether}, little endian, one seqlock at +8. Positions are canonical host
 * coordinates, the same space as ECAM and ECFS. Times are GetTickCount64 milliseconds, the clock
 * both processes already share.
 */
public final class NetherProtocol {
  public static final int MAGIC = 0x484e4345, VERSION = 1, BYTES = 256;
  public static final int FLAG_ACTIVE = 1, FLAG_CENTRED = 2;

  private NetherProtocol() {}

  /**
   * One publication. {@code grid} is a host position of a Minecraft block corner; {@code center}
   * the spread centre.
   */
  public record State(
      long pid,
      long hostPid,
      long epoch,
      long map,
      long anchor,
      boolean active,
      boolean centred,
      WorldOrigin.Vec grid,
      WorldOrigin.Vec center,
      float amount,
      float radius,
      float warp,
      float shake,
      float dread,
      long millis,
      long openedMillis,
      long flashMillis,
      long beatMillis,
      long shakeMillis) {
    public State {
      if (pid <= 0
          || pid > 0xffff_ffffL
          || hostPid <= 0
          || hostPid > 0xffff_ffffL
          || epoch <= 0
          || map < 0
          || map > 0xffff_ffffL
          || anchor <= 0
          || grid == null
          || center == null) throw new IllegalArgumentException("Nether identity");
      for (float v : new float[] {amount, warp, shake, dread})
        if (!(v >= 0 && v <= 1)) throw new IllegalArgumentException("Nether factor");
      if (!(radius >= 0 && radius <= NetherRules.EVERYWHERE))
        throw new IllegalArgumentException("Nether radius");
      if (millis <= 0 || openedMillis < 0 || flashMillis < 0 || beatMillis < 0 || shakeMillis < 0)
        throw new IllegalArgumentException("Nether time");
    }
  }

  /** Body bytes; the writer owns the sequence at +8 and copies everything else around it. */
  public static byte[] encode(State s) {
    var b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, MAGIC)
        .putInt(4, VERSION)
        .putInt(16, (int) s.pid)
        .putInt(20, (int) s.hostPid)
        .putLong(24, s.epoch)
        .putInt(32, (int) s.map)
        .putInt(36, (s.active ? FLAG_ACTIVE : 0) | (s.centred ? FLAG_CENTRED : 0))
        .putLong(40, s.millis)
        .putLong(48, s.anchor)
        .putDouble(56, s.grid.x())
        .putDouble(64, s.grid.y())
        .putDouble(72, s.grid.z())
        .putDouble(80, s.center.x())
        .putDouble(88, s.center.y())
        .putDouble(96, s.center.z())
        .putFloat(104, s.amount)
        .putFloat(108, s.radius)
        .putFloat(112, s.warp)
        .putFloat(116, s.shake)
        .putFloat(120, s.dread)
        .putLong(128, s.openedMillis)
        .putLong(136, s.flashMillis)
        .putLong(144, s.beatMillis)
        .putLong(152, s.shakeMillis);
    return b.array();
  }
}
