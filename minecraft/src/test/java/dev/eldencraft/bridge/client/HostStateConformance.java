package dev.eldencraft.bridge.client;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.function.Consumer;

/** Standalone decoder + real Windows mapping checks with synthetic poses, not a game test. */
public final class HostStateConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("HostState check " + checks);
  }

  private static byte[] fixture(long now) {
    ByteBuffer b = ByteBuffer.allocate(HostState.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, HostState.MAGIC).putInt(4, 1).putLong(8, 2).putLong(16, 3).putLong(24, now);
    b.putInt(32, (int) ProcessHandle.current().pid()).putInt(36, 7);
    b.putDouble(40, 2.5).putDouble(48, 3.5).putDouble(56, 4.5);
    b.putFloat(64, 0).putFloat(68, 0.6f).putFloat(72, 0.8f).putFloat(76, 60);
    b.putDouble(80, 2.5).putDouble(88, 1.8).putDouble(96, 4.5);
    b.putInt(104, 350).putInt(108, 1000).putInt(112, HostState.ATTACK).putInt(116, 1);
    b.putFloat(120, 0.25f).putFloat(124, 0.75f).putLong(128, 42);
    return b.array();
  }

  private static void rejects(Consumer<ByteBuffer> change) {
    byte[] value = fixture(10_000);
    change.accept(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN));
    try {
      HostState.decode(value, 10_000);
      throw new AssertionError("Malformed host state accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static void publish(MemorySegment mapping, byte[] payload, long sequence) {
    mapping.set(ValueLayout.JAVA_LONG, 8, sequence - 1);
    VarHandle.fullFence();
    MemorySegment source = MemorySegment.ofArray(payload);
    MemorySegment.copy(source, 0, mapping, 0, 8);
    MemorySegment.copy(source, 16, mapping, 16, HostState.BYTES - 16);
    VarHandle.fullFence();
    mapping.set(ValueLayout.JAVA_LONG, 8, sequence);
    VarHandle.fullFence();
  }

  public static void main(String[] args) throws Throwable {
    var s = HostState.decode(fixture(10_000), 10_000);
    check(s.active() && s.firstPerson() && s.held(HostState.ATTACK));
    check(s.hostFrame() == 3 && s.hp() == 350 && s.maxHp() == 1000 && s.inputSequence() == 42);
    check(
        s.camera().x() == 2.5 && s.feet().y() == 1.8 && Math.abs(s.forward().y() - 0.6) < 0.00001);
    check(s.cursorX() == 0.25f && s.cursorY() == 0.75f && s.wheelDelta() == 1);
    check(!s.runesValid() && s.runes() == -1);
    check(HostState.decode(fixture(8001), 10_000).active());
    for (int flags :
        new int[] {0, HostState.ACTIVE, HostState.FOREGROUND, HostState.FIRST_PERSON}) {
      byte[] inactive = fixture(10_000);
      ByteBuffer.wrap(inactive).order(ByteOrder.LITTLE_ENDIAN).putInt(36, flags);
      var result = HostState.decode(inactive, 10_000);
      check(!result.active() && result.buttonsDown() == 0 && result.wheelDelta() == 0);
    }
    byte[] whistle = fixture(10_000);
    ByteBuffer.wrap(whistle)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(112, HostState.TORRENT | HostState.SWAP_HANDS);
    check(HostState.decode(whistle, 10_000).held(HostState.TORRENT));
    rejects(b -> b.putInt(112, HostState.TORRENT << 1));
    rejects(b -> b.putInt(0, 0));
    rejects(b -> b.putInt(4, 3));
    rejects(b -> b.putLong(8, 0));
    rejects(b -> b.putLong(8, 3));
    rejects(b -> b.putLong(16, 0));
    rejects(b -> b.putLong(24, 8000));
    rejects(b -> b.putLong(24, 10_001));
    rejects(b -> b.putInt(32, 0));
    rejects(b -> b.putInt(36, 8));
    rejects(b -> b.putDouble(40, Double.NaN));
    rejects(b -> b.putDouble(80, 30_000_001));
    rejects(b -> b.putFloat(64, Float.POSITIVE_INFINITY));
    rejects(
        b -> {
          b.putFloat(68, 0);
          b.putFloat(72, 0);
        });
    rejects(b -> b.putFloat(72, 2));
    rejects(b -> b.putFloat(76, 179));
    rejects(b -> b.putInt(104, -1));
    rejects(b -> b.putInt(108, 0));
    rejects(b -> b.putInt(104, 1001));
    rejects(b -> b.putInt(112, 1 << 30));
    rejects(b -> b.putInt(116, Integer.MIN_VALUE));
    rejects(b -> b.putFloat(120, -0.1f));
    rejects(b -> b.putFloat(124, Float.NaN));
    rejects(b -> b.putLong(128, -1));
    rejects(b -> b.put(255, (byte) 1));
    byte[] extended = fixture(10_000);
    ByteBuffer v2 = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
    v2.putInt(4, 2)
        .putInt(36, 11)
        .putInt(136, 2)
        .putFloat(140, 43200)
        .putFloat(144, -90)
        .putFloat(148, 4.3f)
        .putInt(152, 1)
        .putInt(156, -1);
    var thirdPerson = HostState.decode(extended, 10_000);
    check(!thirdPerson.firstPerson() && thirdPerson.viewMode() == 2 && thirdPerson.timeValid());
    check(
        thirdPerson.avatarYawDegrees() == -90
            && thirdPerson.grounded()
            && thirdPerson.mapId() == 0xffffffffL);
    check(!thirdPerson.runesValid());
    v2.putInt(160, 1);
    check(HostState.decode(extended, 10_000).runes() == 0);
    v2.putInt(164, -1);
    check(HostState.decode(extended, 10_000).runes() == 0xffffffffL);
    v2.putInt(160, 0).putInt(164, 0);
    check(HostState.decode(extended, 10_000).weather() == -1);
    v2.putInt(160, HostState.WEATHER_VALID);
    check(HostState.decode(extended, 10_000).weather() == HostState.CLEAR);
    v2.putInt(160, HostState.RUNES_VALID | HostState.WEATHER_VALID)
        .putInt(164, 5)
        .putInt(168, HostState.THUNDER);
    var stormy = HostState.decode(extended, 10_000);
    check(stormy.weatherValid() && stormy.weather() == HostState.THUNDER && stormy.runes() == 5);
    v2.putInt(160, 0).putInt(164, 0).putInt(168, 0);
    v2.putInt(36, 11 | HostState.SPRINTING);
    check(HostState.decode(extended, 10_000).sprinting());
    v2.putInt(36, 11);
    for (int flags :
        new int[] {0, HostState.ACTIVE, HostState.FOREGROUND, HostState.FIRST_PERSON}) {
      byte[] inactive = extended.clone();
      ByteBuffer.wrap(inactive).order(ByteOrder.LITTLE_ENDIAN).putInt(36, flags).putInt(136, 0);
      var decoded = HostState.decode(inactive, 10_000);
      check(!decoded.active() && decoded.buttonsDown() == 0 && decoded.wheelDelta() == 0);
      ByteBuffer.wrap(inactive).order(ByteOrder.LITTLE_ENDIAN).putInt(160, 1).putInt(164, 100);
      check(!HostState.decode(inactive, 10_000).runesValid());
    }
    for (Consumer<ByteBuffer> invalid :
        java.util.List.<Consumer<ByteBuffer>>of(
            b -> b.putInt(136, 0),
            b -> b.putInt(136, 3),
            b -> b.putFloat(140, 86400),
            b -> b.putFloat(140, Float.NaN),
            b -> b.putFloat(144, 361),
            b -> b.putFloat(148, -1),
            b -> b.putInt(152, 2),
            b -> b.putInt(160, 4),
            b -> b.putInt(164, 1),
            b -> b.putInt(168, 1),
            b -> b.putInt(160, HostState.WEATHER_VALID).putInt(168, 3),
            b -> b.putInt(160, HostState.WEATHER_VALID).putInt(168, -1),
            b -> b.putInt(172, 1))) {
      byte[] changed = extended.clone();
      invalid.accept(ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN));
      try {
        HostState.decode(changed, 10_000);
        throw new AssertionError("Malformed extension accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
    }
    try {
      HostState.decode(new byte[255], 10_000);
      throw new AssertionError("Truncated state accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    if (System.getProperty("os.name").startsWith("Windows")) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle tick =
          linker.downcallHandle(
              kernel.find("GetTickCount64").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_LONG));
      String name = "Local\\EldenCraftHostTest-" + UUID.randomUUID();
      try (HostState missing = new HostState(name)) {
        check(missing.poll() == null);
      }
      try (SharedMemory publisher = SharedMemory.create(name, HostState.BYTES);
          HostState reader = new HostState(name)) {
        long now = (long) tick.invokeExact();
        publish(publisher.segment, fixture(now), 2);
        var result = reader.poll();
        check(
            result != null
                && result.publisherPid() == ProcessHandle.current().pid()
                && result.hp() == 350);
        // No previous active snapshot may leak through an incomplete publication.
        publisher.segment.set(ValueLayout.JAVA_LONG, 8, 3);
        VarHandle.fullFence();
        check(reader.poll() == null);
        byte[] inactive = fixture((long) tick.invokeExact());
        ByteBuffer.wrap(inactive).order(ByteOrder.LITTLE_ENDIAN).putInt(36, HostState.FOREGROUND);
        publish(publisher.segment, inactive, 4);
        check(reader.poll() == null);
        publish(publisher.segment, fixture((long) tick.invokeExact()), 6);
        check(reader.poll() != null);
        byte[] feedbackStale = fixture((long) tick.invokeExact() - 300);
        publish(publisher.segment, feedbackStale, 8);
        check(
            reader.poll(250) == null); // Hurt feedback needs tighter freshness than ordinary input.
        check(reader.poll() != null); // A strict read must not tear down the shared input mapping.
        publish(publisher.segment, fixture((long) tick.invokeExact()), 10);
        check(reader.poll(250) != null);
        byte[] stale = fixture((long) tick.invokeExact() - 2000);
        publish(publisher.segment, stale, 12);
        check(reader.poll() == null);
        reader.close();
        check(reader.poll() == null);
      }
    }
    System.out.println(
        "HostState conformance: " + checks + " checks passed (synthetic poses only).");
  }
}
