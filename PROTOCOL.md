# Local transports

Minecraft, the native host and the compositor exchange bounded publications
through local Windows mappings and shared GPU resources. The format definitions
below are the source of truth for their layouts, version numbers and validation.

| Transport | Responsibility | Definition |
| --- | --- | --- |
| MCPT | World, hand/HUD and avatar frame metadata; CPU fallback pixels | [Python decoder](passthrough/frames.py), [C++ frame header](compositor/include/frame_protocol.hpp) |
| ECGT | Shared GPU textures, adapter identity and synchronization fences | [GPU header](compositor/include/gpu_transport.hpp), [guest transport](minecraft/src/client/java/dev/eldencraft/bridge/client/GpuTransport.java) |
| ECHS | Host pose, input, health, clock and weather | [Native publisher](native/src/host_pose.rs), [guest reader](minecraft/src/client/java/dev/eldencraft/bridge/client/HostState.java) |
| Combat | Native targets and Minecraft-resolved damage receipts | [Native wire](native/src/combat_wire.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/ProxyProtocol.java) |
| Healing | Item consumption and acknowledged regeneration | [Native wire](native/src/healing_wire.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/HealingProtocol.java) |
| Shared world | Terrain, blocks, entities and acknowledged events | [Native wire](native/src/world_wire.rs), [guest client](minecraft/src/client/java/dev/eldencraft/bridge/client/SharedWorldClient.java) |
| Block mesh | Baked geometry, atlas/lightmap identity and handoff | [Mesh header](compositor/include/block_protocol.hpp), [block details](compositor/include/block_details.hpp) |
| Scene camera | Host camera, projection and coordinate alignment | [Camera publisher](native/src/scene_camera.rs), [scene header](compositor/include/scene_protocol.hpp) |
| Chat | Ordered Unicode events for chat and text screens (signs); GUI ownership | [Native input](native/src/chat_input.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/ChatProtocol.java) |
| ECNH | Shared Nether state and terrain layout | [Nether header](compositor/include/nether_protocol.hpp) |
| Campaign JSON | Native victories, capacities, wallet, merchant context and saved purchases | [Native runtime](native/src/campaign_runtime.rs), [guest bridge](minecraft/src/client/java/dev/eldencraft/bridge/client/CampaignBridge.java) |

## Ownership and validation

Readers copy and validate a publication before using it. Each transport carries
its own session identity, sequence and freshness rules. Seqlocks prevent readers
from accepting a partially written publication. Process exit, stale timestamps,
unknown flags and invalid bounds revoke the corresponding authority.

No game pointers cross the process boundary. Imported keys, camera changes and
native adapters release ownership when required state is unavailable. Restoration
checks the current object identity and the value previously written by the bridge.

Minecraft's integrated server owns item consumption, inventory, blocks, mobs and
vanilla action resolution. Elden Ring owns final movement/collision and host HP.
The bridge is limited to the supported executable and offline singleplayer.

Strict bounded JSON for configuration and world messages is implemented in
[JsonWire.java](minecraft/src/main/java/dev/eldencraft/bridge/JsonWire.java).
The [native guide](native/README.md) describes compatibility and diagnostics;
the [compositor guide](compositor/README.md) describes rendering.

ECGT v1 uses reserved host words at bytes 72/76 for the full-size plane mask and
host failure status, and guest byte 152 for its requested mask. Zero masks retain
legacy behavior (all five planes); mask 7 requests world color/depth and overlay,
and mask 31 also requests avatar color/depth. Unused planes are 1x1 shared textures.
Temporary ring saturation or resource transitions skip capture; permanent
unavailability permits the existing CPU fallback. A new guest PID resets the
shared fence, while adding avatar planes for the same guest reuses world resources.
When composition is gated, the host consumes and discards coherent publications
after their producer fence and all outstanding host copies complete. Discarded
publications cannot become fresh images or be copied after their acknowledgement;
an acknowledgement never covers a future frame that has not been published.

## Campaign JSON

