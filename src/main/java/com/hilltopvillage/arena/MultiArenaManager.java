package com.hilltopvillage.arena;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Arena Manager - manages all running arena instances.
 * Inspired by BedWars1058's ArenaManager pattern.
 *
 * <p>Responsibilities:
 * - Load arena definitions from arenas.yml
 * - Create and destroy arena instances
 * - Route players to arenas
 * - Track which arena each player is in
 */
public class MultiArenaManager {

    private final HilltopVillagePlugin plugin;
    private final ArenaManager definitionManager; // Loads definitions from arenas.yml

    // Active arena instances (arenaId → ArenaInstance)
    private final Map<String, ArenaInstance> activeArenas;

    // Player to arena mapping (UUID → arenaId)
    private final Map<UUID, String> playerArenaMap;

    // 掉线重连窗口（UUID → 重连记录），超时未归则执行完整离开流程
    private final Map<UUID, PendingRejoin> pendingRejoins;
    private BukkitTask rejoinExpiryTask;

    // ===== 启用队列（对应 BedWars1058 的 Arena.getEnableQueue） =====
    /** 待启用竞技场 ID 队列（串行处理，一次只加载一个） */
    private final LinkedList<String> enableQueue = new LinkedList<>();
    /** 竞技场生命周期状态 */
    private final Map<String, ArenaStatus> statuses = new ConcurrentHashMap<>();
    private BukkitTask enableQueueTask;

    // ===== 退役实例隔离窗口 =====
    /**
     * 已退役（禁用/停止）但可能仍有「延迟 reset / 结算倒计时」待执行的实例（arenaId → 退役记录）。
     *
     * <p>{@code ArenaInstance.scheduleReset()} 只排一个延迟任务且不保存句柄，实例被移出
     * {@link #activeArenas} 后该任务仍会触发：它会清理整个世界（怪物/商店/村民/掉落物）
     * 并触发该世界的地图回滚。若期间同世界的新实例已经开局，新局就会被这个旧任务破坏。
     * 因此在窗口期内把这些世界视为「被占用」，禁止创建同世界的新实例。</p>
     */
    private final Map<String, RetiredArena> retiredArenas = new ConcurrentHashMap<>();
    private BukkitTask retiredCleanupTask;
    /** 因世界仍在隔离窗口内而排队等待的竞技场（仅用于避免重复刷日志） */
    private final Set<String> quarantineDeferred = ConcurrentHashMap.newKeySet();

    /** 隔离窗口已经结束、但该世界仍在回滚中时，最多再等这么久（防止回滚残留导致世界被永久隔离） */
    private static final int QUARANTINE_RESTORE_EXTRA_SECONDS = 60;

    /** 单条重连记录（保留玩家名用于离线移除时的广播） */
    private record PendingRejoin(String arenaId, String playerName, long expireAt) {}

    /**
     * 退役实例记录。
     *
     * @param arena            已退役的实例（仅用于插件关闭时的兜底同步清理）
     * @param worldName        其占用的世界
     * @param remainingSeconds 隔离窗口剩余秒数，需覆盖旧实例可能仍在排队的结算倒计时 + 延迟 reset
     */
    private record RetiredArena(ArenaInstance arena, String worldName, int remainingSeconds) {}

