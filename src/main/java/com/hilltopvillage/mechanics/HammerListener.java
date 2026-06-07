package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class HammerListener implements Listener {

    private final GameManager gameManager;
    private final HilltopVillagePlugin plugin;
    private final String hammerName;
    private final Map<UUID, BossBar> chargeBars;
    private final Map<UUID, Integer> lastChargeTier;
    private final Map<UUID, Integer> lastBossBarTick;
    private final Set<UUID> recentlyLanded;

    public HammerListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.plugin = gameManager.getPlugin();
        this.hammerName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("hammer.item-name", "&6&l\u795e\u5723\u91cd\u9524"));
        this.chargeBars = new HashMap<>();
        this.lastChargeTier = new HashMap<>();
        this.lastBossBarTick = new HashMap<>();
        this.recentlyLanded = new HashSet<>();
    }

    /* ========== 模型系统 ========== */

    public ItemStack createHammer() {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        ItemStack hammer;

        // ItemsAdder 优先
        if (cfg.isHammerUseItemsAdder() && !cfg.getHammerItemsAdderId().isEmpty()) {
            hammer = createItemsAdderItem(cfg.getHammerItemsAdderId());
            if (hammer == null) {
                // 回退到普通物品
                hammer = createVanillaHammer(cfg);
            }
        } else {
            hammer = createVanillaHammer(cfg);
        }

        if (hammer == null) return null;

        ItemMeta meta = hammer.getItemMeta();
        meta.setDisplayName(hammerName);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "\u53f3\u952e\u7a7a\u6c14\u6fc0\u6d3b\u731b\u51fb\u72b6\u6001");
        lore.add(ChatColor.GRAY + "\u4ece\u9ad8\u5904\u843d\u4e0b\u89e6\u53d1\u5929\u964d\u6b63\u4e49");
        lore.add(ChatColor.GOLD + "\u9ad8\u5ea6\u8d8a\u9ad8\uff0c\u4f24\u5bb3\u8d8a\u5f3a");
        meta.setLore(lore);
        hammer.setItemMeta(meta);
        return hammer;
    }

    private ItemStack createVanillaHammer(GameConfig cfg) {
        ItemStack hammer = new ItemStack(cfg.getHammerMaterial());
        if (cfg.getHammerCustomModelData() > 0) {
            ItemMeta meta = hammer.getItemMeta();
            meta.setCustomModelData(cfg.getHammerCustomModelData());
            hammer.setItemMeta(meta);
        }
        return hammer;
    }

    private ItemStack createItemsAdderItem(String itemId) {
        try {
            // 使用反射避免编译期依赖 ItemsAdder
            Plugin iaPlugin = Bukkit.getPluginManager().getPlugin("ItemsAdder");
            if (iaPlugin != null && iaPlugin.isEnabled()) {
                Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object customStack = customStackClass.getMethod("getInstance", String.class).invoke(null, itemId);
                if (customStack != null) {
                    return (ItemStack) customStackClass.getMethod("getItemStack").invoke(customStack);
                }
            }
        } catch (Exception ignored) {
            plugin.getLogger().warning("ItemsAdder \u672a\u5b89\u88c5\u6216\u65e0\u6cd5\u83b7\u53d6\u7269\u54c1: " + itemId);
        }
        return null;
    }

    public boolean isHammer(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(hammerName);
    }

    /* ========== 右键蓄力激活 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isAlive()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHammer(item)) return;

        if (data.isSmashActive()) return;

        data.activateSmash(player.getLocation().getY(), gameManager.getGameWorld().getGameTime());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.5f);
        player.spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.05);

        // 复用或创建蓄力 BossBar
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar(ChatColor.YELLOW + "\u25a0 \u84c4\u529b\u5f00\u59cb", BarColor.YELLOW, BarStyle.SOLID);
            chargeBars.put(player.getUniqueId(), bar);
            bar.addPlayer(player);
        }
        bar.setTitle(ChatColor.YELLOW + "\u25a0 \u84c4\u529b\u5f00\u59cb");
        bar.setColor(BarColor.YELLOW);
        bar.setProgress(0.0);
        bar.setVisible(true);
        lastChargeTier.put(player.getUniqueId(), 0);

        event.setCancelled(true);
    }

    /* ========== 下落追踪 + BossBar更新 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isSmashActive()) return;

        double currentY = event.getTo().getY();
        Location to = event.getTo();
        boolean isOnGroundNow = isPlayerOnGround(player, to);

        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar != null) {
            int currentTick = Bukkit.getCurrentTick();
            Integer lastTick = lastBossBarTick.get(player.getUniqueId());
            if (lastTick != null && lastTick == currentTick) {
            } else {
                lastBossBarTick.put(player.getUniqueId(), currentTick);

                GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
                double startY = data.getSmashStartY();
                double fallDist = startY - currentY;
                double maxDist = cfg.getHammerHighCharge() * 1.5;
                double progress = Math.max(0.0, Math.min(1.0, fallDist / maxDist));

                bar.setProgress(progress);

                int tier = getHammerTier(cfg, fallDist);
                Integer lastTier = lastChargeTier.get(player.getUniqueId());
                if (lastTier == null || lastTier != tier) {
                    lastChargeTier.put(player.getUniqueId(), tier);
                    if (tier == 2) {
                        bar.setColor(BarColor.RED);
                        bar.setTitle(ChatColor.RED + "\u25a0 \u5929\u964d\u6b63\u4e49\uff01");
                    } else if (tier == 1) {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(ChatColor.GOLD + "\u25a0 \u731b\u51fb\u84c4\u529b\u4e2d...");
                    } else {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(ChatColor.YELLOW + "\u25a0 \u84c4\u529b\u5f00\u59cb");
                    }
                }
            }
        }

        if (!isOnGroundNow) return;
        if (recentlyLanded.contains(player.getUniqueId())) return;

        double fallDistance = startY(player, data, currentY);
        long currentTick = gameManager.getGameWorld().getGameTime();
        long tickLimit = gameManager.getConfigManager().getActiveConfig().getHammerSmashTimeoutTicks();

        if (currentY < data.getLastSmashY() - 0.01) {
            data.setSmashActivateTick(currentTick);
        }
        data.setLastSmashY(currentY);

        if (currentTick - data.getSmashActivateTick() > tickLimit) {
            data.deactivateSmash();
            removeChargeBar(player);
            return;
        }

        if (fallDistance >= 1.0) {
            executeSmash(player, data, fallDistance);
        } else {
            data.deactivateSmash();
            removeChargeBar(player);
        }
    }

    private double startY(Player player, PlayerData data, double currentY) {
        return data.getSmashStartY() - currentY;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        Player player = (Player) event.getEntity();
        PlayerData data = gameManager.getPlayerData(player);
        if (data == null) return;

        if (data.isSmashActive()) {
            event.setCancelled(true);
        }
    }

    /* ========== 猛击执行 ========== */

    private void executeSmash(Player player, PlayerData data, double fallDistance) {
        data.deactivateSmash();
        recentlyLanded.add(player.getUniqueId());

        // 清除蓄力条
        removeChargeBar(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                recentlyLanded.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, 10L);

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        double finalDamage = getTierDamage(cfg, tier);
        double radius = getTierRadius(cfg, tier);

        // 弹起玩家自身
        player.setVelocity(new Vector(0, 0.8 + Math.min(fallDistance * 0.05, 1.2), 0));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f);

        // 伤害提示（汉化）
        String tierName = getTierDisplayName(tier);
        String actionBarMsg = ChatColor.GOLD + "\u25a0 \u731b\u51fb\uff01" + ChatColor.YELLOW + " [" + tierName + "] "
                + ChatColor.RED + String.format("%.1f", finalDamage) + " \u4f24\u5bb3";
        player.sendActionBar(actionBarMsg);

        // 范围伤害（包含玩家弹飞）
        applyAreaDamage(player, player.getLocation(), finalDamage, radius);
        applyAftershock(player.getLocation(), radius);
        spawnSmashEffect(player.getLocation(), fallDistance);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.8f);
    }

    /* ========== BossBar 管理 ========== */

    private void removeChargeBar(Player player) {
        BossBar bar = chargeBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.setVisible(false);
            bar.removeAll();
        }
        lastChargeTier.remove(player.getUniqueId());
        lastBossBarTick.remove(player.getUniqueId());
    }

    public void cleanupPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        removeChargeBar(player);
        recentlyLanded.remove(uuid);
    }

    /* ========== 群体攻击（AOE + 玩家击飞） ========== */

    private void applyAreaDamage(Player attacker, Location center, double damage, double radius) {
        World world = center.getWorld();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && entity.isValid() && entity != attacker);

        for (LivingEntity entity : nearby) {
            double distance = entity.getLocation().distance(center);
            double falloff = 1.0 - (distance / (radius * 1.2));
            falloff = Math.max(0.3, Math.min(1.0, falloff));
            double actualDamage = damage * falloff;

            Vector knockback = entity.getLocation().toVector().subtract(center.toVector()).normalize();

            if (entity instanceof Player) {
                // 击飞附近玩家（不造成伤害）
                knockback.setY(1.2 + falloff * 0.6);
                knockback.multiply(2.0);
                entity.setVelocity(knockback);
                ((Player) entity).playSound(entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 1.0f);
            } else {
                // 对怪物造成伤害 + 击退
                entity.damage(actualDamage);
                knockback.setY(0.4);
                knockback.multiply(1.5);
                entity.setVelocity(knockback);
            }
        }
    }

    private void applyAftershock(Location center, double radius) {
        int duration = gameManager.getConfigManager().getActiveConfig().getHammerAftershockTicks();
        int slownessLevel = 2;

        World world = center.getWorld();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && !(entity instanceof Player) && entity.isValid());

        for (LivingEntity entity : nearby) {
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOW, duration, slownessLevel, false, true, true));
        }
    }

    private void spawnSmashEffect(Location location, double fallDistance) {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        if (tier >= 2) {
            spawnHighTierEffect(location);
        } else if (tier >= 1) {
            spawnMediumTierEffect(location);
        } else {
            spawnLowTierEffect(location);
        }
    }

    /**
     * 低档猛击：轻量尘土 + 小范围烟雾环
     */
    private void spawnLowTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.1, 0);

        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 3, 0.8, 0.2, 0.8, 0.1);
        world.spawnParticle(Particle.CLOUD, center, 15, 1.2, 0.05, 1.2, 0.02);

        for (int i = 0; i < 12; i++) {
            double angle = (2 * Math.PI / 12) * i;
            Location ringLoc = center.clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ringLoc, 1, 0.1, 0.05, 0.1, 0.01);
        }

        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.15, 0), 20,
                1.2, 0.3, 1.2, 0.2, Material.STONE.createBlockData());
    }

    /**
     * 中档猛击：爆炸 + 双环冲击波 + 碎石 + 烟雾柱 + 火花
     */
    private void spawnMediumTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.15, 0);

        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 2, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 10, 1.5, 0.3, 1.5, 0.3);

        for (int i = 0; i < 35; i++) {
            double angle = (2 * Math.PI / 35) * i;
            double dist = 3.5;
            Location ringLoc = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            world.spawnParticle(Particle.CLOUD, ringLoc, 2, 0.2, 0.05, 0.2, 0.02);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ringLoc, 1, 0.1, 0.1, 0.1, 0.01);
        }

        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i;
            Location ringLoc = center.clone().add(Math.cos(angle) * 1.8, 0.05, Math.sin(angle) * 1.8);
            world.spawnParticle(Particle.FLAME, ringLoc, 1, 0.1, 0.1, 0.1, 0.02);
        }

        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 60,
                2.5, 0.5, 2.5, 0.3, Material.STONE.createBlockData());
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 30,
                2.0, 0.5, 2.0, 0.2, Material.COBBLESTONE.createBlockData());

        for (int y = 0; y < 5; y++) {
            double yOff = y * 0.4;
            double spread = 0.3 + y * 0.25;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, yOff, 0),
                    8, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center.clone().add(0, 1.5, 0),
                10, 0.8, 0.5, 0.8, 0.05);

        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 25, 1.0, 0.3, 1.0, 0.1);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.8, 0), 15, 0.6, 0.4, 0.6, 0.08);

        world.spawnParticle(Particle.REDSTONE, center, 40, 2.0, 0.05, 2.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 1.5f));
    }

    /**
     * 高档猛击：巨型爆炸 + 多环扩散 + 闪电 + 龙息 + 火焰旋涡
     */
    private void spawnHighTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.15, 0);

        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 5, 0.5, 0.5, 0.5, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 20, 2.5, 0.5, 2.5, 0.4);

        // 三层环形冲击波
        for (int ring = 0; ring < 3; ring++) {
            double dist = 2.0 + ring * 2.5;
            int count = 20 + ring * 15;
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI / count) * i;
                Location ringLoc = center.clone().add(Math.cos(angle) * dist, ring * 0.15, Math.sin(angle) * dist);
                world.spawnParticle(Particle.CLOUD, ringLoc, 2, 0.3, 0.1, 0.3, 0.03);
                world.spawnParticle(ring == 0 ? Particle.FLAME : Particle.CAMPFIRE_COSY_SMOKE,
                        ringLoc, 2, 0.2, 0.1, 0.2, 0.03);
            }
        }

        // 大量碎石
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.3, 0), 100,
                4.0, 0.8, 4.0, 0.4, Material.STONE.createBlockData());
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.3, 0), 60,
                3.5, 0.8, 3.5, 0.3, Material.DEEPSLATE.createBlockData());

        // 上扬烟雾柱 (更高更密)
        for (int y = 0; y < 8; y++) {
            double yOff = y * 0.5;
            double spread = 0.4 + y * 0.3;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, yOff, 0),
                    12, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center.clone().add(0, 2.5, 0),
                15, 1.2, 0.8, 1.2, 0.05);

        // 闪电
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 50, 2.0, 0.5, 2.0, 0.15);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 1.5, 0), 30, 1.5, 0.8, 1.5, 0.1);

        // 地面尘土
        world.spawnParticle(Particle.REDSTONE, center, 80, 3.0, 0.05, 3.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 2.0f));

        // 火焰旋涡
        for (int i = 0; i < 30; i++) {
            double angle = (2 * Math.PI / 30) * i;
            double dist = 1.2;
            Location flameLoc = center.clone().add(Math.cos(angle) * dist, 0.3, Math.sin(angle) * dist);
            world.spawnParticle(Particle.FLAME, flameLoc, 2, 0.3, 0.2, 0.3, 0.04);
        }

        // 龙息残留
        world.spawnParticle(Particle.DRAGON_BREATH, center.clone().add(0, 0.1, 0),
                5, 1.0, 0.05, 1.0, 0.01);
    }

    /* ========== 伤害分级（每档独立配置） ========== */

    private int getHammerTier(GameConfig cfg, double fallDistance) {
        if (fallDistance >= cfg.getHammerHighCharge()) return 2;
        if (fallDistance >= cfg.getHammerMediumCharge()) return 1;
        return 0;
    }

    private double getTierDamage(GameConfig cfg, int tier) {
        return switch (tier) {
            case 2 -> cfg.getHammerHighDamage();
            case 1 -> cfg.getHammerMediumDamage();
            default -> cfg.getHammerLowDamage();
        };
    }

    private double getTierRadius(GameConfig cfg, int tier) {
        return switch (tier) {
            case 2 -> cfg.getHammerHighRadius();
            case 1 -> cfg.getHammerMediumRadius();
            default -> cfg.getHammerLowRadius();
        };
    }

    private String getTierDisplayName(int tier) {
        return switch (tier) {
            case 2 -> "\u9ad8\u6863";
            case 1 -> "\u4e2d\u6863";
            default -> "\u4f4e\u6863";
        };
    }

    /* ========== 落地检测 ========== */

    private boolean isPlayerOnGround(Player player, Location to) {
        if (player.isOnGround()) return true;

        Location below = to.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid();
    }
}