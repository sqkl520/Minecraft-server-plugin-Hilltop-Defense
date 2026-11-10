package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * R-12 —— {@code slice-06-logic-002} 的<b>多人形态</b>回归网喵。
 *
 * <p><b>为什么单独立类而不是给 {@link HilltopCommandHandlerArenaGuardTest} 追加 {@code @Nested}</b>：
 * 该类的夹具只启用了一个 {@code arena1}（出厂 {@code arenas.yml} 的 {@code min-players: 1}），
 * 「一人离场」时判负判据是 {@code players.size()=0 < 1}；单人场里「离场」与「人数跌破下限」
 * 是同一件事，<b>根本构造不出「一人被拉走、队友仍在场」的形态</b>喵。多人形态需要：
 * ① 一个 {@code min-players: 2} 的场；② 该场也要部署出生点/刷怪点（否则 {@code startGame()}
 * 静默 return 停在 WAITING）；③ 两名玩家分别映射到该场；④ 只有其中一人被送出竞技场世界喵。
 * 四条夹具与单人类互不相容，塞进同一个类会让 {@code @BeforeEach} 变成两套夹具的分支汤，
 * 故独立成类喵。</p>
 *
 * <p><b>多出来的那一半伤害</b>：单人形态下把玩家拉走只是「他自己那局被打成失败」；
 * 多人形态下 {@code GameManager.removeOfflinePlayer} 的
 * {@code players.size() < getArenaMinPlayers() && isGameRunning() → endGame(false)}
 * 会把<b>仍站在场上正常作战的队友</b>一并判负 —— 这才是 {@code slice-06-logic-002} 真正的事故形态喵。
 * 本类的核心用例因此显式构造「两人在场、一人被拉走、队友仍 RUNNING」喵。</p>
 *
 * <p><b>三条夹具妥协照抄 {@link HilltopCommandHandlerArenaGuardTest}</b>（都是 MockBukkit 的能力缺口）：
 * {@code PlayerGoodsManager} 反射置 null、{@code scoreboard.enabled = false}、
 * {@code globalMobCap = 0}喵。</p>
 *
 * <p><b>世界切换只用 {@code setLocation}</b>：{@code PlayerMock#teleport} 在 MockBukkit 4.45.0
 * 的行为不可依赖（round3 实测返回 false 且不改世界），而本类必须先确定「谁在场世界内、谁不在」喵。</p>
 */
class ArenaGuardMultiPlayerTest extends MockBukkitTestBase {

    /** 由 {@code ArenaDefinition.builder(...)} 现场注册的第二竞技场：默认 {@code minPlayers = 2}。 */
    private static final String SECOND_ARENA_ID = "arenaB";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenaManager;
    private ArenaInstance arena1;
    private GameManager arena1Game;
    private ArenaInstance arenaB;
    private GameManager arenaBGame;
    private World arenaBWorld;
    private World lobbyWorld;

    /** 留在场上正常作战的队友喵。 */
    private PlayerMock survivor;
    /** 被管理员 /tp 或第三方插件送出竞技场世界、随后执行跨场 join 的那名成员喵。 */
    private PlayerMock victim;

    private Command bukkitCommand;

    @BeforeEach
    void setUp() {
        plugin = loadFullPluginWithArenas();
        arenaManager = plugin.getMultiArenaManager();

        var config = plugin.getConfigManager().getActiveConfig();
        // 夹具妥协 ②③：ObjectiveMock#numberFormat 未实现（会被 MockBukkit 转成 SKIPPED），
        // Bukkit.getMobGoals() 未实现（globalMobCap=0 让 spawnWave 走同步早退）。
        config.setScoreboardEnabled(false);
        config.setGlobalMobCap(0);
        // 夹具妥协 ①：PlayerInventoryMock#setArmorContents(null) 会 NPE（生产侧支持 null 数组）。
        disablePlayerGoodsManager();

        arena1 = arenaManager.getArena("arena1");
        assertNotNull(arena1, "arena1 未启用 —— 夹具失效（loadFullPluginWithArenas 的 20 tick 没跑完？）");
        arena1Game = arena1.getGameManager();
        deployArena1();

        arenaB = enableSecondArena();
        arenaBGame = arenaB.getGameManager();
        arenaBWorld = server.getWorld("hilltop_arena2");
        lobbyWorld = server.getWorld("lobby");
        assertNotNull(arenaBWorld, "hilltop_arena2 世界不存在");
        assertNotNull(lobbyWorld, "lobby 世界不存在");

        deployArenaB();

        survivor = server.addPlayer("R12Survivor");
        victim = server.addPlayer("R12Victim");

        bukkitCommand = Mockito.mock(Command.class);
        Mockito.when(bukkitCommand.getName()).thenReturn("hilltop");
    }

    // ---------------------------------------------------------------- 夹具

    private void disablePlayerGoodsManager() {
        try {
            Field field = HilltopVillagePlugin.class.getDeclaredField("playerGoodsManager");
            field.setAccessible(true);
            field.set(plugin, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法关闭 PlayerGoodsManager —— 夹具失效", e);
        }
    }

    /**
     * 注册并启用 {@code arenaB}（{@code hilltop_arena2} 出厂就有世界）。
     *
     * <p>用真实的 {@code enableArena} + {@code performTicks}，与
     * {@link HilltopCommandHandlerArenaGuardTest#enableSecondArena()} 同构喵。</p>
     */
    private ArenaInstance enableSecondArena() {
        World world = server.getWorld("hilltop_arena2");
        assertNotNull(world, "hilltop_arena2 世界不存在");
        world.getChunkAt(0, 0).load();

        if (arenaManager.getDefinitionManager().getArena(SECOND_ARENA_ID) == null) {
            ArenaDefinition definition = ArenaDefinition.builder(
                            SECOND_ARENA_ID, "hilltop_arena2", new Location(world, 0.5, 64, 0.5))
                    .enabled(true)
                    .build();
            arenaManager.getDefinitionManager().getRegistry().register(definition);
        }
        arenaManager.enableArena(SECOND_ARENA_ID);
        server.getScheduler().performTicks(20);

        ArenaInstance second = arenaManager.getArena(SECOND_ARENA_ID);
        assertNotNull(second, "第二个竞技场没能启用（status=" + arenaManager.getStatus(SECOND_ARENA_ID) + "）");
        assertEquals(2, second.getMinPlayers(),
                "夹具失效：arenaB 必须是 min-players=2 —— 「一人离场即跌破下限」正是本类要多验的那一半");
        return second;
    }

    /**
     * 给 {@code arena1} 补部署。
     *
     * <p>arena1 是本类的<b>目标场</b>：对照组要证明「未开局成员真的能换过去」，而
     * {@code ArenaInstance.addPlayer} 在 {@code !hasSpawnPoints()} 且非管理员时直接拒绝
     * （{@code arena-not-deployed}）。不部署，对照组的「换场成功」断言会假红喵。</p>
     */
    private void deployArena1() {
        World world = server.getWorld("hilltop_arena1");
        assertNotNull(world, "hilltop_arena1 世界不存在");
        world.getChunkAt(0, 0).load();
        Location core = new Location(world, 0.5, 64, 0.5);
        List<Location> spawns = List.of(new Location(world, 3.5, 64, 3.5));
        arena1Game.setCoreLocation(core);
        arena1Game.setSpawnPoints(spawns);
        arena1Game.setMobSpawnPoints(spawns);
    }

    /**
     * 给 {@code arenaB} 补部署。
     *
     * <p>{@code deployArena1()} 只部署了 arena1；{@code GameManager.startGame()} 的三重前置里
     * {@code hasSpawnPoints()} 与 {@code hasEffectiveMobSpawnPoints()} 都会静默 return，
     * 不部署就永远停在 WAITING，本类会退化成「又一次 WAITING 断言」喵。</p>
     */
    private void deployArenaB() {
        arenaBWorld.getChunkAt(0, 0).load();
        Location core = new Location(arenaBWorld, 0.5, 64, 0.5);
        List<Location> spawns = List.of(new Location(arenaBWorld, 3.5, 64, 3.5));
        arenaBGame.setCoreLocation(core);
        arenaBGame.setSpawnPoints(spawns);
        arenaBGame.setMobSpawnPoints(spawns);
    }

    /** 两人入场 + 显式开局，并把状态钉在 RUNNING（不依赖 10 秒自动开局倒计时）。 */
    private void fillArenaBWithTwoPlayersAndStart() {
        assertTrue(arenaManager.joinArena(survivor, SECOND_ARENA_ID), "队友 join arenaB 失败");
        // 第二人入场时 checkAutoStart 会直接把场推进 STARTING —— 这是预期的自动开局倒计时喵。
        assertTrue(arenaManager.joinArena(victim, SECOND_ARENA_ID), "被拉走者 join arenaB 失败");

        // 陷阱 2：显式 startGame() 并断言 RUNNING，别等倒计时喵。
        arenaB.startGame();
        assertEquals(GameState.RUNNING, arenaB.getState(),
                "arenaB 没进 RUNNING —— startGame 的三重前置（WAITING / 人数 / 两套出生点）有一项没满足");
        assertEquals(GameState.RUNNING, arenaBGame.getState(), "GameManager 侧状态不是 RUNNING");
        assertTrue(arenaBGame.isGameRunning(), "isGameRunning() 必须为 true 才会命中 join 的守卫");
        assertEquals(2, arenaBGame.getPlayerCount(), "arenaB 必须有两名在场成员");

        // 位置钉死：队友在竞技场世界内，被拉走者在世界外（陷阱：setLocation 而非 teleport）。
        survivor.setLocation(new Location(arenaBWorld, 3.5, 64, 3.5));
        victim.setLocation(new Location(lobbyWorld, 0.5, 100.0, 0.5));
        assertEquals("hilltop_arena2", survivor.getWorld().getName(),
                "夹具失效：队友必须站在竞技场世界内，否则「队友背败」无从谈起");
        assertEquals("lobby", victim.getWorld().getName(),
                "夹具失效：被拉走者必须已在竞技场世界外 —— 这是原缺陷漏过合取的唯一入口");

        drainMessages();
    }

    private List<String> drainMessages() {
        return drain(survivor, victim);
    }

    private List<String> drain(PlayerMock... players) {
        List<String> out = new ArrayList<>();
        for (PlayerMock player : players) {
            String message;
            while ((message = player.nextMessage()) != null) {
                out.add(message);
            }
        }
        return out;
    }

    private void run(PlayerMock who, String... args) {
        HilltopCommandHandler handler = new HilltopCommandHandler(plugin);
        handler.handle(who, bukkitCommand, "hilltop", args);
    }

    // ---------------------------------------------------------------- R-12 核心

    @Test
    @DisplayName("R-12：两人在场，一人被送出竞技场世界后跨场 join，队友必须仍是 RUNNING（不得被判负）")
    void teammateMustSurviveWhenOneOfTwoMembersIsPulledAway() {
        fillArenaBWithTwoPlayersAndStart();

        // 被拉走者执行 /hilltop join arena1 —— arena1 已就绪，是真实可达的目标场喵。
        run(victim, "join", "arena1");

        List<String> survivorMessages = drain(survivor);
        List<String> victimMessages = drain(victim);

        // ① 核心断言（先断）：队友那一局不能被翻成失败。
        assertNotEquals(GameState.DEFEAT, arenaB.getState(),
                "★ R-12 回归：arenaB 的竞技场状态被推成了 DEFEAT —— 判负链已触发，"
                        + "仍在场内的队友被记负。链路：join <他场> → MultiArenaManager.leaveArena"
                        + "（无对局中守卫）→ ArenaInstance.removePlayer → GameManager.removeOfflinePlayer 的"
                        + "「players.size() < min-players && isGameRunning()」→ endGame(false)。"
                        + "受害队友收到的播报=" + survivorMessages);
        assertNotEquals(GameState.ENDING, arenaB.getState(),
                "★ R-12 回归：arenaB 已被推进到 ENDING —— defeat 链已触发并进入重置");
        assertEquals(GameState.RUNNING, arenaBGame.getState(),
                "★ R-12 回归：arenaB 的对局状态被改成了 " + arenaBGame.getState()
                        + "。离场者已被 /tp 送出竞技场世界，判负链不该被触发。");
        assertTrue(arenaBGame.isGameRunning(), "★ R-12 回归：队友那一局已不再 isGameRunning()");

        // ② 队友的可见伤害：不能收到任何失败战报喵（这是「背败」的用户可观测形态）。
        String defeat = plugin.getLanguageManager().getFor(survivor, "game-defeat");
        assertFalse(survivorMessages.contains(defeat),
                "★ R-12 回归：队友收到了失败战报「" + defeat + "」—— 他全程站在场上，却被队友的离场判负。"
                        + "实际播报=" + survivorMessages);

        // ③ 命令必须拒绝，而不是「自动离开 + 换场」。
        String alreadyInArena = plugin.getLanguageManager().getFor(victim, "already-in-arena");
        assertTrue(victimMessages.contains(alreadyInArena),
                "★ R-12 回归：对局进行中执行跨场 join 必须被 already-in-arena 拒绝。实际播报="
                        + victimMessages + "。原缺陷的形态是「对局中 && 人在场世界内」的合取，"
                        + "玩家被 /tp 送出世界后合取为 false，于是漏进 leave→join 分支");

        // ④ 两名成员都还在 arenaB，映射完整，没有任何人被挪走喵。
        assertSame(arenaB, arenaManager.getPlayerArena(survivor), "队友的「玩家 → 竞技场」映射被改动");
        assertSame(arenaB, arenaManager.getPlayerArena(victim),
                "★ R-12 回归：被拉走者的映射已指向别的场 —— leaveArena 已执行");
        assertEquals(2, arenaBGame.getPlayerCount(), "★ R-12 回归：arenaB 的人数已跌破 2");
        assertTrue(arenaBGame.getPlayers().contains(victim.getUniqueId()),
                "★ R-12 回归：被拉走者已从 GameManager 的玩家集合中被移除 —— 判负链的第一步已发生");
        assertFalse(arena1.getGameManager().getPlayers().contains(victim.getUniqueId()),
                "★ R-12 回归：被拉走者被拉进了 arena1");

        // 陷阱 3：清理两名玩家的映射，避免 unmock 收尾路径污染后续用例喵。
        arenaManager.leaveArena(survivor);
        arenaManager.leaveArena(victim);
    }

    @Test
    @DisplayName("对照：同一双人夹具里，未开局的 arenaB 允许成员被拉走且不判负")
    void waitingTeammateCanStillBeMovedWithoutDefeat() {
        // 只让两人入场、不 startGame：auto-start 把场推到 STARTING，但 GameManager 仍是 WAITING。
        assertTrue(arenaManager.joinArena(survivor, SECOND_ARENA_ID), "队友 join arenaB 失败");
        assertTrue(arenaManager.joinArena(victim, SECOND_ARENA_ID), "被拉走者 join arenaB 失败");
        assertEquals(GameState.WAITING, arenaBGame.getState(),
                "夹具失效：未显式 startGame 时 GameManager 应停在 WAITING");
        victim.setLocation(new Location(lobbyWorld, 0.5, 100.0, 0.5));
        drainMessages();

        run(victim, "join", "arena1");

        List<String> victimMessages = drain(victim);
        drain(survivor);

        // 与正例合起来，这一对断言把「判据是 isGameRunning()」钉死：
        // 同样一人被拉走、同样是 min-players=2 的场，未开局不判负、进行中必须拒绝。
        assertNotEquals(GameState.DEFEAT, arenaB.getState(), "未开局时离场不该判负");
        assertSame(arena1, arenaManager.getPlayerArena(victim),
                "对照组失效：守卫不该变成「一律拒绝换场」—— 未开局成员必须真的能换到目标场。实际播报="
                        + victimMessages);
        assertEquals(1, arenaBGame.getPlayerCount(), "对照组失效：arenaB 应只剩队友一人");

        arenaManager.leaveArena(survivor);
        arenaManager.leaveArena(victim);
    }

    @Test
    @DisplayName("夹具自检：arenaB 的 min-players 与人数跌破判负链确实成立")
    void fixtureSelfCheckMinPlayersThresholdIsReachable() {
        fillArenaWithTwoPlayersOnly();

        // 直接摘掉一人（绕开命令层），验证判据本身：2 → 1 就该判负喵。
        arenaManager.leaveArena(victim);

        assertEquals(GameState.DEFEAT, arenaBGame.getState(),
                "夹具自检失败：min-players=2 的场掉到 1 人时 GameManager 必须 endGame(false)，"
                        + "否则本类根本没有可守的判负链");
        assertEquals(1, arenaBGame.getPlayerCount(), "arenaB 应只剩队友一人");
        assertNotEquals(GameState.DEFEAT, arena1.getState(), "arena1 与 arenaB 的状态不得互相串味");

        arenaManager.leaveArena(survivor);
    }

    /** 两人入场并显式开局，但不改动任何玩家的世界（供夹具自检使用）。 */
    private void fillArenaWithTwoPlayersOnly() {
        assertTrue(arenaManager.joinArena(survivor, SECOND_ARENA_ID), "队友 join arenaB 失败");
        assertTrue(arenaManager.joinArena(victim, SECOND_ARENA_ID), "被拉走者 join arenaB 失败");
        arenaB.startGame();
        assertEquals(GameState.RUNNING, arenaBGame.getState(), "arenaB 没进 RUNNING");
        drainMessages();
    }
}
