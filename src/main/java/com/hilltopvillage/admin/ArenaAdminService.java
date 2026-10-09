package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaRegistry;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.mechanics.NodeSystem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 竞技场管理 GUI 的数据层：为 GUI 提供<b>任意</b>竞技场（含未启用 / 未部署 / 世界缺失）的只读视图，
 * 以及生成点 / 村民 / 节点的单条删除能力。
 *
 * <h2>为什么需要「实例优先、YAML 兜底」</h2>
 * <p>未启用的竞技场没有 {@link ArenaInstance}（{@code MultiArenaManager.getArena(id)} 返回 null），
 * 因此拿不到 {@link AdminDeployManager}、{@link NodeSystem} 这些挂在实例上的组件。
 * 而 {@link ArenaDefinition} 也不提供村民 / 生成点的「当前部署状态」
 * （{@link ArenaDefinition#getCore()} 与 {@link ArenaDefinition#getShopNpc()} 允许为 null = 尚未部署）。
 * 所以三个数据源一律按下面的优先级取值：</p>
 * <ul>
 *   <li><b>有实例</b> —— 生成点 / 村民 / 商店 NPC 取
 *       {@link AdminDeployManager#getSpawnPoints()}、{@link AdminDeployManager#getVillagers()}、
 *       {@link AdminDeployManager#getShopNpcLocation()}；节点取
 *       {@link NodeSystem#getAllNodeLocations()}（内存注册表）。</li>
 *   <li><b>无实例</b> —— 直接读 {@code arenas.yml} 的
 *       {@code arenas.<id>.spawn-points} / {@code arenas.<id>.villagers} / {@code arenas.<id>.shop-npc}；
 *       节点用 {@link NodeSystem#readNodesForWorld(File, String)} 读 {@code nodes.yml}。</li>
 * </ul>
 *
 * <h2>序号口径（删除类方法的关键约定）</h2>
 * <p>{@link #deleteSpawnPoint(String, int)} / {@link #deleteVillager(String, int)} /
 * {@link #deleteNode(String, int)} 的 {@code index} 是「<b>按该竞技场过滤后的序号</b>」，
 * 即 {@link #spawnPoints(String)} / {@link #villagers(String)} / {@link #nodes(String)}
 * 返回列表中同序号的那一条。GUI 必须用同一个列表的序号调用删除，不要用 {@code arenas.yml} 的原始行号
 * （坐标损坏的条目会被读取时跳过，两者可能不对齐）。</p>
 *
 * <h2>World 可能为 null</h2>
 * <p>世界未加载时 {@link NodeSystem#readNodesForWorld(File, String)} 返回
 * {@code new Location(null, x, y, z)}（保留坐标、不带世界）。本类返回的位置同样如此，
 * 调用方<b>只能</b>使用 x/y/z/yaw/pitch 系方法，禁止对返回的 Location 调用
 * {@code getBlock()} / {@code getWorld().xxx} 等依赖世界的操作（会 NPE）。</p>
 *
 * <h2>只读、不缓存</h2>
 * <p>{@link #listArenas()} / {@link #describe(String)} 每次都实时读 registry 与磁盘，
 * 不在内部缓存 {@link ArenaView}：GUI 每次重建都会调用它们，而 arenas.yml 只有几十行、
 * 竞技场数量是个位数，开销可忽略；缓存反而会在重置 / 重新部署后显示过期数据。</p>
 *
 * <p>本类纯主线程使用（GUI 交互与命令线程），未做额外同步。</p>
 */
public final class ArenaAdminService {

    /** arenas.yml 中按竞技场存储的生成点列表键（与 {@code AdminDeployManager} 的写法一致） */
    private static final String SPAWN_POINTS_KEY = "spawn-points";
    /** arenas.yml 中按竞技场存储的怪物刷怪点列表键（STORY-1.2 新增 schema） */
    private static final String MOB_SPAWN_POINTS_KEY = "mob-spawn-points";
    /** arenas.yml 中按竞技场存储的村民列表键 */
    private static final String VILLAGERS_KEY = "villagers";
    /** arenas.yml 中按竞技场存储的商店 NPC 位置键 */
    private static final String SHOP_NPC_KEY = "shop-npc";

    private final HilltopVillagePlugin plugin;

    public ArenaAdminService(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 列表页用的只读快照。
     *
     * @param arenaId            竞技场 ID（registry 中的规范化 ID，即 arenas.yml 的键）
     * @param displayName        显示名；arenas.yml 未配置时回退为 {@code arenaId}（不为 null）
     * @param configuredEnabled  arenas.yml 的 {@code enabled}（取自 registry 中的定义）
     * @param status             运行时状态（{@link MultiArenaManager#getStatus(String)}）
     * @param worldName          竞技场世界名
     * @param worldLoaded        {@code Bukkit.getWorld(worldName) != null}
     * @param hasCore            定义中是否已配置核心（false = 尚未部署，无法启用）
     * @param deployed           是否已配好固定生成点（有实例时取 {@code gameManager.hasSpawnPoints()}，
     *                           否则取 arenas.yml 的 spawn-points 是否非空）。<b>不等同于 hasCore</b>
     * @param spawnCount         已配置的玩家出生点数量
     * @param mobSpawnCount      已配置的怪物刷怪点数量（STORY-1.2；旧存档为 0，刷怪双用回退）
     * @param villagerCount      已登记的村民数量
     * @param nodeCount          已注册的节点数量（按世界过滤）
     * @param shopNpcSet         是否已配置商店 NPC
     * @param minPlayers         人数下限（arenas.yml 的 game.min-players，含全局兜底）
     * @param maxPlayers         人数上限
     * @param victoryWaves       通关所需波数
     * @param waveIntervalSeconds 波次间隔（秒）
     * @param hasInstance        是否存在运行时实例（未启用 / 已禁用的竞技场为 false）
     * @param currentPlayers     当前玩家人数；仅 {@code hasInstance} 时有意义，否则恒为 0
     * @param gameState          当前对局阶段；仅 {@code hasInstance} 时非 null
     */
    public record ArenaView(
            String arenaId,
            String displayName,
            boolean configuredEnabled,     // arenas.yml 的 enabled
            ArenaStatus status,            // 运行时状态（MultiArenaManager.getStatus）
            String worldName,
            boolean worldLoaded,           // Bukkit.getWorld(worldName) != null
            boolean hasCore,               // definition.getCore() != null
            boolean deployed,              // 有固定生成点（实例优先/YAML 兜底）
            int spawnCount, int mobSpawnCount, int villagerCount, int nodeCount,
            boolean shopNpcSet,
            int minPlayers, int maxPlayers, int victoryWaves, int waveIntervalSeconds,
            boolean hasInstance,           // MultiArenaManager.getArena(id) != null
            int currentPlayers,            // 仅 hasInstance 时有意义，否则 0
            GameState gameState            // 仅 hasInstance 时非 null
    ) {
    }

    /* ========== 只读视图 ========== */

    /** 全部已配置竞技场（含未启用/未部署/世界缺失），按 registry 顺序。 */
    public List<ArenaView> listArenas() {
        List<ArenaView> views = new ArrayList<>();
        for (ArenaDefinition definition : registry().list()) {
            views.add(buildView(definition));
        }
        return views;
    }

    /** 单个竞技场的视图；arenaId 不存在返回 null（大小写不敏感，与 ArenaRegistry 口径一致）。 */
    public ArenaView describe(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        return definition == null ? null : buildView(definition);
    }

    /** 生成点坐标。竞技场不存在或未部署时返回空列表（不返回 null）。 */
    public List<Location> spawnPoints(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null) return List.of();

        ArenaInstance live = liveInstance(definition.getId());
        if (live != null) {
            AdminDeployManager deploy = deployManager(live);
            if (deploy != null) return immutableCopy(deploy.getSpawnPoints());
        }
        return immutableCopy(readYamlLocations(loadArenas(),
                arenaPath(definition.getId(), SPAWN_POINTS_KEY), Bukkit.getWorld(definition.getWorldName())));
    }

    /**
     * 怪物刷怪点坐标（STORY-1.2）。竞技场不存在或未部署时返回空列表（不返回 null）。
     *
     * <p>旧存档返回空列表（不做双用回退）：管理 GUI 展示的是「独立刷怪点的部署状态」，
     * 空 = 服主尚未补部署。刷怪生效坐标见 {@code GameManager#getMobSpawnPointsView()}。</p>
     */
    public List<Location> mobSpawnPoints(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null) return List.of();

        ArenaInstance live = liveInstance(definition.getId());
        if (live != null) {
            AdminDeployManager deploy = deployManager(live);
            if (deploy != null) return immutableCopy(deploy.getMobSpawnPoints());
        }
        return immutableCopy(readYamlLocations(loadArenas(),
                arenaPath(definition.getId(), MOB_SPAWN_POINTS_KEY), Bukkit.getWorld(definition.getWorldName())));
    }

    /** 村民坐标。竞技场不存在或未登记村民时返回空列表（不返回 null）。 */
    public List<Location> villagers(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null) return List.of();

        ArenaInstance live = liveInstance(definition.getId());
        if (live != null) {
            AdminDeployManager deploy = deployManager(live);
            if (deploy != null) return immutableCopy(deploy.getVillagers());
        }
        return immutableCopy(readYamlLocations(loadArenas(),
                arenaPath(definition.getId(), VILLAGERS_KEY), Bukkit.getWorld(definition.getWorldName())));
    }

    /**
     * 节点坐标。竞技场不存在或未注册节点时返回空列表（不返回 null）。
     *
     * <p>无实例时走 {@link NodeSystem#readNodesForWorld(File, String)}：世界未加载时返回的位置
     * {@code world} 为 null（坐标仍有效），调用方不得对其做依赖世界的操作。</p>
     *
     * <p><b>顺序提示：</b>有实例时返回的是 {@code NodeSystem} 内存注册表（{@code ConcurrentHashMap}）的
     * 迭代顺序，同一份未改动的内容重复调用结果是稳定的，但该顺序不保证与 nodes.yml 的书写顺序一致。</p>
     */
    public List<Location> nodes(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null) return List.of();

        ArenaInstance live = liveInstance(definition.getId());
        if (live != null && live.getNodeSystem() != null) {
            return immutableCopy(live.getNodeSystem().getAllNodeLocations());
        }
        return immutableCopy(NodeSystem.readNodesForWorld(plugin.getDataFolder(), definition.getWorldName()));
    }

    /* ========== 单条删除 ========== */

    /**
     * 按「过滤后序号」删除一个怪物生成点。
     *
     * <p>有实例时走 {@link AdminDeployManager#removeSpawnPoint(int)}（内部会落盘并刷新 GameManager），
     * 无实例（或实例拿不到部署管理器）时直接改 arenas.yml 的 {@code arenas.<id>.spawn-points}。</p>
     *
     * @param index {@link #spawnPoints(String)} 返回列表中的序号（不是 arenas.yml 的原始行号）
     * @return true 表示已删除；竞技场不存在、序号越界或写盘失败返回 false
     */
    public boolean deleteSpawnPoint(String arenaId, int index) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null || index < 0) return false;

        ArenaInstance live = liveInstance(definition.getId());
        AdminDeployManager deploy = live == null ? null : deployManager(live);
        if (deploy == null) {
            return deleteYamlEntry(definition.getId(), SPAWN_POINTS_KEY, index);
        }
        if (index >= deploy.getSpawnPoints().size()) return false;
        return deploy.removeSpawnPoint(index);
    }

    /**
     * 按「过滤后序号」删除一个怪物刷怪点（STORY-1.2）。
     *
     * <p>有实例时走 {@link AdminDeployManager#removeMobSpawnPoint(int)}（内部会落盘并刷新 GameManager），
     * 无实例（或实例拿不到部署管理器）时直接改 arenas.yml 的 {@code arenas.<id>.mob-spawn-points}。</p>
     *
     * @param index {@link #mobSpawnPoints(String)} 返回列表中的序号（不是 arenas.yml 的原始行号）
     * @return true 表示已删除；竞技场不存在、序号越界或写盘失败返回 false
     */
    public boolean deleteMobSpawnPoint(String arenaId, int index) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null || index < 0) return false;

        ArenaInstance live = liveInstance(definition.getId());
        AdminDeployManager deploy = live == null ? null : deployManager(live);
        if (deploy == null) {
            return deleteYamlEntry(definition.getId(), MOB_SPAWN_POINTS_KEY, index);
        }
        if (index >= deploy.getMobSpawnPoints().size()) return false;
        return deploy.removeMobSpawnPoint(index);
    }

    /**
     * 按「过滤后序号」删除一名村民。
     *
     * <p>有实例时走 {@link AdminDeployManager#removeVillager(int)}，无实例（或实例拿不到部署管理器）时
     * 直接改 arenas.yml 的 {@code arenas.<id>.villagers}。</p>
     *
     * @param index {@link #villagers(String)} 返回列表中的序号
     * @return true 表示已删除；竞技场不存在、序号越界或写盘失败返回 false
     */
    public boolean deleteVillager(String arenaId, int index) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null || index < 0) return false;

        ArenaInstance live = liveInstance(definition.getId());
        AdminDeployManager deploy = live == null ? null : deployManager(live);
        if (deploy == null) {
            return deleteYamlEntry(definition.getId(), VILLAGERS_KEY, index);
        }
        if (index >= deploy.getVillagers().size()) return false;
        return deploy.removeVillager(index);
    }

    /**
     * 按「过滤后序号」删除一个节点。
     *
     * <p>有实例时走 {@link NodeSystem#removeNode(Location)}，无实例（或实例拿不到节点系统）时走
     * {@link NodeSystem#removeNodeEntryForWorld(File, String, int)}。</p>
     *
     * @param index {@link #nodes(String)} 返回列表中的序号
     * @return true 表示已删除；竞技场不存在、序号越界或写盘失败返回 false
     */
    public boolean deleteNode(String arenaId, int index) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null || index < 0) return false;

        ArenaInstance live = liveInstance(definition.getId());
        NodeSystem nodeSystem = live == null ? null : live.getNodeSystem();
        if (nodeSystem != null) {
            List<Location> locations = nodeSystem.getAllNodeLocations();
            return index < locations.size() && nodeSystem.removeNode(locations.get(index));
        }
        return NodeSystem.removeNodeEntryForWorld(plugin.getDataFolder(), definition.getWorldName(), index);
    }

    /* ========== 游戏参数写入 ========== */

    /** {@link #updateGameParams} 的结果。给出分类而非 boolean，让 GUI 能对不同原因给出不同措辞。 */
    public enum ParamUpdate {
        /** 已写入磁盘；若该竞技场有活动实例，新数值也已推送给它 */
        OK,
        /** registry 中不存在该竞技场 */
        ARENA_NOT_FOUND,
        /** 参数不合法：人数需 &ge; 1、上限需 &ge; 下限、波数需 &ge; 1、间隔需 &ge; 1 */
        INVALID_VALUE,
        /** 该竞技场正在对局中；改人数会让「人数不足判负」在下一名玩家离开时误触发 */
        GAME_IN_PROGRESS,
        /** 本局已结束（VICTORY / DEFEAT / ENDING）但结算倒计时 / 地图回滚尚未走完 */
        COUNTDOWN_IN_PROGRESS,
        /** 写盘失败（IO 错误）。磁盘内容未改变 */
        WRITE_FAILED
    }

    /**
     * 写入某个竞技场的 4 个游戏参数（{@code arenas.<id>.game.*}）。
     *
     * <p>三步：校验 → 写盘并重建 registry（{@link ArenaManager#updateGameParams}）→
     * 推送给活动实例（{@link ArenaInstance#applyGameParams}）。</p>
     *
     * <p><b>为什么必须推送实例</b>：{@code ArenaInstance.definition} 是构造时固化的副本，
     * {@code ArenaManager.load()} 重建的只是 registry 里的对象，活动实例读不到新值。
     * 只写盘会让参数「重启后才生效」，而 GUI 上却显示已改——正是要避免的那类静默失效。
     * 推送只替换 4 个数值，坐标类字段不动，因此不会与 {@code AdminDeployManager} 的生成点副本错位。</p>
     *
     * <p><b>对局中拒绝</b>：{@code min-players} 参与 {@code GameManager.removeOfflinePlayer} 的
     * 「人数不足判负」判定，中途调高会让下一名玩家退出时直接判负；{@code max-players} 影响
     * {@code ArenaInstance.isFull()}。这些都不该在开局后被改写，因此要求先 {@code /hilltop stop}。</p>
     *
     * <p><b>结算倒计时中同样拒绝</b>：{@code VICTORY / DEFEAT / ENDING} 时本局战报尚未播报完毕，
     * {@code GameManager.totalWaves} 还是开局时的快照，而 {@code ArenaScoreboard} 读的是实例定义的
     * 实时值——此间改「通关波数」会让记分板与本局战报口径不一致。</p>
     */
    public ParamUpdate updateGameParams(String arenaId, int minPlayers, int maxPlayers,
                                        int victoryWaves, int waveIntervalSeconds) {
        ArenaDefinition definition = resolve(arenaId);
        if (definition == null) return ParamUpdate.ARENA_NOT_FOUND;

        ArenaInstance live = liveInstance(definition.getId());
        if (live != null) {
            GameState state = live.getState();
            // 对局进行中：参数会被本局的存活 / 满员判定读到，开局后不得改写
            if (state == GameState.STARTING || state == GameState.RUNNING
                    || state == GameState.WAVE_INTERMISSION) {
                return ParamUpdate.GAME_IN_PROGRESS;
            }
            // 本局已结束、但结算倒计时或地图回滚还在走：本局战报与记分板的取值口径尚未对齐
            if (state == GameState.VICTORY || state == GameState.DEFEAT || state == GameState.ENDING) {
                return ParamUpdate.COUNTDOWN_IN_PROGRESS;
            }
        }

        // 先自行校验：ArenaManager.updateGameParams 对「值非法」与「写盘失败」都返回 false，
        // 提前判一次才能把两者区分开（共用 ArenaManager 的判定，不重复实现一份规则）
        if (!ArenaManager.validGameParams(minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds)) {
            return ParamUpdate.INVALID_VALUE;
        }

        if (!plugin.getMultiArenaManager().getDefinitionManager().updateGameParams(
                definition.getId(), minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds)) {
            return ParamUpdate.WRITE_FAILED;
        }

        // 写盘成功后必须让各竞技场的 AdminDeployManager 重读 arenas.yml：它们各自缓存了一份整份副本
        // （savedSpawnPoints / savedCoreLocation 等），不刷新会出现「GUI 显示 3 个生成点、实际刷 2 个」。
        // 与删除路径 saveArenas 的处理保持对称。
        plugin.getMultiArenaManager().reloadAllDeploymentData();

        if (live != null) {
            live.applyGameParams(minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds);
        }
        return ParamUpdate.OK;
    }

    /* ========== 视图构建 ========== */

    private ArenaView buildView(ArenaDefinition definition) {
        String arenaId = definition.getId();
        ArenaInstance live = liveInstance(arenaId);
        String worldName = definition.getWorldName();
        World world = Bukkit.getWorld(worldName);

        boolean deployed;
        boolean shopNpcSet;
        int spawnCount;
        int mobSpawnCount;
        int villagerCount;
        int nodeCount;

        AdminDeployManager deploy = live == null ? null : deployManager(live);
        if (live != null && deploy != null) {
            GameManager gameManager = live.getGameManager();
            spawnCount = deploy.getSpawnPoints().size();
            mobSpawnCount = deploy.getMobSpawnPoints().size();
            villagerCount = deploy.getVillagers().size();
            shopNpcSet = deploy.getShopNpcLocation() != null;
            deployed = gameManager != null && gameManager.hasSpawnPoints();
        } else {
            YamlConfiguration yaml = loadArenas();
            spawnCount = readYamlLocations(yaml, arenaPath(arenaId, SPAWN_POINTS_KEY), world).size();
            mobSpawnCount = readYamlLocations(yaml, arenaPath(arenaId, MOB_SPAWN_POINTS_KEY), world).size();
            villagerCount = readYamlLocations(yaml, arenaPath(arenaId, VILLAGERS_KEY), world).size();
            shopNpcSet = readCoordinates(yaml.getConfigurationSection(arenaPath(arenaId, SHOP_NPC_KEY)), world) != null;
            // 注意：deployed 只看生成点，与 hasCore 是两件事——重置后的竞技场两者都可能为 false
            deployed = spawnCount > 0;
        }

        NodeSystem nodeSystem = live == null ? null : live.getNodeSystem();
        nodeCount = nodeSystem != null
                ? nodeSystem.getAllNodeLocations().size()
                : NodeSystem.readNodesForWorld(plugin.getDataFolder(), worldName).size();

        String displayName = definition.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = arenaId;  // 未配置 display-name 时回退 ID，保证 GUI 侧不为 null
        }

        return new ArenaView(
                arenaId,
                displayName,
                definition.isEnabled(),
                plugin.getMultiArenaManager().getStatus(arenaId),
                worldName,
                world != null,
                definition.getCore() != null,
                deployed,
                spawnCount,
                mobSpawnCount,
                villagerCount,
                nodeCount,
                shopNpcSet,
                definition.getMinPlayers(),
                definition.getMaxPlayers(),
                definition.getVictoryWaves(),
                definition.getWaveIntervalSeconds(),
                live != null,
                live != null ? live.getPlayerCount() : 0,
                live != null ? live.getState() : null);
    }

    /* ========== 数据来源：registry / 实例 ========== */

    private ArenaRegistry registry() {
        return plugin.getMultiArenaManager().getDefinitionManager().getRegistry();
    }

    /**
     * 解析竞技场定义（大小写不敏感，与 {@link ArenaRegistry#get(String)} 一致）。
     * 必须先判空/空白：registry 对空白 ID 会抛 {@link IllegalArgumentException} 而不是返回 null。
     */
    private ArenaDefinition resolve(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return null;
        return registry().get(arenaId);
    }

    /** 运行时实例；未启用 / 已禁用的竞技场返回 null。 */
    private ArenaInstance liveInstance(String arenaId) {
        return plugin.getMultiArenaManager().getArena(arenaId);
    }

    private static AdminDeployManager deployManager(ArenaInstance arena) {
        GameManager gameManager = arena.getGameManager();
        return gameManager == null ? null : gameManager.getAdminDeployManager();
    }

    /** 只读快照：拷贝一份并包成不可修改列表，避免调用方改到管理器内部状态。 */
    private static List<Location> immutableCopy(List<Location> locations) {
        return Collections.unmodifiableList(new ArrayList<>(locations));
    }

    /* ========== 数据来源：arenas.yml（无实例时的兜底） ========== */

    private static String arenaPath(String arenaId, String key) {
        return "arenas." + arenaId + "." + key;
    }

    /** 每次调用都重新读盘（不使用可能陈旧的整份内存副本）。 */
    private YamlConfiguration loadArenas() {
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 读取 arenas.yml 里的一组坐标，返回顺序与 {@link #deleteYamlEntry} 的序号口径严格一致。
     *
     * <p>兼容两种写法：</p>
     * <ul>
     *   <li>列表写法 {@code spawn-points: [{x,y,z}, ...]} —— {@link AdminDeployManager} 与出厂
     *       arenas.yml 用的形式，优先识别；</li>
     *   <li>索引键写法 {@code spawn-points.0.x: ...} —— {@code ArenaManager#writeDefinition} 的形式，
     *       仅在列表写法读不到内容时回退识别（键按数字升序）。</li>
     * </ul>
     *
     * <p>坐标缺失或非法的条目会被跳过（不返回 null 元素）。</p>
     *
     * @param world 位置所属世界；世界未加载时传 null（坐标照常返回）
     */
    private static List<Location> readYamlLocations(YamlConfiguration yaml, String path, World world) {
        List<Location> locations = new ArrayList<>();

        List<Map<?, ?>> list = yaml.getMapList(path);
        if (!list.isEmpty()) {
            for (Map<?, ?> entry : list) {
                Location location = readCoordinates(entry, world);
                if (location != null) locations.add(location);
            }
            return locations;
        }

        for (String key : yamlIndexKeys(yaml, path)) {
            Location location = readCoordinates(yaml.getConfigurationSection(path + "." + key), world);
            if (location != null) locations.add(location);
        }
        return locations;
    }

    /**
     * 删除 arenas.yml 中某个列表的第 index 条（<b>按坐标合法的条目过滤后的序号</b>，
     * 与 {@link #readYamlLocations} 的返回顺序对齐），写盘成功返回 true。
     *
     * <p>写入前重新 {@code loadConfiguration}：{@code AdminDeployManager} 可能在此期间写过
     * arenas.yml，用陈旧的整份内存副本覆写会丢掉它的改动。</p>
     */
    private boolean deleteYamlEntry(String arenaId, String key, int index) {
        if (index < 0) return false;

        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) return false;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = arenaPath(arenaId, key);

        // ① 列表写法
        List<Map<?, ?>> raw = yaml.getMapList(path);
        if (!raw.isEmpty()) {
            List<Map<String, Object>> kept = new ArrayList<>(raw.size());
            int validIndex = 0;
            boolean removed = false;
            for (Map<?, ?> entry : raw) {
                // 只对坐标合法的条目递增序号：读取时跳过的损坏条目不应占用 GUI 的序号
                if (!removed && readCoordinates(entry, null) != null) {
                    if (validIndex == index) {
                        removed = true;
                        continue;
                    }
                    validIndex++;
                }
                kept.add(copyEntry(entry));
            }
            if (!removed) return false;
            yaml.set(path, kept);
            return saveArenas(yaml, file, path);
        }

        // ② 索引键写法（键按数字升序，与 readYamlLocations 的回退分支同一顺序）
        int validIndex = 0;
        for (String indexKey : yamlIndexKeys(yaml, path)) {
            if (readCoordinates(yaml.getConfigurationSection(path + "." + indexKey), null) == null) continue;
            if (validIndex == index) {
                yaml.set(path + "." + indexKey, null);
                return saveArenas(yaml, file, path);
            }
            validIndex++;
        }
        return false;
    }

    /** 索引键写法下的子键，按数字升序（非数字键排最后，与读取分支顺序一致）。 */
    private static List<String> yamlIndexKeys(YamlConfiguration yaml, String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) return List.of();
        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.sort(Comparator.comparingInt(ArenaAdminService::indexKeyOrder));
        return keys;
    }

    private static int indexKeyOrder(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean saveArenas(YamlConfiguration yaml, File file, String path) {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("写入 arenas.yml 失败（" + path + "）: " + exception.getMessage());
            return false;
        }
        // 本类改的是磁盘，而每个已启用竞技场的 AdminDeployManager 在构造时缓存了整份 arenas.yml 副本，
        // 下次部署时会拿那份旧副本回写——把这里刚删掉的条目整条复活。删除成功后必须让它们重读。
        plugin.getMultiArenaManager().reloadAllDeploymentData();
        return true;
    }

    /** 复制一条 YAML 记录（保留未知键），用于整份重写时原样保留其它条目。 */
    private static Map<String, Object> copyEntry(Map<?, ?> entry) {
        Map<String, Object> copy = new LinkedHashMap<>();
        entry.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /* ========== 坐标解析 ========== */

    private static Location readCoordinates(Map<?, ?> map, World world) {
        if (map == null) return null;
        return buildLocation(map.get("x"), map.get("y"), map.get("z"), world);
    }

    private static Location readCoordinates(ConfigurationSection section, World world) {
        if (section == null) return null;
        // 必须用 isSet 判断：getDouble 对缺失的键返回 0.0，会把损坏的节点当成 (0, 0, 0)
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) return null;
        return buildLocation(section.get("x"), section.get("y"), section.get("z"), world);
    }

    /** 坐标合法时构造 Location；world 为 null 表示世界未加载（坐标依旧有效）。 */
    private static Location buildLocation(Object x, Object y, Object z, World world) {
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;
        double dx = ((Number) x).doubleValue();
        double dy = ((Number) y).doubleValue();
        double dz = ((Number) z).doubleValue();
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return null;
        return new Location(world, dx, dy, dz);
    }
}
