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
| 2/8 Set core | Stand at the village core location (the player respawn point) | Click [Set as Core] |
| 3/8 Spawn points | Player spawns + mob spawn points (two independent sets, **at least 1 each**) | Walk to each spot, click [Add Current Location] (player) / [Add Mob Spawn] (mobs) in chat; click [Open Spawn Points Overview] to enter the hub (counts/status at a glance, two sub-manager GUIs for review/delete); click the single [Done] in the overview (both sets must be deployed to leave this step) |
| 4/8 Node registration | Registering energy nodes (respawn anchors) (**at least 1 required**) | **Place the respawn anchor blocks first**, stand next to one and click [Add Node] (a hint appears if the block cannot be registered); click [Continue] to move on |
| 5/8 Villagers | Positions of villagers to protect (**all dead = game lost**, **at least 1 required**) | Walk to a spot and click [Add Villager] — an invulnerable preview villager appears on the spot (numbered overhead, visible during this step only, removed when you leave it); click [Open Villager Manager] to review/delete ([Show Particles] inside previews all villagers in yellow); click [Done] when finished |
| 6/8 Shop location | Where the villager shop NPC stands (**required — deployment cannot finish without it**) | Stand where you want it and click [Set Shop Location]; [Show Particles] previews it in gold; [Clear Shop Location] wipes it to start over, [Continue] moves on |
| 7/8 Config preview | Overview of core/player-spawns/mob-spawns/nodes/villagers/players/waves | Click [Confirm & Save] in the GUI |
| 8/8 Done | Shows the start entry plus an enable hint (auto-queue outcome; an [Enable Arena] button is attached when not auto-enabled) | Click /hilltop start in chat |

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
> Step 4 explains that energy nodes are respawn anchors placed in the world (**registered manually** — walk to the anchor and click [Add Node]; at least 1 required; with 0 nodes nobody can respawn; the team loses when the last living player dies (defeat condition: no alive players and no active nodes)). The [Open Nodes Manager] GUI lets you review/delete registered nodes.
> Step 5 villagers are a **defeat condition**: the game is lost if all of them die, so place at least 1. Walk around and click [Add Villager] to record — each recorded villager spawns an invulnerable, non-collidable preview villager at that spot (numbered overhead, e.g. “Villager #3”) so you can see exactly where they are; these previews live only during Step 5 and are removed the moment you leave it. [Open Villager Manager] opens the review GUI (its [Show Particles] previews all villagers in yellow); click [Done] to continue.
> Step 6 shop location is **required**: a shop villager position must be set before deployment can finish. Stand where you want it and click [Set Shop Location] (you stay on the step and can overwrite / clear); [Clear Shop Location] wipes it to start over and [Continue] opens the preview. The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on server restart; leftover duplicate shop villager entities are cleaned up automatically on plugin startup. `/hilltop deploy setshop` / `/hilltop deploy clearshop` and right-clicking the shop entry on the `/hilltop manage` detail page offer the same set / clear entry points (clearing is refused mid-match — run `stop` first).
> `/hilltop start` validates first: if a game is already running it tells you to stop first; if player count is below min-players it reports current/required counts instead of failing silently.

## Access protection

> **Access protection**: arena worlds are guarded — players who have not joined the match cannot teleport in via `/tp` or `/execute in` (they'll see "you have not joined this arena"); admins are exempt (for deployment). `/hilltop join` is unaffected.
