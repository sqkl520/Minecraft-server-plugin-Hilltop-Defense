package com.hilltopvillage.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

public class GameConfig {

    private String worldName = "world";
    private double coreX, coreY, coreZ;
    private int minPlayers = 2;
    private int maxPlayers = 6;
    private int victoryWaves = 20;
    private int waveIntervalSeconds = 30;
    /**
     * 波次超时保险（秒，P0-C 卡死软锁兜底）：某波持续超过此时长仍未清波时，
     * 先把全部残存怪物传送到最近玩家身边并高亮，再宽限一段时间后强制清除，
     * 打破任何形态的卡死（木桩怪 / 卡地形 / 不可达节点）。{@code <=0} 表示禁用该保险。
     *
     * <p>fix4 反馈12：保险默认<b>禁用</b>（0）——作者要求「去掉自动超时保险」的纯净体验。
     * 代码路径与配置钩子保留：把本键设为 &gt;0 即可随时重新启用（例如未来遇到真卡死）。</p>
     */
    private int waveTimeoutSeconds = 0;

    /**
     * fix4 反馈12 升级归一化用的旧默认值：fix3 及更早版本的 {@link #waveTimeoutSeconds} 默认是 120。
     *
     * <p>存量服务器的 {@code game-settings.yml} 磁盘上仍是 120（配置文件不会被升级覆盖），
     * 为避免「改了默认值但老存档不生效」，ConfigManager 在加载与外部合并时把 120
     * 一律归一化为 0（禁用）。如需重新启用自动保险，请设定为其它正数（如 121）。</p>
     */
    public static final int LEGACY_WAVE_TIMEOUT_SECONDS = 120;
    /** 失败结算倒计时（秒）：显示统计后传送回大厅的等待时间 */
    private int defeatCountdownSeconds = 20;
    /** 村民最大生命值 */
    private double villagerHealth = 20.0;
    /** 掉线重连窗口（秒），0 = 关闭重连（掉线即离开） */
    private int rejoinTimeoutSeconds = 60;
    /**
     * 空置自动结束（秒，G3d）：对局进行中（STARTING / RUNNING / WAVE_INTERMISSION）
     * 在线成员为 0 持续此时长后自动走正常结束路径；{@code <=0} 表示禁用。
     */
    private int emptyArenaEndSeconds = 120;
    /** 是否启用地图恢复（对局结束回滚被破坏/放置的方块） */
    private boolean mapRestoreEnabled = true;
    /** 是否启用记分板侧边栏 */
    private boolean scoreboardEnabled = true;

    private Material hammerMaterial = Material.NETHERITE_AXE;
    private String hammerItemsAdderId = "";
    private int hammerCustomModelData = 0;
    private boolean hammerUseItemsAdder = false;
    private double hammerBaseDamage = 20.0;
    private double hammerEffectRadius = 5.0;
    private int hammerAftershockTicks = 60;
    private int hammerSmashTimeoutTicks = 600;
    private int hammerVisualDisplayTicks = 20;
    private double hammerDamageLowThreshold = 5.0;
    private double hammerDamageMediumThreshold = 10.0;
    private double hammerDamageLowMultiplier = 1.5;
    private double hammerDamageMediumMultiplier = 2.0;
    private double hammerDamageHighMultiplier = 3.0;
    // 每档独立配置（伤害 / 范围 / 蓄力距离）
    private double hammerLowDamage = 20.0;
    private double hammerMediumDamage = 40.0;
    private double hammerHighDamage = 60.0;
    private double hammerLowRadius = 4.0;
    private double hammerMediumRadius = 5.0;
    private double hammerHighRadius = 6.5;
    // 注意：tier.low.charge 已删除（STORY-1.3）—— fall < medium 即判低档，该阈值从未被读取。
    // YAML/GUI 中的对应键同步删除，旧存档残留键由 ConfigManager 清理。此处不保留字段。
    private double hammerMediumCharge = 5.0;
    private double hammerHighCharge = 10.0;
    /** 猛击冷却时长（秒），0 表示无冷却 */
    private double hammerCooldownSeconds = 8.0;
    /** 重锤总可用次数（发锤时写入 lore，耗尽碎裂），0 表示无限 */
    private int hammerMaxUses = 30;
    // 物品显示名（留空则回退 messages.yml 的 item.hammer-name）
    private String hammerItemName = "";

    private List<Material> nodeBlockTypes = new ArrayList<>(Arrays.asList(
            Material.BEACON, Material.ENCHANTING_TABLE, Material.ENDER_CHEST, Material.RESPAWN_ANCHOR
    ));
    private double nodeBaseHealth = 100.0;
    private double nodeBuffRadius = 20.0;
    private double nodeSelfDestructMultiplier = 3.0;
    private int nodeSearchRadius = 128;
    // 修复物品显示名（留空则回退 messages.yml 的 item.repair-cost-name）
    private String repairCostName = "";
    private Material repairCostItem = Material.GHAST_TEAR;
    private int repairCostAmount = 1;
    /** 每消耗 1 个修复物品恢复的节点生命值 */
    private double repairAmountPerCost = 50.0;
    private Map<PotionEffectType, Integer> nodeBuffEffects = new LinkedHashMap<>();
    /** 节点增益刷新周期（tick） */
    private int nodeBuffRefreshTicks = 60;
    /** 多个节点叠加时的最大等级（amplifier 上限） */
    private int nodeBuffStackCap = 3;
    /** 单次施加的增益持续时间（tick） */
    private int nodeBuffDurationTicks = 100;

    private Map<String, MonsterConfig> monsters = new LinkedHashMap<>();
    private Map<Integer, WaveConfig> waves = new LinkedHashMap<>();
    private WaveConfig defaultWave;

    private int spawnRadiusMin = 20;
    private int spawnRadiusMax = 40;
    private double spawnRadiusSpread = 3.0;  // 生成点扩散半径（默认3.0格）
    private int globalMobCap = 80;
    private int mobCapPerPlayer = 15;
    private int spawnYOffset = 0;
    /**
     * 单个飞行抛投者整局最多执行多少轮空投（P0-D 空投失控兜底）。
     * 抛投者存活期间每隔冷却时间就空投一批小怪，无上限会使实体数指数膨胀卡服。
     * {@code <=0} 表示禁用空投（抛投者仍会飞行但不再投怪）。
     */
    private int airdropMaxPerDropper = 3;

    // ---- 村民商店货币掉落 (Shop) ----
    // 击杀波次怪物的货币掉落概率（0~1；默认 1.0 = 每杀必掉，保留为兼容/加权开关）
    private double currencyDropChance = 1.0;
    // 每次掉落的货币数量区间 [min, max]（含两端，运行时随机取值）
    private int currencyDropMin = 1;
    private int currencyDropMax = 2;

