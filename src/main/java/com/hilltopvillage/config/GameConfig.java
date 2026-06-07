package com.hilltopvillage.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;

public class GameConfig {

    private String worldName = "world";
    private double coreX, coreY, coreZ;
    private int minPlayers = 2;
    private int maxPlayers = 6;
    private int victoryWaves = 20;
    private int waveIntervalSeconds = 30;

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
    private double hammerLowDamage = 30.0;
    private double hammerMediumDamage = 40.0;
    private double hammerHighDamage = 60.0;
    private double hammerLowRadius = 4.0;
    private double hammerMediumRadius = 5.0;
    private double hammerHighRadius = 6.5;
    private double hammerLowCharge = 1.0;
    private double hammerMediumCharge = 5.0;
    private double hammerHighCharge = 10.0;

    private List<Material> nodeBlockTypes = new ArrayList<>(Arrays.asList(
            Material.BEACON, Material.ENCHANTING_TABLE, Material.ENDER_CHEST, Material.RESPAWN_ANCHOR
    ));
    private double nodeBaseHealth = 100.0;
    private Material nodeRepairItem = Material.SLIME_BALL;
    private double nodeRepairAmount = 50.0;
    private double nodeBuffRadius = 20.0;
    private double nodeSelfDestructMultiplier = 3.0;
    private Map<PotionEffectType, Integer> nodeBuffEffects = new LinkedHashMap<>();

    private Map<String, MonsterConfig> monsters = new LinkedHashMap<>();
    private Map<Integer, WaveConfig> waves = new LinkedHashMap<>();
    private WaveConfig defaultWave;

    private int spawnRadiusMin = 20;
    private int spawnRadiusMax = 40;
    private int globalMobCap = 80;
    private int mobCapPerPlayer = 15;

    // ---- 烈焰蛋 (Fireball) 配置 ----
    private Material fireballMaterial = Material.FIRE_CHARGE;
    private double fireballSpeed = 1.5;
    private double fireballDamage = 15.0;
    private double fireballExplosionRadius = 4.0;
    private int fireballCooldownTicks = 3;
    private int fireballMaxTravelTicks = 100;
    private double fireballKnockback = 5.0;

    public GameConfig() {
        nodeBuffEffects.put(PotionEffectType.DAMAGE_RESISTANCE, 0);
        nodeBuffEffects.put(PotionEffectType.REGENERATION, 0);

        monsters.put("explode-beetle", new MonsterConfig(Material.CAVE_SPIDER_SPAWN_EGG, 40, 0.3, 1.0,
                "自爆甲虫", 3.0, 0, 0, 0));
        monsters.put("hook-claw-hunter", new MonsterConfig(Material.SKELETON_SPAWN_EGG, 60, 0.25, 1.8,
                "钩爪猎手", 0, 60, 40, 8));
        monsters.put("flying-dropper", new MonsterConfig(Material.PHANTOM_SPAWN_EGG, 80, 0.3, 0,
                "飞行抛投者", 0, 160, 3, 10));

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

        // 钩爪猎手专用
        private double hookVelocity;          // 钩爪投射速度
        private int cooldownTicks;            // 技能冷却 tick 数
        private int stunDurationTicks;        // 眩晕持续 tick 数

        // 飞行抛投者专用
        private int airdropMobCount;          // 每次空投怪物数量
        private int airdropIntervalTicks;     // 空投间隔 tick 数

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
            // 新字段默认值
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
        public int getCooldownTicks() { return cooldownTicks; }
        public void setCooldownTicks(int v) { this.cooldownTicks = v; }
        public int getStunDurationTicks() { return stunDurationTicks; }
        public void setStunDurationTicks(int v) { this.stunDurationTicks = v; }
        public int getAirdropMobCount() { return airdropMobCount; }
        public void setAirdropMobCount(int v) { this.airdropMobCount = v; }
        public int getAirdropIntervalTicks() { return airdropIntervalTicks; }
        public void setAirdropIntervalTicks(int v) { this.airdropIntervalTicks = v; }
    }

    public static class WaveConfig {
        private int totalMobs;
        private Map<String, MobComposition> compositions;

        public WaveConfig(int totalMobs, Map<String, MobComposition> compositions) {
            this.totalMobs = totalMobs;
            this.compositions = compositions;
        }

        public int getTotalMobs() { return totalMobs; }
        public void setTotalMobs(int v) { this.totalMobs = v; }
        public Map<String, MobComposition> getCompositions() { return compositions; }
        public int getTotalWeight() {
            return compositions.values().stream().mapToInt(MobComposition::getWeight).sum();
        }
    }

    public static class MobComposition {
        private int count;
        private int weight;

        public MobComposition(int count, int weight) {
            this.count = count;
            this.weight = weight;
        }

