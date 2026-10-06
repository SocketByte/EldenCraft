# EldenCraft campaign combat merchants and boss progression

The goal is a complete Elden Ring base-game playthrough using Minecraft controls, equipment, inventory and crafting. Permanent offensive progression comes primarily from stronger material tiers. Each unique remembrance boss increases maximum health and stamina automatically. Runes fund custom Minecraft merchant shops. Building is secondary to combat.

There is no manual character leveling, point allocation, custom player item registry, roll, player weapon skill or spell progression. The permitted changes are Minecraft merchant GUIs and catalogs, retuned base weapon damage, a stamina system, and boss-earned health and stamina capacity. Existing vanilla item identities, attack cooldowns, criticals, ordinary movement, shield behavior, durability and crafting remain the foundation.

All combat numbers and route requirements in this balance pass assume unenchanted equipment. Enchantments are deferred and must not be necessary to complete the campaign. XP, lapis in mines and ordinary enchanting remain future integration work, with enchantments serving as additional improvement after the base progression is proven.

Elden Ring supplies enemies, boss identities and phases, story, checkpoints and existing access conditions. This is an offline base-game design; Shadow of the Erdtree is outside scope. The proposals are based on source inspection on 5 October 2026. Numerical values are playtest candidates. Only this planning document has been changed.

## The three progression rewards

Equipment, capacity upgrades and spending money answer different player needs.

| Reward | Source | Purpose |
| --- | --- | --- |
| Stronger vanilla weapons and armor | Mainly minor and major boss loot | Material upgrades increase damage; armor pieces improve survival. |
| Maximum health and stamina | First defeat of each unique remembrance boss | Optional major encounters develop the character without a leveling menu or grindable stat currency. |
| Runes | Native combat and authored treasure rewards | Merchants sell supplies, replacements, alternate equipment and upgrade ingredients. |
| Minecraft XP and lapis | Enemy XP and Elden Ring mines | Reserved for the later enchanting pass; never exchanged for character attributes. |

The loop is to explore, fight, obtain equipment and runes, spend some runes on preparation, then defeat another important boss. A remembrance victory also improves the player's capacities. Minor bosses remain valuable through equipment, armor coverage and resource packages even though they do not grant the permanent capacity bonus.

Shops must support boss-earned progression. Farming ordinary enemies can fund more attempts and equipment already unlocked; it cannot buy the next material tier before an appropriate boss victory or purchase Vigor and Endurance directly.

## Current bridge and missing campaign support

| Area | Source evidence | Required outcome |
| --- | --- | --- |
| Rune display | [engine.rs](../native/src/engine.rs) reads the current native save's rune count. [host_pose.rs](../native/src/host_pose.rs) publishes a valid rune balance, including zero. [HostRuneHud.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/HostRuneHud.java) renders it. | Reuse the real rune wallet. The inspected path is read-only; showing a balance does not implement spending. |
| Merchant interaction | [host_action.rs](../native/src/host_action.rs) activates native interaction prompts on R. It exposes prompt text and ActionButtonParam information, rather than a complete merchant/shop context. | Identify the actual merchant and purchase action, preserve quest dialogue, and open a Minecraft shop with a validated catalog. A generic Talk prompt is not a safe merchant identifier. |
| Melee | [minecraft_melee.rs](../native/src/minecraft_melee.rs) multiplies accepted Minecraft damage by a scale, default 50. [CombatProxyEntity.java](../minecraft/src/main/java/dev/eldencraft/bridge/CombatProxyEntity.java) participates in server damage resolution. | Apply the new base weapon attributes before normal Minecraft attack resolution; recalibrate the native conversion after these changes. |
| Native damage application | [native_damage.rs](../native/src/native_damage.rs) applies calculated HP loss while bypassing the native attack calculator. [ProxyEntities.java](../minecraft/src/main/java/dev/eldencraft/bridge/ProxyEntities.java) gives proxies zero armor and toughness. | Verify native guards, phase immunity, deaths, rewards and special targets. Do not assume every original combat property follows from subtracting HP. |
| Player health | [SharedWorldClient.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/SharedWorldClient.java) mirrors the native HP ratio into the current Minecraft maximum. [world_bridge.rs](../native/src/world_bridge.rs) converts Minecraft player damage using both health maxima. | Apply boss capacity gains consistently to both sides. Increasing native maximum HP while leaving Minecraft maximum fixed changes the meaning of Minecraft damage and healing. |
| Shield | [minecraft_shield.rs](../native/src/minecraft_shield.rs) reduces qualifying frontal damage to approximately one tenth. | Resolve vanilla shield blocking and stamina expenditure. Incoming native attacks must also honor Minecraft armor and hurt resistance. |
| Mining | [TerrainMining.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/TerrainMining.java) gives drops from native surfaces, with temporary depletion in memory. [TerrainMaterials.java](../minecraft/src/main/java/dev/eldencraft/bridge/TerrainMaterials.java) includes wood and iron mappings. | Whitelist ordinary native-surface drops to cobblestone, bricks or stone bricks, and dirt. Author and persist special resource claims rather than granting equipment ores from broad material detection. |
| Persistence | [WorldOrigins.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/WorldOrigins.java) persists map origins, while runtime player identity is pointer-based in the inspected native snapshot. | Add durable character/world pairing, boss completion claims, merchant stock and purchase recovery. |
| Minecraft mob rewards | [mob_proxy.rs](../native/src/mob_proxy.rs) explicitly suppresses native rune drops for its proxy actors. | If Minecraft mobs earn runes, award them through one authored route. Avoid simultaneously paying native and Minecraft-side rewards for one kill. |

