package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.bridge.TerrainMaterials;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional overrides in config/eldencraft-materials.json:
 *
 * <pre>{"hit": {"2": "minecraft:deepslate"}, "body": {"57": "minecraft:spruce_log"}}</pre>
 *
 * "hit" keys are Elden Ring hit materials (HitMtrlParam rows), "body" keys Havok body materials;
 * values are block ids. Use /eldencraft material to read both for the surface you look at.
 */
public final class MaterialConfig {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_world");
  static final String FILE = "eldencraft-materials.json";

  private MaterialConfig() {}

  public static void load() {
    var path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
    try {
      if (!Files.exists(path)) {
        Files.writeString(path, "{\n  \"hit\": {},\n  \"body\": {}\n}\n", StandardCharsets.UTF_8);
        return;
      }
      var json =
          JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
      TerrainMaterials.overrides(section(json, "hit"), section(json, "body"));
      LOG.info("Loaded terrain material overrides from {}", path);
    } catch (Exception failure) {
      LOG.warn("Ignoring {}: {}", path, failure.getMessage());
    }
  }

  static Map<Integer, String> section(JsonObject json, String key) {
    var out = new HashMap<Integer, String>();
    if (!json.has(key)) return out;
    for (var entry : json.getAsJsonObject(key).entrySet()) {
      int id = Integer.parseInt(entry.getKey().trim());
      String block = entry.getValue().getAsString();
      if (id >= 0 && block.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) out.put(id, block);
    }
    return out;
  }
}
