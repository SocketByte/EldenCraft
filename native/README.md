# Native host and loader

The Rust workspace produces `eldencraft_native.dll`, the persistent loader, and
`eldencraft_core.dll`, the gameplay bridge. Minecraft owns its items, blocks and
simulation; the host adapts Elden Ring camera, motion, collision, input and damage.

```powershell
.\scripts\eldencraft.ps1 build-native --offline
.\scripts\eldencraft.ps1 test-native --offline
```

Run these commands from the repository root. Artifacts are written to
`.local/native-build/release`. Both DLLs link the C runtime statically.

## Compatibility and ownership

The SDK revision is `59fbd3b3b7daaf14aca47c9f73530493dba6bc79`. The supported Elden
Ring product version is 2.7.1.0, with executable SHA256:

```text
1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891
```

The launcher and native host both enforce compatibility. Offline session, active
player, foreground window, menu state and publication freshness gate native
ownership. Restoration requires the same live object and a value still owned by
the bridge. Player releases use me3 with `start_online=false` and a separate
`EldenCraft.sl2`.

The movement capsule uses the pinned native resize routine at RVA `0x464670`
for Minecraft clearance (at most 1.8 m high, radius 0.3 m). The routine updates
both native proxies, shape references, broadphase and cached dimensions; the
bridge never edits Havok allocations or dimension fields directly. It retains
the other capsule options and scopes subsequent native animation resize calls,
capturing each newer native pose. Cached scoped sizes preserve their original
restoration lease; native flattened secondary shapes remain supported. Suspension or
the next unowned physics stage restores only the exact dimensions still owned
on the same local player/module. Native ladders retain native motion and size.
The executable SHA and resize/caller/shape-consumer fingerprints gate this path.

Native interactions hand movement back to Elden Ring before the R latch is
consumed. Held movement and jump input stay captured while the original native
root-motion and collision stage completes fog entry, door opening and other
scripted animations. A short startup window covers behavior scheduling;
movement resumes after the native action flags permit it for 150 ms. The physics
hook rechecks the interaction reservation at the collision boundary and restores the
native capsule when it releases ownership. This avoids replacing an animation's
root motion with Minecraft locomotion while its collision rules are changing.

Minecraft enemy fluid contacts are matched to recent native frame/target
generation evidence and a live stage owner. Only horizontal root displacement
and submitted horizontal velocity are scaled (water 0.5, lava 0.25); native AI,
vertical motion and collision remain active. Contacts expire after 150 ms and
release on guest/session/world/control loss. Minecraft hazard damage uses the
existing native enemy damage sink, with historical/current target checks.

## Interaction menus

`interaction_runtime` captures the pinned ESD talk events and environment queries.
Campaign shops and interaction menus share one idempotent talk-event dispatcher;
each event reaches the original handler once unless a replacement handles it.
The environment-query and text hooks verify their own targets before installing.
Event and query values are read through the executable's verified virtual
accessors, including script implementations with different memory layouts.
It replaces complete TalkList and ConversationChoices menus and verified generic
dialogs, preserving their original row IDs, result codes, rest actions and quest
side effects. Its message lookup hook supplies localized action text and NPC
speech. Only text with a fresh Minecraft replacement is suppressed. Unsupported
specialist windows retain native rendering and controls.

Replacement ownership requires a foreground offline player, fresh compositor
input and a ready Minecraft heartbeat. ESD menu capture validates its live script
owner and current offline session using that fresh bridge lease; movement-phase
player physics and activity checks apply only to the separate reset recovery.
Losing a bridge gate releases input and restores camera/HUD ownership. An open
owned menu is held for up to 3 seconds so a brief gap (for example, focus or
readiness changes when an NPC conversation starts) does not answer the script
with Leave; it is cancelled if the lease is not regained, and immediately on
online, lobby or warp transitions. Holds, cancellations and script-initiated
closes are logged. Delivered script results remain available for the owning NPC
to consume. A bounded, same-player grace reset retains passive source rows while
gameplay writes remain suspended; after readiness returns, a still-open native
grace list can transfer to Minecraft.
Grace menus are identified by the common grace talk script (t000001000) or its
grace-only Pass time row, independently of rest/bonfire flags, and their rules are
reapplied when the menu is shown. Native levelling, flask and memorize-spell
grace entries are excluded; Sort chest becomes the Minecraft Ender Chest action.

