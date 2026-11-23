package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.DisplayConfig;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ArenaScoreboard} 刷新任务的异常回收测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：{@code refreshAll()} 的兜底 {@code catch}
 * （{@code ArenaScoreboard.java:409-419}）里，{@code stopTask()} 被移到「把 {@code updateTask}
 * 置空」<b>之前</b>。修复前只置空句柄、不 {@code cancel()}，于是：</p>
 * <ol>
 *   <li>异常被 catch 吞在方法内部，<b>从未穿出 {@code run()}</b>，所以
 *       {@code CraftScheduler} 不会替我们取消任务；</li>
 *   <li>那个 ticker 仍然活着，但 {@code updateTask} 已为 null，
 *       {@code stopTask()} 再也找不到它 —— 变成无人持有的孤儿；</li>
 *   <li>下一次 {@code show()} → {@code ensureTaskRunning()} 看到 null 便再起一条 ——
 *       同一实例两条 ticker，刷新频率与动画相位双双翻倍，且第一条永远不会停。</li>
 * </ol>
 *
 * <p><b>本组为什么断言两件事而不是一件</b>：只断言 {@code updateTask == null} 的话，
 * 修复前的代码<b>也能通过</b>（它本来就是置空）。真正有判别力的是第二件事 ——
 * 被注入的那个任务<b>确实被取消</b>了。两者合起来才唯一地确定「句柄失效」与
 * 「任务停止」保持一致。</p>
 *
 * <p><b>MockBukkit 阻塞点（已实测）</b>：{@code show()} 在 {@code :249} 调
 * {@code Objective#numberFormat(NumberFormat.blank())}，{@code ObjectiveMock} 对该方法
 * 是裸抛 {@code UnimplementedOperationException}，所以 {@code show()} 无法运行。
 * 但构造器、{@code refreshAll()}、{@code writeLines()} 都干净 —— 本组因此直接反射
 * 驱动 {@code refreshAll()}，不经过 {@code show()}。</p>
 *
 * <p><b>另一个 MockBukkit 缺口（实测新发现）</b>：{@code BukkitSchedulerMock#getPendingTasks()}
 * 同样未实现，所以「调度器里还剩几条任务」无法直接断言。本组改用
 * <b>tick 计数器</b>：注入的任务每 tick 自增一个 {@code AtomicInteger}，
 * 推进若干 tick 后计数<b>不再增长</b>就说明任务真的停了 —— 这是对「孤儿还在跑」
 * 最直接的行为观测，比任何句柄检查都硬。</p>
 */
@DisplayName("ArenaScoreboard 刷新任务回收")
class ArenaScoreboardTest extends MockBukkitTestBase {

    private Logger logger;
    private HilltopVillagePlugin plugin;
    private ConfigManager configManager;
    private GameManager gameManager;
    private ArenaInstance arena;
    private PlayerMock player;
    private PlayerData playerData;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("scoreboard-test-" + System.nanoTime());

        plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), new GameConfig());
        configManager = plugin.getConfigManager();
        when(configManager.getDisplayConfig()).thenReturn(DisplayConfig.defaults());
        when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(true);

        // snapshot() 会读满这一整组 getter。全部返回正常值，才能让「对照组」真的跑通
        // （对照组不抛异常，是本组判别力的另一半 —— 见 RefreshTaskRecovery 的类注释）。
        NodeSystem nodes = mock(NodeSystem.class);
        when(nodes.getActiveCount()).thenReturn(2);
        when(nodes.getTotalCount()).thenReturn(3);
        VillagerSystem villagers = mock(VillagerSystem.class);
        when(villagers.getAliveCount()).thenReturn(4);
        when(villagers.getTotalCount()).thenReturn(5);
        WaveManager waves = mock(WaveManager.class);
        when(waves.getRemainingMobCount()).thenReturn(6);

        gameManager = mock(GameManager.class);
        when(gameManager.getPlayerCount()).thenReturn(2);
        when(gameManager.getCurrentWave()).thenReturn(1);
        when(gameManager.getNodeSystem()).thenReturn(nodes);
        when(gameManager.getWaveManager()).thenReturn(waves);

        arena = mock(ArenaInstance.class);
        when(arena.getArenaId()).thenReturn("arena1");
        when(arena.getGameManager()).thenReturn(gameManager);
        when(arena.getState()).thenReturn(GameState.RUNNING);
        when(arena.getSpectatorCount()).thenReturn(0);
        when(arena.getStartCountdown()).thenReturn(-1);
        when(arena.getTotalWaves()).thenReturn(10);
        when(arena.getPlayerCount()).thenReturn(2);
        when(arena.getMinPlayers()).thenReturn(1);
        when(arena.getDisplayName()).thenReturn("Arena 1");
        when(arena.getWaveIntervalSeconds()).thenReturn(20);
        when(arena.getVillagerSystem()).thenReturn(villagers);

        player = server.addPlayer("Boarder");
        when(arena.isSpectator(any(UUID.class))).thenReturn(false);
        playerData = new PlayerData(player.getUniqueId());
        when(gameManager.getPlayerData(any(UUID.class))).thenReturn(playerData);
        when(gameManager.getPlayerData(any(Player.class))).thenReturn(playerData);
    }

    @AfterEach
    void stopAnyTask() {
        // 用例失败时也可能留下仍在跑的任务；显式 cancel 掉，避免污染后续用例的 tick 计数。
        if (arena == null) return;
        try {
            ArenaScoreboard board = scoreboard;
            if (board == null) return;
            BukkitTask task = (BukkitTask) field(board, "updateTask");
            if (task != null) task.cancel();
            // S08-F2：关闭态会留下恢复探针任务，同样要收掉
            BukkitTask probe = (BukkitTask) field(board, "reEnableProbeTask");
            if (probe != null) probe.cancel();
        } catch (ReflectiveOperationException ignored) {
            // 清理失败不应盖过真正的断言失败
        }
    }

    private ArenaScoreboard scoreboard;

    // ---------------------------------------------------------------- 辅助

    private Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = ArenaScoreboard.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = ArenaScoreboard.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 反射构造私有的 {@code ArenaScoreboard.BoardState}（与 {@code show()} 里的成对新建等价）。 */
    private Object newBoardState(Scoreboard board, Objective objective) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.hilltopvillage.mechanics.ArenaScoreboard$BoardState");
        Constructor<?> ctor = type.getDeclaredConstructor(Scoreboard.class, Objective.class);
        ctor.setAccessible(true);
        return ctor.newInstance(board, objective);
    }

    private void invokeRefreshAll() throws ReflectiveOperationException {
        Method method = ArenaScoreboard.class.getDeclaredMethod("refreshAll");
        method.setAccessible(true);
        method.invoke(scoreboard);
    }

    /**
     * 建好一块可刷新的 board 并把一名在线玩家挂上去，再注入一条<b>真实的</b>每秒任务。
     *
     * @param tickCounter 注入任务的任务体每执行一次就自增；用于观测「任务是否还在跑」
     * @return 注入的任务
     */
    private BukkitTask armBoardWithLiveTask(AtomicInteger tickCounter) throws ReflectiveOperationException {
        scoreboard = new ArenaScoreboard(plugin, arena);

        BukkitTask task = server.getScheduler()
                .runTaskTimer(plugin, tickCounter::incrementAndGet, 1L, 1L);
        setField(scoreboard, "updateTask", task);

        Scoreboard board = server.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(ArenaScoreboard.OBJECTIVE_NAME, Criteria.DUMMY, "title");
        @SuppressWarnings("unchecked")
        Map<UUID, Object> boards = (Map<UUID, Object>) field(scoreboard, "boards");
        boards.put(player.getUniqueId(), newBoardState(board, objective));
        player.setScoreboard(board);
        return task;
    }

    /** 让 {@code snapshot()} 在第一个 getter 处炸掉（这是 {@code refreshAll} 的致命路径）。 */
    private void makeSnapshotThrow() {
        // 必须用 doThrow：when(...) 会立即调用一次被桩的方法，异常在桩阶段就抛出，
        // 于是测的就不是 refreshAll 的 catch 了。
        doThrow(new IllegalStateException("snapshot exploded"))
                .when(gameManager).getPlayerCount();
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Nested
    @DisplayName("catch 必须 cancel 任务再置空句柄（本轮修复）")
    class RefreshTaskRecovery {

        /**
         * 致命异常 → 句柄归 null <b>且</b>任务真的被取消 <b>且</b>它不再执行。
         *
         * <p>三条断言各挡一类错误：</p>
         * <ol>
         *   <li>{@code updateTask == null} —— 挡住「catch 里什么都不做，句柄继续挂着已取消的任务」；</li>
         *   <li>{@code task.isCancelled()} —— <b>挡住修复前的代码</b>（只置空不 cancel）；</li>
         *   <li>tick 计数不再增长 —— 行为层复核，挡住「cancel 了一个假的/别的任务」这类取巧实现。</li>
         * </ol>
         */
        @Test
        @DisplayName("致命异常后：句柄为空、任务已取消、且不再执行")
        void fatalErrorCancelsTheTaskNotJustTheHandle() throws Exception {
            AtomicInteger ticks = new AtomicInteger();
            BukkitTask injected = armBoardWithLiveTask(ticks);

            assertFalse(injected.isCancelled(),
                    "前置条件：注入的任务必须是活的，否则本测试什么都没证明");

            makeSnapshotThrow();
            invokeRefreshAll();

            assertNull(field(scoreboard, "updateTask"),
                    "refreshAll 捕获致命异常后必须把 updateTask 置空，"
                            + "否则 ensureTaskRunning 的 isCancelled 分支会一直复用它");
            assertTrue(injected.isCancelled(),
                    "【本轮修复】catch 必须先 stopTask() 再置空。若这里是 false，"
                            + "就是一个仍在调度的孤儿 ticker：stopTask() 再也找不到它，"
                            + "下一次 show() 会再起一条 —— 同一实例两条 ticker，"
                            + "刷新频率与动画相位双双翻倍（slice-10-conc-001）");

            // 行为复核：推进若干 tick，被取消的任务不应再跑。
            int before = ticks.get();
            server.getScheduler().performTicks(5);
            assertEquals(before, ticks.get(),
                    "已取消的任务不得继续执行。计数仍在增长说明取消没有真正生效"
                            + "（或取消的是别的任务），孤儿 ticker 依然在跑");
        }

        /**
         * 对照组：没有异常时任务必须<b>原样保留</b>。
         *
         * <p>没有这一半，一个「每次 refreshAll 都无条件 stopTask()」的实现也能通过上面那条 ——
         * 但那会让侧边栏在第一帧之后永久停更。对照组把「只在异常时停」这个条件锁死。</p>
         */
        @Test
        @DisplayName("对照：无异常时句柄保留、任务不取消、仍在执行")
        void healthyFrameKeepsTheTaskAlive() throws Exception {
            AtomicInteger ticks = new AtomicInteger();
            BukkitTask injected = armBoardWithLiveTask(ticks);

            invokeRefreshAll();

            assertSame(injected, field(scoreboard, "updateTask"),
                    "对照：正常帧不得动 updateTask 句柄");
            assertFalse(injected.isCancelled(),
                    "对照：正常帧不得取消任务 —— 否则侧边栏在第一帧之后永久停更");

            int before = ticks.get();
            server.getScheduler().performTicks(3);
            assertTrue(ticks.get() > before,
                    "对照：任务应当仍在调度并执行。计数不动说明前面那次 refreshAll "
                            + "把它停掉了，上面那条「致命异常会取消」的断言因此失去意义");
        }
    }

    // ---------------------------------------------------------------- 2. 空板自停

    @Nested
    @DisplayName("无人在看时任务自停（耗时与孤儿任务的边界）")
    class IdleShutdown {

        /**
         * {@code boards} 为空时 {@code refreshAll} 必须停掉任务。
         *
         * <p>这是孤儿任务的另一半成因：若空板路径只 {@code return} 不停任务，
         * 每帧都空转、{@code tickCounter} 还在推进动画相位，直到有玩家再进来。</p>
         */
        @Test
        @DisplayName("boards 为空 → 任务被停掉")
        void emptyBoardsStopsTheTask() throws Exception {
            scoreboard = new ArenaScoreboard(plugin, arena);
            AtomicInteger ticks = new AtomicInteger();
            BukkitTask injected = server.getScheduler()
                    .runTaskTimer(plugin, ticks::incrementAndGet, 1L, 1L);
            setField(scoreboard, "updateTask", injected);

            invokeRefreshAll();

            assertTrue(injected.isCancelled(),
                    "boards 为空时 refreshAll 必须停掉刷新任务，否则任务在无人持有记分板时继续空转");
            assertNull(field(scoreboard, "updateTask"), "停任务后句柄必须归 null");
        }

        /**
         * 侧边栏被全局关掉时，已有 board 必须被拆、任务必须停。
         *
         * <p>守的是 {@code :363-374} 的 D5 分支：{@code show()} 只在入场读开关，
         * 管理员对局中关掉侧边栏后若不在这里拆板，配置项形同虚设。</p>
         */
        @Test
        @DisplayName("侧边栏被关闭 → 拆板并停任务")
        void disabledScoreboardHidesBoardsAndStopsTask() throws Exception {
            AtomicInteger ticks = new AtomicInteger();
            BukkitTask injected = armBoardWithLiveTask(ticks);

            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(false);
            invokeRefreshAll();

            assertTrue(injected.isCancelled(), "关闭侧边栏后不得继续刷新（配置项必须真的生效）");
            assertNull(field(scoreboard, "updateTask"), "关闭后句柄必须归 null");
            @SuppressWarnings("unchecked")
            Map<UUID, Object> boards = (Map<UUID, Object>) field(scoreboard, "boards");
            assertTrue(boards.isEmpty(),
                    "关闭侧边栏后已显示的 board 必须被摘除，否则玩家侧边栏冻结在最后一帧");
        }
    }

    // ---------------------------------------------------------------- 3. 开关重新打开

    /**
     * S08-F2：关闭 → 重新打开必须把在场玩家的侧边栏建回来。
     *
     * <p><b>本组为什么不经过 {@code show()}</b>：MockBukkit 的
     * {@code ObjectiveMock#numberFormat} 是裸抛 {@code UnimplementedOperationException}
     * （已反编译核实），真实 {@code show()} 跑不完。所以用 {@link org.mockito.Mockito#spy}
     * 把 {@code show()} 打成空实现，只观测「对谁、调了几次」——本组要证明的正是这件事
     * （重建路径覆盖了哪些人、是否只在翻转的那一次执行），而不是 {@code show()} 的渲染内容
     * （那部分由 {@code show()} 自身的实现与其它用例覆盖）。</p>
     */
    @Nested
    @DisplayName("开关 false → true 时恢复侧边栏（S08-F2）")
    class ReEnableRecovery {

        private ArenaScoreboard spied;

        /** 建一个 spy 版本并让 show() 变成可观测的空实现。 */
        private ArenaScoreboard spiedScoreboard() throws ReflectiveOperationException {
            spied = org.mockito.Mockito.spy(new ArenaScoreboard(plugin, arena));
            org.mockito.Mockito.doNothing().when(spied).show(any(Player.class));
            setField(spied, "updateTask", null);
            return spied;
        }

        private void invokeProbe() throws ReflectiveOperationException {
            Method method = ArenaScoreboard.class.getDeclaredMethod("probeReEnable");
            method.setAccessible(true);
            method.invoke(spied);
        }

        private void invokeRefreshAllOnSpy() throws ReflectiveOperationException {
            Method method = ArenaScoreboard.class.getDeclaredMethod("refreshAll");
            method.setAccessible(true);
            method.invoke(spied);
        }

        /**
         * 关闭 → 打开：每个在线竞技场成员各被 {@code show()} 一次，且刷新任务被拉起。
         *
         * <p>三条断言各挡一类错误：</p>
         * <ol>
         *   <li>{@code show} 调用次数 = 在线成员数 —— 挡住「探针只置标志位、不重建」；</li>
         *   <li>每名成员都被覆盖 —— 挡住「只恢复了第一个 / 只恢复了自己」；</li>
         *   <li>{@code updateTask} 非空 —— 挡住「板建好了但刷新任务没重启」，那种情况下
         *       侧边栏只画一帧就永久冻结。</li>
         * </ol>
         */
        @Test
        @DisplayName("探针看到开关翻回 true → 为每名在线成员重建侧边栏并重启刷新任务")
        void flipToEnabledRestoresEveryOnlineMember() throws Exception {
            PlayerMock second = server.addPlayer("Second");
            when(arena.getPlayers()).thenReturn(List.of(player, second));

            spiedScoreboard();
            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(false);
            invokeRefreshAllOnSpy();

            assertEquals(Boolean.FALSE, field(spied, "lastEnabled"),
                    "前置条件：关闭帧必须先记下 lastEnabled=false，否则翻转判定没有基准");
            org.mockito.Mockito.verify(spied, org.mockito.Mockito.never()).show(any(Player.class));

            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(true);
            invokeProbe();

            org.mockito.Mockito.verify(spied, org.mockito.Mockito.times(1)).show(player);
            org.mockito.Mockito.verify(spied, org.mockito.Mockito.times(1)).show(second);
            assertNotNull(field(spied, "updateTask"),
                    "恢复后刷新任务必须被重新拉起（show 内部 ensureTaskRunning），"
                            + "否则侧边栏只画一帧就永久冻结");
            assertEquals(Boolean.TRUE, field(spied, "lastEnabled"),
                    "恢复后 lastEnabled 必须翻成 true，否则下一次探针会重复重建整帧");
            assertNull(field(spied, "reEnableProbeTask"),
                    "恢复后探针必须自取消 —— 它已完成使命，留一条每秒轮询是纯浪费");
        }

        /**
         * 恢复之后继续推进 tick 不得重复 {@code show()}。
         *
         * <p>没有这一条，一个「每 tick 无条件重建」的实现也能通过上面那条 ——
         * 但那会让每名玩家每秒被新建一次 scoreboard、整帧重发（可见闪烁 + 包量翻倍）。</p>
         */
        @Test
        @DisplayName("对照：恢复完成后再次探针不得重复 show")
        void repeatedProbeDoesNotReshow() throws Exception {
            when(arena.getPlayers()).thenReturn(List.of(player));
            spiedScoreboard();
            setField(spied, "lastEnabled", true);

            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(true);
            invokeProbe();
            invokeProbe();

            org.mockito.Mockito.verify(spied, org.mockito.Mockito.never()).show(any(Player.class));
        }

        /**
         * 关闭态且场上无人 → 探针自取消，不留空转任务。
         *
         * <p>这是「探针不浪费」的一半：对局结束、玩家全部离场后，没有谁需要被恢复，
         * 探针继续每秒读一次开关只是噪音。下一次 {@code show()} 会把它重新挂上。</p>
         */
        @Test
        @DisplayName("关闭且无在线成员 → 探针自取消")
        void probeStopsWhenArenaHasNoPlayers() throws Exception {
            when(arena.getPlayers()).thenReturn(List.of());
            spiedScoreboard();
            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(false);
            invokeRefreshAllOnSpy();

            assertNotNull(field(spied, "reEnableProbeTask"),
                    "前置条件：关闭帧必须挂上探针，否则本条什么都没证明");

            invokeProbe();

            assertNull(field(spied, "reEnableProbeTask"), "无人在场时探针必须自取消");
            org.mockito.Mockito.verify(spied, org.mockito.Mockito.never()).show(any(Player.class));
        }

        /**
         * 关闭期间入场（{@code show()} 早退）同样要挂上探针。
         *
         * <p>挡的是「只有 refreshAll 的关闭分支埋点」这一半实现：玩家在关闭期间加入时
         * {@code board} 从未建立，{@code refreshAll} 的关闭分支不会再被驱动
         * （boards 为空 → 停任务 → 任务死），恢复点就只剩这里。</p>
         *
         * <p>本用例刻意<b>不</b>用 spy：{@link #spiedScoreboard()} 把 {@code show()} 打成了空实现，
         * 走那条路根本到不了真实的早退分支，测的就不是本缺陷。关闭态的 {@code show()} 在触碰
         * 任何 MockBukkit 未实现的 scoreboard API 之前就返回，所以真实实例是安全的。</p>
         */
        @Test
        @DisplayName("关闭期间的 show() 早退但必须挂上探针")
        void showWhileDisabledArmsTheProbe() throws Exception {
            ArenaScoreboard plain = new ArenaScoreboard(plugin, arena);
            scoreboard = plain;
            when(configManager.isScoreboardEffectivelyEnabled()).thenReturn(false);

            plain.show(player);

            assertNotNull(field(plain, "reEnableProbeTask"),
                    "关闭期间入场必须挂上恢复探针，否则重新打开后这名玩家整局没有侧边栏");
            @SuppressWarnings("unchecked")
            Map<UUID, Object> boards = (Map<UUID, Object>) field(plain, "boards");
            assertTrue(boards.isEmpty(), "关闭期间不得建板");
        }
    }
}
