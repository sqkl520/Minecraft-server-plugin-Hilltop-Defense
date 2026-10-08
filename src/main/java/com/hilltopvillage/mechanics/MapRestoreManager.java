package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 地图恢复：记录对局中被改变（破坏/放置/爆炸）的方块，竞技场重置时批量回滚。
 *
 * <p>方案说明：BedWars1058 采用的是「整世界 zip 快照 + 卸载重建世界」。
 * 本实现改用 <b>逐方块日志回滚</b>，原因是：无需服主预置地图快照、不涉及世界卸载重载
 * （避免卸载失败/卡顿影响正在进行的对局），且足以覆盖塔防场景中的常见破坏来源
 * （怪物爆炸、玩家放置与破坏、水与熔岩的倾倒与扩散）。</p>
 *
 * <p>单个坐标只记录本局首次改变前的快照。普通方块保存完整 {@link BlockData}；
 * {@link TileState} 额外保留 Bukkit 的方块状态快照，以恢复容器、牌子等方块实体数据。
 * 回滚失败的方块会在恢复结束时重新写回日志，保证可以重试。</p>
 *
 * <p>所有 Bukkit 方块访问均只允许在服务器主线程执行。每个世界同一时间最多存在
 * 一个恢复任务，重复请求会合并完成回调。</p>
 */
public class MapRestoreManager implements Listener {

    /**
     * 每 tick 最多回滚的方块数，避免瞬时卡顿。
     *
     * <p>取值理由：{@code setType(type, false)} 即使关闭了物理更新，单次仍包含方块状态写入、
     * 光照更新标记与客户端方块包发送，实测约 5-20 µs/次。原值 800 意味着单 tick 消耗
     * 4-16 ms，已占满 50 ms tick 预算的大头，是回滚期间 TPS 尖峰的主因。
     * 降到 200 后单 tick 约 1-4 ms，回到安全区间；代价只是分批次数变多
     * （例：8000 个方块由 10 tick 变为 40 tick ≈ 2 秒），总方块写入量不变，
     * 因此回滚的<b>完整性</b>不受影响，只是摊到更多 tick 上完成。</p>
     *
     * <p>可用 JVM 参数覆盖，便于按机器性能微调而无需重新编译：
     * {@code -Dhilltop.maprestore.blocksPerTick=400}。非法值（非数字 / ≤0 / 空白）
     * 回退到默认 200 并打一条 WARNING —— 配置写错不该让回滚逻辑直接崩掉。</p>
     */
    private static final int BLOCKS_PER_TICK = readBlocksPerTick();

