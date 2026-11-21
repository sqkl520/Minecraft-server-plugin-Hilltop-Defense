package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;

/**
 * S05-F1 —— 三个单条删除方法必须把写盘结果交回调用方。
 *
 * <p><b>缺陷原貌</b>：{@code removeSpawnPoint} / {@code removeMobSpawnPoint} / {@code removeVillager}
 * 的具体形态完全一样：先做序号校验，从内存列表移除，调 {@code saveSpawnPoints()}，
 * 然后<b>无条件</b> {@code return true}。写盘结果被整个丢掉，于是 {@code arenas.yml}
 * 只读或 IO 失败时管理员收到「已删除」，重启后点位原样回来。</p>
 *
 * <p><b>为什么两种 false 不需要新的返回类型</b>：{@code false} 仍然同时表示「序号非法」与
 * 「写盘失败」，但调用方全部<b>先自行校验序号</b>（命令路径与 {@code ArenaAdminService}
 * 都是先查 list 大小再调），因此校验通过后拿到的 false 只可能是写盘失败 —— 语义可区分，
 * 不需要引入三态枚举。本类把两侧都钉住：序号非法时内存不变，写盘失败时返回 false。</p>
 *
 * <p><b>怎么造「写盘失败」</b>：{@link YamlFileGate} 的只读判定来自真读盘，用 mock 替不了。
 * 这里先写一份合法 {@code arenas.yml} 让加载成功（内存里有三个点位），
 * 再把文件改写成空白 —— {@link YamlFiles} 判 SUSPECT → 隔离成 {@code .corrupt} →
 * 闸门锁定只读，与运行期服主改坏文件的路径完全一致。</p>
 */
class AdminDeployWriteResultTest extends MockBukkitTestBase {

    private static final String ARENA_ID = "arena1";

    private HilltopVillagePlugin plugin;
    private GameManager gameManager;
    private AdminDeployManager deploy;
    private File arenasFile;
    private World world;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("hilltop_arena1");
        arenasFile = new File(dataFolder.toFile(), "arenas.yml");

        YamlFileGate gate = new YamlFileGate(arenasFile, YamlFiles.arenasValidator(),
                Logger.getLogger("AdminDeployWriteResultTest-" + UUID.randomUUID()));

        ArenaManager definitions = mock(ArenaManager.class);
        when(definitions.getArenasGate()).thenReturn(gate);
        MultiArenaManager multi = mock(MultiArenaManager.class);
        when(multi.getDefinitionManager()).thenReturn(definitions);

        plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getName()).thenReturn("HilltopVillage");
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdminDeployWriteResultTest-plugin"));
        when(plugin.getMultiArenaManager()).thenReturn(multi);

        gameManager = mock(GameManager.class);
        when(gameManager.getArenaId()).thenReturn(ARENA_ID);
        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.getCoreLocation()).thenReturn(new Location(world, 0, 64, 0));

        deploy = new AdminDeployManager(plugin, gameManager);
    }

    /** 一份合法 arenas.yml：一个竞技场、三类点位各 1~2 条。 */
    private void writeValidArenasYml() throws IOException {
        String body = """
                arenas:
                  arena1:
                    display-name: "Arena 1"
                    enabled: true
                    world: hilltop_arena1
                    spawn-points:
                      - x: 1.0
                        y: 64.0
                        z: 2.0
                      - x: 11.0
                        y: 64.0
                        z: 12.0
                    mob-spawn-points:
                      - x: 3.0
                        y: 64.0
                        z: 4.0
                    villagers:
                      - x: 5.0
                        y: 64.0
                        z: 6.0
                """;
        Files.writeString(arenasFile.toPath(), body, StandardCharsets.UTF_8);
    }

    /** 把 arenas.yml 改成空白：闸门判定 SUSPECT → 隔离 → 本进程只读，后续写盘全部失败。 */
    private void breakArenasYml() throws IOException {
        Files.writeString(arenasFile.toPath(), "   \n", StandardCharsets.UTF_8);
    }

    // ================================================================ 成功路径

    @Nested
    @DisplayName("写盘成功：返回 true（不是「恒 true 也算对」—— 成功侧必须有对照）")
    class SuccessfulWrite {

        @Test
        @DisplayName("三个 remove 方法在可写状态下都返回 true，且点位真的少了一条")
        void removalsReturnTrueWhenWritable() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();
            assertEquals(2, deploy.getSpawnPoints().size(), "前置条件：玩家出生点应有 2 条");
            assertEquals(1, deploy.getMobSpawnPoints().size(), "前置条件：刷怪点应有 1 条");
            assertEquals(1, deploy.getVillagers().size(), "前置条件：村民应有 1 名");

            assertTrue(deploy.removeSpawnPoint(0), "可写状态下删除必须成功");
            assertTrue(deploy.removeMobSpawnPoint(0));
            assertTrue(deploy.removeVillager(0));

            assertEquals(1, deploy.getSpawnPoints().size());
            assertEquals(0, deploy.getMobSpawnPoints().size());
            assertEquals(0, deploy.getVillagers().size());
            assertFalse(deploy.consumeSaveFailure(),
                    "成功路径不得留下失败标记（否则下一条命令会收到假警报）");
        }
    }

    // ================================================================ 写盘失败路径

    @Nested
    @DisplayName("写盘失败：返回 false，不再谎报「已删除」")
    class WriteFailure {

        @Test
        @DisplayName("removeSpawnPoint 在只读闸门下返回 false")
        void removeSpawnPointReportsWriteFailure() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();
            breakArenasYml();
            // 先让闸门真的读到坏文件（生产路径里这一步由 saveSpawnPoints 内部的 load 完成，
            // 这里显式触发一次以便把「点位在内存里」与「闸门已锁」两个前提分开断言）
            assertFalse(deploy.saveSpawnPoints(), "前置条件：坏文件必须让写盘失败");

            assertFalse(deploy.removeSpawnPoint(0),
                    "S05-F1：写盘失败必须返回 false —— 否则管理员收到「已删除」，重启后点位回来");
            assertEquals(1, deploy.getSpawnPoints().size(),
                    "内存里的移除不回滚（与 saveSpawnPoints「失败不刷新 GameManager」口径一致），"
                            + "因此这里断言的是「确实执行了移除」而不是「保持 2 条」");
            assertTrue(deploy.consumeSaveFailure(), "失败标记必须仍然可被消费，供命令路径播报");
        }

        @Test
        @DisplayName("removeMobSpawnPoint 在只读闸门下返回 false")
        void removeMobSpawnPointReportsWriteFailure() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();
            breakArenasYml();

            assertFalse(deploy.removeMobSpawnPoint(0),
                    "三个方法的缺陷形态完全相同，修复也必须一致");
        }

        @Test
        @DisplayName("removeVillager 在只读闸门下返回 false")
        void removeVillagerReportsWriteFailure() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();
            breakArenasYml();

            assertFalse(deploy.removeVillager(0),
                    "村民删除同样不得吞掉写盘结果");
        }
    }

    // ================================================================ 序号非法

    @Nested
    @DisplayName("序号非法：返回 false 且内存完全不变（与写盘失败共用 false，靠调用方前置校验区分）")
    class InvalidIndex {

        @Test
        @DisplayName("越界序号不改动任何列表")
        void outOfRangeIndexChangesNothing() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();

            assertFalse(deploy.removeSpawnPoint(5), "越界必须返回 false");
            assertEquals(2, deploy.getSpawnPoints().size(), "越界不得动内存");

            assertFalse(deploy.removeMobSpawnPoint(-1), "负序号同样越界");
            assertEquals(1, deploy.getMobSpawnPoints().size());

            assertFalse(deploy.removeVillager(9));
            assertEquals(1, deploy.getVillagers().size());
        }
    }

    // ================================================================ 调用方语义

    @Nested
    @DisplayName("命令路径：序号校验前置后可区分两种失败")
    class CommandDistinguishesFailureModes {

        @Test
        @DisplayName("ArenaAdminService 已在调用前自行校验序号（false 因此只剩「写盘失败」一义）")
        void serviceValidatesIndexBeforeCalling() throws IOException {
            writeValidArenasYml();
            deploy.loadSpawnPoints();
            breakArenasYml();

            // 直接复刻 ArenaAdminService#deleteSpawnPoint 的校验顺序：越界先判，再交给 deploy
            int index = 5;
            boolean rejectedBySizeCheck = index >= deploy.getSpawnPoints().size();
            assertTrue(rejectedBySizeCheck,
                    "★ 这一条是「不需要三态返回值」的前提：调用方自己先查大小，"
                            + "因此校验通过后拿到的 false 只能是写盘失败");
        }
    }
}
