package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.SharedWorldBlocks;
import dev.eldencraft.bridge.client.mixin.WorldTerrainLightingInvoker;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Repair old saved shadow-terrain light with vanilla work, one loaded chunk at a time. Server
 * thread only.
 */
final class WorldTerrainLighting {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger("eldencraft_lighting");
  private static final int MAX_PENDING = 128;
  private final Set<LevelChunk> seen = Collections.newSetFromMap(new WeakHashMap<>());
  private final ArrayDeque<LevelChunk> pending = new ArrayDeque<>();
  private ServerLevel owner;
  private CompletableFuture<ChunkAccess> inFlight;
  private LevelChunk inFlightChunk;
  private int repaired;
  private long lastReport, retryAfter;

  void observe(ServerLevel level, Collection<BlockPos> shadowCells) {
    if (!level.dimension().equals(SharedWorldBlocks.DIMENSION)) return;
    if (owner != level) {
      clear();
      owner = level;
    }
    var columns = new LinkedHashSet<Long>();
    for (var pos : shadowCells) columns.add(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
    for (long key : columns) {
      if (pending.size() >= MAX_PENDING) break;
      var pos = ChunkPos.unpack(key);
      var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
      if (chunk != null && seen.add(chunk)) pending.addLast(chunk);
    }
  }

  void tick(ServerLevel level) {
    if (owner != level) return;
    if (inFlight != null) {
      if (!inFlight.isDone()) return;
      // getNow cannot wait here: completion was observed on this same thread.
      try {
        inFlight.getNow(null);
      } catch (RuntimeException failure) {
        retry(inFlightChunk, failure);
      }
      inFlight = null;
      inFlightChunk = null;
    }
    if (System.nanoTime() < retryAfter) return;
    var chunk = pending.pollFirst();
    if (chunk == null) return;
    var pos = chunk.getPos();
    // Never retain/reload an unloaded chunk solely for a cosmetic repair.
    if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) != chunk) {
      seen.remove(chunk);
      return;
    }
    // ChunkAccess rebuilds the sky-source height map from actual BlockState
    // properties. lightChunk(false) queues vanilla propagation asynchronously;
    // no .join(), synthetic light values, clock changes, or block mutations.
    try {
      // lightChunk ends with setLightCorrect(true) on the light thread. For a loaded
      // chunk that an autosave marked saved meanwhile, markUnsaved then adds to
      // ChunkMap's unsynchronized save set concurrently with the server thread,
      // corrupting it (a crash in setChunkUnsaved). Report it on the server thread.
      var server = level.getServer();
      var map = (WorldTerrainLightingInvoker) level.getChunkSource().chunkMap;
      chunk.setUnsavedListener(
          unsaved -> {
            if (server.isSameThread()) map.eldencraft$setChunkUnsaved(unsaved);
            else server.execute(() -> map.eldencraft$setChunkUnsaved(unsaved));
          });
      chunk.initializeLightSources();
      inFlight = level.getChunkSource().getLightEngine().lightChunk(chunk, false);
      inFlightChunk = chunk;
    } catch (RuntimeException failure) {
      retry(chunk, failure);
      return;
    }
    repaired++;
    long now = System.nanoTime();
    if (now - lastReport >= 5_000_000_000L) {
      lastReport = now;
      LOG.info(
          "Shared terrain lighting refresh: submitted={}, pending={}, one vanilla light job per"
              + " world at a time",
          repaired,
          pending.size());
    }
  }

  private void retry(LevelChunk chunk, RuntimeException failure) {
    if (chunk != null && pending.size() < MAX_PENDING) pending.addLast(chunk);
    else seen.remove(chunk);
    retryAfter = System.nanoTime() + 5_000_000_000L;
    LOG.warn("Shared terrain lighting refresh failed; bounded retry in five seconds", failure);
  }

  // Already queued vanilla work belongs to its old world and may finish there.
  // No callback can mutate this queue after a world/server replacement.
  void clear() {
    pending.clear();
    seen.clear();
    owner = null;
    inFlight = null;
    inFlightChunk = null;
    repaired = 0;
    lastReport = retryAfter = 0;
  }
}
