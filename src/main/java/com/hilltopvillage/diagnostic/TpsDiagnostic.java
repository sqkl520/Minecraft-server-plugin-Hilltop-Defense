package com.hilltopvillage.diagnostic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.util.LanguageManager;

public class TpsDiagnostic {

    private final GameManager gameManager;
    private final LanguageManager lang;

    public TpsDiagnostic(GameManager gameManager, LanguageManager lang) {
        this.gameManager = gameManager;
        this.lang = lang;
    }

    /**
     * 获取玩家语言对应的消息（含占位符替换）。
     * 控制台则使用全局默认语言。
     */
    private String msg(CommandSender sender, String key, Object... args) {
        if (sender instanceof Player player) {
            return lang.getFor(player, key, args);
        } else {
            return lang.get(key, lang.getLocale(), args);
        }
    }

    public void printDiagnostic(CommandSender sender) {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();

        sender.sendMessage(msg(sender, "tps.header"));
        sender.sendMessage(msg(sender, "tps.values",
                formatTps(tps[0]), formatTps(tps[1]), formatTps(tps[2])));
        sender.sendMessage(msg(sender, "tps.mspt", String.format("%.2f", mspt)));

        sender.sendMessage(msg(sender, "tps.state", gameManager.getState()));
        sender.sendMessage(msg(sender, "tps.online-players", Bukkit.getOnlinePlayers().size()));
        sender.sendMessage(msg(sender, "tps.game-players", gameManager.getPlayers().size()));
        sender.sendMessage(msg(sender, "tps.wave", gameManager.getCurrentWave()));
        sender.sendMessage(msg(sender, "tps.mobs", gameManager.getWaveManager().getRemainingMobCount()));
        sender.sendMessage(msg(sender, "tps.nodes",
                gameManager.getNodeSystem().getActiveCount(), gameManager.getNodeSystem().getTotalCount()));
        sender.sendMessage(msg(sender, "tps.cleanup-queue",
                gameManager.getDisplayEntityManager().getPendingCleanupCount()));

        sender.sendMessage(msg(sender, "tps.server-header"));
        for (World world : Bukkit.getWorlds()) {
            int entityCount = world.getEntityCount();
            int loadedChunks = world.getLoadedChunks().length;
            int tileEntities = 0;
            try {
                tileEntities = world.getTileEntityCount();
            } catch (NoSuchMethodError ignored) {}

            ChatColor color;
            if (entityCount > 500) {
                color = ChatColor.RED;
            } else if (entityCount > 200) {
                color = ChatColor.YELLOW;
            } else {
                color = ChatColor.GREEN;
            }

            // 颜色码 + 数量合并为一个参数，确保 § 颜色码在已翻译的消息中生效
            sender.sendMessage(msg(sender, "tps.world-line",
                    world.getName(),
                    color + String.valueOf(entityCount),
                    loadedChunks,
                    tileEntities));
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMem = runtime.maxMemory() / 1024 / 1024;
        sender.sendMessage(msg(sender, "tps.memory", usedMem, maxMem));

        if (mspt > 50) {
            sender.sendMessage(msg(sender, "tps.warning-high-mspt"));
            sender.sendMessage(msg(sender, "tps.warning-suggestion"));
            sender.sendMessage(msg(sender, "tps.warning-spark"));
        }
    }

    public String formatTps(double tps) {
        ChatColor color;
        if (tps >= 18.0) {
            color = ChatColor.GREEN;
        } else if (tps >= 15.0) {
            color = ChatColor.YELLOW;
        } else {
            color = ChatColor.RED;
        }
        return color + String.format("%.1f", Math.min(tps, 20.0));
    }
}