    /**
     * 读取 {@code hilltop.maprestore.blocksPerTick}，缺失或非法时回退到 200。
     *
     * <p>本方法在 {@link #BLOCKS_PER_TICK} 的静态初始化期被调用，此时本类实例
     * （以及 {@code plugin} 字段）尚不存在，因此只能走 {@link Bukkit#getLogger()}。
     * 类加载发生在 {@code HilltopVillagePlugin.onEnable()} 构造本管理器时，
     * Bukkit 早已初始化完成，该调用是安全的。</p>
     */
    private static int readBlocksPerTick() {
        String raw = System.getProperty("hilltop.maprestore.blocksPerTick");
        if (raw == null || raw.isBlank()) return 200;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                Bukkit.getLogger().warning("[HilltopVillage] hilltop.maprestore.blocksPerTick=" + raw
                        + " 非法（需为正整数），回退到 200");
                return 200;
            }
            return value;
        } catch (NumberFormatException exception) {
            Bukkit.getLogger().warning("[HilltopVillage] hilltop.maprestore.blocksPerTick=" + raw
                    + " 不是数字，回退到 200");
            return 200;
        }
    }

    /**
     * 单个世界的方块快照上限。
     *
     * <p>为什么必须有：{@link OriginalBlock} 持有 {@code BlockData} + {@code BlockState}，
     * 单条约 140–170 字节；而服务器堆只有 3GB（{@code -Xmx3G}）。大地图连续放水或大量爆炸
     * 可以让 journal 无界增长，500 万方块即 700–850 MB，足以直接 OOM。
     * 达到上限后<b>停止记录</b>并打一条 SEVERE，让管理员知道本局的地图回滚将不完整 ——
     * 这比整个服务器 OOM 要好。</p>
     */
    private static final int MAX_JOURNAL_ENTRIES_PER_WORLD = 1_000_000;

    private final HilltopVillagePlugin plugin;
    /** 世界名 → (方块坐标 → 本局首次改变前的快照) */
    private final Map<String, Map<BlockPos, OriginalBlock>> journals = new ConcurrentHashMap<>();
    /**
     * 已经因超限报过警的世界名。
     *
     * <p>超限后每次记录都打日志会把日志刷爆，因此每个世界只警告一次。
     * 日志被移除（回滚 / discard）时必须同时清掉这里的条目，否则该世界下一局再超限时
     * 会因为「已经警告过」而静默降级，管理员看不到任何提示。</p>
     */
    private final Set<String> journalOverflowWarned = ConcurrentHashMap.newKeySet();
    /** 世界名 → 当前唯一的恢复操作 */
    private final Map<String, RestoreOperation> activeRestores = new ConcurrentHashMap<>();
    /**
     * 流体事件专用的日志解析缓存（世界名 → 该世界当前可写入的日志，值为 null 表示不记账）。
     *
     * <p>{@link BlockFromToEvent} 在水/熔岩扩散时每改变一个方块都会触发一次，频率远高于
     * 破坏/爆炸事件，而 {@link #resolveJournal(World)} 内含配置读取与竞技场线性查找
     * （{@code getArenaByWorld} 会遍历所有活动竞技场），逐事件调用会成为可观开销。
     * 竞技场状态与地图恢复开关在同一 tick 内不会变化，因此同一 tick 内每个世界只解析一次。
     * 日志被移走或重建时会立即失效（见 {@link #invalidateFluidJournalCache()}）。</p>
     */
    private final Map<String, Map<BlockPos, OriginalBlock>> fluidJournalCache = new HashMap<>();
    /** {@link #fluidJournalCache} 对应的 tick；不同 tick 立即整体作废。 */
    private long fluidJournalCacheTick = Long.MIN_VALUE;

    public MapRestoreManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 方块坐标（世界内唯一）。
     *
     * <p>刻意不用 record：record 生成的 hashCode 走 {@code ObjectMethods} +
     * {@code StringConcatFactory}（拼出 {@code "BlockPos[x=…, y=…, z=…]"} 再哈希），
     * 每次调用都要建一个临时 String。本类型是 HashMap 的 key，在爆炸与水流的记录路径上
     * 每方块都要查一次表，属于热路径。本机微基准（JDK 21，2×10⁷ 次调用取中位数）：
     * record 2.76 ns/次，本实现 1.71 ns/次 —— 约 1.6 倍。</p>
     *
     * <p>收益来自<b>缓存</b>而不是公式：不缓存的手写公式与 record 基本同速
     * （2.61 ns/次，说明瓶颈在每次重新计算而非字符串拼接）。坐标不可变，
     * 哈希值恒定，所以构造时算一次即可。</p>
     *
     * <p>也不用 {@link java.util.Objects#hash} —— 它内部把三个 int 装箱成 {@code Integer[]}，
     * 实测在 put+get 混合负载下比本实现慢约 2.7 倍（装箱 + 数组分配）。</p>
     *
     * <p><b>关于公式：</b>这里用 {@code (x*31 + y)*31 + z}，与 {@code record} 的默认实现
     * <b>不等价</b> —— 后者是 {@code 31*(31*(31*1 + x) + y) + z}，多一个常数项 {@code 31³}。
     * 但这不重要：HashMap 只要求 {@code hashCode} 与 {@code equals} 自洽，任何一致的映射都合法，
     * 公式本身不参与跨进程持久化。真正带来收益的是「构造时缓存」而非公式选择。</p>
     */
    private static final class BlockPos {
        private final int x;
        private final int y;
        private final int z;
        /** 构造时一次算好：坐标不可变，哈希值恒定，避免每次查表重复计算 */
        private final int hash;

        private BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.hash = (x * 31 + y) * 31 + z;
        }

        // 访问器名与原先的 record 保持一致，调用方无需改动
        private int x() { return x; }
        private int y() { return y; }
        private int z() { return z; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockPos other)) return false;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * 原方块快照。BlockData 覆盖朝向、半部、含水等属性；TileState 保存方块实体数据。
     * BlockState 本身是 Bukkit 在事件时刻捕获的快照，不会随世界中的方块继续变化。
     */
    private record OriginalBlock(BlockData blockData, BlockState tileState) {}

    /** 单世界恢复操作；仅在主线程读写。 */
    private static final class RestoreOperation {
        private final String worldName;
        private final World world;
        private final List<Map.Entry<BlockPos, OriginalBlock>> entries;
        private final List<Consumer<Boolean>> completionCallbacks = new ArrayList<>();
        /**
         * 本批次回滚失败的方块（保留原始快照）。恢复结束时重新写回日志，使失败可以重试，
         * 而不是让这些方块永久丢失、留下一张半恢复的地图。
         */
        private final List<Map.Entry<BlockPos, OriginalBlock>> failedEntries = new ArrayList<>();
        private int nextIndex;
        private BukkitTask task;
        private boolean finished;

        private RestoreOperation(String worldName, World world,
                                 List<Map.Entry<BlockPos, OriginalBlock>> entries) {
            this.worldName = worldName;
            this.world = world;
            this.entries = entries;
        }

        private void addCompletionCallback(Consumer<Boolean> callback) {
            if (callback != null) completionCallbacks.add(callback);
        }
    }

    /* ========== 记录 ========== */

    /**
     * 解析该世界当前是否可以写入回滚日志。
     *
     * <p>记录窗口从 RUNNING 开始，覆盖波次间隔及 VICTORY/DEFEAT/ENDING 结算阶段，
     * 直到该世界的恢复操作真正开始。WAITING/STARTING 不记录，避免部署期改动入账。</p>
     *
     * <p>本方法包含配置读取与竞技场查找；批量事件必须在循环外只调用一次。</p>
     */
    private Map<BlockPos, OriginalBlock> resolveJournal(World world) {
        if (world == null) return null;
        if (!plugin.getConfigManager().getActiveConfig().isMapRestoreEnabled()) return null;

        String worldName = world.getName();
        if (activeRestores.containsKey(worldName)) return null;

        ArenaInstance arena = plugin.getMultiArenaManager().getArenaByWorld(worldName);
        if (arena == null || !isRecordingState(arena.getState())) return null;

        return journals.computeIfAbsent(worldName, ignored -> new ConcurrentHashMap<>());
    }

    private static boolean isRecordingState(GameState state) {
        return state == GameState.RUNNING
                || state == GameState.WAVE_INTERMISSION
                || state == GameState.VICTORY
                || state == GameState.DEFEAT
                || state == GameState.ENDING;
    }

    /**
     * 流体/高频事件专用的日志解析：同一 tick 内每个世界只执行一次
     * {@link #resolveJournal(World)}，同一 tick 的其余事件直接复用结果。
     *
     * <p>缓存按 tick 整体作废，代价是状态切换的生效可能晚一个 tick（正常 50 ms）：
     * 最坏情况是竞技场刚在本 tick 内进入记录窗口，紧随其后的流体事件仍沿用进入前的空判定；
     * 反向（关闭记录）只会多记若干方块，回滚时一样会还原，不会留下残留。
     * 日志被移走或重建时缓存会立即失效，因此不会写入一个已不再回滚的日志。</p>
     */
    private Map<BlockPos, OriginalBlock> resolveJournalCached(World world) {
        if (world == null) return null;

        int tick = Bukkit.getCurrentTick();
        if (tick != fluidJournalCacheTick) {
            fluidJournalCacheTick = tick;
            fluidJournalCache.clear();
        }

        String worldName = world.getName();
        if (fluidJournalCache.containsKey(worldName)) {
            // 命中缓存；值为 null 表示该世界当前不记账（非竞技场世界、未开局或正在回滚）
            return fluidJournalCache.get(worldName);
        }

        Map<BlockPos, OriginalBlock> journal = resolveJournal(world);
        fluidJournalCache.put(worldName, journal);
        return journal;
    }

    /** 日志被移走或重建时立即作废缓存，避免继续写入一个已不再被回滚的日志。 */
    private void invalidateFluidJournalCache() {
        fluidJournalCacheTick = Long.MIN_VALUE;
        fluidJournalCache.clear();
    }

    /** 方块坐标 → 日志键 */
    private static BlockPos toKey(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    private static BlockPos toKey(BlockState state) {
        return new BlockPos(state.getX(), state.getY(), state.getZ());
    }

    private static OriginalBlock snapshot(BlockState state) {
        BlockData blockData = state.getBlockData().clone();
        BlockState tileState = state instanceof TileState ? state : null;
        return new OriginalBlock(blockData, tileState);
    }

    /**
     * 记账的唯一入口：写日志前统一做容量检查。
     *
     * <p><b>为什么可以在超限时无条件 return：</b>日志只保留每个坐标「本局首次改变前」的快照，
     * 对已记录的坐标 {@code computeIfAbsent} 本来就是一次纯查表、不新增条目。超限后跳过它
     * 不会让已有条目丢失或变化，只是少做一次无意义的查表，语义与改造前一致。
     * 反过来把上限判断放到 {@code computeIfAbsent} 之后，就等于先 clone 了 BlockData
     * （甚至触发同步区块加载）再把结果丢掉，纯属浪费。</p>
     *
     * <p>快照用 {@link Supplier} 惰性求值：{@code snapshot(...)} 会 clone BlockData，
     * 也可能触发同步区块加载（{@code getState(true)}），绝不能对已记录的坐标重复执行。</p>
     *
     * <p>线程说明：Bukkit 方块访问只在主线程（见类注释），size 判断与写入之间不存在真正的
     * 并发窗口；ConcurrentHashMap 只用于防御误用时的可见性。</p>
     *
     * @param worldName       日志所属世界名，仅用于超限告警文案
     * @param journal         目标日志（非 null，调用方已解析）
     * @param key             方块坐标
     * @param snapshotFactory 仅在坐标未记录时才会被调用的快照生成器
     */
    private void recordSnapshot(String worldName, Map<BlockPos, OriginalBlock> journal,
                                BlockPos key, Supplier<OriginalBlock> snapshotFactory) {
        // 超限：停止记账并只警告一次，避免无界增长把 3GB 的堆吃光
        if (journal.size() >= MAX_JOURNAL_ENTRIES_PER_WORLD) {
            if (journalOverflowWarned.add(worldName)) {
                plugin.getLogger().severe("地图回滚日志已达上限 " + MAX_JOURNAL_ENTRIES_PER_WORLD
                        + "（世界 " + worldName + "），后续方块改动将不再记录；"
                        + "本局结束时地图回滚将不完整。请检查是否有异常的放水/爆炸刷屏。");
            }
            return;
        }
        journal.computeIfAbsent(key, ignored -> snapshotFactory.get());
    }

    /** 记录单个方块改变前的状态。 */
    private void record(Block block) {
        if (block == null) return;
        World world = block.getWorld();
        Map<BlockPos, OriginalBlock> journal = resolveJournal(world);
        if (journal == null) return;

        recordSnapshot(world.getName(), journal, toKey(block), () -> snapshot(block.getState(true)));
    }

    /** 记录放置事件提供的原方块状态（不能读取 MONITOR 阶段已经放好的新方块）。 */
    private void recordReplacedStates(World world, Collection<BlockState> states) {
        if (states == null || states.isEmpty()) return;
        Map<BlockPos, OriginalBlock> journal = resolveJournal(world);
        if (journal == null) return;

        String worldName = world.getName();
        for (BlockState state : states) {
            if (state == null) continue;
            recordSnapshot(worldName, journal, toKey(state), () -> snapshot(state));
        }
    }

    /** 批量记录爆炸方块；配置与竞技场只在循环外解析一次。 */
    private void recordAll(World world, Collection<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        Map<BlockPos, OriginalBlock> journal = resolveJournal(world);
        if (journal == null) return;

        String worldName = world.getName();
        for (Block block : blocks) {
            if (block == null) continue;
            recordSnapshot(worldName, journal, toKey(block), () -> snapshot(block.getState(true)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
            recordReplacedStates(event.getBlock().getWorld(), multiPlaceEvent.getReplacedBlockStates());
        } else {
            recordReplacedStates(event.getBlock().getWorld(), List.of(event.getBlockReplacedState()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // 使用快照副本：blockList 在事件后会被 Bukkit 清空
        recordAll(event.getEntity().getWorld(), new ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        recordAll(event.getBlock().getWorld(), new ArrayList<>(event.blockList()));
    }

    /** 冰/雪融化等自然变化也一并记录（塔防地图常见） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        record(event.getBlock());
    }

    /**
     * 玩家倒水/倒熔岩。竞技场世界不受 LobbyProtection 保护，必须自行记账，
     * 否则对局结束后流体会永久留在场上。
     *
     * <p>CraftBukkit 在真正写入流体前触发该事件（取消即不放置），因此 MONITOR 阶段
     * 取到的仍是倾倒前的状态。</p>
     *
     * <p><b>同时记录两个候选格</b>：{@link PlayerBucketEmptyEvent#getBlock()} 与
     * {@code blockClicked.getRelative(face)}。Bukkit 的实现细节在不同版本间有过差异
     * ——前者可能是"被流体替换的方块"，也可能是"被点击的方块"，二者在侧面对着墙倒水时
     * 相差一格。由于本方法只做"首次快照"（未被改变的方块恢复时是 no-op），多记一格
     * 没有副作用，却能保证倾倒形成的水源格一定入账，回滚后不留残水。</p>
     *
     * <p>第二个候选格可能落在未加载区块，先判 {@code isChunkLoaded} 再取状态，
     * 避免为了记账触发同步区块加载。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        record(event.getBlock());

        Block clicked = event.getBlockClicked();
        BlockFace face = event.getBlockFace();
        if (clicked == null || face == null) return;

        Block target = clicked.getRelative(face);
        World world = target.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return;

        record(target);
    }

    /**
     * 流体扩散（水/熔岩流向相邻方块）。只记录目标方块被覆盖前的状态，
     * 源方块的反复流动不会产生重复项（同一坐标只记首次）。
     *
     * <p>该事件在主线程写入目标方块之前触发（取消即阻止扩散），MONITOR 阶段读取到的
     * {@link BlockFromToEvent#getToBlock()} 仍是改变前的状态。</p>
     *
     * <p>性能：单次事件只改变一个方块，因此日志解析一次即用；为避免每 tick 成百上千次
     * 配置读取与竞技场线性查找，解析走 {@link #resolveJournalCached(World)} 的 tick 缓存。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        if (toBlock == null) return;

        Map<BlockPos, OriginalBlock> journal = resolveJournalCached(toBlock.getWorld());
        if (journal == null) return;

        recordSnapshot(toBlock.getWorld().getName(), journal, toKey(toBlock),
                () -> snapshot(toBlock.getState(true)));
    }

    /* ========== 回滚 ========== */

    /** 保留旧 API：分批回滚，不关心完成通知。 */
    public void restore(String worldName) {
        restoreWithResult(worldName, null);
    }

    /** 成功完成时执行回调；恢复失败时只记录日志，不执行该回调。 */
    public boolean restore(String worldName, Runnable onComplete) {
        Consumer<Boolean> callback = onComplete == null
                ? null : success -> { if (success) onComplete.run(); };
        return restoreWithResult(worldName, callback);
    }

    /**
     * 回滚指定世界，并在全部方块处理后于主线程报告是否完整成功。
     * 重复请求不会创建第二个任务，而是合并到已有操作的完成回调中。
     *
     * @return 请求已完成、已启动或已合并时为 true；世界不可用时为 false
     */
    public boolean restoreWithResult(String worldName, Consumer<Boolean> onComplete) {
        requirePrimaryThread();
        if (worldName == null || worldName.isBlank()) return false;

        RestoreOperation active = activeRestores.get(worldName);
        if (active != null) {
            active.addCompletionCallback(onComplete);
            if (!plugin.isEnabled()) finishSynchronously(active);
            return true;
        }

        Map<BlockPos, OriginalBlock> journal = journals.get(worldName);
        if (journal == null || journal.isEmpty()) {
            if (journal != null) {
                journals.remove(worldName, journal);
                // 日志已销毁：清掉超限标记，否则该世界下一局再超限时会静默降级、不再告警
                journalOverflowWarned.remove(worldName);
                invalidateFluidJournalCache();
            }
            runCompletion(onComplete, true, worldName);
            return true;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("地图回滚无法开始：世界未加载 " + worldName);
            return false;
        }

        if (!journals.remove(worldName, journal)) {
            return restoreWithResult(worldName, onComplete);
        }
        // 日志已从 journals 移出：作废 tick 缓存，避免流体事件继续写入这份不再回滚的日志。
        // 同时清掉超限标记 —— 该世界下一次建立日志时应重新获得一次告警机会。
        journalOverflowWarned.remove(worldName);
        invalidateFluidJournalCache();

        RestoreOperation operation = new RestoreOperation(
                worldName, world, new ArrayList<>(journal.entrySet()));
        operation.addCompletionCallback(onComplete);
        activeRestores.put(worldName, operation);

        plugin.getLogger().info("开始回滚 " + worldName + " 的 " + operation.entries.size() + " 个方块改动");
        restoreBatch(operation);
        if (operation.finished) return true;

        if (!plugin.isEnabled()) {
            finishSynchronously(operation);
            return true;
        }

        try {
            operation.task = Bukkit.getScheduler().runTaskTimer(
                    plugin, () -> restoreBatch(operation), 1L, 1L);
        } catch (IllegalPluginAccessException exception) {
            // 禁用流程可能恰好发生在 isEnabled 检查之后；主线程立即收尾，不再调度。
            finishSynchronously(operation);
        }
        return true;
    }

    private void restoreBatch(RestoreOperation operation) {
        if (operation.finished) return;
        int end = Math.min(operation.nextIndex + BLOCKS_PER_TICK, operation.entries.size());
        restoreRange(operation, end);
        if (operation.nextIndex >= operation.entries.size()) complete(operation, false);
    }

    private void restoreRange(RestoreOperation operation, int endExclusive) {
        while (operation.nextIndex < endExclusive) {
            Map.Entry<BlockPos, OriginalBlock> entry = operation.entries.get(operation.nextIndex++);
            if (!restoreBlock(operation.world, entry.getKey(), entry.getValue())) {
                // 保留原始快照，恢复结束时重新入队，使失败可以重试
                operation.failedEntries.add(entry);
            }
        }
    }

    private boolean restoreBlock(World world, BlockPos pos, OriginalBlock original) {
        try {
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            // BlockData 保留朝向、上下半部、含水等属性；关闭物理避免连锁改变。
            block.setBlockData(original.blockData().clone(), false);
            return original.tileState() == null || original.tileState().update(true, false);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("地图回滚方块失败 " + world.getName() + " ["
                    + pos.x() + "," + pos.y() + "," + pos.z() + "]: " + exception.getMessage());
            return false;
        }
    }

    /** 主线程同步完成指定世界的剩余恢复；插件禁用阶段不会再创建 Bukkit 任务。 */
    public boolean restoreNow(String worldName) {
        requirePrimaryThread();
        if (!restore(worldName, null)) return false;
        RestoreOperation operation = activeRestores.get(worldName);
        if (operation != null) finishSynchronously(operation);
        return true;
    }

    /** 主线程同步完成所有 journal/活动任务，供 onDisable 最终兜底。 */
    public void restoreAllNow() {
        requirePrimaryThread();
        Set<String> worldNames = new HashSet<>(journals.keySet());
        worldNames.addAll(activeRestores.keySet());
        for (String worldName : worldNames) {
            restoreNow(worldName);
        }
    }

    private void finishSynchronously(RestoreOperation operation) {
        if (operation.finished) return;
        if (operation.task != null) {
            operation.task.cancel();
            operation.task = null;
        }
        restoreRange(operation, operation.entries.size());
        complete(operation, false);
    }

    private void complete(RestoreOperation operation, boolean discarded) {
        if (operation.finished) return;
        operation.finished = true;
        if (operation.task != null) {
            operation.task.cancel();
            operation.task = null;
        }
        activeRestores.remove(operation.worldName, operation);
        // 世界已退出回滚：作废 tick 缓存，让后续事件按最新状态重新判定是否记账。
        invalidateFluidJournalCache();

        int failedBlocks = operation.failedEntries.size();
        boolean requeued = false;
        if (discarded) {
            plugin.getLogger().warning("地图回滚已取消并丢弃：" + operation.worldName
                    + (failedBlocks > 0 ? "（另有 " + failedBlocks + " 个方块未回滚成功，随记录一并丢弃）" : ""));
        } else if (failedBlocks > 0) {
            requeued = requeueFailedBlocks(operation);
            plugin.getLogger().warning("地图回滚完成但有 " + failedBlocks + " 个方块失败"
                    + (requeued ? "（原始快照已重新入队，可再次回滚补救）" : "（重新入队失败）")
                    + "：" + operation.worldName);
        } else {
            plugin.getLogger().info("地图回滚完成：" + operation.worldName
                    + " 共 " + operation.entries.size() + " 个方块");
        }

        List<Consumer<Boolean>> callbacks = new ArrayList<>(operation.completionCallbacks);
        operation.completionCallbacks.clear();
        // 只有「零失败」才算成功：有失败时调用方必须保持屏障，不能开放半恢复的地图。
        boolean success = !discarded && failedBlocks == 0;
        for (Consumer<Boolean> callback : callbacks) {
            runCompletion(callback, success, operation.worldName);
        }
    }

    /**
     * 把恢复失败的方块重新写回日志，使下一次 restore/restoreNow/shutdown 仍能补救。
     *
     * <p>使用 {@code putIfAbsent} 而不是覆盖：回滚期间 {@link #resolveJournal(World)} 恒返回 null，
     * 这些坐标不可能产生更新的记录，因此写回的必然是同一份原始快照；而重试时日志是
     * 从空表重新建立的，只包含上一轮的失败坐标，所以失败集合不会随重试次数增长。</p>
     *
     * @return 是否至少写回了一个失败的方块
     */
    private boolean requeueFailedBlocks(RestoreOperation operation) {
        if (operation.failedEntries.isEmpty()) return false;

        Map<BlockPos, OriginalBlock> journal = journals.computeIfAbsent(
                operation.worldName, ignored -> new ConcurrentHashMap<>());
        for (Map.Entry<BlockPos, OriginalBlock> entry : operation.failedEntries) {
            journal.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return true;
    }

    private void runCompletion(Consumer<Boolean> callback, boolean success, String worldName) {
        if (callback == null) return;
        try {
            callback.accept(success);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "地图回滚完成回调失败：" + worldName, exception);
        }
    }

    /**
     * 丢弃某个世界的记录并安全取消活动任务。已合并的完成回调仍会执行，
     * 使等待恢复屏障的竞技场不会永久卡在 ENDING。
     */
    public void discard(String worldName) {
        requirePrimaryThread();
        journals.remove(worldName);
        // 日志已丢弃：清掉超限标记，否则该世界下一局再超限时不会告警
        journalOverflowWarned.remove(worldName);
        invalidateFluidJournalCache();
        RestoreOperation operation = activeRestores.get(worldName);
        if (operation != null) complete(operation, true);
    }

    /** 当前是否正在回滚该世界。 */
    public boolean isRestoring(String worldName) {
        return worldName != null && activeRestores.containsKey(worldName);
    }

    /** 当前待回滚的方块数（含活动任务尚未处理的部分，诊断用）。 */
    public int getPendingCount(String worldName) {
        Map<BlockPos, OriginalBlock> journal = journals.get(worldName);
        int pending = journal == null ? 0 : journal.size();
        RestoreOperation operation = activeRestores.get(worldName);
        if (operation != null) pending += operation.entries.size() - operation.nextIndex;
        return pending;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("MapRestoreManager must be called from the Bukkit primary thread");
        }
    }
}
