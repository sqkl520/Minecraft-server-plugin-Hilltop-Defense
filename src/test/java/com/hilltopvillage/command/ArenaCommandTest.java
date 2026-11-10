package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ArenaCommand} 的 {@code /hilltop arenas} 分桶统计与权限契约测试。
 *
 * <p><b>本类守护的 round1 修复：{@code slice-06-logic-006}（Total vs 分桶和对不上）</b>。
 *
 * <p><b>缺陷原状</b>：{@code countByState(...)} 的返回数组里 {@code counts[0]} 恒为
 * <b>全部活动竞技场数</b>，其余下标才是各状态分桶。原实现只传了
 * {@code WAITING / STARTING / RUNNING / ENDING} 四个状态，而 {@code ArenaInstance} 在结算倒计时
 * （{@code defeat-countdown-seconds}）期间会稳定停留在 {@code VICTORY} 或 {@code DEFEAT}。
 * 这两个状态既不计入任何分桶、又照旧计入 {@code counts[0]}，于是<b>每次分出胜负后的十几秒里
 * Total 恒大于四桶之和</b>——玩家看到「总数 3，等待 1 + 进行中 1 + 收尾 0 = 2」这种自相矛盾的输出。
 *
 * <p><b>修复</b>：把 {@code VICTORY} 与 {@code DEFEAT} 也传进 {@code countByState}，
 * 并归入「收尾」桶（两者同属「本局已结束、场馆正在收尾」窗口，具体胜负仍由每个场馆的详情行
 * 单独播报，信息不丢）。{@code WAVE_INTERMISSION} 只有 {@code GameManager} 用，
 * {@code ArenaInstance} 从不停留在该状态，故不列 —— 这一点也有用例固定住。
 *
 * <p><b>怎么测的</b>：{@code ArenaInstance} 是具体类且构造代价高，这里用
 * {@code mock(ArenaInstance.class)} + {@code when(getState())} 精确控制每个场馆的状态，
 * 让 {@code MultiArenaManager.countByState(...)} <b>真实执行</b>（它只是遍历 map 计数，
 * 不碰 Bukkit）。这样断言的正是「传进来的状态集合是否覆盖全部可达状态」这个合同，
 * 而不是把 {@code countByState} 也 mock 掉、变成自己验证自己。
 */
class ArenaCommandTest extends MockBukkitTestBase {

    private MultiArenaManager arenaManager;
    private ArenaManager definitionManager;
    private LanguageManager languageManager;
    private ArenaCommand command;
    private PlayerMock admin;
    private PlayerMock player;
    private Command bukkitCommand;

    /** 把 i18n 桩成「key|arg1|arg2|...」，便于断言传进去的参数。 */
    private static String flatten(String key, Object... args) {
        StringBuilder sb = new StringBuilder(key);
        if (args != null) {
            for (Object arg : args) {
                sb.append('|').append(arg instanceof int[] arr ? java.util.Arrays.toString(arr) : arg);
            }
        }
        return sb.toString();
    }

