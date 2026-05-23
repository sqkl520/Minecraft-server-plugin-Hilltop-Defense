package com.hilltopvillage.config.menu;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.ConfigPermission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;
import java.util.function.Consumer;

public class MainConfigMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;

    public MainConfigMenu(Player admin, ConfigManager configManager) {
        super(admin, ChatColor.DARK_GRAY + "村民守卫战PLUS — 配置中心", 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();

        boolean isAdmin = admin.hasPermission(ConfigPermission.ADMIN.getNode());

        setItem(4, Material.ENCHANTED_BOOK,
                ChatColor.GOLD + "" + ChatColor.BOLD + "游戏配置中心",
                ChatColor.GRAY + "管理所有游戏参数",
                "",
                ChatColor.YELLOW + "点击下方图标进入各配置模块");

        int slot = 19;
        if (isAdmin || admin.hasPermission(ConfigPermission.RULES.getNode())) {
            setClickableItem(slot, Material.CLOCK,
                    ChatColor.GREEN + "游戏规则",
                    ct -> openSubMenu(new GameRulesMenu(admin, configManager, this)),
                    ChatColor.GRAY + "最小/最大玩家数",
                    ChatColor.GRAY + "波次间隔、胜利波次",
                    ChatColor.GRAY + "怪物生成范围",
                    ChatColor.GRAY + "全局怪物上限");
        } else {
            setItem(slot, Material.BARRIER, ChatColor.RED + "游戏规则 (无权限)");
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.WAVES.getNode())) {
            setClickableItem(slot, Material.ZOMBIE_HEAD,
                    ChatColor.GREEN + "波次配置",
                    ct -> openSubMenu(new WaveSettingsMenu(admin, configManager, this)),
                    ChatColor.GRAY + "各波怪物组合",
                    ChatColor.GRAY + "怪物数量与权重",
                    ChatColor.GRAY + "默认波次模板");
        } else {
            setItem(slot, Material.BARRIER, ChatColor.RED + "波次配置 (无权限)");
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.MONSTERS.getNode())) {
            setClickableItem(slot, Material.SKELETON_SPAWN_EGG,
                    ChatColor.GREEN + "怪物设置",
                    ct -> openSubMenu(new MonsterSettingsMenu(admin, configManager, this)),
                    ChatColor.GRAY + "自爆甲虫、钩爪猎手",
                    ChatColor.GRAY + "飞行抛投者属性",
                    ChatColor.GRAY + "生命、速度、技能参数");
        } else {
            setItem(slot, Material.BARRIER, ChatColor.RED + "怪物设置 (无权限)");
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.ITEMS.getNode())) {
            setClickableItem(slot, Material.NETHERITE_AXE,
                    ChatColor.GREEN + "玩法物品设置",
                    ct -> openSubMenu(new ItemSettingsMenu(admin, configManager, this)),
                    ChatColor.GRAY + "重锤攻击参数与伤害倍率",
                    ChatColor.GRAY + "烈焰蛋爆炸与冷却设置",
                    ChatColor.GRAY + "物品材质与模型");
        } else {
            setItem(slot, Material.BARRIER, ChatColor.RED + "重锤设置 (无权限)");
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.NODES.getNode())) {
            setClickableItem(slot, Material.BEACON,
                    ChatColor.GREEN + "节点设置",
                    ct -> openSubMenu(new NodeSettingsMenu(admin, configManager, this)),
                    ChatColor.GRAY + "节点方块类型",
                    ChatColor.GRAY + "基础生命与修复",
                    ChatColor.GRAY + "Buff范围与效果等级");
        } else {
            setItem(slot, Material.BARRIER, ChatColor.RED + "节点设置 (无权限)");
        }

        int bottomRow = 45;
        if (configManager.hasUnsavedChanges()) {
            setClickableItem(bottomRow, Material.LIME_WOOL,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "保存并应用配置",
                    ct -> {
                        configManager.saveToFile();
                        admin.sendMessage(ChatColor.GREEN + "所有配置已保存到 game-settings.yml");
                        admin.sendMessage(ChatColor.GREEN + "新配置将在下一局游戏开始或 /hilltop reloadconfig 时生效。");
                        admin.playSound(admin.getLocation(),
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                        reopen();
                    },
                    ChatColor.YELLOW + "有未保存的更改！",
                    ChatColor.GRAY + "点击保存所有修改");
        } else {
            setItem(bottomRow, Material.GREEN_WOOL,
                    ChatColor.GREEN + "配置已是最新状态",
                    ChatColor.GRAY + "无需保存");
        }

        setClickableItem(bottomRow + 2, Material.ORANGE_WOOL,
                ChatColor.GOLD + "撤销所有未保存更改",
                ct -> {
                    if (configManager.hasUnsavedChanges()) {
                        configManager.revertAll();
                        admin.sendMessage(ChatColor.GOLD + "已撤销所有未保存的更改。");
                        admin.playSound(admin.getLocation(),
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                        reopen();
                    } else {
                        admin.sendMessage(ChatColor.GRAY + "没有需要撤销的更改。");
                    }
                },
                ChatColor.GRAY + "恢复到上次保存的状态");

        setClickableItem(bottomRow + 4, Material.CRAFTING_TABLE,
                ChatColor.AQUA + "加载配置文件",
                ct -> {
                    configManager.loadFromFile();
                    admin.sendMessage(ChatColor.AQUA + "已从 game-settings.yml 重新加载配置。");
                    admin.playSound(admin.getLocation(),
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    reopen();
                },
                ChatColor.GRAY + "从 game-settings.yml 重新加载",
                ChatColor.DARK_GRAY + "注意：当前未保存的更改将被覆盖");

        setItem(bottomRow + 6, Material.BOOK,
                ChatColor.YELLOW + "权限分级说明",
                ChatColor.GRAY + ConfigPermission.ADMIN.getNode() + " — 全部权限",
                ChatColor.GRAY + ConfigPermission.RULES.getNode() + " — 规则配置",
                ChatColor.GRAY + ConfigPermission.WAVES.getNode() + " — 波次配置",
                ChatColor.GRAY + ConfigPermission.MONSTERS.getNode() + " — 怪物配置",
                ChatColor.GRAY + ConfigPermission.ITEMS.getNode() + " — 物品/重锤",
                ChatColor.GRAY + ConfigPermission.NODES.getNode() + " — 节点配置");

        setCloseButton();
        fillEmpty();
    }

    private void openSubMenu(AbstractConfigMenu menu) {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(
                com.hilltopvillage.HilltopVillagePlugin.getPlugin(com.hilltopvillage.HilltopVillagePlugin.class),
                () -> {
                    Bukkit.getPluginManager().registerEvents(menu,
                            com.hilltopvillage.HilltopVillagePlugin.getPlugin(com.hilltopvillage.HilltopVillagePlugin.class));
                    menu.open();
                }, 2L);
    }
}