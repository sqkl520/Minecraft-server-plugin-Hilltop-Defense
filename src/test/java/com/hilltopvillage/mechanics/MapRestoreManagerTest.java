package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link MapRestoreManager} 的回归测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：{@code complete()} 的 success 判定从
 * 「零失败方块」改为「零失败方块 <b>且</b> 日志未被截断」
 * （{@code MapRestoreManager.java:713}）。旧行为下，一份因达到
 * {@code MAX_JOURNAL_ENTRIES_PER_WORLD} 而被截断的日志，回滚完已知的方块后会报告
 * 完整成功 —— 调用方据此撤掉 ENDING 屏障，玩家进入一张<b>半恢复</b>的地图。</p>
 *
 * <p><b>诚实说明：</b>{@code MAX_JOURNAL_ENTRIES_PER_WORLD} 是 1,000,000，
 * 真触发需要百万次 {@code BlockBreakEvent}（约数十秒到数分钟，且要吃下百万条快照的内存），
 * 测试里不可行。因此 {@code journalTruncated} 组直接用反射往那个集合里放世界名 ——
 * 测的是<b>「标记存在时 complete() 如何判定」</b>这条契约，而不是<b>「超限时如何置标记」</b>。
 * 后者由 {@code journalTruncatedIsSetAtCapacity} 组用注入满容量日志的方式覆盖
 * （该组确实走真实的 {@code recordSnapshot} 超限分支，只是把容量伪装成已满）。</p>
 */
@DisplayName("MapRestoreManager 地图回滚")
class MapRestoreManagerTest extends MockBukkitTestBase {

    private static final String WORLD = "hilltop_arena1";

