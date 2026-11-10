package com.hilltopvillage.config;

import java.util.List;

/**
 * 展示配置（{@code display.yml}）的不可变值对象。
 *
 * <p>这里只放<b>已经校验、可以直接渲染</b>的数据：行型归一化、条件名小写化、数值夹取
 * 全部在 {@link DisplayConfigLoader} 完成，渲染端（{@code ArenaScoreboard} /
 * {@code TabListManager}）不再做任何校验，也不再打日志。这样做的原因是侧边栏每 0.5 秒
 * 就要走一遍布局，任何「渲染时才发现配错」的分支都会变成每秒一次的控制台噪音。</p>
 *
 * <p>本类刻意<b>不实现</b> {@code equals}/{@code hashCode}：它不进任何以它为键的集合，
 * 也不参与 {@code GameConfig} 的 GUI 脏检查（那是 {@code game-settings.yml} 的模型，
 * 布局是另一个文件的模型，两套编辑语义必须隔离）。</p>
 *
 * <p>列表字段在 record 的规范构造器里一律 {@link List#copyOf} 成不可变副本——
 * 调用方（或 Bukkit 的 {@code getMapList}）持有的可变列表之后被改动，
 * 不会反过来影响已经生效的布局。</p>
 */
public final class DisplayConfig {

    /** 行型。优先级 {@code SEPARATOR > BLANK > ANIM > STATIC}，由加载器归一化。 */
    public enum LineKind {
        /** 分隔线：渲染为 {@code separatorWidth} 个破折号。 */
        SEPARATOR,
        /** 空行：纯间距。 */
        BLANK,
        /** 静态行：文本 = {@code i18nKey} 在 messages.yml 里的模板。 */
        STATIC,
        /** 动画行：{@code animFrames} 里的 i18n 键按 {@code frameTicks} 循环。 */
        ANIM
    }

    /**
     * 一行布局。
     *
     * <p>{@code when} 恒为小写（加载器统一小写化，条件名大小写不敏感）；
     * 未知条件被保留成原样而<b>不是</b>替换成 {@code always}——把拼错的条件降级成
     * {@code always} 会让本该隐藏的行（例如观战者的「你的击杀」）显示出来，比少一行更糟。</p>
     *
     * @param kind            行型（决定其余字段哪个有效）
     * @param i18nKey         {@code STATIC} 的键；{@code SEPARATOR}/{@code BLANK} 为空串
     * @param args            该行 {@code {0}{1}} 的取值来源名，顺序即占位符位置
     * @param when            可见条件名（已小写）；未知名字永不匹配
     * @param separatorWidth  仅 {@code SEPARATOR}：[1,40]
     * @param animFrames      仅 {@code ANIM}：2-8 个 i18n 键
     * @param frameTicks      仅 {@code ANIM}：每帧 tick 数 [1,200]
     */
    public record Line(LineKind kind, String i18nKey, List<String> args,
                       String when, int separatorWidth, List<String> animFrames,
                       int frameTicks) {

        public Line {
            args = args == null ? List.of() : List.copyOf(args);
            animFrames = animFrames == null ? List.of() : List.copyOf(animFrames);
            if (i18nKey == null) {
                i18nKey = "";
            }
            if (when == null) {
                when = "";
            }
        }

        /** 静态行/动画行的便捷构造（分隔线与空行用不到 args 与宽度）。 */
        public static Line staticLine(String key, List<String> args, String when) {
            return new Line(LineKind.STATIC, key, args, when, 0, List.of(), 0);
        }
    }

    /**
     * 侧边栏配置。
     *
     * @param enabled      本文件的开关（还要与 game-settings 的同名开关取「与」）
     * @param titleKey     标题的 i18n 键；空串表示用出厂键 {@code scoreboard.title}
     * @param refreshTicks 整板重算周期 [5,200]
     * @param lines        有序行列表（顺序即渲染顺序）；空列表 = 只留标题，<b>不是</b>「用默认」
     */
    public record Board(boolean enabled, String titleKey, int refreshTicks, List<Line> lines) {
        public Board {
            lines = lines == null ? List.of() : List.copyOf(lines);
            if (titleKey == null) {
                titleKey = "";
            }
        }
    }