Both games load the same startup configuration described in
[campaign configuration](docs/campaign-configuration.md). Publications use UTF-8
JSON files under `ELDENCRAFT_CAMPAIGN_DIR`, bounded to 128 KiB and replaced
atomically after flushing. They contain values and opaque identities, never
game pointers. Native object access remains on the game thread; its worker
handles files, transaction intents and save witnesses.

`campaign-host.json` carries `version: 1`, process `pid`, campaign `session`,
monotonic `seq`, epoch `timestamp_ms`, `active`, `character`, `runes`, current
and maximum `hp`/`stamina`, unique `defeated` IDs, optional `merchant` and `ack`.
The Minecraft reader cross-checks the ECHS publisher PID and rejects regressing
sequences, impossible capacities and stale or dead publications. A merchant
contains `{id,name,token}`; a purchase acknowledgement contains
`{id,status,amount}`. A context token changes for each native Purchase command.
Optional `dead` explicitly identifies verified native death; ordinary inactive
snapshots cannot trigger death recovery. Character transitions renew the session.
Optional `identity_ready` verifies that the current healthy offline player's save
identity still matches `character`, including while resting suspends gameplay
tasks. It grants only Ender Chest ownership alongside a fresh matching enabled
grace action; inactive snapshots still grant no campaign gameplay or purchases.
Absent metadata is accepted for identity only on active, living snapshots.

Optional `save_load` is a nonnegative presentation sequence. It advances when a
healthy offline save is admitted after an observed unloaded title state, so
reopening the same character shows the multipage guide again. Focus loss, death,
grace rests and map warps retain it. Readers accept absent metadata as zero;
this sequence does not change campaign sessions, claims or purchase journals.

Optional `bosses_active` contains `{id,name,hp,max_hp}` records for the native
frontend manager's registered boss encounters (up to three slots). `id` is an
opaque encounter instance/phase identity, `name` is localized text, and HP values
come directly from the current native actor. Encounter registration is independent
of frontend update/visibility flags; frontend tags are matched by source, not slot
position, and can supply a cached localized name. The pinned message repository
also supplies NPC-name FMG text when render-side names are unavailable.
Dead, stale or unresolved registrations are omitted. Empty or
missing lists clear the guest's native boss HUD; the field is backward compatible
with older host snapshots. Minecraft validates unique IDs, bounded nonempty names
and `0 <= hp <= max_hp` with positive maximum HP before rendering. A new identity
or maximum HP resets the yellow damage trail.

`campaign-boss-debug.json` separately records bounded native boss sampling
diagnostics at most once per second. It preserves recent encounter observations
for investigation after focus loss or game exit; it carries no gameplay authority.

`campaign-guest.json` uses the same version/session/character/timestamp envelope.
Purchase requests add `{id,action:"purchase",merchant,offer,quantity,amount}`.
Native validates the configured price, gates, stock, character and current
merchant before touching the actual rune wallet. Replays first resolve the UUID
in the saved journal. `close_shop` requires the current merchant token.

`campaign-combat.json` publishes server-resolved armor reduction, maximum HP,
stamina, shield readiness and item use. Native accepts a matching observation
for at most 250 ms. Incoming native HP damage uses the fixed configured
HP-to-Minecraft-unit conversion and `damage * (1 - armor / 100)`. `armor` is
the additive percentage-point total of intact, correctly equipped configured
pieces, capped at 100; inventory and hand-held armor do not count. The legacy
`toughness` field remains accepted but does not affect mitigation. Guard events publish
cumulative `guard_seq` and raw `guard_damage`; Minecraft acknowledges only
consumed values. Native subtracts unacknowledged expenditure when authorizing
the next block. Missing or stale campaign state cannot enable legacy blocking.

Optional `boss_hud_ids` and `boss_hud_timestamp_ms` acknowledge up to three exact
native boss identities actually drawn by Minecraft. Native suppresses only the
corresponding frontend tags while both the draw and combat publication are fresh
within 250 ms and match the current campaign session/character. A changed source,
missing receipt, hidden guest HUD or closed bridge releases visibility ownership.
Registration, names and health remain available for future boss publications.

Native attack events additionally publish up to 64 increasing
`damage_events: [{seq,raw_damage,blocked,absorbed?,totem?}]`. Minecraft applies
ordinary armor or shield durability once per event and acknowledges `damage_ack`.
A new native session establishes a baseline instead of replaying earlier damage.

