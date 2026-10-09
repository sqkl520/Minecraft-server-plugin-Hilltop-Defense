package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大厅世界保护（对应 BedWars1058 的 MULTIARENA 大厅保护）。
 *
 * <p>大厅内的破坏、放置、伤害、饥饿、天气、掉落物、爆炸、容器交互全部被阻止；
 * 管理员可通过 {@code /hilltop build} 开启建造模式豁免（用于整修大厅）。</p>
 *
 * <p>只作用于大厅世界，竞技场世界不受影响。</p>
 */
public class LobbyProtectionListener implements Listener {

    /** 掉落到该 Y 值以下时传送回大厅出生点 */
    private static final double VOID_Y_THRESHOLD = 0.0;

    private final HilltopVillagePlugin plugin;
    /** 建造模式（豁免大厅保护的管理员） */
    private final Set<UUID> buildMode = ConcurrentHashMap.newKeySet();

    public LobbyProtectionListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /** 切换建造模式，返回切换后的状态 */
    public boolean toggleBuildMode(Player player) {
        UUID uuid = player.getUniqueId();
        if (buildMode.remove(uuid)) {
            plugin.getLanguageManager().send(player, "build-mode-off");
            return false;
        }
        buildMode.add(uuid);
        plugin.getLanguageManager().send(player, "build-mode-on");
        return true;
    }

    public boolean isBuildMode(Player player) {
        return buildMode.contains(player.getUniqueId());
    }

    /**
     * 退出清理入口：摘掉离玩家的建造模式标记。
     *
     * <p>buildMode 是纯内存的会话态，退出即失效 —— 管理员重进后需重新 /hilltop build。
     * 与 hammerListener / fireballListener 的 cleanupPlayer 同一种写法，
     * 由主类的 onPlayerQuit 统一调用。</p>
     */
    public void cleanupPlayer(Player player) {
        buildMode.remove(player.getUniqueId());
    }

    /** 玩家是否处于大厅世界 */
    private boolean inLobby(Entity entity) {
        if (entity == null || entity.getWorld() == null) return false;
        return plugin.getMultiArenaManager().isLobbyWorld(entity.getWorld().getName());
    }

    /** 方块是否位于大厅世界 */
    private boolean inLobby(Block block) {
        if (block == null || block.getWorld() == null) return false;
        return plugin.getMultiArenaManager().isLobbyWorld(block.getWorld().getName());
    }

    /** 是否应放行（建造模式 / 非大厅） */
    private boolean exempt(Player player) {
        return player == null || !inLobby(player) || isBuildMode(player);
    }

    private void deny(Player player) {
        plugin.getLanguageManager().send(player, "lobby-protected");
    }

    /* ========== 方块 ========== */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!inLobby(event.getEntity())) return;
        event.blockList().clear();
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!inLobby(event.getBlock())) return;
        event.blockList().clear();
        event.setCancelled(true);
    }

    /* ========== 伤害 ========== */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inLobby(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inLobby(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!plugin.getMultiArenaManager().isLobbyWorld(event.getWorld().getName())) return;
        // 大厅禁止下雨
        if (event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    /* ========== 交互 / 掉落物 ========== */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** 阻止大厅内打开容器（工作台/箱子/熔炉等） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (exempt(event.getPlayer())) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Material type = block.getType();
        if (type.isInteractable() && !isHarmless(type)) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** 允许交互的无害方块（按钮/拉杆等大厅装饰） */
    private boolean isHarmless(Material type) {
        return switch (type) {
            case LEVER, STONE_BUTTON, OAK_BUTTON, SPRUCE_BUTTON, BIRCH_BUTTON, JUNGLE_BUTTON,
                 ACACIA_BUTTON, DARK_OAK_BUTTON, CRIMSON_BUTTON, WARPED_BUTTON,
                 BAMBOO_BUTTON, POLISHED_BLACKSTONE_BUTTON, MANGROVE_BUTTON,
                 CHERRY_BUTTON, PALE_OAK_BUTTON -> true;
            default -> false;
        };
    }

    /** 阻止大厅内操作盔甲架 / 物品展示框 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof ArmorStand) && !(target instanceof ItemFrame)) return;
        if (exempt(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** 阻止大厅内伤害盔甲架 / 展示框 / 商店村民 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof ArmorStand) && !(victim instanceof ItemFrame)
                && !(victim instanceof Villager)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (exempt(player)) return;
        event.setCancelled(true);
    }

    /* ========== 虚空保护 ========== */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // 先做最廉价的 Y 轴判断：99.99% 的移动事件都远高于 0，
        // 可整段跳过 inLobby() → isLobbyWorld() → getString("lobby.world") + getWorld(toLowerCase)
        Location to = event.getTo();
        if (to == null || to.getY() > VOID_Y_THRESHOLD) return;

        Player player = event.getPlayer();
        if (!inLobby(player)) return;

        Location lobby = plugin.getMultiArenaManager().getLobbySpawn();
        if (lobby != null) {
            player.teleport(lobby);
        } else {
            player.teleport(player.getWorld().getSpawnLocation());
        }
        player.setFallDistance(0f);
    }
}
