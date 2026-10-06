package dev.eldencraft.bridge;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable ECTG/ECDM codec. Contains no process access or game objects. */
public final class ProxyProtocol {
  public static final int BYTES = 4096, MAX_TARGETS = 16, MAX_RECEIPTS = 32;
  public static final int DEBUG_BOUNDS = 8;

  public record Vec(double x, double y, double z) {}

  public record Target(
      long handle, long generation, Vec min, Vec max, float hp, float maxHp, int flags, long team) {
    public boolean hittable() {
      return flags == 3 && hp > 0;
    }

    public UUID uuid(long epoch) {
      return UUID.nameUUIDFromBytes(
          ("eldencraft-proxy:" + epoch + ":" + handle + ":" + generation)
              .getBytes(StandardCharsets.UTF_8));
    }
  }

  public record Frame(
      long frame,
      long millis,
      long pid,
      int flags,
      long epoch,
      long map,
      Vec camera,
      Vec forward,
      float yaw,
      float pitch,
      float obstruction,
      float scale,
      long ackSession,
      long ackSequence,
      int result,
      List<Target> targets) {
    public boolean ready() {
      return (flags & 3) == 3;
    }

    public boolean debugBounds() {
      return (flags & DEBUG_BOUNDS) != 0;
    }
  }

  public record Receipt(
      long sequence,
      long handle,
      long generation,
      long targetFrame,
      long millis,
      long attackId,
      float damage,
      int wearBefore,
      int wearAfter,
      float playerMaxHp,
      String item) {}

  private ProxyProtocol() {}

  public static Frame decodeTargets(byte[] bytes, long now) {
    require(bytes.length == BYTES, "target size");
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    require(b.getInt(0) == 0x47544345 && b.getInt(4) == 1, "target version");
    long sequence = b.getLong(8),
        frame = b.getLong(16),
        millis = b.getLong(24),
        pid = Integer.toUnsignedLong(b.getInt(32));
    int flags = b.getInt(36);
    long epoch = b.getLong(40), map = Integer.toUnsignedLong(b.getInt(48));
    int count = b.getInt(52);
    require(
        sequence > 0 && (sequence & 1) == 0 && frame > 0 && pid > 0 && epoch > 0,
        "target identity");
    require(millis >= 0 && now >= millis && now - millis < 250, "target freshness");
    require((flags & ~15) == 0 && count >= 0 && count <= MAX_TARGETS, "target flags/count");
    Vec camera = vec(b, 56, 32), forward = vec(b, 68, 1.01);
    double length =
        Math.sqrt(forward.x * forward.x + forward.y * forward.y + forward.z * forward.z);
    require(Math.abs(length - 1) < .001, "target direction");
    float yaw = b.getFloat(80),
        pitch = b.getFloat(84),
        obstruction = b.getFloat(88),
        scale = b.getFloat(92);
    require(
        finite(yaw, 360)
            && finite(pitch, 90)
            && Float.isFinite(obstruction)
            && obstruction >= 0
            && obstruction <= 16,
        "target view");
    require(Float.isFinite(scale) && scale > 0 && scale <= 1_000_000, "damage scale");
    long ackSession = b.getLong(96), ackSequence = b.getLong(104);
    int result = b.getInt(112);
    require(
        ackSession >= 0
            && ackSequence >= 0
            && result >= 0
            && result <= 2
            && (ackSession != 0 || ackSequence == 0),
        "acknowledgment");
    zero(bytes, 116, 128);
    var targets = new ArrayList<Target>();
    var identities = new HashSet<Long>();
    for (int i = 0; i < count; i++) {
      int at = 128 + i * 80;
      long handle = b.getLong(at), generation = b.getLong(at + 8);
      Vec min = vec(b, at + 16, 32), max = vec(b, at + 28, 32);
      float hp = b.getFloat(at + 40), maxHp = b.getFloat(at + 44);
      int tf = b.getInt(at + 48);
      long team = Integer.toUnsignedLong(b.getInt(at + 52));
      require(handle != 0 && generation > 0 && identities.add(handle), "target handle");
      require(min.x < max.x && min.y < max.y && min.z < max.z, "target bounds");
      require(
          Float.isFinite(hp)
              && Float.isFinite(maxHp)
              && hp >= 0
              && maxHp > 0
              && hp <= maxHp
              && maxHp <= 100_000_000,
          "target health");
      require((tf & ~3) == 0, "target entry flags");
      zero(bytes, at + 56, at + 80);
      targets.add(new Target(handle, generation, min, max, hp, maxHp, tf, team));
    }
    zero(bytes, 128 + count * 80, BYTES);
    return new Frame(
        frame,
        millis,
        pid,
        flags,
        epoch,
        map,
        camera,
        forward,
        yaw,
        pitch,
        obstruction,
        scale,
        ackSession,
        ackSequence,
        result,
        List.copyOf(targets));
  }

