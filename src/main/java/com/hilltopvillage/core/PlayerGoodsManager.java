package com.hilltopvillage.core;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 玩家进入竞技场前的状态快照管理（对应 BedWars1058 的 PlayerGoods）。
 *
 * <p>保存内容：背包、盔甲、经验、等级、血量、饱食度、游戏模式、飞行权限、药水效果。</p>
 *
 * <p>与 BedWars1058 的差异：其 PlayerGoods 仅存内存（服务器崩溃 = 玩家物品永久丢失），
 * 本实现落盘到 {@code player-goods.yml}，崩溃后重启仍可归还。</p>
 */
public final class PlayerGoodsManager {

    private static final String FILE_NAME = "player-goods.yml";
    /**
     * 落盘防抖窗口（tick）：1 tick = 下一 tick 立即写。
     *
     * <p><b>为什么是 1 而不是更长：</b>防抖的收益来自「同一 tick 内的多次调用合并成一次写」——
     * 一局结束时 {@code finishSession} 在一个 tick 里循环 {@code removePlayer}，
     * 6 名玩家会连发 6 次 save/restore，1 tick 的窗口足以把它们合并成 1 次写，
     * 合并收益已全部拿到。</p>
     *
     * <p>而窗口越长，数据不一致的窗口就越长：{@code restore()} 只把快照从内存移除，
     * 磁盘上仍是旧内容；若这段时间内服务器非优雅关闭，重启后会<b>再归还一次（物品翻倍）</b>。
     * 窗口从 2 秒缩到 1 tick（50ms），这个不一致窗口缩小了 40 倍，
     * 而合并收益不变 —— 没有理由留长窗口。</p>
     */
    private static final long SAVE_DEBOUNCE_TICKS = 1L;
    /** 玩家背包容量（getContents() 的固定长度） */
    private static final int INVENTORY_SIZE = 36;
    /** 盔甲槽位数量（头盔/胸甲/护腿/靴子） */
    private static final int ARMOR_SIZE = 4;

    private final HilltopVillagePlugin plugin;
    private final File goodsFile;
    private Map<UUID, Snapshot> goods = new ConcurrentHashMap<>();
    /**
     * 是否已有待执行的防抖落盘任务。
     *
     * <p>刻意不加 volatile：该字段只在主线程读写 —— {@link #save(Player)} / {@link #restore(Player)}
     * 本就只能在主线程调用（直接操作 {@code player.getInventory()}），{@link #flushToDisk()} 由
     * 主线程的 Bukkit 调度器回调，{@link #saveNow()} 也由主线程（onDisable / PlayerQuitEvent）调用。
     * 异步线程只拿 {@code yaml} 去写文件，不碰这个标志。</p>
     */
    private boolean saveScheduled;
    /**
     * 尚未到期的防抖任务句柄；{@link #saveNow()} 会取消它，避免同步写完后又跑一次异步写。
     *
     * <p>取消不是「为了正确性」而是「为了少一次多余的磁盘 I/O」：即便不取消，
     * 两次写的也是同一份内存快照，内容相同、幂等无害。但句柄留着能让
     * {@link #saveNow()} 的语义更干净（写完即无待办）。</p>
     */
    private BukkitTask pendingSaveTask;
    /**
     * 所有文件写的互斥锁：防止防抖异步写与关服同步写同时操作磁盘。
     *
     * <p>注意这把锁只保护「序列化 + 写文件」，不保护 {@link #buildYaml()} —— 构建
     * {@link YamlConfiguration} 读的是 Bukkit 对象，必须留在主线程。</p>
     */
    private final Object writeLock = new Object();
    /**
     * 单调递增的写序号：让「后发起的写」能压过「先发起但尚未落盘的在途写」。
     *
     * <p>解决的问题：异步写可能持有「上一 tick 的快照」，若它比 {@link #saveNow()} 的同步写
     * <b>后</b>拿到锁，就会把关服写对的内容覆盖成陈旧内容。每次写请求在<b>主线程</b>
     * 分配一个递增序号，锁内比对 —— 发现自己不是最新就放弃写入。</p>
     * <p>用 {@link AtomicLong} 而非普通 long：序号在主线程分配、在异步线程读取，
     * 需要跨线程可见性。</p>
     */
    private final AtomicLong writeSeq = new AtomicLong();

