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
                () -> cfg.getVictoryWaves(), v -> cfg.setVictoryWaves(Math.min(100, Math.max(1, v))),
                lang.getFor(admin, "config.menu.rules.victory-waves-desc"));

        addAdjustableItem(16, Material.REDSTONE, lang.getFor(admin, "config.menu.rules.wave-interval"),
                cfg.getWaveIntervalSeconds(), 5, 300, 5,
                () -> cfg.getWaveIntervalSeconds(), v -> cfg.setWaveIntervalSeconds(Math.min(300, Math.max(5, v))),
                lang.getFor(admin, "config.menu.rules.wave-interval-desc"));

        addAdjustableItem(20, Material.COMPASS, lang.getFor(admin, "config.menu.rules.spawn-min-radius"),
                cfg.getSpawnRadiusMin(), 5, 100, 2,
                () -> cfg.getSpawnRadiusMin(), v -> cfg.setSpawnRadiusMin(Math.min(100, Math.max(5, v))),
                lang.getFor(admin, "config.menu.rules.spawn-min-radius-desc"));

        // slice-08-logic-002：本槽位的下界不是固定 10，而是「声明下界」与「跨字段约束
        // spawnRadiusMax >= spawnRadiusMin + 5」中的较大者。此前右键判据只认 10，而 setter 里
        // 的 Math.max(min+5, v) 会把 35 抬成 105 —— 减号按钮反而把值往上跳。
        // 现在把有效下界显式交给 helper：右键/聊天都先撞到它，setter 收到的一定是区间内的值，
        // 里面的 Math.max 退化为兜底而不再是「抬值」路径。
        addAdjustableItem(22, Material.COMPASS, lang.getFor(admin, "config.menu.rules.spawn-max-radius"),
                cfg.getSpawnRadiusMax(), 10, 200, 5,
                () -> cfg.getSpawnRadiusMax(),
                v -> cfg.setSpawnRadiusMax(Math.min(200, Math.max(cfg.getSpawnRadiusMin() + 5, v))),
                () -> cfg.getSpawnRadiusMin() + 5,
                lang.getFor(admin, "config.menu.rules.spawn-max-radius-desc"));

        addAdjustableItem(24, Material.SPAWNER, lang.getFor(admin, "config.menu.rules.global-mob-cap"),
                cfg.getGlobalMobCap(), 10, 500, 5,
                () -> cfg.getGlobalMobCap(), v -> cfg.setGlobalMobCap(Math.min(500, Math.max(10, v))),
                lang.getFor(admin, "config.menu.rules.global-mob-cap-desc"));

        addAdjustableItem(26, Material.SPAWNER, lang.getFor(admin, "config.menu.rules.mob-cap-per-player"),
                cfg.getMobCapPerPlayer(), 3, 100, 1,
                () -> cfg.getMobCapPerPlayer(), v -> cfg.setMobCapPerPlayer(Math.min(100, Math.max(3, v))),
                lang.getFor(admin, "config.menu.rules.mob-cap-per-player-desc"));

        setBackButton(ct -> navigateBack());
        fillEmpty();
    }

    private void addAdjustableItem(int slot, Material icon, String name, int current,
                                    int min, int max, int step,
                                    java.util.function.IntSupplier getter,
                                    java.util.function.IntConsumer setter,
                                    String... extraLore) {
        addAdjustableItem(slot, icon, name, current, min, max, step, getter, setter,
                () -> min, extraLore);
    }

    /**
     * 带「动态下界」的版本：下界取自 {@code effectiveMin}，用于跨字段约束的槽位
     * （如 {@code spawn-radius-max} 必须恒 ≥ {@code spawn-radius-min + 5}）。
     *
     * <p>把有效下界收在这里，右键、左键、SHIFT 聊天三条路径就都撞同一道闸；此前右键只比
     * 字面 {@code min}，setter 里的 {@code Math.max(...)} 会把减号变成加号
     * （slice-08-logic-002）。</p>
     */
    private void addAdjustableItem(int slot, Material icon, String name, int current,
                                    int min, int max, int step,
                                    java.util.function.IntSupplier getter,
                                    java.util.function.IntConsumer setter,
                                    java.util.function.IntSupplier effectiveMin,
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
                    int floor = Math.max(min, effectiveMin.getAsInt());
                    if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                        admin.closeInventory();
                        // 区间随 helper 一起传给 handler：聊天输入走同一份 clamp（slice-08-logic-001）
                        NumericInputHandler.requestInteger(plugin, admin,
                                lang.getFor(admin, "config.menu.common.setting-prefix") + name + " (" + lang.getFor(admin, "config.menu.common.current") + getter.getAsInt() + ")",
                                floor, max,
                                v -> {
                                    setter.accept(v);
                                    reopen();
                                },
                                this::reopen, lang);
                    } else if (clickType == ClickType.RIGHT) {
                        int val = getter.getAsInt() - step;
                        if (val >= floor) setter.accept(val);
                        reopen();
                    } else if (clickType == ClickType.LEFT) {
                        // slice-08-logic-009：本分支原来是 else 兜底，会接管 MIDDLE / DROP /
                        // NUMBER_KEY / SWAP_OFFHAND / DOUBLE_CLICK 等所有其它 ClickType ——
                        // 丢出键或热键会静默改配置。收窄为显式 LEFT 白名单。
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