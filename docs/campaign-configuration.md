# Campaign configuration

EldenCraft's offline campaign uses vanilla Minecraft items and attacks, with a stamina bar, boss rewards and shops paid from the Elden Ring character's rune wallet. Native leveling and respec menus are suppressed while the campaign is enabled. Enchantments are excluded from the supplied balance and rewards.

## Files and launching

The source default is [`config/campaign.json`](../config/campaign.json). The Windows release installs an editable copy at `%LOCALAPPDATA%/EldenCraft/campaign.json` on first setup. Subsequent setup and upgrades preserve that copy. Restart both games after editing it.

Both processes must load the same file and use the same bridge directory:

| Setting | Purpose |
| --- | --- |
| `ELDENCRAFT_CAMPAIGN_CONFIG` | Absolute path to the campaign JSON |
| `ELDENCRAFT_CAMPAIGN_DIR` | Shared directory for campaign messages and the native transaction journal |
| `ELDENCRAFT_DATA_DIR` | Existing bridge data directory; campaign messages default to its `campaign` subdirectory |

The release launcher sets these automatically. Developer deployment writes `config/eldencraft-campaign-link.json` inside the dedicated Minecraft profile so a separately launched Modrinth instance reads the same paths. A standalone Fabric run creates `config/eldencraft-campaign.json` from the mod's bundled defaults; connect that path to the native host before playing.

Keep the Minecraft world, EldenCraft native save, campaign configuration and transaction journals together when making a backup. The release launcher creates verified campaign-journal backups alongside its save backups before launch or setup. Each Minecraft player is paired with one native character. A different character cannot use that player's campaign equipment or shop ledger. Use a separate Minecraft world for another native character.

Start a new campaign with a fresh native character and Minecraft world. Existing sandbox inventories are preserved, and already completed bosses qualify for their configured rewards and capacities. Updating the mod does not turn an advanced sandbox save into an opening wooden-equipment playthrough.

Invalid configuration is rejected. Keep the JSON below 128 KiB, use vanilla `minecraft:` item identifiers and give every boss and shop offer a unique ID. The number of remembrance entries must equal `progression.shares`. Configuration is loaded at startup; editing a running session does not change half of a purchase or combat exchange.

## Balance

`weapons` maps item IDs to fully charged base damage and attack speed. Damage includes the player's base attack point. These become Minecraft item attributes before ordinary attack cooldown and critical-hit calculations. Durability and other vanilla item properties remain in use.

| Material | Sword damage | Axe damage | Sword speed | Axe speed |
| --- | ---: | ---: | ---: | ---: |
| Wood | 4 | 7 | 1.6 | 0.8 |
| Stone | 5 | 8 | 1.6 | 0.8 |
| Copper | 6 | 10 | 1.6 | 0.8 |
| Iron | 9 | 15 | 1.6 | 0.9 |
| Diamond | 13 | 21 | 1.6 | 1.0 |
| Netherite | 18 | 29 | 1.6 | 1.0 |

`combat.nativeDamageScale` converts the resolved Minecraft hit into native HP damage. The supplied value is 15: a full netherite sword hit becomes 270 native HP before any existing native damage-path behavior. `combat.bowBaseDamage` defaults to vanilla's 2 and `combat.crossbowBaseDamage` defaults to 4. Both retain ordinary arrow velocity and critical calculations. These are initial tuning values, requiring live boss playtests before claiming equivalence to a specific level-100 Elden Ring build.

Optional `combat.nativeEnemyDamageMultipliers` maps verified native NPC parameter IDs, as JSON keys, to incoming Minecraft damage multipliers from 0.01 to 1000. For example, `{ "123456": 2 }` doubles damage received by that NPC parameter row; this example ID is illustrative. Find actual IDs in native melee/target diagnostics for the supported game. The default map is empty. This supports tuning encounters such as Rykard, whose original HP budget assumes a special native weapon, without introducing a custom Minecraft item. The native sink bounds the final hit to its safe damage range.

