package com.hilltopvillage.arena;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Loads and persists the canonical arena registry. */
public final class ArenaManager {

    /**
     * arenas.yml 当前 schema 版本。
     *
     * <p>与出厂模板 {@code src/main/resources/arenas.yml} 的 {@code schema-version: 2} 保持一致
     * （P2-35：原先代码写 1、模板写 2，任何未来打算「按 schema-version 做迁移」的人都会被误导）。
     * 目前没有按版本分层迁移的读取逻辑，仅在 {@link #load()} 里对「读到更高版本」打 warning。</p>
     */
    private static final int SCHEMA_VERSION = 2;
    private static final Pattern ARENA_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    /** 竞技场未配置 game.min-players 且读不到 GameConfig 时的兜底值（与 GameConfig 字段默认值一致） */
    private static final int DEFAULT_MIN_PLAYERS = 2;

    /**
     * 竞技场的「部署期」字段（相对 {@code arenas.<id>}），由 {@link #clearDeploymentData(String)} 清空。
     *
     * <p>刻意不包含 world / waiting-location / enabled / display-name —— 清空部署数据后
     * 竞技场仍要留在 registry 里（世界、启用状态、名称都不是「部署」的一部分）。</p>
     */
    private static final List<String> DEPLOYMENT_KEYS = List.of(
            "core", "spawn-points", "mob-spawn-points", "villagers", "shop-npc",
            "game.min-players", "game.max-players", "game.victory-waves", "game.wave-interval-seconds",
            "node-search-radius", "spawn-radius-min", "spawn-radius-max", "spawn-y-offset");

    /**
     * 「本竞技场的商店 NPC 位置已有定论」标记键（相对 {@code arenas.<id>}），由
     * {@code AdminDeployManager} 的旧数据迁移负责读写，本类只负责在重置时<b>重新写上</b>。
     *
     * <p>用途：区分「{@code shop-npc} 节点不存在」的两种成因——
     * 旧版本升上来还没迁移（应迁移），还是管理员重置 / 主动清除过（绝不能再迁移）。
     * 没有这个标记时，重置删掉 {@code shop-npc} 后，下次加载会从旧版全局
     * {@code spawnpoints.yml} 再迁移一次，把刚清掉的 NPC 原地复活。</p>
     *
     * <p><b>刻意不在 {@link #DEPLOYMENT_KEYS} 里</b>：重置删的是「位置」，不是「这份旧数据已经处理过」
     * 这个事实，因此 {@link #clearDeploymentData(String)} 删完部署字段后会把它重新写上。</p>
     */
    public static final String SHOP_NPC_MIGRATED_KEY = "shop-npc-migrated";

