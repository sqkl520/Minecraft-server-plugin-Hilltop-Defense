# Configuration Reference (File by File)

> Moved from the English README. The README keeps a summary and links here.
> The closing YAML fence was moved past the starter-kit section to fix a pre-existing rendering defect in the source README; content verbatim otherwise.

## arenas.yml — arenas and lobby (core config)

One node per arena, containing world, waiting area, core, player spawn points, mob spawn points, player counts and waves.

```yaml
default-arena: arena1          # arena joined by /hilltop join with no argument

lobby:
  world: world                 # lobby world
  spawn: {x: 0.5, y: 100.0, z: 0.5, yaw: 0.0, pitch: 0.0}

arenas:
  arena1:
    display-name: "&6&lSky Fortress"
    enabled: true
    world: hilltop_arena1      # arena world (must differ from the lobby)
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}   # pre-match waiting spot
    core: {x: 100.0, y: 64.0, z: 100.0}               # player respawn point
    spawn-points: []           # player spawn points (written by the setup wizard)
    mob-spawn-points: []       # mob spawn points (written by the setup wizard / deploy commands)
    villagers: []              # villager positions (written by the setup wizard)
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

> Note: manually editing `arenas.yml` requires a server restart; `/hilltop reloadconfig` reloads game-settings.yml + monsters-config.yml + messages.yml. See the `/hilltop reloadconfig` row in `commands-reference.md` in the same directory, and "Parameter edits need no restart" in `arena-lifecycle.md`.

## game-settings.yml — game parameters

Editable through the `/hilltop config` GUI, or edit the file and run `/hilltop reloadconfig`.

```yaml
# Basics
min-players: 2                   # players required to start (only applies to arenas that do NOT set it in arenas.yml — see note below)
max-players: 6                   # max players per match
victory-waves: 20                # waves needed to win
wave-interval-seconds: 30        # rest time between waves
wave-timeout-seconds: 0          # wave-timeout safeguard: off by default (0); handle abnormal situations manually with /hilltop wave clear or wave skip. Set it back to a positive value (e.g. 120) to re-enable the automatic safeguard (stuck mobs pulled to players + highlighted, then force-cleared after a 15s grace)

# Added in phases 1-3
defeat-countdown-seconds: 20     # time from battle report to teleport back to lobby
villager-health: 20.0            # villager max health (all dead = defeat)
rejoin-timeout-seconds: 60       # reconnect window, 0 = disable reconnecting
empty-arena-end-seconds: 60      # empty-arena auto-end: end the match once 0 players stay online for this long, 0 or negative = disabled (not recommended)

map-restore:
  enabled: true                  # roll back block changes when a match ends
scoreboard:
  enabled: true                  # sidebar scoreboard

# Nodes
nodes:
  base-health: 100.0             # node starting health
  buff-radius: 20.0              # buff radius
  repair-cost-item: GHAST_TEAR   # repair item
  repair-cost-amount: 1          # items consumed per repair
  repair-amount-per-cost: 50.0   # health restored per item consumed
  buff-effects:                  # node buffs ("EFFECT:AMPLIFIER", 0 = level I)
    - "RESISTANCE:0"
    - "REGENERATION:0"
  buff-refresh-ticks: 60         # buff refresh interval
  buff-stack-cap: 3              # max stacked level from multiple nodes
  buff-duration-ticks: 100       # duration of a single buff application

# Spawning
spawning:
  spawn-radius-min: 20
  spawn-radius-max: 40
  spawn-y-offset: 0              # spawn height offset
  mob-cap-per-player: 15
  global-mob-cap: 80
  airdrop-max-per-dropper: 3     # max airdrop rounds per flying dropper for the whole match (prevents airdrop runaway), 0=disable airdrops

# Starter kit (granted to every player on match start; overflow drops at their feet)
# Default: stone sword + full leather armor set + bread x16 + torches x8
starter-kit:
  enabled: true                  # master switch: false = grant nothing
  items:                         # material = vanilla Material name, amount = count
    - material: STONE_SWORD
      amount: 1
    - material: LEATHER_HELMET   # full leather set granted at match start
      amount: 1
    - material: LEATHER_CHESTPLATE
      amount: 1
    - material: LEATHER_LEGGINGS
      amount: 1
    - material: LEATHER_BOOTS
      amount: 1
    - material: BREAD
      amount: 16
    - material: TORCH
      amount: 8
```

## config.yml

Internal state file — it holds **no game settings** (those live in `game-settings.yml`), only the language and setup-wizard progress. Maintained automatically by the plugin.

```yaml
language: "zh"    # zh / en
```

## Complete reference

See the individual files under `plugins/HilltopVillage/` for the complete reference — each ships with inline comments.

> Note: the "see note below" on the `min-players` line of `game-settings.yml` refers to this — it is the **global default**, and applies only to arenas that do not write `game.min-players` explicitly in `arenas.yml`; for arenas that do write it, their own value is authoritative (set it to `1` for solo matches), and the `min-players` in the configuration GUI does not apply to those arenas.