    /**
     * Tab 列表配置。
     *
     * <p>{@code lobby/arena/spectator} 是「本插件是否接管该上下文玩家的头/尾/名字」，
     * 不是可见性隔离——Minecraft 的 tab 列表是服务器级的，Bukkit 没有低成本的隔离手段。</p>
     *
     * @param nameAlive     存活玩家名字格式的 i18n 键
     * @param nameDead      死亡待复活玩家名字格式的 i18n 键
     * @param nameSpectator 观战者名字格式的 i18n 键
     */
    public record Tab(boolean enabled, int refreshTicks,
                      boolean lobby, boolean arena, boolean spectator,
                      List<Line> header, List<Line> footer,
                      String nameAlive, String nameDead, String nameSpectator) {
        public Tab {
            header = header == null ? List.of() : List.copyOf(header);
            footer = footer == null ? List.of() : List.copyOf(footer);
            if (nameAlive == null) {
                nameAlive = "";
            }
            if (nameDead == null) {
                nameDead = "";
            }
            if (nameSpectator == null) {
                nameSpectator = "";
            }
        }
    }

    /** 出厂侧边栏刷新周期：0.5 秒，倒计时跟手；静止时由整帧签名短路。 */
    public static final int DEFAULT_SCOREBOARD_REFRESH_TICKS = 10;
    /** 出厂 tab 刷新周期：1 秒，与业界默认一致。 */
    public static final int DEFAULT_TAB_REFRESH_TICKS = 20;
    /** 出厂标题键。 */
    public static final String DEFAULT_TITLE_KEY = "scoreboard.title";

    private final Board scoreboard;
    private final Tab tab;

    public DisplayConfig(Board scoreboard, Tab tab) {
        this.scoreboard = scoreboard;
        this.tab = tab;
    }

    public Board scoreboard() {
        return scoreboard;
    }

    public Tab tab() {
        return tab;
    }

    /**
     * 出厂默认（{@code display.yml} 不可用时的兜底）。
     *
     * <p>返回的是与出厂模板<b>等价</b>的配置，不是空配置：侧边栏空白对玩家来说与
     * 「插件坏了」不可区分，而默认布局正是升级前玩家已经看惯的那 10 行。</p>
     *
     * <p>行列表来自 {@link #defaultLines()}，与加载器无关——整个项目里默认布局只在这一处定义，
     * 避免「模板改了、兜底没改」的漂移。</p>
     */
    public static DisplayConfig defaults() {
        Board board = new Board(true, DEFAULT_TITLE_KEY, DEFAULT_SCOREBOARD_REFRESH_TICKS, defaultLines());
        Tab tab = new Tab(true, DEFAULT_TAB_REFRESH_TICKS, true, true, true,
                List.of(Line.staticLine("tab.header.title", List.of(), "always"),
                        Line.staticLine("tab.header.arena", List.of("arenaName", "gameState"), "in-game")),
                List.of(Line.staticLine("tab.footer.hint", List.of(), "always")),
                "tab.name.alive", "tab.name.dead", "tab.name.spectator");
        return new DisplayConfig(board, tab);
    }

    /**
     * 内置的 10 行默认布局——升级前 {@code ArenaScoreboard.refresh()} 的忠实镜像。
     *
     * <p>刻意<b>不</b>带分隔线、空行与轮播提示：出厂默认如果随版本变化，就是一次玩家可见的
     * 回归。这些行型在 {@code display.yml} 头部注释里有示例，需要的服主自行取消注释。</p>
     *
     * <p>顺序：wave → alive → starting → waiting → spectators → nodes → villagers →
     * kills → deaths → crystals。</p>
     */
    public static List<Line> defaultLines() {
        return List.of(
                Line.staticLine("scoreboard.wave", List.of("wave", "totalWaves"), "in-game"),
                Line.staticLine("scoreboard.alive", List.of("alivePlayers"), "in-game"),
                Line.staticLine("scoreboard.starting", List.of("startCountdown"), "start-countdown"),
                Line.staticLine("scoreboard.waiting", List.of("playerCount", "minPlayers"), "waiting-for-players"),
                Line.staticLine("scoreboard.spectators", List.of("spectators"), "in-game"),
                Line.staticLine("scoreboard.nodes", List.of("activeNodes", "totalNodes"), "in-game"),
                Line.staticLine("scoreboard.villagers", List.of("aliveVillagers", "totalVillagers"), "in-game"),
                Line.staticLine("scoreboard.kills", List.of("playerKills"), "alive"),
                Line.staticLine("scoreboard.deaths", List.of("playerDeaths"), "alive"),
                Line.staticLine("scoreboard.crystals", List.of("playerCrystals"), "alive"));
    }
}
