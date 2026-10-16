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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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

    /**
     * G3c：阵亡进入旁观后，快捷栏不会渲染、客户端也不会发送使用物品包，
     * 因此「返回大厅」唯一可靠的入口是聊天里的可点击按钮（runCommand）。
     */
    private void sendLeaveHint(Player player) {
        String msg = plugin.getLanguageManager().getFor(player, "hint.spectate-leave",
                plugin.getLanguageManager().getFor(player, "btn.return-lobby"));
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(msg)
                .clickEvent(ClickEvent.runCommand("/hilltop leave"))
                .hoverEvent(HoverEvent.showText(Component.text("/hilltop leave"))));
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
            // M-18（跨 lane 协同）：死亡即清蓄力态，避免旁观等待 / 重连复活后
            // 带着幽灵蓄力标记触发「幽灵猛击」
            data.deactivateSmash();
        }

        // 跨 lane（约定#1 / M-18）：同步清锤 / 烈焰蛋的监听器侧残留态（蓄力 BossBar / 冷却）。
        // cleanupPlayer 由 F3 保证幂等并含 BossBar 清理；不清理的话蓄力条会残留到退服。
        plugin.getHammerListener().cleanupPlayer(player);
        plugin.getFireballListener().cleanupPlayer(player);

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

        // STORY-1.5：存活判定收敛到 GameManager.hasAlivePlayer()（与 tick 复检同一依据），消灭双份逻辑
        if (!gameManager.hasAlivePlayer() && gameManager.getNodeSystem().getActiveCount() == 0) {
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

        ArenaInstance arena = plugin.getMultiArenaManager().getPlayerArena(player);

        // M-07②：死亡画面跨过波次刷新后的「幽灵态」修复（防御兜底）。
        // 若复活时 PlayerData.alive 已为 true（任何交错路径造成——典型来源是修复前的
        // 「波次刷新对死画面玩家置 alive=true」，修复①已关闭该来源），则直接完成正常
        // 复活：SURVIVAL + 传送核心 + 清旁观标记 + 已有提示，不再套「等待下次波次」，
        // 否则会形成 alive=true + 旁观 的永久幽灵态（respawnDeadPlayers 因 alive 恒真
        // 永远跳过，hasAlivePlayer() 恒真抑制全灭判负）。
        PlayerData data = gameManager.getPlayerData(player);
        if (data != null && data.isAlive()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                // 二次校验（原代码只查 isOnline）：这一 tick 内状态可能又变，以最新 alive 为准
                PlayerData latest = gameManager.getPlayerData(player);
                if (latest == null) return; // 已被移出对局：保持移出流程的结果
                if (latest.isAlive()) {
                    if (arena != null) {
                        arena.setSpectator(player.getUniqueId(), false); // 清旁观标记（幂等）
                    }
                    player.setGameMode(GameMode.SURVIVAL);
                    if (core != null) player.teleport(core);
                    plugin.getLanguageManager().send(player, "respawned");
                } else {
                    // tick 内翻回阵亡（极端交错）：退回等待波次刷新旁观
                    if (arena != null) {
                        arena.setSpectator(player.getUniqueId(), true);
                    }
                    player.setGameMode(GameMode.SPECTATOR);
                    if (core != null) player.teleport(core);
                    plugin.getLanguageManager().send(player, "respawn-waiting");
                    sendLeaveHint(player);
                }
            });
            return;
        }

        // 进入「等待波次刷新」旁观状态
        if (arena != null) {
            arena.setSpectator(player.getUniqueId(), true);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // 二次校验：1 tick 内玩家可能已被波次刷新复活（alive 翻 true）或被移出对局
            //（原代码只查 isOnline，是幽灵态的第二个交错入口）
            PlayerData latest = gameManager.getPlayerData(player);
            if (latest == null) return; // 已被移出：保持移出流程的结果
            if (latest.isAlive()) {
                // 已被波次刷新复活（本波刷新在事件处理与延迟任务之间跑过）：完成复活，
                // 而不是把已复活玩家打回旁观；旁观标记已由波次刷新清除
                player.setGameMode(GameMode.SURVIVAL);
                if (core != null) player.teleport(core);
                return;
            }
            player.setGameMode(GameMode.SPECTATOR);
            if (core != null) player.teleport(core);
            plugin.getLanguageManager().send(player, "respawn-waiting");
            sendLeaveHint(player);
        });
    }
}
