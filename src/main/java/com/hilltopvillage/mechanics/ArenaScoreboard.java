package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.config.DisplayConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 竞技场侧边栏记分板（对应 BedWars1058 的 {@code BwSidebar} + {@code SidebarService}）。
 *
 * <p>每个玩家拥有独立计分板（因为需要显示玩家个人的击杀数），
 * 由一条共享的定时任务按 {@code display.yml: scoreboard.refresh-ticks} 刷新，
 * 避免为每个玩家各开一个任务。</p>
 *
 * <p>显示内容完全由 {@code display.yml} 的 {@code scoreboard.lines} 驱动：每一行有行型
 * （分隔线 / 空行 / 静态 / 动画）、i18n 键、参数名列表与可见条件。布局在每帧由
 * {@link #resolveLayout()} 解析一次，{@code lines} 为空时回退到
 * {@link DisplayConfig#defaultLines()}——那是升级前 10 行的忠实镜像，
 * 整个项目里默认布局只存在这一处，本类不复制第二份。</p>
 *
 * <p>刷新做了两级短路（见 {@link BoardState}）：整帧输入签名命中就整帧跳过，
 * 未命中时再逐行比对文本与分数，只重发真正变化的行。
 * 竞技场级计数（波次 / 存活 / 节点 / 村民 / 观战）每帧只在 {@link #refreshAll()}
 * 里算一次并装进 {@link Snapshot}，再分发给每个玩家——村民存活数是全量遍历
 * {@code isValid() && !isDead()}，绝不能按玩家数重复跑。</p>
 */
public class ArenaScoreboard {

    /**
     * 侧边栏最大行数。
     *
     * <p>Minecraft 侧边栏能稳定显示的极限是 15 行；早先版本取 10 行是「当时内置布局的峰值」，
     * 而不是任何协议限制。配置化之后管理员可以自行加行，所以上限放到 15，
     * 超限时由 {@link #writeLines} 打一条英文 WARNING 后裁尾——
     * 静默裁尾会让「加了一行却不显示」无从排查（D8）。</p>
     */
    private static final int MAX_LINES = 15;

    /**
     * 行 entry 名缓存。
     *
     * <p>侧边栏的行文本放在 Team prefix 里，entry 只需要唯一且稳定，用
     * {@code "§0".."§f"} 这类不可见短串即可。但它们每帧每行都会被引用两次
     * （建行时的 {@code addEntry}、每帧的 {@code getScore}），
     * 且 {@link Scoreboard#resetScores(String)} 也按同一字符串查表——
     * 预先算好比每帧拼 {@code "§" + Integer.toHexString(i)} 更省，也保证三处用的是同一份字面量。</p>
     */
    private static final String[] ENTRIES = new String[MAX_LINES];

    static {
        for (int i = 0; i < MAX_LINES; i++) {
            ENTRIES[i] = "§" + Integer.toHexString(i);
        }
    }

    /**
     * {@code display.yml: scoreboard.lines} 为空时的兜底布局。
     *
     * <p>在这里求值一次，而不是每帧调用 {@link DisplayConfig#defaultLines()}：
     * 兜底路径本来就是异常路径，没必要每帧分配一个 10 元素列表再丢掉。
     * 内容本身仍只有 {@link DisplayConfig#defaultLines()} 一处定义，本类不复制第二份。</p>
     */
    private static final List<DisplayConfig.Line> FALLBACK_LAYOUT = DisplayConfig.defaultLines();

    /** 分隔线字符（全角破折号，客户端字体下宽度与旧版 {@code ---------------} 接近）。 */
    private static final String SEPARATOR_CHAR = "─";

    /**
     * 关闭期间「等待开关被改回 true」的探针周期（tick，20 = 1 秒）。
     *
     * <p>刻意与 {@code display.yml: scoreboard.refresh-ticks} 解耦：探针的任务体只有
     * 「读一次开关」这一件事，把刷新周期调到 1 tick 也不该让它在关闭期间变成 20 Hz 轮询。
     * 1 秒的延迟对「管理员敲完 reloadconfig」完全不构成感受差异。</p>
     */
    private static final int RE_ENABLE_PROBE_TICKS = 20;

    private final HilltopVillagePlugin plugin;
    private final ArenaInstance arena;
    private final LanguageManager lang;
    private final Map<UUID, BoardState> boards = new ConcurrentHashMap<>();
    private BukkitTask updateTask;

    /**
     * 上一次观察到的有效开关值，用于判定 {@code false → true} 翻转（S08-F2）。
     *
     * <p>只用于「恢复」这一侧：关闭时把所有人拆板并停掉刷新任务之后，
     * 任务已死、{@link #refreshAll()} 再也不会被调用，因此不可能靠它发现开关被改回 true。
     * 本字段配合 {@link #reEnableProbeTask} 保证「关闭 → 重新打开」只重建一次板，
     * 而不是每 tick 重建（{@code show()} 每次都新建 scoreboard 并重发整帧）。</p>
     */
    private boolean lastEnabled;

    /**
     * 关闭期间等待开关恢复的轻量探针任务（S08-F2）。
     *
     * <p>为什么需要它：开关一旦关闭，{@link #refreshAll()} 会把所有板拆掉并在
     * {@code boards} 空后 {@link #stopTask()}，此后没有任何一条路径会再回到本类
     * ——管理员重新打开开关时，已经在对局里的玩家会永久失去侧边栏（节点 / 村民 /
     * 波次没有任何其它常驻显示通道），只能靠离场重进、重连或下一局才恢复。</p>
     *
     * <p>为什么不选「每帧轮询」：刷新任务已停，只能另起一条。本探针的周期固定为
     * {@link #RE_ENABLE_PROBE_TICKS}（1 秒）而不是 {@code refresh-ticks}，任务体只有
     * 「读一次布尔值」；开关一旦恢复（或无人在场）立即自取消，
     * 因此稳态下不存在任何额外轮询。</p>
     */
    private BukkitTask reEnableProbeTask;

    /**
     * 帧计数器，单位是 <b>Minecraft tick</b>（不是刷新次数）。
     *
     * <p>存在理由是动画行——签名短路在「快照没变」时会整帧跳过，而动画恰恰要在
     * 快照不变时推进。因此帧号只在存在动画行时参与签名（见 {@link #buildSignature}），
     * 否则静止时仍能命中短路。</p>
     *
     * <p>累加的是刷新周期而不是 1：{@code frame-ticks} 的语义是 tick（出厂注释写的是
     * 「20 tick = 1 秒」），若按「每帧 +1」累加，默认 10 tick 的刷新周期会让动画
     * 比配置快一倍。按周期累加让动画速度与 {@code refresh-ticks} 解耦，
     * 管理员调刷新频率不会连带改变动画节奏（只会改变动画的时间分辨率）。</p>
     */
    private long tickCounter;

    /** 当前生效布局里是否含动画行；由 {@link #resolveLayout()} 在布局变化时重算。 */
    private boolean hasAnimation;
    /**
     * 每条动画行各自的 {@code frame-ticks}（仅动画行，顺序即出现顺序）。
     *
     * <p>必须是<b>逐行</b>的而不是取一个公约数：两行动画的帧长不同时，
     * 用单一除数算签名会在慢那行的帧边界上「看起来没变」而卡帧。
     * 把每行的 {@code tickCounter / frameTicks} 都塞进签名，任何一行换帧都会让签名变化。</p>
     */
    private int[] animFrameTicks = new int[0];
    /** 当前刷新的周期（tick），用于让 {@link #tickCounter} 以真实时间推进。 */
    private int refreshPeriodTicks = DisplayConfig.DEFAULT_SCOREBOARD_REFRESH_TICKS;
    /** 上一帧解析出的布局（内容比较），判定 {@code display.yml} 是否被重载。 */
    private List<DisplayConfig.Line> cachedLayout;
    /** 超限裁尾的 WARN 是否已报过（每份布局只报一次，见 {@link #refresh}）。 */
    private boolean lineOverflowWarned;

    public ArenaScoreboard(HilltopVillagePlugin plugin, ArenaInstance arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.lang = plugin.getLanguageManager();
        resolveLayout();
    }

    /**
     * 单名玩家的记分板状态。
     *
     * <p>为什么要缓存「上一帧的输入签名」与「上一帧实际写入的每行文本」两层：</p>
     * <ul>
     *   <li><b>输入签名</b>用于整帧短路 —— 记分板内容全部由快照 + 个人数据派生，只要它们没变，
     *       重新取 i18n、重新拼字符串、重新 setPrefix 全是白做。静止时（没有击杀、没有波次推进）
     *       这个短路能命中绝大多数帧。</li>
     *   <li><b>已写入文本</b>用于逐行去重 —— 即使签名变了（比如只多了 1 个击杀），
     *       也只有那一行真的需要重发。{@code PlayerTeam#setPlayerPrefix} 在服务端
     *       <b>没有</b>相等性检查（已反编译核实：只 putfield + onTeamChanged），
     *       文本没变也会重发整个 Team 包。</li>
     * </ul>
     *
     * <p>{@code scores[]} 与 {@code written[]} 同生命周期（D4）：分数是侧边栏的排序键，
     * 行数变化后每一行的目标分数都会平移，所以必须有本地缓存才能「变了才发包」。
     * 初值 {@link Integer#MIN_VALUE} 保证首帧必然写入。</p>
     */
    private static final class BoardState {
        private final Scoreboard board;
        private final Objective objective;
        /** 上一帧的输入签名；null 表示尚未渲染过（或布局刚重载，需要强制重绘） */
        private String signature;
        /** 上一帧实际写入的标题；null 表示尚未写入过 */
        private String title;
        /** 上一帧实际写入 Team prefix 的文本；null 表示该行尚未建立 */
        private final String[] written = new String[MAX_LINES];
        /** 上一帧实际写入 objective 的分数；排序键，行数变化时必须重算 */
        private final int[] scores = new int[MAX_LINES];

        private BoardState(Scoreboard board, Objective objective) {
            this.board = board;
            this.objective = objective;
            java.util.Arrays.fill(this.scores, Integer.MIN_VALUE);
        }

        /**
         * 显式拆除这块 board 上的 objective。
         *
         * <p>只在 {@link #show} 发现同一玩家已有旧 board 时调用（D2）。旧 board 变成不可达
         * 对象后只剩 GC 回收，本身不会向客户端发包，但显式 unregister 能让「旧 board 上
         * 还挂着 team / score」这一状态在服务端立即消失，避免将来有代码按 board 反查时
         * 拿到陈旧对象。</p>
         */
        private void discard() {
            try {
                objective.unregister();
            } catch (IllegalStateException ignored) {
                // 已注销（重复 discard）——没有需要清理的东西，且这里不能打日志：show 可能被重入
            }
        }
    }

    /* ========== 布局解析 ========== */

    /**
     * 解析当前生效的行布局，并在布局对象变化时重算动画标志、清空所有签名。
     *
     * <p>{@code display.yml: scoreboard.lines} 为空时回退到 {@link #FALLBACK_LAYOUT}：
     * 加载器把「空列表」定义为「只保留标题」的合法配置，但侧边栏只剩标题对玩家来说
     * 与插件坏了不可区分，所以这里按实施规范 §2.1「平滑迁移」的口径兜底到 10 行默认布局。</p>
     *
     * <p>用内容比较（而不是对象身份）判定布局是否变化：{@code DisplayConfig} 每次重载都
     * 构造新对象，内容相同的重载不该让所有玩家白重绘一帧；内容改了则必须让全部
     * {@code signature} 归零，否则 {@code /hilltop reloadconfig} 改了行文本却因为计数没变
     * 而永远不刷新。record 的 {@code equals} 逐字段比较，行数与参数列表都在比较范围内。</p>
     *
     * @return 本帧生效的行列表（不可变）
     */
    private List<DisplayConfig.Line> resolveLayout() {
        DisplayConfig.Board board = plugin.getConfigManager().getDisplayConfig().scoreboard();
        List<DisplayConfig.Line> lines = board.lines();
        if (lines.isEmpty()) {
            lines = FALLBACK_LAYOUT;
        }
        if (!lines.equals(cachedLayout)) {
            cachedLayout = lines;
            List<Integer> frameTicks = new ArrayList<>(2);
            for (DisplayConfig.Line line : lines) {
                if (line.kind() == DisplayConfig.LineKind.ANIM && line.animFrames().size() >= 2) {
                    frameTicks.add(Math.max(1, line.frameTicks()));
                }
            }
            hasAnimation = !frameTicks.isEmpty();
            int[] ticks = new int[frameTicks.size()];
            for (int i = 0; i < ticks.length; i++) {
                ticks[i] = frameTicks.get(i);
            }
            animFrameTicks = ticks;
            // 布局换了就重新允许报一次超限：管理员可能是"改布局改到超了"，
            // 沿用上一份布局的已报标志会把这个新问题静默掉。
            lineOverflowWarned = false;
            for (BoardState state : boards.values()) {
                state.signature = null;
            }
        }
        return lines;
    }

    /** 当前生效的标题键；空串表示用出厂键。 */
    private String titleKey() {
        String key = plugin.getConfigManager().getDisplayConfig().scoreboard().titleKey();
        return key == null || key.isBlank() ? DisplayConfig.DEFAULT_TITLE_KEY : key;
    }

    /* ========== 生命周期 ========== */

    /** 为玩家创建并显示侧边栏 */
    public void show(Player player) {
        if (!plugin.getConfigManager().isScoreboardEffectivelyEnabled()) {
            // S08-F2：关闭期间入场也要挂上恢复探针。否则管理员重新打开开关后，
            // 这名玩家与对局中的其它成员一样，要等离场重进 / 重连 / 下一局才有侧边栏：
            // 关闭期间 show() 全程早退，boards 里没有任何条目能触发重建。
            ensureReEnableProbeRunning();
            return;
        }
        if (!player.isOnline()) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = lang.getFor(player, titleKey());
        Objective objective = board.registerNewObjective("hilltop", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // D9：1.20.3+ 默认会把 score 值渲染成行尾的红色数字。本类的 score 只承担排序职责，
        // 数字对玩家完全是噪音（还会被误读成击杀数），blank 是全类唯一正确的写法。
        // 改完后排序键仍由 score 值承担，空格式只影响渲染。
        objective.numberFormat(NumberFormat.blank());

        // BoardState 必须与 board 成对新建：written[] / scores[] 描述的是
        // 「这块 board 上当前挂着什么」，只有成对新建才能保证「新建的 board 上一行都没有」
        // 与「缓存全空」这两个前提同时成立（writeLines 里靠它们判断该行是否需要建/写/清）。
        BoardState state = new BoardState(board, objective);
        // 标题已在上面的 registerNewObjective 里写入，记下来免得首帧再 setDisplayName 一次
        state.title = title;

        // D2：覆盖式重建。所有 show 调用点前都有对应的 hide/removePlayer，正常情况下
        // 这里返回值必为 null；万一将来出现重入，显式拆掉旧 objective 而不是把它丢给 GC。
        BoardState old = boards.put(player.getUniqueId(), state);
        if (old != null) {
            old.discard();
        }
        player.setScoreboard(board);

        // 只刷新这一名玩家：show() 的语义是「给这名玩家上板」，不负责顺手重绘全场。
        // 但必须走一次完整 refresh，否则新 board 上 written[]/scores[] 全是空，
        // 第一帧要等到下一个刷新周期才出现（入场瞬间侧边栏空 0.5 秒）。
        refresh(player, state, snapshot(), resolveLayout());
        ensureTaskRunning();
    }

    /**
     * 移除玩家的侧边栏并还原主计分板。
     *
     * <p>与 {@link #clear()} 一样带 board 身份校验（D6）：只有玩家当前挂的确实是本实例
     * 创建的这块 board 时才切回主板，避免把已经转场到其它竞技场的玩家侧边栏拆掉。</p>
     */
    public void hide(Player player) {
        BoardState state = boards.remove(player.getUniqueId());
        if (state == null) return;
        if (player.isOnline() && player.getScoreboard() == state.board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (boards.isEmpty()) stopTask();
    }

    /**
     * 退出清理入口：移除离玩家的残留条目并按需停掉刷新任务。
     *
     * <p>scoreboard.hide() 对离线玩家不做 setScoreboard（玩家对象已失效，调用无意义），
     * 只把该玩家的 BoardState 从 boards 里摘掉；boards 为空时停掉刷新任务，
     * 避免任务在无人持有记分板时继续空转。</p>
     */
    public void cleanupPlayer(Player player) {
        hide(player);
    }

    /** 竞技场重置时清理所有侧边栏 */
    public void clear() {
        for (UUID uuid : new ArrayList<>(boards.keySet())) {
            BoardState state = boards.get(uuid);
            if (state == null) continue;
            Player player = Bukkit.getPlayer(uuid);
            // D6：只拆「自己挂上去的那块」。boards 里可能残留一个已转场到别的竞技场、
            // 其 board 已被对方 show() 接管的 UUID；无这条守卫时会把对方的侧边栏一起拆掉，
            // 且对方 boards 里的条目仍在（它不知道自己被抢了），整局不再恢复。
            if (player != null && player.isOnline() && player.getScoreboard() == state.board) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        stopTask();
        // S08-F2：重置同样收掉恢复探针。重置后成员集合已清空，探针下一 tick 也会自取消；
        // 显式收掉省掉那一次空转，也让 clear() 的语义保持「本实例回到初始态」。
        stopReEnableProbe();
    }

    private void ensureTaskRunning() {
        // 除空判外还须查 isCancelled：任务体抛未捕获异常时 CraftScheduler 会静默取消它，
        // 而 updateTask 字段仍是那个已被取消的句柄——只判 null 会让侧边栏整局冻结在最后一帧
        //（后续 show() 都认为任务还活着）。见 refreshAll 的 try/catch 注释。
        if (updateTask != null && !updateTask.isCancelled()) return;
        updateTask = null;
        // 周期取自 display.yml（出厂 10 tick）。热重载改周期不会打断运行中的任务：
        // 重排会让倒计时行的节奏抖动，而周期变化属于管理员调优动作——下一次任务重建
        // （boards 清空后再次 show）时按新值生效。
        refreshPeriodTicks = Math.max(5, plugin.getConfigManager().getDisplayConfig().scoreboard().refreshTicks());
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, refreshPeriodTicks, refreshPeriodTicks);
    }

    private void stopTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /**
     * 确保「开关恢复」探针在运行（S08-F2，幂等）。
     *
     * <p>只在关闭态被调用：{@link #refreshAll()} 的关闭分支与关闭期间的 {@link #show()}。
     * 两处都要挂，因为关闭分支跑完之后刷新任务已被取消 —— 那是本类最后一次被外部驱动，
     * 不在这里另起一条，恢复就再没有触发点了。</p>
     */
    private void ensureReEnableProbeRunning() {
        // 与 ensureTaskRunning 同一判据：任务体若抛未捕获异常会被 CraftScheduler 静默取消，
        // 只判 null 会永远认为探针还活着。
        if (reEnableProbeTask != null && !reEnableProbeTask.isCancelled()) return;
        reEnableProbeTask = null;
        // 插件禁用阶段不允许再调度 Bukkit 任务
        if (!plugin.isEnabled()) return;
        reEnableProbeTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::probeReEnable, RE_ENABLE_PROBE_TICKS, RE_ENABLE_PROBE_TICKS);
    }

    private void stopReEnableProbe() {
        if (reEnableProbeTask != null) {
            reEnableProbeTask.cancel();
            reEnableProbeTask = null;
        }
    }

    /**
     * 关闭期间的探针任务体：每 {@link #RE_ENABLE_PROBE_TICKS} 读一次开关。
     *
     * <p>三个出口：</p>
     * <ul>
     *   <li>开关仍是 false 且场上已无在线成员 → 自取消（没人需要恢复，下次 {@code show()} 会重挂）；</li>
     *   <li>开关翻回 true → 自取消并重建全部在线成员的侧边栏；</li>
     *   <li>抛异常 → 自取消，避免「抛错 → 每帧再抛」的刷屏循环（与 {@link #refreshAll} 的 catch 同理由）。</li>
     * </ul>
     */
    private void probeReEnable() {
        try {
            if (plugin.getConfigManager().isScoreboardEffectivelyEnabled()) {
                stopReEnableProbe();
                // 兜底：正常路径下 lastEnabled 已在关闭分支被置 false，
                // 这里再判一次是为了挡住「关闭与恢复之间没有经过 refreshAll 关闭分支」的极端时序
                //（例如配置在两次 tick 之间被改回来），避免重复 show() 把整帧重发一遍。
                if (lastEnabled) return;
                restoreBoardsAfterReEnable();
                return;
            }
            lastEnabled = false;
            // 无在线成员：没有人需要恢复。离线成员（重连窗口内）不该在这里被重建 ——
            // 他们重连时 ArenaInstance#rejoin 会自己调 show()。
            if (arena.getPlayers().isEmpty()) stopReEnableProbe();
        } catch (RuntimeException fatal) {
            stopReEnableProbe();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Sidebar re-enable probe failed for arena " + arena.getArenaId()
                            + "; it will be re-created on the next show()", fatal);
        }
    }

    /**
     * 开关从 false 翻回 true 时重建侧边栏（S08-F2）。
     *
     * <p>只遍历<b>在线竞技场成员</b>（{@link ArenaInstance#getPlayers()}）：它已经过滤掉离线玩家，
     * 而 {@code boards} 此刻是空的 —— 按 UUID 集合重建会顺手把早已离场的人拉回来。</p>
     *
     * <p>观战者不特殊处理：入场路径（{@code ArenaInstance#addPlayer}）对所有人一视同仁地调
     * {@link #show}，观战态只影响行可见性（{@code when: spectator}），所以这里复刻同一入口即可。</p>
     */
    private void restoreBoardsAfterReEnable() {
        lastEnabled = true;
        int attempted = 0;
        int restored = 0;
        for (Player player : arena.getPlayers()) {
            // show() 会对每名玩家新建 board 并立即渲染一帧，单个人失败不应连累其他人
            attempted++;
            try {
                show(player);
                restored++;
            } catch (RuntimeException perPlayer) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Failed to restore the sidebar for " + player.getName()
                                + " after scoreboard re-enable (arena " + arena.getArenaId() + ")", perPlayer);
            }
        }
        // 只要有成员被尝试过就确保刷新任务在跑：show() 内部已经调过 ensureTaskRunning，
        // 但那条路径受 isOnline / 开关二次判定影响，这里再兜一次（该方法幂等）。
        // 一个都没挂上时任务是空转的，下一帧的 boards 空判会把它停掉，不会留下空转 ticker。
        if (attempted > 0) {
            ensureTaskRunning();
        }
        if (restored > 0) {
            plugin.getLogger().info("Scoreboard re-enabled for arena " + arena.getArenaId()
                    + "; restored the sidebar for " + restored + " player(s)");
        }
    }

    /* ========== 刷新 ========== */

    /**
     * 每帧入口：解析布局 → 判定开关 → 构造竞技场级快照 → 分发给每个玩家。
     *
     * <p>D5：开关必须在这里再读一次。{@code show()} 只在入场时读开关，管理员对局中关闭
     * 侧边栏后已显示的 board 会继续刷新，配置项形同虚设。这里读到的 false 直接全量拆板。</p>
     *
     * <p>帧号（{@link #tickCounter}）在开关判定之后、快照之前自增：关闭期间不推进动画，
     * 重新打开时动画从关闭处继续，不会出现「关了一会儿再打开，动画跳帧」的观感。</p>
     *
     * <p><b>为什么整段包 try/catch：</b>{@code CraftScheduler} 对抛未捕获异常的任务会记录一次
     * 并<b>静默取消它</b>。若不在这里兜住，任意一次 i18n 取词或某个 getter 抛异常就会让侧边栏
     * 整局冻结在最后一帧，且 {@code updateTask} 字段仍指向那个已被取消的句柄。</p>
     *
     * <p>catch 里<b>不</b>重建任务：同一 tick 内立即重建会掩盖根因、并可能形成「抛错 → 重建
     * → 再抛错」的死循环；改为 {@link #stopTask()} 真正取消任务（<b>不是</b>只把句柄置空），
     * 由下一次 {@code show()} 的 {@link #ensureTaskRunning()} 自然恢复。之所以必须显式
     * {@code cancel()}：异常在本方法的 catch 内就被吞掉，从未穿出 {@code run()}，
     * {@code CraftScheduler} 不会替我们取消任务——只置空句柄会留下一条仍在调度的孤儿 ticker，
     * 而 {@link #stopTask()} 再也找不到它（slice-10-conc-001）。异常路径只吞掉渲染错误，
     * 不改变任何游戏状态。</p>
     *
     * <p>单玩家失败不影响他人：内层 try/catch 只跳过该玩家这一帧，避免一个人坏掉整块板。</p>
     */
    private void refreshAll() {
        try {
            if (!plugin.getConfigManager().isScoreboardEffectivelyEnabled()) {
                // S08-F2：关闭态必须记进 lastEnabled，并挂上恢复探针 —— 本方法是任务被取消前
                // 最后一个会跑到这里的入口，不在这里埋监视点，「重新打开」就永远没有触发者。
                lastEnabled = false;
                for (UUID uuid : new ArrayList<>(boards.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        hide(player);
                    } else {
                        boards.remove(uuid);
                    }
                }
                if (boards.isEmpty()) stopTask();
                ensureReEnableProbeRunning();
                return;
            }
            // 开关为 true 且任务活着：走过一次正常帧就说明当前是「开」态，
            // 让 false → true 的判定基准始终跟着真实开关走（含首帧初始化）。
            lastEnabled = true;
            stopReEnableProbe();
            if (boards.isEmpty()) {
                stopTask();
                return;
            }

            List<DisplayConfig.Line> layout = resolveLayout();
            // 按真实时间推进（累加刷新周期），而不是「每次刷新 +1」：frame-ticks 的单位是 tick，
            // 见 tickCounter 的字段注释。
            tickCounter += refreshPeriodTicks;

            // D7：竞技场级计数每帧只算一次。其中 getAliveCount() 是全量遍历村民并逐个
            // isValid()/isDead()，原实现按「玩家数」重复跑；改为 Snapshot 后是
            // 「每竞技场每帧一次」，与在线人数无关。
            Snapshot snapshot = snapshot();

            for (Map.Entry<UUID, BoardState> entry : boards.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                // S6：离线残留必须摘除，不能只 continue。退出链（hide/cleanupPlayer）正常情况下
                // 会清干净，但只要有一条漏网（别的插件踢人、异常路径），boards 就永远不空 →
                // 任务永不停 → 每帧空转、tickCounter 还在推进动画相位。
                if (player == null || !player.isOnline()) {
                    boards.remove(entry.getKey());
                    continue;
                }
                // 单玩家隔离：一个玩家的渲染异常（极端 i18n 模板、第三方插件改坏 PlayerData）
                // 不应让同场其它玩家的侧边栏一起停更。
                try {
                    refresh(player, entry.getValue(), snapshot, layout);
                } catch (RuntimeException perPlayer) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to refresh the sidebar for " + player.getName()
                                    + " (arena " + arena.getArenaId() + "); skipping this frame", perPlayer);
                }
            }
        } catch (RuntimeException fatal) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Sidebar refresh failed for arena " + arena.getArenaId()
                            + "; the task was cancelled and will be re-created on the next show()", fatal);
            // slice-10-conc-001：必须先 cancel() 再置空。异常在这一帧之内就被本 catch 吞掉，
            // 从未穿出 run() → CraftScheduler 不会取消任务；只置空句柄会把一个仍然活着的
            // ticker 变成无人持有的孤儿（stopTask 再也找不到它），而下一次 show() →
            // ensureTaskRunning 会再起一条 —— 同实例两条 ticker，刷新与动画相位双倍推进。
            // 取消后置空，句柄失效与任务实际状态一致，ensureTaskRunning 才能正确重建。
            stopTask();
        }
    }

    /** 采集竞技场级计数（与观看者无关的部分）。 */
    private Snapshot snapshot() {
        GameManager gameManager = arena.getGameManager();
        int spectators = arena.getSpectatorCount();
        // 存活玩家 = 在场人数 - 观战（等待复活）人数。口径与旧实现逐字一致：
        // 「掉线但仍在重连窗口内」的玩家仍计入 players，本类不改变这个口径。
        int alive = gameManager.getPlayerCount() - spectators;
        int startCountdown = arena.getStartCountdown();
        GameState state = arena.getState();
        boolean waitingForPlayers = state == GameState.WAITING
                && arena.getPlayerCount() < arena.getMinPlayers();
        return new Snapshot(
                gameManager.getCurrentWave(),
                // 波数取竞技场定义（而不是 gameManager 字段）：后者要等 startGame 才同步成
                // per-arena 值，等待期会先显示全局值，出现「20 波突然变 10 波」的跳变
                arena.getTotalWaves(),
                alive,
                spectators,
                gameManager.getNodeSystem().getActiveCount(),
                gameManager.getNodeSystem().getTotalCount(),
                arena.getVillagerSystem().getAliveCount(),
                arena.getVillagerSystem().getTotalCount(),
                startCountdown,
                waitingForPlayers,
                gameManager.getWaveManager().getRemainingMobCount(),
                nextWaveSeconds(state),
                // playerCount / minPlayers / arenaName：这三个参数原先由 resolveArg 每行现读
                // arena，绕过了 Snapshot，因此不进签名——管理员改 min-players 或重命名竞技场后
                // 签名不变、行不重绘，玩家看到旧数字直到某个聚合值恰好变化。收进来后签名自动覆盖。
                arena.getPlayerCount(),
                arena.getMinPlayers(),
                arena.getDisplayName(),
                state);
    }

    /**
     * 休整期剩余秒数。
     *
     * <p>{@link GameManager} 没有暴露休整期倒计时的读取口（{@code onWaveComplete} 只是
     * {@code runTaskLater(20L * intervalSeconds)}，剩余量不落地成字段），本类也不应为显示
     * 需求去新增一个字段。因此这里返回<b>配置的波次间隔</b>
     * （{@code arenas.<id>.game.wave-interval-seconds}），而不是真实倒计时——
     * 只有在休整期刚开始的那一瞬间两者相等，之后会偏大。
     * 若将来 {@code GameManager} 提供了剩余秒 getter，只需替换本方法体。</p>
     *
     * <p>非 {@code WAVE_INTERMISSION} 状态返回 -1（同 {@code ArenaInstance#getStartCountdown}
     * 的「不可用」约定），避免管理员把该行写成 {@code when: always} 时看到固定的「0 秒」。</p>
     */
    private int nextWaveSeconds(GameState state) {
        if (state != GameState.WAVE_INTERMISSION) return -1;
        return arena.getWaveIntervalSeconds();
    }

    /** 单名玩家的一帧刷新：签名短路 → 标题 → 逐行求值 → 写入。 */
    private void refresh(Player player, BoardState state, Snapshot snapshot, List<DisplayConfig.Line> layout) {
        PlayerData data = arena.getGameManager().getPlayerData(player.getUniqueId());

        String signature = buildSignature(player, snapshot, data);
        if (signature.equals(state.signature)) return;
        state.signature = signature;

        // 标题也是 i18n 派生的（show() 里注册时取一次），既然 locale 已纳入签名，
        // 这里必须一并补上——否则玩家切语言后正文会跟上、标题永远停在旧语言。
        // 只在签名未命中的分支执行，静止时零开销；setDisplayName 同样无相等性检查，
        // 所以先比对再写，避免每帧重发一次 Objective 包。
        String title = lang.getFor(player, titleKey());
        if (!title.equals(state.title)) {
            state.title = title;
            state.objective.setDisplayName(title);
        }

        List<String> lines = new ArrayList<>(layout.size());
        for (DisplayConfig.Line line : layout) {
            if (!isVisible(line.when(), snapshot, player, data)) continue;
            String text = renderLine(player, line, snapshot, data);
            if (text != null) lines.add(text);
        }

        if (lines.size() > MAX_LINES) {
            // D8：不静默裁尾。超限说明管理员加的行超过了客户端上限，
            // 裁掉的永远是尾部——没有日志的话「我加的行不显示」无从排查。
            // 每条布局只报一次：本方法是每帧路径，逐帧打日志会把控制台刷满，
            // 反而让真正的告警被淹没（布局不变时超限是确定性的，一次足够定位）。
            if (!lineOverflowWarned) {
                lineOverflowWarned = true;
                plugin.getLogger().warning("Scoreboard layout for arena '" + arena.getArenaId() + "' renders "
                        + lines.size() + " lines; Minecraft can display at most " + MAX_LINES
                        + ". Truncating the tail.");
            }
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
        }

        writeLines(state, lines);
    }

    /**
     * 输入签名：记分板每一行都由快照 + 个人数据派生，它们没变就整帧跳过。
     *
     * <p>用 StringBuilder 而非字符串拼接：这里每帧每玩家只跑一次，
     * 但签名一旦命中就能省下整轮 i18n 取词 + 颜色码翻译 + 逐行 setPrefix（后者每次都发包）。</p>
     *
     * <p>末尾带上玩家当前 locale：行文本 = i18n 模板 × 这些计数，模板是签名的隐藏输入。
     * {@code /hilltop lang} 只改 LanguageManager 里的 locale 映射、不动任何计数，
     * 不把 locale 纳入签名的话，玩家切语言后侧边栏会一直停在旧语言。</p>
     *
     * <p>动画帧号只在 {@link #hasAnimation} 为真时参与签名：没有动画行时多带一个每帧变化的数
     * 会让短路永远失效（等于每秒全量重发），有动画行时不带则动画永远停在第一帧。</p>
     *
     * <p><b>观战态与存活态必须入签名</b>：{@code when: alive} / {@code when: spectator} 两种
     * 条件的取值来自「本玩家」而非竞技场聚合值。默认布局里 kills/deaths/crystals 三行正是
     * {@code when: alive}。若不入签名，「A 死亡、B 复活」这类两人互换的状态在聚合值上完全抵消
     * （alive 不变、spectators 不变），签名命中短路，于是 A 仍显示着个人数据行、B 仍看不到，
     * 直到某个聚合值恰好变化才自愈——休整期可长达整个 wave-interval-seconds。
     * 两次查询都是常数级（{@code isSpectator} 是 HashSet.contains），代价可忽略。</p>
     */
    private String buildSignature(Player player, Snapshot snapshot, PlayerData data) {
        StringBuilder sig = new StringBuilder(72);
        sig.append(snapshot.wave()).append(':').append(snapshot.totalWaves())
           .append(':').append(snapshot.alive()).append(':').append(snapshot.spectators())
           .append(':').append(snapshot.activeNodes()).append(':').append(snapshot.totalNodes())
           .append(':').append(snapshot.aliveVillagers()).append(':').append(snapshot.totalVillagers())
           .append(':').append(snapshot.startCountdown())
           .append(':').append(snapshot.waitingForPlayers() ? 1 : 0)
           .append(':').append(snapshot.remainingMobs()).append(':').append(snapshot.nextWaveSeconds())
           .append(':').append(snapshot.state().name())
           .append(':').append(data == null ? -1 : data.getKills())
           .append(':').append(data == null ? -1 : data.getDeaths())
           .append(':').append(data == null ? -1 : data.getCurrencyCollected())
           // 玩家级可见性输入（见方法注释）：少这两个，"两人互换生死"会让签名命中而整帧不重绘
           .append(':').append(data != null && data.isAlive() ? 1 : 0)
           .append(':').append(arena.isSpectator(player.getUniqueId()) ? 1 : 0)
           // 三个「现读 arena」的参数（见 Snapshot 字段注释）：不进签名则管理员改 min-players
           // 或重命名竞技场后行不重绘。arenaName 是服务端可改文本，直接拼进签名字符串安全
           //（签名只做相等比较，不做解析）。
           .append(':').append(snapshot.playerCount()).append(':').append(snapshot.minPlayers())
           .append(':').append(snapshot.arenaName())
           .append(':').append(lang.getPlayerLocale(player));
        if (hasAnimation) {
            // 逐行取「第几帧」，而不是 tickCounter / 全局步长：两行动画帧长不同时，
            // 单一除数会在慢那行的帧边界上判成「没变」，动画因此卡帧。
            for (int frameTicks : animFrameTicks) {
                sig.append(':').append(tickCounter / frameTicks);
            }
        }
        return sig.toString();
    }

    /* ========== 行求值 ========== */

    /**
     * 可见条件求值。闭集与 {@code DisplayConfigLoader.CONDITIONS} 一一对应；
     * 不在闭集里的名字（管理员拼错）一律返回 false——把拼错的条件降级成 always
     * 会让本该隐藏的行（例如观战者的「你的击杀」）显示出来，比少一行更糟。
     */
    private boolean isVisible(String when, Snapshot snapshot, Player viewer, PlayerData data) {
        return switch (when == null ? "" : when) {
            case "always" -> true;
            case "waiting" -> snapshot.state() == GameState.WAITING;
            case "starting" -> snapshot.state() == GameState.STARTING;
            case "running" -> snapshot.state() == GameState.RUNNING;
            case "intermission" -> snapshot.state() == GameState.WAVE_INTERMISSION;
            case "in-game" -> snapshot.state() == GameState.STARTING
                    || snapshot.state() == GameState.RUNNING
                    || snapshot.state() == GameState.WAVE_INTERMISSION;
            case "finished" -> snapshot.state() == GameState.VICTORY
                    || snapshot.state() == GameState.DEFEAT
                    || snapshot.state() == GameState.ENDING;
            case "start-countdown" -> snapshot.startCountdown() >= 0;
            case "waiting-for-players" -> snapshot.waitingForPlayers();
            case "alive" -> data != null && data.isAlive();
            case "spectator" -> arena.isSpectator(viewer.getUniqueId());
            default -> false;
        };
    }

    /**
     * 把一行配置渲染成最终文本（已含颜色码）。
     *
     * <p>{@code SEPARATOR} 的颜色取自 i18n 键 {@code scoreboard.separator}：只抽取其中的
     * 颜色/样式码（{@code §x} 对）作为前缀，再拼 {@code separatorWidth} 个 {@code ─}。
     * 这样宽度归 {@code display.yml} 管、配色仍归翻译者管——两处都不必为了改长度/改颜色
     * 去动另一个文件。抽不到任何颜色码时就是无格式的纯破折号。</p>
     *
     * @return 该行文本；{@code null} 表示该行应当被跳过（动画帧列表异常，加载器本应已拦住）
     */
    private String renderLine(Player player, DisplayConfig.Line line, Snapshot snapshot, PlayerData data) {
        switch (line.kind()) {
            case SEPARATOR:
                return separatorPrefix(player) + SEPARATOR_CHAR.repeat(Math.max(1, line.separatorWidth()));
            case BLANK:
                return "";
            case ANIM: {
                List<String> frames = line.animFrames();
                if (frames.isEmpty()) return null;
                int frame = (int) ((tickCounter / Math.max(1, line.frameTicks())) % frames.size());
                return lang.getFor(player, frames.get(frame), (Object[]) resolveArgs(line, snapshot, data, player));
            }
            case STATIC:
            default:
                return lang.getFor(player, line.i18nKey(), (Object[]) resolveArgs(line, snapshot, data, player));
        }
    }

    /**
     * 抽取 {@code scoreboard.separator} 模板里的颜色/样式码（连续的 {@code §x} 对）。
     *
     * <p>只取前缀连续段：模板形如 {@code &7&m---------------}，首段即全部格式码；
     * 一旦遇到第一个非 {@code §} 开头的字符就停，避免把模板里的正文颜色码带到分隔线上。</p>
     */
    private String separatorPrefix(Player player) {
        String template = lang.getFor(player, "scoreboard.separator");
        StringBuilder prefix = new StringBuilder(8);
        int i = 0;
        while (i + 1 < template.length() && template.charAt(i) == '§') {
            prefix.append(template, i, i + 2);
            i += 2;
        }
        return prefix.toString();
    }

    /**
     * 把一个行配置的 {@code args} 名字解析成字符串数组。
     *
     * <p>返回 {@code String[]} 而不是 {@code Object[]}：{@code LanguageManager.getFor} 的
     * 尾部是 {@code Object...}，全走 {@code String.valueOf} 让每个值只有一种表示，
     * 也避免把 {@code Integer} 直接塞进 i18n 的格式化路径。</p>
     *
     * <p>{@code playerCount} / {@code minPlayers} 直接读 arena：两个都是 {@code size()} /
     * 定义字段的常数级读取，不值得为它们扩展 {@link Snapshot}（那是给重活的计数用的）。</p>
     */
    private String[] resolveArgs(DisplayConfig.Line line, Snapshot snapshot, PlayerData data, Player player) {
        List<String> names = line.args();
        String[] values = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            values[i] = resolveArg(names.get(i), snapshot, data, player);
        }
        return values;
    }

    private String resolveArg(String name, Snapshot snapshot, PlayerData data, Player player) {
        return switch (name) {
            case "wave" -> String.valueOf(snapshot.wave());
            case "totalWaves" -> String.valueOf(snapshot.totalWaves());
            case "alivePlayers" -> String.valueOf(snapshot.alive());
            case "spectators" -> String.valueOf(snapshot.spectators());
            case "activeNodes" -> String.valueOf(snapshot.activeNodes());
            case "totalNodes" -> String.valueOf(snapshot.totalNodes());
            case "aliveVillagers" -> String.valueOf(snapshot.aliveVillagers());
            case "totalVillagers" -> String.valueOf(snapshot.totalVillagers());
            case "startCountdown" -> String.valueOf(snapshot.startCountdown());
            case "playerCount" -> String.valueOf(snapshot.playerCount());
            case "minPlayers" -> String.valueOf(snapshot.minPlayers());
            case "remainingMobs" -> String.valueOf(snapshot.remainingMobs());
            case "nextWaveSeconds" -> String.valueOf(snapshot.nextWaveSeconds());
            // 个人数据整组缺失（PlayerData 已被 removeOfflinePlayer 清掉，但 board 还在的
            // 极端窗口）时给 "0" 而不是隐藏行：渲染期没有「跳过」的语义，
            // 返回 null 会让 i18n 里出现字面量 "null"，比 0 更糟。
            case "playerKills" -> String.valueOf(data == null ? 0 : data.getKills());
            case "playerDeaths" -> String.valueOf(data == null ? 0 : data.getDeaths());
            case "playerCrystals" -> String.valueOf(data == null ? 0 : data.getCurrencyCollected());
            case "arenaName" -> snapshot.arenaName();
            case "gameState" -> lang.getFor(player, snapshot.state().i18nKey());
            default -> "";
        };
    }

    /* ========== 写入 ========== */

    /**
     * 写入行内容。
     * 每个行使用一个唯一短 entry（§0~§e），文本放在 Team prefix（支持颜色且无长度限制）。
     *
     * <p>逐行比对后再 setPrefix：{@code PlayerTeam#setPlayerPrefix} 没有相等性检查
     * （已反编译核实），不比对的话文本没变也会重发整个 Team 包。
     * 分数同理（{@code Score#setScore} 走 {@code broadcastAll}），用 {@code scores[]} 缓存比对。</p>
     *
     * <p><b>不再有 64 字符截断</b>（D1）：1.13+ 的 prefix 是 Component，没有任何长度上限。
     * 旧实现按 UTF-16 code unit 切 {@code substring(0, 64)}，会切在 {@code §} 与代理对中间，
     * 造成行尾整段染色失效或出现 U+FFFD，且截断结果被写回 {@code written[]} 永不自行修复。</p>
     */
    private void writeLines(BoardState state, List<String> lines) {
        Scoreboard board = state.board;
        Objective objective = state.objective;

        // 清除多余的历史行（D3）。
        // written[i] == null 意味着「这一行在 board 上从未建立过」——board 与 BoardState 在
        // show() 里成对新建，而本类是唯一往这块私有计分板注册 Team 的地方（全仓库仅此一处
        // registerNewTeam），因此 written[i] == null 蕴含 board.getTeam("line" + i) == null。
        //
        // 关键顺序：先 resetScores 再 unregister。Team#unregister() 只解除条目归属，
        // 不会删除 objective 上的 Score；只 unregister 的话那条 "§i" 的 score 仍占位，
        // 以空 prefix 渲染成幽灵空行（且因为分数还在，可能插在可见行中间）。
        for (int i = lines.size(); i < MAX_LINES; i++) {
            if (state.written[i] == null) continue;
            board.resetScores(ENTRIES[i]);
            Team stale = board.getTeam("line" + i);
            if (stale != null) stale.unregister();
            state.written[i] = null;
            state.scores[i] = Integer.MIN_VALUE;
        }

        for (int i = 0; i < lines.size(); i++) {
            String entry = ENTRIES[i];
            Team team = board.getTeam("line" + i);
            if (team == null) {
                team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
            }
            String text = lines.get(i);
            if (!text.equals(state.written[i])) {
                team.setPrefix(text);
                state.written[i] = text;
            }
            // D4：分数是侧边栏的排序键（降序），必须随行数变化重算，不能只在建行那一帧写。
            // 缓冲到 scores[] 再比，避免对未变行每秒重发 score 包。
            int score = lines.size() - i;
            if (state.scores[i] != score) {
                objective.getScore(entry).setScore(score);
                state.scores[i] = score;
            }
        }
    }

    /**
     * 竞技场级计数快照（D7）。
     *
     * <p>这些值每帧只在 {@link #refreshAll()} 里取一次，再传给每个玩家的 {@code refresh}——
     * 原实现把全部 getter 放在每个玩家的 {@code refresh} 里，其中村民存活数是全量遍历，
     * 总成本 = 竞技场数 × 玩家数 × 村民数，与在线人数线性相关。</p>
     *
     * <p>刻意不含个人数据（击杀/死亡/水晶）与 locale：那两项每人不同，取它们本来就是
     * 每人一次。</p>
     */
    private record Snapshot(int wave, int totalWaves, int alive, int spectators,
                            int activeNodes, int totalNodes, int aliveVillagers, int totalVillagers,
                            int startCountdown, boolean waitingForPlayers,
                            int remainingMobs, int nextWaveSeconds,
                            int playerCount, int minPlayers, String arenaName,
                            GameState state) {
    }
}
