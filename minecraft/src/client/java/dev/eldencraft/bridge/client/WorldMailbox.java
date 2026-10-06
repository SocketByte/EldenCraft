package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.WorldProtocol;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** One guarded guest writer and read-only host view, touched only on the client thread. */
final class WorldMailbox implements AutoCloseable {
  private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
  private MemorySegment handle = MemorySegment.NULL,
      view = MemorySegment.NULL,
      guard = MemorySegment.NULL;
  private MethodHandle open, map, unmap, close, tick, mutex, lastError;
  private SharedMemory output;
  private long nextOpen, sequence, frame;
  private boolean failed;
  private WorldProtocol.Host retained;

  private void initialize() {
    if (tick != null) return;
    var linker = Linker.nativeLinker();
    var lib = SymbolLookup.libraryLookup("kernel32", Arena.global());
    open =
        linker.downcallHandle(
            lib.find("OpenFileMappingW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));
    map =
        linker.downcallHandle(
            lib.find("MapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG));
    unmap =
        linker.downcallHandle(
            lib.find("UnmapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    close =
        linker.downcallHandle(
            lib.find("CloseHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    tick =
        linker.downcallHandle(
            lib.find("GetTickCount64").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
    mutex =
        linker.downcallHandle(
            lib.find("CreateMutexW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));
    lastError =
        linker.downcallHandle(
            lib.find("GetLastError").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
  }

  long now() {
    try {
      initialize();
      return (long) tick.invokeExact();
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      return -1;
    }
  }

  WorldProtocol.Host read() {
    if (failed) return null;
    try {
      initialize();
      if (view.address() == 0) {
        long now = System.nanoTime();
        if (now < nextOpen) return null;
        nextOpen = now + 1_000_000_000L;
        try (var a = Arena.ofConfined()) {
          handle =
              (MemorySegment)
                  open.invokeExact(
                      4,
                      0,
                      a.allocateFrom(
                          ValueLayout.JAVA_BYTE,
                          "Local\\EldenCraftWorldHost\0".getBytes(StandardCharsets.UTF_16LE)));
          if (handle.address() == 0) return null;
          var raw = (MemorySegment) map.invokeExact(handle, 4, 0, 0, (long) WorldProtocol.SIZE);
          if (raw.address() == 0) {
            closeInput();
            return null;
          }
          view = raw.reinterpret(WorldProtocol.SIZE);
        }
      }
      for (int attempt = 0; attempt < 3; attempt++) {
        long before = (long) SEQ.getVolatile(view, 8L);
        if (before <= 0 || (before & 1) != 0) continue;
        VarHandle.fullFence();
        int length = view.get(ValueLayout.JAVA_INT, 40);
        if (length <= 0 || length > WorldProtocol.SIZE - 64) {
          VarHandle.fullFence();
          if (before != (long) SEQ.getVolatile(view, 8L)) continue;
          retained = null;
          return null;
        }
        byte[] copy = view.asSlice(0, length + 64L).toArray(ValueLayout.JAVA_BYTE);
        VarHandle.fullFence();
        if (before != (long) SEQ.getVolatile(view, 8L)
            || ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).getLong(8) != before) continue;
        var host = WorldProtocol.decode(copy, now());
        if (!ProcessHandle.of(host.pid()).map(ProcessHandle::isAlive).orElse(false)) {
          closeInput();
          return null;
        }
        retained = host.active() ? host : null;
        return host;
      }
      // Only an in-progress/torn publication may reuse the last immutable
      // envelope. Explicit inactive, invalid, dead or expired input revokes.
      if (retained != null
          && dev.eldencraft.bridge.WorldLeasePolicy.retain(
              retained.millis(),
              now(),
              true,
              ProcessHandle.of(retained.pid()).map(ProcessHandle::isAlive).orElse(false)))
        return retained;
      retained = null;
    } catch (java.io.IOException | IllegalArgumentException rejected) {
      retained = null;
      return null;
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      failed = true;
      closeInput();
      warn(error);
    }
    return null;
  }

  void publish(JsonObject json, boolean active) {
    if (failed) return;
    try {
      initialize();
      if (output == null) {
        try (var a = Arena.ofConfined()) {
          guard =
              (MemorySegment)
                  mutex.invokeExact(
                      MemorySegment.NULL,
                      0,
                      a.allocateFrom(
                          ValueLayout.JAVA_BYTE,
                          "Local\\EldenCraftWorldGuest.Writer\0"
                              .getBytes(StandardCharsets.UTF_16LE)));
          int error = (int) lastError.invokeExact();
          if (guard.address() == 0 || error == 183) {
            closeGuard();
            throw new IllegalStateException("Another world writer exists");
          }
        }
        output = SharedMemory.create("Local\\EldenCraftWorldGuest", WorldProtocol.SIZE);
      }
      byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
      if (body.length > WorldProtocol.SIZE - 64)
        throw new IllegalArgumentException("World output size");
      var header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
      header
          .putInt(0, WorldProtocol.GUEST_MAGIC)
          .putInt(4, 1)
          .putLong(8, sequence + 2)
          .putLong(16, ++frame)
          .putLong(24, now())
          .putInt(32, (int) ProcessHandle.current().pid())
          .putInt(36, active ? 1 : 0)
          .putInt(40, body.length);
      var dst = output.segment;
      SEQ.setVolatile(dst, 8L, sequence + 1);
      VarHandle.fullFence();
      MemorySegment.copy(MemorySegment.ofArray(header.array()), 0, dst, 0, 8);
      MemorySegment.copy(MemorySegment.ofArray(header.array()), 16, dst, 16, 48);
      MemorySegment.copy(MemorySegment.ofArray(body), 0, dst, 64, body.length);
      VarHandle.fullFence();
      sequence += 2;
      SEQ.setVolatile(dst, 8L, sequence);
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      failed = true;
      warn(error);
    }
  }

  private static void warn(Throwable error) {
    org.slf4j.LoggerFactory.getLogger("eldencraft_world")
        .warn("World mailbox unavailable: {}", error.getClass().getSimpleName());
  }

  private void closeInput() {
    retained = null;
    if (view.address() != 0)
      try {
        int ignored = (int) unmap.invokeExact(view);
      } catch (Throwable ignored) {
      }
    if (handle.address() != 0)
      try {
        int ignored = (int) close.invokeExact(handle);
      } catch (Throwable ignored) {
      }
    view = handle = MemorySegment.NULL;
  }

  private void closeGuard() {
    if (guard.address() != 0)
      try {
        int ignored = (int) close.invokeExact(guard);
      } catch (Throwable ignored) {
      }
    guard = MemorySegment.NULL;
  }

  public void close() {
    closeInput();
    if (output != null) {
      output.close();
      output = null;
    }
    closeGuard();
  }
}