`armors` maps vanilla equipment IDs to `{ "armor": 8, "toughness": 3, "knockbackResistance": 0.1 }`. The defaults reproduce vanilla armor attributes for 29 pieces, including copper and netherite. Slot-specific attributes let pieces stack normally. Minecraft armor and toughness are applied to incoming native damage using vanilla's formula; `combat.nativeIncomingDamageScale` defaults to 1 and adjusts overall pressure. Native incoming damage retains the game's existing resolution before the Minecraft armor pass.

This is a remaining combat limitation: native armor and stat defenses are not neutralized, so native class/loadout can add mitigation before Minecraft armor. The supported SDK has no verified authoritative defense override. `nativeIncomingDamageScale` can adjust overall pressure but cannot make differing native loadouts equivalent. A fresh Wretch with native armor kept unequipped provides a consistent starting baseline for campaign testing. Pure Minecraft-only incoming mitigation requires further verified native integration.

Guard expenditure uses raw incoming damage in fixed Minecraft HP units, before armor. Vigor growth therefore does not reduce the stamina cost of blocking the same attack. Individual native hits also wear Minecraft armor or the raised shield using vanilla durability rules. Ordinary food regeneration is forwarded to native HP; the existing golden-apple regeneration path remains separate.

## Stamina

| JSON field | Default | Meaning |
| --- | ---: | --- |
| `stamina.costs.sword` | 12 | Each sword attack, including a miss |
| `stamina.costs.axe` | 24 | Each axe attack |
| `stamina.costs.bow` | 24 | A released arrow |
| `stamina.costs.crossbow` | 24 | A fired shot, once per volley |
| `stamina.costs.other` | 12 | Other melee attacks |
| `stamina.regenPerSecond` | 20 | Recovery per second |
| `stamina.regenDelaySeconds` | 0.5 | Delay after expenditure |
| `stamina.guardBase` | 4 | Cost per blocked hit |
| `stamina.guardPerDamage` | 1.5 | Additional cost per raw incoming Minecraft HP |
| `stamina.guardRecovery` | 5 | Balance needed to raise an exhausted guard again |

Holding a shield or drawing/loading a ranged weapon pauses regeneration. Insufficient stamina prevents a new attack or shot. A shield hit costs `guardBase + guardPerDamage` per raw damage. If the remaining balance covers it, the hit is fully blocked. Otherwise stamina absorbs only the share it pays for, drops to zero, and the rest of the hit goes through as ordinary damage after armor. Either way, emptying stamina with a block breaks the guard. The shield is knocked down with vanilla's shield-break sound and a hotbar cooldown lasting until stamina reaches `guardRecovery` again, and the stamina bar shakes, flashes and shows "Guard broken". While a shield is held, a small shield icon beside the crosshair shows its state in the stamina colors: a faint outline when available, filling during vanilla's 0.25 s raise delay, solid with a short flash once it blocks, and the exhausted color refilling while a broken guard recovers. Native guard events include blocked hits with zero HP loss. Sprinting, jumping, normal Minecraft movement, mining and placing blocks do not spend this stamina; the swings of holding attack on a block (Minecraft or Elden Ring terrain) are never charged as missed attacks.

Shield activation retains vanilla's raise delay and frontal blocking rule. The client reports its actual raised shield; the integrated server separately verifies a held, usable blocking item and available stamina. The server's later copy of the raise timer does not impose a second delay on an already admitted client guard. A brief combat mailbox read miss preserves an already raised shield within the last observation's freshness window; reconnecting while the shield is raised accepts that current held state without replaying attacks. Explicit release, menus, expired observations, item cooldowns and exhausted stamina still prevent blocking. Publishing fresh shield and campaign state uses short state locks so a simultaneous update cannot itself let a hit through.

During active Minecraft gameplay, native weapon attacks, guard, skills and item use are reserved by the bridge. The native Use Item binding, normally R, cannot trigger a flask. These changes use Elden Ring's existing logical bindings and restore them when Minecraft gameplay capture ends.

Verified native death refills stamina for the next attempt. Merely switching windows or losing the bridge does not refill it.

## HUD and popups

Top-level `hud` controls presentation independently of combat costs and capacities. Existing configuration files without this section use the same defaults. All dimensions and positions use Minecraft GUI pixels; Minecraft's GUI scale controls their on-screen size. Restart Minecraft after editing.

