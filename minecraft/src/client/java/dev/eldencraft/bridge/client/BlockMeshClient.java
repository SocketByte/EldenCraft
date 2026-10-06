package dev.eldencraft.bridge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.eldencraft.bridge.*;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Vanilla baked model capture, bounded across render frames. Never waits for the GPU. */
public final class BlockMeshClient {
  public record Exclusion(long mesh, long atlas, long session) {
    public static final Exclusion NONE = new Exclusion(0, 0, 0);

    public boolean active() {
      return mesh > 0 && atlas > 0 && session > 0;
    }
  }

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("eldencraft_mesh");
  private static final BlockMeshMailbox MAILBOX = new BlockMeshMailbox();
  private static final BlockMeshHandoff HANDOFF = new BlockMeshHandoff();
  private static final BlockMeshChanges CHANGES = new BlockMeshChanges();
  private static final long PID = ProcessHandle.current().pid();
  private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};

  /**
   * Animated blocks rendered live through the vanilla moving-block path instead of the static mesh.
   */
  static final int MAX_LIVE = 768;

  private static final Map<Long, MovingBlockRenderState> liveStates = new HashMap<>();
  private static long fullGeneration = 1,
      resourceGeneration = 1,
      lightContext = 1,
      revision,
      atlasRevision,
      sessionCounter = System.currentTimeMillis();
  private static final Set<Long> dirtySections = new HashSet<>();
  private static final Object LIGHT_LOCK = new Object();
  private static final Set<Long> pendingLightSections = new HashSet<>();
  private static long nextLight, nextGeometryLight, reportTime, retryBuild;
  // Visible-section coverage is checked several times per frame; one scan suffices.
  private static long frameStamp;
  private static boolean initialized, failed, closed, atlasPending, lightPending, reportedExclusion;
  private static SharedWorldClient.BlockMeshContext context;
  private static BlockMeshProtocol.Identity identity;
  private static ClientLevel level;
  private static Object models;
  private static GpuTexture atlasTexture;
  // The block atlas texture location that baked block sprites report.
  private static volatile Identifier atlasLocation;
  private static GpuTexture reportedLightTexture;
  private static byte[] atlasPixels, lightPixels, publishedPayload;
  private static int atlasWidth, atlasHeight;
  private static boolean atlasPublished, relight, allLightSections;
  // Installed is the latest published candidate; displayed is the exact acknowledged snapshot.
  private static Build build, installed, displayed;
  private static int[] publishedCounts;
  private static Exclusion exclusion = Exclusion.NONE;
  private static String fallback = "waiting for shared world";

  private BlockMeshClient() {}

  public static void resetFrame() {
    exclusion = Exclusion.NONE;
  }

  public static Exclusion exclusion() {
    return exclusion;
  }

  static BlockMeshProtocol.Identity presentationIdentity() {
    return exclusion.active() ? identity : null;
  }

  public static boolean suppressChunks() {
    return exclusion.active();
  }

  /** Resource/options invalidation may change both faces and their shading. */
  public static void dirty() {
    CHANGES.geometryChanged();
    fullGeneration++;
    dirtySections.clear();
    retryBuild = 0;
  }

  public static void dirty(BlockPos pos, BlockState before, BlockState after) {
    if (before == after || (!real(before) && !real(after))) return;
    CHANGES.geometryChanged();
    retryBuild = 0;
    // Neighbor culling and ambient occlusion sample across section edges.
    for (int x = (pos.getX() >> 4) - 1; x <= (pos.getX() >> 4) + 1; x++)
      for (int y = (pos.getY() >> 4) - 1; y <= (pos.getY() >> 4) + 1; y++)
        for (int z = (pos.getZ() >> 4) - 1; z <= (pos.getZ() >> 4) + 1; z++)
          dirtySections.add(SectionPos.asLong(x, y, z));
    if (dirtySections.size() > 4096) dirty();
  }

  /** Streaming one chunk must not discard every cached section in the render area. */
  public static void chunkChanged(ChunkPos pos) {
    var owner = build != null ? build : installed;
    if (owner == null) return;
    var area = owner.window;
    if (!area.intersectsChunkNeighborhood(pos.x(), pos.z())) return;
    CHANGES.geometryChanged();
    retryBuild = 0;
    // Neighbor sections can expose new faces when the adjacent chunk arrives/leaves.
    for (int x = pos.x() - 1; x <= pos.x() + 1; x++)
      for (int z = pos.z() - 1; z <= pos.z() + 1; z++)
        for (int y = area.minY(); y <= area.maxY(); y++)
          if (area.contains(x, y, z)) dirtySections.add(SectionPos.asLong(x, y, z));
    if (dirtySections.size() > 4096) dirty();
  }

  /** Propagated block/sky light changes bypass ClientLevel.setBlocksDirty in 26.3. */
  public static void lightDirty(SectionPos section) {
    // This notification is allowed from a lighting worker. It never reads world/render state.
    synchronized (LIGHT_LOCK) {
      if (allLightSections) return;
      for (int x = section.x() - 1; x <= section.x() + 1; x++)
        for (int y = section.y() - 1; y <= section.y() + 1; y++)
          for (int z = section.z() - 1; z <= section.z() + 1; z++)
            pendingLightSections.add(SectionPos.asLong(x, y, z));
      if (pendingLightSections.size() > 4096) {
        pendingLightSections.clear();
        allLightSections = true;
      }
    }
  }

  public static void tintDirty() {
    synchronized (LIGHT_LOCK) {
      pendingLightSections.clear();
      allLightSections = true;
    }
  }

  private static void clearLightPending() {
    synchronized (LIGHT_LOCK) {
      pendingLightSections.clear();
      allLightSections = false;
    }
  }

  private static void refreshLightSections(long now) {
    // Let initial coverage finish; then refresh affected cached vertex-light UVs at most 2 Hz.
    if (installed == null || build != null || now < nextGeometryLight) return;
    nextGeometryLight = now + 500_000_000L;
    boolean changed = false;
    synchronized (LIGHT_LOCK) {
      for (long key : installed.geometry.keySet())
        if (allLightSections || pendingLightSections.contains(key)) {
          dirtySections.add(key);
          changed = true;
        }
      pendingLightSections.clear();
      allLightSections = false;
    }
    if (changed) {
      CHANGES.lightingChanged();
      retryBuild = 0;
    }
  }

  /** Called after vanilla repositions ViewArea, before any transient or chunk submission. */
  public static void beginFrame() {
    exclusion = Exclusion.NONE;
    frameStamp++;
    if (failed || closed) return;
    String phase = "shared world readiness";
    try {
      var client = Minecraft.getInstance();
      var next = SharedWorldClient.blockMeshContext();
      if (!SceneCapture.active() || next == null || client.level == null) {
        if (initialized) reportFallback("shared world inactive or identity unavailable");
        pause();
        return;
      }
      var target = client.gameRenderer.mainRenderTarget();
      if (!FrameExporter.exporting()
          || target.width < 1
          || target.height < 1
          || target.width > 1920
          || target.height > 1080) {
        reportFallback(
            "scene capture unavailable or oversized: " + target.width + "x" + target.height);
        pause();
        return;
      }
      phase = "mapping initialization";
      if (!initialized) {
        MAILBOX.initialize();
        initialized = true;
      }
      phase = "mesh identity";
      if (!next.equals(context) || level != client.level) {
        context = next;
        level = client.level;
        identity =
            new BlockMeshProtocol.Identity(
                PID,
                next.hostPid(),
                next.origin().epoch(),
                next.origin().map(),
                ++sessionCounter,
                next.origin().anchorId());
        installed = build = null;
        CHANGES.geometryChanged();
        atlasPublished = false;
        retryBuild = 0;
        resetLightContext();
        invalidateHandoff();
      }
      phase = "block atlas lookup";
      // AtlasManager indexes definition IDs; texture paths belong to TextureManager instead.
      var atlas = client.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
      var texture = atlas.getTexture();
      atlasLocation = atlas.location();
      var nextModels = client.getModelManager().getBlockStateModelSet();
      if (texture != atlasTexture || models != nextModels) {
        atlasTexture = texture;
        models = nextModels;
        resourceGeneration++;
        atlasPixels = null;
        resetLightContext();
        atlasPublished = false;
        installed = build = null;
        CHANGES.geometryChanged();
        retryBuild = 0;
        invalidateHandoff();
        LOG.info(
            "Block mesh atlas: definition={}, texture={}, size={}x{}, format={}, layers={},"
                + " mips={}",
            AtlasIds.BLOCKS,
            atlasLocation,
            texture.getWidth(0),
            texture.getHeight(0),
            texture.getFormat(),
            texture.getDepthOrLayers(),
            texture.getMipLevels());
      }
      long now = System.nanoTime();
      phase = "texture readback";
      requestTextures(client, now);
      refreshLightSections(now);
      phase = "render-area coverage";
      var area = client.levelRenderer.viewArea();
      if (area == null) {
        reportFallback("vanilla ViewArea unavailable");
        pause();
        return;
      }
      var center = area.getCameraSectionPos();
      var visibleWindow =
          new BlockMeshCoverage(
              center.x(),
              center.z(),
              area.getViewDistance(),
              area.minSectionY(),
              area.maxSectionY());
      var window = visibleWindow.withStreamingMargin();
      boolean current = installed != null && installed.valid(window);
      // An ordinary edit does not switch the entire world back to RGB-D. Retain only
      // a complete snapshot for this same render window, never missing chunk coverage.
      if (installed != null && !installed.coversWindow(client, visibleWindow)) invalidateHandoff();
      phase = "atlas publication";
      if (atlasPixels != null && !atlasPublished) {
        MAILBOX.atlas(
            BlockMeshProtocol.header(
                BlockMeshProtocol.ATLAS_MAGIC,
                identity,
                MAILBOX.now(),
                atlasRevision,
                atlasRevision,
                atlasWidth,
                atlasHeight,
                atlasPixels.length,
                0,
                0,
                0,
                true),
            ByteBuffer.wrap(atlasPixels));
        atlasPublished = true;
      }
      phase = "mesh acknowledgement";
      var resident = HANDOFF.select(MAILBOX.acknowledgement(identity));
      if (resident == null) displayed = null;
      else if (resident.mesh() == revision) displayed = installed;
      var oldest = displayed != null ? displayed : installed;
      // Light propagation/tint refreshes never make known faces incomplete. Only
      // actual block/coverage changes consume the old-geometry deadline.
      if (HANDOFF.hasLatest()
          && oldest != null
          && !HANDOFF.geometryWithinGrace(oldest.geometryCurrent(visibleWindow), now)) {
        invalidateHandoff();
        resident = null;
      }
      boolean retained =
          displayed != null
              && displayed.coversWindow(client, visibleWindow)
              && HANDOFF.geometryWithinGrace(displayed.geometryCurrent(visibleWindow), now);
      if (displayed != null && !retained) {
        invalidateHandoff();
        resident = null;
      }
      if (!current && (build == null || !build.valid(window))) build = null;
      phase = "block geometry";
      if ((!current || !HANDOFF.hasLatest())
          && build == null
          && atlasPublished
          && lightPixels != null
          && now >= retryBuild) build = new Build(client, window, installed);
      if (build != null) {
        try {
          if (build.step(client) && (!HANDOFF.hasLatest() || HANDOFF.canPublishColor())) {
            byte[] payload = build.payload(lightPixels);
            int[] counts = build.counts();
            boolean changed =
                !HANDOFF.hasLatest()
                    || BlockMeshHandoff.payloadChanged(publishedPayload, payload)
                    || !Arrays.equals(publishedCounts, counts);
            if (changed) {
              boolean replacing = HANDOFF.hasLatest();
              MAILBOX.mesh(
                  BlockMeshProtocol.header(
                      BlockMeshProtocol.MESH_MAGIC,
                      identity,
                      MAILBOX.now(),
                      ++revision,
                      atlasRevision,
                      payload.length / 24,
                      24,
                      payload.length,
                      counts[0],
                      counts[1],
                      counts[2],
                      true),
                  payload);
              var update =
                  new BlockMeshHandoff.Revision(revision, atlasRevision, identity.session());
              if (replacing) HANDOFF.update(update);
              else HANDOFF.geometry(update);
              publishedPayload = payload;
              publishedCounts = counts;
              LOG.info(
                  "Block mesh published: revision={}, atlas={}, blocks={}, vertices={},"
                      + " coveredChunks={}, retained={}",
                  revision,
                  atlasRevision,
                  build.blocks,
                  payload.length / 24,
                  build.chunks.size(),
                  resident != null);
            } else {
              // Propagated-light dirtiness can leave every actual shaded vertex unchanged.
              displayed = build;
              HANDOFF.geometryWithinGrace(true, now);
            }
            installed = build;
            build = null;
            current = true;
            dirtySections.clear();
            relight = false;
            fallback = "waiting for native GPU acknowledgement";
          }
        } catch (UnsupportedOperationException unsupported) {
          fallback = unsupported.getMessage();
          build = null;
          installed = null;
          invalidateHandoff();
          resident = null;
          retryBuild = now + 1_000_000_000L;
        }
      }
      current = installed != null && installed.valid(window) && installed.coversVisible(client);
      if (current && relight && HANDOFF.canPublishColor()) {
        byte[] payload = installed.payload(lightPixels);
        if (BlockMeshHandoff.payloadChanged(publishedPayload, payload)) {
          int[] counts = installed.counts();
          MAILBOX.mesh(
              BlockMeshProtocol.header(
                  BlockMeshProtocol.MESH_MAGIC,
                  identity,
                  MAILBOX.now(),
                  ++revision,
                  atlasRevision,
                  payload.length / 24,
                  24,
                  payload.length,
                  counts[0],
                  counts[1],
                  counts[2],
                  true),
              payload);
          publishedPayload = payload;
          publishedCounts = counts;
          HANDOFF.color(new BlockMeshHandoff.Revision(revision, atlasRevision, identity.session()));
        }
        relight = false;
      }
      // While one candidate is pending, relight remains set and lightPixels coalesces
      // further changes. The next upload uses only the newest lightmap after promotion.
      retained =
          resident != null
              && displayed != null
              && displayed.coversWindow(client, visibleWindow)
              && HANDOFF.geometryWithinGrace(displayed.geometryCurrent(visibleWindow), now);
      // A pending upload can itself become dirty before its first ACK. Keep its
      // bounded heartbeat alive so the consumer can ACK it and unblock the newer
      // coalesced build; otherwise neither side could advance the single candidate.
      boolean publishedUsable =
          HANDOFF.hasLatest()
              && installed != null
              && installed.coversWindow(client, visibleWindow)
              && (current
                  || retained
                  || HANDOFF.geometryWithinGrace(installed.geometryCurrent(visibleWindow), now));
      MAILBOX.heartbeat(atlasPublished && publishedUsable);
      if (retained)
        exclusion = new Exclusion(resident.mesh(), resident.atlas(), resident.session());
      if (exclusion.active() != reportedExclusion) {
        reportedExclusion = exclusion.active();
        LOG.info(
            "Native block mesh handoff: active={}, reason={}",
            reportedExclusion,
            reportedExclusion ? "matching uploaded mesh and atlas" : fallback);
      }
      if (!current && !retained) reportFallback(fallback);
    } catch (IllegalArgumentException bounds) {
      reportFallback(phase + " rejected: " + bounds.getMessage());
      pause();
    } catch (Throwable failure) {
      failed = true;
      exclusion = Exclusion.NONE;
      MAILBOX.close();
      LOG.warn("Block mesh export disabled; retaining RGB-D chunks: {}", failure.toString());
    }
  }

  private static void reportFallback(String reason) {
    fallback = reason;
    long now = System.nanoTime();
    if (now - reportTime < 5_000_000_000L) return;
    reportTime = now;
    LOG.info(
        "Block mesh RGB-D fallback: {}; atlasReady={}, atlasPending={}, lightReady={},"
            + " lightPending={}, building={}, resident={}, candidate={}",
        reason,
        atlasPixels != null,
        atlasPending,
        lightPixels != null,
        lightPending,
        build != null,
        displayed != null,
        HANDOFF.hasLatest());
  }

  private static void invalidateHandoff() {
    HANDOFF.invalidate();
    MAILBOX.clearAcknowledgement();
    publishedPayload = null;
    publishedCounts = null;
    displayed = null;
  }

  private static void resetLightContext() {
    lightContext++;
    lightPixels = null;
    relight = false;
    nextLight = nextGeometryLight = 0;
    clearLightPending();
  }

  private static void pause() {
    exclusion = Exclusion.NONE;
    if (context != null) resetLightContext();
    context = null;
    identity = null;
    build = installed = null;
    atlasPublished = false;
    invalidateHandoff();
    clearLightPending();
    try {
      if (initialized) MAILBOX.heartbeat(false);
    } catch (Throwable ignored) {
    }
  }

  private static void requestTextures(Minecraft client, long now) {
    if (atlasPixels == null && !atlasPending) {
      var texture = atlasTexture;
      int width = texture.getWidth(0), height = texture.getHeight(0);
      if (width < 1
          || height < 1
          || width > 4096
          || height > 4096
          || texture.getFormat() != GpuFormat.RGBA8_UNORM
          || texture.getDepthOrLayers() != 1)
        throw new IllegalArgumentException(
            "Unsupported block atlas: "
                + width
                + "x"
                + height
                + " "
                + texture.getFormat()
                + " layers="
                + texture.getDepthOrLayers());
      atlasPending = true;
      long generation = resourceGeneration;
      try {
        readTexture(
            texture,
            width * height * 4,
            bytes -> {
              atlasPending = false;
              if (closed || generation != resourceGeneration) return;
              atlasPixels = bytes;
              atlasWidth = width;
              atlasHeight = height;
              atlasRevision++;
              atlasPublished = false;
              LOG.info(
                  "Block mesh atlas readback ready: revision={}, bytes={}",
                  atlasRevision,
                  bytes.length);
            },
            true,
            generation,
            0);
      } catch (Throwable failure) {
        atlasPending = false;
        throw failure;
      }
    }
    if (!lightPending && now >= nextLight) {
      var view = client.gameRenderer.levelLightmap();
      if (view == null) {
        fallback = "vanilla lightmap unavailable";
        return;
      }
      var texture = view.texture();
      if (texture != reportedLightTexture) {
        reportedLightTexture = texture;
        LOG.info(
            "Block mesh lightmap: size={}x{}, format={}, layers={}",
            texture.getWidth(0),
            texture.getHeight(0),
            texture.getFormat(),
            texture.getDepthOrLayers());
      }
      if (texture.getWidth(0) != 16
          || texture.getHeight(0) != 16
          || texture.getFormat() != GpuFormat.RGBA8_UNORM) {
        fallback = "unsupported vanilla lightmap bounds/format";
        return;
      }
      lightPending = true;
      nextLight = now + 500_000_000L;
      long generation = resourceGeneration, scene = lightContext;
      try {
        readTexture(
            texture,
            1024,
            bytes -> {
              lightPending = false;
              if (closed || generation != resourceGeneration || scene != lightContext) return;
              // The real lightmap shader always emits alpha=1. A newly allocated
              // zeroed target is not a rendered night-time lightmap.
              if (!BlockMeshProtocol.renderedLightmap(bytes)) {
                nextLight = 0;
                fallback = "waiting for first rendered vanilla lightmap";
                return;
              }
              // Light-only changes are batched at 2 Hz. Stable light does not change geometry
              // revisions.
              if (!Arrays.equals(lightPixels, bytes)) {
                lightPixels = bytes;
                relight = true;
              }
            },
            false,
            generation,
            scene);
      } catch (Throwable failure) {
        lightPending = false;
        throw failure;
      }
    }
  }

  private static void readTexture(
      GpuTexture texture,
      int size,
      java.util.function.Consumer<byte[]> complete,
      boolean atlas,
      long generation,
      long scene) {
    var buffer =
        RenderSystem.getDevice()
            .createBuffer(
                () -> atlas ? "EldenCraft atlas readback" : "EldenCraft lightmap readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size);
    try {
      RenderSystem.getDevice()
          .createCommandEncoder()
          .copyTextureToBuffer(
              texture,
              buffer,
              0L,
              () -> {
                try {
                  if (closed
                      || generation != resourceGeneration
                      || (!atlas && scene != lightContext)) return;
                  try (var mapped = buffer.map(true, false)) {
                    byte[] bytes = new byte[size];
                    mapped.data().get(bytes);
                    complete.accept(bytes);
                  }
                } catch (Throwable failure) {
                  failed = true;
                  LOG.warn(
                      "Block mesh texture readback failed; keeping RGB-D chunks: {}",
                      failure.toString());
                } finally {
                  if (atlas) atlasPending = false;
                  else lightPending = false;
                  buffer.close();
                }
              },
              0);
    } catch (Throwable failure) {
      buffer.close();
      throw failure;
    }
  }

  private record Section(LevelChunkSection section, int x, int y, int z) {
    long key() {
      return SectionPos.asLong(x, y, z);
    }
  }

  /** {@code live}: packed positions of animated blocks whose quads stay out of the static mesh. */
  private record Geometry(byte[][] layers, int blocks, long[] live) {
    int vertices() {
      int n = 0;
      for (var bytes : layers) n += bytes.length / 28;
      return n;
    }
  }

  private static final class Build {
    final BlockMeshCoverage window;
    final BlockMeshChanges.Version generation = CHANGES.version();
    final long full = fullGeneration, resources = resourceGeneration;
    final SharedWorldClient.BlockMeshContext ctx = context;
    final ModelBlockRenderer renderer;
    final Map<Long, LevelChunk> chunks = new HashMap<>();
    final Map<Long, Geometry> geometry = new TreeMap<>();
    final Set<Long> refreshSections = Set.copyOf(dirtySections);
    final Set<Long> refreshChunks = new HashSet<>();
    final ArrayList<Section> sections = new ArrayList<>();
    ByteArrayOutputStream[] output = newOutputs(), blockOutput = newOutputs();
    final ArrayList<Long> sectionLive = new ArrayList<>();
    boolean animated;
    int blockVertices, live;
    final ByteBuffer vertex = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
    int chunkCursor, sectionCursor, cell, blocks, vertices, sectionBlocks;
    final int width;
    boolean collecting = true;
    long coverFrame = -1;
    boolean coverValue;

    Build(Minecraft client, BlockMeshCoverage window, Build previous) {
      this.window = window;
      width = window.radius() * 2 + 1;
      renderer =
          new ModelBlockRenderer(
              client.options.ambientOcclusion().get(), true, client.getBlockColors());
      fallback = "covering complete render area";
      for (long key : refreshSections)
        refreshChunks.add(ChunkPos.pack(SectionPos.x(key), SectionPos.z(key)));
      if (previous != null
          && previous.full == full
          && previous.resources == resources
          && previous.ctx.equals(ctx)) {
        // Retain immutable overlap, including known-empty chunks. Moving one
        // section scans only the entering border, not hundreds of unchanged chunks.
        for (var entry : previous.chunks.entrySet()) {
          var pos = entry.getValue().getPos();
          if (window.contains(pos.x(), window.minY(), pos.z()))
            chunks.put(entry.getKey(), entry.getValue());
        }
        for (var entry : previous.geometry.entrySet()) {
          long key = entry.getKey();
          if (window.contains(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key)))
            geometry.put(key, entry.getValue());
        }
        for (var value : geometry.values()) {
          vertices += value.vertices();
          blocks += value.blocks();
          live += value.live().length;
        }
        fallback =
            previous.window.equals(window)
                ? "refreshing changed block sections"
                : "extending prebuilt render-area border";
      }
    }

    boolean valid(BlockMeshCoverage now) {
      return CHANGES.current(generation) && sameBasis(now);
    }

    boolean geometryCurrent(BlockMeshCoverage now) {
      return CHANGES.geometryCurrent(generation) && sameContext() && window.contains(now);
    }

    private boolean sameContext() {
      return resources == resourceGeneration && ctx.equals(context);
    }

    private boolean sameBasis(BlockMeshCoverage now) {
      return resources == resourceGeneration && ctx.equals(context) && window.equals(now);
    }

    boolean coversWindow(Minecraft client, BlockMeshCoverage now) {
      return sameContext() && window.contains(now) && coversVisible(client);
    }

    boolean step(Minecraft client) {
      long deadline = System.nanoTime() + 2_000_000L;
      int work = 0;
      while (collecting
          && chunkCursor < width * width
          && work < 32
          && System.nanoTime() < deadline) {
        int x = window.centerX() - window.radius() + chunkCursor % width,
            z = window.centerZ() - window.radius() + chunkCursor / width;
        chunkCursor++;
        long key = ChunkPos.pack(x, z);
        var previous = chunks.get(key);
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk != null && chunk == previous && !refreshChunks.contains(key)) continue;
        work++;
        coverFrame = -1;
        if (chunk == null) {
          chunks.remove(key);
          if (previous != null)
            for (int y = window.minY(); y <= window.maxY(); y++)
              removeSection(SectionPos.asLong(x, y, z));
          continue;
        }
        chunks.put(key, chunk);
        var all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
          int y = chunk.getMinSectionY() + i;
          if (y < window.minY() || y > window.maxY()) continue;
          long section = SectionPos.asLong(x, y, z);
          if (chunk == previous && !refreshSections.contains(section)) continue;
          removeSection(section);
          if (all[i].maybeHas(BlockMeshClient::real)) sections.add(new Section(all[i], x, y, z));
        }
      }
      collecting = chunkCursor < width * width;
      if (collecting) return false;
      work = 0;
      while (sectionCursor < sections.size() && work++ < 4096 && System.nanoTime() < deadline) {
        var s = sections.get(sectionCursor);
        int x = cell & 15, z = (cell >>> 4) & 15, y = cell >>> 8;
        var state = s.section.getBlockState(x, y, z);
        if (real(state)) {
          sectionBlocks++;
          if (++blocks > 4096)
            throw new UnsupportedOperationException("More than 4096 real blocks in render area");
          if (!state.getFluidState().isEmpty())
            throw new UnsupportedOperationException("Fluid geometry remains in RGB-D");
          if (state.getRenderShape() == RenderShape.MODEL) {
            var pos = new BlockPos(s.x * 16 + x, s.y * 16 + y, s.z * 16 + z);
            var canonical = ctx.origin().toHost(pos.getX(), pos.getY(), pos.getZ());
            var model = client.getModelManager().getBlockStateModelSet().get(state);
            boolean opaque =
                ModelBlockRenderer.forceOpaque(client.options.cutoutLeaves().get(), state);
            animated = false;
            blockVertices = 0;
            for (var out : blockOutput) out.reset();
            renderer.tesselateBlock(
                (ox, oy, oz, quad, instance) -> {
                  var material = quad.materialInfo();
                  // A snapshot atlas would freeze fire, magma and the portal: those blocks render
                  // live instead.
                  if (material.sprite().isAnimated()) {
                    animated = true;
                    return;
                  }
                  if (!material.sprite().atlasLocation().equals(atlasLocation))
                    throw new UnsupportedOperationException("Non-block atlas material");
                  int layer =
                      opaque
                          ? 0
                          : switch (material.layer()) {
                            case SOLID -> 0;
                            case CUTOUT -> 1;
                            case TRANSLUCENT -> 2;
                          };
                  if (vertices + blockVertices + 6 > BlockMeshProtocol.MAX_VERTICES)
                    throw new UnsupportedOperationException("Block mesh vertex budget");
                  for (int index : QUAD_TRIANGLES) {
                    var p = quad.position(index);
                    long uv = quad.packedUV(index);
                    vertex.clear();
                    vertex
                        .putFloat(ox + p.x())
                        .putFloat(oy + p.y())
                        .putFloat(oz + p.z())
                        .putFloat(UVPair.unpackU(uv))
                        .putFloat(UVPair.unpackV(uv))
                        .putInt(instance.getColor(index))
                        .putInt(
                            instance.getLightCoordsWithEmission(index, material.lightEmission()));
                    blockOutput[layer].writeBytes(vertex.array());
                    blockVertices++;
                  }
                },
                (float) canonical.x(),
                (float) canonical.y(),
                (float) canonical.z(),
                level,
                pos,
                state,
                model,
                state.getSeed(pos));
            if (animated) {
              if (++live > MAX_LIVE)
                throw new UnsupportedOperationException(
                    "More than " + MAX_LIVE + " animated blocks in render area");
              sectionLive.add(pos.asLong());
            } else {
              for (int i = 0; i < 3; i++) output[i].writeBytes(blockOutput[i].toByteArray());
              vertices += blockVertices;
            }
          }
        }
        cell++;
        if (cell == 4096) {
          // A palette can still mention mined states after its last real block
          // disappears. Empty sections need no repeated propagated-light work.
          if (sectionBlocks == 0) geometry.remove(s.key());
          else
            geometry.put(
                s.key(),
                new Geometry(
                    new byte[][] {
                      output[0].toByteArray(), output[1].toByteArray(), output[2].toByteArray()
                    },
                    sectionBlocks,
                    sectionLive.stream().mapToLong(Long::longValue).toArray()));
          output = newOutputs();
          sectionLive.clear();
          sectionBlocks = 0;
          cell = 0;
          sectionCursor++;
        }
      }
      return sectionCursor == sections.size();
    }

    private void removeSection(long key) {
      var old = geometry.remove(key);
      if (old != null) {
        vertices -= old.vertices();
        blocks -= old.blocks();
        live -= old.live().length;
      }
    }

    boolean coversVisible(Minecraft client) {
      if (coverFrame != frameStamp) {
        coverValue = scanVisible(client);
        coverFrame = frameStamp;
      }
      return coverValue;
    }

    private boolean scanVisible(Minecraft client) {
      for (var section : client.levelRenderer.visibleSections()) {
        if (!section.getSectionMesh().hasRenderableLayers()) continue;
        var p = section.getRenderOrigin();
        int x = p.getX() >> 4, y = p.getY() >> 4, z = p.getZ() >> 4;
        if (!window.contains(x, y, z)
            || chunks.get(ChunkPos.pack(x, z))
                != level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)) return false;
        if (!chunks.containsKey(ChunkPos.pack(x, z))) return false;
      }
      return true;
    }

    int[] counts() {
      int[] counts = new int[3];
      for (var section : geometry.values())
        for (int i = 0; i < 3; i++) counts[i] += section.layers[i].length / 28;
      return counts;
    }

    byte[] payload(byte[] light) {
      var all = ByteBuffer.allocate(vertices * 24).order(ByteOrder.LITTLE_ENDIAN);
      for (int layer = 0; layer < 3; layer++)
        for (var section : geometry.values()) {
          var raw = ByteBuffer.wrap(section.layers[layer]).order(ByteOrder.LITTLE_ENDIAN);
          while (raw.hasRemaining()) {
            for (int i = 0; i < 5; i++) all.putFloat(raw.getFloat());
            all.putInt(BlockMeshProtocol.litRgba(raw.getInt(), raw.getInt(), light));
          }
        }
      return all.array();
    }

    private static ByteArrayOutputStream[] newOutputs() {
      return new ByteArrayOutputStream[] {
        new ByteArrayOutputStream(), new ByteArrayOutputStream(), new ByteArrayOutputStream()
      };
    }
  }

  private static boolean real(BlockState state) {
    return !state.isAir() && !state.is(SharedWorldBlocks.TERRAIN);
  }

  /**
   * While the native mesh replaces vanilla chunks, the animated blocks it left out are submitted
   * like vanilla transient blocks, with the current animated atlas. Called from
   * submitTransientBlocks.
   */
  public static void submitLiveBlocks(PoseStack pose, SubmitNodeCollector collector, Vec3 camera) {
    var shown = displayed;
    var world = level;
    if (!suppressChunks() || shown == null || world == null || camera == null) {
      liveStates.clear();
      return;
    }
    var keep = new HashSet<Long>();
    int count = 0;
    for (var section : shown.geometry.values())
      for (long key : section.live()) {
        if (++count > MAX_LIVE) break;
        var pos = BlockPos.of(key);
        var state = world.getBlockState(pos);
        if (!real(state) || state.getRenderShape() != RenderShape.MODEL) continue;
        keep.add(key);
        var live =
            liveStates.computeIfAbsent(
                key,
                k -> {
                  var m = new MovingBlockRenderState();
                  m.randomSeedPos = pos;
                  m.blockPos = pos;
                  return m;
                });
        live.blockState = state;
        live.biome = world.getBiome(pos);
        live.cardinalLighting = world.cardinalLighting();
        live.lightEngine = world.getLightEngine();
        pose.pushPose();
        pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        collector.submitMovingBlock(pose, live, 0);
        pose.popPose();
      }
    liveStates.keySet().retainAll(keep);
  }

  public static void close() {
    closed = true;
    exclusion = Exclusion.NONE;
    build = installed = null;
    atlasPixels = lightPixels = null;
    invalidateHandoff();
    clearLightPending();
    MAILBOX.close();
  }
}
