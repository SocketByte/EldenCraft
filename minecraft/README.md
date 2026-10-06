# Minecraft bridge

The Fabric client mod exports Minecraft rendering and vanilla gameplay to the
Elden Ring host. It targets Minecraft 26.3, Fabric Loader 0.19.5 and Java 25.

Build and run the conformance suites from the repository root:

```powershell
.\scripts\eldencraft.ps1 build-minecraft --offline
```

The runnable artifact is `build/libs/eldencraft-bridge-<version>.jar`. The sources
JAR is for development. Player installation is handled by the root
`EldenCraft.cmd`; see the [project README](../README.md).

## Responsibilities

The client captures world, hand, HUD and avatar frames, publishes shared textures
or CPU fallback frames, and imports fresh native pose/input state. The integrated
server resolves Minecraft inventory, blocks, mobs, item actions and combat.
Communication uses bounded local mappings with session identities, seqlocks and
freshness checks.

The launcher creates a dedicated Prism instance. When Minecraft finishes its
startup screens, the client creates and joins its EldenCraft survival world
through Minecraft's own world-creation API. Later launches reopen that save,
including saves from earlier dedicated profiles. This works when launching the
mod directly as well, and does not wait for Elden Ring. Cancelling a load or
returning to the title screen leaves Minecraft in its menus.

Focus loss does not pause the dedicated offline world. An open Minecraft pause
screen closes when its window loses focus, independently of the host input
connection. Inventory and chat remain open. Other saves and published sessions
retain vanilla behavior, and the user's pause-on-focus-loss setting is preserved.

## Interactions and map

`CampaignInteractions` reads fresh native prompts, actual ESD choice lists and
spoken text. `InteractionHud` draws prompts and subtitles with Minecraft's font;
`InteractionScreen` uses vanilla buttons and wrapped confirmation messages.
The native script owns quest progression and receives the selected original row
ID. Level-up, flask and memorize-spell grace rows are excluded by native text IDs.
The grace Ender Chest action opens a real server-backed vanilla container without
advancing the native script; closing it returns to the same grace menu. Camera,
attacks, movement and item use release their
input while a replacement menu is open. During a native lease gap of up to three
seconds the open screen stays visible with its choices disabled; a close made
during the gap is delivered when the feed returns.

Imported `Escape` opens the vanilla Minecraft pause menu and its options screens.
The exact pause screen retains a fresh foreground host lease; losing that lease
restores the dedicated world's normal background auto-resume behavior. Native
pause opening is reserved while Minecraft owns the composed interface.

`M` opens `CampaignMapScreen`, including from the grace menu. The map caches
genuine shared terrain samples, displays the player's position and direction,
and lists discovered native Sites of Grace. Coordinates are local to the
published native block; foreign areas use a separate map view rather than
overlapping unrelated coordinates. Drag to pan, scroll to zoom, right-click for
a waypoint, or use arrows, `+`/`-`, `Home` and `Tab`. A seated native rest context
enables confirmed travel; the native side revalidates discovery and restrictions
immediately before the normal world transition.

Replacement screens close on stale state or a changed host session. Unsupported
specialist native screens retain their original input and rendering.

## Configuration

- `ELDENCRAFT_AUTO_WORLD=0`: disable automatic world opening.
- `ELDENCRAFT_CREATE_WORLD=0`: disable first-run creation while allowing an existing world to open.
- `ELDENCRAFT_GUEST_RESOLUTION=window`: keep the Minecraft window's resolution.
- `ELDENCRAFT_FRAME_EXPORT=0`: disable frame publication.
- `config/eldencraft-materials.json` in the instance: terrain-to-block mappings.

The bridge closes on stale/inactive host state, menus, transitions, multiplayer
or LAN publication. Imported keys and camera ownership are released on loss of
authority. Command permissions and item consumption remain vanilla.

Wire contracts are indexed in [PROTOCOL.md](../PROTOCOL.md); rendering is described
in the [compositor guide](../compositor/README.md). Spawn admission is documented
in [SPAWNING.md](SPAWNING.md).
