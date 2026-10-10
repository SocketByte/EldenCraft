package dev.eldencraft.bridge;

import java.util.Arrays;
import java.util.List;

/** Assembles immutable section bytes without accessing a world or render device. */
public record MeshPayload(byte[] bytes, int[] counts, boolean changed) {
  public static MeshPayload assemble(List<byte[][]> sections, byte[] previous, int[] oldCounts) {
    int[] counts = new int[3];
    int size = 0;
    for (var section : sections) {
      if (section.length != 3) throw new IllegalArgumentException("Mesh layers");
      for (int i = 0; i < 3; i++) {
        if (section[i].length % BlockMeshProtocol.LIT_STRIDE != 0)
          throw new IllegalArgumentException("Mesh stride");
        counts[i] += section[i].length / BlockMeshProtocol.LIT_STRIDE;
        size = Math.addExact(size, section[i].length);
      }
    }
    if (size > BlockMeshProtocol.MAX_VERTICES * BlockMeshProtocol.LIT_STRIDE)
      throw new IllegalArgumentException("Mesh capacity");
    byte[] bytes = new byte[size];
    int at = 0;
    for (int layer = 0; layer < 3; layer++)
      for (var section : sections) {
        System.arraycopy(section[layer], 0, bytes, at, section[layer].length);
        at += section[layer].length;
      }
    return new MeshPayload(
        bytes, counts, !Arrays.equals(bytes, previous) || !Arrays.equals(counts, oldCounts));
  }
}
