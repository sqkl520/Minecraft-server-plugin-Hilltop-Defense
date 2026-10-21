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

import java.util.*;

/**
 * Wave config menu (54-slot large chest).
 * Main menu lists all custom waves with pagination.
 * WaveDetailMenu upgraded to 54 slots, supports adding monster types and editing monster props.
 */
public class WaveSettingsMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;
    private final AbstractConfigMenu parent;
    private final Plugin plugin;
    private int page;
    /** Max wave entries per page (21). Pagination buttons appear when exceeding this. */
    private static final int SLOTS_PER_PAGE = 21;

    public WaveSettingsMenu(Player admin, ConfigManager configManager, AbstractConfigMenu parent, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.waves.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
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

        setItem(4, Material.ZOMBIE_HEAD, lang.getFor(admin, "config.menu.waves.header"));

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
            lore.add(lang.getFor(admin, "config.menu.waves.total-mobs") + ChatColor.AQUA + wc.getTotalMobs());
            lore.add(ChatColor.DARK_GRAY + lang.getFor(admin, "config.menu.waves.total-cap-hint"));
            lore.add("");
            for (Map.Entry<String, GameConfig.MobComposition> comp : wc.getCompositions().entrySet()) {
                lore.add(ChatColor.GRAY + "  " + getMobDisplayName(comp.getKey()) + ": "
                        + ChatColor.WHITE + comp.getValue().getCount()
                        + ChatColor.GRAY + lang.getFor(admin, "config.menu.waves.mob-weight-label") + ChatColor.WHITE + comp.getValue().getWeight() + ")");
            }
            lore.add("");
            lore.add(lang.getFor(admin, "config.menu.waves.click-edit"));
            lore.add(lang.getFor(admin, "config.menu.waves.shift-delete"));

            final int wnum = waveNum;
            setClickableItemWithData(slot, getWaveIcon(waveNum),
                    lang.getFor(admin, "config.menu.waves.wave-label", waveNum),
                    lore,
                    clickType -> {
                        if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                            if (waveNums.size() <= 1) {
                                admin.sendMessage(lang.getFor(admin, "config.menu.waves.must-keep-one"));
                            } else {
                                // P2-33：走 ConfigManager.removeWave 记账——保存时磁盘上的旧键
                                // 会被一并清掉，重启后不会复活（直接改 cfg.getWaves() 只删内存）
                                configManager.removeWave(wnum);
                                admin.sendMessage(lang.getFor(admin, "config.menu.waves.deleted", wnum));
                                reopen();
                            }
                        } else {
                            admin.closeInventory();
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                WaveDetailMenu detail = new WaveDetailMenu(admin, configManager, wnum, this, lang);
                                Bukkit.getPluginManager().registerEvents(detail, plugin);
                                detail.open();
                            }, 2L);
                        }
                    });
        }

        setClickableItem(47, Material.EMERALD,
                lang.getFor(admin, "config.menu.waves.add-wave"),
                ct -> {
                    admin.closeInventory();
                    NumericInputHandler.requestInteger(plugin, admin,
                            lang.getFor(admin, "config.menu.waves.prompt-new-wave"),
                            num -> {
                                if (cfg.getWaves().containsKey(num)) {
                                    admin.sendMessage(lang.getFor(admin, "config.menu.waves.wave-exists", num));
                                } else {
                                    cfg.getWaves().put(num, new GameConfig.WaveConfig(10, new LinkedHashMap<>()));
                                    cfg.getWaves().get(num).getCompositions()
                                            .put("explode-beetle", new GameConfig.MobComposition(4, 3));
                                    admin.sendMessage(lang.getFor(admin, "config.menu.waves.wave-created", num));
                                }
                                reopen();
                            },
                            this::reopen, lang);
                },
                lang.getFor(admin, "config.menu.waves.add-wave-desc1"),
                lang.getFor(admin, "config.menu.waves.add-wave-desc2"));

        setItem(49, Material.OAK_SIGN,
                lang.getFor(admin, "config.menu.waves.default-template"),
                lang.getFor(admin, "config.menu.waves.default-total") + ChatColor.AQUA + cfg.getDefaultWave().getTotalMobs(),
                lang.getFor(admin, "config.menu.waves.default-hint"));

        if (page > 0) {
            setClickableItem(45, Material.ARROW, lang.getFor(admin, "config.menu.waves.previous"),
                    ct -> { page--; reopen(); });
        }
        if (endIdx < waveNums.size()) {
            setClickableItem(53, Material.ARROW, lang.getFor(admin, "config.menu.waves.next"),
                    ct -> { page++; reopen(); });
        }

        // Pager menu: slot 45 (size-9) is used by the "previous page" button when page>0,
        // so the shared setBackButton() would overwrite it (back clobbers prev-page). Place
        // the back button in a free bottom-row slot (48) so pagination and back never collide.
        // Reuses the same material + i18n keys as setBackButton() for visual consistency.
        setClickableItem(48, Material.RED_STAINED_GLASS_PANE,
                lang.getFor(admin, "config.menu.common.back"),
                ct -> navigateBack(),
                lang.getFor(admin, "config.menu.common.back-lore"));
        fillEmpty();
    }

    private Material getWaveIcon(int num) {
        if (num <= 3) return Material.GREEN_WOOL;
        if (num <= 6) return Material.YELLOW_WOOL;
        if (num <= 10) return Material.ORANGE_WOOL;
        return Material.RED_WOOL;
    }

    private String getMobDisplayName(String key) {
        switch (key) {
            case "explode-beetle": return lang.getFor(admin, "config.menu.monsters.explode-beetle").replaceAll("&[0-9a-fk-or]", "");
            case "hook-claw-hunter": return lang.getFor(admin, "config.menu.monsters.hook-claw-hunter").replaceAll("&[0-9a-fk-or]", "");
            case "flying-dropper": return lang.getFor(admin, "config.menu.monsters.flying-dropper").replaceAll("&[0-9a-fk-or]", "");
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
    //  Wave Detail Menu (upgraded to 54 slots)
    // ============================================================
    public static class WaveDetailMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final int waveNum;
        private final WaveSettingsMenu parentMenu;
        private final Plugin plugin;
        private String editingMonsterKey;

        public WaveDetailMenu(Player admin, ConfigManager configManager, int waveNum, WaveSettingsMenu parentMenu, LanguageManager lang) {
            super(admin, lang.getFor(admin, "config.menu.waves.detail-title", waveNum), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
            this.configManager = configManager;
            this.waveNum = waveNum;
            this.parentMenu = parentMenu;
            this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        }

        private String mobDisplayName(String key) { return parentMenu.getMobDisplayName(key); }

        @Override
        protected void buildMenu() {
            clickActions.clear();
            inventory.clear();
            GameConfig cfg = configManager.getActiveConfig();
            GameConfig.WaveConfig wc = cfg.getWaves().get(waveNum);
            if (wc == null) {
                admin.sendMessage(lang.getFor(admin, "config.menu.waves.wave-not-found"));
                admin.closeInventory();
                return;
            }

            setItem(4, Material.ZOMBIE_HEAD,
                    lang.getFor(admin, "config.menu.waves.detail-header", waveNum));

            // ---- Total mobs (exact total cap per player, STORY-1.4) ----
            addAdjustableItem(19, Material.SPAWNER, lang.getFor(admin, "config.menu.waves.detail-total"),
                    wc.getTotalMobs(), 1, 100, 2,
                    () -> wc.getTotalMobs(), v -> wc.setTotalMobs(Math.max(1, v)),
                    lang.getFor(admin, "config.menu.waves.total-cap-hint"));

            // ---- Added monster list (Row 4-6: 27-44) ----
            int slot = 27;
            for (Map.Entry<String, GameConfig.MobComposition> entry : wc.getCompositions().entrySet()) {
                String key = entry.getKey();
                GameConfig.MobComposition comp = entry.getValue();
                GameConfig.MonsterConfig mc = cfg.getMonsters().get(key);

                List<String> lore = new ArrayList<>();
                lore.add(lang.getFor(admin, "config.menu.waves.mob-count-weight", comp.getCount(), comp.getWeight()));
                lore.add(lang.getFor(admin, "config.menu.waves.count-per-player-hint"));
                if (mc != null) {
                    lore.add(lang.getFor(admin, "config.menu.waves.mob-stats",
                            String.format("%.0f", mc.getHealth()), String.format("%.1f", mc.getBaseDamage())));
                }
                lore.add("");
                lore.add(lang.getFor(admin, "config.menu.waves.mob-left-count"));
                lore.add(lang.getFor(admin, "config.menu.waves.mob-shift-weight"));
                lore.add(lang.getFor(admin, "config.menu.waves.mob-shift-props"));

                final String fkey = key;
                setClickableItemWithData(slot, getMobIcon(key),
                        ChatColor.GREEN + mobDisplayName(key),
                        lore,
                        clickType -> {
                            if (clickType == ClickType.SHIFT_LEFT) {
                                admin.closeInventory();
                                NumericInputHandler.requestInteger(plugin, admin,
                                        lang.getFor(admin, "config.menu.waves.prompt-weight", fkey, comp.getWeight()),
                                        v -> { comp.setWeight(Math.max(1, v)); reopen(); },
                                        this::reopen, lang);
                            } else if (clickType == ClickType.SHIFT_RIGHT) {
                                admin.closeInventory();
                                editingMonsterKey = fkey;
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    MonsterPropMenu propMenu = new MonsterPropMenu(admin, configManager, fkey, this, lang);
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

            // ---- Add monster button ----
            setClickableItem(46, Material.EMERALD,
                    lang.getFor(admin, "config.menu.waves.detail-add-monster"),
                    ct -> {
                        admin.closeInventory();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            AddMonsterMenu addMenu = new AddMonsterMenu(admin, configManager, waveNum, wc, this, lang);
                            Bukkit.getPluginManager().registerEvents(addMenu, plugin);
                            addMenu.open();
                        }, 2L);
                    },
                    lang.getFor(admin, "config.menu.waves.detail-add-monster-desc1"),
                    lang.getFor(admin, "config.menu.waves.detail-add-monster-desc2"));

            // ---- Instructions ----
            setItem(47, Material.KNOWLEDGE_BOOK,
                    lang.getFor(admin, "config.menu.waves.detail-instructions"),
                    lang.getFor(admin, "config.menu.waves.detail-click-mob"),
                    lang.getFor(admin, "config.menu.waves.detail-count-semantics"),
                    lang.getFor(admin, "config.menu.waves.detail-shift-weight"),
                    lang.getFor(admin, "config.menu.waves.detail-shift-props"),
                    lang.getFor(admin, "config.menu.waves.detail-add-monster-btn"));

            setBackButton(ct -> {
                admin.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                    parentMenu.reopen();
                }, 2L);
            });
            fillEmpty();
        }

        private void addAdjustableItem(int slot, Material icon, String name, int current,
                                        int min, int max, int step,
                                        java.util.function.IntSupplier getter,
                                        java.util.function.IntConsumer setter,
                                        String... extraLore) {
            List<String> loreLines = new ArrayList<>();
            loreLines.add(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current));
            for (String l : extraLore) loreLines.add(l);
            loreLines.add("");
            loreLines.add(lang.getFor(admin, "config.menu.common.left-click") + step + " | " + lang.getFor(admin, "config.menu.common.right-click") + step);
            loreLines.add(lang.getFor(admin, "config.menu.common.shift-custom"));

            setClickableItemWithData(slot, icon, ChatColor.GREEN + name, loreLines,
                    clickType -> {
                        if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                            admin.closeInventory();
                            NumericInputHandler.requestInteger(plugin, admin,
                                    lang.getFor(admin, "config.menu.common.setting-prefix") + name + " (" + lang.getFor(admin, "config.menu.common.current") + getter.getAsInt() + ")",
                                    v -> { setter.accept(v); reopen(); },
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
    }

    // ============================================================
    //  Add Monster Menu (lists monster types not yet in wave)
    // ============================================================
    public static class AddMonsterMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final int waveNum;
        private final GameConfig.WaveConfig waveConfig;
        private final WaveDetailMenu parentMenu;
        private final Plugin plugin;

        public AddMonsterMenu(Player admin, ConfigManager configManager, int waveNum,
                               GameConfig.WaveConfig waveConfig, WaveDetailMenu parentMenu, LanguageManager lang) {
            super(admin, lang.getFor(admin, "config.menu.waves.add-monster-title", waveNum), 54,
                    HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
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
                    lang.getFor(admin, "config.menu.waves.add-monster-header"));

            int slot = 9;
            for (Map.Entry<String, GameConfig.MonsterConfig> entry : cfg.getMonsters().entrySet()) {
                String key = entry.getKey();
                GameConfig.MonsterConfig mc = entry.getValue();

                if (waveConfig.getCompositions().containsKey(key)) continue;

                List<String> lore = new ArrayList<>();
                lore.add(lang.getFor(admin, "config.menu.waves.add-monster-hp-speed",
                        String.format("%.0f", mc.getHealth()), String.format("%.2f", mc.getSpeed())));
                lore.add(lang.getFor(admin, "config.menu.waves.add-monster-damage",
                        String.format("%.1f", mc.getBaseDamage())));
                lore.add("");
                lore.add(lang.getFor(admin, "config.menu.waves.add-monster-click"));
                lore.add(lang.getFor(admin, "config.menu.waves.add-monster-default"));

                setClickableItemWithData(slot, getMobIcon(key),
                        ChatColor.GREEN + getMobDisplayNameStatic(key, lang, admin),
                        lore,
                        ct -> {
                            waveConfig.getCompositions().put(key, new GameConfig.MobComposition(2, 2));
                            admin.sendMessage(lang.getFor(admin, "config.menu.waves.add-monster-added",
                                    getMobDisplayNameStatic(key, lang, admin), waveNum));
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
                        lang.getFor(admin, "config.menu.waves.add-monster-all-added"),
                        lang.getFor(admin, "config.menu.waves.add-monster-all-added-desc"));
            }

            setBackButton(ct -> {
                admin.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.getPluginManager().registerEvents(parentMenu, plugin);
                    parentMenu.reopen();
                }, 2L);
            });
            fillEmpty();
        }

        private static String getMobDisplayNameStatic(String key, LanguageManager lang, Player admin) {
            switch (key) {
                case "explode-beetle": return lang.getFor(admin, "config.menu.monsters.explode-beetle").replaceAll("&[0-9a-fk-or]", "");
                case "hook-claw-hunter": return lang.getFor(admin, "config.menu.monsters.hook-claw-hunter").replaceAll("&[0-9a-fk-or]", "");
                case "flying-dropper": return lang.getFor(admin, "config.menu.monsters.flying-dropper").replaceAll("&[0-9a-fk-or]", "");
                default: return key;
            }
        }
    }

    // ============================================================
    //  Monster Properties Edit Menu (entered from Wave Detail)
    // ============================================================
    public static class MonsterPropMenu extends AbstractConfigMenu {
        private final ConfigManager configManager;
        private final String monsterKey;
        private final WaveDetailMenu parentMenu;
        private final Plugin plugin;

        public MonsterPropMenu(Player admin, ConfigManager configManager, String monsterKey,
                                WaveDetailMenu parentMenu, LanguageManager lang) {
            super(admin, lang.getFor(admin, "config.menu.waves.prop-title", getMobDisplayNameStatic(monsterKey, lang, admin)), 54,
                    HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
            this.configManager = configManager;
            this.monsterKey = monsterKey;
            this.parentMenu = parentMenu;
            this.plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        }

        private static String getMobDisplayNameStatic(String key, LanguageManager lang, Player admin) {
            switch (key) {
                case "explode-beetle": return lang.getFor(admin, "config.menu.monsters.explode-beetle").replaceAll("&[0-9a-fk-or]", "");
                case "hook-claw-hunter": return lang.getFor(admin, "config.menu.monsters.hook-claw-hunter").replaceAll("&[0-9a-fk-or]", "");
                case "flying-dropper": return lang.getFor(admin, "config.menu.monsters.flying-dropper").replaceAll("&[0-9a-fk-or]", "");
                default: return key;
            }
        }

        @Override
        protected void buildMenu() {
            clickActions.clear();
            inventory.clear();
            GameConfig cfg = configManager.getActiveConfig();
            GameConfig.MonsterConfig mc = cfg.getMonsters().get(monsterKey);
            if (mc == null) {
                admin.sendMessage(lang.getFor(admin, "config.menu.waves.mob-not-found"));
                admin.closeInventory();
                return;
            }

            setItem(4, getMobIcon(monsterKey),
                    lang.getFor(admin, "config.menu.waves.prop-header", getMobDisplayNameStatic(monsterKey, lang, admin)));

            // ---- Base attributes (Row 3: 18-21) ----
            addDblItem(19, Material.RED_DYE, lang.getFor(admin, "config.menu.monsters.health"), mc.getHealth(),
                    5, 500, 10, mc::getHealth, mc::setHealth);
            addDblItem(20, Material.FEATHER, lang.getFor(admin, "config.menu.monsters.speed"), mc.getSpeed(),
                    0.1, 2.0, 0.05, mc::getSpeed, mc::setSpeed);
            addDblItem(21, Material.DIAMOND_SWORD, lang.getFor(admin, "config.menu.monsters.base-damage"), mc.getBaseDamage(),
                    0.5, 100, 1.0, mc::getBaseDamage, mc::setBaseDamage,
                    lang.getFor(admin, "config.menu.monsters.base-damage-desc"));

            // ---- Model appearance (Row 4: 27-29) ----
            setItem(27, Material.PAINTING, lang.getFor(admin, "config.menu.monsters.model-appearance"));

            addStrItem(28, Material.NAME_TAG, lang.getFor(admin, "config.menu.monsters.ia-model"),
                    mc.getItemsAdderId(), mc::setItemsAdderId,
                    lang.getFor(admin, "config.menu.waves.prop-ia-prompt"));

            addIntItem(29, Material.ITEM_FRAME, lang.getFor(admin, "config.menu.monsters.custom-model-data"),
                    mc.getCustomModelData(), 0, 999999, 1,
                    () -> mc.getCustomModelData(), mc::setCustomModelData,
                    lang.getFor(admin, "config.menu.monsters.custom-model-data-desc"));

            // ---- Hand equipment (Row 4: 30-31) ----
            addMaterialCycle(30, lang.getFor(admin, "config.menu.monsters.main-hand"), mc.getMainHandItem(),
                    mc::setMainHandItem, WEAPON_LIST, lang.getFor(admin, "config.menu.waves.prop-weapon-cycle-hint"));
            addMaterialCycle(31, lang.getFor(admin, "config.menu.monsters.off-hand"), mc.getOffHandItem(),
                    mc::setOffHandItem, OFFHAND_LIST, lang.getFor(admin, "config.menu.waves.prop-offhand-cycle-hint"));

            // ---- Armor wear (Row 5: 36-40) ----
            setItem(36, Material.IRON_CHESTPLATE, lang.getFor(admin, "config.menu.monsters.armor-wear"));

            addMaterialCycle(37, lang.getFor(admin, "config.menu.monsters.helmet"), mc.getHelmet(), mc::setHelmet, ARMOR_LIST, getArmorHint());
            addMaterialCycle(38, lang.getFor(admin, "config.menu.monsters.chestplate"), mc.getChestplate(), mc::setChestplate, ARMOR_LIST, getArmorHint());
            addMaterialCycle(39, lang.getFor(admin, "config.menu.monsters.leggings"), mc.getLeggings(), mc::setLeggings, ARMOR_LIST, getArmorHint());
            addMaterialCycle(40, lang.getFor(admin, "config.menu.monsters.boots"), mc.getBoots(), mc::setBoots, ARMOR_LIST, getArmorHint());

            // ---- Unique skills (Row 6: 45-48) ----
            switch (monsterKey) {
                case "explode-beetle":
                    addDblItem(46, Material.TNT, lang.getFor(admin, "config.menu.monsters.explode-multiplier"), mc.getExplodeDamageMultiplier(),
                            0.5, 10, 0.5, mc::getExplodeDamageMultiplier, mc::setExplodeDamageMultiplier);
                    break;
                case "hook-claw-hunter":
                    addDblItem(46, Material.FISHING_ROD, lang.getFor(admin, "config.menu.waves.prop-hook-speed"), mc.getHookVelocity(),
                            0.5, 5.0, 0.1, mc::getHookVelocity, mc::setHookVelocity);
                    addIntItem(47, Material.CLOCK, lang.getFor(admin, "config.menu.waves.prop-cooldown"), mc.getCooldownTicks(),
                            20, 600, 20, () -> mc.getCooldownTicks(), mc::setCooldownTicks);
                    addIntItem(48, Material.COBWEB, lang.getFor(admin, "config.menu.waves.prop-stun"), mc.getStunDurationTicks(),
                            10, 200, 10, () -> mc.getStunDurationTicks(), mc::setStunDurationTicks);
                    break;
                case "flying-dropper":
                    addIntItem(46, Material.CLOCK, lang.getFor(admin, "config.menu.waves.prop-airdrop-cooldown"), mc.getCooldownTicks(),
                            20, 600, 20, () -> mc.getCooldownTicks(), mc::setCooldownTicks);
                    addIntItem(47, Material.ZOMBIE_SPAWN_EGG, lang.getFor(admin, "config.menu.waves.prop-airdrop-count"), mc.getAirdropMobCount(),
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
            fillEmpty();
        }

        // ---- Helpers ----

        private void addDblItem(int slot, Material icon, String name, double current,
                                 double min, double max, double step,
                                 java.util.function.DoubleSupplier getter,
                                 java.util.function.DoubleConsumer setter,
                                 String... extraLore) {
            List<String> loreLines = new ArrayList<>();
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
                        NumericInputHandler.requestString(plugin, admin, prompt + lang.getFor(admin, "config.menu.common.leave-blank-clear"),
                                v -> { setter.accept(v != null ? v : ""); reopen(); },
                                this::reopen, lang);
                    },
                    lang.getFor(admin, "config.menu.common.current") + ChatColor.AQUA + display,
                    lang.getFor(admin, "config.menu.common.click-edit"));
        }

        private void addMaterialCycle(int slot, String name, Material current,
                                       java.util.function.Consumer<Material> setter,
                                       Material[] cycle, String... lore) {
            String curName = (current != null && current != Material.AIR) ? translateMat(current) : lang.getFor(admin, "config.menu.common.none");
            Material icon = (current != null && current != Material.AIR) ? current : Material.BARRIER;
            List<String> loreLines = new ArrayList<>();
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

        private String fmtStep(double s) {
            return (s == Math.floor(s)) ? String.valueOf((int) s) : String.format("%.1f", s);
        }

        private String translateMat(Material m) {
            if (m == null) return lang.getFor(admin, "config.menu.common.none");
            String key = "config.menu.mat." + m.name().toLowerCase(Locale.ROOT).replace('_', '-');
            String translated = lang.getFor(admin, key);
            return translated.equals(key) ? m.name().toLowerCase(Locale.ROOT).replace('_', ' ') : translated;
        }

        private String getArmorHint() {
            return lang.getFor(admin, "config.menu.waves.prop-armor-cycle-hint");
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