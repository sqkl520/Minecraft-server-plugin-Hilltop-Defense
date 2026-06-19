package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class HilltopTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("hilltop")) return null;

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            for (String sub : Arrays.asList("start", "stop", "join", "leave", "status", "hammer", "fireball", "help", "setup", "deploy", "config", "reloadconfig", "lang", "tps")) {
                if (sub.startsWith(input)) {
                    boolean isAdmin = sub.equals("start") || sub.equals("stop") || sub.equals("help")
                            || sub.equals("deploy") || sub.equals("config") || sub.equals("reloadconfig");
                    if (isAdmin && !sender.hasPermission("hilltopvillage.admin")) continue;
                    completions.add(sub);
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("deploy") && sender.hasPermission("hilltopvillage.admin")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase();
            for (String sub : Arrays.asList("setcore", "addspawn", "listspawns", "delspawn", "clearspawns", "confirmclear", "reloadnodes", "showparticles")) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            List<String> completions = new ArrayList<>();
            String input = args[1].toLowerCase();
            for (String sub : Arrays.asList("en", "zh")) {
                if (sub.startsWith(input)) completions.add(sub);
            }
            return completions;
        }

        return new ArrayList<>();
    }
}
