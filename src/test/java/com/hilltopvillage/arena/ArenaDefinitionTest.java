package com.hilltopvillage.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ArenaDefinition} 的值对象契约。
 *
 * <p><b>为什么一个「只有 getter」的类需要 20 条断言</b>：它是整个插件里唯一被
 * {@code ArenaManager.load()} / {@code AdminDeployManager} / {@code ArenaInstance} /
 * 管理 GUI 四方共享的<b>可变性边界</b>。它对外承诺「不可变」，靠的是<b>每个 Location 都
 * 进出一律 {@code clone()}</b>；一旦某处漏掉 clone：</p>
 * <ul>
 *   <li>越界的写入会静默改掉「已加载的竞技场定义」——而 {@code ArenaInstance} 持有的是构造期快照，
 *       于是出现「GUI 显示 A、实际刷怪在 B」这类不可复现的错位；</li>
 *   <li>{@code withGameParams} / {@code withWaitingLocation} 是<b>手工逐字段转发</b>的 17 参构造，
 *       漏传或传错位置（例如把 {@code shopNpc} 传给 {@code spawnYOffset} 的位置）不会编译失败，
 *       只会在运行期表现为「改个波数，商店 NPC 跑到虚空」。</li>
 * </ul>
 *
 * <p>本类只依赖 MockBukkit 的「简单世界」来造 {@link Location}，不加载插件，毫秒级。</p>
 */
class ArenaDefinitionTest extends MockBukkitTestBase {

    private World world;

    @BeforeEach
    void createWorld() {
        world = server.addSimpleWorld("hilltop_arena1");
    }

    private Location loc(double x, double y, double z) {
        return new Location(world, x, y, z, 0f, 0f);
    }

