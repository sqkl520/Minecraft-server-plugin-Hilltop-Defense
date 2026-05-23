package com.hilltopvillage.core;

import com.hilltopvillage.ai.ExplodeBeetleGoal;
import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.ai.AirdropSpawnGoal;
import com.hilltopvillage.config.GameConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class WaveManager {

    private final GameManager gameManager;
    private final List<LivingEntity> activeMobs;
    private final Random random;

    public WaveManager(GameManager gameManager) {
        this.gameManager = gameManager;
        this.activeMobs = new CopyOnWriteArrayList<>();
        this.random = new Random();
    }

    public void spawnWave(int waveNumber) {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.WaveConfig waveConfig = cfg.getWaveConfig(waveNumber);

        int globalCap = cfg.getGlobalMobCap();
        if (activeMobs.size() >= globalCap) return;

        int remaining = waveConfig.getTotalMobs() * gameManager.getPlayers().size();
        int mobsPerPlayerCap = cfg.getMobCapPerPlayer();
        remaining = Math.min(remaining, mobsPerPlayerCap * gameManager.getPlayers().size());
        remaining = Math.min(remaining, globalCap - activeMobs.size());

        int totalWeight = waveConfig.getTotalWeight();
        if (totalWeight <= 0) {
            spawnDefaultWave(waveNumber);
            return;
        }

        for (int i = 0; i < remaining; i++) {
            String selectedType = weightSelect(waveConfig, totalWeight);
            if (selectedType == null) continue;

            Location spawnLoc = getRandomSpawnLocation();
            if (spawnLoc == null) continue;

            spawnCustomMob(selectedType, spawnLoc);
        }
    }

    private String weightSelect(GameConfig.WaveConfig waveConfig, int totalWeight) {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Map.Entry<String, GameConfig.MobComposition> entry : waveConfig.getCompositions().entrySet()) {
            cumulative += entry.getValue().getWeight();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return waveConfig.getCompositions().keySet().iterator().next();
    }

    private void spawnDefaultWave(int waveNumber) {
        World world = gameManager.getGameWorld();
        int count = 5 + waveNumber * 2;

        for (int i = 0; i < count; i++) {
            Location loc = getRandomSpawnLocation();
            if (loc == null) continue;
            world.spawnEntity(loc, EntityType.ZOMBIE);
        }
    }

    private void spawnCustomMob(String type, Location location) {
        World world = gameManager.getGameWorld();

        switch (type.toLowerCase()) {
            case "explode-beetle":
                spawnExplodeBeetle(location);
                break;
            case "hook-claw-hunter":
                spawnHookClawHunter(location);
                break;
            case "flying-dropper":
                spawnFlyingDropper(location);
                break;
            default:
                try {
                    EntityType entityType = EntityType.valueOf(type.toUpperCase());
                    Entity entity = world.spawnEntity(location, entityType);
                    if (entity instanceof LivingEntity) {
                        activeMobs.add((LivingEntity) entity);
                    }
                } catch (IllegalArgumentException e) {
                    gameManager.getPlugin().getLogger().warning("Unknown mob type: " + type);
                }
        }
    }

    /**
     * 根据 MonsterConfig 配置为怪物装备武器、副手物品和盔甲。
     * 同时应用 ItemsAdder 模型（如果配置了）和自定义模型数据。
     *
     * @param entity 目标实体
     * @param mc     怪物配置（含武器/盔甲/模型信息）
     */
    private void applyMonsterEquipment(LivingEntity entity, GameConfig.MonsterConfig mc) {
        if (mc == null) return;
        EntityEquipment equip = entity.getEquipment();
        if (equip == null) return;

        // 主手武器
        Material mainHand = mc.getMainHandItem();
        if (mainHand != null && mainHand != Material.AIR) {
            equip.setItem(EquipmentSlot.HAND, new ItemStack(mainHand));
            equip.setDropChance(EquipmentSlot.HAND, 0.05f);
        }

        // 副手物品（如盾牌）
        Material offHand = mc.getOffHandItem();
        if (offHand != null && offHand != Material.AIR) {
            equip.setItem(EquipmentSlot.OFF_HAND, new ItemStack(offHand));
            equip.setDropChance(EquipmentSlot.OFF_HAND, 0.05f);
        }

        // 头盔
        Material helmet = mc.getHelmet();
        if (helmet != null && helmet != Material.AIR) {
            equip.setItem(EquipmentSlot.HEAD, new ItemStack(helmet));
            equip.setDropChance(EquipmentSlot.HEAD, 0.05f);
        }

        // 胸甲
        Material chest = mc.getChestplate();
        if (chest != null && chest != Material.AIR) {
            equip.setItem(EquipmentSlot.CHEST, new ItemStack(chest));
            equip.setDropChance(EquipmentSlot.CHEST, 0.05f);
        }

        // 护腿
        Material legs = mc.getLeggings();
        if (legs != null && legs != Material.AIR) {
            equip.setItem(EquipmentSlot.LEGS, new ItemStack(legs));
            equip.setDropChance(EquipmentSlot.LEGS, 0.05f);
        }

        // 靴子
        Material boots = mc.getBoots();
        if (boots != null && boots != Material.AIR) {
            equip.setItem(EquipmentSlot.FEET, new ItemStack(boots));
            equip.setDropChance(EquipmentSlot.FEET, 0.05f);
        }
    }

    private void spawnExplodeBeetle(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("explode-beetle");

        CaveSpider spider = (CaveSpider) world.spawnEntity(location, EntityType.CAVE_SPIDER);
        double health = mc != null ? mc.getHealth() : 40.0;
        spider.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
        spider.setHealth(health);

        String name = mc != null ? mc.getDisplayName() : "&c自爆甲虫";
        spider.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        spider.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.35;
        spider.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(spider, mc);

        Bukkit.getMobGoals().removeAllGoals(spider);
        Bukkit.getMobGoals().addGoal(spider, 1, new ExplodeBeetleGoal(spider, gameManager));

        activeMobs.add(spider);
    }

    private void spawnHookClawHunter(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("hook-claw-hunter");

        Skeleton skeleton = (Skeleton) world.spawnEntity(location, EntityType.SKELETON);
        double health = mc != null ? mc.getHealth() : 60.0;
        skeleton.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
        skeleton.setHealth(health);

        String name = mc != null ? mc.getDisplayName() : "&5钩爪猎手";
        skeleton.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        skeleton.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.25;
        skeleton.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(skeleton, mc);

        Bukkit.getMobGoals().removeAllGoals(skeleton);
        Bukkit.getMobGoals().addGoal(skeleton, 1, new HookClawHunterGoal(skeleton, gameManager));

        activeMobs.add(skeleton);
    }

    private void spawnFlyingDropper(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("flying-dropper");

        Phantom phantom = (Phantom) world.spawnEntity(location, EntityType.PHANTOM);
        double health = mc != null ? mc.getHealth() : 80.0;
        phantom.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
        phantom.setHealth(health);

        String name = mc != null ? mc.getDisplayName() : "&f飞行抛投者";
        phantom.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        phantom.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.3;
        phantom.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(phantom, mc);

        Bukkit.getMobGoals().removeAllGoals(phantom);
        Bukkit.getMobGoals().addGoal(phantom, 1, new AirdropSpawnGoal(phantom, gameManager));

        activeMobs.add(phantom);
    }

    private Location getRandomSpawnLocation() {
        // 优先使用管理员定义的精确定点
        if (gameManager.getAdminDeployManager().hasFixedSpawnPoints()) {
            List<Location> fixedPoints = gameManager.getAdminDeployManager().getSpawnPoints();
            return fixedPoints.get(random.nextInt(fixedPoints.size())).clone();
        }

        // 回退到核心周围随机生成
        World world = gameManager.getGameWorld();
        Location core = gameManager.getCoreLocation();

        int minRadius = gameManager.getPlugin().getConfig().getInt("spawning.spawn-radius-min", 20);
        int maxRadius = gameManager.getPlugin().getConfig().getInt("spawning.spawn-radius-max", 40);

        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);

            double x = core.getX() + Math.cos(angle) * distance;
            double z = core.getZ() + Math.sin(angle) * distance;

            int yOffset = gameManager.getPlugin().getConfig().getInt("spawning.spawn-y-offset", 0);
            Location loc = new Location(world, x, 0, z);
            loc.setY(world.getHighestBlockYAt(loc) + yOffset + 1);

            if (loc.getY() < core.getY()) {
                return loc;
            }
        }
        return null;
    }

    public void onMobDeath(LivingEntity mob) {
        activeMobs.remove(mob);
    }

    public void clearAllMobs() {
        for (LivingEntity mob : activeMobs) {
            if (mob != null && mob.isValid()) {
                mob.remove();
            }
        }
        activeMobs.clear();
    }

    public boolean isWaveCleared() {
        return activeMobs.isEmpty();
    }

    public List<LivingEntity> getActiveMobs() {
        return activeMobs;
    }

    public int getRemainingMobCount() {
        return activeMobs.size();
    }
}
