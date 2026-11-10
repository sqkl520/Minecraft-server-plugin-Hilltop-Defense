package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.util.LanguageManager;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

public class AdminDeployManager {

    private final HilltopVillagePlugin plugin;
    private final GameManager gameManager;
    /**
     * 旧版全局数据文件（只读、只在一次性迁移时用；本插件不再写它）。
     *
     * <p>读取走 {@link YamlFiles#load}（不做语义哨兵——该文件不可写，坏了也只需降级为「无旧数据」）。</p>
     */
    private File spawnPointsFile;
    private FileConfiguration spawnPointsConfig;
    /** 多竞技场模式：竞技场配置（arenas.yml） */
    private File arenasFile;
    private YamlConfiguration arenasConfig;
    /**
     * arenas.yml 的共享读写闸门。
     *
     * <p><b>必须复用</b> {@code ArenaManager#getArenasGate()}，<b>禁止自建</b>：arenas.yml 有 3 个写入方，
     * 各持一份 gate 会出现「A 判只读、B 照写」，只读降级形同虚设。</p>
     */
    private YamlFileGate arenasGate;
    private final List<Location> savedSpawnPoints;
    /**
     * 已部署的怪物刷怪点（STORY-1.2：与玩家出生点 {@link #savedSpawnPoints} 分离的第二套坐标，
     * 对应 arenas.yml 的 {@code arenas.<id>.mob-spawn-points}）。
     */
    private final List<Location> savedMobSpawnPoints;
    /** 待保护的村民位置（全部阵亡 = 游戏失败） */
    private final List<Location> savedVillagerPositions;
    private Location savedCoreLocation;
    /**
     * 等待区位置（{@code arenas.<id>.waiting-location}；null = 未单独设置）。
     *
     * <p>等待区是玩家 {@code /hilltop join} 后、开局前的落点。未单独设置时由
     * {@link ArenaManager#readDefinition} 回退到核心位置（{@link com.hilltopvillage.arena.ArenaDefinition}
     * 构造函数同样按此兜底），因此本字段允许为 null。</p>
     *
     * <p><b>刻意不写进 {@link ArenaManager#DEPLOYMENT_KEYS}</b>：与 {@code world} / {@code enabled} 同级，
     * 「恢复初始状态」只清部署期位置，不清等待区（重置后等待区继续跟随新的核心）。</p>
     */
    private Location savedWaitingLocation;
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

    /* ========== 数据持久化 ========== */

    /**
     * 取 arenas.yml 的<b>共享</b>闸门（{@code ArenaManager} 持有）。
     *
     * <p>刻意不在这里 new 一个：arenas.yml 有 3 个写入方，各持一份会让只读降级失效。
     * 正常初始化顺序（{@code MultiArenaManager} 先构造 {@code ArenaManager}，之后才可能构造
     * {@code GameManager}）保证这里必然拿得到；拿不到时返回 null，调用方按「不可用」处理
     * （拒读拒写），而不是退化成一份私有的闸门。</p>
     */
    private YamlFileGate arenasGate() {
        if (arenasGate == null && plugin.getMultiArenaManager() != null) {
            arenasGate = plugin.getMultiArenaManager().getDefinitionManager().getArenasGate();
        }
        return arenasGate;
    }