    private ArenaDefinition minimal() {
        return ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0)).build();
    }

    // ================================================================ 校验

    @Nested
    @DisplayName("构造期校验")
    class Validation {

        @Test
        @DisplayName("空白 ID 被拒绝")
        void blankIdIsRejected() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ArenaDefinition.builder("   ", "world", null).build(),
                    "空白 ID 必须在构造期抛 —— registry 的 normalizeId 只是第二道防线");
            assertTrue(failure.getMessage().contains("ID"),
                    "异常消息应点名 ID：" + failure.getMessage());
        }

        @Test
        @DisplayName("null ID 被拒绝")
        void nullIdIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> ArenaDefinition.builder(null, "world", null).build());
        }

        @Test
        @DisplayName("空白世界名被拒绝")
        void blankWorldNameIsRejected() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ArenaDefinition.builder("arena1", "  ", null).build(),
                    "空白世界名必须抛 —— 它的下游是 Bukkit.getWorld(\"\")，只会得到 null 而不会报错");
            assertTrue(failure.getMessage().contains("world name"),
                    "异常消息应点名「world name」：" + failure.getMessage());
        }

        @Test
        @DisplayName("core == null 合法（= 尚未部署）")
        void nullCoreIsLegal() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", null).build();

            assertNull(definition.getCore(),
                    "core == null 表示「尚未部署」：管理员重置会清掉 core，但定义必须留在 registry 里，"
                            + "才能在 GUI 显示为「未部署」并重新部署");
            assertNull(definition.getWaitingLocation(),
                    "core 为 null 且未单独配等待区时，等待区也应是 null（由调用方兜底），"
                            + "不能凭空造一个世界上不存在的坐标");
        }

        @Test
        @DisplayName("spawnPoints 列表里的 null 元素被拒绝，且消息点名 spawnPoint")
        void nullSpawnPointIsRejected() {
            List<Location> spawns = new ArrayList<>();
            spawns.add(loc(1, 100, 1));
            spawns.add(null);

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                            .spawnPoints(spawns).build(),
                    "null 生成点若被放行，getSpawnPoints() 会在传送到一半时抛 NPE");
            assertTrue(failure.getMessage().contains("spawnPoint"),
                    "必须点名是哪个列表的元素：" + failure.getMessage());
        }

        @Test
        @DisplayName("mobSpawnPoints 列表里的 null 元素同样被拒绝")
        void nullMobSpawnPointIsRejected() {
            List<Location> mobs = new ArrayList<>();
            mobs.add(null);

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                            .mobSpawnPoints(mobs).build());
            assertTrue(failure.getMessage().contains("mobSpawnPoint"),
                    "消息必须区分是玩家生成点还是怪物刷怪点：" + failure.getMessage());
        }
    }

    // ================================================================ 防御性拷贝

    @Nested
    @DisplayName("防御性拷贝 —— 类注释承诺的「外部改不动定义」")
    class DefensiveCopies {

        @Test
        @DisplayName("入参 Location 被拷贝：构造后改原对象不影响定义")
        void inputLocationsAreCopied() {
            Location core = loc(10, 100, 10);
            List<Location> spawns = new ArrayList<>(List.of(loc(1, 100, 1)));

            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", core)
                    .spawnPoints(spawns).build();

            core.setX(9999);
            spawns.get(0).setY(9999);

            assertEquals(10, definition.getCore().getX(), 1e-9,
                    "构造期必须 clone()；漏掉的话调用方一个 setX 就改掉了「已加载的定义」");
            assertEquals(100, definition.getSpawnPoints().get(0).getY(), 1e-9);
        }

        @Test
        @DisplayName("getCore() 返回副本：改返回值不影响定义")
        void gettersReturnCopies() {
            ArenaDefinition definition = minimal();

            Location first = definition.getCore();
            first.setX(9999);

            assertNotSame(first, definition.getCore(),
                    "每次 get 都必须返回新 clone —— 否则调用方随手改一下就污染了定义");
            assertEquals(0, definition.getCore().getX(), 1e-9);
        }

        @Test
        @DisplayName("getSpawnPoints / getMobSpawnPoints 返回不可变且逐个 clone 的列表")
        void spawnListsAreImmutableCopies() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .spawnPoints(List.of(loc(1, 100, 1)))
                    .mobSpawnPoints(List.of(loc(2, 64, 2)))
                    .build();

            List<Location> spawns = definition.getSpawnPoints();
            assertEquals(1, spawns.size());
            assertThrows(UnsupportedOperationException.class, spawns::clear,
                    "getSpawnPoints() 必须是不可变视图");

            spawns.get(0).setX(9999);
            assertEquals(1, definition.getSpawnPoints().get(0).getX(), 1e-9,
                    "列表本身不可变还不够 —— 里面的 Location 也必须 clone，否则仍能被改写");

            List<Location> mobs = definition.getMobSpawnPoints();
            assertThrows(UnsupportedOperationException.class, mobs::clear,
                    "getMobSpawnPoints() 同样必须不可变");
        }

        @Test
        @DisplayName("spawnPoints == null 视为空列表（旧存档 / 未部署）")
        void nullSpawnPointsBecomesEmptyList() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .spawnPoints(null).mobSpawnPoints(null).build();

            assertNotNull(definition.getSpawnPoints(),
                    "null 必须归一化为空列表 —— 调用方不应被迫判空");
            assertTrue(definition.getSpawnPoints().isEmpty());
            assertTrue(definition.getMobSpawnPoints().isEmpty(),
                    "旧存档缺 mob-spawn-points 键时返回空列表（兼容语义由 GameManager 决定）");
        }
    }

    // ================================================================ 等待区回退

    @Nested
    @DisplayName("waitingLocation 回退")
    class WaitingFallback {

        @Test
        @DisplayName("未配等待区时回退到 core")
        void fallsBackToCore() {
            ArenaDefinition definition = minimal();

            assertNotNull(definition.getWaitingLocation());
            assertEquals(definition.getCore().getX(), definition.getWaitingLocation().getX(), 1e-9,
                    "玩家开局前应站在核心位置等待（构造器注释：「等待区未配置时跟随 core」）");
            assertEquals(definition.getCore().getY(), definition.getWaitingLocation().getY(), 1e-9);
        }

        @Test
        @DisplayName("显式配了等待区时不被 core 覆盖")
        void explicitWaitingLocationWins() {
            Location waiting = loc(50, 70, 50);
            Location core = loc(0, 100, 0);

            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", waiting, core).build();

            assertEquals(50, definition.getWaitingLocation().getX(), 1e-9,
                    "5 参 builder 的第 3 参是等待区、第 4 参是 core —— 传反了玩家会站在核心上开局");
            assertEquals(0, definition.getCore().getX(), 1e-9);
        }

        @Test
        @DisplayName("4 参 builder 把 core 同时当作等待区")
        void shortBuilderUsesCoreAsWaiting() {
            ArenaDefinition definition = minimal();

            assertEquals(definition.getCore().getX(), definition.getWaitingLocation().getX(), 1e-9,
                    "builder(id, world, core) 的语义是「等待区 = core」");
        }
    }

    // ================================================================ builder 默认值

    @Nested
    @DisplayName("builder 默认值")
    class BuilderDefaults {

        @Test
        @DisplayName("未显式设置时：enabled=true / min=2 / max=6 / waves=20 / interval=30")
        void defaultsMatchDocumentedValues() {
            ArenaDefinition definition = minimal();

            assertTrue(definition.isEnabled(), "新竞技场默认启用");
            assertEquals(2, definition.getMinPlayers(), "默认最少 2 人开局");
            assertEquals(6, definition.getMaxPlayers(), "默认上限 6 人");
            assertEquals(20, definition.getVictoryWaves(), "默认 20 波通关");
            assertEquals(30, definition.getWaveIntervalSeconds(), "默认波次间隔 30 秒");
            assertEquals(0, definition.getNodeSearchRadius());
            assertEquals(0, definition.getSpawnRadiusMin());
            assertEquals(0, definition.getSpawnRadiusMax());
            assertEquals(0, definition.getSpawnYOffset());
        }

        @Test
        @DisplayName("builder 的每个 setter 都真的落到定义上（逐个字段，防传错位置）")
        void everyBuilderFieldLands() {
            Location shopNpc = loc(7, 70, 7);
            List<Location> spawns = List.of(loc(1, 100, 1), loc(2, 100, 2));
            List<Location> mobs = List.of(loc(3, 64, 3), loc(4, 64, 4), loc(5, 64, 5));

            ArenaDefinition definition = ArenaDefinition.builder("arena9", "hilltop_arena1", loc(0, 100, 0))
                    .displayName("Arena Nine")
                    .enabled(false)
                    .spawnPoints(spawns)
                    .mobSpawnPoints(mobs)
                    .shopNpc(shopNpc)
                    .nodeSearchRadius(60)
                    .spawnRadiusMin(2)
                    .spawnRadiusMax(5)
                    .spawnYOffset(3)
                    .minPlayers(3)
                    .maxPlayers(9)
                    .victoryWaves(33)
                    .waveIntervalSeconds(45)
                    .build();

            assertEquals("arena9", definition.getId());
            assertEquals("Arena Nine", definition.getDisplayName());
            assertFalse(definition.isEnabled());
            assertEquals("hilltop_arena1", definition.getWorldName());
            assertEquals(2, definition.getSpawnPoints().size());
            assertEquals(3, definition.getMobSpawnPoints().size());
            assertEquals(7, definition.getShopNpc().getX(), 1e-9);
            assertEquals(60, definition.getNodeSearchRadius());
            assertEquals(2, definition.getSpawnRadiusMin());
            assertEquals(5, definition.getSpawnRadiusMax());
            assertEquals(3, definition.getSpawnYOffset());
            assertEquals(3, definition.getMinPlayers());
            assertEquals(9, definition.getMaxPlayers());
            assertEquals(33, definition.getVictoryWaves());
            assertEquals(45, definition.getWaveIntervalSeconds());
        }
    }

    // ================================================================ 派生方法

    @Nested
    @DisplayName("withGameParams / withWaitingLocation —— 只换目标字段")
    class Derivations {

        @Test
        @DisplayName("withGameParams 只改 4 个数值，其余字段（含 mobSpawnPoints / shopNpc）原样保留")
        void withGameParamsPreservesEverythingElse() {
            ArenaDefinition original = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .displayName("One")
                    .enabled(false)
                    .spawnPoints(List.of(loc(1, 100, 1)))
                    .mobSpawnPoints(List.of(loc(2, 64, 2), loc(3, 64, 3)))
                    .shopNpc(loc(9, 70, 9))
                    .nodeSearchRadius(60)
                    .spawnRadiusMin(2)
                    .spawnRadiusMax(5)
                    .spawnYOffset(3)
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            ArenaDefinition updated = original.withGameParams(4, 8, 30, 45);

            assertEquals(4, updated.getMinPlayers());
            assertEquals(8, updated.getMaxPlayers());
            assertEquals(30, updated.getVictoryWaves());
            assertEquals(45, updated.getWaveIntervalSeconds());

            // 逐字段核对 —— 4 个参数的 withGameParams 走的是 17 参构造器，「只换数值」只能靠
            // 手工转发保证。漏一个字段不会编译失败，只会静默丢数据。
            assertEquals("arena1", updated.getId());
            assertEquals("One", updated.getDisplayName());
            assertFalse(updated.isEnabled(), "改数值不应把启用状态改回 true");
            assertEquals("hilltop_arena1", updated.getWorldName());
            assertEquals(1, updated.getSpawnPoints().size(), "生成点必须保留");
            assertEquals(2, updated.getMobSpawnPoints().size(),
                    "★ 怪物刷怪点是 STORY-1.2 的第二套坐标，漏转发会让 WaveManager 无点可刷");
            assertEquals(9, updated.getShopNpc().getX(), 1e-9,
                    "★ 商店 NPC 位置（17 参构造的最后一个参数）漏转发会让玩家找不到商店");
            assertEquals(60, updated.getNodeSearchRadius(),
                    "★ 这四个刷怪调参没有运行期写入方，丢一次就永久不可恢复（arenas-yml-dc-001/002）");
            assertEquals(2, updated.getSpawnRadiusMin());
            assertEquals(5, updated.getSpawnRadiusMax());
            assertEquals(3, updated.getSpawnYOffset());
            assertEquals(0, updated.getCore().getX(), 1e-9, "核心位置必须保留");
        }

        @Test
        @DisplayName("withGameParams 返回新实例，原定义不变")
        void withGameParamsDoesNotMutateOriginal() {
            ArenaDefinition original = minimal();

            ArenaDefinition updated = original.withGameParams(4, 8, 30, 45);

            assertNotSame(original, updated);
            assertEquals(2, original.getMinPlayers(),
                    "本类没有 setter，with* 必须返回新实例 —— 原地改会让 ArenaInstance 手里的快照与 registry 不一致");
        }

        @Test
        @DisplayName("withWaitingLocation 只换等待区，其余字段原样保留")
        void withWaitingLocationPreservesEverythingElse() {
            ArenaDefinition original = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .spawnPoints(List.of(loc(1, 100, 1)))
                    .mobSpawnPoints(List.of(loc(2, 64, 2)))
                    .shopNpc(loc(9, 70, 9))
                    .minPlayers(3).maxPlayers(9).victoryWaves(33).waveIntervalSeconds(45)
                    .build();

            ArenaDefinition updated = original.withWaitingLocation(loc(50, 70, 50));

            assertEquals(50, updated.getWaitingLocation().getX(), 1e-9);
            assertEquals(3, updated.getMinPlayers(), "4 个游戏参数必须原样保留");
            assertEquals(9, updated.getMaxPlayers());
            assertEquals(33, updated.getVictoryWaves());
            assertEquals(45, updated.getWaveIntervalSeconds());
            assertEquals(1, updated.getMobSpawnPoints().size(),
                    "★ withWaitingLocation 与 withGameParams 是同一个 17 参构造器；"
                            + "漏转发 mobSpawnPoints 会让「只改等待区」顺带清空刷怪点");
            assertEquals(9, updated.getShopNpc().getX(), 1e-9);
        }

        @Test
        @DisplayName("withWaitingLocation(null) 回退到 core（不是把等待区清成 null）")
        void withWaitingLocationNullFallsBackToCore() {
            ArenaDefinition original = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(50, 70, 50), loc(0, 100, 0))
                    .build();

            ArenaDefinition updated = original.withWaitingLocation(null);

            assertNotNull(updated.getWaitingLocation(),
                    "接口约定是「null = 未单独设置」，构造器会回退到 core；"
                            + "若这里返回 null，玩家 join 时会被传到 null 而抛 NPE");
            assertEquals(0, updated.getWaitingLocation().getX(), 1e-9,
                    "回退目标是 core（0），不是原来的等待区（50）");
        }
    }
}
