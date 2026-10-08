package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
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
 * 由一条共享的定时任务每秒刷新一次，避免为每个玩家各开一个任务。</p>
 *
 * <p>显示内容：波次进度、存活玩家、等待复活、能量节点、存活村民、个人击杀。</p>
 *
 * <p>刷新做了两级短路（见 {@link BoardState}）：整帧输入签名命中就整帧跳过，
 * 未命中时再逐行比对文本，只重发真正变化的行。</p>
 */
public class ArenaScoreboard {

    /** 侧边栏最大行数（Minecraft 限制 15 行，留出标题空间） */
    private static final int MAX_LINES = 10;

    private final HilltopVillagePlugin plugin;
    private final ArenaInstance arena;
    private final LanguageManager lang;
    private final Map<UUID, BoardState> boards = new ConcurrentHashMap<>();
    private BukkitTask updateTask;

    public ArenaScoreboard(HilltopVillagePlugin plugin, ArenaInstance arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.lang = plugin.getLanguageManager();
    }

    /**
     * 单名玩家的记分板状态。
     *
     * <p>为什么要缓存「上一帧的输入签名」与「上一帧实际写入的每行文本」两层：</p>
     * <ul>
     *   <li><b>输入签名</b>用于整帧短路 —— 记分板内容全部由这些整数派生，只要它们没变，
     *       重新取 i18n、重新拼字符串、重新 setPrefix 全是白做。静止时（没有击杀、没有波次推进）
     *       这个短路能命中绝大多数 tick。</li>
     *   <li><b>已写入文本</b>用于逐行去重 —— 即使签名变了（比如只多了 1 个击杀），
     *       也只有那一行真的需要重发。{@code PlayerTeam#setPlayerPrefix} 在服务端
     *       <b>没有</b>相等性检查（已反编译核实：只 putfield + onTeamChanged），
     *       文本没变也会重发整个 Team 包。</li>
     * </ul>
     */
    private static final class BoardState {
        private final Scoreboard board;
        private final Objective objective;
        /** 上一帧的输入签名；null 表示尚未渲染过 */
        private String signature;
        /** 上一帧实际写入的标题；null 表示尚未写入过 */
        private String title;
        /** 上一帧实际写入 Team prefix 的文本（已截断到 64 字符）；null 表示该行尚未建立 */
        private final String[] written = new String[MAX_LINES];

        private BoardState(Scoreboard board, Objective objective) {
            this.board = board;
            this.objective = objective;
        }
    }

    /* ========== 生命周期 ========== */