Native offensive attributes, equipment mitigation, skills, flasks and free leveling must not provide another route to player power. Boss-earned capacities are the permitted exception to a neutral host character. Use Vigor and Endurance curves to derive health and stamina; do not accidentally add their native equip-load, resistance or other secondary effects.

## The intended equipment route

This route is the first tuning baseline. Alternate native shardbearer routes remain valid.

| Stretch | Equipment entering the principal fights | Important rewards and merchant changes |
| --- | --- | --- |
| Limgrave and Weeping Peninsula through Margit | Wooden weapons and tools, leather armor assembled through exploration, shield; optional plain bow | Minor bosses give armor pieces, bow access and substantial supplies. Merchants sell wooden replacements, food and arrows. Margit introduces copper, the crafting table and wood supplies. |
| Stormveil through Godrick | Copper weapons and increasingly complete copper armor; stone remains a cheap backup | Encounters and minor bosses fill armor gaps. Godrick introduces an iron weapon, iron shop stock and the first remembrance capacity increase on this baseline route. |
| Liurnia, Raya Lucaria and approachable Caelid content | Iron equipment, with crossbow access | Rennala and other major encounters complete or improve iron coverage, supply equipment alternatives and expand merchant offerings. Remembrance victories add capacity. |
| Radahn, Nokron, Altus, Mt Gelmir and Leyndell | Iron becoming diamond | Major and minor bosses distribute diamond weapons and armor. Relevant victories unlock expensive diamond shop alternatives. Morgott establishes a dependable diamond baseline for Mountaintops. |
| Mountaintops and Farum Azula | Established diamond equipment becoming netherite | Fire Giant, Godskin Duo and Maliketh supply netherite upgrade ingredients and templates. Shops sell suitable repair supplies and costly additional upgrades after access is earned. |
| Godfrey, Radagon and Elden Beast | Full netherite is attainable; capacities reflect the remembrance bosses actually defeated | The normal route must be viable without clearing every optional region. The final remembrance bonus arrives after Elden Beast is defeated. |
| Snowfield, Haligtree, Mohgwyn Palace and other optional major routes | Equipment appropriate to when the player enters | Useful missing equipment, alternate loadouts, large rune rewards and remembrance capacity gains make detours meaningful. |

A boss must be beatable with the equipment and capacities available before its own reward. Give weapons at material milestones and distribute armor pieces across several minor bosses. Reaching a new tier should not instantly complete every slot.

