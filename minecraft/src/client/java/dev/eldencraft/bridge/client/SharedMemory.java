package dev.eldencraft.bridge.client;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Pagefile-backed local Windows mapping; handles are closed on client shutdown. */
final class SharedMemory implements AutoCloseable {
  final MemorySegment segment;
  private final MemorySegment handle, view;
  private final MethodHandle unmap, closeHandle;
  private boolean closed;

  private SharedMemory(
      MemorySegment handle,
      MemorySegment view,
      long size,
      MethodHandle unmap,
      MethodHandle closeHandle) {
    this.handle = handle;
    this.view = view;
    this.segment = view.reinterpret(size);
    this.unmap = unmap;
    this.closeHandle = closeHandle;
  }

  static SharedMemory create(String name, long size) throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    MethodHandle create =
        linker.downcallHandle(
            kernel.find("CreateFileMappingW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));
    MethodHandle map =
        linker.downcallHandle(
            kernel.find("MapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG));
    MethodHandle unmap =
        linker.downcallHandle(
            kernel.find("UnmapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    MethodHandle close =
        linker.downcallHandle(
            kernel.find("CloseHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    try (Arena temporary = Arena.ofConfined()) {
      MemorySegment wideName =
          temporary.allocateFrom(
              ValueLayout.JAVA_BYTE, (name + "\0").getBytes(StandardCharsets.UTF_16LE));
      MemorySegment handle =
          (MemorySegment)
              create.invoke(
                  MemorySegment.ofAddress(-1L),
                  MemorySegment.NULL,
                  4,
                  (int) (size >>> 32),
                  (int) size,
                  wideName);
      if (handle.address() == 0) throw new IllegalStateException("Mapping creation failed");
      MemorySegment view = (MemorySegment) map.invoke(handle, 0xF001F, 0, 0, size);
      if (view.address() == 0) {
        int ignored = (int) close.invoke(handle);
        throw new IllegalStateException("Mapping view failed");
      }
      return new SharedMemory(handle, view, size, unmap, close);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      int ignored = (int) unmap.invoke(view);
    } catch (Throwable ignored) {
    }
    try {
      int ignored = (int) closeHandle.invoke(handle);
    } catch (Throwable ignored) {
    }
  }
}