The stamina bar has a pixel frame, green recovery shading, an amber exhausted state and a short trailing segment after expenditure. It sits above the vanilla health/armor rows, accounting for additional health and absorption hearts. The native boss bars sit at the top center, use Minecraft boss textures and localized Elden Ring names, and show a delayed yellow damage trail behind current red HP. They follow the native game's three registered boss encounters, including simultaneous fights and phase changes, and read current actor HP independently of the hidden native HUD's render state. Names come from the native message repository through its language and archive tables; an unavailable name uses the label "Boss" while keeping the verified health bar visible. Bars disappear when registration clears, the actor dies or unloads, or the bridge becomes inactive. Ordinary Minecraft boss bars retain their own rows. Native rows that cannot fit above the lower HUD are omitted; reduce GUI scale if an unusually small viewport has insufficient room.

| JSON field | Default | Meaning |
| --- | ---: | --- |
| `hud.stamina.enabled` | `true` | Show the stamina bar |
| `hud.stamina.width`, `height` | 182, 9 | Outer frame dimensions |
| `hud.stamina.offsetY` | 0 | Shift vertically from the health-aware position |
| `hud.stamina.showNumbers` | `true` | Show current / maximum stamina above the bar |
| `hud.stamina.trailHoldSeconds` | 0.12 | Pause before the drain trail catches up |
| `hud.stamina.trailPerSecond` | 1.75 | Trail movement in fractions of a full bar per second |
| `hud.stamina.recoveryHighlightSeconds` | 0.12 | Keep recovery shading steady between stamina updates; 0 disables it |
| `hud.bossBar.enabled` | `true` | Show native boss bars |
| `hud.bossBar.width`, `height` | 320, 10 | Outer frame dimensions |
| `hud.bossBar.top` | 12 | Top of the first boss name |
| `hud.bossBar.spacing` | 8 | Gap between boss rows |
| `hud.bossBar.showNumbers` | `false` | Include current / maximum native HP beside the name |
| `hud.bossBar.damageHoldSeconds` | 0.6 | Keep yellow damage visible before it shrinks |
| `hud.bossBar.damageTrailPerSecond` | 0.35 | Yellow trail movement in fractions of a full bar per second |
| `hud.hideAchievementPopups` | `true` | Suppress Minecraft advancement/achievement toasts in EldenCraft |
| `hud.hideRecipePopups` | `true` | Suppress recipe unlock toasts in EldenCraft |
| `hud.hideTutorialPopups` | `true` | Suppress movement, mining and other tutorial cards in EldenCraft |

Stamina colors are `fill`, `exhausted`, `trail`, `border`, `background` and `text`. Boss colors are `fillTint`, `trailTint`, `border`, `background` and `text`; the health tint multiplies Minecraft's white boss texture, while the trail tint multiplies its yellow texture. The default health tint is deep red. Colors accept `#RRGGBB` or `#AARRGGBB`. Widths shrink to fit the viewport. Damage trails reset after healing, phase/instance changes and stale observations rather than carrying old damage into a new fight.

Popup suppression clears queued and already-visible cards, including an indefinitely displayed movement tutorial. It preserves actual advancements, XP, recipe unlocks and tutorial progress without changing Minecraft's global tutorial setting. System alerts remain available. These flags control Minecraft toasts; Steam's external achievement overlay uses Steam's own settings.

Boss diagnostics are saved beside the campaign observations as `campaign-boss-debug.json`. The release location is `%LOCALAPPDATA%/EldenCraft/runtime/data/campaign/campaign-boss-debug.json`. This bounded trace records registered slots, current source HP, frontend tag state, name lookup status and admission reasons; recent encounter data remains available after closing the game. Failed name lookups include archive header and count diagnostics, and a fallback label is identified as `name_source: "fallback"`. When investigating a missing bar, check the active `campaign-host.json` list and this trace while using the same installed Minecraft/native release.

Native incoming hits also produce `Minecraft shield hit:` entries in `eldencraft-native.log`. These include the block decision, attacker position and direction, shield facing, guard lease age, campaign state age, stamina and resulting HP loss. The hook collects a bounded queue and the existing worker writes the log outside the damage callback. These observations help distinguish a dropped guard from a rear attack, stale state or previously spent stamina awaiting acknowledgment.

