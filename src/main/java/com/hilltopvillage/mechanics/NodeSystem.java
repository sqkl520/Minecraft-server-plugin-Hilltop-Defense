package com.hilltopvillage.mechanics;

import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.util.LanguageManager;
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

public class NodeSystem {

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

        if (buffEffects.isEmpty()) {
            buffEffects.put(PotionEffectType.RESISTANCE, 1);
            buffEffects.put(PotionEffectType.REGENERATION, 0);
        }
    }

    // ========== 新增：手动注册节点系统 ==========

    /**
     * 从 nodes.yml 加载节点列表。
     *
     * <p>reload 时同坐标节点沿用原 NodeData，因此受损与已摧毁状态不会被重置；
     * 不再配置或方块材质无效的旧节点会先失效，再从注册表移除。</p>
     */
    public void loadNodesFromConfig() {
        File nodesFile = new File(gameManager.getPlugin().getDataFolder(), "nodes.yml");
        if (!nodesFile.exists()) {
            invalidateAndClearNodes();
            gameManager.getPlugin().getLogger().info("nodes.yml not found, starting with empty node list.");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(nodesFile);
        List<Map<?, ?>> nodeList = config.getMapList("nodes");
        Set<Location> configuredLocations = new LinkedHashSet<>();

        for (Map<?, ?> nodeMap : nodeList) {
            try {
                String worldName = (String) nodeMap.get("world");
                double x = ((Number) nodeMap.get("x")).doubleValue();
                double y = ((Number) nodeMap.get("y")).doubleValue();
                double z = ((Number) nodeMap.get("z")).doubleValue();

                if (gameManager.getGameWorld() == null
                        || !gameManager.getGameWorld().getName().equals(worldName)) {
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
     */
    public void saveNodesToConfig() {
        saveNodesToConfig(resolveArenaWorldNames());
    }

    /**
     * 保存节点列表到 nodes.yml，并删除 {@code arenaWorlds} 中列出的世界的旧条目。
     *
     * <p>{@code arenaWorlds} 为空时不删除任何旧条目：调用方无法确定该清理哪个世界，
     * 保留现场比误删其它竞技场的数据安全。</p>
     */
    private void saveNodesToConfig(Set<String> arenaWorlds) {
        File nodesFile = new File(gameManager.getPlugin().getDataFolder(), "nodes.yml");
        YamlConfiguration config = nodesFile.exists()
                ? YamlConfiguration.loadConfiguration(nodesFile)
                : new YamlConfiguration();

        // 过滤掉本竞技场世界的旧条目，保留其他世界的节点
        List<Map<?, ?>> existing = config.getMapList("nodes");
        List<Map<String, Object>> nodeList = new ArrayList<>();
        if (existing != null) {
            for (Map<?, ?> nodeMap : existing) {
                String worldName = entryWorldName(nodeMap);
                if (worldName != null && arenaWorlds.contains(worldName)) continue;
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
            config.save(nodesFile);
            gameManager.getPlugin().getLogger().info("Saved " + nodes.size() + " energy nodes to nodes.yml.");
        } catch (Exception e) {
            gameManager.getPlugin().getLogger().severe("Failed to save nodes.yml: " + e.getMessage());
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
                    "无法确定本竞技场的世界（世界未加载且无已注册节点），nodes.yml 中的旧节点条目已保留");
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
     * @return true=成功, false=不存在
     */
    public boolean removeNode(Location location) {
        Location normalized = normalizeLocation(location);
        NodeData removed = nodes.get(normalized);

        if (removed != null) {
            // 必须先失效旧对象，再删除注册关系，持有旧引用的 Goal 才能立即放弃目标
            removed.deactivate();
            activeNodes.remove(removed);
            nodes.remove(normalized, removed);
            saveNodesToConfig();
            return true;
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

        YamlConfiguration config = YamlConfiguration.loadConfiguration(nodesFile);
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

        YamlConfiguration config = YamlConfiguration.loadConfiguration(nodesFile);
        List<Map<String, Object>> keptNodes = new ArrayList<>();
        int removed = 0;
        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            if (worldName.equals(entryWorldName(nodeMap))) {
                removed++;
                continue;
            }
            keptNodes.add(copyNodeEntry(nodeMap));
        }

        if (removed == 0) return 0;
        return writeNodes(nodesFile, config, keptNodes) ? removed : 0;
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

        YamlConfiguration config = YamlConfiguration.loadConfiguration(nodesFile);
        List<Map<String, Object>> keptNodes = new ArrayList<>();
        int currentIndex = 0;
        boolean removed = false;
        for (Map<?, ?> nodeMap : config.getMapList("nodes")) {
            // 序号只对属于该世界的条目递增，与 readNodesForWorld 的返回顺序严格对应
            if (!removed && worldName.equals(entryWorldName(nodeMap))) {
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
        if (!worldName.equals(entryWorldName(nodeMap))) return null;

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
        config.set("nodes", nodes);
        try {
            config.save(nodesFile);
            return true;
        } catch (IOException exception) {
            Bukkit.getLogger().log(java.util.logging.Level.SEVERE,
                    "保存 nodes.yml 失败: " + exception.getMessage(), exception);
            return false;
        }
    }

    // ========== 保留的原有方法 ==========

    public boolean isNodeBlock(Block block) {
        return nodeBlockTypes.contains(block.getType());
    }

    public boolean isNodeAt(Location location) {
        Location normalizedLoc = normalizeLocation(location);
        return nodes.containsKey(normalizedLoc);
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

            gameManager.getPlayerData(player).addNodeRepaired();
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

        node.repair(repairAmount);
        node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f);
        return true;
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

    public Set<NodeData> getActiveNodes() {
        return new HashSet<>(activeNodes);
    }

    public double getBaseHealth() {
        return baseHealth;
    }

    public double getBuffRadius() {
        return buffRadius;
    }

    public Map<PotionEffectType, Integer> getBuffEffects() {
        return new LinkedHashMap<>(buffEffects);
    }

    public Set<Material> getNodeBlockTypes() {
        return new HashSet<>(nodeBlockTypes);
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

        public double getHealthPercentage() {
            return maxHealth > 0 ? (currentHealth / maxHealth) : 0;
        }
    }
}
