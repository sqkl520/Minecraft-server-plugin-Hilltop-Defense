package com.hilltopvillage.config.menu;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Monster attribute editor menu (54-slot large chest).
 * Supports three page tabs (Explode Beetle / Hook Claw Hunter / Flying Dropper),
 * each page editable: base attributes, model appearance, weapon equipment, armor, unique skill params.
 */
public class MonsterSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    private enum Page { EXPLODE_BEETLE, HOOK_CLAW_HUNTER, FLYING_DROPPER }

    private Page currentPage = Page.EXPLODE_BEETLE;

    public MonsterSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.monsters.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.SKELETON_SPAWN_EGG, lang.getFor(admin, "config.menu.monsters.header"));

        setClickableItem(9, Material.CAVE_SPIDER_SPAWN_EGG,
                lang.getFor(admin, "config.menu.monsters.explode-beetle"),
                ct -> { currentPage = Page.EXPLODE_BEETLE; reopen(); },
                lang.getFor(admin, "config.menu.monsters.explode-beetle-desc"));
        setClickableItem(10, Material.SKELETON_SPAWN_EGG,
                lang.getFor(admin, "config.menu.monsters.hook-claw-hunter"),
                ct -> { currentPage = Page.HOOK_CLAW_HUNTER; reopen(); },
                lang.getFor(admin, "config.menu.monsters.hook-claw-hunter-desc"));
        setClickableItem(11, Material.PHANTOM_SPAWN_EGG,
                lang.getFor(admin, "config.menu.monsters.flying-dropper"),
                ct -> { currentPage = Page.FLYING_DROPPER; reopen(); },
                lang.getFor(admin, "config.menu.monsters.flying-dropper-desc"));

        String monsterKey;
        String monsterDisplayName;
        switch (currentPage) {
            case EXPLODE_BEETLE:
                monsterKey = "explode-beetle";
                monsterDisplayName = lang.getFor(admin, "config.menu.monsters.explode-beetle").replaceAll("&[0-9a-fk-or]", "");
                setItem(13, Material.CAVE_SPIDER_SPAWN_EGG, lang.getFor(admin, "config.menu.monsters.editing") + monsterDisplayName);
                break;
            case HOOK_CLAW_HUNTER:
                monsterKey = "hook-claw-hunter";
                monsterDisplayName = lang.getFor(admin, "config.menu.monsters.hook-claw-hunter").replaceAll("&[0-9a-fk-or]", "");
                setItem(13, Material.SKELETON_SPAWN_EGG, lang.getFor(admin, "config.menu.monsters.editing") + monsterDisplayName);
                break;
            default:
                monsterKey = "flying-dropper";
                monsterDisplayName = lang.getFor(admin, "config.menu.monsters.flying-dropper").replaceAll("&[0-9a-fk-or]", "");
                setItem(13, Material.PHANTOM_SPAWN_EGG, lang.getFor(admin, "config.menu.monsters.editing") + monsterDisplayName);
                break;
        }

        GameConfig.MonsterConfig mc = cfg.getMonsters().get(monsterKey);
        if (mc == null) return;

        // ========== Base attributes (Row 3: slots 18-26) ==========
        addAdjustableItem(19, Material.RED_DYE, lang.getFor(admin, "config.menu.monsters.health"), mc.getHealth(),
                5, 500, 10, mc::getHealth, mc::setHealth);
        addAdjustableItem(20, Material.FEATHER, lang.getFor(admin, "config.menu.monsters.speed"), mc.getSpeed(),
                0.1, 2.0, 0.05, mc::getSpeed, mc::setSpeed);
        addAdjustableItem(21, Material.DIAMOND_SWORD, lang.getFor(admin, "config.menu.monsters.base-damage"), mc.getBaseDamage(),
                0.5, 100, 1.0, mc::getBaseDamage, mc::setBaseDamage,
                lang.getFor(admin, "config.menu.monsters.base-damage-desc"));

        // ========== Model appearance (Row 4: slots 27-35) ==========
        setItem(27, Material.PAINTING, lang.getFor(admin, "config.menu.monsters.model-appearance"),
                lang.getFor(admin, "config.menu.monsters.model-appearance-desc"));

        String iaId = mc.getItemsAdderId();
        String iaDisplay = (iaId != null && !iaId.isEmpty()) ? iaId : lang.getFor(admin, "config.menu.common.unset");
        setClickableItem(28, Material.NAME_TAG, lang.getFor(admin, "config.menu.monsters.ia-model"),
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestString(plugin, admin,
                            lang.getFor(admin, "config.menu.monsters.ia-model-prompt"),
                            v -> {
                                mc.setItemsAdderId(v != null ? v : "");
                                reopen();
                            },
                            this::reopen, lang);
                },
                lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + iaDisplay,
                lang.getFor(admin, "config.menu.common.shift-edit"));

        addAdjustableItem(29, Material.ITEM_FRAME, lang.getFor(admin, "config.menu.monsters.custom-model-data"), mc.getCustomModelData(),
                0, 999999, 1,
                () -> (double) mc.getCustomModelData(),
                v -> mc.setCustomModelData((int) v),
                lang.getFor(admin, "config.menu.monsters.custom-model-data-desc"));

        // ========== Hand equipment (Row 4: slots 30-31) ==========
        Material mainHand = mc.getMainHandItem();
        String mainHandName = (mainHand != null && mainHand != Material.AIR) ? translateMaterial(mainHand) : lang.getFor(admin, "config.menu.common.empty-hand");
        Material mainIcon = (mainHand != null && mainHand != Material.AIR) ? mainHand : Material.BARRIER;
        setClickableItem(30, mainIcon, lang.getFor(admin, "config.menu.monsters.main-hand"),
                ct -> {
                    Material next = cycleMaterial(mc.getMainHandItem(), WEAPON_CYCLE);
                    mc.setMainHandItem(next);
                    reopen();
                },
                lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + mainHandName,
                lang.getFor(admin, "config.menu.common.click-toggle-custom"),
                lang.getFor(admin, "config.menu.monsters.cycle-weapons"));

        Material offHand = mc.getOffHandItem();
        String offHandName = (offHand != null && offHand != Material.AIR) ? translateMaterial(offHand) : lang.getFor(admin, "config.menu.common.empty-hand");
        Material offIcon = (offHand != null && offHand != Material.AIR) ? offHand : Material.BARRIER;
        setClickableItem(31, offIcon, lang.getFor(admin, "config.menu.monsters.off-hand"),
                ct -> {
                    if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestString(plugin, admin,
                                lang.getFor(admin, "config.menu.monsters.offhand-prompt"),
                                v -> {
                                    if (v == null || v.isEmpty()) {
                                        mc.setOffHandItem(null);
                                    } else {
                                        Material m = Material.getMaterial(v.toUpperCase());
                                        if (m != null) mc.setOffHandItem(m);
                                    }
                                    reopen();
                                },
                                this::reopen, lang);
                    } else {
                        Material next = cycleMaterial(mc.getOffHandItem(), OFFHAND_CYCLE);
                        mc.setOffHandItem(next);
                        reopen();
                    }
                },
                lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + offHandName,
                lang.getFor(admin, "config.menu.common.click-toggle-custom"),
                lang.getFor(admin, "config.menu.monsters.cycle-offhand"));

        // ========== Armor wear (Row 5: slots 36-44) ==========
        setItem(36, Material.IRON_CHESTPLATE, lang.getFor(admin, "config.menu.monsters.armor-wear"),
                lang.getFor(admin, "config.menu.monsters.armor-wear-desc"));

        addArmorSlot(37, lang.getFor(admin, "config.menu.monsters.helmet"), mc.getHelmet(), v -> mc.setHelmet(v),
                lang.getFor(admin, "config.menu.monsters.cycle-helmet"));
        addArmorSlot(38, lang.getFor(admin, "config.menu.monsters.chestplate"), mc.getChestplate(), v -> mc.setChestplate(v),
                lang.getFor(admin, "config.menu.monsters.cycle-chestplate"));
        addArmorSlot(39, lang.getFor(admin, "config.menu.monsters.leggings"), mc.getLeggings(), v -> mc.setLeggings(v),
                lang.getFor(admin, "config.menu.monsters.cycle-leggings"));
        addArmorSlot(40, lang.getFor(admin, "config.menu.monsters.boots"), mc.getBoots(), v -> mc.setBoots(v),
                lang.getFor(admin, "config.menu.monsters.cycle-boots"));

        // ========== Unique skill params (Row 6: slots 45-52) ==========
        if (currentPage == Page.EXPLODE_BEETLE) {
            addAdjustableItem(46, Material.TNT, lang.getFor(admin, "config.menu.monsters.explode-multiplier"), mc.getExplodeDamageMultiplier(),
                    0.5, 10, 0.5, mc::getExplodeDamageMultiplier, mc::setExplodeDamageMultiplier,
                    lang.getFor(admin, "config.menu.monsters.explode-multiplier-desc"));

            // STORY-1.6：自爆是否附带原版实体爆炸（关 → 只结算节点伤害 + 纯视觉，不炸村民/玩家）
            Material toggleIcon = mc.isEntityExplosion() ? Material.GREEN_WOOL : Material.RED_WOOL;
            setClickableItem(47, toggleIcon, lang.getFor(admin, "config.menu.monsters.entity-explosion"),
                    ct -> { mc.setEntityExplosion(!mc.isEntityExplosion()); reopen(); },
                    lang.getFor(admin, "config.menu.monsters.entity-explosion-desc"),
                    lang.getFor(admin, "config.menu.common.current")
                            + lang.getFor(admin, mc.isEntityExplosion()
                                    ? "config.menu.monsters.entity-explosion-on"
                                    : "config.menu.monsters.entity-explosion-off"),
                    lang.getFor(admin, "config.menu.common.click-cycle"));

            setItem(48, Material.KNOWLEDGE_BOOK, lang.getFor(admin, "config.menu.monsters.explode-logic"),
                    lang.getFor(admin, "config.menu.monsters.explode-lore1"),
                    lang.getFor(admin, "config.menu.monsters.explode-lore2"),
                    lang.getFor(admin, "config.menu.monsters.explode-lore3"),
                    lang.getFor(admin, "config.menu.monsters.explode-lore4"),
                    lang.getFor(admin, "config.menu.monsters.explode-lore5"));

        } else if (currentPage == Page.HOOK_CLAW_HUNTER) {
            addAdjustableItem(46, Material.FISHING_ROD, lang.getFor(admin, "config.menu.monsters.hook-velocity"), mc.getHookVelocity(),
                    0.5, 5.0, 0.1, mc::getHookVelocity, mc::setHookVelocity,
                    lang.getFor(admin, "config.menu.monsters.hook-velocity-desc"));
            addAdjustableItem(47, Material.CLOCK, lang.getFor(admin, "config.menu.monsters.cooldown-ticks"), mc.getCooldownTicks(),
                    20, 600, 20,
                    () -> (double) mc.getCooldownTicks(),
                    v -> mc.setCooldownTicks((int) v),
                    lang.getFor(admin, "config.menu.monsters.cooldown-ticks-desc"));
            addAdjustableItem(48, Material.COBWEB, lang.getFor(admin, "config.menu.monsters.stun-duration"), mc.getStunDurationTicks(),
                    10, 200, 10,
                    () -> (double) mc.getStunDurationTicks(),
                    v -> mc.setStunDurationTicks((int) v),
                    lang.getFor(admin, "config.menu.monsters.stun-duration-desc"));

            setItem(49, Material.KNOWLEDGE_BOOK, lang.getFor(admin, "config.menu.monsters.target-info"),
                    lang.getFor(admin, "config.menu.monsters.target-lore1"),
                    lang.getFor(admin, "config.menu.monsters.target-lore2"),
                    lang.getFor(admin, "config.menu.monsters.target-lore3"),
                    lang.getFor(admin, "config.menu.monsters.target-lore4"));

        } else {
            addAdjustableItem(46, Material.CLOCK, lang.getFor(admin, "config.menu.monsters.airdrop-cooldown"), mc.getCooldownTicks(),
                    20, 600, 20,
                    () -> (double) mc.getCooldownTicks(),
                    v -> mc.setCooldownTicks((int) v),
                    lang.getFor(admin, "config.menu.monsters.airdrop-cooldown-desc"));
            addAdjustableItem(47, Material.ZOMBIE_SPAWN_EGG, lang.getFor(admin, "config.menu.monsters.airdrop-count"), mc.getAirdropMobCount(),
                    1, 10, 1,
                    () -> (double) mc.getAirdropMobCount(),
                    v -> mc.setAirdropMobCount((int) v),
                    lang.getFor(admin, "config.menu.monsters.airdrop-count-desc"));

            setItem(48, Material.KNOWLEDGE_BOOK, lang.getFor(admin, "config.menu.monsters.airdrop-logic"),
                    lang.getFor(admin, "config.menu.monsters.airdrop-lore1"),
                    lang.getFor(admin, "config.menu.monsters.airdrop-lore2"),
                    lang.getFor(admin, "config.menu.monsters.airdrop-lore3"),
                    lang.getFor(admin, "config.menu.monsters.airdrop-lore4"));
        }

        setBackButton(ct -> navigateBack());
        fillEmpty();
    }

    /*
     * ======================== Helper methods ========================
     */

    /** Armor slot edit item: click to cycle preset material, Shift+Click for custom input. */
    private void addArmorSlot(int slot, String label, Material current,
                               java.util.function.Consumer<Material> setter, String cycleHint) {
        String name = (current != null && current != Material.AIR) ? translateMaterial(current) : lang.getFor(admin, "config.menu.common.none");
        Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;

        setClickableItem(slot, icon, ChatColor.GREEN + label,
                ct -> {
                    if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestString(plugin, admin,
                                lang.getFor(admin, "config.menu.monsters.armor-prompt", label),
                                v -> {
                                    if (v == null || v.isEmpty()) {
                                        setter.accept(null);
                                    } else {
                                        Material m = Material.getMaterial(v.toUpperCase());
                                        if (m != null) setter.accept(m);
                                    }
                                    reopen();
                                },
                                this::reopen, lang);
                    } else {
                        Material next = cycleMaterial(current, ARMOR_CYCLE);
                        setter.accept(next);
                        reopen();
                    }
                },
                lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + name,
                lang.getFor(admin, "config.menu.common.click-toggle-custom"),
                cycleHint);
    }

    /** Adjustable numeric edit item (supports double). */
    private void addAdjustableItem(int slot, Material icon, String name, double current,
                                    double min, double max, double step,
                                    java.util.function.DoubleSupplier getter,
                                    java.util.function.DoubleConsumer setter,
                                    String... extraLore) {
        List<String> loreLines = new ArrayList<>();
        loreLines.add(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current));
        loreLines.add("");
        loreLines.add(lang.getFor(admin, "config.menu.common.left-click") + formatStep(step) + " | " + lang.getFor(admin, "config.menu.common.right-click") + formatStep(step));
        loreLines.add(lang.getFor(admin, "config.menu.common.shift-custom"));
        for (String l : extraLore) loreLines.add(ChatColor.DARK_GRAY + l);

        setClickableItemWithData(slot, icon, ChatColor.GREEN + name, loreLines,
                clickType -> {
                    if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestNumber(plugin, admin,
                                lang.getFor(admin, "config.menu.common.setting-prefix") + name + " (" + lang.getFor(admin, "config.menu.common.current") + String.format("%.1f", getter.getAsDouble()) + ")",
                                v -> { setter.accept(v); reopen(); },
                                this::reopen, lang);
                    } else if (clickType == ClickType.RIGHT) {
                        double val = getter.getAsDouble() - step;
                        if (val >= min) setter.accept(val);
                        reopen();
                    } else {
                        double val = getter.getAsDouble() + step;
                        if (val <= max) setter.accept(val);
                        reopen();
                    }
                });
    }

    /** Step formatting: hide decimals for integer steps. */
    private String formatStep(double step) {
        return (step == Math.floor(step)) ? String.valueOf((int) step) : String.format("%.1f", step);
    }

    /** Translate material name using i18n, falling back to English name. */
    private String translateMaterial(Material mat) {
        if (mat == null) return lang.getFor(admin, "config.menu.common.none");
        String key = "config.menu.mat." + mat.name().toLowerCase().replace('_', '-');
        String translated = lang.getFor(admin, key);
        return translated.equals(key) ? mat.name().toLowerCase().replace('_', ' ') : translated;
    }

    /** Cycle to next material in preset list. null means clear. */
    private Material cycleMaterial(Material current, Material[] cycleList) {
        if (current == null || current == Material.AIR) {
            return cycleList.length > 0 ? cycleList[0] : null;
        }
        for (int i = 0; i < cycleList.length; i++) {
            if (cycleList[i] == current) {
                return cycleList[(i + 1) % cycleList.length];
            }
        }
        return cycleList[0];
    }

    // ---- Material cycle lists ----
    private static final Material[] WEAPON_CYCLE = {
        Material.DIAMOND_AXE, Material.DIAMOND_SWORD, Material.BOW,
        Material.TRIDENT, Material.NETHERITE_AXE, Material.NETHERITE_SWORD,
        Material.IRON_AXE, Material.IRON_SWORD, Material.STONE_AXE,
        Material.STONE_SWORD, Material.WOODEN_AXE, Material.WOODEN_SWORD,
        Material.AIR
    };
    private static final Material[] OFFHAND_CYCLE = {
        Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.AIR
    };
    private static final Material[] ARMOR_CYCLE = {
        Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET,
        Material.DIAMOND_HELMET, Material.NETHERITE_HELMET, Material.GOLDEN_HELMET,
        Material.AIR
    };

    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }
}