Equivalent basic supplies must exist on alternate first-shardbearer routes. Skipping Godrick cannot permanently remove iron replacements or crafting support. Skipping Margit cannot permanently withhold a crafting table and wood after another substantial victory. Fallback shop unlocks follow the relevant earned progression.

### Keeping wooden equipment through Margit

Cobblestone, sticks and a crafting table allow stone weapons. Early raw wood also permits crafting the table in the inventory grid. The starter route therefore supplies ready-made wooden equipment and withholds ordinary tables and raw wood until Margit, while merchants provide wooden replacements.

This also applies to merchant catalogs. A shop must not sell early iron ingots, diamonds, usable equipment blocks or other recipe ingredients that bypass the tier.

An out-of-order higher boss victory is different from buying unearned gear. The baseline permits earned sequence-breaking rewards. Wooden Margit describes the normal route; an absolute restriction would additionally require deferring stronger rewards until the Margit flag. That remains a separate campaign rule.

## Stronger base material damage

Retune ordinary item damage rather than adding enchantments, named weapons or hidden damage bonuses based on boss count. Every item with the same vanilla identity uses the same authored base statistics, including a crafted replacement.

Proposed fully charged ordinary melee damage, in Minecraft HP units:

| Material | Sword damage | Axe damage | Sword attack rate | Axe attack rate |
| --- | ---: | ---: | ---: | ---: |
| Wood | 4 | 7 | 1.6 per second | 0.8 per second |
| Stone | 5 | 8 | 1.6 | 0.8 |
| Copper | 6 | 10 | 1.6 | 0.8 |
| Iron | 9 | 15 | 1.6 | 0.9 |
| Diamond | 13 | 21 | 1.6 | 1.0 |
| Netherite | 18 | 29 | 1.6 | 1.0 |

These are proposals, not vanilla values or measured encounter results. The attack rates retain the current vanilla family/material differences. Swords grow 4.5 times from wood to netherite; the stock sword ladder was only 4 to 8. The main upgrades are roughly 40–50 percent damage increases, making a new material useful without an enchantment.

Axes deliver larger individual hits and retain slower cooldowns. At the upper tiers their theoretical sustained damage is close to swords: a diamond sword gives 20.8 HP per second and an axe 21, while netherite gives 28.8 and 29 respectively before stamina and encounter openings. Axes cost more stamina, and swords retain their sweeping role. An axe is valuable in a short opening without universally replacing a sword.

Stone is intentionally weaker than copper in this retuned table. Cheap gathered cobblestone therefore supplies a backup without matching the rewarded material. Mining speed and durability can remain vanilla initially. Keep the ordinary armor ladder initially as well; increased maximum health already adds another substantial defensive curve.

Apply these values as base Minecraft attack attributes, so normal cooldown scaling, criticals, durability and feedback remain coherent. Do not multiply the entire final hit by a material factor: that would also amplify later effects in unintended ways. Tools used as improvised weapons must not accidentally outdamage the intended weapon families.

All melee and projectile conversions into native HP are recalibrated after the base statistics change. Retaining the old default multiplier of 50 unchanged would make the revised endgame sword more than twice as damaging as the old plain netherite sword.

## Remembrance capacity progression

Use first defeats of the 15 base-game remembrance encounters. Each contributes one fifteenth of the total progression budget.

| Encounter | Permanent capacity credit |
| --- | --- |
| Godrick the Grafted | One share |
| Rennala Queen of the Full Moon | One share at final completion |
| Starscourge Radahn | One share |
| Regal Ancestor Spirit | One share |
| Astel Naturalborn of the Void | One share |
| Lichdragon Fortissax | One share |
| Rykard Lord of Blasphemy | One share at final completion |
| Morgott the Omen King | One share |
| Fire Giant | One share at final completion |
| Mohg Lord of Blood | One share |
| Maliketh the Black Blade | One share at final completion |
| Dragonlord Placidusax | One share |
| Hoarah Loux Warrior | One share for the complete Godfrey encounter |
| Malenia Blade of Miquella | One share after the Goddess of Rot phase |
| Elden Beast | One share for the complete Radagon and Elden Beast encounter |

