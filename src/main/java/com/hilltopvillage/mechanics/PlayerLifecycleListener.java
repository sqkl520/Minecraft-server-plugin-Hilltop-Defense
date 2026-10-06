package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 玩家死亡与重生处理（多竞技场路由）。
 *
 * <p>规则（对应能量节点 = 血条的设计）：</p>
 * <ul>
 *   <li>玩家死亡后进入旁观状态，等待**波次刷新**</li>
 *   <li>波次刷新时：若有**活跃节点** → 直接在核心重生（无倒计时、无无敌时间）</li>
 *   <li>波次刷新时：若无活跃节点 → 继续旁观</li>
 *   <li>**节点全毁 + 全员阵亡** → 触发失败结算</li>
 * </ul>
 */
public class PlayerLifecycleListener implements Listener {

    private final HilltopVillagePlugin plugin;

    public PlayerLifecycleListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /** 解析玩家所在竞技场的 GameManager（不在竞技场或非进行中返回 null） */
    private GameManager gm(Player player) {
        ArenaInstance arena = plugin.getMultiArenaManager().getPlayerArena(player);
        return arena == null ? null : arena.getGameManager();
    }

    /* ========== 死亡 ========== */

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        // 只有对局进行中才接管死亡；等待区/开局前的死亡按原版处理
        // （否则未部署或尚未开局的竞技场会因「全员阵亡」被误判为失败）
        if (!gameManager.isGameRunning()) return;

        PlayerData data = gameManager.getPlayerData(player);
        if (data != null) {
            data.addDeath();
            data.setAlive(false);
        }

        // 游戏内物品不落地（避免掉落物污染地图 / 被他人拾取）
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);

        broadcastDeath(player, gameManager);
        checkTeamWipe(player, gameManager);
    }

    private void broadcastDeath(Player player, GameManager gameManager) {
        gameManager.broadcastI18n("player-died", player.getName());
    }

    /**
     * 全灭判定：对局进行中，且没有任何存活玩家 **且** 无活跃节点 → 失败。
     * （村民系统实装后，村民全灭也会走同一结算入口）
     */
    private void checkTeamWipe(Player player, GameManager gameManager) {
        ArenaInstance arena = plugin.getMultiArenaManager().getPlayerArena(player);
        if (arena == null) return;

        // 二次守卫：结算只可能发生在对局进行中
        if (!gameManager.isGameRunning()) return;

        boolean anyAlive = false;
        for (java.util.UUID uuid : gameManager.getPlayers()) {
            PlayerData data = gameManager.getPlayerData(uuid);
            if (data != null && data.isAlive()) {
                anyAlive = true;
                break;
            }
        }

        if (!anyAlive && gameManager.getNodeSystem().getActiveCount() == 0) {
            arena.triggerDefeat("defeat-all-down");
        }
    }

    /* ========== 重生 ========== */

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        // 非对局期间（等待区 / 未部署）的死亡：把玩家放回该竞技场的等待区，
        // 保持「在场内」的状态一致，避免原版重生把玩家丢到世界出生点或大厅
        if (!gameManager.isGameRunning()) {
            ArenaInstance arena = plugin.getMultiArenaManager().getPlayerArena(player);
            if (arena != null && arena.getLobbySpawn() != null) {
                event.setRespawnLocation(arena.getLobbySpawn());
            }
            return;
        }

        Location core = gameManager.getCoreLocation();

        // 重生点固定为核心（即使处于旁观等待状态，也让玩家看到核心）
        if (core != null) {
            event.setRespawnLocation(core);
        }

        // 进入「等待波次刷新」旁观状态
        ArenaInstance arena = plugin.getMultiArenaManager().getPlayerArena(player);
        if (arena != null) {
            arena.setSpectator(player.getUniqueId(), true);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.setGameMode(GameMode.SPECTATOR);
            if (core != null) player.teleport(core);
            plugin.getLanguageManager().send(player, "respawn-waiting");
        });
    }
}
