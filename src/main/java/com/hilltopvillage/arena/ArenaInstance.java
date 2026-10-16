package com.hilltopvillage.arena;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.mechanics.ArenaScoreboard;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.mechanics.VillagerSystem;
import com.hilltopvillage.util.LanguageManager;
import com.hilltopvillage.util.SafeSpawnUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arena instance - a single running game.
 * Inspired by BedWars1058's Arena class.
 *
 * <p>Each arena has its own:
 * - GameManager (wave management, game logic)
 * - NodeSystem (defense nodes)
 * - Player tracking
 * - State machine (WAITING → STARTING → RUNNING → ENDING)
 *
 * <p>竞技场<b>不</b>持有商店/NPC 生命周期：商店走插件级单例
 * （{@link HilltopVillagePlugin#getShopManager()}），由部署向导与服务器启停负责生成/清理。
 * 竞技场重置若去调用 NpcManager 的孤儿清理，会把大厅与所有竞技场的商店 NPC 一起删掉。</p>
 */
public class ArenaInstance implements IArena {

    private final HilltopVillagePlugin plugin;
    /**
     * 不可变定义；仅在管理员通过管理 GUI 改游戏参数时被整体替换（见 {@link #applyGameParams}），因此非 final。
     *
     * <p>{@code volatile}：替换只发生在 Bukkit 主线程（GUI 点击 / 聊天输入经 {@code runTask} 跳回主线程），
     * 当前并不需要同步；写上只是把「本字段可能被整体替换」这一约定固化进代码，
     * 让将来在异步任务里读 {@link #getTotalWaves()} / {@link #isEnabled()} 的人天然获得可见性保证。</p>
     *
     * <p><b>注意：</b>坐标类字段（core / spawn-points）刻意不从这里读——部署命令只刷新
     * {@link GameManager}，本字段是构造期快照（理由见 {@link #getCoreLocation()} 与 {@link #getSpawnPoints()}）。</p>
     */
    private volatile ArenaDefinition definition;
    private final String arenaId;

    // Game components (each arena has independent instances)
    private final GameManager gameManager;
    private final NodeSystem nodeSystem;
    private final VillagerSystem villagerSystem;
    private final ArenaScoreboard scoreboard;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;

    // Player tracking
    // ⚠️ 本类刻意<b>不</b>持有自己的 PlayerData 映射：所有增量写（击杀/阵亡/节点修复）都走
    // GameManager 的那份（见 HilltopVillagePlugin#onMobDeath、NodeSystem#repairNode、
    // PlayerLifecycleListener#onDeath、HammerListener），结算也必须读同一份。
    // 曾经这里有一份影子映射，只有在入队时写入一次、之后再无更新，导致统计里
    // kills / deaths / nodes-repaired 恒为 0。需要 PlayerData 一律走 gameManager。
    private final Set<UUID> players;
    private final Set<UUID> spectators;

    // State management
    private GameState state;
    private BukkitTask startCountdownTask;
    /** 结算倒计时任务句柄；中断结算（stop / reset / 关闭）时必须取消并置空 */
    private BukkitTask endSequenceTask;
    /**
     * 空置自动结束任务句柄（G3d）。
     *
     * <p>仅在 {@code config.empty-arena-end-seconds > 0} 且对局进行中
     * （STARTING / RUNNING / WAVE_INTERMISSION）时运行；状态离开进行中或配置关闭后
     * 任务下一 tick 自行退出（{@link #cancelEmptyArenaTask()} 兜底取消）。
     * 显式取消点：{@link #stopGame()}（对局终结）、{@link #resetNow()}（实例终结/插件关闭）。</p>
     */
    private BukkitTask emptyArenaTask;
    /** 已连续空置秒数（在线成员为 0 的持续时间），有人在场时清零 */
    private int emptyArenaSeconds;
    /** 本局代号：每局开始时自增，用于让上一局残留的结算回调静默失效（不依赖 cancel 是否成功） */
    private int sessionId;
    private int countdown;
    private boolean resetScheduled;
    private boolean restoreStarted;
    /** 本局结果：null = 未结束/进行中，true = 胜利，false = 失败（用于统计结算计胜负） */
    private Boolean sessionVictory;
    /**
     * 本局结束瞬间的波次快照（M-08）。
     *
     * <p>收尾链上 {@code GameManager.stopGame()} 会把 currentWave 归零，而 reset 里补录
     * 离线残留玩家统计（recordOffline 的 wavesSurvived）时已读不到真实波次；因此在
     * {@link #onGameEnd()}（归零前）与 {@link #reset()} 开头各用 {@code Math.max} 捕获一次
     * 快照（幂等，两处互补覆盖所有收尾路径），与在线玩家踢出时记录的波次取值一致。
     * 跨局在 completeResetAfterRestore / onGameStart 归零。</p>
     */
    private int sessionWave;

    // ⚠️ 本类刻意<b>不</b>持有自己的 currentWave：波次由 GameManager 推进，这里曾有一份
    // 只在构造与开局时被赋 0、之后永不更新的影子副本，导致传给 StatsManager 的 wave 恒为 0
    // （wavesSurvived / bestWave 对所有玩家永久为 0），以及 /hilltop arenas 永久显示 0/N。
    // 需要波次一律走 gameManager.getCurrentWave()。

    public ArenaInstance(HilltopVillagePlugin plugin, ArenaDefinition definition) {
        this.plugin = plugin;
        this.definition = definition;
        this.arenaId = definition.getId();

        // Initialize state
        this.state = GameState.WAITING;
        this.countdown = 0; // 实际值在 startCountdown() 中从 arenas.yml settings.start-countdown 读取

        // Initialize player tracking
        this.players = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.spectators = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // Initialize game components
        this.configManager = plugin.getConfigManager();
        this.languageManager = plugin.getLanguageManager();

        // Create GameManager for this arena (使用多竞技场构造函数)
        Location coreLocation = definition.getCore();
        List<Location> spawnPoints = definition.getSpawnPoints();
        List<Location> mobSpawnPoints = definition.getMobSpawnPoints();

        this.gameManager = new GameManager(
            plugin,
            arenaId,
            coreLocation.getWorld(),
            coreLocation,
            spawnPoints,
            mobSpawnPoints
        );
        this.nodeSystem = gameManager.getNodeSystem();

        // 待保护村民系统（全部阵亡 = 游戏失败）
        // 仅作为本竞技场的村民状态持有者；事件监听由 VillagerSystem.GlobalListener 全局注册一次，
        // 再按事件实体所在世界路由回本实例（本处不再 registerEvents，避免反复启用/禁用时监听器泄漏）
        this.villagerSystem = new VillagerSystem(plugin, gameManager);

        // 侧边栏记分板
        this.scoreboard = new ArenaScoreboard(plugin, this);

        // 波次系统自然结束（胜利/失败）时同步竞技场状态机
        this.gameManager.setOnGameEndCallback(this::onNaturalGameEnd);

        // P2-12：本世界刚加载（启用队列保证 createWorld 完成才构造实例），清扫崩溃/强杀
        // 残留的 persistent 波次怪（按 hilltopvillage:wave-mob PDC 标记识别，只扫本世界）。
        // 与 HilltopVillagePlugin.onEnable 的静态全服清扫互补：启动时竞技场世界往往
        // 尚未加载，主场景由这里兜底。
        this.gameManager.getWaveManager().cleanupOrphans();

        plugin.getLogger().info("Arena instance created: " + arenaId);
    }

    /**
     * GameManager 波次系统自然结束（胜利/失败）的回调。
     *
     * <p>结算提示已由 GameManager 广播（game-victory / game-defeat），因此这里不再重复广播。</p>
     */
    private void onNaturalGameEnd(boolean victory) {
        beginEndSequence(null, victory);
    }

    /**
     * 调度竞技场重置（幂等，重复调用只调度一次）。
     * 延迟取自 arenas.yml 的 {@code settings.arena-reset-delay}（秒），插件关闭时立即重置，不注册任务。
     */
    private void scheduleReset() {
        if (resetScheduled) return;
        resetScheduled = true;
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::reset, getConfiguredResetDelayTicks());
        } else {
            reset();
        }
    }

    /* ========== arenas.yml settings 段（全局设置） ========== */

    /** 游戏开始倒计时（秒），来自 arenas.yml 的 {@code settings.start-countdown}（缺省 10） */
    private int getConfiguredStartCountdown() {
        return plugin.getMultiArenaManager().getStartCountdownSeconds();
    }

    /** 竞技场重置延迟（tick），来自 arenas.yml 的 {@code settings.arena-reset-delay}（秒 × 20） */
    private long getConfiguredResetDelayTicks() {
        return plugin.getMultiArenaManager().getArenaResetDelayTicks();
    }

    // ========== IArena Implementation ==========

    @Override
    public String getArenaId() {
        return arenaId;
    }

    /**
     * 竞技场显示名（已翻译 & 颜色代码，可直接用于聊天/GUI）。
     *
     * <p>注意：{@link ArenaDefinition#getDisplayName()} 保持原始值不变，
     * 以确保写回 arenas.yml 时不会把已翻译的 § 颜色码存进配置文件。</p>
     */
    @Override
    public String getDisplayName() {
        String raw = definition.getDisplayName() != null
            ? definition.getDisplayName()
            : arenaId;
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    @Override
    public String getWorldName() {
        return definition.getWorldName();
    }

    @Override
    public GameState getState() {
        return state;
    }

    @Override
    public void setState(GameState newState) {
        // ENDING 同时是地图恢复屏障；只有恢复完成回调可以重新开放 WAITING。
        if (restoreStarted && newState != GameState.ENDING) {
            return;
        }

        GameState oldState = this.state;
        this.state = newState;

        // State transition logic
        if (oldState == GameState.WAITING && newState == GameState.STARTING) {
            startCountdown();
        } else if (newState == GameState.RUNNING) {
            onGameStart();
        } else if (newState == GameState.ENDING) {
            onGameEnd();
        }
    }

    @Override
    public List<Player> getPlayers() {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    @Override
    public boolean addPlayer(Player player) {
        // Check if arena is available
        if (!isEnabled()) {
            plugin.getLanguageManager().send(player, "arena-disabled");
            return false;
        }

        // ENDING/回滚期间是硬屏障，不能在任何检查前让玩家进入。
        if (state != GameState.WAITING && state != GameState.STARTING
                || restoreStarted
                || plugin.getMapRestoreManager().isRestoring(definition.getWorldName())) {
            plugin.getLanguageManager().send(player, "game-in-progress");
            return false;
        }

        // 地图存档缺失：任何人都不能进
        if (getWorld() == null) {
            plugin.getLanguageManager().send(player, "arena-map-missing");
            return false;
        }

        // 未部署：默认拒绝，管理员豁免（便于进图执行部署向导核对位置）
        if (!gameManager.hasSpawnPoints() && !player.hasPermission("hilltopvillage.admin")) {
            plugin.getLanguageManager().send(player, "arena-not-deployed", definition.getId());
            return false;
        }

        if (isFull()) {
            plugin.getLanguageManager().send(player, "arena-full");
            return false;
        }

        // Remove from old arena if exists
        ArenaInstance oldArena = plugin.getArenaManager().getPlayerArena(player);
        if (oldArena != null) {
            oldArena.removePlayer(player);
        }

        // 保存玩家原版状态（背包/经验/血量/模式…），离开时归还
        // P2-34：管理器初始化失败时为 null（插件未被禁用，但该功能停用）→ 跳过快照
        if (plugin.getPlayerGoodsManager() != null) {
            plugin.getPlayerGoodsManager().save(player);
        }

        // Add to this arena
        players.add(player.getUniqueId());
        // 同步到 GameManager：玩家集合与 PlayerData 都由它统一持有（本类不另存一份）
        gameManager.addPlayer(player);

        // Teleport to lobby
        Location lobbySpawn = getLobbySpawn();
        if (lobbySpawn != null) {
            player.teleport(lobbySpawn);
        }

        // Set gamemode and restore vitals
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);

        // 显示侧边栏记分板
        scoreboard.show(player);

        // Broadcast join message
        broadcastI18n("player-joined", player.getName(), getPlayerCount(), getMaxPlayers());

        // 未就绪（世界缺失 / 未部署）时给管理员一句提示。原文这里是 else-if，
        // 与开局分支互斥（本分支要求 !isPlayable()，开局分支要求 isPlayable()），
        // 因此拆成独立的 if 后语义不变：既不会「已达标仍提示」，也不会「不可用仍开局」。
        if (state == GameState.WAITING && !isPlayable() && player.hasPermission("hilltopvillage.admin")) {
            plugin.getLanguageManager().send(player, "arena-not-deployed-hint", definition.getId());
        }

        // 人数达标后自动开始倒计时
        // 与 BedWars1058 一致：只有「已就绪」的竞技场才自动开局
        // （BedWars 用显式 /bw enable 启用队列控制，我们用「已部署」等价）
        // 判定抽到 checkAutoStart：applyGameParams 也调用它，使「管理员调低人数下限」同样能立刻开局
        checkAutoStart();

        return true;
    }

    /**
     * 人数达标时进入开局倒计时。
     *
     * <p>由 {@link #addPlayer} 与 {@link #applyGameParams} 共用：后者让
     * 「管理员调低人数下限」也能立刻开局，而不是要等到下一名玩家加入。</p>
     *
     * <p>{@code startCountdownTask == null} 守卫防止重复进入倒计时：倒计时中的竞技场
     * 理论上状态必是 {@code STARTING}（会先被 {@code state == WAITING} 拦住），但这里不靠
     * 「状态机不变式必然成立」，而是直接对共享资源做幂等检查——即使将来有人改动状态迁移顺序，
     * 也不会出现第二个倒计时任务。详见 {@link #startCountdown()} 的赋值时机。</p>
     */
    private void checkAutoStart() {
        if (state == GameState.WAITING && isPlayable() && getPlayerCount() >= getMinPlayers()
                && startCountdownTask == null) {
            setState(GameState.STARTING);
        }
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (!players.contains(uuid)) {
            // 不在本竞技场也要清映射：历史路径可能已把玩家送回大厅却留下「玩家 → 竞技场」映射，
            // 那会让玩家在回大厅后仍被判为「在场内」（登录落地 / 大厅复活 / 退服重连窗口全走错分支）
            plugin.getMultiArenaManager().forgetPlayerArena(uuid);
            return; // Player not in this arena
        }

        // 结算本局统计（sessionVictory 为 null 表示中途离开，不计胜负）
        // 数据必须取自 GameManager 的那一份：增量写（击杀/阵亡/节点修复）全部打在它上面
        // 波次口径用「当前波次 与 sessionWave 快照取大」：局中离场取当前值（快照为 0），
        // 结算完成（stopGame 归零波次）后的残留离场取快照，避免记 0 波
        PlayerData sessionData = gameManager.getPlayerData(uuid);
        if (sessionData != null && plugin.getStatsManager() != null) {
            plugin.getStatsManager().record(player, sessionData, sessionVictory,
                    Math.max(gameManager.getCurrentWave(), sessionWave));
        }

        // Remove from tracking
        players.remove(uuid);
        spectators.remove(uuid);
        // 同步清除「玩家 → 竞技场」映射：批量踢人（stopGame / finishSession / resetNow）
        // 直接调本方法，不会经过 MultiArenaManager#leaveArena，不清就会留下幽灵映射
        plugin.getMultiArenaManager().forgetPlayerArena(uuid);
        // 同步从 GameManager 移除
        gameManager.removePlayer(player);

        // 移除侧边栏记分板
        scoreboard.hide(player);

        // 跨 lane（约定#1 / M-13）：清锤与烈焰蛋的玩家残留态。
        // cleanupPlayer 幂等（F3 负责保证）：清蓄力 BossBar、smash 状态与冷却，
        // 避免玩家在蓄力中离场后进度条永久糊屏、或带着幽灵蓄力态重进。
        plugin.getHammerListener().cleanupPlayer(player);
        plugin.getFireballListener().cleanupPlayer(player);

        // Teleport to lobby
        Location lobbySpawn = plugin.getMultiArenaManager().getLobbySpawn();
        player.teleport(lobbySpawn != null ? lobbySpawn : Bukkit.getWorlds().get(0).getSpawnLocation());

        // 归还玩家原版状态（背包/经验/血量/模式…）
        // P2-34：管理器初始化失败时为 null → 视同「无快照」，走下面的保底清理分支
        if (plugin.getPlayerGoodsManager() == null || !plugin.getPlayerGoodsManager().restore(player)) {
            // 无快照（异常情况）：退回生存模式并清空游戏物品
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
        }

        // Broadcast leave message
        if (state != GameState.ENDING) {
            broadcastI18n("player-left", player.getName(), getPlayerCount(), getMaxPlayers());
        }

        // Check if game should stop (not enough players)
        if (state == GameState.RUNNING && getPlayerCount() < 1) {
            broadcastI18n("game-ended-no-players");
            stopGame();
        }

        // Cancel countdown if not enough players
        if (state == GameState.STARTING && getPlayerCount() < getMinPlayers()) {
            cancelCountdown();
            setState(GameState.WAITING);
            broadcastI18n("countdown-cancelled");
        }
    }

    /**
     * 掉线玩家重连回竞技场。
     *
     * <p>存活状态 → 恢复到核心并以生存模式继续；
     * 阵亡等待复活状态 → 恢复到核心并以旁观模式继续等待波次刷新。</p>
     *
     * @return true 表示重连成功；false 表示该玩家已不在本竞技场或对局已结束
     */
    public boolean rejoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (!players.contains(uuid)) return false;

        // 对局已结束：交回调用方走正常离开流程
        if (state != GameState.RUNNING && state != GameState.WAVE_INTERMISSION
                && state != GameState.STARTING) {
            return false;
        }

        PlayerData data = gameManager.getPlayerData(uuid);
        boolean alive = data == null || data.isAlive();
        Location core = gameManager.getCoreLocation();

        // 跨 lane（约定#1）：重连时先清锤/蛋的残留玩家态（蓄力 BossBar / 蓄力标记 / 冷却）。
        // 退服时的 quit 清理已清过 BossBar，但 PlayerData 的蓄力标记可能仍在，若不清理，
        // 重连落地后第一步就会触发「幽灵猛击」。cleanupPlayer 幂等（F3 保证）；
        // 此处同时清 PlayerData 侧 smash 标记（M-18 rejoin 面），双保险。
        if (data != null) data.deactivateSmash();
        plugin.getHammerListener().cleanupPlayer(player);
        plugin.getFireballListener().cleanupPlayer(player);

        if (alive) {
            player.setGameMode(GameMode.SURVIVAL);
            if (core != null) player.teleport(core);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            spectators.remove(uuid);
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            if (core != null) player.teleport(core);
            spectators.add(uuid);
        }
        player.setFireTicks(0);

        broadcastI18n("player-rejoined", player.getName());
        plugin.getLanguageManager().send(player, "rejoin-welcome");

        // M-06：重连后恢复侧边栏。onPlayerQuit 的清理链已把记分板条目从内存摘除，
        // 不在这里重建的话玩家整局看不到侧边栏（波次/存活/击杀全丢）。
        // show 幂等：内部覆盖式重建 BoardState 并 setScoreboard，重复调用安全。
        scoreboard.show(player);
        return true;
    }

    /**
     * 移除离线玩家（重连超时）。
     *
     * <p>不做传送/背包操作（玩家不在线），物品快照保留在磁盘上，下次登录时自动归还。
     * 侧边栏记分板由 {@link ArenaScoreboard#hide} 清理：hide 内部对离线玩家只做内存条目摘除，
     * 不调 setScoreboard，因此这里传可能已离线的 Player 引用是安全的。</p>
     */
    public void removeOfflinePlayer(UUID uuid, String playerName) {
        if (!players.remove(uuid)) {
            // 同上：即使不在本竞技场，也要清掉可能残留的「玩家 → 竞技场」映射
            plugin.getMultiArenaManager().forgetPlayerArena(uuid);
            return;
        }
        spectators.remove(uuid);
        plugin.getMultiArenaManager().forgetPlayerArena(uuid);

        // 结算本局统计，必须在 gameManager.removeOfflinePlayer() <b>之前</b>取数：
        // 后者会 playerDataMap.remove(uuid)，之后再读就只能拿到一份全新的空 PlayerData
        // （kills / deaths / nodes-repaired 全为 0），等于该玩家这一局白打。
        // 掉线玩家拿不到在线 Player 对象，因此走按 UUID 的 recordOffline 重载。
        PlayerData sessionData = gameManager.getPlayerData(uuid);
        if (sessionData != null && plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordOffline(uuid, playerName, sessionData, sessionVictory,
                    Math.max(gameManager.getCurrentWave(), sessionWave));
        }

        gameManager.removeOfflinePlayer(uuid);

        // 清掉该玩家在本竞技场记分板上的残留条目：重连超时时玩家不在线，
        // hide 内部 isOnline 守卫会跳过 setScoreboard，只做内存摘除（安全）。
        Player offline = Bukkit.getPlayer(uuid);
        if (offline != null) {
            scoreboard.hide(offline);
        }

        if (state != GameState.ENDING && playerName != null) {
            broadcastI18n("player-left", playerName, getPlayerCount(), getMaxPlayers());
        }
    }

    @Override
    public boolean isPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    /** 当前旁观（等待波次刷新复活）的玩家数 */
    public int getSpectatorCount() {
        return spectators.size();
    }

    /** 该玩家是否处于旁观等待复活状态 */
    public boolean isSpectator(UUID uuid) {
        return spectators.contains(uuid);
    }

    /** 标记/取消玩家的旁观等待复活状态 */
    public void setSpectator(UUID uuid, boolean spectator) {
        if (spectator) {
            spectators.add(uuid);
        } else {
            spectators.remove(uuid);
        }
    }

    @Override
    public boolean isPlayer(Player player) {
        return players.contains(player.getUniqueId());
    }

    @Override
    public PlayerData getPlayerData(UUID uuid) {
        return gameManager.getPlayerData(uuid);
    }

    @Override
    public void startGame() {
        if (restoreStarted || plugin.getMapRestoreManager().isRestoring(definition.getWorldName())) {
            return; // 地图回滚完成前禁止开始下一局
        }
        if (state != GameState.WAITING && state != GameState.STARTING) {
            return; // Already started or ended
        }

        cancelCountdown();
        setState(GameState.RUNNING);
    }

    @Override
    public void stopGame() {
        // Cancel any running tasks
        cancelCountdown();
        // 结算倒计时属于被中断的这一局：必须取消，否则到点会把下一局玩家踢出
        cancelEndSequence();
        // G3d：空置值守随本局终结一并取消（幂等；也防旧任务跨局残留）
        cancelEmptyArenaTask();

        // Kick all players
        for (Player player : getPlayers()) {
            removePlayer(player);
        }

        // Reset arena state
        setState(GameState.ENDING);

        // Schedule arena reset (only if plugin is enabled)
        scheduleReset();
    }

    /**
     * 失败结算流程（对应设计：村民全灭 / 节点全毁 + 全员阵亡）。
     *
     * <p>流程：保持玩家在游戏内 → 聊天栏弹出本局统计 → 倒计时 → 传送回大厅 → 竞技场重置。</p>
     *
     * @param messageKey 失败提示的 i18n 键（如 defeat-all-down / defeat-villagers-dead）
     */
    public void triggerDefeat(String messageKey) {
        if (isEnding()) return;

        // 守卫：只有对局真正进行中才允许判定失败。
        // 否则未部署 / 尚未开局的竞技场（玩家在等待区摔死）会被误判为「全员阵亡」
        if (!gameManager.isGameRunning()) return;

        // 停止刷怪与波次调度，但保留玩家在场
        gameManager.haltGame();
        beginEndSequence(messageKey, false);
    }

    /** 是否已进入结算阶段 */
    private boolean isEnding() {
        return state == GameState.DEFEAT || state == GameState.VICTORY || state == GameState.ENDING;
    }

    /**
     * 统一结算流程：记录本局结果 → 播报战报 → 倒计时 → 传送回大厅（归还物品并结算统计）→ 竞技场重置。
     *
     * @param messageKey 额外的结算提示 i18n 键；为 null 表示调用方已广播过提示
     * @param victory    本局是否胜利
     */
    private void beginEndSequence(String messageKey, boolean victory) {
        if (isEnding()) return;

        this.sessionVictory = victory;
        state = victory ? GameState.VICTORY : GameState.DEFEAT;

        if (messageKey != null) {
            broadcastI18n(messageKey);
        }
        broadcastSessionStats();

        // 防御：任何残留的旧结算倒计时先作废，避免同一时刻存在两个倒计时
        cancelEndSequence();

        // 记录本局代号：stop / reset / 新开一局都会让回调失效，过期回调静默丢弃
        final int session = sessionId;
        int countdownSeconds = plugin.getConfigManager().getActiveConfig().getDefeatCountdownSeconds();

        // 插件禁用阶段（onDisable 的同步清理路径）不允许再调度 Bukkit 任务，否则
        // stopAllArenasNow() 会抛 IllegalPluginAccessException 并中断后续的地图回滚。
        // 结算状态与统计口径保持不变，只是不再起倒计时——此时服务器正在关闭，
        // 玩家看不到倒计时，而战报已由上面的 broadcastSessionStats() 播报。
        if (!plugin.isEnabled()) {
            return;
        }

        endSequenceTask = new BukkitRunnable() {
            int remaining = countdownSeconds;

            @Override
            public void run() {
                // 本局已被 stop / reset 中断，或已翻到下一局：静默丢弃
                if (!isCurrentEndSequence(session)) {
                    cancel();
                    // 置空残留句柄（与 finishSession 的收尾一致）。此分支执行时 endSequenceTask
                    // 必为本任务或已被 cancelEndSequence 置 null：新一局的 beginEndSequence 必先
                    // cancelEndSequence 取消旧任务，被取消的任务不会再 run()，故不存在误伤新任务。
                    endSequenceTask = null;
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    finishSession(session);
                    return;
                }
                if (remaining <= 5 || remaining % 5 == 0) {
                    broadcastI18n("defeat-countdown", remaining);
                }
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /**
     * 取消尚未完成的结算倒计时任务（幂等；不改变状态机）。
     */
    private void cancelEndSequence() {
        if (endSequenceTask != null) {
            endSequenceTask.cancel();
            endSequenceTask = null;
        }
    }

    /**
     * 该结算倒计时是否仍属于「当前这一局」且仍处于结算阶段。
     *
     * <p>身份（{@code session}）用于拦截上一局的残留回调；状态用于拦截
     * stop / reset / resetNow 把状态改到 ENDING 或新局改到 WAITING/RUNNING 的情况。</p>
     */
    private boolean isCurrentEndSequence(int session) {
        if (session != sessionId) return false;
        return state == GameState.VICTORY || state == GameState.DEFEAT;
    }

    /**
     * 结算倒计时结束：把所有玩家移出竞技场（内部归还物品、结算统计、送回大厅），
     * 然后转入 ENDING 并调度竞技场重置。
     *
     * <p>只对「发起倒计时的那一局」生效：过期的到期回调（stop / reset / 新局之后才到点）
     * 会被静默丢弃，不会踢出下一局的玩家。</p>
     */
    private void finishSession(int session) {
        if (!isCurrentEndSequence(session)) return;

        endSequenceTask = null;
        for (Player player : getPlayers()) {
            removePlayer(player);
        }
        setState(GameState.ENDING);
        scheduleReset();
    }

    /**
     * 向所有在场玩家播报本局统计。
     * 每人一行：玩家名 / 击杀 / 阵亡 / 修复节点 / 获取水晶（占位符顺序须与 messages.yml 一致）。
     */
    private void broadcastSessionStats() {
        broadcastI18n("defeat-stats-header", gameManager.getCurrentWave(), gameManager.getTotalWaves());
        for (Player player : getPlayers()) {
            PlayerData data = gameManager.getPlayerData(player.getUniqueId());
            if (data == null) continue;
            player.sendMessage(plugin.getLanguageManager().getFor(player, "defeat-stats-line",
                    player.getName(),
                    String.valueOf(data.getKills()),
                    String.valueOf(data.getDeaths()),
                    String.valueOf(data.getNodesRepaired()),
                    String.valueOf(data.getCurrencyCollected())));
        }
    }

    /**
     * 是否满足开局条件。
     *
     * <p>生成点与核心都按<b>实时值</b>判定（读 {@link GameManager} / {@link #getCoreLocation()}）：
     * 部署命令只刷新 GameManager，若用 {@code definition} 的构造期快照，
     * 刚部署好的竞技场仍会被判成「不能开局」。</p>
     *
     * <p>STORY-1.2：追加「怪物刷怪坐标非空」条件。旧存档（仅 {@code spawn-points}）
     * 走双用回退，{@code hasEffectiveMobSpawnPoints()} 同样通过 —— 仅当两套点都为空时才拦截。</p>
     */
    @Override
    public boolean canStart() {
        return isEnabled()
            && getPlayerCount() >= getMinPlayers()
            && gameManager.hasSpawnPoints()
            && gameManager.hasEffectiveMobSpawnPoints()
            && getCoreLocation() != null;
    }

    @Override
    public int getCurrentWave() {
        return gameManager.getCurrentWave();
    }

    @Override
    public int getTotalWaves() {
        return definition.getVictoryWaves();
    }

    /**
     * 本竞技场的波次间隔（秒），来自 {@code arenas.<id>.game.wave-interval-seconds}。
     *
     * <p>{@link GameManager} 每波结束时实时读取本值，因此管理 GUI 改完参数后<b>从下一波起</b>生效，
     * 不需要重建竞技场实例、也不会打断进行中的对局。</p>
     */
    public int getWaveIntervalSeconds() {
        return definition.getWaveIntervalSeconds();
    }

    /**
     * 用管理 GUI 刚写入 arenas.yml 的游戏参数刷新本实例的定义。
     *
     * <p>只替换 4 个数值，坐标类字段（core / spawn-points / villagers / shop-npc）保持原样，
     * 因此不会与 {@code AdminDeployManager} 持有的生成点副本产生不同步。</p>
     *
     * <p>调用方必须已在 {@code ArenaManager} 侧完成校验与落盘——本方法只改内存，不碰磁盘。</p>
     *
     * <p><b>副作用：</b>替换定义后会调用 {@link #checkAutoStart()}——调低人数下限后，
     * 若等待区人数已达新下限，会<b>立刻进入开局倒计时</b>，不必再等下一名玩家加入。</p>
     */
    public void applyGameParams(int minPlayers, int maxPlayers,
                                int victoryWaves, int waveIntervalSeconds) {
        this.definition = definition.withGameParams(minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds);
        checkAutoStart();
    }

    /**
     * 本竞技场核心位置（null = 尚未部署）。
     *
     * <p><b>为什么实时读 {@link GameManager} 而不是 {@code definition}：</b>部署命令
     * （{@code /hilltop deploy setcore}）与部署向导只经
     * {@code AdminDeployManager.saveSpawnPoints() → gameManager.setCoreLocation(...)} 刷新内存，
     * {@code definition} 始终是构造期快照（{@link #applyGameParams} 也只换数值、不碰坐标）。
     * 读快照会让「部署后不重启服务器」的玩家继续在旧核心位置复活。</p>
     *
     * <p><b>空值说明（已核实）：</b>{@link GameManager#getCoreLocation()} 的实现是
     * {@code coreLocation.clone()}，核心为 null 时会 NPE，所以「先取再判空」的写法在这里无效
     * （NPE 在调用内部就抛出了）。但活动实例的 core 不可能为 null：
     * {@code MultiArenaManager} 的两条构建路径（启用队列 / {@code startArena}）都在
     * {@code new ArenaInstance(...)} 之前拒绝未部署核心，{@link ArenaInstance} 构造函数本身也会
     * 解引用 core，且全项目唯一写点 {@code AdminDeployManager.saveSpawnPoints()} 仅在非空时才向
     * {@code gameManager.setCoreLocation(...)} 转发。因此这里直接读取，不用 try/catch 掩盖异常；
     * 调用方仍按「null = 未部署」判空（见 {@link #getSpectatorSpawn()}、{@link #canStart()}）。</p>
     */
    @Override
    public Location getCoreLocation() {
        return gameManager.getCoreLocation();
    }

    @Override
    public Location getLobbySpawn() {
        // ✅ 返回竞技场内的等待区位置（参考 BedWars1058 的 getWaitingLocation）
        return definition.getWaitingLocation();
    }

    @Override
    public Location getSpectatorSpawn() {
        // 10 blocks above core
        Location core = getCoreLocation();
        return core == null ? null : core.clone().add(0, 10, 0);
    }

    /**
     * 本竞技场的玩家出生点列表（防御性拷贝）。
     *
     * <p>STORY-1.2：本列表的语义固定为<b>玩家出生点</b>（开局传送用），怪物刷怪点另见
     * {@code GameManager#getMobSpawnPointsView()}。旧存档仅有本列表时，刷怪会双用回退到这里。</p>
     *
     * <p><b>为什么实时读 {@link GameManager} 而不是 {@code definition}：</b>部署命令
     * （{@code /hilltop deploy addspawn} 等）只经
     * {@code AdminDeployManager.saveSpawnPoints() → gameManager.setSpawnPoints(...)} 刷新内存，
     * {@code definition} 始终是构造期快照（{@link #applyGameParams} 也只换数值、不碰坐标）。
     * 读快照的后果见 {@link #onGameStart()}：开局时若仍读到空的旧列表，玩家就不会被传送出等待区。</p>
     */
    @Override
    public List<Location> getSpawnPoints() {
        return gameManager.getSpawnPoints();
    }

    @Override
    public void broadcast(String message) {
        for (Player player : getPlayers()) {
            player.sendMessage(message);
        }
    }

    @Override
    public void broadcastI18n(String key, Object... args) {
        for (Player player : getPlayers()) {
            languageManager.send(player, key, args);
        }
    }

    @Override
    public boolean isEnabled() {
        return definition.isEnabled();
    }

    @Override
    public boolean isAvailable() {
        return isEnabled()
            && isPlayable()
            && !restoreStarted
            && !plugin.getMapRestoreManager().isRestoring(definition.getWorldName())
            && (state == GameState.WAITING || state == GameState.STARTING)
            && getPlayerCount() < getMaxPlayers();
    }

    /** 是否已完成部署（玩家出生点与怪物刷怪坐标至少各有一套：旧存档的双用点同样算部署完成） */
    @Override
    public boolean isDeployed() {
        return gameManager.hasSpawnPoints() && gameManager.hasEffectiveMobSpawnPoints();
    }

    /** 竞技场世界是否已加载（false = 地图存档缺失/未加载，竞技场不可用） */
    @Override
    public boolean isWorldAvailable() {
        return getWorld() != null;
    }

    /** 竞技场世界（未加载返回 null） */
    public World getWorld() {
        return Bukkit.getWorld(definition.getWorldName());
    }

    /** 竞技场是否对玩家开放（世界可用 + 已部署） */
    public boolean isPlayable() {
        return isWorldAvailable() && isDeployed();
    }

    @Override
    public boolean isFull() {
        return getPlayerCount() >= getMaxPlayers();
    }

    @Override
    public int getPlayerCount() {
        return players.size();
    }

    @Override
    public int getMinPlayers() {
        return definition.getMinPlayers();
    }

    @Override
    public int getMaxPlayers() {
        return definition.getMaxPlayers();
    }

    // ========== Internal Methods ==========

    /**
     * Start the arena start countdown.
     *
     * <p>时长取自 arenas.yml 的 {@code settings.start-countdown}（秒，缺省 10）。</p>
     */
    private void startCountdown() {
        // 地图恢复是下一局的硬屏障；回滚任务存在时不能开始倒计时。
        if (restoreStarted || plugin.getMapRestoreManager().isRestoring(definition.getWorldName())) {
            state = GameState.ENDING;
            return;
        }

        // 防御性守卫：未就绪的竞技场绝不进入倒计时（对应 BedWars 只在 status==waiting 时开局）
        if (!isPlayable()) {
            state = GameState.WAITING;
            return;
        }

        // 重建路径先作废旧句柄并清掉句柄字段，保证 checkAutoStart() 的
        // startCountdownTask == null 守卫不会误判「还有倒计时在跑」而挡住后续新玩家触发的开局
        cancelCountdown();

        countdown = getConfiguredStartCountdown();
        final int initialCountdown = countdown;

        startCountdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                // ⚠️ 以下每个终止分支都走 cancelCountdown()（取消 + 置空 startCountdownTask），
                // 绝不能只调 BukkitRunnable.cancel()：那样任务停了但 startCountdownTask 残留非空，
                // 而 checkAutoStart() 以 startCountdownTask == null 为「可开新倒计时」守卫——
                // 自然结算路径（finishSession→reset→completeResetAfterRestore）回到 WAITING 时
                // 不会碰这个句柄，残留非空会永久挡住后续自动开局（P0-E）。
                if (state != GameState.STARTING) {
                    cancelCountdown();
                    return;
                }

                if (getPlayerCount() < getMinPlayers()) {
                    // Not enough players, cancel countdown
                    cancelCountdown();
                    setState(GameState.WAITING);
                    broadcastI18n("countdown-cancelled");
                    return;
                }

                if (!isPlayable()) {
                    // 竞技场在倒计时途中变为不可用（如管理员清空了生成点）
                    cancelCountdown();
                    setState(GameState.WAITING);
                    broadcastI18n("countdown-cancelled");
                    return;
                }

                if (countdown <= 0) {
                    // Start game（自然开局：与 startGame() 手动开局一致，先 cancelCountdown 再切 RUNNING）
                    cancelCountdown();
                    setState(GameState.RUNNING);
                    return;
                }

                // 倒计时播报：只在首个 tick 与最后 5 秒内提示，避免每秒刷屏
                if (countdown == initialCountdown || countdown <= 5) {
                    broadcastI18n("countdown-message", countdown);
                }

                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        // G3d：进入 STARTING 后拉起空置值守（幂等；0/负数配置时内部直接跳过）
        ensureEmptyArenaTaskRunning();
    }

    /**
     * Cancel countdown task.
     */
    private void cancelCountdown() {
        if (startCountdownTask != null) {
            startCountdownTask.cancel();
            startCountdownTask = null;
        }
    }

    /* ========== 空置自动结束（G3d） ========== */

    /**
     * 确保「空置自动结束」值守任务在运行（幂等）。
     *
     * <p>由 {@link #startCountdown()} 与 {@link #onGameStart()} 在对局进入进行中状态时调用。
     * 任务每个整秒检查一次：状态仍为 STARTING / RUNNING / WAVE_INTERMISSION 且
     * {@link #getPlayers()}（在线成员）为空持续 {@code empty-arena-end-seconds} 秒时，
     * 走与既有「全员退光」一致的 {@link #stopGame()} 正常收尾路径；有人在场（加入 / 重连）
     * 则计时清零。配置为 0 或负数时不启动。</p>
     *
     * <p>取消点：状态离开进行中（任务下一 tick 自行退出）、{@link #stopGame()}、
     * {@link #resetNow()} 与 {@link #completeResetAfterRestore()}。</p>
     */
    private void ensureEmptyArenaTaskRunning() {
        if (emptyArenaTask != null) return;
        // 插件禁用阶段（onDisable 同步收尾路径）不允许再调度 Bukkit 任务
        if (!plugin.isEnabled()) return;
        if (plugin.getConfigManager().getActiveConfig().getEmptyArenaEndSeconds() <= 0) return;

        emptyArenaTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 状态离开进行中（结算 / 重置 / 回到等待）→ 任务自行退出并清空句柄
                if (state != GameState.STARTING && state != GameState.RUNNING
                        && state != GameState.WAVE_INTERMISSION) {
                    cancelEmptyArenaTask();
                    return;
                }
                // 热重载把配置改为 0/负数：计时清零、不再生效（任务随本局结束自行退出）
                int limit = plugin.getConfigManager().getActiveConfig().getEmptyArenaEndSeconds();
                if (limit <= 0) {
                    emptyArenaSeconds = 0;
                    return;
                }
                // 有人在场（加入 / 重连）→ 计时清零
                if (!getPlayers().isEmpty()) {
                    emptyArenaSeconds = 0;
                    return;
                }
                emptyArenaSeconds++;
                if (emptyArenaSeconds >= limit) {
                    plugin.getLogger().info("竞技场 " + arenaId + " 空置 " + limit + "s，自动结束本局");
                    // 沿用既有「全员退光」语义：stopGame()（其内部会取消本任务句柄）
                    stopGame();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * 取消空置值守任务（幂等；须经本方法而非裸 {@code cancel()}，保证句柄置空可被重新拉起）。
     */
    private void cancelEmptyArenaTask() {
        if (emptyArenaTask != null) {
            emptyArenaTask.cancel();
            emptyArenaTask = null;
        }
        emptyArenaSeconds = 0;
    }

    /**
     * Called when game starts (state → RUNNING).
     * ✅ 传送玩家从等待区到游戏区域（参考 BedWars1058 的 onGameStart）
     */
    private void onGameStart() {
        if (restoreStarted || plugin.getMapRestoreManager().isRestoring(definition.getWorldName())) {
            state = GameState.ENDING;
            return;
        }

        // 新一局开始：换发本局代号，使上一局任何残留的结算回调立即失效
        sessionId++;
        cancelEndSequence();
        // M-08：上一局波次快照随新局归零（局中发现的中途离场者按局进程记新波次）
        sessionWave = 0;

        broadcastI18n("game-started");

        // ✅ Teleport all players from waiting area to game spawn points
        // STORY-1.2：此处仍读玩家出生点（getSpawnPoints 语义已固定为玩家出生），怪物刷怪点不影响传送
        // fix4 G1b：传送前对出生点做「区块强制加载 + 地表安全化」——作者实测开局玩家下坠掉虚空
        // （出生点下无地面/区块未加载），重生后正常。安全化失败时保持原坐标原样传送
        // （宁可原样也不把玩家挪到别处）。
        List<Location> spawns = getSpawnPoints();
        if (!spawns.isEmpty()) {
            int index = 0;
            for (Player player : getPlayers()) {
                Location target = safeTeleportTarget(spawns.get(index % spawns.size()));
                if (target != null) {
                    player.teleport(target);
                }
                player.setGameMode(GameMode.SURVIVAL);
                // STORY-1.2 starter-kit：背包在 join 时已由 PlayerGoodsManager 快照并清空，
                // 此时直接发放初始套装；背包满兜底掉脚下
                grantStarterKit(player);
                index++;
            }
        }

        // G3c：开局提示中途离开对局的命令入口（纯文本，观战按钮见 PlayerLifecycleListener）
        broadcastI18n("hint.leave-command");

        // Load nodes from config
        nodeSystem.loadNodesFromConfig();
        nodeSystem.applyAllBuffs();

        // 生成待保护村民（全部阵亡 = 游戏失败）
        // 关键隔离：先清掉部署期残留的村民预览实体。预览是纯装饰、绝对无敌（取消一切伤害）且带独立的
        // PDC 标记，VillagerSystem 既不会把它当真村民、其孤儿清理也不会删它 —— 一旦漏进对局，
        // 就会凭空多出一批打不死的“村民”把游戏搞坏。此处是本场开局的最后屏障。
        plugin.getVillagerPreviewManager().clearInWorld(getWorld());
        villagerSystem.cleanupOrphans();
        // P2-12：开局前最后一道波次怪清扫线（与村民清理同一位置同一理由）——崩溃残留的
        // persistent 波次怪若在构造期清扫时所在区块尚未加载而漏网，本局开局前（玩家已带动
        // 区块加载）在此清除，确保它不能攻击新局的玩家与刚生成的村民。此刻本波刷怪尚未开始
        // （startGame 在下方才调用），activeMobs 为空，按标记清除只可能命中残留。
        gameManager.getWaveManager().cleanupOrphans();
        villagerSystem.spawnAll();

        // Start wave system（波次归零由 gameManager.startGame() 内部完成，本类不再另存副本）
        gameManager.startGame();

        // G3d：手动 startGame() 未经 STARTING 倒计时时也拉起空置值守（幂等）
        ensureEmptyArenaTaskRunning();
    }

    /**
     * 把玩家出生点修正为可安全站立的位置（fix4 G1b）。
     *
     * <p>作者实测：进入游戏后「遁地掉虚空，重生以后就好了」——出生点脚下无地面或所在
     * 区块未加载时，实体传送后会直接下坠。两层修正：① 强制加载出生点所在区块；
     * ② 用 {@link SafeSpawnUtil} 向下扫描找地表安全落点并归位（保留 yaw/pitch）。
     * 安全化失败（如整列无实心方块）保持原坐标原样传送——宁可原样也不把玩家挪到别处。</p>
     */
    private Location safeTeleportTarget(Location spawn) {
        if (spawn == null) return null;
        World world = spawn.getWorld();
        if (world == null) return spawn;
        // 强制加载出生点区块：未加载区块直接传送，实体将落在空中并持续下坠（掉虚空）
        world.getChunkAt(spawn);
        Location safe = SafeSpawnUtil.findSafeGround(spawn);
        return safe != null ? safe : spawn;
    }

    /**
     * Called when game ends (state → ENDING).
     */
    private void onGameEnd() {
        // M-08：捕获本局波次快照 —— 下面的 stopGame() 会把 currentWave 归零，
        // 而 reset() 补录离线残留玩家统计（recordOffline 的 wavesSurvived）时需要真实波次
        sessionWave = Math.max(sessionWave, gameManager.getCurrentWave());
        // Stop all game tasks
        gameManager.stopGame();

        // 清理村民实体
        villagerSystem.clearAll();

        // Show end message (will be customized based on win/loss)
        broadcastI18n("game-ended");
    }

    /**
     * Reset arena resources, then keep ENDING as a hard barrier until map restoration completes.
     */
    private void reset() {
        if (restoreStarted) return;
        restoreStarted = true;
        state = GameState.ENDING;
        resetScheduled = false;

        // 结算倒计时随本局一起作废，避免它对下一局生效
        cancelEndSequence();

        // M-08：捕获本局波次快照。正常收尾路径里 onGameEnd（→ stopGame）已把 currentWave
        // 归零、快照已由那时的 Math.max 记下；resetNow 等不经 onGameEnd 的路径在此兜底捕获
        // （此刻 currentWave 仍是真实值）。
        sessionWave = Math.max(sessionWave, gameManager.getCurrentWave());

        // 重置 GameManager 内部状态（幂等：波次/怪物/节点 Buff 全部清理）。
        // 刻意前移到补录循环之前：先归零状态，循环里对在线残留的 removePlayer 兜底
        // 就不会命中「人数不足 → 判负」等收尾重入分支。
        gameManager.stopGame();

        // M-08：补结算「离线残留」玩家（掉线重连窗口内仍在 players 集合中的玩家）。
        // 在线玩家的本局统计已在 removePlayer() 落库（各收尾路径的踢人循环），此处只处理
        // 未经过 removePlayer 的离线残留——为他们准备的 recordOffline 入口
        // （expirePendingRejoins → removeOfflinePlayer）会因本方法随后清空集合而早退
        // （players.remove 返回 false），不在清集合前补录就会整局统计丢失
        // （掉线 60s 窗口 > 结算 20s + 重置 5s，是常规可复现时序）。
        // 「只结算一次」：removePlayer/removeOfflinePlayer 都会把 UUID 从本集合移除，
        // 集合中残留的每一名成员都必然未结算过（reset 期间处于 ENDING 硬屏障，不可能再 addPlayer）。
        // 波次口径用 sessionWave 快照（getCurrentWave 已被上面的 stopGame 归零）。
        for (UUID uuid : new ArrayList<>(players)) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                // 防御：理论上各收尾踢人循环已把所有在线玩家移出；极端时序下若仍有在线
                // 残留，走完整离场流程（结算统计 + 归还物品 + 送回大厅）
                removePlayer(online);
                continue;
            }
            PlayerData leftoverData = gameManager.getPlayerData(uuid);
            if (leftoverData != null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                String name = offline != null ? offline.getName() : null;
                plugin.getStatsManager().recordOffline(uuid, name, leftoverData, sessionVictory, sessionWave);
            }
            // 同步清掉「玩家 → 竞技场」映射（L06-04 / P2-11 同一收口）：残留映射会让下次
            // 登录的 isInArena 判定为 true，推迟物品快照归还
            plugin.getMultiArenaManager().forgetPlayerArena(uuid);
        }

        // Clear all players
        players.clear();
        spectators.clear();

        // 同步清空 GameManager 的玩家集合与 PlayerData：
        // 处于掉线重连窗口的离线玩家不会经过 removePlayer()，否则会跨局残留成「幽灵玩家」。
        // 本局统计已在上面的补录循环与 removePlayer() 中落库，此处只清集合，不重复结算。
        gameManager.clearAllPlayers();

        // Clean up entities (mobs, villagers, dropped items, scoreboard)
        // 商店/NPC 属于插件级单例的生命周期，竞技场重置不得触碰（否则会删掉大厅与其他竞技场的 NPC）
        villagerSystem.clearAll();
        scoreboard.clear();
        clearDroppedItems();

        // 只有回滚完整成功才能重新进入 WAITING；失败时保持 ENDING 硬屏障，避免开放一张半恢复地图。
        boolean accepted = plugin.getMapRestoreManager().restoreWithResult(
                definition.getWorldName(), success -> {
                    if (success) {
                        completeResetAfterRestore();
                    } else {
                        restoreStarted = false;
                        resetScheduled = false;
                        plugin.getLogger().severe("Arena " + arenaId
                                + " reset blocked: map restoration completed with failed blocks");
                    }
                });
        if (!accepted) {
            restoreStarted = false;
            resetScheduled = false;
            plugin.getLogger().warning("Arena " + arenaId
                    + " reset blocked: map restoration could not start");
        }
    }

    /** 地图已完全恢复；此时才开放下一局。 */
    private void completeResetAfterRestore() {
        restoreStarted = false;
        resetScheduled = false;
        sessionVictory = null;
        // M-08：波次快照随本局结算完毕归零（下一局在 onGameStart 会再次清零，此处对齐语义）
        sessionWave = 0;
        // 保险（幂等）：回到 WAITING 之前确保倒计时句柄干净。正常路径下主修复已在倒计时
        // 终止分支置空，但无论经由哪条结算路径（自然结束 / stopGame）回到 WAITING，
        // 都在此兜底一次，保证 checkAutoStart() 的 startCountdownTask == null 守卫必然成立。
        cancelCountdown();
        // G3d：同理兜底空置值守句柄（任务本身也会因状态离开进行中自行退出，这里显式清理）
        cancelEmptyArenaTask();
        state = GameState.WAITING;
        plugin.getLogger().info("Arena " + arenaId + " has been reset");
    }

    /**
     * 插件禁用时同步停止对局并完成地图回滚，不创建后续 Bukkit 任务。
     * 可由主插件在 onDisable 的竞技场停止阶段调用。
     */
    public void resetNow() {
        cancelCountdown();
        cancelEndSequence();
        // G3d：实例级终结（插件关闭 / 强制重置）必须停掉空置值守
        cancelEmptyArenaTask();
        for (Player player : getPlayers()) {
            removePlayer(player);
        }
        state = GameState.ENDING;
        if (!restoreStarted) reset();
        plugin.getMapRestoreManager().restoreNow(definition.getWorldName());
    }

    /** 清理竞技场世界中残留的掉落物（怪物掉落 / 玩家丢弃） */
    private void clearDroppedItems() {
        World world = gameManager.getGameWorld();
        if (world == null) return;
        // 原来用 world.getEntities() 物化世界里全部实体（含玩家、怪物、展示实体、经验球等），
        // 再逐个 instanceof 过滤。已反编译核实（CraftRegionAccessor）：两个方法都遍历同一份
        // getNMSEntities()、都对每个实体调 getBukkitEntity()，差别只在往中间 List 里塞多少 ——
        // getEntitiesByClass 只把类型匹配的塞进去，因此省掉的是「非掉落物实体」的那部分
        // List.add 与数组扩容。收益不算大，但改法零风险、语义完全一致。
        int removed = 0;
        for (org.bukkit.entity.Item item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
            item.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("已清理 " + removed + " 个残留掉落物 (竞技场 " + arenaId + ")");
        }
    }

    /**
     * 向一名开局玩家发放 starter-kit（STORY-1.2）。
     *
     * <p>配置来源：{@code game-settings.yml} 的 {@code starter-kit} 节
     * （{@code enabled} 总开关 + {@code items} 物品列表）。
     * 时序安全：玩家背包在 join 时已由 {@code PlayerGoodsManager.save()} 快照并清空，
     * 开局发放不会污染玩家原有物品；离场时整包清空、快照归还，套装不会被带走。</p>
     *
     * <p>背包满兜底：{@code addItem} 放不下的部分直接掉在玩家脚下（世界为 null 时丢弃并记日志）。</p>
     */
    private void grantStarterKit(Player player) {
        GameConfig cfg = configManager.getActiveConfig();
        if (!cfg.isStarterKitEnabled()) return;
        List<GameConfig.StarterKitItem> kit = cfg.getStarterKitItems();
        if (kit == null || kit.isEmpty()) return;

        boolean granted = false;
        for (GameConfig.StarterKitItem entry : kit) {
            if (entry == null || entry.getMaterial() == null || !entry.getMaterial().isItem()) continue;
            int amount = Math.max(1, entry.getAmount());
            // 超过单叠上限的数量必须拆叠：new ItemStack(mat, 70) 这类写法会被静默截断，
            // addItem 只认每叠各自的数量
            int maxStack = entry.getMaterial().getMaxStackSize();
            int remaining = amount;
            while (remaining > 0) {
                int stack = Math.min(remaining, maxStack);
                Map<Integer, org.bukkit.inventory.ItemStack> overflow =
                        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(entry.getMaterial(), stack));
                if (!overflow.isEmpty()) {
                    Location feet = player.getLocation();
                    World world = feet != null ? feet.getWorld() : null;
                    if (world == null) {
                        plugin.getLogger().warning("Starter-kit overflow dropped for " + player.getName()
                                + " (world unavailable)");
                    } else {
                        for (org.bukkit.inventory.ItemStack item : overflow.values()) {
                            world.dropItemNaturally(feet, item);
                        }
                    }
                }
                granted = true;
                remaining -= stack;
            }
        }
        if (granted) {
            languageManager.send(player, "setup.kit-received");
        }
    }

    // ========== Getters for Game Components ==========

    public GameManager getGameManager() {
        return gameManager;
    }

    public NodeSystem getNodeSystem() {
        return nodeSystem;
    }

    public VillagerSystem getVillagerSystem() {
        return villagerSystem;
    }

    public ArenaScoreboard getScoreboard() {
        return scoreboard;
    }

    public ArenaDefinition getDefinition() {
        return definition;
    }
}
