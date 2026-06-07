package com.hilltopvillage.config;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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

        settingsYaml.set("hammer.material", activeConfig.getHammerMaterial().name());
        settingsYaml.set("hammer.itemsadder-id", activeConfig.getHammerItemsAdderId());
        settingsYaml.set("hammer.custom-model-data", activeConfig.getHammerCustomModelData());
        settingsYaml.set("hammer.use-itemsadder", activeConfig.isHammerUseItemsAdder());
        settingsYaml.set("hammer.base-damage", activeConfig.getHammerBaseDamage());
        settingsYaml.set("hammer.effect-radius", activeConfig.getHammerEffectRadius());
        settingsYaml.set("hammer.aftershock-ticks", activeConfig.getHammerAftershockTicks());
        settingsYaml.set("hammer.smash-timeout-ticks", activeConfig.getHammerSmashTimeoutTicks());
        settingsYaml.set("hammer.visual-display-ticks", activeConfig.getHammerVisualDisplayTicks());
        settingsYaml.set("hammer.tier.low.charge", activeConfig.getHammerLowCharge());
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

        List<String> nodeTypes = new ArrayList<>();
        for (Material m : activeConfig.getNodeBlockTypes()) {
            nodeTypes.add(m.name());
        }
        settingsYaml.set("nodes.block-types", nodeTypes);
        settingsYaml.set("nodes.base-health", activeConfig.getNodeBaseHealth());
        settingsYaml.set("nodes.repair-item", activeConfig.getNodeRepairItem().name());
        settingsYaml.set("nodes.repair-amount", activeConfig.getNodeRepairAmount());
        settingsYaml.set("nodes.buff-radius", activeConfig.getNodeBuffRadius());
        settingsYaml.set("nodes.self-destruct-multiplier", activeConfig.getNodeSelfDestructMultiplier());

        settingsYaml.set("spawning.spawn-radius-min", activeConfig.getSpawnRadiusMin());
        settingsYaml.set("spawning.spawn-radius-max", activeConfig.getSpawnRadiusMax());
        settingsYaml.set("spawning.global-mob-cap", activeConfig.getGlobalMobCap());
        settingsYaml.set("spawning.mob-cap-per-player", activeConfig.getMobCapPerPlayer());

        // ---- 烈焰蛋配置 ----
        settingsYaml.set("fireball.material", activeConfig.getFireballMaterial().name());
        settingsYaml.set("fireball.speed", activeConfig.getFireballSpeed());
        settingsYaml.set("fireball.damage", activeConfig.getFireballDamage());
        settingsYaml.set("fireball.explosion-radius", activeConfig.getFireballExplosionRadius());
        settingsYaml.set("fireball.cooldown-ticks", activeConfig.getFireballCooldownTicks());
        settingsYaml.set("fireball.max-travel-ticks", activeConfig.getFireballMaxTravelTicks());
        settingsYaml.set("fireball.knockback", activeConfig.getFireballKnockback());

        settingsYaml.set("monsters", null);
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
            settingsYaml.set(path + "cooldown-ticks", mc.getCooldownTicks());
            settingsYaml.set(path + "stun-duration-ticks", mc.getStunDurationTicks());
            settingsYaml.set(path + "airdrop-mob-count", mc.getAirdropMobCount());
        }

        settingsYaml.set("waves", null);
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
        monstersConfigYaml.set("explode-beetle", null);
        monstersConfigYaml.set("hook-claw-hunter", null);
        monstersConfigYaml.set("flying-dropper", null);
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            String path = key + ".";
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

        String hammerMat = settingsYaml.getString("hammer.material");
        if (hammerMat != null) activeConfig.setHammerMaterial(Material.getMaterial(hammerMat));
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
        if (settingsYaml.contains("hammer.tier.low.charge")) activeConfig.setHammerLowCharge(settingsYaml.getDouble("hammer.tier.low.charge"));
        if (settingsYaml.contains("hammer.tier.low.damage")) activeConfig.setHammerLowDamage(settingsYaml.getDouble("hammer.tier.low.damage"));
        if (settingsYaml.contains("hammer.tier.low.radius")) activeConfig.setHammerLowRadius(settingsYaml.getDouble("hammer.tier.low.radius"));
        if (settingsYaml.contains("hammer.tier.medium.charge")) activeConfig.setHammerMediumCharge(settingsYaml.getDouble("hammer.tier.medium.charge"));
        if (settingsYaml.contains("hammer.tier.medium.damage")) activeConfig.setHammerMediumDamage(settingsYaml.getDouble("hammer.tier.medium.damage"));
        if (settingsYaml.contains("hammer.tier.medium.radius")) activeConfig.setHammerMediumRadius(settingsYaml.getDouble("hammer.tier.medium.radius"));
        if (settingsYaml.contains("hammer.tier.high.charge")) activeConfig.setHammerHighCharge(settingsYaml.getDouble("hammer.tier.high.charge"));
        if (settingsYaml.contains("hammer.tier.high.damage")) activeConfig.setHammerHighDamage(settingsYaml.getDouble("hammer.tier.high.damage"));
        if (settingsYaml.contains("hammer.tier.high.radius")) activeConfig.setHammerHighRadius(settingsYaml.getDouble("hammer.tier.high.radius"));

        List<String> nodeTypes = settingsYaml.getStringList("nodes.block-types");
        if (!nodeTypes.isEmpty()) {
            List<Material> materials = new ArrayList<>();
            for (String name : nodeTypes) {
                Material m = Material.getMaterial(name);
                if (m != null) materials.add(m);
            }
            activeConfig.setNodeBlockTypes(materials);
        }
        if (settingsYaml.contains("nodes.base-health")) activeConfig.setNodeBaseHealth(settingsYaml.getDouble("nodes.base-health"));
        String repairItem = settingsYaml.getString("nodes.repair-item");
        if (repairItem != null) activeConfig.setNodeRepairItem(Material.getMaterial(repairItem));
        if (settingsYaml.contains("nodes.repair-amount")) activeConfig.setNodeRepairAmount(settingsYaml.getDouble("nodes.repair-amount"));
        if (settingsYaml.contains("nodes.buff-radius")) activeConfig.setNodeBuffRadius(settingsYaml.getDouble("nodes.buff-radius"));
        if (settingsYaml.contains("nodes.self-destruct-multiplier")) activeConfig.setNodeSelfDestructMultiplier(settingsYaml.getDouble("nodes.self-destruct-multiplier"));

        if (settingsYaml.contains("spawning.spawn-radius-min")) activeConfig.setSpawnRadiusMin(settingsYaml.getInt("spawning.spawn-radius-min"));
        if (settingsYaml.contains("spawning.spawn-radius-max")) activeConfig.setSpawnRadiusMax(settingsYaml.getInt("spawning.spawn-radius-max"));
        if (settingsYaml.contains("spawning.global-mob-cap")) activeConfig.setGlobalMobCap(settingsYaml.getInt("spawning.global-mob-cap"));
        if (settingsYaml.contains("spawning.mob-cap-per-player")) activeConfig.setMobCapPerPlayer(settingsYaml.getInt("spawning.mob-cap-per-player"));

        String fireballMat = settingsYaml.getString("fireball.material");
        if (fireballMat != null) activeConfig.setFireballMaterial(Material.getMaterial(fireballMat));
        if (settingsYaml.contains("fireball.speed")) activeConfig.setFireballSpeed(settingsYaml.getDouble("fireball.speed"));
        if (settingsYaml.contains("fireball.damage")) activeConfig.setFireballDamage(settingsYaml.getDouble("fireball.damage"));
        if (settingsYaml.contains("fireball.explosion-radius")) activeConfig.setFireballExplosionRadius(settingsYaml.getDouble("fireball.explosion-radius"));
        if (settingsYaml.contains("fireball.cooldown-ticks")) activeConfig.setFireballCooldownTicks(settingsYaml.getInt("fireball.cooldown-ticks"));
        if (settingsYaml.contains("fireball.max-travel-ticks")) activeConfig.setFireballMaxTravelTicks(settingsYaml.getInt("fireball.max-travel-ticks"));
        if (settingsYaml.contains("fireball.knockback")) activeConfig.setFireballKnockback(settingsYaml.getDouble("fireball.knockback"));

        ConfigurationSection monsterSection = settingsYaml.getConfigurationSection("monsters");
        if (monsterSection != null) {
            for (String key : monsterSection.getKeys(false)) {
                GameConfig.MonsterConfig mc = activeConfig.getMonsters().get(key);
                if (mc == null) continue;
                String name = settingsYaml.getString("monsters." + key + ".display-egg");
                if (name != null) mc.setDisplayEgg(Material.getMaterial(name));
                if (settingsYaml.contains("monsters." + key + ".health")) mc.setHealth(settingsYaml.getDouble("monsters." + key + ".health"));
                if (settingsYaml.contains("monsters." + key + ".speed")) mc.setSpeed(settingsYaml.getDouble("monsters." + key + ".speed"));
                if (settingsYaml.contains("monsters." + key + ".hook-velocity")) mc.setHookVelocity(settingsYaml.getDouble("monsters." + key + ".hook-velocity"));
                if (settingsYaml.contains("monsters." + key + ".display-name")) mc.setDisplayName(settingsYaml.getString("monsters." + key + ".display-name"));
                if (settingsYaml.contains("monsters." + key + ".explode-damage-multiplier")) mc.setExplodeDamageMultiplier(settingsYaml.getDouble("monsters." + key + ".explode-damage-multiplier"));
                if (settingsYaml.contains("monsters." + key + ".cooldown-ticks")) mc.setCooldownTicks(settingsYaml.getInt("monsters." + key + ".cooldown-ticks"));
                if (settingsYaml.contains("monsters." + key + ".stun-duration-ticks")) mc.setStunDurationTicks(settingsYaml.getInt("monsters." + key + ".stun-duration-ticks"));
                if (settingsYaml.contains("monsters." + key + ".airdrop-mob-count")) mc.setAirdropMobCount(settingsYaml.getInt("monsters." + key + ".airdrop-mob-count"));
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
                mc.setMainHandItem(Material.getMaterial(mainHand));
            }
            String offHand = monstersConfigYaml.getString(key + ".off-hand");
            if (offHand != null && !offHand.isEmpty()) {
                mc.setOffHandItem(Material.getMaterial(offHand));
            }
            String helmet = monstersConfigYaml.getString(key + ".helmet");
            if (helmet != null && !helmet.isEmpty()) {
                mc.setHelmet(Material.getMaterial(helmet));
            }
            String chestplate = monstersConfigYaml.getString(key + ".chestplate");
            if (chestplate != null && !chestplate.isEmpty()) {
                mc.setChestplate(Material.getMaterial(chestplate));
            }
            String leggings = monstersConfigYaml.getString(key + ".leggings");
            if (leggings != null && !leggings.isEmpty()) {
                mc.setLeggings(Material.getMaterial(leggings));
            }
            String boots = monstersConfigYaml.getString(key + ".boots");
            if (boots != null && !boots.isEmpty()) {
                mc.setBoots(Material.getMaterial(boots));
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