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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(statsFile.toPath(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            // fix5 M-12：隔离结果决定日志措辞——原实现在隔离可能失败时仍无条件宣称
            // “已重命名为 .corrupt”，与紧随的失败 warning 自相矛盾。
            boolean quarantined = quarantineCorruptFile();
            plugin.getLogger().log(Level.SEVERE, FILE_NAME + " 解析失败，"
                    + (quarantined ? "已重命名为 " + FILE_NAME + ".corrupt" : "隔离失败（原文件保留）")
                    + "并跳过加载（本次以空统计启动）", exception);
            dirty = false;
            return;
        }
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
                // fix4 反馈6：旧数据文件没有 crystals-collected 键 —— getInt 缺省 0，天然兼容
                stats.crystalsCollected = section.getInt("crystals-collected", 0);
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
            plugin.getLogger().warning("损坏的 " + FILE_NAME + " 已重命名为 " + corrupt.getName());
            return true;
        } catch (IOException moveException) {
            plugin.getLogger().warning("无法隔离损坏的 " + FILE_NAME + "（" + moveException.getMessage()
                    + "），本次跳过加载；下次写盘会原子替换该文件");
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
     * 临时文件 + 原子替换：避免写入中途被中断时留下半截 YAML。
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
     * @param content 已序列化的 YAML 内容
     * @param seq     本次写请求的序号（由 {@link #nextWriteSeq()} 分配）
     */
    private void writeAtomically(String content, long seq) {
        File tmp = new File(statsFile.getParentFile(),
                FILE_NAME + "." + System.nanoTime() + ".tmp");
        try {
            synchronized (writeLock) {
                // 已有更新的写请求（例如关服的 saveNow）→ 放弃本次，让新请求写正确内容
                if (seq != writeSeq.get()) return;
                Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), statsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            // 写失败：重新置脏，让下一轮定时刷盘重试。
            // 注意这里<b>不能</b>在成功路径上清脏标记 —— 本方法可能跑在异步线程，
            // 而清脏标记若发生在这里，会吞掉「快照构建完成之后、写入完成之前」
            // 这段时间内主线程新产生的改动，导致那批数据永远不落盘。
            dirty = true;
            plugin.getLogger().log(Level.SEVERE, "无法保存 " + FILE_NAME, exception);
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
