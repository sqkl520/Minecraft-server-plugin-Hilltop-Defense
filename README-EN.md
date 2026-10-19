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
- **Villager Shop** — Every wave-monster kill drops Energy Crystals (1–2 by default, amount range configurable, and varies by monster type — tougher monsters are worth more); right-click the shop villager to buy items; everything customizable in `shop.yml`; equipment progression line: Iron Sword → Diamond Sword → Netherite Sword, plus iron/diamond armor pieces (leather armor granted at match start)
- **Special Monster AI** — Explode Beetle (rushes nodes to self-destruct; its entity explosion can be toggled off), Hook Claw Hunter (ranged hook grab + blindness), Flying Dropper (airdrops zombie squads); each supports armor/weapons/custom models

**Architecture & Quality of Life**

- **Multi-Arena Support** — A lobby plus multiple independent arena worlds, running concurrent matches without interference; players pick an arena via `/hilltop gui`
- **Lobby System** — Everyone lands in the lobby on join; block break/place, damage, hunger and weather are blocked there; admins can lift protection with `/hilltop build`
- **Sidebar Scoreboard** — Live wave progress, alive players, awaiting respawn, nodes, villagers, personal kills/deaths and crystals gained this match
- **Player Stats & Leaderboards** — Win rate, K/D, best wave and more are persisted; `/hilltop stats` and `/hilltop top`
- **Item Protection** — Inventory/XP/health saved on entering an arena and restored on leaving; snapshots are persisted to disk, so even a server crash won't lose them
- **Reconnect Window** — Disconnecting mid-match keeps a 60-second (configurable) rejoin window
- **Map Restore** — Block changes made during a match are rolled back on reset, so maps don't degrade game after game
- **Graphical Config Center** — Configure game rules, waves, monsters, items and nodes through an intuitive GUI
- **8-Step Setup Wizard** — `/hilltop setup` walks you through core/player-spawns+mob-spawns/nodes/villagers/shop, auto-saving each step
- **Multi-language Support** — Built-in Chinese and English; players can switch with `/hilltop lang <en|zh>` (the preference lasts for the session; admins can change the server default)
- **Location Particle Preview** — Trigger a 30-second particle animation from the setup wizard / management GUI / commands; each of the five location types has its own color: player spawn=green, mob spawn=red, node=blue, villager=yellow, shop=gold

## Energy Nodes Explained

Energy nodes use **Respawn Anchor** blocks. Admins place multiple respawn anchors on the map (4-5 recommended) and **register them manually as energy nodes in Step 4 of the setup wizard** (walk to the anchor and click [Add Node]) — **at least 1 node must be registered**, otherwise once the match starts any player death immediately ends the match in team defeat and nobody can respawn. The node block type is **fixed to the Respawn Anchor** (`RESPAWN_ANCHOR`); this is by design and cannot be changed:

| Block | Material |
|-------|----------|
| Respawn Anchor | RESPAWN_ANCHOR |

> Respawn Anchors keep their **full vanilla Minecraft functionality** (charging with glowstone, setting your respawn point) — the plugin merely **registers them additionally as energy nodes**, layering its buff aura / destructible / repairable mechanics on top.

**Node mechanics:**

- **Buff aura** — Each active node continuously grants **Resistance I + Regeneration** to players within a 20-block radius (configurable); players near nodes take less damage and regenerate faster. **Active node = a node with health above 0**: all nodes are registered as active when they are added; once monsters deplete their health they deactivate (aura disappears), and they reactivate after being repaired with World Tree Sap
- **Destructible** — Nodes have health (default 100); monster attacks and Explode Beetle self-destructs reduce node health; at zero health the node deactivates and its aura disappears
- **Repairable** — Right-click a deactivated node with "World Tree Sap" (default GHAST_TEAR, dropped by Nether ghasts; also purchasable as the World Tree Sap shop item; material configurable via `nodes.repair-cost-item` in game-settings.yml, or via `/hilltop config` → Node Settings) to restore 50 health (consumes 1 per use)
- **Add/remove nodes** — To add nodes, place more respawn anchors in the world, then walk to the anchor and click **[Add Node]** in Step 4 of the setup wizard (nodes must be registered manually — **at least 1 required**; a match with 0 nodes ends in instant team defeat on the first death and players cannot respawn). To remove, click the node entry in the Nodes Manager GUI. Node blocks are protected during matches (cannot be mined) and can be freely broken while deploying.

