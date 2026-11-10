package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link NodeSystem} 的回归测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：{@code saveNodesToConfig()} 改为返回
 * {@code boolean}，{@code removeNode(Location)} 把该结果<b>原样向上传播</b>
 * （{@code NodeSystem.java:250-252,262-309,389-410}）。旧实现无论写盘成败都返回
 * {@code void}，调用方（GUI / 命令）只能报「已删除」—— 而磁盘上的 {@code nodes.yml}
 * 根本没更新，重启后节点<b>幽灵复活</b>，管理员以为删掉了。</p>
 *
 * <p><b>刻意不断言「内存回滚」</b>：{@code removeNode} 写盘失败时<b>不回滚</b>内存删除，
 * 这是有意的（{@code :400-405}）。{@code NodeData.deactivate()} 不可逆，
 * 重建一个新对象会把该节点当前血量一起丢掉 —— 等于给玩家<b>免费修满</b>。
 * 因此本测试在失败分支反而断言「内存里确实已经没了」：一个「顺手修好」成
 * 回滚内存的实现会通过天真的 {@code assertFalse}，却在悄悄重新打开刷血漏洞。</p>
 */
@DisplayName("NodeSystem 能量节点")
class NodeSystemTest extends MockBukkitTestBase {

    private static final String WORLD = "hilltop_arena1";
    private static final int NODE_X = 10;
    private static final int NODE_Y = 64;
    private static final int NODE_Z = 10;