    /**
     * 从 arenas.yml 加载目标竞技场的生成点 / 核心 / 村民 / 商店 NPC 配置。
     * 多竞技场模式下这些数据全部按竞技场隔离存储在 {@code arenas.<id>} 下。
     */
    public void loadSpawnPoints() {
        String arenaId = gameManager.getArenaId();
        arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        YamlFileGate gate = arenasGate();
        if (gate == null) {
            lastSaveFailed = true;
            plugin.getLogger().severe("arenas.yml gate is unavailable (ArenaManager not initialised); "
                    + "keeping the in-memory deployment data and blocking writes");
            return;
        }
        // 只读降级中**不得**从 JAR 模板重建：文件被隔离成 *.corrupt 后若这里再 saveResource 一份出厂模板，
        // 下一次 gate.load() 会读到「合法的新文件」并自动解封，降级被重建动作绕过。
        if (!gate.isReadOnly() && !arenasFile.exists()) {
            plugin.saveResource("arenas.yml", false);
        }

        // 闸门读盘失败时**必须早退、绝不清空内存**：下面的 clear() 会把 savedSpawnPoints /
        // savedCoreLocation 洗成空，随后任意一次部署命令都会触发 saveSpawnPoints() 整份写回，
        // 把空的生成点列表落盘，竞技场就变成「未部署」了。
        YamlFiles.Loaded loaded = gate.load();
        // 闸门已锁只读时，即使文件此刻不存在（隔离后被改名 *.corrupt）也必须早退：
        // YamlFiles.load 对「不存在」返回 ABSENT + 空配置（usable），照走会 clear() 掉内存数据。
        if (!loaded.usable() || gate.isReadOnly()) {
            lastSaveFailed = true;              // 让向导 autoSave 如实播报
            plugin.getLogger().warning("arenas.yml is unusable; keeping the in-memory deployment data "
                    + "(writes are blocked until the file is repaired)");
            return;
        }
        arenasConfig = loaded.config();

        // 世界未加载时必须「先判空再清空」：一旦清了内存，之后任意一次部署命令都会触发
        // saveSpawnPoints() 整份写回，把空的生成点列表落盘，竞技场就变成「未部署」了。
        World world = gameManager.getGameWorld();
        if (world == null) {
            plugin.getLogger().warning("Arena world not found, deferring load.");
            return;
        }

        savedSpawnPoints.clear();
        savedMobSpawnPoints.clear();
        // 本方法的语义是「从磁盘重新读取」，内存里的 core / 等待区 / 商店位置必须一起清空：
        // 重置竞技场会删掉 arenas.yml 的 core 与 shop-npc，若内存还留着旧值，
        // 下一次 saveSpawnPoints() 就会用旧值把它们写回去（重置等于没做）。
        // 等待区不在 DEPLOYMENT_KEYS 里（重置不会删它），这里清空只是「重新以磁盘为准」，
        // 下面的读取分支会把磁盘上仍存在的 waiting-location 读回来。
        savedCoreLocation = null;
        savedWaitingLocation = null;
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

        // 读取该竞技场的等待区位置（可选；缺键 = 未单独设置，运行时由 ArenaDefinition 回退到 core）
        savedWaitingLocation = null;
        String waitingPath = "arenas." + arenaId + ".waiting-location";
        if (arenasConfig.isConfigurationSection(waitingPath)) {
            savedWaitingLocation = readLocation(
                    arenasConfig.getConfigurationSection(waitingPath), world, waitingPath, "Waiting area");
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
        // 只读、只在一次性迁移时用；无哨兵——这份文件本插件不写，坏了只需降级为「无旧数据」。
        YamlFiles.Loaded loaded = YamlFiles.load(spawnPointsFile, null, plugin.getLogger());
        if (!loaded.usable()) {
            plugin.getLogger().warning("spawnpoints.yml is unusable; the legacy shop NPC location will not be migrated");
            spawnPointsConfig = new YamlConfiguration();
            return;
        }
        spawnPointsConfig = loaded.config();
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
        YamlFileGate gate = arenasGate();
        if (gate == null || !gate.write(arenasConfig)) {
            // 失败**不阻断**（返回值语义不变）：下次加载会从同一份旧数据重试，幂等
            plugin.getLogger().severe("Failed to migrate the shop NPC location to arenas.yml "
                    + "(writes are blocked or the write failed); it will be retried on the next load");
            return legacy;
        }
        plugin.getLogger().warning("Migrated the legacy global shop NPC location to arena " + arenaId
                + " (arenas.yml " + path + "); the shop-npc key in spawnpoints.yml was kept");
        return legacy;
    }

    /** 读取 {@code x/y/z/yaw/pitch} 位置节点；节点缺失、坐标缺失或非法时返回 null */
    private Location readLocation(ConfigurationSection section, World world, String path) {
        return readLocation(section, world, path, "Shop NPC");
    }

    /**
     * 读取 {@code x/y/z/yaw/pitch} 位置节点；节点缺失、坐标缺失或非法时返回 null。
     *
     * @param kind 日志措辞用的点位名称（「Shop NPC」/「Waiting area」…），仅影响排障日志
     */
    private Location readLocation(ConfigurationSection section, World world, String path, String kind) {
        if (section == null) return null;
        // 必须用 isSet 判断：getDouble 对缺失的键返回 0.0，会把损坏的节点当成 (0, 0, 0) 生成 NPC
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            plugin.getLogger().warning(kind + " location is missing coordinates, ignored: " + path);
            return null;
        }
        double x = section.getDouble("x"), y = section.getDouble("y"), z = section.getDouble("z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            plugin.getLogger().warning(kind + " location has invalid coordinates, ignored: " + path);
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

        YamlFileGate gate = arenasGate();
        if (gate == null || !gate.writable()) {
            // 只读降级：内存值保持不动（不清空），如实记失败让调用方播报
            lastSaveFailed = true;
            return false;
        }

        // 生成点与核心位置写入 arenas.yml（按竞技场隔离）。
        // 本方法随后会用 arenasConfig 整份覆写文件，所以必须基于磁盘最新内容重新读取：
        // 复用字段里的旧副本回写，会把其它写入方在此期间落盘的内容整份回滚成旧值
        // （ArenaManager.updateGameParams / clearDeploymentData、其它竞技场的 saveSpawnPoints）。
        if (arenasFile == null) {
            arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        }
        YamlFiles.Loaded loaded = gate.load();
        // isReadOnly 一并判断：隔离后文件已不存在 → load() 返回 ABSENT（usable=true），
        // 若只看 usable 会拿空配置当底稿（后续 gate.write 仍会拒写，但没必要把空配置推给内存）
        if (!loaded.usable() || gate.isReadOnly()) {
            lastSaveFailed = true;
            plugin.getLogger().warning("Refusing to write arenas.yml (unusable)");
            return false;
        }
        arenasConfig = loaded.config();

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

        // 等待区（可选位置）：只在内存里有值时才回写。
        // 刻意不写「null 即删除」分支 —— 本方法与「恢复初始状态」共用同一条写盘路径，
        // 且 null 同时代表「未设置」与「节点损坏被忽略」两种成因，删除会误伤服主手写的配置。
        if (savedWaitingLocation != null) {
            writeLocation(arenasConfig, "arenas." + arenaId + ".waiting-location", savedWaitingLocation);
        }

        // 闸门内部已打 SEVERE（拒写 / IO 失败），这里只如实返回失败让调用方播报
        boolean saved = gate.write(arenasConfig);

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
        YamlFileGate gate = arenasGate();
        if (gate == null || !gate.writable()) {
            return false;
        }
        if (arenasFile == null) {
            arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        }
        // 每次写前重读盘（保留原有纪律）：arenasConfig 字段可能是较旧的副本，
        // 直接用它整份覆写会丢掉在此期间由其他管理器（如 ArenaManager）写入的内容。
        YamlFiles.Loaded loaded = gate.load();
        if (!loaded.usable() || gate.isReadOnly()) {
            plugin.getLogger().warning("Refusing to write arenas.yml (unusable)");
            return false;
        }
        YamlConfiguration yaml = loaded.config();
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

        if (!gate.write(yaml)) {
            plugin.getLogger().severe("Failed to save shop NPC location to arenas.yml (writes are blocked or the write failed)");
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
        plugin.getLogger().warning("Refusing to register " + kind + " from another world (expected world "
                + (arenaWorld != null ? arenaWorld.getName() : "not loaded")
                + ", got "
                + (location != null && location.getWorld() != null ? location.getWorld().getName() : "null")
                + " @ " + (location != null ? location.getBlockX() + "," + location.getBlockY()
                        + "," + location.getBlockZ() : "null") + ")");
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
            logRejectedLocation("player spawn point", location);
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

    public void addMobSpawnPoint(Location location) {
        // M-02 兜底：异世界坐标拒收 + 日志（不落盘）
        if (!isInArenaWorld(location)) {
            logRejectedLocation("mob spawn point", location);
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
            logRejectedLocation("villager location", location);
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

    /* ========== 等待区位置（可选；未设置时运行时回退到核心） ========== */

    /**
     * 本竞技场单独设置的等待区位置（{@code arenas.<id>.waiting-location}）。
     *
     * <p>未单独设置（或节点损坏被忽略）时返回 null —— 调用方不要把它当成「玩家落点为空」，
     * 运行时的真实落点由 {@link com.hilltopvillage.arena.ArenaDefinition#getWaitingLocation()}
     * 回退到核心给出。</p>
     *
     * @return 等待区位置的防御性拷贝；未设置返回 null
     */
    public Location getWaitingLocation() {
        return savedWaitingLocation != null ? savedWaitingLocation.clone() : null;
    }

    /**
     * 设置等待区位置并落盘到 {@code arenas.<id>.waiting-location}（写入 x/y/z/yaw/pitch）。
     *
     * <p>等待区是<b>可选</b>项：不设置时运行时回退到核心位置，因此本方法刻意不提供「清除」语义
     * （传 null 视为「不改动」，返回 false 且不写盘）。</p>
     *
     * @return true = 已落盘；false = 写盘失败或坐标为 null（已拒收，未改动磁盘）
     */
    public boolean setWaitingLocation(Location location) {
        if (location == null) return false;
        // M-02 兜底：异世界坐标拒收 + 日志（世界字段不会入盘，落盘后会被按竞技场世界重建）
        if (!isInArenaWorld(location)) {
            logRejectedLocation("waiting area", location);
            return false;
        }
        savedWaitingLocation = location.clone();
        // 先写盘再推送：写盘失败时内存里的实例已经改了反而更误导（重启后回旧值），
        // 因此推送只在 saveSpawnPoints() 成功后才做
        boolean saved = saveSpawnPoints();
        if (saved) {
            pushWaitingLocationToLiveInstance();
        }
        return saved;
    }

    /**
     * 把内存里的等待区推送给已存在的运行实例（内存生效，不碰磁盘）。
     *
     * <p>{@link com.hilltopvillage.arena.ArenaInstance#getLobbySpawn()} 直接读它自己的
     * {@code definition} 快照（不像 core / spawn-points 那样实时读 {@link GameManager}），
     * 因此写完盘后必须再推一次，否则「不重启服务器就改了等待区」不会对下一次
     * {@code /hilltop join} 生效。</p>
     *
     * <p>无实例（未启用 / 正在加载）时静默跳过：下次创建实例会直接从 arenas.yml 读到新值。</p>
     */
    private void pushWaitingLocationToLiveInstance() {
        if (plugin.getMultiArenaManager() == null) return;
        ArenaInstance live = plugin.getMultiArenaManager().getArena(gameManager.getArenaId());
        if (live != null) {
            live.applyWaitingLocation(savedWaitingLocation);
        }
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
}