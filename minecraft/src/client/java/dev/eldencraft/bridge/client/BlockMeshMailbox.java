package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.BlockMeshHandoff;
import dev.eldencraft.bridge.BlockMeshProtocol;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Render-thread writer. The consumer sees complete snapshots under one aligned seqlock. */
final class BlockMeshMailbox implements AutoCloseable {
  private final String prefix;
  private final int meshBytes, atlasBytes;
  private final boolean animated;

  /** The static block mesh, its mipmapped atlas and the animated sprite channel. */
  BlockMeshMailbox() {
    this("EldenCraftBlock", BlockMeshProtocol.MESH_BYTES, BlockMeshProtocol.ATLAS_BYTES, true);
  }

  BlockMeshMailbox(String prefix, int meshBytes, int atlasBytes, boolean animated) {
    this.prefix = prefix;
    this.meshBytes = meshBytes;
    this.atlasBytes = atlasBytes;
    this.animated = animated;
  }

  private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
  private SharedMemory mesh, atlas, anim, light;
  private long lightSeq;
  private byte[] lightHeader;
  private long animSeq;
  private byte[] animHeader;
  private MemorySegment guard = MemorySegment.NULL,
      ackHandle = MemorySegment.NULL,
      ack = MemorySegment.NULL;
  private MethodHandle open, map, unmap, close, clock, mutex, error;
  private long meshSeq, atlasSeq, nextOpen;
  private byte[] meshHeader, atlasHeader;
  private final BlockMeshProtocol.AcknowledgementCache ackCache =
      new BlockMeshProtocol.AcknowledgementCache();
  private boolean closed;

