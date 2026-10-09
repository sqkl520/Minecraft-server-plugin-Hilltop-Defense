package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
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
 * <p>村民会被怪物自然攻击（原版僵尸本就以村民为目标）；<b>只有怪物的直接攻击能伤害村民</b>——
 * 玩家攻击与一切环境伤害（活埋 / 摔落 / 岩浆 / 溺水 / 爆炸 / 雷击 / 火 / 仙人掌等）全部被取消，
 * 且村民以 {@code setAI(false)} 锁定在部署位置，杜绝被玩家推入岩浆或坑洞。</p>
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
            // P0-B：锁定村民位置 —— 禁用自身 AI，村民不再游走，也不会被玩家推入岩浆/坑洞。
            // NoAI 实体仍是怪物的合法攻击目标（怪物照常寻路过来击杀），且不影响受伤判定；
            // 配合 setInvulnerable(false) 保证怪物伤害照常生效（村民可被怪物杀，不是全无敌）。
            villager.setAI(false);

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

    /**
     * 村民伤害策略：<b>白名单放行</b>（取代早期只拦玩家直击的黑名单，堵死环境伤害绕过）。
     *
     * <p>唯一放行条件 = <b>战斗类 cause</b> 且 <b>溯源到敌对怪物</b>，其余一切来源全部取消：</p>
     * <ul>
     *   <li><b>放行</b>：溯源命中原版 {@link Monster} 或本插件登记的波次怪
     *       （{@code WaveManager.activeMobs}），且 cause 属战斗类
     *       —— 近战 {@code ENTITY_ATTACK} / 横扫 {@code ENTITY_SWEEP_ATTACK} /
     *       投射物 {@code PROJECTILE} / 怪物触发的实体爆炸 {@code ENTITY_EXPLOSION}
     *       （后者即甲虫自爆 STORY-1.6，溯源实体是甲虫）。</li>
     *   <li><b>取消</b>：玩家溯源的直击 / 横扫 / 投射物 / 药水；以及所有环境伤害
     *       —— 窒息 / 摔落 / 岩浆 / 溺水 / 雷击 / 火 / 点燃 / 仙人掌 / 虚空 等
     *       要么不在战斗白名单，要么溯源不到怪物，统一取消。从根上堵死
     *       「用泥土活埋、挖井摔死、引岩浆/点燃村民」零门槛强制全场失败。</li>
     * </ul>
     *
     * <p>烈焰蛋 / 重锤 AOE 的村民豁免（STORY-1.6）走各自目标过滤、不经本方法；
     * 即便漏到这里，其溯源实体是玩家，也会被白名单取消，豁免不受影响。</p>
     */
    public void handleVillagerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!villagers.contains(villager)) return;

        // 白名单：战斗类 cause + 溯源到敌对怪物，两者同时满足才放行；否则一律取消。
        if (isMonsterCombatCause(event.getCause()) && isHostileMonsterSource(event)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * 战斗类伤害 cause 白名单：只有这几种 cause 才<i>可能</i>来自怪物的直接攻击。
     *
     * <p>{@code ENTITY_EXPLOSION} 保留在白名单内是为放行甲虫自爆（溯源是甲虫）；
     * 玩家点燃的 TNT 同为该 cause，但溯源是玩家，会在 {@link #isHostileMonsterSource}
     * 处被拦下。其余所有 cause（含 {@code BLOCK_EXPLOSION} 与全部环境伤害）
     * 不在白名单，直接取消。</p>
     */
    private static boolean isMonsterCombatCause(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION;
    }

    /**
     * 溯源判定：伤害是否来自敌对怪物（原版 {@link Monster} 或本插件波次怪）。
     *
     * <p>同时检查 {@code getCausingEntity()}（责任实体，如射箭的骷髅、引爆的甲虫）
     * 与 {@code getDirectEntity()}（直接实体，如箭矢、爆炸源），任一命中怪物即放行；
     * 波次怪可能被配置成非 {@link Monster} 的实体类型（如 Phantom），故以
     * {@code activeMobs} 登记表兜底。玩家及无溯源（纯环境）一律返回 false。</p>
     */
    private boolean isHostileMonsterSource(EntityDamageEvent event) {
        var source = event.getDamageSource();
        return isHostileMonster(source.getCausingEntity())
                || isHostileMonster(source.getDirectEntity());
    }

    /** 实体是否为敌对怪物（原版 {@link Monster} 或已登记的波次怪）；玩家 / null 均否。 */
    private boolean isHostileMonster(Entity entity) {
        if (entity == null || entity instanceof Player) return false;
        if (entity instanceof Monster) return true;
        return gameManager.getWaveManager().getActiveMobs().contains(entity);
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
