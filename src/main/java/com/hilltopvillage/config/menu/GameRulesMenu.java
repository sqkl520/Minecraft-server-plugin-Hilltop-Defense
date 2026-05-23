package com.hilltopvillage.config.menu;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
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

    public GameRulesMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent) {
        super(admin, ChatColor.DARK_GRAY + "游戏规则配置", 36, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();

        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.CLOCK, ChatColor.GREEN + "" + ChatColor.BOLD + "游戏规则配置");

        addAdjustableItem(10, Material.PLAYER_HEAD, "最小玩家数",
                cfg.getMinPlayers(), 1, 20, 1,
                () -> cfg.getMinPlayers(), v -> cfg.setMinPlayers(v),
                "少于该人数时将自动判负");

        addAdjustableItem(12, Material.PLAYER_HEAD, "最大玩家数",
                cfg.getMaxPlayers(), 2, 20, 1,
                () -> cfg.getMaxPlayers(), v -> cfg.setMaxPlayers(Math.min(20, Math.max(2, v))),
                "单局最大参与人数");

        addAdjustableItem(14, Material.TURTLE_EGG, "胜利所需波次",
                cfg.getVictoryWaves(), 1, 100, 1,
                () -> cfg.getVictoryWaves(), v -> cfg.setVictoryWaves(Math.max(1, v)),
                "通关需要的总波次数");

        addAdjustableItem(16, Material.REDSTONE, "波次间隔 (秒)",
                cfg.getWaveIntervalSeconds(), 5, 300, 5,
                () -> cfg.getWaveIntervalSeconds(), v -> cfg.setWaveIntervalSeconds(Math.max(5, v)),
                "每波结束后的休整时间");

        addAdjustableItem(20, Material.COMPASS, "生成最小半径",
                cfg.getSpawnRadiusMin(), 5, 100, 2,
                () -> cfg.getSpawnRadiusMin(), v -> cfg.setSpawnRadiusMin(Math.max(5, v)),
                "怪物在核心周围生成的最小距离");

        addAdjustableItem(22, Material.COMPASS, "生成最大半径",
                cfg.getSpawnRadiusMax(), 10, 200, 5,
                () -> cfg.getSpawnRadiusMax(),
                v -> cfg.setSpawnRadiusMax(Math.max(cfg.getSpawnRadiusMin() + 5, v)),
                "怪物生成的区域外边界");

        addAdjustableItem(24, Material.SPAWNER, "全局怪物上限",
                cfg.getGlobalMobCap(), 10, 500, 5,
                () -> cfg.getGlobalMobCap(), v -> cfg.setGlobalMobCap(Math.max(10, v)),
                "全地图同时存在的最大怪物数");

        addAdjustableItem(26, Material.SPAWNER, "单玩家怪物上限",
                cfg.getMobCapPerPlayer(), 3, 100, 1,
                () -> cfg.getMobCapPerPlayer(), v -> cfg.setMobCapPerPlayer(Math.max(3, v)),
                "每位玩家对应的最大怪物数");

        setBackButton(ct -> navigateBack());
        setCloseButton();
        fillEmpty();
    }

    private void addAdjustableItem(int slot, Material icon, String name, int current,
                                    int min, int max, int step,
                                    java.util.function.IntSupplier getter,
                                    java.util.function.IntConsumer setter,
                                    String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue("当前值", current));
        loreLines.add("");
        loreLines.add(ChatColor.YELLOW + "左键 +" + step + " | 右键 -" + step);
        loreLines.add(ChatColor.AQUA + "Shift+点击 自定义输入");
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
                                "设置" + name + " (当前: " + getter.getAsInt() + ")",
                                v -> {
                                    setter.accept(v);
                                    reopen();
                                },
                                this::reopen);
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