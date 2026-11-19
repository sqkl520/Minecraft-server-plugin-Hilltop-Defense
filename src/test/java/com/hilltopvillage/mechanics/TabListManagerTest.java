package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.DisplayConfig;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link TabListManager} 的刷新任务生命周期测试。
 *
 * <p><b>本轮瞄准的 round1 修复（{@code slice-11-concurrency-001}，孤儿任务再生）</b>：
 * {@code start()} 原先只判 {@code refreshTask != null && period == scheduledPeriod} 就早退。
 * 但 {@code CraftScheduler} 对抛未捕获异常的<b>重复任务会静默取消</b>，此时字段仍非 null、
 * 周期也还是原值 —— 于是 {@code start()} 永远早退，任务再也起不来，tab 整局不再刷新，
 * 而任何「玩家加入 / reloadconfig」都救不回来。修复后早退条件加上
 * {@code !refreshTask.isCancelled()}。</p>
 *
 * <p><b>为什么不能只断言「任务非空」</b>：修复前后的代码在 {@code refreshTask} 字段上完全一样。
 * 有判别力的只有两件事：(a) 被取消的句柄会触发<b>重建</b>；(b) 未被取消且周期相同的句柄
 * <b>不会</b>被重建（否则每次 {@code apply()} 都再起一条 ticker，刷新频率成倍上升）。
 * 两条互为控制组。</p>
 *
 * <p><b>MockBukkit 能力边界（本类实测）</b>：{@code PlayerMock#sendPlayerListHeaderAndFooter}、
 * {@code playerListName(Component)}、{@code setPlayerListOrder(int)} 在本机 4.45.0 上<b>均可用</b>，
 * 所以「刷新真的把东西发出去了」可以断言。未实现的是
 * {@code BukkitSchedulerMock#getPendingTasks()}，因此「调度器里还剩几条」无法直接断言 ——
 * 本类改用<b>句柄身份 + isCancelled + tick 计数器</b>观测，与 {@code ArenaScoreboardTest} 同一手法
 * （见 round2-06 §4）。</p>
 */
@DisplayName("TabListManager — 刷新任务生命周期与孤儿任务再生")
class TabListManagerTest extends MockBukkitTestBase {

    private Logger logger;
    private HilltopVillagePlugin plugin;
    private ConfigManager configManager;
    private MultiArenaManager arenas;
    private TabListManager tabList;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("lobby");
        logger = Logger.getLogger("tablist-" + System.nanoTime());

        plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), new GameConfig());
        configManager = plugin.getConfigManager();
        when(configManager.getDisplayConfig()).thenReturn(DisplayConfig.defaults());

        arenas = mock(MultiArenaManager.class);
        when(plugin.getMultiArenaManager()).thenReturn(arenas);

        player = server.addPlayer("Tabber");
        player.teleport(new Location(world, 0, 64, 0));

        tabList = new TabListManager(plugin);
    }

    @AfterEach
    void stopAnyTask() {
        // 用例失败时也可能留下仍在跑的任务；显式取消，避免污染后续用例。
        if (tabList == null) return;
        try {
            BukkitTask task = (BukkitTask) field("refreshTask");
            if (task != null) task.cancel();
        } catch (ReflectiveOperationException ignored) {
            // 清理失败不应盖过真正的断言失败
        }
    }

    // ---------------------------------------------------------------- 辅助

    private Object field(String name) throws ReflectiveOperationException {
        Field f = TabListManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(tabList);
    }

    private void setField(String name, Object value) throws ReflectiveOperationException {
        Field f = TabListManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(tabList, value);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> cache(String name) throws ReflectiveOperationException {
        return (Map<UUID, Object>) field(name);
    }

    private void invokeRefreshAll() throws ReflectiveOperationException {
        Method m = TabListManager.class.getDeclaredMethod("refreshAll");
        m.setAccessible(true);
        m.invoke(tabList);
    }

    /** 用出厂 tab 配置为底，只改 refresh-ticks / enabled，其余字段原样保留。 */
    private void withTab(boolean enabled, int ticks) {
        DisplayConfig defaults = DisplayConfig.defaults();
        DisplayConfig.Tab base = defaults.tab();
        DisplayConfig.Tab tab = new DisplayConfig.Tab(
                enabled, ticks, base.lobby(), base.arena(), base.spectator(),
                base.header(), base.footer(),
                base.nameAlive(), base.nameDead(), base.nameSpectator());
        when(configManager.getDisplayConfig())
                .thenReturn(new DisplayConfig(defaults.scoreboard(), tab));
    }

    // ---------------------------------------------------------------- 1. orphan-task fix

    @Nested
    @DisplayName("start() 必须识别「已被调度器取消」的句柄（round1 slice-11-concurrency-001）")
    class OrphanTaskRegeneration {

        /**
         * ★ 本条就是 round1 修复本身。
         *
         * <p>模拟真实场景：任务体抛未捕获异常 → {@code CraftScheduler} 静默 cancel → 字段仍非 null、
         * {@code scheduledPeriod} 也还是原值。修复后 {@code start()} 必须发现 {@code isCancelled()}
         * 并重建一条新任务。</p>
         */
        @Test
        @DisplayName("★ 句柄存在但已被取消 → start() 重建新任务")
        void cancelledHandleIsReplaced() throws Exception {
            withTab(true, 20);
            tabList.start();
            BukkitTask original = (BukkitTask) field("refreshTask");
            assertNotNull(original, "前置条件：start() 应真的起了一条任务");

            // 模拟 CraftScheduler 的静默取消：只 cancel 任务，不动字段与 scheduledPeriod。
            original.cancel();
            assertTrue(original.isCancelled(), "前置条件：任务必须处于已取消状态");
            assertEquals(20, field("scheduledPeriod"),
                    "前置条件：周期字段必须仍是原值，否则测的就不是「字段与事实不一致」这条路径");

            tabList.start();
            BukkitTask afterRestart = (BukkitTask) field("refreshTask");

            assertNotNull(afterRestart,
                    "【round1 slice-11-concurrency-001】修复前 start() 只判 refreshTask != null "
                            + "与周期相等就早退，于是被调度器静默取消的任务永远不会被重建 —— "
                            + "tab 头尾/名字/排序整局不再更新，玩家加入与 reloadconfig 都救不回来");
            assertNotEquals(original, afterRestart,
                    "必须换成一条新任务；沿用同一个已取消的句柄等于什么都没做");
            assertFalse(afterRestart.isCancelled(), "重建的任务必须是活的");
        }

        /** 控制组：句柄活着且周期一致时不得重建，否则 ticker 无限增殖。 */
        @Test
        @DisplayName("对照：句柄未取消且周期相同 → start() 不重建")
        void liveHandleIsReused() throws Exception {
            withTab(true, 20);
            tabList.start();
            BukkitTask original = (BukkitTask) field("refreshTask");

            tabList.start();
            BukkitTask second = (BukkitTask) field("refreshTask");

            assertEquals(original, second,
                    "对照：任务活着且周期没变时 start() 必须早退。若这里重建，"
                            + "每次 apply()/reloadconfig 都会再起一条 ticker，刷新频率成倍上升");
            assertFalse(original.isCancelled(), "对照组里原任务必须还活着，本测试才有意义");
        }

        @Test
        @DisplayName("周期变化 → 旧任务被取消、新任务接手（reloadconfig 生效路径）")
        void periodChangeRestartsTheTask() throws Exception {
            withTab(true, 20);
            tabList.start();
            BukkitTask original = (BukkitTask) field("refreshTask");

            withTab(true, 10);
            tabList.start();
            BukkitTask replaced = (BukkitTask) field("refreshTask");

            assertTrue(original.isCancelled(),
                    "周期变了必须停掉旧任务；留着它会让刷新按旧周期继续跑");
            assertNotEquals(original, replaced, "周期变化后应换上新任务");
        }

        @Test
        @DisplayName("stop() 取消任务并把周期归 -1")
        void stopCancelsAndResetsPeriod() throws Exception {
            withTab(true, 20);
            tabList.start();
            BukkitTask task = (BukkitTask) field("refreshTask");

            tabList.stop();

            assertTrue(task.isCancelled(), "stop() 必须真的取消任务");
            assertNull(field("refreshTask"), "stop() 后句柄必须归 null");
            assertEquals(-1, field("scheduledPeriod"), "stop() 后周期应归 -1");
        }

        @Test
        @DisplayName("tab 被关闭 → start() 停任务而不是起任务")
        void disabledTabStopsInsteadOfStarting() throws Exception {
            withTab(false, 20);

            tabList.start();

            assertNull(field("refreshTask"), "tab.enabled=false 时不得启动刷新任务");
        }

        @Test
        @DisplayName("refresh-ticks <= 0 时退回出厂周期（不得起一条 0 tick 的任务）")
        void nonPositivePeriodFallsBackToDefault() throws Exception {
            withTab(true, 0);

            tabList.start();

            assertNotNull(field("refreshTask"), "周期非法时仍应启动任务（用出厂周期兜底）");
            assertEquals(DisplayConfig.DEFAULT_TAB_REFRESH_TICKS, field("scheduledPeriod"),
                    "0/负周期必须退回 DEFAULT_TAB_REFRESH_TICKS，否则 runTaskTimer 会退化成每 tick 一次");
        }
    }

    // ---------------------------------------------------------------- 2. 刷新主循环

    @Nested
    @DisplayName("refreshAll 主循环")
    class RefreshLoop {

        @Test
        @DisplayName("刷新真的把目标玩家的名字与排序发了出去")
        void refreshPushesNameAndOrder() throws Exception {
            withTab(true, 20);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(null);
            tabList.start();

            invokeRefreshAll();

            assertEquals(3000, player.getPlayerListOrder(),
                    "大厅玩家的排序分带应为 ORDER_LOBBY(3000)；为 0 说明名字/排序那两遍根本没跑");
            assertNotNull(player.playerListName(), "刷新后应给目标玩家设置 tab 名字");
        }

        @Test
        @DisplayName("第二帧内容不变时不重发包（缓存比对必须生效）")
        void secondRefreshIsSkippedByCache() throws Exception {
            withTab(true, 20);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(null);
            tabList.start();

            invokeRefreshAll();
            Map<UUID, Object> lastName = cache("lastName");
            Map<UUID, Object> lastOrder = cache("lastOrder");
            Object firstName = lastName.get(player.getUniqueId());
            Object firstOrder = lastOrder.get(player.getUniqueId());
            assertNotNull(firstName, "前置条件：首帧必须写进名字缓存");
            assertNotNull(firstOrder, "前置条件：首帧必须写进排序缓存");

            invokeRefreshAll();

            assertEquals(firstName, lastName.get(player.getUniqueId()),
                    "第二帧名字未变，缓存条目应原样保留（等价于「未重发」）");
            assertEquals(firstOrder, lastOrder.get(player.getUniqueId()),
                    "第二帧排序未变，缓存条目应原样保留");
        }

        @Test
        @DisplayName("tab 被关闭时刷新只清缓存、不停任务（reloadconfig 打回 true 可自愈）")
        void disabledTabClearsCacheButKeepsTask() throws Exception {
            withTab(true, 20);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(null);
            tabList.start();
            invokeRefreshAll();
            assertFalse(cache("lastName").isEmpty(), "前置条件：首帧应写入缓存");

            BukkitTask task = (BukkitTask) field("refreshTask");
            withTab(false, 20);
            invokeRefreshAll();

            assertFalse(task.isCancelled(),
                    "关闭 tab 时刷新任务必须继续活着：开关随时可能被 reloadconfig 打回 true，"
                            + "而那时不一定有玩家加入事件来重启任务");
            assertTrue(cache("lastName").isEmpty(),
                    "关闭时必须清掉名字缓存，否则重新打开后首帧会拿旧值比对而永远不重推");
            assertTrue(cache("lastOrder").isEmpty(), "关闭时必须清掉排序缓存");
        }

        @Test
        @DisplayName("没有在线玩家时刷新直接返回、不抛异常")
        void emptyServerRefreshIsHarmless() throws Exception {
            player.disconnect();
            withTab(true, 20);
            assertTrue(server.getOnlinePlayers().isEmpty(), "前置条件：应无在线玩家");

            invokeRefreshAll();

            assertTrue(cache("lastHeader").isEmpty(), "无人在线时不应产生任何缓存条目");
        }

        /**
         * 运行期致命异常必须被兜住（T6.1），且**保留原有的任务句柄**。
         *
         * <p>2026-11-14 修正：原版的期望是「句柄置空、周期归 -1，下一帧自愈」，
         * 理由是「CraftScheduler 会静默取消抛异常的重复任务，留着旧句柄会让 start() 永远早退」。
         *
         * <p><b>那个前提经反编译核实是错的。</b> 在 paper-1.21.4-232 的
         * {@code CraftScheduler.mainThreadHeartbeat()} 字节码里，任务体抛异常后走的分支是：
         * 记日志 → 发 ServerExceptionEvent → 清空 currentTask → 与正常路径汇合 →
         * 因 {@code period > 0} 而 {@code pending.add(task)} <b>重新入队</b>。
         * 全程没有任何 {@code cancel()} 调用。也就是说周期任务抛异常后会<b>继续按时执行</b>。
         *
         * <p>于是原版「置空句柄」的做法会在下一帧触发 {@code start()} 新建一条，
         * 而旧的那条从未被取消、仍在运行 —— 两条并行，刷新频率翻倍。
         */
        @Test
        @DisplayName("刷新体抛异常 → 被 catch 住，但句柄与周期都保持不变（不制造孤儿任务）")
        void refreshFailureIsContained() throws Exception {
            withTab(true, 20);
            when(arenas.getPlayerArena(any(Player.class)))
                    .thenThrow(new IllegalStateException("boom"));
            tabList.start();

            BukkitTask before = (BukkitTask) field("refreshTask");
            assertNotNull(before, "前置条件：start() 后应有一个任务句柄");

            invokeRefreshAll();

            // 关键断言：句柄身份不变。若被置空，说明 catch 又把一个仍在运行的
            // ticker 变成了孤儿（Paper 不会因异常取消它）。
            assertSame(before, field("refreshTask"),
                    "异常路径不得清空句柄：Paper 的 CraftScheduler 不会取消抛异常的周期任务，"
                            + "清空只会让下一帧 start() 再建一条，与仍然活着的旧任务并行（刷新频率翻倍）");
            assertEquals(20, field("scheduledPeriod"),
                    "异常路径不得把周期归 -1：否则下一帧 start() 会判定「周期变了」而重建");
            assertFalse(before.isCancelled(),
                    "任务不应被取消——本 catch 是兜住异常，不是终止任务");
        }

        /** 连续异常不得让任务数量增长：句柄必须始终是同一个对象。 */
        @Test
        @DisplayName("连续 10 次刷新异常 → 任务句柄始终是同一个（不累积）")
        void repeatedFailuresDoNotAccumulateTasks() throws Exception {
            withTab(true, 20);
            when(arenas.getPlayerArena(any(Player.class)))
                    .thenThrow(new IllegalStateException("boom"));
            tabList.start();

            BukkitTask first = (BukkitTask) field("refreshTask");
            assertNotNull(first);

            for (int i = 0; i < 10; i++) {
                invokeRefreshAll();
                assertSame(first, field("refreshTask"),
                        "第 " + (i + 1) + " 次异常后句柄变了 —— 说明产生了第二条任务，旧任务成了孤儿");
            }
            assertFalse(first.isCancelled(), "十轮异常之后任务仍应活着（由周期驱动自愈）");
        }
    }

    // ---------------------------------------------------------------- 3. 做过的玩家才还原

    @Nested
    @DisplayName("restoreAll 只还原本插件真正碰过的玩家（T5.1）")
    class RestoreAll {

        @Test
        @DisplayName("碰过的玩家被还原成默认；没碰过的人不被改动")
        void onlyTouchedPlayersAreRestored() throws Exception {
            PlayerMock touched = server.addPlayer("Touched");
            PlayerMock untouched = server.addPlayer("Untouched");
            touched.teleport(new Location(world, 0, 64, 0));
            untouched.teleport(new Location(world, 0, 64, 0));

            // 手工把「本插件碰过他」写进缓存（等价于跑过一轮刷新）。
            cache("lastOrder").put(touched.getUniqueId(), Integer.valueOf(3000));
            cache("lastHeader").put(touched.getUniqueId(), Component.empty());
            untouched.setPlayerListOrder(7777);

            tabList.restoreAll();

            assertEquals(0, touched.getPlayerListOrder(), "碰过的玩家排序必须归 0（客户端默认）");
            assertEquals(7777, untouched.getPlayerListOrder(),
                    "从未被本插件碰过的玩家不得被改动 —— 否则 tab.enabled=false 时"
                            + "会把别的插件设的 tab 布局一并抹掉");
        }

        @Test
        @DisplayName("还原后缓存清空且任务停止")
        void restoreClearsCachesAndStops() throws Exception {
            withTab(true, 20);
            tabList.start();
            BukkitTask task = (BukkitTask) field("refreshTask");
            cache("lastName").put(player.getUniqueId(), Component.empty());

            tabList.restoreAll();

            assertTrue(task.isCancelled(), "插件关闭时还原后必须停掉刷新任务");
            assertNull(field("refreshTask"), "还原后句柄应归 null");
            assertTrue(cache("lastName").isEmpty(), "还原后缓存必须清空");
        }

        @Test
        @DisplayName("缓存为空时 restoreAll 什么都不做（不遍历全部在线玩家）")
        void emptyCacheRestoresNothing() {
            player.setPlayerListOrder(1234);

            tabList.restoreAll();

            assertEquals(1234, player.getPlayerListOrder(),
                    "缓存为空说明本插件从未碰过他，restoreAll 不得把全服玩家的分带清零");
        }
    }

    // ---------------------------------------------------------------- 4. 纯逻辑

    @Nested
    @DisplayName("可见条件与状态分类（私有纯方法，反射驱动）")
    class PureLogic {

        @Test
        @DisplayName("isVisible 对未知条件返回 false（拼错的 when 不得降级成 always）")
        void unknownConditionIsInvisible() throws Exception {
            Object running = viewFor(GameState.RUNNING, false);
            assertFalse(invokeIsVisible("typo-condition", running),
                    "闭集外的名字必须 false：降级成 always 会让本该隐藏的行冒出来");
            assertTrue(invokeIsVisible("running", running),
                    "对照：running 在 RUNNING 状态下应可见");
        }

        @Test
        @DisplayName("in-game 覆盖 STARTING/RUNNING/休整；finished 覆盖三种结算态")
        void stateBandsAreCorrect() throws Exception {
            assertTrue(invokeIsVisible("in-game", viewFor(GameState.STARTING, false)));
            assertTrue(invokeIsVisible("in-game", viewFor(GameState.RUNNING, false)));
            assertTrue(invokeIsVisible("in-game", viewFor(GameState.WAVE_INTERMISSION, false)));
            assertFalse(invokeIsVisible("in-game", viewFor(GameState.WAITING, false)));

            assertTrue(invokeIsVisible("finished", viewFor(GameState.VICTORY, false)));
            assertTrue(invokeIsVisible("finished", viewFor(GameState.DEFEAT, false)));
            assertTrue(invokeIsVisible("finished", viewFor(GameState.ENDING, false)));
            assertFalse(invokeIsVisible("finished", viewFor(GameState.RUNNING, false)));
        }

        @Test
        @DisplayName("排序分带：大厅 > 存活 > 死亡 > 观战，且都为正（Paper 要求 order >= 0）")
        void orderBandsAreStrictlyOrderedAndPositive() throws Exception {
            int lobby = invokeOrderOf("LOBBY");
            int alive = invokeOrderOf("ALIVE");
            int dead = invokeOrderOf("DEAD");
            int spectator = invokeOrderOf("SPECTATOR");

            assertTrue(lobby > alive, "大厅应排在最前");
            assertTrue(alive > dead, "存活应排在死亡待复活之前");
            assertTrue(dead > spectator, "死亡待复活应排在纯观战之前");
            assertTrue(spectator >= 0, "所有分带必须 >= 0（Paper 的 setPlayerListOrder 要求）");
        }

        @Test
        @DisplayName("stateOf：大厅/存活/死亡待复活/纯观战 四态判别")
        void stateOfDistinguishesDeadFromSpectator() throws Exception {
            // ⚠️ 必须先建成局部变量，再 thenReturn：直接 when(...).thenReturn(mockArena(...))
            // 会在桩还没闭合时重入 when()，抛 UnfinishedStubbingException
            // （MechanicsFixtures 类注释记录的同一个坑，本类踩了两次）。
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(null);
            assertEquals("LOBBY", invokeStateOf(player));

            ArenaInstance aliveArena = mockArena(GameState.RUNNING, false);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(aliveArena);
            assertEquals("ALIVE", invokeStateOf(player));

            ArenaInstance deadArena = mockArena(GameState.RUNNING, true);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(deadArena);
            player.setGameMode(GameMode.SPECTATOR);
            assertEquals("DEAD", invokeStateOf(player),
                    "对局进行中的旁观者 + SPECTATOR 模式是「死亡待复活」，应排在存活之后而不是最后");

            ArenaInstance waitingArena = mockArena(GameState.WAITING, true);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(waitingArena);
            assertEquals("SPECTATOR", invokeStateOf(player),
                    "场未开始时旁观者是纯观战。若这里判成 DEAD，等开局的观众会被排进对局主视区");
        }

        @Test
        @DisplayName("contextOf：不在任何竞技场 → 大厅；是旁观者 → 观战；否则 → 对局")
        void contextOfTracksArenaMembership() throws Exception {
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(null);
            assertEquals("LOBBY", invokeContextOf(player));

            ArenaInstance memberArena = mockArena(GameState.RUNNING, false);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(memberArena);
            assertEquals("ARENA", invokeContextOf(player));

            ArenaInstance spectatorArena = mockArena(GameState.RUNNING, true);
            when(arenas.getPlayerArena(any(Player.class))).thenReturn(spectatorArena);
            assertEquals("SPECTATOR", invokeContextOf(player));
        }

        // ---- 反射驱动私有纯方法（不向生产代码加测试可见性） ----

        private ArenaInstance mockArena(GameState state, boolean spectator) {
            ArenaInstance arena = mock(ArenaInstance.class);
            when(arena.getArenaId()).thenReturn("arena1");
            when(arena.getState()).thenReturn(state);
            when(arena.isSpectator(any(UUID.class))).thenReturn(spectator);
            when(arena.getPlayerCount()).thenReturn(2);
            when(arena.getMinPlayers()).thenReturn(4);
            when(arena.getSpectatorCount()).thenReturn(1);
            when(arena.getStartCountdown()).thenReturn(-1);
            when(arena.getCurrentWave()).thenReturn(3);
            when(arena.getTotalWaves()).thenReturn(10);
            when(arena.getWaveIntervalSeconds()).thenReturn(20);
            when(arena.getDisplayName()).thenReturn("Arena 1");
            return arena;
        }

        private Object viewFor(GameState state, boolean spectator) throws ReflectiveOperationException {
            Class<?> viewType = Class.forName("com.hilltopvillage.mechanics.TabListManager$View");
            java.lang.reflect.Constructor<?> ctor = viewType.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(null, null, state, spectator, false, -1, 0);
        }

        private boolean invokeIsVisible(String when, Object view) throws ReflectiveOperationException {
            Method m = TabListManager.class.getDeclaredMethod(
                    "isVisible", String.class,
                    Class.forName("com.hilltopvillage.mechanics.TabListManager$View"));
            m.setAccessible(true);
            return (boolean) m.invoke(tabList, when, view);
        }

        private int invokeOrderOf(String stateName) throws ReflectiveOperationException {
            Class<?> stateType = Class.forName("com.hilltopvillage.mechanics.TabListManager$TargetState");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object state = Enum.valueOf((Class<Enum>) stateType, stateName);
            Method m = TabListManager.class.getDeclaredMethod("orderOf", stateType);
            m.setAccessible(true);
            return (int) m.invoke(tabList, state);
        }

        private String invokeStateOf(Player p) throws ReflectiveOperationException {
            Method m = TabListManager.class.getDeclaredMethod("stateOf", Player.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(tabList, p));
        }

        private String invokeContextOf(Player p) throws ReflectiveOperationException {
            Method m = TabListManager.class.getDeclaredMethod("contextOf", Player.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(tabList, p));
        }
    }

    // ---------------------------------------------------------------- 5. 缓存与构造

    @Test
    @DisplayName("cleanup(player) 清掉四张缓存，不尝试还原")
    void cleanupDropsAllFourCaches() throws Exception {
        UUID id = player.getUniqueId();
        for (String name : new String[] {"lastHeader", "lastFooter", "lastName"}) {
            cache(name).put(id, Component.empty());
        }
        cache("lastOrder").put(id, Integer.valueOf(3000));
        player.setPlayerListOrder(4321);

        tabList.cleanup(player);

        for (String name : new String[] {"lastHeader", "lastFooter", "lastName", "lastOrder"}) {
            assertFalse(cache(name).containsKey(id),
                    name + " 里不得残留已退出玩家的条目（缓存是纯镜像，退出即删）");
        }
        assertEquals(4321, player.getPlayerListOrder(),
                "cleanup 不得尝试还原：玩家已离开，对失效实体调用没有意义");
    }

    @Test
    @DisplayName("对 null 玩家的 cleanup/apply/refreshNow 都是安全的空操作")
    void nullPlayerIsSafe() throws Exception {
        tabList.cleanup(null);
        tabList.apply(null);
        tabList.refreshNow(null);

        assertEquals(0, cache("lastName").size(), "null 玩家不应往缓存里写任何东西");
        assertEquals(0, cache("lastOrder").size(), "null 玩家不应往缓存里写任何东西");
    }

    @Test
    @DisplayName("构造器不启动任何任务（生命周期由 start()/apply() 显式驱动）")
    void constructionDoesNotScheduleAnything() throws Exception {
        assertNull(field("refreshTask"), "构造阶段不得启动任务");
        assertEquals(-1, field("scheduledPeriod"), "构造阶段周期应为 -1（未调度）");
    }

    @Test
    @DisplayName("apply() 在 tab 关闭时不做任何事，打开时会自愈启动任务")
    void applySelfHealsTheTask() throws Exception {
        withTab(false, 20);
        tabList.apply(player);
        assertNull(field("refreshTask"), "tab 关闭时 apply() 不得启动任务");

        withTab(true, 20);
        tabList.apply(player);
        assertNotNull(field("refreshTask"),
                "tab 打开后第一个加入的玩家应是补开点 —— 插件启停顺序可能让任务没开起来");
    }
}
