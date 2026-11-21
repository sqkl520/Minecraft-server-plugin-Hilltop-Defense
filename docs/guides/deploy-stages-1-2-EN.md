[简体中文](deploy-stages-1-2.md) | English

# Deploy Stages 1–2: Prepare the Worlds and Configure the Arena

> Moved from the English README. The README keeps a summary and links here.

From zero to your first match in 6 phases. **Follow them in order for a first-time setup.** This file is the complete version of the three stages **1**, **2** and **2.5**: preparing the worlds, configuring the arena, and how to get yourself into the arena world for a first deployment. For the step-by-step notes on stage 3 (the setup wizard) see `setup-wizard.md`; stages 4-6 (tuning parameters, self-checks, starting a match) stay in the README's "From empty folder to first match: 6 stages" section.

## Phase 1: Prepare the worlds

The plugin uses a "lobby + arena" split architecture (multi-arena mode) and needs at least 2 worlds:

| World | Purpose | Example |
|-------|---------|---------|
| **Lobby world** | Where players wait and browse menus | `lobby` |
| **Arena world** | The actual match map | `hilltop_arena1` — one per arena |

Arena worlds can be created by copying with WorldEdit, Multiverse, or duplicating a world folder.

> The lobby and arena **must be different worlds** — the plugin relies on this to tell protection rules from game logic.

### About `level-name` in `server.properties`

`level-name` designates **which folder in the server root is loaded as the main world** (default `world`). If the name doesn't match, the server generates a new world instead of loading the one you wanted.

Paper auto-loads **only that single world** — other worlds are not loaded even if their folders exist.

**This plugin loads missing worlds itself**: if a world folder (with `level.dat`) exists in the server root, the plugin loads it at startup via `Bukkit.createWorld` (the same approach BedWars1058 uses). Therefore:

- **Recommended**: set `level-name` to your **lobby world name**, so players land in the lobby directly with no teleport flicker
  ```properties
  level-name=lobby
  ```
- **Leaving it unchanged also works**: the plugin loads the arena/lobby worlds referenced in `arenas.yml`
- **Lobby fallback**: if `lobby.world` is blank or points to a world that doesn't exist, the plugin falls back to the server's main world (`level-name`), so it works out of the box

### Lobby spawn (a step independent of the 8-step wizard)

> **This is not part of the 8-step wizard.** The wizard (Phase 3) only writes **arena** data (core / spawn points / mob spawn points / nodes / villagers / shop); the `lobby` section of `arenas.yml` is never written by it. Skip this and you are likely to hit the two traps below.

**What ships as the factory default**

```yaml
lobby:
  world: lobby        # factory default; if no world named `lobby` exists it silently falls back to the main world
  spawn:              # factory placeholder — main-world coordinates, unrelated to your lobby world
    x: 0.5
    y: 100.0
    z: 0.5
```

**The two traps**

| Trap | Symptom | Cause |
|------|---------|-------|
| `lobby.world` names a world that doesn't exist | Players land outside your lobby and nothing is logged | `getLobbyWorldName()` **silently falls back to the main world** when `lobby.world` is blank or not loaded (by design) |
| `lobby.spawn` is still the placeholder | Players land in mid-air / at a spot unrelated to your lobby | The factory coordinates are the main world's `0.5 / 100.0 / 0.5` and were never adjusted for your lobby world |

**How to set it**

Stand in the world you want to use as the lobby and run:

```
/hilltop lobby setspawn        # admin
```

- The world named in `lobby.world` **exists** → you must run it in that world (prevents arena coordinates from being silently written as the lobby spawn)
- `lobby.world` is blank / names a world that **does not exist** (it can never be entered) → you may run it in **any world**; the plugin also sets `lobby.world` to your current world and explicitly says so ("lobby world set to X as well")

> The `lobby.spawn` keys (x/y/z/yaw/pitch) are written to `arenas.yml` and **take effect immediately**: new logins, lobby respawns and `/hilltop lobby` all use the new point, no restart needed.
>
> Step 8 of the wizard checks the current configuration (lobby world not set, or spawn still at the placeholder) and reminds you if either applies: **a reminder only, never a blocker** — deployment still counts as complete.

## Phase 2: Configure the arena

After installing the plugin, edit `plugins/HilltopVillage/arenas.yml`:

```yaml
default-arena: arena1

lobby:
  world: world          # lobby world name
  spawn:                # lobby spawn (where players land on join)
    x: 0.5
    y: 100.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0

arenas:
  arena1:
    display-name: "&6&lSky Fortress"
    enabled: true
    world: hilltop_arena1        # arena world (must differ from the lobby)
    waiting-location:            # where players gather after /hilltop join, before the match
      x: 100.5                   # optional: delete this whole section and the runtime falls back to core
      y: 80.0                    # written by step 2 [Set as Waiting Area] or /hilltop deploy setwaiting
      z: 100.5                   # "Restore initial state" does NOT clear it (not in DEPLOYMENT_KEYS)
    core:                        # player respawn point
      x: 100.0
      y: 64.0
      z: 100.0
    spawn-points: []             # player spawn points (filled in by the setup wizard, phase 3)
    mob-spawn-points: []         # mob spawn points (second, independent set; same wizard step / deploy commands)
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

> To apply: **restart the server**. `/hilltop reloadconfig` does not re-read arenas.yml (exception: per-arena params changed via the management GUI take effect immediately — see arena-lifecycle.md)

## Phase 2.5: Enter the arena world

This plugin has **no** command to teleport you into an arena world (`setup`, `deploy` and other deploy commands all require you to already be standing in the target world), so for your first deployment, get there yourself with any of:

| Method | Command | Notes |
|------|---------|-------|
| Vanilla teleport (OP required) | `/execute in hilltop_arena1 run tp @s 100 64 100` | Replace `hilltop_arena1` with your arena world name and the coordinates with your map center. Fly first (`/gamemode creative`) so you don't fall into unloaded chunks |
| Multiverse (if installed) | `/mvtp hilltop_arena1` | Teleports to that world's spawn |
| Warp plugin (e.g. MyWarp, if installed) | Per that plugin's usage | Set a landmark in the arena world — handy when deploying multiple arenas later |

> Once you're there, fly around and check two things: ① this really is the map you prepared (don't deploy in the wrong world); ② you're in creative mode (`/gamemode creative`) — you'll need it for placing respawn anchors, villagers and terrain tweaks. Step 1 of the wizard shows your current world name; cancel and re-teleport if it doesn't match.

> Note: the full step-by-step guide for Stage 3 (running the setup wizard) is in `setup-wizard.md` in the same directory; the 8-step wizard table and Stages 4-6 remain in the README's "From empty folder to first match: 6 stages" section.
