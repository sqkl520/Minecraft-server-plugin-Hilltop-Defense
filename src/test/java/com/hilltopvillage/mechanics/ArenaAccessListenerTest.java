package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ArenaAccessListener} 的准入守卫测试（P2-41，作者决策 D-4）。
 *
 * <p><b>为什么要这条守卫</b>：竞技场世界此前没有任何「世界级」入口防护 —— 入场的唯一门是
 * {@code /hilltop join}。任何玩家用原版 {@code /tp}、{@code /execute in}、Multiverse 进入后，
 * 交互全部开放：可攻击波次怪（非参赛者击杀 NPE）、捡走掉落货币、用商店 NPC 购物、随意破坏方块。
 * 本监听器把「只有成员在世界里」从不变量升级为世界层强制。</p>
 *
 * <p><b>最容易被写错的一处（也是本类的重点）</b>：成员判定必须<b>同时</b>查两处。
 * {@code MultiArenaManager#joinArena} 的时序是 {@code arena.addPlayer(player)} 内部<b>先</b>
 * 把玩家加入实例的成员集合、<b>再</b>传送到场内，而 {@code playerArenaMap.put} 发生在
 * {@code addPlayer} <b>返回之后</b>。因此只查 {@code getPlayerArena()} 会把<b>正常入场的传送
 * 误拦</b> —— 玩家点 join 后永远进不去。这条路径必须单独覆盖。</p>
 *
 * <p><b>本类不用 MockBukkit 的传送语义</b>：{@link PlayerTeleportEvent} 的
 * {@code getTo()} / {@code getPlayer()} 与 {@code setCancelled} 都是纯数据操作，
 * 直接 new + 读 {@code isCancelled()} 即可，不需要真的传送。</p>
 */
@DisplayName("ArenaAccessListener — 竞技场世界准入守卫")
class ArenaAccessListenerTest extends MockBukkitTestBase {

    private static final String ARENA_WORLD = "hilltop_arena1";
    private static final String LOBBY_WORLD = "lobby";
    private static final String ORDINARY_WORLD = "ordinary";
    private static final String ARENA_ID = "arena1";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenas;
    private ArenaManager definitions;
    private ArenaDefinition definition;
    private ArenaInstance instance;
    private ArenaAccessListener listener;
    private WorldMock arenaWorld;
    private PlayerMock player;
    /** 监听器发出的 i18n key（按顺序）。 */
    private final List<String> sentKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arenaWorld = server.addSimpleWorld(ARENA_WORLD);
        server.addSimpleWorld(LOBBY_WORLD);
        server.addSimpleWorld(ORDINARY_WORLD);

        Logger logger = Logger.getLogger("arena-access-" + System.nanoTime());
        plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), new GameConfig());

        // send 是 void 且 varargs：用默认 Answer 记录，显式 (Object) 转型见
        // LobbyProtectionListenerTest 的同一处注释（否则 valueOf(char[]) 会 ClassCast）。
        LanguageManager lang = mock(LanguageManager.class, invocation -> {
            if ("send".equals(invocation.getMethod().getName())) {
                sentKeys.add(String.valueOf((Object) invocation.getArgument(1)));
            }
            return null;
        });
        when(lang.get(anyString())).thenReturn("x");
        when(plugin.getLanguageManager()).thenReturn(lang);

        definition = mock(ArenaDefinition.class);
        when(definition.getId()).thenReturn(ARENA_ID);

        definitions = mock(ArenaManager.class);
        when(definitions.getArenaByWorld(ARENA_WORLD)).thenReturn(definition);

        instance = mock(ArenaInstance.class);
        when(instance.getArenaId()).thenReturn(ARENA_ID);

        arenas = mock(MultiArenaManager.class);
        when(arenas.isLobbyWorld(LOBBY_WORLD)).thenReturn(true);
        when(arenas.isLobbyWorld(ARENA_WORLD)).thenReturn(false);
        when(arenas.isLobbyWorld(ORDINARY_WORLD)).thenReturn(false);
        when(arenas.getDefinitionManager()).thenReturn(definitions);
        when(arenas.getArena(ARENA_ID)).thenReturn(instance);
        when(plugin.getMultiArenaManager()).thenReturn(arenas);

        listener = new ArenaAccessListener(plugin);
        player = server.addPlayer("Intruder");
        player.teleport(new Location(arenaWorld, 0, 64, 0));
    }

    private PlayerTeleportEvent teleportTo(String worldName) {
        WorldMock target = (WorldMock) server.getWorld(worldName);
        return new PlayerTeleportEvent(player,
                new Location(server.getWorld(LOBBY_WORLD), 0, 64, 0),
                new Location(target, 0, 64, 0));
    }

    // ---------------------------------------------------------------- 1. 拒绝

    @Nested
    @DisplayName("非成员进入竞技场世界必须被拒")
    class Denied {

        @Test
        @DisplayName("非成员传送进竞技场世界 → 取消并提示")
        void nonMemberIsDenied() {
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(null);
            when(instance.isPlayer(any(org.bukkit.entity.Player.class))).thenReturn(false);

            PlayerTeleportEvent event = teleportTo(ARENA_WORLD);
            listener.onTeleport(event);

            assertTrue(event.isCancelled(),
                    "非成员用外部手段（/tp、/execute in、Multiverse）进入竞技场世界必须被拦；"
                            + "放进来就能攻击波次怪（非参赛者击杀 NPE）、捡货币、用商店、破坏方块");
            assertEquals(List.of("arena.access-denied"), sentKeys,
                    "被拒的玩家必须收到提示，否则看起来像服务器卡住了");
        }

        @Test
        @DisplayName("已禁用 / 未部署的场也会被拦（按定义表反查，不要求实例存在）")
        void disabledArenaStillGuards() {
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(null);
            // 实例不存在（场未启用）—— 定义仍在，世界仍应受保护
            when(arenas.getArena(ARENA_ID)).thenReturn(null);

            PlayerTeleportEvent event = teleportTo(ARENA_WORLD);
            listener.onTeleport(event);

            assertTrue(event.isCancelled(),
                    "守卫必须按竞技场**定义**判定（含已禁用/未部署），不能只按活动实例。"
                            + "否则停用一个场就等于把它的世界对外开放");
        }
    }

    // ---------------------------------------------------------------- 2. 放行

    @Nested
    @DisplayName("放行路径（漏放会误拦正常玩法）")
    class Allowed {

        @Test
        @DisplayName("成员：playerArenaMap 已指向本场 → 放行")
        void mappedMemberIsAllowed() {
            ArenaInstance mapped = mock(ArenaInstance.class);
            when(mapped.getArenaId()).thenReturn(ARENA_ID);
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(mapped);

            PlayerTeleportEvent event = teleportTo(ARENA_WORLD);
            listener.onTeleport(event);

            assertFalse(event.isCancelled(), "已入场的成员被传送进场内必须是放行的");
            assertTrue(sentKeys.isEmpty(), "放行时不得发送任何提示");
        }

        /**
         * ★ join 时序窗口：{@code addPlayer} 先把玩家加入成员集合再传送，
         * 而 {@code playerArenaMap.put} 在 {@code addPlayer} 返回之后。
         */
        @Test
        @DisplayName("★ join 时序窗口：成员集合已含该玩家、但 playerArenaMap 还没写 → 放行")
        void joinWindowIsAllowed() {
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(null);
            when(instance.isPlayer(any(org.bukkit.entity.Player.class))).thenReturn(true);

            PlayerTeleportEvent event = teleportTo(ARENA_WORLD);
            listener.onTeleport(event);

            assertFalse(event.isCancelled(),
                    "【关键】join 流程的时序是「先入成员集合 → 再传送 → 最后写 map」。"
                            + "只查 getPlayerArena() 会把正常入场的传送误拦 —— 玩家点 join 后永远进不去。"
                            + "放行条件必须是「map 命中 或 实例成员集合命中」");
            assertTrue(sentKeys.isEmpty(), "放行时不得提示");
        }

        @Test
        @DisplayName("管理员（hilltopvillage.admin）→ 放行（部署向导 / 管理 GUI 需要自由进出）")
        void adminIsAllowed() {
            player.addAttachment(server.getPluginManager().getPlugin("HilltopVillageTestHost") != null
                            ? server.getPluginManager().getPlugin("HilltopVillageTestHost")
                            : createDummyPlugin("HilltopVillageAdminHost"),
                    "hilltopvillage.admin", true);
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(null);
            when(instance.isPlayer(any(org.bukkit.entity.Player.class))).thenReturn(false);

            PlayerTeleportEvent event = teleportTo(ARENA_WORLD);
            listener.onTeleport(event);

            assertFalse(event.isCancelled(),
                    "管理员必须能自由进出竞技场世界 —— 部署向导与管理 GUI 传送核心都依赖它");
        }

        @Test
        @DisplayName("目标是大厅世界 → 放行（配置异常时与竞技场世界重合的兜底）")
        void lobbyWorldIsAllowed() {
            PlayerTeleportEvent event = teleportTo(LOBBY_WORLD);
            listener.onTeleport(event);

            assertFalse(event.isCancelled(), "回大厅永远是放行的");
        }

        @Test
        @DisplayName("目标是普通世界（无竞技场定义）→ 放行")
        void ordinaryWorldIsAllowed() {
            PlayerTeleportEvent event = teleportTo(ORDINARY_WORLD);
            listener.onTeleport(event);

            assertFalse(event.isCancelled(),
                    "本插件只拦「进竞技场世界」，普通世界之间的传送不归它管");
        }

        @Test
        @DisplayName("成员从竞技场世界传送出去 → 放行（结算踢回大厅）")
        void leavingArenaIsAllowed() {
            when(arenas.getPlayerArena(any(org.bukkit.entity.Player.class))).thenReturn(null);
            PlayerTeleportEvent event = new PlayerTeleportEvent(player,
                    new Location(arenaWorld, 0, 64, 0),
                    new Location(server.getWorld(LOBBY_WORLD), 0, 64, 0));

            listener.onTeleport(event);

            assertFalse(event.isCancelled(),
                    "只拦「进」不拦「出」；否则结算踢回大厅时玩家会被卡在竞技场世界里出不来");
        }
    }

    // ---------------------------------------------------------------- 3. 边界

    @Test
    @DisplayName("getTo() 为 null → 安全放行（不得 NPE）")
    void nullDestinationIsSafe() {
        PlayerTeleportEvent event = new PlayerTeleportEvent(player,
                new Location(arenaWorld, 0, 64, 0), null);
        listener.onTeleport(event);
        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("getTo().getWorld() 为 null → 安全放行（不得 NPE）")
    void nullDestinationWorldIsSafe() {
        PlayerTeleportEvent event = new PlayerTeleportEvent(player,
                new Location(arenaWorld, 0, 64, 0), new Location(null, 0, 64, 0));
        listener.onTeleport(event);
        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("目标世界是大厅且也是竞技场世界（配置重合）→ 按大厅优先放行")
    void lobbyTakesPrecedenceOverArenaDefinition() {
        when(arenas.isLobbyWorld(ORDINARY_WORLD)).thenReturn(true);
        when(definitions.getArenaByWorld(ORDINARY_WORLD)).thenReturn(definition);

        PlayerTeleportEvent event = teleportTo(ORDINARY_WORLD);
        listener.onTeleport(event);

        assertFalse(event.isCancelled(),
                "大厅判定必须先于竞技场定义判定 —— 配置把同一世界同时写进两处时，"
                            + "若按竞技场判定拦人，全服玩家都进不了大厅");
    }
}
