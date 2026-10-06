package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** An explored terrain map built from genuine native collision samples and unlocked locations. */
final class CampaignMapScreen extends Screen {
  private record Cell(int x, int z, double height) {}

  private static final int MAX_CELLS = 65_536;
  private static final LinkedHashMap<Long, Cell> TERRAIN = new LinkedHashMap<>();
  private static long terrainPid, terrainMap, terrainSession;
  private final InteractionMapView view = new InteractionMapView();
  private final long pid, session, map;
  private int displayBlock;
  private int left, top, mapWidth, mapHeight, page, selected = -1, sampleTicks;
  private double waypointX, waypointZ;
  private int waypointBlock;
  private boolean waypoint, dragging, confirmTravel, pending;
  private long pendingTravelSequence;
  private List<InteractionProtocol.Marker> markers = List.of();
  private Button travel;
  private String status = "";

  CampaignMapScreen(InteractionProtocol.Snapshot s) {
    super(Component.literal("Map"));
    pid = s.pid();
    session = s.session();
    var host = HostController.healthSnapshot(net.minecraft.client.Minecraft.getInstance());
    map = host == null ? 0 : host.mapId();
    if (terrainPid != pid || terrainMap != map || terrainSession != session) {
      TERRAIN.clear();
      terrainPid = pid;
      terrainMap = map;
      terrainSession = session;
    }
    if (host != null) view.center(host.feet().x(), host.feet().z());
    displayBlock = (int) map;
    markers = sortedMarkers(s);
  }

