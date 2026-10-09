package com.hilltopvillage.core;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.util.DisplayEntityManager;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    private final HilltopVillagePlugin plugin;
    private final String arenaId;
    private GameState state;
    private int currentWave;
    private int totalWaves;
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap;
    private final Set<UUID> players;
    private final WaveManager waveManager;
    private final NodeSystem nodeSystem;
    private final DisplayEntityManager displayEntityManager;
    private final AdminDeployManager adminDeployManager;
    private final ConfigManager configManager;
    private Location coreLocation;
    private World gameWorld;
    private BukkitTask waveCheckTask;
    /** 休整期「排队下一波」的延迟任务句柄（取消见 {@link #cancelWaveScheduler()}） */
    private BukkitTask waveSchedulerTask;
    private List<Location> arenaSpawnPoints;
    /**
     * 怪物刷怪点（STORY-1.2：与玩家出生点分离的第二套坐标）。
     *
     * <p>向后兼容：旧存档的 {@code mob-spawn-points} 为空时，刷怪回退到玩家出生点
     * （{@link #getMobSpawnPointsView()} 内部处理），因此本字段为空<b>不</b>代表刷不了怪。</p>
     */
    private List<Location> arenaMobSpawnPoints;
    /** 游戏自然结束（胜利/失败）时的回调，由 ArenaInstance 注入用于状态机同步与统计结算 */
    private java.util.function.Consumer<Boolean> onGameEndCallback;

    /**
     * 设置游戏自然结束回调（ArenaInstance 用于同步竞技场状态机与统计结算）
     * @param callback 参数为 true 表示胜利，false 表示失败
     */
    public void setOnGameEndCallback(java.util.function.Consumer<Boolean> callback) {
        this.onGameEndCallback = callback;
    }

    /**
     * 多竞技场构造函数（每个竞技场实例拥有独立的 GameManager）
     * @param plugin 插件实例
     * @param arenaId 竞技场 ID
     * @param world 竞技场世界
     * @param coreLocation 核心位置
     * @param spawnPoints 生成点列表
     */
    public GameManager(HilltopVillagePlugin plugin, String arenaId, World world, Location coreLocation, List<Location> spawnPoints) {
        this(plugin, arenaId, world, coreLocation, spawnPoints, List.of());
    }

    /**
     * 多竞技场构造函数（带怪物刷怪点）。
     *
     * @param plugin 插件实例
     * @param arenaId 竞技场 ID
     * @param world 竞技场世界
     * @param coreLocation 核心位置
     * @param spawnPoints 玩家出生点列表
     * @param mobSpawnPoints 怪物刷怪点列表（null/空 = 未部署，刷怪回退到玩家出生点）
     */
    public GameManager(HilltopVillagePlugin plugin, String arenaId, World world, Location coreLocation,
                       List<Location> spawnPoints, List<Location> mobSpawnPoints) {
        this.plugin = plugin;
        this.arenaId = arenaId;
        this.arenaSpawnPoints = new ArrayList<>(spawnPoints == null ? List.of() : spawnPoints);
        this.arenaMobSpawnPoints = new ArrayList<>(mobSpawnPoints == null ? List.of() : mobSpawnPoints);
        this.state = GameState.WAITING;
        this.currentWave = 0;
        this.playerDataMap = new ConcurrentHashMap<>();
        this.players = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // 多竞技场模式：使用传入的参数
        this.gameWorld = world;
        this.coreLocation = coreLocation;

        // configManager 必须在 totalWaves 赋值前初始化，确保从 GameConfig 读取。
        // 使用插件级共享单例（而非 new ConfigManager(plugin)）：否则每个竞技场会持有
        // 一份独立的 GameConfig 快照，导致 GUI / reloadconfig 的修改对 NodeSystem、
        // WaveManager 等读不到（须重启才生效），且与 SetupWizard 的 saveToFile() 写入
        // 不同实例、互相覆盖。GameConfig 是只读快照，共享无副作用。
        this.configManager = plugin.getConfigManager();
        this.totalWaves = configManager.getActiveConfig().getVictoryWaves();
        this.waveManager = new WaveManager(this);
        this.nodeSystem = new NodeSystem(this);
        this.displayEntityManager = new DisplayEntityManager(plugin);
        this.adminDeployManager = new AdminDeployManager(plugin, this);
    }

    /**
     * 获取竞技场 ID
     */
    public String getArenaId() {
        return arenaId;
    }

    /**
     * 本竞技场的人数下限。
     *
     * <p>以该竞技场自己在 arenas.yml 里的 {@code game.min-players} 为准
     * （即 {@link ArenaInstance#getMinPlayers()}）——玩家可能有意把某个竞技场配成单人开局，
     * 全局 game-settings.yml 的 min-players 只作为拿不到竞技场实例时的兜底。
     * 两个消费者（{@code ArenaInstance} 的自动开局判定与这里的开局校验/人数不足判负）
     * 必须取同一个值，否则会出现「竞技场认为已达标 → 进入 RUNNING，
     * 但 GameManager 因人数不足直接 return，既不刷怪也不走波次」的卡死对局。</p>
     *
     * <p>刻意<b>不缓存到字段</b>：GameManager 在本竞技场 {@code ArenaInstance} 的构造期间创建，
     * 那时实例尚未注册进 {@link MultiArenaManager}，缓存会把兜底值固化下来。
     * 两处调用（开局校验、人数不足判负）都不在热路径上，实时查询开销可忽略。</p>
     */
    private int getArenaMinPlayers() {
        ArenaInstance arena = liveArena();
        return arena != null ? arena.getMinPlayers() : configManager.getActiveConfig().getMinPlayers();
    }

    /**
     * 本竞技场的通关波数。
     *
     * <p>与 {@link #getArenaMinPlayers()} 同理：per-arena 值优先，拿不到实例时回落到全局
     * {@code game-settings.yml}。之所以要实时取，是因为 {@code ArenaInstance.getTotalWaves()}
     * 已把 {@code arenas.<id>.game.victory-waves} 显示在记分板上——若这里仍读全局值，
     * 就会出现「记分板写 20 波、实际第 10 波就结算」的显示与逻辑不一致。</p>
     */
    private int getArenaVictoryWaves() {
        ArenaInstance arena = liveArena();
        return arena != null ? arena.getTotalWaves() : configManager.getActiveConfig().getVictoryWaves();
    }

    /** 本竞技场的波次间隔秒数；per-arena 值优先，拿不到实例时回落全局值。 */
    private int getArenaWaveIntervalSeconds() {
        ArenaInstance arena = liveArena();
        return arena != null ? arena.getWaveIntervalSeconds() : configManager.getActiveConfig().getWaveIntervalSeconds();
    }

    /**
     * 本竞技场对应的活动实例；未启用（或正被启用队列加载）时返回 null。
     *
     * <p>刻意<b>不缓存</b>：GameManager 在本竞技场 {@code ArenaInstance} 的构造期间创建，
     * 那时实例尚未注册进 {@link MultiArenaManager}，缓存会把 null 或全局兜底值固化下来。</p>
     */
    private ArenaInstance liveArena() {
        MultiArenaManager multiArenaManager = plugin.getMultiArenaManager();
        return multiArenaManager == null ? null : multiArenaManager.getArena(arenaId);
    }

    public void startGame() {
        if (state != GameState.WAITING) return;
        if (players.size() < getArenaMinPlayers()) return;

        // Check if spawn points are configured
        if (!hasSpawnPoints()) {
            plugin.getLogger().warning("Cannot start game: No spawn points configured!");
            // 走 i18n（每个玩家各自语言），与 ArenaSelectionGUI 的未部署提示保持同一套键
            broadcastI18n("arena-no-spawn-points");
            return;
        }

        // STORY-1.2 双保险：仅当两套点都为空时才拦截（旧存档双用回退的场不在此列，
        // 它们在 hasSpawnPoints() 已通过、hasEffectiveMobSpawnPoints() 同样通过）
        if (!hasEffectiveMobSpawnPoints()) {
            plugin.getLogger().warning("Cannot start game: No mob spawn points configured for arena " + arenaId + "!");
            broadcastI18n("arena-no-mob-spawns");
            return;
        }

        // 旧存档双用告警（每局一次）：spawn-points 非空但 mob-spawn-points 为空时，
        // 怪物与玩家将共用同一套坐标 —— 提醒服主补部署怪物刷怪点
        if (isLegacyDualUseSpawns()) {
            plugin.getLogger().warning("Arena " + arenaId + " uses legacy dual-use spawn points "
                    + "(mob-spawn-points not deployed): mobs will spawn at player spawn locations. "
                    + "Please deploy dedicated mob spawn points with /hilltop deploy addmobspawn.");
        }

        state = GameState.STARTING;
        broadcastI18n("game-start");

        // 从配置文件加载节点（同步，无需回调）
        nodeSystem.loadNodesFromConfig();
        // 新一局开始：loadNodesFromConfig 为了让管理员 reload（/hilltop deploy reloadnodes）
        // 保留现场，会沿用同坐标节点的血量与 active 状态。这里是唯一真正的开局点
        // （ArenaInstance.onGameStart → startGame），因此在此显式重置，避免残血/已摧毁节点跨局残留。
        nodeSystem.resetAllNodes();
        nodeSystem.applyAllBuffs();
        state = GameState.RUNNING;
        currentWave = 0;
        // 每局开始时重读通关波数：竞技场此刻必然已注册进 MultiArenaManager，能拿到 per-arena 值，
        // 同时管理 GUI 在上一局结束后改过的参数也会在此生效（构造函数那次赋值只是未开局时的兜底）
        this.totalWaves = getArenaVictoryWaves();
        startNextWave();
    }

    public void startNextWave() {
        // 入口先作废排队句柄：上一波结束时的排队若未被中断（如结算打断），
        // 本调用与残留回调会各自推进一次，形成双开波次。
        cancelWaveScheduler();

        if (state == GameState.DEFEAT || state == GameState.VICTORY) return;

        currentWave++;
        if (currentWave > totalWaves) {
            endGame(true);
            return;
        }

        state = GameState.RUNNING;

        broadcastI18n("wave-start", currentWave);

        // 波次刷新时复活阵亡玩家（需要至少一个活跃节点）
        respawnDeadPlayers();

        waveManager.spawnWave(currentWave);

        // Cancel any leftover check task from a previous wave before starting a new one
        if (waveCheckTask != null) {
            waveCheckTask.cancel();
            waveCheckTask = null;
        }

        // Single repeating task: first fire after 5 s, then every 3 s (60 ticks)
        waveCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkWaveCompletion();
            }
        }.runTaskTimer(plugin, 100L, 60L);
    }

    /**
     * 波次刷新时复活阵亡玩家。
     *
     * <p>规则：<b>至少一个活跃节点</b>时，所有阵亡玩家直接在核心重生（无倒计时、无无敌时间）；
     * 无活跃节点时保持旁观，等待节点被修复后的下一波。</p>
     */
    public void respawnDeadPlayers() {
        boolean hasActiveNode = nodeSystem.getActiveCount() > 0;
        Location core = coreLocation;

        for (UUID uuid : players) {
            PlayerData data = playerDataMap.get(uuid);
            if (data == null || data.isAlive()) continue;

            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            if (!hasActiveNode) {
                plugin.getLanguageManager().send(player, "respawn-blocked-no-node");
                continue;
            }

            data.setAlive(true);
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            if (core != null) {
                player.teleport(core);
            }
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setFireTicks(0);
            // 退出旁观状态
            ArenaInstance arena = plugin.getMultiArenaManager().getArena(arenaId);
            if (arena != null) arena.setSpectator(uuid, false);
            plugin.getLanguageManager().send(player, "respawned");
        }
    }

    /**
     * 当前是否有存活玩家（供全灭判定使用）。
     */
    public boolean hasAlivePlayer() {
        for (UUID uuid : players) {
            PlayerData data = playerDataMap.get(uuid);
            if (data != null && data.isAlive()) return true;
        }
        return false;
    }

    public void checkWaveCompletion() {
        if (state != GameState.RUNNING && state != GameState.WAVE_INTERMISSION) {
            // Game ended by other means — stop the repeating task
            if (waveCheckTask != null) {
                waveCheckTask.cancel();
                waveCheckTask = null;
            }
            return;
        }

        if (waveManager.isWaveCleared()) {
            // Wave done — cancel task before proceeding
            if (waveCheckTask != null) {
                waveCheckTask.cancel();
                waveCheckTask = null;
            }
            onWaveComplete();
        }
        // else: wave still in progress, task will fire again in 60 ticks
    }

    private void onWaveComplete() {
        broadcastI18n("wave-complete", currentWave);

        state = GameState.WAVE_INTERMISSION;

        // 排队前先作废旧句柄：本方法可能被重复调用，重复排队会双开波次
        cancelWaveScheduler();

        // 实时读取：管理 GUI 改了波次间隔后，无需等到下一局，从本次休整期起即按新值倒计时
        int intervalSeconds = getArenaWaveIntervalSeconds();
        waveSchedulerTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 任务已触发，句柄失效（startNextWave 的入口取消因此是空操作）
                waveSchedulerTask = null;
                // 保留既有语义：只有仍处于休整期才推进下一波
                if (state == GameState.WAVE_INTERMISSION) {
                    startNextWave();
                }
            }
        }.runTaskLater(plugin, 20L * intervalSeconds);
    }

    /**
     * 作废尚未触发的「排队下一波」任务（幂等，不改变 {@link GameState}）。
     *
     * <p>该排队回调只检查状态是否仍为 {@link GameState#WAVE_INTERMISSION}，而
     * {@link #haltGame()} <b>刻意不改变状态</b>（结算期间玩家需留在场上查看战报）。
     * 于是休整期内触发失败判定时 {@code haltGame()} 停不掉这个回调，倒计时期间
     * 它仍会命中并 {@code startNextWave() → spawnWave()}，重新刷出一批怪物
     * （随后被 {@code finishSession → stopGame → clearAllMobs} 清掉，属短时异常但可观测）。</p>
     *
     * <p>因此所有中断路径（{@link #haltGame()}、{@link #stopGame()}、{@link #endGame(boolean)}）
     * 与 {@link #startNextWave()} 入口都必须调用本方法。</p>
     */
    private void cancelWaveScheduler() {
        if (waveSchedulerTask != null) {
            waveSchedulerTask.cancel();
            waveSchedulerTask = null;
        }
    }

    public void endGame(boolean victory) {
        state = victory ? GameState.VICTORY : GameState.DEFEAT;
        broadcastI18n(victory ? "game-victory" : "game-defeat");

        if (waveCheckTask != null) {
            waveCheckTask.cancel();
            waveCheckTask = null;
        }

        // 若在休整期内自然结束（如人数不足判负），残留的排队回调会重新刷怪
        cancelWaveScheduler();

        waveManager.clearAllMobs();

        playerDataMap.values().forEach(PlayerData::deactivateSmash);

        // 通知 ArenaInstance 同步状态机与统计结算（胜利/失败后进入清理阶段）
        if (onGameEndCallback != null) {
            onGameEndCallback.accept(victory);
        }
    }

    public void stopGame() {
        state = GameState.WAITING;
        currentWave = 0;
        if (waveCheckTask != null) {
            waveCheckTask.cancel();
            waveCheckTask = null;
        }
        cancelWaveScheduler();
        // 节点系统改为配置文件加载，无需取消任务
        nodeSystem.stopBuffTask();
        waveManager.clearAllMobs();
        playerDataMap.values().forEach(PlayerData::deactivateSmash);
    }

    /**
     * 清空本局玩家集合与对应的 {@link PlayerData}（跨局清理）。
     *
     * <p>供竞技场重置路径调用：处于掉线重连窗口的玩家不会经过
     * {@code ArenaInstance.removePlayer(Player)}，若只在 GameManager 外部清集合，
     * 这些离线玩家会跨局残留，污染开局人数校验、刷怪数量与存活判定。</p>
     *
     * <p>本方法<b>不做</b>统计结算，也不触发人数不足的 {@link #endGame(boolean)} 判定
     * （因此不会与重置流程互相重入）。本局统计的落库在
     * {@code ArenaInstance.removePlayer(Player)} 中完成，调用方应先走完对局收尾流程再调用本方法。</p>
     */
    public void clearAllPlayers() {
        players.clear();
        playerDataMap.values().forEach(PlayerData::deactivateSmash);
        playerDataMap.clear();
    }

    /**
     * 停止波次调度与清除怪物，但<b>不改变 GameManager 状态</b>。
     * 供失败/胜利结算使用（玩家需要留在场上查看统计）。
     */
    public void haltGame() {
        if (waveCheckTask != null) {
            waveCheckTask.cancel();
            waveCheckTask = null;
        }
        // 本方法刻意不改状态（仍可能是 WAVE_INTERMISSION），排队回调因此不会被状态拦住，
        // 必须显式取消，否则结算倒计时期间会重新刷怪并播报 wave-start
        cancelWaveScheduler();
        waveManager.clearAllMobs();
    }

    public void addPlayer(Player player) {
        players.add(player.getUniqueId());
        playerDataMap.computeIfAbsent(player.getUniqueId(), PlayerData::new);
    }

    public void removePlayer(Player player) {
        removeOfflinePlayer(player.getUniqueId());
    }

    /**
     * 按 UUID 移除玩家（玩家可能已离线，不做任何实体操作）。
     * 供重连超时清理使用。
     */
    public void removeOfflinePlayer(UUID uuid) {
        players.remove(uuid);
        PlayerData data = playerDataMap.remove(uuid);
        if (data != null) {
            data.deactivateSmash();
        }

        if (players.size() < getArenaMinPlayers() && state == GameState.RUNNING) {
            endGame(false);
        }
    }

    /**
     * 向所有游戏内玩家发送 i18n 消息，每个玩家使用其各自的语言偏好。
     *
     * @param key  messages.yml 中的消息键
     * @param args 占位符参数 {0}, {1}, ...
     */
    public void broadcastI18n(String key, Object... args) {
        LanguageManager lm = plugin.getLanguageManager();
        for (UUID playerId : players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(lm.getFor(player, key, args));
            }
        }
    }

    public PlayerData getPlayerData(UUID playerId) {
        return playerDataMap.get(playerId);
    }

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.get(player.getUniqueId());
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentWave() {
        return currentWave;
    }

    public int getTotalWaves() {
        return totalWaves;
    }

    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    /** 当前等待/参与游戏的玩家数（供命令层做 start 前的人数校验提示） */
    public int getPlayerCount() {
        return players.size();
    }

    public WaveManager getWaveManager() {
        return waveManager;
    }

    public NodeSystem getNodeSystem() {
        return nodeSystem;
    }

    public DisplayEntityManager getDisplayEntityManager() {
        return displayEntityManager;
    }

    public AdminDeployManager getAdminDeployManager() {
        return adminDeployManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public Location getCoreLocation() {
        return coreLocation.clone();
    }

    public World getGameWorld() {
        return gameWorld;
    }

    public HilltopVillagePlugin getPlugin() {
        return plugin;
    }

    public boolean isGameRunning() {
        return state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION;
    }

    /**
     * 获取生成点列表（防御性拷贝）
     */
    public List<Location> getSpawnPoints() {
        return new ArrayList<>(arenaSpawnPoints);
    }

    /**
     * 生成点列表的<b>只读视图</b>（不拷贝列表，也不克隆其中的 {@link Location}）。
     *
     * <p>供「每只怪生成时都要读一次」的热路径使用：{@link #getSpawnPoints()} 每次都
     * {@code new ArrayList<>(...)}，刷一波 80 只怪就要拷 80 次小数组。本方法只包一层
     * {@code unmodifiableList}，调用方只能读，改不动内部列表。</p>
     *
     * <p><b>使用约束：</b>返回的视图绑定在<b>当前</b>的 {@code arenaSpawnPoints} 实例上。
     * {@link #setSpawnPoints(List)} 会整体替换该字段，因此视图<b>不可跨部署改动缓存</b>，
     * 每次需要时重新调用本方法即可（热路径调用方正是这样做的）。</p>
     *
     * <p><b>元素安全：</b>视图中的 {@link Location} 仍是内部对象本体，调用方若直接
     * {@code add}/{@code setWorld} 会改到内部状态。当前唯一的热路径调用方
     * {@code WaveManager.getRandomSpawnLocation()} 取出元素后立刻 {@code clone()}
     * 再对副本做扩散偏移，不会改到内部对象，因此使用视图是安全的。</p>
     */
    public List<Location> getSpawnPointsView() {
        return Collections.unmodifiableList(arenaSpawnPoints);
    }

    /**
     * 检查是否有配置的生成点
     */
    public boolean hasSpawnPoints() {
        return !arenaSpawnPoints.isEmpty();
    }

    /**
     * 是否配置了<b>独立的</b>怪物刷怪点（{@code mob-spawn-points} 非空）。
     *
     * <p>注意：本方法只反映新 schema 的部署状态，旧存档双用回退的场返回 false。
     * 开局拦截判定请用 {@link #hasEffectiveMobSpawnPoints()}。</p>
     */
    public boolean hasMobSpawnPoints() {
        return !arenaMobSpawnPoints.isEmpty();
    }

    /**
     * 是否有<b>生效的</b>怪物刷怪坐标（新部署的独立刷怪点，或旧存档的双用回退）。
     *
     * <p>开局拦截的唯一依据：仅当两套点都为空时返回 false。</p>
     */
    public boolean hasEffectiveMobSpawnPoints() {
        return !arenaMobSpawnPoints.isEmpty() || !arenaSpawnPoints.isEmpty();
    }

    /**
     * 是否处于「旧存档双用」状态：玩家出生点非空、但独立怪物刷怪点为空。
     *
     * <p>此时怪物与玩家共用 spawn-points，开局不拦截，但记 warning 日志提醒服主补部署。</p>
     */
    public boolean isLegacyDualUseSpawns() {
        return arenaMobSpawnPoints.isEmpty() && !arenaSpawnPoints.isEmpty();
    }

    /**
     * 怪物刷怪点列表的防御性拷贝（不含双用回退：只返回独立部署的 {@code mob-spawn-points}）。
     */
    public List<Location> getMobSpawnPoints() {
        return new ArrayList<>(arenaMobSpawnPoints);
    }

    /**
     * 怪物刷怪坐标的<b>只读视图</b>（含双用回退：独立刷怪点为空时回退到玩家出生点）。
     *
     * <p>供 {@code WaveManager.getRandomSpawnLocation()} 热路径使用：与
     * {@link #getSpawnPointsView()} 相同的只读视图约定（不可跨部署改动缓存，
     * 取出元素后必须 {@code clone()} 再改）。</p>
     *
     * <p>视图可能绑定在 {@code arenaMobSpawnPoints} 或 {@code arenaSpawnPoints} 上，
     * 两者在 {@code set*} 方法中都可能被整体替换，因此调用方每次需要时重新调用本方法。</p>
     */
    public List<Location> getMobSpawnPointsView() {
        List<Location> effective = arenaMobSpawnPoints.isEmpty() ? arenaSpawnPoints : arenaMobSpawnPoints;
        return Collections.unmodifiableList(effective);
    }

    /**
     * 部署导更新生成点后刷新内存列表
     */
    public void setSpawnPoints(List<Location> spawnPoints) {
        this.arenaSpawnPoints = new ArrayList<>(spawnPoints == null ? List.of() : spawnPoints);
    }

    /**
     * 部署命令/向导更新怪物刷怪点后刷新内存列表
     */
    public void setMobSpawnPoints(List<Location> mobSpawnPoints) {
        this.arenaMobSpawnPoints = new ArrayList<>(mobSpawnPoints == null ? List.of() : mobSpawnPoints);
    }

    /**
     * 部署向导更新核心位置后刷新内存
     */
    public void setCoreLocation(Location location) {
        this.coreLocation = location != null ? location.clone() : null;
    }
}