    public MultiArenaManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.definitionManager = new ArenaManager(plugin);
        this.activeArenas = new ConcurrentHashMap<>();
        this.playerArenaMap = new ConcurrentHashMap<>();
        this.pendingRejoins = new ConcurrentHashMap<>();
        startRejoinExpiryTask();
    }

    /** 每秒扫描一次重连窗口，超时未归的玩家执行完整离开流程 */
    private void startRejoinExpiryTask() {
        rejoinExpiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expirePendingRejoins, 20L, 20L);
    }

    /** 处理超时的重连记录 */
    private void expirePendingRejoins() {
        if (pendingRejoins.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingRejoin> entry : new ArrayList<>(pendingRejoins.entrySet())) {
            PendingRejoin pending = entry.getValue();
            if (now <= pending.expireAt()) continue;

            pendingRejoins.remove(entry.getKey());
            playerArenaMap.remove(entry.getKey());

            ArenaInstance arena = activeArenas.get(pending.arenaId());
            if (arena == null) continue;

            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                // 异常情况：玩家在线但窗口已过期（如插件重载），走完整离开流程
                arena.removePlayer(player);
            } else {
                // 离线：只清理竞技场状态，物品快照保留在磁盘上供下次登录归还
                arena.removeOfflinePlayer(entry.getKey(), pending.playerName());
            }
            plugin.getLogger().info("重连窗口超时，玩家已移出竞技场: " + pending.playerName());
        }
    }

    /** 插件关闭时停止重连扫描、启用队列与退役隔离任务 */
    public void shutdown() {
        if (rejoinExpiryTask != null) {
            rejoinExpiryTask.cancel();
            rejoinExpiryTask = null;
        }
        stopEnableQueueTask();
        stopRetiredCleanupTask();
        pendingRejoins.clear();
        enableQueue.clear();
        quarantineDeferred.clear();
        // retiredArenas 特意保留：随后的 stopAllArenasNow() 还要用它们做兜底同步清理
    }

    /**
     * 玩家离开服务器时的统一入口。
     * 对局进行中，或 arenas.yml 的 {@code settings.auto-leave-on-quit=false} →
     * 保留重连窗口（不归还物品、不移出竞技场）；其他情况 → 正常离开竞技场。
     *
     * <p>{@code auto-leave-on-quit=false} 只是把「离开」改为「保留位置」，
     * 原有的对局进行中重连窗口逻辑（超时清理、登录恢复）完全不变；
     * 若重连功能本身已关闭（{@code rejoin-timeout-seconds<=0}）则无法保留位置，仍按正常离开处理。</p>
     */
    public void handleQuit(Player player) {
        ArenaInstance arena = getPlayerArena(player);
        if (arena == null) return;

        GameState state = arena.getState();
        boolean inProgress = state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION;
        int timeout = plugin.getConfigManager().getActiveConfig().getRejoinTimeoutSeconds();

        // settings.auto-leave-on-quit=false：退服时一律保留竞技场位置（等待/倒计时阶段也不例外）
        boolean keepPosition = !isAutoLeaveOnQuit() || inProgress;

        if (!keepPosition || timeout <= 0) {
            leaveArena(player);
            return;
        }

        pendingRejoins.put(player.getUniqueId(),
                new PendingRejoin(arena.getArenaId(), player.getName(),
                        System.currentTimeMillis() + timeout * 1000L));
        arena.broadcastI18n("player-reconnecting", player.getName(), String.valueOf(timeout));
    }

    /**
     * 玩家登录时尝试恢复未过期的重连窗口。
     *
     * @return true 表示已恢复到竞技场（调用方不应再执行大厅落地）
     */
    public boolean tryRejoin(Player player) {
        PendingRejoin pending = pendingRejoins.remove(player.getUniqueId());
        if (pending == null) return false;

        ArenaInstance arena = activeArenas.get(pending.arenaId());
        if (arena == null) {
            playerArenaMap.remove(player.getUniqueId());
            restoreGoodsOnFailedRejoin(player);
            return false;
        }

        if (!arena.rejoin(player)) {
            // 竞技场已结束或玩家已被移出：按正常流程送回大厅
            arena.removePlayer(player);
            playerArenaMap.remove(player.getUniqueId());
            // P2-11：重连窗口内登录且局已结束 → removePlayer 对「已被 reset 清出 players 集合」
            // 的玩家会早退（第一行 !players.contains 分支），跳过其物品归还；这里补一次，
            // 保证物品当场归还而不是拖到下一次登录（期间对局货币可在大厅消费，经济口径漏洞）。
            // restore 幂等：无快照时返回 false 且不发送提示，不会重复归还。
            restoreGoodsOnFailedRejoin(player);
            return false;
        }
        return true;
    }

    /**
     * 重连失败后的物品归还兜底（P2-11）。
     *
     * <p>正常路径下 {@code removePlayer} 内部已归还；但「重连窗口内登录且局已结束并完成
     * reset」的时序里，reset 已把玩家从 players 集合清出，removePlayer 第一行即早退，
     * 归还被跳过。restore 幂等，无快照时为无害空操作。</p>
     */
    private void restoreGoodsOnFailedRejoin(Player player) {
        if (plugin.getPlayerGoodsManager().has(player)) {
            if (plugin.getPlayerGoodsManager().restore(player)) {
                plugin.getLanguageManager().send(player, "goods-restored");
            }
        }
    }

    /** 该玩家当前是否处于重连窗口内 */
    public boolean isPendingRejoin(Player player) {
        return pendingRejoins.containsKey(player.getUniqueId());
    }

    /**
     * Load all arena definitions from arenas.yml.
     * Does NOT start arena instances yet.
     */
    public void loadArenaDefinitions() {
        definitionManager.load();
        plugin.getLogger().info("Loaded " + definitionManager.getRegistry().size() + " arena definitions");
    }

    /* ========== 启用队列（对应 BedWars1058 的 enableQueue） ========== */

    /**
     * 将竞技场加入启用队列（对应 BedWars1058 的 {@code Arena.addToEnableQueue}）。
     *
     * <p>队列<b>串行</b>处理：一次只加载一个竞技场，加载完成后自动处理下一个，
     * 与 BedWars1058 的 {@code removeFromEnableQueue()} → 加载队首 的行为一致。</p>
     *
     * @param arenaId 竞技场 ID
     * @return true 表示已加入队列（或已就绪）
     */
    public boolean enableArena(String arenaId) {
        // 键必须归一化：ArenaRegistry.get() 是大小写不敏感的，而 activeArenas / enableQueue / statuses
        // 一律以定义里的规范 ID（小写）为键（ArenaInstance.arenaId 也取自 definition.getId()）。
        // 若不归一化，getArena(...) 的精确键查找会失配：/hilltop enable Arena1 会造出一个「拿不到的实例」。
        // 归一化必须在所有 containsKey / contains 检查之前完成，否则归一到规范 ID 后会被重复入队。
        ArenaDefinition definition = definitionManager.getArena(arenaId);
        if (definition != null) arenaId = definition.getId();

        if (activeArenas.containsKey(arenaId)) {
            return true; // 已就绪
        }
        if (enableQueue.contains(arenaId)) {
            return true; // 已在队列中
        }

        if (definition == null) {
            plugin.getLogger().warning("竞技场定义不存在: " + arenaId);
            return false;
        }
        if (!definition.isEnabled()) {
            plugin.getLogger().warning("竞技场在配置中已禁用（enabled: false）: " + arenaId);
            return false;
        }
        // 未部署核心（arenas.yml 无 core）时不能启用：ArenaInstance 构造要用 coreLocation.getWorld()，null 会 NPE
        if (definition.getCore() == null) {
            plugin.getLogger().warning("拒绝启用竞技场 " + arenaId
                    + "：尚未部署核心（arenas.yml 中该竞技场缺少 core 配置）");
            return false;
        }

        enableQueue.add(arenaId);
        statuses.put(arenaId, ArenaStatus.QUEUED);
        plugin.getLogger().info("竞技场 " + arenaId + " 已加入启用队列");

        ensureEnableQueueTaskRunning();
        return true;
    }

    /** 每 tick 处理一个待启用竞技场（串行加载，避免一次性卡住服务器） */
    private void ensureEnableQueueTaskRunning() {
        if (enableQueueTask != null) return;
        enableQueueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processEnableQueue, 1L, 1L);
    }

    /**
     * 处理启用队列队首（对应 BedWars1058 的 {@code RestoreAdapter.onEnable} → {@code Arena.init()}）。
     *
     * <p>只有世界成功加载后才会初始化竞技场；初始化完成的竞技场才进入 {@link ArenaStatus#READY}，
     * 与 BedWars1058「init 末尾 changeStatus(waiting) 才算就绪」一致。</p>
     */
    private void processEnableQueue() {
        if (enableQueue.isEmpty()) {
            stopEnableQueueTask();
            return;
        }

        String arenaId = enableQueue.poll();
        ArenaDefinition definition = definitionManager.getArena(arenaId);
        if (definition == null) {
            statuses.put(arenaId, ArenaStatus.DISABLED);
            return;
        }

        // 兜底：定义入队后 core 可能被移除/重载（未部署核心），此路径绝不能走到 new ArenaInstance(...)
        if (definition.getCore() == null) {
            plugin.getLogger().warning("拒绝启用竞技场 " + arenaId
                    + "：尚未部署核心（arenas.yml 中该竞技场缺少 core 配置）");
            statuses.put(arenaId, ArenaStatus.DISABLED);
            return;
        }

        // 一个世界只能对应一个竞技场：同世界的另一个竞技场已就绪/排队时拒绝启用
        String conflict = findWorldConflict(arenaId, definition.getWorldName());
        if (conflict != null) {
            plugin.getLogger().severe("拒绝启用竞技场 " + arenaId + "：世界 " + definition.getWorldName()
                    + " 已被竞技场 " + conflict + " 占用（一个世界只能对应一个竞技场，"
                    + "共用世界会导致地图回滚互相破坏）");
            statuses.put(arenaId, ArenaStatus.DISABLED);
            return;
        }

        // 世界尚未就绪：仍处于退役实例的隔离窗口内，或正在回滚 → 放回队尾稍后自动重试
        String worldName = definition.getWorldName();
        boolean worldBusy = isWorldQuarantined(worldName)
                || plugin.getMapRestoreManager().isRestoring(worldName);
        if (worldBusy) {
            enableQueue.addLast(arenaId);
            statuses.put(arenaId, ArenaStatus.QUEUED);
            if (quarantineDeferred.add(arenaId)) {
                plugin.getLogger().info("竞技场 " + arenaId + " 暂缓启用：世界 " + worldName
                        + " 仍处于上一个实例的延迟 reset 隔离窗口或地图回滚中，稍后自动重试");
            }
            return;
        }
        quarantineDeferred.remove(arenaId);

        statuses.put(arenaId, ArenaStatus.LOADING);

        // 1) 确保世界已加载
        if (!ensureWorldLoaded(definition.getWorldName())) {
            plugin.getLogger().severe("竞技场 " + arenaId + " 启用失败：世界 " + definition.getWorldName()
                    + " 无法加载（服务器根目录下是否缺少该世界文件夹？）");
            statuses.put(arenaId, ArenaStatus.DISABLED);
            return;
        }

        // 2) 创建并初始化竞技场实例
        try {
            ArenaInstance arena = new ArenaInstance(plugin, definition);
            activeArenas.put(arenaId, arena);
            statuses.put(arenaId, ArenaStatus.READY);
            plugin.getLogger().info("竞技场已就绪: " + arenaId);
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "竞技场 " + arenaId + " 初始化失败", exception);
            statuses.put(arenaId, ArenaStatus.DISABLED);
        }
    }

    /** 世界未加载时尝试从磁盘加载（Paper 只自动加载 level-name 世界） */
    private boolean ensureWorldLoaded(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        if (Bukkit.getWorld(worldName) != null) return true;

        File folder = new File(Bukkit.getWorldContainer(), worldName);
        if (!new File(folder, "level.dat").exists()) return false;

        try {
            return Bukkit.createWorld(new WorldCreator(worldName)) != null;
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "加载世界失败: " + worldName, exception);
            return false;
        }
    }

    private void stopEnableQueueTask() {
        if (enableQueueTask != null) {
            enableQueueTask.cancel();
            enableQueueTask = null;
        }
    }

    /**
     * 禁用竞技场（对应 BedWars1058 的 {@code /bw disable}）。
     *
     * <p>用于<b>维护</b>：踢出玩家、从可加入列表中移除。对局进行中时拒绝禁用，
     * 与 BedWars1058「There is a game running on this Arena, please disable after the game!」一致。</p>
     *
     * @param arenaId 竞技场 ID
     * @return 操作结果说明的 i18n 键；成功返回 "deploy.ok"
     */
    public String disableArena(String arenaId) {
        if (!definitionManager.getRegistry().list().stream()
                .anyMatch(def -> def.getId().equals(arenaId))) {
            return "arena-not-found";
        }

        ArenaInstance arena = activeArenas.get(arenaId);
        if (arena == null) {
            enableQueue.remove(arenaId);
            statuses.put(arenaId, ArenaStatus.DISABLED);
            return "arena-already-disabled";
        }

        GameState state = arena.getState();
        if (state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION
                || state == GameState.STARTING) {
            return "arena-disable-in-progress";
        }

        // 踢出残留玩家并移除实例。
        // stopGame() 只调度「延迟 reset」且无法取消，实例移除后该任务仍会触发并清理整个世界；
        // 因此退役时把该世界放进隔离窗口，窗口结束前不允许创建同世界的新实例。
        arena.stopGame();
        purgePlayerArenaMapping(arenaId);
        retireArena(arenaId, arena, state);
        activeArenas.remove(arenaId);
        statuses.put(arenaId, ArenaStatus.DISABLED);

        plugin.getLogger().info("竞技场已禁用: " + arenaId);
        return "deploy.ok";
    }

    /**
     * 启用所有配置中标记为 enabled 的竞技场（插件启动时调用）。
     * 与 BedWars1058 启动时把所有合法竞技场加入启用队列的行为一致。
     */
    public void enableAllConfiguredArenas() {
        for (ArenaDefinition definition : definitionManager.getRegistry().list()) {
            if (definition.isEnabled()) {
                enableArena(definition.getId());
            } else {
                statuses.put(definition.getId(), ArenaStatus.DISABLED);
            }
        }
    }

    /** 竞技场生命周期状态 */
    public ArenaStatus getStatus(String arenaId) {
        if (activeArenas.containsKey(arenaId)) return ArenaStatus.READY;
        if (enableQueue.contains(arenaId)) {
            ArenaStatus s = statuses.get(arenaId);
            return s == null ? ArenaStatus.QUEUED : s;
        }
        ArenaStatus s = statuses.get(arenaId);
        return s == null ? ArenaStatus.DISABLED : s;
    }

    /** 当前启用队列（只读） */
    public List<String> getEnableQueue() {
        return Collections.unmodifiableList(new ArrayList<>(enableQueue));
    }

    /**
     * 保留旧接口：直接创建一个已就绪的竞技场实例（不走队列）。
     * 供需要同步就绪的场景使用。
     *
     * <p>⚠️ 已弃用（P2-35）：本方法绕过「启用队列」状态机——不登记 {@code enableQueue}、
     * 不更新 {@code statuses} 的全部语义（{@code startAllArenas()} 同样零调用方），
     * 与队列路径并存会让 {@code getStatus()} / {@code disableArena} / {@code canReset()}
     * 得到与实际不符的判断。正常启用请走 {@code enableAllConfiguredArenas()} / 启用队列。
     * 实测除 {@link #startAllArenas()}（同样零外部调用方）外无其它调用方。</p>
     *
     * @deprecated 绕过启用队列状态机的旧接口；请走启用队列路径（enableArena / enableAllConfiguredArenas）。
     */
    @Deprecated(forRemoval = true)
    public boolean startArena(String arenaId) {
        if (activeArenas.containsKey(arenaId)) {
            return false;
        }
        ArenaDefinition definition = definitionManager.getArena(arenaId);
        if (definition == null) {
            plugin.getLogger().warning("Arena definition not found: " + arenaId);
            return false;
        }
        if (!definition.isEnabled()) {
            plugin.getLogger().warning("Arena " + arenaId + " is disabled");
            return false;
        }
        // 未部署核心（arenas.yml 无 core）时不能启动：ArenaInstance 构造会调用 coreLocation.getWorld()
        if (definition.getCore() == null) {
            plugin.getLogger().warning("拒绝启动竞技场 " + arenaId
                    + "：尚未部署核心（arenas.yml 中该竞技场缺少 core 配置）");
            return false;
        }

        // 一个世界只能对应一个竞技场（运行期兜底，避免共享世界导致地图回滚互相破坏）
        String conflict = findWorldConflict(arenaId, definition.getWorldName());
        if (conflict != null) {
            plugin.getLogger().severe("拒绝启动竞技场 " + arenaId + "：世界 " + definition.getWorldName()
                    + " 已被竞技场 " + conflict + " 占用（一个世界只能对应一个竞技场）");
            return false;
        }
        if (isWorldQuarantined(definition.getWorldName())) {
            plugin.getLogger().warning("拒绝启动竞技场 " + arenaId + "：世界 " + definition.getWorldName()
                    + " 仍处于上一个实例的延迟 reset 隔离窗口内，请稍后重试");
            return false;
        }

        if (!ensureWorldLoaded(definition.getWorldName())) {
            plugin.getLogger().warning("Arena " + arenaId + " world could not be loaded");
            return false;
        }

        ArenaInstance arena = new ArenaInstance(plugin, definition);
        activeArenas.put(arenaId, arena);
        statuses.put(arenaId, ArenaStatus.READY);
        plugin.getLogger().info("Started arena: " + arenaId);
        return true;
    }

    /**
     * 停止并移除竞技场实例（不走「禁用」语义，供插件关闭/重置使用）。
     *
     * @param arenaId arena ID
     */
    public void stopArena(String arenaId) {
        ArenaInstance arena = activeArenas.get(arenaId);
        if (arena == null) {
            plugin.getLogger().warning("Arena " + arenaId + " is not running");
            return;
        }

        // 插件关闭阶段不能走延迟 reset：onDisable 期间 plugin.isEnabled() 仍可能为 true，
        // 若创建延迟任务会在禁用流程结束时被取消，导致地图只清理不恢复。
        if (!plugin.isEnabled()) {
            arena.resetNow();
        } else {
            GameState retiredState = arena.getState();
            arena.stopGame();
            // 启用阶段：stopGame() 留下无法取消的延迟 reset，退役时需隔离其世界
            retireArena(arenaId, arena, retiredState);
        }
        // 两条分支共用：退役后该竞技场的映射条目必须清掉（离线玩家不走 removePlayer）
        purgePlayerArenaMapping(arenaId);
        activeArenas.remove(arenaId);
        plugin.getLogger().info("Stopped arena: " + arenaId);
    }

    /**
     * 插件关闭专用：同步清理所有竞技场并完成地图回滚，不创建延迟任务。
     *
     * <p>逐个竞技场做异常隔离：禁用阶段移除最后一名玩家可能触发结算倒计时
     * （无条件 {@code runTaskTimer}）并抛出 {@code IllegalPluginAccessException}；
     * 单个竞技场失败不能中断其余竞技场，也不能让调用方跳过随后的
     * {@code MapRestoreManager.restoreAllNow()}。</p>
     */
    public void stopAllArenasNow() {
        for (String arenaId : new ArrayList<>(activeArenas.keySet())) {
            ArenaInstance arena = activeArenas.get(arenaId);
            if (arena == null) continue;
            resetArenaNow(arenaId, arena);
            activeArenas.remove(arenaId);
        }

        // 已退役实例的延迟 reset 尚未执行就被禁用：同样做同步清理，
        // 避免残留商店 NPC / 村民 / 掉落物（方块部分由 restoreAllNow 兜底）
        for (String arenaId : new ArrayList<>(retiredArenas.keySet())) {
            RetiredArena retired = retiredArenas.remove(arenaId);
            if (retired == null) continue;
            resetArenaNow(arenaId, retired.arena());
        }
    }

    /** 同步停止单个竞技场；异常只记录日志，保证调用方继续处理其余竞技场与地图回滚。 */
    private void resetArenaNow(String arenaId, ArenaInstance arena) {
        try {
            arena.resetNow();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "同步停止竞技场失败，已跳过: " + arenaId, exception);
        }
    }

    /* ========== 竞技场世界唯一性与退役隔离 ========== */

    /**
     * 竞技场退役：把实例标记为「延迟 reset 待失效」，并在窗口期内隔离它占用的世界。
     *
     * <p>{@code ArenaInstance.scheduleReset()} 的延迟任务没有可取消的句柄，实例移出
     * {@link #activeArenas} 后仍会触发一次完整重置（清怪/商店/村民/掉落物 + 地图回滚）。
     * 隔离窗口 = 退役瞬间仍可能排队的「结算倒计时」+「延迟 reset」的最大耗时，
     * 窗口结束前同世界的新实例不会被创建（启用请求留在队列里自动重试）。</p>
     *
     * @param retiredState 退役前的 {@link GameState}（必须在 {@code stopGame()} 之前取得）
     */
    private void retireArena(String arenaId, ArenaInstance arena, GameState retiredState) {
        String worldName = arena.getWorldName();
        int graceSeconds = computeRetireGraceSeconds(retiredState);
        retiredArenas.put(arenaId, new RetiredArena(arena, worldName, graceSeconds));
        ensureRetiredCleanupTaskRunning();
        plugin.getLogger().info("竞技场 " + arenaId + " 已退役：世界 " + worldName
                + " 隔离 " + graceSeconds + " 秒，等待其延迟 reset 失效");
    }

    /**
     * 隔离窗口秒数。
     *
     * <p>退役前处于 VICTORY/DEFEAT 时，{@code beginEndSequence()} 的结算倒计时仍在排队
     * （倒计时结束还会再排一次延迟 reset），因此窗口 = {@code defeat-countdown-seconds}
     * + {@code settings.arena-reset-delay}；其余状态（WAITING/ENDING 等）只可能在
     * {@code stopGame()} 时排一次延迟 reset，窗口 = {@code settings.arena-reset-delay}。
     * 两种情形都在此基础上留 2 秒余量。</p>
     */
    private int computeRetireGraceSeconds(GameState retiredState) {
        int seconds = definitionManager.getArenaResetDelaySeconds() + 2;
        if (retiredState == GameState.VICTORY || retiredState == GameState.DEFEAT) {
            seconds += plugin.getConfigManager().getActiveConfig().getDefeatCountdownSeconds();
        }
        return seconds;
    }

    private void ensureRetiredCleanupTaskRunning() {
        if (retiredCleanupTask != null) return;
        retiredCleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processRetiredArenas, 20L, 20L);
    }

    private void stopRetiredCleanupTask() {
        if (retiredCleanupTask != null) {
            retiredCleanupTask.cancel();
            retiredCleanupTask = null;
        }
    }

    /**
     * 每秒递减隔离窗口。
     *
     * <p>窗口结束且该世界已不在回滚中时解除隔离；若窗口结束但世界仍在回滚，
     * 最多再等 {@link #QUARANTINE_RESTORE_EXTRA_SECONDS} 秒，避免回滚操作异常残留
     * 导致该世界被永久隔离、启用队列空转。</p>
     */
    private void processRetiredArenas() {
        if (retiredArenas.isEmpty()) {
            stopRetiredCleanupTask();
            return;
        }

        boolean releasedAny = false;
        for (Map.Entry<String, RetiredArena> entry : new ArrayList<>(retiredArenas.entrySet())) {
            RetiredArena retired = entry.getValue();
            int remaining = retired.remainingSeconds() - 1;
            boolean restoring = plugin.getMapRestoreManager().isRestoring(retired.worldName());

            boolean windowOver = remaining <= 0 && (!restoring
                    || remaining <= -QUARANTINE_RESTORE_EXTRA_SECONDS);
            if (!windowOver) {
                retiredArenas.put(entry.getKey(),
                        new RetiredArena(retired.arena(), retired.worldName(), remaining));
                continue;
            }
            if (restoring) {
                plugin.getLogger().warning("竞技场 " + entry.getKey() + " 的隔离窗口已超出等待上限，"
                        + "但世界 " + retired.worldName() + " 仍在回滚中；解除隔离（新对局会自行等待回滚完成）");
            }

            retiredArenas.remove(entry.getKey());
            releasedAny = true;
            plugin.getLogger().info("竞技场 " + entry.getKey() + " 的隔离窗口已结束，世界 "
                    + retired.worldName() + " 可以重新启用");
        }

        // 有世界被解封且仍有排队请求时，恢复启用队列处理
        if (releasedAny && !enableQueue.isEmpty()) {
            ensureEnableQueueTaskRunning();
        }
    }

    /** 该世界是否仍被某个退役实例的延迟 reset 占用。 */
    private boolean isWorldQuarantined(String worldName) {
        if (worldName == null) return false;
        for (RetiredArena retired : retiredArenas.values()) {
            if (worldName.equals(retired.worldName())) return true;
        }
        return false;
    }

    /**
     * 查找占用了同一世界的另一个竞技场（已就绪实例或启用队列中的定义）。
     *
     * <p>加载阶段已拒绝「一个世界对应多个竞技场」的配置（见 {@code ArenaManager.load()}），
     * 这里做运行期兜底：避免通过 {@code /hilltop enable} 或直接 {@code startArena} 造出
     * 两个共享世界的活动实例（共享世界会让地图回滚互相破坏）。</p>
     *
     * @return 冲突的竞技场 ID；无冲突返回 null
     */
    private String findWorldConflict(String arenaId, String worldName) {
        if (worldName == null || worldName.isBlank()) return null;

        for (ArenaInstance active : activeArenas.values()) {
            if (!active.getArenaId().equals(arenaId) && worldName.equals(active.getWorldName())) {
                return active.getArenaId();
            }
        }
        for (String queuedId : enableQueue) {
            if (queuedId.equals(arenaId)) continue;
            ArenaDefinition queued = definitionManager.getArena(queuedId);
            if (queued != null && worldName.equals(queued.getWorldName())) return queuedId;
        }
        return null;
    }

    /**
     * Start all enabled arenas.
     * Called on plugin enable.
     *
     * <p>⚠️ 已弃用（P2-35）：与 {@link #startArena(String)} 同款旧接口——实测全仓库零调用方
     * （插件启动实际走 {@code enableAllConfiguredArenas()} 的启用队列）。
     * 与之同弃用可顺便抑制内部调用的 deprecation 警告（javac 不警告「弃用实体内部」的使用）。</p>
     *
     * @deprecated 死接口；请走启用队列路径（enableAllConfiguredArenas / enableArena）。
     */
    @Deprecated(forRemoval = true)
    public void startAllArenas() {
        List<ArenaDefinition> definitions = definitionManager.getRegistry().list();
        int started = 0;

        for (ArenaDefinition definition : definitions) {
            if (definition.isEnabled()) {
                if (startArena(definition.getId())) {
                    started++;
                }
            }
        }

        plugin.getLogger().info("Started " + started + " arenas");
    }

    /**
     * Stop all running arenas.
     * Called on plugin disable.
     */
    public void stopAllArenas() {
        for (String arenaId : new ArrayList<>(activeArenas.keySet())) {
            stopArena(arenaId);
        }
    }

    /**
     * Get an active arena instance by ID.
     *
     * @param arenaId arena ID
     * @return arena instance, or null if not running
     */
    public ArenaInstance getArena(String arenaId) {
        return activeArenas.get(arenaId);
    }

    /**
     * Get all active arena instances.
     */
    public Collection<ArenaInstance> getActiveArenas() {
        return Collections.unmodifiableCollection(activeArenas.values());
    }

    /**
     * Get the arena a player is currently in.
     *
     * @param player player
     * @return arena instance, or null if not in any arena
     */
    public ArenaInstance getPlayerArena(Player player) {
        String arenaId = playerArenaMap.get(player.getUniqueId());
        return arenaId == null ? null : activeArenas.get(arenaId);
    }

    /**
     * Join a player to an arena.
     *
     * @param player player
     * @param arenaId arena ID
     * @return true if joined successfully
     */
    public boolean joinArena(Player player, String arenaId) {
        // C3-01：显式 ID 先经定义侧归一化（ArenaRegistry.get 大小写不敏感，activeArenas 按规范 ID
        // 精确键查找）——否则 /hilltop join Arena1 会拿不到实例而误报 arena-not-found，
        // 与 stop/enable/disable 的归一化口径分裂。
        String canonicalId = arenaId;
        if (arenaId != null && !arenaId.isBlank()) {
            ArenaDefinition def = definitionManager.getArena(arenaId);
            if (def != null) canonicalId = def.getId();
        }
        ArenaInstance arena = canonicalId == null ? null : activeArenas.get(canonicalId);
        if (arena == null) {
            plugin.getLanguageManager().send(player, "arena-not-found", arenaId);
            return false;
        }

        if (arena.addPlayer(player)) {
            playerArenaMap.put(player.getUniqueId(), canonicalId);
            return true;
        }

        return false;
    }

    /**
     * Remove a player from their current arena.
     *
     * @param player player
     */
    public void leaveArena(Player player) {
        ArenaInstance arena = getPlayerArena(player);
        if (arena != null) {
            arena.removePlayer(player);
            playerArenaMap.remove(player.getUniqueId());
        }
    }

    /**
     * 玩家被移出竞技场时清除「玩家 → 竞技场」映射。
     *
     * <p>由 {@link ArenaInstance#removePlayer} / {@link ArenaInstance#removeOfflinePlayer} 统一回调。
     * 这两条路径既可能源自 {@link #leaveArena}，也可能源自批量踢人
     * （{@code stopGame()} / {@code finishSession()} / {@code resetNow()}），
     * 后者原先不会经过 {@code leaveArena}，映射因此残留：玩家已在大厅却仍被判为「在竞技场内」，
     * 导致登录落地、大厅复活点、退服重连窗口、烈焰蛋竞技场判定全部走错分支。</p>
     */
    public void forgetPlayerArena(UUID uuid) {
        if (uuid != null) playerArenaMap.remove(uuid);
    }

    /**
     * 竞技场退役时兜底清除该竞技场名下的全部映射。
     *
     * <p>离线玩家不会经过 {@code removePlayer}（他们走的是重连超时分支），
     * 因此退役/停止竞技场时需要按 arenaId 反查一次，避免这些条目指向已从
     * {@link #activeArenas} 移除的实例、变成永久垃圾。</p>
     */
    private void purgePlayerArenaMapping(String arenaId) {
        if (arenaId == null) return;
        playerArenaMap.entrySet().removeIf(entry -> arenaId.equals(entry.getValue()));
    }

    /**
     * Auto-join a player to any available arena.
     * Prefers arenas with more players (to avoid spreading players thin).
     *
     * @param player player
     * @return true if joined successfully
     */
    public boolean autoJoinArena(Player player) {
        // Get all available arenas
        List<ArenaInstance> available = new ArrayList<>();
        for (ArenaInstance arena : activeArenas.values()) {
            if (arena.isAvailable()) {
                available.add(arena);
            }
        }

        if (available.isEmpty()) {
            plugin.getLanguageManager().send(player, "no-arenas-available");
            return false;
        }

        // Sort by player count (descending) - prefer fuller arenas
        available.sort((a, b) -> Integer.compare(b.getPlayerCount(), a.getPlayerCount()));

        // Join the first one
        ArenaInstance arena = available.get(0);
        return joinArena(player, arena.getArenaId());
    }

    /**
     * Get available arenas (can be joined).
     */
    public List<ArenaInstance> getAvailableArenas() {
        List<ArenaInstance> available = new ArrayList<>();
        for (ArenaInstance arena : activeArenas.values()) {
            if (arena.isAvailable()) {
                available.add(arena);
            }
        }
        return available;
    }

    /**
     * Get the default arena (for /hilltop join with no arguments).
     */
    public ArenaInstance getDefaultArena() {
        ArenaDefinition defaultDef = definitionManager.getDefaultArena();
        if (defaultDef == null) {
            return null;
        }
        return activeArenas.get(defaultDef.getId());
    }

    /**
     * Get the first active arena (fallback when no default arena exists).
     */
    public ArenaInstance getFirstActiveArena() {
        return activeArenas.values().stream().findFirst().orElse(null);
    }

    /**
     * 解析目标竞技场 <b>ID</b>（可解析出已被禁用、尚无实例的竞技场）。
     *
     * <p>优先级：显式 ID → 玩家所在竞技场 → 默认竞技场 → 第一个已配置的竞技场。</p>
     *
     * <p><b>显式 ID 未命中不回退</b>：只要传入了非空 arenaId 但没有任何定义匹配，
     * 直接返回 null（调用方据此报 {@code arena-not-found}）——旧行为会静默落回
     * 「玩家所在 → 默认 → 第一个」链，`/hilltop enable <typo>` 会误操作另一个竞技场。
     * 仅 arenaId 为 null / 空白时才走回退链。</p>
     *
     * <p>与 {@link #resolveArena(String, Player)} 的区别：后者返回运行时实例，
     * 因此无法用于 {@code /hilltop enable} 这类需要操作「未启用竞技场」的场景。</p>
     *
     * @param arenaId 显式竞技场 ID（可为 null）
     * @param player  命令发送者（可为 null）
     * @return 竞技场 ID；显式 ID 未命中或（未提供 ID 且）无任何已配置竞技场时返回 null
     */
    public String resolveArenaId(String arenaId, Player player) {
        // 键必须归一化：ArenaRegistry.get() 大小写不敏感，但 activeArenas（以及 getArena / disableArena /
        // getStatus）都按定义里的规范 ID 做精确键查找；原样返回用户输入会让 /hilltop disable Arena1 之类的
        // 调用失配（拿不到实例、状态回落成 DISABLED）。
        // 只调用一次 getArena，保证 null 判定与取 ID 用的是同一个对象。
        if (arenaId != null && !arenaId.isBlank()) {
            ArenaDefinition explicit = definitionManager.getArena(arenaId);
            return explicit != null ? explicit.getId() : null;
        }
        if (player != null) {
            String current = playerArenaMap.get(player.getUniqueId());
            if (current != null) return current;
        }
        ArenaDefinition def = definitionManager.getDefaultArena();
        if (def != null) return def.getId();

        List<ArenaDefinition> all = definitionManager.getRegistry().list();
        return all.isEmpty() ? null : all.get(0).getId();
    }

    /**
     * Resolve the target arena for a command.
     * Priority: explicit arenaId → player's current arena → default arena → first active arena.
     *
     * <p><b>显式 ID 未命中不回退</b>：只要传入了非空 arenaId 但没有任何活动实例匹配，
     * 直接返回 null（调用方据此报 {@code arena-not-found}）——旧行为会静默落回
     * 「玩家所在 → 默认 → 第一个活动」链，`/hilltop stop <typo>` 会停掉另一个竞技场的对局。
     * 仅 arenaId 为 null / 空白时才走回退链。</p>
     *
     * @param arenaId explicit arena ID (may be null)
     * @param player  command sender if a player (may be null)
     * @return resolved arena, or null if none found (explicit ID given but unknown → also null)
     */
    public ArenaInstance resolveArena(String arenaId, Player player) {
        if (arenaId != null && !arenaId.isBlank()) {
            // 先精确命中活动实例（原有语义保留）；再按定义归一化大小写查一次
            // （ArenaRegistry.get() 大小写不敏感，但 activeArenas 按规范 ID 精确键）。
            // 两种情况都未命中时直接返回 null，绝不掉进回退链（M-09：打错的 ID 静默改靶）。
            ArenaInstance explicit = activeArenas.get(arenaId);
            if (explicit != null) return explicit;
            ArenaDefinition explicitDef = definitionManager.getArena(arenaId);
            if (explicitDef == null) return null;
            return activeArenas.get(explicitDef.getId());
        }
        if (player != null) {
            ArenaInstance playerArena = getPlayerArena(player);
            if (playerArena != null) return playerArena;
        }
        ArenaInstance def = getDefaultArena();
        if (def != null) return def;
        return getFirstActiveArena();
    }

    /**
     * Reload arena definitions from arenas.yml.
     * Note: Does NOT restart running arenas.
     */
    public void reloadDefinitions() {
        definitionManager.reload();
        plugin.getLogger().info("Reloaded arena definitions");
    }

    /**
     * 让所有活动竞技场的 AdminDeployManager 重新从 arenas.yml 读取部署数据。
     *
     * <p>每个 {@code AdminDeployManager} 在构造时就把整份 arenas.yml 读进内存副本，
     * {@code saveSpawnPoints()} 时整份写回。因此重置竞技场 A（删掉 A 的键）之后，
     * 管理员只要在竞技场 B 执行一次部署命令，B 就会用构造时的旧副本回写，
     * 把 A 被删掉的键全部「复活」（重启后表现为「重置没生效」）。
     * 删除了某竞技场的键之后必须调用本方法，让其它竞技场改用最新文件内容。</p>
     *
     * <p>逐个竞技场做异常隔离：单个竞技场重读失败只记录日志，不影响其余竞技场。</p>
     */
    public void reloadAllDeploymentData() {
        for (ArenaInstance arena : new ArrayList<>(getActiveArenas())) {
            if (arena == null) continue;
            try {
                GameManager gameManager = arena.getGameManager();
                if (gameManager == null || gameManager.getAdminDeployManager() == null) continue;
                gameManager.getAdminDeployManager().loadSpawnPoints();
            } catch (Exception exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "重读竞技场部署数据失败，已跳过: " + arena.getArenaId(), exception);
            }
        }
    }

    // ========== arenas.yml settings 段（全局设置，转发给定义管理器读取） ==========

    /** 插件启动时是否自动启用所有已启用的竞技场（{@code settings.auto-start-arenas}，缺省 true） */
    public boolean isAutoStartArenas() {
        return definitionManager.isAutoStartArenas();
    }

    /** 玩家退服时是否自动离开竞技场（{@code settings.auto-leave-on-quit}，缺省 true） */
    public boolean isAutoLeaveOnQuit() {
        return definitionManager.isAutoLeaveOnQuit();
    }

    /** 游戏开始倒计时（秒，{@code settings.start-countdown}，缺省 10） */
    public int getStartCountdownSeconds() {
        return definitionManager.getStartCountdownSeconds();
    }

    /** 竞技场重置延迟（tick，{@code settings.arena-reset-delay} 秒 × 20，缺省 100 tick = 5 秒） */
    public long getArenaResetDelayTicks() {
        return definitionManager.getArenaResetDelaySeconds() * 20L;
    }

    // ========== World Management (参考 BedWars1058) ==========

    /**
     * 大厅出生点（来自 arenas.yml 的 lobby.world + lobby.spawn）。
     * 未配置或世界未加载时返回 null。
     */
    public Location getLobbySpawn() {
        return definitionManager.getLobbySpawn();
    }

    /**
     * 把当前位置写为大厅出生点（fix4 反馈9，{@code /hilltop lobby setspawn}）。
     * 写盘走定义管理器的「重读盘 + 只改 lobby.spawn 键」路径，写后即时生效。
     *
     * @param location 新的出生点（须位于大厅世界；世界校验由命令层完成）
     * @return true = 已写入磁盘并同步内存；false = 写盘失败
     */
    public boolean setLobbySpawn(Location location) {
        return definitionManager.updateLobbySpawn(location);
    }

    /**
     * 大厅世界（未配置或未加载返回 null）。
     */
    public World getLobbyWorld() {
        String name = definitionManager.getLobbyWorldName();
        return name == null ? null : Bukkit.getWorld(name);
    }

    /**
     * Check if a world is a lobby world.
     * ✅ 用于监听器判断是否在大厅世界（需要保护）
     *
     * <p>大小写不敏感比较（fix4 附带修复，仿 P2-29/C2-01）：Bukkit 的世界查找不区分大小写
     * （{@code Bukkit.getWorld("lobby")} 能找到文件夹为 {@code Lobby} 的世界），若此处精确
     * {@code equals} 而 arenas.yml 的 {@code lobby.world} 大小写与实际文件夹不符，大厅保护 /
     * 大厅复活 / 准入判定会全部静默失效。{@link #getLobbySpawn()} 走的是大小写不敏感的
     * Bukkit 查找，两边口径由此统一。</p>
     *
     * @param worldName world name
     * @return true if it's the lobby world
     */
    public boolean isLobbyWorld(String worldName) {
        if (worldName == null) return false;
        String lobbyWorld = definitionManager.getLobbyWorldName();
        return lobbyWorld != null && lobbyWorld.equalsIgnoreCase(worldName);
    }

    /**
     * 传送玩家回大厅（大厅未配置时回退到主世界出生点）。
     */
    public void sendToLobby(Player player) {
        Location spawn = getLobbySpawn();
        player.teleport(spawn != null ? spawn : Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    /**
     * 将玩家置于大厅状态：传送到大厅出生点并重置血量/饱食/模式/药水/飞行。
     * 玩家已在大厅时不重复传送（避免每次进服拉扯视角）。
     *
     * @return true 表示执行了传送
     */
    public boolean applyLobbyState(Player player) {
        boolean teleported = false;
        Location spawn = getLobbySpawn();
        if (spawn != null && (player.getWorld() != spawn.getWorld()
                || player.getLocation().distanceSquared(spawn) > 4.0)) {
            player.teleport(spawn);
            teleported = true;
        }

        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        for (var effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        return teleported;
    }

    /**
     * Get the arena instance by world name.
     * ✅ 用于根据世界查找对应的竞技场
     *
     * <p>世界唯一性由 {@code ArenaManager.load()}（加载阶段拒绝重复世界的定义）与
     * {@link #findWorldConflict(String, String)}（启用阶段兜底）共同保证，
     * 因此同一世界最多只有一个活动实例，这里的首次命中即唯一结果。</p>
     *
     * @param worldName world name
     * @return arena instance, or null if not found
     */
    public ArenaInstance getArenaByWorld(String worldName) {
        if (worldName == null) return null;

        for (ArenaInstance arena : activeArenas.values()) {
            if (worldName.equals(arena.getWorldName())) {
                return arena;
            }
        }

        return null;
    }

    /**
     * Check if a world is an arena world.
     * ✅ 用于判断世界是否是竞技场世界
     *
     * @param worldName world name
     * @return true if it's an arena world
     */
    public boolean isArenaWorld(String worldName) {
        return getArenaByWorld(worldName) != null;
    }

    /**
     * Get the definition manager (for admin commands).
     */
    public ArenaManager getDefinitionManager() {
        return definitionManager;
    }

    /**
     * Check if a player is in any arena.
     */
    public boolean isInArena(Player player) {
        return playerArenaMap.containsKey(player.getUniqueId());
    }

    /**
     * 按给定状态顺序统计活动竞技场数量。
     *
     * <p>返回数组与 {@code states} 一一对应（调用方按返回顺序拼到 i18n 文本里）。
     * 不在此枚举到的状态（含 {@code WAVE_INTERMISSION} / {@code VICTORY} / {@code DEFEAT}）
     * 不计入任何桶 —— 它们是瞬时的对局内状态，统计口径保持旧行为不变。</p>
     */
    public int[] countByState(GameState... states) {
        int[] counts = new int[states.length + 1];
        for (ArenaInstance arena : activeArenas.values()) {
            GameState state = arena.getState();
            for (int i = 0; i < states.length; i++) {
                if (state == states[i]) {
                    counts[i + 1]++;
                    break;
                }
            }
        }
        counts[0] = activeArenas.size();
        return counts;
    }
}
