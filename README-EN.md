English | [简体中文](README.md)

> ⚠️ **This is a Minecraft plugin developed by an enthusiast with AI assistance. Feel free to submit Issues for any questions or suggestions. If you like this project, give it a star!**

# Hilltop Village

A cooperative PvE tower defense plugin for Minecraft servers. Players must work together to protect the village core from waves of increasingly powerful monsters.

## Features

- **Cooperative Tower Defense** — Team up with other players to defend the village core from monster destruction
- **Wave-based Assault System** — Monsters attack in waves with increasing difficulty; survive all waves to win
- **Sacred Hammer** — Right-click to leap and charge a smash attack from above, dealing three-tier area damage + particle shockwaves + aftershock slowdown; supports ItemsAdder custom models
- **Fireball** — BedWars-style Fireball: right-click to throw with flame particle trail, explodes on impact for area damage + knockback + ignite; configurable cooldown
- **Energy Node System** — Special blocks (Beacons, Enchanting Tables, etc.) act as energy nodes, providing Resistance + Regeneration buffs to nearby players; nodes can be damaged by monsters and repaired with World Tree Sap
- **Special Monster AI** — Explode Beetle (rushes nodes to self-destruct), Hook Claw Hunter (ranged hook grab teleport + blindness), Flying Dropper (airdrops zombie squads); each monster supports armor/weapons/custom models
- **Graphical Config Center** — Admins configure game rules, wave compositions, monster attributes, item parameters, and energy nodes through an intuitive GUI
- **Clickable Command Menu** — `/hilltop deploy` displays an interactive clickable text menu for quick command execution
- **Multi-language Support** — Built-in Chinese and English; players can switch with `/hilltop lang <en|zh>`
- **Spawn Point Particle Display** — After deploying spawn points, trigger a 30-second colorful particle animation to visualize all spawn locations

## Commands

| Command | Description |
|------|------|
| `/hilltop join` | Join the game waiting queue |
| `/hilltop leave` | Leave the game |
| `/hilltop status` | View game status (wave/players/monsters/nodes) |
| `/hilltop hammer` | Get the Sacred Hammer |
| `/hilltop fireball` | Get a Fireball |
| `/hilltop lang <en\|zh>` | Switch language |
| `/hilltop start` | Start the game (Admin) |
| `/hilltop stop` | Force stop the game (Admin) |
| `/hilltop help` | Show command help |
| `/hilltop deploy` | Open deploy menu (Admin) |
| `/hilltop config` | Open config center GUI (Admin) |
| `/hilltop reloadconfig` | Reload game settings (Admin) |

### Deploy Sub-commands (`/hilltop deploy`)

| Command | Description |
|------|------|
| `setcore` | Set current location as game core |
| `addspawn` | Add current location as monster spawn point |
| `listspawns` | List all spawn points |
| `delspawn <id>` | Delete a spawn point by ID |
| `clearspawns` | Clear all spawn points (requires `confirmclear`) |
| `reloadnodes` | Re-scan energy nodes |
| `showparticles` | Show spawn point particle display (30s) |

### Config Sub-commands (`/hilltop config`)

| Command | Description |
|------|------|
| `nodes repairitem <material>` | Set node repair item |
| `nodes add <material>` | Add a node block type |
| `nodes remove <material>` | Remove a node block type |

## Permissions

| Permission Node | Default | Description |
|------|------|------|
| `hilltopvillage.player` | true | Basic player permissions |
| `hilltopvillage.admin` | OP | Admin permissions (includes all sub-permissions) |
| `hilltopvillage.config.admin` | OP | Config center GUI access |
| `hilltopvillage.config.rules` | false | Game rules configuration |
| `hilltopvillage.config.waves` | false | Wave configuration |
| `hilltopvillage.config.monsters` | false | Monster attribute configuration |
| `hilltopvillage.config.items` | false | Hammer/item configuration |
| `hilltopvillage.config.nodes` | false | Energy node configuration |

## Installation

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/plugman load HilltopVillage`
4. Edit `plugins/HilltopVillage/config.yml` for basic configuration
5. Use `/hilltop deploy` to set up the core and spawn points
6. Use `/hilltop config` to adjust game parameters via GUI
7. Use `/hilltop start` to begin the game

## Configuration

Key configuration options (`config.yml`):

```yaml
language: "zh"

game:
  world-name: "world"
  min-players: 2
  max-players: 6
  lobby-wait-seconds: 60
  wave-interval-seconds: 30
  victory-waves: 20

nodes:
  block-types: [BEACON, ENCHANTING_TABLE, ENDER_CHEST, RESPAWN_ANCHOR]
  base-health: 100.0
  buff-radius: 20.0
  repair-cost-item: SLIME_BALL

spawning:
  spawn-radius-min: 20
  spawn-radius-max: 40
  mob-cap-per-player: 15
  global-mob-cap: 80
```

See `plugins/HilltopVillage/config.yml` and `game-settings.yml` for complete configuration details.

## Special Monsters

| Monster | Entity Type | Ability |
|------|------|------|
| **Explode Beetle** | Cave Spider | Rushes energy nodes and self-destructs, dealing massive node damage |
| **Hook Claw Hunter** | Skeleton | Fires hooks at range, teleporting players and applying blindness + slowness |
| **Flying Dropper** | Phantom | Flies above players and airdrops zombie squads |

## Dependencies

- Paper 1.19+
- Java 17+
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