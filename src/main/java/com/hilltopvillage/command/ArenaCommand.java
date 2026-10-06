package com.hilltopvillage.command;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Arena join/leave commands.
 * Similar to BedWars1058's join/leave system.
 *
 * Commands:
 * - /hilltop join [arena] - join an arena (auto-join if no arena specified)
 * - /hilltop leave - leave current arena
 * - /hilltop list - list all available arenas
 * - /hilltop arenas - show arena status (admin)
 */
public class ArenaCommand implements CommandExecutor {

    private final HilltopVillagePlugin plugin;
    private final MultiArenaManager arenaManager;
    private final LanguageManager languageManager;

    public ArenaCommand(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.arenaManager = plugin.getMultiArenaManager();
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "join" -> handleJoin(sender, args);
            case "leave" -> handleLeave(sender);
            case "list" -> handleList(sender);
            case "arenas" -> handleArenas(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    /**
     * /hilltop join [arena]
     * Join an arena (auto-join if no arena specified).
     */
    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(languageManager.get("player-only-command"));
            return;
        }

        // Check if already in arena
        if (arenaManager.isInArena(player)) {
            languageManager.send(player, "already-in-arena");
            return;
        }

        if (args.length == 1) {
            // Auto-join
            if (arenaManager.autoJoinArena(player)) {
                // Success message sent by arena
            } else {
                languageManager.send(player, "no-arenas-available");
            }
        } else {
            // Join specific arena
            String arenaId = args[1];
            if (arenaManager.joinArena(player, arenaId)) {
                // Success message sent by arena
            } else {
                languageManager.send(player, "join-failed", arenaId);
            }
        }
    }

    /**
     * /hilltop leave
     * Leave current arena.
     */
    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(languageManager.get("player-only-command"));
            return;
        }

        ArenaInstance arena = arenaManager.getPlayerArena(player);
        if (arena == null) {
            languageManager.send(player, "not-in-arena");
            return;
        }

        arenaManager.leaveArena(player);
        languageManager.send(player, "arena-left");
    }

    /**
     * /hilltop list
     * List all available arenas with status.
     */
    private void handleList(CommandSender sender) {
        sender.sendMessage(languageManager.get("arena-list-header"));

        var arenas = arenaManager.getActiveArenas();
        if (arenas.isEmpty()) {
            sender.sendMessage(languageManager.get("no-active-arenas"));
            return;
        }

        for (ArenaInstance arena : arenas) {
            String status = getArenaStatus(arena);
            String players = arena.getPlayerCount() + "/" + arena.getMaxPlayers();

            sender.sendMessage(String.format("§7[%s] §e%s §7- %s §7- §a%s",
                    arena.getArenaId(),
                    arena.getDisplayName(),
                    status,
                    players));
        }

        sender.sendMessage(languageManager.get("arena-list-footer"));
    }

    /**
     * /hilltop arenas
     * Show detailed arena status (admin command).
     */
    private void handleArenas(CommandSender sender) {
        if (!sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(languageManager.get("no-permission"));
            return;
        }

        sender.sendMessage(languageManager.get("arena-status-header"));
        sender.sendMessage(arenaManager.getStatistics());
        sender.sendMessage("");

        // 遍历所有「已配置」的竞技场（含已禁用/排队中的，它们没有运行时实例）
        for (var definition : arenaManager.getDefinitionManager().getRegistry().list()) {
            String id = definition.getId();
            ArenaStatus status = arenaManager.getStatus(id);
            ArenaInstance arena = arenaManager.getActiveArenas().stream()
                    .filter(a -> a.getArenaId().equals(id)).findFirst().orElse(null);

            sender.sendMessage(String.format("§e%s §7(%s)", id, definition.getDisplayName() == null ? id
                    : org.bukkit.ChatColor.translateAlternateColorCodes('&', definition.getDisplayName())));

            String statusLine = switch (status) {
                case DISABLED -> "§8已禁用";
                case QUEUED -> "§e排队启用中";
                case LOADING -> "§6加载中...";
                case READY -> arena == null ? "§7未知" : getArenaStatus(arena);
            };
            sender.sendMessage(String.format("  §7状态: %s%s", statusLine, describeIssue(definition, arena, status)));

            if (arena != null) {
                sender.sendMessage(String.format("  §7玩家: §a%d§7/§e%d §7(最少: §c%d§7)",
                        arena.getPlayerCount(), arena.getMaxPlayers(), arena.getMinPlayers()));
                sender.sendMessage(String.format("  §7波次: §6%d§7/§e%d",
                        arena.getCurrentWave(), arena.getTotalWaves()));
            }
            sender.sendMessage(String.format("  §7世界: §b%s%s",
                    definition.getWorldName(),
                    org.bukkit.Bukkit.getWorld(definition.getWorldName()) == null ? " §c(未加载)" : ""));
            sender.sendMessage("");
        }
    }

    /** 按具体原因给出可操作的排查提示 */
    private String describeIssue(com.hilltopvillage.arena.ArenaDefinition definition,
                                 ArenaInstance arena, ArenaStatus status) {
        if (status == ArenaStatus.DISABLED && !definition.isEnabled()) {
            return " §7(arenas.yml 中 enabled: false → 改为 true 后 /hilltop enable " + definition.getId() + ")";
        }
        if (status == ArenaStatus.DISABLED) {
            return " §7(被 /hilltop disable 关闭 → 用 /hilltop enable " + definition.getId() + " 恢复)";
        }
        if (org.bukkit.Bukkit.getWorld(definition.getWorldName()) == null) {
            return " §c(世界未加载 → 检查世界文件夹是否存在)";
        }
        if (arena != null && !arena.isDeployed()) {
            return " §7(尚未配置生成点 → 运行 /hilltop setup)";
        }
        return "";
    }

    /**
     * Get arena status display string.
     */
    private String getArenaStatus(ArenaInstance arena) {
        if (!arena.isEnabled()) return "§8已禁用";
        if (!arena.isDeployed()) return "§7未部署";
        GameState state = arena.getState();
        return switch (state) {
            case WAITING -> "§a等待中";
            case STARTING -> "§6准备中";
            case RUNNING -> "§c进行中";
            case ENDING -> "§8结束中";
            default -> "§7未知";
        };
    }

    /**
     * Send command help.
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l=== Hilltop Village 命令 ===");
        sender.sendMessage("§e/hilltop join [arena] §7- 加入竞技场");
        sender.sendMessage("§e/hilltop leave §7- 离开当前竞技场");
        sender.sendMessage("§e/hilltop list §7- 列出所有竞技场");

        if (sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage("§c管理员命令:");
            sender.sendMessage("§e/hilltop arenas §7- 查看详细状态");
            sender.sendMessage("§e/hilltop start <arena> §7- 启动竞技场");
            sender.sendMessage("§e/hilltop stop <arena> §7- 停止竞技场");
        }
    }
}