The combat publication also carries `absorption` (Minecraft absorption hearts,
in Minecraft health units) and `totems` (death-protection items in the paired
survival player's two hands). After armor, an open native hit first spends
absorption, less what events after `damage_ack` already spent; the event's
`absorbed` records it and Minecraft removes that much when applying the event.
If the remainder would leave the player at or below zero HP while an unspent
totem is held, native instead leaves one Minecraft health point
(`max_hp / guest_max_hp`) and marks the event `totem:true`. Self-inflicted
native damage (Elden Ring's own hazards) bypasses armor and absorption but is
caught the same way, recorded with `raw_damage:0`. Minecraft spends the totem
with vanilla's own death protection (effects, statistic, advancement and
animation); the regeneration it grants reaches native HP through
`campaign-healing.json`. Blocked events never spend absorption or a totem, and
both fields are optional for older hosts. Minecraft hazards already resolved by
the integrated server use vanilla's totem and absorption directly.

`experience_seq` and `experience_total` are cumulative confirmed lethal-hit XP
observations. Minecraft stores its checkpoint and awarded XP in the same player
save. New native sessions establish a baseline. These observations exclude
unconfirmed despawns; boss first-clear experience instead shares the boss receipt.

`loot_seq` counts confirmed lethal hits on ordinary enemies; targets classified
as bosses before the hit (registered boss health bar or NpcParam boss rune
award) are excluded. `loot_events` repeats up to 64 of the latest
`{seq,max_hp}` kills, strictly increasing and never beyond `loot_seq`. While the
shared world is live, each also carries `map` and `position`: the enemy's death
point in that anchor map's stable region frame, where floor drops land.
Minecraft rolls the configured drop table once per kill after its saved cursor,
seeded by character, session and sequence, and saves the cursor with the items.
A new native session establishes a baseline; kills evicted from the list before
observation are forfeited. Both fields are optional for older hosts.

`campaign-healing.json` carries cumulative `heal_seq` and `heal_total` from
actual server food healing. A fresh zero baseline precedes the first pulse;
native consumes only subsequent increases for the current character and
session. This channel excludes the separate acknowledged golden-apple system.

Purchase recovery spans two saves. Native persists an intent before debit and
requires a native save witness before returning `debited`. Minecraft records
the UUID with delivered inventory, flushes the integrated world/player save,
reads back the receipt, then commits its shop ledger. An uncertain native save
is quarantined rather than retried as a fresh debit. Restoring mismatched save
generations or deleting the journals falls outside this recovery contract.

## Text screens

MCPT's descriptor word at +104 is 0 without a Minecraft screen, 1 with one, and
2 when that screen takes typed text (a sign editor). On 2, native attaches the
ordered chat channel to it with event kind 5 instead of opening chat (kinds 1
and 2). The session starts with that event and ends when the screen closes;
characters and editing keys then go to the screen, and ECHS keyboard input is
not applied to it.

## Weather

ECHS v2's extension word at byte 160 carries `RUNES_VALID` (1, balance at 164)
and `WEATHER_VALID` (2, weather at 168: 0 clear, 1 rain, 2 thunder). Unset
fields and bytes 172 onward are zero; readers reject anything else. Native reads
the `WorldAreaWeather` singleton exactly as the game's consumer at RVA 0x6a32e3
does, after fingerprinting both reads. IDs 20, 21, 40, 41 and 52 (rain, heavy
rain, snow, heavy snow, heavy fog rain) become rain; 30 and 31 (storms) become
thunder; every other known ID is clear. Minecraft re-asserts the matching server
weather each second while fresh, and leaves its own cycle alone otherwise.

## Interaction JSON and navigation

The same campaign directory contains `interaction-host.json`,
`interaction-guest.json` and `interaction-ui.json`. Each version-1 envelope carries
the host `pid`, a separate interaction `session`, increasing `seq` and epoch
`timestamp_ms`. The guest checks the ECHS publisher PID and rejects observations
older than 500 ms. The native side requires a matching identity, new sequence and
request age of at most 500 ms, with a 100 ms future-clock allowance. Host files
are bounded to 128 KiB by the guest and commands to 64 KiB by native.

The host publishes `active`, `blocking`, optional `prompt`, `menu`, `subtitle`
and `input`. A prompt is `{token,text_id,text,enabled}`. A menu is
`{token,kind,title,choices:[{id,text,enabled,action?}]}`, where `kind` is `npc`, `grace` or
`dialog`; there are at most 64 unique actual native rows. Tokens bind selection
to the current live menu. Text is bounded, localized and stripped of native
display markup. The optional choice action `ender_chest` is valid only in grace
menus. It opens the paired Minecraft player's persistent vanilla Ender Chest;
it sends no native selection and keeps the grace result context open underneath.
Merchant Purchase rows (text 20000010) are published as `Shop` with their
original row ID; Sell rows (20000011) are omitted. A subtitle contains `{text}`.

`interaction-ui.json` carries `action:"ui_state"`, `token:0`, `ready` and `open`
every 100 ms. Its separate mailbox prevents a heartbeat from overwriting a
choice request. `interaction-guest.json` carries `select` plus `choice`, `close`
or `interact` with the current token. Unknown fields/actions, stale commands,
disabled rows and context mismatches cannot execute a game action. Selecting a
choice returns the original ESD result.

The optional `eldencraft_menu_input(buttons,x,y,wheel)` core/loader export keeps
the existing ECHS gameplay buttons unchanged. The 12 menu bits are confirm, cancel,
up, down, left, right, map, tab, zoom-in, zoom-out, shift and home; map, zoom
and home are reserved and unused by the guest. The mailbox
retains short discrete presses for 80 ms and expires after 250 ms. Published
`input:{seq,buttons,pressed}` is deduplicated by the guest with rising-held-bit
fallback. Mouse clicks, cursor and wheel retain the existing ECHS screen path.

The world map is Elden Ring's own. Native opens it for M by holding the native
Map binding's digital input for 120 ms, or follows the player's own Map binding
press. While it is open the host publishes no gameplay state, exactly as for a
blocking native menu, so composition, ECHS input and native input reservations
are released. It is closed after M, Escape or the Map binding is pressed and
released for 250 ms, or immediately once the player moves or a load begins.

## Creative, fluid and climb travel

The shared-world guest's optional `flight` sample is
`{sequence,time_ms,observed_frame,gliding,travel,velocity}`. `travel` is `none`
(the default when omitted), `creative`, `water`, `lava` or `climb`; it is mutually exclusive
with `gliding:true`. Velocity is in metres per second. Non-gliding travel vectors are
bounded to a combined 30 m/s, and glides retain the 120 m/s bound. An inactive
sample has a zero vector. All modes use the same pid/session/epoch/map binding,
recent native-frame witness, immutable sequence and 250 ms expiry (the guest's
read, server tick and publication already take up to about 130 ms of it). A
repeated read does not extend that expiry. Both components must be updated
together to use the additional travel modes; existing glide samples remain accepted.

Creative flight uses the player's genuine `mayfly`/`flying` abilities. Two fresh
jump presses within seven Minecraft ticks toggle flight; held input and
reacquisition cannot toggle it. Space ascends, Shift descends and vanilla
Player.travel supplies acceleration and drag. Native collision still applies,
and the server ends flight on a touchdown after observing takeoff. A creative
sample with zero velocity remains active so stationary flight can hover.

The guest counts an elytra press only for a fresh jump edge seen in a rendered
frame whose host pose, and the one before it, were airborne: the press that
starts a native jump has left the ground before any server tick sees it. Native
blends the first 150 ms of a glide from its own preceding velocity, ends a glide
itself after two grounded, descending frames (ignoring the server's still-arriving
glide until it stops, or for at most 400 ms), and hands up to 8 m/s of the
glide's horizontal momentum to ordinary friction when a glide ends.

Minecraft runs genuine integrated-server travel and fluid/climb checks. Only
the paired stand-in's position integration is deferred to native collision;
the sample records the requested pre-collision travel vector for fluid/climb
motion. Fresh physical controls supply jump, sneak and movement. Wall feedback
comes from advancing native positions, never a stale stand-in collision flag.
No sample can drive an Elden Ring ladder: the native ladder state releases
movement and jump/dodge capture, and R uses the game's ordinary action latch.
The host scene's optional `native_ladder` boolean (default false) also releases
the guest stand-in's travel and prevents an elytra opening during native climbing.
Fresh fluid/climb motion cancels native fall consequences while that lease is
active; unlike an elytra glide, it grants no post-travel airborne immunity.

Optional guest `fluids` contains at most 64 unique enemy contacts:
`{id,generation,medium,position,time_ms,observed_frame}`, where `medium` is
`water` or `lava`. The integrated server samples real fluid contact after
synchronizing each native proxy. Native admission requires an active paired
session, ready terrain, a native frame observed within 150 ms, and matching
live/historical target identity and position. The native stage also compares
its current owner and handle with the admitted local identity token. Only
horizontal movement is scaled (water 0.5, lava 0.25). Empty contacts, expiry or
loss revoke this effect; no native dimension or velocity field is retained.

`environment` damage receipts can target the paired player or a current native
enemy proxy. They still originate in actual server `hurtServer` health loss,
require paired-session provenance and match the target's historical/current
generation and position. Scoped block effects run despite stand-in `noPhysics`;
collision damage remains excluded. Water damages through vanilla drowning,
while lava/fire uses vanilla hazard cadence and equipment/effect rules.

## Torrent

ECHS button bit 22 is Y, the Torrent whistle; the compositor, native fallback
capture and guest reader all accept exactly 23 gameplay bits. Older components
reject bit 22, so all three must be updated together. The guest consumes
whistle presses only during gameplay without a screen, as fresh-input edges on
the integrated server.

The integrated server owns the summon rules, the real vanilla horse and its
health. The shared-world guest publication carries an optional
`torrent:{sequence,time_ms,observed_frame,mounted}` beside `flight`. It is
published only while the paired player's kinematics are active and the mount
was synchronized with the host during that server tick. Native accepts it under
the elytra sample's rules, with its own 150 ms window: a matching pid, session,
epoch and map, a recently published native frame, a nonregressing sequence and
150 ms of freshness. A repeated envelope never renews a mount. A missing,
stale or unmounted sample returns movement to the on-foot gait at the next
physics stage. A glide always takes precedence over the mount.

While mounted, the native movement model uses Torrent's gait instead of the
vanilla player's: a 0.25 blocks/tick gallop, a 1.5x held dash in any
direction, 12 m/s jumps with one air jump, and no crouch, item-use or
knockback effects. The camera eye rises by the 0.84375 m saddle height, which
the guest also uses to seat the avatar. Native collision remains
authoritative. Native hit damage stays on the host's HP; the guest applies the
same fraction of it to Torrent.

## Block mesh

Minecraft publishes every real placed block in its render area as one baked mesh,
drawn by the compositor in Elden Ring's frame with the host camera. The captured
RGB-D frame omits vanilla chunk geometry only while an acknowledged mesh revision
covers the whole vanilla view, so blocks are never drawn twice or not at all.

The mesh window is the vanilla render area plus a two-chunk streaming margin at
every render distance up to 32 chunks (at most 131072 section palette checks per
complete build). Crossing chunk borders rebuilds only the entering strip while the
displayed revision stays. Edits, chunk streaming and reloaded chunk objects rebuild
in place; only a lost context or a view beyond the prebuilt border (fast travel)
returns the world to RGB-D. Each geometry change is timed until a displayed
revision contains it, and one left undisplayed for 1 s revokes the mesh. Chunks
holding only air and shadow terrain (which neither render nor occlude) are not
geometry changes.

Fluids are tessellated by vanilla's `FluidRenderer` into the mesh (water in the
translucent pass, lava in the solid one). A mesh holds at most 2097152 vertices in
a 64 MiB mapping; there is no separate block count limit.

