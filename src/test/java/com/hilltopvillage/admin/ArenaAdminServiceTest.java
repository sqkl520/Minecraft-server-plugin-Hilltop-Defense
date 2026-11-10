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


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.ArenaAdminService.ArenaView;
import com.hilltopvillage.admin.ArenaAdminService.ParamUpdate;
import com.hilltopvillage.admin.ArenaAdminService.ShopNpcDelete;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaRegistry;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;

/**
 * {@link ArenaAdminService} 的只读视图与写入闸门测试。
 *
 * <p>本类瞄准 round1 的 <b>slice-02-eh-005</b>：无实例时的点位计数「读不到」与「未部署」
 * 在返回值上是同一个 0，GUI 因此会对着一个坏掉的 arenas.yml 报「还没部署」，
 * 管理员于是去重新部署——而不是去修文件。{@code ArenaView.dataUnavailable()} 就是为消歧而加的。
 * 除此之外覆盖三个写入入口（deleteShopNpc / updateGameParams）的拒绝原因分类，
 * 因为它们的返回值是枚举而不是 boolean，错一个分支就会让 GUI 给出错误的指引。</p>
 *
 * <p><b>需要真实文件</b>：{@link YamlFileGate} 的只读判定来自真读盘，用 mock 替不了。
 * 因此本类继承 {@link MockBukkitTestBase}（它提供每用例独立的临时 dataFolder，
 * 且 {@code Bukkit.server} 必须存在 —— {@code buildView} 会调 {@code Bukkit.getWorld}），
 * 再通过 {@link YamlFiles} 的出厂校验器让闸门真的走到「损坏 → 只读」分支。</p>
 */
class ArenaAdminServiceTest extends MockBukkitTestBase {

    private java.io.File pluginDataFolder;