## Shop System

Every wave-monster kill **drops 1–2 Energy Crystals** (currency; the drop-chance gate `shop.currency-drop-chance` defaults to 1.0 = always drop, and the amount range is controlled by `shop.currency-drop-min` / `shop.currency-drop-max`, default 1–2 — all configurable in `game-settings.yml`). Airdropped zombies **never drop crystals** (anti-AFK design). **Drops vary by monster type**: Explode Beetle 1, Hook Claw Hunter 1–2, Flying Dropper 2–3 (the harder the monster, the more it's worth) — configure `drop-min` / `drop-max` per monster in the `monsters` section of `game-settings.yml` (`-1` falls back to the global value, `0/0` means that monster drops nothing). The end-of-match battle report, the sidebar, `/hilltop stats` and `/hilltop top crystals` show your crystals gained this match / all time. Walk up to the shop villager and **right-click** it to open the shop GUI; left-click an item icon to purchase.

**Economy loop**: monsters drop crystals → right-click the shop villager to buy items/World Tree Sap → right-click deactivated nodes with sap to repair → nodes resume buffing the defenders.

**Shop villager deployment**:

- Setup wizard Step 6 (`/hilltop setup`): stand at the target spot and click [Set Shop Location] (required — deployment cannot finish without a shop location). After setting you stay on the step: click [Set Shop Location] again to move it, click [Clear Shop Location] to wipe it and start over (you stay on Step 6), then click [Continue] to open the preview
- Commands work too: `/hilltop deploy setshop` sets your position and spawns the NPC, `/hilltop deploy clearshop` clears this arena's shop (stored location + entity); the `/hilltop manage` detail page also clears the shop on right-click
- Clearing is **refused while a match is in progress** (the shop NPC is a plugin-level singleton — clearing would take the shop away from players mid-match); run `/hilltop stop` first
- The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on restart; leftover duplicate entities are cleaned up automatically on startup
- The shop villager is invulnerable (all damage cancelled) and non-interactive — it exists purely as the shop entry
- Facing is horizontal only: deployment copies just the player's yaw and forces pitch to 0 (the villager never looks down/up; even a pitch written wrong in an old save is auto-corrected on restart), and once spawned it turns horizontally to face the nearest player within 8 blocks

**Custom items in shop.yml** (`plugins/HilltopVillage/shop.yml`):

The shop ships with 16 items by default (54-slot GUI, laid out in sections): Tools/Consumables (Sacred Hammer 5 / Fireball x8 3 / World Tree Sap x4 2 / **Bow 15 / Arrows x8 2** — the new bow line fills the gap of having no ranged option against the flying Dropper), Weapons (**Iron Sword 8 / Diamond Sword 25 / Netherite Sword 48**), and Armor (**iron pieces 6 each / diamond pieces 14 each**, sold piece by piece). All equipment is **vanilla blank** (no enchantments — enchanting is planned for the next version). **A leather armor set is granted automatically at match start** (alongside the stone sword/bread/torches — see the `starter-kit` section of `game-settings.yml`).

```yaml
shop:
  currency:
    material: EMERALD      # currency item material
    name: "&aEnergy Crystal" # currency display name (color codes supported)
  npc:
    name: "&6Shop Villager" # NPC overhead name
  gui:
    title: "&8Village Shop" # GUI title (empty = language file shop.gui.title)
    size: 54               # GUI size (multiple of 9, max 54; default 54 = 6 rows)
  items:
    sacred_hammer:
      slot: 10             # GUI slot (0 ~ size-1)
      price: 5             # price in Energy Crystals
      type: HAMMER         # HAMMER / FIREBALL / REPAIR_ITEM / MATERIAL / EQUIPMENT
      amount: 1            # quantity per purchase (HAMMER always 1, ignored)
      icon:
        material: NETHERITE_AXE
      name: "&6Sacred Hammer" # display name (color codes supported)
      lore:                # lore lines (price line appended automatically)
        - "&7Right-click air to charge, land to smash"
    iron_sword:
      slot: 11             # equipment item example
      price: 8
      type: EQUIPMENT      # equipment: delivers 1 item of icon.material
      icon:
        material: IRON_SWORD # EQUIPMENT: required — both the icon and the delivered item material
      name: "&fIron Sword" # name/lore are written onto the item the player receives
      lore:
        - "&7Hits harder than stone — your first weapon upgrade"
      unbreakable: false   # optional: true = unbreakable (omit for normal durability)
```

- Every item entry, price, name and durability is customizable in `shop.yml` — **server owners can freely add/remove items and change prices or slots**
- `type` values: `HAMMER` (Sacred Hammer, always 1 regardless of amount) / `FIREBALL` / `REPAIR_ITEM` (World Tree Sap) / `MATERIAL` (any vanilla item, gives `amount` of `icon.material`) / `EQUIPMENT` (delivers 1 item of `icon.material`; `icon.material` is **required** = the delivered item material, `name` / `lore` are optional but are written onto the delivered item, `unbreakable` optional = unbreakable)
- Run `/hilltop shop reload` (admin) after editing — no restart needed
- Anti-exploit: all clicks and drags inside the shop GUI are cancelled (icons can't be taken), and purchases have a 250ms cooldown against double-click double-charging
- Currency anti-spoofing: Energy Crystals are identified by an item PDC tag, so **renaming a plain emerald to the currency name with an anvil/name tag will NOT count as money**. To hand out currency manually, use `/hilltop shop give <player> [amount]` (emeralds from vanilla `/give` lack the tag and are not accepted)

> **Upgrade note for existing servers**: `shop.yml` uses "**file exists = template not released**" — after upgrading, if the server already has an old `shop.yml`, the **new items (bow/arrows/swords/armor) will NOT appear automatically**. Update the server's `shop.yml` with the new template (manually add the item entries, or back up and delete the old file to let it regenerate and then re-apply your customizations), then run `/hilltop shop reload`.


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

### Phase 2.5: Enter the arena world

This plugin has **no** command to teleport you into an arena world (`setup`, `deploy` and other deploy commands all require you to already be standing in the target world), so for your first deployment, get there yourself with any of:

| Method | Command | Notes |
|------|---------|-------|
| Vanilla teleport (OP required) | `/execute in hilltop_arena1 run tp @s 100 64 100` | Replace `hilltop_arena1` with your arena world name and the coordinates with your map center. Fly first (`/gamemode creative`) so you don't fall into unloaded chunks |
| Multiverse (if installed) | `/mvtp hilltop_arena1` | Teleports to that world's spawn |
| Warp plugin (e.g. MyWarp, if installed) | Per that plugin's usage | Set a landmark in the arena world — handy when deploying multiple arenas later |

> Once you're there, fly around and check two things: ① this really is the map you prepared (don't deploy in the wrong world); ② you're in creative mode (`/gamemode creative`) — you'll need it for placing respawn anchors, villagers and terrain tweaks. Step 1 of the wizard shows your current world name; cancel and re-teleport if it doesn't match.

### Phase 3: Run the setup wizard

Go to the **arena world** (see Phase 2.5) and run:

```
/hilltop setup
```

> The wizard targets the arena **based on the world you are standing in** (any arena, not just the default),
> so you can deploy even when that arena has no core yet and no running instance. If you are not standing in a
> configured arena world (see `arenas.yml`), the wizard tells you so (`You are not standing in any configured
> arena world`) and how to teleport in. After you finish Step 8 the arena is **added to the enable queue
> automatically** (when `enabled: true`); once loading finishes players can `/hilltop join` and admins can
> `/hilltop start`. If the arena is `enabled: false` in `arenas.yml` (`arena2` / `arena3` ship that way),
> set it to `true` and **restart the server** (it queues on startup). Note that `/hilltop enable <arena>`
> **cannot bypass this switch** — arenas with `enabled: false` are rejected; manual queueing only works
> for deployed arenas with `enabled: true`.

| Step | Content | Action |
|------|---------|--------|
| 1/8 Confirm world | Shows your current world name | Click [Confirm] |
| 2/8 Set core | Stand at the village core location (the player respawn point) | Click [Set as Core] |
| 3/8 Spawn points | Player spawns + mob spawn points (two independent sets, **at least 1 each**) | Walk to each spot, click [Add Current Location] (player) / [Add Mob Spawn] (mobs) in chat; click [Open Spawn Points Overview] to enter the hub (counts/status at a glance, two sub-manager GUIs for review/delete); click the single [Done] in the overview (both sets must be deployed to leave this step) |
| 4/8 Node registration | Registering energy nodes (respawn anchors) (**at least 1 required**) | **Place the respawn anchor blocks first**, stand next to one and click [Add Node] (a hint appears if the block cannot be registered); click [Continue] to move on |
| 5/8 Villagers | Positions of villagers to protect (**all dead = game lost**, **at least 1 required**) | Walk to a spot and click [Add Villager] — an invulnerable preview villager appears on the spot (numbered overhead, visible during this step only, removed when you leave it); click [Open Villager Manager] to review/delete ([Show Particles] inside previews all villagers in yellow); click [Done] when finished |
| 6/8 Shop location | Where the villager shop NPC stands (**required — deployment cannot finish without it**) | Stand where you want it and click [Set Shop Location]; [Show Particles] previews it in gold; [Clear Shop Location] wipes it to start over, [Continue] moves on |
| 7/8 Config preview | Overview of core/player-spawns/mob-spawns/nodes/villagers/players/waves | Click [Confirm & Save] in the GUI |
| 8/8 Done | Shows the start entry plus an enable hint (auto-queue outcome; an [Enable Arena] button is attached when not auto-enabled) | Click /hilltop start in chat |

> **Redoing an earlier step**: `/hilltop setup goto <step>` (1-8) jumps back to any step to redo it.

> **Particle color guide**: every location preview uses a colored beam + spiral, one color per location type — player spawn=**green**, mob spawn=**red**, energy node=**blue**, protected villager=**yellow**, shop=**gold**. This applies to the Step 3 manager GUIs, the Step 4 node GUI, the Step 5 villager GUI, the Step 6 shop button, `/hilltop deploy showparticles`, and the Shift+Left-click preview in the arena management GUI (`/hilltop manage`).

**The three key concepts**

| Concept | Role | Consequence when destroyed |
|---------|------|---------------------------|
| **Core** | The player **respawn point**; has no health | None (just a respawn coordinate) |
| **Energy node** (respawn anchor) | The real "health bar": buffs nearby players with Resistance + Regeneration; monsters attack it, players repair it | **Once all are destroyed, fallen players cannot respawn** |
| **Villagers** | Must be protected; the count is however many you placed | **If every villager dies, the game is lost** |

> **Required items**: player spawn points, mob spawn points, villagers and the shop location — all four are mandatory. If any is missing, clicking [Confirm & Save] is rejected and the wizard jumps back to the corresponding step.

### Phase 4: Tune the game settings

```
/hilltop config
```

Adjust player counts, waves, monster attributes, item parameters, node and villager health through the GUI. Click [Confirm & Save] — **new settings apply at the start of the next match**.

You can also edit `plugins/HilltopVillage/game-settings.yml` directly and run `/hilltop reloadconfig`.

### Arena maintenance (enable / disable)

Arenas use a **BedWars1058-style "enable queue"** model, so you can take a single arena down for maintenance without affecting the others:

```
/hilltop disable <arena>    # shut the arena down (maintenance)
/hilltop enable  <arena>    # re-enable it (joins the enable queue)
```

| Command | Behavior |
|---------|----------|
| `/hilltop disable` | Kicks players out → removes it from the joinable list → status becomes "disabled". **Refused while a match is in progress** (wait for the match to end) |
| `/hilltop enable` | Joins the **enable queue** → loads the world → initializes → status becomes "ready", only then can players join |

**The enable queue is processed serially** — one arena is loaded at a time, and the next is handled automatically once loading finishes, so several worlds are never loaded at once and the server stays responsive (same as BedWars1058's `enableQueue`).

> **Enabling right after disabling takes a few seconds**: `/hilltop disable` only stops the match — the previous match's delayed reset and map rollback still need to finish. To keep the old instance's reset from wiping the new match, the plugin puts the arena into a **quarantine window** (default: `arenas.yml`'s `settings.arena-reset-delay` + 2 seconds; if the arena was disabled during its settlement phase, the `defeat-countdown-seconds` value is added on top). Running `/hilltop enable` before the window ends **does not fail** — it queues and retries automatically, with a single console notice.

### Arena Management GUI (`/hilltop manage`)

Admins run `/hilltop manage` to open a 54-slot menu listing **every configured arena** — including ones with `enabled: false`, a missing world folder, or that were never deployed (those are flagged as missing core coordinates, and the [Enable] button will refuse until you run `/hilltop setup`).

| View | What you can do |
|------|-----------------|
| **List** | See all arenas; left-click to open the detail page |
| **Detail** | Status / world / players / waves / deployment counts / shop location / parameter summary; enable, disable, teleport to core, open the sub-lists, **right-click the shop entry to clear the shop NPC** (location + entity; refused mid-match) |
| **Player-spawn / Mob-spawn / Node / Villager lists** | 45 entries per page; **left-click to teleport**, **right-click to delete** one entry, **Shift+Left-click** to preview its position with particles (color matches the list type: spawn green / mob spawn red / node blue / villager yellow) |
| **Game parameters** | Edit that arena's `min-players` / `max-players` / `victory-waves` / `wave-interval-seconds`; left-click and type the new value in chat |
| **Restore initial state** | Wipe all deployment data + game parameters of that arena and force-rollback the map blocks, so you can redeploy from scratch |

> **What "Restore initial state" does**: deletes the arena's core coordinates, all player spawn points, all mob spawn points, all villagers, all energy nodes, the shop NPC (both the stored location and the live entity), plus the per-arena game parameters and radius offsets, and force-rolls back the world's map blocks. It **keeps** the world, display name and `enabled` flag, so the arena stays in the list as "not deployed" afterwards. There is a confirmation page (listing exactly what will be cleared plus the real player count on site) and a second cancel button. **Refused while a match is in progress** — run `/hilltop stop <arena>` first. Because the core coordinates are removed, the arena is not re-enabled automatically; redeploy it and then `/hilltop enable`.

> **Parameter edits need no restart**: changes made through the management GUI are written to `arenas.yml` immediately and pushed to the running arena — player counts apply from the next match, wave interval and victory waves apply from the next intermission. **Editing `arenas.yml` by hand still requires a server restart** (same as the other arena settings). Edits are refused while a match is in progress (including the end-of-match countdown), so raising the player floor mid-match can't trigger an auto-defeat.

> **Deleting one entry uses the "filtered ordinal"**: entries with broken coordinates are skipped when reading, so the numbering you see in the GUI may not match the raw line numbers in `arenas.yml`. Always delete from the GUI rather than editing the file using the GUI's numbers.

> **One world can back only one arena**: the plugin checks world uniqueness when loading `arenas.yml`; definitions that reuse a world are rejected with an explicit warning (world names are case-insensitive). This constraint is what makes map rollback correct — rollback journals and restore tasks are keyed by world name, so two arenas sharing one world would roll back each other's ongoing matches.

> **The player minimum (`min-players`) is authoritative per arena**: `arenas.yml`'s `game.min-players` is that arena's value (e.g. set an arena to `1` for solo play); arenas without an explicit value inherit the global `min-players` from `game-settings.yml`. Note: the `min-players` in the config GUI is the **global default** and has no effect on arenas that spell it out in `arenas.yml`. Two ways to change it — the parameter page in `/hilltop manage` (**takes effect immediately**, for the next match), or edit `arenas.yml` and **restart the server**.

> **Shop NPCs are managed by the plugin**: the shop villagers in the lobby and in each arena are spawned by the deployment wizard and cleaned up by server lifecycle. **The end-of-match map rollback never touches any NPC**, so shops stay available between matches; only "Restore initial state" in `/hilltop manage` clears the shop NPC (location + entity) **of that arena's world**, leaving the lobby and other arenas untouched.

**Arena lifecycle states** (visible via `/hilltop arenas`):

| State | Meaning |
|-------|---------|
| **Disabled** | `enabled: false`, or shut down via `/hilltop disable` |
| **Queued for enabling** | Waiting in the enable queue |
| **Loading...** | Loading the world / initializing |
| **Ready** | Can be joined, can start |
| **Not deployed** | The world exists, but spawn points are not configured yet (run `/hilltop setup`) |
| **Map missing** | Configured, but the world save does not exist |

> Only "ready" arenas can auto-start — an undeployed/disabled arena will not start even with players inside.

### Phase 5: Verify the deployment

| Check | Command | Expected |
|-------|---------|----------|
| Arena loaded | `/hilltop list` | Your arena is listed |
| Spawn points set | `/hilltop deploy listspawns` | At least 1 player spawn point |
| Mob spawn points set | `/hilltop deploy listmobspawns` | At least 1 mob spawn point |
| Nodes registered | `/hilltop deploy` menu | Known nodes > 0 |
| Villagers registered | `/hilltop deploy listvillagers` | At least 1 villager |
| Shop set | `/hilltop deploy setshop`, then check on site | Shop villager spawned; use `/hilltop deploy clearshop` to start over |
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
/hilltop deploy reloadnodes    # reload the node registry from nodes.yml (does not scan blocks)
/hilltop deploy addvillager    # stand at each villager location (repeatable)
/hilltop deploy listvillagers  # list all registered villagers
/hilltop deploy setshop        # stand at the shop spot (spawns the shop NPC immediately)
/hilltop deploy clearshop      # clear this arena's shop (refused mid-match)
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

> Want to leave mid-match? `/hilltop leave` returns you to the lobby; it works while spectating after death too (the spectate notice includes a clickable button).

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
  Crystals gained: 12
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

Leaderboard types: `kills` · `wins` · `games` · `best-wave` · `nodes` · `deaths` · `crystals`

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
- **Setting the lobby spawn**: an admin standing in the lobby world can run `/hilltop lobby setspawn` to save their current position (including facing) as the lobby spawn in `arenas.yml` — it **takes effect immediately**: new logins, lobby respawns and `/hilltop lobby` all use the new point, no restart needed

## Commands

> "Console" column: ✅ = runnable from the server console (admin commands still require OP / the `hilltopvillage.admin` permission, which the console has by default);
> ❌ = must be run by a player in game.

| Command | Description | Console |
|------|------|--------|
| `/hilltop join [arena]` | Join an arena (auto-matches an available one if omitted) | ❌ |
| `/hilltop leave` | Leave the current arena | ❌ |
| `/hilltop gui` | Open the arena selection menu (with status and player counts) | ❌ |
| `/hilltop lobby` | Return to the lobby (sub-command `setspawn`: run it while standing in the lobby world to set the lobby spawn to your current position, effective immediately — Admin) | ❌ |
| `/hilltop status [arena]` | View game status (wave/players/mobs/nodes/awaiting respawn) | ✅ |
| `/hilltop stats [player]` | View your own or someone else's stats | ✅ (with a player name) |
| `/hilltop top <type>` | Leaderboards (kills/wins/games/best-wave/nodes/deaths/crystals) | ✅ |
| `/hilltop hammer` | Get the Sacred Hammer (Admin; players buy from the shop) | ❌ |
| `/hilltop fireball` | Get a Fireball (Admin; players buy from the shop) | ❌ |
| `/hilltop lang <en\|zh>` | Switch language (players change their own session language; admins/console change the server default and persist it) | ✅ |
| `/hilltop start [arena]` | Start the game (Admin) | ✅ |
| `/hilltop stop [arena]` | Force stop the game (Admin) | ✅ |
| `/hilltop wave clear [arena]` | Force-clear all mobs of the current wave and finish it (enters intermission; cleared mobs produce no drops and don't count as kills) (Admin, `hilltopvillage.admin`) | ✅ |
| `/hilltop wave skip [arena]` | Skip the intermission and start the next wave now (while RUNNING it equals clear-wave + skip in one step; refused without judging a loss if there aren't enough players) (Admin, `hilltopvillage.admin`) | ✅ |
| `/hilltop help` | Show command help (Admin) | ✅ |
| `/hilltop list` | List all arenas | ✅ |
| `/hilltop arenas` | Detailed arena status (Admin) | ✅ |
| `/hilltop manage` | Open the arena management GUI: inspect any arena, delete a single node/player-spawn/mob-spawn/villager/shop NPC (right-click the shop entry on the detail page), edit game parameters, restore initial state (Admin) | ❌ |
| `/hilltop enable [arena]` | Add an arena to the enable queue (Admin) | ✅ |
| `/hilltop disable [arena]` | Disable an arena for maintenance (Admin) | ✅ |
| `/hilltop deploy` | Open deploy menu (Admin) | ❌ |
| `/hilltop setup` | 8-step guided setup wizard (Admin) | ❌ |
| `/hilltop config` | Open config center GUI (Admin) | ❌ |
| `/hilltop reloadconfig` | Reload game configs (game-settings.yml + monsters-config.yml + messages.yml) (Admin; arenas.yml still requires restart) | ✅ |
| `/hilltop shop reload` | Reload villager shop config (Admin) | ✅ |
| `/hilltop shop give <player> [amount]` | Give shop currency (Energy Crystals; default 1, max 6400) to an online player (Admin; works in any world, including the lobby) | ✅ |
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
| `reloadnodes` | Reload the node registry from nodes.yml (no block scanning; register nodes via Step 4 [Add Node]) |
| `showparticles` | Show particle display at all player spawn points (30s, green beams) |
| `addvillager` | Register your position as a villager (all dead = defeat) |
| `listvillagers` | List all registered villagers |
| `delvillager <id>` | Remove a villager by id |
| `clearvillagers` | Clear all villagers |
| `setshop` | Set the shop NPC to your position and spawn it immediately |
| `clearshop` | Clear this arena's shop NPC (location + entity; refused mid-match — run `stop` first) |

### Setup Wizard Sub-commands (`/hilltop setup`)

Available while the wizard is in progress (normally triggered via clickable chat buttons, no typing needed):

| Command | Description |
|------|------|
| `confirm-world` | Confirm game world (Step 1) |
| `confirm-core` | Set current location as village core (Step 2) |
| `addspawn` | Add current location as player spawn point (Step 3, feedback includes index) |
| `overview` | Open the Spawn Points Overview GUI (Step 3 hub: counts/status of both sets + two sub-manager entries + the single [Done]) |
| `spawngui` | Open player spawn point manager GUI (Step 3, direct; [Back to Overview] returns to the hub) |
| `spawns-done` | Finish spawn point setup (Step 3, kept for backward compat only; same logic as [Done] in the overview) |
| `addmobspawn` | Add current location as mob spawn point (Step 3, feedback includes index) |
| `mobspawngui` | Open mob spawn point manager GUI (Step 3, direct; [Back to Overview] returns to the hub) |
| `confirm-nodes` | Confirm energy node setup (Step 4, requires at least 1 node) |
| `rescan` | Deprecated (only prints a notice; use the [Open Nodes Manager] GUI) |
| `villagers-done` | Finish villager placement and move to the shop step (Step 5, requires at least 1 villager) |
| `confirm-shop` | Set current location as shop villager position and spawn the NPC immediately (Step 6, **required**; you stay on the step and can overwrite / clear) |
| `showshopparticles` | Preview the shop location with a gold particle beam (Step 6; tells you if not set yet) |
| `clear-shop` | Clear this arena's shop location and NPC (Step 6; you stay on the step; refused mid-match) |
| `preview` | Open the config preview GUI (Step 7; refused with a jump back if spawns/villagers/shop are missing) |
| `goto <step>` | Jump to a specific step (1-8) |

> Step 3 deploys two independent sets: walk to a player-spawn location and click [Add Current Location] (feedback: "Spawn point #N added"), then walk to a mob-spawn location and click [Add Mob Spawn] (feedback: "Mob spawn point #N added"). [Open Spawn Points Overview] opens the Step 3 hub: counts and status of both sets, plus entries into the two 54-slot sub-manager GUIs (45 entries per page, each with [Back to Overview]). The hub carries the single [Done] button (green when both sets have ≥1 point) — both sets must have at least 1 point to leave this step.
> Step 4 explains that energy nodes are respawn anchors placed in the world (**registered manually** — walk to the anchor and click [Add Node]; at least 1 required; a match with 0 nodes ends in instant team defeat on the first death and players cannot respawn). The [Open Nodes Manager] GUI lets you review/delete registered nodes.
> Step 5 villagers are a **defeat condition**: the game is lost if all of them die, so place at least 1. Walk around and click [Add Villager] to record — each recorded villager spawns an invulnerable, non-collidable preview villager at that spot (numbered overhead, e.g. “Villager #3”) so you can see exactly where they are; these previews live only during Step 5 and are removed the moment you leave it. [Open Villager Manager] opens the review GUI (its [Show Particles] previews all villagers in yellow); click [Done] to continue.
> Step 6 shop location is **required**: a shop villager position must be set before deployment can finish. Stand where you want it and click [Set Shop Location] (you stay on the step and can overwrite / clear); [Clear Shop Location] wipes it to start over and [Continue] opens the preview. The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on server restart; leftover duplicate shop villager entities are cleaned up automatically on plugin startup. `/hilltop deploy setshop` / `/hilltop deploy clearshop` and right-clicking the shop entry on the `/hilltop manage` detail page offer the same set / clear entry points (clearing is refused mid-match — run `stop` first).
> `/hilltop start` validates first: if a game is already running it tells you to stop first; if player count is below min-players it reports current/required counts instead of failing silently.

> **Access protection**: arena worlds are guarded — players who have not joined the match cannot teleport in via `/tp` or `/execute in` (they'll see "you have not joined this arena"); admins are exempt (for deployment). `/hilltop join` is unaffected.

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
wave-timeout-seconds: 0          # wave-timeout safeguard: off by default (0); handle abnormal situations manually with /hilltop wave clear or wave skip. Set it back to a positive value (e.g. 120) to re-enable the automatic safeguard (stuck mobs pulled to players + highlighted, then force-cleared after a 15s grace)

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