package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.menu.MainConfigMenu;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.diagnostic.TpsDiagnostic;
import com.hilltopvillage.util.LanguageManager;

public class HilltopCommandHandler {

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final GameManager gameManager;
    private final SetupWizard setupWizard;
    private final DeployMenuSender deployMenuSender;
    private final TpsDiagnostic tpsDiagnostic;
    private final Set<UUID> pendingClearConfirm = new HashSet<>();

    public HilltopCommandHandler(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.gameManager = plugin.getGameManager();
        this.setupWizard = plugin.getSetupWizard();
        this.deployMenuSender = new DeployMenuSender(lang);
        this.tpsDiagnostic = new TpsDiagnostic(gameManager, lang);
    }

    public boolean handle(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("hilltop")) return false;

        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        if (args.length < 1) {
            deployMenuSender.sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        // Permission check helper
        if (!isPlayer && !sub.equals("status") && !sub.equals("reloadconfig") && !sub.equals("lang")) {
            sender.sendMessage(lang.get("players-only"));
            return true;
        }

        switch (sub) {
            case "start":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                if (isPlayer) gameManager.addPlayer(player);
                gameManager.startGame();
                sender.sendMessage(lang.getFor(player, "game-starting"));
                break;

            case "stop":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                gameManager.stopGame();
                sender.sendMessage(lang.getFor(player, "game-stopped"));
                break;

            case "join":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                gameManager.addPlayer(player);
                sender.sendMessage(lang.getFor(player, "joined-game"));
                break;

            case "leave":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                gameManager.removePlayer(player);
                sender.sendMessage(lang.getFor(player, "left-game"));
                break;

            case "status":
                sender.sendMessage(lang.getFor(player, "status-header"));
                sender.sendMessage(lang.getFor(player, "status-state", gameManager.getState()));
                sender.sendMessage(lang.getFor(player, "status-wave", gameManager.getCurrentWave()));
                sender.sendMessage(lang.getFor(player, "status-players", gameManager.getPlayers().size()));
                sender.sendMessage(lang.getFor(player, "status-mobs", gameManager.getWaveManager().getRemainingMobCount()));
                sender.sendMessage(lang.getFor(player, "status-nodes",
                        gameManager.getNodeSystem().getActiveCount(), gameManager.getNodeSystem().getTotalCount()));
                break;

            case "hammer":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                player.getInventory().addItem(plugin.getHammerListener().createHammer());
                sender.sendMessage(lang.getFor(player, "received-hammer"));
                break;

            case "fireball":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                player.getInventory().addItem(plugin.getFireballListener().createFireball());
                player.sendMessage(lang.getFor(player, "received-fireball"));
                break;

            case "help":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                deployMenuSender.sendUsage(sender);
                break;

            case "deploy":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                handleDeployCommand(sender, args);
                break;

            case "config":
                if (!sender.hasPermission("hilltopvillage.config.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                handleConfigCommand(sender, args);
                break;

            case "reloadconfig":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                gameManager.getConfigManager().loadFromFile();
                sender.sendMessage(lang.getFor(player, "config-reloaded"));
                break;

            case "lang":
                handleLangCommand(sender, args);
                break;

            case "setup":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                if (args.length >= 2 && setupWizard.hasSession(player)) {
                    setupWizard.handle(player, args[1]);
                } else {
                    setupWizard.start(player);
                }
                break;

            case "tps":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                tpsDiagnostic.printDiagnostic(sender);
                break;

            default:
                deployMenuSender.sendUsage(sender);
                break;
        }

        return true;
    }

    private void handleConfigCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("players-only"));
            return;
        }