## Remembrance progression

The 15 supplied remembrance bosses each contribute one unique share. A victory advances the equivalent attributes proportionally:

```text
fraction = min(unique_remembrance_victories, shares) / shares
vigor = startVigor + fraction * (endVigor - startVigor)
endurance = startEndurance + fraction * (endEndurance - startEndurance)
Minecraft maximum HP = baseMinecraftHealth * HP(vigor) / HP(startVigor)
maximum stamina = Stamina(endurance)
```

`healthCurve` and `staminaCurve` are editable ordered arrays of `{ "level": ..., "value": ... }` anchors. Values interpolate between anchors. The default starts at equivalent Vigor 10 / Endurance 10 and ends at Vigor 60 / Endurance 30: 1900 native HP, approximately 91.79 Minecraft HP and 90 stamina. The supplied stamina curve is campaign tuning, starting at 50 and ending at 90; its values can be changed independently of the Endurance labels. Native attribute levels, damage stats and equip load are not awarded. Capacity growth preserves current HP rather than healing it.

Boss state comes from native completion event flags, not remembrance inventory items. The native journal retains unique victories across sessions and NG+ so neither duplicated remembrances nor repeated clears award more capacity. Optional bosses increase capacity; completing the main route does not require every optional remembrance.

The HP anchors are corroborated by [Bandai Namco's attribute explanation](https://www.bandainamcoent.com/news/elden-ring-introduction-part-2-advanced-stats). Native event flags were checked against the [authored modding flag reference](https://soulsmodding.com/doku.php?id=er-refmat:event-flag-list) and [ER Documentation](https://github.com/vawser/ER-Documentation/blob/main/Info%20-%20Event%20Flags%20-%20Gameplay.txt).

## Rewards and gathering

`starterItems` and each boss's `rewards` are arrays of `{ "item": "minecraft:iron_sword", "count": 1 }`. Set a boss's `eventFlag` to its final native completion flag and `remembrance` to whether it contributes a capacity share. Optional `experience` awards Minecraft XP once with that boss's first-clear receipt (0–1,000,000). The defaults include 159 encounters; ordinary and alternate variants do not count as remembrances.

The main route grants copper after Margit, iron after Godrick, diamond through the middle campaign and netherite from Fire Giant onward. Optional bosses supply region-appropriate equipment and maintenance materials. Wooden equipment is available before Margit. Crafting access and raw logs are gated so ordinary stone gathering does not immediately bypass the opening gear tier.

Rewards wait when the complete batch cannot fit in the inventory. Claims are saved with the items in the Minecraft player save, preventing a separate claims file from disagreeing with inventory. Removing a boss entry does not remove previously granted equipment. Changing an existing reward ID does not retroactively grant that reward again.

`mining.allowedBlocks` controls ordinary native-terrain gathering. It defaults to stone/cobblestone, bricks and dirt variants. Wood, iron ore and other progression materials are excluded. `mining.regrowTicks` sets the delay before a sampled cell can yield another item; 12000 ticks is ten minutes at 20 TPS. Mining supplies an item without deleting Elden Ring's terrain.

`mining.resourceZones` can override a hit material in a particular native map. The defaults map ore surfaces in eight base-game tunnels to lapis ore. Each rule contains a packed `sourceMap`, `hitMaterials` and vanilla `block` ID. Map bytes are `(area << 24) | (block << 16) | (region << 8) | index`; for example `m32_01_00_00` is 536936448. Only matched ore surfaces gain this exception, and ordinary Minecraft tool/drop rules still apply. The future enchantment economy can use this lapis without changing the current baseline damage balance.

Confirmed lethal Minecraft melee or ranged hits award native-enemy XP using top-level `experience`: `mobBase` plus `mobPerNativeHp` times the target's maximum HP, capped by `maxPerKill`. Defaults are 3, 0.005 and 100. Boss first-clear XP is additional. Cumulative native kill receipts and a checkpoint in the Minecraft player save prevent replaying observed kills as repeated XP. A new native session starts a fresh observation baseline; target disappearance alone does not count as a kill.

Lapis ore also follows vanilla mining XP rules. Merchants sell an enchanting table, books and bookshelves after early major victories. Vanilla enchanting remains available, but the supplied weapon and boss balance does not assume enchantments.

## Shops

The merchant's Purchase row is shown as **Shop** and its native Purchase command opens the custom Minecraft screen; the native Sell row is hidden. Talk and quest dialogue continue through Elden Ring. The shop uses dark pixel panels with bright cream text and gold rune prices. It displays vanilla item icons, prerequisites and stock, with single-bundle and stack purchases. Inventory delivery runs on the integrated server.

The catalog and selected-item details have separate panels, with purchase feedback above the vanilla inventory grid. Page buttons and scrolling navigate the catalog. Bulk labels show the quantity currently affordable within stock and item stack limits; unavailable purchases explain their reason in the feedback strip or tooltip. Smaller GUI dimensions show a readable prompt to increase window size or reduce GUI scale, with Close and Escape available.

```json
{
  "id": "regional_shop",
  "title": "Merchant",
  "merchant_ids": ["100000"],
  "offers": [{
    "id": "regional_iron_sword",
    "item": "minecraft:iron_sword",
    "count": 1,
    "price": 6500,
    "stock": -1,
    "unlock_any": ["godrick", "rennala"],
    "unlock_all": []
  }]
}
```

Put these entries in top-level `shops`. `merchant_ids` contains native shop-range IDs, as strings. Exact merchant matches take priority over the `"*"` fallback. The supplied fallback gives all recognized merchants the same 65-offer campaign catalog; replace or supplement it with regional catalogs. `count` is the number of items in a bundle, `price` the rune cost per bundle, and `stock` the number of bundles available per native character (`-1` means unlimited). `unlock_any` requires at least one listed victory; `unlock_all` requires all listed victories. Empty arrays impose no condition.

The native host independently validates merchant context, prerequisites, configured price, stock and wallet balance. It writes a durable purchase intent, debits the real wallet and requests a native save. Minecraft delivers only after the confirmed debit. The inventory save records a purchase UUID before shop stock commits, allowing safe retries after a disconnect or crash. An ambiguous native save quarantines the purchase instead of debiting again or delivering free items. Keep journals when restoring paired saves; manually mixing different save generations cannot be reconciled automatically.

Native save discovery uses the dedicated `EldenCraft.sl2`. If it cannot be found uniquely, set optional top-level `nativeSavePath` to its absolute path. Purchases wait for save discovery rather than accepting an unrelated Elden Ring save as proof.

An offer can optionally specify `native_item_lot` for an existing native **map item lot** containing Goods/key rewards, and `native_name` for its display name. Its vanilla `item` is only the GUI icon in this case, and `count` must be 1. Native validates the lot and grants it through the native dialogue award command; Minecraft does not create a substitute quest item. Weapon and armor lots are not supported. Use verified lot IDs appropriate to the supported game, including their award flags. The default catalog contains Minecraft goods only and leaves native quest-key acquisition in the world intact.

Selling is not enabled in this baseline. Repeat purchases of food, ammunition, replacements and repair/crafting materials provide ongoing rune sinks without creating profits from renewable mined blocks. Special finite stock and price/gate changes are configurable.

## Development and verification

`debugKits` defaults to false. Set it true only for deliberate testing of the dedicated offline world. `enabled: false` disables campaign progression and merchant interception; it does not erase granted items, claims or journals.

Run `scripts/eldencraft.ps1 build-minecraft --offline`, `scripts/eldencraft.ps1 test-native --offline`, the Python tests and `tests/windows-launcher.Tests.ps1`. Campaign conformance covers configuration, progression caps, stamina, JSON observations and purchase recovery. Native integration is pinned to the supported executable and offline session.

Live acceptance still needs a complete route, all remembrance phase completions, native save interruption, inventory saturation, shield pressure, ranged combat, food recovery, rest/death recovery and optional boss mechanics. Existing passthrough limits such as native AI navigation around placed blocks and incomplete native potion/firework effects remain relevant. Do not treat automated tests as proof of final combat balance or a completed live playthrough.
