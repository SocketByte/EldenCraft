package dev.eldencraft.bridge.client;

import com.google.gson.*;
import dev.eldencraft.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Small save-owned origin ledger; ordinary Minecraft saves retain blocks and entities. */
final class WorldOrigins {
  private final Path file;
  private final Map<Long, WorldOrigin> entries = new LinkedHashMap<>();

  WorldOrigins(MinecraftServer server) throws IOException {
    file = server.getWorldPath(LevelResource.ROOT).resolve("data/eldencraft-origins.json");
    if (Files.exists(file)) {
      byte[] bytes;
      try (var stream = Files.newInputStream(file)) {
        bytes = stream.readNBytes(131073);
      }
      if (bytes.length > 131072) throw new IOException("World origin ledger too large");
      var data = WorldProtocol.parse(bytes);
      var list = data.getAsJsonArray("origins");
      if (list == null || list.size() > 1024) throw new IOException("World origin ledger entries");
      for (var value : list) {
        var o = value.getAsJsonObject();
        long map = WorldProtocol.integer(o, "map", 0, 0xffff_ffffL),
            id = WorldProtocol.integer(o, "anchor", 1, 1024);
        var h = o.getAsJsonArray("host");
        if (h == null || h.size() != 3 || entries.containsKey(map))
          throw new IOException("World origin ledger shape");
        entries.put(
            map,
            new WorldOrigin(
                1,
                map,
                id,
                new WorldOrigin.Vec(
                    h.get(0).getAsDouble(), h.get(1).getAsDouble(), h.get(2).getAsDouble()),
                guest(id)));
      }
    }
  }

  WorldOrigin get(WorldProtocol.Host host) throws IOException {
    var entry = entries.get(host.map());
    if (entry == null) {
      long id = entries.size() + 1;
      if (id > 1024) throw new IOException("World origin capacity");
      entry = new WorldOrigin(host.epoch(), host.map(), id, host.feet(), guest(id));
      entries.put(host.map(), entry);
      save();
    }
    return new WorldOrigin(
        host.epoch(), host.map(), entry.anchorId(), entry.hostOrigin(), entry.guestOrigin());
  }

  private static WorldOrigin.Vec guest(long id) {
    return new WorldOrigin.Vec(((id - 1) % 32) * 4096, 64, ((id - 1) / 32) * 4096);
  }

  private void save() throws IOException {
    var data = new JsonObject();
    var list = new JsonArray();
    for (var e : entries.values()) {
      var o = new JsonObject();
      o.addProperty("map", e.map());
      o.addProperty("anchor", e.anchorId());
      o.add("host", WorldProtocol.vector(e.hostOrigin()));
      list.add(o);
    }
    data.add("origins", list);
    Files.createDirectories(file.getParent());
    var temp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temp, data.toString());
    try {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
