package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import com.hilltopvillage.admin.gui.ArenaManageGUI;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.menu.MainConfigMenu;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.diagnostic.TpsDiagnostic;
import com.hilltopvillage.stats.StatsManager;
import com.hilltopvillage.util.LanguageManager;

public class HilltopCommandHandler {

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final MultiArenaManager arenaManager;
    private final ArenaCommand arenaCommand;
    private final SetupWizard setupWizard;
    private final DeployMenuSender deployMenuSender;
    private final TpsDiagnostic tpsDiagnostic;
    private final Set<UUID> pendingClearConfirm = new HashSet<>();
    /** clearmobspawns 的二次确认集合（与玩家出生点的 pendingClearConfirm 独立，避免互相串确认） */
    private final Set<UUID> pendingClearMobConfirm = new HashSet<>();

    public HilltopCommandHandler(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.arenaManager = plugin.getMultiArenaManager();
        this.arenaCommand = plugin.getArenaCommand();
        this.setupWizard = plugin.getSetupWizard();
        this.deployMenuSender = new DeployMenuSender(lang);
        this.tpsDiagnostic = new TpsDiagnostic(plugin, lang);
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
            case "start": {
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                String startArenaId = args.length >= 2 ? args[1] : null;
                ArenaInstance startArena = arenaManager.resolveArena(startArenaId, player);
                if (startArena == null) {
                    sender.sendMessage(lang.getFor(player, "no-active-arenas"));
                    break;
                }
                GameManager startGm = startArena.getGameManager();
                // Check if game is deployed (spawn points configured)
                if (!startGm.hasSpawnPoints()) {
                    // 走 i18n（按玩家语言）；两键均为仓库既有键，hint 保留「如何部署」的指引
                    sender.sendMessage(lang.getFor(player, "arena-not-deployed"));
                    sender.sendMessage(lang.getFor(player, "arena-not-deployed-hint"));
                    break;
                }
                // STORY-1.2：怪物刷怪坐标同样不能为空（旧存档双用回退的场不受影响）
                if (!startGm.hasEffectiveMobSpawnPoints()) {
                    sender.sendMessage(lang.getFor(player, "arena-no-mob-spawns"));
                    break;
                }
                // Check game state
                if (startGm.getState() != GameState.WAITING) {
                    sender.sendMessage(lang.getFor(player, "game-start-already-running"));
                    break;
                }
                // 人数下限以「本竞技场」的配置为准（arenas.yml 的 game.min-players），
                // 与 ArenaInstance / GameManager 的判定保持同一来源；读全局值会让
                // arena1(=1) 这类 per-arena 配置在命令入口被误拦。
                int minPlayers = startArena.getMinPlayers();
                int currentPlayers = startGm.getPlayerCount();
                if (currentPlayers < minPlayers) {
                    sender.sendMessage(lang.getFor(player, "game-start-need-players",
                            String.valueOf(currentPlayers), String.valueOf(minPlayers)));
                    break;
                }
                startArena.startGame();
                sender.sendMessage(lang.getFor(player, "game-starting"));
                break;
            }

            case "stop": {
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                String stopArenaId = args.length >= 2 ? args[1] : null;
                ArenaInstance stopArena = arenaManager.resolveArena(stopArenaId, player);
                if (stopArena == null) {
                    sender.sendMessage(lang.getFor(player, "no-active-arenas"));
                    break;
                }
                stopArena.stopGame();
                sender.sendMessage(lang.getFor(player, "game-stopped"));
                break;
            }

            case "join":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                // 已在竞技场中时的处理：
                //  · 对局进行中且人在场内 → 明确拒绝（不能半路换场）
                //  · 其余情况（等待中的成员、死亡后重生到别处、被外部插件传送等）
                //    → 视为可切换的等待状态，自动离开原竞技场后继续加入
                if (arenaManager.isInArena(player)) {
                    ArenaInstance current = arenaManager.getPlayerArena(player);
                    boolean inCurrentWorld = current != null
                            && player.getWorld().getName().equals(current.getWorldName());
                    boolean gameRunning = current != null && current.getGameManager().isGameRunning();

                    if (gameRunning && inCurrentWorld) {
                        lang.send(player, "already-in-arena");
                        break;
                    }

                    arenaManager.leaveArena(player);
                    lang.send(player, "arena-auto-left");
                }
                if (args.length >= 2) {
                    arenaManager.joinArena(player, args[1]);
                } else {
                    arenaManager.autoJoinArena(player);
                }
                break;

            case "leave":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                // Check if in arena
                if (!arenaManager.isInArena(player)) {
                    lang.send(player, "not-in-arena");
                    break;
                }
                arenaManager.leaveArena(player);
                lang.send(player, "arena-left");
                break;

            case "status": {
                String statusArenaId = args.length >= 2 ? args[1] : null;
                ArenaInstance statusArena = arenaManager.resolveArena(statusArenaId, player);
                if (statusArena == null) {
                    sender.sendMessage(lang.getFor(player, "no-active-arenas"));
                    break;
                }
                GameManager statusGm = statusArena.getGameManager();
                sender.sendMessage(lang.getFor(player, "status-header"));
                sender.sendMessage(lang.getFor(player, "status-state", statusGm.getState()));
                sender.sendMessage(lang.getFor(player, "status-wave", statusGm.getCurrentWave()));
                sender.sendMessage(lang.getFor(player, "status-players", statusGm.getPlayers().size()));
                sender.sendMessage(lang.getFor(player, "status-mobs", statusGm.getWaveManager().getRemainingMobCount()));
                sender.sendMessage(lang.getFor(player, "status-nodes",
                        statusGm.getNodeSystem().getActiveCount(), statusGm.getNodeSystem().getTotalCount()));
                sender.sendMessage(lang.getFor(player, "status-spectators",
                        String.valueOf(statusArena.getSpectatorCount())));
                break;
            }

            case "hammer":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                player.getInventory().addItem(plugin.getHammerListener().createHammer());
                sender.sendMessage(lang.getFor(player, "received-hammer"));
                break;

            case "fireball":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
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
                plugin.getConfigManager().loadFromFile();
                sender.sendMessage(lang.getFor(player, "config-reloaded"));
                plugin.getLanguageManager().reload();
                sender.sendMessage(lang.getFor(player, "messages-reloaded"));
                // 竞技场参数来自 arenas.yml，无法热重载，明确告知管理员需重启
                sender.sendMessage(lang.getFor(player, "config.reload-arena-notice"));
                break;

            case "lang":
                handleLangCommand(sender, args);
                break;

            case "setup":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
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

            case "gui":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                plugin.getArenaSelectionGUI().openGUI(player);
                break;

            case "build":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                plugin.getLobbyProtectionListener().toggleBuildMode(player);
                break;

            case "stats":
                handleStatsCommand(sender, args);
                break;

            case "top":
                handleTopCommand(sender, args);
                break;

            case "list":
                arenaCommand.onCommand(sender, command, label, new String[]{"list"});
                break;

            case "arenas":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                arenaCommand.onCommand(sender, command, label, new String[]{"arenas"});
                break;

            case "manage":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                handleManage(player);
                break;

            case "lobby":
                if (!isPlayer) {
                    sender.sendMessage(lang.get("players-only"));
                    return true;
                }
                handleLobbyCommand(player);
                break;

            case "enable":
                handleEnableArena(sender, args, player);
                break;

            case "disable":
                handleDisableArena(sender, args, player);
                break;

            case "shop":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(lang.getFor(player, "no-permission"));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
                    plugin.getShopManager().reload();
                    int itemCount = plugin.getShopManager().getShopConfig().getItems().size();
                    sender.sendMessage(lang.getFor(player, "shop-reloaded", String.valueOf(itemCount)));
                    break;
                }
                sender.sendMessage(lang.getFor(player, "usage-shop-reload"));
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
        ConfigManager cm = plugin.getConfigManager();

        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("nodes") && args.length >= 4) {
                if (!admin.hasPermission("hilltopvillage.config.nodes")) {
                    admin.sendMessage(lang.getFor(admin, "no-permission"));
                    return;
                }
                if (args[2].equalsIgnoreCase("repairitem")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        cm.getActiveConfig().setRepairCostItem(mat);
                        cm.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.repairitem-set", mat.name()));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
                // 节点方块类型固定为重生锚（见 NodeSystem#loadConfig），不再提供 add / remove 入口
                admin.sendMessage(lang.getFor(admin, "config.unknown-sub", args[2]));
                return;
            }
        }

        MainConfigMenu menu = new MainConfigMenu(admin, cm, lang);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        menu.open();
    }

    /**
     * /hilltop manage
     * 打开竞技场管理 GUI（竞技场列表 → 单竞技场详情 → 重置 / 条目删除 / 粒子预览）。
     *
     * <p>与 {@link #handleConfigCommand} 的 {@code MainConfigMenu} 同一套写法：
     * <b>先注册监听器再 open()</b>，否则打开后的第一次点击无人处理；
     * GUI 关闭时由 {@code AbstractConfigMenu} 自行 {@code HandlerList.unregisterAll}，
     * 这里不需要额外反注册。</p>
     *
     * <p>本功能不提供 {@code /hilltop manage <竞技场ID>} 直达形式，
     * 目标竞技场一律在 GUI 内选择。</p>
     */
    private void handleManage(Player admin) {
        ArenaManageGUI gui = new ArenaManageGUI(admin, plugin);
        Bukkit.getPluginManager().registerEvents(gui, plugin);
        gui.open();
    }

    private void handleDeployCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("players-only"));
            return;
        }

        Player admin = (Player) sender;

        // 解析目标竞技场：玩家所在竞技场 → 默认竞技场 → 第一个活动竞技场
        ArenaInstance deployArena = arenaManager.resolveArena(null, admin);
        if (deployArena == null) {
            admin.sendMessage(lang.getFor(admin, "no-active-arenas"));
            return;
        }

        // 部署必须在目标竞技场世界内进行，否则会把大厅坐标误写成竞技场坐标
        if (!admin.getWorld().getName().equals(deployArena.getWorldName())) {
            admin.sendMessage(lang.getFor(admin, "deploy.must-be-in-arena-world",
                    deployArena.getDisplayName(), deployArena.getWorldName()));
            return;
        }

        GameManager deployGm = deployArena.getGameManager();
        AdminDeployManager deploy = deployGm.getAdminDeployManager();

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
                admin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
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

            case "addmobspawn":
                deploy.addMobSpawnPoint(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.mobspawn-added"));
                admin.sendMessage(lang.getFor(admin, "deploy.mobspawn-coords",
                        deploy.getMobSpawnPoints().size() - 1,
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                admin.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        admin.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                break;

            case "listmobspawns":
                List<Location> mobPoints = deploy.getMobSpawnPoints();
                if (mobPoints.isEmpty()) {
                    admin.sendMessage(lang.getFor(admin, "deploy.listmobspawns-empty"));
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.listmobspawns-header", mobPoints.size()));
                    for (int i = 0; i < mobPoints.size(); i++) {
                        Location loc = mobPoints.get(i);
                        admin.sendMessage(lang.getFor(admin, "deploy.listmobspawns-entry", i,
                                String.format("%.1f", loc.getX()),
                                String.format("%.1f", loc.getY()),
                                String.format("%.1f", loc.getZ())));
                    }
                    admin.sendMessage(lang.getFor(admin, "deploy.listmobspawns-hint"));
                }
                break;

            case "delmobspawn":
                if (args.length < 3) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-usage"));
                    return;
                }
                try {
                    int index = Integer.parseInt(args[2]);
                    if (deploy.removeMobSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-deleted", index));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-not-found", index));
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearmobspawns":
                if (!pendingClearMobConfirm.contains(admin.getUniqueId())) {
                    pendingClearMobConfirm.add(admin.getUniqueId());
                    admin.sendMessage(lang.getFor(admin, "deploy.clearmobspawns-warning", deploy.getMobSpawnPoints().size()));
                    admin.sendMessage(lang.getFor(admin, "deploy.clearmobspawns-confirm"));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pendingClearMobConfirm.remove(admin.getUniqueId())) {
                            admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-expired"));
                        }
                    }, 20L * 15);
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-already"));
                }
                break;

            case "confirmclearmob":
                if (pendingClearMobConfirm.remove(admin.getUniqueId())) {
                    int count = deploy.getMobSpawnPoints().size();
                    deploy.clearMobSpawnPoints();
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclearmob-done", count));
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclearmob-no-pending"));
                }
                break;

            case "reloadnodes":
                deploy.loadSpawnPoints();
                deployGm.getNodeSystem().loadNodesFromConfig();
                admin.sendMessage(lang.getFor(admin, "deploy.reloadnodes-done",
                        deployGm.getNodeSystem().getTotalCount()));
                break;

            case "showparticles":
                deploy.showSpawnPointParticles(admin, lang);
                break;

            case "addvillager":
                deploy.addVillager(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.addvillager-added"));
                admin.sendMessage(lang.getFor(admin, "deploy.addvillager-coords",
                        deploy.getVillagers().size() - 1,
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                admin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        admin.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
                admin.playSound(admin.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
                break;

            case "listvillagers": {
                List<Location> villagers = deploy.getVillagers();
                if (villagers.isEmpty()) {
                    admin.sendMessage(lang.getFor(admin, "deploy.listvillagers-empty"));
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.listvillagers-header", villagers.size()));
                    for (int i = 0; i < villagers.size(); i++) {
                        Location loc = villagers.get(i);
                        admin.sendMessage(lang.getFor(admin, "deploy.listvillagers-entry", i,
                                String.format("%.1f", loc.getX()),
                                String.format("%.1f", loc.getY()),
                                String.format("%.1f", loc.getZ())));
                    }
                }
                break;
            }

            case "delvillager":
                if (args.length < 3) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delvillager-usage"));
                    return;
                }
                try {
                    int index = Integer.parseInt(args[2]);
                    if (deploy.removeVillager(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-deleted", index));
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-not-found", index));
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearvillagers":
                deploy.clearVillagers();
                admin.sendMessage(lang.getFor(admin, "deploy.clearvillagers-done", 0));
                break;

            default:
                admin.sendMessage(lang.getFor(admin, "deploy.unknown-sub", args[1]));
                admin.sendMessage(lang.getFor(admin, "deploy.unknown-sub-hint"));
                break;
        }
    }

    /**
     * /hilltop lobby
     * 返回大厅。若玩家在竞技场中则先正常离开（归还物品），否则直接传送。
     */
    private void handleLobbyCommand(Player player) {
        if (arenaManager.isInArena(player)) {
            arenaManager.leaveArena(player);
            lang.send(player, "arena-left");
            return;
        }
        arenaManager.sendToLobby(player);
        lang.send(player, "lobby-teleported");
    }

    /**
     * /hilltop enable [竞技场]
     * 把竞技场加入启用队列（对应 BedWars1058 的 {@code /bw enable}）。
     */
    private void handleEnableArena(CommandSender sender, String[] args, Player player) {
        if (!sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(lang.getFor(player, "no-permission"));
            return;
        }

        String arenaId = arenaManager.resolveArenaId(args.length >= 2 ? args[1] : null, player);
        if (arenaId == null) {
            sender.sendMessage(lang.getFor(player, "no-active-arenas"));
            return;
        }

        ArenaStatus before = arenaManager.getStatus(arenaId);

        if (before == ArenaStatus.READY) {
            sender.sendMessage(lang.getFor(player, "arena-enable-already-ready", arenaId));
            return;
        }
        if (before.isPending()) {
            sender.sendMessage(lang.getFor(player, "arena-enable-already-queued", arenaId));
            return;
        }

        if (arenaManager.enableArena(arenaId)) {
            sender.sendMessage(lang.getFor(player, "arena-enable-queued", arenaId));
        } else {
            sender.sendMessage(lang.getFor(player, "arena-enable-failed", arenaId));
        }
    }

    /**
     * /hilltop disable [竞技场]
     * 禁用竞技场用于维护（对应 BedWars1058 的 {@code /bw disable}）。
     * 对局进行中会被拒绝。
     */
    private void handleDisableArena(CommandSender sender, String[] args, Player player) {
        if (!sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(lang.getFor(player, "no-permission"));
            return;
        }

        String arenaId = arenaManager.resolveArenaId(args.length >= 2 ? args[1] : null, player);
        if (arenaId == null) {
            sender.sendMessage(lang.getFor(player, "no-active-arenas"));
            return;
        }

        String result = arenaManager.disableArena(arenaId);

        switch (result) {
            case "deploy.ok" -> sender.sendMessage(lang.getFor(player, "arena-disabled-ok", arenaId));
            case "arena-already-disabled" -> sender.sendMessage(lang.getFor(player, "arena-already-disabled", arenaId));
            case "arena-disable-in-progress" -> sender.sendMessage(lang.getFor(player, "arena-disable-in-progress", arenaId));
            default -> sender.sendMessage(lang.getFor(player, "arena-not-found", arenaId));
        }
    }

    /**
     * /hilltop stats [玩家名]
     * 查看自己或他人的持久统计。
     */
    private void handleStatsCommand(CommandSender sender, String[] args) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        StatsManager stats = plugin.getStatsManager();
        if (stats == null) {
            sender.sendMessage(lang.getFor(player, "stats.disabled"));
            return;
        }

        StatsManager.PlayerStats target;
        String displayName;

        if (args.length >= 2) {
            target = stats.findByName(args[1]);
            if (target == null) {
                sender.sendMessage(lang.getFor(player, "stats.player-not-found", args[1]));
                return;
            }
            displayName = target.getName();
        } else {
            if (!isPlayer) {
                sender.sendMessage(lang.getFor(null, "stats.usage"));
                return;
            }
            target = stats.get(player.getUniqueId());
            if (target.getName() == null || target.getName().isEmpty()) {
                target = null;
            }
            if (target == null) {
                sender.sendMessage(lang.getFor(player, "stats.player-not-found", player.getName()));
                return;
            }
            displayName = player.getName();
        }

        sender.sendMessage(lang.getFor(player, "stats.header", displayName));
        sender.sendMessage(lang.getFor(player, "stats.games", String.valueOf(target.getGamesPlayed())));
        sender.sendMessage(lang.getFor(player, "stats.win-loss",
                String.valueOf(target.getWins()),
                String.valueOf(target.getLosses()),
                String.format("%.0f", target.getWinRate() * 100)));
        sender.sendMessage(lang.getFor(player, "stats.kills-deaths",
                String.valueOf(target.getKills()),
                String.valueOf(target.getDeaths()),
                formatRatio(target)));
        sender.sendMessage(lang.getFor(player, "stats.nodes", String.valueOf(target.getNodesRepaired())));
        sender.sendMessage(lang.getFor(player, "stats.waves",
                String.valueOf(target.getWavesSurvived()),
                String.valueOf(target.getBestWave())));

        if (args.length < 2) {
            sender.sendMessage(lang.getFor(player, "stats.self-hint"));
        }
    }

    private String formatRatio(StatsManager.PlayerStats target) {
        if (target.getDeaths() == 0) {
            return target.getKills() == 0 ? "0.00" : String.format("%.2f", (double) target.getKills());
        }
        return String.format("%.2f", (double) target.getKills() / target.getDeaths());
    }

    /**
     * /hilltop top <类型>
     * 查看排行榜（kills / wins / games / best-wave / nodes / deaths）。
     */
    private void handleTopCommand(CommandSender sender, String[] args) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        StatsManager stats = plugin.getStatsManager();
        if (stats == null) {
            sender.sendMessage(lang.getFor(player, "stats.disabled"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(lang.getFor(player, "top.usage", StatsManager.StatType.allKeys()));
            return;
        }

        StatsManager.StatType type = StatsManager.StatType.fromKey(args[1]);
        if (type == null) {
            sender.sendMessage(lang.getFor(player, "top.types", StatsManager.StatType.allKeys()));
            return;
        }

        List<Map.Entry<UUID, StatsManager.PlayerStats>> top = stats.top(type, 10);
        if (top.isEmpty()) {
            sender.sendMessage(lang.getFor(player, "top.empty"));
            return;
        }

        sender.sendMessage(lang.getFor(player, "top.header", type.getKey().toUpperCase(), String.valueOf(top.size())));
        int rank = 1;
        for (Map.Entry<UUID, StatsManager.PlayerStats> entry : top) {
            sender.sendMessage(lang.getFor(player, "top.entry",
                    String.valueOf(rank++),
                    entry.getValue().getName(),
                    String.valueOf(statValue(type, entry.getValue()))));
        }
    }

    private int statValue(StatsManager.StatType type, StatsManager.PlayerStats stats) {
        return switch (type) {
            case KILLS -> stats.getKills();
            case WINS -> stats.getWins();
            case GAMES -> stats.getGamesPlayed();
            case BEST_WAVE -> stats.getBestWave();
            case NODES -> stats.getNodesRepaired();
            case DEATHS -> stats.getDeaths();
        };
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
