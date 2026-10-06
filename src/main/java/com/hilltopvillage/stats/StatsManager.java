package com.hilltopvillage.stats;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 玩家统计持久化（对应 BedWars1058 的 {@code PlayerStats} + 数据库层）。
 *
 * <p>按用户决策使用 <b>YAML 文件存储</b>（{@code player-stats.yml}），后续可平滑替换为数据库实现。</p>
 *
 * <p>写入时机（参照 BedWars1058 的「内存累加 → 离开竞技场时落盘」）：
 * 玩家离开竞技场时累计本局数据并写盘。中途离开只累计击杀/阵亡/修复，不计胜负。</p>
 */
public class StatsManager {

    private static final String FILE_NAME = "player-stats.yml";

    private final HilltopVillagePlugin plugin;
    private final File statsFile;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    /** 是否有未落盘的改动 */
    private volatile boolean dirty;

    public StatsManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.statsFile = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    /* ========== 数据结构 ========== */

    /** 单个玩家的持久统计 */
    public static final class PlayerStats {
        private String name = "";
        private int gamesPlayed;
        private int wins;
        private int losses;
        private int kills;
        private int deaths;
        private int nodesRepaired;
        private int wavesSurvived;
        private int bestWave;
        private long firstPlay;
        private long lastPlay;

        public String getName() { return name; }
        public int getGamesPlayed() { return gamesPlayed; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public int getKills() { return kills; }
        public int getDeaths() { return deaths; }
        public int getNodesRepaired() { return nodesRepaired; }
        public int getWavesSurvived() { return wavesSurvived; }
        public int getBestWave() { return bestWave; }
        public long getFirstPlay() { return firstPlay; }
        public long getLastPlay() { return lastPlay; }

        /** 胜率（0.0~1.0），无对局时返回 0 */
        public double getWinRate() {
            return gamesPlayed == 0 ? 0.0 : (double) wins / gamesPlayed;
        }

        /** 平均每局击杀 */
        public double getKillsPerGame() {
            return gamesPlayed == 0 ? 0.0 : (double) kills / gamesPlayed;
        }
    }

    /** 可排序的统计字段（用于排行榜） */
    public enum StatType {
        KILLS("kills", Comparator.comparingInt(PlayerStats::getKills)),
        WINS("wins", Comparator.comparingInt(PlayerStats::getWins)),
        GAMES("games", Comparator.comparingInt(PlayerStats::getGamesPlayed)),
        BEST_WAVE("best-wave", Comparator.comparingInt(PlayerStats::getBestWave)),
        NODES("nodes", Comparator.comparingInt(PlayerStats::getNodesRepaired)),
        DEATHS("deaths", Comparator.comparingInt(PlayerStats::getDeaths));

        private final String key;
        private final Comparator<PlayerStats> comparator;

        StatType(String key, Comparator<PlayerStats> comparator) {
            this.key = key;
            this.comparator = comparator;
        }

        public String getKey() { return key; }

        /** 降序比较器（数值大的排前面） */
        public Comparator<PlayerStats> descending() {
            return comparator.reversed();
        }

        public static StatType fromKey(String key) {
            for (StatType type : values()) {
                if (type.key.equalsIgnoreCase(key)) return type;
            }
            return null;
        }

        public static String allKeys() {
            StringBuilder sb = new StringBuilder();
            for (StatType type : values()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(type.key);
            }
            return sb.toString();
        }
    }

    /* ========== 查询 ========== */

    /** 取玩家统计（不存在则创建空记录，不落盘） */
    public PlayerStats get(UUID uuid) {
        return cache.computeIfAbsent(uuid, k -> new PlayerStats());
    }

    /** 按名字模糊查找（用于 /hilltop stats <玩家名>） */
    public PlayerStats findByName(String name) {
        if (name == null) return null;
        for (PlayerStats stats : cache.values()) {
            if (stats.getName() != null && stats.getName().equalsIgnoreCase(name)) return stats;
        }
        return null;
    }