    private HilltopVillagePlugin plugin;
    private MultiArenaManager multi;
    private ArenaManager definitions;
    private ArenaRegistry registry;
    private ArenaAdminService service;
    private final List<ArenaDefinition> registered = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        pluginDataFolder = dataFolder.toFile();
        plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getName()).thenReturn("HilltopVillage");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ArenaAdminServiceTest-" + UUID.randomUUID()));
        when(plugin.getDataFolder()).thenReturn(pluginDataFolder);

        multi = mock(MultiArenaManager.class);
        definitions = mock(ArenaManager.class);
        registry = mock(ArenaRegistry.class);

        when(plugin.getMultiArenaManager()).thenReturn(multi);
        when(multi.getDefinitionManager()).thenReturn(definitions);
        when(definitions.getArenasGate()).thenReturn(null);
        when(multi.getStatus(anyString())).thenReturn(ArenaStatus.DISABLED);
        // ArenaAdminService.registry() == multi.getDefinitionManager().getRegistry()
        when(definitions.getRegistry()).thenReturn(registry);
        when(registry.list()).thenAnswer(invocation -> List.copyOf(registered));
        when(registry.get(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return registered.stream()
                    .filter(definition -> definition.getId().equalsIgnoreCase(id))
                    .findFirst().orElse(null);
        });

        service = new ArenaAdminService(plugin);
    }

    // ------------------------------------------------------------------ 夹具

    private ArenaDefinition definition(String id, String worldName, boolean enabled, Location core) {
        ArenaDefinition definition = new ArenaDefinition(id, id, enabled, worldName,
                null, core, List.of(), 8, 10, 40, 0);
        return definition;
    }

    private void register(ArenaDefinition definition) {
        registered.add(definition);
    }

    /** 让 arenas.yml 在 dataFolder 里真实存在且内容合法。 */
    private void writeArenasYml(String body) throws IOException {
        Files.writeString(pluginDataFolder.toPath().resolve("arenas.yml"), body, StandardCharsets.UTF_8);
    }

    /** 让 arenas.yml 损坏（空文件 → 闸门判 SUSPECT 并隔离），从而进入只读降级。 */
    private void corruptArenasYml() throws IOException {
        Files.writeString(pluginDataFolder.toPath().resolve("arenas.yml"), "   \n", StandardCharsets.UTF_8);
    }

    private void attachGate() {
        YamlFileGate gate = new YamlFileGate(pluginDataFolder.toPath().resolve("arenas.yml").toFile(),
                YamlFiles.arenasValidator(), plugin.getLogger());
        when(definitions.getArenasGate()).thenReturn(gate);
    }

    // ================================================================== 视图

    @Nested
    @DisplayName("只读视图")
    class Views {

        @Test
        @DisplayName("arenaId 不存在时 describe 返回 null，各列表方法返回空列表而非 null")
        void unknownArenaYieldsEmptyNotNull() {
            assertNull(service.describe("ghost"), "不存在的竞技场 describe 必须返回 null（GUI 据此显示 404）");
            assertEquals(List.of(), service.spawnPoints("ghost"), "列表方法一律返回空列表，不返回 null");
            assertEquals(List.of(), service.mobSpawnPoints("ghost"));
            assertEquals(List.of(), service.villagers("ghost"));
            assertEquals(List.of(), service.nodes("ghost"));
            assertNull(service.shopNpc("ghost"));
        }

        @Test
        @DisplayName("describe 的 arenaId 查找忽略大小写（与 ArenaRegistry 同口径）")
        void describeIsCaseInsensitive() {
            register(definition("arena1", "hilltop_arena1", true, null));

            assertNotNull(service.describe("ARENA1"), "registry 的查找忽略大小写");
            assertEquals("arena1", service.describe("ARENA1").arenaId());
        }

        @Test
        @DisplayName("displayName 为空 / 空白时回落到 arenaId（GUI 侧不为 null）")
        void blankDisplayNameFallsBackToId() {
            register(new ArenaDefinition("arena1", "   ", true, "hilltop_arena1",
                    null, null, List.of(), 8, 10, 40, 0));

            ArenaView view = service.describe("arena1");
            assertNotNull(view);
            assertEquals("arena1", view.displayName(),
                    "display-name 未配置时回退 ID —— 否则 GUI 上是一个空标题");
        }

        @Test
        @DisplayName("listArenas 按 registry 顺序，一条不多一条不少")
        void listArenasMirrorsRegistryOrder() {
            register(definition("a", "w_a", true, null));
            register(definition("b", "w_b", false, null));
            register(definition("c", "w_c", true, null));

            assertEquals(List.of("a", "b", "c"),
                    service.listArenas().stream().map(ArenaView::arenaId).toList(),
                    "listArenas 必须按 registry 的 list() 顺序，否则 GUI 分页会错位");
        }

        @Test
        @DisplayName("hasCore / configuredEnabled / hasInstance 分别来自定义与实例，不互相代偿")
        void flagsComeFromTheirOwnSource() {
            register(definition("deployed", "w1", true, new Location(null, 0, 64, 0)));
            register(definition("bare", "w2", false, null));

            ArenaView deployed = service.describe("deployed");
            assertTrue(deployed.hasCore(), "core 非 null → hasCore");
            assertTrue(deployed.configuredEnabled());
            assertFalse(deployed.hasInstance(), "没有活动实例时 hasInstance 恒 false，与 hasCore 无关");

            ArenaView bare = service.describe("bare");
            assertFalse(bare.hasCore());
            assertFalse(bare.configuredEnabled());
        }

        @Test
        @DisplayName("deployed 只看生成点数量，不看 hasCore（重置后两者可同时为 false）")
        void deployedDerivesFromSpawnPointsOnly() throws IOException {
            attachGate();
            writeArenasYml("""
                    arenas:
                      arena1:
                        display-name: Arena One
                        core: {x: 0, y: 64, z: 0}
                    """);
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));

            ArenaView view = service.describe("arena1");
            assertTrue(view.hasCore(), "core 已配置");
            assertFalse(view.deployed(),
                    "deployed 只表示「有固定生成点」；core 在但没生成点时必须为 false，"
                            + "否则 GUI 会显示「已部署」而实际无法开局");
        }
    }

    // ================================================================== 005

    @Nested
    @DisplayName("dataUnavailable —— slice-02-eh-005 的消歧标志")
    class DataUnavailable {

        @Test
        @DisplayName("arenas.yml 损坏被隔离时 dataUnavailable 为 true（不是「未部署」）")
        void corruptYamlSetsDataUnavailable() throws IOException {
            corruptArenasYml();
            attachGate();
            register(definition("arena1", "hilltop_arena1", true, null));

            ArenaView view = service.describe("arena1");

            assertEquals(0, view.spawnCount(), "读不到就是 0 —— 0 本身无法区分两种语义");
            assertTrue(view.dataUnavailable(),
                    "★ 关键：arenas.yml 被闸门判为不可用 / 只读降级时，"
                            + "GUI 必须播报「数据不可用」而不是「未部署」。"
                            + "没了这个标志，管理员看到「0 个生成点」会去重新部署，"
                            + "而真正该做的是修文件 —— 这正是 slice-02-eh-005");
        }

        @Test
        @DisplayName("文件根本不存在（首次运行）时不算「不可用」")
        void absentFileIsNotDataUnavailable() {
            attachGate();     // 文件不存在 → 闸门返回 ABSENT，readOnly 仍为 false
            register(definition("arena1", "hilltop_arena1", true, null));

            ArenaView view = service.describe("arena1");

            assertFalse(view.dataUnavailable(),
                    "文件不存在 = 全新安装，不是故障；这里若报「数据不可用」，"
                            + "每个新服主第一次打开 GUI 都会看到一条假故障");
            assertEquals(0, view.spawnCount());
        }

        @Test
        @DisplayName("activе 实例在场时 dataUnavailable 恒为 false（数据来自内存，永远可信）")
        void liveInstanceIsNeverDataUnavailable() throws IOException {
            corruptArenasYml();
            attachGate();
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));

            // 内层 mock 必须在 when(...) 之外先桩好（round2 勘察报告 B-2 的 UnfinishedStubbing 坑）
            AdminDeployManager deploy = mock(AdminDeployManager.class);
            when(deploy.getSpawnPoints()).thenReturn(List.of());
            when(deploy.getMobSpawnPoints()).thenReturn(List.of());
            when(deploy.getVillagers()).thenReturn(List.of());
            when(deploy.getShopNpcLocation()).thenReturn(null);
            com.hilltopvillage.core.GameManager gameManager = mock(com.hilltopvillage.core.GameManager.class);
            when(gameManager.getAdminDeployManager()).thenReturn(deploy);
            when(gameManager.hasSpawnPoints()).thenReturn(true);

            ArenaInstance live = mock(ArenaInstance.class);
            when(live.getNodeSystem()).thenReturn(null);
            when(live.getGameManager()).thenReturn(gameManager);
            when(multi.getArena("arena1")).thenReturn(live);

            ArenaView view = service.describe("arena1");

            assertFalse(view.dataUnavailable(),
                    "实例在场时计数来自 AdminDeployManager 的内存，"
                            + "即使磁盘坏了也不该标「数据不可用」");
        }

        @Test
        @DisplayName("闸门为 null（拿不到 arenasGate）时按「不可用」处理")
        void missingGateIsTreatedAsUnavailable() {
            when(definitions.getArenasGate()).thenReturn(null);
            register(definition("arena1", "hilltop_arena1", true, null));

            assertTrue(service.describe("arena1").dataUnavailable(),
                    "拿不到共享闸门意味着无法判断文件状态，保守按「不可用」上报 —— "
                            + "谎报「未部署」会让管理员做错事");
        }
    }

    // ================================================================== 写入闸门

    @Nested
    @DisplayName("updateGameParams 的拒绝分类")
    class UpdateGameParams {

        @Test
        @DisplayName("对局进行中（STARTING / RUNNING / WAVE_INTERMISSION）→ GAME_IN_PROGRESS")
        void liveGameRejectsParamUpdate() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));

            for (GameState state : List.of(GameState.STARTING, GameState.RUNNING, GameState.WAVE_INTERMISSION)) {
                stubLive("arena1", state);
                assertEquals(ParamUpdate.GAME_IN_PROGRESS,
                        service.updateGameParams("arena1", 2, 10, 20, 30),
                        state + " 时必须拒绝：min-players 参与「人数不足判负」，"
                                + "中途调高会让下一名玩家退出时直接判负");
            }
        }

        @Test
        @DisplayName("结算倒计时中（VICTORY / DEFEAT / ENDING）→ COUNTDOWN_IN_PROGRESS（区别于 GAME_IN_PROGRESS）")
        void settlementRejectsWithItsOwnReason() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));

            for (GameState state : List.of(GameState.VICTORY, GameState.DEFEAT, GameState.ENDING)) {
                stubLive("arena1", state);
                assertEquals(ParamUpdate.COUNTDOWN_IN_PROGRESS,
                        service.updateGameParams("arena1", 2, 10, 20, 30),
                        state + " 时本局战报尚未播完，改「通关波数」会让记分板与战报口径不一致。"
                                + "必须与「对局进行中」区分，GUI 的措辞不同");
            }
        }

        @Test
        @DisplayName("参数非法 → INVALID_VALUE，且不触碰磁盘（先校验再写）")
        void invalidParamsAreRejectedBeforeAnyWrite() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));

            assertEquals(ParamUpdate.INVALID_VALUE, service.updateGameParams("arena1", 0, 10, 20, 30),
                    "min-players 需 ≥ 1");
            assertEquals(ParamUpdate.INVALID_VALUE, service.updateGameParams("arena1", 10, 5, 20, 30),
                    "max-players 需 ≥ min-players");
            assertEquals(ParamUpdate.INVALID_VALUE, service.updateGameParams("arena1", 2, 10, 0, 30),
                    "victory-waves 需 ≥ 1");
            assertEquals(ParamUpdate.INVALID_VALUE, service.updateGameParams("arena1", 2, 10, 20, 0),
                    "wave-interval 需 ≥ 1");

            verify(definitions, never()).updateGameParams(anyString(), any(Integer.class), any(Integer.class),
                    any(Integer.class), any(Integer.class));
        }

        @Test
        @DisplayName("写盘失败 → WRITE_FAILED（不与 INVALID_VALUE 混为一谈）")
        void writeFailureHasItsOwnReason() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));
            when(definitions.updateGameParams("arena1", 2, 10, 20, 30)).thenReturn(false);

            assertEquals(ParamUpdate.WRITE_FAILED, service.updateGameParams("arena1", 2, 10, 20, 30),
                    "ArenaManager 对「值非法」与「写盘失败」都返回 false；"
                            + "本层必须先自己校验一遍才能把两者分开，否则 GUI 会把 IO 错误说成参数错误");
        }

        @Test
        @DisplayName("竞技场不存在 → ARENA_NOT_FOUND")
        void unknownArenaRejects() {
            assertEquals(ParamUpdate.ARENA_NOT_FOUND, service.updateGameParams("ghost", 2, 10, 20, 30));
        }

        @Test
        @DisplayName("写盘成功后必须让各竞技场重读 arenas.yml（否则旧副本会写回旧值）")
        void successfulWriteRefreshesDeploymentData() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));
            when(definitions.updateGameParams("arena1", 3, 12, 25, 40)).thenReturn(true);

            assertEquals(ParamUpdate.OK, service.updateGameParams("arena1", 3, 12, 25, 40));

            verify(multi).reloadAllDeploymentData();
        }

        @Test
        @DisplayName("有活动实例时把新参数推送给它（否则「重启后才生效」而 GUI 显示已改）")
        void successfulWritePushesParamsIntoLiveInstance() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));
            when(definitions.updateGameParams("arena1", 3, 12, 25, 40)).thenReturn(true);
            stubLive("arena1", GameState.WAITING);

            ArenaInstance live = multi.getArena("arena1");
            assertEquals(ParamUpdate.OK, service.updateGameParams("arena1", 3, 12, 25, 40));

            verify(live).applyGameParams(3, 12, 25, 40);
        }
    }

    @Nested
    @DisplayName("deleteShopNpc 的拒绝分类")
    class DeleteShopNpc {

        @Test
        @DisplayName("对局进行中拒绝（isGameInProgress 口径）")
        void liveGameRejectsShopDelete() {
            register(definition("arena1", "hilltop_arena1", true, new Location(null, 0, 64, 0)));
            stubLive("arena1", GameState.RUNNING);

            assertEquals(ShopNpcDelete.GAME_IN_PROGRESS, service.deleteShopNpc("arena1"));
        }

        @Test
        @DisplayName("arenas.yml 损坏时如实报 WRITE_FAILED，而不是谎报 NOT_SET")
        void corruptYamlReportsWriteFailure() throws IOException {
            corruptArenasYml();
            attachGate();
            register(definition("arena1", "hilltop_arena1", true, null));

            assertEquals(ShopNpcDelete.WRITE_FAILED, service.deleteShopNpc("arena1"),
                    "文件损坏被隔离成 *.corrupt 时真实语义是「不可用」；"
                            + "报 NOT_SET 会让管理员以为「本来就没设置」，从而不去修文件");
        }

        @Test
        @DisplayName("文件不存在且闸门未锁定 → NOT_SET（确实没什么可删）")
        void absentFileIsNotSet() {
            attachGate();
            register(definition("arena1", "hilltop_arena1", true, null));

            assertEquals(ShopNpcDelete.NOT_SET, service.deleteShopNpc("arena1"));
        }

        @Test
        @DisplayName("竞技场不存在 → ARENA_NOT_FOUND")
        void unknownArenaRejectsShopDelete() {
            assertEquals(ShopNpcDelete.ARENA_NOT_FOUND, service.deleteShopNpc("ghost"));
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 给某个竞技场挂一个「有实例」的桩。
     *
     * <p>同样必须在 {@code when(...)} 之外先完成内层 mock 的桩，
     * 否则抛 {@code UnfinishedStubbingException}（round2 勘察报告 B-2 的同一个坑）。</p>
     */
    private void stubLive(String arenaId, GameState state) {
        ArenaInstance instance = mock(ArenaInstance.class);
        when(instance.getState()).thenReturn(state);
        when(instance.getNodeSystem()).thenReturn(null);
        when(multi.getArena(arenaId)).thenReturn(instance);
    }

    /** {@code ArenaAdminService.deployManager} 要一个带 {@code getAdminDeployManager()} 的 GameManager。 */
    private com.hilltopvillage.core.GameManager deployAsGameManager(AdminDeployManager deploy) {
        com.hilltopvillage.core.GameManager gameManager = mock(com.hilltopvillage.core.GameManager.class);
        when(gameManager.getAdminDeployManager()).thenReturn(deploy);
        return gameManager;
    }
}