  public static byte[] encodeReceipts(
      long sequence,
      long frame,
      long now,
      long pid,
      boolean active,
      long session,
      long hostPid,
      long map,
      long epoch,
      List<Receipt> receipts) {
    require(
        sequence > 0 && (sequence & 1) == 0 && frame > 0 && now >= 0 && pid > 0 && session > 0,
        "receipt identity");
    require(hostPid > 0 && epoch > 0 && receipts.size() <= MAX_RECEIPTS, "receipt host/count");
    var b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, 0x4d444345)
        .putInt(4, 1)
        .putLong(8, sequence)
        .putLong(16, frame)
        .putLong(24, now)
        .putInt(32, (int) pid);
    b.putInt(36, active ? 1 : 0)
        .putLong(40, session)
        .putInt(48, (int) hostPid)
        .putInt(52, (int) map)
        .putLong(56, epoch)
        .putInt(64, receipts.size());
    long previous = 0;
    int at = 128;
    for (Receipt r : receipts) {
      require(
          r.sequence > previous
              && r.handle != 0
              && r.generation > 0
              && r.targetFrame > 0
              && r.millis >= 0
              && r.attackId > 0,
          "receipt sequence/target");
      require(
          Float.isFinite(r.damage)
              && r.damage > 0
              && r.damage <= 1000
              && r.wearBefore >= 0
              && r.wearAfter >= 0,
          "receipt damage/wear");
      require(
          Float.isFinite(r.playerMaxHp) && r.playerMaxHp > 0 && r.playerMaxHp <= 2048,
          "receipt player health");
      byte[] id = r.item.getBytes(StandardCharsets.UTF_8);
      require(id.length <= 40 && r.item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "receipt item");
      b.putLong(at, r.sequence)
          .putLong(at + 8, r.handle)
          .putLong(at + 16, r.generation)
          .putLong(at + 24, r.targetFrame)
          .putLong(at + 32, r.millis)
          .putLong(at + 40, r.attackId);
      b.putFloat(at + 48, r.damage)
          .putInt(at + 52, r.wearBefore)
          .putInt(at + 56, r.wearAfter)
          .putFloat(at + 60, r.playerMaxHp)
          .putInt(at + 64, id.length);
      b.position(at + 72);
      b.put(id);
      previous = r.sequence;
      at += 112;
    }
    return b.array();
  }

  private static Vec vec(ByteBuffer b, int at, double bound) {
    float x = b.getFloat(at), y = b.getFloat(at + 4), z = b.getFloat(at + 8);
    require(finite(x, bound) && finite(y, bound) && finite(z, bound), "target vector");
    return new Vec(x, y, z);
  }

  private static boolean finite(float value, double bound) {
    return Float.isFinite(value) && Math.abs(value) <= bound;
  }

  private static void zero(byte[] b, int start, int end) {
    for (int i = start; i < end; i++) require(b[i] == 0, "reserved bytes");
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }
}