    /** 排行榜前 N 名（过滤掉零记录） */
    public List<Map.Entry<UUID, PlayerStats>> top(StatType type, int limit) {
        List<Map.Entry<UUID, PlayerStats>> list = new ArrayList<>(cache.entrySet());
        list.removeIf(entry -> entry.getValue().getGamesPlayed() == 0
                && entry.getValue().getKills() == 0);
        list.sort((a, b) -> type.descending().compare(a.getValue(), b.getValue()));
        return list.subList(0, Math.min(limit, list.size()));
    }

    /* ========== 结算 ========== */

    /**
     * 结算一名玩家的本局数据到持久统计。
     *
     * @param player  玩家
     * @param data    本局临时数据
     * @param victory 本局结果：true 胜利 / false 失败 / null 中途离开（不计胜负）
     * @param wave    本局到达的波次
     */
    public void record(Player player, PlayerData data, Boolean victory, int wave) {
        if (player == null) return;

        PlayerStats stats = get(player.getUniqueId());
        stats.name = player.getName();

        if (data != null) {
            stats.kills += data.getKills();
            stats.deaths += data.getDeaths();
            stats.nodesRepaired += data.getNodesRepaired();
        }

        stats.wavesSurvived += Math.max(0, wave);
        stats.bestWave = Math.max(stats.bestWave, wave);

        if (victory != null) {
            stats.gamesPlayed++;
            if (victory) {
                stats.wins++;
            } else {
                stats.losses++;
            }
            long now = System.currentTimeMillis();
            if (stats.firstPlay == 0) stats.firstPlay = now;
            stats.lastPlay = now;
        }

        dirty = true;
        save();
    }

    /* ========== YAML 持久化 ========== */

    public void load() {
        cache.clear();
        if (!statsFile.exists()) {
            dirty = false;
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            dirty = false;
            return;
        }

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;

                PlayerStats stats = new PlayerStats();
                stats.name = section.getString("name", "");
                stats.gamesPlayed = section.getInt("games-played", 0);
                stats.wins = section.getInt("wins", 0);
                stats.losses = section.getInt("losses", 0);
                stats.kills = section.getInt("kills", 0);
                stats.deaths = section.getInt("deaths", 0);
                stats.nodesRepaired = section.getInt("nodes-repaired", 0);
                stats.wavesSurvived = section.getInt("waves-survived", 0);
                stats.bestWave = section.getInt("best-wave", 0);
                stats.firstPlay = section.getLong("first-play", 0);
                stats.lastPlay = section.getLong("last-play", 0);

                cache.put(uuid, stats);
                loaded++;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的统计记录: " + key, exception);
            }
        }

        dirty = false;
        if (loaded > 0) {
            plugin.getLogger().info("已加载 " + loaded + " 条玩家统计记录");
        }
    }

    /** 将内存统计写入磁盘（仅在存在改动时写） */
    public void save() {
        if (!dirty) return;

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            PlayerStats stats = entry.getValue();
            // 从未参与过对局且没有任何数据的空记录不落盘
            if (stats.gamesPlayed == 0 && stats.kills == 0 && stats.deaths == 0
                    && stats.nodesRepaired == 0 && stats.wavesSurvived == 0) {
                continue;
            }

            String path = "players." + entry.getKey();
            yaml.set(path + ".name", stats.name);
            yaml.set(path + ".games-played", stats.gamesPlayed);
            yaml.set(path + ".wins", stats.wins);
            yaml.set(path + ".losses", stats.losses);
            yaml.set(path + ".kills", stats.kills);
            yaml.set(path + ".deaths", stats.deaths);
            yaml.set(path + ".nodes-repaired", stats.nodesRepaired);
            yaml.set(path + ".waves-survived", stats.wavesSurvived);
            yaml.set(path + ".best-wave", stats.bestWave);
            yaml.set(path + ".first-play", stats.firstPlay);
            yaml.set(path + ".last-play", stats.lastPlay);
        }

        try {
            yaml.save(statsFile);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法保存 " + FILE_NAME, exception);
        }
    }

    /** 插件关闭时强制落盘 */
    public void saveNow() {
        dirty = true;
        save();
    }

    /** 供排行榜展示用：UUID → 名字的映射（含离线玩家） */
    public Map<UUID, String> nameMap() {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getName());
        }
        return map;
    }

    /** 在线玩家判定辅助（排行榜展示时标记） */
    public boolean isOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.isOnline();
    }
}
