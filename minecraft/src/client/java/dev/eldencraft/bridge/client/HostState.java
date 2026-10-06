package dev.eldencraft.bridge.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Read-only ECHS v1/v2 transport. No Minecraft dependencies or OS input injection. */
public final class HostState implements AutoCloseable {
  public static final String MAPPING_NAME = "Local\\EldenCraftHost";
  public static final int BYTES = 256, MAGIC = 0x53484345, VERSION = 2;
  public static final int ACTIVE = 1,
      FOREGROUND = 2,
      FIRST_PERSON = 4,
      TIME_VALID = 8,
      SPRINTING = 16;
  public static final int ATTACK = 1, USE = 1 << 1, INVENTORY = 1 << 2, ESCAPE = 1 << 3;
  public static final int HOTBAR_1 = 1 << 4, JUMP = 1 << 13, SNEAK = 1 << 14, SPRINT = 1 << 15;
  public static final int FORWARD = 1 << 16, BACKWARD = 1 << 17, LEFT = 1 << 18, RIGHT = 1 << 19;
  public static final int DROP = 1 << 20, SWAP_HANDS = 1 << 21, TORRENT = 1 << 22;
  public static final int BUTTON_MASK = (1 << 23) - 1;
  private static final VarHandle SEQUENCE =
      ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).varHandle();
  private final String mappingName;
  private Native api;
  private MemorySegment handle = MemorySegment.NULL,
      view = MemorySegment.NULL,
      process = MemorySegment.NULL;
  private long processPid, nextOpenNanos;
  private boolean closed;
  private String status = "waiting for host";

  public record Vec3(double x, double y, double z) {}

  public record Snapshot(
      long sequence,
      long hostFrame,
      long timestampMillis,
      long publisherPid,
      int flags,
      Vec3 camera,
      Vec3 forward,
      float verticalFovDegrees,
      Vec3 feet,
      int hp,
      int maxHp,
      int buttonsDown,
      int wheelDelta,
      float cursorX,
      float cursorY,
      long inputSequence,
      int viewMode,
      float timeSeconds,
      float avatarYawDegrees,
      float movementSpeed,
      boolean grounded,
      long mapId,
      long runes) {
    public boolean firstPerson() {
      return (flags & FIRST_PERSON) != 0;
    }

    public boolean active() {
      return (flags & (ACTIVE | FOREGROUND)) == (ACTIVE | FOREGROUND);
    }

    public boolean held(int button) {
      return (buttonsDown & button) != 0;
    }

    public boolean timeValid() {
      return (flags & TIME_VALID) != 0;
    }

    public boolean sprinting() {
      return (flags & SPRINTING) != 0;
    }

    /** -1 means unavailable; zero is a valid native currency balance. */
    public boolean runesValid() {
      return runes >= 0;
    }
  }

  public HostState() {
    this(MAPPING_NAME);
  }

  // Package-private to let tests use a unique synthetic map, never the game's mapping.
  HostState(String mappingName) {
    this.mappingName = mappingName;
  }

  /**
   * Null always means release imported controls. This method does not cache an old active state.
   */
  public synchronized Snapshot poll() {
    return poll(2000);
  }

  /**
   * A stricter freshness requirement may reject feedback without invalidating the input reader's
   * mapping.
   */
  public synchronized Snapshot poll(long maxAgeMillis) {
    if (maxAgeMillis < 1 || maxAgeMillis > 2000)
      throw new IllegalArgumentException("Invalid host freshness bound");
    if (closed) return null;
    try {
      if (view.address() == 0) {
        long now = System.nanoTime();
        if (nextOpenNanos != 0 && now - nextOpenNanos < 0) return null;
        nextOpenNanos = now + 1_000_000_000L;
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
          status = "Windows mapping unavailable";
          return null;
        }
        if (api == null) api = new Native();
        if (!openMapping()) {
          status = "waiting for host mapping";
          return null;
        }
      }
      for (int attempt = 0; attempt < 3; attempt++) {
        long before = (long) SEQUENCE.getVolatile(view, 8L);
        if (before == 0 || (before & 1) != 0) continue;
        VarHandle.fullFence();
        byte[] copy = new byte[BYTES];
        MemorySegment.copy(view, 0, MemorySegment.ofArray(copy), 0, BYTES);
        VarHandle.fullFence();
        long after = (long) SEQUENCE.getVolatile(view, 8L);
        if (before != after || (after & 1) != 0) continue;
        // Sample after the copy so a producer ticking during the copy cannot look future-dated.
        long nowMillis = (long) api.tickCount.invokeExact();
        Snapshot result = decode(copy, nowMillis);
        if (result.sequence() != before) continue;
        if (!publisherAlive(result.publisherPid())) {
          releaseMapping();
          status = "host process unavailable";
          return null;
        }
        if (!result.active()) {
          status = "host inactive or unfocused";
          return null;
        }
        if (nowMillis - result.timestampMillis() >= maxAgeMillis) {
          status = "host publication outside freshness bound";
          return null;
        }
        status = "active";
        return result;
      }
      status = "host publication in progress";
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      releaseMapping();
      status = "host state rejected: " + error.getClass().getSimpleName();
    }
    return null;
  }

  public synchronized String status() {
    return status;
  }

  private boolean openMapping() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment name =
          arena.allocateFrom(
              ValueLayout.JAVA_BYTE, (mappingName + "\0").getBytes(StandardCharsets.UTF_16LE));
      handle = (MemorySegment) api.open.invokeExact(4, 0, name);
      if (handle.address() == 0) return false;
      MemorySegment address = (MemorySegment) api.map.invokeExact(handle, 4, 0, 0, (long) BYTES);
      if (address.address() == 0) {
        releaseMapping();
        return false;
      }
      view = address.reinterpret(BYTES);
      return true;
    }
  }

  private boolean publisherAlive(long pid) throws Throwable {
    if (pid != processPid) {
      closeHandle(process);
      process = MemorySegment.NULL;
      processPid = 0;
      process = (MemorySegment) api.openProcess.invokeExact(0x00100000, 0, (int) pid);
      if (process.address() == 0) return false;
      processPid = pid;
    }
    return process.address() != 0 && (int) api.wait.invokeExact(process, 0) == 258;
  }

  private void closeHandle(MemorySegment value) {
    if (api == null || value.address() == 0) return;
    try {
      int ignored = (int) api.close.invokeExact(value);
    } catch (Throwable ignored) {
    }
  }

  private void releaseMapping() {
    if (api != null && view.address() != 0) {
      try {
        int ignored = (int) api.unmap.invokeExact(view);
      } catch (Throwable ignored) {
      }
    }
    closeHandle(handle);
    closeHandle(process);
    view = handle = process = MemorySegment.NULL;
    processPid = 0;
  }

  @Override
  public synchronized void close() {
    closed = true;
    releaseMapping();
    status = "closed";
  }

  /** Strict portable decoder. Inactive states can decode, but poll never returns them as input. */
  static Snapshot decode(byte[] bytes, long nowMillis) {
    require(bytes.length == BYTES, "incorrect ECHS size");
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int version = b.getInt(4);
    require(
        b.getInt(0) == MAGIC && (version == 1 || version == VERSION), "unsupported ECHS version");
    long sequence = b.getLong(8), frame = b.getLong(16), timestamp = b.getLong(24);
    long pid = Integer.toUnsignedLong(b.getInt(32));
    int flags = b.getInt(36);
    require(
        sequence > 0 && (sequence & 1) == 0 && frame > 0 && pid != 0,
        "invalid publication identity");
    require(
        timestamp >= 0 && nowMillis >= timestamp && nowMillis - timestamp < 2000,
        "stale or future host pose");
    require((flags & ~(version == 1 ? 7 : 31)) == 0, "unknown host flags");
    Vec3 camera = position(b, 40), feet = position(b, 80);
    double x = b.getFloat(64), y = b.getFloat(68), z = b.getFloat(72);
    double norm = Math.sqrt(x * x + y * y + z * z);
    require(Double.isFinite(norm) && Math.abs(norm - 1.0) < 0.001, "invalid camera direction");
    Vec3 forward = new Vec3(x / norm, y / norm, z / norm);
    float fov = b.getFloat(76);
    require(Float.isFinite(fov) && fov >= 1.0f && fov < 179.0f, "invalid field of view");
    int hp = b.getInt(104), maxHp = b.getInt(108), buttons = b.getInt(112), wheel = b.getInt(116);
    require(maxHp > 0 && maxHp <= 10_000_000 && hp >= 0 && hp <= maxHp, "invalid host health");
    require(
        (buttons & ~BUTTON_MASK) == 0 && wheel >= -16 && wheel <= 16, "invalid imported controls");
    float cursorX = b.getFloat(120), cursorY = b.getFloat(124);
    long inputSequence = b.getLong(128);
    require(
        Float.isFinite(cursorX)
            && Float.isFinite(cursorY)
            && cursorX >= 0
            && cursorX <= 1
            && cursorY >= 0
            && cursorY <= 1
            && inputSequence >= 0,
        "invalid cursor or input sequence");
    int viewMode = (flags & FIRST_PERSON) != 0 ? 0 : 1;
    float timeSeconds = 0,
        avatarYaw = (float) Math.toDegrees(Math.atan2(-forward.x(), forward.z())),
        movementSpeed = 0;
    boolean grounded = true;
    long mapId = 0, runes = -1;
    if (version == 2) {
      viewMode = b.getInt(136);
      timeSeconds = b.getFloat(140);
      avatarYaw = b.getFloat(144);
      movementSpeed = b.getFloat(148);
      int ground = b.getInt(152);
      mapId = Integer.toUnsignedLong(b.getInt(156));
      require(
          viewMode >= 0
              && viewMode <= 2
              && ((flags & (ACTIVE | FOREGROUND)) != (ACTIVE | FOREGROUND)
                  || ((flags & FIRST_PERSON) != 0) == (viewMode == 0)),
          "invalid view mode");
      require(
          Float.isFinite(timeSeconds) && timeSeconds >= 0 && timeSeconds < 86400,
          "invalid host time");
      require(Float.isFinite(avatarYaw) && Math.abs(avatarYaw) <= 360, "invalid avatar yaw");
      require(
          Float.isFinite(movementSpeed)
              && movementSpeed >= 0
              && movementSpeed <= 100
              && (ground == 0 || ground == 1),
          "invalid avatar movement");
      grounded = ground == 1;
      int extensions = b.getInt(160);
      long balance = Integer.toUnsignedLong(b.getInt(164));
      require(
          (extensions & ~1) == 0 && (extensions == 1 || balance == 0),
          "invalid host rune extension");
      if ((extensions & 1) != 0) runes = balance;
    }
    for (int i = version == 1 ? 136 : 168; i < BYTES; i++)
      require(bytes[i] == 0, "nonzero reserved byte");
    if ((flags & (ACTIVE | FOREGROUND)) != (ACTIVE | FOREGROUND)) {
      flags &= ~ACTIVE;
      buttons = 0;
      wheel = 0;
      runes = -1;
    }
    return new Snapshot(
        sequence,
        frame,
        timestamp,
        pid,
        flags,
        camera,
        forward,
        fov,
        feet,
        hp,
        maxHp,
        buttons,
        wheel,
        cursorX,
        cursorY,
        inputSequence,
        viewMode,
        timeSeconds,
        avatarYaw,
        movementSpeed,
        grounded,
        mapId,
        runes);
  }

  private static Vec3 position(ByteBuffer b, int offset) {
    double x = b.getDouble(offset), y = b.getDouble(offset + 8), z = b.getDouble(offset + 16);
    require(
        Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(z)
            && Math.abs(x) <= 30_000_000
            && Math.abs(y) <= 30_000_000
            && Math.abs(z) <= 30_000_000,
        "invalid host position");
    return new Vec3(x, y, z);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static final class Native {
    final MethodHandle open, map, unmap, close, tickCount, openProcess, wait;

    Native() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      open =
          linker.downcallHandle(
              kernel.find("OpenFileMappingW").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS));
      map =
          linker.downcallHandle(
              kernel.find("MapViewOfFile").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_LONG));
      unmap =
          linker.downcallHandle(
              kernel.find("UnmapViewOfFile").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      close =
          linker.downcallHandle(
              kernel.find("CloseHandle").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      tickCount =
          linker.downcallHandle(
              kernel.find("GetTickCount64").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_LONG));
      openProcess =
          linker.downcallHandle(
              kernel.find("OpenProcess").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT));
      wait =
          linker.downcallHandle(
              kernel.find("WaitForSingleObject").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }
  }
}
