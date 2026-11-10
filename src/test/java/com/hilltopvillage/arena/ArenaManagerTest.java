package com.hilltopvillage.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.testsupport.MockBukkitTestBase.TestPaths;

/**
 * {@link ArenaManager} 的准入校验与部署数据清理。
 *
 * <p>本类刻意<b>不</b>触碰玩法层（{@code MultiArenaManager} / {@code ArenaInstance}）——
 * 那需要全量启动（0.5–5 s/次）。这里只覆盖两条「配错了会让竞技场凭空消失」的路径：</p>
 *
 * <ol>
 *   <li><b>{@code validationFailure()} 的可解释性</b>（fix-round1 pattern-2）：旧实现只打一行
 *       "Skipping invalid arena definition: &lt;id&gt;"，管理员看不出是「坐标 NaN」「世界名不符」
 *       还是「max-players &lt; min-players」。而 {@code ArenaResetService.canReset} 只会报
 *       {@code ARENA_NOT_FOUND} —— 连「重置自救」都做不到。修复把判定收敛到本方法并返回原因。</li>
 *   <li><b>{@code clearDeploymentData()} 只删部署字段</b>（{@code arenas-yml-dc-001/002}）：
 *       四个刷怪调参（{@code spawn-radius-min/max}、{@code node-search-radius}、{@code spawn-y-offset}）
 *       没有任何运行期写入方，重置删掉后重新部署<b>不会写回</b>，竞技场会永久静默回落到全局值
 *       （刷怪半径 20/40、节点搜索 128），全程无日志。本类用真实磁盘往返钉住这四个键仍在。</li>
 *   <li><b>{@code validGameParams()} 与 {@code validationFailure()} 同口径</b>：
 *       两者若不一致，GUI 允许写入的值会在下次 {@code load()} 时被判无效、
 *       竞技场直接从 registry 与 GUI 列表里消失。</li>
 * </ol>
 *
 * <p><b>夹具</b>：{@code MockBukkit.createMockPlugin} 的哑插件 + 简单世界。构造器只碰
 * {@code getDataFolder()} / {@code getLogger()} / {@code saveResource()}，实测不触发
 * {@code Bukkit.getWorldContainer()}（那是 {@code load()} 里 {@code ensureWorldsLoaded()} 的事）。</p>
 */
class ArenaManagerTest extends MockBukkitTestBase {

    private PluginMock plugin;
    private ArenaManager manager;
    private World arenaWorld;

    @BeforeEach
    void setUp() {
        plugin = createDummyPlugin("HilltopVillage");
        manager = new ArenaManager(plugin);
        arenaWorld = server.addSimpleWorld("hilltop_arena1");
    }

    private File arenasFile() {
        return new File(plugin.getDataFolder(), "arenas.yml");
    }

    private Location loc(double x, double y, double z) {
        return new Location(arenaWorld, x, y, z, 0f, 0f);
    }

    /** 一个「除被测字段之外全部合法」的定义，便于单点变异某一条判据。 */
    private ArenaDefinition valid() {
        return ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                .spawnPoints(List.of(loc(1, 100, 1)))
                .mobSpawnPoints(List.of(loc(2, 64, 2)))
                .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                .build();
    }

    // ================================================================ 校验

    @Nested
    @DisplayName("validationFailure —— 拒绝原因必须点名真实问题")
    class Validation {

        @Test
        @DisplayName("合法定义通过 validate() 且 validationFailure() 返回 null")
        void validDefinitionPasses() {
            assertTrue(manager.validate(valid()), "基线：valid() 必须通过，否则下面每条断言都没有意义");
            assertNull(manager.validationFailure(valid()));
        }

        @Test
        @DisplayName("null 定义：原因明确为 null")
        void nullDefinition() {
            assertFalse(manager.validate(null));
            assertEquals("definition is null", manager.validationFailure(null));
        }

        @Test
        @DisplayName("非法 ID 格式：原因点名正则与允许字符集")
        void badlyFormattedId() {
            // 「Bad Id」含空格与大写 —— 走 registry 时会被 normalizeId 小写化，但空格永远不合法。
            // ArenaDefinition 的构造器只拒绝空白，所以「Bad Id」能构造出来，正好在校验层被拦。
            ArenaDefinition definition = ArenaDefinition.builder("Bad Id", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);

            assertNotNull(reason, "含空格与大写的 ID 必须被判无效");
            assertTrue(reason.contains("does not match"),
                    "原因应说明「与正则不匹配」，而不是笼统的「无效」：" + reason);
            assertTrue(reason.contains("[a-z0-9][a-z0-9_-]*"),
                    "原因应给出正则本体，服主才知道合法字符集：" + reason);
            assertFalse(manager.validate(definition));
        }

