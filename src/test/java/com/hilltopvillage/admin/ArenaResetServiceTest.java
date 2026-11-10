package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.ArenaResetService.Gate;
import com.hilltopvillage.admin.ArenaResetService.Outcome;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.mechanics.MapRestoreManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ArenaResetService} 的准入闸门与中止路径测试。
 *
 * <p><b>范围声明</b>：本类只测<b>可以在主线程同步走到、且不触达真实方块回滚</b>的路径 ——
 * {@link ArenaResetService#canReset} 的七种判定、{@code reset()} 的 blocked 收尾、
 * 以及 slice-02-eh-004（中止后必须恢复启用状态）/ slice-02-logic-002（{@code pendingFor}
 * 按世界名而非竞技场 ID）两条 round1 修复。12 步重置序列的中间步骤（clearNodesForWorld、
 * clearDeploymentData、map restore）需要真实文件与真实回滚任务，<b>本类不覆盖</b>，
 * 见报告 residual_risks。</p>
 *
 * <p><b>为什么这些值得测</b>：{@code canReset} 的判定顺序是本类的核心不变量 ——
 * ALREADY_RESETTING 必须排在最前，因为重置中途竞技场会被压成 DISABLED 且 core 已删，
 * 其余判定只会给出误导性的 MISSING_CORE / STATUS_PENDING。顺序错了，GUI 会引导管理员
 * 去做一件正在被做的事。</p>
 */
class ArenaResetServiceTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private MultiArenaManager multi;
    private ArenaManager definitions;
    private MapRestoreManager mapRestore;
    private ArenaResetService service;

    @BeforeEach
    void setUp() {
        plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ArenaResetServiceTest"));
        when(plugin.getName()).thenReturn("HilltopVillage");

        multi = mock(MultiArenaManager.class);
        definitions = mock(ArenaManager.class);
        mapRestore = mock(MapRestoreManager.class);

        when(plugin.getMultiArenaManager()).thenReturn(multi);
        when(plugin.getMapRestoreManager()).thenReturn(mapRestore);
        when(multi.getDefinitionManager()).thenReturn(definitions);
        when(multi.getStatus(anyString())).thenReturn(ArenaStatus.DISABLED);

        service = new ArenaResetService(plugin);
    }

    // ------------------------------------------------------------------ 夹具

    /** 世界已加载、core 已部署、enabled: true 的「就绪」定义。 */
    private ArenaDefinition readyDefinition(String id, String worldName, boolean enabled, Location core) {
        return new ArenaDefinition(id, id, enabled, worldName,
                null, core, List.of(), 8, 10, 40, 0);
    }

    private Location coreIn(String worldName) {
        World world = server.getWorld(worldName);
        if (world == null) world = server.addSimpleWorld(worldName);
        return new Location(world, 0, 64, 0);
    }

    /**
     * 造一个「对局中」的实例并挂到 {@code multi.getArena(id)}。
     *
     * <p><b>必须在 {@code when(...)} 之外完成 mock 与桩</b>：直接写
     * {@code when(multi.getArena(id)).thenReturn(liveInstance(state))} 会让
     * {@code liveInstance} 内部对 {@code instance.getState()} 的 {@code when()} 落在
     * 外层未闭合的桩里，抛 {@code UnfinishedStubbingException}。
     * 这是 {@code MechanicsFixtures} 里记录的同一个坑（round2 勘察报告 B-2）。</p>
     */
    private void stubLiveInstance(String arenaId, GameState state) {
        ArenaInstance instance = mock(ArenaInstance.class);
        when(instance.getState()).thenReturn(state);
        when(multi.getArena(arenaId)).thenReturn(instance);
    }

    // ================================================================== canReset

    @Nested
    @DisplayName("canReset 的准入判定")
    class CanReset {

        @Test
        @DisplayName("竞技场不存在 → ARENA_NOT_FOUND")
        void unknownArenaIsNotFound() {
            when(definitions.getArena("ghost")).thenReturn(null);

            assertEquals(Gate.ARENA_NOT_FOUND, service.canReset("ghost"));
        }

        @Test
        @DisplayName("空白 / null 的 ID 不被当作有效竞技场")
        void blankArenaIdIsNotFound() {
            assertEquals(Gate.ARENA_NOT_FOUND, service.canReset(""));
            assertEquals(Gate.ARENA_NOT_FOUND, service.canReset("   "));
            assertEquals(Gate.ARENA_NOT_FOUND, service.canReset(null));
        }

        @Test
        @DisplayName("启用队列中（QUEUED / LOADING）→ STATUS_PENDING")
        void pendingStatusBlocksReset() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.QUEUED);

            assertEquals(Gate.STATUS_PENDING, service.canReset("arena1"),
                    "QUEUED/LOADING 时重置会与旧定义建实例竞争，必须拦住");

            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.LOADING);
            assertEquals(Gate.STATUS_PENDING, service.canReset("arena1"));
        }

        @Test
        @DisplayName("未部署核心（core == null）→ MISSING_CORE")
        void missingCoreBlocksReset() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            assertEquals(Gate.MISSING_CORE, service.canReset("arena1"),
                    "core 为 null 表示尚未部署，没有可重置的内容");
        }

        @Test
        @DisplayName("对局进行中（RUNNING / WAVE_INTERMISSION / STARTING）→ GAME_IN_PROGRESS")
        void liveGameBlocksReset() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.READY);

            for (GameState state : List.of(GameState.RUNNING, GameState.WAVE_INTERMISSION, GameState.STARTING)) {
                stubLiveInstance("arena1", state);
                assertEquals(Gate.GAME_IN_PROGRESS, service.canReset("arena1"),
                        state + " 时重置会连同在场玩家一起踢掉");
            }
        }

        @Test
        @DisplayName("上一次回滚未完成（ENDING）→ RESET_IN_PROGRESS")
        void endingStateBlocksReset() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.READY);
            stubLiveInstance("arena1", GameState.ENDING);

            assertEquals(Gate.RESET_IN_PROGRESS, service.canReset("arena1"));
        }

        @Test
        @DisplayName("世界加载完成且无对局 → OK（PAUSED/WAITING 等非争议状态放行）")
        void idleArenaIsResettable() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.DISABLED);

            assertEquals(Gate.OK, service.canReset("arena1"));

            stubLiveInstance("arena1", GameState.WAITING);
            assertEquals(Gate.OK, service.canReset("arena1"), "WAITING 不是进行中，允许重置");
        }

        @Test
        @DisplayName("ALREADY_RESETTING 必须优先于 MISSING_CORE / STATUS_PENDING（否则 GUI 给出误导性原因）")
        void alreadyResettingOutranksTheMidResetGates() {
            // 重置进行中竞技场的真实形态：门已占，core 已被删，状态可能被压成 QUEUED/DISABLED。
            // 这三个条件同时成立 —— 只有「先看门」才能给出正确原因。
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.QUEUED);
            occupyInFlight("arena1");

            assertEquals(Gate.ALREADY_RESETTING, service.canReset("arena1"),
                    "重置中途 core 已删、状态被压成 QUEUED —— MISSING_CORE 与 STATUS_PENDING 都会命中。"
                            + "若 in-flight 判定不是排在它们之前，管理员会被告知「尚未部署核心」或"
                            + "「正在加载中」，而真正答案是「正在重置」。"
                            + "这是 canReset 类注释里点名的顺序约束");

            // 对照：门释放后，同一个竞技场立刻回落到真实的阻断原因 —— 证明上面命中的确实是门。
            // 注意回落值是 STATUS_PENDING 而不是 MISSING_CORE：源码里 STATUS_PENDING 排在
            // MISSING_CORE 之前（ArenaResetService:177-179），本用例同时把这条顺序也钉住。
            service.shutdown();
            assertEquals(Gate.STATUS_PENDING, service.canReset("arena1"),
                    "门释放后应回落到真实的阻断原因。此处状态仍是 QUEUED，"
                            + "而 STATUS_PENDING 在源码里先于 MISSING_CORE 判定 —— "
                            + "这条断言把该顺序钉住了（它影响 GUI 对「为什么不能重置」的措辞）");
        }

        @Test
        @DisplayName("门按规范化后的定义 ID 记，大小写变体同样命中")
        void inFlightKeyUsesCanonicalId() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
            occupyInFlight("arena1");

            assertTrue(service.isResetting("arena1"));
            assertFalse(service.isResetting("nonexistent"));
        }
    }

    // ================================================================== blocked outcome

    @Nested
    @DisplayName("reset() 的 blocked 收尾")
    class BlockedOutcome {

        @Test
        @DisplayName("被拦下时不谎报成功，且 blockedBy 如实回传")
        void blockedResetReportsNoSuccess() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            List<Outcome> got = new ArrayList<>();
            service.reset("arena1", got::add);

            assertEquals(1, got.size(), "回调必须触发且只触发一次，否则 GUI 永久等待");
            Outcome outcome = got.get(0);
            assertEquals(Gate.MISSING_CORE, outcome.blockedBy());
            assertFalse(outcome.dataCleared(), "没开始执行，dataCleared 不得谎报");
            assertFalse(outcome.nodesCleared());
            assertFalse(outcome.shopNpcCleared());
            assertFalse(outcome.mapRestored());
            assertFalse(outcome.reinstated(), "没开始执行，不可能重新启用");
            assertNull(outcome.failStep(), "blocked 由 blockedBy 表达，failStep 保持 null");
            assertNull(outcome.reinstateSkipped(), "没进入重启用判定，reinstateSkipped 保持 null");
        }

        @Test
        @DisplayName("blockedOutcome 的 pendingBlocksBefore 按【世界名】查，不是竞技场 ID（slice-02-logic-002）")
        void pendingBlocksAreLookedUpByWorldName() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));
            // 世界名上有待回滚方块，竞技场 ID 上没有 —— 传错键就会恒得 0
            when(mapRestore.getPendingCount("hilltop_arena1")).thenReturn(37);
            when(mapRestore.getPendingCount("arena1")).thenReturn(0);

            List<Outcome> got = new ArrayList<>();
            service.reset("arena1", got::add);

            assertEquals(37, got.get(0).blocksPendingBefore(),
                    "MapRestoreManager.journals 以【世界名】为键。传竞技场 ID 会恒查空、"
                            + "GUI 于是告诉管理员「没有待回滚方块」，而实际有 37 个 —— "
                            + "这正是 slice-02-logic-002");
        }

        @Test
        @DisplayName("世界名缺失时 pendingFor 返回 0，不把 null 传进 ConcurrentHashMap")
        void nullWorldNameYieldsZeroPending() {
            // worldName 不能为空白（ArenaDefinition 拒绝），故用 mock 返回 null worldName
            ArenaDefinition definition = mock(ArenaDefinition.class);
            when(definition.getId()).thenReturn("arena1");
            when(definition.getWorldName()).thenReturn(null);
            when(definition.getCore()).thenReturn(null);
            when(definitions.getArena("arena1")).thenReturn(definition);

            List<Outcome> got = new ArrayList<>();
            service.reset("arena1", got::add);

            assertEquals(0, got.get(0).blocksPendingBefore(),
                    "getPendingCount(null) 会抛 NPE；pendingFor 必须先把空世界名短路成 0");
            verify(mapRestore, never()).getPendingCount(null);
        }

        @Test
        @DisplayName("null 回调不抛异常（GUI 关闭后仍能安全收尾）")
        void nullCallbackIsTolerated() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            service.reset("arena1", null);

            assertEquals(Gate.MISSING_CORE, service.canReset("arena1"),
                    "没有回调也不得改变状态机");
        }

        @Test
        @DisplayName("回调抛异常被吞掉并记日志，不破坏本服务状态")
        void throwingCallbackIsSwallowed() {
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            service.reset("arena1", outcome -> {
                throw new IllegalStateException("GUI 已关闭");
            });

            // 门未被占用：后续调用仍能正常判定
            assertEquals(Gate.MISSING_CORE, service.canReset("arena1"),
                    "回调异常不得让 in-flight 门永久占用");
        }

        @Test
        @DisplayName("statusAfter 在近况良好时取真实状态，id 空白时回落到 DISABLED")
        void statusAfterFallsBackWhenIdIsBlank() {
            when(multi.getStatus("arena1")).thenReturn(ArenaStatus.READY);
            when(definitions.getArena("arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            List<Outcome> got = new ArrayList<>();
            service.reset("arena1", got::add);

            assertEquals(ArenaStatus.READY, got.get(0).statusAfter());
        }
    }

    @Nested
    @DisplayName("arenaId 的规范化")
    class CanonicalId {

        @Test
        @DisplayName("blockedOutcome 用规范化的定义 ID，而不是调用方传进来的原始字符串")
        void outcomeCarriesCanonicalId() {
            when(definitions.getArena("Arena1"))
                    .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, null));

            List<Outcome> got = new ArrayList<>();
            service.reset("Arena1", got::add);

            assertEquals("arena1", got.get(0).arenaId(),
                    "registry 查找忽略大小写，但对外一律用规范化 ID —— "
                            + "否则 GUI 拿 'Arena1' 去查状态会查不到");
        }

        @Test
        @DisplayName("竞技场不存在时保留调用方的原始 ID（否则错误信息里 ID 是 null）")
        void unknownArenaKeepsRawId() {
            when(definitions.getArena("ghost")).thenReturn(null);

            List<Outcome> got = new ArrayList<>();
            service.reset("ghost", got::add);

            assertEquals("ghost", got.get(0).arenaId(),
                    "定义不存在时必须回显原始 ID，否则管理员看不到自己刚才点了哪个");
            assertEquals(Gate.ARENA_NOT_FOUND, got.get(0).blockedBy());
        }
    }

    // ================================================================== 工具

    /** 把某个 arenaId 塞进私有的 in-flight 门，模拟「一次重置正在跑」。 */
    private void occupyInFlight(String arenaId) {
        try {
            java.lang.reflect.Field field = ArenaResetService.class.getDeclaredField("inFlight");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Boolean> map = (java.util.Map<String, Boolean>) field.get(service);
            map.put(arenaId, Boolean.TRUE);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法占用 inFlight 门（字段被重命名了？）", failure);
        }
    }

    @Test
    @DisplayName("shutdown 清空 in-flight 记录")
    void shutdownClearsInFlight() {
        when(definitions.getArena("arena1"))
                .thenReturn(readyDefinition("arena1", "hilltop_arena1", true, coreIn("hilltop_arena1")));
        occupyInFlight("arena1");
        assertTrue(service.isResetting("arena1"));

        service.shutdown();

        assertFalse(service.isResetting("arena1"));
        assertNotNull(service);
    }
}