  void initialize() throws Throwable {
    if (clock != null) return;
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
    clock =
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
    error =
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
                      ("Local\\" + prefix + "Mesh.Writer\0").getBytes(StandardCharsets.UTF_16LE)));
      int status = (int) error.invokeExact();
      if (guard.address() == 0 || status == 183)
        throw new IllegalStateException("Block mesh writer already exists");
    }
    mesh = SharedMemory.create("Local\\" + prefix + "Mesh", meshBytes);
    atlas = SharedMemory.create("Local\\" + prefix + "Atlas", atlasBytes);
    if (animated) {
      anim = SharedMemory.create("Local\\" + prefix + "Anim", BlockMeshProtocol.ANIM_BYTES);
      light = SharedMemory.create("Local\\" + prefix + "Light", BlockMeshProtocol.LIGHT_BYTES);
    }
  }

  /** The real 16x16 lightmap updates independently of immutable geometry. */
  void lighting(byte[] header, byte[] data) {
    lightHeader = header.clone();
    writeLight(data);
  }

  private void writeLight(byte[] data) {
    if (light == null || lightHeader == null || closed) return;
    var dst = light.segment;
    SEQ.setVolatile(dst, 8L, lightSeq + 1);
    VarHandle.fullFence();
    var src = MemorySegment.ofArray(lightHeader);
    MemorySegment.copy(src, 0, dst, 0, 8);
    MemorySegment.copy(src, 16, dst, 16, 112);
    if (data != null) MemorySegment.copy(MemorySegment.ofArray(data), 0, dst, 128, 1024);
    VarHandle.fullFence();
    SEQ.setVolatile(dst, 8L, lightSeq + 2);
    lightSeq += 2;
  }

  /** Current frames of every animated sprite in the resident atlas. */
  void animation(byte[] header, byte[] data) {
    if (anim == null) return;
    animHeader = header.clone();
    long seq = animSeq;
    var dst = anim.segment;
    SEQ.setVolatile(dst, 8L, seq + 1);
    VarHandle.fullFence();
    var src = MemorySegment.ofArray(header);
    MemorySegment.copy(src, 0, dst, 0, 8);
    MemorySegment.copy(src, 16, dst, 16, 112);
    MemorySegment.copy(MemorySegment.ofArray(data), 0, dst, 128, data.length);
    VarHandle.fullFence();
    SEQ.setVolatile(dst, 8L, seq + 2);
    animSeq = seq + 2;
  }

  long now() throws Throwable {
    return (long) clock.invokeExact();
  }

  void mesh(byte[] header, byte[] data) {
    meshHeader = header.clone();
    write(mesh, header, MemorySegment.ofArray(data), false);
  }

  void atlas(byte[] header, ByteBuffer data) {
    atlasHeader = header.clone();
    write(atlas, header, MemorySegment.ofBuffer(data), true);
  }

  void heartbeat(boolean active) throws Throwable {
    long now = now();
    if (meshHeader != null) {
      var b = ByteBuffer.wrap(meshHeader).order(ByteOrder.LITTLE_ENDIAN);
      b.putLong(48, now).putInt(36, active ? 1 : 0);
      write(mesh, meshHeader, null, false);
    }
    if (atlasHeader != null) {
      var b = ByteBuffer.wrap(atlasHeader).order(ByteOrder.LITTLE_ENDIAN);
      b.putLong(48, now).putInt(36, active ? 1 : 0);
      write(atlas, atlasHeader, null, true);
    }
    if (lightHeader != null) {
      ByteBuffer.wrap(lightHeader)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putLong(48, now)
          .putInt(36, active ? 1 : 0);
      writeLight(null);
    }
    if (animHeader != null && anim != null && !closed) {
      var b = ByteBuffer.wrap(animHeader).order(ByteOrder.LITTLE_ENDIAN);
      b.putLong(48, now).putInt(36, active ? 1 : 0);
      long seq = animSeq;
      SEQ.setVolatile(anim.segment, 8L, seq + 1);
      VarHandle.fullFence();
      MemorySegment.copy(MemorySegment.ofArray(animHeader), 16, anim.segment, 16, 112);
      VarHandle.fullFence();
      SEQ.setVolatile(anim.segment, 8L, seq + 2);
      animSeq = seq + 2;
    }
  }

  private void write(SharedMemory target, byte[] header, MemorySegment data, boolean isAtlas) {
    if (closed || target == null) return;
    long seq = isAtlas ? atlasSeq : meshSeq;
    var dst = target.segment;
    SEQ.setVolatile(dst, 8L, seq + 1);
    VarHandle.fullFence();
    var src = MemorySegment.ofArray(header);
    MemorySegment.copy(src, 0, dst, 0, 8);
    MemorySegment.copy(src, 16, dst, 16, 112);
    if (data != null)
      MemorySegment.copy(
          data, 0, dst, 128, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(80));
    VarHandle.fullFence();
    SEQ.setVolatile(dst, 8L, seq + 2);
    if (isAtlas) atlasSeq = seq + 2;
    else meshSeq = seq + 2;
  }

  BlockMeshHandoff.Revision acknowledgement(BlockMeshProtocol.Identity id) throws Throwable {
    if (closed) return null;
    if (ack.address() == 0) {
      long now = now();
      if (now < nextOpen) return null;
      nextOpen = now + 1000;
      try (var a = Arena.ofConfined()) {
        ackHandle =
            (MemorySegment)
                open.invokeExact(
                    4,
                    0,
                    a.allocateFrom(
                        ValueLayout.JAVA_BYTE,
                        "Local\\EldenCraftBlockMeshAck\0".getBytes(StandardCharsets.UTF_16LE)));
        if (ackHandle.address() == 0) return null;
        var raw = (MemorySegment) map.invokeExact(ackHandle, 4, 0, 0, 128L);
        if (raw.address() == 0) {
          closeAck();
          return null;
        }
        ack = raw.reinterpret(128);
      }
    }
    for (int i = 0; i < 3; i++) {
      long before = (long) SEQ.getVolatile(ack, 8L);
      if (before <= 0 || (before & 1) != 0) continue;
      VarHandle.fullFence();
      byte[] copy = ack.toArray(ValueLayout.JAVA_BYTE);
      VarHandle.fullFence();
      if (before != (long) SEQ.getVolatile(ack, 8L)) continue;
      return ackCache.accept(copy, id, now());
    }
    // An odd/changing seqlock is not a revocation. Reuse only previously coherent
    // bytes, revalidated against this identity and their unchanged250ms timestamp.
    return ackCache.contended(id, now());
  }

  void clearAcknowledgement() {
    ackCache.clear();
  }

  private void closeAck() {
    ackCache.clear();
    try {
      if (ack.address() != 0) {
        int ignored = (int) unmap.invokeExact(ack);
      }
    } catch (Throwable ignored) {
    }
    try {
      if (ackHandle.address() != 0) {
        int ignored = (int) close.invokeExact(ackHandle);
      }
    } catch (Throwable ignored) {
    }
    ack = ackHandle = MemorySegment.NULL;
  }

  public void close() {
    if (closed) return;
    try {
      heartbeat(false);
    } catch (Throwable ignored) {
    }
    closed = true;
    closeAck();
    if (mesh != null) mesh.close();
    if (atlas != null) atlas.close();
    if (anim != null) anim.close();
    if (light != null) light.close();
    try {
      if (guard.address() != 0) {
        int ignored = (int) close.invokeExact(guard);
      }
    } catch (Throwable ignored) {
    }
  }
}
