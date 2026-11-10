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
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}   # pre-match waiting spot (optional; falls back to core)
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

> Note: manually editing `arenas.yml` requires a server restart; `/hilltop reloadconfig` reloads game-settings.yml + monsters-config.yml + messages.yml + display.yml. See the `/hilltop reloadconfig` row in `commands-reference.md` in the same directory, and "Parameter edits need no restart" in `arena-lifecycle.md`.

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
empty-arena-end-seconds: 120     # empty-arena auto-end: end the match once 0 players stay online for this long, 0 or negative = disabled (not recommended)

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

## The `waves` section of game-settings.yml — wave composition

The `waves` section at the end of `game-settings.yml` (the "wave composition" page in the GUI) decides what every wave spawns and how much.

```yaml
waves:
  default:                       # template for waves with no explicit entry (the shipped file configures only 1 / 5 / 10)
    total-mobs: 10               # per-player exact total cap (0 = spawn nothing for this wave)
    compositions:
      explode-beetle: {count: 4, weight: 3}
      hook-claw-hunter: {count: 3, weight: 2}
      flying-dropper: {count: 3, weight: 1}
  "1":                           # a numeric key is the wave number (quoted — YAML keys must be strings)
    total-mobs: 8
    compositions: {explode-beetle: {count: 6, weight: 5}, hook-claw-hunter: {count: 2, weight: 1}}
```

Three things to know:

| Field | Meaning |
|---|---|
| `count` | **Per-player count**: actual spawns are `count × players present`. Wave 1 with `count: 6` beetles and 2 players → 12 beetles |
| `total-mobs` | **Per-player exact total cap**: if `sum(count) × players` exceeds it, the mix is trimmed proportionally; it never pads up. Three caps apply and the smallest wins (`total-mobs` → `mob-cap-per-player` → `global-mob-cap`) |
| `weight` | Legacy weight: **only used when every `count` in the wave is 0 / missing** (then the old weighted-random path runs); ignored on the normal path |

**`total-mobs: 0` disables a wave.** It trims `sum(count) × players` to zero, so `WaveManager`'s `maxTotal <= 0` branch returns immediately and nothing spawns — this is the only supported way to skip a wave. Do **not** express "spawn nothing" by deleting the whole `waves.default` section: a missing section means "restore factory defaults" and gets written back.

**Delete and reload semantics** (since 1.1.0):

- After deleting a wave in the GUI you **must click [Confirm & Save]**. Deleting and then running `/hilltop reloadconfig` without saving makes the wave reappear from disk (disk is authoritative).
- Conversely, a wave number you hand-write into this file (e.g. `waves: {"7": ...}`) **will** take effect after `/hilltop reloadconfig` or a restart. Older versions silently discarded it; fixed in 1.1.0.
- A wave that sets only `total-mobs` and no `compositions` degrades to "spawn 5+2×waveNumber vanilla zombies". This version logs a summary WARNING naming that wave.

## display.yml — sidebar and tab list layout

Layout for the sidebar scoreboard and the player list (tab). This file is **hand-written by the server owner and never written back by the plugin**, so your comments stay intact.

```yaml
scoreboard:
  enabled: true              # ANDed with the same switch in game-settings.yml
  title: scoreboard.title    # i18n key for the title (a key name, not literal text)
  refresh-ticks: 10          # full repaint period, 5-200
  lines:
    - key: scoreboard.wave
      args: [wave, totalWaves]
      when: in-game
```

**Four line kinds** (exactly one per line):

| Syntax | Meaning |
|--------|---------|
| `separator: 20` | A divider line (count = width, 1-40) |
| `blank: true` | An empty spacer line |
| `key: <i18n key>` | Static line, text from `messages.yml` |
| `anim: [key1, key2, ...]` | Animated line, 2-8 frames cycling; set `frame-ticks` for frame duration |

**Optional fields**: `args` (source of `{0}{1}...` for that line, in order), `when` (visibility condition), `frame-ticks` (anim only).

**Condition names**: `always` `waiting` `starting` `running` `intermission` `in-game` `finished` `start-countdown` `waiting-for-players` `alive` `spectator`

**Arg names**: `wave` `totalWaves` `alivePlayers` `spectators` `activeNodes` `totalNodes` `aliveVillagers` `totalVillagers` `playerKills` `playerDeaths` `playerCrystals` `startCountdown` `playerCount` `minPlayers` `remainingMobs` `nextWaveSeconds` `arenaName` `gameState`

**Caps**: at most 15 sidebar lines; at most 8 lines each for tab header/footer.

**Failure behaviour**: an unknown condition name makes that line never show (no error); an unknown arg name skips that line; out-of-range numbers are clamped. Each case logs one English warning to the console. If the whole file is unusable, the built-in default layout is used and the sidebar never goes blank.

> **About `nextWaveSeconds`**: `GameManager` does not expose the intermission countdown, so this returns the **configured wave interval**, not a live countdown — the two are equal only at the instant intermission begins. The default layout does not use it.

## config.yml

Internal state file — it holds **no game settings** (those live in `game-settings.yml`), only the language and setup-wizard progress. Maintained automatically by the plugin.

```yaml
language: "zh"    # zh / en
```

## Complete reference

See the individual files under `plugins/HilltopVillage/` for the complete reference — each ships with inline comments.

> Note: the "see note below" on the `min-players` line of `game-settings.yml` refers to this — it is the **global default**, and applies only to arenas that do not write `game.min-players` explicitly in `arenas.yml`; for arenas that do write it, their own value is authoritative (set it to `1` for solo matches), and the `min-players` in the configuration GUI does not apply to those arenas.