    // ---- 开局初始套装 (starter-kit, STORY-1.2) ----
    // 开局时向每名在场玩家发放的物品；enabled=false 时不发放
    // 默认与 game-settings.yml 模板保持一致：石剑 + 皮革四件套（D-2 决策）+ 面包/火把
    private boolean starterKitEnabled = true;
    private List<StarterKitItem> starterKitItems = defaultStarterKitItems();

    /** 新默认套装（装备线轮起：石剑 + 皮革四件套 + 面包x16 + 火把x8，与 game-settings.yml 模板一致）。 */
    public static List<StarterKitItem> defaultStarterKitItems() {
        return new ArrayList<>(Arrays.asList(
                new StarterKitItem(Material.STONE_SWORD, 1),
                new StarterKitItem(Material.LEATHER_HELMET, 1),
                new StarterKitItem(Material.LEATHER_CHESTPLATE, 1),
                new StarterKitItem(Material.LEATHER_LEGGINGS, 1),
                new StarterKitItem(Material.LEATHER_BOOTS, 1),
                new StarterKitItem(Material.BREAD, 16),
                new StarterKitItem(Material.TORCH, 8)));
    }

    /**
     * 装备线上线前的旧默认套装（石剑 + 面包x16 + 火把x8，1.0.9-fix4 及更早的出厂值）。
     * 升级归一化用：见 {@link #isLegacyDefaultStarterKit}。
     */
    public static List<StarterKitItem> legacyDefaultStarterKitItems() {
        return new ArrayList<>(Arrays.asList(
                new StarterKitItem(Material.STONE_SWORD, 1),
                new StarterKitItem(Material.BREAD, 16),
                new StarterKitItem(Material.TORCH, 8)));
    }

    /**
     * 是否为「旧默认套装」：逐项 material+amount 全等（顺序敏感）。
     *
     * <p>与 {@code LEGACY_WAVE_TIMEOUT_SECONDS} 同款升级归一化：存量服务器的
     * {@code game-settings.yml} 磁盘上仍是旧默认列表（配置文件不会被升级覆盖，
     * 且磁盘列表优先于代码默认），若直接沿用，皮革四件套在升级后永远不会发放。
     * ConfigManager 在加载与外部合并时，把恰好等于此列表的值升级为新默认
     * （追加皮革四件套）；服主改过任意一项（含顺序/数量）则原样保留——
     * 代价是「服主恰好手写过逐字相同的旧默认」也会被一并升级，这是有意的取舍
     * （旧默认正是出厂值本身，且升级收益远大于该边界）。</p>
     */
    public static boolean isLegacyDefaultStarterKit(List<StarterKitItem> parsed) {
        return parsed != null && parsed.equals(legacyDefaultStarterKitItems());
    }

    // ---- 烈焰蛋 (Fireball) 配置 ----
    private Material fireballMaterial = Material.FIRE_CHARGE;
    private double fireballSpeed = 1.5;
    private double fireballDamage = 15.0;
    private double fireballExplosionRadius = 4.0;
    private int fireballCooldownTicks = 3;
    private int fireballMaxTravelTicks = 100;
    private double fireballKnockback = 5.0;
    private double fireballProximityFuse = 1.5;  // 近炸检测半径（格），0=禁用
    // 物品显示名（留空则回退 messages.yml 的 item.fireball-name）
    private String fireballItemName = "";

    public GameConfig() {
        nodeBuffEffects.put(PotionEffectType.RESISTANCE, 0);
        nodeBuffEffects.put(PotionEffectType.REGENERATION, 0);

        // displayName 留空：由 WaveManager 回退到 messages.yml 的 mob.* key
        // entityType 默认值与历史硬编码保持一致，可在 monsters-config.yml 中覆盖
        MonsterConfig beetle = new MonsterConfig(Material.CAVE_SPIDER_SPAWN_EGG, 40, 0.3, 1.0,
                "", 3.0, 0, 0, 0);
        beetle.setEntityType(EntityType.CAVE_SPIDER);
        // 按怪种掉落（E2 作者拍板）：甲虫 1 个
        beetle.setDropMin(1);
        beetle.setDropMax(1);
        monsters.put("explode-beetle", beetle);

        MonsterConfig hunter = new MonsterConfig(Material.SKELETON_SPAWN_EGG, 60, 0.25, 1.8,
                "", 0, 60, 40, 8);
        hunter.setEntityType(EntityType.SKELETON);
        // 按怪种掉落（E2）：钩爪猎手 1~2 个
        hunter.setDropMin(1);
        hunter.setDropMax(2);
        monsters.put("hook-claw-hunter", hunter);

        MonsterConfig dropper = new MonsterConfig(Material.PHANTOM_SPAWN_EGG, 80, 0.3, 0,
                "", 0, 160, 3, 10);
        dropper.setEntityType(EntityType.PHANTOM);
        // 按怪种掉落（E2）：飞行抛投者 2~3 个（飞在 +8 高度最难打，打难打的怪更值钱）
        dropper.setDropMin(2);
        dropper.setDropMax(3);
        monsters.put("flying-dropper", dropper);

        defaultWave = new WaveConfig(10, new LinkedHashMap<>());
        defaultWave.getCompositions().put("explode-beetle", new MobComposition(4, 3));
        defaultWave.getCompositions().put("hook-claw-hunter", new MobComposition(3, 2));
        defaultWave.getCompositions().put("flying-dropper", new MobComposition(3, 1));

        WaveConfig wave1 = new WaveConfig(8, new LinkedHashMap<>());
        wave1.getCompositions().put("explode-beetle", new MobComposition(6, 5));
        wave1.getCompositions().put("hook-claw-hunter", new MobComposition(2, 1));
        waves.put(1, wave1);

        WaveConfig wave5 = new WaveConfig(14, new LinkedHashMap<>());
        wave5.getCompositions().put("explode-beetle", new MobComposition(5, 3));
        wave5.getCompositions().put("hook-claw-hunter", new MobComposition(4, 4));
        wave5.getCompositions().put("flying-dropper", new MobComposition(5, 2));
        waves.put(5, wave5);

        WaveConfig wave10 = new WaveConfig(20, new LinkedHashMap<>());
        wave10.getCompositions().put("explode-beetle", new MobComposition(8, 4));
        wave10.getCompositions().put("hook-claw-hunter", new MobComposition(6, 5));
        wave10.getCompositions().put("flying-dropper", new MobComposition(6, 3));
        waves.put(10, wave10);
    }

