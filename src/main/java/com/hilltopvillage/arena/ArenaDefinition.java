package com.hilltopvillage.arena;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of one arena.
 *
 * <p>Locations are copied on input and output so callers cannot mutate the
 * definition through Bukkit's mutable Location type.</p>
 *
 * <p><b>空值契约：</b>{@link #getCore()} 与 {@link #getShopNpc()} 允许返回 null，
 * 表示该竞技场<b>尚未部署</b>（管理员重置竞技场会清掉 arenas.yml 里的 core / shop-npc 段，
 * 但竞技场定义仍留在 registry 中，以便在 GUI 里显示为「未部署」并重新部署）。
 * 调用方必须容忍 null——例如 {@code ArenaInstance} 不允许在 core 为 null 时创建实例。</p>
 */
public final class ArenaDefinition {

    private final String id;
    private final String displayName;
    private final boolean enabled;
    private final String worldName;
    private final Location waitingLocation;  // ✅ 等待区位置（游戏开始前玩家等待的地方）
    private final Location core;             // null = 尚未部署
    private final Location shopNpc;          // null = 本竞技场未配置商店 NPC
    private final List<Location> spawnPoints;
    private final int nodeSearchRadius;
    private final int spawnRadiusMin;
    private final int spawnRadiusMax;
    private final int spawnYOffset;
    private final int minPlayers;
    private final int maxPlayers;
    private final int victoryWaves;
    private final int waveIntervalSeconds;

    /**
     * @param core 竞技场核心位置；<b>null 表示尚未部署</b>（读取方必须容忍 null）
     */
    public ArenaDefinition(String id, String displayName, boolean enabled, String worldName,
                           Location waitingLocation, Location core, List<Location> spawnPoints,
                           int nodeSearchRadius, int spawnRadiusMin, int spawnRadiusMax, int spawnYOffset) {
        this(id, displayName, enabled, worldName, waitingLocation, core, spawnPoints, nodeSearchRadius,
                spawnRadiusMin, spawnRadiusMax, spawnYOffset, 2, 6, 20, 30);
    }

    /**
     * @param core 竞技场核心位置；<b>null 表示尚未部署</b>（读取方必须容忍 null）
     */
    public ArenaDefinition(String id, String displayName, boolean enabled, String worldName,
                           Location waitingLocation, Location core, List<Location> spawnPoints,
                           int nodeSearchRadius, int spawnRadiusMin, int spawnRadiusMax, int spawnYOffset,
                           int minPlayers, int maxPlayers, int victoryWaves,
                           int waveIntervalSeconds) {
        this(id, displayName, enabled, worldName, waitingLocation, core, spawnPoints, nodeSearchRadius,
                spawnRadiusMin, spawnRadiusMax, spawnYOffset, minPlayers, maxPlayers, victoryWaves,
                waveIntervalSeconds, null);
    }

    /**
     * @param core    竞技场核心位置；<b>null 表示尚未部署</b>（读取方必须容忍 null）
     * @param shopNpc 本竞技场的商店 NPC 位置；null 表示未配置
     */
    public ArenaDefinition(String id, String displayName, boolean enabled, String worldName,
                           Location waitingLocation, Location core, List<Location> spawnPoints,
                           int nodeSearchRadius, int spawnRadiusMin, int spawnRadiusMax, int spawnYOffset,
                           int minPlayers, int maxPlayers, int victoryWaves,
                           int waveIntervalSeconds, Location shopNpc) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Arena ID must not be blank");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Arena world name must not be blank");
        }
        this.id = id;
        this.displayName = displayName;
        this.enabled = enabled;
        this.worldName = worldName;
        // 等待区未配置时跟随 core；core 也为 null（未部署）时保持 null，由调用方兜底
        this.waitingLocation = copyLocation(waitingLocation != null ? waitingLocation : core);
        this.core = copyLocation(core);
        this.shopNpc = copyLocation(shopNpc);
        List<Location> copies = new ArrayList<>();
        if (spawnPoints != null) {
            for (Location spawnPoint : spawnPoints) {
                copies.add(copyLocation(Objects.requireNonNull(spawnPoint, "spawnPoint")));
            }
        }
        this.spawnPoints = Collections.unmodifiableList(copies);
        this.nodeSearchRadius = nodeSearchRadius;
        this.spawnRadiusMin = spawnRadiusMin;
        this.spawnRadiusMax = spawnRadiusMax;
        this.spawnYOffset = spawnYOffset;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.victoryWaves = victoryWaves;
        this.waveIntervalSeconds = waveIntervalSeconds;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public String getWorldName() { return worldName; }
    /** 等待区位置；未配置且 core 也为空（未部署）时返回 null */
    public Location getWaitingLocation() { return copyLocation(waitingLocation); }
    /** 竞技场核心位置；<b>null = 尚未部署</b>（重置竞技场会清掉该位置） */
    public Location getCore() { return copyLocation(core); }
    /** 本竞技场的商店 NPC 位置；null = 未配置 */
    public Location getShopNpc() { return copyLocation(shopNpc); }

    /** Returns an immutable list containing defensive copies of all locations. */
    public List<Location> getSpawnPoints() {
        List<Location> copies = new ArrayList<>(spawnPoints.size());
        for (Location spawnPoint : spawnPoints) copies.add(copyLocation(spawnPoint));
        return Collections.unmodifiableList(copies);
    }

    public int getNodeSearchRadius() { return nodeSearchRadius; }
    public int getSpawnRadiusMin() { return spawnRadiusMin; }
    public int getSpawnRadiusMax() { return spawnRadiusMax; }
    public int getSpawnYOffset() { return spawnYOffset; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getVictoryWaves() { return victoryWaves; }
    public int getWaveIntervalSeconds() { return waveIntervalSeconds; }

    /**
     * 派生一个只替换 4 个游戏参数的新定义，其余字段（世界、坐标、启用状态、名称）原样保留。
     *
     * <p>本类不可变、没有 setter，管理员在竞技场管理 GUI 改参数时走这条路径。</p>
     *
     * <p><b>刻意不提供「整体替换定义」的方法</b>：管理 GUI 只改数值，用 {@code refreshDefinition}
     * 之类的整体替换会把坐标类字段一并带过来，而 {@code AdminDeployManager} 手里还有一份
     * 自己的生成点副本，两者一旦不同步就会出现「GUI 显示 3 个生成点、实际刷 2 个」这类静默错位。</p>
     *
     * @param minPlayers          最少开局人数（&ge; 1）
     * @param maxPlayers          人数上限（&ge; minPlayers）
     * @param victoryWaves        通关波数（&ge; 1）
     * @param waveIntervalSeconds 波次间隔秒数（&ge; 1）
     */
    public ArenaDefinition withGameParams(int minPlayers, int maxPlayers,
                                          int victoryWaves, int waveIntervalSeconds) {
        return new ArenaDefinition(id, displayName, enabled, worldName, waitingLocation, core, spawnPoints,
                nodeSearchRadius, spawnRadiusMin, spawnRadiusMax, spawnYOffset,
                minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds, shopNpc);
    }

    public static Builder builder(String id, String worldName, Location core) {
        return new Builder(id, worldName, core);
    }

    public static Builder builder(String id, String worldName, Location waitingLocation, Location core) {
        return new Builder(id, worldName, waitingLocation, core);
    }

    /** 位置防御性拷贝；null 原样返回（core / shop-npc 允许为空 = 未部署） */
    private static Location copyLocation(Location location) {
        return location == null ? null : location.clone();
    }

    public static final class Builder {
        private final String id;
        private final String worldName;
        private final Location waitingLocation;
        private final Location core;
        private Location shopNpc;
        private String displayName;
        private boolean enabled = true;
        private List<Location> spawnPoints = List.of();
        private int nodeSearchRadius;
        private int spawnRadiusMin;
        private int spawnRadiusMax;
        private int spawnYOffset;
        private int minPlayers = 2;
        private int maxPlayers = 6;
        private int victoryWaves = 20;
        private int waveIntervalSeconds = 30;

        private Builder(String id, String worldName, Location core) {
            this.id = id;
            this.worldName = worldName;
            this.waitingLocation = core;  // 默认等待区和核心位置相同
            this.core = core;
        }

        private Builder(String id, String worldName, Location waitingLocation, Location core) {
            this.id = id;
            this.worldName = worldName;
            this.waitingLocation = waitingLocation;
            this.core = core;
        }

        public Builder displayName(String value) { displayName = value; return this; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder spawnPoints(List<Location> value) { spawnPoints = value; return this; }
        /** 商店 NPC 位置（null = 本竞技场未配置，对应 arenas.yml 的 {@code arenas.<id>.shop-npc}） */
        public Builder shopNpc(Location value) { shopNpc = value; return this; }
        public Builder nodeSearchRadius(int value) { nodeSearchRadius = value; return this; }
        public Builder spawnRadiusMin(int value) { spawnRadiusMin = value; return this; }
        public Builder spawnRadiusMax(int value) { spawnRadiusMax = value; return this; }
        public Builder spawnYOffset(int value) { spawnYOffset = value; return this; }
        public Builder minPlayers(int value) { minPlayers = value; return this; }
        public Builder maxPlayers(int value) { maxPlayers = value; return this; }
        public Builder victoryWaves(int value) { victoryWaves = value; return this; }
        public Builder waveIntervalSeconds(int value) { waveIntervalSeconds = value; return this; }

        public ArenaDefinition build() {
            return new ArenaDefinition(id, displayName, enabled, worldName, waitingLocation, core, spawnPoints,
                    nodeSearchRadius, spawnRadiusMin, spawnRadiusMax, spawnYOffset,
                    minPlayers, maxPlayers, victoryWaves, waveIntervalSeconds, shopNpc);
        }
    }
}
