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
    /**
     * P2-10：最近一次 arenas.yml 写盘是否失败（由 {@link #saveSpawnPoints()} 设置）。
     * 向导在 autoSave / autoSaveSilent 中读取并清除，用于向管理员如实播报保存失败。
     */
    private volatile boolean lastSaveFailed = false;

    public AdminDeployManager(HilltopVillagePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.savedSpawnPoints = new ArrayList<>();
        this.savedMobSpawnPoints = new ArrayList<>();
        this.savedVillagerPositions = new ArrayList<>();
        loadSpawnPoints();
    }

    /* ========== 粒子展示 ========== */

    /** 单次预览的默认持续时间（tick）：600 = 30 秒 */
    private static final long PREVIEW_DURATION_TICKS = 600L;
    /** 预览任务轮询间隔（tick），与 {@link LocationParticlePreview} 的刷新频率一致 */
    private static final long PREVIEW_PERIOD_TICKS = 4L;
    /** 距离剔除半径（格）：超过该距离的点不绘制粒子 */
    private static final double PREVIEW_VIEW_DISTANCE = 64.0;

    public void showSpawnPointParticles(Player viewer, LanguageManager lang) {
        showSpawnPointParticles(viewer, lang, PREVIEW_DURATION_TICKS);
    }

    public void showSpawnPointParticles(Player viewer, LanguageManager lang, long durationTicks) {
        showPointParticles(viewer, savedSpawnPoints, PointKind.SPAWN, lang, durationTicks);
    }

    /**
     * 怪物刷怪点粒子预览（F-1：与玩家出生点分开的第二套数据源 {@link #savedMobSpawnPoints}）。
     *
     * <p>向导刷怪点管理 GUI 的 [显示粒子] 由此进入。修复前该按钮误调
     * {@link #showSpawnPointParticles}，粒子全发到玩家出生点、出生点为空时静默无粒子；
     * 现在空列表会明确提示 {@code particles.no-mobspawnpoints}。</p>
     */
    public void showMobSpawnPointParticles(Player viewer, LanguageManager lang) {
        showPointParticles(viewer, savedMobSpawnPoints, PointKind.MOB_SPAWN, lang, PREVIEW_DURATION_TICKS);
    }

    /** 显示节点粒子（节点位置由调用方提供：向导场景下可能还未经 saveSpawnPoints 落盘） */
    public void showNodeParticles(Player viewer, List<Location> nodeLocations, LanguageManager lang) {
        showPointParticles(viewer, nodeLocations, PointKind.NODE, lang, PREVIEW_DURATION_TICKS);
    }

    /** 显示待保护村民位置的粒子预览（F-7：向导第 5 步村民管理 GUI 的 [显示粒子]） */
    public void showVillagerParticles(Player viewer, LanguageManager lang) {
        showPointParticles(viewer, savedVillagerPositions, PointKind.VILLAGER, lang, PREVIEW_DURATION_TICKS);
    }

    /** 显示商店村民位置的粒子预览（F-7：商店只有一个坐标；未设置时走空列表提示） */
    public void showShopParticles(Player viewer, LanguageManager lang) {
        List<Location> targets = savedShopNpcLocation == null
                ? Collections.emptyList()
                : Collections.singletonList(savedShopNpcLocation);
        showPointParticles(viewer, targets, PointKind.SHOP, lang, PREVIEW_DURATION_TICKS);
    }

    /**
     * 五类点位预览的统一实现。
     *
     * <p>提示文案由本方法统一发送：空列表 → {@code kind.emptyKey()}，有列表 →
     * {@code kind.showingKey()}（占位符 {0}=点位数、{1}=持续秒数）+ 颜色图例
     * {@code particles.hint}。调用方（向导 / 命令）不再自行补发「已启动」类提示 ——
     * 修复前向导在空列表时也会照发 {@code setup.particles-started}，与「无点位」提示互相矛盾。</p>
     *
     * <p>颜色与绘制统一走 {@link PointParticleRenderer}（配色表见 {@link PointKind}），
     * 与竞技场管理 GUI 的 {@link LocationParticlePreview} 视觉完全一致。</p>
     *
     * <p>同一实例同一时刻只保留一个预览任务（与旧行为一致）：同一竞技场的第二个管理员
     * 触发预览会打断前一个。按玩家隔离的任务隔离由 {@link LocationParticlePreview} 负责。</p>
     */
    private void showPointParticles(Player viewer, List<Location> locations, PointKind kind,
                                    LanguageManager lang, long durationTicks) {
        if (locations.isEmpty()) {
            viewer.sendMessage(lang.getFor(viewer, kind.emptyKey()));
            return;
        }

        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
        }

        viewer.sendMessage(lang.getFor(viewer, kind.showingKey(), locations.size(), durationTicks / 20));
        viewer.sendMessage(lang.getFor(viewer, "particles.hint"));

        // 快照一份，避免预览过程中源列表被 add / remove 改动
        final List<Location> targets = new ArrayList<>(locations);

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

                for (Location loc : targets) {
                    World world = loc.getWorld();
                    if (world == null) continue;  // 世界未加载：跳过该点，不抛异常
                    // 距离剔除：viewer 超过 64 格则跳过该点
                    if (viewer.getWorld() != world
                            || viewer.getLocation().distanceSquared(loc) > PREVIEW_VIEW_DISTANCE * PREVIEW_VIEW_DISTANCE) {
                        continue;
                    }

                    PointParticleRenderer.drawMarker(world, loc, kind, angle, ticksElapsed);
                }

                ticksElapsed++;
            }
        }.runTaskTimer(plugin, 0L, PREVIEW_PERIOD_TICKS).getTaskId();
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

    /**
     * 把内存中的部署数据写入 arenas.yml。
     *
     * <p>P2-10：返回写盘结果（IOException 时 log severe + false），供调用方（向导）向管理员
     * 如实播报 —— 修复前写盘失败全程静默、向导照报「已添加/已保存」，第 8 步还会把「写失败」
     * 误诊为 enabled 配置问题乱引导。</p>
     *
     * @return true = 生成点/核心与商店位置均已落盘（或无需写盘）；false = 至少一次写盘失败
     */
    public boolean saveSpawnPoints() {
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

        boolean saved = true;
        try {
            arenasConfig.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save arenas.yml: " + e.getMessage());
            saved = false;
        }

        // 刷新内存中的 GameManager（生成点与核心位置）
        gameManager.setSpawnPoints(savedSpawnPoints);
        gameManager.setMobSpawnPoints(savedMobSpawnPoints);
        if (savedCoreLocation != null) {
            gameManager.setCoreLocation(savedCoreLocation);
        }

        // 商店村民位置写入同一份 arenas.yml 的 arenas.<id>.shop-npc（按竞技场隔离）
        if (!saveShopNpcLocation()) {
            saved = false;
        }
        // P2-10：记录本次写盘结果（反映最近一次写入，供向导的 autoSave 调用点消费并如实播报）
        lastSaveFailed = !saved;
        return saved;
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
     *
     * @return true = 已落盘或无需写盘；false = 写盘失败（已 log severe）
     */
    private boolean saveShopNpcLocation() {
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
            return true; // 无位置且磁盘上也没有：无需写盘
        }
        // 无论写入还是清除都打上标记：管理员对商店 NPC 的显式决定优先于旧版遗留数据，
        // 否则刚清除的位置会在下次加载时被 migrateLegacyShopNpc 从 spawnpoints.yml 复活
        yaml.set(migratedFlagPath(gameManager.getArenaId()), true);

        try {
            yaml.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save shop NPC location to arenas.yml: " + e.getMessage());
            return false;
        }
        // 与磁盘保持一致：否则后续 saveSpawnPoints() 会用缺少 shop-npc 的旧副本整份覆写、把位置抹掉
        arenasConfig = yaml;
        return true;
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

    /**
     * M-02 兜底：坐标世界与竞技场世界一致性校验。
     *
     * <p>向导的 handle() 入口已统一加世界守卫；本方法防的是<b>未来新增入口漏加守卫</b>——
     * 异世界坐标一旦落盘（world 字段被 arenas.yml 丢弃、下次加载按竞技场世界重建）会产生
     * 「重启后坐标固化到错误位置」的静默数据损坏，代价远高于这里的一次日志。</p>
     */
    private boolean isInArenaWorld(Location location) {
        World arenaWorld = gameManager.getGameWorld();
        return location != null && location.getWorld() != null
                && arenaWorld != null && location.getWorld().equals(arenaWorld);
    }

    /** 异世界坐标被拒收时的日志行（世界名 + 坐标，便于排障） */
    private void logRejectedLocation(String kind, Location location) {
        World arenaWorld = gameManager.getGameWorld();
        plugin.getLogger().warning("拒绝登记异世界的" + kind + "（期望世界 "
                + (arenaWorld != null ? arenaWorld.getName() : "未加载")
                + "，实际 "
                + (location != null && location.getWorld() != null ? location.getWorld().getName() : "null")
                + " @ " + (location != null ? location.getBlockX() + "," + location.getBlockY()
                        + "," + location.getBlockZ() : "null") + "）");
    }

    /**
     * P2-10：取走（读取并清除）最近一次写盘失败标记（主线程专用；读取即消费，避免重复提示）。
     */
    public boolean consumeSaveFailure() {
        boolean failed = lastSaveFailed;
        lastSaveFailed = false;
        return failed;
    }

    public void addSpawnPoint(Location location) {
        // M-02 兜底：异世界坐标拒收 + 日志（不落盘）
        if (!isInArenaWorld(location)) {
            logRejectedLocation("玩家出生点", location);
            return;
        }
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
        // M-02 兜底：异世界坐标拒收 + 日志（不落盘）
        if (!isInArenaWorld(location)) {
            logRejectedLocation("怪物刷怪点", location);
            return;
        }
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

    /** 登记一个村民位置（保存方块坐标；异世界坐标拒收 + 日志，见 M-02 兜底） */
    public void addVillager(Location location) {
        if (!isInArenaWorld(location)) {
            logRejectedLocation("村民位置", location);
            return;
        }
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

    /**
     * 设置竞技场核心位置并落盘。
     *
     * @return true = 已落盘（或新值为 null 时也视为成功，删除语义见 P2-4）；false = 写盘失败
     */
    public boolean setCoreLocation(Location location) {
        savedCoreLocation = (location != null) ? location.clone() : null;
        return saveSpawnPoints();
    }

    /* ========== 商店村民位置 ========== */

    /** 本竞技场的商店村民位置（未设置返回 null） */
    public Location getShopNpcLocation() {
        return savedShopNpcLocation != null ? savedShopNpcLocation.clone() : null;
    }

    /**
     * 设置商店村民位置并持久化到 arenas.yml 的 {@code arenas.<id>.shop-npc}（null = 删除该节点）。
     *
     * @return true = 已落盘；false = 写盘失败（已 log severe，调用方应向管理员如实播报）
     */
    public boolean setShopNpcLocation(Location location) {
        savedShopNpcLocation = (location != null) ? location.clone() : null;
        return saveSpawnPoints();
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
        admin.sendMessage(lang.getFor(admin, "deploy.menu-shop-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy setshop" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-setshop"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy clearshop" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-clearshop"));

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