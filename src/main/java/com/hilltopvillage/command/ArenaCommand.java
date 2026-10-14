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
            // C3-02：joinArena 全失败路径均已在内部发送精确原因（arena-not-found / arena-full /
            // game-in-progress / arena-not-deployed …），此处不再补发泛化 join-failed（避免双消息叠报）
            arenaManager.joinArena(player, args[1]);
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
        sender.sendMessage(forSender(sender, "arena-list-header"));

        var arenas = arenaManager.getActiveArenas();
        if (arenas.isEmpty()) {
            sender.sendMessage(forSender(sender, "no-active-arenas"));
            return;
        }

        for (ArenaInstance arena : arenas) {
            sender.sendMessage(forSender(sender, "arena-list-entry",
                    arena.getArenaId(),
                    arena.getDisplayName(),
                    getArenaStatus(sender, arena),
                    arena.getPlayerCount() + "/" + arena.getMaxPlayers()));
        }

        sender.sendMessage(forSender(sender, "arena-list-footer"));
    }

    /**
     * /hilltop arenas
     * Show detailed arena status (admin command).
     */
    private void handleArenas(CommandSender sender) {
        if (!sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(forSender(sender, "no-permission"));
            return;
        }

        sender.sendMessage(forSender(sender, "arena-status-header"));
        // countByState 返回 int[]：必须逐个拆包再传。直接把 int[] 丢给 Object... 变参，
        // Java 会把它当成「单个参数」（int[] 不是 Object[]），{0} 会打成 [I@xxx。
        int[] counts = arenaManager.countByState(GameState.WAITING, GameState.STARTING,
                GameState.RUNNING, GameState.ENDING);
        sender.sendMessage(forSender(sender, "arena-status-statistics",
                counts[0], counts[1], counts[2], counts[3], counts[4]));
        sender.sendMessage("");

        // 遍历所有「已配置」的竞技场（含已禁用/排队中的，它们没有运行时实例）
        for (var definition : arenaManager.getDefinitionManager().getRegistry().list()) {
            String id = definition.getId();
            ArenaStatus status = arenaManager.getStatus(id);
            ArenaInstance arena = arenaManager.getActiveArenas().stream()
                    .filter(a -> a.getArenaId().equals(id)).findFirst().orElse(null);

            sender.sendMessage(forSender(sender, "arena-detail-title", id,
                    definition.getDisplayName() == null ? id
                            : org.bukkit.ChatColor.translateAlternateColorCodes('&', definition.getDisplayName())));

            String statusLine = switch (status) {
                case DISABLED -> forSender(sender, "arena-status-disabled");
                case QUEUED -> forSender(sender, "arena-status-queued");
                case LOADING -> forSender(sender, "arena-status-loading");
                case READY -> arena == null
                        ? forSender(sender, "arena-detail-status-unknown")
                        : getArenaStatus(sender, arena);
            };
            sender.sendMessage(forSender(sender, "arena-detail-status",
                    statusLine, describeIssue(sender, definition, arena, status)));

            if (arena != null) {
                sender.sendMessage(forSender(sender, "arena-detail-players",
                        arena.getPlayerCount(), arena.getMaxPlayers(), arena.getMinPlayers()));
                sender.sendMessage(forSender(sender, "arena-detail-wave",
                        arena.getCurrentWave(), arena.getTotalWaves()));
            }
            String worldSuffix = org.bukkit.Bukkit.getWorld(definition.getWorldName()) == null
                    ? forSender(sender, "arena-detail-world-unloaded") : "";
            sender.sendMessage(forSender(sender, "arena-detail-world",
                    definition.getWorldName(), worldSuffix));
            sender.sendMessage("");
        }
    }

    /** 按具体原因给出可操作的排查提示 */
    private String describeIssue(CommandSender sender,
                                 com.hilltopvillage.arena.ArenaDefinition definition,
                                 ArenaInstance arena, ArenaStatus status) {
        if (status == ArenaStatus.DISABLED && !definition.isEnabled()) {
            return forSender(sender, "arena-issue-flag-disabled", definition.getId());
        }
        if (status == ArenaStatus.DISABLED) {
            return forSender(sender, "arena-issue-disabled", definition.getId());
        }
        if (org.bukkit.Bukkit.getWorld(definition.getWorldName()) == null) {
            return forSender(sender, "arena-issue-world");
        }
        if (arena != null && !arena.isDeployed()) {
            return forSender(sender, "arena-issue-undeployed");
        }
        return "";
    }

    /**
     * Get arena status display string.
     *
     * <p>与 {@code ArenaSelectionGUI#getStatusText} 保持同一套判定口径：
     * 禁用 → 地图缺失 → 未部署 → 状态机，便于玩家把两个界面看到的状态对上号。
     * （ArenaCommand 之前漏了地图缺失分支，已补齐。）</p>
     */
    private String getArenaStatus(CommandSender sender, ArenaInstance arena) {
        if (!arena.isEnabled()) return forSender(sender, "arena-disabled");
        if (!arena.isWorldAvailable()) return forSender(sender, "arena-status-map-missing");
        if (!arena.isDeployed()) return forSender(sender, "arena-status-undeployed");
        GameState state = arena.getState();
        return switch (state) {
            case WAITING -> forSender(sender, "arena-status-waiting");
            case STARTING -> forSender(sender, "arena-status-starting");
            case RUNNING, WAVE_INTERMISSION -> forSender(sender, "arena-status-running");
            case ENDING -> forSender(sender, "arena-status-ending");
            case VICTORY -> forSender(sender, "state.victory");
            case DEFEAT -> forSender(sender, "state.defeat");
        };
    }

    /** 取发送者语言的文本：玩家用个人语言，控制台用全局默认语言 */
    private String forSender(CommandSender sender, String key, Object... args) {
        if (sender instanceof Player player) {
            return languageManager.getFor(player, key, args);
        }
        return languageManager.get(key, languageManager.getLocale(), args);
    }

    /**
     * Send command help.
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(forSender(sender, "arena-list-help-title"));
        sender.sendMessage(forSender(sender, "usage-join"));
        sender.sendMessage(forSender(sender, "usage-leave"));
        sender.sendMessage(forSender(sender, "usage-list"));

        if (sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(forSender(sender, "arena-list-help-admin"));
            sender.sendMessage(forSender(sender, "usage-arenas"));
            sender.sendMessage(forSender(sender, "usage-start"));
            sender.sendMessage(forSender(sender, "usage-stop"));
        }
    }
}
