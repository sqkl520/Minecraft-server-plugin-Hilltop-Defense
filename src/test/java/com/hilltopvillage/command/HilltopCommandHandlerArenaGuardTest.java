package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link HilltopCommandHandler} 的两处 round1 修复的回归网 —— 本类存在的唯一理由。
 *
 * <p>守的是两个具体分支，不是整个 92.5 KB 路由类：</p>
 *
 * <ol>
 *   <li><b>{@code slice-06-logic-002}</b>（{@code fix-021}）：{@code /hilltop join <他场>}
 *       可以从<b>进行中</b>的对局里把成员拉走。原判定是合取
 *       {@code gameRunning && inCurrentWorld}，于是「对局中、但人已被 /tp 或第三方插件
 *       送出竞技场世界」这一形态漏进 {@code leaveArena → join} 分支；
 *       {@code GameManager#removeOfflinePlayer} 的
 *       {@code players.size() < min && isGameRunning() → endGame(false)}
 *       随即把<b>仍在场内的队友</b>判负。修复把条件降为单条件 {@code gameRunning}。</li>
 *
 *   <li><b>{@code slice-03-logic-004}</b>：{@code /hilltop disable <队列中的场>} 报
 *       「本来就是禁用」。{@code MultiArenaManager#disableArena} 在「实例不存在」时
 *       返回 {@code arena-already-disabled}，但该分支其实<b>做了事</b>——撤下启用队列并置
 *       {@code DISABLED}。修复让 {@code handleDisableArena} 先采样 {@code getStatus}，
 *       {@code isPending()}（{@code QUEUED}/{@code LOADING}）时改播 {@code arena-disabled-ok}。</li>
 * </ol>
 *
 * <p><b>为什么必须用真实插件而不是 mock</b>：两条缺陷的触点都不在命令层内部 ——
 * 前者要 {@code GameManager} 真的因为人数跌破阈值跑出 {@code endGame(false)}，
 * 后者要 {@code MultiArenaManager} 真的把场从启用队列里撤下来。把这两个协作者 mock 掉，
 * 测试就退化成「验证自己写下的 when(...)」，正是 {@code ArenaCommandTest} 类注释里
 * 批评的「自己验证自己」。因此本类用 {@link #loadFullPluginWithArenas()} 起真实插件，
 * {@code arena1} 由出厂 {@code arenas.yml} 真实启用（{@code min-players: 1}、20 波）。</p>
 *
 * <p><b>三处必须做的夹具妥协</b>（都是 MockBukkit 的能力缺口，不是被测代码的问题）：</p>
 * <ol>
 *   <li>{@code PlayerGoodsManager} 反射置 null。{@code PlayerInventoryMock#setArmorContents(null)}
 *       抛 {@code NullPointerException: ItemStack was null}，而真实 Bukkit 允许 null 数组；
 *       {@code ArenaInstance} 本来就显式支持该管理器为 null（P2-34），这条路径是<b>生产态</b>而非伪造态。</li>
 *   <li>{@code scoreboard.enabled = false}：{@code ObjectiveMock#numberFormat(...)} 在
 *       MockBukkit 4.45.0 抛 {@code UnimplementedOperationException}，而 MockBukkit 会把它
 *       转成 <b>SKIPPED 而不是 FAILED</b>（本轮流程约束 #2）。关掉开关让 {@code show()} 早退，
 *       否则本类会以「绿 + Skipped」的形态骗过 CI。</li>
 *   <li>{@code globalMobCap = 0}：让 {@code spawnWave} 走「已满容量」的同步早退，
 *       避开 {@code Bukkit.getMobGoals()} 未实现。</li>
 * </ol>
 */
class HilltopCommandHandlerArenaGuardTest extends MockBukkitTestBase {

    /** 第二个竞技场（建在 {@code hilltop_arena2} 上），用于「跨场 join」的目标场。 */
    private static final String SECOND_ARENA_ID = "arenaB";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenaManager;
    private ArenaInstance arena1;
    private GameManager arena1Game;
    private PlayerMock admin;
    private Command bukkitCommand;

    @BeforeEach
    void setUp() {
        plugin = loadFullPluginWithArenas();
        arenaManager = plugin.getMultiArenaManager();

        var config = plugin.getConfigManager().getActiveConfig();
        // 见类注释「夹具妥协」第 2、3 条
        config.setScoreboardEnabled(false);
        config.setGlobalMobCap(0);
        // 见类注释「夹具妥协」第 1 条
        disablePlayerGoodsManager();

        arena1 = arenaManager.getArena("arena1");
        assertNotNull(arena1,
                "arena1 未启用 —— 夹具失效。loadFullPluginWithArenas() 的 20 tick 没跑完启用队列？");
        arena1Game = arena1.getGameManager();

        // 出厂 arenas.yml 的 spawn-points 是空的，而 startGame 会静默 return 保持 WAITING。
        // 这里补上出生点，让「进行中」这个前置状态真的可达。
        deployArena1();

        admin = server.addPlayer("GuardTester");
        // ⚠️ addAttachment 的 Plugin 必须是真实 Plugin（本插件实例），Mockito mock 会在
        // getDescription() 上 NPE（round2 波次三已记录）。
        admin.addAttachment(plugin, "hilltopvillage.admin", true);

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
     * 注册并启用第二个竞技场（{@code hilltop_arena2} 出厂就有世界）。
     *
     * <p>用<b>真实的</b> {@code enableArena} + {@code performTicks} 而不是塞 mock：
     * 目标场必须是 {@code activeArenas} 里的真实实例，{@code /hilltop join arenaB}
     * 才有东西可落，否则「join 成功」这条对照会假绿。</p>
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
        assertNotNull(second,
                "第二个竞技场没能启用（status=" + arenaManager.getStatus(SECOND_ARENA_ID)
                        + "）—— 跨场 join 的对照夹具失效");
        return second;
    }

    /** 把玩家从竞技场世界送出去，模拟管理员 /tp 或第三方插件的跨世界传送。 */
    private void sendPlayerOutOfArenaWorld() {
        World lobby = server.getWorld("lobby");
        assertNotNull(lobby, "lobby 世界不存在");
        admin.teleport(new Location(lobby, 0.5, 100.0, 0.5));
        assertEquals("lobby", admin.getWorld().getName());
        assertNotEquals(arena1.getWorldName(), admin.getWorld().getName(),
                "夹具失效：玩家仍在竞技场世界内 —— 「人不在场世界」这个前置没建立起来");
    }

    private void joinArena1AndStartGame() {
        assertTrue(arenaManager.joinArena(admin, "arena1"), "join arena1 失败");
        arena1.startGame();
        assertEquals(GameState.RUNNING, arena1Game.getState(),
                "对局没进 RUNNING —— 夹具失效");
        assertTrue(arena1Game.isGameRunning(), "isGameRunning() 必须为 true 才会命中 join 的守卫");
        // startGame 会广播开局文案，清掉以免和后面的断言混淆
        drainMessages();
    }

    private List<String> drainMessages() {
        List<String> out = new ArrayList<>();
        String message;
        while ((message = admin.nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    private List<String> run(String... args) {
        HilltopCommandHandler handler = new HilltopCommandHandler(plugin);
        handler.handle(admin, bukkitCommand, "hilltop", args);
        return drainMessages();
    }

    // ------------------------------------------- slice-06-logic-002 的核心回归

    @Test
    @DisplayName("slice-06-logic-002：对局进行中，人已被送出竞技场世界，跨场 join 仍必须被拒绝")
    void runningMemberOutsideArenaWorldCannotBePulledToAnotherArena() {
        ArenaInstance second = enableSecondArena();
        joinArena1AndStartGame();
        sendPlayerOutOfArenaWorld();

        List<String> messages = run("join", SECOND_ARENA_ID);

        // ① 核心断言（先断，因为这才是缺陷的真实伤害）：那一局不能被翻成失败
        assertNotEquals(GameState.DEFEAT, arena1.getState(),
                "★ slice-06-logic-002 回归：arena1 的竞技场状态被推成了 DEFEAT —— 判负链已触发。"
                        + "链路：join <他场> → MultiArenaManager.leaveArena（无对局中守卫）→ "
                        + "ArenaInstance.removePlayer → GameManager.removeOfflinePlayer 的"
                        + "「players.size() < min-players && isGameRunning()」→ endGame(false)。"
                        + "仍在场内正常作战的队友被记负。实际播报=" + messages);
        assertNotEquals(GameState.ENDING, arena1.getState(),
                "★ slice-06-logic-002 回归：竞技场被推进到 ENDING —— defeat 链已触发并进入重置");
        assertEquals(GameState.RUNNING, arena1Game.getState(),
                "★ slice-06-logic-002 回归：arena1 的对局状态被改成了 " + arena1Game.getState()
                        + "。离开的是一名已被 /tp 送出竞技场世界的成员，"
                        + "判负链不该被触发。实际播报=" + messages);

        // ② 命令必须明确拒绝，而不是「自动离开 + 换场」的静默行为
        String alreadyInArena = plugin.getLanguageManager().getFor(admin, "already-in-arena");
        assertTrue(messages.contains(alreadyInArena),
                "★ slice-06-logic-002 回归：对局进行中执行 /hilltop join " + SECOND_ARENA_ID
                        + " 必须被 already-in-arena 拒绝。实际播报=" + messages
                        + "。原缺陷的形态是「对局中 && 人在场世界内」的合取，"
                        + "玩家被 /tp 送出世界后合取为 false，于是漏进 leave→join 分支");

        // ③ 玩家必须仍在原竞技场，且没有被挪到目标场
        assertNotNull(arenaManager.getPlayerArena(admin),
                "★ slice-06-logic-002 回归：玩家的「玩家 → 竞技场」映射已被清空（leaveArena 已执行）");
        assertEquals("arena1", arenaManager.getPlayerArena(admin).getArenaId(),
                "★ slice-06-logic-002 回归：玩家被移出了 arena1（映射指向了别的场）");
        assertTrue(arena1Game.getPlayers().contains(admin.getUniqueId()),
                "★ slice-06-logic-002 回归：玩家已从 GameManager 的玩家集合中被移除 —— 判负链的第一步已发生");
        assertEquals(1, arena1Game.getPlayers().size(), "arena1 必须仍是 1 人在场");
        assertNotSame(second, arenaManager.getPlayerArena(admin),
                "★ slice-06-logic-002 回归：玩家被拉进了目标场 " + SECOND_ARENA_ID);
        assertEquals(0, second.getPlayerCount(),
                "★ slice-06-logic-002 回归：目标场不该接收这名玩家");

        // 清理：把玩家送出对局，避免 unmock 时的收尾路径污染后续用例
        arenaManager.leaveArena(admin);
    }

    @Test
    @DisplayName("对照组：对局未开始（WAITING）时跨场 join 仍然放行并完成换场")
    void waitingMemberIsStillMovedByCrossArenaJoin() {
        ArenaInstance second = enableSecondArena();

        // 站在「等待中」的原场里：arenaB 由 builder 造出（min-players=2 且未部署出生点），
        // 单人 join 后稳定停在 WAITING —— 正是修复文档所说的「可切换的等待状态」。
        // （不用 arena1 做原场：arena1 的 min-players=1，单人 join 会立刻自动开局进 STARTING。）
        assertTrue(arenaManager.joinArena(admin, SECOND_ARENA_ID), "join arenaB 失败");
        assertEquals(GameState.WAITING, second.getGameManager().getState(),
                "夹具失效：arenaB 应停在 WAITING");
        assertFalse(second.getGameManager().isGameRunning(),
                "夹具失效：arenaB 的 isGameRunning() 必须为 false，否则本用例与正例重叠");
        drainMessages();

        List<String> messages = run("join", "arena1");

        String autoLeft = plugin.getLanguageManager().getFor(admin, "arena-auto-left");
        assertTrue(messages.contains(autoLeft),
                "对照组失效：等待状态下跨场 join 应当先自动离开原场（arena-auto-left），实际=" + messages);
        assertNotNull(arenaManager.getPlayerArena(admin), "对照组失效：玩家离开原场后没有加入任何场");
        assertEquals("arena1", arenaManager.getPlayerArena(admin).getArenaId(),
                "对照组失效：守卫不应变成「一律拒绝换场」—— 等待状态下玩家必须真的能换到目标场");
        assertFalse(second.getGameManager().getPlayers().contains(admin.getUniqueId()),
                "对照组失效：玩家应已离开 arenaB 的玩家集合");

        // WAITING 状态下离开不该判负
        assertNotEquals(GameState.DEFEAT, second.getGameManager().getState(),
                "WAITING 阶段离开不应触发判负");

        arenaManager.leaveArena(admin);
    }

    // ------------------------------------------- slice-03-logic-004 的核心回归

    @Test
    @DisplayName("slice-03-logic-004：禁用队列中的竞技场播报「已禁用」而非「本来就是禁用」")
    void disablingQueuedArenaReportsSuccess() {
        // 注册一个启用后会排在队列里的场，但**不推进 tick** —— 让它稳定停在 QUEUED。
        World world = server.getWorld("hilltop_arena3");
        assertNotNull(world, "hilltop_arena3 世界不存在");
        world.getChunkAt(0, 0).load();
        arenaManager.getDefinitionManager().getRegistry().register(
                ArenaDefinition.builder("arenaC", "hilltop_arena3", new Location(world, 0.5, 64, 0.5))
                        .enabled(true)
                        .build());
        assertTrue(arenaManager.enableArena("arenaC"), "enableArena(arenaC) 应入队成功");

        ArenaStatus before = arenaManager.getStatus("arenaC");
        assertTrue(before.isPending(),
                "夹具失效：arenaC 必须停在 QUEUED/LOADING 才是本用例的分支，实际=" + before);
        assertEquals(ArenaStatus.QUEUED, before);
        assertNull(arenaManager.getArena("arenaC"),
                "夹具失效：arenaC 不应已有实例 —— 否则 disableArena 会走 deploy.ok 分支，"
                        + "绕开本用例要测的「实例不存在但确实撤了队」这条路径");
        drainMessages();

        List<String> messages = run("disable", "arenaC");
        String ok = plugin.getLanguageManager().getFor(admin, "arena-disabled-ok", "arenaC");
        String already = plugin.getLanguageManager().getFor(admin, "arena-already-disabled", "arenaC");
        assertNotEquals(ok, already,
                "夹具失效：两条 i18n 文案必须不同，否则本用例无法区分");

        assertTrue(messages.contains(ok),
                "★ slice-03-logic-004 回归：禁用 QUEUED 状态的竞技场必须播报 arena-disabled-ok。"
                        + "实际播报=" + messages + "。原缺陷：handleDisableArena 播报的是 "
                        + "disableArena 的返回串 arena-already-disabled —— 而该分支其实已经把场从"
                        + "启用队列撤下并置 DISABLED，是「做了事却说没做」");
        assertFalse(messages.contains(already),
                "★ slice-03-logic-004 回归：不能播报 arena-already-disabled —— 该场此前是 QUEUED，"
                        + "并非本来就是禁用。实际播报=" + messages);

        // 语义侧证：disableArena 确实做了事（撤队 + 置 DISABLED）
        assertEquals(ArenaStatus.DISABLED, arenaManager.getStatus("arenaC"),
                "arenaC 应已被置为 DISABLED");
        assertFalse(arenaManager.getEnableQueue().contains("arenaC"),
                "arenaC 应已从启用队列中撤下 —— 这正是「disable 做了事」的证据");

        arenaManager.leaveArena(admin);
    }

    @Test
    @DisplayName("对照组：真正本来就禁用的竞技场仍播报「已经是禁用状态」")
    void disablingGenuinelyDisabledArenaStillReportsAlreadyDisabled() {
        // 出厂 arenas.yml 里 arena2 是 enabled: false，启动时被置 DISABLED，无实例、不在队列中。
        assertEquals(ArenaStatus.DISABLED, arenaManager.getStatus("arena2"),
                "夹具失效：arena2 应处于 DISABLED");
        assertFalse(arenaManager.getStatus("arena2").isPending(),
                "夹具失效：arena2 不应是 pending 态，否则本对照组与正例重叠");
        drainMessages();

        List<String> messages = run("disable", "arena2");

        String ok = plugin.getLanguageManager().getFor(admin, "arena-disabled-ok", "arena2");
        String already = plugin.getLanguageManager().getFor(admin, "arena-already-disabled", "arena2");

        assertTrue(messages.contains(already),
                "对照组失效：真·已禁用的场必须播报 arena-already-disabled，实际=" + messages);
        assertFalse(messages.contains(ok),
                "对照组失效：真·已禁用的场不该播报 arena-disabled-ok，实际=" + messages);

        // 与正例合起来，这一对断言把「isPending() 这个判据」钉死：
        // 两次调用 disableArena 的返回值都是 arena-already-disabled，文案却必须不同。
        assertEquals(ArenaStatus.DISABLED, arenaManager.getStatus("arena2"));

        arenaManager.leaveArena(admin);
    }

    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }
}