        public int getCount() { return count; }
        public void setCount(int v) { this.count = v; }
        public int getWeight() { return weight; }
        public void setWeight(int v) { this.weight = v; }
    }

    public GameConfig snapshot() {
        GameConfig snap = new GameConfig();
        snap.worldName = this.worldName;
        snap.coreX = this.coreX; snap.coreY = this.coreY; snap.coreZ = this.coreZ;
        snap.minPlayers = this.minPlayers; snap.maxPlayers = this.maxPlayers;
        snap.victoryWaves = this.victoryWaves; snap.waveIntervalSeconds = this.waveIntervalSeconds;
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
        snap.hammerLowCharge = this.hammerLowCharge; snap.hammerMediumCharge = this.hammerMediumCharge;
        snap.hammerHighCharge = this.hammerHighCharge;
        snap.nodeBlockTypes = new ArrayList<>(this.nodeBlockTypes);
        snap.nodeBaseHealth = this.nodeBaseHealth; snap.nodeRepairItem = this.nodeRepairItem;
        snap.nodeRepairAmount = this.nodeRepairAmount; snap.nodeBuffRadius = this.nodeBuffRadius;
        snap.nodeSelfDestructMultiplier = this.nodeSelfDestructMultiplier;
        snap.nodeBuffEffects = new LinkedHashMap<>(this.nodeBuffEffects);
        snap.monsters = new LinkedHashMap<>(this.monsters);
        snap.waves = new LinkedHashMap<>(this.waves);
        snap.defaultWave = this.defaultWave;
        snap.spawnRadiusMin = this.spawnRadiusMin; snap.spawnRadiusMax = this.spawnRadiusMax;
        snap.globalMobCap = this.globalMobCap; snap.mobCapPerPlayer = this.mobCapPerPlayer;
        snap.fireballMaterial = this.fireballMaterial; snap.fireballSpeed = this.fireballSpeed;
        snap.fireballDamage = this.fireballDamage; snap.fireballExplosionRadius = this.fireballExplosionRadius;
        snap.fireballCooldownTicks = this.fireballCooldownTicks; snap.fireballMaxTravelTicks = this.fireballMaxTravelTicks;
        snap.fireballKnockback = this.fireballKnockback;
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
    public double getHammerLowCharge() { return hammerLowCharge; }
    public void setHammerLowCharge(double v) { this.hammerLowCharge = v; }
    public double getHammerMediumCharge() { return hammerMediumCharge; }
    public void setHammerMediumCharge(double v) { this.hammerMediumCharge = v; }
    public double getHammerHighCharge() { return hammerHighCharge; }
    public void setHammerHighCharge(double v) { this.hammerHighCharge = v; }
    public List<Material> getNodeBlockTypes() { return nodeBlockTypes; }
    public void setNodeBlockTypes(List<Material> v) { this.nodeBlockTypes = v; }
    public double getNodeBaseHealth() { return nodeBaseHealth; }
    public void setNodeBaseHealth(double v) { this.nodeBaseHealth = v; }
    public Material getNodeRepairItem() { return nodeRepairItem; }
    public void setNodeRepairItem(Material v) { this.nodeRepairItem = v; }
    public double getNodeRepairAmount() { return nodeRepairAmount; }
    public void setNodeRepairAmount(double v) { this.nodeRepairAmount = v; }
    public double getNodeBuffRadius() { return nodeBuffRadius; }
    public void setNodeBuffRadius(double v) { this.nodeBuffRadius = v; }
    public double getNodeSelfDestructMultiplier() { return nodeSelfDestructMultiplier; }
    public void setNodeSelfDestructMultiplier(double v) { this.nodeSelfDestructMultiplier = v; }
    public Map<PotionEffectType, Integer> getNodeBuffEffects() { return nodeBuffEffects; }
    public Map<String, MonsterConfig> getMonsters() { return monsters; }
    public Map<Integer, WaveConfig> getWaves() { return waves; }
    public WaveConfig getDefaultWave() { return defaultWave; }
    public void setDefaultWave(WaveConfig v) { this.defaultWave = v; }
    public int getSpawnRadiusMin() { return spawnRadiusMin; }
    public void setSpawnRadiusMin(int v) { this.spawnRadiusMin = v; }
    public int getSpawnRadiusMax() { return spawnRadiusMax; }
    public void setSpawnRadiusMax(int v) { this.spawnRadiusMax = v; }
    public int getGlobalMobCap() { return globalMobCap; }
    public void setGlobalMobCap(int v) { this.globalMobCap = v; }
    public int getMobCapPerPlayer() { return mobCapPerPlayer; }
    public void setMobCapPerPlayer(int v) { this.mobCapPerPlayer = v; }

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

    public WaveConfig getWaveConfig(int waveNumber) {
        return waves.getOrDefault(waveNumber, defaultWave);
    }
}