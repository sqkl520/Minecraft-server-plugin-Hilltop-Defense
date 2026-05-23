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

import java.util.ArrayList;
import java.util.List;

/**
 * 怪物属性编辑菜单（54格大箱子）。
 * 支持三页切换（自爆甲虫 / 钩爪猎手 / 飞行抛投者），
 * 每页可编辑：基础属性、模型外观、武器装备、盔甲、特有技能参数。
 */
public class MonsterSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;

    private enum Page { EXPLODE_BEETLE, HOOK_CLAW_HUNTER, FLYING_DROPPER }

    private Page currentPage = Page.EXPLODE_BEETLE;

    public MonsterSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent) {
        super(admin, ChatColor.DARK_GRAY + "怪物设置", 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.SKELETON_SPAWN_EGG, ChatColor.RED + "" + ChatColor.BOLD + "特殊怪物属性");

        setClickableItem(9, Material.CAVE_SPIDER_SPAWN_EGG,
                ChatColor.YELLOW + "自爆甲虫",
                ct -> { currentPage = Page.EXPLODE_BEETLE; reopen(); },
                ChatColor.GRAY + "快速移动→自爆摧毁节点");
        setClickableItem(10, Material.SKELETON_SPAWN_EGG,
                ChatColor.YELLOW + "钩爪猎手",
                ct -> { currentPage = Page.HOOK_CLAW_HUNTER; reopen(); },
                ChatColor.GRAY + "远程→投射物→拖拽玩家");
        setClickableItem(11, Material.PHANTOM_SPAWN_EGG,
                ChatColor.YELLOW + "飞行抛投者",
                ct -> { currentPage = Page.FLYING_DROPPER; reopen(); },
                ChatColor.GRAY + "飞行→空投怪物群");

        String monsterKey;
        switch (currentPage) {
            case EXPLODE_BEETLE:
                monsterKey = "explode-beetle";
                setItem(13, Material.CAVE_SPIDER_SPAWN_EGG, ChatColor.GREEN + "当前编辑: 自爆甲虫");
                break;
            case HOOK_CLAW_HUNTER:
                monsterKey = "hook-claw-hunter";
                setItem(13, Material.SKELETON_SPAWN_EGG, ChatColor.GREEN + "当前编辑: 钩爪猎手");
                break;
            default:
                monsterKey = "flying-dropper";
                setItem(13, Material.PHANTOM_SPAWN_EGG, ChatColor.GREEN + "当前编辑: 飞行抛投者");
                break;
        }

        GameConfig.MonsterConfig mc = cfg.getMonsters().get(monsterKey);
        if (mc == null) return;

        // ========== 基础属性 (Row 3: slots 18-26) ==========
        addAdjustableItem(19, Material.RED_DYE, "生命值", mc.getHealth(),
                5, 500, 10, mc::getHealth, mc::setHealth);
        addAdjustableItem(20, Material.FEATHER, "移动速度", mc.getSpeed(),
                0.1, 2.0, 0.05, mc::getSpeed, mc::setSpeed);
        addAdjustableItem(21, Material.DIAMOND_SWORD, "基础伤害", mc.getBaseDamage(),
                0.5, 100, 1.0, mc::getBaseDamage, mc::setBaseDamage,
                "怪物每次普通攻击造成的伤害值");

        // ========== 模型外观 (Row 4: slots 27-35) ==========
        setItem(27, Material.PAINTING, ChatColor.GOLD + "" + ChatColor.BOLD + "模型外观",
                ChatColor.GRAY + "下方配置模型和自定义数据");

        String iaId = mc.getItemsAdderId();
        String iaDisplay = (iaId != null && !iaId.isEmpty()) ? iaId : "未设置";
        setClickableItem(28, Material.NAME_TAG, ChatColor.GREEN + "ItemsAdder模型",
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestString(plugin, admin,
                            "输入ItemsAdder命名空间ID\n(如 myplugin:boss，留空清除)",
                            v -> {
                                mc.setItemsAdderId(v != null ? v : "");
                                reopen();
                            },
                            this::reopen);
                },
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + iaDisplay,
                ChatColor.YELLOW + "Shift+点击 编辑");

        addAdjustableItem(29, Material.ITEM_FRAME, "自定义模型数据", mc.getCustomModelData(),
                0, 999999, 1,
                () -> (double) mc.getCustomModelData(),
                v -> mc.setCustomModelData((int) v),
                "配合资源包使用，0=不使用");

        // ========== 手持装备 (Row 4: slots 30-31) ==========
        Material mainHand = mc.getMainHandItem();
        String mainHandName = (mainHand != null && mainHand != Material.AIR) ? formatMaterialName(mainHand) : "空手";
        Material mainIcon = (mainHand != null && mainHand != Material.AIR) ? mainHand : Material.BARRIER;
        setClickableItem(30, mainIcon, ChatColor.GREEN + "主手武器",
                ct -> {
                    Material next = cycleMaterial(mc.getMainHandItem(), WEAPON_CYCLE);
                    mc.setMainHandItem(next);
                    reopen();
                },
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + mainHandName,
                ChatColor.YELLOW + "点击切换 | Shift+点击 自定义",
                ChatColor.DARK_GRAY + "可循环: 剑/斧/弓/三叉戟/空");

        Material offHand = mc.getOffHandItem();
        String offHandName = (offHand != null && offHand != Material.AIR) ? formatMaterialName(offHand) : "空";
        Material offIcon = (offHand != null && offHand != Material.AIR) ? offHand : Material.BARRIER;
        setClickableItem(31, offIcon, ChatColor.GREEN + "副手物品",
                ct -> {
                    if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestString(plugin, admin,
                                "输入副手物品Material名\n(如 SHIELD，留空清除)",
                                v -> {
                                    if (v == null || v.isEmpty()) {
                                        mc.setOffHandItem(null);
                                    } else {
                                        Material m = Material.getMaterial(v.toUpperCase());
                                        if (m != null) mc.setOffHandItem(m);
                                    }
                                    reopen();
                                },
                                this::reopen);
                    } else {
                        Material next = cycleMaterial(mc.getOffHandItem(), OFFHAND_CYCLE);
                        mc.setOffHandItem(next);
                        reopen();
                    }
                },
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + offHandName,
                ChatColor.YELLOW + "点击切换 | Shift+点击 自定义",
                ChatColor.DARK_GRAY + "可循环: 盾牌/图腾/空");

        // ========== 盔甲穿戴 (Row 5: slots 36-44) ==========
        setItem(36, Material.IRON_CHESTPLATE, ChatColor.GOLD + "" + ChatColor.BOLD + "盔甲穿戴",
                ChatColor.GRAY + "下方四格配置头盔/胸甲/护腿/靴子");

        addArmorSlot(37, "头盔", mc.getHelmet(), v -> mc.setHelmet(v));
        addArmorSlot(38, "胸甲", mc.getChestplate(), v -> mc.setChestplate(v));
        addArmorSlot(39, "护腿", mc.getLeggings(), v -> mc.setLeggings(v));
        addArmorSlot(40, "靴子", mc.getBoots(), v -> mc.setBoots(v));

        // ========== 特有技能参数 (Row 6: slots 45-52) ==========
        if (currentPage == Page.EXPLODE_BEETLE) {
            addAdjustableItem(46, Material.TNT, "自爆伤害倍率", mc.getExplodeDamageMultiplier(),
                    0.5, 10, 0.5, mc::getExplodeDamageMultiplier, mc::setExplodeDamageMultiplier,
                    "甲虫自爆时对节点造成的伤害倍数");

            setItem(47, Material.KNOWLEDGE_BOOK, "自爆逻辑说明",
                    ChatColor.GRAY + "1. 高速冲向最近的能量节点",
                    ChatColor.GRAY + "2. 到达节点后立即自爆",
                    ChatColor.GRAY + "3. 爆炸对节点造成 base-damage x 倍率 的伤害",
                    ChatColor.GRAY + "4. 爆炸同时伤害周围玩家");

        } else if (currentPage == Page.HOOK_CLAW_HUNTER) {
            addAdjustableItem(46, Material.FISHING_ROD, "钩爪投射速度", mc.getHookVelocity(),
                    0.5, 5.0, 0.1, mc::getHookVelocity, mc::setHookVelocity,
                    "钩爪投射物的飞行速度");
            addAdjustableItem(47, Material.CLOCK, "技能冷却 (tick)", mc.getCooldownTicks(),
                    20, 600, 20,
                    () -> (double) mc.getCooldownTicks(),
                    v -> mc.setCooldownTicks((int) v),
                    "两次钩爪攻击之间的冷却时间");
            addAdjustableItem(48, Material.COBWEB, "眩晕时长 (tick)", mc.getStunDurationTicks(),
                    10, 200, 10,
                    () -> (double) mc.getStunDurationTicks(),
                    v -> mc.setStunDurationTicks((int) v),
                    "玩家被拖拽后的眩晕(失明+减速)时长");

            setItem(49, Material.KNOWLEDGE_BOOK, "如何识别有效目标",
                    ChatColor.GRAY + "仅瞄准比自己高4格以上的玩家",
                    ChatColor.GRAY + "水平距离在8~30格内",
                    ChatColor.GRAY + "需要直接视线 (RayTrace)",
                    ChatColor.GRAY + "锁定瞄准20 tick后发射");

        } else {
            addAdjustableItem(46, Material.CLOCK, "空投冷却 (tick)", mc.getCooldownTicks(),
                    20, 600, 20,
                    () -> (double) mc.getCooldownTicks(),
                    v -> mc.setCooldownTicks((int) v),
                    "两次空投间隔");
            addAdjustableItem(47, Material.ZOMBIE_SPAWN_EGG, "每次空投数量", mc.getAirdropMobCount(),
                    1, 10, 1,
                    () -> (double) mc.getAirdropMobCount(),
                    v -> mc.setAirdropMobCount((int) v),
                    "每次空投在目标位置生成的怪物数");

            setItem(48, Material.KNOWLEDGE_BOOK, "空投逻辑说明",
                    ChatColor.GRAY + "1. 优先选择玩家周围10格随机位置",
                    ChatColor.GRAY + "2. 回退到核心周围40格",
                    ChatColor.GRAY + "3. 飞到目标上方8格处",
                    ChatColor.GRAY + "4. 分批生成怪物(每10tick一只)");
        }

        setBackButton(ct -> navigateBack());
        setCloseButton();
        fillEmpty();
    }

    /*
     * ======================== 辅助方法 ========================
     */

    /** 盔甲槽位编辑项：点击循环预设材质，Shift+点击自定义输入。 */
    private void addArmorSlot(int slot, String label, Material current,
                               java.util.function.Consumer<Material> setter) {
        String name = (current != null && current != Material.AIR) ? formatMaterialName(current) : "无";
        Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;

        setClickableItem(slot, icon, ChatColor.GREEN + label,
                ct -> {
                    if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        NumericInputHandler.requestString(plugin, admin,
                                "输入" + label + " Material名\n(如 LEATHER_HELMET，留空清除)",
                                v -> {
                                    if (v == null || v.isEmpty()) {
                                        setter.accept(null);
                                    } else {
                                        Material m = Material.getMaterial(v.toUpperCase());
                                        if (m != null) setter.accept(m);
                                    }
                                    reopen();
                                },
                                this::reopen);
                    } else {
                        Material next = cycleMaterial(current, ARMOR_CYCLE);
                        setter.accept(next);
                        reopen();
                    }
                },
                ChatColor.GRAY + "当前: " + ChatColor.AQUA + name,
                ChatColor.YELLOW + "点击切换 | Shift+点击 自定义",
                ChatColor.DARK_GRAY + getArmorTypeHint(label));
    }

    /** 可调数值编辑项（支持浮点数）。 */
    private void addAdjustableItem(int slot, Material icon, String name, double current,
                                    double min, double max, double step,
                                    java.util.function.DoubleSupplier getter,
                                    java.util.function.DoubleConsumer setter,
                                    String... extraLore) {
        List<String> loreLines = new ArrayList<>();
        loreLines.add(formatValue("当前值", current));
        loreLines.add("");
        loreLines.add(ChatColor.YELLOW + "左键 +" + formatStep(step) + " | 右键 -" + formatStep(step));
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

    /** 步长格式化：整数不显示小数位。 */
    private String formatStep(double step) {
        return (step == Math.floor(step)) ? String.valueOf((int) step) : String.format("%.1f", step);
    }

    /** 材质名格式化：用中文友好名替换原版枚举名。 */
    private String formatMaterialName(Material mat) {
        if (mat == null) return "无";
        switch (mat) {
            case WOODEN_SWORD: return "木剑";
            case STONE_SWORD: return "石剑";
            case IRON_SWORD: return "铁剑";
            case GOLDEN_SWORD: return "金剑";
            case DIAMOND_SWORD: return "钻石剑";
            case NETHERITE_SWORD: return "下界合金剑";
            case WOODEN_AXE: return "木斧";
            case STONE_AXE: return "石斧";
            case IRON_AXE: return "铁斧";
            case GOLDEN_AXE: return "金斧";
            case DIAMOND_AXE: return "钻石斧";
            case NETHERITE_AXE: return "下界合金斧";
            case BOW: return "弓";
            case CROSSBOW: return "弩";
            case TRIDENT: return "三叉戟";
            case SHIELD: return "盾牌";
            case TOTEM_OF_UNDYING: return "不死图腾";
            case LEATHER_HELMET: return "皮革头盔";
            case LEATHER_CHESTPLATE: return "皮革胸甲";
            case LEATHER_LEGGINGS: return "皮革护腿";
            case LEATHER_BOOTS: return "皮革靴子";
            case IRON_HELMET: return "铁头盔";
            case IRON_CHESTPLATE: return "铁胸甲";
            case IRON_LEGGINGS: return "铁护腿";
            case IRON_BOOTS: return "铁靴子";
            case DIAMOND_HELMET: return "钻石头盔";
            case DIAMOND_CHESTPLATE: return "钻石胸甲";
            case DIAMOND_LEGGINGS: return "钻石护腿";
            case DIAMOND_BOOTS: return "钻石靴子";
            case NETHERITE_HELMET: return "下界合金头盔";
            case NETHERITE_CHESTPLATE: return "下界合金胸甲";
            case NETHERITE_LEGGINGS: return "下界合金护腿";
            case NETHERITE_BOOTS: return "下界合金靴子";
            case GOLDEN_HELMET: return "金头盔";
            case GOLDEN_CHESTPLATE: return "金胸甲";
            case GOLDEN_LEGGINGS: return "金护腿";
            case GOLDEN_BOOTS: return "金靴子";
            case CHAINMAIL_HELMET: return "锁链头盔";
            case CHAINMAIL_CHESTPLATE: return "锁链胸甲";
            case CHAINMAIL_LEGGINGS: return "锁链护腿";
            case CHAINMAIL_BOOTS: return "锁链靴子";
            default: return mat.name().toLowerCase().replace('_', ' ');
        }
    }

    /** 在预设列表中循环下一个材质。null 表示清除。 */
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

    /** 获取盔甲类型的提示文字。 */
    private String getArmorTypeHint(String label) {
        switch (label) {
            case "头盔": return "可循环: 皮革/锁链/铁/钻石/下界合金/金 头盔";
            case "胸甲": return "可循环: 皮革/锁链/铁/钻石/下界合金/金 胸甲";
            case "护腿": return "可循环: 皮革/锁链/铁/钻石/下界合金/金 护腿";
            case "靴子": return "可循环: 皮革/锁链/铁/钻石/下界合金/金 靴子";
            default: return "点击循环材质";
        }
    }

    // ---- 材质循环列表 ----
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
