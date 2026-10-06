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
 */
public class ArenaScoreboard {

    /** 侧边栏最大行数（Minecraft 限制 15 行，留出标题空间） */
    private static final int MAX_LINES = 10;

    private final HilltopVillagePlugin plugin;
    private final ArenaInstance arena;
    private final LanguageManager lang;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private BukkitTask updateTask;

    public ArenaScoreboard(HilltopVillagePlugin plugin, ArenaInstance arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.lang = plugin.getLanguageManager();
    }

    /* ========== 生命周期 ========== */

    /** 为玩家创建并显示侧边栏 */
    public void show(Player player) {
        if (!plugin.getConfigManager().getActiveConfig().isScoreboardEnabled()) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("hilltop", Criteria.DUMMY,
                lang.getFor(player, "scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);

        refresh(player, board, objective);
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
        for (Map.Entry<UUID, Scoreboard> entry : boards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            Scoreboard board = entry.getValue();
            Objective objective = board.getObjective("hilltop");
            if (objective == null) continue;
            refresh(player, board, objective);
        }
    }

    private void refresh(Player player, Scoreboard board, Objective objective) {
        GameManager gameManager = arena.getGameManager();

        List<String> lines = new ArrayList<>();
        lines.add(lang.getFor(player, "scoreboard.wave",
                String.valueOf(gameManager.getCurrentWave()),
                // 取竞技场定义里的波数（而不是 gameManager 的字段）：后者要等 startGame 才同步成
                // per-arena 值，等待期会先显示全局值，出现「20 波突然变 10 波」的跳变
                String.valueOf(arena.getTotalWaves())));
        lines.add(lang.getFor(player, "scoreboard.alive",
                String.valueOf(gameManager.getPlayerCount() - arena.getSpectatorCount())));
        lines.add(lang.getFor(player, "scoreboard.spectators",
                String.valueOf(arena.getSpectatorCount())));
        lines.add(lang.getFor(player, "scoreboard.nodes",
                String.valueOf(gameManager.getNodeSystem().getActiveCount()),
                String.valueOf(gameManager.getNodeSystem().getTotalCount())));
        lines.add(lang.getFor(player, "scoreboard.villagers",
                String.valueOf(arena.getVillagerSystem().getAliveCount()),
                String.valueOf(arena.getVillagerSystem().getTotalCount())));

        PlayerData data = gameManager.getPlayerData(player.getUniqueId());
        if (data != null) {
            lines.add(lang.getFor(player, "scoreboard.kills", String.valueOf(data.getKills())));
            lines.add(lang.getFor(player, "scoreboard.deaths", String.valueOf(data.getDeaths())));
        }

        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }

        writeLines(board, objective, lines);
    }

    /**
     * 写入行内容。
     * 每个行使用一个唯一短 entry（§0~§f），文本放在 Team prefix（支持颜色且无长度限制）。
     */
    private void writeLines(Scoreboard board, Objective objective, List<String> lines) {
        // 清除多余的历史行
        for (int i = lines.size(); i < MAX_LINES; i++) {
            Team stale = board.getTeam("line" + i);
            if (stale != null) stale.unregister();
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
            team.setPrefix(text.length() > 64 ? text.substring(0, 64) : text);
        }
    }
}
