package dev.eldencraft.bridge;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded strict JSON shared by host configuration and world messages. */
public final class JsonWire {
  private static final int MAX_INPUT_BYTES = 131_072;

  private JsonWire() {}

  public static JsonArray vector(int... values) {
    JsonArray out = new JsonArray();
    for (int value : values) out.add(value);
    return out;
  }

  public static JsonArray vector(double... values) {
    JsonArray out = new JsonArray();
    for (double value : values) out.add(value);
    return out;
  }

  public static JsonObject parse(byte[] bytes) throws IOException {
    if (bytes.length > MAX_INPUT_BYTES) throw new IOException("JSON input exceeds limit");
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    JsonReader reader = new JsonReader(new StringReader(text));
    reader.setStrictness(Strictness.STRICT);
    JsonElement value = element(reader, 0, new int[] {0});
    if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
      throw new IOException("Invalid JSON object");
    return value.getAsJsonObject();
  }

  private static JsonElement element(JsonReader reader, int depth, int[] count) throws IOException {
    if (depth > 12 || ++count[0] > 12000) throw new IOException("JSON complexity limit");
    switch (reader.peek()) {
      case BEGIN_OBJECT -> {
        JsonObject value = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (value.has(key)) throw new IOException("Duplicate JSON key");
          value.add(key, element(reader, depth + 1, count));
        }
        reader.endObject();
        return value;
      }
      case BEGIN_ARRAY -> {
        JsonArray value = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) value.add(element(reader, depth + 1, count));
        reader.endArray();
        return value;
      }
      case STRING -> {
        return new JsonPrimitive(reader.nextString());
      }
      case BOOLEAN -> {
        return new JsonPrimitive(reader.nextBoolean());
      }
      case NULL -> {
        reader.nextNull();
        return JsonNull.INSTANCE;
      }
      case NUMBER -> {
        String number = reader.nextString();
        double finite = Double.parseDouble(number);
        if (!Double.isFinite(finite)) throw new IOException("Nonfinite JSON number");
        return number.matches("-?(0|[1-9][0-9]*)")
            ? new JsonPrimitive(new java.math.BigInteger(number))
            : new JsonPrimitive(new java.math.BigDecimal(number));
      }
      default -> throw new IOException("Invalid JSON");
    }
  }

  public static long integer(JsonElement value, long min, long max) throws IOException {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || value.getAsNumber() instanceof java.math.BigDecimal)
      throw new IOException("Expected integer");
    try {
      long number = value.getAsBigDecimal().longValueExact();
      if (number < min || number > max) throw new IOException("Integer out of range");
      return number;
    } catch (ArithmeticException e) {
      throw new IOException("Expected bounded integer");
    }
  }

  public static String string(JsonElement value) throws IOException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IOException("Expected string");
    return value.getAsString();
  }
}
