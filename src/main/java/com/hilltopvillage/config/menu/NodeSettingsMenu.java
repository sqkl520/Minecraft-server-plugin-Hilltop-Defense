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

public class NodeSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    public NodeSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent) {
        super(admin, ChatColor.DARK_GRAY + "能量节点设置", 36, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.BEACON, ChatColor.AQUA + "" + ChatColor.BOLD + "能量节点系统");

        addAdjustableItem(10, Material.ENCHANTING_TABLE, "节点基础生命",
                cfg.getNodeBaseHealth(), 10, 1000, 50,
                () -> cfg.getNodeBaseHealth(), v -> cfg.setNodeBaseHealth(v),
                "每个节点的初始生命值");

        addAdjustableItem(12, Material.GOLDEN_APPLE, "单次修复量",
                cfg.getNodeRepairAmount(), 10, 500, 25,
                () -> cfg.getNodeRepairAmount(), v -> cfg.setNodeRepairAmount(v),
                "每次使用世界树汁液恢复的生命");

        addAdjustableItem(14, Material.ENDER_EYE, "Buff生效半径",
                cfg.getNodeBuffRadius(), 3, 60, 2,
                () -> cfg.getNodeBuffRadius(), v -> cfg.setNodeBuffRadius(v),
                "活跃节点对玩家的Buff生效距离 (格)");

        addAdjustableItem(16, Material.TNT, "自爆伤害倍率",
                cfg.getNodeSelfDestructMultiplier(), 0.5, 10, 0.5,
                () -> cfg.getNodeSelfDestructMultiplier(), v -> cfg.setNodeSelfDestructMultiplier(v),
                "甲虫自爆时对节点的伤害加成系数");

        setItem(20, Material.KNOWLEDGE_BOOK, ChatColor.YELLOW + "" + ChatColor.BOLD + "节点方块类型",
                ChatColor.GRAY + "当前共 " + cfg.getNodeBlockTypes().size() + " 种方块",
                ChatColor.GRAY + "管理员可通过以下命令修改:",
                ChatColor.AQUA + "/hilltop config nodes add <材料名>",
                ChatColor.AQUA + "/hilltop config nodes remove <材料名>",
                ChatColor.DARK_GRAY + "默认为信标、附魔台、末影箱、重生锚");

        setItem(22, Material.SLIME_BALL, ChatColor.YELLOW + "修复物品",
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + cfg.getNodeRepairItem().name(),
                ChatColor.GRAY + "管理员可通过命令修改:",
                ChatColor.AQUA + "/hilltop config nodes repairitem <材料名>",
                ChatColor.DARK_GRAY + "玩家手持该物品右键节点即可修复");

        setBackButton(ct -> navigateBack());
        setCloseButton();
        fillEmpty();
    }

    private void addAdjustableItem(int slot, Material icon, String name, double current,
                                    double min, double max, double step,
                                    java.util.function.DoubleSupplier getter,
                                    java.util.function.DoubleConsumer setter,
                                    String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue("当前值", current));
        loreLines.add("");
        loreLines.add(ChatColor.YELLOW + "左键 +" + step + " | 右键 -" + step);
        loreLines.add(ChatColor.AQUA + "Shift+点击 自定义输入");
        for (String l : extraLore) loreLines.add(ChatColor.DARK_GRAY + l);

        setClickableItemWithData(slot, icon, ChatColor.GREEN + name, loreLines,
                clickType -> {
                    if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestNumber(plugin, admin,
                                "设置" + name + " (当前: " + String.format("%.1f", getter.getAsDouble()) + ")",
                                v -> { setter.accept(v); reopen(); },
                                this::reopen);
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