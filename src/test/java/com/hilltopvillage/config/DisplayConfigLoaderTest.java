package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DisplayConfigLoader} 的行为测试。
 *
 * <p>核心断言不是「正常配置能读」，而是<b>「写错一行不会让整块板消失」</b>：
 * 每条 fail-soft 规则都配一个「其它行仍然在」的断言，每条夹取规则都同时断言
 * 「值被夹到边界」与「确实有一条 warning」——只夹不提，管理员永远不知道自己的 999 变成了 40。</p>
 *
 * <p>纯 JUnit5：用 {@code YamlConfiguration.loadFromString} 造配置，不加载 Bukkit 运行时。
 * 第 1 个用例刻意从 JAR 资源里读 {@code /display.yml}，这样出厂模板写坏了会在 CI 里
 * 立刻暴露，而不是等到某个服主升级后发现侧边栏变空白。</p>
 */
class DisplayConfigLoaderTest {

    private static YamlConfiguration yaml(String raw) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(raw);
        } catch (org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new AssertionError("test fixture is not valid YAML", failure);
        }
        return config;
    }

    /** 只有一行的最简配置，用来隔离单条校验规则。 */
    private static YamlConfiguration board(String linesBlock) {
        return yaml("scoreboard:\n"
                + "  enabled: true\n"
                + "  refresh-ticks: 10\n"
                + "  lines:\n" + linesBlock);
    }

    private static DisplayConfigLoader.Result parseBoard(String linesBlock) {
        return DisplayConfigLoader.parse(board(linesBlock));
    }

    private static boolean anyWarningContains(DisplayConfigLoader.Result result, String needle) {
        return result.warnings().stream().anyMatch(w -> w.contains(needle));
    }

    // ---------------------------------------------------------------- 出厂模板

    @Test
    @DisplayName("出厂 display.yml 零 warning，且复刻今天的 10 行顺序")
    void shippedTemplateParsesCleanly() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/display.yml")) {
            assertNotNull(stream, "display.yml must be packaged into the JAR");
            YamlConfiguration config = yaml(new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            DisplayConfigLoader.Result result = DisplayConfigLoader.parse(config);

            assertEquals(List.of(), result.warnings(),
                    "the shipped template must never produce warnings");
            List<DisplayConfig.Line> lines = result.config().scoreboard().lines();
            assertEquals(10, lines.size(), "the factory layout must keep today's 10 lines");
            assertEquals(List.of(
                            "scoreboard.wave", "scoreboard.alive", "scoreboard.starting",
                            "scoreboard.waiting", "scoreboard.spectators", "scoreboard.nodes",
                            "scoreboard.villagers", "scoreboard.kills", "scoreboard.deaths",
                            "scoreboard.crystals"),
                    lines.stream().map(DisplayConfig.Line::i18nKey).toList());
            assertEquals(List.of("in-game", "in-game", "start-countdown", "waiting-for-players",
                            "in-game", "in-game", "in-game", "alive", "alive", "alive"),
                    lines.stream().map(DisplayConfig.Line::when).toList());
            assertEquals(10, result.config().scoreboard().refreshTicks());
            assertTrue(result.config().tab().enabled());
            assertFalse(result.config().tab().header().isEmpty());
            assertFalse(result.config().tab().footer().isEmpty());
        }
    }

    @Test
    @DisplayName("defaults() 有内容，且能按行型/键/条件原样解析回来（默认布局只定义一次）")
    void defaultsRoundTrip() {
        DisplayConfig defaults = DisplayConfig.defaults();
        assertFalse(defaults.scoreboard().lines().isEmpty(), "defaults must never be an empty board");
        assertFalse(defaults.tab().header().isEmpty());

        // 把 defaults() 的行原样喂回 loader：形状必须一致（证明两份来源没有漂移）。
        List<Map<String, Object>> serialised = new ArrayList<>();
        for (DisplayConfig.Line line : defaults.scoreboard().lines()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", line.i18nKey());
            entry.put("args", line.args());
            entry.put("when", line.when());
            serialised.add(entry);
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("scoreboard.lines", serialised);

        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(config);

        assertEquals(List.of(), result.warnings());
        assertEquals(defaults.scoreboard().lines(), result.config().scoreboard().lines());
    }

    // ---------------------------------------------------------------- fail-soft 行级规则

    @Test
    @DisplayName("声明 16 行 → 截断到 15 行 + warning，前 15 行保序")
    void tooManyLinesTruncated() {
        // 每行给<b>不同</b>的 key：全部写成同一个字面量时，「保留前 15 行」与「保留后 15 行」
        // 得到完全相同的 List，截断方向不可观测（变异为 subList(size-limit, size) 也全绿）。
        // loader 不校验 key 是否存在于 messages.yml，只要求非空字符串，故用序号后缀即可。
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            block.append("    - key: scoreboard.line").append(i).append('\n');
        }

        DisplayConfigLoader.Result result = parseBoard(block.toString());

        List<DisplayConfig.Line> lines = result.config().scoreboard().lines();
        assertEquals(15, lines.size());
        // 前 15 行保序：i = 0..14，第 16 行（i = 15）被丢弃。
        assertEquals(java.util.stream.IntStream.range(0, 15)
                        .mapToObj(i -> "scoreboard.line" + i).toList(),
                lines.stream().map(DisplayConfig.Line::i18nKey).toList(),
                "truncation must keep the FIRST 15 declared lines, in order");
        assertTrue(anyWarningContains(result, "exceeds the limit of 15"));
    }

    @Test
    @DisplayName("行不是 map → 跳过该行 + warning，其它行保留")
    void nonMapLineSkipped() {
        DisplayConfigLoader.Result result = parseBoard(
                "    - just-a-string\n"
                        + "    - key: scoreboard.alive\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        assertEquals("scoreboard.alive", result.config().scoreboard().lines().get(0).i18nKey());
        assertTrue(anyWarningContains(result, "entry is not a map"));
    }

    @Test
    @DisplayName("同时写 key 与 separator → 取 separator + warning")
    void multipleKindsResolveByPriority() {
        DisplayConfigLoader.Result result = parseBoard(
                "    - key: scoreboard.alive\n"
                        + "      separator: 12\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        DisplayConfig.Line line = result.config().scoreboard().lines().get(0);
        assertEquals(DisplayConfig.LineKind.SEPARATOR, line.kind());
        assertEquals(12, line.separatorWidth());
        assertTrue(anyWarningContains(result, "multiple line kinds"));
    }

    @Test
    @DisplayName("四种行型一个都没有 → 跳过 + warning")
    void noKindSkipped() {
        DisplayConfigLoader.Result result = parseBoard(
                "    - args: [wave]\n"
                        + "    - key: scoreboard.alive\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        assertTrue(anyWarningContains(result, "no line kind"));
    }

    @Test
    @DisplayName("未知 when → 行保留（不跳过、不降级成 always）+ warning")
    void unknownConditionIsKeptButNeverMatches() {
        DisplayConfigLoader.Result result = parseBoard("    - key: scoreboard.kills\n"
                + "      when: alive_plyer\n");

        assertEquals(1, result.config().scoreboard().lines().size(), "unknown when must not drop the line");
        String when = result.config().scoreboard().lines().get(0).when();
        assertEquals("alive_plyer", when);
        assertFalse("always".equals(when), "a typo must never be upgraded to 'always'");
        assertFalse(DisplayConfigLoader.CONDITIONS.contains(when));
        assertTrue(anyWarningContains(result, "unknown condition"));
    }

    @Test
    @DisplayName("when 大小写不敏感 → 存成小写")
    void conditionIsLowercased() {
        DisplayConfigLoader.Result result = parseBoard("    - key: scoreboard.kills\n"
                + "      when: IN-GAME\n");

        assertEquals(List.of(), result.warnings());
        assertEquals("in-game", result.config().scoreboard().lines().get(0).when());
    }

    @Test
    @DisplayName("未知 args 名字 → 跳过该行 + warning（大小写敏感）")
    void unknownArgSkipsLine() {
        DisplayConfigLoader.Result result = parseBoard(
                "    - key: scoreboard.wave\n"
                        + "      args: [wave, totalwaves]\n"
                        + "    - key: scoreboard.alive\n"
                        + "      args: [alivePlayers]\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        assertEquals("scoreboard.alive", result.config().scoreboard().lines().get(0).i18nKey());
        assertTrue(anyWarningContains(result, "unknown arg 'totalwaves'"));
    }

    @Test
    @DisplayName("anim 只有 1 帧 → 跳过该行 + warning")
    void singleFrameAnimSkipped() {
        DisplayConfigLoader.Result result = parseBoard(
                "    - anim: [scoreboard.kills]\n"
                        + "    - key: scoreboard.alive\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        assertTrue(anyWarningContains(result, "needs at least 2"));
    }

    @Test
    @DisplayName("anim 合法 → 帧数与 frame-ticks 原样保留")
    void validAnimKept() {
        DisplayConfigLoader.Result result = parseBoard("    - anim: [scoreboard.kills, scoreboard.deaths]\n"
                + "      frame-ticks: 40\n");

        assertEquals(List.of(), result.warnings());
        DisplayConfig.Line line = result.config().scoreboard().lines().get(0);
        assertEquals(DisplayConfig.LineKind.ANIM, line.kind());
        assertEquals(List.of("scoreboard.kills", "scoreboard.deaths"), line.animFrames());
        assertEquals(40, line.frameTicks());
    }

    // ---------------------------------------------------------------- 夹取规则

    @Test
    @DisplayName("frame-ticks: 0 → 夹到 1 + warning")
    void frameTicksClampedLow() {
        DisplayConfigLoader.Result result = parseBoard("    - anim: [scoreboard.kills, scoreboard.deaths]\n"
                + "      frame-ticks: 0\n");

        assertEquals(1, result.config().scoreboard().lines().get(0).frameTicks());
        assertTrue(anyWarningContains(result, "clamped to 1"));
    }

    @Test
    @DisplayName("separator: 999 → 夹到 40 + warning")
    void separatorClampedHigh() {
        DisplayConfigLoader.Result result = parseBoard("    - separator: 999\n");

        assertEquals(40, result.config().scoreboard().lines().get(0).separatorWidth());
        assertTrue(anyWarningContains(result, "clamped to 40"));
    }

    @Test
    @DisplayName("separator: 0 → 夹到 1 + warning（夹取，不丢行）")
    void separatorClampedLow() {
        DisplayConfigLoader.Result result = parseBoard("    - separator: 0\n");

        assertEquals(1, result.config().scoreboard().lines().size());
        assertEquals(1, result.config().scoreboard().lines().get(0).separatorWidth());
        assertTrue(anyWarningContains(result, "clamped to 1"));
    }

    @Test
    @DisplayName("refresh-ticks: 1 → 夹到 5 + warning")
    void refreshTicksClamped() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  refresh-ticks: 1\n"
                + "  lines: []\n"));

        assertEquals(5, result.config().scoreboard().refreshTicks());
        assertTrue(anyWarningContains(result, "clamped to 5"));
    }

    // ---------------------------------------------------------------- 板级 / 结构

    @Test
    @DisplayName("顶层既无 scoreboard 也无 tab → defaults() + warning")
    void emptyTopLevelFallsBackToDefaults() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml(""));

        assertTrue(anyWarningContains(result, "neither a 'scoreboard' nor a 'tab' section"));
        assertEquals(DisplayConfig.defaults().scoreboard().lines(),
                result.config().scoreboard().lines());
        assertFalse(result.config().scoreboard().lines().isEmpty(),
                "a broken file must never produce a blank sidebar");
    }

    @Test
    @DisplayName("lines: [] 是合法意图（只留标题），不回退默认布局")
    void explicitEmptyLinesIsHonoured() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  enabled: true\n"
                + "  lines: []\n"));

        assertEquals(List.of(), result.warnings());
        assertEquals(List.of(), result.config().scoreboard().lines());
    }

    @Test
    @DisplayName("缺 lines 段 → 回退内置默认布局（与显式空列表区分）")
    void missingLinesFallsBackToDefaultLayout() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  enabled: false\n"));

        assertEquals(10, result.config().scoreboard().lines().size());
        assertFalse(result.config().scoreboard().enabled(), "the switch itself must still be read");
    }

    @Test
    @DisplayName("tab.header 写 9 行 → 截断到 8 行 + warning")
    void tabHeaderTruncatedToEight() {
        StringBuilder block = new StringBuilder("tab:\n  header:\n");
        for (int i = 0; i < 9; i++) {
            block.append("    - key: tab.header.title\n");
        }

        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml(block.toString()));

        assertEquals(8, result.config().tab().header().size());
        assertTrue(anyWarningContains(result, "exceeds the limit of 8"));
    }

    @Test
    @DisplayName("只有 tab 段时，scoreboard 回退默认布局而不是空")
    void tabOnlyFileKeepsDefaultBoard() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("tab:\n"
                + "  enabled: true\n"
                + "  header:\n"
                + "    - key: tab.header.title\n"));

        assertEquals(10, result.config().scoreboard().lines().size());
        assertEquals(1, result.config().tab().header().size());
    }

    @Test
    @DisplayName("公开常量：条件与 args 闭集就是规范里的那两组名字（逐名集合相等）")
    void constantsMatchSpec() {
        // 断言<b>集合相等</b>而不是基数：改名（spectator → spectat0r）不改变基数，
        // 但会让侧边栏那一行永不显示 —— 一个可观测的功能回退，而计数断言全绿。
        assertEquals(
                java.util.Set.of("always", "waiting", "starting", "running", "intermission",
                        "in-game", "finished", "start-countdown", "waiting-for-players",
                        "alive", "spectator"),
                DisplayConfigLoader.CONDITIONS,
                "the condition closed set is the spec's name list, not merely 11 of something");
        assertEquals(
                java.util.Set.of("wave", "totalWaves", "alivePlayers", "spectators",
                        "activeNodes", "totalNodes", "aliveVillagers", "totalVillagers",
                        "playerKills", "playerDeaths", "playerCrystals", "startCountdown",
                        "playerCount", "minPlayers", "remainingMobs", "nextWaveSeconds",
                        "arenaName", "gameState"),
                DisplayConfigLoader.ARGS,
                "the args closed set is the spec's name list, not merely 18 of something");
        assertFalse(DisplayConfigLoader.ARGS.contains("nextwavecount"), "args are case-sensitive");
    }

    @Test
    @DisplayName("tab.contexts 写成标量（漏缩进）时必须告警，而不是静默全开")
    void scalarContextsWarnsInsteadOfSilentlyEnablingAll() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("tab:\n"
                + "  enabled: true\n"
                + "  contexts: false\n"));

        // 三个开关仍然全开（安全的默认），但必须有一条 warning 指出写法错了——
        // 否则 `contexts: false` 这个「想整体关闭」的意图会被静默反转。
        assertTrue(result.config().tab().lobby());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tab.contexts")),
                "expected a warning about the scalar tab.contexts, got: " + result.warnings());
    }

    @Test
    @DisplayName("args 漏方括号时跳过该行，而不是渲染出字面量 {0}")
    void nonListArgsSkipsTheLine() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  lines:\n"
                + "    - key: scoreboard.wave\n"
                + "      args: wave, totalWaves\n"));

        // 该行被<b>跳过</b>（不是退回默认布局）：`lines` 段存在且是合法列表，走 parseLines；
        // 单行内 args 不是列表 → parseArgs 返回 null → 该行丢弃。故结果行数 = 0，
        // 而不是 defaultLines() 的 10 行。这条断言是注释与实现之间的唯一桥梁。
        assertEquals(0, result.config().scoreboard().lines().size(),
                "a non-list args block skips just that line; the board must end up with 0 lines "
                        + "(not the 10-line default layout)");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("args is not a list")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("line skipped")));
    }

    @Test
    @DisplayName("separator 值无效但同一行有 key 时，降级到 key 而不是整行消失")
    void invalidSeparatorFallsBackToKey() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  lines:\n"
                + "    - key: scoreboard.alive\n"
                + "      separator: auto\n"));

        List<DisplayConfig.Line> lines = result.config().scoreboard().lines();
        assertEquals(1, lines.size(), "the line must survive via its key definition");
        assertEquals(DisplayConfig.LineKind.STATIC, lines.get(0).kind());
        assertEquals("scoreboard.alive", lines.get(0).i18nKey());
    }

    @Test
    @DisplayName("多行型告警点名最终采用哪一个")
    void multipleKindsWarningNamesTheWinner() {
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(yaml("scoreboard:\n"
                + "  lines:\n"
                + "    - key: scoreboard.alive\n"
                + "      separator: 12\n"));

        assertTrue(result.warnings().stream().anyMatch(w ->
                        w.contains("separator") && w.contains("wins")),
                "expected the warning to name the winning kind, got: " + result.warnings());
    }
}
