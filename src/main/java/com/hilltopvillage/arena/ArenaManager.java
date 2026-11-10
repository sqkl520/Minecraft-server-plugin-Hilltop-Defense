package com.hilltopvillage.arena;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;
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

    /** 出厂模板 {@code lobby.spawn} 的占位坐标（与 {@code src/main/resources/arenas.yml} 一致）。 */
    private static final double FACTORY_LOBBY_SPAWN_X = 0.5;
    private static final double FACTORY_LOBBY_SPAWN_Y = 100.0;
    private static final double FACTORY_LOBBY_SPAWN_Z = 0.5;
    /** 坐标比较容差：YAML 里 100 与 100.0 读出来是同一个 double，这里只为吃掉浮点误差。 */
    private static final double LOBBY_COORD_EPSILON = 1.0e-6;

    /**
     * 竞技场的「部署期」字段（相对 {@code arenas.<id>}），由 {@link #clearDeploymentData(String)} 清空。
     *
     * <p>刻意不包含 world / waiting-location / enabled / display-name —— 清空部署数据后
     * 竞技场仍要留在 registry 里（世界、启用状态、名称都不是「部署」的一部分）。</p>
     *
     * <p><b>同样刻意不包含这四个刷怪调参：</b>{@code spawn-radius-min} / {@code spawn-radius-max}
     * （arenas-yml-dc-002）、{@code node-search-radius} / {@code spawn-y-offset}（arenas-yml-dc-001）。
     * 它们<b>没有任何运行期写入方</b>——部署向导（{@code AdminDeployManager#saveSpawnPoints} 与
     * {@code saveShopNpcLocation}）只写 spawn-points / mob-spawn-points / villagers / core /
     * waiting-location / shop-npc，参数编辑页（{@link #updateGameParams}）只写 {@code game.*}，
     * {@code ArenaAdminService} 只增删点列表与 shop-npc，{@code ConfigManager} 写的是全局
     * {@code game-settings.yml} 的 {@code spawning.*} / {@code nodes.*}。<b>唯一</b>的设置途径是
     * 手工编辑 arenas.yml。</p>
     *
     * <p>本列表的正当性来自 {@link #clearDeploymentData(String)} 的承诺「重置后可重新走一次部署流程」：
     * 被删的键必须<b>能被向导重建</b>。上面那条清单一律满足（core / spawn-points / villagers / shop-npc
     * 由向导逐个写入，{@code game.*} 由参数编辑页重建）。这四个键恰恰相反，它们是与地图几何绑定的
     * 刷怪调参：出厂模板给每座竞技场预设 {@code spawn-radius-min/max = 1/3}、
     * {@code node-search-radius = 50/50/60}、{@code spawn-y-offset = 0}，而全局兜底分别是
     * {@code spawning.spawn-radius-min/max = 20/40}、{@code nodes.search-radius = 128}、
     * {@code spawning.spawn-y-offset = 0}。模板注释写明「大半径需要配合开阔地形——越界点会落进
     * 虚空/水面，被安全落点检测丢弃后重试」，即小半径是作者在天空岛地图上实测调出来的结论，
     * 不是可推导的默认值。</p>
     *
     * <p>重置删掉它们后重新部署<b>不会</b>写回，该竞技场会永久静默回落到全局值（刷怪半径 20/40、
     * 节点搜索 128），全程无日志：按小半径调优的地图上有效刷怪点会大幅塌缩，节点搜索半径则由
     * 50/60 膨胀到 128。{@code spawn-y-offset} 是其中唯一「出厂值恰好等于全局值」（都是 0）的键，
     * 重置它在出厂配置下暂时看不出差异——但一旦管理员把它调成非 0，重置同样会让该项调参不可恢复，
     * 因此按同一条规则一并移出。</p>
     */
    private static final List<String> DEPLOYMENT_KEYS = List.of(
            "core", "spawn-points", "mob-spawn-points", "villagers", "shop-npc",
            "game.min-players", "game.max-players", "game.victory-waves", "game.wave-interval-seconds");

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
    /**
     * arenas.yml 的共享读写闸门。
     *
     * <p>{@code AdminDeployManager} 与 {@code ArenaAdminService} 也写这份文件，必须复用同一个实例
     * （经 {@link #getArenasGate()}），否则「A 判只读、B 照写」会让只读降级形同虚设。</p>
     */
    private final YamlFileGate arenasGate;

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for arenas.yml.");
        }
        this.arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        this.configuration = new YamlConfiguration();
        this.arenasGate = new YamlFileGate(arenasFile, YamlFiles.arenasValidator(), plugin.getLogger());
        ensureFile();
    }

    /**
     * arenas.yml 的共享闸门——{@code AdminDeployManager} / {@code ArenaAdminService} 必须复用，
     * 禁止各建一份（各持一份会让只读降级失效）。
     */
    public YamlFileGate getArenasGate() {
        return arenasGate;
    }

    /** Loads arenas, replacing the current in-memory registry. */
    public void load() {
        ensureFile();
        YamlFiles.Loaded loaded = arenasGate.load();
        // 闸门已锁只读时，即使文件此刻不存在（隔离后被改名 *.corrupt）也按不可用处理：
        // YamlFiles.load 对「不存在」返回 ABSENT + 空配置（usable），照走会用空配置重建 registry。
        if (!loaded.usable() || arenasGate.isReadOnly()) {
            plugin.getLogger().severe("arenas.yml is unusable; 0 arenas loaded this run and arenas.yml "
                    + "will NOT be written (see the log line above for the reason)");
            registry.clear();
            return;
        }
        configuration = loaded.config();

        // P2-35：schema-version 统一为与出厂模板一致的常量；读到更高版本时打 warning，
        // 提醒管理员该文件可能由更新版插件写过，当前版本可能不认识其中部分内容。
        int schemaVersion = configuration.getInt("schema-version", SCHEMA_VERSION);
        if (schemaVersion > SCHEMA_VERSION) {
            plugin.getLogger().warning("arenas.yml schema-version is " + schemaVersion
                    + ", newer than the " + SCHEMA_VERSION
                    + " supported by this build;"
                    + " the file may have been written by a newer version and some entries may not be recognized");
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
                    String rejection = validationFailure(definition);
                    if (rejection != null) {
                        // fix-round1 pattern-2：拒绝原因必须可见——这是唯一的加载闸门，静默 continue
                        // 会让竞技场从 /hilltop list 与 GUI 里凭空消失，而 ArenaResetService 只会报
                        // ARENA_NOT_FOUND，管理员没有任何线索可循。
                        plugin.getLogger().warning("Skipping invalid arena definition '" + id + "': " + rejection
                                + " (fix arenas.yml -> arenas." + id + " and run /hilltop reloadarena,"
                                + " otherwise this arena is unavailable)"
                                + (definition == null ? "" : " [world '" + definition.getWorldName() + "']"));
                        continue;
                    }
                    String owner = worldOwners.get(normalizeWorldName(definition.getWorldName()));
                    if (owner != null) {
                        plugin.getLogger().warning("Refusing to load arena " + id + ": world " + definition.getWorldName()
                                + " is already owned by arena " + owner + " (one world can only back one arena;"
                                + " sharing a world would make the two map rollbacks corrupt each other)");
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
        // slice-05-logic-004：空白值按「未配置」处理。registry.get("   ") 会走到 normalizeId 抛
        // IllegalArgumentException，而该语句不在上面的 per-arena try/catch 内，异常会冒出 load()，
        // 把一个「只是多打了几个空格」的配置变成整次加载失败。读路径在这里与写路径对齐：
        // ArenaRegistry.setDefaultArenaId 同样把空白视为「清空默认值」。
        if (defaultId != null && defaultId.isBlank()) defaultId = null;
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
                plugin.getLogger().warning("Skipping unloaded world (no such world folder under the server root): " + worldName);
                continue;
            }

            try {
                World world = Bukkit.createWorld(new WorldCreator(worldName));
                if (world != null) {
                    plugin.getLogger().info("World loaded automatically: " + worldName);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to load world automatically: " + worldName, exception);
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

    /**
     * {@code lobby.spawn} 是否仍是出厂模板里的占位值（{@code 0.5 / 100.0 / 0.5}）。
     *
     * <p>出厂模板给的是<b>主世界坐标</b>的占位值：服主如果没改过，玩家就会落在一个
     * 大概率悬空、且与服主大厅世界无关的位置。因此「等于占位值」视为「未自定义」，
     * 仅供部署向导的提醒使用（只读判定，不写盘、不阻断部署）。</p>
     *
     * <p>缺 {@code lobby.spawn} 段时<b>不算</b>占位值：那种情况下 {@link #getLobbySpawn()} 会回退到
     * 大厅世界自己的出生点，是安全且有意义的落点，不需要提醒服主。</p>
     *
     * @return true = 大厅出生点仍是出厂占位值，需要提醒服主设置
     */
    public boolean isLobbySpawnFactoryDefault() {
        ConfigurationSection spawn = configuration.getConfigurationSection("lobby.spawn");
        if (spawn == null) return false;
        return isFactoryDefaultSpawn(
                spawn.getDouble("x", FACTORY_LOBBY_SPAWN_X),
                spawn.getDouble("y", FACTORY_LOBBY_SPAWN_Y),
                spawn.getDouble("z", FACTORY_LOBBY_SPAWN_Z));
    }

    /**
     * 「大厅出生点是否仍是出厂占位值」的纯函数部分（无 Bukkit 依赖，可直接单测）。
     *
     * @param x 出生点 X
     * @param y 出生点 Y
     * @param z 出生点 Z
     * @return true = 与出厂模板的 {@code 0.5 / 100.0 / 0.5} 完全重合
     */
    static boolean isFactoryDefaultSpawn(double x, double y, double z) {
        return Math.abs(x - FACTORY_LOBBY_SPAWN_X) < LOBBY_COORD_EPSILON
                && Math.abs(y - FACTORY_LOBBY_SPAWN_Y) < LOBBY_COORD_EPSILON
                && Math.abs(z - FACTORY_LOBBY_SPAWN_Z) < LOBBY_COORD_EPSILON;
    }

    /**
     * {@code lobby.world} 是否<b>显式配置且可用</b>（即「大厅世界已定」）。
     *
     * <p>与 {@link #getLobbyWorldName()} 的三级回退口径刻意不同：后者会在配置缺失/世界不存在时
     * 静默回退到主世界（设计如此，保证开箱可用），但正是这个回退让「服主忘了配大厅」变成
     * 不可见的失败。本方法只看显式配置，供部署向导提醒与 {@code setspawn} 降级路径判定使用。</p>
     *
     * @return true = {@code lobby.world} 为空，或它指向的世界当前不存在
     */
    public boolean isConfiguredLobbyWorldMissing() {
        String name = getConfiguredLobbyWorldName();
        return name == null || Bukkit.getWorld(name) == null;
    }

    /**
     * 大厅出生点（未配置或世界未加载返回 null） */
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

        YamlFiles.Loaded loaded = arenasGate.load();
        if (!loaded.usable() || !arenasGate.writable()) {
            plugin.getLogger().warning("Refusing to write arenas.yml (unusable)");
            return false;
        }
        YamlConfiguration disk = loaded.config();
        disk.set("lobby.spawn.x", location.getX());
        disk.set("lobby.spawn.y", location.getY());
        disk.set("lobby.spawn.z", location.getZ());
        disk.set("lobby.spawn.yaw", (double) location.getYaw());
        disk.set("lobby.spawn.pitch", (double) location.getPitch());
        if (!arenasGate.write(disk)) {
            // 闸门内部已打 SEVERE（拒写 / IO 失败），此处只如实返回失败让调用方播报
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

        plugin.getLogger().info("Lobby spawn updated: " + location.getWorld().getName()
                + " (" + String.format("%.1f", location.getX()) + ", "
                + String.format("%.1f", location.getY()) + ", "
                + String.format("%.1f", location.getZ()) + ")");
        return true;
    }

    /**
     * 降级路径：{@code lobby.world} 未配置 / 指向的世界不存在时，允许管理员在<b>任意世界</b>
     * 执行 {@code /hilltop lobby setspawn} —— 同时把 {@code lobby.world} 写为当前位置所在世界。
     *
     * <p><b>为什么需要：</b>{@link #updateLobbySpawn(Location)} 的正常路径要求「人已经在
     * {@code lobby.world} 指的那个世界里」，但 {@code lobby.world} 指向不存在的世界时，
     * 那个世界永远进不去 → 管理员永远无法设置大厅出生点，只能手改 YAML。
     * 本方法把「定世界」与「定坐标」一次写完，打破这个死局。</p>
     *
     * <p><b>纪律与 {@link #updateLobbySpawn(Location)} 完全一致：</b>重读盘 → 只写 lobby 段的目标键
     * （{@code lobby.world} + {@code lobby.spawn.*}）→ 闸门写盘 → 同步内存副本。
     * 写盘失败时内存副本同样不更新，避免「磁盘没写进去、本进程却按新值跑」的分叉状态。</p>
     *
     * @param location 管理员当前位置（调用方已确认 {@code lobby.world} 不可用）
     * @return true = 已写入磁盘与内存；false = 写盘失败
     */
    public boolean updateLobbySpawnAndWorld(Location location) {
        if (location == null || location.getWorld() == null) return false;
        String worldName = location.getWorld().getName();

        YamlFiles.Loaded loaded = arenasGate.load();
        if (!loaded.usable() || !arenasGate.writable()) {
            plugin.getLogger().warning("Refusing to write arenas.yml (unusable)");
            return false;
        }
        YamlConfiguration disk = loaded.config();
        disk.set("lobby.world", worldName);
        disk.set("lobby.spawn.x", location.getX());
        disk.set("lobby.spawn.y", location.getY());
        disk.set("lobby.spawn.z", location.getZ());
        disk.set("lobby.spawn.yaw", (double) location.getYaw());
        disk.set("lobby.spawn.pitch", (double) location.getPitch());
        if (!arenasGate.write(disk)) {
            return false;
        }

        // 写盘成功后才同步内存副本（getLobbySpawn() / getConfiguredLobbyWorldName() 都读它）
        configuration.set("lobby.world", worldName);
        configuration.set("lobby.spawn.x", location.getX());
        configuration.set("lobby.spawn.y", location.getY());
        configuration.set("lobby.spawn.z", location.getZ());
        configuration.set("lobby.spawn.yaw", (double) location.getYaw());
        configuration.set("lobby.spawn.pitch", (double) location.getPitch());

        plugin.getLogger().info("Lobby spawn updated and lobby world set to " + worldName + " ("
                + String.format("%.1f", location.getX()) + ", "
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
        return validationFailure(definition) == null;
    }

    /**
     * 返回该定义通不过 {@link #validate(ArenaDefinition)} 的**具体原因**；通过时返回 {@code null}。
     *
     * <p>fix-round1 pattern-2：{@link #load()} 是唯一的加载闸门，任一字段不合法就 {@code continue}
     * ——定义既不注册、也不抛异常，旧实现只打一行 "Skipping invalid arena definition: &lt;id&gt;"，
     * 管理员看不出是「坐标 NaN」「世界名大小写不符」还是「max-players &lt; min-players」，
     * 而 {@code ArenaResetService.canReset} 只会报 {@code ARENA_NOT_FOUND}，连重置自救都做不到。
     * 把判定逻辑收敛到本方法并返回原因，{@link #validate(ArenaDefinition)} 继续保留布尔语义，
     * 判定标准一字未改，只补上可观测性。</p>
     */
    public String validationFailure(ArenaDefinition definition) {
        if (definition == null) return "definition is null";
        if (!ARENA_ID.matcher(definition.getId()).matches()) {
            return "id '" + definition.getId() + "' does not match " + ARENA_ID.pattern()
                    + " (lowercase letters, digits, '-' and '_' only)";
        }
        if (definition.getWorldName().isBlank()) return "world name is blank";
        // core 缺失是合法的（core == null 表示尚未部署），因此只在存在时校验
        Location core = definition.getCore();
        if (core != null) {
            String reason = invalidLocation(core, definition.getWorldName(), "core");
            if (reason != null) return reason;
        }
        for (Location spawn : definition.getSpawnPoints()) {
            String reason = invalidLocation(spawn, definition.getWorldName(), "spawn-points");
            if (reason != null) return reason;
        }
        // 怪物刷怪点：缺键/空列表 = 未部署（合法的旧存档状态）；有值时逐个校验坐标合法性
        for (Location mobSpawn : definition.getMobSpawnPoints()) {
            String reason = invalidLocation(mobSpawn, definition.getWorldName(), "mob-spawn-points");
            if (reason != null) return reason;
        }
        if (!validGameParams(definition.getMinPlayers(), definition.getMaxPlayers(),
                definition.getVictoryWaves(), definition.getWaveIntervalSeconds())) {
            return "game params invalid (min-players " + definition.getMinPlayers()
                    + ", max-players " + definition.getMaxPlayers()
                    + ", victory-waves " + definition.getVictoryWaves()
                    + ", wave-interval-seconds " + definition.getWaveIntervalSeconds()
                    + "; require min > 0, max >= min, victory-waves > 0, interval > 0)";
        }
        return null;
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
                    plugin.getLogger().warning("Skipping invalid spawn point " + id + ".spawn-points." + index);
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
                    plugin.getLogger().warning("Skipping invalid mob spawn point " + id + ".mob-spawn-points." + index);
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
            plugin.getLogger().warning("Ignoring damaged location entry " + path + ": " + exception.getMessage());
            return null;
        }
    }

    /**
     * 清空某个竞技场的全部部署数据（管理员在竞技场管理 GUI 上执行「重置竞技场」）。
     *
     * <p>删除 {@link #DEPLOYMENT_KEYS} 列出的字段（core / spawn-points / mob-spawn-points / villagers /
     * shop-npc / game.*），<b>保留</b> world / waiting-location / enabled / display-name 以及四个刷怪调参
     * {@code spawn-radius-min} / {@code spawn-radius-max} / {@code node-search-radius} /
     * {@code spawn-y-offset}，使竞技场仍留在 registry 中并显示为「未部署」，可以重新走一次部署流程。</p>
     *
     * <p>保留那四个键是因为它们属刷怪调参而非部署产物（无任何运行期写入方，只能手改），
     * 删掉后重新部署不会写回，竞技场会永久静默回落到全局值，理由与证据见
     * {@link #DEPLOYMENT_KEYS} 的 javadoc。</p>
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

        YamlFiles.Loaded loaded = arenasGate.load();
        if (!loaded.usable() || !arenasGate.writable()) {
            plugin.getLogger().warning("Refusing to clear arena deployment data (arenas.yml is unusable): " + arenaId);
            return false;
        }
        YamlConfiguration disk = loaded.config();
        // 竞技场 ID 在 registry 里是不区分大小写的（ArenaRegistry 会归一化），
        // 这里也按同样口径匹配 YAML 键，避免大小写不一致导致「重置」静默失败
        String key = findArenaKey(disk, arenaId);
        if (key == null) {
            plugin.getLogger().warning("Failed to clear deployment data: arenas.yml has no arena named " + arenaId);
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

        if (!arenasGate.write(disk)) {
            plugin.getLogger().severe("Failed to clear arena deployment data: " + arenaId);
            return false;
        }

        // 重建 registry：内存中的旧定义还带着刚被删掉的 core / spawn-points
        load();
        plugin.getLogger().info("Cleared arena deployment data: " + arenaId);
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
            plugin.getLogger().warning("Refusing to write invalid arena game settings for " + arenaId
                    + " (players " + minPlayers + "-" + maxPlayers + ", waves " + victoryWaves
                    + ", interval " + waveIntervalSeconds + "s)");
            return false;
        }

        YamlFiles.Loaded loaded = arenasGate.load();
        if (!loaded.usable() || !arenasGate.writable()) {
            plugin.getLogger().warning("Refusing to write game settings (arenas.yml is unusable): " + arenaId);
            return false;
        }
        YamlConfiguration disk = loaded.config();
        String key = findArenaKey(disk, arenaId);
        if (key == null) {
            plugin.getLogger().warning("Failed to write game settings: arenas.yml has no arena named " + arenaId);
            return false;
        }

        String path = "arenas." + key + ".game";
        disk.set(path + ".min-players", minPlayers);
        disk.set(path + ".max-players", maxPlayers);
        disk.set(path + ".victory-waves", victoryWaves);
        disk.set(path + ".wave-interval-seconds", waveIntervalSeconds);

        if (!arenasGate.write(disk)) {
            plugin.getLogger().severe("Failed to write arena game settings: " + arenaId);
            return false;
        }

        // 重建 registry：内存中的旧定义还带着改之前的 4 个数值
        load();
        plugin.getLogger().info("Updated arena game settings: " + arenaId
                + " (players " + minPlayers + "-" + maxPlayers + ", waves " + victoryWaves
                + ", interval " + waveIntervalSeconds + "s)");
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
        // 只读降级中**不得**从 JAR 模板重建：读盘不可用时文件已被隔离成 *.corrupt，
        // 若这里再 saveResource 一份出厂模板，下一次 gate.load() 就会读到「合法的新文件」
        // 并自动解封——降级被自己的重建动作绕过（正是 spec D1.5-③ 第 4 步要拦的路径）。
        if (arenasGate != null && arenasGate.isReadOnly()) return;
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
                && (location.getWorld() == null
                        || worldName.equalsIgnoreCase(location.getWorld().getName()));
    }

    /**
     * {@link #validLocation(Location, String)} 的可解释版本：通过时返回 {@code null}，
     * 否则返回一句「是哪个位置、哪一条不满足」，供 {@link #validationFailure(ArenaDefinition)} 汇总。
     * 判定条件与 {@code validLocation} 逐条一致（保持两个入口同口径）。
     */
    private static String invalidLocation(Location location, String worldName, String what) {
        if (location == null) return what + " is null";
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())) {
            return what + " has a non-finite coordinate (" + location.getX() + ", "
                    + location.getY() + ", " + location.getZ() + ")";
        }
        if (!Float.isFinite(location.getYaw()) || !Float.isFinite(location.getPitch())) {
            return what + " has a non-finite rotation (yaw " + location.getYaw()
                    + ", pitch " + location.getPitch() + ")";
        }
        if (location.getWorld() != null && !worldName.equalsIgnoreCase(location.getWorld().getName())) {
            return what + " is in world '" + location.getWorld().getName()
                    + "' but the arena declares world '" + worldName + "'";
        }
        return null;
    }

    /** 世界名归一化键：Bukkit 的世界查找不区分大小写，唯一性校验也必须忽略大小写。 */
    private static String normalizeWorldName(String worldName) {
        return worldName == null ? "" : worldName.toLowerCase(Locale.ROOT);
    }
}