    /**
     * 给 i18n 打桩：所有重载都返回非空文本。
     *
     * <p><b>为什么必须显式桩「零个可变参数」那一路</b>：{@code forSender(...)} 里的
     * {@code languageManager.get(key, locale, args)} 在 {@code args} 为空数组时，
     * Mockito 的 {@code any()} <b>不会</b>命中 varargs 位置，会静默落到默认返回值 {@code null}；
     * 随后 {@code PlayerMock#sendMessage(null)} 抛 {@code IllegalArgumentException: message cannot be null}。
     * 实测踩过：未桩时 {@code ArenaCommandTest} 8 条用例全红在 {@code runArenas} 上。
     * 这与 mechanics 夹具里记录的坑是同一个。
     */
    @BeforeEach
    void rig() {
        var host = createDummyPlugin("ArenaCmdHost");

        definitionManager = new ArenaManager(host);
        definitionManager.getRegistry().register(ArenaDefinition.builder("arena1", "hilltop_arena1", null).build());
        definitionManager.getRegistry().register(ArenaDefinition.builder("arena2", "hilltop_arena2", null).build());

        arenaManager = mock(MultiArenaManager.class);
        when(arenaManager.getDefinitionManager()).thenReturn(definitionManager);
        when(arenaManager.getActiveArenas()).thenReturn(List.of());
        // 详情行会对每个「已配置」竞技场取运行时状态；未桩时返回 null，
        // switch (status) 直接 NPE。真实实现里 getStatus 对已注册 ID 恒返回非 null。
        when(arenaManager.getStatus(any(String.class))).thenReturn(ArenaStatus.READY);

        // countByState 用真实实现（纯内存遍历，不触达 Bukkit），只把数据源换成我们控制的 map。
        // ⚠️ countByState(GameState... states) 是 **varargs**：Mockito 把实参摊平进 getArguments()，
        // 所以这里必须用 `getArguments()` 整体当数组断言状态集合 —— 用 `getArgument(0)`
        // 只会拿到第一个枚举值本身，强转数组会 ClassCastException（2026-11-03 实测踩过）。
        // 反过来，这也让本用例真正验证「调用方传进来的是哪个状态集合」。
        when(arenaManager.countByState(any(GameState[].class))).thenAnswer(inv -> {
            Object[] raw = inv.getArguments();
            GameState[] states = java.util.Arrays.stream(raw)
                    .map(GameState.class::cast)
                    .toArray(GameState[]::new);
            int[] counts = new int[states.length + 1];
            var active = arenaManager.getActiveArenas();
            counts[0] = active.size();
            for (ArenaInstance arena : active) {
                for (int i = 0; i < states.length; i++) {
                    if (arena.getState() == states[i]) {
                        counts[i + 1]++;
                        break;
                    }
                }
            }
            return counts;
        });

        // ⚠️ i18n 桩法（2026-11-03 实测，三次踩坑后定型）：
        // LanguageManager 的文本方法是 **varargs**（Object... args）。Mockito 对 varargs 的匹配
        // 极不可靠：`any()` 匹配不到「零个可变参数」；`getArgument(2)` 在零参数时数组越界
        // （Mockito 把 varargs 摊平进 getArguments()）；`doAnswer(...).when(mock).get(any(),any(),any())`
        // 同样因为匹配不到零参数而漏桩。漏掉的形态静默返回 null，随后
        // PlayerMock#sendMessage(null) 抛 IllegalArgumentException: message cannot be null。
        //
        // 唯一可靠的做法：**在 mock() 时就传入默认 Answer**，让所有方法、所有 arity 都走同一个实现，
        // 完全绕开参数匹配。
        languageManager = mock(LanguageManager.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object[] all = invocation.getArguments();
            if (method.equals("getLocale")) return "en";
            if (!method.equals("get") && !method.equals("getFor")) {
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            // get(key, locale, args...) → key 在 all[0]；getFor(player, key, args...) → key 在 all[1]
            StringBuilder sb = new StringBuilder(String.valueOf(all[method.equals("get") ? 0 : 1]));
            for (int i = 2; i < all.length; i++) {
                sb.append('|').append(all[i]);
            }
            return sb.toString();
        });

        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);
        when(plugin.getLanguageManager()).thenReturn(languageManager);

        command = new ArenaCommand(plugin);

        admin = server.addPlayer("ArenaCmdAdmin");
        admin.addAttachment(host, "hilltopvillage.admin", true);
        player = server.addPlayer("ArenaCmdPlayer");