    /** 分配下一个写序号（只在主线程调用，保证序号顺序与主线程的调用顺序一致）。 */
    private long nextWriteSeq() {
        return writeSeq.incrementAndGet();
    }

    public PlayerGoodsManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.goodsFile = new File(plugin.getDataFolder(), FILE_NAME);
        loadFromDisk();
    }

    // ========== 快照数据结构 ==========

    /** 单个玩家的状态快照 */
    public static final class Snapshot {
        private String playerName = "";
        private ItemStack[] contents;
        private ItemStack[] armor;
        private ItemStack offHand;
        private float exp;
        private int level;
        private double health;
        private int food;
        private GameMode gameMode = GameMode.SURVIVAL;
        private boolean allowFlight;
        private boolean flying;
        private Collection<PotionEffect> effects = new ArrayList<>();

        public String getPlayerName() { return playerName; }
    }

    // ========== 保存 / 归还 ==========

    /**
     * 保存玩家当前状态并清空其背包（进入竞技场前调用）。
     * 若该玩家已有未归还的快照，则保留原快照不覆盖（避免连续加入时丢失原始物品）。
     *
     * @return true 表示本次新建了快照
     */
    public boolean save(Player player) {
        UUID uuid = player.getUniqueId();
        if (goods.containsKey(uuid)) {
            // 已有快照：只清空当前背包，不覆盖原始快照
            clearInventory(player);
            return false;
        }

        Snapshot snapshot = new Snapshot();
        snapshot.playerName = player.getName();
        snapshot.contents = player.getInventory().getContents().clone();
        snapshot.armor = player.getInventory().getArmorContents().clone();
        snapshot.offHand = player.getInventory().getItemInOffHand().clone();
        snapshot.exp = player.getExp();
        snapshot.level = player.getLevel();
        snapshot.health = player.getHealth();
        snapshot.food = player.getFoodLevel();
        snapshot.gameMode = player.getGameMode();
        snapshot.allowFlight = player.getAllowFlight();
        snapshot.flying = player.isFlying();
        snapshot.effects = new ArrayList<>(player.getActivePotionEffects());

        goods.put(uuid, snapshot);
        // 只登记一次防抖落盘：新建快照这一瞬间数据已进内存，写盘可以延后合并
        scheduleSaveToDisk();

        // 保存后清空状态，准备进入游戏
        clearInventory(player);
        player.setExp(0);
        player.setLevel(0);
        player.setFireTicks(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        return true;
    }

    /**
     * 归还玩家此前保存的状态（离开竞技场时调用）。
     *
     * @return true 表示成功归还；false 表示该玩家没有快照
     */
    public boolean restore(Player player) {
        Snapshot snapshot = goods.remove(player.getUniqueId());
        if (snapshot == null) return false;

        clearInventory(player);

        if (snapshot.contents != null) {
            player.getInventory().setContents(snapshot.contents);
        }
        if (snapshot.armor != null) {
            player.getInventory().setArmorContents(snapshot.armor);
        }
        if (snapshot.offHand != null) {
            player.getInventory().setItemInOffHand(snapshot.offHand);
        }
        player.setExp(snapshot.exp);
        player.setLevel(snapshot.level);
        player.setFoodLevel(snapshot.food);
        player.setGameMode(snapshot.gameMode);
        player.setAllowFlight(snapshot.allowFlight);
        player.setFlying(snapshot.allowFlight && snapshot.flying);

        // 血量恢复需在模式设置后，且不能超过当前最大血量
        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.max(1.0, Math.min(snapshot.health, maxHealth)));

        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : snapshot.effects) {
            player.addPotionEffect(effect);
        }

        scheduleSaveToDisk();
        return true;
    }

    /** 该玩家是否有未归还的快照 */
    public boolean has(Player player) {
        return goods.containsKey(player.getUniqueId());
    }

    /** 所有持有快照的玩家 UUID（供关服/启动时检查） */
    public Collection<UUID> pendingUuids() {
        return new ArrayList<>(goods.keySet());
    }

    // ========== 内部工具 ==========

    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    // ========== YAML 持久化 ==========

    private void loadFromDisk() {
        goods = new ConcurrentHashMap<>();
        if (!goodsFile.exists()) return;

        // P2-34 文件级容错：半截/损坏的 YAML 若逃逸到 onEnable 会让整个插件加载失败 ——
        // 比丢一份快照严重得多。损坏文件重命名为 .corrupt 保留现场，本次以空数据启动
        //（仿 ArenaManager.load() 的兜底姿势）。
        //
        // 刻意不用 YamlConfiguration.loadConfiguration(File)：1.21.4 的该静态方法内部
        // 自带 catch（已反编译核实）—— 解析失败时只打一条 "Cannot load" 日志并返回空配置，
        // 异常根本传不出来，下面的 quarantine 分支永远不可达。改为显式读取文件内容 +
        // loadFromString：解析错误直接抛 InvalidConfigurationException，兜底才真正生效。
        //（charset 与 loadConfiguration 内部一致，均为 UTF-8，已反编译核实。）
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(goodsFile.toPath(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            // fix5 M-12：隔离结果决定日志措辞——原实现在隔离可能失败时仍无条件宣称
            // “已重命名为 .corrupt”，与紧随的失败 warning 自相矛盾。
            boolean quarantined = quarantineCorruptFile();
            plugin.getLogger().log(Level.SEVERE, FILE_NAME + " 解析失败，"
                    + (quarantined ? "已重命名为 " + FILE_NAME + ".corrupt" : "隔离失败（原文件保留）")
                    + "并跳过加载（本次以空快照启动）", exception);
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return;

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;

                Snapshot snapshot = new Snapshot();
                snapshot.playerName = section.getString("player-name", "");

                snapshot.contents = readItems(section, "contents", INVENTORY_SIZE);
                snapshot.armor = readItems(section, "armor", ARMOR_SIZE);

                snapshot.offHand = section.getItemStack("off-hand");
                snapshot.exp = (float) section.getDouble("exp", 0.0);
                snapshot.level = section.getInt("level", 0);
                snapshot.health = section.getDouble("health", 20.0);
                snapshot.food = section.getInt("food", 20);
                snapshot.allowFlight = section.getBoolean("allow-flight", false);
                snapshot.flying = section.getBoolean("flying", false);

                String mode = section.getString("game-mode");
                snapshot.gameMode = mode == null ? GameMode.SURVIVAL : parseGameMode(mode);

                List<PotionEffect> effects = new ArrayList<>();
                for (Map<?, ?> raw : section.getMapList("effects")) {
                    PotionEffect effect = readEffect(raw);
                    if (effect != null) effects.add(effect);
                }
                snapshot.effects = effects;

                goods.put(uuid, snapshot);
                loaded++;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的物品快照: " + key, exception);
            }
        }

        if (loaded > 0) {
            plugin.getLogger().info("已从 " + FILE_NAME + " 恢复 " + loaded + " 份未归还的玩家物品快照");
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
        File corrupt = new File(goodsFile.getParentFile(), goodsFile.getName() + ".corrupt");
        try {
            Files.move(goodsFile.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("损坏的 " + FILE_NAME + " 已重命名为 " + corrupt.getName());
            return true;
        } catch (IOException moveException) {
            plugin.getLogger().warning("无法隔离损坏的 " + FILE_NAME + "（" + moveException.getMessage()
                    + "），本次跳过加载；下次写盘会原子替换该文件");
            return false;
        }
    }

    /**
     * 构建 goods 的 YAML 快照（仅读内存，不碰磁盘）。
     *
     * <p>刻意把「序列化」与「写文件」拆开：序列化读的是 Bukkit 的
     * {@link YamlConfiguration}，线程安全性没有官方保证，因此留在主线程；
     * 只有纯粹的 {@link java.nio.file.Files} 写入丢到异步线程。</p>
     */
    private YamlConfiguration buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Snapshot> entry : goods.entrySet()) {
            String path = "players." + entry.getKey();
            Snapshot snapshot = entry.getValue();
            yaml.set(path + ".player-name", snapshot.playerName);

            // 按槽位索引存储：背包数组含 null（空格子），不能用 List.of（不允许 null 元素）
            writeItems(yaml, path + ".contents", snapshot.contents);
            writeItems(yaml, path + ".armor", snapshot.armor);

            yaml.set(path + ".off-hand", snapshot.offHand);
            yaml.set(path + ".exp", (double) snapshot.exp);
            yaml.set(path + ".level", snapshot.level);
            yaml.set(path + ".health", snapshot.health);
            yaml.set(path + ".food", snapshot.food);
            yaml.set(path + ".game-mode", snapshot.gameMode == null ? null : snapshot.gameMode.name());
            yaml.set(path + ".allow-flight", snapshot.allowFlight);
            yaml.set(path + ".flying", snapshot.flying);

            List<Map<String, Object>> effects = new ArrayList<>();
            for (PotionEffect effect : snapshot.effects) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", effect.getType().getName());
                map.put("duration", effect.getDuration());
                map.put("amplifier", effect.getAmplifier());
                map.put("ambient", effect.isAmbient());
                map.put("particles", effect.hasParticles());
                effects.add(map);
            }
            yaml.set(path + ".effects", effects);
        }
        return yaml;
    }

    /**
     * 临时文件 + 原子替换：避免写入中途被中断时留下半截 YAML。
     *
     * <p><b>为什么临时文件名要带唯一后缀：</b>防抖到期的异步写与关服时的
     * {@link #saveNow()} 同步写可能并发。若共用固定的 {@code .tmp}，两个线程会同时
     * {@link Files#write} 到同一个文件 —— 内容交错，可能写出<b>损坏的半截 YAML</b>；
     * 而损坏的 {@code player-goods.yml} 会让下次启动 {@link #loadFromDisk()} 解析失败，
     * 直接丢掉全部玩家的物品快照。用 {@link System#nanoTime()} 做后缀让每次写的临时文件
     * 互不相干，再由 {@link #writeLock} 把「写临时文件 + 原子替换」整段串行化。</p>
     *
     * <p><b>为什么 {@code saveToString()} 也放进锁里：</b>它读的是 Bukkit 的
     * {@link YamlConfiguration}，序列化本身没有官方并发保证，串行化更稳。
     * 但 {@link #buildYaml()} 构建配置对象那一步仍然留在主线程，不挪进锁内。</p>
     *
     * <p><b>为什么要校验 {@code seq}（不是「与防抖同级」的折中）：</b>异步写手里拿的可能是
     * 「上一 tick 的快照」，而 {@link #saveNow()} 拿的是「当前快照」。若异步写<b>后</b>拿到锁，
     * 它会把已经写对的关服内容覆盖成陈旧内容 —— 那不是「丢一个 tick 的增量」，而是
     * <b>关服屏障被破坏</b>：玩家已归还物品的删除记录被回退，下次登录会再归还一次（物品翻倍）。
     * 因此每次写都带一个单调递增序号，锁内发现已有更新的写请求时直接放弃本次写入。</p>
     *
     * @param seq 本次写请求的序号（由 {@link #nextWriteSeq()} 分配）
     */
    private void writeYaml(YamlConfiguration yaml, long seq) {
        // 临时文件名带唯一后缀：防抖异步写与 saveNow() 同步写可能并发，
        // 共用固定的 ".tmp" 会让两个线程写同一个文件 → 内容交错、写出损坏的 YAML，
        // 而损坏的 player-goods.yml 会让下次启动解析失败、丢掉全部物品快照。
        File tmp = new File(goodsFile.getParentFile(),
                FILE_NAME + "." + System.nanoTime() + ".tmp");
        try {
            synchronized (writeLock) {
                // 已有更新的写请求（例如关服的 saveNow）→ 放弃本次，让新请求写正确内容
                if (seq != writeSeq.get()) return;
                Files.write(tmp.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), goodsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法保存 " + FILE_NAME, exception);
        } finally {
            // 写失败时清掉可能残留的临时文件，避免目录里堆积垃圾
            if (tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    /**
     * 调度一次落盘（防抖合并）。
     *
     * <p>为什么不是每次 save/restore 都立刻写盘：一局结束时会有 N 名玩家先后离场，
     * 每次都全量重建整份 YAML 再同步写盘 —— 含所有历史玩家的背包快照，
     * 历史玩家越多越慢，且全部发生在主线程。改为「1 tick 防抖窗口内合并成一次」，
     * 序列化仍留主线程，文件写丢到异步线程。</p>
     *
     * <p>代价：服务器崩溃时最多丢 1 tick（约 50ms）内的变更。关服走
     * {@link #saveNow()} 仍会同步落盘，不受影响。</p>
     *
     * <p>只在主线程调用（save / restore 都是主线程语义）。</p>
     */
    private void scheduleSaveToDisk() {
        if (saveScheduled) return;
        // 禁用阶段（onDisable）禁止再调度任务：与 ArenaInstance#scheduleReset 同一范式 ——
        // 此时插件正在关闭，延迟任务可能被取消或抛 IllegalPluginAccessException，
        // 那样这次改动就彻底丢了。改为当场同步写完，保证关服路径不依赖调度器。
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        saveScheduled = true;
        pendingSaveTask = Bukkit.getScheduler().runTaskLater(plugin, this::flushToDisk, SAVE_DEBOUNCE_TICKS);
    }

    /** 防抖窗口到期：主线程序列化，异步线程写文件。 */
    private void flushToDisk() {
        saveScheduled = false;
        pendingSaveTask = null;
        if (!plugin.isEnabled()) {
            // 禁用阶段：禁止再调度任务，直接同步写完
            writeYaml(buildYaml(), nextWriteSeq());
            return;
        }
        final YamlConfiguration yaml = buildYaml();
        final long seq = nextWriteSeq();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeYaml(yaml, seq));
    }

    /**
     * 强制同步落盘，并作废尚未到期的防抖任务。
     *
     * <p>两个调用场景：① 插件关闭（`onDisable`）；② 玩家退出服务器（`PlayerQuitEvent`）。</p>
     *
     * <p><b>为什么退出时也必须同步写（不能靠防抖）：</b>防抖引入了一条玩家可主动制造的
     * 刷物品路径 ——</p>
     * <pre>
     *   restore(P)  → goods.remove(P)，只登记了防抖任务
     *   P 立刻 /quit → Bukkit 把「已归还物品」的背包写进 P 的 .dat
     *   下一个 tick 之前服务器非优雅关闭（kill / 断电 / OOM）→ 防抖任务未执行
     *   → 磁盘上仍是 P 的【进竞技场前】旧快照
     *   重启 → onPlayerJoin 的 has(P) 为 true → 再归还一次 → 物品翻倍
     * </pre>
     * <p>退出事件是这条时序里唯一能稳定插桩的位置：玩家一走就当场写完，之后无论进程怎么死，
     * 磁盘状态都与内存一致。写盘仍走 {@link #writeYaml}（唯一临时名 + 锁），只是不等防抖窗口。</p>
     */
    public void saveNow() {
        cancelScheduledSave();
        // 序号在这里分配（主线程），锁内校验 —— 使任何更早发起、尚未落盘的在途异步写放弃写入，
        // 从而不会把这次写对的内容覆盖成陈旧快照
        writeYaml(buildYaml(), nextWriteSeq());
    }

    /** 取消尚未到期的防抖任务（幂等）；不清 saveScheduled 之外的任何状态。 */
    private void cancelScheduledSave() {
        saveScheduled = false;
        if (pendingSaveTask != null) {
            pendingSaveTask.cancel();
            pendingSaveTask = null;
        }
    }

    /** 按槽位索引写入物品（跳过空格子，避免 null 元素无法序列化） */
    private void writeItems(YamlConfiguration yaml, String path, ItemStack[] items) {
        yaml.set(path, null);
        if (items == null) return;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                yaml.set(path + "." + i, item);
            }
        }
    }

    /** 按槽位索引读取物品 */
    private ItemStack[] readItems(ConfigurationSection section, String path, int size) {
        ItemStack[] items = new ItemStack[size];
        ConfigurationSection itemsSection = section.getConfigurationSection(path);
        if (itemsSection == null) return items;
        for (String key : itemsSection.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                if (index >= 0 && index < size) {
                    items[index] = itemsSection.getItemStack(key);
                }
            } catch (NumberFormatException ignored) {
                // 非法槽位键，跳过
            }
        }
        return items;
    }

    private static PotionEffect readEffect(Map<?, ?> raw) {
        Object typeName = raw.get("type");
        if (!(typeName instanceof String name)) return null;
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type == null) return null;

        int duration = raw.get("duration") instanceof Number n ? n.intValue() : 200;
        int amplifier = raw.get("amplifier") instanceof Number n ? n.intValue() : 0;
        boolean ambient = !(raw.get("ambient") instanceof Boolean b) || b;
        boolean particles = !(raw.get("particles") instanceof Boolean b) || b;
        return new PotionEffect(type, duration, amplifier, ambient, particles);
    }

    private static GameMode parseGameMode(String name) {
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return GameMode.SURVIVAL;
        }
    }
}
