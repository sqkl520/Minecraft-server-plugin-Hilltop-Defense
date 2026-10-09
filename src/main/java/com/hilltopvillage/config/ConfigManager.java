package com.hilltopvillage.config;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ConfigManager {

    private final HilltopVillagePlugin plugin;
    private GameConfig activeConfig;
    private GameConfig lastSavedSnapshot;
    private File settingsFile;
    private FileConfiguration settingsYaml;
    private File monstersConfigFile;
    private FileConfiguration monstersConfigYaml;

    public ConfigManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.activeConfig = new GameConfig();
        this.lastSavedSnapshot = activeConfig.snapshot();
        initSettingsFile();
        initMonstersConfigFile();
        loadFromFile();
    }

    /**
     * 初始化游戏设置配置文件 (game-settings.yml)。
     * 首次运行时从 jar 中复制默认文件。
     */
    private void initSettingsFile() {
        settingsFile = new File(plugin.getDataFolder(), "game-settings.yml");
        if (!settingsFile.exists()) {
            plugin.saveResource("game-settings.yml", false);
        }
        settingsYaml = YamlConfiguration.loadConfiguration(settingsFile);
    }

    /**
     * 初始化怪物配置文件 (monsters-config.yml)。
     * 首次运行时从 jar 中复制默认文件。此文件包含所有怪物类型的完整属性。
     */
    private void initMonstersConfigFile() {
        monstersConfigFile = new File(plugin.getDataFolder(), "monsters-config.yml");
        if (!monstersConfigFile.exists()) {
            plugin.saveResource("monsters-config.yml", false);
        }
        monstersConfigYaml = YamlConfiguration.loadConfiguration(monstersConfigFile);
    }

    public GameConfig getActiveConfig() {
        return activeConfig;
    }

    public boolean hasUnsavedChanges() {
        GameConfig current = activeConfig.snapshot();
        return !current.equals(lastSavedSnapshot);
    }

    public void saveToFile() {
        settingsYaml.set("world-name", activeConfig.getWorldName());
        settingsYaml.set("core.x", activeConfig.getCoreX());
        settingsYaml.set("core.y", activeConfig.getCoreY());
        settingsYaml.set("core.z", activeConfig.getCoreZ());
        settingsYaml.set("min-players", activeConfig.getMinPlayers());
        settingsYaml.set("max-players", activeConfig.getMaxPlayers());
        settingsYaml.set("victory-waves", activeConfig.getVictoryWaves());
        settingsYaml.set("wave-interval-seconds", activeConfig.getWaveIntervalSeconds());
        settingsYaml.set("defeat-countdown-seconds", activeConfig.getDefeatCountdownSeconds());
        settingsYaml.set("villager-health", activeConfig.getVillagerHealth());
        settingsYaml.set("rejoin-timeout-seconds", activeConfig.getRejoinTimeoutSeconds());
        settingsYaml.set("map-restore.enabled", activeConfig.isMapRestoreEnabled());
        settingsYaml.set("scoreboard.enabled", activeConfig.isScoreboardEnabled());

        settingsYaml.set("hammer.material", activeConfig.getHammerMaterial().name());
        settingsYaml.set("hammer.itemsadder-id", activeConfig.getHammerItemsAdderId());
        settingsYaml.set("hammer.custom-model-data", activeConfig.getHammerCustomModelData());
        settingsYaml.set("hammer.use-itemsadder", activeConfig.isHammerUseItemsAdder());
        settingsYaml.set("hammer.base-damage", activeConfig.getHammerBaseDamage());
        settingsYaml.set("hammer.effect-radius", activeConfig.getHammerEffectRadius());
        settingsYaml.set("hammer.aftershock-ticks", activeConfig.getHammerAftershockTicks());
        settingsYaml.set("hammer.smash-timeout-ticks", activeConfig.getHammerSmashTimeoutTicks());
        settingsYaml.set("hammer.visual-display-ticks", activeConfig.getHammerVisualDisplayTicks());
        settingsYaml.set("hammer.cooldown-seconds", activeConfig.getHammerCooldownSeconds());
        settingsYaml.set("hammer.max-uses", activeConfig.getHammerMaxUses());
        // tier.low.charge 已删除（fall < medium 即 low，阈值无用）；磁盘残留键在此清掉，避免复活
        settingsYaml.set("hammer.tier.low.charge", null);
        settingsYaml.set("hammer.tier.low.damage", activeConfig.getHammerLowDamage());
        settingsYaml.set("hammer.tier.low.radius", activeConfig.getHammerLowRadius());
        settingsYaml.set("hammer.tier.medium.charge", activeConfig.getHammerMediumCharge());
        settingsYaml.set("hammer.tier.medium.damage", activeConfig.getHammerMediumDamage());
        settingsYaml.set("hammer.tier.medium.radius", activeConfig.getHammerMediumRadius());
        settingsYaml.set("hammer.tier.high.charge", activeConfig.getHammerHighCharge());
        settingsYaml.set("hammer.tier.high.damage", activeConfig.getHammerHighDamage());
        settingsYaml.set("hammer.tier.high.radius", activeConfig.getHammerHighRadius());
        // 旧版兼容字段
        settingsYaml.set("hammer.damage-low-threshold", activeConfig.getHammerDamageLowThreshold());
        settingsYaml.set("hammer.damage-medium-threshold", activeConfig.getHammerDamageMediumThreshold());
        settingsYaml.set("hammer.damage-low-multiplier", activeConfig.getHammerDamageLowMultiplier());
        settingsYaml.set("hammer.damage-medium-multiplier", activeConfig.getHammerDamageMediumMultiplier());
        settingsYaml.set("hammer.damage-high-multiplier", activeConfig.getHammerDamageHighMultiplier());
        settingsYaml.set("hammer.item-name", activeConfig.getHammerItemName());

        settingsYaml.set("nodes.base-health", activeConfig.getNodeBaseHealth());
        settingsYaml.set("nodes.buff-radius", activeConfig.getNodeBuffRadius());
        settingsYaml.set("nodes.self-destruct-multiplier", activeConfig.getNodeSelfDestructMultiplier());
        settingsYaml.set("nodes.search-radius", activeConfig.getNodeSearchRadius());
        settingsYaml.set("nodes.repair-cost-name", activeConfig.getRepairCostName());
        settingsYaml.set("nodes.repair-cost-item", activeConfig.getRepairCostItem().name());
        settingsYaml.set("nodes.repair-cost-amount", activeConfig.getRepairCostAmount());
        settingsYaml.set("nodes.repair-amount-per-cost", activeConfig.getRepairAmountPerCost());

        // 节点增益效果：格式 "效果名:等级"（等级为 amplifier，0 = I 级）
        List<String> buffEffectList = new ArrayList<>();
        for (Map.Entry<PotionEffectType, Integer> buff : activeConfig.getNodeBuffEffects().entrySet()) {
            // PotionEffectType -> NamespacedKey -> String（如 "resistance"），再转大写写入配置
            String effectName = buff.getKey().getKey().getKey();
            buffEffectList.add(effectName.toUpperCase() + ":" + buff.getValue());
        }
        settingsYaml.set("nodes.buff-effects", buffEffectList);
        settingsYaml.set("nodes.buff-refresh-ticks", activeConfig.getNodeBuffRefreshTicks());
        settingsYaml.set("nodes.buff-stack-cap", activeConfig.getNodeBuffStackCap());
        settingsYaml.set("nodes.buff-duration-ticks", activeConfig.getNodeBuffDurationTicks());

        settingsYaml.set("spawning.spawn-radius-min", activeConfig.getSpawnRadiusMin());
        settingsYaml.set("spawning.spawn-radius-max", activeConfig.getSpawnRadiusMax());
        settingsYaml.set("spawning.global-mob-cap", activeConfig.getGlobalMobCap());
        settingsYaml.set("spawning.mob-cap-per-player", activeConfig.getMobCapPerPlayer());
        settingsYaml.set("spawning.spawn-y-offset", activeConfig.getSpawnYOffset());

        // ---- 烈焰蛋配置 ----
        settingsYaml.set("fireball.material", activeConfig.getFireballMaterial().name());
        settingsYaml.set("fireball.speed", activeConfig.getFireballSpeed());
        settingsYaml.set("fireball.damage", activeConfig.getFireballDamage());
        settingsYaml.set("fireball.explosion-radius", activeConfig.getFireballExplosionRadius());
        settingsYaml.set("fireball.cooldown-ticks", activeConfig.getFireballCooldownTicks());
        settingsYaml.set("fireball.max-travel-ticks", activeConfig.getFireballMaxTravelTicks());
        settingsYaml.set("fireball.knockback", activeConfig.getFireballKnockback());
        settingsYaml.set("fireball.proximity-fuse", activeConfig.getFireballProximityFuse());
        settingsYaml.set("fireball.item-name", activeConfig.getFireballItemName());

        // ---- 村民商店货币掉落 ----
        settingsYaml.set("shop.currency-drop-chance", activeConfig.getCurrencyDropChance());
        settingsYaml.set("shop.currency-drop-amount", activeConfig.getCurrencyDropAmount());

        // ---- 开局初始套装 (starter-kit, STORY-1.2) ----
        settingsYaml.set("starter-kit.enabled", activeConfig.isStarterKitEnabled());
        List<Map<String, Object>> starterKitList = new ArrayList<>();
        for (GameConfig.StarterKitItem item : activeConfig.getStarterKitItems()) {
            if (item == null || item.getMaterial() == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("material", item.getMaterial().name());
            map.put("amount", Math.max(1, item.getAmount()));
            starterKitList.add(map);
        }
        settingsYaml.set("starter-kit.items", starterKitList);

        // 不整段清空 sections，以保留管理员手写的键与注释；代价是已删除的怪物/波次可能残留旧键
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            String path = "monsters." + key + ".";
            settingsYaml.set(path + "display-egg", mc.getDisplayEgg().name());
            settingsYaml.set(path + "health", mc.getHealth());
            settingsYaml.set(path + "speed", mc.getSpeed());
            settingsYaml.set(path + "hook-velocity", mc.getHookVelocity());
            settingsYaml.set(path + "display-name", mc.getDisplayName());
            settingsYaml.set(path + "explode-damage-multiplier", mc.getExplodeDamageMultiplier());
            if ("explode-beetle".equals(key)) {
                settingsYaml.set(path + "entity-explosion", mc.isEntityExplosion());
            }
            settingsYaml.set(path + "cooldown-ticks", mc.getCooldownTicks());
            settingsYaml.set(path + "stun-duration-ticks", mc.getStunDurationTicks());
            settingsYaml.set(path + "airdrop-mob-count", mc.getAirdropMobCount());
        }

        settingsYaml.set("waves.default.total-mobs", activeConfig.getDefaultWave().getTotalMobs());
        for (Map.Entry<String, GameConfig.MobComposition> comp : activeConfig.getDefaultWave().getCompositions().entrySet()) {
            settingsYaml.set("waves.default.compositions." + comp.getKey() + ".count", comp.getValue().getCount());
            settingsYaml.set("waves.default.compositions." + comp.getKey() + ".weight", comp.getValue().getWeight());
        }
        for (Map.Entry<Integer, GameConfig.WaveConfig> entry : activeConfig.getWaves().entrySet()) {
            String wavePath = "waves." + entry.getKey() + ".";
            settingsYaml.set(wavePath + "total-mobs", entry.getValue().getTotalMobs());
            for (Map.Entry<String, GameConfig.MobComposition> comp : entry.getValue().getCompositions().entrySet()) {
                settingsYaml.set(wavePath + "compositions." + comp.getKey() + ".count", comp.getValue().getCount());
                settingsYaml.set(wavePath + "compositions." + comp.getKey() + ".weight", comp.getValue().getWeight());
            }
        }

        try {
            settingsYaml.save(settingsFile);
            saveMonstersConfig();
            lastSavedSnapshot = activeConfig.snapshot();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save game-settings.yml: " + e.getMessage());
        }
    }

    /**
     * 将怪物完整配置保存到 monsters-config.yml。
     * 包含所有怪物类型的基础属性、模型外观、武器装备、盔甲等完整信息。
     */
    private void saveMonstersConfig() {
        // 不整段清空，以保留管理员手写的键与注释；代价是已删除的怪物可能残留旧键
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            String path = key + ".";
            monstersConfigYaml.set(path + "entity-type",
                    mc.getEntityType() != null ? mc.getEntityType().name() : "");
            monstersConfigYaml.set(path + "display-name", mc.getDisplayName());
            if (mc.getDisplayEgg() != null) monstersConfigYaml.set(path + "display-egg", mc.getDisplayEgg().name());
            monstersConfigYaml.set(path + "health", mc.getHealth());
            monstersConfigYaml.set(path + "speed", mc.getSpeed());
            monstersConfigYaml.set(path + "base-damage", mc.getBaseDamage());
            monstersConfigYaml.set(path + "itemsadder-id", mc.getItemsAdderId() != null ? mc.getItemsAdderId() : "");
            monstersConfigYaml.set(path + "custom-model-data", mc.getCustomModelData());
            monstersConfigYaml.set(path + "main-hand", mc.getMainHandItem() != null ? mc.getMainHandItem().name() : "");
            monstersConfigYaml.set(path + "off-hand", mc.getOffHandItem() != null ? mc.getOffHandItem().name() : "");
            monstersConfigYaml.set(path + "helmet", mc.getHelmet() != null ? mc.getHelmet().name() : "");
            monstersConfigYaml.set(path + "chestplate", mc.getChestplate() != null ? mc.getChestplate().name() : "");
            monstersConfigYaml.set(path + "leggings", mc.getLeggings() != null ? mc.getLeggings().name() : "");
            monstersConfigYaml.set(path + "boots", mc.getBoots() != null ? mc.getBoots().name() : "");
            monstersConfigYaml.set(path + "explode-damage-multiplier", mc.getExplodeDamageMultiplier());
            monstersConfigYaml.set(path + "hook-velocity", mc.getHookVelocity());
            monstersConfigYaml.set(path + "cooldown-ticks", mc.getCooldownTicks());
            monstersConfigYaml.set(path + "stun-duration-ticks", mc.getStunDurationTicks());
            monstersConfigYaml.set(path + "airdrop-mob-count", mc.getAirdropMobCount());
        }
        try {
            monstersConfigYaml.save(monstersConfigFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save monsters-config.yml: " + e.getMessage());
        }
    }

    public void loadFromFile() {
        if (settingsFile.exists()) {
            settingsYaml = YamlConfiguration.loadConfiguration(settingsFile);
        }

        if (settingsYaml.contains("world-name")) activeConfig.setWorldName(settingsYaml.getString("world-name"));
        if (settingsYaml.contains("core.x")) activeConfig.setCoreX(settingsYaml.getDouble("core.x"));
        if (settingsYaml.contains("core.y")) activeConfig.setCoreY(settingsYaml.getDouble("core.y"));
        if (settingsYaml.contains("core.z")) activeConfig.setCoreZ(settingsYaml.getDouble("core.z"));
        if (settingsYaml.contains("min-players")) activeConfig.setMinPlayers(settingsYaml.getInt("min-players"));
        if (settingsYaml.contains("max-players")) activeConfig.setMaxPlayers(settingsYaml.getInt("max-players"));
        if (settingsYaml.contains("victory-waves")) activeConfig.setVictoryWaves(settingsYaml.getInt("victory-waves"));
        if (settingsYaml.contains("wave-interval-seconds")) activeConfig.setWaveIntervalSeconds(settingsYaml.getInt("wave-interval-seconds"));
        if (settingsYaml.contains("defeat-countdown-seconds")) activeConfig.setDefeatCountdownSeconds(settingsYaml.getInt("defeat-countdown-seconds"));
        if (settingsYaml.contains("villager-health")) activeConfig.setVillagerHealth(settingsYaml.getDouble("villager-health"));
        if (settingsYaml.contains("rejoin-timeout-seconds")) activeConfig.setRejoinTimeoutSeconds(settingsYaml.getInt("rejoin-timeout-seconds"));
        if (settingsYaml.contains("map-restore.enabled")) activeConfig.setMapRestoreEnabled(settingsYaml.getBoolean("map-restore.enabled"));
        if (settingsYaml.contains("scoreboard.enabled")) activeConfig.setScoreboardEnabled(settingsYaml.getBoolean("scoreboard.enabled"));

        String hammerMat = settingsYaml.getString("hammer.material");
        if (hammerMat != null) {
            Material hammerMatResult = Material.getMaterial(hammerMat);
            if (hammerMatResult != null) {
                activeConfig.setHammerMaterial(hammerMatResult);
            } else {
                plugin.getLogger().warning("Unknown material: " + hammerMat + ", keeping default");
            }
        }
        if (settingsYaml.contains("hammer.itemsadder-id")) activeConfig.setHammerItemsAdderId(settingsYaml.getString("hammer.itemsadder-id"));
        if (settingsYaml.contains("hammer.custom-model-data")) activeConfig.setHammerCustomModelData(settingsYaml.getInt("hammer.custom-model-data"));
        if (settingsYaml.contains("hammer.use-itemsadder")) activeConfig.setHammerUseItemsAdder(settingsYaml.getBoolean("hammer.use-itemsadder"));
        if (settingsYaml.contains("hammer.base-damage")) activeConfig.setHammerBaseDamage(settingsYaml.getDouble("hammer.base-damage"));
        if (settingsYaml.contains("hammer.effect-radius")) activeConfig.setHammerEffectRadius(settingsYaml.getDouble("hammer.effect-radius"));
        if (settingsYaml.contains("hammer.aftershock-ticks")) activeConfig.setHammerAftershockTicks(settingsYaml.getInt("hammer.aftershock-ticks"));
        if (settingsYaml.contains("hammer.smash-timeout-ticks")) activeConfig.setHammerSmashTimeoutTicks(settingsYaml.getInt("hammer.smash-timeout-ticks"));
        if (settingsYaml.contains("hammer.visual-display-ticks")) activeConfig.setHammerVisualDisplayTicks(settingsYaml.getInt("hammer.visual-display-ticks"));
        if (settingsYaml.contains("hammer.damage-low-threshold")) activeConfig.setHammerDamageLowThreshold(settingsYaml.getDouble("hammer.damage-low-threshold"));
        if (settingsYaml.contains("hammer.damage-medium-threshold")) activeConfig.setHammerDamageMediumThreshold(settingsYaml.getDouble("hammer.damage-medium-threshold"));
        if (settingsYaml.contains("hammer.damage-low-multiplier")) activeConfig.setHammerDamageLowMultiplier(settingsYaml.getDouble("hammer.damage-low-multiplier"));
        if (settingsYaml.contains("hammer.damage-medium-multiplier")) activeConfig.setHammerDamageMediumMultiplier(settingsYaml.getDouble("hammer.damage-medium-multiplier"));
        if (settingsYaml.contains("hammer.damage-high-multiplier")) activeConfig.setHammerDamageHighMultiplier(settingsYaml.getDouble("hammer.damage-high-multiplier"));
        if (settingsYaml.contains("hammer.item-name")) activeConfig.setHammerItemName(settingsYaml.getString("hammer.item-name"));
        if (settingsYaml.contains("hammer.cooldown-seconds")) activeConfig.setHammerCooldownSeconds(settingsYaml.getDouble("hammer.cooldown-seconds"));
        if (settingsYaml.contains("hammer.max-uses")) activeConfig.setHammerMaxUses(settingsYaml.getInt("hammer.max-uses"));
        // tier.low.charge 已删除：load 时忽略磁盘残留键，不再读入
        if (settingsYaml.contains("hammer.tier.low.damage")) activeConfig.setHammerLowDamage(settingsYaml.getDouble("hammer.tier.low.damage"));
        if (settingsYaml.contains("hammer.tier.low.radius")) activeConfig.setHammerLowRadius(settingsYaml.getDouble("hammer.tier.low.radius"));
        if (settingsYaml.contains("hammer.tier.medium.charge")) activeConfig.setHammerMediumCharge(settingsYaml.getDouble("hammer.tier.medium.charge"));
        if (settingsYaml.contains("hammer.tier.medium.damage")) activeConfig.setHammerMediumDamage(settingsYaml.getDouble("hammer.tier.medium.damage"));
        if (settingsYaml.contains("hammer.tier.medium.radius")) activeConfig.setHammerMediumRadius(settingsYaml.getDouble("hammer.tier.medium.radius"));
        if (settingsYaml.contains("hammer.tier.high.charge")) activeConfig.setHammerHighCharge(settingsYaml.getDouble("hammer.tier.high.charge"));
        if (settingsYaml.contains("hammer.tier.high.damage")) activeConfig.setHammerHighDamage(settingsYaml.getDouble("hammer.tier.high.damage"));
        if (settingsYaml.contains("hammer.tier.high.radius")) activeConfig.setHammerHighRadius(settingsYaml.getDouble("hammer.tier.high.radius"));

        // 节点方块固定为重生锚（见 NodeSystem#loadConfig），不再从 YAML 读取
        if (settingsYaml.contains("nodes.base-health")) activeConfig.setNodeBaseHealth(settingsYaml.getDouble("nodes.base-health"));
        if (settingsYaml.contains("nodes.buff-radius")) activeConfig.setNodeBuffRadius(settingsYaml.getDouble("nodes.buff-radius"));
        if (settingsYaml.contains("nodes.self-destruct-multiplier")) activeConfig.setNodeSelfDestructMultiplier(settingsYaml.getDouble("nodes.self-destruct-multiplier"));
        if (settingsYaml.contains("nodes.search-radius")) activeConfig.setNodeSearchRadius(settingsYaml.getInt("nodes.search-radius"));
        if (settingsYaml.contains("nodes.repair-cost-name")) activeConfig.setRepairCostName(settingsYaml.getString("nodes.repair-cost-name"));
        String repairCostItemStr = settingsYaml.getString("nodes.repair-cost-item");
        if (repairCostItemStr != null) {
            Material rcMat = Material.getMaterial(repairCostItemStr);
            if (rcMat != null) { activeConfig.setRepairCostItem(rcMat); } else { plugin.getLogger().warning("Unknown material: " + repairCostItemStr + ", keeping default"); }
        }
        if (settingsYaml.contains("nodes.repair-cost-amount")) activeConfig.setRepairCostAmount(settingsYaml.getInt("nodes.repair-cost-amount"));
        if (settingsYaml.contains("nodes.repair-amount-per-cost")) activeConfig.setRepairAmountPerCost(settingsYaml.getDouble("nodes.repair-amount-per-cost"));

        // 旧键迁移：早期版本使用 nodes.repair-item / nodes.repair-amount，现统一为
        // nodes.repair-cost-item / nodes.repair-amount-per-cost。仅在未部署新键时迁移。
        // 旧键值读取完毕后会立即从 YAML 中删除并落盘，使第二次启动起不再残留旧键、不再重复打印警告。
        boolean hasLegacyRepairItem = settingsYaml.contains("nodes.repair-item");
        boolean hasLegacyRepairAmount = settingsYaml.contains("nodes.repair-amount");
        if (hasLegacyRepairItem || hasLegacyRepairAmount) {
            // 1) 修复物品：仅在未部署新键时迁移
            if (hasLegacyRepairItem && !settingsYaml.contains("nodes.repair-cost-item")) {
                String legacyRepairItem = settingsYaml.getString("nodes.repair-item");
                Material legacyRepairMat = legacyRepairItem == null ? null : Material.getMaterial(legacyRepairItem);
                if (legacyRepairMat != null) {
                    activeConfig.setRepairCostItem(legacyRepairMat);
                }
                plugin.getLogger().warning("检测到旧配置键 nodes.repair-item，已自动迁移到 nodes.repair-cost-item");
                // 新键原本缺失，补写入迁移后的值，避免删除旧键后配置丢失
                settingsYaml.set("nodes.repair-cost-item", activeConfig.getRepairCostItem().name());
            } else if (hasLegacyRepairItem) {
                plugin.getLogger().warning("检测到已废弃的配置键 nodes.repair-item，当前使用 nodes.repair-cost-item，该旧键将被忽略");
            }

            // 2) 单次修复量：同样仅在未部署新键时迁移
            if (hasLegacyRepairAmount && !settingsYaml.contains("nodes.repair-amount-per-cost")) {
                activeConfig.setRepairAmountPerCost(settingsYaml.getDouble("nodes.repair-amount"));
                settingsYaml.set("nodes.repair-amount-per-cost", activeConfig.getRepairAmountPerCost());
            }

            // 3) 旧键值已读取完毕（或确认被忽略），从 YAML 中删除旧键并写回磁盘，
            //    使第二次启动起不再残留旧键、不再重复打印警告。
            //    已存在的新键 nodes.repair-cost-item / nodes.repair-amount-per-cost 不受影响。
            settingsYaml.set("nodes.repair-item", null);
            settingsYaml.set("nodes.repair-amount", null);
            try {
                settingsYaml.save(settingsFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to remove legacy keys from game-settings.yml: " + e.getMessage());
            }
        }

        // 节点增益效果：字符串列表，格式 "效果名:等级"（等级为 amplifier，0 = I 级）
        List<String> buffEffectList = settingsYaml.getStringList("nodes.buff-effects");
        if (!buffEffectList.isEmpty()) {
            Map<PotionEffectType, Integer> parsedBuffs = new LinkedHashMap<>();
            for (String raw : buffEffectList) {
                if (raw == null || raw.isBlank()) continue;
                String[] parts = raw.split(":", 2);
                if (parts.length != 2) {
                    plugin.getLogger().warning("Invalid nodes.buff-effects entry (expected \"EFFECT:AMPLIFIER\"): " + raw);
                    continue;
                }
                PotionEffectType effectType = PotionEffectType.getByName(parts[0].trim().toUpperCase());
                if (effectType == null) {
                    plugin.getLogger().warning("Unknown potion effect: " + parts[0].trim() + ", skipping");
                    continue;
                }
                try {
                    parsedBuffs.put(effectType, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid amplifier in nodes.buff-effects entry: " + raw);
                }
            }
            if (!parsedBuffs.isEmpty()) {
                activeConfig.getNodeBuffEffects().clear();
                activeConfig.getNodeBuffEffects().putAll(parsedBuffs);
            }
        }
        if (settingsYaml.contains("nodes.buff-refresh-ticks")) activeConfig.setNodeBuffRefreshTicks(settingsYaml.getInt("nodes.buff-refresh-ticks"));
        if (settingsYaml.contains("nodes.buff-stack-cap")) activeConfig.setNodeBuffStackCap(settingsYaml.getInt("nodes.buff-stack-cap"));
        if (settingsYaml.contains("nodes.buff-duration-ticks")) activeConfig.setNodeBuffDurationTicks(settingsYaml.getInt("nodes.buff-duration-ticks"));

        if (settingsYaml.contains("spawning.spawn-radius-min")) activeConfig.setSpawnRadiusMin(settingsYaml.getInt("spawning.spawn-radius-min"));
        if (settingsYaml.contains("spawning.spawn-radius-max")) activeConfig.setSpawnRadiusMax(settingsYaml.getInt("spawning.spawn-radius-max"));
        if (settingsYaml.contains("spawning.global-mob-cap")) activeConfig.setGlobalMobCap(settingsYaml.getInt("spawning.global-mob-cap"));
        if (settingsYaml.contains("spawning.mob-cap-per-player")) activeConfig.setMobCapPerPlayer(settingsYaml.getInt("spawning.mob-cap-per-player"));
        if (settingsYaml.contains("spawning.spawn-y-offset")) activeConfig.setSpawnYOffset(settingsYaml.getInt("spawning.spawn-y-offset"));

        String fireballMat = settingsYaml.getString("fireball.material");
        if (fireballMat != null) {
            Material fireballMatResult = Material.getMaterial(fireballMat);
            if (fireballMatResult != null) {
                activeConfig.setFireballMaterial(fireballMatResult);
            } else {
                plugin.getLogger().warning("Unknown material: " + fireballMat + ", keeping default");
            }
        }
        if (settingsYaml.contains("fireball.speed")) activeConfig.setFireballSpeed(settingsYaml.getDouble("fireball.speed"));
        if (settingsYaml.contains("fireball.damage")) activeConfig.setFireballDamage(settingsYaml.getDouble("fireball.damage"));
        if (settingsYaml.contains("fireball.explosion-radius")) activeConfig.setFireballExplosionRadius(settingsYaml.getDouble("fireball.explosion-radius"));
        if (settingsYaml.contains("fireball.cooldown-ticks")) activeConfig.setFireballCooldownTicks(settingsYaml.getInt("fireball.cooldown-ticks"));
        if (settingsYaml.contains("fireball.max-travel-ticks")) activeConfig.setFireballMaxTravelTicks(settingsYaml.getInt("fireball.max-travel-ticks"));
        if (settingsYaml.contains("fireball.knockback")) activeConfig.setFireballKnockback(settingsYaml.getDouble("fireball.knockback"));
        if (settingsYaml.contains("fireball.proximity-fuse")) activeConfig.setFireballProximityFuse(settingsYaml.getDouble("fireball.proximity-fuse"));
        if (settingsYaml.contains("fireball.item-name")) activeConfig.setFireballItemName(settingsYaml.getString("fireball.item-name"));

        // ---- 村民商店货币掉落 ----
        if (settingsYaml.contains("shop.currency-drop-chance")) activeConfig.setCurrencyDropChance(settingsYaml.getDouble("shop.currency-drop-chance"));
        if (settingsYaml.contains("shop.currency-drop-amount")) activeConfig.setCurrencyDropAmount(settingsYaml.getInt("shop.currency-drop-amount"));

        // ---- 开局初始套装 (starter-kit, STORY-1.2) ----
        // 缺键 = 旧配置文件：保持 GameConfig 字段默认值（enabled=true + 石剑/面包/火把），不覆盖
        if (settingsYaml.contains("starter-kit.enabled")) {
            activeConfig.setStarterKitEnabled(settingsYaml.getBoolean("starter-kit.enabled"));
        }
        // getMapList 不存在时返回空列表；仅当键存在时才整体替换默认列表，
        // 否则旧配置文件（无 starter-kit 节）会被清空成空套装
        if (settingsYaml.isSet("starter-kit.items")) {
            List<GameConfig.StarterKitItem> parsedKit = new ArrayList<>();
            for (Map<?, ?> map : settingsYaml.getMapList("starter-kit.items")) {
                Object materialRaw = map.get("material");
                if (!(materialRaw instanceof String materialName) || materialName.isBlank()) {
                    plugin.getLogger().warning("Invalid starter-kit item (missing material), skipping");
                    continue;
                }
                Material material = Material.getMaterial(materialName.trim().toUpperCase());
                if (material == null || !material.isItem()) {
                    plugin.getLogger().warning("Unknown starter-kit material: " + materialName + ", skipping");
                    continue;
                }
                Object amountRaw = map.get("amount");
                int amount = (amountRaw instanceof Number number) ? number.intValue() : 1;
                parsedKit.add(new GameConfig.StarterKitItem(material, Math.max(1, amount)));
            }
            activeConfig.setStarterKitItems(parsedKit);
        }

        ConfigurationSection monsterSection = settingsYaml.getConfigurationSection("monsters");
        if (monsterSection != null) {
            for (String key : monsterSection.getKeys(false)) {
                GameConfig.MonsterConfig mc = activeConfig.getMonsters().get(key);
                if (mc == null) continue;
                String name = settingsYaml.getString("monsters." + key + ".display-egg");
                if (name != null) {
                    Material eggMat = Material.getMaterial(name);
                    if (eggMat != null) {
                        mc.setDisplayEgg(eggMat);
                    } else {
                        plugin.getLogger().warning("Unknown material: " + name + ", keeping default");
                    }
                }
                if (settingsYaml.contains("monsters." + key + ".health")) mc.setHealth(settingsYaml.getDouble("monsters." + key + ".health"));
                if (settingsYaml.contains("monsters." + key + ".speed")) mc.setSpeed(settingsYaml.getDouble("monsters." + key + ".speed"));
                if (settingsYaml.contains("monsters." + key + ".hook-velocity")) mc.setHookVelocity(settingsYaml.getDouble("monsters." + key + ".hook-velocity"));
                if (settingsYaml.contains("monsters." + key + ".display-name")) mc.setDisplayName(settingsYaml.getString("monsters." + key + ".display-name"));
                if (settingsYaml.contains("monsters." + key + ".explode-damage-multiplier")) mc.setExplodeDamageMultiplier(settingsYaml.getDouble("monsters." + key + ".explode-damage-multiplier"));
                if (settingsYaml.contains("monsters." + key + ".entity-explosion")) mc.setEntityExplosion(settingsYaml.getBoolean("monsters." + key + ".entity-explosion"));
                if (settingsYaml.contains("monsters." + key + ".cooldown-ticks")) mc.setCooldownTicks(settingsYaml.getInt("monsters." + key + ".cooldown-ticks"));
                if (settingsYaml.contains("monsters." + key + ".stun-duration-ticks")) mc.setStunDurationTicks(settingsYaml.getInt("monsters." + key + ".stun-duration-ticks"));
                if (settingsYaml.contains("monsters." + key + ".airdrop-mob-count")) mc.setAirdropMobCount(settingsYaml.getInt("monsters." + key + ".airdrop-mob-count"));
                if (settingsYaml.contains("monsters." + key + ".airdrop-mob-type")) mc.setAirdropMobType(settingsYaml.getString("monsters." + key + ".airdrop-mob-type"));
            }
        }

        ConfigurationSection waveSection = settingsYaml.getConfigurationSection("waves");
        if (waveSection != null) {
            if (settingsYaml.contains("waves.default.total-mobs")) {
                activeConfig.getDefaultWave().setTotalMobs(settingsYaml.getInt("waves.default.total-mobs"));
            }
            ConfigurationSection defaultComps = settingsYaml.getConfigurationSection("waves.default.compositions");
            if (defaultComps != null) {
                for (String key : defaultComps.getKeys(false)) {
                    GameConfig.MobComposition comp = activeConfig.getDefaultWave().getCompositions().get(key);
                    if (comp == null) continue;
                    if (settingsYaml.contains("waves.default.compositions." + key + ".count"))
                        comp.setCount(settingsYaml.getInt("waves.default.compositions." + key + ".count"));
                    if (settingsYaml.contains("waves.default.compositions." + key + ".weight"))
                        comp.setWeight(settingsYaml.getInt("waves.default.compositions." + key + ".weight"));
                }
            }
            for (String waveKey : waveSection.getKeys(false)) {
                if (waveKey.equals("default")) continue;
                try {
                    int waveNum = Integer.parseInt(waveKey);
                    GameConfig.WaveConfig wc = activeConfig.getWaves().get(waveNum);
                    if (wc == null) continue;
                    if (settingsYaml.contains("waves." + waveKey + ".total-mobs"))
                        wc.setTotalMobs(settingsYaml.getInt("waves." + waveKey + ".total-mobs"));
                    ConfigurationSection comps = settingsYaml.getConfigurationSection("waves." + waveKey + ".compositions");
                    if (comps != null) {
                        for (String mobKey : comps.getKeys(false)) {
                            GameConfig.MobComposition comp = wc.getCompositions().get(mobKey);
                            if (comp == null) continue;
                            if (settingsYaml.contains("waves." + waveKey + ".compositions." + mobKey + ".count"))
                                comp.setCount(settingsYaml.getInt("waves." + waveKey + ".compositions." + mobKey + ".count"));
                            if (settingsYaml.contains("waves." + waveKey + ".compositions." + mobKey + ".weight"))
                                comp.setWeight(settingsYaml.getInt("waves." + waveKey + ".compositions." + mobKey + ".weight"));
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        loadMonstersConfig();

        lastSavedSnapshot = activeConfig.snapshot();
    }

    /**
     * 从 monsters-config.yml 加载所有怪物的完整配置（模型、装备、伤害等扩展字段）。
     * game-settings.yml 中的基础字段优先加载以实现向后兼容。
     */
    private void loadMonstersConfig() {
        if (!monstersConfigFile.exists()) return;
        monstersConfigYaml = YamlConfiguration.loadConfiguration(monstersConfigFile);

        for (String key : activeConfig.getMonsters().keySet()) {
            GameConfig.MonsterConfig mc = activeConfig.getMonsters().get(key);
            if (mc == null) continue;

            String entityTypeName = monstersConfigYaml.getString(key + ".entity-type");
            if (entityTypeName != null && !entityTypeName.isEmpty()) {
                try {
                    EntityType parsedType = EntityType.valueOf(entityTypeName.trim().toUpperCase());
                    // 合法名称不代表可生成：PLAYER / ITEM / ARROW 等类型无法 spawnEntity，会中断刷怪任务。
                    // 此处只接受实体类是 Mob 子类的类型，不满足则保留默认值。
                    Class<?> entityClass = parsedType.getEntityClass();
                    if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) {
                        plugin.getLogger().warning("Entity type " + parsedType.name() + " for monster " + key
                                + " is not a spawnable mob, keeping default (" + mc.getEntityType() + ")");
                    } else {
                        mc.setEntityType(parsedType);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown entity type: " + entityTypeName
                            + " for monster " + key + ", keeping default (" + mc.getEntityType() + ")");
                }
            }
            if (monstersConfigYaml.contains(key + ".base-damage")) {
                mc.setBaseDamage(monstersConfigYaml.getDouble(key + ".base-damage"));
            }
            if (monstersConfigYaml.contains(key + ".itemsadder-id")) {
                mc.setItemsAdderId(monstersConfigYaml.getString(key + ".itemsadder-id"));
            }
            if (monstersConfigYaml.contains(key + ".custom-model-data")) {
                mc.setCustomModelData(monstersConfigYaml.getInt(key + ".custom-model-data"));
            }
            String mainHand = monstersConfigYaml.getString(key + ".main-hand");
            if (mainHand != null && !mainHand.isEmpty()) {
                Material mainHandMat = Material.getMaterial(mainHand);
                if (mainHandMat != null) { mc.setMainHandItem(mainHandMat); }
                else { plugin.getLogger().warning("Unknown material: " + mainHand + ", keeping default"); }
            }
            String offHand = monstersConfigYaml.getString(key + ".off-hand");
            if (offHand != null && !offHand.isEmpty()) {
                Material offHandMat = Material.getMaterial(offHand);
                if (offHandMat != null) { mc.setOffHandItem(offHandMat); }
                else { plugin.getLogger().warning("Unknown material: " + offHand + ", keeping default"); }
            }
            String helmet = monstersConfigYaml.getString(key + ".helmet");
            if (helmet != null && !helmet.isEmpty()) {
                Material helmetMat = Material.getMaterial(helmet);
                if (helmetMat != null) { mc.setHelmet(helmetMat); }
                else { plugin.getLogger().warning("Unknown material: " + helmet + ", keeping default"); }
            }
            String chestplate = monstersConfigYaml.getString(key + ".chestplate");
            if (chestplate != null && !chestplate.isEmpty()) {
                Material chestplateMat = Material.getMaterial(chestplate);
                if (chestplateMat != null) { mc.setChestplate(chestplateMat); }
                else { plugin.getLogger().warning("Unknown material: " + chestplate + ", keeping default"); }
            }
            String leggings = monstersConfigYaml.getString(key + ".leggings");
            if (leggings != null && !leggings.isEmpty()) {
                Material leggingsMat = Material.getMaterial(leggings);
                if (leggingsMat != null) { mc.setLeggings(leggingsMat); }
                else { plugin.getLogger().warning("Unknown material: " + leggings + ", keeping default"); }
            }
            String boots = monstersConfigYaml.getString(key + ".boots");
            if (boots != null && !boots.isEmpty()) {
                Material bootsMat = Material.getMaterial(boots);
                if (bootsMat != null) { mc.setBoots(bootsMat); }
                else { plugin.getLogger().warning("Unknown material: " + boots + ", keeping default"); }
            }
        }
    }

    public GameConfig snapshotForPreview() {
        return activeConfig.snapshot();
    }

    public void revertAll() {
        if (lastSavedSnapshot != null) {
            this.activeConfig = lastSavedSnapshot.snapshot();
        }
    }
}