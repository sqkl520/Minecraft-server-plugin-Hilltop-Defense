package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.DisplayConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab 列表管理器（{@link ArenaScoreboard} 的兄弟类）。
 *
 * <p>职责只有三件，且三件的「生效范围」完全不同，实现时必须区分：</p>
 * <ol>
 *   <li><b>头/尾</b>（{@code sendPlayerListHeaderAndFooter}）—— <b>每个观看者私有</b>，
 *       单包单收件人、无遍历（反编译核实：{@code CraftPlayer} 只发一个
 *       {@code ClientboundTabListPacket}）。所以可以每秒重算，但仍做 {@code Component.equals}
 *       比对，省掉静止时的无用包。</li>
 *   <li><b>名字</b>（{@code playerListName(Component)}）—— <b>目标玩家的属性</b>，
 *       服务端会遍历所有在线玩家逐个 {@code canSee} 后广播。因此只能按<b>目标</b>状态算，
 *       不能按观看者算，而且必须做变更检测（否则 20 人服每秒 400 个增量包）。</li>
 *   <li><b>排序</b>（{@code setPlayerListOrder(int)}）—— 同样是目标属性、同样是
 *       O(在线人数) 广播（Paper 官方实现见 {@code CraftPlayer#setPlayerListOrder}），
 *       同带内一律用同一个值，不做 {@code band + hash}：客户端自己会按名字排，
 *       再加扰动只会让同一秒内两次刷新出现顺序抖动。</li>
 * </ol>
 *
 * <p><b>为什么 tab 里没有血量</b>：{@code ClientboundPlayerInfoUpdatePacket} 的条目里
 * 根本没有 health 字段（只有 profileId/profile/listed/latency/gameType/displayName/
 * showHat/listOrder/chatSession），Bukkit 层也没有任何入口能塞进去。想显示血量只能走
 * {@code DisplaySlot.PLAYER_LIST} 的计分板 objective（那是侧边栏的事，且观战者不渲染分数），
 * 或者引入协议库改包——两者都与本项目「零新增依赖、不用 NMS/反射」的约束冲突。
 * <b>不要再尝试</b>用 {@code playerListName} 拼血量数字：那会把名字做成每秒变化的全服广播。</p>
 *
 * <p>本类不持有任何按玩家的长生命周期对象：四张缓存都是「上一次发出去的值」的纯镜像，
 * 玩家退出即删（{@link #cleanup(Player)}）。这样即便某个玩家在缓存里残留，也只是一条
 * 几十字节的 {@code Component}，不会像计分板那样泄漏一整块 board。</p>
 */
public class TabListManager {

    /* ========== 排序分带（实施规范 §1.4） ========== */

    /** 大厅：排最前，让未入局的玩家不挡对局玩家的视线。 */
    private static final int ORDER_LOBBY = 3000;
    /** 存活：对局主视区。 */
    private static final int ORDER_ALIVE = 2000;
    /** 死亡待复活：仍在对局里，但排在存活之后。 */
    private static final int ORDER_DEAD = 1000;
    /** 观战：最后。注意客户端还会对 {@code GameMode.SPECTATOR} 额外斜体 + 置底，
     * 所以死亡者的分带只是「比纯观战靠前」，无法覆盖客户端的观战者装饰。 */
    private static final int ORDER_SPECTATOR = 500;

    private static final String KEY_NAME_ALIVE = "tab.name.alive";
    private static final String KEY_NAME_DEAD = "tab.name.dead";
    private static final String KEY_NAME_SPECTATOR = "tab.name.spectator";
    /** 管理员叠加层：不改分带，只换名字格式（保持状态可读性）。 */
    private static final String KEY_NAME_ADMIN = "tab.name.admin";

    /** 分隔线的配色模板键：与侧边栏共用，改一处颜色两边一起变。 */
    private static final String KEY_SEPARATOR = "scoreboard.separator";
    private static final String SEPARATOR_CHAR = "─";

    /** 玩家在 tab 里的「上下文」，决定用哪一段头/尾、以及该段开关是否放行。 */
    private enum Context {
        LOBBY, ARENA, SPECTATOR
    }

    /** 目标玩家的状态，决定名字格式与排序分带。 */
    private enum TargetState {
        LOBBY, ALIVE, DEAD, SPECTATOR
    }

    /**
     * 一次刷新内对某个玩家的竞技场侧快照。
     *
     * <p>头/尾每行都可能引用 {@code wave}/{@code alivePlayers}/{@code remainingMobs} 等，
     * 同一帧内多次调用 getter 会重复遍历（{@code WaveManager#getRemainingMobCount} 不是常数级）。
     * 每个观看者每帧只采一次，行数再多也只遍历一次。</p>
     *
     * <p>大厅玩家没有竞技场，此时 {@code arena}/{@code state} 为 {@code null}，
     * 所有竞技场参数解析成 0/空串——引用这些参数的行本就应该配 {@code in-game} 条件，
     * 配错了也只是显示 0，不会抛异常。</p>
     */
    private record View(ArenaInstance arena, PlayerData data, GameState state,
                        boolean spectator, boolean waitingForPlayers,
                        int startCountdown, int remainingMobs) {
    }

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final MultiArenaManager arenas;
    /** 全项目统一的 {@code §} 字符串 → Component 转换器（与 {@code DeployMenuSender} 同一用法）。 */
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    /** 观看者 → 上次发出的头。 */
    private final Map<UUID, Component> lastHeader = new ConcurrentHashMap<>();
    /** 观看者 → 上次发出的尾。 */
    private final Map<UUID, Component> lastFooter = new ConcurrentHashMap<>();
    /** 目标 → 上次发出的名字。 */
    private final Map<UUID, Component> lastName = new ConcurrentHashMap<>();
    /** 目标 → 上次发出的排序分带。 */
    private final Map<UUID, Integer> lastOrder = new ConcurrentHashMap<>();

    private BukkitTask refreshTask;
    /** 当前任务的周期；-1 表示没有任务。用于识别 {@code display.yml} 热重载后的周期变化。 */
    private int scheduledPeriod = -1;
    /**
     * 动画帧所需的单调 tick 计数（只在刷新任务里推进，不依赖系统时钟）。
     *
     * <p><b>按真实时间累加</b>（每轮 {@code += 刷新周期}），不是每轮 {@code +1}：
     * {@code display.yml} 里 {@code frame-ticks} 的单位是 <b>Minecraft tick</b>
     * （出厂注释写的是「20 tick = 1 秒」）。若按「每次刷新 +1」累加，出厂配置
     * {@code tab.refresh-ticks: 20} 配 {@code frame-ticks: 20} 的动画会变成
     * 每 20 次刷新（= 400 tick = 20 秒）才换一帧，比配置慢 20 倍，
     * 而同一个 {@code frame-ticks} 在侧边栏（{@code ArenaScoreboard#tickCounter}
     * 同样按周期累加）上却是正确速度——同一个值两边观感不一致就是这个 bug。
     * 按周期累加后，动画速度与 {@code refresh-ticks} 解耦，管理员调刷新频率
     * 只会改变动画的时间分辨率，不会连带改变节奏。</p>
     */
    private long tick;
    /** 掉线程告警只打一次，避免一旦误用就刷屏。 */
    private boolean warnedOffThread;

    public TabListManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.arenas = plugin.getMultiArenaManager();
    }

    /* ========== 生命周期 ========== */

    /**
     * 启动刷新任务（幂等）。
     *
     * <p>周期每次启动都从配置现读，所以 {@code /hilltop reloadconfig} 改了
     * {@code tab.refresh-ticks} 之后，只要有人调用一次 {@link #start()} 就会换周期；
     * 即使没人调，{@link #refreshAll()} 也会发现周期不符并自行重排（见该方法）。</p>
     */
    public void start() {
        DisplayConfig.Tab tab = tabConfig();
        if (tab == null || !tab.enabled()) {
            stop();
            return;
        }
        int period = tab.refreshTicks() > 0 ? tab.refreshTicks() : DisplayConfig.DEFAULT_TAB_REFRESH_TICKS;
        // 除周期比对还须查 isCancelled：stop() / restoreAll() 会主动 cancel 任务，
        // 此时 refreshTask 字段虽已置空、但若有人绕过来调用也会命中；而 Error（catch RuntimeException
        // 抓不到）导致的异常会让 CraftScheduler 走到「异常→重新入队」分支的 finally，
        // 状态可能不一致。isCancelled 是最省事的一致性判据。
        // 注意：**不要**指望「任务抛异常后 CraftScheduler 会取消它」——
        // 反编译 paper-1.21.4-232 的 mainThreadHeartbeat 证实它不会（详见 refreshAll 的注释）。
        if (refreshTask != null && !refreshTask.isCancelled() && period == scheduledPeriod) return;
        stopTask();
        scheduledPeriod = period;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, period, period);
    }

    /** 取消刷新任务。注意它<b>不</b>还原已发出的头/尾——还原是 {@link #restoreAll()} 的事。 */
    public void stop() {
        stopTask();
        scheduledPeriod = -1;
    }

    /**
     * 玩家加入：为这名玩家做一次强制推送。
     *
     * <p>「强制」= 先删掉他在四张缓存里的条目，再走正常的计算路径，于是必然会发包。
     * 加入时必须强制：服务端在新玩家进服时发的 {@code ADD_PLAYER} 条目带的是默认名与
     * 默认排序，而缓存里可能还残留着他上次会话的值——不删就会「看起来没变」而被跳过。</p>
     */
    public void apply(Player player) {
        if (player == null || !player.isOnline()) return;
        DisplayConfig.Tab tab = tabConfig();
        if (tab == null || !tab.enabled()) return;
        // 自愈：若插件启停顺序导致任务没开起来，第一个加入的玩家就是补开点
        start();
        forcePush(player, tab, "apply");
    }

    /**
     * 玩家退出：只丢缓存，<b>不</b>尝试还原。
     *
     * <p>玩家已经离开，{@code playerListName}/{@code setPlayerListOrder} 对一个失效的
     * 实体调用没有意义；而且退出时的名字/排序本来就随实体一起消失。
     * 唯一要做的是别让缓存跟着 UUID 一起留到下次会话。</p>
     */
    public void cleanup(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        // 四张表都按 UUID 键：目标项（名字/排序）是他的，观看者项（头/尾）也是他的
        lastHeader.remove(id);
        lastFooter.remove(id);
        lastName.remove(id);
        lastOrder.remove(id);
    }

    /**
     * 插件关闭：把每个在线玩家的名字、排序、头尾恢复成「本插件从未接管过」的样子。
     *
     * <p><b>只还原本插件真正碰过的玩家</b>（T5.1）：遍历名单取四张缓存的 keySet 并集，
     * 而不是全部在线玩家。理由：若 {@code tab.enabled: false}，本进程从未发过任何 tab 包；
     * 若某玩家一直处于被关闭的上下文（例如 {@code tab.lobby: false}，大厅交给别的插件管），
     * 本插件也没碰过他。按「全部在线玩家」还原会把别的插件的 tab 布局一并抹掉。
     * 缓存为空时连遍历都省掉。</p>
     *
     * <p><b>三项必须按人分别判定，不能一律复位</b>（D3）：四张缓存的「写入时机」不同——
     * {@link #lastHeader}/{@link #lastFooter} 在旧实现里对<b>每个在线观看者每帧</b>都写过，
     * 而 {@link #lastName}/{@link #lastOrder} 只在真正调用过 {@code playerListName} /
     * {@code setPlayerListOrder} 时才写。把并集里的每个人都做「名字+排序+头尾」三件事，
     * 等于给「本插件明确声明不接管」的玩家也清了名字与分带，正好又是一次覆盖别的插件。</p>
     *
     * <p>所以这里是逐个玩家分项判断：</p>
     * <ul>
     *   <li><b>名字</b>：仅当 {@link #lastName} 有该 UUID（本插件真的设过）时才复位成 {@code null}。</li>
     *   <li><b>排序</b>：仅当 {@link #lastOrder} 有该 UUID 时才复位成 0。</li>
     *   <li><b>头/尾</b>：仅当 {@link #lastHeader}/{@link #lastFooter} 有该 UUID 时才发一次空值。</li>
     * </ul>
     *
     * <p>顺序无关紧要，但要<b>逐人</b>发：头/尾是单包单收件人，
     * 名字与排序虽然广播，也以「按目标逐个还原」为口径，避免漏掉某个刚进服的人。</p>
     */
    public void restoreAll() {
        if (!ensureMainThread("restoreAll")) return;
        Set<UUID> touched = new LinkedHashSet<>();
        touched.addAll(lastHeader.keySet());
        touched.addAll(lastFooter.keySet());
        touched.addAll(lastName.keySet());
        touched.addAll(lastOrder.keySet());
        for (UUID id : touched) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            // 分项判：缓存条目存在 = 本插件真的在该项上动过手。用 remove 而不是 get，
            // 顺便把「已经还原过」这件事写进缓存状态，避免重复路径二次复位。
            if (lastName.remove(id) != null) {
                // null = 恢复客户端默认名（不是空名字）；显式转型是为了让「这是 Component 重载、
                // 不是已废弃的 String 版 setPlayerListName」在调用点一眼可见
                player.playerListName((Component) null);
            }
            if (lastOrder.remove(id) != null) {
                player.setPlayerListOrder(0);
            }
            // 头/尾是单人单值：只有本插件发过才擦，否则会抹掉别的插件设的内容
            if (lastHeader.remove(id) != null || lastFooter.remove(id) != null) {
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
        }
        clearCaches();
        stop();
    }

    /**
     * 丢掉全部「上次发出值」的镜像，让下一轮刷新必然重发。
     *
     * <p>三处调用：开关关闭时（见 {@link #refreshAll()}）、{@code restoreAll()} 还原之后、
     * 以及玩家加入/状态变化时的强制推送。语义统一为「缓存只描述已发出的值，一旦
     * 事实上已不成立，就必须清掉」——留着过期镜像的后果不是多发一个包，而是<b>少发</b>一个包。</p>
     */
    private void clearCaches() {
        lastHeader.clear();
        lastFooter.clear();
        lastName.clear();
        lastOrder.clear();
    }

    /** 状态变化钩子：强制重推某一名玩家（作为观看者推头/尾，作为目标推名字/排序）。 */
    public void refreshNow(Player player) {
        if (player == null || !player.isOnline()) return;
        DisplayConfig.Tab tab = tabConfig();
        if (tab == null || !tab.enabled()) return;
        forcePush(player, tab, "refreshNow");
    }

    private void forcePush(Player player, DisplayConfig.Tab tab, String where) {
        if (!ensureMainThread(where)) return;
        UUID id = player.getUniqueId();
        lastHeader.remove(id);
        lastFooter.remove(id);
        lastName.remove(id);
        lastOrder.remove(id);
        pushHeaderFooter(player, tab);
        // 名字/排序也按「目标」重推一遍：状态变化往往同时影响两者（入局改变分带、
        // 死亡改变名字），而这两项是广播，重推的收益覆盖全服而不是只有这名玩家
        pushName(player, tab);
        pushOrder(player);
    }

    private void stopTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /* ========== 刷新循环 ========== */

    /**
     * 每秒（可配）一轮全量刷新。
     *
     * <p>被刷新的对象永远是「当前在线的人」，不维护自己的玩家集合：本类的输入完全是
     * {@link MultiArenaManager} 与 {@link GameManager} 的当前状态，多存一份玩家表只会
     * 制造第二份真相（进出竞技场、掉线重连时不同步）。</p>
     *
     * <p>没有在线玩家时直接返回：不需要停任务（任务本身就是一个空的 Runnable 调用），
     * 但也不做任何取配置以外的动作。每个玩家访问前都过 {@code isOnline()}——
     * 这一轮迭代途中可能有人退出，而 {@code getOnlinePlayers()} 的快照不保证这一点。</p>
     *
     * <p><b>为什么整段包 try/catch（T6.1）：</b>渲染一帧里的任何一处抛异常，
     * 都不该让日志刷屏或中断后续玩家的处理。这里兜住并记一条 SEVERE。</p>
     *
     * <p><b>为什么 catch 里<b>不</b>清空句柄与周期（2026-11-14 修正）：</b>
     * 原实现写的是「置空句柄 + 周期归 -1，下一帧 start() 自然重建」，理由是
     * 「{@code CraftScheduler} 对抛未捕获异常的重复任务会静默取消它」。那个前提是<b>错的</b>——
     * 反编译 {@code paper-1.21.4-232} 的 {@code CraftScheduler.mainThreadHeartbeat()} 可见，
     * 任务体抛异常后走的分支是：记 WARNING → 发 {@code ServerExceptionEvent} → 清 currentTask →
     * <b>与正常路径汇合</b> → 因 {@code period > 0} 而 {@code pending.add(task)} 重新入队。
     * 全程没有 {@code cancel()}。所以周期任务抛异常后会按时继续执行。</p>
     *
     * <p>既然旧任务从未被取消，把它置空就等于制造一个无人持有的孤儿：
     * 下一帧 {@link #start()} 看到 {@code refreshTask == null} 会新建一条，
     * 而旧的那条还在跑 → 两条（以及每异常一次再加一条）并行，
     * {@link #refreshAllUnchecked()} 里的相位推进也就按倍数进行，动画双倍速。</p>
     *
     * <p>保留句柄才是正确语义：异常已经被这个 catch 吃掉，任务本身是健康的，
     * 下一个周期照常执行，导致失败的条件消失后自然恢复。同样的写法见
     * {@code ArenaScoreboard} 的 catch 分支。</p>
     */
    private void refreshAll() {
        try {
            refreshAllUnchecked();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Tab list refresh failed; the task stays scheduled and will retry next period", e);
        }
    }

    private void refreshAllUnchecked() {
        if (!ensureMainThread("refreshAll")) return;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) return;

        DisplayConfig.Tab tab = tabConfig();
        if (tab == null || !tab.enabled()) {
            // 「关闭 = 什么都不做」：不主动清空已发出的头/尾（那会把玩家正看着的内容抹掉，
            // 与「按开关接管」的语义相反）。但缓存必须丢掉——否则重新打开开关后首帧会拿
            // 「关闭前发过的值」比对，判定为未变化而永远不重推。
            //
            // 刻意<b>不</b>停任务：开关随时可能被 /hilltop reloadconfig 打回 true，
            // 而那时不一定有玩家加入事件来重启任务；空跑一轮的代价只是一次配置读取。
            clearCaches();
            return;
        }

        // 热重载：display.yml 改了 refresh-ticks 而没人调 start()，就在这里重排一次。
        // 重排必须在遍历之前，否则本帧会带着旧周期继续跑。
        int period = tab.refreshTicks() > 0 ? tab.refreshTicks() : DisplayConfig.DEFAULT_TAB_REFRESH_TICKS;
        if (period != scheduledPeriod) {
            start();
            return;
        }

        // 按真实时间推进动画相位（D1）：frame-ticks 的单位是 tick，不是「刷新次数」。
        // 此处 scheduledPeriod 必然 > 0 —— 上面刚比对过 period != scheduledPeriod，
        // 而 period 已经过 `> 0 ? : DEFAULT` 夹取；仍取 max(1, …) 只是防止将来有人
        // 改动上面那段夹取而在这里退化成「相位不推进」。
        tick += Math.max(1, scheduledPeriod);

        // 第一遍：头/尾（观看者私有）。第二遍：名字/排序（目标属性，广播）。
        // 拆成两遍不是洁癖：头/尾循环里会采竞技场快照，名字/排序只需要玩家自身状态，
        // 合在一起会让每个玩家多采一次快照。
        for (Player viewer : online) {
            if (viewer == null || !viewer.isOnline()) continue;
            pushHeaderFooter(viewer, tab);
        }
        for (Player target : online) {
            if (target == null || !target.isOnline()) continue;
            // T4.1：上下文开关管的是<b>整个</b> tab 接管，不只是头/尾。关掉某个上下文却照推
            // 名字与排序，会把别的插件在该上下文设置的 tab 布局覆盖掉（例如 tab.lobby: false
            // 的诉求通常就是「大厅的 tab 交给别人管」）。关闭时走一次「复位 + 清缓存」，
            // 与头尾「关闭 = 发一次空值」保持同一范式；复位只在缓存非空时真的发包。
            if (!isManaged(target, tab)) {
                resetNameAndOrder(target);
                continue;
            }
            pushName(target, tab);
            pushOrder(target);
        }
    }

    /** 目标玩家当前上下文是否由本插件接管（与 {@link #pushHeaderFooter} 的 managed 判定同源）。 */
    private boolean isManaged(Player target, DisplayConfig.Tab tab) {
        return switch (contextOf(target)) {
            case LOBBY -> tab.lobby();
            case ARENA -> tab.arena();
            case SPECTATOR -> tab.spectator();
        };
    }

    /**
     * 上下文关闭时把该玩家还原成客户端默认（名字 null、排序 0），并清掉两项缓存。
     *
     * <p>只在「缓存里还留着值」时发包：从没接管过的人不需要任何动作，
     * 而刚被关闭的上下文恰好只复位一次，不会每秒重发。</p>
     */
    private void resetNameAndOrder(Player target) {
        if (!target.isOnline()) return;
        UUID id = target.getUniqueId();
        if (lastName.remove(id) != null) {
            target.playerListName((Component) null);
        }
        if (lastOrder.remove(id) != null) {
            target.setPlayerListOrder(0);
        }
    }

    /* ========== 头 / 尾 ========== */

    /**
     * 按观看者的上下文渲染并发送头/尾。
     *
     * <p><b>上下文未接管时只「擦一次」，之后不再发包</b>（D2）：Bukkit 的
     * {@code sendPlayerListHeaderAndFooter} 是<b>每个玩家唯一一份</b>值，没有「谁设的」
     * 这一维度。原实现在未接管的上下文里照样发 {@link Component#empty()}，
     * 于是管理员把 {@code tab.contexts.lobby} 关掉、把大厅 tab 交给别的插件管时，
     * 本插件每秒都会把对方的头/尾抹成空白——关闭某个上下文的本意是「这块我不碰」，
     * 而不是「这块必须是空的」。</p>
     *
     * <p>但反过来「完全不发」也会留下上一个已接管上下文里的旧文字（玩家从对局回到大厅会
     * 看到对局期间的提示）。两种错都得避，所以按缓存状态区分：</p>
     * <ul>
     *   <li>缓存里<b>还留着本插件发过的值</b>（{@link #lastHeader}/{@link #lastFooter} 有该
     *       UUID 条目）→ 说明是「刚刚从接管变成不接管」，发<b>恰好一次</b>空值把旧文字擦掉，
     *       并删掉缓存条目。下一次刷新时缓存已空，就不再发包。</li>
     *   <li>缓存里<b>没有</b>该 UUID 条目 → 本插件在这个上下文里从未发过任何东西，
     *       直接返回。这里正是不该碰别的插件内容的那条路径。</li>
     * </ul>
     *
     * <p>删缓存这一步不能省：留着旧值会让「未接管」分支永远判定为「刚从接管变过来」而每秒发包，
     * 而且等到上下文重新被接管时，第一帧会拿旧值比对而误判「没变化」，头/尾再也不更新。</p>
     */
    private void pushHeaderFooter(Player viewer, DisplayConfig.Tab tab) {
        Context context = contextOf(viewer);
        boolean managed = switch (context) {
            case LOBBY -> tab.lobby();
            case ARENA -> tab.arena();
            case SPECTATOR -> tab.spectator();
        };

        UUID id = viewer.getUniqueId();
        if (!managed) {
            // 只在「本插件确实在这里留下过内容」时擦一次；否则一个包都不发，
            // 把这块让给别的插件（头/尾是单人单值，多发一个空值就是抹别人的屏）
            boolean hadHeader = lastHeader.remove(id) != null;
            boolean hadFooter = lastFooter.remove(id) != null;
            if (hadHeader || hadFooter) {
                if (viewer.isOnline()) {
                    viewer.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                }
            }
            return;
        }

        View view = viewOf(viewer);
        Component header = buildBlock(tab.header(), viewer, view);
        Component footer = buildBlock(tab.footer(), viewer, view);

        // Component 是不可变树，equals 是结构比较；这里比的是「即将发的」与「上次发的」，
        // 与服务端缓存无关（服务端只缓存 header/footer 字段，没有相等性检查）
        if (header.equals(lastHeader.get(id)) && footer.equals(lastFooter.get(id))) return;
        if (!viewer.isOnline()) return;

        viewer.sendPlayerListHeaderAndFooter(header, footer);
        lastHeader.put(id, header);
        lastFooter.put(id, footer);
    }

    /** 把一组行配置渲染成一整块文本，行间用换行符连接（客户端会自行处理折行上限）。 */
    private Component buildBlock(List<DisplayConfig.Line> lines, Player viewer, View view) {
        Component block = Component.empty();
        boolean first = true;
        for (DisplayConfig.Line line : lines) {
            if (!isVisible(line.when(), view)) continue;
            String text = renderLine(viewer, line, view);
            if (text == null) continue;
            if (!first) block = block.append(Component.newline());
            block = block.append(legacy.deserialize(text));
            first = false;
        }
        return block;
    }

    /**
     * 可见条件求值。闭集与 {@code DisplayConfigLoader.CONDITIONS} 一一对应。
     *
     * <p>闭集外的名字（管理员拼错）一律 false：把拼错降级成 {@code always} 会让本该
     * 隐藏的行（观战者看到「你的击杀」之类）冒出来，比少一行更难排查。</p>
     *
     * <p>与侧边栏的差别只有一处：这里没有「属于哪块 board」的概念，所以
     * {@code alive}/{@code spectator} 直接按<b>观看者</b>自身判定，而不是按某个竞技场
     * ——{@link View} 就是「这名观看者」的快照，因此本方法不需要单独的 viewer 参数。</p>
     */
    private boolean isVisible(String when, View view) {
        GameState state = view.state();
        return switch (when == null ? "" : when) {
            case "always" -> true;
            case "waiting" -> state == GameState.WAITING;
            case "starting" -> state == GameState.STARTING;
            case "running" -> state == GameState.RUNNING;
            case "intermission" -> state == GameState.WAVE_INTERMISSION;
            case "in-game" -> state == GameState.STARTING
                    || state == GameState.RUNNING
                    || state == GameState.WAVE_INTERMISSION;
            case "finished" -> state == GameState.VICTORY
                    || state == GameState.DEFEAT
                    || state == GameState.ENDING;
            case "start-countdown" -> view.startCountdown() >= 0;
            case "waiting-for-players" -> view.waitingForPlayers();
            case "alive" -> view.data() != null && view.data().isAlive();
            case "spectator" -> view.spectator();
            default -> false;
        };
    }

    /**
     * 把一行配置渲染成带 {@code §} 颜色码的原始文本。
     *
     * @return {@code null} 表示跳过该行（动画帧列表异常；加载器本应已拦住）
     */
    private String renderLine(Player viewer, DisplayConfig.Line line, View view) {
        return switch (line.kind()) {
            case SEPARATOR -> separatorPrefix(viewer) + SEPARATOR_CHAR.repeat(Math.max(1, line.separatorWidth()));
            case BLANK -> "";
            case ANIM -> {
                List<String> frames = line.animFrames();
                if (frames.isEmpty()) yield null;
                int frame = (int) ((tick / Math.max(1, line.frameTicks())) % frames.size());
                yield lang.getFor(viewer, frames.get(frame), (Object[]) resolveArgs(line, view, viewer));
            }
            case STATIC -> lang.getFor(viewer, line.i18nKey(), (Object[]) resolveArgs(line, view, viewer));
        };
    }

    /**
     * 抽取分隔线模板里前缀连续的颜色/样式码。
     *
     * <p>宽度归 {@code display.yml}、配色归翻译者，两处各管一半；
     * 抽不到任何颜色码时就退化成无格式的破折号。</p>
     */
    private String separatorPrefix(Player viewer) {
        String template = lang.getFor(viewer, KEY_SEPARATOR);
        StringBuilder prefix = new StringBuilder(8);
        int i = 0;
        while (i + 1 < template.length() && template.charAt(i) == '§') {
            prefix.append(template.charAt(i)).append(template.charAt(i + 1));
            i += 2;
        }
        return prefix.toString();
    }

    /* ========== 名字 / 排序（目标属性） ========== */

    /**
     * 渲染并发送目标玩家的 tab 名字。
     *
     * <p>名字是<b>目标</b>的属性：一旦发出，所有看得见他的观看者都显示同一个字符串，
     * 且是按目标自己的 locale 翻译的——这是一条无法绕开的限制（客户端只存一份 displayName）。
     * 所以这里不接收 viewer 参数，用 {@code target} 自己取词。</p>
     *
     * <p>管理员叠加层只换格式、不换分带：如果连分带一起提到最前，
     * 会掩盖「这个管理员正在对局中」这一本来有用的信息。</p>
     */
    private void pushName(Player target, DisplayConfig.Tab tab) {
        Component name = legacy.deserialize(nameText(target, tab));
        // 先判在线再写缓存：若目标在这一轮迭代中途退出，写进缓存会留下一条「从未发出」的值，
        // 下次同 UUID 会话的首帧会因此被误判为「没变」而跳过
        if (!target.isOnline()) return;
        UUID id = target.getUniqueId();
        if (name.equals(lastName.get(id))) return;
        // 名字必须用 Component 重载：String 版早已 @Deprecated（Paper），
        // 且会经过一次额外的 legacy 序列化再反序列化
        target.playerListName(name);
        lastName.put(id, name);
    }

    private String nameText(Player target, DisplayConfig.Tab tab) {
        String key;
        if (target.hasPermission("hilltopvillage.admin")) {
            key = KEY_NAME_ADMIN;
        } else {
            key = switch (stateOf(target)) {
                case LOBBY, ALIVE -> fallbackKey(tab.nameAlive(), KEY_NAME_ALIVE);
                case DEAD -> fallbackKey(tab.nameDead(), KEY_NAME_DEAD);
                case SPECTATOR -> fallbackKey(tab.nameSpectator(), KEY_NAME_SPECTATOR);
            };
        }
        return lang.getFor(target, key, target.getName());
    }

    /**
     * 配置里的键为空时退回出厂键。
     *
     * <p>空串在这个位置只可能来自「管理员把 player-name 改成了空」，这时用出厂键
     * 比让所有人名字变成空字符串安全——tab 里出现一排空白行比格式不对更吓人。</p>
     */
    private String fallbackKey(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    /** 发送排序分带；同带内的先后由客户端按名字决定（规范明确要求不要做 band + hash）。 */
    private void pushOrder(Player target) {
        int order = orderOf(stateOf(target));
        if (!target.isOnline()) return;
        UUID id = target.getUniqueId();
        Integer previous = lastOrder.get(id);
        // Integer 与 int 比较会拆箱，这里 prev 已判 null，== 是数值比较
        if (previous != null && previous == order) return;
        // Paper 要求 order >= 0；四个常量全部为正，无需再夹取
        target.setPlayerListOrder(order);
        lastOrder.put(id, order);
    }

    private int orderOf(TargetState state) {
        return switch (state) {
            case LOBBY -> ORDER_LOBBY;
            case ALIVE -> ORDER_ALIVE;
            case DEAD -> ORDER_DEAD;
            case SPECTATOR -> ORDER_SPECTATOR;
        };
    }

    /**
     * 目标玩家状态分类。
     *
     * <p>「死亡待复活」与「观战」在数据上都是 {@code spectators} 集合里的 UUID，
     * 靠 <b>GameMode</b> + <b>竞技场是否在跑</b> 区分：死亡者被客户端强制切成
     * {@code SPECTATOR} 以便自由视角，而真正的观战者在对局未开始时（WAITING/STARTING）
     * 也处于 SPECTATOR 模式——所以两个条件必须同时成立才判为 DEAD。</p>
     *
     * <p>「在跑」取 {@code RUNNING} 与 {@code WAVE_INTERMISSION} 两态，
     * 与 {@code GameManager#isGameRunning()} 的口径一致：休整期仍然算对局内，
     * 此时死亡者应排到存活之后而不是被当成纯观战。</p>
     */
    private TargetState stateOf(Player target) {
        ArenaInstance arena = arenas.getPlayerArena(target);
        if (arena == null) return TargetState.LOBBY;
        if (arena.isSpectator(target.getUniqueId())) {
            GameState state = arena.getState();
            boolean running = state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION;
            if (target.getGameMode() == GameMode.SPECTATOR && running) {
                return TargetState.DEAD;
            }
            return TargetState.SPECTATOR;
        }
        return TargetState.ALIVE;
    }

    /* ========== 上下文与参数 ========== */

    /**
     * 观看者的上下文。
     *
     * <p>刻意<b>不</b>用 {@code isLobbyWorld} 判定大厅：{@code arenas.yml} 未配置
     * {@code lobby.world} 时该方法恒 false，会把所有大厅玩家判成「无上下文」而拿不到
     * 任何头/尾——而「不在任何竞技场」本身就是大厅的充分条件，且与排序分带的口径一致。</p>
     */
    private Context contextOf(Player viewer) {
        ArenaInstance arena = arenas.getPlayerArena(viewer);
        if (arena == null) return Context.LOBBY;
        return arena.isSpectator(viewer.getUniqueId()) ? Context.SPECTATOR : Context.ARENA;
    }

    /** 采集观看者所在竞技场的计数快照；大厅玩家得到全 null/0 的空快照。 */
    private View viewOf(Player viewer) {
        ArenaInstance arena = arenas.getPlayerArena(viewer);
        if (arena == null) {
            return new View(null, null, null, false, false, -1, 0);
        }
        GameManager gameManager = arena.getGameManager();
        PlayerData data = gameManager == null ? null : gameManager.getPlayerData(viewer.getUniqueId());
        GameState state = arena.getState();
        int remainingMobs = 0;
        if (gameManager != null && gameManager.getWaveManager() != null) {
            remainingMobs = gameManager.getWaveManager().getRemainingMobCount();
        }
        return new View(
                arena,
                data,
                state,
                arena.isSpectator(viewer.getUniqueId()),
                state == GameState.WAITING && arena.getPlayerCount() < arena.getMinPlayers(),
                arena.getStartCountdown(),
                remainingMobs);
    }

    /**
     * 把 {@code args} 名字解析成字符串数组。
     *
     * <p>与 {@code ArenaScoreboard} 同一套名字闭集（{@code DisplayConfigLoader.ARGS}），
     * 但取值走本类的 {@link View}——头/尾只需要「这名观看者所在的局」，
     * 不需要侧边栏那份更细的逐玩家击杀数据（头/尾每行都带击杀数会让每秒都有的广播
     * 变成每杀一人都要改全服 tab）。</p>
     *
     * <p>全部返回 {@code String}：{@code LanguageManager.getFor} 的尾部是
     * {@code Object...}，统一 {@code String.valueOf} 避免把 {@code Integer} 直接塞进格式化路径。</p>
     */
    private String[] resolveArgs(DisplayConfig.Line line, View view, Player viewer) {
        List<String> names = line.args();
        String[] values = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            values[i] = resolveArg(names.get(i), view, viewer);
        }
        return values;
    }

    private String resolveArg(String name, View view, Player viewer) {
        ArenaInstance arena = view.arena();
        return switch (name) {
            case "wave" -> String.valueOf(arena == null ? 0 : arena.getCurrentWave());
            case "totalWaves" -> String.valueOf(arena == null ? 0 : arena.getTotalWaves());
            case "alivePlayers" -> String.valueOf(arena == null ? 0
                    : arena.getPlayerCount() - arena.getSpectatorCount());
            case "spectators" -> String.valueOf(arena == null ? 0 : arena.getSpectatorCount());
            case "activeNodes" -> String.valueOf(arena == null ? 0
                    : arena.getGameManager().getNodeSystem().getActiveCount());
            case "totalNodes" -> String.valueOf(arena == null ? 0
                    : arena.getGameManager().getNodeSystem().getTotalCount());
            case "aliveVillagers" -> String.valueOf(arena == null ? 0
                    : arena.getVillagerSystem().getAliveCount());
            case "totalVillagers" -> String.valueOf(arena == null ? 0
                    : arena.getVillagerSystem().getTotalCount());
            case "playerKills" -> String.valueOf(view.data() == null ? 0 : view.data().getKills());
            case "playerDeaths" -> String.valueOf(view.data() == null ? 0 : view.data().getDeaths());
            case "playerCrystals" -> String.valueOf(view.data() == null ? 0 : view.data().getCurrencyCollected());
            case "startCountdown" -> String.valueOf(view.startCountdown());
            case "playerCount" -> String.valueOf(arena == null ? 0 : arena.getPlayerCount());
            case "minPlayers" -> String.valueOf(arena == null ? 0 : arena.getMinPlayers());
            case "remainingMobs" -> String.valueOf(view.remainingMobs());
            // 休整期剩余秒数只有「配置的波次间隔」可读（GameManager 未把剩余量落地成字段，
            // 与 ArenaScoreboard 同一取舍）；非休整期返回 0，引用该参数的行应配 intermission 条件
            case "nextWaveSeconds" -> String.valueOf(
                    view.state() == GameState.WAVE_INTERMISSION ? arena.getWaveIntervalSeconds() : 0);
            case "arenaName" -> arena == null ? "" : arena.getDisplayName();
            case "gameState" -> view.state() == null ? "" : lang.getFor(viewer, view.state().i18nKey());
            default -> "";
        };
    }

    /* ========== 工具 ========== */

    private DisplayConfig.Tab tabConfig() {
        DisplayConfig config = plugin.getConfigManager().getDisplayConfig();
        return config == null ? null : config.tab();
    }

    /**
     * 所有 Bukkit 调用都必须在主线程。调度器给的是同步任务，理论上永远不会落到别的线程；
     * 这个守卫防的是将来有人从异步回调里调 {@link #refreshNow(Player)}——
     * 那种情况下 {@code sendPlayerListHeaderAndFooter} 会在网络线程上被调用，
     * 轻则抛异常，重则让连接卡死。告警只打一次，避免误用变成刷屏。
     */
    private boolean ensureMainThread(String where) {
        if (Bukkit.isPrimaryThread()) return true;
        if (!warnedOffThread) {
            warnedOffThread = true;
            plugin.getLogger().warning("TabListManager." + where
                    + " was called off the main thread; tab list update skipped");
        }
        return false;
    }
}
