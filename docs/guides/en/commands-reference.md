# Commands Reference (All Sub-commands)

> Moved from the English README. The README keeps a summary and links here.

## Commands

## Console column

> "Console" column: ✅ = runnable from the server console (admin commands still require OP / the `hilltopvillage.admin` permission, which the console has by default);
> ❌ = must be run by a player in game.

## Main command list

| Command | Description | Console |
|------|------|--------|
| `/hilltop join [arena]` | Join an arena (auto-matches an available one if omitted) | ❌ |
| `/hilltop leave` | Leave the current arena | ❌ |
| `/hilltop gui` | Open the arena selection menu (with status and player counts) | ❌ |
| `/hilltop lobby` | Return to the lobby (sub-command `setspawn`: run it while standing in the lobby world to set the lobby spawn to your current position, effective immediately — Admin) | ❌ |
| `/hilltop status [arena]` | View game status (arena/wave/players in arena/mobs/nodes/awaiting respawn) | ✅ |
| `/hilltop stats [player]` | View your own or someone else's stats | ✅ (with a player name) |
| `/hilltop top <type>` | Leaderboards (kills/wins/games/best-wave/nodes/deaths/crystals) | ✅ |
| `/hilltop hammer` | Get the Sacred Hammer (Admin; players buy from the shop) | ❌ |
| `/hilltop fireball` | Get a Fireball (Admin; players buy from the shop) | ❌ |
| `/hilltop lang <en\|zh>` | Switch language (players change their own session language; admins/console change the server default and persist it) | ✅ |
| `/hilltop start [arena]` | Start the game (Admin) | ✅ |
| `/hilltop stop [arena]` | Force stop the game (Admin) | ✅ |
| `/hilltop wave clear [arena]` | Force-clear all mobs of the current wave and finish it (enters intermission; cleared mobs produce no drops and don't count as kills) (Admin, `hilltopvillage.admin`) | ✅ |
| `/hilltop wave skip [arena]` | Skip the intermission and start the next wave now (while RUNNING it equals clear-wave + skip in one step; refused without judging a loss if there aren't enough players) (Admin, `hilltopvillage.admin`) | ✅ |
| `/hilltop help` | Show command help (available to all players; content is tiered by permission) | ✅ |
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
| `/hilltop build` | Toggle lobby build mode (Admin, for lobby maintenance; **lobby world only** — arena changes are handled by map rollback, build mode does not affect arenas) | ❌ |
| `/hilltop tps` | TPS/MSPT diagnostics (Admin) | ✅ |

## Deploy Sub-commands (`/hilltop deploy`)

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

## Manual command cheat sheet (veterans)

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

## Config Sub-commands (`/hilltop config`)

| Command | Description |
|------|------|
| `nodes repairitem <material>` | Set node repair item |

## Where the setup-wizard sub-commands live

- The full table of `/hilltop setup` sub-commands (16 entries, including the deprecated `rescan`) is in `setup-wizard.md`
- The permission nodes (7 of them) and the console-availability rule for commands are covered in the README's Permissions section
