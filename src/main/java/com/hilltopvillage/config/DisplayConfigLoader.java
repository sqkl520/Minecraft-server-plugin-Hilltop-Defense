package com.hilltopvillage.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * {@code display.yml} → {@link DisplayConfig} 的纯函数加载器。
 *
 * <p>不碰 Bukkit 单例、不读文件、<b>不打任何日志</b>：所有问题都以英文 warning 字符串返回，
 * 由调用方（{@code ConfigManager}）决定记到哪个 logger、以什么级别记。这样这个类可以
 * 被纯 JUnit5 直接测（{@code YamlConfiguration.loadFromString} + 断言 warning 数量），
 * 也让「加载期校验、渲染期零校验」的分工成立——侧边栏每 0.5 秒走一遍布局，
 * 渲染路径里不能有任何会打日志的分支，否则一行配错会变成每秒一条控制台噪音。</p>
 *
 * <p><b>失败模型是 fail-soft（行级）</b>：单行写错只跳过那一行，板的其余部分照常渲染；
 * 唯一的 fail-hard 是「顶层连 {@code scoreboard} / {@code tab} 两段都没有」——
 * 此时返回 {@link DisplayConfig#defaults()}。空白侧边栏对玩家来说与「插件坏了」不可区分，
 * 所以没有任何一条规则以「渲染空板」收场。</p>
 *
 * <p>校验规则（每条违规 → 一条 warning，见实施规范 §1.3）：</p>
 * <ul>
 *   <li>声明行数超过 15（tab 头/尾超过 8）→ 截断 + warning</li>
 *   <li>行不是 map、四种行型一个都没有、命中多种行型 → 跳过该行（多种行型按优先级取一个）</li>
 *   <li>{@code when} 不在条件闭集 → 保留该行但永不渲染（<b>不</b>降级成 {@code always}：
 *       把拼错的条件当成 always 会让本该隐藏的行显示出来，比少一行更糟）</li>
 *   <li>{@code args} 里出现闭集外的名字 → 跳过该行（值错会让整行变成一列 {@code ?}，
 *       与「管理员加的那行没了」相比后者更难定位）</li>
 *   <li>{@code anim} 帧数不在 [2,8] → 跳过该行</li>
 *   <li>数值越界（separator 宽度 / frame-ticks / refresh-ticks）→ 夹到边界 + warning
 *       （夹取而非跳过：多画几根破折号或刷新快一点不至于让整行消失）</li>
 * </ul>
 */
public final class DisplayConfigLoader {

    private DisplayConfigLoader() {
    }

    /**
     * 合法条件名闭集（小写）。渲染器据此判定「这行该不该出现」；
     * 不在此集合里的名字一律视为永不成立，而不是当成 {@code always}。
     */
    public static final Set<String> CONDITIONS = ordered(
            "always", "waiting", "starting", "running", "intermission", "in-game",
            "finished", "start-countdown", "waiting-for-players", "alive", "spectator");

    /**
     * 合法 {@code args} 名字闭集（<b>大小写敏感</b>，恰为下列拼写）。
     *
     * <p>名字只活在 schema 层：加载器把它们编译成有序的位置列表，渲染器按名字取值后
     * 仍然调用 {@code LanguageManager.getFor(player, key, args...)}——messages.yml 里
     * 的 {@code {0}{1}} 语义一个字都不用改。</p>
     */
    public static final Set<String> ARGS = ordered(
            "wave", "totalWaves", "alivePlayers", "spectators", "activeNodes", "totalNodes",
            "aliveVillagers", "totalVillagers", "playerKills", "playerDeaths", "playerCrystals",
            "startCountdown", "playerCount", "minPlayers", "remainingMobs",
            "nextWaveSeconds", "arenaName", "gameState");

    /** 侧边栏声明行数硬上限（Minecraft 侧边栏可显示的极限）。 */
    public static final int MAX_BOARD_LINES = 15;
    /** tab 头/尾各自的行数上限（信息型上限，不是协议上限）。 */
    public static final int MAX_TAB_LINES = 8;
    /** {@code separator} 宽度范围。 */
    public static final int MIN_SEPARATOR_WIDTH = 1;
    public static final int MAX_SEPARATOR_WIDTH = 40;
    /** {@code anim} 帧数范围。 */
    public static final int MIN_ANIM_FRAMES = 2;
    public static final int MAX_ANIM_FRAMES = 8;
    /** {@code frame-ticks} 范围。 */
    public static final int MIN_FRAME_TICKS = 1;
    public static final int MAX_FRAME_TICKS = 200;
    /** {@code scoreboard.refresh-ticks} 范围。 */
    public static final int MIN_BOARD_REFRESH_TICKS = 5;
    public static final int MAX_BOARD_REFRESH_TICKS = 200;
    /** {@code tab.refresh-ticks} 范围。 */
    public static final int MIN_TAB_REFRESH_TICKS = 1;
    public static final int MAX_TAB_REFRESH_TICKS = 200;
    /** {@code frame-ticks} 缺省值。 */
    public static final int DEFAULT_FRAME_TICKS = 10;

    /**
     * 加载结果。
     *
     * @param config   永远非 {@code null}：最坏情况是 {@link DisplayConfig#defaults()}
     * @param warnings 英文 warning 清单（不可变）；正常时为不可变空列表
     */
    public record Result(DisplayConfig config, List<String> warnings) {
        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * 解析一份 {@code display.yml}。
     *
     * @param yaml 已解析的配置；允许为 {@code null}（视同整份缺失）
     * @return 配置 + warning 清单；任何情况下 {@code config} 都可用
     */
    public static Result parse(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        if (yaml == null) {
            warnings.add("display.yml is null; falling back to the built-in default layout");
            return new Result(DisplayConfig.defaults(), warnings);
        }
        boolean hasBoard = yaml.isConfigurationSection("scoreboard");
        boolean hasTab = yaml.isConfigurationSection("tab");
        if (!hasBoard && !hasTab) {
            // 顶层结构不可用（空文件、截断成标量、两段都被删）：整份回退，但绝不能空白。
            warnings.add("display.yml has neither a 'scoreboard' nor a 'tab' section; "
                    + "falling back to the built-in default layout");
            return new Result(DisplayConfig.defaults(), warnings);
        }

        DisplayConfig defaults = DisplayConfig.defaults();
        DisplayConfig.Board board = hasBoard
                ? parseBoard(yaml.getConfigurationSection("scoreboard"), warnings)
                : defaults.scoreboard();
        DisplayConfig.Tab tab = hasTab
                ? parseTab(yaml.getConfigurationSection("tab"), warnings)
                : defaults.tab();
        return new Result(new DisplayConfig(board, tab), warnings);
    }

    // ------------------------------------------------------------------ scoreboard

    private static DisplayConfig.Board parseBoard(ConfigurationSection section, List<String> warnings) {
        boolean enabled = bool(section, "enabled", true, "scoreboard.enabled", warnings);

        String titleKey = section.getString("title");
        if (titleKey == null || titleKey.isBlank()) {
            if (section.isSet("title")) {
                warnings.add("display.yml scoreboard.title is empty or not a string; "
                        + "using '" + DisplayConfig.DEFAULT_TITLE_KEY + "'");
            }
            titleKey = DisplayConfig.DEFAULT_TITLE_KEY;
        }

        int refreshTicks = clampInt(section, "refresh-ticks",
                DisplayConfig.DEFAULT_SCOREBOARD_REFRESH_TICKS,
                MIN_BOARD_REFRESH_TICKS, MAX_BOARD_REFRESH_TICKS,
                "scoreboard.refresh-ticks", warnings);

        List<DisplayConfig.Line> lines;
        if (!section.contains("lines")) {
            // 键不存在 = 「恢复出厂布局」。与「显式空列表」（= 只留标题）必须区分：
            // 把两者合并会让「我想清空侧边栏」变成「我想恢复默认」，是个会反复被踩的坑。
            lines = DisplayConfig.defaultLines();
        } else if (!section.isList("lines")) {
            warnings.add("display.yml scoreboard.lines is not a list; using the built-in default layout");
            lines = DisplayConfig.defaultLines();
        } else {
            lines = parseLines(section.getList("lines"), "scoreboard.lines",
                    MAX_BOARD_LINES, warnings);
        }
        return new DisplayConfig.Board(enabled, titleKey, refreshTicks, lines);
    }

    // ------------------------------------------------------------------ tab

    private static DisplayConfig.Tab parseTab(ConfigurationSection section, List<String> warnings) {
        boolean enabled = bool(section, "enabled", true, "tab.enabled", warnings);
        int refreshTicks = clampInt(section, "refresh-ticks",
                DisplayConfig.DEFAULT_TAB_REFRESH_TICKS,
                MIN_TAB_REFRESH_TICKS, MAX_TAB_REFRESH_TICKS,
                "tab.refresh-ticks", warnings);

        // contexts 缺省全开：老配置里没有这个段时行为与「全部接管」一致。
        //
        // 但「写成标量」是另一回事：`contexts: false`（想整体关闭，却漏了缩进）会让
        // get("contexts.lobby") 取默认值、isSet 也为 false，三个开关静默全开——
        // 行为与意图正好相反且零告警。显式检出来。
        if (section.isSet("contexts") && !section.isConfigurationSection("contexts")) {
            warnings.add("display.yml tab.contexts must be a section with lobby/arena/spectator keys; "
                    + "the scalar value is ignored and all three default to true");
        }
        boolean lobby = bool(section, "contexts.lobby", true, "tab.contexts.lobby", warnings);
        boolean arena = bool(section, "contexts.arena", true, "tab.contexts.arena", warnings);
        boolean spectator = bool(section, "contexts.spectator", true, "tab.contexts.spectator", warnings);

        List<DisplayConfig.Line> header = parseOptionalLines(section, "header",
                "tab.header", warnings);
        List<DisplayConfig.Line> footer = parseOptionalLines(section, "footer",
                "tab.footer", warnings);

        String nameAlive = nameKey(section, "player-name.alive", "tab.name.alive", warnings);
        String nameDead = nameKey(section, "player-name.dead", "tab.name.dead", warnings);
        String nameSpectator = nameKey(section, "player-name.spectator", "tab.name.spectator", warnings);

        return new DisplayConfig.Tab(enabled, refreshTicks, lobby, arena, spectator,
                header, footer, nameAlive, nameDead, nameSpectator);
    }

    private static List<DisplayConfig.Line> parseOptionalLines(ConfigurationSection section,
                                                              String key, String path,
                                                              List<String> warnings) {
        if (!section.contains(key)) {
            return List.of();
        }
        if (!section.isList(key)) {
            warnings.add("display.yml " + path + " is not a list; using an empty " + path);
            return List.of();
        }
        return parseLines(section.getList(key), path, MAX_TAB_LINES, warnings);
    }

    private static String nameKey(ConfigurationSection section, String key, String fallback,
                                  List<String> warnings) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            if (section.isSet(key)) {
                warnings.add("display.yml tab." + key + " is empty or not a string; using '"
                        + fallback + "'");
            }
            return fallback;
        }
        return value;
    }

    // ------------------------------------------------------------------ lines

    /**
     * 解析一段行列表。声明长度超限时<b>先截断再解析</b>——上限是「声明数」上限，
     * 用 {@code when} 让多行互斥、实际渲染更少是正常用法。
     */
    private static List<DisplayConfig.Line> parseLines(List<?> raw, String path, int limit,
                                                       List<String> warnings) {
        if (raw == null) {
            return List.of();
        }
        List<?> declared = raw;
        if (declared.size() > limit) {
            warnings.add("display.yml " + path + ": " + declared.size() + " declared line(s) exceeds "
                    + "the limit of " + limit + "; only the first " + limit + " are used");
            declared = declared.subList(0, limit);
        }
        List<DisplayConfig.Line> out = new ArrayList<>(declared.size());
        for (int i = 0; i < declared.size(); i++) {
            // 1-based 序号：与管理员在文件里从第一行往下数的位置一致。
            String at = path + "[" + (i + 1) + "]";
            DisplayConfig.Line line = parseLine(declared.get(i), at, warnings);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /** 单行解析；返回 {@code null} 表示跳过该行（warning 已写入）。 */
    private static DisplayConfig.Line parseLine(Object entry, String at, List<String> warnings) {
        // SnakeYAML 直接用 loadFromString 时列表项是 LinkedHashMap；若调用方给的是
        // 已被 Bukkit 包装过的段（例如从 ConfigurationSection 转出来的），也要能读。
        Map<?, ?> map;
        if (entry instanceof Map<?, ?> raw) {
            map = raw;
        } else if (entry instanceof ConfigurationSection section) {
            map = section.getValues(false);
        } else {
            warnings.add("display.yml " + at + ": entry is not a map; line skipped");
            return null;
        }

        Number separatorRaw = number(map, "separator");
        boolean separatorPresent = has(map, "separator");
        boolean blankPresent = Boolean.TRUE.equals(map.get("blank"));
        boolean animPresent = has(map, "anim");
        boolean keyPresent = has(map, "key");

        // 行型优先级 separator > blank > anim > key（与实施规范 §1.3 一致）。
        // 「命中多种」本身不是错误，但必须告警：管理员很可能多写了一行字段。
        int kinds = (separatorPresent ? 1 : 0) + (blankPresent ? 1 : 0)
                + (animPresent ? 1 : 0) + (keyPresent ? 1 : 0);
        if (kinds == 0) {
            warnings.add("display.yml " + at + ": no line kind (separator / blank / key / anim); "
                    + "line skipped");
            return null;
        }
        if (kinds > 1) {
            // 点名最终采用了哪一个：只列「声明了哪些」时，管理员还得回查 §1.3 的优先级表
            // 才知道哪个字段被丢弃了。
            String winning = separatorPresent ? "separator"
                    : blankPresent ? "blank"
                    : animPresent ? "anim" : "key";
            warnings.add("display.yml " + at + ": multiple line kinds declared ("
                    + String.join(", ", declaredKinds(separatorPresent, blankPresent, animPresent, keyPresent))
                    + "); '" + winning + "' wins, the others are ignored");
        }

        String when = parseWhen(map, at, warnings);

        if (separatorPresent) {
            if (separatorRaw == null) {
                // 不直接 return null：如果同一行还写了合法的 key/anim，被丢弃的只是 separator，
                // 整行不该跟着消失。仅在「没有可降级的行型」时才跳过该行。
                if (keyPresent || animPresent || blankPresent) {
                    warnings.add("display.yml " + at + ": separator is not a number; "
                            + "falling back to the next declared line kind");
                } else {
                    warnings.add("display.yml " + at + ": separator is not a number; line skipped");
                    return null;
                }
            } else {
                int width = separatorRaw.intValue();
                if (width < MIN_SEPARATOR_WIDTH || width > MAX_SEPARATOR_WIDTH) {
                    // 夹取而不是跳过：多画/少画几根破折号不至于让整行消失，管理员也能从 warning
                    // 看到自己的 999 变成了 40。
                    int clamped = Math.max(MIN_SEPARATOR_WIDTH, Math.min(MAX_SEPARATOR_WIDTH, width));
                    warnings.add("display.yml " + at + ": separator width " + width
                            + " is out of range [" + MIN_SEPARATOR_WIDTH + "," + MAX_SEPARATOR_WIDTH
                            + "]; clamped to " + clamped);
                    width = clamped;
                }
                return new DisplayConfig.Line(DisplayConfig.LineKind.SEPARATOR, "", List.of(), when,
                        width, List.of(), 0);
            }
        }

        if (blankPresent) {
            return new DisplayConfig.Line(DisplayConfig.LineKind.BLANK, "", List.of(), when,
                    0, List.of(), 0);
        }

        List<String> args = parseArgs(map, at, warnings);
        if (args == null) {
            return null;
        }

        if (animPresent) {
            List<String> frames = parseFrames(map.get("anim"), at, warnings);
            if (frames == null) {
                return null;
            }
            int frameTicks = clampInt(map, "frame-ticks", DEFAULT_FRAME_TICKS,
                    MIN_FRAME_TICKS, MAX_FRAME_TICKS, at + ".frame-ticks", warnings);
            return new DisplayConfig.Line(DisplayConfig.LineKind.ANIM, "", args, when,
                    0, frames, frameTicks);
        }

        Object keyRaw = map.get("key");
        if (!(keyRaw instanceof String key) || key.isBlank()) {
            warnings.add("display.yml " + at + ": key is missing, not a string, or blank; line skipped");
            return null;
        }
        return DisplayConfig.Line.staticLine(key, args, when);
    }

    private static List<String> declaredKinds(boolean separator, boolean blank,
                                              boolean anim, boolean key) {
        List<String> names = new ArrayList<>(4);
        if (separator) names.add("separator");
        if (blank) names.add("blank");
        if (anim) names.add("anim");
        if (key) names.add("key");
        return names;
    }

    /**
     * 条件名：大小写不敏感，存储时统一小写。
     *
     * <p>不在 {@link #CONDITIONS} 里的名字<b>保留原样</b>（不替换成 {@code always}），
     * 渲染器对闭集外的名字一律判 false——该行永不显示，但仍在列表里，管理员数行数时
     * 不会「我写了 12 行怎么只剩 11 行」。</p>
     */
    private static String parseWhen(Map<?, ?> map, String at, List<String> warnings) {
        Object raw = map.get("when");
        if (raw == null) {
            return "always";
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (!CONDITIONS.contains(value)) {
            warnings.add("display.yml " + at + ": unknown condition '" + value + "'; valid: "
                    + String.join(", ", CONDITIONS) + ". The line will never render");
        }
        return value;
    }

    /**
     * args 名：大小写敏感。任一名字不在闭集 → 整行跳过（返回 {@code null}）。
     *
     * <p>{@code args} 缺省时返回空列表——规范绑定表由渲染层按 key 补，
     * 加载器不重复维护一份键→args 的映射。</p>
     */
    private static List<String> parseArgs(Map<?, ?> map, String at, List<String> warnings) {
        Object raw = map.get("args");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            // 与「未知 arg」同策略：跳过该行，而不是保留一行必然渲染成字面量 {0}/{1} 的文本。
            // 后者比整行消失更难被认出是配置错误——玩家看到的是 "Wave: {0}/{1}"。
            warnings.add("display.yml " + at + ": args is not a list; line skipped "
                    + "(wrap them in brackets: args: [wave, totalWaves])");
            return null;
        }
        List<String> args = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String name)) {
                warnings.add("display.yml " + at + ": args contains a non-string entry '" + item
                        + "'; line skipped");
                return null;
            }
            if (!ARGS.contains(name)) {
                warnings.add("display.yml " + at + ": unknown arg '" + name + "'; valid: "
                        + String.join(", ", ARGS) + ". Line skipped");
                return null;
            }
            args.add(name);
        }
        return args;
    }

    /**
     * 动画帧：只收字符串项；过滤后不足 2 帧或超过 8 帧 → 跳过该行。
     *
     * <p>与 {@code 05-config-schema-design} 的「1 帧退化成静态、多于 8 帧截断」相比，
     * 这里按实施规范 §1.3 取更严格的「跳过 + WARN」：半截动画（少了几帧）在玩家侧
     * 看起来像卡住，比整行消失更难诊断。</p>
     */
    private static List<String> parseFrames(Object raw, String at, List<String> warnings) {
        if (!(raw instanceof List<?> list)) {
            warnings.add("display.yml " + at + ": anim is not a list; line skipped");
            return null;
        }
        List<String> frames = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String frame && !frame.isBlank()) {
                frames.add(frame);
            } else {
                warnings.add("display.yml " + at + ": anim contains a non-string or blank frame '"
                        + item + "'; the frame is dropped");
            }
        }
        if (frames.size() < MIN_ANIM_FRAMES) {
            warnings.add("display.yml " + at + ": anim has " + frames.size() + " usable frame(s), "
                    + "needs at least " + MIN_ANIM_FRAMES + "; line skipped");
            return null;
        }
        if (frames.size() > MAX_ANIM_FRAMES) {
            warnings.add("display.yml " + at + ": anim has " + frames.size() + " frames, exceeds "
                    + MAX_ANIM_FRAMES + "; line skipped");
            return null;
        }
        return frames;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean has(Map<?, ?> map, String key) {
        return map.containsKey(key);
    }

    private static Number number(Map<?, ?> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Number number ? number : null;
    }

    /**
     * 读布尔值：缺省用 {@code fallback}；存在但不是布尔 → 用 {@code fallback} + warning。
     */
    private static boolean bool(ConfigurationSection section, String path, boolean fallback,
                                String label, List<String> warnings) {
        if (!section.isSet(path)) {
            return fallback;
        }
        Object raw = section.get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        warnings.add("display.yml " + label + " is not a boolean ('" + raw
                + "'); using " + fallback);
        return fallback;
    }

    /** 读整数并夹到 [min,max]；缺省用 {@code fallback}，非数字 → {@code fallback} + warning。 */
    private static int clampInt(ConfigurationSection section, String path, int fallback,
                                int min, int max, String label, List<String> warnings) {
        if (!section.isSet(path)) {
            return fallback;
        }
        Object raw = section.get(path);
        if (!(raw instanceof Number number)) {
            warnings.add("display.yml " + label + " is not a number ('" + raw
                    + "'); using " + fallback);
            return fallback;
        }
        return clamp(number.intValue(), min, max, label, warnings);
    }

    /** 同上，但输入是行列表里的一个 map 字段。 */
    private static int clampInt(Map<?, ?> map, String key, int fallback,
                                int min, int max, String label, List<String> warnings) {
        Object raw = map.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            warnings.add("display.yml " + label + " is not a number ('" + raw
                    + "'); using " + fallback);
            return fallback;
        }
        return clamp(number.intValue(), min, max, label, warnings);
    }

    private static int clamp(int value, int min, int max,
                             String label, List<String> warnings) {
        if (value < min) {
            warnings.add("display.yml " + label + ": " + value + " is out of range ["
                    + min + "," + max + "]; clamped to " + min);
            return min;
        }
        if (value > max) {
            warnings.add("display.yml " + label + ": " + value + " is out of range ["
                    + min + "," + max + "]; clamped to " + max);
            return max;
        }
        return value;
    }

    /** 保持声明顺序的不可变集合（warning 里按这个顺序列出合法名字，读起来稳定）。 */
    private static Set<String> ordered(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(names)));
    }
}
