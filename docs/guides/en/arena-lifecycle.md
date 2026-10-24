# Arena Lifecycle: Enable Queue, Management GUI, Restore Initial State

> Moved from the English README. The README keeps a summary and links here.

## Arena maintenance (enable / disable)

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

> Note: `/hilltop enable <arena>` **cannot bypass the `enabled: false` switch in `arenas.yml`** — while that arena is `false`, enable is rejected; set it to `true` and **restart the server** (it queues on startup). Manual queueing only works for deployed arenas with `enabled: true`. See "Which arena the wizard targets" in `setup-wizard.md`.

## Arena Management GUI (`/hilltop manage`)

Admins run `/hilltop manage` to open a 54-slot menu listing **every configured arena** — including ones with `enabled: false`, a missing world folder, or that were never deployed (those are flagged as missing core coordinates, and the [Enable] button will refuse until you run `/hilltop setup`).

| View | What you can do |
|------|-----------------|
| **List** | See all arenas; left-click to open the detail page |
| **Detail** | Status / world / players / waves / deployment counts / shop location / parameter summary; enable, disable, teleport to core, open the sub-lists, **right-click the shop entry to clear the shop NPC** (location + entity; refused mid-match) |
| **Player-spawn / Mob-spawn / Node / Villager lists** | 45 entries per page; **left-click to teleport**, **right-click to delete** one entry, **Shift+Left-click** to preview its position with particles (color matches the list type: spawn green / mob spawn red / node blue / villager yellow) |
| **Game parameters** | Edit that arena's `min-players` / `max-players` / `victory-waves` / `wave-interval-seconds`; left-click and type the new value in chat |
| **Restore initial state** | Wipe all deployment data + game parameters of that arena and force-rollback the map blocks, so you can redeploy from scratch |

## Notes on the management GUI

> **What "Restore initial state" does**: deletes the arena's core coordinates, all player spawn points, all mob spawn points, all villagers, all energy nodes, the shop NPC (both the stored location and the live entity), plus the per-arena game parameters and radius offsets, and force-rolls back the world's map blocks. It **keeps** the world, display name and `enabled` flag, so the arena stays in the list as "not deployed" afterwards. There is a confirmation page (listing exactly what will be cleared plus the real player count on site) and a second cancel button. **Refused while a match is in progress** — run `/hilltop stop <arena>` first. Because the core coordinates are removed, the arena is not re-enabled automatically; redeploy it and then `/hilltop enable`.

> **Parameter edits need no restart**: changes made through the management GUI are written to `arenas.yml` immediately and pushed to the running arena — player counts apply from the next match, wave interval and victory waves apply from the next intermission. **Editing `arenas.yml` by hand still requires a server restart** (same as the other arena settings). Edits are refused while a match is in progress (including the end-of-match countdown), so raising the player floor mid-match can't trigger an auto-defeat.

> **Deleting one entry uses the "filtered ordinal"**: entries with broken coordinates are skipped when reading, so the numbering you see in the GUI may not match the raw line numbers in `arenas.yml`. Always delete from the GUI rather than editing the file using the GUI's numbers.

> **One world can back only one arena**: the plugin checks world uniqueness when loading `arenas.yml`; definitions that reuse a world are rejected with an explicit warning (world names are case-insensitive). This constraint is what makes map rollback correct — rollback journals and restore tasks are keyed by world name, so two arenas sharing one world would roll back each other's ongoing matches.

> **The player minimum (`min-players`) is authoritative per arena**: `arenas.yml`'s `game.min-players` is that arena's value (e.g. set an arena to `1` for solo play); arenas without an explicit value inherit the global `min-players` from `game-settings.yml`. Note: the `min-players` in the config GUI is the **global default** and has no effect on arenas that spell it out in `arenas.yml`. Two ways to change it — the parameter page in `/hilltop manage` (**takes effect immediately**, for the next match), or edit `arenas.yml` and **restart the server**.

> **Shop NPCs are managed by the plugin**: the shop villagers in the lobby and in each arena are spawned by the deployment wizard and cleaned up by server lifecycle. **The end-of-match map rollback never touches any NPC**, so shops stay available between matches; only "Restore initial state" in `/hilltop manage` clears the shop NPC (location + entity) **of that arena's world**, leaving the lobby and other arenas untouched.

## Arena lifecycle states

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
