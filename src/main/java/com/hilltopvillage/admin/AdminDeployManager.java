package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class AdminDeployManager {

    private final HilltopVillagePlugin plugin;
    private final GameManager gameManager;
    private File spawnPointsFile;
    private FileConfiguration spawnPointsConfig;
    /** 多竞技场模式：竞技场配置（arenas.yml） */
    private File arenasFile;
    private YamlConfiguration arenasConfig;
    private final List<Location> savedSpawnPoints;
    /**
     * 已部署的怪物刷怪点（STORY-1.2：与玩家出生点 {@link #savedSpawnPoints} 分离的第二套坐标，
     * 对应 arenas.yml 的 {@code arenas.<id>.mob-spawn-points}）。
     */
    private final List<Location> savedMobSpawnPoints;
    /** 待保护的村民位置（全部阵亡 = 游戏失败） */
    private final List<Location> savedVillagerPositions;
    private Location savedCoreLocation;
    /** 商店村民位置（null = 本图无商店） */
    private Location savedShopNpcLocation;
    private int showParticlesTaskId = -1;

    public AdminDeployManager(HilltopVillagePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.savedSpawnPoints = new ArrayList<>();
        this.savedMobSpawnPoints = new ArrayList<>();
        this.savedVillagerPositions = new ArrayList<>();
        loadSpawnPoints();
    }

    /* ========== 粒子展示 ========== */

    public void showSpawnPointParticles(Player viewer, LanguageManager lang) {
        showSpawnPointParticles(viewer, lang, 600L);
    }

    /** 重载：向导模式专用（无需传入 LanguageManager，自动获取） */
    public void showSpawnPointParticles(Player viewer) {
        showSpawnPointParticles(viewer, plugin.getLanguageManager(), 600L);
    }

    public void showSpawnPointParticles(Player viewer, LanguageManager lang, long durationTicks) {
        if (savedSpawnPoints.isEmpty()) {
            viewer.sendMessage(lang.getFor(viewer, "particles.no-spawnpoints"));
            return;
        }

        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
        }

        viewer.sendMessage(lang.getFor(viewer, "particles.showing", savedSpawnPoints.size(), durationTicks / 20));
        viewer.sendMessage(lang.getFor(viewer, "particles.hint"));

        double radius = 3.0;

        showParticlesTaskId = new BukkitRunnable() {
            int ticksElapsed = 0;
            double angle = 0;

            @Override
            public void run() {
                if (ticksElapsed >= durationTicks) {
                    cancel();
                    showParticlesTaskId = -1;
                    return;
                }

                // 若 viewer 已离线则取消任务，避免对离线玩家持续运算
                if (!viewer.isOnline()) {
                    cancel();
                    showParticlesTaskId = -1;
                    return;
                }

                angle += Math.PI / 16;  // 慢速旋转
                if (angle >= 2 * Math.PI) angle = 0;

                World world = gameManager.getGameWorld();

                for (Location loc : savedSpawnPoints) {
                    // 距离剔除：viewer 超过 64 格则跳过该点
                    if (viewer.getWorld() != loc.getWorld()
                            || viewer.getLocation().distanceSquared(loc) > 64 * 64) {
                        continue;
                    }

                    // === 方案C：符文 + 垂直光柱 ===

                    // 1. 垂直光柱（END_ROD 白色粒子，从地面到 y+20）
                    //
                    // 【刻意不合并为「一次调用 + 大 offset」，原因见 LocationParticlePreview 同类注释】
                    // count>0 时 offset 是高斯散布的标准差 σ（客户端 ClientPacketListener
                    // 对每个粒子执行 pos += nextGaussian() × offset），不是 ±均匀范围。
                    // 合并成 σ=10、中心 y+10 会有约 32% 粒子溢出到柱外、密度中浓端淡，
                    // 地面起点消失 —— 视觉与现状不一致，故保留逐点循环。
                    double beamHeight = 20.0;
                    double beamStep = 0.4;  // 每0.4格一个粒子（y=0→20 共 51 次迭代）
                    for (double y = 0; y <= beamHeight; y += beamStep) {
                        world.spawnParticle(Particle.END_ROD,
                            loc.getX() + 0.5, loc.getY() + y, loc.getZ() + 0.5,
                            1, 0, 0, 0, 0);
                    }

                    // 2. 周围螺旋符文装饰（ENCHANT 紫色附魔台粒子）
                    double spiralRadius = 1.2;
                    double spiralHeight = 3.0;
                    int spiralPoints = 3;  // 每帧3个粒子点
                    for (int i = 0; i < spiralPoints; i++) {
                        double spiralAngle = angle + (i * 2 * Math.PI / spiralPoints);
                        double spiralY = (ticksElapsed % 60) * (spiralHeight / 60.0);  // 3秒循环一次
                        double spiralX = loc.getX() + 0.5 + Math.cos(spiralAngle) * spiralRadius;
                        double spiralZ = loc.getZ() + 0.5 + Math.sin(spiralAngle) * spiralRadius;
                        world.spawnParticle(Particle.ENCHANTED_HIT,
                            spiralX, loc.getY() + spiralY, spiralZ,
                            1, 0, 0, 0, 0);
                    }
                }

                ticksElapsed++;
            }
        }.runTaskTimer(plugin, 0L, 4L).getTaskId();
    }

    /** 显示节点粒子（复用生成点粒子逻辑） */
    public void showNodeParticles(Player viewer, List<Location> nodeLocations, LanguageManager lang) {
        if (nodeLocations.isEmpty()) {
            viewer.sendMessage("§c没有已注册的节点");
            return;
        }

        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
        }

        long durationTicks = 600L; // 30秒
        viewer.sendMessage("§a正在显示 " + nodeLocations.size() + " 个节点的位置，持续 " + (durationTicks / 20) + " 秒");

        showParticlesTaskId = new BukkitRunnable() {
            int ticksElapsed = 0;
            double angle = 0;

            @Override
            public void run() {
                if (ticksElapsed >= durationTicks) {
                    cancel();
                    showParticlesTaskId = -1;
                    return;
                }

                if (!viewer.isOnline()) {
                    cancel();
                    showParticlesTaskId = -1;
                    return;
                }

                angle += Math.PI / 16;
                if (angle >= 2 * Math.PI) angle = 0;

                World world = gameManager.getGameWorld();

                for (Location loc : nodeLocations) {
                    if (viewer.getWorld() != loc.getWorld()
                            || viewer.getLocation().distanceSquared(loc) > 64 * 64) {
                        continue;
                    }

                    // 垂直光柱（蓝色粒子，区分生成点）
                    double beamHeight = 15.0;
                    double beamStep = 0.5;
                    for (double y = 0; y <= beamHeight; y += beamStep) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                            loc.getX() + 0.5, loc.getY() + y, loc.getZ() + 0.5,
                            1, 0, 0, 0, 0);
                    }

                    // 螺旋符文（金色粒子）
                    double spiralRadius = 1.0;
                    double spiralHeight = 2.5;
                    int spiralPoints = 3;
                    for (int i = 0; i < spiralPoints; i++) {
                        double spiralAngle = angle + (i * 2 * Math.PI / spiralPoints);
                        double spiralY = (ticksElapsed % 50) * (spiralHeight / 50.0);
                        double spiralX = loc.getX() + 0.5 + Math.cos(spiralAngle) * spiralRadius;
                        double spiralZ = loc.getZ() + 0.5 + Math.sin(spiralAngle) * spiralRadius;
                        world.spawnParticle(Particle.ENCHANTED_HIT,
                            spiralX, loc.getY() + spiralY, spiralZ,
                            1, 0, 0, 0, 0);
                    }
                }

                ticksElapsed++;
            }
        }.runTaskTimer(plugin, 0L, 4L).getTaskId();
    }

    /** 在两点之间绘制粒子连线 */
    private void drawLine(World world, Particle particle,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2) {
        int steps = 4;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + (x2 - x1) * t;
            double y = y1 + (y2 - y1) * t;
            double z = z1 + (z2 - z1) * t;
            world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    public void stopParticleShow() {
        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
            showParticlesTaskId = -1;
        }
    }

    /* ========== 数据持久化 ========== */

    /**
     * 从 arenas.yml 加载目标竞技场的生成点 / 核心 / 村民 / 商店 NPC 配置。
     * 多竞技场模式下这些数据全部按竞技场隔离存储在 {@code arenas.<id>} 下。
     */
    public void loadSpawnPoints() {
        String arenaId = gameManager.getArenaId();
        arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            plugin.saveResource("arenas.yml", false);
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);

        // 世界未加载时必须「先判空再清空」：一旦清了内存，之后任意一次部署命令都会触发
        // saveSpawnPoints() 整份写回，把空的生成点列表落盘，竞技场就变成「未部署」了。
        World world = gameManager.getGameWorld();
        if (world == null) {
            plugin.getLogger().warning("Arena world not found, deferring load.");
            return;
        }

        savedSpawnPoints.clear();
        savedMobSpawnPoints.clear();
        // 本方法的语义是「从磁盘重新读取」，内存里的 core / 商店位置必须一起清空：
        // 重置竞技场会删掉 arenas.yml 的 core 与 shop-npc，若内存还留着旧值，
        // 下一次 saveSpawnPoints() 就会用旧值把它们写回去（重置等于没做）
        savedCoreLocation = null;
        savedShopNpcLocation = null;

        // 读取该竞技场的生成点列表
        List<Map<?, ?>> list = arenasConfig.getMapList("arenas." + arenaId + ".spawn-points");
        for (Map<?, ?> map : list) {
            Object xo = map.get("x"), yo = map.get("y"), zo = map.get("z");
            if (!(xo instanceof Number) || !(yo instanceof Number) || !(zo instanceof Number)) continue;
            double x = ((Number) xo).doubleValue();
            double y = ((Number) yo).doubleValue();
            double z = ((Number) zo).doubleValue();
            savedSpawnPoints.add(new Location(world, x, y, z));
        }

        // 读取该竞技场的核心位置
        String corePath = "arenas." + arenaId + ".core";
        if (arenasConfig.isConfigurationSection(corePath)) {
            double cx = arenasConfig.getDouble(corePath + ".x");
            double cy = arenasConfig.getDouble(corePath + ".y");
            double cz = arenasConfig.getDouble(corePath + ".z");
            savedCoreLocation = new Location(world, cx, cy, cz);
        }

        // 读取该竞技场的怪物刷怪点（STORY-1.2 新 schema；旧存档缺键 → 空列表，刷怪双用回退）
        List<Map<?, ?>> mobList = arenasConfig.getMapList("arenas." + arenaId + ".mob-spawn-points");
        for (Map<?, ?> map : mobList) {
            Object xo = map.get("x"), yo = map.get("y"), zo = map.get("z");
            if (!(xo instanceof Number) || !(yo instanceof Number) || !(zo instanceof Number)) continue;
            double x = ((Number) xo).doubleValue();
            double y = ((Number) yo).doubleValue();
            double z = ((Number) zo).doubleValue();
            savedMobSpawnPoints.add(new Location(world, x, y, z));
        }

        // 读取该竞技场的村民位置
        savedVillagerPositions.clear();
        List<Map<?, ?>> villagerList = arenasConfig.getMapList("arenas." + arenaId + ".villagers");
        for (Map<?, ?> map : villagerList) {
            Object xo = map.get("x"), yo = map.get("y"), zo = map.get("z");
            if (!(xo instanceof Number) || !(yo instanceof Number) || !(zo instanceof Number)) continue;
            savedVillagerPositions.add(new Location(world,
                    ((Number) xo).doubleValue(),
                    ((Number) yo).doubleValue(),
                    ((Number) zo).doubleValue()));
        }

        ensureSpawnPointsConfigLoaded();
        savedShopNpcLocation = loadShopNpcLocation(arenaId, world);
    }

    /**
     * 确保旧版全局 spawnpoints.yml 已加载。
     *
     * <p>生成点 / 核心 / 村民 / 商店 NPC 位置都已迁移到 arenas.yml（按竞技场隔离），
     * spawnpoints.yml 现在只在<b>读取旧数据做一次性迁移</b>时使用，本插件不再写它。</p>
     */
    private void ensureSpawnPointsConfigLoaded() {
        if (spawnPointsConfig != null) return;
        spawnPointsFile = new File(plugin.getDataFolder(), "spawnpoints.yml");
        if (!spawnPointsFile.exists()) {
            plugin.saveResource("spawnpoints.yml", false);
        }
        spawnPointsConfig = YamlConfiguration.loadConfiguration(spawnPointsFile);
    }

    /* ========== 商店村民位置（按竞技场存储在 arenas.yml） ========== */

    /**
     * 读取目标竞技场的商店 NPC 位置：{@code arenas.<id>.shop-npc}。
     *
     * <p>该节点缺失时会尝试从旧版全局存储（spawnpoints.yml 的 {@code shop-npc}）迁移一次
     * （见 {@link #migrateLegacyShopNpc}）。节点存在但内容损坏时返回 null 且<b>不</b>迁移，
     * 避免用旧数据覆盖管理员刚改过（但写坏）的配置。</p>
     *
     * <p>已经由管理员定夺过（打过 {@link ArenaManager#SHOP_NPC_MIGRATED_KEY} 标记）的竞技场
     * <b>不迁移</b>：重置竞技场会删掉 {@code shop-npc}，此时再迁移会把刚清掉的 NPC 原地复活。</p>
     *
     * @param arenaId    竞技场 ID
     * @param arenaWorld 竞技场世界（位置所属世界，shop-npc 不再单独存 world）
     */
    private Location loadShopNpcLocation(String arenaId, World arenaWorld) {
        String path = "arenas." + arenaId + ".shop-npc";
        if (arenasConfig.isConfigurationSection(path)) {
            return readLocation(arenasConfig.getConfigurationSection(path), arenaWorld, path);
        }
        if (arenasConfig.getBoolean(migratedFlagPath(arenaId), false)) return null;
        return migrateLegacyShopNpc(arenaId, arenaWorld, path);
    }

    /** {@link ArenaManager#SHOP_NPC_MIGRATED_KEY} 的完整路径 */
    private static String migratedFlagPath(String arenaId) {
        return "arenas." + arenaId + "." + ArenaManager.SHOP_NPC_MIGRATED_KEY;
    }

    /**
     * 旧数据迁移：把 spawnpoints.yml 里的全局 {@code shop-npc} 复制进当前竞技场。
     *
     * <p>只在 {@code world} 与该竞技场世界一致时迁移（否则这份数据属于别的竞技场）。
     * 迁移结果立刻写入 arenas.yml，<b>下次加载会直接命中 {@code arenas.<id>.shop-npc} 分支、
     * 不再进入本方法</b>，因此迁移天然只发生一次（幂等）。即使写入失败，下次加载也会
     * 从同一份旧数据重新迁移，结果一致，不会产生重复或错位。</p>
     *
     * <p>旧键<b>保留不删</b>：历史配置可能存在多个竞技场共用同一份全局数据的场景，
     * 删掉会让后加载的竞技场拿不到位置。</p>
     *
     * @return 迁移出的位置（同时已写盘）；无旧数据或世界不匹配时返回 null
     */
    private Location migrateLegacyShopNpc(String arenaId, World arenaWorld, String path) {
        ensureSpawnPointsConfigLoaded();
        if (!spawnPointsConfig.isConfigurationSection("shop-npc")) return null;

        String legacyWorld = spawnPointsConfig.getString("shop-npc.world");
        if (legacyWorld == null || !legacyWorld.equals(arenaWorld.getName())) return null;

        Location legacy = readLocation(spawnPointsConfig.getConfigurationSection("shop-npc"),
                arenaWorld, "spawnpoints.yml:shop-npc");
        if (legacy == null) return null;

        writeLocation(arenasConfig, path, legacy);
        // 打上「已迁移」标记：迁移只应发生一次，之后即使 shop-npc 被删（重置或主动清除）也不再回填
        arenasConfig.set(migratedFlagPath(arenaId), true);
        try {
            arenasConfig.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("迁移商店 NPC 位置到 arenas.yml 失败: " + e.getMessage());
            return legacy;
        }
        plugin.getLogger().warning("已将旧的全局商店 NPC 位置迁移到竞技场 " + arenaId
                + "（arenas.yml 的 " + path + "）；spawnpoints.yml 的 shop-npc 键保留未删除");
        return legacy;
    }

    /** 读取 {@code x/y/z/yaw/pitch} 位置节点；节点缺失、坐标缺失或非法时返回 null */
    private Location readLocation(ConfigurationSection section, World world, String path) {
        if (section == null) return null;
        // 必须用 isSet 判断：getDouble 对缺失的键返回 0.0，会把损坏的节点当成 (0, 0, 0) 生成 NPC
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            plugin.getLogger().warning("商店 NPC 位置坐标缺失，已忽略: " + path);
            return null;
        }
        double x = section.getDouble("x"), y = section.getDouble("y"), z = section.getDouble("z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            plugin.getLogger().warning("商店 NPC 位置坐标无效，已忽略: " + path);
            return null;
        }
        float yaw = (float) section.getDouble("yaw"), pitch = (float) section.getDouble("pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** 写入 {@code x/y/z/yaw/pitch} 位置节点 */
    private void writeLocation(FileConfiguration yaml, String path, Location location) {
        yaml.set(path + ".x", location.getX());
        yaml.set(path + ".y", location.getY());
        yaml.set(path + ".z", location.getZ());
        yaml.set(path + ".yaw", location.getYaw());
        yaml.set(path + ".pitch", location.getPitch());
    }

    public void saveSpawnPoints() {
        String arenaId = gameManager.getArenaId();

        // 生成点与核心位置写入 arenas.yml（按竞技场隔离）。
        // 本方法随后会用 arenasConfig 整份覆写文件，所以必须基于磁盘最新内容重新读取：
        // 复用字段里的旧副本回写，会把其它写入方在此期间落盘的内容整份回滚成旧值
        // （ArenaManager.updateGameParams / clearDeploymentData、其它竞技场的 saveSpawnPoints）。
        if (arenasFile == null) {
            arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Location loc : savedSpawnPoints) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", loc.getX());
            map.put("y", loc.getY());
            map.put("z", loc.getZ());
            list.add(map);
        }
        arenasConfig.set("arenas." + arenaId + ".spawn-points", list);

        // 怪物刷怪点（STORY-1.2：与 spawn-points 同级的新 schema 键）
        List<Map<String, Object>> mobList = new ArrayList<>();
        for (Location loc : savedMobSpawnPoints) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", loc.getX());
            map.put("y", loc.getY());
            map.put("z", loc.getZ());
            mobList.add(map);
        }
        arenasConfig.set("arenas." + arenaId + ".mob-spawn-points", mobList);

        // 村民位置（全部阵亡 = 游戏失败）
        List<Map<String, Object>> villagerList = new ArrayList<>();
        for (Location loc : savedVillagerPositions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", loc.getX());
            map.put("y", loc.getY());
            map.put("z", loc.getZ());
            villagerList.add(map);
        }
        arenasConfig.set("arenas." + arenaId + ".villagers", villagerList);

        if (savedCoreLocation != null) {
            arenasConfig.set("arenas." + arenaId + ".core.x", savedCoreLocation.getX());
            arenasConfig.set("arenas." + arenaId + ".core.y", savedCoreLocation.getY());
            arenasConfig.set("arenas." + arenaId + ".core.z", savedCoreLocation.getZ());
        }

        try {
            arenasConfig.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save arenas.yml: " + e.getMessage());
        }

        // 刷新内存中的 GameManager（生成点与核心位置）
        gameManager.setSpawnPoints(savedSpawnPoints);
        gameManager.setMobSpawnPoints(savedMobSpawnPoints);
        if (savedCoreLocation != null) {
            gameManager.setCoreLocation(savedCoreLocation);
        }

        // 商店村民位置写入同一份 arenas.yml 的 arenas.<id>.shop-npc（按竞技场隔离）
        saveShopNpcLocation();
    }

    /**
     * 单独持久化商店村民位置到 arenas.yml 的 {@code arenas.<id>.shop-npc}。
     *
     * <p>位置为 null 时必须<b>删除</b>该节点：旧实现只清内存、不删 YAML 键，
     * 服务器重启后 {@link com.hilltopvillage.shop.NpcManager#respawnFromConfig()}
     * 会把已经「清除」的 NPC 又生成回来。</p>
     *
     * <p>每次写入前重新从磁盘读取：{@code arenasConfig} 字段可能是较旧的副本，
     * 直接用它整份覆写会丢掉在此期间由其他管理器（如 ArenaManager）写入的内容。</p>
     */
    private void saveShopNpcLocation() {
        if (arenasFile == null) {
            arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(arenasFile);
        String path = "arenas." + gameManager.getArenaId() + ".shop-npc";

        if (savedShopNpcLocation != null) {
            writeLocation(yaml, path, savedShopNpcLocation);
        } else if (yaml.isSet(path)) {
            // isSet 先行判断：对不存在的键 set(path, null) 会创建空的父节点（竞技场不存在时会写入垃圾节点）
            yaml.set(path, null);
        } else {
            return; // 无位置且磁盘上也没有：无需写盘
        }
        // 无论写入还是清除都打上标记：管理员对商店 NPC 的显式决定优先于旧版遗留数据，
        // 否则刚清除的位置会在下次加载时被 migrateLegacyShopNpc 从 spawnpoints.yml 复活
        yaml.set(migratedFlagPath(gameManager.getArenaId()), true);

        try {
            yaml.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save shop NPC location to arenas.yml: " + e.getMessage());
            return;
        }
        // 与磁盘保持一致：否则后续 saveSpawnPoints() 会用缺少 shop-npc 的旧副本整份覆写、把位置抹掉
        arenasConfig = yaml;
    }

    /* ========== 生成点操作 ========== */

    public List<Location> getSpawnPoints() {
        return Collections.unmodifiableList(savedSpawnPoints);
    }

    public Location getCoreLocation() {
        return savedCoreLocation != null ? savedCoreLocation.clone() : null;
    }

    public boolean hasFixedSpawnPoints() {
        return !savedSpawnPoints.isEmpty();
    }

    public void addSpawnPoint(Location location) {
        // 保存方块坐标（整数），粒子显示时+0.5才是方块正中心
        Location blockCenter = new Location(
            location.getWorld(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        savedSpawnPoints.add(blockCenter);
        saveSpawnPoints();
    }

    public boolean removeSpawnPoint(int index) {
        if (index < 0 || index >= savedSpawnPoints.size()) return false;
        savedSpawnPoints.remove(index);
        saveSpawnPoints();
        return true;
    }

    public void clearSpawnPoints() {
        savedSpawnPoints.clear();
        saveSpawnPoints();
    }

    /* ========== 怪物刷怪点操作（STORY-1.2：与玩家出生点分离的第二套坐标） ========== */

    /** 已部署的怪物刷怪点（只读视图；旧存档返回空列表，刷怪双用回退到玩家出生点） */
    public List<Location> getMobSpawnPoints() {
        return Collections.unmodifiableList(savedMobSpawnPoints);
    }

    public boolean hasMobSpawnPoints() {
        return !savedMobSpawnPoints.isEmpty();
    }

    public void addMobSpawnPoint(Location location) {
        // 保存方块坐标（整数），与 addSpawnPoint 同口径
        Location blockCenter = new Location(
            location.getWorld(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        savedMobSpawnPoints.add(blockCenter);
        saveSpawnPoints();
    }

    public boolean removeMobSpawnPoint(int index) {
        if (index < 0 || index >= savedMobSpawnPoints.size()) return false;
        savedMobSpawnPoints.remove(index);
        saveSpawnPoints();
        return true;
    }

    public void clearMobSpawnPoints() {
        savedMobSpawnPoints.clear();
        saveSpawnPoints();
    }

    /* ========== 村民操作（全部阵亡 = 游戏失败） ========== */

    /** 已登记的村民位置（防御性拷贝） */
    public List<Location> getVillagers() {
        List<Location> copies = new ArrayList<>(savedVillagerPositions.size());
        for (Location loc : savedVillagerPositions) copies.add(loc.clone());
        return Collections.unmodifiableList(copies);
    }

    /** 登记一个村民位置（保存方块坐标） */
    public void addVillager(Location location) {
        Location blockCenter = new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
        savedVillagerPositions.add(blockCenter);
        saveSpawnPoints();
    }

    public boolean removeVillager(int index) {
        if (index < 0 || index >= savedVillagerPositions.size()) return false;
        savedVillagerPositions.remove(index);
        saveSpawnPoints();
        return true;
    }

    public void clearVillagers() {
        savedVillagerPositions.clear();
        saveSpawnPoints();
    }

    public void setCoreLocation(Location location) {
        savedCoreLocation = (location != null) ? location.clone() : null;
        saveSpawnPoints();
    }

    /* ========== 商店村民位置 ========== */

    /** 本竞技场的商店村民位置（未设置返回 null） */
    public Location getShopNpcLocation() {
        return savedShopNpcLocation != null ? savedShopNpcLocation.clone() : null;
    }

    /** 设置商店村民位置并持久化到 arenas.yml 的 {@code arenas.<id>.shop-npc}（null = 删除该节点） */
    public void setShopNpcLocation(Location location) {
        savedShopNpcLocation = (location != null) ? location.clone() : null;
        saveSpawnPoints();
    }

    /* ========== 部署菜单 ========== */

    public void openDeployMenu(Player admin, LanguageManager lang) {
        if (!admin.hasPermission("hilltopvillage.admin")) {
            admin.sendMessage(lang.getFor(admin, "deploy.menu-no-permission"));
            return;
        }

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-title-line1"));
        admin.sendMessage("");

        admin.sendMessage(lang.getFor(admin, "deploy.menu-status-label"));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-state", getStateDisplay(admin, lang, gameManager.getState())));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-core-coords", formatCoreLocation(admin, lang)));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-spawn-count",
                savedSpawnPoints.size(),
                savedSpawnPoints.isEmpty() ? lang.getFor(admin, "deploy.menu-random-yes") : lang.getFor(admin, "deploy.menu-random-no")));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-mobspawn-count", savedMobSpawnPoints.size()));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-nodes", gameManager.getNodeSystem().getTotalCount()));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-villagers", savedVillagerPositions.size()));
        admin.sendMessage("");

        admin.sendMessage(lang.getFor(admin, "deploy.menu-operations-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy setcore" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-setcore"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy addspawn" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-addspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy listspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-listspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy delspawn <id>" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-delspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy showparticles" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-showparticles"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy clearspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-clearspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy addmobspawn" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-addmobspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy listmobspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-listmobspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy delmobspawn <id>" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-delmobspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy clearmobspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-clearmobspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy reloadnodes" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-reloadnodes"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy confirmclear" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-confirmclear"));
        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-villager-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy addvillager" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-addvillager"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy listvillagers" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-listvillagers"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy delvillager <id>" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-delvillager"));

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-game-control-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop start" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-start"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop stop" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-stop"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop status" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-status"));

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-tip"));
    }

    private String getStateDisplay(Player admin, LanguageManager lang, GameState state) {
        switch (state) {
            case WAITING: return lang.getFor(admin, "state.waiting");
            case STARTING: return lang.getFor(admin, "state.starting");
            case RUNNING: return lang.getFor(admin, "state.running");
            case WAVE_INTERMISSION: return lang.getFor(admin, "state.wave-intermission");
            case VICTORY: return lang.getFor(admin, "state.victory");
            case DEFEAT: return lang.getFor(admin, "state.defeat");
            default: return state.name();
        }
    }

    private String formatCoreLocation(Player admin, LanguageManager lang) {
        Location core = savedCoreLocation != null ? savedCoreLocation : gameManager.getCoreLocation();
        if (core == null) return lang.getFor(admin, "deploy.menu-core-unset");
        return ChatColor.WHITE + String.format("%.1f, %.1f, %.1f", core.getX(), core.getY(), core.getZ());
    }
}