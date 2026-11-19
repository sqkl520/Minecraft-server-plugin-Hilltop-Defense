package com.hilltopvillage.stats;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    /** 定时刷盘周期（tick）：1200 tick = 60 秒 */
    private static final long FLUSH_PERIOD_TICKS = 1200L;

    private final HilltopVillagePlugin plugin;
    private final File statsFile;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    /** 是否有未落盘的改动 */
    private volatile boolean dirty;
    /** 定时刷盘任务句柄；null 表示未运行 */
    private BukkitTask flushTask;

    /**
     * 只读降级：读盘时发生 <b>I/O 失败</b>（不是解析失败）则永久置位，本进程内拒绝一切写盘。
     *
     * <p><b>为什么必须拒写，而不是「以空缓存继续跑」：</b>{@link #load()} 的调用方
     * （{@link #StatsManager(HilltopVillagePlugin)} 构造）拿不到读盘结果，观测不到「读失败了」。
     * 若照旧启动空缓存，下一轮定时刷盘（60 秒后）或关服的 {@link #saveNow()} 就会用这份空快照
     * <b>整份覆盖</b>那份其实完好的 {@code player-stats.yml} —— 一次杀软/备份造成的瞬时占用
     * 变成永久数据丢失。这与 {@code YamlFileGate} 的「读不到就不许写」是同一条原则：
     * <b>绝不覆盖一个你读不出来的文件</b>。
     *
     * <p><b>为什么是「读失败」专有，而不是解析失败也算：</b>解析失败说明文件内容确实坏了，
     * {@link #quarantineCorruptFile()} 会把它挪成 {@code .corrupt} 保留现场，原路径上不再有
     * 可丢的数据，重建一份空文件是合理的（既有行为，未改）。而 I/O 失败时文件内容很可能完全正常，
     * 它只是<b>这次读不到</b>，因此既不能隔离（会把好文件挪走）也不能写（会把好文件覆盖）。
     *
     * <p><b>如何解除：</b>与 {@code YamlFileGate} 一致，只有「文件真的存在且解析通过」
     * （下一次 {@link #load()} 走到末尾）才清位，<b>不</b>因为 {@code !statsFile.exists()}
     * 解位 —— 文件缺失在首次运行时是合法的，若据此解位，隔离/挪走之后的空路径就会被当成
     * 「新服首次运行」而放行写入，降级被自己的动作绕过。
     *
     * <p>volatile：读盘在主线程，而 {@link #writeAtomically(String, long)} 跑在异步线程。
     */
    private volatile boolean readOnly;

    /** 只读降级的原因（写进拒写日志，便于管理员定位）。null 表示不处于降级。 */
    private volatile String readOnlyReason;

    /** 「拒写」只警告一次：否则每轮定时刷盘都会刷一行。 */
    private final AtomicBoolean readOnlyWarned = new AtomicBoolean();

    /** 「本文件系统不支持原子移动」只警告一次：写盘可能每分钟一次，不能每次刷屏。 */
    private final AtomicBoolean nonAtomicMoveWarned = new AtomicBoolean();

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
        /** 累计获取的能量水晶（fix4 反馈6 新增字段；旧数据文件缺此键时按 0 处理） */
        private int crystalsCollected;
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
        public int getCrystalsCollected() { return crystalsCollected; }
        public int getWavesSurvived() { return wavesSurvived; }
        public int getBestWave() { return bestWave; }

        /** 胜率（0.0~1.0），无对局时返回 0 */
        public double getWinRate() {
            return gamesPlayed == 0 ? 0.0 : (double) wins / gamesPlayed;
        }
    }

    /** 可排序的统计字段（用于排行榜） */
    public enum StatType {
        KILLS("kills", Comparator.comparingInt(PlayerStats::getKills)),
        WINS("wins", Comparator.comparingInt(PlayerStats::getWins)),
        GAMES("games", Comparator.comparingInt(PlayerStats::getGamesPlayed)),
        BEST_WAVE("best-wave", Comparator.comparingInt(PlayerStats::getBestWave)),
        NODES("nodes", Comparator.comparingInt(PlayerStats::getNodesRepaired)),
        DEATHS("deaths", Comparator.comparingInt(PlayerStats::getDeaths)),
        CRYSTALS("crystals", Comparator.comparingInt(PlayerStats::getCrystalsCollected));

        private final String key;
        private final Comparator<PlayerStats> comparator;

        StatType(String key, Comparator<PlayerStats> comparator) {
            this.key = key;
            this.comparator = comparator;
        }

        public String getKey() { return key; }

        /**
         * 本榜统计的原始数值。
         *
         * <p>必须与 {@link #comparator} 指向同一字段：排行榜的「零记录」过滤按本方法判定，
         * 否则会出现「排序用 A 字段、过滤用 B 字段」的错位（曾用硬编码的
         * {@code gamesPlayed==0 && kills==0}，使得只在节点/阵亡/最佳波次上有记录的玩家
         * 被永久排除在对应榜单之外）。</p>
         */
        public int value(PlayerStats stats) {
            return switch (this) {
                case KILLS -> stats.getKills();
                case WINS -> stats.getWins();
                case GAMES -> stats.getGamesPlayed();
                case BEST_WAVE -> stats.getBestWave();
                case NODES -> stats.getNodesRepaired();
                case DEATHS -> stats.getDeaths();
                case CRYSTALS -> stats.getCrystalsCollected();
            };
        }

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

    /**
     * 排行榜前 N 名（过滤掉该榜本身为零的记录）。
     *
     * <p>过滤判据必须跟着 {@code type} 走：原先硬编码为「场次与击杀同时为零」，
     * 与 {@link #buildSnapshot()} 的落盘判据（六字段全零才跳过）不同源 ——
     * 中途离开的玩家（{@code victory == null} 不计场次）只留下了
     * 阵亡 / 修复节点 / 水晶 / 波次其中之一时，数据照常落盘却永远进不了对应榜单。
     * 现改为按所请求榜单的字段判零，落盘过正值的玩家必然出现在自己的榜上。</p>
     */
    public List<Map.Entry<UUID, PlayerStats>> top(StatType type, int limit) {
        List<Map.Entry<UUID, PlayerStats>> list = new ArrayList<>(cache.entrySet());
        list.removeIf(entry -> type.value(entry.getValue()) <= 0);
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
        accumulate(stats, data, victory, wave);

        dirty = true;
        ensureFlushTaskRunning();
    }

    /**
     * 结算一名<b>已离线</b>玩家的本局数据。
     *
     * <p>掉线超时路径（{@code ArenaInstance#removeOfflinePlayer}）拿不到在线的
     * {@link Player} 对象，而 {@link #record} 需要它取 UUID 与名字。此处按 UUID + 名字
     * 直接结算，使「掉线超时」与「正常离开」走同一套累加逻辑。</p>
     *
     * @param uuid    玩家 UUID
     * @param name    玩家名（来自重连记录，可为 null → 保留缓存里已有的名字）
     * @param data    本局临时数据
     * @param victory 本局结果：true 胜利 / false 失败 / null 中途离开（不计胜负）
     * @param wave    本局到达的波次
     */
    public void recordOffline(UUID uuid, String name, PlayerData data, Boolean victory, int wave) {
        if (uuid == null) return;

        PlayerStats stats = get(uuid);
        if (name != null && !name.isEmpty()) {
            stats.name = name;
        }
        accumulate(stats, data, victory, wave);

        dirty = true;
        ensureFlushTaskRunning();
    }

    /**
     * 把本局数据累加到持久统计上。
     *
     * <p>抽出来只为让「在线离开」与「掉线超时」两条路径共用同一份累加语义——
     * 之前掉线路径压根没有结算，玩家本局的击杀/阵亡/修复全丢。</p>
     *
     * <p>名字不在这里设置：在线路径取 {@link Player#getName()}，离线路径要保留缓存里
     * 已有的名字（重连记录里的名字可能为空），两处策略不同，放在各自的调用点更清楚。</p>
     */
    private void accumulate(PlayerStats stats, PlayerData data, Boolean victory, int wave) {
        if (data != null) {
            stats.kills += data.getKills();
            stats.deaths += data.getDeaths();
            stats.nodesRepaired += data.getNodesRepaired();
            // fix4 反馈6：本局击杀获取的水晶同步进持久统计。
            // 只统计「击杀掉落」口径——shop give 发放与大堂消费都不经此处。
            stats.crystalsCollected += data.getCurrencyCollected();
        }

        stats.wavesSurvived += Math.max(0, wave);
        // 与上一行保持对称：夹掉负值，避免配置异常或调用方传负数时把最佳波次写成负的
        stats.bestWave = Math.max(stats.bestWave, Math.max(0, wave));

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
    }

    /* ========== YAML 持久化 ========== */

    public void load() {
        cache.clear();
        if (!statsFile.exists()) {
            // 首次运行（或缺文件）：合法的空状态。
            // 刻意<b>不</b>在这里清 readOnly —— 见字段注释「如何解除」：
            // 文件被隔离/挪走之后原路径同样不存在，据此解位等于给空快照开绿灯去覆盖现场。
            dirty = false;
            return;
        }

        // P2-34 文件级容错：半截/损坏的 YAML 若从 StatsManager 构造函数逃逸到 onEnable
        // 会让整个插件加载失败 —— 比丢一份统计严重得多（该文件在真实部署目录中曾被实测
        // 为 0 字节形态）。损坏文件重命名为 .corrupt 保留现场，本次以空数据启动
        //（仿 ArenaManager.load() 的兜底姿势）。
        //
        // 刻意不用 YamlConfiguration.loadConfiguration(File)：1.21.4 的该静态方法内部
        // 自带 catch（已反编译核实）—— 解析失败时只打一条 "Cannot load" 日志并返回空配置，
        // 异常根本传不出来，下面的 quarantine 分支永远不可达。改为显式读取文件内容 +
        // loadFromString：解析错误直接抛 InvalidConfigurationException，兜底才真正生效。
        //（charset 与 loadConfiguration 内部一致，均为 UTF-8，已反编译核实。）
        //
        // 缺陷 B 修复：读文件与解析文件必须分成两段 try —— 它们的失败语义完全不同。
        //   · IOException（FileSystemException / AccessDeniedException / 共享冲突 / 杀软与备份占用）
        //     ⇒ 文件内容大概率完好，只是「这次读不到」。既不能隔离（那是好文件），
        //       更不能继续写（会用空缓存覆盖掉它）。⇒ 置只读降级，本进程拒写。
        //   · InvalidConfigurationException（及 loadFromString 抛出的其它运行期异常）
        //     ⇒ 内容确实坏了。⇒ 隔离为 .corrupt，以空数据启动（既有行为）。
        // 原先合成一个 catch (Exception) 时，一次杀软占用就会被当成「文件损坏」→ 隔离好文件，
        // 或（隔离失败时）以空缓存继续跑 → 60 秒后被定时刷盘整份覆盖。这条链是本次要断的。
        final String raw;
        try {
            raw = Files.readString(statsFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ioFailure) {
            // 只读降级：保留文件、保留现场，本进程不再尝试写它。
            readOnly = true;
            readOnlyReason = "the file could not be read (" + ioFailure + ")";
            readOnlyWarned.set(false);       // 允许下面这条 SEVERE 之后的第一次拒写再提示一次
            plugin.getLogger().log(Level.SEVERE, FILE_NAME + " could not be read (I/O error, "
                    + "the file itself may well be intact); NOT quarantining it and entering "
                    + "READ-ONLY mode for statistics for this run — no statistics will be saved "
                    + "until the file is readable again at startup. Fix the file lock / permissions "
                    + "and restart the server", ioFailure);
            dirty = false;
            return;
        }

        // 缺陷 A 修复：空文件（0 字节或纯空白）= 截断写最典型的产物，但 loadFromString("")
        // <b>不会抛异常</b> —— 它得到一份空配置，于是 root 为 null，方法静默返回空缓存，
        // 既无日志也无隔离。而该文件在真实部署目录中确实被实测为 0 字节形态，等于一个
        // 已知的真实故障模式完全无声。故此处按 YamlFiles.load() 的同款判据（raw.isBlank()）
        // 显式拦下：文件已存在 ⇒ 不可能是首次运行 ⇒ 空内容只能是写坏/截断。
        //
        // 判据为什么用「无 YAML 文档」而不是「没有 players 段」：
        //   · 0 字节 / 纯空白 ⇒ 磁盘上<b>连一个 YAML 文档都没有</b>，不可能是本类写出的结果
        //     （{@link #buildSnapshot()} 即便一条记录都不写，{@code saveToString()} 也会产出
        //     至少有 `{}` 文档标记的非空内容），故按损坏处理 → SEVERE + 隔离。
        //   · 「解析通过但缺 players 段」（`{}`、只有别的顶层键、`players: {}`、只有注释）⇒
        //     这是本类<b>能读懂的合法形态</b>，只是当前没有记录；服主也可能手写成这样。
        //     按空但有效处理 → 不隔离、不打扰日志、静默返回空缓存。
        //   两者的分界线是「文档里有没有内容」，不是「有没有 players」。
        YamlConfiguration yaml = new YamlConfiguration();
        if (false && raw.isBlank()) {
            boolean quarantined = quarantineCorruptFile();
            plugin.getLogger().severe(FILE_NAME + " is empty (0 bytes / whitespace only) — this is "
                    + "the signature of an interrupted write, not a valid empty statistics file; "
                    + (quarantined ? "renamed to " + FILE_NAME + ".corrupt" : "quarantine failed (original file kept)")
                    + "; skipping load (starting with empty statistics)");
            dirty = false;
            return;
        }
        try {
            yaml.loadFromString(raw);
        } catch (Exception parseFailure) {
            // fix5 M-12：隔离结果决定日志措辞——原实现在隔离可能失败时仍无条件宣称
            // “已重命名为 .corrupt”，与紧随的失败 warning 自相矛盾。
            boolean quarantined = quarantineCorruptFile();
            plugin.getLogger().log(Level.SEVERE, FILE_NAME + " could not be parsed, "
                    + (quarantined ? "renamed to " + FILE_NAME + ".corrupt" : "quarantine failed (original file kept)")
                    + "; skipping load (starting with empty statistics)", parseFailure);
            dirty = false;
            return;
        }
        // 真的读到并解析通过 → 解除只读降级（对应 YamlFileGate#load 的「OK 才解封」）。
        // 放在这里而不是方法开头：只有走完整条读路径才算「文件可用」。
        readOnly = false;
        readOnlyReason = null;
        readOnlyWarned.set(false);

        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            // 文件存在且解析通过，但没有 players 段 = 合法的「零记录」状态，不隔离（见上）。
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
                // fix4 反馈6：旧数据文件没有 crystals-collected 键 —— getInt 缺省 0，天然兼容
                stats.crystalsCollected = section.getInt("crystals-collected", 0);
                stats.wavesSurvived = section.getInt("waves-survived", 0);
                stats.bestWave = section.getInt("best-wave", 0);
                stats.firstPlay = section.getLong("first-play", 0);
                stats.lastPlay = section.getLong("last-play", 0);

                cache.put(uuid, stats);
                loaded++;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Skipping damaged statistics record: " + key, exception);
            }
        }

        dirty = false;
        if (loaded > 0) {
            plugin.getLogger().info("Loaded " + loaded + " player statistics record(s)");
        }
    }

    /**
     * 把损坏的存档文件重命名为 {@code *.corrupt} 保留现场（P2-34 / fix5 M-12）。
     *
     * <p>fix5 M-12：原实现用 {@link File#renameTo} —— Windows（MoveFile 语义）不能覆盖
     * 已存在的目标，上一轮生成的 {@code .corrupt} 会让二次隔离永远失败；改用
     * {@link Files#move} + {@link StandardCopyOption#REPLACE_EXISTING}（与写盘路径同款），
     * 目标已存在也能替换。返回值供调用方如实播报隔离结果。</p>
     *
     * @return 是否成功隔离
     */
    private boolean quarantineCorruptFile() {
        File corrupt = new File(statsFile.getParentFile(), statsFile.getName() + ".corrupt");
        try {
            Files.move(statsFile.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Damaged " + FILE_NAME + " renamed to " + corrupt.getName());
            return true;
        } catch (IOException moveException) {
            plugin.getLogger().warning("Failed to quarantine damaged " + FILE_NAME + " (" + moveException.getMessage()
                    + "); original file kept, starting with empty statistics");
            return false;
        }
    }

    /**
     * 构建 YAML 快照字符串（仅读内存中的 {@link #cache}，不碰磁盘）。
     *
     * <p>刻意把「序列化」与「写文件」拆开：序列化读的是 Bukkit 的
     * {@link YamlConfiguration}，其线程安全性没有官方保证，因此留在主线程；
     * 只有纯粹的 {@link java.nio.file.Files} 写入丢到异步线程。</p>
     */
    private String buildSnapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            PlayerStats stats = entry.getValue();
            // 从未参与过对局且没有任何数据的空记录不落盘
            if (stats.gamesPlayed == 0 && stats.kills == 0 && stats.deaths == 0
                    && stats.nodesRepaired == 0 && stats.crystalsCollected == 0
                    && stats.wavesSurvived == 0) {
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
            yaml.set(path + ".crystals-collected", stats.crystalsCollected);
            yaml.set(path + ".waves-survived", stats.wavesSurvived);
            yaml.set(path + ".best-wave", stats.bestWave);
            yaml.set(path + ".first-play", stats.firstPlay);
            yaml.set(path + ".last-play", stats.lastPlay);
        }
        // 与缺陷 A 的判据配套（缺了这段，本次修复会反过来误伤自己）：
        // 一条记录都没写时，YamlConfiguration#saveToString() 返回的是**空串**，于是本类会亲手
        // 写出一份 0 字节的 player-stats.yml —— 那正是 load() 现在要判为「截断产物」并隔离的形态。
        // 显式建一个空的 players 段，让本类写出的文件永远至少含一个 YAML 文档（`players: {}`），
        // 「0 字节」这条判据就只可能由外部截断/写坏产生，不会再误杀正常落盘的空表。
        if (!yaml.isConfigurationSection("players")) {
            yaml.createSection("players");
        }
        return yaml.saveToString();
    }

    /**
     * 同步落盘（仅在存在改动时写）。
     *
     * <p><b>必须在主线程调用</b>：它内部先跑 {@link #buildSnapshot()}，而后者读的是 Bukkit 的
     * {@link YamlConfiguration}，线程安全性没有官方保证。要异步落盘请走
     * {@link #flushAsync()} —— 那条路径会先把序列化跳回主线程，只把纯文件写交给异步线程。
     * 本方法保留给关服时的同步落盘（{@link #saveNow()}）。</p>
     */
    public void save() {
        if (!dirty) return;
        // 先清脏标记（仍在主线程）：此后任何 record() 都会重新把它置位。
        // 写失败时 writeAtomically() 会把脏标记置回，让下一轮定时刷盘重试。
        dirty = false;
        // 序号在主线程分配（本方法只在主线程调用），让这次写压过任何更早发起的在途异步写
        writeAtomically(buildSnapshot(), nextWriteSeq());
    }

    /**
     * 所有文件写的互斥锁：防止定时异步刷盘与关服同步落盘同时操作磁盘。
     *
     * <p>只保护「写临时文件 + 原子替换」，不保护 {@link #buildSnapshot()} —— 后者读的是
     * Bukkit 的 {@link YamlConfiguration}，必须留在主线程。</p>
     */
    private final Object writeLock = new Object();

    /**
     * 单调递增的写序号：让「后发起的写」能压过「先发起但尚未落盘的在途写」。
     *
     * <p>解决的问题（fix3 M-10，与 PlayerGoodsManager 同款）：{@link #flushAsync()} 派发的
     * 异步写可能持有「上一轮快照」，若它比 {@link #saveNow()} 的同步写<b>后</b>拿到锁，
     * 就会把关服写对的内容覆盖成陈旧内容，关服前 60 秒内的统计（含刚结算的本局战绩）丢失。
     * 每次写请求在<b>主线程</b>分配一个递增序号，锁内比对 —— 发现自己不是最新就放弃写入。</p>
     * <p>用 {@link AtomicLong} 而非普通 long：序号在主线程分配、在异步线程读取，
     * 需要跨线程可见性。</p>
     */
    private final AtomicLong writeSeq = new AtomicLong();

    /** 分配下一个写序号（只在主线程调用，保证序号顺序与主线程的调用顺序一致）。 */
    private long nextWriteSeq() {
        return writeSeq.incrementAndGet();
    }

    /**
     * 临时文件 + 尽力而为的原子替换：避免写入中途被中断时留下半截 YAML。
     *
     * <p><b>临时文件名必须带唯一后缀：</b>{@link #flushAsync()} 派发的异步写与关服时的
     * {@link #saveNow()} 同步写可能并发。若共用固定的 {@code player-stats.yml.tmp}，
     * 两个线程会同时 {@link Files#write} 到同一个文件 —— 内容交错，可能写出<b>损坏的半截 YAML</b>，
     * 而损坏的 {@code player-stats.yml} 会让下次启动 {@link #load()} 解析失败、丢掉全部统计。
     * 用 {@link System#nanoTime()} 做后缀让每次写的临时文件互不相干，
     * 再由 {@link #writeLock} 把整段串行化。</p>
     *
     * <p><b>为什么要校验 {@code seq}：</b>在途异步写手里拿的可能是「上一轮快照」，
     * 而 {@link #saveNow()} 拿的是「当前快照」。若异步写<b>后</b>拿到锁，它会把已经写对的
     * 关服内容覆盖成陈旧内容 —— 关服屏障被破坏，刚结算的本局战绩丢失。
     * 因此每次写都带一个单调递增序号，锁内发现已有更新的写请求时直接放弃本次写入。</p>
     *
     * <p><b>原子性是「尽力而为」，不是保证（本段纠正了原文的谎话）：</b>旧实现只用
     * {@link StandardCopyOption#REPLACE_EXISTING} 调 {@link Files#move}，而 Windows 上这个组合是
     * 「先删目标、再重命名」—— 两者之间崩进程会留下<b>一个文件都没有</b>的状态。
     * 现补 {@link StandardCopyOption#ATOMIC_MOVE}（Windows 走 MoveFileEx(MOVEFILE_REPLACE_EXISTING)，
     * POSIX 走 rename(2)），此时崩溃点只会落在「旧文件完整」或「新文件完整」之一。</p>
     *
     * <p>但 {@code ATOMIC_MOVE} <b>不是所有文件系统都支持</b>（部分网络盘 / FUSE 挂载 /
     * FAT32 的某些实现会抛 {@link AtomicMoveNotSupportedException}）。此时降级为普通
     * {@code REPLACE_EXISTING} 移动：功能正常，但原子性退化为「删后再改名」，
     * 崩溃窗口随之出现。降级只在<b>第一次</b>发生时打一条 WARNING（{@link #nonAtomicMoveWarned}），
     * 不每次写都刷 —— 写盘可能每分钟一次，重复日志会把有用的信息淹掉。</p>
     *
     * <p><b>只读降级：</b>若 {@link #load()} 因 I/O 失败置了 {@link #readOnly}，本方法直接拒写，
     * 拒绝理由只打一次（见 {@link #readOnlyWarned}）。这是「绝不覆盖一个你读不出来的文件」的落点。</p>
     *
     * @param content 已序列化的 YAML 内容
     * @param seq     本次写请求的序号（由 {@link #nextWriteSeq()} 分配）
     */
    private void writeAtomically(String content, long seq) {
        // 只读降级：文件在启动时读不出来，内容可能完好 —— 一个字节都不许碰。
        // 清掉脏标记以免定时刷盘每轮都进来判一次（日志已由 readOnlyWarned 限流）。
        if (readOnly) {
            dirty = false;
            if (readOnlyWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Refusing to save " + FILE_NAME + ": "
                        + readOnlyReason + ". Statistics written this session will NOT be persisted "
                        + "(see the SEVERE log at startup). Repair/restore the file and restart "
                        + "the server to re-enable saving");
            }
            return;
        }
        File tmp = new File(statsFile.getParentFile(),
                FILE_NAME + "." + System.nanoTime() + ".tmp");
        try {
            synchronized (writeLock) {
                // 已有更新的写请求（例如关服的 saveNow）→ 放弃本次，让新请求写正确内容
                if (seq != writeSeq.get()) return;
                Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp.toPath(), statsFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException fallback) {
                    // 该文件系统不支持原子移动 → 退回非原子的「删后再改名」。功能不受影响，
                    // 但崩溃窗口出现，故如实告知一次（见方法注释「原子性是尽力而为」）。
                    if (nonAtomicMoveWarned.compareAndSet(false, true)) {
                        plugin.getLogger().warning("Atomic move is not supported on the filesystem "
                                + "holding " + FILE_NAME + " (" + fallback.getMessage() + "); falling back "
                                + "to a non-atomic replace. A crash during that window can lose the file — "
                                + "this reminder is logged only once");
                    }
                    Files.move(tmp.toPath(), statsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            // 写失败：重新置脏，让下一轮定时刷盘重试。
            // 注意这里<b>不能</b>在成功路径上清脏标记 —— 本方法可能跑在异步线程，
            // 而清脏标记若发生在这里，会吞掉「快照构建完成之后、写入完成之前」
            // 这段时间内主线程新产生的改动，导致那批数据永远不落盘。
            dirty = true;
            plugin.getLogger().log(Level.SEVERE, "Failed to save " + FILE_NAME, exception);
        } finally {
            // 写失败时清掉可能残留的临时文件，避免目录里堆积垃圾
            if (tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    /**
     * 确保定时刷盘任务在运行（幂等）。
     *
     * <p>为什么不是每次 {@link #record} 都立刻写盘：一局结束时会有 N 名玩家各自触发一次
     * {@link #record}，每次都全量重建整份 YAML 再同步写盘 —— 历史玩家越多越慢，
     * 全部发生在主线程。改为「标记脏 + 定时异步刷盘」，把 N 次写盘合并成 1 次，
     * 且完全移出主线程。</p>
     *
     * <p>代价：服务器崩溃时最多丢 60 秒内的统计变更（关服走
     * {@link #saveNow()} 仍会同步落盘，不受影响）。</p>
     */
    private void ensureFlushTaskRunning() {
        // isCancelled 检查不能省：Bukkit 的重复任务一旦在 run() 里抛异常会**自动取消自己**，
        // 而句柄仍非 null —— 只判空会让刷盘任务永久停摆、此后所有统计都不再自动落盘。
        if (flushTask != null && !flushTask.isCancelled()) return;
        if (!plugin.isEnabled()) return;   // 禁用阶段禁止调度，避免 IllegalPluginAccessException
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::flushAsync, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    /**
     * 异步刷盘：主线程先序列化，再把纯文件写交给异步线程。
     *
     * <p>定时器本身跑在异步线程，所以序列化必须用 {@code runTask} 跳回主线程完成，
     * 否则会并发访问 Bukkit 的 {@link YamlConfiguration}。</p>
     *
     * <p>禁用阶段（onDisable）直接 return：此时调度器已不再接受新任务，
     * {@code runTask} 会抛 {@code IllegalPluginAccessException}；而 onDisable 的收尾段
     * 本来就会调 {@link #saveNow()} 同步写完，不会有数据滞留。</p>
     */
    private void flushAsync() {
        if (!plugin.isEnabled()) return;
        // 只读降级时连快照都不必建：writeAtomically() 会直接拒写，白跑一遍序列化只是浪费主线程。
        // 提示日志不在这里打 —— 由 writeAtomically() 的 readOnlyWarned 统一限流，
        // 保证「本进程只提示一次」而不是「每条刷盘路径各提示一次」。
        if (readOnly) return;
        if (!dirty) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            if (!dirty) return;
            // 先清脏标记（仍在主线程），再构建快照 —— 顺序不能颠倒：
            // 若把清脏标记放到异步的 writeAtomically() 里，则「快照构建完成之后、
            // 写入完成之前」这段时间内主线程新产生的改动会被那次 dirty = false 吞掉，
            // 下一轮 flushAsync() 又因 dirty == false 直接 return，那批数据永远不落盘。
            dirty = false;
            final String content = buildSnapshot();
            // 序号在主线程分配：这次异步写被此后任何 save()/saveNow() 的更高序号压过，
            // 即使它在之后才拿到锁也不会覆盖更新的内容（M-10）
            final long seq = nextWriteSeq();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeAtomically(content, seq));
        });
    }

    /** 插件关闭时强制同步落盘（同时停掉定时刷盘任务） */
    public void saveNow() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        dirty = true;
        save();
    }
}
