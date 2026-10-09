English | [简体中文](README.md)

> ⚠️ **This is a Minecraft plugin developed by an enthusiast with AI assistance. Feel free to submit Issues for any questions or suggestions. If you like this project, give it a star!**

# Hilltop Village

A cooperative PvE tower defense plugin for Minecraft servers. Players must work together to protect the village core from waves of increasingly powerful monsters.

## Features

**Gameplay**

- **Cooperative Tower Defense** — Team up to keep the villagers alive; survive every wave to win
- **Wave-based Assault System** — Monsters attack in waves with increasing difficulty, with a breather between each
- **Energy Node System** — Respawn anchors act as energy nodes, buffing nearby players with Resistance + Regeneration; monsters can destroy them, players repair them with World Tree Sap; **when all are destroyed, fallen players can no longer respawn**
- **Villagers to Protect** — Only monsters can damage villagers (player attacks and environmental damage — suffocation/fall/lava/drowning/explosion/lightning/fire/cactus — are all cancelled); villagers stay locked at their deployed positions. **If every villager dies, the game is lost** (count set by the admin during deployment)
- **Death & Respawn** — Fallen players spectate until the next wave, then respawn at the core; impossible while no node is active
- **Sacred Hammer** — Right-click to leap and charge a smash attack from above, dealing three-tier area damage + particle shockwaves + aftershock slowdown; supports ItemsAdder custom models; smashes have a cooldown (8s default) and a use limit (30 default, shatters when exhausted — buy a new one from the shop); smashing and node repair only work while a game is running
- **Fireball** — BedWars-style Fireball: right-click to throw with flame particle trail, explodes on impact for area damage + knockback + ignite (zero damage to players — knockback only, no ignite; villagers fully exempt and no longer trigger proximity fuse); configurable cooldown
- **Villager Shop** — Killing monsters drops Energy Crystals (configurable chance); right-click the shop villager to buy items; everything customizable in `shop.yml`
- **Special Monster AI** — Explode Beetle (rushes nodes to self-destruct; its entity explosion can be toggled off), Hook Claw Hunter (ranged hook grab + blindness), Flying Dropper (airdrops zombie squads); each supports armor/weapons/custom models

**Architecture & Quality of Life**

- **Multi-Arena Support** — A lobby plus multiple independent arena worlds, running concurrent matches without interference; players pick an arena via `/hilltop gui`
- **Lobby System** — Everyone lands in the lobby on join; block break/place, damage, hunger and weather are blocked there; admins can lift protection with `/hilltop build`
- **Sidebar Scoreboard** — Live wave progress, alive players, awaiting respawn, nodes, villagers and personal kills/deaths
- **Player Stats & Leaderboards** — Win rate, K/D, best wave and more are persisted; `/hilltop stats` and `/hilltop top`
- **Item Protection** — Inventory/XP/health saved on entering an arena and restored on leaving; snapshots are persisted to disk, so even a server crash won't lose them
- **Reconnect Window** — Disconnecting mid-match keeps a 60-second (configurable) rejoin window
- **Map Restore** — Block changes made during a match are rolled back on reset, so maps don't degrade game after game
- **Graphical Config Center** — Configure game rules, waves, monsters, items and nodes through an intuitive GUI
- **8-Step Setup Wizard** — `/hilltop setup` walks you through core/player-spawns+mob-spawns/nodes/villagers/shop, auto-saving each step
- **Multi-language Support** — Built-in Chinese and English; players can switch with `/hilltop lang <en|zh>`
- **Spawn Point Particle Display** — After deploying spawn points, trigger a 30-second colorful particle animation to visualize all spawn locations

## Energy Nodes Explained

Energy nodes are **special blocks placed in the world** — the plugin scans and detects them automatically (no command needed to "set" them). The node block type is **fixed to the Respawn Anchor** (`RESPAWN_ANCHOR`); this is by design and cannot be changed:

| Block | Material |
|-------|----------|
| Respawn Anchor | RESPAWN_ANCHOR |