Mesh v1 accepts legacy 24-byte prelit vertices and 28-byte raw vertices. Both have
position at byte 0, UV at byte 12 and color at byte 20; raw vertices retain ARGB
color and append packed light UVs at byte 24. `Local\\EldenCraftBlockLight` uses
the same 128-byte header with magic `0x4c4d4345` (ECML), followed by 1024 bytes of
RGBA8 lightmap pixels. Its count/stride are 16/16, identity and atlas revision must
match the mesh, and its independent revision changes only with the lightmap.
Lighting is applied per vertex with the original bilinear sampling and rounding.

The atlas header's word at byte 104 is its mip level count (0 or 1 for one level,
at most `floor(log2(max(w,h))) + 1` and 13). The payload is the whole chain,
tightly packed RGBA8, largest level first, at most 90 MiB. Mesh and ACK headers
keep bytes 104..127 zero. The compositor samples nearest texels with linear
filtering between levels, as vanilla does. Mining cracks and outlines keep their
original 8 MiB / 64 MiB mappings and a single level.

`Local\EldenCraftBlockAnim` (magic `0x4e414345`, 4 MiB) carries the current frame
of every animated sprite the mesh uses, republished each game tick. Its header
uses the mesh identity; `revision` (byte 56) is the animation tick and
`atlas_revision` (byte 64) the atlas it updates; `count` is the region count (at
most 4096) and `stride` is zero. The payload starts with `count` 16-byte records
`{u16 x, u16 y, u16 width, u16 height, u8 mip, 3 zero bytes, u32 offset}` followed
by RGBA8 pixels; each region lies inside its atlas level, and its pixels lie after
the table, 4-byte aligned, inside the payload. Regions cover the padded sprite
rectangle with edge-clamped padding. The compositor copies them into the atlas it
is about to sample. A sprite whose frames cannot be read stays live in the RGB-D
scene instead.

