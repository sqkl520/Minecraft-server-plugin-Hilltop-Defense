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
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

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

    public NpcManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.shopNpcKey = new NamespacedKey(plugin, "shop-npc");
    }

    /** 生成商店村民（先 despawn 旧实体，幂等可重复调用） */
    public void spawnNpc(Location location) {
        despawnNpc();
        if (location == null || location.getWorld() == null) return;

        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
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
    }

    /** 清除当前商店村民（未生成时无操作，幂等） */
    public void despawnNpc() {
        if (npc != null) {
            npc.remove();
            npc = null;
        }
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