    /** 为玩家创建并显示侧边栏 */
    public void show(Player player) {
        if (!plugin.getConfigManager().getActiveConfig().isScoreboardEnabled()) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = lang.getFor(player, "scoreboard.title");
        Objective objective = board.registerNewObjective("hilltop", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // BoardState 必须与 board 成对新建：written[] 描述的是「这块 board 上当前挂着什么」，
        // 只有成对新建才能保证「新建的 board 上一行都没有」与「written 全 null」这两个前提同时成立
        // （writeLines 里靠 written[i] == null 判断该行是否需要在 board 上收尾，见该方法注释）
        BoardState state = new BoardState(board, objective);
        // 标题已在上面的 registerNewObjective 里写入，记下来免得首帧再 setDisplayName 一次
        state.title = title;
        boards.put(player.getUniqueId(), state);
        player.setScoreboard(board);

        refresh(player, state);
        ensureTaskRunning();
    }

    /** 移除玩家的侧边栏并还原主计分板 */
    public void hide(Player player) {
        if (boards.remove(player.getUniqueId()) == null) return;
        if (player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (boards.isEmpty()) stopTask();
    }

    /** 竞技场重置时清理所有侧边栏 */
    public void clear() {
        for (UUID uuid : new ArrayList<>(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        stopTask();
    }

    private void ensureTaskRunning() {
        if (updateTask != null) return;
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
    }

    private void stopTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /* ========== 刷新 ========== */

    private void refreshAll() {
        if (boards.isEmpty()) {
            stopTask();
            return;
        }
        for (Map.Entry<UUID, BoardState> entry : boards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            BoardState state = entry.getValue();
            // objective 随 BoardState 一起持有，无需再回查 board：少一次哈希查找，也避免
            // 「board 还在但 objective 被摘掉」时拿到 null 后再去 refresh 的空指针路径
            if (state.objective == null) continue;
            refresh(player, state);
        }
    }

    private void refresh(Player player, BoardState state) {
        GameManager gameManager = arena.getGameManager();
        PlayerData data = gameManager.getPlayerData(player.getUniqueId());

        int wave = gameManager.getCurrentWave();
        int totalWaves = arena.getTotalWaves();
        int alive = gameManager.getPlayerCount() - arena.getSpectatorCount();
        int spectators = arena.getSpectatorCount();
        int activeNodes = gameManager.getNodeSystem().getActiveCount();
        int totalNodes = gameManager.getNodeSystem().getTotalCount();
        int aliveVillagers = arena.getVillagerSystem().getAliveCount();
        int totalVillagers = arena.getVillagerSystem().getTotalCount();
        int kills = data == null ? -1 : data.getKills();
        int deaths = data == null ? -1 : data.getDeaths();

        // 输入签名：记分板每一行都由这些整数派生，它们没变就整帧跳过。
        // 用 StringBuilder 而非字符串拼接：这里每秒每玩家只跑一次，
        // 但签名一旦命中就能省下 5-7 次 i18n 取词 + 颜色码翻译 + 逐行 setPrefix（后者每次都发包）。
        //
        // 末尾带上玩家当前 locale：行文本 = i18n 模板 × 这些整数，模板是签名的隐藏输入。
        // /hilltop lang 只改 LanguageManager 里的 locale 映射、不动任何计数，
        // 不把 locale 纳入签名的话，玩家切语言后侧边栏会一直停在旧语言，
        // 直到某个计数恰好变化才刷新（原实现每秒重拼字符串，1 秒内必然跟上）。
        StringBuilder sig = new StringBuilder(64);
        sig.append(wave).append(':').append(totalWaves).append(':').append(alive)
           .append(':').append(spectators).append(':').append(activeNodes).append(':').append(totalNodes)
           .append(':').append(aliveVillagers).append(':').append(totalVillagers)
           .append(':').append(kills).append(':').append(deaths)
           .append(':').append(lang.getPlayerLocale(player));
        String signature = sig.toString();
        if (signature.equals(state.signature)) return;
        state.signature = signature;

        // 标题也是 i18n 派生的（show() 里注册时取一次），既然 locale 已纳入签名，
        // 这里必须一并补上——否则玩家切语言后正文会跟上、标题永远停在旧语言。
        // 只在签名未命中的分支执行，静止时零开销；setDisplayName 同样无相等性检查，
        // 所以先比对再写，避免每秒重发一次 Objective 包。
        String title = lang.getFor(player, "scoreboard.title");
        if (!title.equals(state.title)) {
            state.title = title;
            state.objective.setDisplayName(title);
        }

        // 复用签名阶段已取好的局部变量：原实现在这里把 6 个 getter 又跑了一遍，
        // 其中 getAliveCount() 是全量遍历 CopyOnWriteArrayList + isValid()，属于白跑第二遍。
        List<String> lines = new ArrayList<>();
        lines.add(lang.getFor(player, "scoreboard.wave",
                String.valueOf(wave),
                // 取竞技场定义里的波数（而不是 gameManager 的字段）：后者要等 startGame 才同步成
                // per-arena 值，等待期会先显示全局值，出现「20 波突然变 10 波」的跳变
                String.valueOf(totalWaves)));
        lines.add(lang.getFor(player, "scoreboard.alive", String.valueOf(alive)));
        lines.add(lang.getFor(player, "scoreboard.spectators", String.valueOf(spectators)));
        lines.add(lang.getFor(player, "scoreboard.nodes",
                String.valueOf(activeNodes), String.valueOf(totalNodes)));
        lines.add(lang.getFor(player, "scoreboard.villagers",
                String.valueOf(aliveVillagers), String.valueOf(totalVillagers)));

        if (data != null) {
            lines.add(lang.getFor(player, "scoreboard.kills", String.valueOf(kills)));
            lines.add(lang.getFor(player, "scoreboard.deaths", String.valueOf(deaths)));
        }

        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }

        writeLines(state, lines);
    }

    /**
     * 写入行内容。
     * 每个行使用一个唯一短 entry（§0~§f），文本放在 Team prefix（支持颜色且无长度限制）。
     *
     * <p>逐行比对后再 setPrefix：{@code PlayerTeam#setPlayerPrefix} 没有相等性检查
     * （已反编译核实），不比对的话文本没变也会重发整个 Team 包。</p>
     */
    private void writeLines(BoardState state, List<String> lines) {
        Scoreboard board = state.board;
        Objective objective = state.objective;

        // 清除多余的历史行。
        // written[i] == null 意味着「这一行在 board 上从未建立过」——board 与 BoardState 在
        // show() 里成对新建，而本类是唯一往这块私有计分板注册 Team 的地方（全仓库仅此一处
        // registerNewTeam），因此 written[i] == null 蕴含 board.getTeam("line" + i) == null，
        // 跳过它是安全的，不会漏掉「Team 存在但 written 为 null」的情形。
        for (int i = lines.size(); i < MAX_LINES; i++) {
            if (state.written[i] == null) continue;
            Team stale = board.getTeam("line" + i);
            if (stale != null) stale.unregister();
            state.written[i] = null;
        }

        for (int i = 0; i < lines.size(); i++) {
            String entry = "§" + Integer.toHexString(i);
            Team team = board.getTeam("line" + i);
            if (team == null) {
                team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
                objective.getScore(entry).setScore(lines.size() - i);
            }
            String text = lines.get(i);
            // prefix 上限 64 字符（含颜色码），超长截断
            if (text.length() > 64) text = text.substring(0, 64);
            if (!text.equals(state.written[i])) {
                team.setPrefix(text);
                state.written[i] = text;
            }
        }
    }
}