    private Logger logger;
    private WorldMock world;
    private File nodesFile;
    private NodeSystem system;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("node-test-" + System.nanoTime());
        GameConfig config = new GameConfig();
        config.setNodeBaseHealth(100.0);

        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), config);
        world = server.addSimpleWorld(WORLD);

        GameManager gameManager = mock(GameManager.class);
        // 先赋局部变量再 thenReturn（同 MechanicsFixtures 注释里的 Mockito 重入坑）
        ConfigManager configManager = plugin.getConfigManager();
        when(gameManager.getConfigManager()).thenReturn(configManager);
        when(gameManager.getPlugin()).thenReturn(plugin);
        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.getArenaId()).thenReturn("arena1");

        nodesFile = new File(dataFolder.toFile(), "nodes.yml");
        system = new NodeSystem(gameManager);
    }

    // ---------------------------------------------------------------- 工具

    /** 放一个重生锚并在其正上方调用 addNode（addNode 向下查一格）。 */
    private int placeAndAddNode(int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.RESPAWN_ANCHOR);
        return system.addNode(new Location(world, x, y + 1, z));
    }

    private Location nodeLocation(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    private List<?> readNodesEntries() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(nodesFile.toPath(), StandardCharsets.UTF_8));
        return yaml.getMapList("nodes");
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Test
    @DisplayName("文件可写时 removeNode 返回 true 且磁盘条目真的消失")
    void removeNodeReturnsTrueWhenWriteSucceeds() throws Exception {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z), "前置条件：节点应被成功添加");
        assertEquals(1, system.getTotalCount());
        assertEquals(1, readNodesEntries().size(), "前置条件：nodes.yml 应已写入 1 条");

        assertTrue(system.removeNode(nodeLocation(NODE_X, NODE_Y, NODE_Z)),
                "写盘成功时应返回 true —— 否则 GUI 会把一次成功报成失败");
        assertEquals(0, system.getTotalCount(), "内存中的节点也应被删除");
        assertEquals(0, readNodesEntries().size(),
                "nodes.yml 里的条目必须真的消失，否则重启后节点会幽灵复活");
    }

    /**
     * 损坏的 {@code nodes.yml} → 拒写 → {@code removeNode} 返回 false，
     * <b>但内存里的节点仍然已被删除</b>。
     *
     * <p>后一半断言是这条测试的真正价值：它把「不回滚内存」这个<b>有意设计</b>
     * 钉在测试里。将来若有人为了「让失败更干净」加上回滚，这里会红 ——
     * 而那不是一次改进，是把免费修满节点的漏洞重新打开。</p>
     */
    @Test
    @DisplayName("nodes.yml 损坏时 removeNode 返回 false（内存删除不回滚）")
    void removeNodeReturnsFalseWhenFileUnwritable() throws Exception {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z), "前置条件：节点应被成功添加");

        // 写入一个 nodesValidator 必然拒绝的内容：nodes 段不是列表
        Files.writeString(nodesFile.toPath(), "nodes: \"not-a-list\"\n", StandardCharsets.UTF_8);
        var capture = attachLogCapture(logger);

        assertFalse(system.removeNode(nodeLocation(NODE_X, NODE_Y, NODE_Z)),
                "文件不可用时应返回 false，让调用方能如实告诉管理员「没保存成功」");

        assertEquals(0, system.getTotalCount(),
                "内存删除【不】应回滚：重建 NodeData 会把血量一起重置，等于免费修满节点");
        assertTrue(capture.anyContains("will reappear after a restart"),
                "必须明确告知「重启后会复活」，否则管理员会以为删除已生效；实际：" + capture.lines());
    }

    @Test
    @DisplayName("saveNodesToConfig 在只读降级后持续返回 false 并告警")
    void saveReturnsFalseOnceFileIsQuarantined() {
        // 先让 loadNodesFromConfig 撞上坏文件 → NODES_READ_ONLY 锁存
        try {
            Files.writeString(nodesFile.toPath(), "  \n", StandardCharsets.UTF_8);   // 空文件 → SUSPECT
        } catch (Exception e) {
            throw new AssertionError("前置条件写入失败", e);
        }
        system.loadNodesFromConfig();

        var capture = attachLogCapture(logger);
        assertFalse(system.saveNodesToConfig(),
                "只读降级后必须拒写 —— 整份覆写会把其它竞技场的节点条目一起抹掉");
        assertTrue(capture.anyMatch(line -> line.startsWith(Level.WARNING.getName() + "|")
                        && (line.contains("Refusing to save") || line.contains("Refusing to write"))),
                "拒写必须留日志；实际捕获：" + capture.lines());
    }

    @Test
    @DisplayName("不存在的节点 removeNode 返回 false，不做任何写盘")
    void removeNodeReturnsFalseForUnknownLocation() {
        assertFalse(system.removeNode(new Location(world, 99, 64, 99)),
                "不存在的坐标应直接返回 false（不是「写盘失败」，而是「本来就没有」）");
        assertFalse(nodesFile.exists(), "没有节点时不该创建 nodes.yml");
    }

    // ---------------------------------------------------------------- 2. 大小写一致性

    /**
     * 写回过滤必须忽略世界名大小写。
     *
     * <p>守的是 fix6 Y1-N1：{@code contains} 是精确比较，服主把 {@code arenas.yml} 里的
     * 世界名改大小写后，旧条目会失配而<b>永不被过滤</b> —— 表现为「删除节点不落盘、
     * 下次加载幽灵复活」。这条修复没有任何玩家可见的即时症状，只有长期数据腐坏。</p>
     */
    @Test
    @DisplayName("写回过滤忽略世界名大小写，旧条目被清掉而不是重复")
    void saveFiltersExistingEntriesIgnoringWorldNameCase() throws Exception {
        // 手工写入一条「大小写不同」的旧条目（模拟改过 arenas.yml 的服主）
        YamlConfiguration seed = new YamlConfiguration();
        seed.set("nodes", List.of(java.util.Map.of(
                "world", "Hilltop_Arena1", "x", 1, "y", 64, "z", 1)));
        Files.writeString(nodesFile.toPath(), seed.saveToString(), StandardCharsets.UTF_8);

        // 本实例的世界名是全小写，注册一个新节点后落盘
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z), "前置条件：新节点应添加成功");

        List<?> entries = readNodesEntries();
        assertEquals(1, entries.size(),
                "写回应只留下本世界的新条目；实际条目数 " + entries.size()
                        + "（=2 说明大小写不同的旧条目没被过滤，删除节点将来会幽灵复活）");
        assertEquals(WORLD, ((java.util.Map<?, ?>) entries.get(0)).get("world"));
    }

    // ---------------------------------------------------------------- 3. 数值边界

    @Test
    @DisplayName("damageNode 被当前血量截断，归零后移出活跃集")
    void damageNodeClampsToCurrentHealthAndDeactivates() {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z));
        assertEquals(1, system.getActiveCount());

        assertEquals(30.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), 30.0),
                "正常伤害应原样返回");
        assertEquals(70.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), 1e9),
                "超出剩余血量的伤害必须被截断为剩余值 —— 返回 1e9 会让战报/统计溢出");
        assertEquals(0, system.getActiveCount(), "血量归零后节点必须退出活跃集");
    }

    @Test
    @DisplayName("非有限值与负伤害被忽略")
    void damageNodeRejectsInvalidInput() {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z));

        assertEquals(0.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), -5.0));
        assertEquals(0.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), Double.NaN));
        assertEquals(0.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z),
                Double.POSITIVE_INFINITY));
        assertEquals(1, system.getActiveCount(), "无效输入不得改变节点状态");
    }

    @Test
    @DisplayName("addNode 拒绝非重生锚方块与重复坐标")
    void addNodeValidatesBlockTypeAndDuplicates() {
        // 非重生锚：addNode 向下查到 STONE
        world.getBlockAt(20, 64, 20).setType(Material.STONE);
        assertEquals(1, system.addNode(new Location(world, 20, 65, 20)),
                "返回 1 = 不是有效节点方块");
        assertEquals(0, system.getTotalCount());

        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z), "第一次添加应成功");
        assertEquals(2, system.addNode(new Location(world, NODE_X, NODE_Y + 1, NODE_Z)),
                "返回 2 = 已存在");
        assertEquals(1, system.getTotalCount());
    }

    // ---------------------------------------------------------------- 4. 配置加载

    @Test
    @DisplayName("loadNodesFromConfig 在文件损坏时保留现有节点")
    void loadKeepsNodesWhenFileUnusable() throws Exception {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z));
        assertEquals(0, placeAndAddNode(NODE_X + 2, NODE_Y, NODE_Z));
        assertEquals(2, system.getTotalCount());

        Files.writeString(nodesFile.toPath(), "nodes: oops\n", StandardCharsets.UTF_8);
        var capture = attachLogCapture(logger);

        system.loadNodesFromConfig();

        assertEquals(2, system.getTotalCount(),
                "文件损坏时必须保留现有节点 —— 只有「文件不存在」才是清空语义。"
                        + "把损坏当空配置会让 /hilltop deploy reloadnodes 静默抹掉整个竞技场");
        assertTrue(capture.anyMatch(line -> line.startsWith(Level.SEVERE.getName() + "|")),
                "损坏必须打 SEVERE；实际：" + capture.lines());
    }

    @Test
    @DisplayName("nodes.yml 不存在时从空列表开始")
    void loadStartsEmptyWhenFileAbsent() {
        assertFalse(nodesFile.exists(), "前置条件：构造函数不应创建 nodes.yml");
        assertEquals(0, system.getTotalCount());
        assertEquals(0, system.getActiveCount());
    }

    @Test
    @DisplayName("resetAllNodes 把打残的节点恢复满血并重新激活")
    void resetAllNodesRestoresHealthAndActivity() {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z));
        system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), 1e9);
        assertEquals(0, system.getActiveCount(), "前置条件：节点应已被摧毁");

        system.resetAllNodes();

        assertEquals(1, system.getActiveCount(),
                "新一局必须把节点恢复活跃 —— 否则上一局打残的节点会跨局残留，开局即劣势");
        assertEquals(100.0, system.damageNode(nodeLocation(NODE_X, NODE_Y, NODE_Z), 1e9),
                "血量应已回满 100");
    }

    // ---------------------------------------------------------------- 5. 查询与纯工具

    @Test
    @DisplayName("getNodeAt 对视角/小数坐标不敏感（normalizeLocation）")
    void getNodeAtIgnoresPitchAndFractionalCoordinates() {
        assertEquals(0, placeAndAddNode(NODE_X, NODE_Y, NODE_Z));

        // Location 的小数部分是 floor 的（getBlockX/Z 语义），因此偏移必须保持在同一方块内
        Location withAngle = new Location(world, NODE_X + 0.7, NODE_Y + 0.4, NODE_Z + 0.3, 45f, 30f);
        assertNotNull(system.getNodeAt(withAngle),
                "节点查找必须按整数方块坐标归一化；带 yaw/pitch 的玩家位置查不到节点会让修复交互失效");
        assertNull(system.getNodeAt(new Location(world, NODE_X + 5, NODE_Y, NODE_Z)));
    }
}