  @Override
  protected void init() {
    left = 12;
    top = 34;
    mapWidth = Math.max(16, width - (markers.isEmpty() || width < 280 || height < 165 ? 24 : 164));
    mapHeight = Math.max(16, height - 80);
    if (width < 210 || height < 115) return;
    addRenderableWidget(
        Button.builder(Component.literal("-"), b -> view.zoom(-1, 0, 0))
            .bounds(left, height - 36, 24, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("+"), b -> view.zoom(1, 0, 0))
            .bounds(left + 28, height - 36, 24, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Center"), b -> center())
            .bounds(left + 58, height - 36, 58, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width - 76, height - 36, 64, 20)
            .build());
    if (!markers.isEmpty() && width >= 280 && height >= 165) {
      int rows = markerRows(), pages = Math.max(1, (markers.size() + rows - 1) / rows);
      page = Math.clamp(page, 0, pages - 1);
      for (int row = 0; row < rows; row++) {
        int index = page * rows + row;
        if (index >= markers.size()) break;
        var marker = markers.get(index);
        addRenderableWidget(
            Button.builder(
                    Component.literal(MinecraftUi.clipped(font, marker.text(), 122)),
                    b -> selectMarker(index))
                .bounds(width - 144, top + row * 22, 132, 20)
                .build());
      }
      if (pages > 1) {
        addRenderableWidget(
                    Button.builder(Component.literal("<"), b -> markerPage(-1))
                        .bounds(width - 144, top + rows * 22, 26, 20)
                        .build())
                .active =
            page > 0;
        addRenderableWidget(
                    Button.builder(Component.literal(">"), b -> markerPage(1))
                        .bounds(width - 114, top + rows * 22, 26, 20)
                        .build())
                .active =
            page + 1 < pages;
      }
      travel =
          addRenderableWidget(
              Button.builder(
                      Component.literal(confirmTravel ? "Confirm travel" : "Travel"), b -> travel())
                  .bounds(width - 144, height - 60, 132, 20)
                  .build());
      updateTravel();
    }
  }

  private int markerRows() {
    return Math.max(1, (height - 142) / 22);
  }

  private void markerPage(int delta) {
    page = Math.clamp(page + delta, 0, Math.max(0, (markers.size() - 1) / markerRows()));
    rebuildWidgets();
  }

  private void selectMarker(int index) {
    if (pending) return;
    selected = index;
    confirmTravel = false;
    status = "";
    var marker = markers.get(index);
    displayBlock = marker.block();
    view.center(marker.x(), marker.z());
    updateTravel();
  }

  private void updateTravel() {
    if (travel == null) return;
    travel.active =
        !pending && selected >= 0 && selected < markers.size() && markers.get(selected).travel();
    travel.setMessage(
        Component.literal(pending ? "Travelling..." : confirmTravel ? "Confirm travel" : "Travel"));
    travel.setTooltip(
        Tooltip.create(
            Component.literal(
                selected < 0
                    ? "Select a discovered Site of Grace."
                    : pending
                        ? "Travelling..."
                        : !travel.active
                            ? "Rest at a Site of Grace to travel."
                            : "Travel to " + markers.get(selected).text())));
  }

  private void travel() {
    if (selected < 0 || selected >= markers.size() || !markers.get(selected).travel() || pending)
      return;
    if (!confirmTravel) {
      confirmTravel = true;
      status = "Travel to " + markers.get(selected).text() + "?";
      updateTravel();
      return;
    }
    pendingTravelSequence = CampaignInteractions.travel(markers.get(selected).id());
    if (pendingTravelSequence > 0) {
      pending = true;
      status = "Travelling...";
      updateTravel();
    } else status = "Travel request could not be sent.";
  }

  private void center() {
    var host = HostController.healthSnapshot(minecraft);
    if (host != null) {
      displayBlock = (int) host.mapId();
      view.center(host.feet().x(), host.feet().z());
    }
    confirmTravel = false;
    status = "";
    updateTravel();
  }

  private boolean contains(double x, double y) {
    return x >= left && x < left + mapWidth && y >= top && y < top + mapHeight;
  }

  private int mapX(double x) {
    return left + mapWidth / 2 + view.pixelX(x);
  }

  private int mapZ(double z) {
    return top + mapHeight / 2 + view.pixelZ(z);
  }

  @Override
  public void tick() {
    var s = CampaignInteractions.snapshot();
    var host = HostController.healthSnapshot(minecraft);
    if (s == null
        || s.pid() != pid
        || s.session() != session
        || host == null
        || host.mapId() != map) {
      minecraft.gui.setScreen(null);
      return;
    }
    var updatedMarkers = sortedMarkers(s);
    if (!updatedMarkers.equals(markers)) {
      Integer selectedId =
          selected >= 0 && selected < markers.size() ? markers.get(selected).id() : null;
      markers = updatedMarkers;
      selected = -1;
      if (selectedId != null)
        for (int i = 0; i < markers.size(); i++)
          if (markers.get(i).id() == selectedId) {
            selected = i;
            break;
          }
      rebuildWidgets();
    }
    if (pending) {
      var ack =
          InteractionProtocol.travelAcknowledgement(
              s, pid, session, pendingTravelSequence, System.currentTimeMillis());
      if (ack != null) {
        status = ack.message();
        if (ack.status().equals("rejected")) {
          pending = false;
          pendingTravelSequence = 0;
          confirmTravel = false;
          updateTravel();
        }
      }
    }
    if (sampleTicks++ % 10 == 0) observeTerrain(s, host);
  }

  private static List<InteractionProtocol.Marker> sortedMarkers(InteractionProtocol.Snapshot s) {
    return s.markers().stream()
        .sorted(
            Comparator.comparing(InteractionProtocol.Marker::text, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(InteractionProtocol.Marker::id))
        .toList();
  }

  static void observeTerrain(InteractionProtocol.Snapshot s, HostState.Snapshot host) {
    if (s == null || host == null) return;
    if (terrainPid != s.pid() || terrainMap != host.mapId() || terrainSession != s.session()) {
      TERRAIN.clear();
      terrainPid = s.pid();
      terrainMap = host.mapId();
      terrainSession = s.session();
    }
    var terrain = SharedWorldClient.mapTerrainSnapshot(host);
    if (terrain == null || !terrain.coordinates().matches(s.pid(), host.mapId())) return;
    for (var entry : terrain.shapes().entrySet()) {
      if (entry.getValue().isEmpty()) continue;
      BlockPos p = BlockPos.of(entry.getKey());
      WorldOrigin.Vec h;
      try {
        h = terrain.coordinates().toSource(p.getX(), p.getY(), p.getZ());
      } catch (IllegalArgumentException invalidPosition) {
        continue;
      }
      int x = (int) Math.floor(h.x()), z = (int) Math.floor(h.z());
      long key = ((long) x << 32) | (z & 0xffff_ffffL);
      double height = h.y() + entry.getValue().max(net.minecraft.core.Direction.Axis.Y);
      var old = TERRAIN.get(key);
      if (old == null || height > old.height()) TERRAIN.put(key, new Cell(x, z, height));
    }
    while (TERRAIN.size() > MAX_CELLS) TERRAIN.pollFirstEntry();
  }

  void hostInput(int pressed, int held) {
    if ((pressed & CampaignInteractions.CANCEL) != 0) {
      onClose();
      return;
    }
    double speed = (held & CampaignInteractions.SHIFT) != 0 ? 24 : 8;
    if ((held & CampaignInteractions.LEFT) != 0) view.pan(speed, 0);
    if ((held & CampaignInteractions.RIGHT) != 0) view.pan(-speed, 0);
    if ((held & CampaignInteractions.UP) != 0) view.pan(0, speed);
    if ((held & CampaignInteractions.DOWN) != 0) view.pan(0, -speed);
    if ((pressed & CampaignInteractions.ZOOM_IN) != 0) view.zoom(1, 0, 0);
    if ((pressed & CampaignInteractions.ZOOM_OUT) != 0) view.zoom(-1, 0, 0);
    if ((pressed & CampaignInteractions.HOME) != 0) center();
    if ((pressed & CampaignInteractions.TAB) != 0 && !markers.isEmpty()) {
      selectMarker(
          Math.floorMod(
              selected + ((held & CampaignInteractions.SHIFT) != 0 ? -1 : 1), markers.size()));
      page = selected / markerRows();
      rebuildWidgets();
    }
    if ((pressed & CampaignInteractions.CONFIRM) != 0) travel();
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (contains(event.x(), event.y())) {
      if (event.button() == 0) {
        for (int index = 0; index < markers.size(); index++) {
          var marker = markers.get(index);
          if (marker.block() != displayBlock) continue;
          if (Math.abs(event.x() - mapX(marker.x())) <= 6
              && Math.abs(event.y() - mapZ(marker.z())) <= 6) {
            selectMarker(index);
            return true;
          }
        }
        dragging = true;
      } else if (event.button() == 1) {
        double x = view.worldX(event.x() - left - mapWidth / 2.0);
        double z = view.worldZ(event.y() - top - mapHeight / 2.0);
        boolean remove =
            waypoint
                && waypointBlock == displayBlock
                && Math.hypot(x - waypointX, z - waypointZ) <= 6 / view.scale();
        waypointX = x;
        waypointZ = z;
        waypointBlock = displayBlock;
        waypoint = !remove;
      }
      return true;
    }
    return super.mouseClicked(event, doubleClick);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    if (dragging) {
      dragging = false;
      return true;
    }
    return super.mouseReleased(event);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    if (dragging) {
      view.pan(dx, dy);
      return true;
    }
    return super.mouseDragged(event, dx, dy);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (contains(x, y)) {
      view.zoom(vertical, x - left - mapWidth / 2.0, y - top - mapHeight / 2.0);
      return true;
    }
    if (!markers.isEmpty()) {
      markerPage(vertical > 0 ? -1 : 1);
      return true;
    }
    return super.mouseScrolled(x, y, horizontal, vertical);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean isInGameUi() {
    return true;
  }

  @Override
  public void onClose() {
    CampaignInteractions.closeMap();
    minecraft.gui.setScreen(null);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
    gui.fill(0, 0, width, height, 0x99000000);
    MinecraftUi.panel(gui, 4, 4, Math.max(1, width - 8), Math.max(1, height - 8));
    gui.text(font, "Map", 12, 14, MinecraftUi.TEXT, false);
    gui.text(
        font,
        MinecraftUi.clipped(
            font, "Drag to pan  |  Scroll to zoom  |  Right click: waypoint", width - 68),
        46,
        14,
        MinecraftUi.MUTED,
        false);
    if (width < 210 || height < 115) {
      gui.text(font, "Reduce GUI scale to view the map.", 12, 40, MinecraftUi.TEXT, false);
      return;
    }
    MinecraftUi.slot(gui, left - 1, top - 1, mapWidth + 2, mapHeight + 2, 0xffe0d2ae);
    gui.enableScissor(left, top, left + mapWidth, top + mapHeight);
    for (var cell : displayBlock == (int) map ? TERRAIN.values() : List.<Cell>of()) {
      int x = mapX(cell.x()), z = mapZ(cell.z()), size = Math.max(1, (int) Math.ceil(view.scale()));
      if (x < left - size || z < top - size || x >= left + mapWidth || z >= top + mapHeight)
        continue;
      int shade = (int) Math.clamp(112 + cell.height() * .35, 72, 160);
      int color =
          0xff000000 | shade << 16 | Math.min(190, shade + 22) << 8 | Math.max(48, shade - 25);
      gui.fill(x, z, x + size, z + size, color);
    }
    for (int index = 0; index < markers.size(); index++) {
      var marker = markers.get(index);
      if (marker.block() != displayBlock) continue;
      int x = mapX(marker.x()), z = mapZ(marker.z());
      marker(gui, x, z, index == selected ? 0xffffffff : 0xffffd454);
      if (Math.abs(mouseX - x) <= 6 && Math.abs(mouseY - z) <= 6)
        gui.setTooltipForNextFrame(Component.literal(marker.text()), mouseX, mouseY);
    }
    if (waypoint && displayBlock == waypointBlock)
      marker(gui, mapX(waypointX), mapZ(waypointZ), 0xff66d4ff);
    var host = HostController.healthSnapshot(minecraft);
    if (host != null && displayBlock == (int) host.mapId()) {
      int x = mapX(host.feet().x()), z = mapZ(host.feet().z());
      marker(gui, x, z, 0xffeeeeee);
      int dx = (int) Math.round(host.forward().x() * 8),
          dz = (int) Math.round(host.forward().z() * 8);
      gui.fill(x + dx - 1, z + dz - 1, x + dx + 2, z + dz + 2, 0xffe03030);
    }
    gui.disableScissor();
    if (TERRAIN.isEmpty() || displayBlock != (int) map)
      gui.text(
          font,
          MinecraftUi.clipped(font, "No terrain sampled in this area yet.", mapWidth - 16),
          left + 8,
          top + 8,
          MinecraftUi.TEXT,
          false);
    gui.text(font, "N", left + mapWidth - 10, top + 5, MinecraftUi.TEXT, false);
    String area =
        displayBlock == (int) map
            ? "Current area"
            : selected >= 0 && selected < markers.size()
                ? markers.get(selected).text()
                : "Selected area";
    String footer =
        status.isBlank()
            ? area + "  |  " + Math.round(view.x()) + ", " + Math.round(view.z())
            : status;
    gui.text(
        font,
        MinecraftUi.clipped(font, footer, width - 24),
        12,
        height - 49,
        MinecraftUi.MUTED,
        false);
    if (!status.isBlank()
        && mouseX >= 12
        && mouseX < width - 12
        && mouseY >= height - 50
        && mouseY < height - 38)
      gui.setTooltipForNextFrame(Component.literal(status), mouseX, mouseY);
    super.extractRenderState(gui, mouseX, mouseY, partialTick);
  }

  private static void marker(GuiGraphicsExtractor gui, int x, int y, int color) {
    gui.fill(x - 3, y - 3, x + 4, y + 4, 0xff242424);
    gui.fill(x - 1, y - 2, x + 2, y + 3, color);
    gui.fill(x - 2, y - 1, x + 3, y + 2, color);
  }
}
