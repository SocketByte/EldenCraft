# Shared-dimension mob spawning

New mobs in the shared dimension are admitted only from actual spawn eggs,
including dispensers and egg use on an adult. Natural spawning, spawners,
ordinary breeding, bucket releases, commands and population-increasing splits
are blocked there. Other dimensions retain vanilla spawning.

Existing entities are preserved. Loading mobs, dimension transfers and one-for-one
vanilla conversions remain allowed. Internal combat proxies, players, dropped
items and projectiles are unaffected.

Admission checks the real egg item, requested entity type, level and a single-use
scoped permit at both creation and server insertion. The current rules are defined
in [WorldSpawnPolicy.java](src/main/java/dev/eldencraft/bridge/WorldSpawnPolicy.java)
and [WorldMobSpawning.java](src/client/java/dev/eldencraft/bridge/client/WorldMobSpawning.java).
The Java conformance suite checks every spawn reason in the pinned Minecraft enum.