Margit, Godskin Duo, Golden Shade Godfrey, the ordinary Ancestor Spirit and the other versions of Mohg or Astel do not grant this credit. They still award their appropriate loot and runes.

A proposed starting equivalence is 10 Vigor and 10 Endurance. With `n` unique eligible victories:

```text
progress = n / 15
effectiveVigor = 10 + 50 * progress
effectiveEndurance = 10 + 20 * progress

maximumHealth = nativeHealthCurve(effectiveVigor)
maximumStamina = nativeStaminaCurve(effectiveEndurance)
```

These are capacity equivalents for the design, not an allocation screen. Every boss grants about 3.333 Vigor-equivalent and 1.333 Endurance-equivalent. Actual HP and stamina increments follow the capacity curves, so they need not be identical amounts after each boss. Evaluate the entire result from the unique kill count and interpolate fractional attribute equivalents between validated curve entries. Rounding every reward to three Vigor and one Endurance would miss the endpoint.

After all 15, the capacities equal 60 Vigor and 30 Endurance. The native HP reference is 414 at 10 Vigor and 1900 at 60, documented by [Bandai Namco](https://www.bandainamcoent.com/news/elden-ring-introduction-part-2-advanced-stats). The [SaveForge parameter reference](https://github.com/oisis/EldenRing-SaveForge/blob/main/spec/26-parameter-reference.md) records 130 stamina at 30 Endurance. Confirm the full curves and stamina units against the pinned native game parameters before implementation; do not copy unverified offsets or assume a table from another game version matches.

To preserve a 20 HP Minecraft start while keeping fixed health units, a direct mapping is:

```text
minecraftMaximumHealth = 20 * nativeHealthCurve(effectiveVigor) / 414
minecraftMaximumStamina = nativeStaminaCurve(effectiveEndurance)
```

This illustrative health mapping produces:

| Unique victories | Vigor equivalent | Endurance equivalent | Minecraft maximum HP |
| --- | ---: | ---: | ---: |
| 0 | 10 | 10 | 20 |
| 3 | 20 | 14 | 31.50 |
| 6 | 30 | 18 | 48.02 |
| 9 | 40 | 22 | 70.05 |
| 12 | 50 | 26 | 82.32 |
| 15 | 60 | 30 | 91.79 |

This is about 46 hearts at completion, not 60 Minecraft HP or 30 stamina units. The intermediate HP anchors are also recorded in the parameter reference. A different display can present the same health pool more compactly; changing the display must not change the damage arithmetic.

Only actual unique boss completion grants a share. Consuming, buying, selling or duplicating a remembrance does not. Reloading, replaying a phase, reviving an actor or receiving the same completion event twice does not. Derive capacities from a durable per-character set of completed encounters. Preserve existing current HP when granting maximum capacity, then let ordinary checkpoint healing restore it; reopening a reward or shop must not create a healing exploit.

Cap first-playthrough progression at these endpoints. NG+ should retain the achieved capacities and avoid granting another copy of the same permanent upgrades beyond the cap, while ordinary rewards can follow a separately defined new-cycle policy.

### Mandatory route and maximum completion

A typical Godrick/Rennala route has six remembrance victories before Radagon and Elden Beast: those two, Morgott, Fire Giant, Maliketh and Hoarah Loux. That is approximately 30 Vigor and 18 Endurance under this proposal. The final victory becomes the seventh, not the fifteenth.

Balance mandatory fights against that actual route, not a 60 Vigor character assumed at the end. Optional victories improve the margin for mistakes. Also test an extensively explored route with up to 14 credits before the final encounter. Making all optional bosses compulsory to survive the ending would contradict the intended full playthrough.

## Stamina and defense

Starting stamina and the final capacity come from the validated Endurance curve. Family costs and recovery rules remain independent of progression.

| Action or rule | First test value |
| --- | --- |
| Sword attack | 12 stamina |
| Axe attack | 24 stamina |
| Bow shot | 24 stamina |
| Crossbow shot | 24 stamina |
| Qualifying shield block | 4 plus 1.5 times incoming damage in fixed Minecraft HP units before armor |
| Recovery | 20 per second after 0.5 seconds without a stamina action |
| Holding shield or drawing/loading a ranged weapon | Pauses recovery |

Charge committed attacks, including misses, and fired shots. Charge a crossbow volley once at firing so preloaded hotbars cannot bypass the resource. Insufficient stamina prevents a new attack or shot. A valid shield block can consume the remaining stamina down to zero while blocking that hit; it then lowers until at least a small fixed threshold returns. Successful blocks require an outcome event even when HP loss is zero.

Use vanilla cooldowns alongside stamina. Better materials deal more damage for the same family cost; bigger capacity adds another modest improvement. Do not add sprint costs, rolls, guard counters, a posture meter or stamina upgrades from shop purchases and equipment.

Resolve native incoming attacks through Minecraft armor, shield direction and activation, absorption, durability and hurt-resistance timing once. Hidden native armor cannot provide a second reduction. Higher health must not secretly reduce shield cost: calculate attack pressure in fixed damage units rather than as a percentage of the current maximum.

Synchronize Minecraft and native maximum health before using their ratio for damage transport. The normal native-to-Minecraft health-unit conversion should remain constant as boss capacities increase. Otherwise one Minecraft hazard can scale its damage upward with Vigor, or one native hit can receive an unintended extra reduction.

Keep vanilla armor values for this first experiment: full leather has 7 armor points, copper 10, iron 15, diamond 20 with 8 toughness, and netherite 20 with 12 toughness in the pinned Minecraft version. Test the combined capacity and armor curve. A health upgrade plus armor may provide more survival improvement than either seems to provide in isolation.

## Bows and crossbows without enchantments

Vanilla bows have no copper, iron, diamond or netherite variants. Do not create a hidden damage multiplier from remembrance count to imitate that material ladder.

Use existing ranged weapons and ammunition as the initial progression:

| Equipment | Initial role and target |
| --- | --- |
| Plain bow with ordinary arrows | Accessible early ranged tool; roughly the usual 7–10 HP close-range fully drawn critical impact, checked by actual shots |
| Plain crossbow with ordinary arrows | Iron-stage ranged upgrade; initially target 12–16 HP at short range through a global crossbow base-damage adjustment |
| Tipped arrows | Later optional ammunition choices with their ordinary Minecraft effects |
| Explosive firework ammunition | Costly later crossbow option requiring area-damage and self-damage testing |

These impact ranges are tuning targets, not guarantees for every distance. Preserve vanilla arrow velocity, draw/loading behavior and critical-arrow rules; alter base projectile damage deliberately per existing weapon type. Crossbows retain the unenchanted 1.25-second load time for this pass.

The crossbow offers stronger prepared shots and the bow offers quicker successive shots. Both cost stamina. Measure boss damage uptime, loading exposure, shot accuracy, arrow consumption and actual native HP removed. Low-risk range can compensate for lower sustained damage; it must not make ranged equipment the fastest and cheapest answer to almost every encounter.

Merchants keep ordinary ammunition reliably available. A ranged route must be able to finish and retry encounters without mandatory arrow farming. Advanced ammunition is a preparation expense rather than a permanent attribute increase. Check large hitboxes, multi-part bosses, projectiles touching several proxies, explosions and firing after switching weapons: one contact cannot be counted repeatedly or use the wrong weapon's statistics.

A bow-only route cannot be assumed to have five gear tiers while enchantments are deferred. Its endgame viability needs explicit testing. This pass establishes a useful bow, a stronger existing crossbow and ammunition choices; enchantments can later add breadth without becoming the required offensive progression.

## Custom Minecraft merchant shops

Recognized Elden Ring merchants open a custom Minecraft shop screen for purchases. It uses Minecraft item icons, tooltips, fonts and inventory presentation, and spends the real native rune balance. It has no emerald conversion or rune-token item.

The screen shows the merchant name and rune balance, a goods grid, the selected item's ordinary name and statistics, unit and batch prices, remaining stock, quantity controls and the player's inventory. Buying one or a stack is an explicit action. Unaffordable, sold-out or unavailable goods clearly explain the reason. Goods shown in the catalog cannot be dragged into inventory as free stacks.

Preserve native dialogue and quest interactions. Either replace the native Purchase branch after dialogue or provide a Talk action that returns to the appropriate native conversation. Do not intercept every Talk prompt, dismiss an unfinished quest scene or leave the original native purchase action running behind the Minecraft window.

Use stable merchant/shop identities and existing availability conditions. Native merchant deaths and bell-bearing transfers must preserve access to appropriate stock through the existing world mechanisms. Roundtable merchants provide a dependable central supply route; nomadic and specialist merchants retain distinct catalogs.

### Shop stock and unlocks

| Catalog category | Goods and policy |
| --- | --- |
| Essentials | Food, ordinary arrows, wooden replacements and other basic supplies; repeatable stock |
| Current-tier maintenance | Repair materials and suitable replacement weapons or shields after the material tier is earned |
| Alternate equipment | Expensive weapons and armor for a different loadout or missing slot; first access follows relevant boss progression |
| Upgrade ingredients | Vanilla diamonds, netherite ingots, upgrade templates and smithing support at suitable milestones |
| Advanced preparation | Tipped arrows, potions and explosive ammunition with suitable ingredient and price budgets |
| Secondary building | Approved building materials, storage and wood after crafting access; no combat-stat purchase |

Do not sell a full higher-tier set in an early shop merely because a player saved enough runes. Catalog prerequisites follow earned material access. The first strong weapon and most substantial upgrades remain boss rewards; shops provide alternatives and maintenance afterward.

Give essentials generous repeatable availability. Persist limited equipment and special-item stock across reopening, resting and restarting. A limited stock item must not be the only possible replacement for mandatory equipment after it breaks.

Price recipe ingredients and finished equipment together. Cheap repair ingots also craft weapons and armor, so the cheapest legal recipe must be included in the equipment budget. Limiting ready-made armor stock does not limit its availability if an unlimited inexpensive ingredient supply crafts the same armor. Without enchantments, crafting a replacement weapon can also cost fewer ingredients than repairing it; use the player's actual cheapest maintenance route when estimating spending.

The first economy pass can be buy-only. Selling requires another analysis of renewable block farming, ingredient-to-crafted-item profit and merchant price differences. A low buyback percentage alone does not prove there is no infinite-money crafting loop.

### Rune economy

Initially preserve native rune rewards and price shops against observed route income. Treat a normal attempt's supply purchase as a small part of a representative regional boss payout, and a useful optional equipment purchase as a significant part. A starting hypothesis is 2–5 percent for a routine supply batch and 30–60 percent for an equipment purchase. These guide the initial catalog budget; they are not dynamic prices based on the player's held balance.

Basic supplies remain inexpensive enough to recover after failure. Additional netherite upgrades, alternate full loadouts, costly ammunition and valuable consumables create larger later spending choices. Every available reward has a purpose even when ordinary food becomes cheap relative to late-game income.

Boss equipment should still feel like a victory reward, not a receipt telling the player to farm more money before using it. Guarantee the main route's essential gear or upgrade materials directly. Buying extra preparation should help, without being compulsory for every attempt.

Retain native rune loss and recovery as the initial currency policy. It gives spending before a dangerous journey a purpose. The proposed Minecraft `keepInventory` preset can retain equipment while runes use native recovery. Both rules must be tested together, including multiple deaths, unrecovered runes and death with a shop open.

Native rune-consumable treasure can be converted into a credited rune reward when collected, rather than adding a new player-facing token. Conversion and collection must happen once. Minecraft XP remains separate and never becomes a rune-backed Vigor purchase.

### Spending must be a real transaction

The existing rune HUD is useful groundwork, but a shop needs a write path and durable recovery.

1. Resolve an active merchant session for the correct paired character and catalog.
2. Validate item, quantity, price, unlock, stock, current rune balance and inventory capacity using authoritative state.
3. Record a uniquely identified purchase intent, perform the native debit through a validated game-thread path, and receive an authoritative outcome.
4. Grant the Minecraft item exactly once, update stock and persist the purchase result with the inventory.
5. Recover a pending or interrupted purchase without charging twice, granting twice or leaving paid goods missing.

Client displays never grant items or alter currency. Wallet reads and merchant context must remain available while the Minecraft GUI is open even if ordinary combat/input leases are suspended. The native world does not become invulnerable simply because a shop is visible. Close or invalidate a session on death, character change, unavailable merchant or stale bridge state.

A debit acknowledgment alone is not a durable two-game transaction. Native save rollback and Minecraft inventory saving can occur at different times. The later implementation must prove a purchase journal and paired save/recovery protocol before shop spending is considered complete. Test interruptions before debit, after debit, after item grant and after either save.

## Loot supplies and gathering

Ordinary native terrain supplies cobblestone, bricks or stone bricks, and dirt. It does not become an iron, diamond, netherite or wood mine through a broad surface heuristic. Native land remains intact under the current gathering model, so resource limits are deliberate gameplay rules rather than physical terrain destruction.

Boss loot supplies weapons, armor and important upgrade ingredients. Ordinary enemies, treasure locations and merchants maintain food, ammunition, sticks, string, flint, feathers, leather and relevant repair supplies. Audit every usable recipe's ingredients because the shared world has empty generation and restricted Minecraft population growth; normal villages and ore veins cannot be assumed to exist.

Smithing Stone mine rewards remain candidates for lapis conversion in the later enchanting pass. Mines must already be worthwhile without enchanting, through a gear reward, rune cache or useful supply package. Do not make a deferred book the sole reward for a difficult optional encounter.

Full netherite armor and one sword require five upgrades. A possible guaranteed late-route distribution remains two ingot/template packages from Fire Giant, two from Godskin Duo and one from Maliketh, plus smithing-table access. Godskin Duo provides equipment progress without a remembrance capacity gain. Merchants and optional encounters provide additional upgrades for an axe or other equipment.

Define a reward manifest for every encounter instance with its stable completion identifier, expected material and capacity baseline, vanilla item stacks, rune policy, future XP reward, first-clear behavior and remembrance-credit eligibility. One boss family can have several encounters; only the correct remembrance instance grants capacity.

First-clear loot, capacity credit and shop unlocks share durable completion records. Multi-phase fights award once. Keep pending items recoverable after inventory overflow or an interrupted transition, using ordinary inventory or a vanilla chest at a safe checkpoint. No reward-token currency or custom equipment item is needed.

## Healing retries and encounter fairness

Large capacity growth changes healing economics. A 20 HP start mapped to roughly 92 HP at full completion takes much more ordinary food regeneration or fixed healing-potion output to refill. Validate vanilla food, saturation, healing and checkpoint restoration at early, mandatory-route and full-completion capacities. Merchants need reliable appropriate supplies. Do not solve a poor retry loop with a custom flask or an unrequested new healing ability.

All health changes must survive the next host-health update. Maximum capacity, current HP, absorption, ongoing damage and native death ownership must agree. A boss credit preserves current HP; a legitimate respawn or checkpoint heal restores the appropriate maximum. Equipment and stamina spent during a failed attempt cannot be silently restored by reopening a shop.

The native bosses require explicit testing with ordinary movement and shield use:

| Encounter issue | Necessary outcome |
| --- | --- |
| Long combinations and wide attacks | A survivable movement or shield response, correct hurt resistance and an actual recovery opening |
| Rennala and phase puzzles | Correct supporting targets and phase transitions, without damage during invulnerability |
| Fire Giant, dragons and Elden Beast | Reachable melee hit volumes and practical movement uptime |
| Rykard | A vanilla-weapon solution with suitable health and lava preparation, without requiring Serpent-Hunter as player equipment |
| Mohg | A ritual survivable with available capacities and ordinary preparation |
| Malenia | Waterfowl and healing remain coherent with stamina-limited Minecraft blocking |
| Maliketh and ongoing effects | Direct damage and damage over time counted once with appropriate defenses and healing |
| Native invaders and special actors | Legitimate targets accepted without breaking neutral quest NPCs |

Keep native boss identities and phases. Fixed encounter HP or attack adjustments are acceptable when the permitted tools cannot produce a fair fight. Enemy damage does not automatically scale to the player's remembrance count or equipment; additional exploration should confer an advantage.

Building and ranged safety need joint tests. If native AI cannot reach a player on a placed tower, stamina alone cannot create danger. Arena placement protection is a possible explicit world rule; unrestricted placement everywhere means accepting some building cheese. Test collision and AI interaction before choosing it. Pearls, explosives and flight must not strand the character or bypass required story events; powerful traversal can be introduced late or after the ending.

## The endgame comparison

The final reference is an unenchanted netherite loadout, boss-earned capacities approaching 60 Vigor and 30 Endurance, and no temporary offensive or defensive buffs. Compare it with a specified native level-100-style physical build with a fully upgraded weapon. If a strict level 100 is used, its remaining attribute allocation must fit the specified Vigor and Endurance; record exact weapon, affinity and armor.

Calibrate damage by actual native HP removed, attacks supported by stamina, hits survived and encounter duration. Attack rating alone is insufficient. For illustration, if the reference attack removes 450 native HP and the proposed netherite sword deals 18 Minecraft HP, the initial outgoing conversion is 25. Wood then removes 100 HP and copper 150. The 450 value is an example, not a measured result.

Use a fixed outgoing conversion and consistent health units for incoming damage. Improved offense comes from the item, and improved capacity from unique boss victories. There is no hidden level-based damage multiplier.

Measure both the minimum mandatory route and extensive optional completion. Full completion may make earlier encounters easier, but the mandatory route must remain viable. If an encounter needs tuning, adjust its authored parameters rather than assuming an enchantment or another undisclosed player system will fix it.

## Full campaign coverage and implementation order

Cover the main path through Leyndell, Mountaintops, Farum Azula, Ashen Capital and an ending. Also cover alternate shardbearers, Weeping Peninsula, Caelid and Dragonbarrow, Mt Gelmir and Volcano Manor, Siofra, Ainsel, Nokron, Nokstella, Lake of Rot, Astel, Deeproot, the sewers, Snowfield, Haligtree and Mohgwyn Palace.

Preserve quest keys, medallions, Great Rune story flags, transport, native dialogue and ending choices. Great Runes retain their story role without adding unplanned player buffs. Verify at least a normal ending, Ranni's route and Frenzied Flame access, then the remaining ending prerequisites.

A later implementation should proceed in this order:

1. Define paired character identity and the boss, reward and merchant manifests, including all 15 remembrance credits and alternate-route unlocks.
2. Make Minecraft damage and defense authoritative, then apply the proposed unenchanted weapon attributes and establish native conversion benchmarks.
3. Add derived boss health/stamina capacities and the stamina action rules, with durable unique-kill detection and correct healing/death synchronization.
4. Build a real merchant transaction through native rune spending and Minecraft inventory, including save interruption recovery, then the custom shop screen.
5. Author the wooden-to-copper-to-iron slice through Godrick with minor-boss equipment, a useful merchant economy and the first remembrance upgrade.
6. Extend loot, shop catalogs and capacity checks through all mandatory and optional regions.
7. Run complete shield/sword, axe and ranged playthroughs without enchantments, including the minimum route and extensive exploration.
8. Return to enchanting after material, capacity and rune progression work independently; keep its contribution supplementary.

Record actual hits, DPS during real openings, shield expenditure, stamina recovery, incoming HP loss, durability, healing, arrows, runes earned/spent and retries. Shop checks include insufficient funds, full inventory, repeat clicks, stock reloads, bell bearings, death, changing characters and interrupted saves. Capacity checks include every eligible encounter, phases, duplicated remembrances, reloading and NG+.

The first playable slice must demonstrate all three rewards: better equipment, a real rune spending choice, and an automatic remembrance capacity gain. The full base-game campaign remains the objective. A wider equipment list or a shop screen alone cannot establish that progression works.
