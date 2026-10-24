# Shop Customization (shop.yml Full Reference)

> Moved from the English README. The README keeps a summary and links here.

## Shop villager deployment

**Shop villager deployment**:

- Setup wizard Step 6 (`/hilltop setup`): stand at the target spot and click [Set Shop Location] (required — deployment cannot finish without a shop location). After setting you stay on the step: click [Set Shop Location] again to move it, click [Clear Shop Location] to wipe it and start over (you stay on Step 6), then click [Continue] to open the preview
- Commands work too: `/hilltop deploy setshop` sets your position and spawns the NPC, `/hilltop deploy clearshop` clears this arena's shop (stored location + entity); the `/hilltop manage` detail page also clears the shop on right-click
- Clearing is **refused while a match is in progress** (the shop NPC is a plugin-level singleton — clearing would take the shop away from players mid-match); run `/hilltop stop` first
- The position persists per arena in the `arenas.<id>.shop-npc` node of `arenas.yml` and auto-restores on restart; leftover duplicate entities are cleaned up automatically on startup
- The shop villager is invulnerable (all damage cancelled) and non-interactive — it exists purely as the shop entry
- Facing is horizontal only: deployment copies just the player's yaw and forces pitch to 0 (the villager never looks down/up; even a pitch written wrong in an old save is auto-corrected on restart), and once spawned it turns horizontally to face the nearest player within 8 blocks

## Custom items in shop.yml

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

## Item entry reference

- Every item entry, price, name and durability is customizable in `shop.yml` — **server owners can freely add/remove items and change prices or slots**
- `type` values: `HAMMER` (Sacred Hammer, always 1 regardless of amount) / `FIREBALL` / `REPAIR_ITEM` (World Tree Sap) / `MATERIAL` (any vanilla item, gives `amount` of `icon.material`) / `EQUIPMENT` (delivers 1 item of `icon.material`; `icon.material` is **required** = the delivered item material, `name` / `lore` are optional but are written onto the delivered item, `unbreakable` optional = unbreakable)
- Run `/hilltop shop reload` (admin) after editing — no restart needed
- Anti-exploit: all clicks and drags inside the shop GUI are cancelled (icons can't be taken), and purchases have a 250ms cooldown against double-click double-charging
- Currency anti-spoofing: Energy Crystals are identified by an item PDC tag, so **renaming a plain emerald to the currency name with an anvil/name tag will NOT count as money**. To hand out currency manually, use `/hilltop shop give <player> [amount]` (emeralds from vanilla `/give` lack the tag and are not accepted)

## Upgrade note for existing servers

> **Upgrade note for existing servers**: `shop.yml` uses "**file exists = template not released**" — after upgrading, if the server already has an old `shop.yml`, the **new items (bow/arrows/swords/armor) will NOT appear automatically**. Update the server's `shop.yml` with the new template (manually add the item entries, or back up and delete the old file to let it regenerate and then re-apply your customizations), then run `/hilltop shop reload`.
