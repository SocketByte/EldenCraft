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

Combat proxies admit native or authored upright collision shapes up to 63.8 m
high and 31.25 m radius, bounded by Minecraft's 64 m entity extents including
padding. Nearby scans measure distance to the published body surface, so a
giant's distant physics origin does not hide an otherwise reachable body.
Melee publication intersects large boxes with its player-relative 64 m window;
a distant edge cannot invalidate the entire target frame. These remain upright
collision proxies, rather than per-bone weak-point hitboxes. Rejected shape
diagnostics include the instance and authored dimensions. Native encounter,
activity, phase protection, fresh identity, reach and cover checks still apply.
Registered human NPC bosses such as Gideon use `PlayerIns` and its verified
`CSPlayerDamageModule`, rather than `EnemyIns`. Their exact boss registration,
hostile team, loaded NPC parameters and normal readiness checks admit the hitbox
and native damage path. Unregistered player-shaped helpers do not qualify.
Unregistered enemies qualify with native character type 5 or the large scripted
enemy type 7. An authored boss body also qualifies through its exact current
health-owner registration, while class, hostile team, loaded NPC parameters and
readiness remain required. This admits scripted shared-health bodies without
classifying unrelated helpers from proximity or a matching model.
The [boss damage link inventory](src/boss_damage_links.rs) follows the native
encounter identities and event setup documented in
[enemy data](https://github.com/thefifthmatt/SoulsRandomizers/blob/master/diste/Base/enemy.txt)
and [event templates](https://github.com/thefifthmatt/SoulsRandomizers/blob/master/diste/Base/events.txt).
Fire Giant, Godfrey/Hoarah Loux, Beast Clergyman/Maliketh, Messmer and Consort
Radahn use continuous-health phase pairs. Accepted body loss is mirrored only
onto their exact dormant later-phase owner, never below 1 HP. An active boss's
invincibility, hit-disable state or bad delta-time sample grants no dormant
exception. Once the later phase activates, its normal native processor owns
lethal damage. Receipts log dormant transfer as `gauge_mirror`.
Godskin Duo, Dragonkin Soldier of Nokstella, Messmer's primary serpent,
Putrescent Knight's linked body and Scadutree Avatar's three phase slots use
registered active health controllers. Their bodies publish that current pool's
health to Minecraft and forward accepted damage with the native processor and
feedback, without applying encounter scaling twice. Where an authored immortal
body has reached its observed native 1 HP floor, the live controller accepts the
calculated hit instead; global/debug immortality grants no such supplement.
Controller loss counts toward melee and world-damage acknowledgements even if
the body itself could not lose HP. This path permits native lethal damage and
logs `referred_damage`; it never uses a dormant owner's 1 HP floor.
All transfers capture copied owner evidence before body feedback, subtract any
loss already forwarded natively, and recheck the exact registration, instance,
damage/HP modules, readiness, NPC identity and maximum HP afterward. Healing,
phase resets, ambiguous owners or changed evidence invalidate the transfer.
Rennala, God-Devouring Serpent/Rykard and Radagon/Elden Beast keep independent
phase health bars. Godrick and other moveset changes remain native; Malenia
keeps her same-actor health reset. No shared model alone establishes a health
link. Reaction is followed by a fresh identity/health check before damage
notification, so a retired actor or newly reset phase receives no old feedback.
The regressions exercise link isolation, dormant/active handoff, shared-pool
lethal feedback, immortality, health resets and retired-module cleanup using
native-call stand-ins. Actual cutscenes and victory flags still need in-game
verification.
Native camera tilt and knockback, and Minecraft's host hurt animation/sound,
treat continuous HP drain as one feedback episode. Each fresh HP sample still
updates the baseline, but later drain ticks do not restart the hurt animation or
reapply knockback. After 500 ms without another HP decrease, a new hit can start
feedback. Character changes, invalid samples and publication gaps rebase the
detectors without replaying missed damage; damage and healing remain independent
of this presentation policy.
The guest accepts the same player-relative +/-64 m coordinate window, with each
entity extent still limited to 64 m. Large native HP pools use a capped 1024 HP
Minecraft damage recipient; native health and each vanilla damage receipt keep
their configured scale. Pending melee and ranged loss is subtracted after the cap.

Raised shields resolve native damage sources through the current character and
projectile registries. A projectile and its owner may occupy different caller and
request source slots. Current owner identity must agree when both resolve;
projectile position, then its velocity at a coincident impact, supplies the front
half-plane test. Stationary coincident waves fall back to their verified owner.
Reaction vectors remain diagnostic only. The normal guard lease and server
stamina costs still apply.

## Interaction menus

The explicit `/unlockall` debug command unlocks every discoverable Site of Grace
and reveals all map fragments, including DLC rows, using the loaded
`BonfireWarpParam` and `WorldMapPieceParam` tables. It requires
the active offline campaign bridge and an unshared singleplayer Minecraft session;
the supplies-only `debugKits` setting does not hide this command. Native processing
checks the current character/session and request freshness, then sets only each
grace row's `eventflagId` discovery flag and map row's `openEventFlagId` reveal
condition, then reads them back before acknowledging success. Map opening flags
also expand the travel area. Boss-clear, quest, map acquisition animation and
grace text-condition flags are untouched.
The prefix layout follows the pinned SDK and
[Paramdex's grace definition](https://github.com/soulsmods/Paramdex/blob/master/ER/Defs/BonfireWarpParam.xml).

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
An offline grace reset can set the Lua proxy's `net_message` flag while the
bonfire begin/sitting flags remain valid. That proxy flag alone does not cancel
passive recovery; the offline session, live script, bonfire, load/exit state and
bounded same-player reset checks still apply.
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
