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

import java.util.Locale;

/**
 * Arena list/status view commands (internal form).
 *
 * <p>fix6 Y4-B25：本类只保留 HCH 硬编码调用的两种形态（{@code list} / {@code arenas}）。
 * 旧的 join/leave/无参/help 分支自 fix3 起即不可达（唯一调用方 HilltopCommandHandler
 * 只以 {@code new String[]{"list"}} / {@code new String[]{"arenas"}} 进入），本轮删除。</p>
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
        // 仅提供内部调用形态：HCH 以 list/arenas 两形态调用（join/leave 等命令入口由 HCH 路由，
        // sendHelp 的消费点已随本收窄一并消失）
        if (args.length == 0) return true;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender);
            case "arenas" -> handleArenas(sender);
            default -> { }
        }
        return true;
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
}