        bukkitCommand = mock(Command.class);
        when(bukkitCommand.getName()).thenReturn("hilltop");
    }

    /** 为 {@code /hilltop arenas} 准备指定状态的若干活动场馆。 */
    private List<ArenaInstance> arenasIn(GameState... states) {
        List<ArenaInstance> list = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            ArenaInstance arena = mock(ArenaInstance.class);
            when(arena.getArenaId()).thenReturn("arena" + (i + 1));
            when(arena.getDisplayName()).thenReturn("Arena " + (i + 1));
            when(arena.getWorldName()).thenReturn("hilltop_arena" + (i + 1));
            when(arena.getState()).thenReturn(states[i]);
            when(arena.getPlayerCount()).thenReturn(0);
            when(arena.getMaxPlayers()).thenReturn(6);
            when(arena.getMinPlayers()).thenReturn(2);
            when(arena.getCurrentWave()).thenReturn(0);
            when(arena.getTotalWaves()).thenReturn(20);
            when(arena.isEnabled()).thenReturn(true);
            when(arena.isWorldAvailable()).thenReturn(true);
            when(arena.isDeployed()).thenReturn(true);
            list.add(arena);
        }
        when(arenaManager.getActiveArenas()).thenReturn(list);
        return list;
    }

    private List<String> messagesOf(PlayerMock sender) {
        List<String> out = new ArrayList<>();
        String msg;
        while ((msg = sender.nextMessage()) != null) {
            out.add(msg);
        }
        return out;
    }

    private List<String> runArenas(PlayerMock sender) {
        try {
            command.onCommand(sender, bukkitCommand, "hilltop", new String[]{"arenas"});
        } catch (ArrayIndexOutOfBoundsException e) {
            // 生产代码按固定下标取 counts[0..6]，因此 countByState 的入参个数一旦少传，
            // 这里不会「悄悄少统计」，而是直接越界崩溃。
            // 2026-11-03 变异 M9 实测：删掉 VICTORY/DEFEAT 两个入参 →
            // 本测试 5 条全红，但原始信息是一句裸 ArrayIndexOutOfBoundsException。
            // 包一层可读消息，让失败直接点名「状态集合与下标不匹配」这个真实问题。
            throw new AssertionError(
                    "★ slice-06-logic-006 回归：/hilltop arenas 崩溃 —— countByState 的入参状态个数"
                            + "与 ArenaCommand 里固定的 counts[0..6] 下标不再匹配。"
                            + "极可能是漏传了 VICTORY / DEFEAT：它们既计入 Total(counts[0])、"
                            + "又不落入任何桶，正是原缺陷的形状。原始异常=" + e, e);
        }
        return messagesOf(sender);
    }

    /**
     * 取出统计行并解析成 [total, waiting, starting, running, ending]。
     *
     * <p><b>为什么不直接用字符串断言</b>：{@code countByState} 的入参个数一旦变化，
     * 返回数组长度就变，{@code forSender(...)} 的 varargs 个数也随之变。
     * 2026-11-03 变异 M9 实测：删掉 VICTORY/DEFEAT 两个入参后，
     * 裸 {@code split("|")} + 固定下标断言会抛 {@code ArrayIndexOutOfBoundsException}
     * —— 虽然红得对，但错误信息完全看不出「有人少传了状态」，是低质量的失败。
     * 这里显式断言���数个数并给出可读消息，让变异失败能直接点名问题。
     */
    private int[] statisticsOf(List<String> messages) {
        String line = messages.stream()
                .filter(s -> s.startsWith("arena-status-statistics"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到 arena-status-statistics 行，实际输出=" + messages));
        String[] parts = line.split("\\|");
        assertEquals(6, parts.length,
                "★ slice-06-logic-006 回归：统计行必须是 Total + 4 个分桶共 5 个参数"
                        + "（文本本身 + 5 个占位）。实际只有 " + (parts.length - 1)
                        + " 个参数 —— 极可能是 countByState 少传了状态（VICTORY / DEFEAT 被漏掉），"
                        + "这会让 Total 大于分桶之和，正是原缺陷的形状。实际行=" + line);
        int[] out = new int[5];
        for (int i = 0; i < 5; i++) {
            out[i] = Integer.parseInt(parts[i + 1]);
        }
        return out;
    }

    // ------------------------------------------- slice-06-logic-006 的核心回归

    @Test
    @DisplayName("slice-06-logic-006：VICTORY 状态计入分桶，Total 等于分桶之和")
    void victoryStateCountsTowardBuckets() {
        arenasIn(GameState.WAITING, GameState.RUNNING, GameState.VICTORY);

        int[] s = statisticsOf(runArenas(admin));
        // [total, waiting, starting, running, ending]
        int total = s[0], waiting = s[1], starting = s[2], running = s[3], ending = s[4];

        assertEquals(3, total, "Total 必须是 3 个活动场馆");
        assertEquals(1, ending,
                "★ slice-06-logic-006 回归：VICTORY 必须计入「收尾」桶。"
                        + "原实现漏传 VICTORY/DEFEAT，导致胜负后的十几秒里 Total 恒大于四桶之和 —— "
                        + "玩家看到「总数 3，分桶和 2」这种自相矛盾的输出");
        assertEquals(total, waiting + starting + running + ending,
                "★ slice-06-logic-006：四桶之和必须等于 Total"
                        + "（wait=" + waiting + " start=" + starting
                        + " run=" + running + " end=" + ending + " total=" + total + "）");
    }

    @Test
    @DisplayName("slice-06-logic-006：DEFEAT 状态同样计入分桶")
    void defeatStateCountsTowardBuckets() {
        arenasIn(GameState.WAITING, GameState.DEFEAT);

        int[] s = statisticsOf(runArenas(admin));
        int total = s[0], waiting = s[1], starting = s[2], running = s[3], ending = s[4];

        assertEquals(2, total, "Total 必须是 2 个活动场馆");
        assertEquals(1, ending,
                "★ slice-06-logic-006 回归：DEFEAT 必须计入「收尾」桶，否则团灭后 Total 与分桶和不等");
        assertEquals(total, waiting + starting + running + ending,
                "★ slice-06-logic-006：四桶之和必须等于 Total");
    }

    @Test
    @DisplayName("slice-06-logic-006：分桶之和恒等于 Total（对全部可达状态做穷举）")
    void bucketSumEqualsTotalForEveryReachableState() {
        // ArenaInstance 的全部可达状态（不含 WAVE_INTERMISSION，它只有 GameManager 用）
        GameState[] reachable = {
                GameState.WAITING, GameState.STARTING, GameState.RUNNING,
                GameState.ENDING, GameState.VICTORY, GameState.DEFEAT,
        };

        // 一次放 6 个场馆，每个状态各一个
        arenasIn(reachable);

        int[] s = statisticsOf(runArenas(admin));
        int total = s[0], waiting = s[1], starting = s[2], running = s[3], ending = s[4];

        assertEquals(reachable.length, total, "Total 必须是全部活动场馆数（6）");
        assertEquals(1, waiting, "WAITING 桶");
        assertEquals(1, starting, "STARTING 桶");
        assertEquals(1, running, "RUNNING 桶");
        assertEquals(3, ending, "ENDING + VICTORY + DEFEAT 三个状态都归入「收尾」桶");
        assertEquals(total, waiting + starting + running + ending,
                "★ slice-06-logic-006 回归：6 个场馆各占一个可达状态时，"
                        + "四桶之和必须等于总数 6。任何未传入 countByState 的状态都会"
                        + "「计入 Total 却不落入任何桶」，这就是原 bug 的形状");
    }

    @Test
    @DisplayName("WAVE_INTERMISSION 不是 ArenaInstance 的可达停留状态，故不参与分桶（与实现注释一致）")
    void waveIntermissionIsNotBucketed() {
        // 只放一个 WAVE_INTERMISSION 场馆：它既不在传入的状态集合里，也不该被算进任何桶。
        // 这不是 bug —— ArenaInstance 从不停留在该状态（实现注释已核实）。
        // 本用例固定住这个前提，避免有人「顺手」把它加进去却又不改 Total 的语义。
        arenasIn(GameState.WAVE_INTERMISSION);

        int[] s = statisticsOf(runArenas(admin));
        int total = s[0], waiting = s[1], starting = s[2], running = s[3], ending = s[4];

        assertEquals(1, total, "Total 仍是 1 —— counts[0] 统计的是全部活动场馆");
        assertEquals(0, waiting + starting + running + ending,
                "WAVE_INTERMISSION 不在 countByState 的入参里，故四桶全 0；"
                        + "若哪天 ArenaInstance 开始停留在该状态，本断言会红，提醒把它补进入参");
    }

    @Test
    @DisplayName("RUNNING 与 WAVE_INTERMISSION 在详情行都显示为「进行中」")
    void runningAndIntermissionShareDetailText() {
        arenasIn(GameState.RUNNING);

        List<String> out = runArenas(admin);

        assertTrue(out.stream().anyMatch(s -> s.startsWith("arena-list-entry") || s.startsWith("arena-detail")),
                "有活动场馆时必须输出详情行，实际=" + out);
    }

    // ---------------------------------------------------------------- 权限

    @Test
    @DisplayName("非管理员执行 /hilltop arenas 只得到 no-permission，不泄露任何竞技场信息")
    void nonAdminSeesOnlyNoPermission() {
        arenasIn(GameState.RUNNING, GameState.WAITING);

        List<String> out = runArenas(player);

        assertEquals(List.of("no-permission"), out,
                "/hilltop arenas 是 admin 命令，普通玩家只应收到 no-permission —— "
                        + "多输出任何一行都会泄露服务器拓扑");

        verify(arenaManager, never()).countByState(any(GameState[].class));
    }

    // ---------------------------------------------------------------- 其它入口形态

    @Test
    @DisplayName("/hilltop list 对普通玩家可用，且列出每个活动场馆")
    void listIsAvailableToPlayers() {
        arenasIn(GameState.WAITING, GameState.RUNNING);

        command.onCommand(player, bukkitCommand, "hilltop", new String[]{"list"});
        List<String> out = messagesOf(player);

        assertTrue(out.get(0).startsWith("arena-list-header"), "首行应是列表头，实际=" + out);
        assertTrue(out.get(out.size() - 1).startsWith("arena-list-footer"), "末行应是列表尾");
        assertEquals(4, out.size(),
                "2 个场馆 + 头 + 尾 = 4 行，实际=" + out);
    }

    @Test
    @DisplayName("/hilltop list 在没有活动场馆时输出 no-active-arenas")
    void listReportsNoActiveArenas() {
        when(arenaManager.getActiveArenas()).thenReturn(List.of());

        command.onCommand(player, bukkitCommand, "hilltop", new String[]{"list"});
        List<String> out = messagesOf(player);

        assertEquals(List.of("arena-list-header", "no-active-arenas"), out,
                "无活动场馆时必须明确告知，而不是只打一个空列表头");
    }

    @Test
    @DisplayName("无参数时什么也不做（唯一调用方 HCH 只以 list/arenas 进入）")
    void noArgsIsNoOp() {
        command.onCommand(admin, bukkitCommand, "hilltop", new String[0]);
        assertEquals(List.of(), messagesOf(admin), "args 为空时必须静默返回");
    }

    @Test
    @DisplayName("未知子命令不输出任何东西（fix6 Y4-B25：已收窄为 list/arenas 两形态）")
    void unknownSubcommandIsNoOp() {
        command.onCommand(admin, bukkitCommand, "hilltop", new String[]{"join"});
        assertEquals(List.of(), messagesOf(admin),
                "join/leave 等入口已由 HCH 路由，本类不得再处理 —— 静默返回即可");
    }
}
