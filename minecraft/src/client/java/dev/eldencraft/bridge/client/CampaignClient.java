package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import dev.eldencraft.bridge.CampaignConfig;
import dev.eldencraft.bridge.JsonWire;
import java.io.IOException;
import java.nio.file.*;
import net.fabricmc.loader.api.FabricLoader;

/** Both games load the same campaign file; changes take effect after restarting both. */
public final class CampaignClient {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_campaign");
  private static JsonObject link;
  private static boolean linkLoaded;
  private static Path configuration;

  private CampaignClient() {}

  static synchronized JsonObject link() {
    if (linkLoaded) return link;
    linkLoaded = true;
    Path path = FabricLoader.getInstance().getConfigDir().resolve("eldencraft-campaign-link.json");
    if (!Files.exists(path)) return null;
    try {
      if (Files.size(path) > 4096) throw new IOException("Campaign link exceeds limit");
      link = JsonWire.parse(Files.readAllBytes(path));
      for (String key : link.keySet()) {
        if (!key.equals("config") && !key.equals("directory"))
          throw new IOException("Unknown campaign link key");
        String value = JsonWire.string(link.get(key));
        if (value.isBlank() || !Path.of(value).isAbsolute())
          throw new IOException("Campaign link paths must be absolute");
      }
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalStateException("Cannot load " + path + ": " + error.getMessage(), error);
    }
    return link;
  }

  public static void initialize() {
    try {
      String explicit = System.getenv("ELDENCRAFT_CAMPAIGN_CONFIG");
      var link = link();
      boolean linked = explicit != null && !explicit.isBlank();
      if (!linked && link != null && link.has("config")) {
        explicit = link.get("config").getAsString();
        linked = true;
      }
      configuration =
          linked
              ? Path.of(explicit)
              : FabricLoader.getInstance().getConfigDir().resolve("eldencraft-campaign.json");
      if (!Files.exists(configuration) && !linked) {
        Files.createDirectories(configuration.getParent());
        try (var source =
            CampaignClient.class.getResourceAsStream("/eldencraft-campaign-default.json")) {
          if (source == null) throw new IOException("Bundled campaign defaults missing");
          Files.copy(source, configuration);
        }
      }
      var rules = CampaignConfig.load(configuration);
      CampaignCombat.validateRegistry(rules);
      CampaignConfig.install(rules);
      CampaignBridge.initialize();
      CampaignShops.initialize();
      CampaignTutorial.initialize();
      LOG.info(
          "Campaign rules loaded from {} ({} bosses)",
          configuration,
          CampaignConfig.current().bosses.size());
    } catch (IOException | IllegalArgumentException error) {
      // A malformed rules file must never silently start with free items or a
      // different damage economy than the native host.
      throw new IllegalStateException(
          "Campaign configuration rejected at " + configuration + ": " + error.getMessage(), error);
    }
  }

  public static Path configuration() {
    return configuration;
  }
}
