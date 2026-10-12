package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 村民商店 NPC 管理器：生成 / 清除 / 重启恢复 / 防僵尸实体。
 * 实体识别 = PDC 标记 hilltopvillage:shop-npc（后续购买 Story 的 ShopListener 复用 isShopNpc() 判断右键目标）。
 */
public class NpcManager {

    /** PDC 标记：本插件生成的商店村民（BYTE 1，崩溃残留实体据此识别清理） */
    private final NamespacedKey shopNpcKey;

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    /** 当前商店村民实体引用（null = 未生成） */
    private Villager npc;
    /** 「看向附近玩家」循环任务 ID（-1 = 未运行）；随 NPC 一起启停，禁用时由 despawnNpc 取消 */
    private int lookTaskId = -1;

    /** 附近此半径（格）内的玩家会让 NPC 水平转向他 */
    private static final double LOOK_RADIUS = 8.0;
    /** 转向任务执行周期（tick）：0.5 秒一次，够顺滑又不至于密集发包 */
    private static final long LOOK_PERIOD_TICKS = 10L;
    /** 转头的最小角度变化（度）：小于此值不重设，避免 NPC 静止时无意义地反复发包 */
    private static final float LOOK_MIN_ANGLE = 2.0f;

    public NpcManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.shopNpcKey = new NamespacedKey(plugin, "shop-npc");
    }

    /**
     * 生成商店村民（先 despawn 旧实体，幂等可重复调用）。
     *
     * <p>朝向<b>只保留水平 yaw，pitch 一律归零</b>：村民永远不低头/抬头（F-4）。这里是全插件
     * 生成商店村民的唯一入口，在此统一兜底——即便旧存档（arenas.yml）里 {@code pitch} 被写坏，
     * 重启恢复（{@link #respawnFromConfig()}）或任何调用方传进来的坐标也会在生成时被自动纠正。</p>
     */
    public void spawnNpc(Location location) {
        despawnNpc();
        if (location == null || location.getWorld() == null) return;

        // 只保留水平朝向：克隆后把 pitch 归零再生成，避免低头状态被带入实体
        Location spawnLocation = location.clone();
        spawnLocation.setPitch(0f);

        Villager villager = (Villager) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.VILLAGER);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        // 铁匠外观比较像商人
        villager.setProfession(Villager.Profession.TOOLSMITH);

        String npcName = resolveNpcName();
        if (npcName != null && !npcName.isEmpty()) {
            villager.setCustomName(npcName);
        }
        villager.setCustomNameVisible(true);

        villager.getPersistentDataContainer().set(shopNpcKey, PersistentDataType.BYTE, (byte) 1);
        this.npc = villager;
        startLookTask();
    }

    /** 清除当前商店村民（未生成时无操作，幂等）；同时停掉转向任务 */
    public void despawnNpc() {
        stopLookTask();
        if (npc != null) {
            npc.remove();
            npc = null;
        }
    }

    /**
     * 清除「正站在指定世界里」的商店村民：世界不匹配（单例实体属于其它竞技场或大厅）时保持不动。
     *
     * <p>商店 NPC 是插件级单例（{@link #npc} 只持有一个实体），位置在哪个世界就归哪个竞技场，
     * 因此按「实体世界名 == 给定世界名」判定归属 —— 清除竞技场 A 的商店配置时，绝不能误删
     * 竞技场 B 或大厅正在使用的实体。世界名空白 / 未生成实体 / 实体世界为 null 时不做任何事。</p>
     *
     * @param worldName 目标世界名（通常是竞技场世界）
     * @return true = 确实清掉了一个站在该世界里的实体
     */
    public boolean despawnIfInWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        Location location = getNpcLocation();
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(worldName)) return false;
        despawnNpc();
        return true;
    }

    /**
     * 启动「看向附近玩家」任务：每 {@link #LOOK_PERIOD_TICKS} tick 让 NPC 面向
     * {@link #LOOK_RADIUS} 格内最近的玩家，<b>只改 yaw、pitch 恒为 0</b>。
     *
     * <p>纯视觉增强，不依赖 AI（NPC 的 {@code setAI(false)} 保持不变）：不改动实体位置，
     * 也不影响右键商店交互（{@link #isShopNpc(org.bukkit.entity.Entity)} 依据引用/PDC 判定，
     * 与朝向无关）。任务随 NPC 一并启停，禁用阶段由 {@link #despawnNpc()} 取消，不会泄漏。</p>
     */
    private void startLookTask() {
        stopLookTask();
        lookTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (npc == null || !npc.isValid()) {
                    stopLookTask();
                    return;
                }
                Location npcLocation = npc.getLocation();
                Player nearest = nearestPlayer(npcLocation);
                if (nearest == null) return;

                Vector direction = nearest.getLocation().toVector().subtract(npcLocation.toVector());
                if (direction.lengthSquared() < 1.0e-6) return; // 与玩家重合，无法求朝向
                Location facing = npcLocation.clone();
                facing.setDirection(direction);
                float yaw = facing.getYaw();
                // 角度差过小则不重设，避免 NPC 静止时无意义地反复发包
                if (Math.abs(normalizeAngle(yaw - npcLocation.getYaw())) < LOOK_MIN_ANGLE) return;
                npc.setRotation(yaw, 0f); // 只改 yaw，pitch 锁 0
            }
        }.runTaskTimer(plugin, LOOK_PERIOD_TICKS, LOOK_PERIOD_TICKS).getTaskId();
    }

    /** 停止转向任务（幂等） */
    private void stopLookTask() {
        if (lookTaskId != -1) {
            Bukkit.getScheduler().cancelTask(lookTaskId);
            lookTaskId = -1;
        }
    }

    /** 半径 {@link #LOOK_RADIUS} 格内、与 NPC 同世界的最近玩家（无则 null） */
    private Player nearestPlayer(Location npcLocation) {
        World world = npcLocation.getWorld();
        if (world == null) return null;
        Player nearest = null;
        double best = LOOK_RADIUS * LOOK_RADIUS;
        for (Player player : world.getPlayers()) {
            double distance = player.getLocation().distanceSquared(npcLocation);
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** 把角度差归一化到 [-180, 180)，便于比较实际转头幅度 */
    private static float normalizeAngle(float angle) {
        float result = angle % 360f;
        if (result < -180f) result += 360f;
        else if (result >= 180f) result -= 360f;
        return result;
    }

    /** 判断实体是否为商店村民：当前引用或 PDC 标记（ShopListener 右键判断复用） */
    public boolean isShopNpc(Entity entity) {
        if (entity == null) return false;
        if (entity == npc) return true;
        return entity.getPersistentDataContainer().has(shopNpcKey, PersistentDataType.BYTE);
    }

    /**
     * 服务器启动时恢复商店 NPC：从 arenas.yml 的竞技场定义里取各竞技场自己的
     * {@code shop-npc} 位置，按竞技场 ID 排序取第一个生成。
     *
     * <p>不再只认默认竞技场 / 第一个活动竞技场——商店位置现在按竞技场隔离存储，
     * 而竞技场走「启用队列」串行加载（同 tick 通常还没有任何活动实例），
     * 因此直接读定义（{@link ArenaDefinition#getShopNpc()}）更可靠。</p>
     *
     * <p>商店 NPC 是插件级单例（{@link #npc} 只持有一个实体），多个竞技场都配置了
     * 商店位置时只能生成第一个；按 ID 排序保证每次重启的选择一致。</p>
     */
    public void respawnFromConfig() {
        List<ArenaDefinition> definitions = new ArrayList<>(
                plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list());
        definitions.sort(Comparator.comparing(ArenaDefinition::getId));

        ArenaDefinition target = null;
        Location location = null;
        int configured = 0;
        for (ArenaDefinition definition : definitions) {
            Location candidate = definition.getShopNpc();
            if (candidate == null) continue;
            configured++;
            if (location == null) {
                target = definition;
                location = candidate;
            }
        }
        if (target == null) return;

        if (configured > 1) {
            plugin.getLogger().warning("有 " + configured + " 个竞技场配置了商店 NPC 位置，"
                    + "但商店 NPC 是插件级单例，仅生成竞技场 " + target.getId() + " 的位置");
        }
        spawnNpc(location);
        plugin.getLogger().info("Shop NPC respawned for arena " + target.getId());
    }

    /** 清理孤儿实体：PDC 有标记但非当前引用（崩溃/重装残留），启动 spawn 前调用一次 */
    public void cleanupOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity == npc) continue;
                if (entity.getPersistentDataContainer().has(shopNpcKey, PersistentDataType.BYTE)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Shop NPC cleanup: removed " + removed + " orphan entity(ies)");
        }
    }

    /** 当前 NPC 位置（未生成返回 null） */
    public Location getNpcLocation() {
        return npc != null ? npc.getLocation() : null;
    }

    /** 头顶名称：shop.yml 的 shop.npc.name 优先（支持颜色码），空则回退 i18n 的 shop.npc.name */
    private String resolveNpcName() {
        if (plugin.getShopManager() != null && plugin.getShopManager().getShopConfig() != null) {
            String configured = plugin.getShopManager().getShopConfig().getNpcName();
            if (configured != null && !configured.isEmpty()) {
                return ChatColor.translateAlternateColorCodes('&', configured);
            }
        }
        return lang.get("shop.npc.name");
    }
}