    private final JavaPlugin plugin;
    private final File arenasFile;
    private final ArenaRegistry registry = new ArenaRegistry();
    private YamlConfiguration configuration;

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for arenas.yml.");
        }
        this.arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        this.configuration = new YamlConfiguration();
        ensureFile();
    }

    /** Loads arenas, replacing the current in-memory registry. */
    public void load() {
        ensureFile();
        try {
            configuration = YamlConfiguration.loadConfiguration(arenasFile);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Could not load arenas.yml; keeping an empty registry.", exception);
            configuration = new YamlConfiguration();
        }

        // P2-35：schema-version 统一为与出厂模板一致的常量；读到更高版本时打 warning，
        // 提醒管理员该文件可能由更新版插件写过，当前版本可能不认识其中部分内容。
        int schemaVersion = configuration.getInt("schema-version", SCHEMA_VERSION);
        if (schemaVersion > SCHEMA_VERSION) {
            plugin.getLogger().warning("arenas.yml 的 schema-version 为 " + schemaVersion
                    + "，高于当前插件支持的 " + SCHEMA_VERSION + "；"
                    + "该文件可能由更新版本的插件写入，部分内容可能无法识别");
        }

        // Paper 默认只加载 level-name 指向的世界；竞技场/大厅世界文件夹存在但未加载时自动加载
        ensureWorldsLoaded();

        registry.clear();
        ConfigurationSection arenas = configuration.getConfigurationSection("arenas");
        if (arenas == null) {
            plugin.getLogger().warning("arenas.yml has no arenas section; no arenas loaded.");
        } else {
            // 一个世界只能对应一个竞技场：世界名 → 已占用它的竞技场 ID。
            // 两个竞技场共用一个世界会共享 MapRestoreManager 的回滚日志（日志按世界名索引），
            // 一方结束就会回滚整个世界，破坏另一方进行中的对局，因此重复世界的定义一律拒绝加载。
            Map<String, String> worldOwners = new LinkedHashMap<>();
            for (String id : arenas.getKeys(false)) {
                try {
                    ArenaDefinition definition = readDefinition(id, arenas.getConfigurationSection(id));
                    if (!validate(definition)) {
                        plugin.getLogger().warning("Skipping invalid arena definition: " + id);
                        continue;
                    }
                    String owner = worldOwners.get(normalizeWorldName(definition.getWorldName()));
                    if (owner != null) {
                        plugin.getLogger().warning("拒绝加载竞技场 " + id + "：世界 " + definition.getWorldName()
                                + " 已被竞技场 " + owner + " 占用（一个世界只能对应一个竞技场，"
                                + "共用世界会导致地图回滚互相破坏）");
                        continue;
                    }
                    registry.register(definition);
                    worldOwners.put(normalizeWorldName(definition.getWorldName()), id);
                } catch (Exception exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Skipping damaged arena definition: " + id, exception);
                }
            }
        }
        String defaultId = configuration.getString("default-arena");
        if (defaultId != null && registry.get(defaultId) != null) {
            registry.setDefaultArenaId(defaultId);
        } else if (defaultId != null) {
            plugin.getLogger().warning("default-arena does not reference a loaded arena: " + defaultId);
        }
    }

    /**
     * 自动加载 arenas.yml 中引用但尚未加载的世界。
     *
     * <p>Paper 启动时只加载 {@code server.properties} 的 {@code level-name} 世界，
     * 竞技场世界需要由插件主动加载（与 BedWars1058 的 RestoreAdapter 行为一致）。
     * 仅在磁盘上存在 level.dat 时才加载，避免误建空世界。</p>
     */
    private void ensureWorldsLoaded() {
        Set<String> worldNames = new LinkedHashSet<>();

        // 使用显式配置值（不回溯主世界），确保配置里写的世界会被尝试加载
        String lobbyWorld = getConfiguredLobbyWorldName();
        if (lobbyWorld != null) worldNames.add(lobbyWorld);

        ConfigurationSection arenas = configuration.getConfigurationSection("arenas");
        if (arenas != null) {
            for (String id : arenas.getKeys(false)) {
                String world = arenas.getString(id + ".world");
                if (world != null && !world.isBlank()) worldNames.add(world);
            }
        }

        for (String worldName : worldNames) {
            if (Bukkit.getWorld(worldName) != null) continue;

            File folder = new File(Bukkit.getWorldContainer(), worldName);
            if (!new File(folder, "level.dat").exists()) {
                plugin.getLogger().warning("跳过未加载的世界（服务器根目录下不存在该世界文件夹）: " + worldName);
                continue;
            }

            try {
                World world = Bukkit.createWorld(new WorldCreator(worldName));
                if (world != null) {
                    plugin.getLogger().info("已自动加载世界: " + worldName);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "自动加载世界失败: " + worldName, exception);
            }
        }
    }

    public void reload() {
        load();
    }

    public ArenaRegistry getRegistry() { return registry; }
    public ArenaDefinition getArena(String id) { return registry.get(id); }

    /* ========== settings 段（全局设置，不属于任何单个竞技场） ========== */

    /**
     * 插件启动时是否自动启用所有 {@code enabled: true} 的竞技场。
     *
     * <p>对应 arenas.yml 的 {@code settings.auto-start-arenas}；缺省 true，
     * 保证旧配置文件缺少该键时行为与之前一致（启动即自动启用）。</p>
     */
    public boolean isAutoStartArenas() {
        return configuration.getBoolean("settings.auto-start-arenas", true);
    }

    /**
     * 玩家退出服务器时是否自动离开竞技场。
     *
     * <p>对应 arenas.yml 的 {@code settings.auto-leave-on-quit}；缺省 true。
     * 为 false 时退服保留竞技场位置（走重连窗口），不归还物品、不移出竞技场。</p>
     */
    public boolean isAutoLeaveOnQuit() {
        return configuration.getBoolean("settings.auto-leave-on-quit", true);
    }

    /** 竞技场重置延迟（秒）。对应 {@code settings.arena-reset-delay}；缺省 5 秒 */
    public int getArenaResetDelaySeconds() {
        return Math.max(0, configuration.getInt("settings.arena-reset-delay", 5));
    }

    /** 游戏开始倒计时（秒）。对应 {@code settings.start-countdown}；缺省 10 秒 */
    public int getStartCountdownSeconds() {
        return Math.max(0, configuration.getInt("settings.start-countdown", 10));
    }

    /**
     * 大厅世界名。
     *
     * <p>优先使用 {@code lobby.world}；若该项缺失、为空，或指向的世界当前未加载，
     * 则回退到服务器主世界（即 {@code server.properties} 中 {@code level-name} 指向的世界），
     * 保证插件在未配置大厅时依然可用。</p>
     *
     * @return 大厅世界名；服务器没有任何世界时返回 null
     */
    public String getLobbyWorldName() {
        String name = configuration.getString("lobby.world");
        if (name != null && !name.isBlank() && Bukkit.getWorld(name) != null) {
            return name;
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return (name == null || name.isBlank()) ? null : name;
        }
        return Bukkit.getWorlds().get(0).getName();
    }

    /** arenas.yml 中显式配置的大厅世界名（不回溯，供世界加载阶段使用） */
    public String getConfiguredLobbyWorldName() {
        String name = configuration.getString("lobby.world");
        return (name == null || name.isBlank()) ? null : name;
    }

    /** 大厅出生点（未配置或世界未加载返回 null） */
    public Location getLobbySpawn() {
        String worldName = getLobbyWorldName();
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        ConfigurationSection spawn = configuration.getConfigurationSection("lobby.spawn");
        if (spawn == null) return world.getSpawnLocation();

        double x = spawn.getDouble("x", world.getSpawnLocation().getX());
        double y = spawn.getDouble("y", world.getSpawnLocation().getY());
        double z = spawn.getDouble("z", world.getSpawnLocation().getZ());
        float yaw = (float) spawn.getDouble("yaw", 0.0);
        float pitch = (float) spawn.getDouble("pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * 把执行者当前位置写为大厅出生点（fix4 反馈9）：{@code lobby.spawn} 的
     * x/y/z/yaw/pitch 五键。
     *
     * <p><b>遵守「重读盘 + 只改目标键」纪律</b>（与 {@link #clearDeploymentData}、
     * {@link #updateGameParams} 同款）：旧 save()（fix5 X6-④ 已删除，曾是「用固化旧值
     * 整份覆写」的死代码地雷），这里重新读盘 → 只写 {@code lobby.spawn.*} →
     * save。<b>不调用 {@link #load()}</b> 也无必要：本方法只改 lobby 段，
     * arena 定义不受影响（且大厅出生点每次 {@link #getLobbySpawn()} 都实时从
     * {@code configuration} 字段读——该字段指向磁盘加载的同一份 YamlConfiguration，
     * 写盘后需把内存副本同步补写相同键才会即时生效，见下方注释）。</p>
     *
     * @param location 管理员当前位置（调用方须已校验处于大厅世界）
     * @return true = 已写入磁盘；false = 写盘失败
     */
    public boolean updateLobbySpawn(Location location) {
        if (location == null || location.getWorld() == null) return false;

        YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile);
        disk.set("lobby.spawn.x", location.getX());
        disk.set("lobby.spawn.y", location.getY());
        disk.set("lobby.spawn.z", location.getZ());
        disk.set("lobby.spawn.yaw", (double) location.getYaw());
        disk.set("lobby.spawn.pitch", (double) location.getPitch());
        try {
            disk.save(arenasFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "写入大厅出生点失败", exception);
            return false;
        }

        // 同步内存副本（configuration 字段）：getLobbySpawn() 实时读该字段，
        // 只写磁盘不写内存的话，新出生点要等重启/重载才生效。
        // 这里逐键补写与磁盘相同的值，不重跑 load()（避免为一次坐标写入重建整个 registry）。
        configuration.set("lobby.spawn.x", location.getX());
        configuration.set("lobby.spawn.y", location.getY());
        configuration.set("lobby.spawn.z", location.getZ());
        configuration.set("lobby.spawn.yaw", (double) location.getYaw());
        configuration.set("lobby.spawn.pitch", (double) location.getPitch());

        plugin.getLogger().info("大厅出生点已更新: " + location.getWorld().getName()
                + " (" + String.format("%.1f", location.getX()) + ", "
                + String.format("%.1f", location.getY()) + ", "
                + String.format("%.1f", location.getZ()) + ")");
        return true;
    }

    public ArenaDefinition getDefaultArena() {
        String id = registry.getDefaultArenaId();
        return id == null ? null : registry.get(id);
    }

    /**
     * 按世界名查找竞技场定义。
     *
     * <p>{@link #load()} 已保证「一个世界只对应一个竞技场」（重复世界的定义会被拒绝加载），
     * 因此这里命中第一条即可视为唯一结果。</p>
     */
    public ArenaDefinition getArenaByWorld(String worldName) { return registry.getByWorldName(worldName); }

    /**
     * Returns true when all persisted fields satisfy the Phase 1 contract.
     *
     * <p>只校验单个定义自身的字段；「一个世界只对应一个竞技场」属于跨定义约束，
     * 需要看到整个注册表，因此在 {@link #load()} 中统一校验（重复世界的定义不注册）。</p>
     *
     * <p><b>core 缺失是合法的</b>（{@code core == null} 表示该竞技场尚未部署）：
     * 重置竞技场会清掉 core，此时竞技场必须继续留在 registry 里，才能在 GUI 中显示为
     * 「未部署」并重新部署。硬性要求 core 非空会让整座竞技场从 registry 消失。</p>
     */
    public boolean validate(ArenaDefinition definition) {
        if (definition == null || !ARENA_ID.matcher(definition.getId()).matches()
                || definition.getWorldName().isBlank()) return false;
        Location core = definition.getCore();
        if (core != null && !validLocation(core, definition.getWorldName())) return false;
        for (Location spawn : definition.getSpawnPoints()) {
            if (!validLocation(spawn, definition.getWorldName())) return false;
        }
        // 怪物刷怪点：缺键/空列表 = 未部署（合法的旧存档状态）；有值时逐个校验坐标合法性
        for (Location mobSpawn : definition.getMobSpawnPoints()) {
            if (!validLocation(mobSpawn, definition.getWorldName())) return false;
        }
        return validGameParams(definition.getMinPlayers(), definition.getMaxPlayers(),
                definition.getVictoryWaves(), definition.getWaveIntervalSeconds());
    }

    private ArenaDefinition readDefinition(String id, ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Arena node is not a section");
        String worldName = requiredString(section, "world");
        // core 段缺失 = 该竞技场尚未部署（重置会清掉它）；损坏时也只记日志并当作未部署，
        // 不再抛异常——抛异常会被 load() 吞掉，导致整座竞技场从 registry 里彻底消失
        Location core = readOptionalLocation(section.getConfigurationSection("core"), worldName, id + ".core");

        // ✅ 读取等待区位置（如果没有配置，默认使用 core）
        Location waitingLocation = core;  // 默认值
        if (section.isConfigurationSection("waiting-location")) {
            waitingLocation = readOptionalLocation(
                    section.getConfigurationSection("waiting-location"), worldName, id + ".waiting-location");
        }

        List<Location> spawns = new ArrayList<>();
        List<?> rawSpawns = section.getList("spawn-points");
        if (rawSpawns != null) {
            for (int index = 0; index < rawSpawns.size(); index++) {
                Object raw = rawSpawns.get(index);
                if (!(raw instanceof Map<?, ?> map)) {
                    plugin.getLogger().warning("跳过无效生成点 " + id + ".spawn-points." + index);
                    continue;
                }
                // 注意：不能用 getConfigurationSection("spawn-points.0")——Bukkit 不支持用路径索引 YAML 列表，
                // 必须直接从 getList 返回的 Map 中读取坐标
                spawns.add(readLocationFromMap(map, worldName, id + ".spawn-points." + index));
            }
        }
        // 怪物刷怪点（STORY-1.2 新增 schema）：与 spawn-points 同级。
        // 旧存档无此键 → 空列表（不抛异常）；开局时的兼容语义由 GameManager 决定（双用回退）
        List<Location> mobSpawns = new ArrayList<>();
        List<?> rawMobSpawns = section.getList("mob-spawn-points");
        if (rawMobSpawns != null) {
            for (int index = 0; index < rawMobSpawns.size(); index++) {
                Object raw = rawMobSpawns.get(index);
                if (!(raw instanceof Map<?, ?> map)) {
                    plugin.getLogger().warning("跳过无效怪物刷怪点 " + id + ".mob-spawn-points." + index);
                    continue;
                }
                mobSpawns.add(readLocationFromMap(map, worldName, id + ".mob-spawn-points." + index));
            }
        }
        ConfigurationSection game = section.getConfigurationSection("game");
        ArenaDefinition.Builder builder = ArenaDefinition.builder(id, worldName, waitingLocation, core)
                .displayName(section.getString("display-name"))
                .enabled(section.getBoolean("enabled", true)).spawnPoints(spawns)
                .mobSpawnPoints(mobSpawns)
                // 商店 NPC 位置按竞技场隔离存储（与 core / spawn-points 同级）
                .shopNpc(readOptionalLocation(
                        section.getConfigurationSection("shop-npc"), worldName, id + ".shop-npc"))
                .nodeSearchRadius(section.getInt("node-search-radius", 0))
                .spawnRadiusMin(section.getInt("spawn-radius-min", 0))
                .spawnRadiusMax(section.getInt("spawn-radius-max", 0))
                .spawnYOffset(section.getInt("spawn-y-offset", 0));
        if (game != null) {
            // 兜底值必须自洽：默认 max-players 不能小于实际生效的 min-players，
            // 否则 validate() 会以「max < min」判该竞技场无效、让它从 registry 消失
            // （重置清掉 game.* 后若还残留 game 段，走的就是这条兜底路径）。
            // 显式写在配置文件里的值不受影响，仍然原样使用。
            int minPlayers = game.getInt("min-players", defaultMinPlayers());
            builder.minPlayers(minPlayers)
                    .maxPlayers(game.getInt("max-players", Math.max(6, minPlayers)))
                    .victoryWaves(game.getInt("victory-waves", 20))
                    .waveIntervalSeconds(game.getInt("wave-interval-seconds", 30));
        }
        return builder.build();
    }

    /**
     * 竞技场未显式配置 {@code game.min-players} 时继承的全局值（game-settings.yml 的 min-players）。
     *
     * <p>显式配置过的竞技场仍以 arenas.yml 为准——例如把 arena1 配成 {@code 1} 表示允许单人开局，
     * 这是有意的 per-arena 配置。只有「整个 min-players 键都不存在」时才继承全局值，
     * 避免硬编码默认值 2 与 game-settings.yml 不一致，导致竞技场按 A 值开局、GameManager 按 B 值校验。</p>
     *
     * <p>本类其余功能只依赖 {@link JavaPlugin} 的通用 API（getDataFolder / getLogger / saveResource），
     * 仅此处为读取 GameConfig 做一次向下转型；拿不到具体插件实例时退回 GameConfig 的字段默认值。</p>
     */
    private int defaultMinPlayers() {
        if (!(plugin instanceof HilltopVillagePlugin hilltop)) return DEFAULT_MIN_PLAYERS;
        return hilltop.getConfigManager().getActiveConfig().getMinPlayers();
    }

    /**
     * 从 YAML 列表项（Map）读取坐标。
     * Bukkit 的 {@code getConfigurationSection("list.0")} 无法索引列表，因此必须走此路径。
     */
    private Location readLocationFromMap(Map<?, ?> map, String worldName, String path) {
        double x = mapDouble(map, "x", path);
        double y = mapDouble(map, "y", path);
        double z = mapDouble(map, "z", path);
        float yaw = (float) mapDoubleOrDefault(map, "yaw", 0.0);
        float pitch = (float) mapDoubleOrDefault(map, "pitch", 0.0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid rotation at " + path);
        }
        World world = Bukkit.getWorld(worldName);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static double mapDouble(Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Missing or invalid " + key + " at " + path);
        }
        return number.doubleValue();
    }

    private static double mapDoubleOrDefault(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        return (value instanceof Number number && Double.isFinite(number.doubleValue()))
                ? number.doubleValue() : fallback;
    }

    private Location readLocation(ConfigurationSection section, String worldName, String path) {
        if (section == null) throw new IllegalArgumentException("Missing coordinates at " + path);
        double x = requiredDouble(section, "x", path), y = requiredDouble(section, "y", path);
        double z = requiredDouble(section, "z", path);
        float yaw = (float) section.getDouble("yaw", 0.0), pitch = (float) section.getDouble("pitch", 0.0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid rotation");
        World world = Bukkit.getWorld(worldName);
        if (world == null) plugin.getLogger().warning("World is not loaded for arena location " + path + ": " + worldName);
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * 读取可选的坐标节点：节点缺失返回 null，内容损坏时记一条 warning 并返回 null。
     *
     * <p>core / shop-npc 这类位置本来就允许缺失（null = 尚未部署）。若照旧抛异常，
     * 异常会被 {@link #load()} 的 catch 吞掉，整座竞技场从 registry 消失——
     * GUI 里看不到、也无法再次部署，正是重置流程要避免的结果。</p>
     *
     * @return 位置；节点不存在或损坏时返回 null
     */
    private Location readOptionalLocation(ConfigurationSection section, String worldName, String path) {
        if (section == null) return null;
        try {
            return readLocation(section, worldName, path);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("忽略损坏的位置节点 " + path + "：" + exception.getMessage());
            return null;
        }
    }

    /**
     * 清空某个竞技场的全部部署数据（管理员在竞技场管理 GUI 上执行「重置竞技场」）。
     *
     * <p>删除 {@link #DEPLOYMENT_KEYS} 列出的字段（spawn-points / villagers / core / shop-npc /
     * game.* / 半径与偏移），<b>保留</b> world / waiting-location / enabled / display-name，
     * 使竞技场仍留在 registry 中并显示为「未部署」，可以重新走一次部署流程。</p>
     *
     * <p><b>必须基于磁盘最新内容操作</b>：旧 save()（fix5 X6-④ 已删除，曾是「用 registry
     * 里的旧值整份覆写」）会把刚删掉的 spawn-points / core 原样写回去（撤销本次清理）。
     * 因此这里重新读盘 → 只删目标键 → save → {@link #load()} 重建 registry。</p>
     *
     * @param arenaId 竞技场 ID
     * @return true = 已写入磁盘并重新加载；false = 竞技场不存在或写入失败
     */
    public boolean clearDeploymentData(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return false;

        YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile);
        // 竞技场 ID 在 registry 里是不区分大小写的（ArenaRegistry 会归一化），
        // 这里也按同样口径匹配 YAML 键，避免大小写不一致导致「重置」静默失败
        String key = findArenaKey(disk, arenaId);
        if (key == null) {
            plugin.getLogger().warning("清空部署数据失败：arenas.yml 中不存在竞技场 " + arenaId);
            return false;
        }
        String path = "arenas." + key;

        for (String field : DEPLOYMENT_KEYS) {
            String fullPath = path + "." + field;
            // isSet 先行判断：对不存在的键调用 set(path, null) 会顺手创建空的父节点
            if (disk.isSet(fullPath)) disk.set(fullPath, null);
        }

        // game 的 4 个键都删掉后只剩一个空节点：留着会让 readDefinition 走「有 game 段」分支、
        // 用全局 min-players 兜底（可能大于默认 max-players，反而让竞技场被判无效而从 GUI 消失）。
        // 删掉空节点，使该竞技场回到与「从未部署」完全一致的状态。
        ConfigurationSection game = disk.getConfigurationSection(path + ".game");
        if (game != null && game.getKeys(false).isEmpty()) disk.set(path + ".game", null);

        // 标记「本竞技场的商店 NPC 位置已有定论」：否则下次加载时 AdminDeployManager 会认为
        // shop-npc 只是还没迁移，从旧版 spawnpoints.yml 把刚被清掉的位置再迁移回来
        disk.set(path + "." + SHOP_NPC_MIGRATED_KEY, true);

        try {
            disk.save(arenasFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "清空竞技场部署数据失败: " + arenaId, exception);
            return false;
        }

        // 重建 registry：内存中的旧定义还带着刚被删掉的 core / spawn-points
        load();
        plugin.getLogger().info("已清空竞技场部署数据: " + arenaId);
        return true;
    }

    /**
     * 写入某个竞技场的 4 个游戏参数（{@code arenas.<id>.game.*}），供竞技场管理 GUI 的参数编辑页使用。
     *
     * <p><b>必须基于磁盘最新内容操作</b>，理由同 {@link #clearDeploymentData(String)}：
     * 旧 save()（fix5 X6-④ 已删除）会用 registry 里的旧值整份覆写，把 {@code AdminDeployManager} 刚写进去的
     * 生成点/村民一并改回旧值。因此这里重新读盘 → 只写目标键 → save → {@link #load()}。</p>
     *
     * <p><b>刻意不调用旧 save()</b>：它是死代码地雷（会用固化的旧值覆写 core / spawn-points），已在 fix5 X6-④ 删除。</p>
     *
     * <p>注意本方法只负责磁盘与 registry；已存在的 {@code ArenaInstance} 持有自己的定义副本，
     * 需要调用方另外通过 {@code ArenaInstance#applyGameParams} 推送（见
     * {@code ArenaAdminService#updateGameParams}）。</p>
     *
     * @return true = 已写入磁盘并重新加载；false = 参数非法、竞技场不存在或写盘失败
     */
    public boolean updateGameParams(String arenaId, int minPlayers, int maxPlayers,
                                    int victoryWaves, int waveIntervalSeconds) {
        if (arenaId == null || arenaId.isBlank()) return false;
        if (!validGameParams(minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds)) {
            plugin.getLogger().warning("拒绝写入非法的竞技场游戏参数: " + arenaId
                    + "（人数 " + minPlayers + "-" + maxPlayers + "，波数 " + victoryWaves
                    + "，间隔 " + waveIntervalSeconds + "）");
            return false;
        }

        YamlConfiguration disk = YamlConfiguration.loadConfiguration(arenasFile);
        String key = findArenaKey(disk, arenaId);
        if (key == null) {
            plugin.getLogger().warning("写入游戏参数失败：arenas.yml 中不存在竞技场 " + arenaId);
            return false;
        }

        String path = "arenas." + key + ".game";
        disk.set(path + ".min-players", minPlayers);
        disk.set(path + ".max-players", maxPlayers);
        disk.set(path + ".victory-waves", victoryWaves);
        disk.set(path + ".wave-interval-seconds", waveIntervalSeconds);

        try {
            disk.save(arenasFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "写入竞技场游戏参数失败: " + arenaId, exception);
            return false;
        }

        // 重建 registry：内存中的旧定义还带着改之前的 4 个数值
        load();
        plugin.getLogger().info("已更新竞技场游戏参数: " + arenaId
                + "（人数 " + minPlayers + "-" + maxPlayers + "，波数 " + victoryWaves
                + "，间隔 " + waveIntervalSeconds + "s）");
        return true;
    }

    /**
     * 游戏参数合法性判定，与 {@link #validate(ArenaDefinition)} 逐条一致。
     *
     * <p>两处共用同一个方法，避免「GUI 允许写入的值却过不了加载校验」——那会让竞技场
     * 在下次 {@link #load()} 时被判无效、直接从 registry 与 GUI 列表里消失。</p>
     */
    public static boolean validGameParams(int minPlayers, int maxPlayers,
                                          int victoryWaves, int waveIntervalSeconds) {
        return minPlayers > 0
                && maxPlayers >= minPlayers
                && victoryWaves > 0
                && waveIntervalSeconds > 0;
    }

    /** 在 arenas.yml 中按 ID（忽略大小写）查找竞技场节点键；不存在返回 null */
    private static String findArenaKey(YamlConfiguration yaml, String arenaId) {
        ConfigurationSection arenas = yaml.getConfigurationSection("arenas");
        if (arenas == null) return null;
        for (String candidate : arenas.getKeys(false)) {
            if (candidate.equalsIgnoreCase(arenaId)) return candidate;
        }
        return null;
    }

    private void ensureFile() {
        if (!arenasFile.exists()) {
            plugin.saveResource("arenas.yml", false);
            if (!arenasFile.exists()) {
                try { arenasFile.createNewFile(); } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not create arenas.yml.", exception);
                }
            }
        }
    }

    private static String requiredString(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static double requiredDouble(ConfigurationSection section, String key, String path) {
        if (!section.isSet(key)) throw new IllegalArgumentException("Missing coordinate " + path + "." + key);
        double value = section.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid coordinate " + path + "." + key);
        return value;
    }
    private static boolean validLocation(Location location, String worldName) {
        return location != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ()) && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch())
                && (location.getWorld() == null || worldName.equals(location.getWorld().getName()));
    }

    /** 世界名归一化键：Bukkit 的世界查找不区分大小写，唯一性校验也必须忽略大小写。 */
    private static String normalizeWorldName(String worldName) {
        return worldName == null ? "" : worldName.toLowerCase(Locale.ROOT);
    }
}