The transient HUD controller also reserves the documented native Pause permission
while the composed interface is available. It uses verified native
reset/getter/setter fingerprints, changes only the permission bit, and restores
it with live menu identity and value checks. Minecraft supplies the ordinary
pause/options screens. The native UI task timing still requires gameplay QA.

`native_map` follows Elden Ring's own world map. `M` opens it by briefly holding
the native Map binding's digital input; the player's own Map binding is followed
as well. While it is open the engine suspends exactly as for a blocking native
menu, releasing composition, ECHS input and native input reservations. Close
keys resume Minecraft once released; any player movement or load also ends it.
Merchant Sell rows are omitted from replacement menus and Purchase is shown as
Shop, so only the campaign's intercepted shop is reachable.

## Hot reload

The loader owns the game task registrations and compositor exports. It starts a
private copy of the core from the runtime's `loaded/` directory.
`ELDENCRAFT_HOT_RELOAD=1` enables replacement on the post-physics task; the old core
releases its owned changes first. Retired copies remain in memory. For a developer
runtime, `scripts/eldencraft.ps1 reload-native` rebuilds and copies the core.
Minecraft and compositor updates require a restart.

## Diagnostics

`ELDENCRAFT_DATA_DIR` selects the runtime data directory. Native and loader logs
are written there. Fault reports are under `crash/`; inspect dumps before sharing.
`ELDENCRAFT_DIAGNOSTIC_MODE=task-only` exercises task registration without running
gameplay callbacks.

The optional `ELDENCRAFT_FILE_CONTROL=1` interface accepts increasing, bounded
command sequences through `command.json`. See `src/control.rs` and
`scripts/eldencraft-control.ps1`. Acknowledgements report dispatch, not completion
of game actions.

## Placed-block collision

Minecraft publishes merged, seamless boxes for placed blocks around the player
and ahead of their motion. [Colliders](src/native_colliders.rs) create missing
static bodies nearest first and keep each stale body until its replacement
exists, so a box that changes shape never leaves a frame without a floor.
[Step assist](src/step_assist.rs) gives the movement model vanilla step-up onto
placed slabs and stair steps (0.6 m; a full block on Torrent) with an owned hop;
Elden Ring terrain keeps native stepping. Terrain sampling sizes its ray budget
from measured query cost (about 1.25 ms per 33 ms tick, 96 to 1020 rays) and
refines floors at 25 cm within three cells of the feet.

Grounded Torrent strides follow the native floor normal at a constant path
speed, rather than pushing a horizontal stride into uphill terrain. Wall
feedback includes height gained or lost along the stride, so sustained slope
travel does not repeatedly clear momentum. Brief contact loss keeps the riding
ground gait until the vertical model declares a ledge fall; jumps and double
jumps retain airborne travel. The floor normal must be finite, approximately
unit length and within 60 degrees of upright. Collision, sliding on steeper
surfaces and ground adhesion remain native.

Torrent state uses the copied native clock, without extrapolating it with the
guest's separate clock. A missing publication keeps the last accepted mount
until its original 350 ms deadline; it never renews that deadline. Real
dismounts publish `mounted=false` and apply immediately. This keeps the riding
gait, saddle height and mounted camera bob suppression coherent across client,
server and native ticks. Movement diagnostics report mount sequence, age and
transition count to distinguish a lease dropout from terrain contact.

Subsystem implementations: [transport index](../PROTOCOL.md),
[movement](src/movement_driver.rs), [flight](src/player_flight.rs),
[damage](src/native_damage.rs), [colliders](src/native_colliders.rs)
and [camera](src/scene_camera.rs).
