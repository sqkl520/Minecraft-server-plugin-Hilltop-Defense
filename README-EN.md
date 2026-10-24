🌐 [**中文**](README.md) · **English**

<div align="center">

# Hilltop Village

> *"No towers. Eight villagers cannot run."*

> ⚠️ **This is a Minecraft plugin developed by an enthusiast with AI assistance. Feel free to submit Issues for any questions or suggestions. If you like this project, give it a star!**

[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue)](LICENSE) [![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-brightgreen)](#install-in-three-steps) [![Java](https://img.shields.io/badge/Java-21-blue)](#install-in-three-steps) [![Deps](https://img.shields.io/badge/Deps-zero%20external%20deps-c2410c)](#numbers-you-can-recompute) [![Version](https://img.shields.io/badge/Version-1.1.0--SNAPSHOT--fix2-lightgrey)](https://github.com/sqkl520/Minecraft-server-plugin-Hilltop-Defense/releases)

**Turn an empty arena into a 20-wave village defense that rolls back every changed block after each match.**

<sub>Depends on the Paper API alone — no compile-time third-party libraries, both the `depend` and `softdepend` lists in plugin.yml are empty; with ItemsAdder installed, items and mobs can carry custom models — without it everything falls back to vanilla looks</sub>

"How many more matches will this map survive?" — every arena that has been blown up and rebuilt by players eventually makes a server owner ask that question.

Vanilla Minecraft will not restore your map for you: after one match of twenty waves of exploding beetles and fireballs,
the broken blocks stay where they fell, the map looks worse match after match, and in the end you rebuild the whole thing.
That is the entire reason this plugin exists: it records every single block change inside an arena, and rolls them back from the
snapshot when the match ends (at most 200 blocks per tick, up to 1,000,000 journal entries per world) — plus one rule about not
hitting your own team: the hammer and the fireball deal zero damage to players.

```text
plugins/  ←  put the jar in there, restart the server
/hilltop setup   ←  stand inside the arena world and follow the 8-step wizard
/hilltop start   ←  start a match
```

Version requirements: Paper 1.21.4+ / Java 21. Both the compile dependency and the tests hang on the Paper API alone (`grep -A4 "<dependency>" pom.xml` shows the only non-test dependency is paper-api); Spigot is unverified — do not deploy on the assumption that it "should work".

[See a full match](#how-a-match-unfolds) · [30-second start](#install-in-three-steps) · [Why believe it](#numbers-you-can-recompute) · [What it won't do](#limitations)

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
 </picture>
</a>

<sub>The real curve, from star-history.com — the repository in the chart is this repository</sub>

</div>

---

## How a match unfolds

There is no GIF to show, so here is every phase of a match exactly as it happens — from joining the server to being teleported back to the lobby: phases ① to ⑤, the complete flow.

### How a match actually runs

```
①  player joins the server
     teleported to the lobby-world spawn, gains lobby protection (cannot break blocks, take damage, get hungry)
②  player joins an arena
   /hilltop join  or  pick an arena in /hilltop gui
     → inventory / XP / health are saved (written to disk), inventory cleared for battle
     → teleported to the arena waiting area
     → the sidebar scoreboard appears (wave / alive / nodes / villagers / your kills)
     → once the player count reaches min-players, the countdown starts on its own
③  match begins
     countdown reaches zero → players teleport to the player spawn, game mode switches to survival, the starter kit is handed out
     → every energy node activates at its configured health and buffs nearby players → villagers to protect spawn per configuration
     → the first wave arrives
④  wave loop
     monsters spawn at the mob spawn points  →  they attack nearby players, otherwise they push on the nodes
     the spawn count of each monster type = the configured `count` (per player) × players present (wave 1: 6 beetles / 2 hunters per player,
     so a 2-player match actually spawns 12 beetles + 4 hunters), trimmed by the `total-mobs` ceiling and the global / per-player caps
     clear every monster in the wave  →  rest phase (30 seconds by default) → during it, dead players respawn at the core (an active node is required)
     rest ends  →  next wave spawns (too few players = instant loss, no empty wave is started)
⑤  end
   victory: survive every wave (20 by default)
   defeat: all villagers dead  or  all nodes destroyed + all players dead  or  too few players during the rest phase
        (while no player is alive and no node is active, the check repeats every 3 seconds and the loss lands within 3 seconds)
        ↓
   players stay in the arena and read the match report → a countdown (20 seconds by default) → teleported back to the lobby
   → inventory / XP / health returned exactly as they were → map changes rolled back → the arena resets for the next match
```

That "per-player count × players present" multiplication is not copy, it is read out of the configuration: `count` lives in the `waves` section of `game-settings.yml`, and the player count comes from `min-players` / `max-players`. Recompute: `grep -nE "^(min-players|max-players|victory-waves|wave-interval-seconds):" src/main/resources/game-settings.yml` → lines 20 / 21 / 23 / 25, values 2 / 6 / 20 / 30.

### Demo output

This is what the sidebar looks like once you are in the arena — wave, player count, node headroom, villager headroom and your own record, all in one screen:

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

If you do not want it, turn off `scoreboard.enabled` in `game-settings.yml` and the whole block disappears; match logic is untouched.<sub>The "eight" in the opening line and the `Villagers: 6/8` in the sample are both the typical scale: how many villagers exist is decided by how many you register in wizard step 5, and the node count works the same way.</sub>Every match adds to your record in `player-stats.yml`: `/hilltop stats [player]` for one player — the record panel includes the **win rate** and **K/D** (`StatsManager.getWinRate()`, i18n keys `stats.win-loss` / `stats.kills-deaths`), and `/hilltop top <type>` gives leaderboards over seven dimensions (`kills` · `wins` · `games` · `best-wave` · `nodes` · `deaths` · `crystals`), each showing the **top 10** (`stats.top(type, 10)`). A player who leaves with `/hilltop leave` mid-match only accumulates kills / deaths / nodes repaired — **wins and losses are not counted**.

## Install in three steps

1. Download the newest JAR and drop it into your server's `plugins/` directory
2. Restart the server (during development `/plugman load HilltopVillage` also works)
3. Walk the 6 stages under "From empty folder to first match" below — this step cannot be skipped, the plugin does not know where your map is

Requirements: **Paper 1.21.4+ / Java 21**. The jar carries 8 yml templates (`ls src/main/resources/*.yml | wc -l` → 8, 2,978 lines in total); on first startup, 7 of them — the editable configs — are generated into `plugins/HilltopVillage/`.

### 8 templates ship in the jar, 7 of them are generated on first startup

Of the 8 templates, `plugin.yml` is packed into the jar for Bukkit to unpack and never lands in the plugin folder; the other 7 are written to `plugins/HilltopVillage/` on first startup. Whether they carry Chinese comments splits them into two groups — recompute: `grep -c '#.*[一-鿿]' src/main/resources/*.yml` → six files have Chinese comment lines, `plugin.yml` and `spawnpoints.yml` are 0:

| File | What it owns | Lands in the plugin folder |
|---|---|---|
| `arenas.yml` | **Multi-arena definitions + lobby settings** (world, core, two coordinate sets, villagers, shop, per-arena parameters) | Yes, with Chinese comments |
| `game-settings.yml` | All game rules (21 top-level keys), editable through the `/hilltop config` GUI | Yes, with Chinese comments |
| `monsters-config.yml` | Entity type, armor and weapon for the 3 specialist monsters | Yes, with Chinese comments |
| `shop.yml` | The villager shop's 16 items, prices and slots | Yes, with Chinese comments |
| `messages.yml` | Localized text, one `en` and one `zh` section | Yes, with Chinese comments |
| `config.yml` | A pure internal-state file — only `language` and wizard progress left, **no game parameters at all** | Yes, with Chinese comments |
| `spawnpoints.yml` | Migration source for the legacy **shop-villager** position (no longer written; the new location lives at `arenas.<id>.shop-npc` in `arenas.yml`) | Yes, machine-readable template (no Chinese comments) |
| `plugin.yml` | Command declarations and the 7 permission nodes | No, packed into the jar (machine-readable template, no Chinese comments) |

`nodes.yml` (node coordinates), `player-goods.yml` (item snapshots) and `player-stats.yml` (records) are maintained by the plugin itself at runtime and are created the first time they are needed.

> ⚠️ Since 1.0.9 `config.yml` has been slimmed down to a pure state file — to change game rules, edit `game-settings.yml` or use the `/hilltop config` GUI, and stop looking for parameters inside it.

### Paper and Java 21 only — zero compile-time third-party libraries

In `plugin.yml`, `depend` and `softdepend` are **both empty lists** — not even a soft dependency is declared. The pom has exactly two dependencies: `paper-api` (provided) and JUnit (test scope), and neither one ends up in the artifact. ItemsAdder is **optional**, not a dependency: the code calls its custom-model API through reflection, and if it is not installed the plugin logs one warning and falls back to vanilla items — the precise boundary of "zero external dependencies" is zero third-party libraries at compile time; it runs either way.

Recompute: `grep -E "^(depend|softdepend):" src/main/resources/plugin.yml` → two empty lists; `grep -rniE "okhttp|httpclient|Gson|Jackson" src/main/java --include=*.java | wc -l` → 0.

## Check your install: what seven commands prove

The most common deployment mistake is not a typo — it is "looks configured, actually missing one point". Run these seven in order; each one proves a single component is in place. If any result is wrong, do not run `/hilltop start` yet.

| What it proves | Command | Expected result |
|---|---|---|
| An arena is loaded | `/hilltop list` | your arenas are listed |
| Player spawn configured | `/hilltop deploy listspawns` | at least 1 |
| Mob spawn configured | `/hilltop deploy listmobspawns` | at least 1 |
| Nodes registered | run `/hilltop deploy reloadnodes`, read the echo | known node count > 0 |
| Villagers registered | `/hilltop deploy listvillagers` | at least 1 |
| Shop configured | run `/hilltop deploy setshop`, then look on site | the shop villager spawned; to redo, `/hilltop deploy clearshop` |
| Overall state | `/hilltop status` | wave / players / monsters / nodes readable in one screen |

> Note: when both coordinate sets (player spawns and mob spawns) are empty, `/hilltop start` **refuses to start** and tells you to finish deployment first — it does not silently hang. Each kind of point has its own file on disk (nodes → `nodes.yml`, item snapshots → `player-goods.yml`, records → `player-stats.yml`), so "which one is missing" can be looked up individually.

## What players defend, and how

**One sentence for the objective**: survive every wave while not letting the villagers be wiped out. Players place no towers and build nothing — the defense is the player's own body, their movement, and the handful of items in their hands. **The resource loop is one closed circle**: kill monsters → they drop energy crystals → spend crystals in the shop on items and sap → sap repairs nodes → nodes keep buffing you so you can keep defending the village.

| Means | How you get it | Effect |
|---|---|---|
| **Sacred Hammer** | Bought at the villager shop (admin: `/hilltop hammer`) | Right-click empty air to leap and charge, land to smash; the higher the jump, the higher the damage — three tiers of area damage + a particle shockwave + an aftershock slowdown; 8-second cooldown and a 30-use limit by default, then it shatters and you buy another. The smash and node repair are only available while a match is running; the model can be bound to an ItemsAdder custom asset |
| **Fireball** | Bought at the shop (admin: `/hilltop fireball`) | Right-click to throw: a fireworks trail, an explosion on impact with area damage + knockback + ignite, cooldown configurable; zero damage to players, knockback only (its damage to mobs was not weakened by the zero-friendly-fire change), and villagers are fully exempt and no longer trigger the proximity check |
| **World Tree Sap** | Kill a Nether ghast / buy at the shop | Right-click a deactivated energy node; one unit restores 50 health |
| **Node buff** | Stand near an active node | Resistance I + Regeneration — the node is a mobile healing spring |

**After death**: you go to spectate and respawn at the **core** when the next wave spawns — no countdown, no invulnerability window. Want out mid-match: `/hilltop leave` (the spectator prompt also carries clickable buttons). A disconnect keeps a 60-second reconnect window (configurable; `rejoin-timeout-seconds: 0` disables it) — reconnecting inside the window puts you straight back into the fight, and after the timeout you are removed from the arena while your item snapshot stays on disk for your next login.

**Zero friendly fire is not a slogan — it was checked skill by skill**: the hammer's landing damage and the fireball's explosion deal zero damage to players, knockback only; bows, crossbows and melee swings between players go through the same friendly-fire gate (`mechanics/FriendlyFireListener.java`, cancelled only when both sides are members present in that match). Villagers are exempt even from environmental damage (suffocation / fall / lava / drowning / explosion / lightning / fire / cactus) — only monsters can take villager health. If you want players to be unable to fight each other, the reason is this rule, not a config switch.

**One exception that has to be stated clearly**: the Explode Beetle's vanilla entity explosion when it rushes a node is **not covered by the zero-friendly-fire promise** (`monsters.explode-beetle.entity-explosion` defaults to on) — it goes through `ENTITY_EXPLOSION` with the monster as the source, so players near the node can be caught by it. If you want the blast to take nodes down without hurting people, turn that switch off: the beetle degrades into a purely visual self-destruct and only node damage is resolved. "Zero damage between players" and "monster explosions can still hit you" are two different things — do not conflate them.

## Ten things actually running in a match

Every row gives "what a player or server owner sees" and "where to change it".

| # | What is running | What you see | Where to change it (config key / code location) |
|---|---|---|---|
| 1 | **Wave assault** | Monsters come wave after wave with rising difficulty; the rest phase between waves is 30 seconds by default; spawned in batches, counted only after the batch is done | `victory-waves` / `wave-interval-seconds` / the `waves` section; `core/WaveManager.java` |
| 2 | **Villagers to protect** | Villagers stay fixed at their deployed positions and never wander off; all of them dead = loss (the count is decided by deployment) | `villager-health`; `mechanics/VillagerSystem.java` |
| 3 | **Energy nodes** | Respawn anchors give nearby players resistance + regeneration; monsters can empty them, sap can repair them | `nodes.*` (base-health / buff-radius / repair-*); `mechanics/NodeSystem.java` |
| 4 | **Death and respawn** | Death sends you to spectate, the next wave respawns you at the core; with no active node, no respawn | `rejoin-timeout-seconds`; `core/GameManager.java` |
| 5 | **Sacred Hammer** | Right-click to leap, charge and smash; three damage tiers rising with height; 8s cooldown / 30 uses | `hammer.*`; `mechanics/HammerListener.java` |
| 6 | **Fireball** | Right-click throw + fireworks trail + explosive knockback; zero damage to players, villagers exempt | `fireball.*`; `mechanics/FireballListener.java` |
| 7 | **Villager shop** | 16 default items in three sections; right-click the shop villager to open it, left-click to buy | `shop.yml`; `shop/ShopManager.java` |
| 8 | **Three specialist monsters** | The Explode Beetle charges nodes, the Hook Claw Hunter drags players from range, the Flying Dropper airdrops zombies | `monsters-config.yml` + the `monsters` section; the `ai/` package |
| 9 | **Multiple arenas in parallel** | A lobby plus N independent worlds running several matches at once; pick one in `/hilltop gui` | `arenas.yml`; `arena/MultiArenaManager.java` |
| 10 | **Scoreboard and leaderboards** | Eight live lines in the sidebar, records on disk, `/hilltop top` over seven dimensions | `scoreboard.enabled`; `stats/StatsManager.java` |

Three of those ten (nodes, crystals, villagers) live or die on each other: all nodes destroyed → no respawn → the villagers cannot be held → loss. They were deliberately interlocked that way. The difficulty ramp is written in the configuration, not hardcoded — the `waves` section gives waves 1 / 5 / 10 their own tier (mob cap `total-mobs` = 8 / 14 / 20, and the composition gets harder tier by tier), while every other wave uses the `default` tier (10); editing one tier affects only that tier. Recompute: `grep -A2 total-mobs src/main/resources/game-settings.yml`

## How it works: four hard rules, and breaking any one of them breaks the game

This is not a feature list; it is four rules that decide wins and losses: nodes are the health bar, the map rolls back, crystals form a closed loop, three monsters each grab one tactic — exactly the four subsections below. Each of the four can be pointed at a config key or a line of code.

### Nodes are the village's health bar — lose them all and nobody respawns

Energy nodes use **Respawn Anchor** blocks; 4-5 of them is the recommended count. An admin places the anchors on the map, then walks up to one in step 4 of the setup wizard and clicks [Add Node] to **register it manually** — **at least 1 must be registered**: with 0 nodes nobody can respawn, and the moment the last living player dies the whole team loses (the defeat condition is "no alive player **and** no active node" holding at the same time). It carries two layers of function and the vanilla layer is untouched: players can still charge it with glowstone and set their respawn point on it; the plugin layer treats it as an entity that can be shot down, repaired, and that grants buffs.

| Rule | Default | Note |
|---|---|---|
| Buff aura radius | 20 blocks | Every **active node** (health above 0) continuously grants Resistance I + Regeneration to players inside the radius |
| Node health | 100 | Monster melee and Explode Beetle self-destructs both drain it; at zero it deactivates and the aura disappears |
| Repair per use | 50 health / 1 sap | Right-click a deactivated node; sap defaults to GHAST_TEAR (kill a Nether ghast or buy it at the shop); the material can be changed via `nodes.repair-cost-item` or in the GUI's energy-node settings |
| Definition of "active" | health > 0 | Everything is registered as active when added; emptied, it deactivates; repaired, it reactivates |

To add nodes = place more respawn anchors, then register them in step 4 (impossible during a match); to remove one = click its entry in the node-management GUI. Node blocks cannot be mined during a match (anti-mistake) and break normally while deploying. Buffs from several nodes stack but there is a level cap (`nodes.buff-stack-cap`), so they never stack into invulnerability. Recompute: `grep -nE "^  (base-health|buff-radius|repair-cost-item|repair-cost-amount|repair-amount-per-cost):" src/main/resources/game-settings.yml` → 124:100.0 / 134:20.0 / 126:GHAST_TEAR / 128:1 / 130:50.0.

### The map does not degrade match after match — but it has a 1,000,000-block ceiling

Blocks broken or placed during a match are **rolled back cell by cell automatically** when the arena resets. This is the plugin's hardest promise, and also its most honest limitation.

- **Scope**: arena worlds only, and while a match is running only (an admin building during deployment is unaffected)
- **What gets recorded**: block breaks, block placements, entity explosions, block explosions, natural changes (ice/snow melting), water and lava pouring and spreading
- **Fidelity**: the full `BlockData` is stored (facing, top/bottom half, waterlogged) plus the block entity's `TileState` (containers, signs) — not just the material
- **Batched rollback**: at most 200 blocks per tick, no chained physics updates; **the arena does not open for the next match until the rollback has fully succeeded**, and failed blocks keep their snapshot for retry. The number is tunable by JVM flag: `-Dhilltop.maprestore.blocksPerTick=400` — a larger value rolls back faster at the cost of a longer tick; invalid values (non-numeric or ≤ 0) fall back to 200 and each log one WARNING, while a missing or blank property silently uses 200
- **Ceiling**: one world records at most 1,000,000 block changes (roughly 140–170 MB). At the cap, **recording stops** and a `SEVERE` line goes to the console, and that match's rollback will be incomplete. Why draw the line there: server heaps are usually 3 GB, and 5,000,000 entries would need about 700–850 MB — enough to OOM outright. Stopping at 1,000,000 and warning beats crashing the whole server. A normal match will essentially never reach it; if you genuinely need more, raise the ceiling together with your heap size
- **Can be switched off**: `map-restore.enabled` in `game-settings.yml`

```bash
grep -n "MAX_JOURNAL_ENTRIES_PER_WORLD = \|return 200" src/main/java/com/hilltopvillage/mechanics/MapRestoreManager.java
  # 106:private static final int MAX_JOURNAL_ENTRIES_PER_WORLD = 1_000_000;   ← 100 万格上限
  # 81 / 87 / 93:return 200;                                                  ← 每 tick 200 格
```

> ⚠️ The rollback journal is **in memory**, not on disk. A match that ends normally always rolls back; if the server crashes mid-match, that match's journal is not recovered. Do not read "rollback" as "survives a power cut" — this distinction is stated at the mechanism layer, not written as an effect promise.

**Item protection is a different matter, and it really does go to disk**: on entering an arena your inventory / XP / health are stored in `player-goods.yml` and returned exactly as they were when you leave. Writes go through "write a unique `.tmp` first, then an atomic move", and a file that fails to parse is renamed to `*.corrupt` and quarantined so the next startup is not blocked by a broken file. So the sentence "after a server restart the player still gets their inventory back on login" applies to item snapshots only — never to map blocks.

### Where crystals come from and where they go

The currency is the energy crystal, and it has exactly one source and one destination, which is why the economy cannot inflate. **Drops**: killing wave monsters always drops one (`shop.currency-drop-chance` defaults to 1.0 = always), 1-2 by default, the range controlled by `currency-drop-min` / `currency-drop-max`; it varies per monster — the Explode Beetle 1, the Hook Claw Hunter 1-2, the Flying Dropper 2-3 (the harder the monster, the more it is worth; `drop-min` / `drop-max` per monster in the `monsters` section, `-1` falls back to the global value, `0/0` means that monster drops nothing). Airdropped zombies **never drop currency**, which is deliberate anti-AFK design: idling on airdrops does not farm an economy. The end-of-match report, the sidebar, `/hilltop stats` and `/hilltop top crystals` all show crystals for this match and all time.

**Spending**: the villager shop ships 16 default items across 5 types (`HAMMER` / `FIREBALL` / `REPAIR_ITEM` / `MATERIAL` / `EQUIPMENT`), laid out in sections of a 54-slot GUI — the tool/consumable section (Hammer 5 / Fireball ×8 3 / Sap ×4 2 / Bow 15 / Arrows ×8 2), the weapon section (Iron Sword 8 / Diamond Sword 25 / Netherite Sword 48), the armor section (iron pieces 6 each / diamond pieces 14 each, sold piece by piece). Every piece of equipment is a **vanilla blank**, with no enchantments. At match start you additionally get a leather four-piece set + stone sword + bread + torches (the `starter-kit` section). Walk up to the shop villager, right-click to open, left-click an icon to buy.

**Two anti-forgery measures**: crystals are identified by a PDC tag inside the item, so renaming a plain emerald to the currency name with an anvil or a name tag **will not pass as money**; to hand out currency by hand use `/hilltop shop give <player> [amount]` — emeralds from vanilla `/give` are not accepted by the shop. All clicks and drags inside the shop GUI are cancelled (you cannot take the icon off the shelf), and purchases have a 250-millisecond cooldown against double-click double-charging.

```bash
awk '/^  items:/{f=1;next} f&&/^  [^ ]/{f=0} f&&/^    [a-z_0-9]+:/{c++} END{print c" items"}' src/main/resources/shop.yml   # → 16 items
grep -nE "^  currency-drop-(chance|min|max):" src/main/resources/game-settings.yml   # 159/161/162 行：1.0 / 1 / 2
grep -n "BUY_COOLDOWN_MS" src/main/java/com/hilltopvillage/shop/ShopListener.java    # 35:= 250L
grep -rn "ITEM_KIND_KEY =" src/main/java --include=*.java | wc -l                    # → 3（重锤/烈焰蛋/汁液各一处）
```

**The shop villager itself**: invulnerable (all damage cancelled) and cannot be pushed — it exists purely as the shop entry. Its facing comes from the player's yaw only, with pitch forced to 0 (never looking down or up; a bad pitch written into an old save is auto-corrected on restart), and once spawned it also turns horizontally to face the nearest player within 8 blocks. Clearing it is **refused while a match is running** — the shop NPC is a plugin-level singleton, and clearing it would take away the shop the players in the arena are using; run `/hilltop stop` first.

After editing `shop.yml`, run `/hilltop shop reload` — no restart needed. The full field list for an item entry (`slot` / `price` / `type` / `amount` / `icon.material` / `name` / `lore` / `unbreakable`) and a complete YAML sample are in [Shop customization](docs/guides/en/shop-customization.md).

> ⚠️ `shop.yml` follows "file exists = template is not released" — when the server already has an old file, items added in a newer version (bow, arrows, swords, armor) **will not appear on their own**. After upgrading, either add the entries by hand, or back the old file up, delete it, and let it regenerate.

### Three monsters, each grabbing one tactic

Four kinds of enemy, each holding one tactical slot; the entity type of all three specialists can be swapped.

| Monster | Default entity | Which tactic it grabs | One-line counter |
|---|---|---|---|
| Ordinary monsters (zombies etc.) | vanilla | Attack players if one is near, otherwise push on the **nodes** | Do not leave your back toward the nodes |
| Explode Beetle | cave spider | Sprints at the nearest node and self-destructs | Protect it first — do not let its center land on a node |
| Hook Claw Hunter | skeleton | Drags players over with a ranged hook + blindness + slowness | Do not stand low; on flat ground with no height difference it degrades into attacking nodes |
| Flying Dropper | phantom | Flies over players and airdrops zombie squads | One dropper has a cap on airdrop rounds for the whole match |

> Note: 3 is the number of **specialist mechanic monsters** (`grep -cE "^[a-z].*:" src/main/resources/monsters-config.yml` → 3), not "all enemies". Airdropped zombies and ordinary wave monsters reuse vanilla entities; the `monsters` section additionally holds three `display-egg` entries (cave spider / skeleton / phantom spawn eggs) used as GUI display props — recompute: `grep -n "display-egg" src/main/resources/game-settings.yml` → 219/236/251.

The beetle's self-destruct carries a **vanilla entity explosion**, on by default (`monsters.explode-beetle.entity-explosion`): with it on, the blast still hurts nearby players on top of node damage; turn it off and only node damage is resolved (particles and sound stay — a purely visual self-destruct). One dropper's airdrop rounds are capped by `spawning.airdrop-max-per-dropper` (default 3, 0 = airdrops disabled) so airdrops cannot run away with the server. Entity types are changed in `monsters-config.yml` under `entity-type` (swap `explode-beetle` to `ZOMBIE`, for example), then `/hilltop reloadconfig` applies it. Each monster can also carry `itemsadder-id` (an ItemsAdder model namespace ID) or `custom-model-data` (vanilla custom model data, paired with a resource pack) — left empty / 0 and the mob keeps its vanilla look.

## From empty folder to first match: 6 stages

For a first-time setup, work through them in order; each stage carries the one pitfall most likely to bite you. Full procedures, edge cases and pitfall notes have been moved into guide files inside the repository — what stays here is the skeleton.

| Stage | What you do | The one pitfall to watch | Full version |
|---|---|---|---|
| 1 Prepare the worlds | A lobby and an arena: **two different worlds**, at least 2 | A wrong `level-name` in `server.properties` makes the server create an empty world instead of loading your map | [Stages 1-2](docs/guides/en/deploy-stages-1-2.md) |
| 2 Configure the arena | Edit `arenas.yml`: world, core, waiting area, per-arena parameters | Editing `arenas.yml` by hand **requires a restart** (reloadconfig does not re-read it) | [Stages 1-2](docs/guides/en/deploy-stages-1-2.md) |
| 2.5 Get into the arena world | Teleport yourself: `/execute in <world> run tp @s …` or Multiverse | **This plugin has no command that teleports you into an arena world**, while `setup` / `deploy` both require you to already be standing in the target world | [Stages 1-2](docs/guides/en/deploy-stages-1-2.md) |
| 3 Run the setup wizard | `/hilltop setup`, all 8 steps | Player spawns, mob spawns, villagers and the shop location — **all four are mandatory** | [Setup wizard](docs/guides/en/setup-wizard.md) |
| 4 Tune the game parameters | Open the GUI with `/hilltop config`, or edit `game-settings.yml` directly | After editing in the GUI press "Save & apply"; **the new configuration takes effect from the next match** | see the "What reloads" section below |
| 5 Verify the deployment | Run those 7 self-check commands | When both coordinate sets are empty, `start` is refused — that is not a bug | see "Check your install" |
| 6 Play one match | `/hilltop join` or `/hilltop gui` → an admin runs `/hilltop start` | Below `min-players` nothing starts, and it reports current/required headcount explicitly | [Commands reference](docs/guides/en/commands-reference.md) |

The lobby and the arena must be two different worlds — the plugin routes protection rules and game logic on that basis. An arena world can be created with a WorldEdit copy, with Multiverse, or by copying the world folder outright. When a world folder exists but Paper has not loaded it, the plugin calls `Bukkit.createWorld` itself at startup (the same approach BedWars1058 takes), which is why `level-name` is best set to the lobby world's name; not changing it is fine too, since the plugin loads the worlds `arenas.yml` references. After the first teleport into an arena, fly a lap and confirm two things: this really is the map you prepared, and you are in creative mode (you will need it to place respawn anchors and position villagers).

### What each of the 8 wizard steps does

Run `/hilltop setup` while standing inside the arena world. The wizard binds to **the world you are currently in**, and is not limited to the default arena; it can begin even when that arena has no core configured and no running instance. After step 8, arenas with `enabled: true` join the enable queue automatically (an arena with `enabled: false` has to be switched back to true and the server restarted — `/hilltop enable` cannot bypass that switch).

| Step | Content | What you click |
|---|---|---|
| 1 | Confirm the world | The current world name is shown; if it is not yours, cancel and teleport again |
| 2 | Set the core | Stand at the player respawn position, click [Set as core] |
| 3 | Spawn-point management | Player spawns + mob spawns: **two independent coordinate sets, at least 1 each**; in [Open spawn overview] click the single [Done] |
| 4 | Node registration | **Place the respawn anchors on the map first**, walk up to one and click [Add node], at least 1 |
| 5 | Villager placement | Walk to the spot and click [Add villager]; the registration point immediately shows an invulnerable preview villager (numbered overhead, visible in this step only) |
| 6 | Shop location | Stand at the target spot, click [Set shop location] — **required**, deployment cannot finish without it |
| 7 | Configuration preview | Overview of core / both coordinate sets / nodes / villagers / player counts / waves, then click [Confirm and save] |
| 8 | Deployment complete | The start entry point and the enable result are shown |

**How the three concepts divide the work** — mixing them up is the most common deployment accident:

| Concept | Role | Consequence if destroyed |
|---|---|---|
| Core | The players' respawn point; **it has no health of its own** | None (it is only a respawn coordinate) |
| Energy node (respawn anchor) | The real health bar: grants buffs, can be shot down, can be repaired | **With all of them destroyed, dead players cannot respawn** |
| Villagers | What you are defending; how many depends on how many you placed | **All of them dead = the game is lost** |

Particle previews are always a colored light column plus an orbiting spiral, and the five location types are one color per meaning: player spawn green, mob spawn red, node blue, villager yellow, shop gold. To jump back to an earlier step, use `/hilltop setup goto <1-8>`. **The mandatory items really are mandatory**: if any of player spawns, mob spawns, villagers, shop location is missing, [Confirm and save] is refused and you are sent back to the matching step to fill it. **Every step autosaves**: finishing a step writes `arenas.yml` right then, and the current step number is persisted together with player and arena — quitting mid-setup, disconnecting, or switching arenas all lose no progress, and `setup.saved-exit` tells you which world to return to and keep going in.

### What the configuration skeleton looks like

Inside `arenas.yml`, one arena is these few keys; everything else is optional:

```yaml
default-arena: arena1
lobby: {world: world, spawn: {x: 0.5, y: 100.0, z: 0.5, yaw: 0.0, pitch: 0.0}}
arenas:
  arena1:
    display-name: "&6&lSky Fortress"
    enabled: true
    world: hilltop_arena1      # must be a different world from the lobby
    core: {x: 100.0, y: 64.0, z: 100.0}          # player respawn point
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}
    spawn-points: []           # player spawns (written by the wizard)
    mob-spawn-points: []       # mob spawns (the second coordinate set, written by the wizard)
    # villagers: / shop-npc: also written into this file by the wizard, persisted per arena, restored automatically on restart
    game: {min-players: 2, max-players: 6, victory-waves: 20, wave-interval-seconds: 30}
```

### Arenas can run several matches at once, and can be disabled individually

Arenas use an "enable queue" model: `/hilltop disable <arena>` takes an arena out for maintenance, `/hilltop enable <arena>` puts it back in the queue, and other arenas are unaffected. The queue is processed **serially** — one world loaded at a time, so loading several worlds simultaneously cannot stall the server.

`/hilltop manage` opens a 54-slot management GUI: read status, delete a single spawn / mob spawn / node / villager / shop entry, change per-arena game parameters, restore the initial state. **One world can map to only one arena** — world uniqueness is validated on load and duplicates are refused, because the rollback journal is keyed by world name, so two arenas sharing a world would have one match's end roll back the other one's live game. The shop NPC is managed by the plugin as a whole (spawned by the setup wizard, cleaned on server start/stop, duplicate entities left by a crash cleared automatically at startup), and **the map rollback at the end of a match never touches any NPC** — the shop stays available between matches. "Restore initial state" clears coordinates, villagers, nodes, shop and per-arena parameters and force-rolls blocks back; it **keeps** the world, the display name and the `enabled` switch, has a confirmation page, and is refused while a match is running. Deleting a single coordinate follows the GUI's **filtered index**, so do not use it to edit files by hand (corrupt entries are skipped on read, so the index may not match the original line number).

### The deep documentation is in the repository, not somewhere else

The full procedure for each stage above, the complete command table and the key-by-key configuration reference are all inside the repository and ship with the repository (run `git add` first when verifying locally; the guide files and this file live in the same repository, six CN + six EN — recompute: `git ls-files docs/guides | wc -l` → 12):

- [Prepare the worlds and configure the arena (stages 1 / 2 / 2.5)](docs/guides/en/deploy-stages-1-2.md) — including why `level-name` makes the server create a new world
- [Step-by-step notes on the 8-step setup wizard](docs/guides/en/setup-wizard.md) — including every `setup` subcommand and the five particle colors
- [Arena lifecycle: enable queue / management GUI / restore initial state](docs/guides/en/arena-lifecycle.md)
- [Villager shop customization (every `shop.yml` field)](docs/guides/en/shop-customization.md)
- [Complete command reference (all first- and second-level subcommands)](docs/guides/en/commands-reference.md)
- [Configuration file reference, key by key](docs/guides/en/config-reference.md)

## What reloads instantly and what needs a restart

The most common question from server owners is "does changing this need a restart". The answer splits by file into three tiers, with nothing left out:

| Where you change it | How | When it takes effect |
|---|---|---|
| `game-settings.yml` | Save in the `/hilltop config` GUI, or edit the file and run `/hilltop reloadconfig` | **From the next match** (the wave interval and the target wave apply from the next rest phase) |
| `monsters-config.yml` | Edit the file, then `/hilltop reloadconfig` | From the next wave spawn |
| `messages.yml` | Edit the file, then `/hilltop reloadconfig` | The next message already uses the new text |
| `shop.yml` | Edit the file, then `/hilltop shop reload` | Immediately, no restart |
| `arenas.yml` | **The server must be restarted** | reloadconfig does not re-read it |
| Per-arena parameters in the admin GUI | `/hilltop manage` → the game-parameters page | Written into `arenas.yml` immediately and pushed to running arenas, **no restart** (refused while a match is running) |

`/hilltop config` opens a visual configuration center: player counts, wave combinations, monster attributes, item parameters, node and villager health are all changed in the GUI, what you see is what you get, and no file has to be opened. Two situations that bite: **editing `arenas.yml` by hand does nothing until you restart**; and **changing a running arena's player counts mid-match (the settlement countdown counts as mid-match) is refused**, so a mid-match edit cannot trigger a "too few players" loss.

**The wave-timeout safety net is off by default**: `wave-timeout-seconds: 0`. In an extreme stall, handle it by hand — `/hilltop wave clear` force-clears the current wave and enters the rest phase, `/hilltop wave skip` skips the rest phase and starts the next wave immediately. To get the automatic fallback back, set it to a positive number (120, for example). While every player is dead and no node is active, the check repeats every 3 seconds and the loss lands within 3 seconds; if an arena holds no player for longer than `empty-arena-end-seconds`, that arena ends on its own instead of idling.

Recompute: `grep -nE "^(wave-timeout-seconds|defeat-countdown-seconds|rejoin-timeout-seconds|empty-arena-end-seconds):" src/main/resources/game-settings.yml` → lines 30 / 33 / 37 / 40, values 0 / 20 / 60 / 60.

## Numbers you can recompute

"How did you arrive at that number?" — everyone who writes a number into a document deserves to be asked that. Every line below gives its recompute command **in the same section**, and after cloning the repository they run verbatim from the **repository root** (Git-Bash / POSIX syntax, read-only commands only). A number you cannot reproduce is a number that should not be written here.

| Claim | Number | How it is counted |
|---|---|---|
| Java source files / lines of source | **69** files / **27415** lines | everything under `src/main/java`, main class included |
| Bukkit event handlers | **65** `@EventHandler` | spread over 17 files; lobby protection alone has 16 in one file |
| First-level subcommands | **26** | the plugin.yml usage string / the main switch / Tab completion — **three lists, line-for-line identical** |
| Permission nodes | **7** | `hilltopvillage.admin` carries 6 children, `config.*` default to false |
| Default shop items | **16** items / **5** types | the `items` section of `shop.yml` |
| Localized text | **2108** lines, en / zh **960** keys each | both top-level sections hold the same key count; players switch their own with `/hilltop lang <en\|zh>`, an admin doing it changes the server default and persists it |
| Wave configuration | **4** groups (`default` + `"1"` + `"5"` + `"10"`) | `victory-waves: 20` is the **wave you must reach to win**, not 20 configured wave rows |
| Templates shipped in the jar | **8** yml files / **2978** lines | 7 of them are generated into `plugins/HilltopVillage/` on first startup (plugin.yml stays inside the jar) |
| Package structure | **13** packages + 1 root main class | `admin ai arena command config core diagnostic doc gui mechanics shop stats util` |

```bash
  # 规模与资源
find src/main/java -name '*.java' | wc -l                             # → 69
find src/main/java -name '*.java' -print0 | xargs -0 wc -l | tail -1  # → 27415 total
grep -rn "@EventHandler" src/main/java --include=*.java | wc -l       # → 65
ls -d src/main/java/com/hilltopvillage/*/ | wc -l                     # → 13 个包
ls src/main/resources/*.yml | wc -l                                   # → 8 个模板
wc -l src/main/resources/*.yml | tail -1                              # → 2978 total
  # 配置面
awk '/^permissions:/,0' src/main/resources/plugin.yml | grep -cE "^  [a-z]"   # → 7 权限节点
awk '/^  items:/{f=1;next} f&&/^  [^ ]/{f=0} f&&/^    [a-z_0-9]+:/{c++} END{print c}' src/main/resources/shop.yml  # → 16 商品
grep -oE "type: (MATERIAL|HAMMER|FIREBALL|REPAIR_ITEM|EQUIPMENT)" src/main/resources/shop.yml | sort -u | wc -l    # → 5 种 type
awk 'NR>=278 && /^  [^ ]/' src/main/resources/game-settings.yml | sed 's/:.*//'   # → default / "1" / "5" / "10"，4 组
wc -l src/main/resources/messages.yml                  # → 2108
grep -nE "^(en|zh):" src/main/resources/messages.yml   # → 14:en: / 1063:zh:（两段各 960 键）
```

Three command lists reconciled against each other — this is the line that best shows "documentation as code" is not a figure of speech: the usage string in `plugin.yml`, the main switch cases in `HilltopCommandHandler`, and the first-level completion array in `HilltopTabCompleter` each hold 26 entries, and `diff` comes back empty.

```bash
sed -n '/^commands:/,/^permissions:/p' src/main/resources/plugin.yml \
  | grep -oE '<[a-z| >]+>' | tr '<| >' '\n' | grep -E '^[a-z]+$' | sort -u > /tmp/usage.txt
sed -n '117,598p' src/main/java/com/hilltopvillage/command/HilltopCommandHandler.java \
  | grep -oE 'case "[a-z]+"' | sed 's/case "//;s/"//' | sort -u > /tmp/switch.txt
diff /tmp/usage.txt /tmp/switch.txt          # → 空输出；两个文件各 26 行
```

The command surface splits into five groups by purpose, and the 26 first-level subcommands fill them exactly (second-level subcommands are counted separately; the full table is in [commands-reference](docs/guides/en/commands-reference.md)):

| Purpose | First-level subcommands |
|---|---|
| Players | `join` `leave` `lobby` `gui` `stats` `top` `lang` |
| Matches and diagnostics | `start` `stop` `wave` `status` `list` `arenas` `tps` `help` |
| Deployment | `setup` `deploy` `manage` `enable` `disable` |
| Configuration | `config` `reloadconfig` `shop` |
| Items and building | `hammer` `fireball` `build` |

> Note: this section deliberately **does not** state player counts, server counts or performance-gain percentages — numbers that cannot be recomputed. The test directory currently holds one smoke-test class, so "full test coverage" is not going to appear here either; the quality narrative goes through the review rounds recorded in CHANGELOG.

## Where it sits among similar plugins

Checked on **2026-10-22**, and every cell was filled against a source: the GitHub side from `api.github.com` / `gh api`, the SpigotMC side from the **unofficial** mirror `api.spiget.org` (the site itself sits behind Cloudflare and cannot be fetched), so those figures are order-of-magnitude only and not an official count. Anything that could not be fetched is written as "not verified". No guessing.

| Dimension | Hilltop Village | Village Defense | Villager Defense | Tower Defense X |
|---|---|---|---|---|
| Shape of play | Co-op PvE, players defend in person (action-oriented) | Co-op PvE village-defense minigame | Co-op PvE villager-defense arena | Classic **tower-building** defense, BTD-like |
| Waves / shop / map rollback / multi-arena | All present | All present | Waves present, the rest not verified | All present |
| **Energy nodes** | **Yes** (destructible, repairable, and they buff players) | **No equivalent** (no node/energy file found in the repository) | Not verified | **No** |
| **External dependencies** | **Zero** (`depend: []` + `softdepend: []`) | 6 softdepends (PlaceholderAPI / ProtocolLib / ViaVersion and so on) | Not verified | Can run standalone, supports MythicMobs among others |
| Languages | en / zh, 960 keys each | **13** community languages (its strongest suit) | Not verified | Some strings hardcoded in English |
| MC version | Paper 1.21.4 | 1.8 → 26.3 | 1.16 → 1.21 | 1.7 → 26.1 |
| License / source | Source public, **PolyForm Noncommercial** (non-commercial) | GPL-3.0 | GPL-3.0 | Closed source, paid, 12 USD |
| Maintenance (baseline 2026-10-22) | Active | Active (commits still landing 2026-10-10) | Half-active / tapering (last commit 2025-07-14) | Half-active |

**Two words of credit where it is due**: Village Defense is the most mature GPL-3.0 peer on this track — listed since 2017, spanning 1.8→26.3, 13 languages, full MySQL and BungeeCord support; our "multi-arena + enable queue" model simply follows the path mature implementations like BedWars1058 already proved out, and we do not pretend we invented it (the fireball's inspiration comes from its fireball too — its source does contain a `FireballListener`, and we say so plainly). Tower Defense X brings BTD-style numerical progression into Minecraft: 16 towers × 16 monsters plus bosses and Versus wave control — that depth of content is what only a closed-source paid project dares to lay out (this project does not take that shape). But its shape is PvP where players tear out each other's beds, or a tower-building defense where you stand and watch towers shoot; this project is co-op PvE where players hold nodes together.

**Four things therefore not sold as differentiators**: waves, shop, map rollback, multi-arena — BedWars-style plugins and Village Defense have had these built in for years; writing them as advantages gets refuted by any peer in one sentence. The real differences are three facts. First, the defense objective has one extra layer: peers tie win/loss to villagers staying alive, and here the energy nodes sit outside the villagers (an active index, drainable by monsters, pullable back with sap, and they grant buffs along the way), so win/loss becomes a fight over node positions. Second, lower deployment coupling: peers commonly hang on PlaceholderAPI / ProtocolLib / ViaVersion to run BungeeCord networks, while one jar runs straight on Paper 1.21.4. Third, the licensing stance was chosen deliberately — PolyForm Noncommercial allows learning and non-commercial play on your own server and forbids commercial use. That is not a weakened GPL, it is a different legal posture, and it is written here as positioning rather than as a flaw being hidden.

This is not a tower-building defense; it is defending people. Players place no towers and do not idle watching turrets shoot; every point of damage comes from the player's own positioning, their jumps, and the hammer in their hand.

> ⚠️ Cells not verified (re-check by hand before release, or leave them out per the table above): whether Villager Defense has shop / multi-language / multi-arena / rollback built in; this project's SpigotMC distribution data (never listed there, so there is no figure to cite; for competitor cells, if quoted, the caveat is the unofficial Spiget mirror, as of 2026-10-22); SleepingTowns itself — neither GitHub nor search locates a matching repository, and Spigot ID 88442 redirects to a different plugin, so it is **kept out of the comparison table**. Three more names from the early candidate list — BattleTowers / DefenseGate / TFE — were cross-checked and confirmed not to exist on GitHub (API 404 + zero search hits), and all were removed. If it cannot be found, we say it cannot be found; we do not cite things that do not exist.

## The lobby: the first thing you see, and nothing breaks there

Players land in the lobby on join and enter an arena deliberately, via `/hilltop join` or `/hilltop gui`. The lobby is not "just a normal world": it is a room sealed by 16 event handlers — block breaking, block placing, damage, hunger, weather, item drops and container interaction are **all blocked** inside the lobby world (burning / explosions / ignition / buckets / damage / movement each covered by their own handler).

- **Build mode**: an admin runs `/hilltop build` to lift protection temporarily while renovating the lobby; run it again to close it
- **Void protection**: falling out of the world in the lobby teleports you back to the lobby spawn automatically
- **Spawn takes effect immediately**: an admin standing inside the lobby world runs `/hilltop lobby setspawn`, writing the current position (heading included) into `arenas.yml` — **no restart needed**; new logins, lobby respawns and `/hilltop lobby` all use the new point
- **Entry guard**: the arena world checks external teleports for admission, so a player not in the match cannot get in even with `/tp` or `/execute in` (they are told they have not joined this arena); admins are exempted so they can deploy, and a normal `/hilltop join` is unaffected
- **Where it is configured**: `lobby.world` / `lobby.spawn` in `arenas.yml`; left empty or pointed at a world that does not exist, the plugin falls back to the server's main world (the `level-name` one) so the default install still works

Recompute those 16 kinds of interception: `grep -c "@EventHandler" src/main/java/com/hilltopvillage/mechanics/LobbyProtectionListener.java` → 16.

## Limitations

**❌ 1. It is not a tower-building defense.** Players cannot place towers, cannot build automatic attack rigs, cannot idle and watch turrets shoot monsters. The only defensive means are skill items (the hammer, the fireball), movement and node buffs. If you want the "line up a row of towers and then spectate" kind of play, Tower Defense X and its family are that; this plugin will disappoint you.

**❌ 2. There is no enchantment system.** Every piece of equipment in the shop is a vanilla blank — an iron sword is just an iron sword, and the diamond armor carries no enchantments. Ranged play is covered by the bow line added in the shop (Bow 15 crystals / Arrows ×8 2 crystals), but enchanting itself is not in this version.

**❌ 3. Map rollback has a 1,000,000-block ceiling.** Once a single world has recorded 1,000,000 block changes, **recording stops** and one `SEVERE` line is logged, and that match's rollback will be incomplete — the map keeps part of the damage. A normal match will essentially never reach it; if you really intend to blow a million-plus cells into one map, raise the ceiling yourself and adjust your heap size along with it. There is a harder one underneath it: the rollback journal lives in memory, so if the server crashes mid-match that match's journal is not recovered.

### Beyond the boundary: the pitfalls that bite hardest during deployment

All six below have been tripped over already, and tripping on one makes you think the plugin is broken:

| Pitfall | Symptom | What to do |
|---|---|---|
| **There is no command that teleports you into an arena** | `setup` / `deploy` both require you to already be standing in the target world, and a first-time deployer has nowhere to go | Vanilla `/execute in <world> run tp @s …` or Multiverse; run `/gamemode creative` first, then teleport |
| **A solo match needs per-arena configuration** | With the global `min-players: 2`, one person clicking join does not start anything | Use `/hilltop manage` on the parameters page to set **that arena's** `min-players` to 1 (takes effect immediately), or edit `arenas.yml` and restart; the `min-players` you see in the GUI is the global default and does **not** apply to an arena that configures it explicitly |
| **An existing `shop.yml` will not produce the new items** | After upgrading the shop is still the same few things and you conclude the new items were never made | "File exists = template not released" is deliberate; add the entries by hand, or back the file up and delete it so it regenerates |
| **Editing `arenas.yml` by hand does nothing until a restart** | You change world names and coordinates and the plugin keeps running the old configuration | Restart the server; only parameter changes made in the management GUI are hot-applied |
| **The `rescan` entry point still exists but is deprecated** | Typing `/hilltop setup rescan` returns one line and scans nothing | Register nodes by hand with [Add node] in step 4; the deprecated entry was not removed, it only returns a message |
| **Re-enabling right after a disable takes a few seconds** | `/hilltop enable` sits in the queue retrying and the console prints notices | Normal behaviour: the previous match's delayed reset and map rollback have to finish, and the world loads once the isolation window closes |

This is a plugin that becomes fun once you have walked six stages, not a product that is fun the moment it is installed — the first match still requires one person standing at every single point.

## Five designs that got dropped

Half of this plugin's present shape comes from five rounds of "we did it that way first, then changed it". The material is all in the repository's `CHANGELOG.md` version sections; what follows covers mechanism and judgement only — each one starts with why the original choice was defensible, then states the cost.

**1. The wave-timeout safety net: from "on by default" back to "off by default".** It was added for the ugliest kind of stall: every player alive, the wave simply not clearable, and `/stop` the only answer. So a fallback layer was built — after a wave times out, the leftovers are teleported to the nearest living player and highlighted, and if 15 seconds of grace still does not clear them they are force-removed; the written default was 120 seconds. It sounded airtight, and yet the default went back to **0**: automatically clearing leftovers masks the real cause of a stuck AI, and a signal that should have been reported got swallowed by the safety net. The code path and the config hook are **fully retained**; set a positive number and it revives. Existing servers whose file on disk still says the old default 120 get normalized to 0, and anything you set by hand to another value is untouched. **The cost**: an extreme stall needs the server owner to run `/hilltop wave clear` or `wave skip`.

**2. Energy nodes: from "scan the blocks at match start" to "register coordinates manually + protect the block".** Scanning looked convenient — on game start, scan some radius around the core for beacons / enchanting tables / ender chests / respawn anchors, and whatever is placed becomes whatever. The convenience assumed that map makers place blocks the way you imagined, and that assumption does not hold: missed detections plus scan overhead ended it, and the flow became an admin walking up to an anchor in step 4 and clicking [Add node], with coordinates saved into `nodes.yml` and loaded from the file on restart rather than re-scanned. **The cost**: zero nodes must be fixed by a person — with 0 nodes nobody in the match can respawn, and the loss triggers the moment the last player dies ("no alive player and no active node"). The line in today's documentation saying "no block scanning" is the wording this round trip left behind.

**3. The shop NPC: from "held by each arena" to "a plugin-level singleton".** Having every `ArenaInstance` own its own shopManager reads nicely in object-oriented terms — whoever owns the arena owns its lifecycle. What actually broke it: on arena reset the instance called its own `cleanup()`, that method iterates **all worlds** deleting entities carrying the shop marker, and the check meant to "skip myself" never held because the instance had no NPC reference — so one match ending vaporized the shop of every other arena. Now arenas hold no shop and no NPC lifecycle at all; everything goes through `plugin.getShopManager()`. **The cost**: clearing the shop while a match is running is **refused**, because it would take away the shop everyone in the arena is using.

**4. Listeners: from "one registration per arena" to "one global singleton routing by world".** Registering a `VillagerSystem` per arena looked naturally isolated. But `disableArena` / `stopArena` never unregistered, so toggling arenas repeatedly accumulated listeners — memory climbing, and villager damage handled more than once. The fix was a single global registration that routes back to the right arena by world name, plus an `instanceof Villager` short-circuit. **The cost**: one extra comment in the source reading "no registerEvents here anymore, to avoid a listener leak" as a road marker, and the event-dispatch logic for several parallel arenas has been more complex ever since.

**5. Player spawns and mob spawns: one coordinate set split into two, exploding into the void once wired up.** Player spawns and mob spawns originally shared one coordinate set; they were later split into `spawn-points` / `mob-spawn-points` (an old save holding only the first is treated as both, with a warning logged). The reason to split was solid: the two want opposite terrain — player points need open ground that is safe, mob spawns only need to hug the island's edge. This round trip hit both the "split the coordinates" and the "wire it up" kinds of pitfall: what actually blew up came after `spawn-radius-min/max` got wired in and took effect — a random 20-40 block offset around every spawn point, and on a skyblock map an offset flies you off the island, so monsters spawned into the void in batches with `fell out of the world` all over the console, and the symptom was "not one monster in wave 1". **Wiring a config in activates a surface that was asleep** — that lesson bought the safe-landing retry: scan downward for the first solid non-liquid block and take its top face, require 2 clear blocks above the landing spot, treat anything near the world floor as unsafe and return null, and after eight offset candidates fail, fall back to the base point. **The cost**: the shipped template's radius went from 20/40 to 1/3 across the board, and an existing server's `arenas.yml` has to be edited smaller by hand or rely on the fallback detection.

## Feedback & contributions

**What counts as a bug**: the plugin says one thing and does another — a rollback that was incomplete but logged no warning, [Confirm and save] refused for no reason, `/hilltop start` claiming too few players when there are enough, a configuration eaten by an upgrade. **What counts as a proposal**: play and feel — new monster tactics, wave pacing, GUI layout; in the equipment-progression line, enchanting is already on the plan.

**Run three things before filing an issue**; those three blocks of output save both sides one round trip: `grep -n "^## \[" CHANGELOG.md | head -3` (is your version in the newest section), `grep -cE "SEVERE|WARNING" logs/latest.log` (does the console hold warnings that already explain themselves), plus one in-game `/hilltop status` (wave / players / monsters / nodes in one screen).

**Contributing code**: fork → change → get `mvn clean package` passing → open a PR. Before touching nodes, the shop or the listeners, read "Five designs that got dropped" above and the matching sections of `CHANGELOG.md` — the holes those rounds fell into do not need to be fallen into again. Contributions are bound by the same non-commercial license.

## Permissions: 7 nodes, and config is OP-only by default

| Permission node | Default | Note |
|---|---|---|
| `hilltopvillage.admin` | OP | Administrator permission; carries the 6 children below |
| `hilltopvillage.config.admin` | OP | Access to the configuration-center GUI |
| `hilltopvillage.config.rules` | **false** | Game rules |
| `hilltopvillage.config.waves` | **false** | Wave configuration |
| `hilltopvillage.config.monsters` | **false** | Monster attributes |
| `hilltopvillage.config.items` | **false** | Hammer / item configuration |
| `hilltopvillage.config.nodes` | **false** | Energy node configuration |

Everything except `admin` and `config.admin` defaults to false — the person who installs the plugin is not automatically the person who may change the rules. Another 11 player-only subcommands (join / leave / lobby / gui / hammer / fireball / setup / deploy / config / manage / build) are on the console blacklist, and running them from the console is refused.

```bash
awk '/^permissions:/,0' src/main/resources/plugin.yml | grep -cE "^  [a-z]"   # → 7
grep -n "CONSOLE_BLOCKED_COMMANDS" src/main/java/com/hilltopvillage/command/HilltopCommandHandler.java   # → 66 行，11 个
```

## Build it with one mvn command

```bash
mvn clean package
```

The artifact is `target/HilltopVillage-<version>.jar`. Requires JDK 21 + Maven; the dependencies are `paper-api` (provided) and JUnit (test scope) only, so no third-party class is put into the jar.

## License: learn from it, mod it, do not sell it

PolyForm Noncommercial License 1.0.0 — learning, non-commercial play on your own server, and modifying it freely are all fine; **commercial use is not**. GitHub's license field reads it as other (`gh api repos/sqkl520/Minecraft-server-plugin-Hilltop-Defense --jq .license.spdx_id` → `NOASSERTION`; this one needs a network connection and may be skipped), and that is not a detection failure — it is the normal result for a non-commercial license. Any use, modification or distribution must keep the `LICENSE` and `NOTICE` files at the project root and keep them intact — the first line of `head -3 LICENSE` is the full name of the agreement.

<div align="center">

**Walk the 8-step wizard, and players can hold twenty waves.**
**Finish a match, and the map goes back to block one.**

<br>

*"No towers. Eight villagers cannot run."*

</div>
