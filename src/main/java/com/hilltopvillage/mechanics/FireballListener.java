package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 烈焰蛋系统（仿起床战争 Fireball）。
 * 右键投掷，沿飞行轨迹洒粒子，命中后爆炸并对周围怪物造成伤害。
 */
public class FireballListener implements Listener {

    private final GameManager gameManager;
    private final HilltopVillagePlugin plugin;
    private final String fireballName;
    private final Map<UUID, Integer> cooldowns;
    private final Set<UUID> activeCooldowns;
    private BukkitRunnable cooldownTicker;
    private boolean cooldownTickerRunning;

    public FireballListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.plugin = gameManager.getPlugin();
        this.fireballName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("fireball.item-name", "&c&l烈焰蛋"));
        this.cooldowns = new HashMap<>();
        this.activeCooldowns = new HashSet<>();
        this.cooldownTickerRunning = false;
    }

    private void ensureCooldownTickerRunning() {
        if (cooldownTickerRunning) return;
        cooldownTickerRunning = true;
        cooldownTicker = new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, Integer>> it = cooldowns.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Integer> entry = it.next();
                    int remaining = entry.getValue() - 2;
                    if (remaining <= 0) {
                        it.remove();
                        activeCooldowns.remove(entry.getKey());
                    } else {
                        entry.setValue(remaining);
                    }
                }
                if (cooldowns.isEmpty()) {
                    cooldownTickerRunning = false;
                    cancel();
                }
            }
        };
        cooldownTicker.runTaskTimer(plugin, 2L, 2L);
    }

    /* ========== 创建烈焰蛋物品 ========== */

    public ItemStack createFireball() {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        ItemStack item = new ItemStack(cfg.getFireballMaterial());

        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(fireballName);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "右键投掷，命中后爆炸");
        lore.add(ChatColor.RED + "对怪物造成范围伤害");
        int cdTicks = cfg.getFireballCooldownTicks();
        lore.add(ChatColor.GOLD + "冷却: " + (cdTicks > 0 ? String.format("%.1f", cdTicks / 20.0) + " 秒" : "无"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isFireball(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(fireballName);
    }

    /* ========== 右键投掷 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        boolean isOffhand = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        ItemStack item = isOffhand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (!isFireball(item)) return;

        event.setCancelled(true);

        // 冷却检查
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        UUID pid = player.getUniqueId();
        int cd = cooldowns.getOrDefault(pid, 0);
        if (cd > 0) {
            player.sendMessage(ChatColor.RED + "烈焰蛋冷却中... " + String.format("%.1f", cd / 20.0) + " 秒");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            return;
        }

        // 消耗物品 (生存模式)
        if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL || player.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            item.setAmount(item.getAmount() - 1);
        }

        // 冷却
        cooldowns.put(pid, cfg.getFireballCooldownTicks());
        activeCooldowns.add(pid);
        ensureCooldownTickerRunning();

        // 发射 fireball 投射物
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        Snowball projectile = player.launchProjectile(Snowball.class, direction.multiply(cfg.getFireballSpeed()));
        projectile.setMetadata("hilltop_fireball", new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        projectile.setGravity(false);
        projectile.setGlowing(true);

        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.5f);
        player.spawnParticle(Particle.FLAME, eye.add(direction.clone().multiply(0.5)), 10, 0.1, 0.1, 0.1, 0.02);

        // 飞行粒子轨迹
        startTrailTask(projectile, cfg);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispense(BlockDispenseEvent event) {
        if (event.isCancelled()) return;
        ItemStack item = event.getItem();
        if (!isFireball(item)) return;

        event.setCancelled(true);

        org.bukkit.block.Dispenser dispenser = (org.bukkit.block.Dispenser) event.getBlock().getState();
        org.bukkit.inventory.Inventory inv = dispenser.getInventory();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && isFireball(slot)) {
                slot.setAmount(slot.getAmount() - 1);
                if (slot.getAmount() <= 0) {
                    inv.setItem(i, null);
                }
                break;
            }
        }

        Vector velocity = event.getVelocity();
        Location origin = event.getBlock().getLocation().toCenterLocation().add(velocity.clone().normalize().multiply(0.6));

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        Snowball projectile = origin.getWorld().spawn(origin, Snowball.class);
        projectile.setMetadata("hilltop_fireball", new FixedMetadataValue(plugin, "DISPENSER"));
        projectile.setGravity(false);
        projectile.setGlowing(true);
        projectile.setVelocity(velocity.clone().normalize().multiply(cfg.getFireballSpeed()));

        origin.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.5f);
        startTrailTask(projectile, cfg);
    }

    /* ========== 飞行轨迹粒子 ========== */

    private void startTrailTask(Snowball projectile, GameConfig cfg) {
        new BukkitRunnable() {
            int ticks = 0;
            int maxTicks = cfg.getFireballMaxTravelTicks();

            @Override
            public void run() {
                if (!projectile.isValid() || projectile.isDead()) {
                    cancel();
                    return;
                }
                ticks++;
                if (ticks > maxTicks) {
                    projectile.remove();
                    cancel();
                    return;
                }
                Location loc = projectile.getLocation();
                World w = loc.getWorld();
                w.spawnParticle(Particle.FLAME, loc, 3, 0.1, 0.1, 0.1, 0.01);
                w.spawnParticle(Particle.SMOKE_NORMAL, loc, 2, 0.15, 0.15, 0.15, 0.01);
                w.spawnParticle(Particle.LAVA, loc, 1, 0.05, 0.05, 0.05, 0);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public void cleanupPlayer(Player player) {
        cooldowns.remove(player.getUniqueId());
        activeCooldowns.remove(player.getUniqueId());
    }

    /* ========== 命中爆炸 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball projectile = (Snowball) event.getEntity();
        if (!projectile.hasMetadata("hilltop_fireball")) return;

        Location impact = projectile.getLocation().clone();

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        World world = impact.getWorld();

        // 获取投掷者（优先用 getShooter，metadata 作备用）
        UUID throwerUuid = null;
        if (projectile.getShooter() instanceof Player) {
            throwerUuid = ((Player) projectile.getShooter()).getUniqueId();
        } else if (projectile.hasMetadata("hilltop_fireball")) {
            try {
                throwerUuid = UUID.fromString(projectile.getMetadata("hilltop_fireball").get(0).asString());
            } catch (IllegalArgumentException ignored) {}
        }
        projectile.removeMetadata("hilltop_fireball", plugin);

        // 爆炸音效
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        world.playSound(impact, Sound.ENTITY_BLAZE_DEATH, 0.8f, 1.0f);

        // 中心爆炸粒子
        world.spawnParticle(Particle.EXPLOSION_LARGE, impact, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, impact, 5, 0.5, 0.5, 0.5, 0.2);
        world.spawnParticle(Particle.FLAME, impact, 30, 1.0, 0.5, 1.0, 0.05);
        world.spawnParticle(Particle.LAVA, impact, 15, 0.5, 0.2, 0.5, 0.02);
        world.spawnParticle(Particle.SMOKE_LARGE, impact, 10, 0.8, 0.5, 0.8, 0.05);

        // 环形火焰扩散
        for (int i = 0; i < 16; i++) {
            double angle = (2 * Math.PI / 16) * i;
            double x = Math.cos(angle) * 2.5;
            double z = Math.sin(angle) * 2.5;
            world.spawnParticle(Particle.FLAME, impact.clone().add(x, 0.2, z), 1, 0, 0, 0, 0.02);
        }

        // 范围伤害与击退（BedWars1058 风格公式：水平推离爆炸点 + 智能 Y 轴处理）
        double radius = cfg.getFireballExplosionRadius();
        double damage = cfg.getFireballDamage();
        double knockback = cfg.getFireballKnockback();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(impact, radius, radius, radius,
                entity -> entity != null && entity.isValid());

        for (LivingEntity entity : nearby) {
            double distance = entity.getLocation().distance(impact);
            double falloff = 1.0 - (distance / (radius * 1.3));
            falloff = Math.max(0.4, Math.min(1.0, falloff));

            entity.setNoDamageTicks(0);

            boolean isThrower = throwerUuid != null && entity.getUniqueId().equals(throwerUuid);

            if (entity instanceof Player) {
                entity.damage(isThrower ? 2.0 : 4.0);
                entity.setFireTicks(20);
            } else {
                entity.damage(damage * falloff);
                entity.setFireTicks(40);
            }

            // 投掷者本人跳过此循环，延迟单独处理
            if (isThrower) continue;

            applyBwKnockback(entity, impact, knockback, falloff);
        }

        // 对投掷者本人：同一公式，延迟 1 tick（避免同 tick 被覆盖）
        if (throwerUuid != null) {
            final UUID finalUuid = throwerUuid;
            final Location finalImpact = impact.clone();
            final double finalRadius = radius;
            final double finalKnockback = knockback;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player thrower = Bukkit.getPlayer(finalUuid);
                if (thrower != null && thrower.isOnline()
                        && thrower.getWorld().equals(finalImpact.getWorld())
                        && thrower.getLocation().distance(finalImpact) <= finalRadius) {
                    double distance = thrower.getLocation().distance(finalImpact);
                    double falloff = 1.0 - (distance / (finalRadius * 1.3));
                    falloff = Math.max(0.4, Math.min(1.0, falloff));
                    applyBwKnockback(thrower, finalImpact, finalKnockback, falloff);
                }
            });
        }

        // 命中位置生成短暂火焰粒子残留
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ >= 10) { cancel(); return; }
                world.spawnParticle(Particle.FLAME, impact.clone().add(0, 0.1, 0),
                        3, 1.0, 0.05, 1.0, 0.01);
                world.spawnParticle(Particle.SMOKE_NORMAL, impact.clone().add(0, 0.2, 0),
                        2, 0.8, 0.1, 0.8, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * BedWars1058 风格击退公式。
     * 水平方向：推离爆炸中心；垂直方向：动态 Y 轴处理（补偿脚下爆炸、跳跃增强）。
     */
    private void applyBwKnockback(LivingEntity entity, Location impact, double knockback, double falloff) {
        Vector entityVector = entity.getLocation().toVector();
        Vector diff = impact.toVector().subtract(entityVector);

        double vScale = knockback * falloff;

        if (diff.lengthSquared() < 1e-6) {
            entity.setVelocity(new Vector(0, vScale * 0.45, 0));
            return;
        }

        Vector normalizedVector = diff.normalize();

        // 水平方向：用独立倍率（远小于垂直），避免脚下稍微偏离就横飞几十格
        double hScale = vScale * 0.25;
        Vector horizontalVector = new Vector(
                normalizedVector.getX() * hScale * -1,
                0,
                normalizedVector.getZ() * hScale * -1);

        double y = normalizedVector.getY();
        if (y < 0) y += 1.5;
        if (y <= 0.5) {
            y = vScale * 0.45;
        } else {
            y = y * vScale * 0.45;
        }

        entity.setVelocity(horizontalVector.setY(y));
    }
}