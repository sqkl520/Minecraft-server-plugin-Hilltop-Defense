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

public class NodeSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    public NodeSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.nodes.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.BEACON, lang.getFor(admin, "config.menu.nodes.header"));

        addAdjustableItem(10, Material.ENCHANTING_TABLE, lang.getFor(admin, "config.menu.nodes.base-health"),
                cfg.getNodeBaseHealth(), 10, 1000, 50,
                () -> cfg.getNodeBaseHealth(), v -> cfg.setNodeBaseHealth(v),
                lang.getFor(admin, "config.menu.nodes.base-health-desc"));

        addAdjustableItem(12, Material.GOLDEN_APPLE, lang.getFor(admin, "config.menu.nodes.repair-amount"),
                cfg.getRepairAmountPerCost(), 10, 500, 25,
                () -> cfg.getRepairAmountPerCost(), v -> cfg.setRepairAmountPerCost(v),
                lang.getFor(admin, "config.menu.nodes.repair-amount-desc"));

        addAdjustableItem(14, Material.ENDER_EYE, lang.getFor(admin, "config.menu.nodes.buff-radius"),
                cfg.getNodeBuffRadius(), 3, 60, 2,
                () -> cfg.getNodeBuffRadius(), v -> cfg.setNodeBuffRadius(v),
                lang.getFor(admin, "config.menu.nodes.buff-radius-desc"));

        addAdjustableItem(16, Material.TNT, lang.getFor(admin, "config.menu.nodes.self-destruct"),
                cfg.getNodeSelfDestructMultiplier(), 0.5, 10, 0.5,
                () -> cfg.getNodeSelfDestructMultiplier(), v -> cfg.setNodeSelfDestructMultiplier(v),
                lang.getFor(admin, "config.menu.nodes.self-destruct-desc"));

        // 节点方块类型固定为重生锚，此处仅作说明性展示
        setItem(20, Material.RESPAWN_ANCHOR, lang.getFor(admin, "config.menu.nodes.block-types-header"),
                lang.getFor(admin, "config.menu.nodes.block-types-fixed"));

        setItem(22, Material.GHAST_TEAR, lang.getFor(admin, "config.menu.nodes.repair-item"),
                lang.getFor(admin, "config.menu.nodes.repair-item-current") + ChatColor.AQUA + cfg.getRepairCostItem().name(),
                lang.getFor(admin, "config.menu.nodes.repair-item-cmd1"),
                lang.getFor(admin, "config.menu.nodes.repair-item-cmd2"),
                lang.getFor(admin, "config.menu.nodes.repair-item-hint"));

        setBackButton(ct -> navigateBack());
        fillEmpty();
    }

    private void addAdjustableItem(int slot, Material icon, String name, double current,
                                    double min, double max, double step,
                                    java.util.function.DoubleSupplier getter,
                                    java.util.function.DoubleConsumer setter,
                                    String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current));
        loreLines.add("");
        loreLines.add(lang.getFor(admin, "config.menu.common.left-click") + step + " | " + lang.getFor(admin, "config.menu.common.right-click") + step);
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

    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }
}