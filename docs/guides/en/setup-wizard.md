# Setup Wizard: Step-by-Step Reference

> Moved from the English README. The README keeps a summary and links here.

## Running the wizard

Go to the **arena world** (see Phase 2.5 in `deploy-stages-1-2.md`) and run:

```
/hilltop setup
```

## Which arena the wizard targets

> The wizard targets the arena **based on the world you are standing in** (any arena, not just the default),
> so you can deploy even when that arena has no core yet and no running instance. If you are not standing in a
> configured arena world (see `arenas.yml`), the wizard tells you so (`You are not standing in any configured
> arena world`) and how to teleport in. After you finish Step 8 the arena is **added to the enable queue
> automatically** (when `enabled: true`); once loading finishes players can `/hilltop join` and admins can
> `/hilltop start`. If the arena is `enabled: false` in `arenas.yml` (`arena2` / `arena3` ship that way),
> set it to `true` and **restart the server** (it queues on startup). Note that `/hilltop enable <arena>`
> **cannot bypass this switch** — arenas with `enabled: false` are rejected; manual queueing only works
> for deployed arenas with `enabled: true`.

## The 8 steps in full

| Step | Content | Action |
|------|---------|--------|
| 1/8 Confirm world | Shows your current world name | Click [Confirm] |
| 2/8 Set core | Stand at the village core location (the player respawn point) | Click [Set as Core] (advances to step 3); the waiting area is optional, see "Waiting area in step 2" below |
| 3/8 Spawn points | Player spawns + mob spawn points (two independent sets, **at least 1 each**) | Walk to each spot, click [Add Current Location] (player) / [Add Mob Spawn] (mobs) in chat; click [Open Spawn Points Overview] to enter the hub (counts/status at a glance, two sub-manager GUIs for review/delete); click the single [Done] in the overview (both sets must be deployed to leave this step) |
| 4/8 Node registration | Registering energy nodes (respawn anchors) (**at least 1 required**) | **Place the respawn anchor blocks first**, stand next to one and click [Add Node] (a hint appears if the block cannot be registered); click [Continue] to move on |
| 5/8 Villagers | Positions of villagers to protect (**all dead = game lost**, **at least 1 required**) | Walk to a spot and click [Add Villager] — an invulnerable preview villager appears on the spot (numbered overhead, visible during this step only, removed when you leave it); click [Open Villager Manager] to review/delete ([Show Particles] inside previews all villagers in yellow); click [Done] when finished |
| 6/8 Shop location | Where the villager shop NPC stands (**required — deployment cannot finish without it**) | Stand where you want it and click [Set Shop Location]; [Show Particles] previews it in gold; [Clear Shop Location] wipes it to start over, [Continue] moves on |
| 7/8 Config preview | Overview of core/player-spawns/mob-spawns/nodes/villagers/players/waves | Click [Confirm & Save] in the GUI |
| 8/8 Done | Shows the start entry plus an enable hint (auto-queue outcome; an [Enable Arena] button is attached when not auto-enabled) | Click /hilltop start in chat |

### Waiting area in step 2 (optional, no extra step)

Step 2 sends two buttons in chat: `[Set as Core]` and `[Set as Waiting Area] (optional)`.

| Button | Command | Writes | Advances? |
|--------|---------|--------|-----------|
| `[Set as Core]` | `/hilltop setup confirm-core` | `arenas.<id>.core` | ✅ goes to step 3 |
| `[Set as Waiting Area]` | `/hilltop setup confirm-waiting` | `arenas.<id>.waiting-location` (`x/y/z/yaw/pitch`) | ❌ stays on step 2 |

- **What the waiting area is**: where players stand after `/hilltop join` while the start countdown runs
  (`ArenaDefinition.getWaitingLocation()`, which backs `ArenaInstance.getLobbySpawn()`). When it is not set,
  the runtime falls back to the core, so players wait on the respawn point.
- **The two buttons are independent and order-free**: waiting-then-core and core-then-waiting are both just
  two separate writes. After `[Set as Waiting Area]` the button row is re-sent so you can still click
  `[Set as Core]`.
- **Optional, never blocking**: at step 8 a `setup.done-no-waiting` reminder is appended when the waiting area
  is unset or identical to the core (click it to run `/hilltop setup goto 2`). Deployment still completes.
