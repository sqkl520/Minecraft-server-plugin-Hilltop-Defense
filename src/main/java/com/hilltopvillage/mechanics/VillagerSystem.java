package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 待保护村民系统。
 *
 * <p>设计对应「村民全部阵亡 = 游戏失败」：管理员在部署向导中手动放置村民，
 * 数量取决于管理员放了多少个；村民血量在 {@code /hilltop config} 中配置。</p>
 *
 * <p>村民会被怪物自然攻击（原版僵尸本就以村民为目标），玩家无法伤害村民。</p>
 *
 * <p><b>事件注册方式：</b>本类<b>不再</b>实现 {@link Listener}，也不再由
 * {@code ArenaInstance} 逐个注册。村民事件统一由 {@link GlobalListener} 在
 * {@code HilltopVillagePlugin#onEnable()} 中<b>全局注册一次</b>，再按事件实体所在世界
 * 路由到对应竞技场的本实例处理。这样反复启用/禁用竞技场不会累积残留监听器
 * （每个残留监听器都会强引用其 GameManager → World，造成真实内存泄漏）。</p>
 */
public class VillagerSystem {

    /**
     * 村民标记 PDC 键名（{@code hilltopvillage:village-villager} 的键部分）。
     *
     * <p>字符串常量化后，静态方法可不依赖插件实例构造 {@link NamespacedKey}，
     * 供烈焰蛋 / 重锤 / 甲虫自爆三处 AOE 共用同一判定。</p>
     */
    public static final String VILLAGE_VILLAGER_KEY = "village-villager";

    /**
     * 判断实体是否为本插件生成的待保护村民（供三处 AOE 豁免共用）。
     *
     * @param entity 待判定实体（null 安全）
     * @return PDC 含 {@code hilltopvillage:village-villager} 标记时返回 true
     */
    public static boolean isVillageVillager(Entity entity) {
        if (entity == null) return false;
        HilltopVillagePlugin plugin = HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class);
        if (plugin == null) return false;
        return entity.getPersistentDataContainer().has(
                new NamespacedKey(plugin, VILLAGE_VILLAGER_KEY), PersistentDataType.BYTE);
    }

    private final HilltopVillagePlugin plugin;
    private final GameManager gameManager;
    private final NamespacedKey villagerKey;
    private final List<Villager> villagers = new CopyOnWriteArrayList<>();

    public VillagerSystem(HilltopVillagePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.villagerKey = new NamespacedKey(plugin, VILLAGE_VILLAGER_KEY);
    }

    /* ========== 生成 / 清理 ========== */

    /**
     * 按部署配置生成所有村民（游戏开始时调用）。
     * 已存在的村民会先被清理，保证幂等。
     */
    public void spawnAll() {
        clearAll();

        List<Location> positions = gameManager.getAdminDeployManager().getVillagers();
        if (positions.isEmpty()) {
            plugin.getLogger().warning("竞技场 " + gameManager.getArenaId() + " 未部署村民，村民全灭判定不会触发");
            return;
        }

        double health = gameManager.getConfigManager().getActiveConfig().getVillagerHealth();

        for (Location location : positions) {
            if (location.getWorld() == null) continue;
            Villager villager = (Villager) location.getWorld()
                    .spawnEntity(location.clone().add(0.5, 0, 0.5), org.bukkit.entity.EntityType.VILLAGER);

            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.setAdult();
            villager.setProfession(Villager.Profession.FARMER);
            villager.setInvulnerable(false);
            villager.setCollidable(true);
            villager.setSilent(false);

            var maxHealth = villager.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(Math.max(1.0, health));
            }
            villager.setHealth(Math.max(1.0, health));

            villager.setCustomName(plugin.getLanguageManager()
                    .get("villager.display-name", plugin.getLanguageManager().getLocale(), health));
            villager.setCustomNameVisible(true);

            villager.getPersistentDataContainer().set(villagerKey, PersistentDataType.BYTE, (byte) 1);
            villagers.add(villager);
        }

        plugin.getLogger().info("竞技场 " + gameManager.getArenaId() + " 已生成 " + villagers.size() + " 个待保护村民");
    }

    /** 清理全部村民实体（游戏结束 / 竞技场重置时调用） */
    public void clearAll() {
        for (Villager villager : villagers) {
            if (villager != null && villager.isValid()) {
                villager.remove();
            }
        }
        villagers.clear();
    }

    /** 清理世界中残留的村民实体（崩溃 / 重载后的孤儿实体） */
    public void cleanupOrphans() {
        if (gameManager.getGameWorld() == null) return;
        int removed = 0;
        for (Entity entity : gameManager.getGameWorld().getEntities()) {
            if (!(entity instanceof Villager villager)) continue;
            if (!villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) continue;
            if (villagers.contains(villager)) continue;
            villager.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("已清理 " + removed + " 个残留村民实体");
        }
    }

    /* ========== 状态查询 ========== */

    public int getAliveCount() {
        int count = 0;
        for (Villager villager : villagers) {
            if (villager != null && villager.isValid() && !villager.isDead()) count++;
        }
        return count;
    }

    public int getTotalCount() {
        return villagers.size();
    }

    public boolean hasAlive() {
        return getAliveCount() > 0;
    }

    /* ========== 事件处理（由 GlobalListener 按世界路由后调用） ========== */

    /** 玩家不能伤害村民（怪物照常可以） */
    public void handleVillagerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!villagers.contains(villager)) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            var damager = event.getDamageSource().getCausingEntity();
            if (damager instanceof Player) {
                event.setCancelled(true);
            }
        }
    }

    /** 村民阵亡：更新名牌，全部阵亡则触发失败 */
    public void handleVillagerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!villagers.contains(villager)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        villagers.remove(villager);

        int alive = getAliveCount();
        if (alive > 0) {
            gameManager.broadcastI18n("villager-died", String.valueOf(alive));
            return;
        }

        // 全部阵亡 → 失败结算
        ArenaInstance arena = plugin.getMultiArenaManager().getArena(gameManager.getArenaId());
        if (arena != null) {
            arena.triggerDefeat("defeat-villagers-dead");
        }
    }

    /* ========== 全局事件路由（整服只注册一次） ========== */

    /**
     * 全局唯一的村民事件监听器。
     *
     * <p>在 {@code HilltopVillagePlugin#onEnable()} 中注册一次，与 hammerListener / nodeListener
     * 等并列。收到村民相关事件后按<b>事件实体所在世界</b>找到对应竞技场，再委托给该竞技场的
     * {@link VillagerSystem} 实例处理，因此每个竞技场依旧保有各自独立的村民状态
     * （{@code ArenaInstance} 上的清理/生成/统计接口保持不变）。</p>
     *
     * <p>竞技场被禁用（实例已从 activeArenas 移除）或事件发生在非竞技场世界时，
     * 查找结果为空 → 直接忽略，不会误处理已卸载世界中残留的村民实体。</p>
     */
    public static final class GlobalListener implements Listener {

        private final HilltopVillagePlugin plugin;

        public GlobalListener(HilltopVillagePlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onVillagerDamage(EntityDamageEvent event) {
            // 类型短路放在最前：EntityDamageEvent 是全服高频事件，非村民事件不应触发竞技场查找
            if (!(event.getEntity() instanceof Villager)) return;
            VillagerSystem system = resolve(event.getEntity());
            if (system != null) {
                system.handleVillagerDamage(event);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onVillagerDeath(EntityDeathEvent event) {
            // 类型短路放在最前：避免为非村民死亡事件做无谓的竞技场查找
            if (!(event.getEntity() instanceof Villager)) return;
            VillagerSystem system = resolve(event.getEntity());
            if (system != null) {
                system.handleVillagerDeath(event);
            }
        }

        /**
         * 按实体所在世界解析所属竞技场的村民系统。
         *
         * @return 竞技场未启用 / 世界非竞技场世界 / 世界为空时返回 null
         */
        private VillagerSystem resolve(Entity entity) {
            World world = entity.getWorld();
            if (world == null) return null;
            ArenaInstance arena = plugin.getMultiArenaManager().getArenaByWorld(world.getName());
            return arena == null ? null : arena.getVillagerSystem();
        }
    }
}
