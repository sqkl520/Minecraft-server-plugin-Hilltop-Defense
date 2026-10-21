package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;

public class HilltopTabCompleter implements TabCompleter {

    /** 需要补全竞技场 ID 的子命令 */
    private static final List<String> ARENA_ARG_COMMANDS =
            Arrays.asList("start", "stop", "status", "join", "enable", "disable");

    private final HilltopVillagePlugin plugin;

    public HilltopTabCompleter(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 所有已配置的竞技场 ID（含已禁用/未启用的，便于管理员用 /hilltop enable 恢复）。
     */
    private List<String> arenaIds(String input) {
        List<String> ids = new ArrayList<>();
        for (var definition : plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list()) {
            if (definition.getId().toLowerCase(Locale.ROOT).startsWith(input)) {
                ids.add(definition.getId());
            }
        }
        return ids;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("hilltop")) return null;

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase(Locale.ROOT);

            for (String sub : Arrays.asList("start", "stop", "join", "leave", "lobby", "status", "list", "arenas",
                    "manage", "enable", "disable", "hammer", "fireball", "help", "setup", "deploy", "config",
                    "reloadconfig", "shop", "lang", "tps", "gui", "build", "stats", "top", "wave")) {
                if (sub.startsWith(input)) {
                    // fix6 Y3-R07-08：help 撤出 admin 门（命令体已开放给玩家，补全同步）
                    boolean isAdmin = sub.equals("start") || sub.equals("stop")
                            || sub.equals("setup") || sub.equals("deploy") || sub.equals("config")
                            || sub.equals("reloadconfig") || sub.equals("shop") || sub.equals("tps")
                            || sub.equals("build") || sub.equals("arenas") || sub.equals("manage")
                            || sub.equals("enable") || sub.equals("disable") || sub.equals("wave")
                            || sub.equals("hammer") || sub.equals("fireball");
                    if (isAdmin && !sender.hasPermission("hilltopvillage.admin")) continue;
                    completions.add(sub);
                }
            }
            return completions;
        }

        // 第 2 个参数：竞技场 ID
        if (args.length == 2 && ARENA_ARG_COMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean adminOnly = sub.equals("start") || sub.equals("stop")
                    || sub.equals("enable") || sub.equals("disable");
            if (adminOnly && !sender.hasPermission("hilltopvillage.admin")) return new ArrayList<>();
            return arenaIds(args[1].toLowerCase(Locale.ROOT));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("shop") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("reload", "give")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        // /hilltop lobby setspawn：补全 setspawn 子命令（仅管理员）
        if (args.length == 2 && args[0].equalsIgnoreCase("lobby") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            if ("setspawn".startsWith(args[1].toLowerCase(Locale.ROOT))) completions.add("setspawn");
            return completions;
        }

        // /hilltop wave <clear|skip>
        if (args.length == 2 && args[0].equalsIgnoreCase("wave") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("clear", "skip")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        // /hilltop wave <clear|skip> <竞技场>
        if (args.length == 3 && args[0].equalsIgnoreCase("wave")
                && sender.hasPermission("hilltopvillage.admin")) {
            return arenaIds(args[2].toLowerCase(Locale.ROOT));
        }

        // /hilltop shop give <玩家>：补全在线玩家名
        if (args.length == 3 && args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("give")
                && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[2].toLowerCase(Locale.ROOT);
            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    completions.add(online.getName());
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("deploy") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("help", "setcore", "addspawn", "listspawns", "delspawn", "clearspawns",
                    "confirmclear", "reloadnodes", "showparticles",
                    "addmobspawn", "listmobspawns", "delmobspawn", "clearmobspawns", "confirmclearmob",
                    "addvillager", "listvillagers", "delvillager", "clearvillagers", "confirmclearvillagers",
                    "setshop", "clearshop")) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("setup") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("confirm-world", "confirm-core", "addspawn", "overview", "spawngui", "spawns-done",
                    "addmobspawn", "mobspawngui",
                    "addnode", "nodesgui", "show-particles",
                    "confirm-nodes", "rescan", "villagers-done", "confirm-shop", "showshopparticles", "clear-shop",
                    "addvillager", "villagersgui", "saveexit",
                    "preview", "goto")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        // M-01：/hilltop setup goto <N> —— 补全 1-8 步骤数字
        if (args.length == 3 && args[0].equalsIgnoreCase("setup") && args[1].equalsIgnoreCase("goto")
                && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[2];
            for (int step = 1; step <= 8; step++) {
                String value = String.valueOf(step);
                if (value.startsWith(input)) completions.add(value);
            }
            return completions;
        }

        // /hilltop stats [玩家名]：补全在线玩家名
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    completions.add(online.getName());
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("kills", "wins", "games", "best-wave", "nodes", "deaths", "crystals")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String sub : Arrays.asList("en", "zh")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        return new ArrayList<>();
    }
}