    private Logger logger;
    private WorldMock world;
    private MapRestoreManager manager;
    private ArenaInstance arena;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("mrm-test-" + System.nanoTime());
        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), null);

        world = server.addSimpleWorld(WORLD);
        // resolveJournal() 的两道门：竞技场必须存在且处于记录状态。
        // 缺任何一个，journal 恒为空，所有记录类断言都会「通过」但什么也没测。
        arena = mock(ArenaInstance.class);
        when(arena.getState()).thenReturn(GameState.RUNNING);
        MultiArenaManager arenaManager = mock(MultiArenaManager.class);
        when(arenaManager.getArenaByWorld(WORLD)).thenReturn(arena);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);

        manager = new MapRestoreManager(plugin);
    }

    // ---------------------------------------------------------------- 工具

    /** 破坏一个方块并让它真的变成空气（事件本身不改方块，Bukkit 语义如此）。 */
    private Block breakBlock(int x, int y, int z, Material original) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(original);
        manager.onBlockBreak(new BlockBreakEvent(block, null));
        block.setType(Material.AIR);
        return block;
    }

    /** 取私有字段（反射是访问 {@code journals} / {@code journalTruncated} 的唯一途径）。 */
    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        try {
            Field f = MapRestoreManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("字段 " + name + " 无法反射读取；若已改名，本测试需要同步", e);
        }
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Nested
    @DisplayName("complete() 的 success 判定（本轮修复）")
    class SuccessJudgement {

        /**
         * 修复的正向契约 + 同方法内的反向控制。
         *
         * <p>两个断言必须在<b>同一个测试方法</b>里：只测「截断 → false」的话，
         * 一个把 {@code success} 硬编码成 {@code false} 的实现也能通过；
         * 只测「未截断 → true」的话，旧实现（忽略截断）照样通过。
         * 成对出现才把「success 随截断标记变化」这一因果钉死。</p>
         */
        @Test
        @DisplayName("日志被截断时回调 false 且方块仍被回滚；未截断的对照回调 true")
        void truncatedJournalReportsFailureButStillRestoresBlocks() {
            breakBlock(5, 64, 5, Material.DIAMOND_BLOCK);
            assertEquals(1, manager.getPendingCount(WORLD), "前置条件：破坏事件应入账 1 条");

            @SuppressWarnings("unchecked")
            Set<String> truncated = field("journalTruncated");
            truncated.add(WORLD);

            List<Boolean> results = new ArrayList<>();
            manager.restoreWithResult(WORLD, results::add);
            server.getScheduler().performTicks(5);

            assertEquals(List.of(false), results,
                    "日志被截断时 complete() 必须报告 false —— 否则调用方会撤掉 ENDING 屏障，"
                            + "把一张半恢复的地图开放给玩家");
            assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(5, 64, 5).getType(),
                    "报告失败不等于放弃回滚：已知的方块仍必须改回去");
            assertFalse(truncated.contains(WORLD),
                    "complete() 必须取走（remove）截断标记，否则该世界下一局会被永远判负");

            // ---- 对照：完全相同的场景，只是没有截断标记 ----
            breakBlock(6, 64, 6, Material.GOLD_BLOCK);
            List<Boolean> control = new ArrayList<>();
            manager.restoreWithResult(WORLD, control::add);
            server.getScheduler().performTicks(5);

            assertEquals(List.of(true), control,
                    "未截断时必须报告 true；若这里也是 false，说明实现把 success 写死成了 false，"
                            + "上面那条断言其实什么都没证明");
            assertEquals(Material.GOLD_BLOCK, world.getBlockAt(6, 64, 6).getType());
        }

        /**
         * 截断必须留下可留痕的 SEVERE 日志，且文案点明「地图仍需人工修复」。
         *
         * <p>为什么单独测日志：{@code success=false} 只让调用方保持屏障，
         * 而服主是从日志知道<b>要做什么</b>。一条不含处置建议的失败日志等于把事故丢回给玩家。</p>
         */
        @Test
        @DisplayName("截断导致的失败必须打 SEVERE 并点名世界与人工修复")
        void truncatedJournalLogsSevereNamingWorld() {
            breakBlock(5, 64, 5, Material.STONE);
            @SuppressWarnings("unchecked")
            Set<String> truncated = field("journalTruncated");
            truncated.add(WORLD);

            var capture = attachLogCapture(logger);
            manager.restoreWithResult(WORLD, ok -> { });
            server.getScheduler().performTicks(5);

            assertTrue(capture.anyMatch(line -> line.startsWith(Level.SEVERE.getName() + "|")
                            && line.contains(WORLD) && line.contains("INCOMPLETE")),
                    "应有一条 SEVERE 提到世界名与 INCOMPLETE；实际捕获：" + capture.lines());
            assertTrue(capture.anyContains("journal was truncated"),
                    "日志必须说明是「日志被截断」而不是笼统的失败；实际捕获：" + capture.lines());
        }
    }

    // ---------------------------------------------------------------- 2. 截断的检出

    @Nested
    @DisplayName("超限检出（真实 recordSnapshot 分支）")
    class TruncationDetection {

        /**
         * 往私有 {@code journals} 里塞一份「看起来已满」的日志，再记录一个方块。
         *
         * <p>这条走的是<b>真实的</b> {@code recordSnapshot} 容量判断，不是反射置标记，
         * 所以它守的是「超限时必须<b>同时</b>做两件事」这条契约：置 {@code journalTruncated}
         * 标记（供 complete() 判负）+ 只告警一次（避免百万条日志刷屏）。</p>
         *
         * <p>塞进去的是一个 {@code size()} 恒返回上限的 Map 替身，而不是真的造百万条快照：
         * 后者要 140+ MB 和数十秒。替身只覆写 {@code size()}，{@code computeIfAbsent}
         * 仍然是正常 Map 语义，因此「已满就 return、不新增条目」的行为完全真实。</p>
         */
        @Test
        @DisplayName("日志已满时记录会置截断标记，且只告警一次")
        void capacityExceededMarksTruncatedAndWarnsOnce() {
            @SuppressWarnings("unchecked")
            Map<String, Map<?, ?>> journals =
                    (Map<String, Map<?, ?>>) (Map<?, ?>) field("journals");
            OverfullJournal overfull = new OverfullJournal();
            journals.put(WORLD, overfull);

            var capture = attachLogCapture(logger);

            // 触发真实的 recordSnapshot：两次破坏 → 两次容量判断
            Block a = world.getBlockAt(1, 64, 1);
            a.setType(Material.STONE);
            manager.onBlockBreak(new BlockBreakEvent(a, null));
            Block b = world.getBlockAt(2, 64, 2);
            b.setType(Material.STONE);
            manager.onBlockBreak(new BlockBreakEvent(b, null));

            @SuppressWarnings("unchecked")
            Set<String> truncated = field("journalTruncated");
            assertTrue(truncated.contains(WORLD),
                    "达到容量上限后必须置 journalTruncated —— 否则 complete() 无从知道有方块没被记录");

            long severeAboutLimit = capture.lines().stream()
                    .filter(l -> l.startsWith(Level.SEVERE.getName() + "|"))
                    .filter(l -> l.contains("reached its limit"))
                    .count();
            assertEquals(1, severeAboutLimit,
                    "超限告警必须恰好一次（两次破坏只警告一次）；实际：" + capture.lines());
        }

        /** 一个 size() 恒等于「已满」的日志替身；其余 Map 语义保持真实。 */
        private final class OverfullJournal extends ConcurrentHashMap<Object, Object> {
            @Override
            public int size() {
                return 1_000_000;   // == MAX_JOURNAL_ENTRIES_PER_WORLD
            }
        }
    }

    // ---------------------------------------------------------------- 3. 记录窗口

    @Nested
    @DisplayName("记录窗口（isRecordingState 的外部表现）")
    class RecordingWindow {

        @Test
        @DisplayName("非记录状态不记账，记录状态记账")
        void journalOnlyWrittenInsideRecordingStates() {
            when(arena.getState()).thenReturn(GameState.WAITING);
            breakBlock(3, 64, 3, Material.STONE);
            assertEquals(0, manager.getPendingCount(WORLD),
                    "WAITING（部署/等待期）改变的方块不应入账，否则地图回滚会把部署期的建设一起抹掉");

            when(arena.getState()).thenReturn(GameState.ENDING);
            breakBlock(4, 64, 4, Material.STONE);
            assertEquals(1, manager.getPendingCount(WORLD),
                    "ENDING（结算期）仍须记录：结算期的爆炸/火焰同样要回滚");
        }

        @Test
        @DisplayName("同一坐标的多次改变只记第一条快照")
        void repeatedChangesAtSamePositionKeepFirstSnapshot() {
            Block block = world.getBlockAt(7, 64, 7);
            block.setType(Material.DIAMOND_BLOCK);
            manager.onBlockBreak(new BlockBreakEvent(block, null));
            block.setType(Material.AIR);

            block.setType(Material.STONE);
            manager.onBlockBreak(new BlockBreakEvent(block, null));
            block.setType(Material.AIR);

            assertEquals(1, manager.getPendingCount(WORLD),
                    "日志只保存「本局首次改变前」的快照，同一坐标不得累积多条");

            manager.restoreWithResult(WORLD, ok -> { });
            server.getScheduler().performTicks(5);
            assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(7, 64, 7).getType(),
                    "回滚必须还原到【第一次】改变前的方块，而不是第二次的 STONE");
        }
    }

    // ---------------------------------------------------------------- 4. 回滚机制

    @Nested
    @DisplayName("分批回滚机制")
    class BatchedRestore {

        /**
         * {@code BLOCKS_PER_TICK} 是 200（可通过 {@code -Dhilltop.maprestore.blocksPerTick} 覆盖）。
         *
         * <p>为什么值得测：这个常量是「回滚不把 TPS 打崩」的唯一旋钮。若它退回 800
         * （修复前的值），单 tick 会占满 50 ms 预算的一半以上 —— 但功能测试全绿，
         * 因为方块最终都还原了。只有断言「单 tick 只处理 200 个」才拦得住这种性能回退。</p>
         */
        @Test
        @DisplayName("单个 tick 最多回滚 BLOCKS_PER_TICK 个方块，且回滚仍在进行")
        void restoreIsChunkedPerTick() {
            int total = 450;
            for (int i = 0; i < total; i++) {
                Block block = world.getBlockAt(i % 30, 64 + i / 30, 0);
                block.setType(Material.STONE);
                manager.onBlockBreak(new BlockBreakEvent(block, null));
                block.setType(Material.AIR);
            }
            assertEquals(total, manager.getPendingCount(WORLD));

            manager.restoreWithResult(WORLD, ok -> { });
            // restoreWithResult 立即跑第一批，之后每 tick 一批
            int afterFirstBatch = manager.getPendingCount(WORLD);
            assertEquals(total - 200, afterFirstBatch,
                    "restoreWithResult 应在返回前同步跑完第一批 200 个；实际剩余 " + afterFirstBatch);

            assertTrue(manager.isRestoring(WORLD), "450 个方块尚未处理完，应仍在回滚中");

            server.getScheduler().performTicks(1);
            assertEquals(total - 400, manager.getPendingCount(WORLD),
                    "performTicks(1) 应再处理恰好 200 个");

            server.getScheduler().performTicks(2);
            assertEquals(0, manager.getPendingCount(WORLD), "补足剩余批次后应归零");
            assertFalse(manager.isRestoring(WORLD), "回滚完成后必须退出 isRestoring 状态");
        }

        @Test
        @DisplayName("空日志的回滚立即成功、不创建任务")
        void restoreWithEmptyJournalSucceedsImmediately() {
            List<Boolean> results = new ArrayList<>();
            assertTrue(manager.restoreWithResult(WORLD, results::add),
                    "空日志属于正常情况（本局没改过地图），必须返回 true 而不是失败");
            assertEquals(List.of(true), results, "空日志的回调也应被调用（参数 true）");
            assertFalse(manager.isRestoring(WORLD), "不应为一份空日志创建回滚任务");
        }

        @Test
        @DisplayName("世界未加载时回滚返回 false 并告警，日志不被消费")
        void restoreFailsWhenWorldMissing() {
            breakBlock(9, 64, 9, Material.STONE);
            assertEquals(1, manager.getPendingCount(WORLD), "前置条件：日志里应有 1 条");

            // 日志里有内容，但世界已不在了 —— 这是「竞技场世界被卸载」的真实形态。
            // 注意：日志为空时 restoreWithResult 走的是「无事可做 → true」的早退分支，
            // 根本到不了这里，所以必须先记一条再卸世界。
            assertTrue(server.unloadWorld(world, false), "前置条件：世界应能被卸载");

            var capture = attachLogCapture(logger);
            boolean started = manager.restoreWithResult(WORLD, ok ->
                    fail("世界不存在时不应调用完成回调（那会让调用方以为回滚成功了）"));

            assertFalse(started, "世界不可用时应返回 false，让调用方保持屏障并重试");
            assertTrue(capture.anyContains("world not loaded"),
                    "必须留下明确日志，否则服主只看到一个静默的失败；实际：" + capture.lines());
            assertEquals(1, manager.getPendingCount(WORLD),
                    "世界不可用导致回滚失败时，日志必须原样保留以便下次重试");
        }
    }

    // ---------------------------------------------------------------- 5. 高保真回滚

    @Nested
    @DisplayName("方块状态保真度")
    class Fidelity {

        /**
         * <b>这里刻意没有「朝向/BlockData 保真」测试</b>，理由是实测出来的限制：
         * MockBukkit 4.45.0 的 {@code BlockMock} 在 {@code setBlockData(...)} 时只写
         * {@code material} + {@code blockData} 两个字段，<b>从不重建 {@code state}</b>，
         * 因此 {@code block.getState(true).getBlockData()} 永远返回该材质的<b>默认</b>
         * BlockData（实测：设成 {@code facing=east} 的楼梯，state 仍读回 {@code facing=north}）。
         * 而生产代码 {@code snapshot()} 正是从 {@code state.getBlockData()} 取快照，
         * 所以快照在<b>捕获阶段</b>就已经丢了朝向 —— 回滚侧再正确也无法观测到差异。
         * 这条保真度只能在真实服务端验证，MockBukkit 下写出来会是一根假绿测试。
         */

        /**
         * 告示牌文本必须往返（{@code TileState.update} 生效）。
         *
         * <p>这是 {@code restoreBlock} 里 {@code tileState().update(...)} 那半条路径的
         * 唯一可断言效果。{@code BlockData} 只描述方块类型与朝向，不含牌上文字；
         * 若 update 被跳过，牌会变回空白 —— 玩家看到的是「地图坏了」。</p>
         *
         * <p><b>已知边界：</b>容器（箱子）的物品栏在 MockBukkit 里<b>不</b>往返
         * （{@code BlockMock.setBlockData} 不重建 state），因此这里只测告示牌文本，
         * 不测箱子内容。</p>
         */
        @Test
        @DisplayName("回滚保留告示牌文本（TileState.update 路径）")
        void restorePreservesSignText() {
            Block block = world.getBlockAt(15, 64, 15);
            block.setType(Material.OAK_SIGN);
            Sign sign = (Sign) block.getState();
            sign.setLine(0, "Sector 7");
            sign.update(true, false);

            manager.onBlockBreak(new BlockBreakEvent(block, null));
            block.setType(Material.AIR);

            manager.restoreWithResult(WORLD, ok -> { });
            server.getScheduler().performTicks(5);

            BlockState restored = world.getBlockAt(15, 64, 15).getState();
            assertTrue(restored instanceof Sign, "回滚后应是告示牌，实际 " + restored.getClass());
            assertEquals("Sector 7", ((Sign) restored).getLine(0),
                    "告示牌文本必须随回滚还原 —— 这是 tileState().update() 的唯一可观测效果");
        }
    }

    // ---------------------------------------------------------------- 6. 事件契约

    @Nested
    @DisplayName("监听器注册契约")
    class ListenerContract {

        /**
         * 所有记账事件监听器必须是 {@code MONITOR} + {@code ignoreCancelled = true}。
         *
         * <p>这两条是设计要害，且都不会被功能测试发现：</p>
         * <ul>
         *   <li>改成 {@code NORMAL} 之外的低优先级 → 别家监听器可能还没改完方块，
         *       快照取到的是中间态；</li>
         *   <li>丢掉 {@code ignoreCancelled} → 被保护逻辑取消的破坏（如末地水晶保护）
         *       也会入账，回滚时反而把没变过的方块写成快照值。</li>
         * </ul>
         * <p>反射读注解是在没有真实 Bukkit 事件总线的条件下唯一能守住这条的方式。</p>
         */
        @Test
        @DisplayName("记账监听器一律 MONITOR 且忽略已取消事件")
        void recordingHandlersUseMonitorAndIgnoreCancelled() {
            int checked = 0;
            for (var method : MapRestoreManager.class.getDeclaredMethods()) {
                EventHandler handler = method.getAnnotation(EventHandler.class);
                if (handler == null) continue;
                // 本类的监听器只有一种用途：记账。因此不区分事件类型，全部适用同一条契约。
                assertEquals(EventPriority.MONITOR, handler.priority(),
                        method.getName() + " 必须是 MONITOR 优先级，否则取到的可能不是「改变前」的状态");
                assertTrue(handler.ignoreCancelled(),
                        method.getName() + " 必须 ignoreCancelled —— "
                                + "被取消的事件没有真正改变方块，入账会让回滚写脏数据");
                checked++;
            }
            assertEquals(7, checked,
                    "应发现 7 个记账监听器；实际 " + checked + " 个。"
                            + "若本类新增/删除了事件，请同步本测试的期望值");
        }
    }
}
