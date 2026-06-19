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

/**
 * Gameplay item settings (54-slot large chest).
 * Manages all configurable properties for Sacred Hammer and Fireball.
 */
public class ItemSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    private enum Page { HAMMER, FIREBALL }
    private Page currentPage = Page.HAMMER;

    public ItemSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.items.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.NETHERITE_AXE, lang.getFor(admin, "config.menu.items.header"));

        // ---- Item tab switch (Row 2: 10-11) ----
        setClickableItem(10, Material.NETHERITE_AXE,
                (currentPage == Page.HAMMER ? ChatColor.GREEN : ChatColor.GRAY) + lang.getFor(admin, "config.menu.items.hammer-tab"),
                ct -> { currentPage = Page.HAMMER; reopen(); },
                lang.getFor(admin, "config.menu.items.hammer-tab-desc"));
        setClickableItem(11, Material.FIRE_CHARGE,
                (currentPage == Page.FIREBALL ? ChatColor.GREEN : ChatColor.GRAY) + lang.getFor(admin, "config.menu.items.fireball-tab"),
                ct -> { currentPage = Page.FIREBALL; reopen(); },
                lang.getFor(admin, "config.menu.items.fireball-tab-desc"));

        if (currentPage == Page.HAMMER) {
            buildHammerPage(cfg);
        } else {
            buildFireballPage(cfg);
        }

        setBackButton(ct -> navigateBack());
        fillEmpty();
    }

    private void buildHammerPage(GameConfig cfg) {
        setItem(13, Material.NETHERITE_AXE, lang.getFor(admin, "config.menu.items.editing-hammer"));

        // Row 3: Global params
        addIntItem(19, Material.CLOCK, lang.getFor(admin, "config.menu.items.hammer.smash-timeout"), cfg.getHammerSmashTimeoutTicks(),
                20, 1200, 20, () -> cfg.getHammerSmashTimeoutTicks(), v -> cfg.setHammerSmashTimeoutTicks(v),
                lang.getFor(admin, "config.menu.items.hammer.smash-timeout-desc"));

        addIntItem(21, Material.COBWEB, lang.getFor(admin, "config.menu.items.hammer.aftershock"), cfg.getHammerAftershockTicks(),
                10, 200, 10, () -> cfg.getHammerAftershockTicks(), v -> cfg.setHammerAftershockTicks(v),
                lang.getFor(admin, "config.menu.items.hammer.aftershock-desc"));

        // Row 4: Low + Medium tier
        setItem(28, Material.GREEN_WOOL, lang.getFor(admin, "config.menu.items.hammer.low-tier"));
        addDblItem(29, Material.FEATHER, lang.getFor(admin, "config.menu.items.hammer.low-charge"), cfg.getHammerLowCharge(),
                0.5, 30, 0.5, cfg::getHammerLowCharge, cfg::setHammerLowCharge,
                lang.getFor(admin, "config.menu.items.hammer.low-charge-desc"));
        addDblItem(30, Material.IRON_SWORD, lang.getFor(admin, "config.menu.items.hammer.low-damage"), cfg.getHammerLowDamage(),
                1, 500, 5, cfg::getHammerLowDamage, cfg::setHammerLowDamage,
                lang.getFor(admin, "config.menu.items.hammer.low-damage-desc"));
        addDblItem(31, Material.SLIME_BALL, lang.getFor(admin, "config.menu.items.hammer.low-radius"), cfg.getHammerLowRadius(),
                1, 30, 1, cfg::getHammerLowRadius, cfg::setHammerLowRadius,
                lang.getFor(admin, "config.menu.items.hammer.low-radius-desc"));

        setItem(32, Material.YELLOW_WOOL, lang.getFor(admin, "config.menu.items.hammer.med-tier"));
        addDblItem(33, Material.FEATHER, lang.getFor(admin, "config.menu.items.hammer.med-charge"), cfg.getHammerMediumCharge(),
                0.5, 50, 0.5, cfg::getHammerMediumCharge, cfg::setHammerMediumCharge,
                lang.getFor(admin, "config.menu.items.hammer.med-charge-desc"));
        addDblItem(34, Material.IRON_SWORD, lang.getFor(admin, "config.menu.items.hammer.med-damage"), cfg.getHammerMediumDamage(),
                1, 500, 5, cfg::getHammerMediumDamage, cfg::setHammerMediumDamage,
                lang.getFor(admin, "config.menu.items.hammer.med-damage-desc"));
        addDblItem(35, Material.SLIME_BALL, lang.getFor(admin, "config.menu.items.hammer.med-radius"), cfg.getHammerMediumRadius(),
                1, 30, 1, cfg::getHammerMediumRadius, cfg::setHammerMediumRadius,
                lang.getFor(admin, "config.menu.items.hammer.med-radius-desc"));

        // Row 5: High tier + Model
        setItem(37, Material.RED_WOOL, lang.getFor(admin, "config.menu.items.hammer.high-tier"));
        addDblItem(38, Material.FEATHER, lang.getFor(admin, "config.menu.items.hammer.high-charge"), cfg.getHammerHighCharge(),
                0.5, 100, 0.5, cfg::getHammerHighCharge, cfg::setHammerHighCharge,
                lang.getFor(admin, "config.menu.items.hammer.high-charge-desc"));
        addDblItem(39, Material.IRON_SWORD, lang.getFor(admin, "config.menu.items.hammer.high-damage"), cfg.getHammerHighDamage(),
                1, 500, 5, cfg::getHammerHighDamage, cfg::setHammerHighDamage,
                lang.getFor(admin, "config.menu.items.hammer.high-damage-desc"));
        addDblItem(40, Material.SLIME_BALL, lang.getFor(admin, "config.menu.items.hammer.high-radius"), cfg.getHammerHighRadius(),
                1, 30, 1, cfg::getHammerHighRadius, cfg::setHammerHighRadius,
                lang.getFor(admin, "config.menu.items.hammer.high-radius-desc"));

        setItem(43, Material.PAINTING, lang.getFor(admin, "config.menu.items.hammer.model"));
        addMaterialCycle(44, lang.getFor(admin, "config.menu.items.hammer.mat"), cfg.getHammerMaterial(),
                cfg::setHammerMaterial, HAMMER_MAT_LIST, lang.getFor(admin, "config.menu.items.hammer.mat-desc"));

        addIntItem(52, Material.ITEM_FRAME, lang.getFor(admin, "config.menu.items.hammer.custom-model"), cfg.getHammerCustomModelData(),
                0, 999999, 1, () -> cfg.getHammerCustomModelData(), cfg::setHammerCustomModelData,
                lang.getFor(admin, "config.menu.items.hammer.custom-model-desc"));

        addStrItem(53, Material.NAME_TAG, lang.getFor(admin, "config.menu.items.hammer.ia-model"), cfg.getHammerItemsAdderId(),
                cfg::setHammerItemsAdderId, lang.getFor(admin, "config.menu.items.enter-model-id"));
    }

    private void buildFireballPage(GameConfig cfg) {
        setItem(13, Material.FIRE_CHARGE, lang.getFor(admin, "config.menu.items.editing-fireball"));

        addDblItem(19, Material.IRON_SWORD, lang.getFor(admin, "config.menu.items.fireball.damage"), cfg.getFireballDamage(),
                1, 200, 5, cfg::getFireballDamage, cfg::setFireballDamage,
                lang.getFor(admin, "config.menu.items.fireball.damage-desc"));

        addDblItem(21, Material.SLIME_BALL, lang.getFor(admin, "config.menu.items.fireball.radius"), cfg.getFireballExplosionRadius(),
                1, 20, 1, cfg::getFireballExplosionRadius, cfg::setFireballExplosionRadius,
                lang.getFor(admin, "config.menu.items.fireball.radius-desc"));

        addDblItem(23, Material.FEATHER, lang.getFor(admin, "config.menu.items.fireball.speed"), cfg.getFireballSpeed(),
                0.5, 5, 0.5, cfg::getFireballSpeed, cfg::setFireballSpeed,
                lang.getFor(admin, "config.menu.items.fireball.speed-desc"));

        addIntItem(25, Material.REDSTONE, lang.getFor(admin, "config.menu.items.fireball.cooldown"), cfg.getFireballCooldownTicks(),
                0, 600, 1, () -> cfg.getFireballCooldownTicks(), v -> cfg.setFireballCooldownTicks(v),
                lang.getFor(admin, "config.menu.items.fireball.cooldown-desc"));

        addDblItem(28, Material.FEATHER, lang.getFor(admin, "config.menu.items.fireball.knockback"), cfg.getFireballKnockback(),
                0.5, 20, 0.5, cfg::getFireballKnockback, cfg::setFireballKnockback,
                lang.getFor(admin, "config.menu.items.fireball.knockback-desc"));

        addIntItem(30, Material.ENDER_PEARL, lang.getFor(admin, "config.menu.items.fireball.max-travel"), cfg.getFireballMaxTravelTicks(),
                10, 600, 10, () -> cfg.getFireballMaxTravelTicks(), v -> cfg.setFireballMaxTravelTicks(v),
                lang.getFor(admin, "config.menu.items.fireball.max-travel-desc"));

        addDblItem(32, Material.STRING, lang.getFor(admin, "config.menu.items.fireball.proximity-fuse"), cfg.getFireballProximityFuse(),
                0, 5, 0.5, cfg::getFireballProximityFuse, cfg::setFireballProximityFuse,
                lang.getFor(admin, "config.menu.items.fireball.proximity-fuse-desc"));

        setItem(33, Material.PAINTING, lang.getFor(admin, "config.menu.items.fireball.mat-section"));

        addMaterialCycle(34, lang.getFor(admin, "config.menu.items.fireball.mat"), cfg.getFireballMaterial(),
                cfg::setFireballMaterial, FIREBALL_MAT_LIST, lang.getFor(admin, "config.menu.items.fireball.mat-desc"));
    }

    /* === General adjustable item helpers === */

    private void addDblItem(int slot, Material icon, String name, double current,
                            double min, double max, double step,
                            java.util.function.DoubleSupplier getter,
                            java.util.function.DoubleConsumer setter,
                            String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current));
        loreLines.add("");
        loreLines.add(lang.getFor(admin, "config.menu.common.left-click") + fmtStep(step) + " | " + lang.getFor(admin, "config.menu.common.right-click") + fmtStep(step));
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

    private void addIntItem(int slot, Material icon, String name, int current,
                             int min, int max, int step,
                             java.util.function.IntSupplier getter,
                             java.util.function.IntConsumer setter,
                             String... extraLore) {
        addDblItem(slot, icon, name, current, min, max, step,
                () -> (double) getter.getAsInt(),
                v -> setter.accept((int) v),
                extraLore);
    }

    private void addStrItem(int slot, Material icon, String name, String current,
                            java.util.function.Consumer<String> setter, String prompt) {
        String display = (current != null && !current.isEmpty()) ? current : lang.getFor(admin, "config.menu.common.unset");
        Material i = (current != null && !current.isEmpty()) ? Material.NAME_TAG : Material.BARRIER;
        setClickableItem(slot, i, ChatColor.GREEN + name,
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestString(plugin, admin, prompt,
                            v -> { setter.accept(v != null ? v : ""); reopen(); },
                            this::reopen, lang);
                },
                lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + display,
                lang.getFor(admin, "config.menu.common.click-edit"));
    }

    private void addMaterialCycle(int slot, String name, Material current,
                                   java.util.function.Consumer<Material> setter,
                                   Material[] cycle, String... lore) {
        String curName = (current != null) ? formatMat(current) : lang.getFor(admin, "config.menu.common.none");
        Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + curName);
        loreLines.add("");
        loreLines.add(lang.getFor(admin, "config.menu.common.click-cycle"));
        for (String l : lore) loreLines.add(ChatColor.DARK_GRAY + l);

        setClickableItemWithData(slot, icon, ChatColor.GREEN + name, loreLines,
                ct -> {
                    Material next = nextMat(current, cycle);
                    setter.accept(next);
                    reopen();
                });
    }

    private Material nextMat(Material cur, Material[] cycle) {
        if (cur == null || cur == Material.AIR) return cycle.length > 0 ? cycle[0] : null;
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == cur) return cycle[(i + 1) % cycle.length];
        }
        return cycle[0];
    }

    private String formatMat(Material m) {
        if (m == null) return lang.getFor(admin, "config.menu.common.none");
        String key = "config.menu.mat." + m.name().toLowerCase().replace('_', '-');
        String translated = lang.getFor(admin, key);
        return translated.equals(key) ? m.name().toLowerCase().replace('_', ' ') : translated;
    }

    private String fmtStep(double s) {
        return (s == Math.floor(s)) ? String.valueOf((int) s) : String.format("%.1f", s);
    }

    private static final Material[] HAMMER_MAT_LIST = {
        Material.NETHERITE_AXE, Material.DIAMOND_AXE, Material.IRON_AXE,
        Material.GOLDEN_AXE, Material.STONE_AXE, Material.WOODEN_AXE
    };
    private static final Material[] FIREBALL_MAT_LIST = {
        Material.FIRE_CHARGE, Material.MAGMA_CREAM, Material.BLAZE_POWDER,
        Material.GUNPOWDER, Material.FIREWORK_ROCKET
    };

    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }
}