> Respawn Anchors keep their **full vanilla Minecraft functionality** (charging with glowstone, setting your respawn point) — the plugin merely **registers them additionally as energy nodes**, layering its buff aura / destructible / repairable mechanics on top.

**Node mechanics:**

- **Buff aura** — Each active node continuously grants **Resistance I + Regeneration** to players within a 20-block radius (configurable); players near nodes take less damage and regenerate faster. **Active node = a node with health above 0**: all nodes are registered as active during scanning; once monsters deplete their health they deactivate (aura disappears), and they reactivate after being repaired with World Tree Sap
- **Destructible** — Nodes have health (default 100); monster attacks and Explode Beetle self-destructs reduce node health; at zero health the node deactivates and its aura disappears
- **Repairable** — Right-click a deactivated node with "World Tree Sap" (default GHAST_TEAR, dropped by Nether ghasts; also purchasable as the World Tree Sap shop item; material configurable via `nodes.repair-cost-item` in game-settings.yml, or via `/hilltop config` → Node Settings) to restore 50 health (consumes 1 per use)
- **Add/remove nodes** — To add nodes, place more of the above blocks in the world, then run `/hilltop deploy reloadnodes` or click [Rescan Nodes] in the setup wizard; to remove, break the block and rescan. Scan range: 128 blocks horizontally around the core (configurable via `nodes.search-radius`) + full world height; if no nodes are found, make sure the blocks are within range and their chunks are loaded

## Shop System

Killing wave monsters has a chance to drop **Energy Crystals** (currency; default 40% chance of 1, configurable via `shop.currency-drop-chance` / `shop.currency-drop-amount` in game-settings.yml). Walk up to the shop villager and **right-click** it to open the shop GUI; left-click an item icon to purchase.

**Economy loop**: monsters drop crystals → right-click the shop villager to buy items/World Tree Sap → right-click deactivated nodes with sap to repair → nodes resume buffing the defenders.

**Shop villager deployment**:

- Setup wizard Step 6 (`/hilltop setup`): stand at the target spot and click [Set Shop Location] (required — deployment cannot finish without a shop location)
- The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on restart; leftover duplicate entities are cleaned up automatically on startup
- The shop villager is invulnerable (all damage cancelled) and non-interactive — it exists purely as the shop entry

**Custom items in shop.yml** (`plugins/HilltopVillage/shop.yml`):

```yaml
shop:
  currency:
    material: EMERALD      # currency item material
    name: "&aEnergy Crystal" # currency display name (color codes supported)
  npc:
    name: "&6Shop Villager" # NPC overhead name
  gui:
    title: "&8Village Shop" # GUI title (empty = language file shop.gui.title)
    size: 27               # GUI size (9/18/27/45/54)
  items:
    sacred_hammer:
      slot: 10             # GUI slot (0 ~ size-1)
      price: 5             # price in Energy Crystals
      type: HAMMER         # HAMMER / FIREBALL / REPAIR_ITEM / MATERIAL
      amount: 1            # quantity per purchase (HAMMER always 1, ignored)
      icon:
        material: NETHERITE_AXE
      name: "&6Sacred Hammer" # display name (color codes supported)
      lore:                # lore lines (price line appended automatically)
        - "&7Right-click air to charge, land to smash"
```

