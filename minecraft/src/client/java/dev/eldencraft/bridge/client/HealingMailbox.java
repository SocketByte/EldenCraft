package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.HealingProtocol;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Separate single-writer mappings for healing receipts and host acknowledgements. */
final class HealingMailbox implements AutoCloseable {
  private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
  private MemorySegment handle = MemorySegment.NULL, view = MemorySegment.NULL;
  private MethodHandle open, map, unmap, close, tick;
  private SharedMemory output;
  private long nextOpen, sequence, frame;
  private boolean failed;

  private void initialize() {
    if (tick != null) return;
    var linker = Linker.nativeLinker();
    var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
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
    tick =
        linker.downcallHandle(
            kernel.find("GetTickCount64").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG));
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

  HealingProtocol.Host read() {
    if (failed) return null;
    try {
      initialize();
      if (view.address() == 0) {
        long nanos = System.nanoTime();
        if (nanos < nextOpen) return null;
        nextOpen = nanos + 1_000_000_000L;
        try (var arena = Arena.ofConfined()) {
          var name =
              arena.allocateFrom(
                  ValueLayout.JAVA_BYTE,
                  "Local\\EldenCraftHealingHost\0".getBytes(StandardCharsets.UTF_16LE));
          handle = (MemorySegment) open.invokeExact(4, 0, name);
          if (handle.address() == 0) return null;
          var raw = (MemorySegment) map.invokeExact(handle, 4, 0, 0, 4096L);
          if (raw.address() == 0) {
            closeInput();
            return null;
          }
          view = raw.reinterpret(4096);
        }
      }
      for (int attempt = 0; attempt < 3; attempt++) {
        long before = (long) SEQ.getVolatile(view, 8L);
        if (before <= 0 || (before & 1) != 0) continue;
        VarHandle.fullFence();
        byte[] copy = view.toArray(ValueLayout.JAVA_BYTE);
        VarHandle.fullFence();
        if (before != (long) SEQ.getVolatile(view, 8L)) continue;
        if (java.nio.ByteBuffer.wrap(copy).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(8)
            != before) continue;
        var host = HealingProtocol.decodeHost(copy, now());
        if (!ProcessHandle.of(host.pid()).map(ProcessHandle::isAlive).orElse(false)) {
          closeInput();
          return null;
        }
        return host;
      }
    } catch (IllegalArgumentException rejected) {
      return null;
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      failed = true;
      closeInput();
      warn(error);
    }
    return null;
  }

  void publish(
      HealingProtocol.Host host,
      boolean active,
      long session,
      List<HealingProtocol.Receipt> receipts) {
    if (host == null || failed || session <= 0) return;
    try {
      if (output == null) output = SharedMemory.create("Local\\EldenCraftHealing", 4096);
      byte[] bytes =
          HealingProtocol.encode(
              sequence + 2,
              ++frame,
              now(),
              ProcessHandle.current().pid(),
              active,
              session,
              host,
              receipts);
      var dest = output.segment;
      dest.set(ValueLayout.JAVA_LONG, 8, sequence + 1);
      VarHandle.fullFence();
      var source = MemorySegment.ofArray(bytes);
      MemorySegment.copy(source, 0, dest, 0, 8);
      MemorySegment.copy(source, 16, dest, 16, 4080);
      VarHandle.fullFence();
      sequence += 2;
      dest.set(ValueLayout.JAVA_LONG, 8, sequence);
      VarHandle.fullFence();
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      failed = true;
      warn(error);
    }
  }

  private static void warn(Throwable error) {
    org.slf4j.LoggerFactory.getLogger("eldencraft_healing")
        .warn("Healing mailbox unavailable: {}", error.getClass().getSimpleName());
  }

  private void closeInput() {
    if (unmap != null && view.address() != 0)
      try {
        int ignored = (int) unmap.invokeExact(view);
      } catch (Throwable ignored) {
      }
    if (close != null && handle.address() != 0)
      try {
        int ignored = (int) close.invokeExact(handle);
      } catch (Throwable ignored) {
      }
    view = handle = MemorySegment.NULL;
  }

  public void close() {
    closeInput();
    if (output != null) {
      output.close();
      output = null;
    }
  }
}
