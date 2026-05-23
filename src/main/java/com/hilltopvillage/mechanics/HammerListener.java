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
    private final Set<UUID> recentlyLanded;

    public HammerListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.plugin = gameManager.getPlugin();
        this.hammerName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("hammer.item-name", "&6&l\u795e\u5723\u91cd\u9524"));
        this.chargeBars = new HashMap<>();
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

        if (player.isOnGround()) {
            player.sendMessage(ChatColor.RED + "\u4f60\u5fc5\u987b\u8df3\u8d77\u6765\u624d\u80fd\u6fc0\u6d3b\u731b\u51fb\uff01");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            event.setCancelled(true);
            return;
        }

        data.activateSmash(player.getLocation().getY(), gameManager.getGameWorld().getGameTime());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.5f);
        player.spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.05);

        // 复用或创建蓄力 BossBar
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
            chargeBars.put(player.getUniqueId(), bar);
        }
        bar.setTitle(ChatColor.GOLD + "\u25a0 \u731b\u51fb\u84c4\u529b\u4e2d...");
        bar.setColor(BarColor.YELLOW);
        bar.setProgress(0.0);
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
        bar.setVisible(true);

        event.setCancelled(true);
    }

    /* ========== 下落追踪 + BossBar更新 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isSmashActive()) return;

        double currentY = event.getTo().getY();
        Location to = event.getTo();
        boolean isOnGroundNow = isPlayerOnGround(player, to);

        // 更新 BossBar 进度
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar != null) {
            double startY = data.getSmashStartY();
            double fallDist = startY - currentY;
            double maxDist = 15.0;
            double progress = Math.max(0.0, Math.min(1.0, fallDist / maxDist));

            bar.setProgress(progress);
            if (fallDist >= 10) {
                bar.setColor(BarColor.RED);
                bar.setTitle(ChatColor.RED + "\u25a0 \u5929\u964d\u6b63\u4e49\uff01");
            } else if (fallDist >= 3) {
                bar.setColor(BarColor.YELLOW);
                bar.setTitle(ChatColor.GOLD + "\u25a0 \u731b\u51fb\u84c4\u529b\u4e2d...");
            } else {
                bar.setColor(BarColor.YELLOW);
                bar.setTitle(ChatColor.YELLOW + "\u25a0 \u84c4\u529b\u5f00\u59cb");
            }
        }

        if (!isOnGroundNow) return;
        if (recentlyLanded.contains(player.getUniqueId())) return;

        double fallDistance = startY(player, data, currentY);
        long currentTick = gameManager.getGameWorld().getGameTime();
        long activateTick = data.getSmashActivateTick();
        long tickLimit = gameManager.getConfigManager().getActiveConfig().getHammerSmashTimeoutTicks();

        if (currentTick - activateTick > tickLimit) {
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
        double baseDamage = cfg.getHammerBaseDamage();
        double multiplier = getDamageMultiplier(fallDistance);
        double finalDamage = baseDamage * multiplier;
        double radius = cfg.getHammerEffectRadius();

        // 弹起玩家自身
        player.setVelocity(new Vector(0, 0.8 + Math.min(fallDistance * 0.05, 1.2), 0));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f);

        // 伤害提示（汉化）
        String tierName = getTierName(fallDistance);
        String actionBarMsg = ChatColor.GOLD + "\u25a0 \u731b\u51fb\uff01" + ChatColor.YELLOW + " [" + tierName + "] "
                + ChatColor.RED + String.format("%.1f", finalDamage) + " \u4f24\u5bb3";
        player.sendActionBar(actionBarMsg);

        // 范围伤害（包含玩家弹飞）
        applyAreaDamage(player, player.getLocation(), finalDamage, radius);
        applyAftershock(player.getLocation(), radius);
        spawnSmashEffect(player.getLocation());

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.8f);
    }

    /* ========== BossBar 管理 ========== */

    private void removeChargeBar(Player player) {
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar != null) {
            bar.setVisible(false);
            bar.removeAll();
        }
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

    /**
     * 猛击落地粒子特效（夸张原版重锤效果）。
     * 取消铁砧BlockDisplay，改用多层粒子模拟冲击波：
     * 1. 中心大爆炸粒子
     * 2. 环形扩散冲击波 (35个点散射)
     * 3. 地面碎石飞溅
     * 4. 上扬烟雾柱
     * 5. 闪电火花
     */
    private void spawnSmashEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.15, 0);

        // 中心大爆炸
        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 2, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 10, 1.5, 0.3, 1.5, 0.3);

        // 环形冲击波 (地面扩散)
        for (int i = 0; i < 35; i++) {
            double angle = (2 * Math.PI / 35) * i;
            double dist = 3.5;
            double x = Math.cos(angle) * dist;
            double z = Math.sin(angle) * dist;
            Location ringLoc = center.clone().add(x, 0, z);
            world.spawnParticle(Particle.CLOUD, ringLoc, 2, 0.2, 0.05, 0.2, 0.02);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ringLoc, 1, 0.1, 0.1, 0.1, 0.01);
        }

        // 内环密集冲击波
        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i;
            double dist = 1.8;
            double x = Math.cos(angle) * dist;
            double z = Math.sin(angle) * dist;
            Location ringLoc = center.clone().add(x, 0.05, z);
            world.spawnParticle(Particle.FLAME, ringLoc, 1, 0.1, 0.1, 0.1, 0.02);
        }

        // 地面碎石飞溅
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 60,
                2.5, 0.5, 2.5, 0.3, Material.STONE.createBlockData());
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 30,
                2.0, 0.5, 2.0, 0.2, Material.COBBLESTONE.createBlockData());

        // 上扬烟柱 (多层)
        for (int y = 0; y < 5; y++) {
            double yOff = y * 0.4;
            double spread = 0.3 + y * 0.25;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, yOff, 0),
                    8, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center.clone().add(0, 1.5, 0),
                10, 0.8, 0.5, 0.8, 0.05);

        // 闪电火花 (中心密集散射)
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 25, 1.0, 0.3, 1.0, 0.1);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.8, 0), 15, 0.6, 0.4, 0.6, 0.08);

        // 地面尘土 (用 REDSTONE 模拟)
        world.spawnParticle(Particle.REDSTONE, center, 40, 2.0, 0.05, 2.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 1.5f));
    }

    /* ========== 伤害分级（汉化） ========== */

    private double getDamageMultiplier(double fallDistance) {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        if (fallDistance < cfg.getHammerDamageLowThreshold()) {
            return cfg.getHammerDamageLowMultiplier();
        } else if (fallDistance <= cfg.getHammerDamageMediumThreshold()) {
            return cfg.getHammerDamageMediumMultiplier();
        } else {
            return cfg.getHammerDamageHighMultiplier();
        }
    }

    private String getTierName(double fallDistance) {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        if (fallDistance < cfg.getHammerDamageLowThreshold()) return "\u4f4e\u6863";
        else if (fallDistance <= cfg.getHammerDamageMediumThreshold()) return "\u4e2d\u6863";
        else return "\u9ad8\u6863";
    }

    /* ========== 落地检测 ========== */

    private boolean isPlayerOnGround(Player player, Location to) {
        if (to.getY() % 1.0 == 0.0 || to.getY() % 0.5 == 0.0) {
            return player.isOnGround();
        }

        World world = to.getWorld();
        Location below = to.clone().subtract(0, 0.1, 0);
        if (below.getBlock().getType().isSolid()) return true;

        return player.isOnGround() || player.getFallDistance() == 0.0f;
    }
}