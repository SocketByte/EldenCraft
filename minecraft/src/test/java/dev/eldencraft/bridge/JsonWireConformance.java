package dev.eldencraft.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Strict parsing checks retained after retiring the gameplay TCP relay. */
public final class JsonWireConformance {
  private static int checks;

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  private static void rejects(Checked operation) throws Exception {
    try {
      operation.run();
    } catch (IOException | RuntimeException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Malformed input accepted");
  }

  private static void rejects(String text) throws Exception {
    rejects(() -> JsonWire.parse(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static void check(boolean condition) {
    checks++;
    if (!condition) throw new AssertionError("JSON conformance check " + checks);
  }

  public static void main(String[] args) throws Exception {
    for (String text :
        new String[] {
          "{\"nested\":{\"x\":1,\"x\":2}}",
          "{\"x\":NaN}",
          "{\"x\":1e999}",
          "{\"x\":1} trailing",
          "[]",
          "{\"x\":1,}",
          "{\"x\":undefined}"
        }) rejects(text);
    rejects(() -> JsonWire.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
    rejects("{\"x\":\"" + "a".repeat(131_072) + "\"}");
    rejects("{\"x\":" + "[".repeat(14) + "0" + "]".repeat(14) + "}");
    rejects("{\"x\":[" + "0,".repeat(12_001) + "0]}");
    for (String number : new String[] {"1.0", "1e0", "true", "9007199254740992", "-1"}) {
      rejects(
          () ->
              JsonWire.integer(
                  JsonWire.parse(("{\"x\":" + number + "}").getBytes(StandardCharsets.UTF_8))
                      .get("x"),
                  0,
                  (1L << 53) - 1));
    }
    var valid =
        JsonWire.parse("{\"schema\":1,\"text\":\"Minecraft 世界\"}".getBytes(StandardCharsets.UTF_8));
    check(JsonWire.integer(valid.get("schema"), 1, 1) == 1);
    check(JsonWire.string(valid.get("text")).equals("Minecraft 世界"));
    rejects(() -> JsonWire.string(valid.get("schema")));
    rejects(() -> JsonWire.integer(null, 0, 1));
    check(JsonWire.vector(1, -2, 3).toString().equals("[1,-2,3]"));
    check(JsonWire.vector(0.5, -1.25, 2.0).get(1).getAsDouble() == -1.25);
    System.out.println("JSON conformance: " + checks + " checks passed.");
  }
}
