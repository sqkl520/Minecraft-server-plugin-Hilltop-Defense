package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HammerListener implements Listener {

    /** 重锤剩余次数 PDC 键（存发锤时的 max-uses 快照，每次猛击 -1） */
    private static final String HAMMER_USES_KEY = "hammer-uses-left";

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final String hammerName;
    private final Map<UUID, BossBar> chargeBars;
    private final Map<UUID, Integer> lastChargeTier;
    private final Map<UUID, Integer> lastBossBarTick;
    private final Set<UUID> recentlyLanded;
    /** 猛击冷却：玩家 UUID → 下次可用时间戳（毫秒，System.currentTimeMillis） */
    private final Map<UUID, Long> smashCooldowns;

    public HammerListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        String configuredName = plugin.getConfigManager().getActiveConfig().getHammerItemName();
        this.hammerName = ChatColor.translateAlternateColorCodes('&',
                (configuredName == null || configuredName.isEmpty())
                        ? lang.get("item.hammer-name")
                        : configuredName);
        this.chargeBars = new ConcurrentHashMap<>();
        this.lastChargeTier = new ConcurrentHashMap<>();
        this.lastBossBarTick = new ConcurrentHashMap<>();
        this.recentlyLanded = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.smashCooldowns = new ConcurrentHashMap<>();
    }

    /** 解析玩家所在竞技场的 GameManager（不在任何竞技场时返回 null） */
    private GameManager gm(Player player) {
        var arena = plugin.getMultiArenaManager().getPlayerArena(player);
        return arena == null ? null : arena.getGameManager();
    }

    /* ========== 模型系统 ========== */

    public ItemStack createHammer() {
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
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
        lore.add(lang.get("hammer.lore1"));
        lore.add(lang.get("hammer.lore2"));
        lore.add(lang.get("hammer.lore3"));
        int maxUses = cfg.getHammerMaxUses();
        if (maxUses > 0) {
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER, maxUses);
            lore.add(lang.get("hammer.lore-uses", lang.getLocale(), maxUses));
        }
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
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        // STORY-1.8：只在对局运行（含整备期，休整期允许开锤）时允许开锤，等待区/结算期拒绝并提示
        if (!gameManager.isGameRunning()) {
            ItemStack probe = player.getInventory().getItemInMainHand();
            if (isHammer(probe)) {
                player.sendMessage(lang.getFor(player, "hammer.not-running"));
                event.setCancelled(true);
            }
            return;
        }

        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isAlive()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHammer(item)) return;

        if (data.isSmashActive()) return;

        // STORY-1.3：冷却检查（时间戳方案，无 ticker）
        double cooldownSeconds = plugin.getConfigManager().getActiveConfig().getHammerCooldownSeconds();
        if (cooldownSeconds > 0) {
            Long readyAt = smashCooldowns.get(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (readyAt != null && now < readyAt) {
                long remainSeconds = (readyAt - now + 999) / 1000;
                player.sendActionBar(lang.getFor(player, "hammer.cooldown", remainSeconds));
                event.setCancelled(true);
                return;
            }
        }

        data.activateSmash(player.getLocation().getY(), gameManager.getGameWorld().getGameTime());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.5f);
        player.spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.05);

        // 复用或创建蓄力 BossBar
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar(lang.getFor(player, "hammer.bossbar.charging"), BarColor.YELLOW, BarStyle.SOLID);
            chargeBars.put(player.getUniqueId(), bar);
            bar.addPlayer(player);
        }
        bar.setTitle(lang.getFor(player, "hammer.bossbar.charging"));
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
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

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

                GameConfig cfg = plugin.getConfigManager().getActiveConfig();
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
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.ready"));
                    } else if (tier == 1) {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.smashing"));
                    } else {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.charging"));
                    }
                }
            }
        }

        if (!isOnGroundNow) return;
        if (recentlyLanded.contains(player.getUniqueId())) return;

        double fallDistance = startY(player, data, currentY);
        long currentTick = gameManager.getGameWorld().getGameTime();
        long tickLimit = plugin.getConfigManager().getActiveConfig().getHammerSmashTimeoutTicks();

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
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

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

        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        double finalDamage = getTierDamage(cfg, tier);
        double radius = getTierRadius(cfg, tier);

        // STORY-1.3：冷却计时起点（猛击落地成功触发）
        double cooldownSeconds = cfg.getHammerCooldownSeconds();
        if (cooldownSeconds > 0) {
            smashCooldowns.put(player.getUniqueId(),
                    System.currentTimeMillis() + (long) (cooldownSeconds * 1000));
        }

        // STORY-1.3：扣使用次数（max-uses 方案，原版/ItemsAdder 统一行为）
        if (!consumeUse(player)) {
            // 次数刚好耗尽 → 碎裂，本次猛击照常结算（伤害已打出）
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            player.sendMessage(lang.getFor(player, "hammer.broken"));
        }

        // 弹起玩家自身
        player.setVelocity(new Vector(0, 0.8 + Math.min(fallDistance * 0.05, 1.2), 0));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f);

        // 伤害提示
        String tierName = getTierDisplayName(player, tier);
        String smashKey = tier == 2 ? "hammer.smash.high" : tier == 1 ? "hammer.smash.medium" : "hammer.smash.low";
        String actionBarMsg = lang.getFor(player, smashKey, tierName, String.format("%.1f", finalDamage));
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
        smashCooldowns.remove(uuid);
    }

    /**
     * 消耗主手重锤 1 次使用次数（STORY-1.3 耐久方案）。
     *
     * <p>无 PDC 计数器（max-uses=0 时发的锤、或玩家普通攻击消耗耐久中）→ 返回 true 不处理；
     * 剩余次数 &gt; 1 → PDC -1 并同步 lore 显示、映射原版耐久条（如 meta 支持）；剩余 1 次 →
     * 返回 false，由调用方碎裂（本次猛击已结算）。</p>
     *
     * @return 锤子是否还有剩余次数（false = 已耗尽，调用方应碎裂）
     */
    private boolean consumeUse(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHammer(item)) return true;
        ItemMeta meta = item.getItemMeta();
        Integer left = meta.getPersistentDataContainer().get(
                new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER);
        if (left == null) return true;
        int remain = left - 1;
        if (remain <= 0) return false;

        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER, remain);
        List<String> lore = meta.getLore();
        if (lore != null && !lore.isEmpty()) {
            lore.set(lore.size() - 1, lang.getFor(player, "hammer.lore-uses", remain));
            meta.setLore(lore);
        }
        // 原版耐久条映射显示（ItemsAdder 等非 Damageable meta 自动跳过）
        if (meta instanceof Damageable damageable) {
            int maxUses = plugin.getConfigManager().getActiveConfig().getHammerMaxUses();
            if (maxUses > 0 && item.getType().getMaxDurability() > 0) {
                double fraction = (double) remain / maxUses;
                damageable.setDamage((int) (item.getType().getMaxDurability() * (1.0 - fraction)));
            }
        }
        item.setItemMeta(meta);
        return true;
    }

    /* ========== 群体攻击（AOE + 玩家击飞） ========== */

    private void applyAreaDamage(Player attacker, Location center, double damage, double radius) {
        World world = center.getWorld();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && entity.isValid() && entity != attacker
                        && !VillagerSystem.isVillageVillager(entity));

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
        int duration = plugin.getConfigManager().getActiveConfig().getHammerAftershockTicks();
        int slownessLevel = 2;

        World world = center.getWorld();
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && !(entity instanceof Player) && entity.isValid()
                        && !VillagerSystem.isVillageVillager(entity));

        for (LivingEntity entity : nearby) {
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, duration, slownessLevel, false, true, true));
        }
    }

    private void spawnSmashEffect(Location location, double fallDistance) {
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        if (tier >= 2) {
            HammerEffectRenderer.spawnHighTierEffect(location);
        } else if (tier >= 1) {
            HammerEffectRenderer.spawnMediumTierEffect(location);
        } else {
            HammerEffectRenderer.spawnLowTierEffect(location);
        }
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

    private String getTierDisplayName(Player player, int tier) {
        return switch (tier) {
            case 2 -> lang.getFor(player, "hammer.tier.high");
            case 1 -> lang.getFor(player, "hammer.tier.medium");
            default -> lang.getFor(player, "hammer.tier.low");
        };
    }

    /* ========== 落地检测 ========== */

    private boolean isPlayerOnGround(Player player, Location to) {
        if (player.isOnGround()) return true;

        Location below = to.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer());
    }
}