- **Equivalent entry point**: `/hilltop deploy setwaiting` from anywhere (refused while a match is running).
- **"Restore initial state" does not clear it**: `waiting-location` is deliberately absent from
  `ArenaManager.DEPLOYMENT_KEYS`, on the same level as `world` / `enabled` — resetting an arena keeps it
  (if you delete it yourself, the runtime falls back to the new core).

### Step 8 also reminds you about the lobby spawn

The 8-step wizard **only writes arena data** — the `lobby` section of `arenas.yml` is never touched. So after
`setup.done-next`, the Step 8 banner appends a `setup.done-lobby` reminder when the current configuration matches:

| Trigger | Note |
|---------|------|
| `lobby.world` is empty | The lobby silently falls back to the main world |
| The world named in `lobby.world` does not exist / is not loaded | Same, and that world can never be entered |
| `lobby.spawn` is still the factory placeholder `0.5 / 100.0 / 0.5` | The factory coordinates are main-world based — players may land in mid-air |

The reminder names the exact command `/hilltop lobby setspawn` (click to fill in chat) and says what happens if you skip it. **It is a reminder, not a blocker** — deployment still counts as complete and the arena is still queued. When both the lobby world and the spawn are configured, nothing extra is sent.

> For the full lobby-spawn instructions (including the fallback path when `lobby.world` is unusable) see the "Lobby spawn" section in `deploy-stages-1-2.md`.

> **Required items**: player spawn points, mob spawn points, villagers and the shop location — all four are mandatory. If any is missing, clicking [Confirm & Save] is rejected and the wizard jumps back to the corresponding step.

## Redoing an earlier step

> **Redoing an earlier step**: `/hilltop setup goto <step>` (1-8) jumps back to any step to redo it.

## Particle color guide

> **Particle color guide**: every location preview uses a colored beam + spiral, one color per location type — player spawn=**green**, mob spawn=**red**, energy node=**blue**, protected villager=**yellow**, shop=**gold**. This applies to the Step 3 manager GUIs, the Step 4 node GUI, the Step 5 villager GUI, the Step 6 shop button, `/hilltop deploy showparticles`, and the Shift+Left-click preview in the arena management GUI (`/hilltop manage`).

## Setup wizard sub-commands (`/hilltop setup`)

Available while the wizard is in progress (normally triggered via clickable chat buttons, no typing needed):

| Command | Description |
|------|------|
| `confirm-world` | Confirm game world (Step 1) |
| `confirm-core` | Set current location as village core (Step 2; advances to Step 3) |
| `confirm-waiting` | Set current location as the waiting area (Step 2, **optional**; stays on Step 2, does not advance) |
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
> Step 4 explains that energy nodes are respawn anchors placed in the world (**registered manually** — walk to the anchor and click [Add Node]; at least 1 required; with 0 nodes nobody can respawn; the team loses when the last living player dies (defeat condition: no alive players and no active nodes)). The [Open Nodes Manager] GUI lets you review/delete registered nodes.
> Step 5 villagers are a **defeat condition**: the game is lost if all of them die, so place at least 1. Walk around and click [Add Villager] to record — each recorded villager spawns an invulnerable, non-collidable preview villager at that spot (numbered overhead, e.g. “Villager #3”) so you can see exactly where they are; these previews live only during Step 5 and are removed the moment you leave it. [Open Villager Manager] opens the review GUI (its [Show Particles] previews all villagers in yellow); click [Done] to continue.
> Step 6 shop location is **required**: a shop villager position must be set before deployment can finish. Stand where you want it and click [Set Shop Location] (you stay on the step and can overwrite / clear); [Clear Shop Location] wipes it to start over and [Continue] opens the preview. The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on server restart; leftover duplicate shop villager entities are cleaned up automatically on plugin startup. `/hilltop deploy setshop` / `/hilltop deploy clearshop` and right-clicking the shop entry on the `/hilltop manage` detail page offer the same set / clear entry points (clearing is refused mid-match — run `stop` first).
> `/hilltop start` validates first: if a game is already running it tells you to stop first; if player count is below min-players it reports current/required counts instead of failing silently.

## Access protection

> **Access protection**: arena worlds are guarded — players who have not joined the match cannot teleport in via `/tp` or `/execute in` (they'll see "you have not joined this arena"); admins are exempt (for deployment). `/hilltop join` is unaffected.
