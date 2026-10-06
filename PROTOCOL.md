# Local transports

Minecraft, the native host and the compositor exchange bounded publications
through local Windows mappings and shared GPU resources. The format definitions
below are the source of truth for their layouts, version numbers and validation.

| Transport | Responsibility | Definition |
| --- | --- | --- |
| MCPT | World, hand/HUD and avatar frame metadata; CPU fallback pixels | [Python decoder](passthrough/frames.py), [C++ frame header](compositor/include/frame_protocol.hpp) |
| ECGT | Shared GPU textures, adapter identity and synchronization fences | [GPU header](compositor/include/gpu_transport.hpp), [guest transport](minecraft/src/client/java/dev/eldencraft/bridge/client/GpuTransport.java) |
| ECHS | Host pose, input, health and clock | [Native publisher](native/src/host_pose.rs), [guest reader](minecraft/src/client/java/dev/eldencraft/bridge/client/HostState.java) |
| Combat | Native targets and Minecraft-resolved damage receipts | [Native wire](native/src/combat_wire.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/ProxyProtocol.java) |
| Healing | Item consumption and acknowledged regeneration | [Native wire](native/src/healing_wire.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/HealingProtocol.java) |
| Shared world | Terrain, blocks, entities and acknowledged events | [Native wire](native/src/world_wire.rs), [guest client](minecraft/src/client/java/dev/eldencraft/bridge/client/SharedWorldClient.java) |
| Block mesh | Baked geometry, atlas/lightmap identity and handoff | [Mesh header](compositor/include/block_protocol.hpp), [block details](compositor/include/block_details.hpp) |
| Scene camera | Host camera, projection and coordinate alignment | [Camera publisher](native/src/scene_camera.rs), [scene header](compositor/include/scene_protocol.hpp) |
| Chat | Ordered Unicode events and GUI ownership | [Native input](native/src/chat_input.rs), [guest protocol](minecraft/src/main/java/dev/eldencraft/bridge/ChatProtocol.java) |
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

`campaign-combat.json` publishes server-resolved armor, toughness, maximum HP,
stamina, shield readiness and item use. Native accepts a matching observation
for at most 250 ms. Incoming native HP damage uses the fixed configured
HP-to-Minecraft-unit conversion and vanilla armor formula. Guard events publish
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
`damage_events: [{seq,raw_damage,blocked}]`. Minecraft applies ordinary armor
or shield durability once per event and acknowledges `damage_ack`. A new
native session establishes a baseline instead of replaying earlier damage.

`experience_seq` and `experience_total` are cumulative confirmed lethal-hit XP
observations. Minecraft stores its checkpoint and awarded XP in the same player
save. New native sessions establish a baseline. These observations exclude
unconfirmed despawns; boss first-clear experience instead shares the boss receipt.

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
the same freshness rules as the elytra sample: a matching pid, session, epoch
and map, a recently published native frame, a nonregressing sequence and
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

## Verification

Python tests cover tools and frame decoding. Rust tests cover wire validation and
native ownership policies. The Minecraft build runs its Java conformance suites;
CTest covers compositor transport, geometry, shaders and projection. The
[release checklist](docs/building.md#release-checks) covers first-run installation
and combined gameplay.