    /**
     * 单个怪物类型的完整属性配置。
     * 可在 monsters-config.yml 中定义和修改。
     */
    public static class MonsterConfig {
        // 基础标识
        private Material displayEgg;          // GUI 中显示的刷怪蛋图标
        private String displayName;           // 怪物头顶显示名称（彩色）
        private EntityType entityType;        // 实际生成的实体类型（可在 monsters-config.yml 覆盖）

        // 战斗属性
        private double health;                // 最大生命值
        private double speed;                 // 移动速度倍率 (0.1~2.0)
        private double baseDamage;            // 基础攻击伤害值

        // 模型外观
        private String itemsAdderId;          // ItemsAdder 模型命名空间ID (如 "myplugin:boss")
        private int customModelData;          // 原版自定义模型数据值 (配合资源包, 0=不使用)

        // 手持装备
        private Material mainHandItem;        // 主手武器/工具 (如 IRON_SWORD)
        private Material offHandItem;         // 副手物品 (如 SHIELD, 可为 null)

        // 盔甲穿戴
        private Material helmet;              // 头盔 (如 LEATHER_HELMET, 可为 null)
        private Material chestplate;          // 胸甲
        private Material leggings;            // 护腿
        private Material boots;               // 靴子

        // 自爆虫专用
        private double explodeDamageMultiplier; // 自爆对节点伤害倍率
        // 自爆是否附带原版实体爆炸（false 时只结算节点伤害 + 纯视觉效果，不炸伤村民/玩家）
        private boolean entityExplosion = true;

        // 钩爪猎手专用
        private double hookVelocity;          // 钩爪投射速度
        private int cooldownTicks;            // 技能冷却 tick 数
        private int stunDurationTicks;        // 眩晕持续 tick 数

        // 飞行抛投者专用
        private int airdropMobCount;          // 每次空投怪物数量
        private int airdropIntervalTicks;     // 空投间隔 tick 数
        private String airdropMobType;        // 空投怪物类型

        // 按怪种掉落（E2，2026-10-17 作者拍板）：该怪种被击杀时的货币掉落区间覆盖。
        // -1 = 未配置（端点级回退：某端 <0 时该端用全局 shop.currency-drop-min/max 对应端）。
        private int dropMin = -1;             // 每杀掉落货币数量下限（-1 = 未配置）
        private int dropMax = -1;             // 每杀掉落货币数量上限（-1 = 未配置）

        /**
         * 创建一个完整的怪物配置对象。
         *
         * @param displayEgg            刷怪蛋图标
         * @param health                最大生命值
         * @param speed                 移动速度倍率
         * @param hookVelocity          钩爪投射速度
         * @param displayName           显示名称
         * @param explodeDamageMultiplier 自爆伤害倍率
         * @param cooldownTicks         技能冷却
         * @param stunDurationTicks     眩晕时长
         * @param airdropMobCount       空投数量
         */
        public MonsterConfig(Material displayEgg, double health, double speed, double hookVelocity,
                             String displayName, double explodeDamageMultiplier, int cooldownTicks,
                             int stunDurationTicks, int airdropMobCount) {
            this.displayEgg = displayEgg;
            this.health = health;
            this.speed = speed;
            this.hookVelocity = hookVelocity;
            this.displayName = displayName;
            this.explodeDamageMultiplier = explodeDamageMultiplier;
            this.cooldownTicks = cooldownTicks;
            this.stunDurationTicks = stunDurationTicks;
            this.airdropMobCount = airdropMobCount;
            this.airdropMobType = "ZOMBIE";
            // 新字段默认值
            this.entityType = EntityType.ZOMBIE;
            this.baseDamage = 5.0;
            this.itemsAdderId = "";
            this.customModelData = 0;
            this.mainHandItem = null;
            this.offHandItem = null;
            this.helmet = null;
            this.chestplate = null;
            this.leggings = null;
            this.boots = null;
            this.airdropIntervalTicks = 10;
        }

        // --- Getter/Setter ---

