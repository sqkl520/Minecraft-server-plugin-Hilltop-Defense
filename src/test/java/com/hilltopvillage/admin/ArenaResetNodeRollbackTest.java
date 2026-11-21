package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.ArenaResetService.Outcome;
import com.hilltopvillage.admin.ArenaResetService.ReinstateSkip;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.mechanics.MapRestoreManager;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.testsupport.MockBukkitTestBase.TestPaths;

/**
 * #4 回归：第 8 步 {@code clearDeploymentData} 失败时，第 5 步已不可逆删除 nodes.yml，
 * 中止路径却会把竞技场重新启用 —— 结果是一张「可玩但 0 节点」的坏图。
 *
 * <p>本测试走真实 {@code nodes.yml} 与真实 {@link ArenaResetService} 的前 8 步，
 * 只把世界/实例/arenas.yml 侧替换为 mock。断言两件事：</p>
 * <ol>
 *   <li>中止后目标世界的 2 个节点必须回到文件里，其它世界条目不受影响；</li>
 *   <li>{@link Outcome#nodesCleared()} 不能再谎报节点清理成功。</li>
 * </ol>
 */
class ArenaResetNodeRollbackTest extends MockBukkitTestBase {

    private static final String ARENA_ID = "arena1";
    private static final String WORLD = "hilltop_arena1";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager multi;
    private ArenaManager definitions;
    private MapRestoreManager mapRestore;
    private ArenaResetService service;

    @BeforeEach
    void setUp() {
        plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ArenaResetNodeRollbackTest"));
        when(plugin.getName()).thenReturn("HilltopVillage");
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());

        multi = mock(MultiArenaManager.class);
        definitions = mock(ArenaManager.class);
        mapRestore = mock(MapRestoreManager.class);
        when(plugin.getMultiArenaManager()).thenReturn(multi);
        when(plugin.getMapRestoreManager()).thenReturn(mapRestore);
        when(multi.getDefinitionManager()).thenReturn(definitions);
        when(multi.getStatus(anyString())).thenReturn(ArenaStatus.DISABLED);

        service = new ArenaResetService(plugin);
    }

    @Test
    @DisplayName("第 8 步失败中止：先恢复第 5 步删掉的节点，再重新启用竞技场")
    void deploymentFailureRestoresNodesBeforeReinstate() throws Exception {
        File nodesFile = dataFolder.resolve("nodes.yml").toFile();
        TestPaths.write(nodesFile, """
                nodes:
                  - world: hilltop_arena1
                    x: 10
                    y: 64
                    z: 10
                  - world: hilltop_arena1
                    x: 20
                    y: 64
                    z: 20
                  - world: lobby
                    x: 0
                    y: 64
                    z: 0
                """);

        World world = server.addSimpleWorld(WORLD);
        Location core = new Location(world, 10, 65, 10);
        ArenaDefinition definition = new ArenaDefinition(
                ARENA_ID, "Arena 1", true, WORLD, null, core, List.of(), 8, 10, 40, 0);
        when(definitions.getArena(ARENA_ID)).thenReturn(definition);

        ArenaInstance live = mock(ArenaInstance.class);
        when(live.getState()).thenReturn(GameState.WAITING);
        when(live.getNodeSystem()).thenReturn(mock(NodeSystem.class));
        when(multi.getArena(ARENA_ID)).thenReturn(live);
        when(mapRestore.getPendingCount(WORLD)).thenReturn(0);

        // 第 8 步：模拟 arenas.yml 不可写（重置前 core 仍在 → reinstate 会成功）
        when(definitions.clearDeploymentData(ARENA_ID)).thenReturn(false);
        when(multi.enableArena(ARENA_ID)).thenReturn(true);

        AtomicReference<Outcome> result = new AtomicReference<>();
        service.reset(ARENA_ID, result::set);

        Outcome outcome = result.get();
        assertNotNull(outcome, "中止路径必须回调一次");

        List<Location> restored = NodeSystem.readNodesForWorld(dataFolder.toFile(), WORLD);
        assertEquals(2, restored.size(),
                "第 8 步失败后必须把第 5 步删掉的 2 个节点恢复，否则重新启用的是 0 节点空图");
        assertEquals(10, restored.get(0).getBlockX());
        assertEquals(20, restored.get(1).getBlockX());
        assertEquals(1, NodeSystem.readNodesForWorld(dataFolder.toFile(), "lobby").size(),
                "回滚只能影响目标世界，不能动其它竞技场/大厅的节点");

        assertEquals("deployment", outcome.failStep());
        assertTrue(outcome.reinstated(), "core 未被清掉，中止应能恢复启用");
        assertFalse(outcome.nodesCleared(),
                "节点已随中止回滚，不能再报告 nodesCleared=true");
        verify(multi).enableArena(ARENA_ID);
    }

    @Test
    @DisplayName("节点回滚也失败：绝不重新启用，否则仍是 0 节点空图")
    void rollbackFailureKeepsArenaDisabled() throws Exception {
        File nodesFile = dataFolder.resolve("nodes.yml").toFile();
        TestPaths.write(nodesFile, """
                nodes:
                  - world: hilltop_arena1
                    x: 10
                    y: 64
                    z: 10
                  - world: hilltop_arena1
                    x: 20
                    y: 64
                    z: 20
                """);

        World world = server.addSimpleWorld(WORLD);
        Location core = new Location(world, 10, 65, 10);
        ArenaDefinition definition = new ArenaDefinition(
                ARENA_ID, "Arena 1", true, WORLD, null, core, List.of(), 8, 10, 40, 0);
        when(definitions.getArena(ARENA_ID)).thenReturn(definition);

        ArenaInstance live = mock(ArenaInstance.class);
        when(live.getState()).thenReturn(GameState.WAITING);
        when(live.getNodeSystem()).thenReturn(mock(NodeSystem.class));
        when(multi.getArena(ARENA_ID)).thenReturn(live);
        when(mapRestore.getPendingCount(WORLD)).thenReturn(0);

        Path moved = dataFolder.resolveSibling(dataFolder.getFileName() + "-moved");
        doAnswer(invocation -> {
            // 让 dataFolder 本身变成普通文件：随后 restoreNodesForWorld 无法创建 nodes.yml。
            Files.move(dataFolder, moved);
            Files.writeString(dataFolder, "not a directory");
            return false;
        }).when(definitions).clearDeploymentData(ARENA_ID);

        try {
            AtomicReference<Outcome> result = new AtomicReference<>();
            service.reset(ARENA_ID, result::set);

            Outcome outcome = result.get();
            assertNotNull(outcome);
            assertEquals("deployment", outcome.failStep());
            assertFalse(outcome.reinstated(), "节点补不回来时禁止重新启用");
            assertEquals(ReinstateSkip.NODE_ROLLBACK_FAILED, outcome.reinstateSkipped());
            verify(multi, never()).enableArena(ARENA_ID);
        } finally {
            TestPaths.deleteQuietly(moved);
        }
    }
}
