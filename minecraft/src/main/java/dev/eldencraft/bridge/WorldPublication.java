package dev.eldencraft.bridge;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Client-thread encoding of immutable server children and a fresh top-level envelope. */
public final class WorldPublication {
  private JsonElement blocks;
  private byte[] encodedBlocks;
  private int lastSize = 4096;

  public byte[] encode(JsonObject json, int limit) {
    var out = new ByteArrayOutputStream(Math.min(lastSize + 256, limit));
    out.write('{');
    boolean first = true;
    for (var entry : json.entrySet()) {
      if (!first) out.write(',');
      first = false;
      write(
          out,
          new JsonPrimitive(entry.getKey()).toString().getBytes(StandardCharsets.UTF_8),
          limit);
      out.write(':');
      byte[] value;
      if (entry.getKey().equals("blocks")) {
        if (blocks != entry.getValue()) {
          blocks = entry.getValue();
          encodedBlocks = blocks.toString().getBytes(StandardCharsets.UTF_8);
        }
        value = encodedBlocks;
      } else value = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
      write(out, value, limit);
    }
    if (out.size() >= limit) throw new IllegalArgumentException("World output size");
    out.write('}');
    lastSize = out.size();
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, byte[] bytes, int limit) {
    if (bytes.length > limit - out.size()) throw new IllegalArgumentException("World output size");
    out.writeBytes(bytes);
  }
}
