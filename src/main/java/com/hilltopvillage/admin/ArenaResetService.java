package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.mechanics.MapRestoreManager;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.shop.NpcManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 竞技场「恢复初始状态」的编排器（竞技场管理 GUI 的「重置竞技场」按钮）。
 *
 * <p>一次重置 = 把某个竞技场彻底还原成「从未部署过」的样子：</p>
 * <ol>
 *   <li>退役并移除活动实例（内部完成踢人 / 归还背包 / 送回大厅 / 退役隔离窗口登记）</li>
 *   <li>删除 nodes.yml 中属于该竞技场世界的全部节点条目</li>
 *   <li>清空 arenas.yml 中的部署数据（core / spawn-points / villagers / shop-npc / game.* / 半径偏移）</li>
 *   <li>强制回滚该世界的地图方块改动（{@link MapRestoreManager}）</li>
 * </ol>
 *
 * <p>重置完成后该竞技场仍留在 registry 中、显示为「未部署」，可以重新走一遍部署流程 ——
 * 这是本服务与「删除竞技场」的本质区别。</p>
 *
 * <p><b>重置成功 ≠ 自动恢复可用</b>：第 3 步删掉的 {@code core} 是 {@code enableArena} 的硬前提，
 * 因此重启用判定会短路为 {@link ReinstateSkip#MISSING_CORE}（INFO 级日志，属预期行为），
 * 竞技场保持 DISABLED，等管理员重新部署后再由 {@code /hilltop enable} 启用。
 * 未启用的具体原因通过 {@link Outcome#reinstateSkipped()} 交给 GUI 显示。</p>
 *
 * <p><b>线程约束：必须在 Bukkit 主线程调用。</b>{@link MapRestoreManager} 的方块操作带主线程断言，
 * 本服务内部也不使用任何异步任务；{@link #reset} 的完成回调同样保证在主线程触发。</p>
 *
 * <p><b>为什么第 4 步「禁用实例」必须最先做</b>：实例消失才能杜绝重置期间有新玩家加入或自动开局。
 * 且<b>必须走 {@link MultiArenaManager#disableArena(String)}</b>，
 * 绝不能自己调 {@code ArenaInstance#stopGame()}：后者只调度「延迟 reset」而无法取消，
 * 退役隔离窗口的登记就在 {@code disableArena} 内部；绕过它会让旧实例的延迟 reset 到点后清空整个世界。</p>
 */
public final class ArenaResetService {

    /** 日志统一前缀（管理类操作走日志而非 i18n，便于服主排查） */
    private static final String LOG = "[manage-reset] ";

    private final HilltopVillagePlugin plugin;

    /**
     * in-flight 门：key = 规范化后的竞技场 ID（registry 的查找忽略大小写，见 {@code ArenaRegistry}）。
     * 只做「有没有正在跑的重置」判定，不保存状态；用 {@code remove} 的返回值充当幂等门。
     */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public ArenaResetService(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /** 重置准入判定结果 */
    public enum Gate {

        /** 可以重置 */
        OK,

        /** registry 里没有这个竞技场 */
        ARENA_NOT_FOUND,

        /** 状态为 QUEUED / LOADING：启用队列正在加载，此时重置会与旧定义建实例竞争 */
        STATUS_PENDING,

        /** {@code definition.getCore() == null}：尚未部署，无需重置 */
        MISSING_CORE,

        /** 实例存在且 GameState ∈ {RUNNING, WAVE_INTERMISSION, STARTING} */
        GAME_IN_PROGRESS,

        /** 实例存在且 GameState == ENDING：上一次地图回滚未完成 */
        RESET_IN_PROGRESS,

        /** 本服务已有正在执行的重置任务 */
        ALREADY_RESETTING
    }

    /**
     * 重置完成后「没有重新启用竞技场」的具体原因。
     *
     * <p>注意与入口闸门 {@link Gate#MISSING_CORE} 是两回事：闸门说的是「这个竞技场本来就还没部署，
     * 没什么可重置的」，本枚举说的是「重置已经成功完成，但（按设计或按配置）不把它重新放回启用队列」。</p>
     */
    public enum ReinstateSkip {

        /** 重置前就没有活动实例（wasReady == false），本来就不需要重新启用 */
        NOT_READY_BEFORE,

        /** 核心坐标已被本次重置清空 —— 预期行为：需管理员重新部署后再启用 */
        MISSING_CORE,

        /** 配置中该竞技场为 enabled: false，不自动启用 */
        CONFIG_DISABLED,

        /** 定义、核心、enabled 都正常，enableArena 却拒绝了 —— 非预期，需要排查 */
        ENABLE_REJECTED
    }

    /**
     * 一次重置的结果快照。
     *
     * @param arenaId             规范化后的竞技场 ID
     * @param dataCleared         部署数据（arenas.yml）是否已清空
     * @param nodesCleared        节点清理是否成功完成（本来就没有节点也算成功）
     * @param shopNpcCleared      是否确实清掉了一个「已配置的」商店 NPC（本来就没有 → false）
     * @param mapRestored         地图方块是否已完整回滚（含「本来就无需回滚」的情形）
     * @param mapRestoreSkipped   是否跳过了地图回滚（无需回滚，或世界未加载无法回滚）
     * @param reinstated          是否成功把竞技场重新放回启用队列
     * @param statusAfter         重置结束后的竞技场状态
     * @param blockedBy           非 {@link Gate#OK} 表示这次调用根本没开始（此时其余字段均为 false / 未尝试）
     * @param blocksPendingBefore 本次重置开始前该世界待回滚的方块数
     * @param failStep            null = 全成功；否则为失败步骤名：
     *                            {@code nodes} / {@code shop-npc} / {@code deployment} / {@code restore}，
     *                            兜底的未预期异常记为 {@code internal}
     * @param reinstateSkipped    未重新启用的原因；reinstated == true 时为 null。
     *                            <p>仅当流程真正走到第 12 步的「重新启用」判定、且判定为不启用（或被拒）时非 null。
     *                            中止（failStep = {@code nodes}/{@code deployment}）、地图回滚失败
     *                            （failStep = {@code restore}）以及 blockedBy != OK 这几条路径压根没进入重启用判定，
     *                            此时同样为 null —— 调用方应先看 blockedBy / failStep 判断整体成败，
     *                            再用本字段为「重置成功但未启用」选择文案。</p>
     */
    public record Outcome(
            String arenaId,
            boolean dataCleared,
            boolean nodesCleared,
            boolean shopNpcCleared,
            boolean mapRestored,
            boolean mapRestoreSkipped,
            boolean reinstated,
            ArenaStatus statusAfter,
            Gate blockedBy,
            int blocksPendingBefore,
            String failStep,
            ReinstateSkip reinstateSkipped
    ) {}

    /** 一次重置的不可变上下文（准入通过后创建，供完成 / 中止路径复用） */
    private record ResetCtx(
            String arenaId,
            String world,
            boolean worldLoaded,
            boolean wasReady,
            boolean hadShopNpc,
            int pendingBlocksBefore,
            long startedAt
    ) {}

    // ========== 查询 ==========

    /**
     * 判定该竞技场当前能否重置。
     *
     * <p>顺序说明：{@link Gate#ALREADY_RESETTING} 放在最前。一次正在执行的重置中途会先删掉
     * core、再把状态压回 DISABLED，此时其余判定只会给出误导性结论（MISSING_CORE / STATUS_PENDING），
     * 只有「正在重置」才是调用方真正需要看到的答案。</p>
     */
    public Gate canReset(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        String key = definition == null ? arenaId : definition.getId();
        if (key != null && inFlight.containsKey(key)) return Gate.ALREADY_RESETTING;
        if (definition == null) return Gate.ARENA_NOT_FOUND;

        MultiArenaManager multi = plugin.getMultiArenaManager();
        if (multi.getStatus(definition.getId()).isPending()) return Gate.STATUS_PENDING;
        if (definition.getCore() == null) return Gate.MISSING_CORE;

        ArenaInstance live = multi.getArena(definition.getId());
        if (live != null) {
            GameState state = live.getState();
            if (state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION
                    || state == GameState.STARTING) {
                return Gate.GAME_IN_PROGRESS;
            }
            if (state == GameState.ENDING) return Gate.RESET_IN_PROGRESS;
        }
        return Gate.OK;
    }

    /** 本服务是否有针对该竞技场的 in-flight 重置任务（无论它最终成功与否）。 */
    public boolean isResetting(String arenaId) {
        ArenaDefinition definition = resolve(arenaId);
        String key = definition == null ? arenaId : definition.getId();
        return key != null && inFlight.containsKey(key);
    }

    // ========== 执行 ==========

    /**
     * 执行完整重置。
     *
     * <p>回调保证在主线程、且<b>只调用一次</b>；注意它<b>可能在本调用栈内同步触发</b>
     * （世界没有任何待回滚方块时 {@code MapRestoreManager} 会直接回完成通知），
     * 调用方不得假设回调一定发生在 {@code reset} 返回之后。</p>
     */
    public void reset(String arenaId, Consumer<Outcome> onDone) {
        long startedAt = System.currentTimeMillis();

        ArenaDefinition definition = resolve(arenaId);
        Gate gate = canReset(arenaId);
        if (gate != Gate.OK) {
            deliver(onDone, blockedOutcome(arenaId, definition, gate));
            return;
        }

        String key = definition.getId();
        // 主线程内重入只可能来自「完成后立刻再点一次」与上一条回调的交错，putIfAbsent 足够
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            deliver(onDone, blockedOutcome(arenaId, definition, Gate.ALREADY_RESETTING));
            return;
        }

        try {
            proceed(key, startedAt, onDone);
        } catch (RuntimeException exception) {
            // 兜底：无论中途哪里抛异常，都必须把门释放并给调用方一个结果，否则 GUI 会永久等待。
            // 若异常发生在 restoreWithResult 已收下回调之后，那次回调会因门已被摘掉而自动失声（见 finish）。
            plugin.getLogger().log(Level.SEVERE, LOG + "failed arena=" + key + " step=internal", exception);
            if (releaseInFlight(key)) {
                deliver(onDone, new Outcome(key, false, false, false, false, false, false,
                        safeStatus(key), null, pendingFor(key), "internal", null));
            }
        }
    }

    /**
     * 插件关闭时清理 in-flight 记录（不中断正在进行的回滚，回滚由
     * {@link MapRestoreManager#restoreAllNow()} 兜底）。
     */
    public void shutdown() {
        inFlight.clear();
    }

    // ========== 12 步序列 ==========

    private void proceed(String arenaId, long startedAt, Consumer<Outcome> onDone) {
        MultiArenaManager multi = plugin.getMultiArenaManager();
        MapRestoreManager mapRestore = plugin.getMapRestoreManager();

        // 1. 定义必然存在（canReset 已判定 OK）
        ArenaDefinition definition = multi.getDefinitionManager().getArena(arenaId);
        String world = definition.getWorldName();
        boolean worldLoaded = world != null && Bukkit.getWorld(world) != null;

        // 3. 实例引用必须在 disableArena 之前取：disableArena 会把实例从 activeArenas 移除
        ArenaInstance live = multi.getArena(arenaId);
        boolean wasReady = live != null;

        int pendingBefore = pendingFor(world);
        plugin.getLogger().info(LOG + "start arena=" + arenaId + " world=" + world
                + " wasReady=" + wasReady + " pendingBlocks=" + pendingBefore);

        ResetCtx ctx = new ResetCtx(arenaId, world, worldLoaded, wasReady,
                definition.getShopNpc() != null, pendingBefore, startedAt);

        // 4. 最先禁用实例：踢人 / 归还背包 / 送回大厅 / 退役隔离窗口登记都在 disableArena 内部
        if (wasReady) multi.disableArena(arenaId);

        // 5. 一次落盘删除该世界在 nodes.yml 的全部条目（世界未加载也能做）
        File dataFolder = plugin.getDataFolder();
        int expectedNodes = NodeSystem.readNodesForWorld(dataFolder, world).size();
        int removedNodes = NodeSystem.clearNodesForWorld(dataFolder, world);
        plugin.getLogger().info(LOG + "nodes-cleared arena=" + arenaId + " count=" + removedNodes);
        // clearNodesForWorld 的返回值 0 有两种含义（没有条目 / 写入失败），用清点结果消歧
        if (expectedNodes > 0 && removedNodes == 0) {
            abort(ctx, "nodes", "nodes.yml 写入失败（清点 " + expectedNodes + " 条，实际删除 0 条）",
                    false, false, onDone);
            return;
        }

        // 6. 内存一致：退役实例的节点表仍持有这些节点，清掉并幂等重写一次 nodes.yml
        NodeSystem liveNodes = live == null ? null : live.getNodeSystem();
        if (liveNodes != null) liveNodes.clearAllNodes();

        // 7. 商店 NPC：第 8 步只会删掉 arenas.yml 里的位置（= 下次不再生成），
        //    已经站在世界里的村民实体不会因此消失，且它带 PDC 标记、右键仍能打开商店。
        //    这里按「实体所在世界 == 本竞技场世界」精确清掉它。
        //    ⚠️ 绝不能改用 NpcManager.cleanupOrphans()：那会删掉所有世界的商店 NPC，
        //    包括大厅与其它竞技场的（商店是插件级单例，位置在哪个世界就归哪个竞技场）。
        if (despawnOwnShopNpc(world)) {
            plugin.getLogger().info(LOG + "shop-npc-despawned arena=" + arenaId + " world=" + world);
        }

        // 8. 清空部署数据（内部重建 registry，definition 引用自此变陈旧）
        ArenaManager definitions = multi.getDefinitionManager();
        boolean dataOk = definitions.clearDeploymentData(arenaId);
        if (dataOk) plugin.getLogger().info(LOG + "deployment-cleared arena=" + arenaId);
        if (!dataOk) {
            abort(ctx, "deployment", "clearDeploymentData 返回 false（竞技场不存在或 arenas.yml 写入失败）",
                    true, false, onDone);
            return;
        }

        // 9. 让其它竞技场的 AdminDeployManager 重读最新 arenas.yml：
        //    否则它们下次 saveSpawnPoints 会用构造时的整份旧副本回写，把刚删的键全部「复活」
        multi.reloadAllDeploymentData();

        // 10. 地图回滚
        int pending = pendingFor(world);
        if (pending == 0) {
            // 没有待回滚方块 = 地图本来就是干净的，等价于回滚成功
            finish(ctx, true, true, null, true, true, onDone);
            return;
        }
        if (!worldLoaded) {
            // 世界没加载就动不了方块：保持 DISABLED，等世界可用后由管理员重试
            plugin.getLogger().warning(LOG + "aborted arena=" + arenaId
                    + " step=restore reason=世界未加载，无法回滚 " + pending + " 个方块");
            finish(ctx, false, true, "restore", true, true, onDone);
            return;
        }

        // 回调可能在本调用栈内同步触发，finish 必须重入安全
        boolean accepted = mapRestore.restoreWithResult(world, mapOk ->
                finish(ctx, mapOk, false, mapOk ? null : "restore", true, true, onDone));
        plugin.getLogger().info(LOG + "restore-requested arena=" + arenaId + " blocks=" + pending
                + " accepted=" + accepted);
        if (!accepted) {
            // 世界名空白 / 世界不可用：restoreWithResult 直接返回 false 且不回调，必须自己收尾
            plugin.getLogger().warning(LOG + "aborted arena=" + arenaId
                    + " step=restore reason=restoreWithResult 拒绝请求（世界名空白或世界不可用）");
            finish(ctx, false, false, "restore", true, true, onDone);
        }
    }

    /**
     * 第 12 步：收尾。
     *
     * <p><b>重入安全</b>：{@code MapRestoreManager.restoreWithResult} 有三条到达本方法的路径 ——</p>
     * <ul>
     *   <li><b>同步回调</b>：日志为空（或世界名合法且世界已加载但请求被合并后又立即完成）时，
     *       回调在 {@code reset()} 的调用栈内直接执行；</li>
     *   <li><b>合并回调</b>：世界已有活动回滚任务时，本服务的回调被追加进该任务的完成列表，
     *       稍后（或同一 tick 的后续批次）触发；</li>
     *   <li><b>无回调</b>：世界名空白 / 世界不可用 → 返回 false 且不回调，
     *       由 {@link #proceed} 主动调用本方法收尾。</li>
     * </ul>
     * <p>以及 {@code proceed} 中途失败/抛异常时的中止路径。所有路径统一用
     * {@link #releaseInFlight(String)} 的 {@code remove} 返回值当唯一门：只有摘掉 in-flight 记录的
     * 那一次调用会执行副作用（重新启用竞技场）并回调，其余一律静默返回 —— 因此
     * 「只回调一次」与「enableArena 只执行一次」同时成立。</p>
     */
    private void finish(ResetCtx ctx, boolean mapRestored, boolean mapRestoreSkipped, String failStep,
                        boolean nodesCleared, boolean dataCleared, Consumer<Outcome> onDone) {
        if (!releaseInFlight(ctx.arenaId())) return;

        boolean reinstated = false;
        ReinstateSkip reinstateSkipped = null;
        String step = failStep;
        if (!mapRestored) {
            // 与 ArenaInstance.reset 的保守策略一致：不回滚成功就保持 DISABLED，不开放一张半恢复的地图。
            // 这条路径不进入重启用判定，reinstateSkipped 保持 null（整体失败由 failStep 表达）。
            step = "restore";
            if (!mapRestoreSkipped) {
                plugin.getLogger().severe(LOG + "restore-failed arena=" + ctx.arenaId() + " world=" + ctx.world());
            }
        } else {
            reinstateSkipped = reinstate(ctx);
            reinstated = reinstateSkipped == null;
        }

        Outcome outcome = outcome(ctx, nodesCleared, dataCleared, mapRestored, mapRestoreSkipped,
                reinstated, reinstateSkipped, null, step);
        logDone(ctx, outcome);
        deliver(onDone, outcome);
    }

    /**
     * 中止：记录 WARN、释放 in-flight 门、回调一次，后续步骤一律不再执行。
     */
    private void abort(ResetCtx ctx, String step, String reason, boolean nodesCleared, boolean dataCleared,
                       Consumer<Outcome> onDone) {
        plugin.getLogger().warning(LOG + "aborted arena=" + ctx.arenaId() + " step=" + step + " reason=" + reason);
        if (!releaseInFlight(ctx.arenaId())) return;

        Outcome outcome = outcome(ctx, nodesCleared, dataCleared, false, false, false, null, null, step);
        logDone(ctx, outcome);
        deliver(onDone, outcome);
    }

    /**
     * 第 12 步的重新启用判定：让新 ArenaDefinition 装配进新 ArenaInstance 的唯一途径。
     *
     * <p><b>为什么必须先查 core</b>：本次重置刚刚删掉了 arenas.yml 里的 {@code core}，而
     * {@link MultiArenaManager#enableArena(String)} 在 {@code definition.getCore() == null} 时会
     * 直接返回 false 并打印「拒绝启用竞技场 …：尚未部署核心」——既不进启用队列也不会重试。
     * 那既不是错误也不该报 WARN（核心被清空正是重置的预期结果），因此在调用前先自行短路，
     * 把「未启用的原因」如实回传给 GUI。</p>
     *
     * @return null = 已成功放回启用队列；否则为未启用的具体原因
     */
    private ReinstateSkip reinstate(ResetCtx ctx) {
        if (!ctx.wasReady()) {
            plugin.getLogger().info(LOG + "reinstate-skipped arena=" + ctx.arenaId()
                    + " reason=NOT_READY_BEFORE 重置前没有活动实例，无需重新启用");
            return ReinstateSkip.NOT_READY_BEFORE;
        }

        try {
            MultiArenaManager multi = plugin.getMultiArenaManager();
            ArenaDefinition fresh = multi.getDefinitionManager().getArena(ctx.arenaId());
            if (fresh == null) {
                // 定义在重置过程中被外部删除：不该发生，按「本该成功却被拒」处理
                plugin.getLogger().warning(LOG + "reinstate-rejected arena=" + ctx.arenaId()
                        + " reason=ENABLE_REJECTED 竞技场定义已不存在");
                return ReinstateSkip.ENABLE_REJECTED;
            }
            if (fresh.getCore() == null) {
                plugin.getLogger().info(LOG + "reinstate-skipped arena=" + ctx.arenaId()
                        + " reason=MISSING_CORE 核心坐标已清空，需重新部署后再启用");
                return ReinstateSkip.MISSING_CORE;
            }
            if (!fresh.isEnabled()) {
                plugin.getLogger().info(LOG + "reinstate-skipped arena=" + ctx.arenaId()
                        + " reason=CONFIG_DISABLED 配置中该竞技场为 enabled: false");
                return ReinstateSkip.CONFIG_DISABLED;
            }
            if (multi.enableArena(ctx.arenaId())) return null;

            plugin.getLogger().warning(LOG + "reinstate-rejected arena=" + ctx.arenaId()
                    + " reason=ENABLE_REJECTED enableArena 返回 false（核心与 enabled 均正常）");
            return ReinstateSkip.ENABLE_REJECTED;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, LOG + "reinstate-failed arena=" + ctx.arenaId(), exception);
            return ReinstateSkip.ENABLE_REJECTED;
        }
    }

    // ========== 工具 ==========

    /** 按 registry 的「忽略大小写」口径解析竞技场定义；不存在或 id 空白返回 null。 */
    private ArenaDefinition resolve(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return null;
        return plugin.getMultiArenaManager().getDefinitionManager().getArena(arenaId);
    }

    /**
     * 摘掉 in-flight 记录；返回 true 表示本次调用是「赢家」。
     * 所有终态路径都必须先过这道门，这是幂等与「只回调一次」的唯一保证。
     */
    private boolean releaseInFlight(String arenaId) {
        return arenaId != null && inFlight.remove(arenaId) != null;
    }

    private Outcome outcome(ResetCtx ctx, boolean nodesCleared, boolean dataCleared, boolean mapRestored,
                            boolean mapRestoreSkipped, boolean reinstated, ReinstateSkip reinstateSkipped,
                            Gate blockedBy, String failStep) {
        return new Outcome(ctx.arenaId(),
                dataCleared,
                nodesCleared,
                ctx.hadShopNpc() && dataCleared,
                mapRestored,
                mapRestoreSkipped,
                reinstated,
                safeStatus(ctx.arenaId()),
                blockedBy,
                ctx.pendingBlocksBefore(),
                failStep,
                reinstateSkipped);
    }

    /**
     * 未开始执行时的结果（blockedBy != OK）。此时一个字段都不该谎报成功，
     * 但 {@code blocksPendingBefore} / {@code statusAfter} 仍尽力给出真实值，供 GUI 提示用。
     */
    private Outcome blockedOutcome(String arenaId, ArenaDefinition definition, Gate gate) {
        String key = definition == null ? arenaId : definition.getId();
        return new Outcome(key, false, false, false, false, false, false,
                safeStatus(key), gate, pendingFor(key), null, null);
    }

    /**
     * 查询某世界待回滚方块数。世界名为空（拿到定义但 world 未配置）时返回 0：
     * {@link MapRestoreManager#getPendingCount(String)} 的底层是 ConcurrentHashMap，传 null 会抛 NPE。
     */
    private int pendingFor(String worldName) {
        if (worldName == null || worldName.isBlank()) return 0;
        return plugin.getMapRestoreManager().getPendingCount(worldName);
    }

    /**
     * 清掉「本竞技场世界里」的商店 NPC 实体，返回是否确实清掉了一个。
     *
     * <p>插件级商店是单例（{@link com.hilltopvillage.shop.NpcManager} 只持有一个实体），
     * 位置在哪个世界就归哪个竞技场，因此用<b>世界名相等</b>作为归属判定：世界不匹配说明这个商店
     * 是别的竞技场或大厅的，保持不动。</p>
     *
     * <p>世界不加载也不会误判——插件全库没有任何 {@code unloadWorld} 调用，竞技场世界一旦加载
     * 就常驻，因此 {@code npc.getLocation()} 恒可用。</p>
     */
    private boolean despawnOwnShopNpc(String world) {
        if (world == null || world.isBlank() || plugin.getShopManager() == null) return false;
        NpcManager npcManager = plugin.getShopManager().getNpcManager();
        if (npcManager == null) return false;

        Location location = npcManager.getNpcLocation();
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(world)) return false;

        npcManager.despawnNpc();
        return true;
    }

    /** 查状态；id 为 null / 空白时返回 DISABLED，避免把 null 传进底层映射（会抛 NPE）。 */
    private ArenaStatus safeStatus(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return ArenaStatus.DISABLED;
        return plugin.getMultiArenaManager().getStatus(arenaId);
    }

    private void logDone(ResetCtx ctx, Outcome outcome) {
        plugin.getLogger().info(LOG + "done arena=" + ctx.arenaId()
                + " mapRestored=" + outcome.mapRestored()
                + " reinstated=" + outcome.reinstated()
                + " statusAfter=" + outcome.statusAfter()
                + " elapsedMs=" + (System.currentTimeMillis() - ctx.startedAt()));
    }

    /** 回调异常不得影响本服务状态：吞掉并记日志，绝不向上冒泡破坏调用方流程。 */
    private void deliver(Consumer<Outcome> onDone, Outcome outcome) {
        if (onDone == null) return;
        try {
            onDone.accept(outcome);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, LOG + "callback-failed arena=" + outcome.arenaId(), exception);
        }
    }
}