        Player admin = (Player) sender;
        ConfigManager cm = gameManager.getConfigManager();

        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("nodes") && args.length >= 4) {
                if (args[2].equalsIgnoreCase("repairitem") && admin.hasPermission("hilltopvillage.config.nodes")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        cm.getActiveConfig().setNodeRepairItem(mat);
                        cm.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.repairitem-set", mat.name()));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
                if (args[2].equalsIgnoreCase("add") && admin.hasPermission("hilltopvillage.config.nodes")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        if (!cm.getActiveConfig().getNodeBlockTypes().contains(mat)) {
                            cm.getActiveConfig().getNodeBlockTypes().add(mat);
                            cm.saveToFile();
                            admin.sendMessage(lang.getFor(admin, "config.node-added", mat.name()));
                        } else {
                            admin.sendMessage(lang.getFor(admin, "config.node-exists"));
                        }
                    } else {
                        admin.sendMessage(lang.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
                if (args[2].equalsIgnoreCase("remove") && admin.hasPermission("hilltopvillage.config.nodes")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        cm.getActiveConfig().getNodeBlockTypes().remove(mat);
                        cm.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.node-removed", mat.name()));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
            }
        }

        MainConfigMenu menu = new MainConfigMenu(admin, cm, lang);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        menu.open();
    }

    private void handleDeployCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("players-only"));
            return;
        }

        Player admin = (Player) sender;
        AdminDeployManager deploy = gameManager.getAdminDeployManager();

        if (args.length < 2) {
            deployMenuSender.sendDeployMenu(admin);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "setcore":
                deploy.setCoreLocation(admin.getLocation());
                plugin.reloadConfig();
                admin.sendMessage(lang.getFor(admin, "deploy.setcore-set"));
                admin.sendMessage(lang.getFor(admin, "deploy.setcore-coords",
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                admin.getWorld().spawnParticle(Particle.VILLAGER_HAPPY,
                        admin.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                break;

            case "addspawn":
                deploy.addSpawnPoint(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.addspawn-added"));
                admin.sendMessage(lang.getFor(admin, "deploy.addspawn-coords",
                        deploy.getSpawnPoints().size() - 1,
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                admin.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        admin.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                break;

            case "listspawns":
                List<Location> points = deploy.getSpawnPoints();
                if (points.isEmpty()) {
                    admin.sendMessage(lang.getFor(admin, "deploy.listspawns-empty"));
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.listspawns-header", points.size()));
                    for (int i = 0; i < points.size(); i++) {
                        Location loc = points.get(i);
                        admin.sendMessage(lang.getFor(admin, "deploy.listspawns-entry", i,
                                String.format("%.1f", loc.getX()),
                                String.format("%.1f", loc.getY()),
                                String.format("%.1f", loc.getZ())));
                    }
                    admin.sendMessage(lang.getFor(admin, "deploy.listspawns-hint"));
                }
                break;

            case "delspawn":
                if (args.length < 3) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-usage"));
                    return;
                }
                try {
                    int index = Integer.parseInt(args[2]);
                    if (deploy.removeSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-deleted", index));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-not-found", index));
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearspawns":
                if (!pendingClearConfirm.contains(admin.getUniqueId())) {
                    pendingClearConfirm.add(admin.getUniqueId());
                    admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-warning", deploy.getSpawnPoints().size()));
                    admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-confirm"));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pendingClearConfirm.remove(admin.getUniqueId())) {
                            admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-expired"));
                        }
                    }, 20L * 15);
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-already"));
                }
                break;

            case "confirmclear":
                if (pendingClearConfirm.remove(admin.getUniqueId())) {
                    int count = deploy.getSpawnPoints().size();
                    deploy.clearSpawnPoints();
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclear-done", count));
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclear-no-pending"));
                }
                break;

            case "reloadnodes":
                deploy.loadSpawnPoints();
                gameManager.getNodeSystem().loadNodesFromWorld();
                admin.sendMessage(lang.getFor(admin, "deploy.reloadnodes-done",
                        gameManager.getNodeSystem().getTotalCount()));
                break;

            case "showparticles":
                deploy.showSpawnPointParticles(admin, lang);
                break;

            default:
                admin.sendMessage(lang.getFor(admin, "deploy.unknown-sub", args[1]));
                admin.sendMessage(lang.getFor(admin, "deploy.unknown-sub-hint"));
                break;
        }
    }

    public void handleLangCommand(CommandSender sender, String[] args) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        if (args.length < 2) {
            sender.sendMessage(lang.getFor(player, "lang.usage"));
            return;
        }

        String locale = args[1].toLowerCase();
        if (!locale.equals("en") && !locale.equals("zh")) {
            sender.sendMessage(lang.getFor(player, "lang.invalid"));
            return;
        }

        lang.setLocale(locale);
        if (isPlayer) {
            lang.setPlayerLocale(player, locale);
        }
        sender.sendMessage(lang.getFor(player, "lang.switched", locale));
    }

}
