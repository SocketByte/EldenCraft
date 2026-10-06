package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.ChatProtocol;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;

/** Open-existing read view only. Expected PID comes from the independently live ECHS reader. */
final class ChatMailbox implements AutoCloseable {
  private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
  private MemorySegment handle = MemorySegment.NULL, view = MemorySegment.NULL;
  private MethodHandle open, map, unmap, close, tick;
  private long nextOpen;

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
  }

  long now() {
    try {
      initialize();
      return (long) tick.invokeExact();
    } catch (Throwable e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      return -1;
    }
  }

  ChatProtocol.Packet read(long pid) {
    try {
      initialize();
      if (view.address() == 0) {
        long now = System.nanoTime();
        if (now < nextOpen) return null;
        nextOpen = now + 250_000_000L;
        try (var arena = Arena.ofConfined()) {
          handle =
              (MemorySegment)
                  open.invokeExact(
                      4,
                      0,
                      arena.allocateFrom(
                          ValueLayout.JAVA_BYTE,
                          "Local\\EldenCraftChat\0".getBytes(StandardCharsets.UTF_16LE)));
          if (handle.address() == 0) return null;
          var raw = (MemorySegment) map.invokeExact(handle, 4, 0, 0, (long) ChatProtocol.BYTES);
          if (raw.address() == 0) {
            close();
            return null;
          }
          view = raw.reinterpret(ChatProtocol.BYTES);
        }
      }
      for (int i = 0; i < 3; i++) {
        long before = (long) SEQ.getVolatile(view, 8L);
        if (before <= 0 || (before & 1) != 0) continue;
        VarHandle.fullFence();
        byte[] copy = view.toArray(ValueLayout.JAVA_BYTE);
        VarHandle.fullFence();
        if (before != (long) SEQ.getVolatile(view, 8L)) continue;
        var packet = ChatProtocol.decode(copy, now());
        if (packet.sequence() != before) continue;
        if (packet.pid() != pid) {
          close();
          return null;
        }
        return packet;
      }
    } catch (Throwable e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      close();
    }
    return null;
  }

  public void close() {
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
}