## Scene camera history

The native camera export keeps the last eight main-camera submissions. Each
packet's frame word (byte 16) is the submission sequence. Given room for one
packet the export returns the newest; given room for more it writes up to eight
fresh packets, newest first, and returns their count. Elden Ring 2.7.1.0 presents
the image rendered with the submission before the newest: at Present the hook
already holds the next frame's camera. The compositor draws native blocks, the
RGB-D reprojection and the Nether with that earlier camera (`EcCameraLatency`,
default 1, verified in play). On top of it, it tracks how many submissions
arrived since the previous Present (each Present shows exactly one) and adds any
extra lead. More than one submission per Present on average, or a lead that never
returns to zero for two seconds, adds nothing. `EcCameraAutoPacing` disables the
measured part; the 5-second scene log reports the chosen offsets.

Shared region XYZ keep their gameplay coordinates. The host screen basis has
opposite horizontal handedness to vanilla Minecraft, so captured world paintings
reflect their front sprite U coordinates across the whole image. World text
reflects each line around its center and reverses emitted quad winding, retaining
vanilla glyph UVs, colors, lighting and depth. These corrections run only during
the world pass, which ends after world capture and before avatar, hand and GUI
rendering. Scene projection metadata and world geometry retain their alignment.

## Placed-block colliders

The shared-world guest publishes the collision boxes of real placed blocks around
the player and the point they will reach in 0.75 s (12 blocks around both, 8 below
and 12 above, snapped to 4-block steps), merged into maximal exact boxes and
capped at the 4096 nearest. Its `blocks` entries carry up to 64 merged boxes each,
keyed `merged:<n>`; native code uses only their geometry. Native creates missing
bodies nearest first (up to 96 or 2 ms per task) and destroys stale bodies only
after every replacement exists, unless the 4096-body table has no room.

The movement model receives the installed boxes within 4 m of the feet each
PostPhysics. While grounded, a stride that runs into a placed step no higher than
0.6 m (1 m on Torrent) with body headroom above it starts an owned hop over it,
like vanilla step-up; walls, ceilings and Elden Ring terrain are unaffected.

## Verification

Python tests cover tools and frame decoding. Rust tests cover wire validation and
native ownership policies. The Minecraft build runs its Java conformance suites;
CTest covers compositor transport, geometry, shaders and projection. The
[release checklist](docs/building.md#release-checks) covers first-run installation
and combined gameplay.
