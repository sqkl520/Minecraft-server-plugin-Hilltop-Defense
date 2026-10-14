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

public class GameRulesMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    public GameRulesMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.rules.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();

        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.CLOCK, lang.getFor(admin, "config.menu.rules.header"));

        addAdjustableItem(10, Material.PLAYER_HEAD, lang.getFor(admin, "config.menu.rules.min-players"),
                cfg.getMinPlayers(), 1, 20, 1,
                () -> cfg.getMinPlayers(), v -> cfg.setMinPlayers(Math.min(20, Math.max(1, v))),
                lang.getFor(admin, "config.menu.rules.min-players-desc"));

        addAdjustableItem(12, Material.PLAYER_HEAD, lang.getFor(admin, "config.menu.rules.max-players"),
                cfg.getMaxPlayers(), 2, 20, 1,
                () -> cfg.getMaxPlayers(), v -> cfg.setMaxPlayers(Math.min(20, Math.max(2, v))),
                lang.getFor(admin, "config.menu.rules.max-players-desc"));

        addAdjustableItem(14, Material.TURTLE_EGG, lang.getFor(admin, "config.menu.rules.victory-waves"),
                cfg.getVictoryWaves(), 1, 100, 1,
                () -> cfg.getVictoryWaves(), v -> cfg.setVictoryWaves(Math.max(1, v)),
                lang.getFor(admin, "config.menu.rules.victory-waves-desc"));

        addAdjustableItem(16, Material.REDSTONE, lang.getFor(admin, "config.menu.rules.wave-interval"),
                cfg.getWaveIntervalSeconds(), 5, 300, 5,
                () -> cfg.getWaveIntervalSeconds(), v -> cfg.setWaveIntervalSeconds(Math.max(5, v)),
                lang.getFor(admin, "config.menu.rules.wave-interval-desc"));

        addAdjustableItem(20, Material.COMPASS, lang.getFor(admin, "config.menu.rules.spawn-min-radius"),
                cfg.getSpawnRadiusMin(), 5, 100, 2,
                () -> cfg.getSpawnRadiusMin(), v -> cfg.setSpawnRadiusMin(Math.max(5, v)),
                lang.getFor(admin, "config.menu.rules.spawn-min-radius-desc"));

        addAdjustableItem(22, Material.COMPASS, lang.getFor(admin, "config.menu.rules.spawn-max-radius"),
                cfg.getSpawnRadiusMax(), 10, 200, 5,
                () -> cfg.getSpawnRadiusMax(),
                v -> cfg.setSpawnRadiusMax(Math.max(cfg.getSpawnRadiusMin() + 5, v)),
                lang.getFor(admin, "config.menu.rules.spawn-max-radius-desc"));

        addAdjustableItem(24, Material.SPAWNER, lang.getFor(admin, "config.menu.rules.global-mob-cap"),
                cfg.getGlobalMobCap(), 10, 500, 5,
                () -> cfg.getGlobalMobCap(), v -> cfg.setGlobalMobCap(Math.max(10, v)),
                lang.getFor(admin, "config.menu.rules.global-mob-cap-desc"));

        addAdjustableItem(26, Material.SPAWNER, lang.getFor(admin, "config.menu.rules.mob-cap-per-player"),
                cfg.getMobCapPerPlayer(), 3, 100, 1,
                () -> cfg.getMobCapPerPlayer(), v -> cfg.setMobCapPerPlayer(Math.max(3, v)),
                lang.getFor(admin, "config.menu.rules.mob-cap-per-player-desc"));

        setBackButton(ct -> navigateBack());
        fillEmpty();
    }

    private void addAdjustableItem(int slot, Material icon, String name, int current,
                                    int min, int max, int step,
                                    java.util.function.IntSupplier getter,
                                    java.util.function.IntConsumer setter,
                                    String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current));
        loreLines.add("");
        loreLines.add(lang.getFor(admin, "config.menu.common.left-click") + step + " | " + lang.getFor(admin, "config.menu.common.right-click") + step);
        loreLines.add(lang.getFor(admin, "config.menu.common.shift-custom"));
        for (String l : extraLore) {
            loreLines.add(ChatColor.DARK_GRAY + l);
        }

        setClickableItemWithData(slot, icon,
                ChatColor.GREEN + name,
                loreLines,
                clickType -> {
                    if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestInteger(plugin, admin,
                                lang.getFor(admin, "config.menu.common.setting-prefix") + name + " (" + lang.getFor(admin, "config.menu.common.current") + getter.getAsInt() + ")",
                                v -> {
                                    setter.accept(v);
                                    reopen();
                                },
                                this::reopen, lang);
                    } else if (clickType == ClickType.RIGHT) {
                        int val = getter.getAsInt() - step;
                        if (val >= min) setter.accept(val);
                        reopen();
                    } else {
                        int val = getter.getAsInt() + step;
                        if (val <= max) setter.accept(val);
                        reopen();
                    }
                });
    }

    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }
}