        public Material getDisplayEgg() { return displayEgg; }
        public void setDisplayEgg(Material m) { this.displayEgg = m; }
        public EntityType getEntityType() { return entityType; }
        public void setEntityType(EntityType v) { this.entityType = v; }
        public double getHealth() { return health; }
        public void setHealth(double v) { this.health = v; }
        public double getSpeed() { return speed; }
        public void setSpeed(double v) { this.speed = v; }
        public double getBaseDamage() { return baseDamage; }
        public void setBaseDamage(double v) { this.baseDamage = v; }
        public String getItemsAdderId() { return itemsAdderId; }
        public void setItemsAdderId(String v) { this.itemsAdderId = v; }
        public int getCustomModelData() { return customModelData; }
        public void setCustomModelData(int v) { this.customModelData = v; }
        public Material getMainHandItem() { return mainHandItem; }
        public void setMainHandItem(Material v) { this.mainHandItem = v; }
        public Material getOffHandItem() { return offHandItem; }
        public void setOffHandItem(Material v) { this.offHandItem = v; }
        public Material getHelmet() { return helmet; }
        public void setHelmet(Material v) { this.helmet = v; }
        public Material getChestplate() { return chestplate; }
        public void setChestplate(Material v) { this.chestplate = v; }
        public Material getLeggings() { return leggings; }
        public void setLeggings(Material v) { this.leggings = v; }
        public Material getBoots() { return boots; }
        public void setBoots(Material v) { this.boots = v; }
        public double getHookVelocity() { return hookVelocity; }
        public void setHookVelocity(double v) { this.hookVelocity = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public double getExplodeDamageMultiplier() { return explodeDamageMultiplier; }
        public void setExplodeDamageMultiplier(double v) { this.explodeDamageMultiplier = v; }
        public boolean isEntityExplosion() { return entityExplosion; }
        public void setEntityExplosion(boolean v) { this.entityExplosion = v; }
        public int getCooldownTicks() { return cooldownTicks; }
        public void setCooldownTicks(int v) { this.cooldownTicks = v; }
        public int getStunDurationTicks() { return stunDurationTicks; }
        public void setStunDurationTicks(int v) { this.stunDurationTicks = v; }
        public int getAirdropMobCount() { return airdropMobCount; }
        public void setAirdropMobCount(int v) { this.airdropMobCount = v; }
        public String getAirdropMobType() { return airdropMobType; }
        public void setAirdropMobType(String v) { this.airdropMobType = v; }
        public int getAirdropIntervalTicks() { return airdropIntervalTicks; }
        public void setAirdropIntervalTicks(int v) { this.airdropIntervalTicks = v; }
        /** 该怪种掉落货币数量下限（{@code <0} = 未配置，回退全局 shop.currency-drop-min） */
        public int getDropMin() { return dropMin; }
        public void setDropMin(int v) { this.dropMin = v; }
        /** 该怪种掉落货币数量上限（{@code <0} = 未配置，回退全局 shop.currency-drop-max） */
        public int getDropMax() { return dropMax; }
        public void setDropMax(int v) { this.dropMax = v; }

        /**
         * 返回当前怪物配置的完整深拷贝，与原实例字段完全独立。
         * Material / String 均为不可变类型，直接赋值即满足深拷贝语义。
         */
        public MonsterConfig copy() {
            MonsterConfig c = new MonsterConfig(
                    this.displayEgg, this.health, this.speed, this.hookVelocity,
                    this.displayName, this.explodeDamageMultiplier,
                    this.cooldownTicks, this.stunDurationTicks, this.airdropMobCount);
            c.entityType = this.entityType;
            c.baseDamage = this.baseDamage;
            c.itemsAdderId = this.itemsAdderId;
            c.customModelData = this.customModelData;
            c.mainHandItem = this.mainHandItem;
            c.offHandItem = this.offHandItem;
            c.helmet = this.helmet;
            c.chestplate = this.chestplate;
            c.leggings = this.leggings;
            c.boots = this.boots;
            c.airdropMobType = this.airdropMobType;
            c.airdropIntervalTicks = this.airdropIntervalTicks;
            c.entityExplosion = this.entityExplosion;
            c.dropMin = this.dropMin;
            c.dropMax = this.dropMax;
            return c;
        }

        /** 值语义比较（P2-31 快照比较用）；字段集与 {@link #copy()} 一致。 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MonsterConfig other)) return false;
            return displayEgg == other.displayEgg
                    && Double.compare(health, other.health) == 0
                    && Double.compare(speed, other.speed) == 0
                    && Double.compare(hookVelocity, other.hookVelocity) == 0
                    && Double.compare(explodeDamageMultiplier, other.explodeDamageMultiplier) == 0
                    && cooldownTicks == other.cooldownTicks
                    && stunDurationTicks == other.stunDurationTicks
                    && airdropMobCount == other.airdropMobCount
                    && entityType == other.entityType
                    && Double.compare(baseDamage, other.baseDamage) == 0
                    && customModelData == other.customModelData
                    && mainHandItem == other.mainHandItem
                    && offHandItem == other.offHandItem
                    && helmet == other.helmet
                    && chestplate == other.chestplate
                    && leggings == other.leggings
                    && boots == other.boots
                    && entityExplosion == other.entityExplosion
                    && airdropIntervalTicks == other.airdropIntervalTicks
                    && dropMin == other.dropMin
                    && dropMax == other.dropMax
                    && Objects.equals(displayName, other.displayName)
                    && Objects.equals(itemsAdderId, other.itemsAdderId)
                    && Objects.equals(airdropMobType, other.airdropMobType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(displayEgg, health, speed, hookVelocity, displayName,
                    explodeDamageMultiplier, cooldownTicks, stunDurationTicks, airdropMobCount,
                    entityType, baseDamage, itemsAdderId, customModelData, mainHandItem, offHandItem,
                    helmet, chestplate, leggings, boots, entityExplosion, airdropMobType,
                    airdropIntervalTicks, dropMin, dropMax);
        }
    }

    public static class WaveConfig {
        /** 精确总数上限（STORY-1.4：原语义"每人总只数"已废弃；保留字段名与存档键，向后兼容） */
        private int totalMobs;
        private Map<String, MobComposition> compositions;

        public WaveConfig(int totalMobs, Map<String, MobComposition> compositions) {
            this.totalMobs = totalMobs;
            this.compositions = compositions;
        }

        /**
         * 精确总数上限（{@code total-mobs}）。
         * 当 {@code sum(count) × 人数} 超过 {@code total-mobs × 人数} 时按比例裁剪；反之不补齐。
         */
        public int getTotalMobs() { return totalMobs; }
        public void setTotalMobs(int v) { this.totalMobs = v; }
        public Map<String, MobComposition> getCompositions() { return compositions; }
        public int getTotalWeight() {
            return compositions.values().stream().mapToInt(MobComposition::getWeight).sum();
        }
        /** 各类型 {@code count} 之和（精确配比的基础；全 0 时刷怪回退旧权重路径） */
        public int getTotalCount() {
            return compositions.values().stream().mapToInt(MobComposition::getCount).sum();
        }

        /**
         * 返回当前波次配置的完整深拷贝。
         * compositions map 内每个 MobComposition 均新建独立实例。
         */
        public WaveConfig copy() {
            Map<String, MobComposition> compositionsCopy = new LinkedHashMap<>();
            for (Map.Entry<String, MobComposition> entry : this.compositions.entrySet()) {
                compositionsCopy.put(entry.getKey(), entry.getValue().copy());
            }
            return new WaveConfig(this.totalMobs, compositionsCopy);
        }

        /** 值语义比较（P2-31 快照比较用）；composition 依赖 {@link MobComposition#equals}。 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WaveConfig other)) return false;
            return totalMobs == other.totalMobs
                    && Objects.equals(compositions, other.compositions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(totalMobs, compositions);
        }
    }

    public static class MobComposition {
        /** 每人只数：该类型实际刷出 count × 在场人数 只（STORY-1.4 起精确生效，不再是纯展示字段） */
        private int count;
        /** 权重：仅当本波所有 count 全 0 时的兼容回退路径使用；正常路径忽略 */
        private int weight;

        public MobComposition(int count, int weight) {
            this.count = count;
            this.weight = weight;
        }

        public int getCount() { return count; }
        public void setCount(int v) { this.count = v; }
        public int getWeight() { return weight; }
        public void setWeight(int v) { this.weight = v; }

        /** 返回当前实例的深拷贝。 */
        public MobComposition copy() {
            return new MobComposition(this.count, this.weight);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MobComposition other)) return false;
            return count == other.count && weight == other.weight;
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, weight);
        }
    }

    /**
     * 开局初始套装中的单个条目（STORY-1.2）。
     * 对应 game-settings.yml 的 {@code starter-kit.items} 列表项（{@code material} + {@code amount}）。
     */
    public static class StarterKitItem {
        private Material material;
        private int amount;

        public StarterKitItem(Material material, int amount) {
            this.material = material;
            this.amount = amount;
        }

        public Material getMaterial() { return material; }
        public void setMaterial(Material v) { this.material = v; }
        public int getAmount() { return amount; }
        public void setAmount(int v) { this.amount = v; }

        /** 返回当前实例的深拷贝（Material 不可变，直接赋值即满足深拷贝语义）。 */
        public StarterKitItem copy() {
            return new StarterKitItem(this.material, this.amount);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StarterKitItem other)) return false;
            return material == other.material && amount == other.amount;
        }

        @Override
        public int hashCode() {
            return Objects.hash(material, amount);
        }
    }

    public GameConfig snapshot() {
        GameConfig snap = new GameConfig();
        snap.worldName = this.worldName;
        snap.coreX = this.coreX; snap.coreY = this.coreY; snap.coreZ = this.coreZ;
        snap.minPlayers = this.minPlayers; snap.maxPlayers = this.maxPlayers;
        snap.victoryWaves = this.victoryWaves; snap.waveIntervalSeconds = this.waveIntervalSeconds;
        snap.waveTimeoutSeconds = this.waveTimeoutSeconds;
        snap.defeatCountdownSeconds = this.defeatCountdownSeconds;
        snap.villagerHealth = this.villagerHealth;
        snap.rejoinTimeoutSeconds = this.rejoinTimeoutSeconds;
        snap.emptyArenaEndSeconds = this.emptyArenaEndSeconds;
        snap.mapRestoreEnabled = this.mapRestoreEnabled;
        snap.scoreboardEnabled = this.scoreboardEnabled;
        snap.hammerMaterial = this.hammerMaterial; snap.hammerItemsAdderId = this.hammerItemsAdderId;
        snap.hammerCustomModelData = this.hammerCustomModelData; snap.hammerUseItemsAdder = this.hammerUseItemsAdder;
        snap.hammerBaseDamage = this.hammerBaseDamage;
        snap.hammerEffectRadius = this.hammerEffectRadius; snap.hammerAftershockTicks = this.hammerAftershockTicks;
        snap.hammerSmashTimeoutTicks = this.hammerSmashTimeoutTicks; snap.hammerVisualDisplayTicks = this.hammerVisualDisplayTicks;
        snap.hammerDamageLowThreshold = this.hammerDamageLowThreshold; snap.hammerDamageMediumThreshold = this.hammerDamageMediumThreshold;
        snap.hammerDamageLowMultiplier = this.hammerDamageLowMultiplier; snap.hammerDamageMediumMultiplier = this.hammerDamageMediumMultiplier;
        snap.hammerDamageHighMultiplier = this.hammerDamageHighMultiplier;
        snap.hammerLowDamage = this.hammerLowDamage; snap.hammerMediumDamage = this.hammerMediumDamage;
        snap.hammerHighDamage = this.hammerHighDamage;
        snap.hammerLowRadius = this.hammerLowRadius; snap.hammerMediumRadius = this.hammerMediumRadius;
        snap.hammerHighRadius = this.hammerHighRadius;
        snap.hammerMediumCharge = this.hammerMediumCharge;
        snap.hammerHighCharge = this.hammerHighCharge;
        snap.hammerCooldownSeconds = this.hammerCooldownSeconds;
        snap.hammerMaxUses = this.hammerMaxUses;
        snap.hammerItemName = this.hammerItemName;
        snap.nodeBlockTypes = new ArrayList<>(this.nodeBlockTypes);
        snap.nodeBaseHealth = this.nodeBaseHealth; snap.nodeBuffRadius = this.nodeBuffRadius;
        snap.nodeSelfDestructMultiplier = this.nodeSelfDestructMultiplier;
        snap.nodeSearchRadius = this.nodeSearchRadius;
        snap.repairCostName = this.repairCostName;
        snap.repairCostItem = this.repairCostItem;
        snap.repairCostAmount = this.repairCostAmount;
        snap.repairAmountPerCost = this.repairAmountPerCost;
        snap.nodeBuffEffects = new LinkedHashMap<>(this.nodeBuffEffects);
        snap.nodeBuffRefreshTicks = this.nodeBuffRefreshTicks;
        snap.nodeBuffStackCap = this.nodeBuffStackCap;
        snap.nodeBuffDurationTicks = this.nodeBuffDurationTicks;
        // deep copy monsters — 每个 MonsterConfig 独立拷贝，snapshot 与 activeConfig 完全隔离
        snap.monsters = new LinkedHashMap<>();
        for (Map.Entry<String, MonsterConfig> entry : this.monsters.entrySet()) {
            snap.monsters.put(entry.getKey(), entry.getValue().copy());
        }
        // deep copy waves — 每个 WaveConfig 及其 compositions 独立拷贝
        snap.waves = new LinkedHashMap<>();
        for (Map.Entry<Integer, WaveConfig> entry : this.waves.entrySet()) {
            snap.waves.put(entry.getKey(), entry.getValue().copy());
        }
        snap.defaultWave = this.defaultWave.copy();
        snap.spawnRadiusMin = this.spawnRadiusMin; snap.spawnRadiusMax = this.spawnRadiusMax;
        // P2-31：spawnRadiusSpread 此前漏拷——两边都恒为默认 3.0，equals 本无影响，
        // 但快照应忠实反映字段（也是 equals 包含该字段的前提）
        snap.spawnRadiusSpread = this.spawnRadiusSpread;
        snap.globalMobCap = this.globalMobCap; snap.mobCapPerPlayer = this.mobCapPerPlayer;
        snap.spawnYOffset = this.spawnYOffset;
        snap.airdropMaxPerDropper = this.airdropMaxPerDropper;
        snap.fireballMaterial = this.fireballMaterial; snap.fireballSpeed = this.fireballSpeed;
        snap.fireballDamage = this.fireballDamage; snap.fireballExplosionRadius = this.fireballExplosionRadius;
        snap.fireballCooldownTicks = this.fireballCooldownTicks; snap.fireballMaxTravelTicks = this.fireballMaxTravelTicks;
        snap.fireballKnockback = this.fireballKnockback;
        snap.fireballProximityFuse = this.fireballProximityFuse;
        snap.fireballItemName = this.fireballItemName;
        snap.currencyDropChance = this.currencyDropChance;
        snap.currencyDropMin = this.currencyDropMin;
        snap.currencyDropMax = this.currencyDropMax;
        // starter-kit 深拷贝：每个条目独立拷贝（snapshot 与 activeConfig 完全隔离）
        snap.starterKitEnabled = this.starterKitEnabled;
        snap.starterKitItems = new ArrayList<>();
        for (StarterKitItem item : this.starterKitItems) {
            snap.starterKitItems.add(item.copy());
        }
        return snap;
    }

    public String getWorldName() { return worldName; }
    public void setWorldName(String v) { this.worldName = v; }
    public double getCoreX() { return coreX; }
    public void setCoreX(double v) { this.coreX = v; }
    public double getCoreY() { return coreY; }
    public void setCoreY(double v) { this.coreY = v; }
    public double getCoreZ() { return coreZ; }
    public void setCoreZ(double v) { this.coreZ = v; }
    public int getMinPlayers() { return minPlayers; }
    public void setMinPlayers(int v) { this.minPlayers = v; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int v) { this.maxPlayers = v; }
    public int getVictoryWaves() { return victoryWaves; }
    public void setVictoryWaves(int v) { this.victoryWaves = v; }
    public int getWaveIntervalSeconds() { return waveIntervalSeconds; }
    public void setWaveIntervalSeconds(int v) { this.waveIntervalSeconds = v; }
    public int getWaveTimeoutSeconds() { return waveTimeoutSeconds; }
    public void setWaveTimeoutSeconds(int v) { this.waveTimeoutSeconds = v; }
    public int getDefeatCountdownSeconds() { return defeatCountdownSeconds; }
    public void setDefeatCountdownSeconds(int v) { this.defeatCountdownSeconds = v; }
    public double getVillagerHealth() { return villagerHealth; }
    public void setVillagerHealth(double v) { this.villagerHealth = v; }
    public int getRejoinTimeoutSeconds() { return rejoinTimeoutSeconds; }
    public void setRejoinTimeoutSeconds(int v) { this.rejoinTimeoutSeconds = v; }
    public int getEmptyArenaEndSeconds() { return emptyArenaEndSeconds; }
    public void setEmptyArenaEndSeconds(int v) { this.emptyArenaEndSeconds = v; }
    public boolean isMapRestoreEnabled() { return mapRestoreEnabled; }
    public void setMapRestoreEnabled(boolean v) { this.mapRestoreEnabled = v; }
    public boolean isScoreboardEnabled() { return scoreboardEnabled; }
    public void setScoreboardEnabled(boolean v) { this.scoreboardEnabled = v; }
    public Material getHammerMaterial() { return hammerMaterial; }
    public void setHammerMaterial(Material v) { this.hammerMaterial = v; }
    public String getHammerItemsAdderId() { return hammerItemsAdderId; }
    public void setHammerItemsAdderId(String v) { this.hammerItemsAdderId = v; }
    public int getHammerCustomModelData() { return hammerCustomModelData; }
    public void setHammerCustomModelData(int v) { this.hammerCustomModelData = v; }
    public boolean isHammerUseItemsAdder() { return hammerUseItemsAdder; }
    public void setHammerUseItemsAdder(boolean v) { this.hammerUseItemsAdder = v; }
    public double getHammerBaseDamage() { return hammerBaseDamage; }
    public void setHammerBaseDamage(double v) { this.hammerBaseDamage = v; }
    public double getHammerEffectRadius() { return hammerEffectRadius; }
    public void setHammerEffectRadius(double v) { this.hammerEffectRadius = v; }
    public int getHammerAftershockTicks() { return hammerAftershockTicks; }
    public void setHammerAftershockTicks(int v) { this.hammerAftershockTicks = v; }
    public int getHammerSmashTimeoutTicks() { return hammerSmashTimeoutTicks; }
    public void setHammerSmashTimeoutTicks(int v) { this.hammerSmashTimeoutTicks = v; }
    public int getHammerVisualDisplayTicks() { return hammerVisualDisplayTicks; }
    public void setHammerVisualDisplayTicks(int v) { this.hammerVisualDisplayTicks = v; }
    public double getHammerDamageLowThreshold() { return hammerDamageLowThreshold; }
    public void setHammerDamageLowThreshold(double v) { this.hammerDamageLowThreshold = v; }
    public double getHammerDamageMediumThreshold() { return hammerDamageMediumThreshold; }
    public void setHammerDamageMediumThreshold(double v) { this.hammerDamageMediumThreshold = v; }
    public double getHammerDamageLowMultiplier() { return hammerDamageLowMultiplier; }
    public void setHammerDamageLowMultiplier(double v) { this.hammerDamageLowMultiplier = v; }
    public double getHammerDamageMediumMultiplier() { return hammerDamageMediumMultiplier; }
    public void setHammerDamageMediumMultiplier(double v) { this.hammerDamageMediumMultiplier = v; }
    public double getHammerDamageHighMultiplier() { return hammerDamageHighMultiplier; }
    public void setHammerDamageHighMultiplier(double v) { this.hammerDamageHighMultiplier = v; }
    public double getHammerLowDamage() { return hammerLowDamage; }
    public void setHammerLowDamage(double v) { this.hammerLowDamage = v; }
    public double getHammerMediumDamage() { return hammerMediumDamage; }
    public void setHammerMediumDamage(double v) { this.hammerMediumDamage = v; }
    public double getHammerHighDamage() { return hammerHighDamage; }
    public void setHammerHighDamage(double v) { this.hammerHighDamage = v; }
    public double getHammerLowRadius() { return hammerLowRadius; }
    public void setHammerLowRadius(double v) { this.hammerLowRadius = v; }
    public double getHammerMediumRadius() { return hammerMediumRadius; }
    public void setHammerMediumRadius(double v) { this.hammerMediumRadius = v; }
    public double getHammerHighRadius() { return hammerHighRadius; }
    public void setHammerHighRadius(double v) { this.hammerHighRadius = v; }
    public double getHammerMediumCharge() { return hammerMediumCharge; }
    public void setHammerMediumCharge(double v) { this.hammerMediumCharge = v; }
    public double getHammerHighCharge() { return hammerHighCharge; }
    public void setHammerHighCharge(double v) { this.hammerHighCharge = v; }
    public double getHammerCooldownSeconds() { return hammerCooldownSeconds; }
    public void setHammerCooldownSeconds(double v) { this.hammerCooldownSeconds = v; }
    public int getHammerMaxUses() { return hammerMaxUses; }
    public void setHammerMaxUses(int v) { this.hammerMaxUses = v; }
    public List<Material> getNodeBlockTypes() { return nodeBlockTypes; }
    public void setNodeBlockTypes(List<Material> v) { this.nodeBlockTypes = v; }
    public double getNodeBaseHealth() { return nodeBaseHealth; }
    public void setNodeBaseHealth(double v) { this.nodeBaseHealth = v; }
    public double getNodeBuffRadius() { return nodeBuffRadius; }
    public void setNodeBuffRadius(double v) { this.nodeBuffRadius = v; }
    public double getNodeSelfDestructMultiplier() { return nodeSelfDestructMultiplier; }
    public void setNodeSelfDestructMultiplier(double v) { this.nodeSelfDestructMultiplier = v; }
    public int getNodeSearchRadius() { return nodeSearchRadius; }
    public void setNodeSearchRadius(int v) { this.nodeSearchRadius = v; }
    public String getRepairCostName() { return repairCostName; }
    public void setRepairCostName(String v) { this.repairCostName = v; }
    public Material getRepairCostItem() { return repairCostItem; }
    public void setRepairCostItem(Material v) { this.repairCostItem = v; }
    public int getRepairCostAmount() { return repairCostAmount; }
    public void setRepairCostAmount(int v) { this.repairCostAmount = v; }
    public double getRepairAmountPerCost() { return repairAmountPerCost; }
    public void setRepairAmountPerCost(double v) { this.repairAmountPerCost = v; }
    public Map<PotionEffectType, Integer> getNodeBuffEffects() { return nodeBuffEffects; }
    public int getNodeBuffRefreshTicks() { return nodeBuffRefreshTicks; }
    public void setNodeBuffRefreshTicks(int v) { this.nodeBuffRefreshTicks = v; }
    public int getNodeBuffStackCap() { return nodeBuffStackCap; }
    public void setNodeBuffStackCap(int v) { this.nodeBuffStackCap = v; }
    public int getNodeBuffDurationTicks() { return nodeBuffDurationTicks; }
    public void setNodeBuffDurationTicks(int v) { this.nodeBuffDurationTicks = v; }
    public Map<String, MonsterConfig> getMonsters() { return monsters; }
    public Map<Integer, WaveConfig> getWaves() { return waves; }
    public WaveConfig getDefaultWave() { return defaultWave; }
    public void setDefaultWave(WaveConfig v) { this.defaultWave = v; }
    public int getSpawnRadiusMin() { return spawnRadiusMin; }
    public void setSpawnRadiusMin(int v) { this.spawnRadiusMin = v; }
    public int getSpawnRadiusMax() { return spawnRadiusMax; }
    public void setSpawnRadiusMax(int v) { this.spawnRadiusMax = v; }
    public double getSpawnRadiusSpread() { return spawnRadiusSpread; }
    public void setSpawnRadiusSpread(double v) { this.spawnRadiusSpread = v; }
    public int getGlobalMobCap() { return globalMobCap; }
    public void setGlobalMobCap(int v) { this.globalMobCap = v; }
    public int getMobCapPerPlayer() { return mobCapPerPlayer; }
    public void setMobCapPerPlayer(int v) { this.mobCapPerPlayer = v; }
    public int getAirdropMaxPerDropper() { return airdropMaxPerDropper; }
    public void setAirdropMaxPerDropper(int v) { this.airdropMaxPerDropper = v; }
    public int getSpawnYOffset() { return spawnYOffset; }
    public void setSpawnYOffset(int v) { this.spawnYOffset = v; }

    public Material getFireballMaterial() { return fireballMaterial; }
    public void setFireballMaterial(Material v) { this.fireballMaterial = v; }
    public double getFireballSpeed() { return fireballSpeed; }
    public void setFireballSpeed(double v) { this.fireballSpeed = v; }
    public double getFireballDamage() { return fireballDamage; }
    public void setFireballDamage(double v) { this.fireballDamage = v; }
    public double getFireballExplosionRadius() { return fireballExplosionRadius; }
    public void setFireballExplosionRadius(double v) { this.fireballExplosionRadius = v; }
    public int getFireballCooldownTicks() { return fireballCooldownTicks; }
    public void setFireballCooldownTicks(int v) { this.fireballCooldownTicks = v; }
    public int getFireballMaxTravelTicks() { return fireballMaxTravelTicks; }
    public void setFireballMaxTravelTicks(int v) { this.fireballMaxTravelTicks = v; }
    public double getFireballKnockback() { return fireballKnockback; }
    public void setFireballKnockback(double v) { this.fireballKnockback = v; }
    public double getFireballProximityFuse() { return fireballProximityFuse; }
    public void setFireballProximityFuse(double v) { this.fireballProximityFuse = v; }
    public String getFireballItemName() { return fireballItemName; }
    public void setFireballItemName(String v) { this.fireballItemName = v; }
    public String getHammerItemName() { return hammerItemName; }
    public void setHammerItemName(String v) { this.hammerItemName = v; }

    public double getCurrencyDropChance() { return currencyDropChance; }
    public void setCurrencyDropChance(double v) { this.currencyDropChance = v; }
    public int getCurrencyDropMin() { return currencyDropMin; }
    public void setCurrencyDropMin(int v) { this.currencyDropMin = v; }
    public int getCurrencyDropMax() { return currencyDropMax; }
    public void setCurrencyDropMax(int v) { this.currencyDropMax = v; }

    /** starter-kit 总开关（game-settings.yml 的 {@code starter-kit.enabled}） */
    public boolean isStarterKitEnabled() { return starterKitEnabled; }
    public void setStarterKitEnabled(boolean v) { this.starterKitEnabled = v; }
    /** starter-kit 物品列表（{@code starter-kit.items}；返回内部列表本体，调用方不得持有跨配置变更） */
    public List<StarterKitItem> getStarterKitItems() { return starterKitItems; }
    public void setStarterKitItems(List<StarterKitItem> v) { this.starterKitItems = v; }

    public WaveConfig getWaveConfig(int waveNumber) {
        return waves.getOrDefault(waveNumber, defaultWave);
    }

    // ============================================================
    //  值语义 equals/hashCode（P2-31）
    //
    //  ConfigManager.hasUnsavedChanges() 靠 `activeConfig.snapshot().equals(lastSavedSnapshot)`
    //  判断是否有未保存修改。此前全类无 equals → Object 引用比较 → 恒为 false 取反恒 true，
    //  主菜单永远显示「待保存」。以下逐字段展开（与 snapshot() 拷贝的字段集一一对应）。
    //
    //  维护约定：新增配置字段时，必须同时更新 snapshot() 拷贝、saveToFile 写盘【和这里】，
    //  否则「改了新字段但菜单不显示待保存」。
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GameConfig other)) return false;
        return Objects.equals(worldName, other.worldName)
                && Double.compare(coreX, other.coreX) == 0
                && Double.compare(coreY, other.coreY) == 0
                && Double.compare(coreZ, other.coreZ) == 0
                && minPlayers == other.minPlayers
                && maxPlayers == other.maxPlayers
                && victoryWaves == other.victoryWaves
                && waveIntervalSeconds == other.waveIntervalSeconds
                && waveTimeoutSeconds == other.waveTimeoutSeconds
                && defeatCountdownSeconds == other.defeatCountdownSeconds
                && Double.compare(villagerHealth, other.villagerHealth) == 0
                && rejoinTimeoutSeconds == other.rejoinTimeoutSeconds
                && emptyArenaEndSeconds == other.emptyArenaEndSeconds
                && mapRestoreEnabled == other.mapRestoreEnabled
                && scoreboardEnabled == other.scoreboardEnabled
                && hammerMaterial == other.hammerMaterial
                && Objects.equals(hammerItemsAdderId, other.hammerItemsAdderId)
                && hammerCustomModelData == other.hammerCustomModelData
                && hammerUseItemsAdder == other.hammerUseItemsAdder
                && Double.compare(hammerBaseDamage, other.hammerBaseDamage) == 0
                && Double.compare(hammerEffectRadius, other.hammerEffectRadius) == 0
                && hammerAftershockTicks == other.hammerAftershockTicks
                && hammerSmashTimeoutTicks == other.hammerSmashTimeoutTicks
                && hammerVisualDisplayTicks == other.hammerVisualDisplayTicks
                && Double.compare(hammerDamageLowThreshold, other.hammerDamageLowThreshold) == 0
                && Double.compare(hammerDamageMediumThreshold, other.hammerDamageMediumThreshold) == 0
                && Double.compare(hammerDamageLowMultiplier, other.hammerDamageLowMultiplier) == 0
                && Double.compare(hammerDamageMediumMultiplier, other.hammerDamageMediumMultiplier) == 0
                && Double.compare(hammerDamageHighMultiplier, other.hammerDamageHighMultiplier) == 0
                && Double.compare(hammerLowDamage, other.hammerLowDamage) == 0
                && Double.compare(hammerMediumDamage, other.hammerMediumDamage) == 0
                && Double.compare(hammerHighDamage, other.hammerHighDamage) == 0
                && Double.compare(hammerLowRadius, other.hammerLowRadius) == 0
                && Double.compare(hammerMediumRadius, other.hammerMediumRadius) == 0
                && Double.compare(hammerHighRadius, other.hammerHighRadius) == 0
                && Double.compare(hammerMediumCharge, other.hammerMediumCharge) == 0
                && Double.compare(hammerHighCharge, other.hammerHighCharge) == 0
                && Double.compare(hammerCooldownSeconds, other.hammerCooldownSeconds) == 0
                && hammerMaxUses == other.hammerMaxUses
                && Objects.equals(hammerItemName, other.hammerItemName)
                && Objects.equals(nodeBlockTypes, other.nodeBlockTypes)
                && Double.compare(nodeBaseHealth, other.nodeBaseHealth) == 0
                && Double.compare(nodeBuffRadius, other.nodeBuffRadius) == 0
                && Double.compare(nodeSelfDestructMultiplier, other.nodeSelfDestructMultiplier) == 0
                && nodeSearchRadius == other.nodeSearchRadius
                && Objects.equals(repairCostName, other.repairCostName)
                && repairCostItem == other.repairCostItem
                && repairCostAmount == other.repairCostAmount
                && Double.compare(repairAmountPerCost, other.repairAmountPerCost) == 0
                && Objects.equals(nodeBuffEffects, other.nodeBuffEffects)
                && nodeBuffRefreshTicks == other.nodeBuffRefreshTicks
                && nodeBuffStackCap == other.nodeBuffStackCap
                && nodeBuffDurationTicks == other.nodeBuffDurationTicks
                && Objects.equals(monsters, other.monsters)
                && Objects.equals(waves, other.waves)
                && Objects.equals(defaultWave, other.defaultWave)
                && spawnRadiusMin == other.spawnRadiusMin
                && spawnRadiusMax == other.spawnRadiusMax
                && Double.compare(spawnRadiusSpread, other.spawnRadiusSpread) == 0
                && globalMobCap == other.globalMobCap
                && mobCapPerPlayer == other.mobCapPerPlayer
                && spawnYOffset == other.spawnYOffset
                && airdropMaxPerDropper == other.airdropMaxPerDropper
                && Double.compare(currencyDropChance, other.currencyDropChance) == 0
                && currencyDropMin == other.currencyDropMin
                && currencyDropMax == other.currencyDropMax
                && starterKitEnabled == other.starterKitEnabled
                && Objects.equals(starterKitItems, other.starterKitItems)
                && fireballMaterial == other.fireballMaterial
                && Double.compare(fireballSpeed, other.fireballSpeed) == 0
                && Double.compare(fireballDamage, other.fireballDamage) == 0
                && Double.compare(fireballExplosionRadius, other.fireballExplosionRadius) == 0
                && fireballCooldownTicks == other.fireballCooldownTicks
                && fireballMaxTravelTicks == other.fireballMaxTravelTicks
                && Double.compare(fireballKnockback, other.fireballKnockback) == 0
                && Double.compare(fireballProximityFuse, other.fireballProximityFuse) == 0
                && Objects.equals(fireballItemName, other.fireballItemName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, coreX, coreY, coreZ, minPlayers, maxPlayers,
                victoryWaves, waveIntervalSeconds, waveTimeoutSeconds, defeatCountdownSeconds,
                villagerHealth, rejoinTimeoutSeconds, emptyArenaEndSeconds, mapRestoreEnabled, scoreboardEnabled,
                hammerMaterial, hammerItemsAdderId, hammerCustomModelData, hammerUseItemsAdder,
                hammerBaseDamage, hammerEffectRadius, hammerAftershockTicks, hammerSmashTimeoutTicks,
                hammerVisualDisplayTicks, hammerDamageLowThreshold, hammerDamageMediumThreshold,
                hammerDamageLowMultiplier, hammerDamageMediumMultiplier, hammerDamageHighMultiplier,
                hammerLowDamage, hammerMediumDamage, hammerHighDamage,
                hammerLowRadius, hammerMediumRadius, hammerHighRadius,
                hammerMediumCharge, hammerHighCharge, hammerCooldownSeconds, hammerMaxUses,
                hammerItemName, nodeBlockTypes, nodeBaseHealth, nodeBuffRadius,
                nodeSelfDestructMultiplier, nodeSearchRadius, repairCostName, repairCostItem,
                repairCostAmount, repairAmountPerCost, nodeBuffEffects, nodeBuffRefreshTicks,
                nodeBuffStackCap, nodeBuffDurationTicks, monsters, waves, defaultWave,
                spawnRadiusMin, spawnRadiusMax, spawnRadiusSpread, globalMobCap, mobCapPerPlayer,
                spawnYOffset, airdropMaxPerDropper, currencyDropChance, currencyDropMin, currencyDropMax,
                starterKitEnabled, starterKitItems, fireballMaterial, fireballSpeed, fireballDamage,
                fireballExplosionRadius, fireballCooldownTicks, fireballMaxTravelTicks,
                fireballKnockback, fireballProximityFuse, fireballItemName);
    }
}