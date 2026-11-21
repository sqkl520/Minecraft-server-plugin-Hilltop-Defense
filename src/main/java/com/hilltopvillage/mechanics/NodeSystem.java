package com.hilltopvillage.mechanics;

import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
import com.hilltopvillage.util.YamlFiles;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class NodeSystem {

    /**
     * nodes.yml 的只读降级标记（按绝对路径）。
     *
     * <p><b>为什么是静态的：</b>{@link #writeNodes} / {@link #clearNodesForWorld} /
     * {@link #removeNodeEntryForWorld} 都是 static（竞技场管理 GUI 对未启用竞技场也要能改文件），
     * 拿不到实例上的 gate。用「路径 → 只读」的静态表让这些静态写入口共享同一份降级结论。</p>
     *
     * <p>键是绝对路径：{@code dataFolder} 在一个进程内不变，不会泄漏成多个条目。</p>
     */
    private static final Set<String> NODES_READ_ONLY = ConcurrentHashMap.newKeySet();

    /** 静态写入口的只读判定：命中标记即拒写（并打一行 warning）。 */
    private static boolean nodesWritable(File nodesFile, Logger logger) {
        if (!NODES_READ_ONLY.contains(nodesFile.getAbsolutePath())) return true;
        logger.warning("Refusing to write nodes.yml: it was unreadable at load time");
        return false;
    }

    /**
     * 清除 nodes.yml 的只读降级标记（仅供 {@code /hilltop reloadconfig} 调用）。
     *
     * <p><b>这是全局行为：</b>{@link #NODES_READ_ONLY} 是静态集合（静态写入口 {@link #writeNodes} /
     * {@link #clearNodesForWorld} / {@link #removeNodeEntryForWorld} 共享），清空后<b>本进程内所有世界 /
     * 所有竞技场</b>的 nodes.yml 写入口一并解封，不只影响某个竞技场实例。</p>
     *
     * <p>解封只是清除缓存态，不保证文件已修好：下一次 {@link #loadNodesFromConfig()}（或任一静态读入口）
     * 读盘仍会重新判定，文件依旧损坏时会把路径重新加入 {@link #NODES_READ_ONLY} 并重新打日志。</p>
     */
    public void resetReadOnlyGates() {
        NODES_READ_ONLY.clear();
    }

    private final GameManager gameManager;
    private final ConcurrentHashMap<Location, NodeData> nodes;
    private final Set<NodeData> activeNodes;
    private final Set<Material> nodeBlockTypes;
    private double baseHealth;
    private double buffRadius;
    private final Map<PotionEffectType, Integer> buffEffects;
    private int buffRefreshTicks = 60;
    private int buffStackCap = 3;
    private int buffDurationTicks = 100;
    private BukkitRunnable buffTask;

    public NodeSystem(GameManager gameManager) {
        this.gameManager = gameManager;
        this.nodes = new ConcurrentHashMap<>();
        this.activeNodes = ConcurrentHashMap.newKeySet();
        this.nodeBlockTypes = new HashSet<>();
        this.buffEffects = new LinkedHashMap<>();

        loadConfig();
        loadNodesFromConfig();
    }

    /**
     * 创建规范化的Location（只包含world和整数坐标，忽略pitch/yaw）
     * 用于HashMap键的一致性比较，避免视角差异导致查询失败
     */
    private static Location normalizeLocation(Location location) {
        return new Location(
            location.getWorld(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
    }

    private void loadConfig() {
        // 统一从 GameConfig 读取，确保配置中心 GUI 修改后立即生效
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();

        // 只使用重生锚作为节点方块
        nodeBlockTypes.clear();
        nodeBlockTypes.add(Material.RESPAWN_ANCHOR);

        this.baseHealth = cfg.getNodeBaseHealth();
        this.buffRadius = cfg.getNodeBuffRadius();
        this.buffRefreshTicks = cfg.getNodeBuffRefreshTicks();
        this.buffStackCap = cfg.getNodeBuffStackCap();
        this.buffDurationTicks = cfg.getNodeBuffDurationTicks();

        buffEffects.clear();
        buffEffects.putAll(cfg.getNodeBuffEffects());

        // round5 F4：空表不再补默认值。GameConfig 的构造器预置 RESISTANCE:0 / REGENERATION:0，
        // 因此「空表」只可能来自管理员的显式配置（`nodes.buff-effects: []`）而不是「没配」——
        // 旧实现此处无条件补一对默认值，把 `[]` 这个「想关掉全部节点增益」的意图又盖回去，
        // 且补的是 RESISTANCE:1（与预置的 :0 还不一致），玩家拿到的等级无从解释。
        // 空表 = 不施加任何增益，这是唯一自洽的语义。
    }

    // ========== 新增：手动注册节点系统 ==========

    /**
     * 从 nodes.yml 加载节点列表。
     *
     * <p>reload 时同坐标节点沿用原 NodeData，因此受损与已摧毁状态不会被重置；
     * 不再配置或方块材质无效的旧节点会先失效，再从注册表移除。</p>
     *
     * <p><b>文件损坏时保留现有节点</b>（不调 {@code invalidateAndClearNodes()}）：只有「文件不存在」
     * 才是清空语义。同时把路径记入 {@link #NODES_READ_ONLY}，让本进程的所有写入口（含静态写入口）拒写。</p>
     */
    public void loadNodesFromConfig() {
        File nodesFile = new File(gameManager.getPlugin().getDataFolder(), "nodes.yml");
        if (!nodesFile.exists()) {
            // 文件「不存在」有两种成因：① 从未部署过（正常，清空）；② 本次读盘失败被隔离成
            // nodes.yml.corrupt。后者不能当成「空配置」——那会在 reloadnodes / 开局时把内存里的
            // 节点静默清掉（而磁盘上的坏文件已被改名，看起来像「数据丢了」）。因此只读锁存在时早退。
            if (NODES_READ_ONLY.contains(nodesFile.getAbsolutePath())) {
                gameManager.getPlugin().getLogger().warning("nodes.yml is missing because it was quarantined "
                        + "after a failed read; keeping the current " + nodes.size() + " node(s)");
                return;
            }
            invalidateAndClearNodes();
            gameManager.getPlugin().getLogger().info("nodes.yml not found, starting with empty node list.");
            return;
        }

        Logger logger = gameManager.getPlugin().getLogger();
        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), logger);
        if (!loaded.usable()) {
            // 不可用时不碰内存：保留现有节点，只把该文件锁成只读
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            logger.severe("nodes.yml is unusable; keeping the current " + nodes.size()
                    + " node(s) and refusing all writes to it this run");
            return;
        }
        // 真的读到并校验通过 → 解除只读（与 YamlFileGate 的 OK 分支同口径）
        if (loaded.status() == YamlFiles.Status.OK) {
            NODES_READ_ONLY.remove(nodesFile.getAbsolutePath());
        }
        YamlConfiguration config = loaded.config();
        List<Map<?, ?>> nodeList = config.getMapList("nodes");
        Set<Location> configuredLocations = new LinkedHashSet<>();

        for (Map<?, ?> nodeMap : nodeList) {
            try {
                String worldName = (String) nodeMap.get("world");
                double x = ((Number) nodeMap.get("x")).doubleValue();
                double y = ((Number) nodeMap.get("y")).doubleValue();
                double z = ((Number) nodeMap.get("z")).doubleValue();

                if (gameManager.getGameWorld() == null
                        || !gameManager.getGameWorld().getName().equalsIgnoreCase(worldName)) {
                    continue;
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    gameManager.getPlugin().getLogger().warning("World not found for node: " + worldName);
                    continue;
                }

                Location loc = normalizeLocation(new Location(world, x, y, z));
                Material actualType = loc.getBlock().getType();
                if (actualType != Material.RESPAWN_ANCHOR) {
                    gameManager.getPlugin().getLogger().warning("Skipped invalid energy node at "
                            + worldName + " " + loc.getBlockX() + "," + loc.getBlockY() + ","
                            + loc.getBlockZ() + ": expected RESPAWN_ANCHOR, found " + actualType);
                    continue;
                }

                configuredLocations.add(loc);
            } catch (Exception e) {
                gameManager.getPlugin().getLogger().warning("Failed to load node: " + e.getMessage());
            }
        }

        // 先使旧对象失效，再删除注册关系，确保持有旧引用的 Goal 会立即放弃目标
        for (Map.Entry<Location, NodeData> entry : new ArrayList<>(nodes.entrySet())) {
            if (configuredLocations.contains(entry.getKey())) continue;
            NodeData staleNode = entry.getValue();
            staleNode.deactivate();
            activeNodes.remove(staleNode);
            nodes.remove(entry.getKey(), staleNode);
        }

        for (Location loc : configuredLocations) {
            NodeData nodeData = nodes.get(loc);
            if (nodeData == null) {
                nodeData = new NodeData(loc, baseHealth);
                nodes.put(loc, nodeData);
            }

            // 保留现有健康值与 active 状态，仅修复活跃索引的一致性
            if (nodeData.isActive()) {
                activeNodes.add(nodeData);
            } else {
                activeNodes.remove(nodeData);
            }
        }

        gameManager.getPlugin().getLogger().info("Loaded " + nodes.size() + " energy nodes from nodes.yml.");
    }

    private void invalidateAndClearNodes() {
        for (NodeData node : nodes.values()) {
            node.deactivate();
        }
        activeNodes.clear();
        nodes.clear();
    }

    /**
     * 把所有已注册节点恢复为满血并重新标记为活跃，同时重建 {@link #activeNodes} 索引。
     *
     * <p>语义等价于「新一局的全新节点」：{@link #loadNodesFromConfig()} 为了让管理员 reload
     * （{@code /hilltop deploy reloadnodes}）保留现场，会沿用同坐标的 NodeData 及其当前血量与
     * active 状态，因此开局路径必须显式调用本方法，否则上一局被打残或已被摧毁的节点会跨局残留。</p>
     *
     * <p>只能在「新一局真正开始」时调用；管理员 reload 路径不得调用，否则会破坏其保留状态的语义。</p>
     */
    public void resetAllNodes() {
        for (NodeData node : nodes.values()) {
            node.resetToFull();
        }
        // 全部节点恢复活跃，整体重建索引即可，无需逐个做差量判断
        activeNodes.clear();
        activeNodes.addAll(nodes.values());
    }

    /**
     * 保存节点列表到 nodes.yml（多竞技场隔离：保留其他世界的节点条目，只更新本竞技场世界的节点）
     *
     * @return true 表示已落盘；false 表示被只读降级拦下或写入失败（两种情形都已打日志）
     */
    public boolean saveNodesToConfig() {
        return saveNodesToConfig(resolveArenaWorldNames());
    }

    /**
     * 保存节点列表到 nodes.yml，并删除 {@code arenaWorlds} 中列出的世界的旧条目。
     *
     * <p>{@code arenaWorlds} 为空时不删除任何旧条目：调用方无法确定该清理哪个世界，
     * 保留现场比误删其它竞技场的数据安全。</p>
     *
     * @return true 表示已落盘；false 表示被只读降级拦下或写入失败（两种情形都已打日志）
     */
    private boolean saveNodesToConfig(Set<String> arenaWorlds) {
        File nodesFile = new File(gameManager.getPlugin().getDataFolder(), "nodes.yml");
        Logger logger = gameManager.getPlugin().getLogger();
        if (!nodesWritable(nodesFile, logger)) return false;

        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), logger);
        if (!loaded.usable()) {
            // 读不到就不能写：整份覆写会把其它世界/其它竞技场的条目抹掉
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            logger.warning("Refusing to write nodes.yml (unusable); node changes were NOT saved");
            return false;
        }
        YamlConfiguration config = loaded.config();

        // 过滤掉本竞技场世界的旧条目，保留其他世界的节点
        List<Map<?, ?>> existing = config.getMapList("nodes");
        List<Map<String, Object>> nodeList = new ArrayList<>();
        if (existing != null) {
            for (Map<?, ?> nodeMap : existing) {
                String worldName = entryWorldName(nodeMap);
                // fix6 锐评 Y1-N1：contains 为大小写敏感精确比较——服主改 arenas.yml 世界名
                // 大小写后，旧名条目会失配而永不被过滤（删除节点不落盘、下次加载幽灵复活）。
                // 与读取侧（:109 等 equalsIgnoreCase）统一口径：逐项忽略大小写匹配。
                if (worldName != null && arenaWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(worldName))) continue;
                nodeList.add(copyNodeEntry(nodeMap));
            }
        }

        for (Location loc : nodes.keySet()) {
            Map<String, Object> nodeMap = new LinkedHashMap<>();
            nodeMap.put("world", loc.getWorld().getName());
            nodeMap.put("x", loc.getBlockX());
            nodeMap.put("y", loc.getBlockY());
            nodeMap.put("z", loc.getBlockZ());
            nodeList.add(nodeMap);
        }

        config.set("nodes", nodeList);

        try {
            YamlFiles.write(nodesFile, config);
            logger.info("Saved " + nodes.size() + " energy nodes to nodes.yml.");
            return true;
        } catch (IOException e) {
            logger.severe("Failed to save nodes.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * 本实例负责（可写入 nodes.yml）的世界名集合。
     *
     * <p>正常路径取 {@code gameManager.getGameWorld()}。世界未加载时它是 null，
     * 而 {@code worldName.equals(null)} 恒为 false —— 用 null 做过滤条件会导致一个旧条目都删不掉
     * （表现为「清空竞技场节点无效」）。此时退化为「已注册节点所属世界」兜底；
     * 两者都取不到时返回空集合，保存逻辑即保留全部旧条目。</p>
     */
    private Set<String> resolveArenaWorldNames() {
        Set<String> worldNames = new LinkedHashSet<>();
        World world = gameManager.getGameWorld();
        if (world != null) {
            worldNames.add(world.getName());
            return worldNames;
        }

        for (Location location : nodes.keySet()) {
            if (location.getWorld() != null) {
                worldNames.add(location.getWorld().getName());
            }
        }
        return worldNames;
    }

    /**
     * 清空本实例的全部节点（内存 + 一次 nodes.yml 落盘）。
     *
     * <p>批量清除不走 {@link #removeNode(Location)} 的逐个删除路径：后者每次都会完整读写一遍
     * nodes.yml，N 个节点就是 N 次文件写。这里整体失效并清空内存后只落盘一次。</p>
     *
     * @return 被清除的节点数
     */
    public int clearAllNodes() {
        // 必须趁 nodes 还没被清空时解析世界名：世界未加载时该兜底依赖已注册节点
        Set<String> arenaWorlds = resolveArenaWorldNames();
        int removed = nodes.size();
        invalidateAndClearNodes();
        if (arenaWorlds.isEmpty()) {
            gameManager.getPlugin().getLogger().warning(
                    "Cannot determine this arena's world (world not loaded and no registered nodes); existing node entries in nodes.yml were kept");
        }
        saveNodesToConfig(arenaWorlds);
        return removed;
    }

    /**
     * 手动添加节点
     * @param location 玩家站立位置（会向下检测1格的方块）
     * @return 0=成功, 1=不是有效节点方块, 2=已存在
     */
    public int addNode(Location location) {
        // 检查脚下方块
        Location blockLoc = location.clone().subtract(0, 1, 0).getBlock().getLocation();
        Block block = blockLoc.getBlock();

        if (!isNodeBlock(block)) {
            return 1; // 不是有效节点方块
        }

        Location normalized = normalizeLocation(blockLoc);

        if (nodes.containsKey(normalized)) {
            return 2; // 已存在
        }

        NodeData nodeData = new NodeData(normalized, baseHealth);
        nodes.put(normalized, nodeData);
        activeNodes.add(nodeData);

        saveNodesToConfig();
        return 0; // 成功
    }

    /**
     * 移除节点
     * @param location 节点坐标
     * @return true=已删除并成功写回 nodes.yml；false=不存在，或写盘失败（内存已删但磁盘未更新）
     */
    public boolean removeNode(Location location) {
        Location normalized = normalizeLocation(location);
        NodeData removed = nodes.get(normalized);

        if (removed != null) {
            // 必须先失效旧对象，再删除注册关系，持有旧引用的 Goal 才能立即放弃目标
            removed.deactivate();
            activeNodes.remove(removed);
            nodes.remove(normalized, removed);
            if (saveNodesToConfig()) return true;

            // 内存已删、磁盘未删。刻意不回滚内存删除：NodeData.deactivate() 不可逆，
            // 重建一个新对象会把该节点的当前血量一起丢掉（等于免费修满）。
            // 两条拒写路径都已各自打过日志，这里补一句把「重启后会复活」讲明白。
            gameManager.getPlugin().getLogger().warning(
                    "Node removed from memory but nodes.yml was NOT updated at " + normalized
                            + "; it will reappear after a restart");
            return false;
        }

        return false;
    }

    /**
     * 获取所有节点坐标列表
     */
    public List<Location> getAllNodeLocations() {
        List<Location> locations = new ArrayList<>(nodes.size());
        for (Location location : nodes.keySet()) {
            locations.add(location.clone());
        }
        return locations;
    }

    // ========== 文件级工具（不依赖实例 / 世界是否加载） ==========

    /**
     * 读取 nodes.yml 中属于该世界的全部节点坐标（不依赖实例，返回空列表而非 null）。
     *
     * <p>未启用的竞技场没有 {@link GameManager}（{@code getArena()} 返回 null），拿不到本类的实例，
     * 竞技场管理 GUI 只能直接读文件，故提供这组静态工具。返回顺序与文件中的书写顺序一致，
     * 与 {@link #removeNodeEntryForWorld(File, String, int)} 的序号语义相同。</p>
     *
     * <p><b>世界未加载时</b>（{@code Bukkit.getWorld(worldName) == null}，未启用的竞技场通常就是这种情况）
     * 不丢弃数据：世界对象缺失只是「读不到方块」，节点坐标本身仍然有效，因此返回的
     * {@link Location} 保留全部坐标、{@code world} 为 null（方法签名固定为 {@code List<Location>}，
     * 这是唯一能不丢坐标的表示）。调用方只能使用 x/y/z 系方法，禁止对 {@code world} 为 null 的
     * Location 调用 {@code getBlock()} / {@code getWorld()} 等依赖世界的操作。</p>
     *
     * @param dataFolder 插件数据目录（{@code plugin.getDataFolder()}）
     * @param worldName  目标世界名
     */
    public static List<Location> readNodesForWorld(File dataFolder, String worldName) {
        List<Location> locations = new ArrayList<>();
        if (dataFolder == null || worldName == null) return locations;

        File nodesFile = new File(dataFolder, "nodes.yml");
        if (!nodesFile.exists()) return locations;

        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), staticLogger());
        if (!loaded.usable()) {
            // 不可用 → 锁成只读并返回空列表。调用方（ArenaResetService）会得到 0，
            // 随后 expectedNodes>0 && removedNodes==0 的消歧分支会把重置判为失败，不会误报成功。
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            return locations;
        }
        if (loaded.status() == YamlFiles.Status.OK) {
            NODES_READ_ONLY.remove(nodesFile.getAbsolutePath());
        }
        YamlConfiguration config = loaded.config();
        World world = Bukkit.getWorld(worldName); // 可能为 null：世界未加载时坐标照样返回，只是不携带 World

        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            Location location = readNodeLocation(nodeMap, world, worldName);
            if (location != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    /**
     * 一次性从 nodes.yml 删除属于该世界的全部节点条目。
     *
     * <p>保留其它世界的条目，与 {@link #saveNodesToConfig()} 的多竞技场隔离语义一致。
     * 没有条目可删时不写文件（避免无意义的整份重写）。</p>
     *
     * @return 删除数量；世界名非法、文件不存在或写入失败时返回 0
     */
    public static int clearNodesForWorld(File dataFolder, String worldName) {
        if (dataFolder == null || worldName == null) return 0;

        File nodesFile = new File(dataFolder, "nodes.yml");
        if (!nodesFile.exists()) return 0;

        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), staticLogger());
        if (!loaded.usable()) {
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            return 0;
        }
        if (loaded.status() == YamlFiles.Status.OK) {
            NODES_READ_ONLY.remove(nodesFile.getAbsolutePath());
        }
        YamlConfiguration config = loaded.config();
        List<Map<String, Object>> keptNodes = new ArrayList<>();
        int removed = 0;
        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            // fix6 Y1-F1-03：世界名比较忽略大小写（与 Bukkit 世界查找口径一致）
            if (worldName.equalsIgnoreCase(entryWorldName(nodeMap))) {
                removed++;
                continue;
            }
            keptNodes.add(copyNodeEntry(nodeMap));
        }

        if (removed == 0) return 0;
        return writeNodes(nodesFile, config, keptNodes) ? removed : 0;
    }

    /**
     * 把一批属于指定世界的节点坐标追加回 {@code nodes.yml}。
     *
     * <p>#4 重置回滚专用：第 5 步 {@link #clearNodesForWorld} 是不可逆删除，若后续
     * {@code clearDeploymentData} 失败并中止，竞技场会被重新启用但节点已空 ——
     * 变成一张「可玩但 0 节点」的坏图。重置前先 {@link #readNodesForWorld} 备份坐标，
     * 中止时用本方法恢复。节点的持久化格式只有 world/x/y/z（血量不落盘），因此
     * {@link Location} 足以无损恢复。</p>
     *
     * <p>只追加目标世界的条目，保留其它世界；已存在的相同坐标会跳过，避免失败重试时
     * 写出重复节点。世界未加载时 Location 的 world 为 null，本方法只用 x/y/z，安全。</p>
     *
     * @param dataFolder 插件数据目录
     * @param worldName  目标世界名（恢复条目的 world 字段）
     * @param locations  备份坐标；null / 空表示无需恢复，返回 true
     * @return true = 无需恢复或已成功写入；false = 文件不可用 / 写盘失败
     */
    public static boolean restoreNodesForWorld(File dataFolder, String worldName,
                                               List<Location> locations) {
        if (locations == null || locations.isEmpty()) return true;
        if (dataFolder == null || worldName == null || worldName.isBlank()) return false;

        File nodesFile = new File(dataFolder, "nodes.yml");
        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), staticLogger());
        if (!loaded.usable()) {
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            return false;
        }
        if (loaded.status() == YamlFiles.Status.OK) {
            NODES_READ_ONLY.remove(nodesFile.getAbsolutePath());
        }
        YamlConfiguration config = loaded.config();

        List<Map<String, Object>> all = new ArrayList<>();
        Set<String> existingKeys = new HashSet<>();
        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            all.add(copyNodeEntry(nodeMap));
            if (worldName.equalsIgnoreCase(entryWorldName(nodeMap))) {
                existingKeys.add(nodeKey(nodeMap.get("x"), nodeMap.get("y"), nodeMap.get("z")));
            }
        }

        int restored = 0;
        for (Location location : locations) {
            if (location == null) continue;
            Map<String, Object> nodeMap = new LinkedHashMap<>();
            nodeMap.put("world", worldName);
            nodeMap.put("x", location.getBlockX());
            nodeMap.put("y", location.getBlockY());
            nodeMap.put("z", location.getBlockZ());
            if (!existingKeys.add(nodeKey(nodeMap.get("x"), nodeMap.get("y"), nodeMap.get("z")))) {
                continue;
            }
            all.add(nodeMap);
            restored++;
        }

        if (restored == 0) return true;
        boolean written = writeNodes(nodesFile, config, all);
        if (written) {
            staticLogger().info("[HilltopVillage] Restored " + restored + " node(s) for world "
                    + worldName + " after aborted reset");
        }
        return written;
    }

    private static String nodeKey(Object x, Object y, Object z) {
        return coordinateKey(x) + "/" + coordinateKey(y) + "/" + coordinateKey(z);
    }

    /** YAML 里坐标可能是 Integer 或 Double；按整块坐标归一化，避免 100 与 100.0 被当两条。 */
    private static String coordinateKey(Object value) {
        return value instanceof Number
                ? Long.toString(((Number) value).longValue())
                : String.valueOf(value);
    }

    /**
     * 从 nodes.yml 删除属于该世界的第 index 条（按该世界过滤后的序号，从 0 开始）。
     *
     * @return true 表示删除并成功写回文件
     */
    public static boolean removeNodeEntryForWorld(File dataFolder, String worldName, int index) {
        if (dataFolder == null || worldName == null || index < 0) return false;

        File nodesFile = new File(dataFolder, "nodes.yml");
        if (!nodesFile.exists()) return false;

        YamlFiles.Loaded loaded = YamlFiles.load(nodesFile, YamlFiles.nodesValidator(), staticLogger());
        if (!loaded.usable()) {
            NODES_READ_ONLY.add(nodesFile.getAbsolutePath());
            return false;
        }
        if (loaded.status() == YamlFiles.Status.OK) {
            NODES_READ_ONLY.remove(nodesFile.getAbsolutePath());
        }
        YamlConfiguration config = loaded.config();
        List<Map<String, Object>> keptNodes = new ArrayList<>();
        int currentIndex = 0;
        boolean removed = false;
        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            // 序号只对属于该世界的条目递增，与 readNodesForWorld 的返回顺序严格对应
            // fix6 Y1-F1-03：世界名比较忽略大小写
            if (!removed && worldName.equalsIgnoreCase(entryWorldName(nodeMap))) {
                if (currentIndex == index) {
                    removed = true;
                    continue;
                }
                currentIndex++;
            }
            keptNodes.add(copyNodeEntry(nodeMap));
        }

        if (!removed) return false;
        return writeNodes(nodesFile, config, keptNodes);
    }

    /**
     * 把 nodes.yml 的单条记录转成 Location；不属于该世界或字段非法时返回 null。
     * 世界未加载时传入的 {@code world} 为 null，坐标照样返回。
     */
    private static Location readNodeLocation(Map<?, ?> nodeMap, World world, String worldName) {
        // fix6 Y1-F1-03：世界名比较忽略大小写
        if (!worldName.equalsIgnoreCase(entryWorldName(nodeMap))) return null;

        Object x = nodeMap.get("x");
        Object y = nodeMap.get("y");
        Object z = nodeMap.get("z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;

        return new Location(world,
                ((Number) x).doubleValue(),
                ((Number) y).doubleValue(),
                ((Number) z).doubleValue());
    }

    /** 取 nodes.yml 单条记录的世界名；缺失或为 null 时返回 null。 */
    private static String entryWorldName(Map<?, ?> nodeMap) {
        if (nodeMap == null) return null;
        Object worldName = nodeMap.get("world");
        return worldName == null ? null : String.valueOf(worldName);
    }

    /** 复制一条节点记录（保留未知键），用于整份重写时原样保留其它世界的条目。 */
    private static Map<String, Object> copyNodeEntry(Map<?, ?> nodeMap) {
        Map<String, Object> copy = new LinkedHashMap<>();
        nodeMap.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    /**
     * 把过滤后的节点列表写回 nodes.yml。
     *
     * @return true 表示写入成功
     */
    private static boolean writeNodes(File nodesFile, YamlConfiguration config, List<Map<String, Object>> nodes) {
        Logger logger = staticLogger();
        if (!nodesWritable(nodesFile, logger)) return false;
        config.set("nodes", nodes);
        try {
            YamlFiles.write(nodesFile, config);
            return true;
        } catch (IOException exception) {
            logger.log(java.util.logging.Level.SEVERE,
                    "[HilltopVillage] Failed to save nodes.yml: " + exception.getMessage(), exception);
            return false;
        }
    }

    /** 静态工具方法的日志出口（没有插件实例可用时退回 Bukkit 全局日志）。 */
    private static Logger staticLogger() {
        return Bukkit.getLogger();
    }

    // ========== 保留的原有方法 ==========

    public boolean isNodeBlock(Block block) {
        return nodeBlockTypes.contains(block.getType());
    }

    public NodeData getNodeAt(Location location) {
        Location normalizedLoc = normalizeLocation(location);
        return nodes.get(normalizedLoc);
    }

    public double damageNode(Location location, double damage) {
        NodeData node = getNodeAt(location);
        if (!isRegisteredActive(node) || !Double.isFinite(damage) || damage <= 0) return 0;

        double actualDamage = Math.min(damage, node.getCurrentHealth());
        node.damage(actualDamage);

        if (!node.isActive()) {
            activeNodes.remove(node);
            node.getLocation().getWorld().playEffect(node.getLocation(), Effect.ENDER_SIGNAL, 0);
            node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f);

            LanguageManager lang = gameManager.getPlugin().getLanguageManager();
            for (UUID pid : gameManager.getPlayers()) {
                Player p = Bukkit.getPlayer(pid);
                if (p != null && p.isOnline()) {
                    p.sendMessage(lang.getFor(p, "node.destroyed"));
                }
            }
        }

        return actualDamage;
    }

    public boolean repairNode(Location location, Player player, double repairAmount) {
        NodeData node = getNodeAt(location);
        if (node == null || !Double.isFinite(repairAmount) || repairAmount <= 0) return false;

        if (!node.isActive()) {
            node.repair(repairAmount);
            if (!node.isActive()) return false;
            activeNodes.add(node);

            // P2-20（M-19 同族防御）：非参赛玩家（playerDataMap 无此人）理论上已被
            // NodeListener 的入口判空拦下，这里再做一层判空，杜绝跨入口的 NPE。
            PlayerData data = gameManager.getPlayerData(player);
            if (data != null) {
                data.addNodeRepaired();
            }
            LanguageManager lang = gameManager.getPlugin().getLanguageManager();
            for (UUID pid : gameManager.getPlayers()) {
                Player p = Bukkit.getPlayer(pid);
                if (p != null && p.isOnline()) {
                    p.sendMessage(lang.getFor(p, "node.repaired"));
                }
            }

            node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.8f);
            return true;
        }

        // P2-20：满血节点不再「修复」——返回 false，调用方（NodeListener）据此不消耗修复物品。
        // 原实现无条件 repair() 并返回 true，导致满血右键静默吞掉材料。
        if (isNodeFull(node)) {
            return false;
        }

        node.repair(repairAmount);
        node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f);
        return true;
    }

    /**
     * 节点是否已满血（P2-20）。
     *
     * <p>供修复入口在扣除材料前查询：满血时不再消耗修复物品、不播修复音效。</p>
     */
    public boolean isNodeFull(NodeData node) {
        return node != null && node.getCurrentHealth() >= node.getMaxHealth();
    }

    public void applyAllBuffs() {
        if (buffTask != null) {
            buffTask.cancel();
        }

        // 开局时先按最新配置刷新一次（含刷新周期本身）
        loadConfig();

        buffTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameManager.isGameRunning()) {
                    cancel();
                    return;
                }

                // 每轮刷新一次配置，使 GUI / reloadconfig 的修改无需重启即可生效
                loadConfig();

                for (UUID playerId : gameManager.getPlayers()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) continue;

                    int activeNodeCount = countActiveNodesNear(player.getLocation());
                    if (activeNodeCount > 0) {
                        applyBuffsToPlayer(player, activeNodeCount);
                    }
                }
            }
        };

        buffTask.runTaskTimer(gameManager.getPlugin(), 0L, Math.max(1, buffRefreshTicks));
    }

    private int countActiveNodesNear(Location playerLoc) {
        int count = 0;
        // buffRadius 与 playerLoc 在循环内不会变化，提到循环外只算一次
        double buffRadiusSq = buffRadius * buffRadius;
        World playerWorld = playerLoc.getWorld();
        for (NodeData node : activeNodes) {
            if (!isRegisteredActive(node)) continue;
            // 直读 node.location 而非 getLocation()：后者每次返回 location.clone()，
            // 本方法位于 NodeSystem 顶层类内，可访问静态嵌套类 NodeData 的私有字段，
            // 而这里只读坐标、不把 Location 交给外部，无需副本。
            Location nodeLoc = node.location;
            if (nodeLoc.getWorld() != playerWorld) continue;

            // 手算 3D 距离平方：Location#distanceSquared 内部会做三次 getWorld() 判空/同世界
            // 校验（上面已手动比较过 world），且坐标通过 getX/getY/getZ 直取字段。
            // 必须保留 dy——原实现是 3D 距离平方，漏掉 Y 轴会改变判定结果。
            double dx = nodeLoc.getX() - playerLoc.getX();
            double dy = nodeLoc.getY() - playerLoc.getY();
            double dz = nodeLoc.getZ() - playerLoc.getZ();
            if (dx * dx + dy * dy + dz * dz <= buffRadiusSq) {
                count++;
            }
        }
        return count;
    }

    private void applyBuffsToPlayer(Player player, int activeNodes) {
        int effectiveAmplifier = Math.min(activeNodes - 1, buffStackCap);

        for (Map.Entry<PotionEffectType, Integer> entry : buffEffects.entrySet()) {
            PotionEffectType type = entry.getKey();
            int baseAmplifier = entry.getValue();
            int finalAmplifier = baseAmplifier + effectiveAmplifier;

            player.addPotionEffect(new PotionEffect(
                    type, buffDurationTicks, finalAmplifier, false, true, true));
        }
    }

    public void stopBuffTask() {
        if (buffTask != null) {
            buffTask.cancel();
            buffTask = null;
        }
    }

    /**
     * 查找离给定位置最近的活跃节点。
     *
     * <p>只遍历 {@link #activeNodes}（活跃节点视图），而不是遍历全部节点后在循环内过滤，
     * 使怪物 AI 每轮评估的遍历量与「活跃节点数」成正比，而非「全部节点数」。</p>
     *
     * <p>该视图由 NodeSystem 独占维护，同步点见：
     * {@link #loadNodesFromConfig()}（保留同坐标状态并失效已移除对象）、{@link #resetAllNodes()}（开局重置为满血活跃）、
     * {@link #addNode(Location)}、
     * {@link #removeNode(Location)}、{@link #clearAllNodes()}、{@link #damageNode(Location, double)}（摧毁）、
     * {@link #repairNode(Location, Player, double)}（修复）。</p>
     */
    public NodeData getNearestNode(Location location) {
        NodeData nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        // location 在整个循环内不变，world 与坐标提到循环外只取一次
        World targetWorld = location.getWorld();
        double targetX = location.getX();
        double targetY = location.getY();
        double targetZ = location.getZ();

        for (NodeData node : activeNodes) {
            // 身份校验防止 reload / remove 后仍把旧 NodeData 当作合法目标
            if (!isRegisteredActive(node)) continue;

            // 直读 node.location 而非 getLocation()：后者每次返回 location.clone()，
            // 本方法位于 NodeSystem 顶层类内，可访问静态嵌套类 NodeData 的私有字段；
            // 这里只读坐标，不把 Location 交给外部，因此不需要副本。
            Location nodeLoc = node.location;
            // 与原实现一致的引用比较（两个 world 同为 null 时也会像原来一样跳过）
            if (nodeLoc.getWorld() != targetWorld) continue;

            // 手算 3D 距离平方：上面已手动比较过 world，Location#distanceSquared 内部的
            // 判空与同世界校验就成了纯开销。必须保留 dy——原实现是 3D 距离平方。
            double dx = nodeLoc.getX() - targetX;
            double dy = nodeLoc.getY() - targetY;
            double dz = nodeLoc.getZ() - targetZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            // 严格小于：并列时保留先遍历到的节点，与原实现逐字一致
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = node;
            }
        }

        return nearest;
    }

    /**
     * 查找离给定位置最近、但<b>排除</b>指定节点的活跃节点（P0-C：卡死怪放弃当前节点改选次近节点）。
     *
     * <p>与 {@link #getNearestNode(Location)} 同样只遍历 {@link #activeNodes} 并做身份校验；
     * {@code excluded} 为 null 时等价于 {@link #getNearestNode(Location)}。无其它可选节点时返回 null，
     * 由调用方（卡死放弃计数）兜底自毁出列。</p>
     */
    public NodeData getNearestNodeExcluding(Location location, NodeData excluded) {
        NodeData nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        World targetWorld = location.getWorld();
        double targetX = location.getX();
        double targetY = location.getY();
        double targetZ = location.getZ();

        for (NodeData node : activeNodes) {
            if (node == excluded) continue;
            if (!isRegisteredActive(node)) continue;

            Location nodeLoc = node.location;
            if (nodeLoc.getWorld() != targetWorld) continue;

            double dx = nodeLoc.getX() - targetX;
            double dy = nodeLoc.getY() - targetY;
            double dz = nodeLoc.getZ() - targetZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = node;
            }
        }

        return nearest;
    }

    /**
     * 判断 NodeData 是否仍是当前坐标注册的同一个活跃对象。
     * 查询由 ConcurrentHashMap 与活跃索引完成，单次为 O(1)。
     */
    public boolean isRegisteredActive(NodeData node) {
        if (node == null || !node.isActive() || !activeNodes.contains(node)) return false;
        return nodes.get(node.location) == node;
    }

    public int getTotalCount() {
        return nodes.size();
    }

    public int getActiveCount() {
        return activeNodes.size();
    }

    /** 节点数据内部类 */
    public static class NodeData {
        private final Location location;
        private double currentHealth;
        private final double maxHealth;
        private boolean active;

        private NodeData(Location location, double maxHealth) {
            this.location = normalizeLocation(location);
            this.maxHealth = maxHealth;
            this.currentHealth = maxHealth;
            this.active = true;
        }

        public Location getLocation() {
            return location.clone();
        }

        public double getCurrentHealth() {
            return currentHealth;
        }

        public double getMaxHealth() {
            return maxHealth;
        }

        public boolean isActive() {
            return active;
        }

        private void deactivate() {
            this.active = false;
        }

        private void damage(double amount) {
            currentHealth -= amount;
            if (currentHealth <= 0) {
                currentHealth = 0;
                active = false;
            }
        }

        private void repair(double amount) {
            currentHealth = Math.min(currentHealth + amount, maxHealth);
            if (currentHealth > 0) {
                active = true;
            }
        }

        /** 恢复满血并置为活跃（仅供 {@link NodeSystem#resetAllNodes()} 的开局重置使用） */
        private void resetToFull() {
            this.currentHealth = maxHealth;
            this.active = true;
        }
    }
}
