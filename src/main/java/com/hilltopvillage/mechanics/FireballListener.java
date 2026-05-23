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
    /** 每个玩家的冷却倒计时 (tick 数) */
    private final Map<UUID, Integer> cooldowns;

    public FireballListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.plugin = gameManager.getPlugin();
        this.fireballName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("fireball.item-name", "&c&l烈焰蛋"));
        this.cooldowns = new HashMap<>();
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
        lore.add(ChatColor.GOLD + "冷却: " + cfg.getFireballCooldownTicks() / 20 + " 秒");
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
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isFireball(item)) return;

        event.setCancelled(true);

        // 冷却检查
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        UUID pid = player.getUniqueId();
        int cd = cooldowns.getOrDefault(pid, 0);
        if (cd > 0) {
            player.sendMessage(ChatColor.RED + "烈焰蛋冷却中... " + (cd / 20.0) + " 秒");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            return;
        }

        // 消耗物品 (生存模式)
        if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL || player.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            item.setAmount(item.getAmount() - 1);
        }

        // 冷却
        cooldowns.put(pid, cfg.getFireballCooldownTicks());
        new BukkitRunnable() {
            @Override
            public void run() {
                int remaining = cooldowns.getOrDefault(pid, 0) - 1;
                if (remaining <= 0) {
                    cooldowns.remove(pid);
                    cancel();
                } else {
                    cooldowns.put(pid, remaining);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

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
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /* ========== 命中爆炸 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball projectile = (Snowball) event.getEntity();
        if (!projectile.hasMetadata("hilltop_fireball")) return;

        Location impact = projectile.getLocation();

        // 获取投掷者 UUID（必须在 removeMetadata 之前，用于对自己仅击退不伤害）
        String throwerUuid = null;
        if (projectile.hasMetadata("hilltop_fireball")) {
            throwerUuid = projectile.getMetadata("hilltop_fireball").get(0).asString();
        }
        projectile.removeMetadata("hilltop_fireball", plugin);

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        World world = impact.getWorld();

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

        // 范围伤害（仅对非玩家实体）
        double radius = cfg.getFireballExplosionRadius();
        double damage = cfg.getFireballDamage();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(impact, radius, radius, radius,
                entity -> entity != null && entity.isValid() && !(entity instanceof Player));

        for (LivingEntity entity : nearby) {
            double distance = entity.getLocation().distance(impact);
            double falloff = 1.0 - (distance / (radius * 1.3));
            falloff = Math.max(0.4, Math.min(1.0, falloff));

            // 先造成伤害
            entity.setNoDamageTicks(0);
            entity.damage(damage * falloff);

            // 点燃
            entity.setFireTicks(40);

            // 击退（在 damage 之后覆盖）
            Vector kb = entity.getLocation().toVector().subtract(impact.toVector()).normalize();
            kb.setY(0.8);
            kb.multiply(4.0 * falloff);
            entity.setVelocity(kb);
        }

        // 对投掷者本人：仅击退，不伤害
        if (throwerUuid != null) {
            Player thrower = Bukkit.getPlayer(UUID.fromString(throwerUuid));
            if (thrower != null && thrower.isOnline()
                    && thrower.getLocation().distance(impact) <= radius) {
                double distance = thrower.getLocation().distance(impact);
                double falloff = 1.0 - (distance / (radius * 1.3));
                falloff = Math.max(0.4, Math.min(1.0, falloff));

                Vector kb = thrower.getLocation().toVector().subtract(impact.toVector()).normalize();
                kb.setY(0.8);
                kb.multiply(4.0 * falloff);
                thrower.setVelocity(kb);
            }
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
}