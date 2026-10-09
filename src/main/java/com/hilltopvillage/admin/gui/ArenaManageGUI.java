package com.hilltopvillage.admin.gui;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.ArenaAdminService;
import com.hilltopvillage.admin.ArenaAdminService.ArenaView;
import com.hilltopvillage.admin.ArenaAdminService.ParamUpdate;
import com.hilltopvillage.admin.ArenaResetService;
import com.hilltopvillage.admin.ArenaResetService.Gate;
import com.hilltopvillage.admin.ArenaResetService.Outcome;
import com.hilltopvillage.admin.ArenaResetService.ReinstateSkip;
import com.hilltopvillage.admin.LocationParticlePreview;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.config.menu.NumericInputHandler;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 竞技场管理 GUI：一个 54 格菜单内以 {@link View} 枚举切换 7 个视图，用后退栈维护层级。
 *
 * <h2>为什么是单类 + View 枚举</h2>
 * <p>7 个视图共享同一份状态（{@link #selectedArenaId} 的当前竞技场、页码、以及「谁打开了谁」的层级关系），
 * 拆成多个类就要把这份状态在构造参数与回调里传来传去，还会各自持有一份 {@code clickActions}。
 * 单类实现让「后退」只是一次 {@code history.pop()}。</p>
 *
 * <h2>数据来源</h2>
 * <ul>
 *   <li>{@link ArenaAdminService} —— 只读视图（含未启用 / 未部署 / 世界缺失的竞技场）与单条删除；</li>
 *   <li>{@link ArenaResetService} —— 「恢复初始状态」的准入判定与执行；</li>
 *   <li>{@link LocationParticlePreview} —— 列表项的粒子预览；</li>
 *   <li>{@link MultiArenaManager} —— 启用 / 禁用开关。</li>
 * </ul>
 * 三者都通过 {@link #hilltop} 每次现取，不缓存字段：管理 GUI 是长生命周期对象，
 * 缓存服务引用会在插件重载后指向旧实例。</p>
 *
 * <h2>底行布局（45-53）与「不要用 setBackButton」</h2>
 * <p>父类 {@code AbstractConfigMenu#setBackButton} 固定占用 {@code size - 9} = 45，
 * 与分页控件冲突（见 {@link AbstractAdminMenu} 的类 javadoc）。本类一律用
 * {@code setClickableItem(SLOT_BACK, ...)} 自己放返回按钮。</p>
 *
 * <h2>标题</h2>
 * <p>{@code AbstractConfigMenu} 在构造时就把标题写进了 {@code Bukkit.createInventory}，
 * 之后无法再改（{@code title} / {@code inventory} 均为 final），因此本 GUI 的窗口标题恒为
 * {@code manage.title.list}。各视图的 {@code manage.title.*} 改用于底行中部的信息物品名，
 * 让管理员在窗口内仍能看到「当前在哪个视图、看的是哪个竞技场」。</p>
 *
 * <h2>线程</h2>
 * <p>纯主线程：全部操作发生在 Bukkit 事件线程与 GUI 交互流程内，
 * {@link ArenaResetService#reset} 的回调也保证在主线程触发（但可能是同步触发，见
 * {@link #handleResetOutcome}）。</p>
 */
public class ArenaManageGUI extends AbstractAdminMenu {

    /** 菜单尺寸：45 格内容 + 9 格自定义底行 */
    private static final int SIZE = 54;
    /** 每页条目数（= 内容区 0-44 的格数） */
    private static final int PAGE_SIZE = 45;

    /* 底行槽位：45-53 全部由本类自行分配，绝不调用 setBackButton */
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;

    /** 「空列表 / 竞技场已不存在」的提示物品槽位 */
    private static final int SLOT_EMPTY = 22;

    /** 粒子预览持续时间（tick）：600 = 30 秒 */
    private static final long PREVIEW_TICKS = 600L;

    /**
     * {@code reinstateSkipped == MISSING_CORE} 时的降级文案。
     *
     * <p>重置会清空 core、重新启用必然因缺 core 被拒，因此「重置成功但需重新部署」是
     * <b>最常见</b>的结果。它有自己的键 {@code manage.reset.done-missing-core}，
     * 与确认页的清单项 {@code manage.reset.warn-core}（「· 核心坐标（竞技场将变为未部署）」）
     * 是两回事——后者是「将要被删的清单项」，挪到结果播报里读起来像还没执行。</p>
     */
    private static final String RESET_MISSING_CORE_KEY = "manage.reset.done-missing-core";

    /** 当前视图。层级关系由 {@link #history} 维护，不靠枚举序号推导。 */
    private enum View {
        LIST,
        DETAIL,
        SPAWN_LIST,
        MOB_SPAWN_LIST,
        NODE_LIST,
        VILLAGER_LIST,
        PARAMS,
        RESET_CONFIRM
    }

    /** 四个坐标列表视图的差异（数据源 / 图标 / 标题键），渲染与删除逻辑共用一份。 */
    private enum ListKind {
        SPAWN,
        MOB_SPAWN,
        NODE,
        VILLAGER
    }

    private View view = View.LIST;
    /** 后退栈：进入子视图前压入当前视图 */
    private final Deque<View> history = new ArrayDeque<>();

    /** 当前选中的竞技场：DETAIL / 三个列表 / PARAMS / RESET_CONFIRM 共用 */
    private String selectedArenaId;
    /** 待重置的竞技场：仅 RESET_CONFIRM 有效，离开该视图立即置 null */
    private String pendingResetArenaId;

    /** 分页页码（0 基）；进入新视图时归零，删除后钳制 */
    private int page;

    /**
     * @param admin  使用该菜单的管理员
     * @param plugin 主插件实例
     */
    public ArenaManageGUI(Player admin, HilltopVillagePlugin plugin) {
        super(admin, plugin.getLanguageManager().getFor(admin, "manage.title.list"), SIZE, plugin,
                plugin.getLanguageManager());
    }

    /* ========== 渲染入口 ========== */

    /**
     * 渲染当前视图。
     *
     * <p>开头必须先 {@code clickActions.clear()} + {@code inventory.clear()}：
     * 父类约定如此，否则同一槽位的上一次 action 会在重建后残留（视图切换时表现为「点了新按钮却执行旧逻辑」）。</p>
     */
    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();

        switch (view) {
            case LIST -> renderList();
            case DETAIL -> renderDetail();
            case SPAWN_LIST -> renderLocationList(ListKind.SPAWN);
            case MOB_SPAWN_LIST -> renderLocationList(ListKind.MOB_SPAWN);
            case NODE_LIST -> renderLocationList(ListKind.NODE);
            case VILLAGER_LIST -> renderLocationList(ListKind.VILLAGER);
            case PARAMS -> renderParams();
            case RESET_CONFIRM -> renderResetConfirm();
        }
    }

    /* ========== LIST：全部竞技场（45/页） ========== */

    private void renderList() {
        List<ArenaView> arenas = adminService().listArenas();
        clampPage(arenas.size());
        int pages = pageCount(arenas.size());
        int start = page * PAGE_SIZE;

        if (arenas.isEmpty()) {
            setItem(SLOT_EMPTY, Material.BARRIER, lang.getFor(admin, "manage.list.empty"));
        }

        for (int i = 0; i < PAGE_SIZE && start + i < arenas.size(); i++) {
            placeArenaEntry(i, arenas.get(start + i));
        }

        // 底行中部：总数 + 页码（窗口标题被父类固定为 manage.title.list，页码只能放这里）
        setItemWithData(SLOT_INFO, Material.BOOK, lang.getFor(admin, "manage.list.header", arenas.size()),
                List.of(lang.getFor(admin, "manage.list.page", page + 1, pages)));
        renderPager(pages);
        fillEmpty();
    }

    /** LIST 的条目：左键进入 DETAIL；其余点击类型一律忽略（防误触）。 */
    private void placeArenaEntry(int slot, ArenaView arena) {
        String arenaId = arena.arenaId();
        Material material = switch (arena.status()) {
            case READY -> arena.deployed() ? Material.LIME_WOOL : Material.YELLOW_WOOL;
            case QUEUED, LOADING -> Material.YELLOW_WOOL;
            case DISABLED -> arena.hasCore() ? Material.RED_WOOL : Material.GRAY_WOOL;
        };

        List<String> lore = new ArrayList<>();
        lore.add(lang.getFor(admin, "manage.list.entry-id", arena.arenaId()));
        lore.add(lang.getFor(admin, "manage.list.entry-status", viewStatusText(arena)));
        lore.add(arena.worldLoaded()
                ? lang.getFor(admin, "manage.list.entry-world", arena.worldName())
                : lang.getFor(admin, "manage.list.entry-world-missing", arena.worldName()));
        lore.add(lang.getFor(admin, arena.configuredEnabled()
                ? "manage.list.entry-enabled" : "manage.list.entry-disabled"));
        lore.add(lang.getFor(admin, deployStateKey(arena)));
        lore.add(lang.getFor(admin, "manage.list.entry-counts",
                arena.spawnCount(), arena.mobSpawnCount(), arena.villagerCount(), arena.nodeCount()));
        lore.add(lang.getFor(admin, "manage.list.entry-params",
                arena.minPlayers(), arena.maxPlayers(), arena.victoryWaves(), arena.waveIntervalSeconds()));
        lore.add(lang.getFor(admin, arena.hasInstance()
                ? "manage.list.entry-players" : "manage.list.source-yaml",
                arena.currentPlayers(), arena.maxPlayers()));
        lore.add(lang.getFor(admin, arena.shopNpcSet()
                ? "manage.list.entry-shop" : "manage.list.entry-shop-none"));
        lore.add(lang.getFor(admin, "manage.list.entry-click"));

        setClickableItemWithData(slot, material, displayName(arena), lore, clickType -> {
            if (clickType != ClickType.LEFT) return;
            selectedArenaId = arenaId;
            openView(View.DETAIL);
        });
    }

    /** 部署状态三态：未部署（缺核心）> 未部署 > 已部署。 */
    private String deployStateKey(ArenaView arena) {
        if (!arena.hasCore()) return "manage.list.entry-no-core";
        return arena.deployed() ? "manage.list.entry-deployed" : "manage.list.entry-undeployed";
    }

    /* ========== DETAIL ========== */

    private void renderDetail() {
        ArenaView arena = currentView();
        if (arena == null) {
            renderArenaMissing("manage.title.detail");
            return;
        }

        // 概要：状态 / 世界 / 配置开关 / 部署状态 / 在线人数（未加载时说明数据来自磁盘）
        List<String> summary = new ArrayList<>();
        summary.add(lang.getFor(admin, "manage.list.entry-id", arena.arenaId()));
        summary.add(lang.getFor(admin, "manage.list.entry-status", viewStatusText(arena)));
        summary.add(arena.worldLoaded()
                ? lang.getFor(admin, "manage.list.entry-world", arena.worldName())
                : lang.getFor(admin, "manage.list.entry-world-missing", arena.worldName()));
        summary.add(lang.getFor(admin, arena.configuredEnabled()
                ? "manage.list.entry-enabled" : "manage.list.entry-disabled"));
        summary.add(lang.getFor(admin, deployStateKey(arena)));
        summary.add(lang.getFor(admin, "manage.list.entry-params",
                arena.minPlayers(), arena.maxPlayers(), arena.victoryWaves(), arena.waveIntervalSeconds()));
        summary.add(lang.getFor(admin, arena.hasInstance()
                ? "manage.list.entry-players" : "manage.list.source-yaml",
                arena.currentPlayers(), arena.maxPlayers()));

        setItemWithData(4, Material.NETHER_STAR,
                lang.getFor(admin, "manage.title.detail", displayName(arena)), summary);

        // 三类坐标列表 + 参数（计数显示在 lore 上，进入子视图后才有分页）
        setClickableItemWithData(10, Material.SPAWNER, lang.getFor(admin, "manage.detail.spawns"),
                List.of(lang.getFor(admin, "manage.detail.spawns-lore", arena.spawnCount())),
                clickType -> openView(View.SPAWN_LIST));
        // STORY-1.2: mob spawn entry takes slot 11; villagers move 14->13 to keep one row
        setClickableItemWithData(11, Material.ZOMBIE_HEAD, lang.getFor(admin, "manage.detail.mobspawns"),
                List.of(lang.getFor(admin, "manage.detail.mobspawns-lore", arena.mobSpawnCount())),
                clickType -> openView(View.MOB_SPAWN_LIST));
        setClickableItemWithData(12, Material.END_ROD, lang.getFor(admin, "manage.detail.nodes"),
                List.of(lang.getFor(admin, "manage.detail.nodes-lore", arena.nodeCount())),
                clickType -> openView(View.NODE_LIST));
        setClickableItemWithData(13, Material.VILLAGER_SPAWN_EGG, lang.getFor(admin, "manage.detail.villagers"),
                List.of(lang.getFor(admin, "manage.detail.villagers-lore", arena.villagerCount())),
                clickType -> openView(View.VILLAGER_LIST));
        setClickableItemWithData(14, Material.COMPARATOR, lang.getFor(admin, "manage.detail.params"),
                List.of(lang.getFor(admin, "manage.detail.params-lore",
                        arena.minPlayers(), arena.maxPlayers(), arena.victoryWaves(), arena.waveIntervalSeconds())),
                clickType -> openView(View.PARAMS));

        renderShopItem(arena);
        renderTeleportItem(arena);
        renderToggleItem(arena);

        setClickableItemWithData(25, Material.TNT, lang.getFor(admin, "manage.detail.reset"),
                List.of(lang.getFor(admin, "manage.detail.reset-lore1"),
                        lang.getFor(admin, "manage.detail.reset-lore2"),
                        lang.getFor(admin, "manage.detail.reset-lore3"),
                        lang.getFor(admin, "manage.detail.reset-lore4")),
                clickType -> enterResetConfirm());

        setClickableItem(SLOT_BACK, Material.ARROW, lang.getFor(admin, "manage.detail.back"),
                clickType -> goBack());
        fillEmpty();
    }

    /** 商店 NPC：只读展示坐标；未设置时用中性图标，不注册点击。 */
    private void renderShopItem(ArenaView arena) {
        if (!arena.shopNpcSet()) {
            setItemWithData(19, Material.GRAY_DYE, lang.getFor(admin, "manage.detail.shop"),
                    List.of(lang.getFor(admin, "manage.detail.shop-none")));
            return;
        }
        Location shop = shopNpcOf(arena.arenaId());
        setItemWithData(19, Material.EMERALD, lang.getFor(admin, "manage.detail.shop"),
                List.of(lang.getFor(admin, "manage.detail.shop-lore", shop == null ? "-" : coords(shop))));
    }

    /**
     * 传送到核心。
     *
     * <p>两种不可用情形都置灰：定义里没有 core（未部署）、core 带的世界为 null（世界未加载，
     * {@code ArenaManager} 读盘时 {@code Bukkit.getWorld} 返回 null，直接 teleport 会 NPE）。</p>
     */
    private void renderTeleportItem(ArenaView arena) {
        Location core = coreOf(arena.arenaId());
        String title = lang.getFor(admin, "manage.detail.teleport-core");

        if (core == null) {
            setItemWithData(21, Material.GRAY_DYE, title,
                    List.of(lang.getFor(admin, "manage.reset.blocked-missing-core")));
            return;
        }
        if (core.getWorld() == null) {
            setItemWithData(21, Material.GRAY_DYE, title,
                    List.of(lang.getFor(admin, "manage.list.entry-world-missing", arena.worldName())));
            return;
        }

        setClickableItem(21, Material.COMPASS, title, clickType -> {
            admin.teleport(core);
            lang.send(admin, "manage.entry.teleported",
                    coord(core.getX()), coord(core.getY()), coord(core.getZ()));
            reopen();  // 跨世界传送会被客户端关闭背包，必须重新打开
        }, lang.getFor(admin, "manage.detail.teleport-core-lore"));
    }

    /**
     * 启用 / 禁用开关。
     *
     * <p>置灰的两种情形：{@code hasCore == false}（{@code enableArena} 会因 core 缺失直接拒绝）、
     * {@code enabled: false}（{@code enableArena} 同样拒绝，见 {@code MultiArenaManager#enableArena}）。</p>
     */
    private void renderToggleItem(ArenaView arena) {
        String arenaId = arena.arenaId();

        if (arena.status() != ArenaStatus.DISABLED) {
            setClickableItem(23, Material.REDSTONE_BLOCK, lang.getFor(admin, "manage.detail.disable"),
                    clickType -> {
                        multiArenaManager().disableArena(arenaId);
                        reopen();
                    });
            return;
        }

        if (arena.hasCore() && arena.configuredEnabled()) {
            setClickableItem(23, Material.EMERALD_BLOCK, lang.getFor(admin, "manage.detail.enable"),
                    clickType -> {
                        multiArenaManager().enableArena(arenaId);
                        reopen();
                    });
            return;
        }

        setItemWithData(23, Material.GRAY_DYE, lang.getFor(admin, "manage.detail.enable"),
                List.of(lang.getFor(admin, arena.hasCore()
                        ? "manage.list.entry-disabled" : "manage.reset.blocked-missing-core")));
    }

    /* ========== 三个坐标列表（45/页） ========== */

    private void renderLocationList(ListKind kind) {
        ArenaView arena = currentView();
        if (arena == null) {
            renderArenaMissing(titleKey(kind));
            return;
        }

        String arenaId = arena.arenaId();
        List<Location> locations = locationsOf(kind, arenaId);
        clampPage(locations.size());
        int pages = pageCount(locations.size());
        int start = page * PAGE_SIZE;

        if (locations.isEmpty()) {
            setItem(SLOT_EMPTY, Material.BARRIER,
                    lang.getFor(admin, titleKey(kind), displayName(arena)),
                    lang.getFor(admin, "manage.page.empty"));
        }

        for (int i = 0; i < PAGE_SIZE && start + i < locations.size(); i++) {
            Location location = locations.get(start + i);
            final int index = start + i;               // 全局序号：与 ArenaAdminService 的删除口径一致
            final String worldName = location.getWorld() != null
                    ? location.getWorld().getName() : arena.worldName();

            placeLocationEntry(i, kind, arenaId, index, location, worldName);
        }

        setItemWithData(SLOT_INFO, Material.BOOK,
                lang.getFor(admin, titleKey(kind), displayName(arena)),
                List.of(lang.getFor(admin, "manage.list.page", page + 1, pages)));
        renderPager(pages);
        setClickableItem(SLOT_BACK, Material.ARROW, lang.getFor(admin, "manage.back"),
                clickType -> goBack());
        fillEmpty();
    }

    /** 坐标列表条目：左键传送 / 右键删除 / Shift+左键粒子预览，lore 写明了这三种操作。 */
    private void placeLocationEntry(int slot, ListKind kind, String arenaId, int index,
                                    Location location, String worldName) {
        Material material = switch (kind) {
            case SPAWN -> Material.SPAWNER;
            case MOB_SPAWN -> Material.ZOMBIE_HEAD;
            case NODE -> Material.END_ROD;
            case VILLAGER -> Material.VILLAGER_SPAWN_EGG;
        };
        List<String> lore = List.of(lang.getFor(admin, "manage.entry.lore-world", worldName),
                lang.getFor(admin, "manage.entry.teleport"),
                lang.getFor(admin, "manage.entry.delete"),
                lang.getFor(admin, "manage.entry.particles"));

        setClickableItemWithData(slot, material,
                lang.getFor(admin, "manage.entry.name", index + 1,
                        coord(location.getX()), coord(location.getY()), coord(location.getZ())),
                lore,
                clickType -> handleLocationClick(kind, arenaId, index, location, worldName, clickType));
    }

    /**
     * 列表项点击路由：Shift+左键 = 粒子预览，右键 = 删除，左键 = 传送。
     *
     * <p>刻意只认这三种 {@link ClickType}：{@code SHIFT_RIGHT} / {@code DOUBLE_CLICK} 等一律忽略，
     * 避免误触删除。</p>
     */
    private void handleLocationClick(ListKind kind, String arenaId, int index, Location location,
                                     String worldName, ClickType clickType) {
        if (clickType == ClickType.SHIFT_LEFT) {
            previewService().show(admin, List.of(location), PREVIEW_TICKS);
            return;
        }

        if (clickType == ClickType.RIGHT) {
            boolean deleted = deleteLocation(kind, arenaId, index);
            lang.send(admin, deleted ? "manage.entry.deleted" : "manage.entry.delete-failed", index + 1);
            // 删空当前页时必须回退上一页，否则会停在一个空页上（renderLocationList 里还会再钳制一次）
            clampPage(locationsOf(kind, arenaId).size());
            reopen();
            return;
        }

        if (clickType != ClickType.LEFT) return;

        if (location.getWorld() == null) {
            // 世界未加载：坐标有效但不能传送（ArenaAdminService 会返回 world == null 的位置）
            lang.send(admin, "manage.list.entry-world-missing", worldName);
            return;
        }
        admin.teleport(location);
        lang.send(admin, "manage.entry.teleported",
                coord(location.getX()), coord(location.getY()), coord(location.getZ()));
        reopen();  // 跨世界传送会让客户端关闭背包，必须重新打开
    }

    private boolean deleteLocation(ListKind kind, String arenaId, int index) {
        return switch (kind) {
            case SPAWN -> adminService().deleteSpawnPoint(arenaId, index);
            case MOB_SPAWN -> adminService().deleteMobSpawnPoint(arenaId, index);
            case NODE -> adminService().deleteNode(arenaId, index);
            case VILLAGER -> adminService().deleteVillager(arenaId, index);
        };
    }

    private List<Location> locationsOf(ListKind kind, String arenaId) {
        return switch (kind) {
            case SPAWN -> adminService().spawnPoints(arenaId);
            case MOB_SPAWN -> adminService().mobSpawnPoints(arenaId);
            case NODE -> adminService().nodes(arenaId);
            case VILLAGER -> adminService().villagers(arenaId);
        };
    }

    private static String titleKey(ListKind kind) {
        return switch (kind) {
            case SPAWN -> "manage.title.spawns";
            case MOB_SPAWN -> "manage.title.mobspawns";
            case NODE -> "manage.title.nodes";
            case VILLAGER -> "manage.title.villagers";
        };
    }

    /* ========== PARAMS（只读） ========== */

    /**
     * 参数页的 4 个可编辑项。
     *
     * <p>把「槽位 / 图标 / 标签键 / 取值范围 / 读取哪个字段」收进枚举，渲染与写入各走一次
     * switch，避免四个字段抄四遍（抄错一处就是「点了 A 改了 B」）。</p>
     *
     * <p>取值范围与全局配置菜单（{@code GameRulesMenu}）保持一致，只是这里的上限放宽到
     * {@code ArenaManager} 的校验口径（人数 ≥ 1、上限 ≥ 下限、波数 ≥ 1、间隔 ≥ 1）——
     * 界面给出的是推荐范围，真正拦住非法值的仍是写盘前的 {@code validGameParams}。</p>
     */
    private enum Param {
        MIN_PLAYERS(19, Material.PLAYER_HEAD, "config.menu.rules.min-players", 1, 20),
        MAX_PLAYERS(21, Material.PLAYER_HEAD, "config.menu.rules.max-players", 2, 20),
        VICTORY_WAVES(23, Material.TURTLE_EGG, "config.menu.rules.victory-waves", 1, 100),
        WAVE_INTERVAL(25, Material.REDSTONE, "config.menu.rules.wave-interval", 5, 300);

        final int slot;
        final Material icon;
        final String labelKey;
        final int min;
        final int max;

        Param(int slot, Material icon, String labelKey, int min, int max) {
            this.slot = slot;
            this.icon = icon;
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
        }

        /** 本项在给定竞技场视图里的当前值 */
        int read(ArenaView arena) {
            return switch (this) {
                case MIN_PLAYERS -> arena.minPlayers();
                case MAX_PLAYERS -> arena.maxPlayers();
                case VICTORY_WAVES -> arena.victoryWaves();
                case WAVE_INTERVAL -> arena.waveIntervalSeconds();
            };
        }
    }

    /**
     * 参数视图：4 个 per-arena 游戏参数（{@code arenas.<id>.game.*}）在此编辑。
     *
     * <p>点击任一条目 → 关闭背包 → 在聊天栏输入新值（复用 {@link NumericInputHandler}）。
     * 不在这里做 ±1 步进：这四项的合理步进本来就不一致（人数 1、波次间隔 5），
     * 而管理页的使用频率远低于玩家侧菜单，直接输入更省事，也不会「点快了把值点飞」。</p>
     *
     * <p>写盘走 {@link ArenaAdminService#updateGameParams}，它包含「对局中拒绝」判定并会把新值
     * 推送到活动实例——人数在下一局生效，波次间隔与波数从下一个休整期起生效，不需要重启。</p>
     *
     * <p>标签复用 {@code config.menu.rules.*}（全局配置菜单里的同名文案，
     * 语义为「最少人数 / 最多人数 / 通关波数 / 波次间隔」，与本视图内容一致）。</p>
     */
    private void renderParams() {
        ArenaView arena = currentView();
        if (arena == null) {
            renderArenaMissing("manage.title.params");
            return;
        }

        for (Param param : Param.values()) {
            renderParamItem(arena, param);
        }

        setItemWithData(31, Material.PAPER, lang.getFor(admin, "manage.detail.params"),
                List.of(lang.getFor(admin, "manage.detail.params-lore",
                                arena.minPlayers(), arena.maxPlayers(),
                                arena.victoryWaves(), arena.waveIntervalSeconds()),
                        lang.getFor(admin, "manage.params.edit-hint"),
                        lang.getFor(admin, "manage.detail.params-inert")));

        setClickableItem(SLOT_BACK, Material.ARROW, lang.getFor(admin, "manage.back"),
                clickType -> goBack());
        fillEmpty();
    }

    /** 单个可编辑参数条目：点击后在聊天栏输入新值。 */
    private void renderParamItem(ArenaView arena, Param param) {
        String label = lang.getFor(admin, param.labelKey);
        int current = param.read(arena);
        setClickableItemWithData(param.slot, param.icon, ChatColor.GREEN + label,
                List.of(formatValue(lang.getFor(admin, "config.menu.common.current-value"), current),
                        lang.getFor(admin, "manage.params.range", param.min, param.max),
                        "",
                        lang.getFor(admin, "manage.params.click-hint")),
                clickType -> requestParamInput(arena, param, label, current));
    }

    /**
     * 关背包 → 聊天栏输入 → 写盘。取消或 30 秒超时只重开 GUI，不动任何数据。
     *
     * <p>{@code arena} 快照在点击瞬间取定：输入期间管理员可能用命令改了别的参数，
     * 用旧快照的其余三项回写，会把这期间的改动覆盖掉。改用「先取最新视图、只替换本项」的
     * 写法会引入更多分支，而管理页单人操作的窗口极短，故接受这一取舍并在此显式记录。</p>
     */
    private void requestParamInput(ArenaView arena, Param param, String label, int current) {
        admin.closeInventory();
        NumericInputHandler.requestInteger(plugin, admin,
                lang.getFor(admin, "config.menu.common.setting-prefix") + label
                        + " (" + lang.getFor(admin, "config.menu.common.current") + current + ")",
                value -> {
                    applyParam(arena, param, label, value);
                    reopen();
                },
                this::reopen, lang);
    }

    /**
     * 把新值合入另外三项，一次性写盘。
     *
     * <p>写盘失败时磁盘内容不变（{@code ArenaManager} 先校验后落盘，失败不产生半截写入），
     * 因此这里不需要回滚任何内存状态，只需按结果给出对应提示。</p>
     */
    private void applyParam(ArenaView arena, Param param, String label, int value) {
        int min = arena.minPlayers();
        int max = arena.maxPlayers();
        int waves = arena.victoryWaves();
        int interval = arena.waveIntervalSeconds();
        switch (param) {
            case MIN_PLAYERS -> min = value;
            case MAX_PLAYERS -> max = value;
            case VICTORY_WAVES -> waves = value;
            case WAVE_INTERVAL -> interval = value;
        }

        ParamUpdate result = adminService().updateGameParams(arena.arenaId(), min, max, waves, interval);
        switch (result) {
            case OK -> lang.send(admin, "manage.params.saved", label, value);
            case ARENA_NOT_FOUND -> lang.send(admin, "manage.reset.blocked-not-found");
            case INVALID_VALUE -> lang.send(admin, "manage.params.invalid");
            // 专用文案：manage.reset.blocked-game-progress 写着「先 /hilltop stop」，对局进行中才成立
            case GAME_IN_PROGRESS -> lang.send(admin, "manage.params.blocked-in-game", arena.arenaId());
            // 结算倒计时中本局已结束，「先 stop」没有意义，故另用一句（不带 {0}，键里也没有占位符）
            case COUNTDOWN_IN_PROGRESS -> lang.send(admin, "manage.params.blocked-countdown");
            case WRITE_FAILED -> lang.send(admin, "manage.params.write-failed");
        }
    }

    /* ========== RESET_CONFIRM ========== */

    private void renderResetConfirm() {
        ArenaView arena = currentView();
        if (arena == null) {
            renderArenaMissing("manage.title.reset-confirm");
            return;
        }

        List<String> warnings = new ArrayList<>();
        warnings.add(lang.getFor(admin, "manage.reset.warn-title"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-spawns"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-villagers"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-nodes"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-shop"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-core"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-params"));
        warnings.add(lang.getFor(admin, "manage.reset.warn-map"));
        // 实际在场人数：仅实例存在时才有意义（未加载的竞技场 currentPlayers 恒为 0，不该谎报一行）
        if (arena.hasInstance() && arena.currentPlayers() > 0) {
            warnings.add(lang.getFor(admin, "manage.reset.warn-players", arena.currentPlayers()));
        }
        warnings.add("");
        warnings.add(lang.getFor(admin, "manage.reset.warn-keep"));

        setItemWithData(4, Material.TNT,
                lang.getFor(admin, "manage.reset.confirm-item", displayName(arena)), warnings);

        setClickableItem(29, Material.LIME_WOOL, lang.getFor(admin, "manage.reset.confirm-btn"),
                clickType -> confirmReset(), lang.getFor(admin, "manage.reset.confirm-btn-lore"));
        setClickableItem(33, Material.RED_WOOL, lang.getFor(admin, "manage.reset.cancel-btn"),
                clickType -> {
                    lang.send(admin, "manage.reset.cancelled");
                    goBack();
                }, lang.getFor(admin, "manage.reset.cancel-btn-lore"));

        setClickableItem(SLOT_BACK, Material.ARROW, lang.getFor(admin, "manage.back"),
                clickType -> goBack());
        fillEmpty();
    }

    /**
     * 点确认按钮：重新过一遍 {@link ArenaResetService#canReset} 再执行。
     *
     * <p>确认页可能停留很久，期间竞技场可能已开局 / 已开始另一次重置 / 已在启用队列里，
     * 直接调 {@code reset} 只会白跑一趟。</p>
     *
     * <p>成功后<b>先切回 DETAIL 再执行</b>：{@code reset} 的回调可能在本次调用栈内同步触发
     * （世界没有待回滚方块时 {@code MapRestoreManager} 直接回报完成），若不先离开确认页，
     * 回调里的 {@code reopen()} 会再次渲染确认页，管理员可以重复点击。</p>
     */
    private void confirmReset() {
        String arenaId = pendingResetArenaId;
        if (arenaId == null) return;   // 防御：正常流程下进入 RESET_CONFIRM 时必然已赋值

        Gate gate = resetService().canReset(arenaId);
        if (gate != Gate.OK) {
            lang.send(admin, blockedKey(gate), arenaId);
            applyView(View.DETAIL);
            return;
        }

        // 回调里要用到的「重置前」快照必须在调用前取好：reset() 返回后数据已被清空
        int nodesBefore = adminService().nodes(arenaId).size();
        ArenaView before = currentView();
        String worldName = before != null ? before.worldName() : arenaId;

        applyView(View.DETAIL);
        lang.send(admin, "manage.reset.started", arenaId);

        resetService().reset(arenaId, outcome -> handleResetOutcome(outcome, nodesBefore, worldName));
    }

    /**
     * {@link ArenaResetService#reset} 的完成回调。
     *
     * <p><b>可能是同步回调</b>（调用栈内），因此这里只做三件安全的事：
     * 发消息、更新本对象的纯内存字段、{@code reopen()}。
     * 绝不再调 {@code openInventory} 或关背包 —— 那会与调用栈中尚未结束的点击处理打架。</p>
     */
    private void handleResetOutcome(Outcome outcome, int nodesBefore, String worldName) {
        if (!admin.isOnline()) return;

        reportReset(outcome, nodesBefore, worldName);

        page = 0;          // 重置会清空三个列表，页码必须归零
        reopen();
    }

    /** 按 {@link Outcome} 逐项播报。failStep 可能取规格未枚举的值（如 internal），一律按「非 null 即失败」处理。 */
    private void reportReset(Outcome outcome, int nodesBefore, String worldName) {
        Gate blocked = outcome.blockedBy();
        if (blocked != null && blocked != Gate.OK) {
            lang.send(admin, blockedKey(blocked), outcome.arenaId());
            return;
        }

        String failStep = outcome.failStep();
        if (failStep != null) {
            // 世界未加载导致回滚被跳过时，fail-skipped-world 比 fail-restore 更准确地说明原因
            if ("restore".equals(failStep) && outcome.mapRestoreSkipped() && !outcome.mapRestored()) {
                lang.send(admin, "manage.reset.fail-skipped-world", worldName);
            }
            lang.send(admin, "manage.reset.partial", failStepText(failStep));
            return;
        }

        lang.send(admin, "manage.reset.done", outcome.arenaId());
        if (outcome.nodesCleared() && nodesBefore > 0) {
            lang.send(admin, "manage.reset.done-nodes", nodesBefore);
        }
        if (outcome.shopNpcCleared()) {
            lang.send(admin, "manage.reset.done-shop");
        }
        if (outcome.dataCleared()) {
            lang.send(admin, "manage.reset.done-data");
        }

        if (outcome.reinstated()) {
            lang.send(admin, "manage.reset.done-reinstated", arenaStatusText(outcome.statusAfter()));
        } else if (outcome.reinstateSkipped() != null) {
            // 统一带上 arenaId：只有 done-enable-rejected 用到 {0}，其余键没有占位符，多余的参数会被忽略
            lang.send(admin, reinstateSkipKey(outcome.reinstateSkipped()), outcome.arenaId());
        }
    }

    /**
     * 未重新启用的文案。
     *
     * <p>三种跳过原因给出三句不同的话：{@code done-not-reinstated} 断言「配置中 enabled: false」，
     * 而 {@code NOT_READY_BEFORE}（重置前本来就没启用）与 {@code ENABLE_REJECTED}（被启用队列拒绝）
     * 都不是这个原因，复用会让管理员去 arenas.yml 里找一个并不存在的 {@code enabled: false}。</p>
     */
    private String reinstateSkipKey(ReinstateSkip skip) {
        return switch (skip) {
            case MISSING_CORE -> RESET_MISSING_CORE_KEY;
            case NOT_READY_BEFORE -> "manage.reset.done-not-ready-before";
            case CONFIG_DISABLED -> "manage.reset.done-not-reinstated";
            case ENABLE_REJECTED -> "manage.reset.done-enable-rejected";
        };
    }

    /** 失败步骤名 → 文案；未知步骤（含兜底的 {@code internal}）原样展示，不抛异常。 */
    private String failStepText(String failStep) {
        String key = switch (failStep) {
            case "nodes" -> "manage.reset.fail-step-nodes";
            case "shop-npc" -> "manage.reset.fail-step-shop";
            case "deployment" -> "manage.reset.fail-step-data";
            case "restore" -> "manage.reset.fail-restore";
            default -> null;
        };
        return key == null ? failStep : lang.getFor(admin, key);
    }

    /** {@link Gate} 的 7 个值全部有对应提示（枚举穷尽，新增值会在此编译期暴露）。 */
    private String blockedKey(Gate gate) {
        return switch (gate) {
            case OK -> null;   // 调用方负责先判定，永不作为提示使用
            case ARENA_NOT_FOUND -> "manage.reset.blocked-not-found";
            case STATUS_PENDING -> "manage.reset.blocked-pending";
            case MISSING_CORE -> "manage.reset.blocked-missing-core";
            case GAME_IN_PROGRESS -> "manage.reset.blocked-game-progress";   // {0} = 竞技场 ID
            case RESET_IN_PROGRESS -> "manage.reset.blocked-reset-progress";
            case ALREADY_RESETTING -> "manage.reset.blocked-already";
        };
    }

    /* ========== 视图流转 ========== */

    /** 进入子视图：先把当前视图压栈（供「返回」回退）。 */
    private void openView(View target) {
        history.push(view);
        applyView(target);
    }

    /** 返回上一级；栈空时回到 LIST（LIST 是根视图，没有「上一级」）。 */
    private void goBack() {
        applyView(history.isEmpty() ? View.LIST : history.pop());
    }

    /**
     * 切换视图并重建菜单。
     *
     * <p>两个状态字段在这里统一维护，避免各视图各自清理：
     * {@code pendingResetArenaId} 只在 RESET_CONFIRM 内有效，离开立即置 null；
     * 回到 LIST 时清空 {@code selectedArenaId}（它只在某个竞技场的上下文里有意义）。</p>
     */
    private void applyView(View target) {
        if (target != View.RESET_CONFIRM) pendingResetArenaId = null;
        if (target == View.LIST) selectedArenaId = null;
        view = target;
        page = 0;
        reopen();
    }

    /** 进入确认页时绑定待重置竞技场。 */
    private void enterResetConfirm() {
        pendingResetArenaId = selectedArenaId;
        openView(View.RESET_CONFIRM);
    }

    /* ========== 分页 ========== */

    /**
     * 页码钳制：把 {@link #page} 收进 {@code [0, 最后一页]}。
     *
     * <p>删除最后一条后当前页可能已经超界（空页），此处会自动回退到上一页。
     * 每次渲染分页视图时都要先调用它，而不是只在删除时调用 —— 竞技场 / 节点数也可能被外部命令改动。</p>
     */
    private void clampPage(int totalEntries) {
        int lastPage = Math.max(0, (totalEntries - 1) / PAGE_SIZE);
        if (page > lastPage) page = lastPage;
        if (page < 0) page = 0;
    }

    private static int pageCount(int totalEntries) {
        return Math.max(1, (totalEntries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 渲染上一页 / 下一页（只有一页时两个都不放）。 */
    private void renderPager(int pages) {
        if (page > 0) {
            setClickableItem(SLOT_PREV, Material.ARROW, lang.getFor(admin, "manage.page.prev"), clickType -> {
                page--;
                reopen();
            });
        }
        if (page < pages - 1) {
            setClickableItem(SLOT_NEXT, Material.ARROW, lang.getFor(admin, "manage.page.next"), clickType -> {
                page++;
                reopen();
            });
        }
    }

    /* ========== 通用工具 ========== */

    /** 当前选中竞技场的实时视图；竞技场已被删除 / ID 为空时返回 null。 */
    private ArenaView currentView() {
        return adminService().describe(selectedArenaId);
    }

    /** 竞技场在 GUI 内的显示名（已解析 {@code &} 颜色码）。 */
    private static String displayName(ArenaView arena) {
        return ChatColor.translateAlternateColorCodes('&', arena.displayName());
    }

    /** 竞技场已不存在时的兜底页面：只放一个原因提示与返回按钮，不注册任何危险操作。 */
    private void renderArenaMissing(String titleKey) {
        setItemWithData(SLOT_EMPTY, Material.BARRIER,
                lang.getFor(admin, titleKey, selectedArenaId == null ? "" : selectedArenaId),
                List.of(lang.getFor(admin, "manage.reset.blocked-not-found")));
        setClickableItem(SLOT_BACK, Material.ARROW, lang.getFor(admin, "manage.back"),
                clickType -> goBack());
        fillEmpty();
    }

    /**
     * 竞技场维度的实时状态文案。
     *
     * <p>复用 {@code arena-status-*} 这一组既有键：{@link ArenaStatus} 只表达生命周期
     * （禁用 / 排队 / 加载 / 就绪），真正的对局阶段在 {@link GameState} 里，所以在 READY 时改看后者。</p>
     */
    private String viewStatusText(ArenaView arena) {
        if (arena.status() != ArenaStatus.READY || arena.gameState() == null) {
            return arenaStatusText(arena.status());
        }
        String key = switch (arena.gameState()) {
            case WAITING -> "arena-status-waiting";
            case STARTING -> "arena-status-starting";
            case RUNNING, WAVE_INTERMISSION -> "arena-status-running";
            case VICTORY -> "state.victory";
            case DEFEAT -> "state.defeat";
            case ENDING -> "arena-status-ending";
        };
        return lang.getFor(admin, key);
    }

    private String arenaStatusText(ArenaStatus status) {
        ArenaStatus resolved = status == null ? ArenaStatus.DISABLED : status;
        String key = switch (resolved) {
            case DISABLED -> "arena-status-disabled";
            case QUEUED -> "arena-status-queued";
            case LOADING -> "arena-status-loading";
            // READY = 已加载且空闲，语义与「等待中」一致
            case READY -> "arena-status-waiting";
        };
        return lang.getFor(admin, key);
    }

    private static String coord(double value) {
        if (!Double.isFinite(value)) return "?";
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String coords(Location location) {
        return coord(location.getX()) + ", " + coord(location.getY()) + ", " + coord(location.getZ());
    }

    /* ========== 服务取用 ========== */

    private ArenaAdminService adminService() {
        return hilltop.getArenaAdminService();
    }

    private ArenaResetService resetService() {
        return hilltop.getArenaResetService();
    }

    private LocationParticlePreview previewService() {
        return hilltop.getLocationParticlePreview();
    }

    private MultiArenaManager multiArenaManager() {
        return hilltop.getMultiArenaManager();
    }

    /** 核心坐标；竞技场不存在或未部署返回 null。 */
    private Location coreOf(String arenaId) {
        ArenaDefinition definition = definitionOf(arenaId);
        return definition == null ? null : definition.getCore();
    }

    /** 商店 NPC 坐标；竞技场不存在或未设置返回 null。 */
    private Location shopNpcOf(String arenaId) {
        ArenaDefinition definition = definitionOf(arenaId);
        return definition == null ? null : definition.getShopNpc();
    }

    private ArenaDefinition definitionOf(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return null;
        return multiArenaManager().getDefinitionManager().getArena(arenaId);
    }
}
