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

/**
 * 玩法物品设置（54格大箱子）。
 * 统一管理重锤和烈焰蛋的所有可配置属性。
 *
 * 布局：
 *   Row 1 (0-8):   标题
 *   Row 2 (9-17):  物品标签切换 (重锤 / 烈焰蛋)
 *   Row 3-5 (18-44): 当前选中物品的配置项
 *   Row 6 (45-53): 返回 / 关闭
 */
public class ItemSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    /** 当前编辑的物品类型 */
    private enum Page { HAMMER, FIREBALL }
    private Page currentPage = Page.HAMMER;

    public ItemSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent) {
        super(admin, ChatColor.DARK_GRAY + "玩法物品设置", 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.NETHERITE_AXE, ChatColor.GOLD + "" + ChatColor.BOLD + "玩法物品设置");

        // ---- 物品标签切换 (Row 2: 10-11) ----
        setClickableItem(10, Material.NETHERITE_AXE,
                (currentPage == Page.HAMMER ? ChatColor.GREEN : ChatColor.GRAY) + "神圣重锤",
                ct -> { currentPage = Page.HAMMER; reopen(); },
                ChatColor.GRAY + "右键激活猛击，高处落下触发天降正义");
        setClickableItem(11, Material.FIRE_CHARGE,
                (currentPage == Page.FIREBALL ? ChatColor.GREEN : ChatColor.GRAY) + "烈焰蛋",
                ct -> { currentPage = Page.FIREBALL; reopen(); },
                ChatColor.GRAY + "右键投掷，命中后爆炸");

        if (currentPage == Page.HAMMER) {
            buildHammerPage(cfg);
        } else {
            buildFireballPage(cfg);
        }

        setBackButton(ct -> navigateBack());
        setCloseButton();
        fillEmpty();
    }

    /* ================================================================
     *  重锤设置页
     * ================================================================ */
    private void buildHammerPage(GameConfig cfg) {
        setItem(13, Material.NETHERITE_AXE, ChatColor.GREEN + "当前编辑: 神圣重锤");

        // Row 3: 全局参数
        addIntItem(19, Material.CLOCK, "猛击超时(tick)", cfg.getHammerSmashTimeoutTicks(),
                20, 1200, 20, () -> cfg.getHammerSmashTimeoutTicks(), v -> cfg.setHammerSmashTimeoutTicks(v),
                "右键激活后允许落地的最大时间窗口（下落中自动续期）");

        addIntItem(21, Material.COBWEB, "余震时长(tick)", cfg.getHammerAftershockTicks(),
                10, 200, 10, () -> cfg.getHammerAftershockTicks(), v -> cfg.setHammerAftershockTicks(v),
                "落地后怪物减速的持续时间");

        // Row 4: 低档 + 中档
        setItem(28, Material.GREEN_WOOL, ChatColor.GREEN + "" + ChatColor.BOLD + "低档");
        addDblItem(29, Material.FEATHER, "蓄力距离(格)", cfg.getHammerLowCharge(),
                0.5, 30, 0.5, cfg::getHammerLowCharge, cfg::setHammerLowCharge,
                "下落距离达到此值触发低档");
        addDblItem(30, Material.IRON_SWORD, "伤害", cfg.getHammerLowDamage(),
                1, 500, 5, cfg::getHammerLowDamage, cfg::setHammerLowDamage,
                "低档造成的伤害值");
        addDblItem(31, Material.SLIME_BALL, "范围(格)", cfg.getHammerLowRadius(),
                1, 30, 1, cfg::getHammerLowRadius, cfg::setHammerLowRadius,
                "低档AOE作用范围");

        setItem(32, Material.YELLOW_WOOL, ChatColor.YELLOW + "" + ChatColor.BOLD + "中档");
        addDblItem(33, Material.FEATHER, "蓄力距离(格)", cfg.getHammerMediumCharge(),
                0.5, 50, 0.5, cfg::getHammerMediumCharge, cfg::setHammerMediumCharge,
                "下落距离达到此值触发中档");
        addDblItem(34, Material.IRON_SWORD, "伤害", cfg.getHammerMediumDamage(),
                1, 500, 5, cfg::getHammerMediumDamage, cfg::setHammerMediumDamage,
                "中档造成的伤害值");
        addDblItem(35, Material.SLIME_BALL, "范围(格)", cfg.getHammerMediumRadius(),
                1, 30, 1, cfg::getHammerMediumRadius, cfg::setHammerMediumRadius,
                "中档AOE作用范围");

        // Row 5: 高档 + 模型材质
        setItem(37, Material.RED_WOOL, ChatColor.RED + "" + ChatColor.BOLD + "高档");
        addDblItem(38, Material.FEATHER, "蓄力距离(格)", cfg.getHammerHighCharge(),
                0.5, 100, 0.5, cfg::getHammerHighCharge, cfg::setHammerHighCharge,
                "下落距离达到此值触发高档");
        addDblItem(39, Material.IRON_SWORD, "伤害", cfg.getHammerHighDamage(),
                1, 500, 5, cfg::getHammerHighDamage, cfg::setHammerHighDamage,
                "高档造成的伤害值");
        addDblItem(40, Material.SLIME_BALL, "范围(格)", cfg.getHammerHighRadius(),
                1, 30, 1, cfg::getHammerHighRadius, cfg::setHammerHighRadius,
                "高档AOE作用范围");

        setItem(43, Material.PAINTING, ChatColor.GOLD + "" + ChatColor.BOLD + "模型材质");
        addMaterialCycle(44, "锤子材质", cfg.getHammerMaterial(),
                cfg::setHammerMaterial, HAMMER_MAT_LIST, "点击循环切换基础材质");

        addIntItem(52, Material.ITEM_FRAME, "自定义模型数据", cfg.getHammerCustomModelData(),
                0, 999999, 1, () -> cfg.getHammerCustomModelData(), cfg::setHammerCustomModelData,
                "配合资源包使用，0=不使用");

        addStrItem(53, Material.NAME_TAG, "ItemsAdder模型ID", cfg.getHammerItemsAdderId(),
                cfg::setHammerItemsAdderId, "输入命名空间ID (如 myplugin:heavy_hammer) | 留空不使用");
    }

    /* ================================================================
     *  烈焰蛋设置页
     * ================================================================ */
    private void buildFireballPage(GameConfig cfg) {
        setItem(13, Material.FIRE_CHARGE, ChatColor.GREEN + "当前编辑: 烈焰蛋");

        // Row 3: 基础战斗参数
        addDblItem(19, Material.IRON_SWORD, "爆炸伤害", cfg.getFireballDamage(),
                1, 200, 5, cfg::getFireballDamage, cfg::setFireballDamage,
                "命中后对周围怪物造成的核心伤害");

        addDblItem(21, Material.SLIME_BALL, "爆炸半径(格)", cfg.getFireballExplosionRadius(),
                1, 20, 1, cfg::getFireballExplosionRadius, cfg::setFireballExplosionRadius,
                "爆炸伤害的作用范围");

        addDblItem(23, Material.FEATHER, "飞行速度", cfg.getFireballSpeed(),
                0.5, 5, 0.5, cfg::getFireballSpeed, cfg::setFireballSpeed,
                "投射物的飞行速度倍率");

        addIntItem(25, Material.REDSTONE, "冷却时间(tick)", cfg.getFireballCooldownTicks(),
                0, 600, 1, () -> cfg.getFireballCooldownTicks(), v -> cfg.setFireballCooldownTicks(v),
                "两次投掷之间的最小间隔，0=无冷却");

        // Row 4: 击退、射程与材质
        addDblItem(28, Material.FEATHER, "击退强度", cfg.getFireballKnockback(),
                0.5, 20, 0.5, cfg::getFireballKnockback, cfg::setFireballKnockback,
                "爆炸击退力度，对怪物和自身均生效");

        addIntItem(30, Material.ENDER_PEARL, "最大飞行(tick)", cfg.getFireballMaxTravelTicks(),
                10, 600, 10, () -> cfg.getFireballMaxTravelTicks(), v -> cfg.setFireballMaxTravelTicks(v),
                "投射物飞行最大时长，超时自动消失");

        setItem(33, Material.PAINTING, ChatColor.GOLD + "" + ChatColor.BOLD + "-- 材质 --");

        addMaterialCycle(34, "烈焰蛋材质", cfg.getFireballMaterial(),
                cfg::setFireballMaterial, FIREBALL_MAT_LIST, "点击循环切换基础材质");
    }

    /* ================================================================
     *  通用可调节项辅助方法
     * ================================================================ */

    /** 添加 double 可调节项（左键+步长 / 右键-步长 / Shift自定义） */
    private void addDblItem(int slot, Material icon, String name, double current,
                            double min, double max, double step,
                            java.util.function.DoubleSupplier getter,
                            java.util.function.DoubleConsumer setter,
                            String... extraLore) {
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(formatValue("当前值", current));
        loreLines.add("");
        loreLines.add(ChatColor.YELLOW + "左键 +" + fmtStep(step) + " | 右键 -" + fmtStep(step));
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

    /** 添加 int 可调节项 */
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

    /** 添加字符串输入项 */
    private void addStrItem(int slot, Material icon, String name, String current,
                            java.util.function.Consumer<String> setter, String prompt) {
        String display = (current != null && !current.isEmpty()) ? current : "未设置";
        Material i = (current != null && !current.isEmpty()) ? Material.NAME_TAG : Material.BARRIER;
        setClickableItem(slot, i, ChatColor.GREEN + name,
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestString(plugin, admin, prompt,
                            v -> { setter.accept(v != null ? v : ""); reopen(); },
                            this::reopen);
                },
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + display,
                ChatColor.YELLOW + "点击编辑");
    }

    /** 添加材质循环切换项 */
    private void addMaterialCycle(int slot, String name, Material current,
                                   java.util.function.Consumer<Material> setter,
                                   Material[] cycle, String... lore) {
        String curName = (current != null) ? formatMat(current) : "无";
        Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;
        java.util.List<String> loreLines = new java.util.ArrayList<>();
        loreLines.add(ChatColor.GRAY + "当前: " + ChatColor.AQUA + curName);
        loreLines.add("");
        loreLines.add(ChatColor.YELLOW + "点击循环切换");
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
        if (m == null) return "无";
        return m.name().toLowerCase().replace('_', ' ');
    }

    private String fmtStep(double s) {
        return (s == Math.floor(s)) ? String.valueOf((int) s) : String.format("%.1f", s);
    }

    /* ================================================================
     *  材质循环列表
     * ================================================================ */
    private static final Material[] HAMMER_MAT_LIST = {
        Material.NETHERITE_AXE, Material.DIAMOND_AXE, Material.IRON_AXE,
        Material.GOLDEN_AXE, Material.STONE_AXE, Material.WOODEN_AXE
    };
    private static final Material[] FIREBALL_MAT_LIST = {
        Material.FIRE_CHARGE, Material.MAGMA_CREAM, Material.BLAZE_POWDER,
        Material.GUNPOWDER, Material.FIREWORK_ROCKET
    };

    /* ================================================================
     *  导航
     * ================================================================ */
    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }
}