        @Test
        @DisplayName("大写 ID 也被拒绝（正则只接受小写）")
        void uppercaseIdIsRejected() {
            ArenaDefinition definition = ArenaDefinition.builder("Arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);
            assertNotNull(reason, "ARENA_ID 是 [a-z0-9][a-z0-9_-]*，大写 A 不匹配");
            assertTrue(reason.contains("does not match"), reason);
        }

        @Test
        @DisplayName("core 坐标 NaN：原因点名 non-finite 且带上实际坐标")
        void nonFiniteCoreCoordinate() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1",
                            new Location(arenaWorld, Double.NaN, 100, 0.5))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);

            assertNotNull(reason);
            assertTrue(reason.contains("non-finite coordinate"),
                    "NaN 坐标必须被拦 —— 它会让 Bukkit 在传送时静默失败或落到 0,0：" + reason);
            assertTrue(reason.contains("NaN"),
                    "原因应带上实际值（NaN），服主才知道是哪一段坏了：" + reason);
        }

        @Test
        @DisplayName("生成点坐标 Infinite：原因点名哪个列（spawn-points）")
        void nonFiniteSpawnPoint() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .spawnPoints(List.of(new Location(arenaWorld, 1, Double.POSITIVE_INFINITY, 1)))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);
            assertNotNull(reason);
            assertTrue(reason.contains("spawn-points"),
                    "必须点名是玩家生成点，而不是怪物刷怪点：" + reason);
        }

        @Test
        @DisplayName("怪物刷怪点坐标非法：原因点名 mob-spawn-points")
        void nonFiniteMobSpawnPoint() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .mobSpawnPoints(List.of(new Location(arenaWorld, Double.NaN, 64, 1)))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);
            assertNotNull(reason);
            assertTrue(reason.contains("mob-spawn-points"),
                    "STORY-1.2 的第二套坐标也要校验并点名：" + reason);
        }

        @Test
        @DisplayName("slice-05-logic-001：世界名大小写不同<b>不算</b>错配（equalsIgnoreCase）")
        void worldNameCaseDifferenceIsAccepted() {
            // 关键：Location 所在世界的名字必须与定义里声明的世界名<b>大小写不同</b>，
            // 否则 equals 与 equalsIgnoreCase 等价，这个测试就测不出任何东西
            // （第一版正是这么写的，M14 变异下未捕获 —— 已修正）。
            World other = server.addSimpleWorld("Hilltop_Arena1");
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "HILLTOP_ARENA1",
                            new Location(other, 0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            assertTrue(manager.validate(definition),
                    "slice-05-logic-001：旧实现用 equals，世界名大小写与 arenas.yml 不一致时会误判无效。"
                            + "Bukkit 的世界查找本身不区分大小写，能正常加载与游玩 —— 校验必须忽略大小写。"
                            + "Location 世界实际名：" + (other == null ? "null" : other.getName())
                            + "，声明名：HILLTOP_ARENA1，原因：" + manager.validationFailure(definition));
        }

        @Test
        @DisplayName("对照：世界名真的不符时被拒绝（证明上一条不是恒真）")
        void worldNameCaseTestHasARealCounterpart() {
            World other = server.addSimpleWorld("Hilltop_Arena1");
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena2",
                            new Location(other, 0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            assertFalse(manager.validate(definition),
                    "worldName 既不是大小写变体、也不是同名 —— 必须被判无效，"
                            + "否则上一条断言在「世界名永不校验」的实现下也会绿");
        }

        @Test
        @DisplayName("世界名真的不符时被拒绝，且原因点名两个世界")
        void worldNameMismatchIsRejected() {
            World lobby = server.addSimpleWorld("lobby");
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1",
                            new Location(lobby, 0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);

            assertNotNull(reason);
            assertTrue(reason.contains("is in world 'lobby'"), "原因要点名实际世界：" + reason);
            assertTrue(reason.contains("declares world 'hilltop_arena1'"),
                    "原因要点名声明的世界，服主才知道该改哪一边：" + reason);
        }

        @Test
        @DisplayName("core == null（未部署）合法 —— 校验不得因缺 core 而拒绝")
        void nullCoreIsValid() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", null)
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            assertTrue(manager.validate(definition),
                    "core == null 表示「尚未部署」：重置竞技场会清掉 core，此时定义必须继续留在 registry 里，"
                            + "才能在 GUI 显示为「未部署」并重新部署。硬性要求 core 非空会让整座竞技场从 registry 消失。"
                            + "实际原因：" + manager.validationFailure(definition));
        }

        @Test
        @DisplayName("max-players < min-players：原因同时点名两个实际数值与判据")
        void maxBelowMin() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(6).maxPlayers(2).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            String reason = manager.validationFailure(definition);

            assertNotNull(reason);
            assertTrue(reason.contains("min-players 6"), "要点名实际值 6：" + reason);
            assertTrue(reason.contains("max-players 2"), "要点名实际值 2：" + reason);
            assertTrue(reason.contains("max >= min"), "要给出判据，而不是只说「无效」：" + reason);
        }

        @Test
        @DisplayName("min-players == 0 被拒绝")
        void zeroMinPlayers() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(0).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            assertNotNull(manager.validationFailure(definition));
        }

        @Test
        @DisplayName("victory-waves == 0 被拒绝")
        void zeroVictoryWaves() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(0).waveIntervalSeconds(30)
                    .build();

            assertNotNull(manager.validationFailure(definition));
        }

        @Test
        @DisplayName("wave-interval-seconds == 0 被拒绝")
        void zeroWaveInterval() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(2).maxPlayers(6).victoryWaves(20).waveIntervalSeconds(0)
                    .build();

            assertNotNull(manager.validationFailure(definition));
        }

        @Test
        @DisplayName("min == max 合法（单人生存的竞技场是合法配置）")
        void minEqualsMaxIsValid() {
            ArenaDefinition definition = ArenaDefinition.builder("arena1", "hilltop_arena1", loc(0, 100, 0))
                    .minPlayers(1).maxPlayers(1).victoryWaves(20).waveIntervalSeconds(30)
                    .build();

            assertTrue(manager.validate(definition),
                    "判据是 max >= min，等号合法：" + manager.validationFailure(definition));
        }
    }

    // ================================================================ validGameParams 同口径

    @Nested
    @DisplayName("validGameParams —— 与 validationFailure 同一条判据")
    class GameParams {

        @Test
        @DisplayName("合法组合全部返回 true")
        void legalCombinations() {
            assertTrue(ArenaManager.validGameParams(1, 1, 1, 1));
            assertTrue(ArenaManager.validGameParams(2, 6, 20, 30));
            assertTrue(ArenaManager.validGameParams(3, 3, 99, 5));
        }

        @Test
        @DisplayName("四条判据各有一个反例")
        void eachClauseRejects() {
            assertFalse(ArenaManager.validGameParams(0, 6, 20, 30), "min-players 必须 > 0");
            assertFalse(ArenaManager.validGameParams(6, 2, 20, 30), "max-players 必须 >= min-players");
            assertFalse(ArenaManager.validGameParams(2, 6, 0, 30), "victory-waves 必须 > 0");
            assertFalse(ArenaManager.validGameParams(2, 6, 20, 0), "wave-interval-seconds 必须 > 0");
        }

        @Test
        @DisplayName("与 validationFailure 同口径：GUI 允许写入的值一定过得了加载校验")
        void agreesWithValidationForEveryCombination() {
            // 两处若不同口径，GUI 能存进去的配置会在下次 load() 时被判无效、
            // 竞技场从 registry 与 GUI 列表里静默消失。这里穷举小范围交叉核对。
            for (int min = 0; min <= 4; min++) {
                for (int max = 0; max <= 6; max++) {
                    for (int waves : List.of(0, 1, 20)) {
                        for (int interval : List.of(0, 1, 30)) {
                            boolean direct = ArenaManager.validGameParams(min, max, waves, interval);
                            ArenaDefinition definition = ArenaDefinition.builder(
                                            "arena1", "hilltop_arena1", loc(0, 100, 0))
                                    .minPlayers(min).maxPlayers(max)
                                    .victoryWaves(waves).waveIntervalSeconds(interval)
                                    .build();
                            boolean viaValidation = manager.validate(definition);

                            assertEquals(direct, viaValidation,
                                    "validGameParams 与 validationFailure 对 (min=" + min + ", max=" + max
                                            + ", waves=" + waves + ", interval=" + interval
                                            + ") 判定不一致 —— GUI 允许写入的值会过不了加载校验，竞技场直接消失");
                        }
                    }
                }
            }
        }
    }

    // ================================================================ clearDeploymentData

    @Nested
    @DisplayName("clearDeploymentData —— 只删部署字段，四个刷怪调参必须保留")
    class ClearDeployment {

        /** 写一份带全部部署字段 + 四个调参的 arenas.yml，然后加载。 */
        private void writeArenaYml(String extraArenaKeys) throws IOException {
            String yaml = """
                    schema-version: 2
                    arenas:
                      arena1:
                        display-name: "A1"
                        enabled: true
                        world: hilltop_arena1
                        node-search-radius: 50
                        spawn-radius-min: 1
                        spawn-radius-max: 3
                        spawn-y-offset: 7
                        core:
                          x: 0.5
                          y: 100.0
                          z: 0.5
                        waiting-location:
                          x: 0.5
                          y: 100.0
                          z: 0.5
                        spawn-points:
                          - x: 1.0
                            y: 100.0
                            z: 1.0
                        mob-spawn-points:
                          - x: 2.0
                            y: 64.0
                            z: 2.0
                        villagers:
                          - x: 3.0
                            y: 64.0
                            z: 3.0
                        shop-npc:
                          x: 4.0
                          y: 70.0
                          z: 4.0
                        game:
                          min-players: 2
                          max-players: 6
                          victory-waves: 20
                          wave-interval-seconds: 30
                    """ + extraArenaKeys;
            TestPaths.write(arenasFile(), yaml);
            manager.load();
        }

        @Test
        @DisplayName("部署字段被删（core / spawn-points / mob-spawn-points / villagers / shop-npc / game.*）")
        void deploymentFieldsAreRemoved() throws IOException {
            writeArenaYml("");
            assertNotNull(manager.getArena("arena1"), "前置条件：arena1 应已加载");

            assertTrue(manager.clearDeploymentData("arena1"), "清理应成功");

            YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile());
            String base = "arenas.arena1.";
            assertFalse(disk.isSet(base + "core"), "core 属部署产物，重置后由向导重建");
            assertFalse(disk.isSet(base + "spawn-points"), "生成点属部署产物");
            assertFalse(disk.isSet(base + "mob-spawn-points"), "怪物刷怪点属部署产物");
            assertFalse(disk.isSet(base + "villagers"), "村民属部署产物");
            assertFalse(disk.isSet(base + "shop-npc"), "商店 NPC 属部署产物");
            assertFalse(disk.isSet(base + "game"),
                    "game 的 4 键都删掉后会剩一个空节点，必须整段删掉 —— "
                            + "留着会让 readDefinition 走「有 game 段」分支、用全局 min-players 兜底");
        }

        @Test
        @DisplayName("arenas-yml-dc-001/002：四个刷怪调参在重置后仍在磁盘上")
        void tuningKeysSurviveReset() throws IOException {
            writeArenaYml("");

            assertTrue(manager.clearDeploymentData("arena1"));

            YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile());
            String base = "arenas.arena1.";
            assertEquals(1, disk.getInt(base + "spawn-radius-min"),
                    "arenas-yml-dc-002：spawn-radius-min 没有运行期写入方，删掉后重新部署不会写回，"
                            + "竞技场会永久静默回落到全局值 20，按小半径调优的图上有效刷怪点会大幅塌缩");
            assertEquals(3, disk.getInt(base + "spawn-radius-max"),
                    "spawn-radius-max 同理（出厂 3 vs 全局 40）");
            assertEquals(50, disk.getInt(base + "node-search-radius"),
                    "arenas-yml-dc-001：node-search-radius 删掉后会由 50 膨胀到全局 128");
            assertEquals(7, disk.getInt(base + "spawn-y-offset"),
                    "spawn-y-offset 出厂值恰好等于全局值（都是 0），但一旦管理员调成非 0，"
                            + "重置同样会让该项调参不可恢复");
        }

        @Test
        @DisplayName("非部署字段（world / enabled / display-name）保留")
        void identityFieldsSurviveReset() throws IOException {
            writeArenaYml("");

            assertTrue(manager.clearDeploymentData("arena1"));

            YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile());
            String base = "arenas.arena1.";
            assertEquals("hilltop_arena1", disk.getString(base + "world"), "世界名不是「部署」的一部分");
            assertTrue(disk.getBoolean(base + "enabled"), "启用状态不是「部署」的一部分");
            assertEquals("A1", disk.getString(base + "display-name"), "显示名不是「部署」的一部分");
        }

        @Test
        @DisplayName("shop-npc-migrated 标记被重新写上（防止重置后旧 NPC 被自动迁移复活）")
        void shopNpcMigratedMarkerIsRewritten() throws IOException {
            writeArenaYml("");

            assertTrue(manager.clearDeploymentData("arena1"));

            YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile());
            assertTrue(disk.getBoolean("arenas.arena1." + ArenaManager.SHOP_NPC_MIGRATED_KEY),
                    "没有这个标记时，重置删掉 shop-npc 后，下次加载会从旧版全局 spawnpoints.yml "
                            + "再迁移一次，把刚清掉的 NPC 原地复活");
        }

        @Test
        @DisplayName("清理后 registry 被重建：arena1 仍在，但 core 变 null")
        void registryIsRebuiltAfterClear() throws IOException {
            writeArenaYml("");
            assertNotNull(manager.getArena("arena1").getCore(), "前置条件：清理前 core 非空");

            assertTrue(manager.clearDeploymentData("arena1"));

            ArenaDefinition after = manager.getArena("arena1");
            assertNotNull(after,
                    "清理后竞技场必须仍在 registry 里 —— 否则管理员无法从 GUI 看到它并重新部署");
            assertNull(after.getCore(), "内存中的旧定义还带着刚被删掉的 core，必须重建");
            assertEquals(1, after.getSpawnRadiusMin(),
                    "四个调参从磁盘重读后仍是 1，证明它们既没被删、也没被内存旧值覆盖");
        }

        @Test
        @DisplayName("arenaId 大小写不敏感（findArenaKey 用 equalsIgnoreCase）")
        void arenaIdLookupIsCaseInsensitive() throws IOException {
            writeArenaYml("");

            assertTrue(manager.clearDeploymentData("ARENA1"),
                    "registry 的 ID 归一化是不区分大小写的；这里若用 equals 会让「重置」静默失败");
        }

        @Test
        @DisplayName("未知竞技场返回 false 且不改动文件")
        void unknownArenaReturnsFalse() throws IOException {
            writeArenaYml("");
            String before = TestPaths.read(arenasFile());

            assertFalse(manager.clearDeploymentData("ghost"));

            assertEquals(before, TestPaths.read(arenasFile()),
                    "未命中的清理绝不应写盘 —— 否则会把整份文件重写一遍");
        }

        @Test
        @DisplayName("null / 空白 arenaId 直接返回 false（不读盘）")
        void nullOrBlankArenaId() {
            assertFalse(manager.clearDeploymentData(null));
            assertFalse(manager.clearDeploymentData("   "));
        }
    }

    // ================================================================ 派生：getDefaultArena / getArenaByWorld

    @Nested
    @DisplayName("查表")
    class Lookups {

        @Test
        @DisplayName("getArena(id) 大小写不敏感；未知 id 返回 null")
        void getArenaNormalizesId() {
            ArenaDefinition definition = valid();
            manager.getRegistry().register(definition);

            assertNotNull(manager.getArena("ARENA1"));
            assertNull(manager.getArena("ghost"));
            assertNull(manager.getArena(null), "null 必须返回 null（命令层直接用玩家输入）");
        }

        @Test
        @DisplayName("getArenaByWorld 大小写不敏感（P2-29 的 /hilltop setup 起手解析）")
        void getArenaByWorldIgnoresCase() {
            manager.getRegistry().register(valid());

            assertNotNull(manager.getArenaByWorld("Hilltop_Arena1"));
            assertNotNull(manager.getArenaByWorld("HILLTOP_ARENA1"));
            assertNull(manager.getArenaByWorld("lobby"));
        }

        @Test
        @DisplayName("getDefaultArena 在未设默认时返回 null")
        void defaultArenaIsNullWhenUnset() {
            manager.getRegistry().register(valid());

            assertNull(manager.getDefaultArena(),
                    "未设 default-arena 时 getDefaultArena() 应是 null —— 调用方据此提示「请先设置默认竞技场」");
        }

        @Test
        @DisplayName("getDefaultArena 跟随 registry 的默认标记")
        void defaultArenaFollowsRegistry() {
            manager.getRegistry().register(valid());
            manager.getRegistry().setDefaultArenaId("arena1");

            assertNotNull(manager.getDefaultArena());
            assertEquals("arena1", manager.getDefaultArena().getId());
        }

        /**
         * <p>slice-05-logic-004 的<b>回归测试</b>（round2 已修复，本测试守护修复）。</p>
         *
         * <p><b>修复前的缺陷</b>（round1 审计 {@code round1-02-audit-slice-05-logic.md}
         * §"slice-05-logic-004"）：{@code ArenaManager.java:195} 直接
         * {@code registry.get(defaultId)} 且没有空白判断。{@code default-arena: "   "} 时
         * {@code getString} 返回三个空格（非 null），于是 {@code registry.get("   ")} →
         * {@code ArenaRegistry.normalizeId()} 抛 {@code IllegalArgumentException:
         * Arena ID must not be blank}。该语句<b>不在</b> {@code load()} 的 per-arena
         * {@code try/catch} 内（那个 try 只包住 {@code readDefinition} + {@code register}），
         * {@code load()} 也没有外层捕获 —— 异常直接冒出 {@code load()}，整次竞技场加载中断。</p>
         *
         * <p><b>修复</b>：{@code load()} 读到 {@code default-arena} 后先把空白归一为 null，
         * 于是空白值等价于「该项未配置」。这与写路径一致 ——
         * {@code ArenaRegistry.setDefaultArenaId} 对 {@code null} 或 {@code isBlank()}
         * 同样是「清空默认值」而不是报错（见 {@code ArenaRegistry.java:48-52}）。</p>
         *
         * <p><b>本测试原本是 characterization 形式</b>（断言「会抛」），修复后已改写为断言
         * 正确行为：不抛、arena1 正常加载、默认竞技场保持未设置。</p>
         */
        @Test
        @DisplayName("slice-05-logic-004：空白 default-arena 视为未配置，load() 不抛且默认竞技场为空")
        void blankDefaultArenaIsTreatedAsUnconfigured() throws IOException {
            TestPaths.write(arenasFile(), """
                    schema-version: 2
                    default-arena: "   "
                    arenas:
                      arena1:
                        display-name: "A1"
                        enabled: true
                        world: hilltop_arena1
                        node-search-radius: 50
                        spawn-radius-min: 1
                        spawn-radius-max: 3
                        spawn-y-offset: 7
                        game:
                          min-players: 2
                          max-players: 6
                          victory-waves: 20
                          wave-interval-seconds: 30
                    """);

            assertDoesNotThrow(() -> manager.load(),
                    "slice-05-logic-004：空白 default-arena 只是「没配」，不能把整次加载打挂 —— "
                            + "修复前这里会冒出 registry.normalizeId 的 "
                            + "IllegalArgumentException: Arena ID must not be blank");

            assertNotNull(manager.getArena("arena1"),
                    "异常若发生在 load() 中途，arena1 之前的注册会保留、但后续加载全部中断；"
                            + "修复后 arena1 必须完整加载");
            assertNull(manager.getDefaultArena(),
                    "空白 default-arena 必须被当作「未设置」，与 setDefaultArenaId 的空白语义一致；"
                            + "若这里非空，说明空白值被当成了一个真实竞技场 ID");
        }

        @Test
        @DisplayName("对照：非空但未注册的 default-arena 只告警、不中断 load()")
        void unregisteredDefaultArenaOnlyWarns() throws IOException {
            TestPaths.write(arenasFile(), """
                    schema-version: 2
                    default-arena: ghost
                    arenas:
                      arena1:
                        display-name: "A1"
                        enabled: true
                        world: hilltop_arena1
                        node-search-radius: 50
                        game:
                          min-players: 2
                          max-players: 6
                          victory-waves: 20
                          wave-interval-seconds: 30
                    """);
            var logs = attachLogCapture(plugin.getLogger());

            manager.load();

            assertNotNull(manager.getArena("arena1"), "非空但未注册的默认值不应中断 load()");
            assertNull(manager.getDefaultArena());
            assertTrue(logs.anyContains("default-arena does not reference a loaded arena"),
                    "必须告警，否则管理员不知道默认竞技场没生效：" + logs.lines());
        }
    }
}
