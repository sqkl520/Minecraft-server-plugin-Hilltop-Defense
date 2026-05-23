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

import java.util.*;

/**
 * 波次配置菜单（54格大箱子）。
 * 主菜单列出所有自定义波次，支持翻页。
 * WaveDetailMenu 升级为 54 格，支持添加怪物种类及编辑怪物属性。
 */
public class WaveSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;
    private int page;
    /** 每页最多显示的波次条目数 (21)。超出此阈值自动显示翻页按钮。 */
    private static final int SLOTS_PER_PAGE = 21;

    public WaveSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent) {
        super(admin, ChatColor.DARK_GRAY + "波次配置", 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
        this.configManager = configManager;
        this.parent = parent;
        this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        this.page = 0;
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();
        GameConfig cfg = configManager.getActiveConfig();

        setItem(4, Material.ZOMBIE_HEAD, ChatColor.RED + "" + ChatColor.BOLD + "怪物波次编辑器");

        List<Integer> waveNums = new ArrayList<>(cfg.getWaves().keySet());
        Collections.sort(waveNums);

        int totalPages = Math.max(1, (waveNums.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
        int startIdx = page * SLOTS_PER_PAGE;
        int endIdx = Math.min(startIdx + SLOTS_PER_PAGE, waveNums.size());

        for (int i = startIdx; i < endIdx; i++) {
            int waveNum = waveNums.get(i);
            GameConfig.WaveConfig wc = cfg.getWaves().get(waveNum);
            if (wc == null) continue;

            int slot = 9 + (i - startIdx);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "总怪物数: " + ChatColor.AQUA + wc.getTotalMobs());
            lore.add("");
            for (Map.Entry<String, GameConfig.MobComposition> comp : wc.getCompositions().entrySet()) {
                lore.add(ChatColor.GRAY + "  " + getMobDisplayName(comp.getKey()) + ": "
                        + ChatColor.WHITE + comp.getValue().getCount()
                        + ChatColor.GRAY + " (权重:" + ChatColor.WHITE + comp.getValue().getWeight() + ")");
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "点击编辑该波次");
            lore.add(ChatColor.AQUA + "Shift+点击删除");

            final int wnum = waveNum;
            setClickableItemWithData(slot, getWaveIcon(waveNum),
                    ChatColor.GREEN + "第 " + waveNum + " 波",
                    lore,
                    clickType -> {
                        if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                            if (waveNums.size() <= 1) {
                                admin.sendMessage(ChatColor.RED + "至少保留一个自定义波次。");
                            } else {
                                cfg.getWaves().remove(wnum);
                                admin.sendMessage(ChatColor.YELLOW + "已删除第 " + wnum + " 波的配置。");
                                reopen();
                            }
                        } else {
                            admin.closeInventory();
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                WaveDetailMenu detail = new WaveDetailMenu(admin, configManager, wnum, this);
                                Bukkit.getPluginManager().registerEvents(detail, plugin);
                                detail.open();
                            }, 2L);
                        }
                    });
        }

        setClickableItem(47, Material.EMERALD,
                ChatColor.GREEN + "添加新波次",
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestInteger(plugin, admin,
                            "输入新波次编号 (如 3, 7, 15)",
                            num -> {
                                if (cfg.getWaves().containsKey(num)) {
                                    admin.sendMessage(ChatColor.RED + "波次 " + num + " 已存在。");
                                } else {
                                    cfg.getWaves().put(num, new GameConfig.WaveConfig(10, new LinkedHashMap<>()));
                                    cfg.getWaves().get(num).getCompositions()
                                            .put("explode-beetle", new GameConfig.MobComposition(4, 3));
                                    admin.sendMessage(ChatColor.GREEN + "已创建第 " + num + " 波配置 (默认模板)。");
                                }
                                reopen();
                            },
                            this::reopen);
                },
                ChatColor.GRAY + "创建自定义波次",
                ChatColor.DARK_GRAY + "优先于默认波次模板");

        setItem(49, Material.OAK_SIGN,
                ChatColor.GRAY + "默认波次模板",
                ChatColor.GRAY + "总怪物数: " + ChatColor.AQUA + cfg.getDefaultWave().getTotalMobs(),
                ChatColor.GRAY + "未定义波次将使用此模板");

        if (page > 0) {
            setClickableItem(45, Material.ARROW, ChatColor.WHITE + "上一页",
                    ct -> { page--; reopen(); });
        }
        if (endIdx < waveNums.size()) {
            setClickableItem(53, Material.ARROW, ChatColor.WHITE + "下一页",
                    ct -> { page++; reopen(); });
        }

        setBackButton(ct -> navigateBack());
        setCloseButton();
        fillEmpty();
    }

    private Material getWaveIcon(int num) {
        if (num <= 3) return Material.GREEN_WOOL;
        if (num <= 6) return Material.YELLOW_WOOL;
        if (num <= 10) return Material.ORANGE_WOOL;
        return Material.RED_WOOL;
    }

    static String getMobDisplayName(String key) {
        switch (key) {
            case "explode-beetle": return "自爆甲虫";
            case "hook-claw-hunter": return "钩爪猎手";
            case "flying-dropper": return "飞行抛投者";
            default: return key;
        }
    }

    static Material getMobIcon(String key) {
        switch (key) {
            case "explode-beetle": return Material.CAVE_SPIDER_SPAWN_EGG;
            case "hook-claw-hunter": return Material.SKELETON_SPAWN_EGG;
            case "flying-dropper": return Material.PHANTOM_SPAWN_EGG;
            default: return Material.ZOMBIE_SPAWN_EGG;
        }
    }

    private void navigateBack() {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getPluginManager().registerEvents(parent, plugin);
            parent.reopen();
        }, 2L);
    }

    // ============================================================
    //  波次详情菜单（升级为 54 格）
    //  布局：
    //    Row 1 (0-8):   标题 + 装饰
    //    Row 2 (9-17):  怪物种类标签 (最多8格: 9-16)
    //    Row 3 (18-26): 总怪物数 + 基础编辑
    //    Row 4-6 (27-44): 已添加怪物的详细属性和操作
    //    Row 7 (45-53): 添加怪物 / 返回 / 关闭
    // ============================================================
    public static class WaveDetailMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final int waveNum;
        private final WaveSettingsMenu parentMenu;
        private final Plugin plugin;
        /** 当前编辑的怪物 key（用于属性编辑弹窗），null 表示未选中 */
        private String editingMonsterKey;

        public WaveDetailMenu(Player admin, ConfigManager configManager, int waveNum, WaveSettingsMenu parentMenu) {
            super(admin, ChatColor.DARK_GRAY + "编辑第 " + waveNum + " 波", 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
            this.configManager = configManager;
            this.waveNum = waveNum;
            this.parentMenu = parentMenu;
            this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        }

        @Override
        protected void buildMenu() {
            clickActions.clear();
            inventory.clear();
            GameConfig cfg = configManager.getActiveConfig();
            GameConfig.WaveConfig wc = cfg.getWaves().get(waveNum);
            if (wc == null) {
                admin.sendMessage(ChatColor.RED + "波次配置不存在！");
                admin.closeInventory();
                return;
            }

            setItem(4, Material.ZOMBIE_HEAD,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "第 " + waveNum + " 波详细配置");

            // ---- 总怪物数 ----
            addAdjustableItem(19, Material.SPAWNER, "总怪物数",
                    wc.getTotalMobs(), 1, 100, 2,
                    () -> wc.getTotalMobs(), v -> wc.setTotalMobs(Math.max(1, v)));

            // ---- 已添加的怪物列表 (Row 4-6: 27-44) ----
            int slot = 27;
            for (Map.Entry<String, GameConfig.MobComposition> entry : wc.getCompositions().entrySet()) {
                String key = entry.getKey();
                GameConfig.MobComposition comp = entry.getValue();
                GameConfig.MonsterConfig mc = cfg.getMonsters().get(key);

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "数量: " + ChatColor.AQUA + comp.getCount()
                        + ChatColor.GRAY + " | 权重: " + ChatColor.AQUA + comp.getWeight());
                if (mc != null) {
                    lore.add(ChatColor.GRAY + "生命: " + ChatColor.WHITE + String.format("%.0f", mc.getHealth())
                            + ChatColor.GRAY + " | 伤害: " + ChatColor.WHITE + String.format("%.1f", mc.getBaseDamage()));
                }
                lore.add("");
                lore.add(ChatColor.YELLOW + "左键 +数量 | 右键 -数量");
                lore.add(ChatColor.AQUA + "Shift+左键 编辑权重");
                lore.add(ChatColor.GOLD + "Shift+右键 编辑怪物属性");

                final String fkey = key;
                setClickableItemWithData(slot, getMobIcon(key),
                        ChatColor.GREEN + getMobDisplayName(key),
                        lore,
                        clickType -> {
                            if (clickType == ClickType.SHIFT_LEFT) {
                                admin.closeInventory();
                                NumericInputHandler.requestInteger(plugin, admin,
                                        fkey + "权重 (当前: " + comp.getWeight() + ")",
                                        v -> { comp.setWeight(Math.max(1, v)); reopen(); },
                                        this::reopen);
                            } else if (clickType == ClickType.SHIFT_RIGHT) {
                                admin.closeInventory();
                                editingMonsterKey = fkey;
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    MonsterPropMenu propMenu = new MonsterPropMenu(admin, configManager, fkey, this);
                                    Bukkit.getPluginManager().registerEvents(propMenu, plugin);
                                    propMenu.open();
                                }, 2L);
                            } else if (clickType == ClickType.RIGHT) {
                                comp.setCount(Math.max(0, comp.getCount() - 1));
                                reopen();
                            } else {
                                comp.setCount(comp.getCount() + 1);
                                reopen();
                            }
                        });
                slot++;
            }

            // ---- 添加怪物按钮 (第 46-47 格) ----
            setClickableItem(46, Material.EMERALD,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "添加怪物",
                    ct -> {
                        admin.closeInventory();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            AddMonsterMenu addMenu = new AddMonsterMenu(admin, configManager, waveNum, wc, this);
                            Bukkit.getPluginManager().registerEvents(addMenu, plugin);
                            addMenu.open();
                        }, 2L);
                    },
                    ChatColor.GRAY + "向本波次添加新的怪物种类",
                    ChatColor.DARK_GRAY + "可从已定义的怪物类型中选择");

            // ---- 删除按钮提示 ----
            setItem(47, Material.KNOWLEDGE_BOOK,
                    ChatColor.GRAY + "操作说明",
                    ChatColor.GRAY + "点击怪物: 调整数量",
                    ChatColor.GRAY + "Shift+左键: 编辑权重",
                    ChatColor.GOLD + "Shift+右键: 编辑属性",
                    ChatColor.GREEN + "下方按钮: 添加新怪物");

            setBackButton(ct -> {
                admin.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                    parentMenu.reopen();
                }, 2L);
            });
            setCloseButton();
            fillEmpty();
        }

        private void addAdjustableItem(int slot, Material icon, String name, int current,
                                        int min, int max, int step,
                                        java.util.function.IntSupplier getter,
                                        java.util.function.IntConsumer setter) {
            List<String> loreLines = new ArrayList<>();
            loreLines.add(formatValue("当前值", current));
            loreLines.add("");
            loreLines.add(ChatColor.YELLOW + "左键 +" + step + " | 右键 -" + step);
            loreLines.add(ChatColor.AQUA + "Shift+点击 自定义");

            setClickableItemWithData(slot, icon, ChatColor.GREEN + name, loreLines,
                    clickType -> {
                        if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                            admin.closeInventory();
                            NumericInputHandler.requestInteger(plugin, admin,
                                    "设置" + name + " (当前: " + getter.getAsInt() + ")",
                                    v -> { setter.accept(v); reopen(); },
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
    }

    // ============================================================
    //  添加怪物菜单（列出未在波次中的怪物类型）
    // ============================================================
    public static class AddMonsterMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final int waveNum;
        private final GameConfig.WaveConfig waveConfig;
        private final WaveDetailMenu parentMenu;
        private final Plugin plugin;

        public AddMonsterMenu(Player admin, ConfigManager configManager, int waveNum,
                               GameConfig.WaveConfig waveConfig, WaveDetailMenu parentMenu) {
            super(admin, ChatColor.DARK_GRAY + "添加怪物到第" + waveNum + "波", 27,
                    HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
            this.configManager = configManager;
            this.waveNum = waveNum;
            this.waveConfig = waveConfig;
            this.parentMenu = parentMenu;
            this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        }

        @Override
        protected void buildMenu() {
            clickActions.clear();
            inventory.clear();
            GameConfig cfg = configManager.getActiveConfig();

            setItem(4, Material.ZOMBIE_SPAWN_EGG,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "选择要添加的怪物种类");

            int slot = 9;
            for (Map.Entry<String, GameConfig.MonsterConfig> entry : cfg.getMonsters().entrySet()) {
                String key = entry.getKey();
                GameConfig.MonsterConfig mc = entry.getValue();

                if (waveConfig.getCompositions().containsKey(key)) continue; // 已存在则跳过

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "生命: " + ChatColor.WHITE + String.format("%.0f", mc.getHealth())
                        + ChatColor.GRAY + " | 速度: " + ChatColor.WHITE + String.format("%.2f", mc.getSpeed()));
                lore.add(ChatColor.GRAY + "伤害: " + ChatColor.WHITE + String.format("%.1f", mc.getBaseDamage()));
                lore.add("");
                lore.add(ChatColor.YELLOW + "点击添加到本波次");
                lore.add(ChatColor.GRAY + "默认数量=2, 权重=2");

                setClickableItemWithData(slot, getMobIcon(key),
                        ChatColor.GREEN + getMobDisplayName(key),
                        lore,
                        ct -> {
                            waveConfig.getCompositions().put(key, new GameConfig.MobComposition(2, 2));
                            admin.sendMessage(ChatColor.GREEN + "已将 " + getMobDisplayName(key) + " 添加到第" + waveNum + "波");
                            admin.closeInventory();
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                                parentMenu.reopen();
                            }, 2L);
                        });
                slot++;
            }

            if (slot == 9) {
                setItem(13, Material.BARRIER,
                        ChatColor.RED + "所有怪物类型已添加",
                        ChatColor.GRAY + "本波次已包含全部怪物种类");
            }

            setBackButton(ct -> {
                admin.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                    parentMenu.reopen();
                }, 2L);
            });
            setCloseButton();
            fillEmpty();
        }
    }

    // ============================================================
    //  怪物属性编辑菜单（从波次详情进入，编辑单个怪物类型属性）
    // ============================================================
    public static class MonsterPropMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final String monsterKey;
        private final WaveDetailMenu parentMenu;
        private final Plugin plugin;

        public MonsterPropMenu(Player admin, ConfigManager configManager, String monsterKey,
                                WaveDetailMenu parentMenu) {
            super(admin, ChatColor.DARK_GRAY + "编辑 " + getMobDisplayName(monsterKey) + " 属性", 54,
                    HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class));
            this.configManager = configManager;
            this.monsterKey = monsterKey;
            this.parentMenu = parentMenu;
            this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        }

        @Override
        protected void buildMenu() {
            clickActions.clear();
            inventory.clear();
            GameConfig cfg = configManager.getActiveConfig();
            GameConfig.MonsterConfig mc = cfg.getMonsters().get(monsterKey);
            if (mc == null) {
                admin.sendMessage(ChatColor.RED + "怪物配置不存在！");
                admin.closeInventory();
                return;
            }

            setItem(4, getMobIcon(monsterKey),
                    ChatColor.GREEN + "" + ChatColor.BOLD + "编辑: " + getMobDisplayName(monsterKey));

            // ---- 基础属性 (Row 3: 18-21) ----
            addDblItem(19, Material.RED_DYE, "生命值", mc.getHealth(),
                    5, 500, 10, mc::getHealth, mc::setHealth);
            addDblItem(20, Material.FEATHER, "移动速度", mc.getSpeed(),
                    0.1, 2.0, 0.05, mc::getSpeed, mc::setSpeed);
            addDblItem(21, Material.DIAMOND_SWORD, "基础伤害", mc.getBaseDamage(),
                    0.5, 100, 1.0, mc::getBaseDamage, mc::setBaseDamage,
                    "怪物每次普通攻击造成的伤害");

            // ---- 模型外观 (Row 4: 27-29) ----
            setItem(27, Material.PAINTING, ChatColor.GOLD + "" + ChatColor.BOLD + "模型外观");

            addStrItem(28, Material.NAME_TAG, "ItemsAdder模型",
                    mc.getItemsAdderId(), mc::setItemsAdderId,
                    "输入命名空间ID (如 myplugin:boss)");

            addIntItem(29, Material.ITEM_FRAME, "自定义模型数据",
                    mc.getCustomModelData(), 0, 999999, 1,
                    () -> mc.getCustomModelData(), mc::setCustomModelData,
                    "配合资源包使用，0=不使用");

            // ---- 手持装备 (Row 4: 30-31) ----
            addMaterialCycle(30, "主手武器", mc.getMainHandItem(),
                    mc::setMainHandItem, WEAPON_LIST, "点击循环切换武器");
            addMaterialCycle(31, "副手物品", mc.getOffHandItem(),
                    mc::setOffHandItem, OFFHAND_LIST, "点击循环切换");

            // ---- 盔甲穿戴 (Row 5: 36-40) ----
            setItem(36, Material.IRON_CHESTPLATE, ChatColor.GOLD + "" + ChatColor.BOLD + "盔甲");

            addMaterialCycle(37, "头盔", mc.getHelmet(), mc::setHelmet, ARMOR_LIST, getArmorHint("头盔"));
            addMaterialCycle(38, "胸甲", mc.getChestplate(), mc::setChestplate, ARMOR_LIST, getArmorHint("胸甲"));
            addMaterialCycle(39, "护腿", mc.getLeggings(), mc::setLeggings, ARMOR_LIST, getArmorHint("护腿"));
            addMaterialCycle(40, "靴子", mc.getBoots(), mc::setBoots, ARMOR_LIST, getArmorHint("靴子"));

            // ---- 特有技能 (Row 6: 45-48) ----
            switch (monsterKey) {
                case "explode-beetle":
                    addDblItem(46, Material.TNT, "自爆伤害倍率", mc.getExplodeDamageMultiplier(),
                            0.5, 10, 0.5, mc::getExplodeDamageMultiplier, mc::setExplodeDamageMultiplier);
                    break;
                case "hook-claw-hunter":
                    addDblItem(46, Material.FISHING_ROD, "钩爪速度", mc.getHookVelocity(),
                            0.5, 5.0, 0.1, mc::getHookVelocity, mc::setHookVelocity);
                    addIntItem(47, Material.CLOCK, "技能冷却(tick)", mc.getCooldownTicks(),
                            20, 600, 20, () -> mc.getCooldownTicks(), mc::setCooldownTicks);
                    addIntItem(48, Material.COBWEB, "眩晕时长(tick)", mc.getStunDurationTicks(),
                            10, 200, 10, () -> mc.getStunDurationTicks(), mc::setStunDurationTicks);
                    break;
                case "flying-dropper":
                    addIntItem(46, Material.CLOCK, "空投冷却(tick)", mc.getCooldownTicks(),
                            20, 600, 20, () -> mc.getCooldownTicks(), mc::setCooldownTicks);
                    addIntItem(47, Material.ZOMBIE_SPAWN_EGG, "空投数量", mc.getAirdropMobCount(),
                            1, 10, 1, () -> mc.getAirdropMobCount(), mc::setAirdropMobCount);
                    break;
            }

            setBackButton(ct -> {
                admin.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                    parentMenu.reopen();
                }, 2L);
            });
            setCloseButton();
            fillEmpty();
        }

        // ---- 辅助方法 ----

        private void addDblItem(int slot, Material icon, String name, double current,
                                 double min, double max, double step,
                                 java.util.function.DoubleSupplier getter,
                                 java.util.function.DoubleConsumer setter,
                                 String... extraLore) {
            List<String> loreLines = new ArrayList<>();
            loreLines.add(formatValue("当前值", current));
            loreLines.add("");
            loreLines.add(ChatColor.YELLOW + "左键 +" + fmtStep(step) + " | 右键 -" + fmtStep(step));
            loreLines.add(ChatColor.AQUA + "Shift+点击 自定义");
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
            String display = (current != null && !current.isEmpty()) ? current : "未设置";
            Material i = (current != null && !current.isEmpty()) ? Material.NAME_TAG : Material.BARRIER;
            setClickableItem(slot, i, ChatColor.GREEN + name,
                    ct -> {
                        admin.closeInventory();
                        NumericInputHandler.requestString(plugin, admin, prompt + " | 留空清除",
                                v -> { setter.accept(v != null ? v : ""); reopen(); },
                                this::reopen);
                    },
                    ChatColor.GRAY + "当前: " + ChatColor.AQUA + display,
                    ChatColor.YELLOW + "点击编辑");
        }

        private void addMaterialCycle(int slot, String name, Material current,
                                       java.util.function.Consumer<Material> setter,
                                       Material[] cycle, String... lore) {
            String curName = (current != null && current != Material.AIR) ? formatMat(current) : "无";
            Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;
            List<String> loreLines = new ArrayList<>();
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

        private String fmtStep(double s) {
            return (s == Math.floor(s)) ? String.valueOf((int) s) : String.format("%.1f", s);
        }

        private String formatMat(Material m) {
            if (m == null) return "无";
            return m.name().toLowerCase().replace('_', ' ');
        }

        private String getArmorHint(String label) {
            return "可循环: 皮革/铁/钻石/下界合金/金/空";
        }

        private static final Material[] WEAPON_LIST = {
            Material.DIAMOND_AXE, Material.DIAMOND_SWORD, Material.BOW,
            Material.TRIDENT, Material.NETHERITE_AXE, Material.NETHERITE_SWORD,
            Material.IRON_AXE, Material.IRON_SWORD, Material.AIR
        };
        private static final Material[] OFFHAND_LIST = {
            Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.AIR
        };
        private static final Material[] ARMOR_LIST = {
            Material.LEATHER_HELMET, Material.IRON_HELMET,
            Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
            Material.GOLDEN_HELMET, Material.AIR
        };
    }
}