- `type` values: `HAMMER` (Sacred Hammer, always 1 regardless of amount) / `FIREBALL` / `REPAIR_ITEM` (World Tree Sap) / `MATERIAL` (any vanilla item, gives `amount` of `icon.material`)
- Run `/hilltop shop reload` (admin) after editing — no restart needed
- Anti-exploit: all clicks and drags inside the shop GUI are cancelled (icons can't be taken), and purchases have a 250ms cooldown against double-click double-charging


## Deployment Guide (Server Owner Guide)

From zero to your first match in 6 phases. **Follow them in order for a first-time setup.**

### Phase 1: Prepare the worlds

The plugin uses a "lobby + arena" split architecture (multi-arena mode) and needs at least 2 worlds:

| World | Purpose | Example |
|-------|---------|---------|
| **Lobby world** | Where players wait and browse menus | `lobby` |
| **Arena world** | The actual match map | `hilltop_arena1` — one per arena |

Arena worlds can be created by copying with WorldEdit, Multiverse, or duplicating a world folder.

> The lobby and arena **must be different worlds** — the plugin relies on this to tell protection rules from game logic.

#### About `level-name` in `server.properties`

`level-name` designates **which folder in the server root is loaded as the main world** (default `world`). If the name doesn't match, the server generates a new world instead of loading the one you wanted.

Paper auto-loads **only that single world** — other worlds are not loaded even if their folders exist.

**This plugin loads missing worlds itself**: if a world folder (with `level.dat`) exists in the server root, the plugin loads it at startup via `Bukkit.createWorld` (the same approach BedWars1058 uses). Therefore:

- **Recommended**: set `level-name` to your **lobby world name**, so players land in the lobby directly with no teleport flicker
  ```properties
  level-name=lobby
  ```
- **Leaving it unchanged also works**: the plugin loads the arena/lobby worlds referenced in `arenas.yml`
- **Lobby fallback**: if `lobby.world` is blank or points to a world that doesn't exist, the plugin falls back to the server's main world (`level-name`), so it works out of the box

### Phase 2: Configure the arena

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
    waiting-location:            # where players wait before the match starts
      x: 100.5
      y: 80.0
      z: 100.5
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

> To apply: restart the server, or run `/hilltop reloadconfig` and restart the arena.

### Phase 3: Run the setup wizard

Go to the **arena world** and run:

```
/hilltop setup
```

| Step | Content | Action |
|------|---------|--------|
| 1/8 Confirm world | Shows your current world name | Click [Confirm] |
| 2/8 Set core | Stand at the village core location (the player respawn point) | Click [Set as Core] |
| 3/8 Spawn points | Player spawns + mob spawn points (two independent sets, **at least 1 each**) | Walk to each spot, click [Add Current Location] (player) / [Add Mob Spawn] (mobs) in chat; click [Open Spawn Manager]/[Open Mob Spawn Manager] to review/delete; click [Done] in either GUI (both sets must be deployed to leave this step) |
| 4/8 Node registration | Register respawn anchors as energy nodes | **Place the respawn anchor blocks first**, stand next to one and click [Add Node]; click [Done] |
| 5/8 Villagers | Positions of villagers to protect (**all dead = game lost**, **at least 1 required**) | Walk to a spot and click [Add Villager]; click [Villager Manager] to review/delete; click [Done] when finished |
| 6/8 Shop location | Where the villager shop NPC stands (**required — deployment cannot finish without it**) | Stand where you want it and click [Set Shop Location] |
| 7/8 Config preview | Overview of core/player-spawns/mob-spawns/nodes/villagers/players/waves | Click [Confirm & Save] in the GUI |
| 8/8 Done | Shows the start entry | Click /hilltop start in chat |

**The three key concepts**

| Concept | Role | Consequence when destroyed |
|---------|------|---------------------------|
| **Core** | The player **respawn point**; has no health | None (just a respawn coordinate) |
| **Energy node** (respawn anchor) | The real "health bar": buffs nearby players with Resistance + Regeneration; monsters attack it, players repair it | **Once all are destroyed, fallen players cannot respawn** |
| **Villagers** | Must be protected; the count is however many you placed | **If every villager dies, the game is lost** |

### Phase 4: Tune the game settings

```
/hilltop config
```

Adjust player counts, waves, monster attributes, item parameters, node and villager health through the GUI. Click [Confirm & Save] — **new settings apply at the start of the next match**.

You can also edit `plugins/HilltopVillage/game-settings.yml` directly and run `/hilltop reloadconfig`.

### Arena Management GUI (`/hilltop manage`)

Admins run `/hilltop manage` to open a 54-slot menu listing **every configured arena** — including ones with `enabled: false`, a missing world folder, or that were never deployed (those are flagged as missing core coordinates, and the [Enable] button will refuse until you run `/hilltop setup`).

| View | What you can do |
|------|-----------------|
| **List** | See all arenas; left-click to open the detail page |
| **Detail** | Status / world / players / waves / deployment counts / shop location / parameter summary; enable, disable, teleport to core, open the sub-lists |
| **Player-spawn / Mob-spawn / Node / Villager lists** | 45 entries per page; **left-click to teleport**, **right-click to delete** one entry, **Shift+Left-click** to preview its position with particles |
| **Game parameters** | Edit that arena's `min-players` / `max-players` / `victory-waves` / `wave-interval-seconds`; left-click and type the new value in chat |
| **Restore initial state** | Wipe all deployment data + game parameters of that arena and force-rollback the map blocks, so you can redeploy from scratch |

> **What "Restore initial state" does**: deletes the arena's core coordinates, all player spawn points, all mob spawn points, all villagers, all energy nodes, the shop NPC (both the stored location and the live entity), plus the per-arena game parameters and radius offsets, and force-rolls back the world's map blocks. It **keeps** the world, display name and `enabled` flag, so the arena stays in the list as "not deployed" afterwards. There is a confirmation page (listing exactly what will be cleared plus the real player count on site) and a second cancel button. **Refused while a match is in progress** — run `/hilltop stop <arena>` first. Because the core coordinates are removed, the arena is not re-enabled automatically; redeploy it and then `/hilltop enable`.

> **Parameter edits need no restart**: changes made through the management GUI are written to `arenas.yml` immediately and pushed to the running arena — player counts apply from the next match, wave interval and victory waves apply from the next intermission. **Editing `arenas.yml` by hand still requires a server restart** (same as the other arena settings). Edits are refused while a match is in progress (including the end-of-match countdown), so raising the player floor mid-match can't trigger an auto-defeat.

> **Deleting one entry uses the "filtered ordinal"**: entries with broken coordinates are skipped when reading, so the numbering you see in the GUI may not match the raw line numbers in `arenas.yml`. Always delete from the GUI rather than editing the file using the GUI's numbers.

### Phase 5: Verify the deployment

| Check | Command | Expected |
|-------|---------|----------|
| Arena loaded | `/hilltop list` | Your arena is listed |
| Spawn points set | `/hilltop deploy listspawns` | At least 1 player spawn point |
| Mob spawn points set | `/hilltop deploy listmobspawns` | At least 1 mob spawn point |
| Nodes registered | `/hilltop deploy` menu | Known nodes > 0 |
| Villagers registered | `/hilltop deploy listvillagers` | At least 1 villager |
| Overall status | `/hilltop status` | Shows wave/players/mobs/nodes |

> If both spawn sets are empty, `/hilltop start` refuses to start and tells you to finish deployment first.

### Phase 6: Start a match

```
/hilltop join      # players join (or /hilltop gui for the selection menu)
/hilltop start     # admin starts (min-players required)
```

**Manual command cheat sheet (veterans)**

```
/hilltop deploy setcore        # stand at the core location
/hilltop deploy addspawn       # stand at each player spawn location (repeatable)
/hilltop deploy listspawns     # list all player spawn points
/hilltop deploy addmobspawn    # stand at each mob spawn location (repeatable)
/hilltop deploy listmobspawns  # list all mob spawn points
/hilltop deploy showparticles  # confirm positions via particle effects
/hilltop deploy reloadnodes    # reload node configuration
/hilltop deploy addvillager    # stand at each villager location (repeatable)
/hilltop deploy listvillagers  # list all registered villagers
```

## Gameplay Workflow

### How a match runs

```
1. Player joins the server
     Teleported to the LOBBY spawn with lobby protection
     (no block break/place, no damage, no hunger)

2. Player enters an arena
   /hilltop join  or  /hilltop gui
     -> Inventory/XP/health saved to disk, inventory cleared for battle
     -> Teleported to the arena's WAITING AREA
     -> Sidebar scoreboard appears (wave / alive / nodes / villagers / personal kills)
     -> Once min-players is reached, the start countdown begins

3. Match starts
     Countdown hits zero -> players teleport to PLAYER SPAWN POINTS, switched to Survival, starter kit granted
     -> All energy nodes activate at their configured health and buff nearby players
     -> Villagers spawn at their configured positions
     -> Wave 1 attacks

4. Wave loop
     Monsters spawn from MOB SPAWN POINTS  ->  attack players when nearby, otherwise advance on NODES
     Per-type spawn count = configured `count` (per player) x players present
     (e.g. wave 1 with beetle 6 / hunter 2 spawns 12 beetles + 4 hunters in a 2-player match),
     capped by the `total-mobs` exact-total cap and the global/per-player caps
     Wave cleared     ->  intermission (30s by default)
                          -> fallen players respawn at the CORE (requires an active node)
     Intermission ends -> next wave (insufficient players ends the match in defeat instead of opening an empty wave)

5. End
   Victory: survive all waves (20 by default)
   Defeat:  all villagers slain  OR  all nodes destroyed + all players down  OR  insufficient players during intermission
            (no alive players + no active nodes is re-checked every 3s; defeat lands within 3s)
        |
   Players stay in the arena to read the BATTLE REPORT -> countdown (20s by default)
   -> teleported back to the lobby
   -> inventory/XP/health restored -> block changes rolled back -> arena resets for the next match
```

### What players actually do

**Goal**: survive every wave while keeping the villagers alive.

| Tool | How to get it | Effect |
|------|--------------|--------|
| **Sacred Hammer** | Buy from the shop (or Admin `/hilltop hammer`) | Right-click to leap and charge, then smash down for three-tier area damage (higher fall = more damage); has a cooldown and a use limit — shatters when exhausted and must be repurchased |
| **Fireball** | Buy from the shop (or Admin `/hilltop fireball`) | Right-click to throw; explodes on impact for area damage + knockback + ignite (0 damage to players, knockback only; monsters unchanged; villagers exempt) |
| **World Tree Sap** | Kill ghasts / buy from the shop | Right-click a destroyed energy node to repair it |
| **Node buffs** | Stand near an active node | Resistance + Regeneration (nodes are mobile healing fountains) |

**Resource loop**: kill monsters → Energy Crystals drop → right-click the shop villager to buy items → repair nodes / restock → keep defending.

### Monster types

| Monster | Behaviour |
|---------|-----------|
| **Regular monsters** (zombies etc.) | Attack players when nearby, otherwise advance on and attack **energy nodes** |
| **Explode Beetle** | Rushes the nearest node and self-destructs (protect nodes first!) |
| **Hook Claw Hunter** | Ranged hook that drags players in, plus blindness |
| **Flying Dropper** | Flies overhead and airdrops zombie squads |

### Death & respawn rules

| Situation | Result |
|-----------|--------|
| Fallen, an active node exists | Become a spectator; **respawn at the CORE** on the next wave (no countdown, no invulnerability) |
| Fallen, all nodes destroyed | Become a spectator and **cannot respawn** until a teammate repairs a node |
| Disconnected mid-match | A **60-second** (configurable) rejoin window is kept; reconnecting returns you to the fight |
| Rejoin window expired | Removed from the arena; the item snapshot stays on disk and is restored on your next login |

### Scoreboard

A sidebar appears automatically inside an arena:

```
   Hilltop Village
  Wave: 7/20
  Alive: 4
  Awaiting respawn: 1
  Nodes: 3/5
  Villagers: 6/8
  Your kills: 42
  Your deaths: 2
```

Disable it with `scoreboard.enabled` in `game-settings.yml`.

### Stats & leaderboards

Accumulated into `plugins/HilltopVillage/player-stats.yml` at the end of every match:

```
/hilltop stats            # your own stats
/hilltop stats Steve      # someone else's stats
/hilltop top kills        # kill leaderboard (top 10)
/hilltop top wins         # win leaderboard
```

Leaderboard types: `kills` · `wins` · `games` · `best-wave` · `nodes` · `deaths`

> Leaving mid-match still counts kills/deaths/node repairs, but **does not count as a win or loss**.

### Map restore

Blocks broken or placed during a match are **rolled back automatically** when the arena resets, so maps don't degrade from explosions and player building.

- Scope: **arena worlds only**, and **only while a match is running** (admin building during deployment is untouched)
- Sources: block break, block place, entity explosion, block explosion, natural changes (ice/snow melting), **water/lava placement and flow**
- Fidelity: full block data (`BlockData` — facing, half, waterlogged, etc.) plus block-entity data (`TileState` for containers, signs, …) is stored, not just the material
- Rollback is batched (max 200 blocks per tick) and does not trigger chain physics updates; **the next match is not opened until rollback fully succeeds**, and blocks that fail to restore keep their snapshot for retry
  - Tune the per-tick batch with a JVM flag: `-Dhilltop.maprestore.blocksPerTick=400` (default `200`). Higher = faster rollback but more work per tick; invalid values (non-numeric / ≤ 0) fall back to 200 with a console WARNING
- **Recording cap**: at most 1,000,000 block changes per world (roughly 140–170 MB). Past the cap, recording **stops** and one `SEVERE` line is logged; the rollback at the end of that match will be incomplete
  - Why a cap exists: servers typically run a 3 GB heap, and 5 million block records need ~700–850 MB — enough to OOM outright. Stopping at 1M with a warning beats crashing the whole server
  - A normal match will practically never reach it; if your map genuinely needs more, raise the cap alongside your heap size
- Disable with `map-restore.enabled` in `game-settings.yml`

### Item protection

Inventory, XP and health are saved when you enter an arena and restored when you leave. Snapshots are **persisted to disk**, so even after a server crash they are restored when the player logs back in.

## Lobby

Players wait in the lobby after joining the server and enter an arena via `/hilltop join` or `/hilltop gui`.

- **Lobby protection**: block break/place, damage, hunger, weather, item drops and container interaction are all blocked **inside the lobby world**
- **Build mode**: admins can run `/hilltop build` to temporarily lift lobby protection for maintenance (run again to disable)
- **Void protection**: falling out of the lobby world teleports you back to the lobby spawn
- Lobby world and spawn are configured under `lobby.world` / `lobby.spawn` in `arenas.yml`

## Commands

> "Console" column: ✅ = runnable from the server console (admin commands still require OP / the `hilltopvillage.admin` permission, which the console has by default);
> ❌ = must be run by a player in game.

| Command | Description | Console |
|------|------|--------|
| `/hilltop join [arena]` | Join an arena (auto-matches an available one if omitted) | ❌ |
| `/hilltop leave` | Leave the current arena | ❌ |
| `/hilltop gui` | Open the arena selection menu (with status and player counts) | ❌ |
| `/hilltop status [arena]` | View game status (wave/players/mobs/nodes/awaiting respawn) | ✅ |
| `/hilltop stats [player]` | View your own or someone else's stats | ✅ (with a player name) |
| `/hilltop top <type>` | Leaderboards (kills/wins/games/best-wave/nodes/deaths) | ✅ |
| `/hilltop hammer` | Get the Sacred Hammer (Admin; players buy from the shop) | ❌ |
| `/hilltop fireball` | Get a Fireball (Admin; players buy from the shop) | ❌ |
| `/hilltop lang <en\|zh>` | Switch language | ✅ |
| `/hilltop start [arena]` | Start the game (Admin) | ✅ |
| `/hilltop stop [arena]` | Force stop the game (Admin) | ✅ |
| `/hilltop help` | Show command help (Admin) | ✅ |
| `/hilltop list` | List all arenas | ✅ |
| `/hilltop arenas` | Detailed arena status (Admin) | ✅ |
| `/hilltop manage` | Open the arena management GUI: inspect any arena, delete a single node/player-spawn/mob-spawn/villager, edit game parameters, restore initial state (Admin) | ❌ |
| `/hilltop deploy` | Open deploy menu (Admin) | ❌ |
| `/hilltop setup` | 8-step guided setup wizard (Admin) | ❌ |
| `/hilltop config` | Open config center GUI (Admin) | ❌ |
| `/hilltop reloadconfig` | Reload game configs (game-settings.yml + monsters-config.yml + messages.yml) (Admin; arenas.yml still requires restart) | ✅ |
| `/hilltop shop reload` | Reload villager shop config (Admin) | ✅ |
| `/hilltop build` | Toggle lobby build mode (Admin, for lobby maintenance) | ❌ |
| `/hilltop tps` | TPS/MSPT diagnostics (Admin) | ✅ |

### Deploy Sub-commands (`/hilltop deploy`)

| Command | Description |
|------|------|
| `confirmclear` | Confirm clearing all spawn points (the second confirmation for `clearspawns`) |
| `setcore` | Set current location as game core |
| `addspawn` | Add current location as player spawn point |
| `listspawns` | List all player spawn points |
| `delspawn <id>` | Delete a player spawn point by ID |
| `clearspawns` | Clear all player spawn points (requires `confirmclear`) |
| `addmobspawn` | Add current location as mob spawn point |
| `listmobspawns` | List all mob spawn points |
| `delmobspawn <id>` | Delete a mob spawn point by ID |
| `clearmobspawns` | Clear all mob spawn points (requires `confirmclearmob`) |
| `reloadnodes` | Re-scan energy nodes |
| `showparticles` | Show spawn point particle display (30s) |
| `addvillager` | Register your position as a villager (all dead = defeat) |
| `listvillagers` | List all registered villagers |
| `delvillager <id>` | Remove a villager by id |
| `clearvillagers` | Clear all villagers |

### Setup Wizard Sub-commands (`/hilltop setup`)

Available while the wizard is in progress (normally triggered via clickable chat buttons, no typing needed):

| Command | Description |
|------|------|
| `confirm-world` | Confirm game world (Step 1) |
| `confirm-core` | Set current location as village core (Step 2) |
| `addspawn` | Add current location as player spawn point (Step 3, feedback includes index) |
| `spawngui` | Open player spawn point manager GUI (Step 3, reopenable anytime) |
| `spawns-done` | Finish spawn point setup (Step 3, kept for backward compat; use [Done] in the GUI) |
| `addmobspawn` | Add current location as mob spawn point (Step 3, feedback includes index) |
| `mobspawngui` | Open mob spawn point manager GUI (Step 3, reopenable anytime) |
| `confirm-nodes` | Confirm energy node scan result (Step 4) |
| `rescan` | Rescan energy nodes and re-send the Step 4 message (Step 4) |
| `villagers-done` | Finish villager placement and move to the shop step (Step 5, requires at least 1 villager) |
| `confirm-shop` | Set current location as shop villager position and spawn the NPC immediately (Step 6, **required**) |
| `preview` | Open the config preview GUI (Step 7; refused with a jump back if spawns/villagers/shop are missing) |
| `goto <step>` | Jump to a specific step (1-8) |

> Step 3 deploys two independent sets: walk to a player-spawn location and click [Add Current Location] (feedback: "Spawn point #N added"), then walk to a mob-spawn location and click [Add Mob Spawn] (feedback: "Mob spawn point #N added"). [Open Spawn Manager] / [Open Mob Spawn Manager] open 54-slot GUIs (45 entries per page) to review/delete either set at any time. The [Done] button lives in either manager GUI (slot 51) — both sets must have at least 1 point to leave this step.
> Step 4 explains that energy nodes are respawn anchors placed in the world (auto-detected); place more blocks and click [Rescan Nodes] to recount.
> Step 5 villagers are a **defeat condition**: the game is lost if all of them die, so place at least 1. Walk around and click [Add Villager] to record, [Villager Manager] to review, [Done] to continue.
> Step 6 shop location is **required**: a shop villager position must be set before deployment can finish. The shop villager position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on server restart; leftover duplicate shop villager entities are cleaned up automatically on plugin startup.
> `/hilltop start` validates first: if a game is already running it tells you to stop first; if player count is below min-players it reports current/required counts instead of failing silently.

### Config Sub-commands (`/hilltop config`)

| Command | Description |
|------|------|
| `nodes repairitem <material>` | Set node repair item |

## Permissions

| Permission Node | Default | Description |
|------|------|------|
| `hilltopvillage.admin` | OP | Admin permissions (includes all sub-permissions) |
| `hilltopvillage.config.admin` | OP | Config center GUI access |
| `hilltopvillage.config.rules` | false | Game rules configuration |
| `hilltopvillage.config.waves` | false | Wave configuration |
| `hilltopvillage.config.monsters` | false | Monster attribute configuration |
| `hilltopvillage.config.items` | false | Hammer/item configuration |
| `hilltopvillage.config.nodes` | false | Energy node configuration |

## Installation

1. Download the latest JAR and place it in your server's `plugins/` directory
2. Restart the server (or `/plugman load HilltopVillage`)
3. Follow the 6 phases in the **[Deployment Guide](#deployment-guide-server-owner-guide)**

> Requirements: **Paper 1.21.4+** / **Java 21**. On first start the plugin generates `config.yml`, `arenas.yml`, `game-settings.yml`, `messages.yml`, `shop.yml`, `nodes.yml`, `spawnpoints.yml`.

### Generated files

| File | Purpose |
|------|---------|
| `config.yml` | Language and basic settings |
| `arenas.yml` | **Multi-arena definitions + lobby settings** (the core config) |
| `game-settings.yml` | All game parameters (editable via the `/hilltop config` GUI) |
| `messages.yml` | Multi-language text |
| `shop.yml` | Shop items and prices |
| `nodes.yml` | Energy node coordinates (written by the setup wizard) |
| `spawnpoints.yml` | Legacy migration source for shop villager location (no longer written; the live value is `arenas.<id>.shop-npc` in `arenas.yml`) |
| `player-goods.yml` | Player item snapshots (crash protection, automatic) |
| `player-stats.yml` | Player statistics (automatic) |

## Configuration

### arenas.yml — arenas and lobby (core config)

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

### game-settings.yml — game parameters

Editable through the `/hilltop config` GUI, or edit the file and run `/hilltop reloadconfig`.

```yaml
# Basics
min-players: 2                   # players required to start (only applies to arenas that do NOT set it in arenas.yml — see note below)
max-players: 6                   # max players per match
victory-waves: 20                # waves needed to win
wave-interval-seconds: 30        # rest time between waves
wave-timeout-seconds: 120        # stall safeguard: if a wave stalls, stuck mobs are pulled to players and highlighted, then force-cleared after a 15s grace, 0=disable

# Added in phases 1-3
defeat-countdown-seconds: 20     # time from battle report to teleport back to lobby
villager-health: 20.0            # villager max health (all dead = defeat)
rejoin-timeout-seconds: 60       # reconnect window, 0 = disable reconnecting

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
```

# Starter kit (granted to every player on match start; overflow drops at their feet)
starter-kit:
  enabled: true                  # master switch: false = grant nothing
  items:                         # material = vanilla Material name, amount = count
    - material: STONE_SWORD
      amount: 1
    - material: BREAD
      amount: 16
    - material: TORCH
      amount: 8

### config.yml

Internal state file — it holds **no game settings** (those live in `game-settings.yml`), only the language and setup-wizard progress. Maintained automatically by the plugin.

```yaml
language: "zh"    # zh / en
```

See the individual files under `plugins/HilltopVillage/` for the complete reference — each ships with inline comments.

## Special Monsters

| Monster | Entity Type | Ability |
|------|------|------|
| **Explode Beetle** | Cave Spider | Rushes energy nodes and self-destructs, dealing massive node damage (`monsters.explode-beetle.entity-explosion` can disable the attached entity explosion, keeping node damage only) |
| **Hook Claw Hunter** | Skeleton | Fires hooks at range, teleporting players and applying blindness + slowness |
| **Flying Dropper** | Phantom | Flies above players and airdrops zombie squads |

> The table above lists the default entity types; they can be changed via `entity-type` in `monsters-config.yml` (e.g. set `explode-beetle` to `ZOMBIE`), then run `/hilltop reloadconfig`.

## Dependencies

- Paper 1.21.4+
- Java 21
- ItemsAdder (optional, for custom item/monster models)

## Build

```bash
mvn clean package
```

The JAR file will be generated in the `target/` directory.

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/).

Any use, modification, or distribution must retain the `LICENSE` and `NOTICE` files in the project root directory, and ensure their contents remain intact.

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
 </picture>
</a>