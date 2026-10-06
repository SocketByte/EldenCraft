package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.NetherProtocol;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;

/** Client-thread ECNH writer. The compositor copies one 256-byte page under the seqlock at +8. */
final class NetherMailbox implements AutoCloseable {
  private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
  private SharedMemory page;
  private MemorySegment guard = MemorySegment.NULL;
  private MethodHandle clock, close;
  private long sequence;
  private boolean closed, failed;

  boolean ready() {
    return page != null && !closed;
  }

  /** Creates the page once; a second Minecraft instance cannot own the name and stays silent. */
  boolean initialize() {
    if (page != null || closed || failed) return page != null;
    try {
      var linker = Linker.nativeLinker();
      var lib = SymbolLookup.libraryLookup("kernel32", Arena.global());
      clock =
          linker.downcallHandle(
              lib.find("GetTickCount64").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_LONG));
      close =
          linker.downcallHandle(
              lib.find("CloseHandle").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      var mutex =
          linker.downcallHandle(
              lib.find("CreateMutexW").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS));
      var error =
          linker.downcallHandle(
              lib.find("GetLastError").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
      try (var a = Arena.ofConfined()) {
        guard =
            (MemorySegment)
                mutex.invokeExact(
                    MemorySegment.NULL,
                    0,
                    a.allocateFrom(
                        ValueLayout.JAVA_BYTE,
                        "Local\\EldenCraftNether.Writer\0".getBytes(StandardCharsets.UTF_16LE)));
        int status = (int) error.invokeExact();
        if (guard.address() == 0 || status == 183)
          throw new IllegalStateException("Nether writer already exists");
      }
      page = SharedMemory.create("Local\\EldenCraftNether", NetherProtocol.BYTES);
      return true;
    } catch (Throwable failure) {
      failed = true;
      return false;
    }
  }

  long now() {
    try {
      return (long) clock.invokeExact();
    } catch (Throwable t) {
      return 0;
    }
  }

  void publish(byte[] body) {
    if (!ready()) return;
    var dst = page.segment;
    SEQ.setVolatile(dst, 8L, sequence + 1);
    VarHandle.fullFence();
    var src = MemorySegment.ofArray(body);
    MemorySegment.copy(src, 0, dst, 0, 8);
    MemorySegment.copy(src, 16, dst, 16, NetherProtocol.BYTES - 16);
    VarHandle.fullFence();
    SEQ.setVolatile(dst, 8L, sequence + 2);
    sequence += 2;
  }

  /**
   * An inactive page with a zero amount: the compositor fades out instead of holding the last
   * frame.
   */
  void clear() {
    if (!ready()) return;
    var dst = page.segment;
    SEQ.setVolatile(dst, 8L, sequence + 1);
    VarHandle.fullFence();
    dst.set(ValueLayout.JAVA_INT_UNALIGNED, 36, 0);
    dst.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 104, 0f);
    VarHandle.fullFence();
    SEQ.setVolatile(dst, 8L, sequence + 2);
    sequence += 2;
  }

  @Override
  public void close() {
    if (closed) return;
    clear();
    closed = true;
    if (page != null) page.close();
    try {
      if (guard.address() != 0) {
        int ignored = (int) close.invokeExact(guard);
      }
    } catch (Throwable ignored) {
    }
  }
}
