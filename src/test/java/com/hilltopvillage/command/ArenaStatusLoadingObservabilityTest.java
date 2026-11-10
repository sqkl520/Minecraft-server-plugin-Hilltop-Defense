package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * R-13 —— {@link ArenaStatus#LOADING} 的<b>可观测性判定</b>与 {@code isPending()} 分支覆盖喵。
 *
 * <p><b>本类同时给出两条结论，二者不可互相替代：</b></p>
 *
 * <ol>
 *   <li><b>生产时序：LOADING 从外部不可观测（结论 b）</b>。证据见
 *       {@code MultiArenaManager.processEnableQueue()}（{@code :328}）——
 *       它从取队首（{@code :334}）到写 {@code LOADING}（{@code :375}）、再到写 {@code READY}
 *       （{@code :389}）或 {@code DISABLED}（{@code :356}/{@code :382}/{@code :394}）
 *       <b>全部在同一个方法体内同步完成</b>，中间没有 {@code return}、没有二次调度、
 *       没有任何让出主线程的点。调度器（{@code :319} 的 {@code runTaskTimer(..., 1L, 1L)}）
 *       是单线程 tick 模型：两次 tick 之间才能观察，而 LOADING 的生命期完全落在这段间隔之内，
 *       因此任何「tick 边界采样」都不可能命中它喵。
 *       本结论由 {@link #loadingIsNeverObservableAtTickBoundaries()} <b>实测</b>复核：
 *       逐 tick 采样全程无 LOADING，且确实抵达 READY（证明队列真的跑过，不是空转假绿）喵。</li>
 *
 *   <li><b>分支覆盖：{@code isPending()} 的 LOADING 半边仍必须被钉死（手段 a，受控状态注入）</b>。
 *       既然生产时序拿不到 LOADING，就只能用<b>状态注入</b>（反射写 {@code statuses}）
 *       把 {@code getStatus} 置为 LOADING，再走真实的 {@code /hilltop disable} 路径喵。
 *       这一条<b>只证明判据覆盖 LOADING</b>，<b>不证明</b>生产环境能观察到 LOADING——
 *       报告中必须原样转述这一点喵。</li>
 * </ol>
 *
 * <p><b>为什么不合并成一条用例</b>：两条结论的逻辑方向相反。第 1 条若被「造出 LOADING」的
 * 用例取代，就会滑向「为了让覆盖率好看而伪造时序」；第 2 条若被第 1 条取代，
 * {@code isPending()} 的 LOADING 分支就会永远没有测试守护喵。</p>
 */
class ArenaStatusLoadingObservabilityTest extends MockBukkitTestBase {

    /** 队列探针场：注册在世界 {@value #PROBE_WORLD} 上，只用于逐 tick 采样。 */
    private static final String PROBE_ARENA_ID = "arenaProbe";
    private static final String PROBE_WORLD = "hilltop_loadprobe";

    /** 注入用场：注册但<b>从不启用</b>，因此没有实例、也不在启用队列里，状态可被自由注入。 */
    private static final String INJECT_ARENA_ID = "arenaInject";
    private static final String INJECT_WORLD = "hilltop_loadinject";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenaManager;
    private PlayerMock admin;
    private Command bukkitCommand;

    @BeforeEach
    void setUp() {
        plugin = loadFullPluginWithArenas();
        arenaManager = plugin.getMultiArenaManager();

        var config = plugin.getConfigManager().getActiveConfig();
        // 与 ArenaGuardMultiPlayerTest 同一组夹具妥协：scoreboard 的 ObjectiveMock#numberFormat
        // 未实现（会被 MockBukkit 转成 SKIPPED）、getMobGoals 未实现（globalMobCap=0 早退）。
        // 本类不构造对局，关掉只是为了不让任何偶然路径踩到它们喵。
        config.setScoreboardEnabled(false);
        config.setGlobalMobCap(0);

        admin = server.addPlayer("LoadingTester");
        admin.addAttachment(plugin, "hilltopvillage.admin", true);
        bukkitCommand = Mockito.mock(Command.class);
        Mockito.when(bukkitCommand.getName()).thenReturn("hilltop");
    }

    // ---------------------------------------------------------------- 夹具

    /** 在工作目录副本里新开一个世界并注册一个启用中的竞技场定义喵。 */
    private void registerArena(String arenaId, String worldName) {
        World world = server.getWorld(worldName);
        if (world == null) {
            world = server.addSimpleWorld(worldName);
        }
        world.getChunkAt(0, 0).load();
        if (arenaManager.getDefinitionManager().getArena(arenaId) == null) {
            ArenaDefinition definition = ArenaDefinition.builder(
                            arenaId, worldName, new Location(world, 0.5, 64, 0.5))
                    .enabled(true)
                    .build();
            arenaManager.getDefinitionManager().getRegistry().register(definition);
        }
    }

    /**
     * 受控状态注入：直接改 {@code MultiArenaManager#statuses} 这个 {@code ConcurrentHashMap}。
     *
     * <p>这是<b>测试侧注入</b>，不是生产时序。它绕过的正是本类要判定的那段
     * {@code processEnableQueue} 窗口，因此只能用来验证「判据是否覆盖 LOADING」喵。</p>
     */
    @SuppressWarnings("unchecked")
    private void injectStatus(String arenaId, ArenaStatus status) {
        try {
            Field field = MultiArenaManager.class.getDeclaredField("statuses");
            field.setAccessible(true);
            Map<String, ArenaStatus> statuses = (Map<String, ArenaStatus>) field.get(arenaManager);
            assertNotNull(statuses, "statuses 字段不应为 null");
            statuses.put(arenaId, status);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法访问 statuses 字段 —— 注入夹具失效", e);
        }
    }

    private List<String> run(String... args) {
        HilltopCommandHandler handler = new HilltopCommandHandler(plugin);
        handler.handle(admin, bukkitCommand, "hilltop", args);
        List<String> out = new ArrayList<>();
        String message;
        while ((message = admin.nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    // ------------------------------------------------ 结论 b：生产时序不可观测

    @Test
    @DisplayName("R-13(b)：逐 tick 采样全程观察不到 LOADING —— 它在单次任务体内自生自灭")
    void loadingIsNeverObservableAtTickBoundaries() {
        registerArena(PROBE_ARENA_ID, PROBE_WORLD);
        assertTrue(arenaManager.enableArena(PROBE_ARENA_ID), "enableArena(arenaProbe) 应入队成功");

        // 陷阱 6：一次都别 tick，此时才稳定停在 QUEUED喵。
        assertEquals(ArenaStatus.QUEUED, arenaManager.getStatus(PROBE_ARENA_ID),
                "夹具失效：入队后未 tick 时必须停在 QUEUED");

        List<ArenaStatus> samples = new ArrayList<>();
        samples.add(arenaManager.getStatus(PROBE_ARENA_ID));

        // 逐 tick 推进并采样。真实时序里 enableQueueTask 的 period=1，所以 1 tick 就够跑完；
        // 多采样几轮是为了覆盖「万一将来变成跨 tick 分片」的形态 —— 那时本用例会自动变红，
        // 正好提示「可观测性结论已经过期，需要重新判定」喵。
        for (int tick = 1; tick <= 5 && samples.get(samples.size() - 1) != ArenaStatus.READY; tick++) {
            server.getScheduler().performTicks(1);
            samples.add(arenaManager.getStatus(PROBE_ARENA_ID));
        }

        assertFalse(samples.contains(ArenaStatus.LOADING),
                "★ R-13(b) 结论被推翻：tick 边界采样观察到了 LOADING，样本=" + samples
                        + "。这意味着 processEnableQueue 在同一方法体内不再自洽"
                        + "（有人插入了让出点），报告的可观测性判定必须重做");
        assertEquals(ArenaStatus.READY, samples.get(samples.size() - 1),
                "夹具失效：队列没跑完，样本=" + samples + " —— 此时「观察不到 LOADING」是空转假绿");
        assertEquals(List.of(ArenaStatus.QUEUED, ArenaStatus.READY), samples,
                "本机实测的时间线：QUEUED（tick 前）→ READY（第 1 个 tick 内），"
                        + "LOADING 完全落在两次观测之间，实际样本=" + samples);

        // 语义侧证：READY 确实来自 processEnableQueue 的成功分支，而不是状态残留喵。
        ArenaInstance probe = arenaManager.getArena(PROBE_ARENA_ID);
        assertNotNull(probe, "processEnableQueue 没有造出实例 —— 上面的 READY 不可信");
        assertTrue(arenaManager.getEnableQueue().isEmpty(), "队列应已清空");
        assertEquals(ArenaStatus.READY, arenaManager.getStatus(PROBE_ARENA_ID));

        // 收尾：退役探针场，别让它留在 activeArenas 里影响本类其它用例的默认场解析喵。
        arenaManager.disableArena(PROBE_ARENA_ID);
    }

    // ------------------------------------ 结论 a：受控注入证明 LOADING 半边被覆盖

    @Test
    @DisplayName("R-13(a) 状态注入形态：getStatus 为 LOADING 时 disable 必须播报禁用成功")
    void injectedLoadingStatusIsTreatedAsPendingByDisable() {
        registerArena(INJECT_ARENA_ID, INJECT_WORLD);
        // 从不 enableArena：场上没实例、队列里没条目，getStatus 直接读 statuses 喵。
        assertNotSame(ArenaStatus.LOADING, arenaManager.getStatus(INJECT_ARENA_ID),
                "夹具失效：注入前不该已经是 LOADING");

        injectStatus(INJECT_ARENA_ID, ArenaStatus.LOADING);
        assertEquals(ArenaStatus.LOADING, arenaManager.getStatus(INJECT_ARENA_ID),
                "注入失败：getStatus 未读到 LOADING");
        assertTrue(arenaManager.getStatus(INJECT_ARENA_ID).isPending(),
                "LOADING 必须是 isPending() —— 这是 handleDisableArena 的唯一判据");

        List<String> messages = run("disable", INJECT_ARENA_ID);

        String ok = plugin.getLanguageManager().getFor(admin, "arena-disabled-ok", INJECT_ARENA_ID);
        String already = plugin.getLanguageManager().getFor(admin, "arena-already-disabled", INJECT_ARENA_ID);
        assertNotSame(ok, already, "夹具失效：两条 i18n 文案必须不同，否则本用例无法区分");

        assertTrue(messages.contains(ok),
                "★ R-13(a) 回归：LOADING 态禁用必须播报 arena-disabled-ok。实际播报=" + messages
                        + "。判据是 handleDisableArena 的 statusBefore.isPending()；"
                        + "若 isPending() 漏掉 LOADING，这里会退化成「本来就是禁用」的错误文案");
        assertFalse(messages.contains(already),
                "★ R-13(a) 回归：LOADING 并非「本来就是禁用」。实际播报=" + messages);
        assertEquals(ArenaStatus.DISABLED, arenaManager.getStatus(INJECT_ARENA_ID),
                "disableArena 应已把状态置为 DISABLED（它确实做了事）");
    }

    @Test
    @DisplayName("R-13(a) 反向对照：真·DISABLED 的场仍播报「已经是禁用状态」")
    void genuinelyDisabledArenaStillReportsAlreadyDisabled() {
        // arena2 在出厂 arenas.yml 里 enabled: false，启动时被置 DISABLED，无实例、不在队列中喵。
        assertEquals(ArenaStatus.DISABLED, arenaManager.getStatus("arena2"),
                "夹具失效：arena2 应处于 DISABLED");
        assertFalse(arenaManager.getStatus("arena2").isPending(),
                "夹具失效：arena2 不该是 pending 态，否则与注入用例重叠");

        List<String> messages = run("disable", "arena2");

        String ok = plugin.getLanguageManager().getFor(admin, "arena-disabled-ok", "arena2");
        String already = plugin.getLanguageManager().getFor(admin, "arena-already-disabled", "arena2");
        assertTrue(messages.contains(already),
                "对照组失效：真·已禁用的场必须播报 arena-already-disabled，实际=" + messages);
        assertFalse(messages.contains(ok),
                "对照组失效：真·已禁用的场不该播报 arena-disabled-ok，实际=" + messages);
    }

    @Test
    @DisplayName("判据自检：isPending() 恰好覆盖 QUEUED + LOADING，不多不少")
    void isPendingCoversExactlyQueuedAndLoading() {
        assertTrue(ArenaStatus.QUEUED.isPending(), "QUEUED 必须 isPending()");
        assertTrue(ArenaStatus.LOADING.isPending(), "LOADING 必须 isPending() —— R-13 的全部要害");
        assertFalse(ArenaStatus.READY.isPending(), "READY 不得 isPending()");
        assertFalse(ArenaStatus.DISABLED.isPending(), "DISABLED 不得 isPending()");
    }
}
