package com.hilltopvillage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.menu.MainConfigMenu;
import com.hilltopvillage.config.menu.NumericInputHandler;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.mechanics.FireballListener;
import com.hilltopvillage.mechanics.HammerListener;
import com.hilltopvillage.mechanics.NodeListener;
import com.hilltopvillage.util.LanguageManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class HilltopVillagePlugin extends JavaPlugin implements Listener, TabCompleter {

    private GameManager gameManager;
    private HammerListener hammerListener;
    private NodeListener nodeListener;
    private FireballListener fireballListener;
    private LanguageManager languageManager;
    private final Set<UUID> pendingClearConfirm = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.languageManager = new LanguageManager(this);
        this.gameManager = new GameManager(this);
        this.hammerListener = new HammerListener(gameManager);
        this.nodeListener = new NodeListener(gameManager);
        this.fireballListener = new FireballListener(gameManager);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(hammerListener, this);
        Bukkit.getPluginManager().registerEvents(nodeListener, this);
        Bukkit.getPluginManager().registerEvents(fireballListener, this);
        Bukkit.getPluginManager().registerEvents(new NumericInputHandler(), this);

        getCommand("hilltop").setTabCompleter(this);

        generateCommandsDocument();

        getLogger().info("HilltopVillage - Village Defense PLUS has been enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stopGame();
            gameManager.getNodeSystem().stopBuffTask();
            gameManager.getDisplayEntityManager().cleanupAll();
        }

        getLogger().info("HilltopVillage has been disabled.");
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball snowball = (Snowball) event.getEntity();

        if (!"hook_claw".equals(snowball.getCustomName())) return;

        if (event.getHitEntity() instanceof Player) {
            Player hitPlayer = (Player) event.getHitEntity();
            HookClawHunterGoal.onHookHit(hitPlayer, snowball, gameManager);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMobDeath(EntityDeathEvent event) {
        if (!gameManager.isGameRunning()) return;

        if (gameManager.getWaveManager().getActiveMobs().contains(event.getEntity())) {
            gameManager.getWaveManager().onMobDeath(event.getEntity());

            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                gameManager.getPlayerData(killer).addKill();
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (gameManager.getState() == com.hilltopvillage.core.GameState.WAITING
                || gameManager.getState() == com.hilltopvillage.core.GameState.STARTING) {
            gameManager.addPlayer(player);
            player.sendMessage(languageManager.getFor(player, "join-queue"));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (gameManager.getPlayers().contains(event.getPlayer().getUniqueId())) {
            gameManager.removePlayer(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("hilltop")) return false;

        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        // Permission check helper
        if (!isPlayer && !sub.equals("status") && !sub.equals("reloadconfig") && !sub.equals("lang")) {
            sender.sendMessage(languageManager.get("players-only"));
            return true;
        }

        switch (sub) {
            case "start":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                if (isPlayer) gameManager.addPlayer(player);
                gameManager.startGame();
                sender.sendMessage(languageManager.getFor(player, "game-starting"));
                break;

            case "stop":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                gameManager.stopGame();
                sender.sendMessage(languageManager.getFor(player, "game-stopped"));
                break;

            case "join":
                if (!isPlayer) {
                    sender.sendMessage(languageManager.get("players-only"));
                    return true;
                }
                gameManager.addPlayer(player);
                sender.sendMessage(languageManager.getFor(player, "joined-game"));
                break;

            case "leave":
                if (!isPlayer) {
                    sender.sendMessage(languageManager.get("players-only"));
                    return true;
                }
                gameManager.removePlayer(player);
                sender.sendMessage(languageManager.getFor(player, "left-game"));
                break;

            case "status":
                sender.sendMessage(languageManager.getFor(player, "status-header"));
                sender.sendMessage(languageManager.getFor(player, "status-state", gameManager.getState()));
                sender.sendMessage(languageManager.getFor(player, "status-wave", gameManager.getCurrentWave()));
                sender.sendMessage(languageManager.getFor(player, "status-players", gameManager.getPlayers().size()));
                sender.sendMessage(languageManager.getFor(player, "status-mobs", gameManager.getWaveManager().getRemainingMobCount()));
                sender.sendMessage(languageManager.getFor(player, "status-nodes",
                        gameManager.getNodeSystem().getActiveCount(), gameManager.getNodeSystem().getTotalCount()));
                break;

            case "hammer":
                if (!isPlayer) {
                    sender.sendMessage(languageManager.get("players-only"));
                    return true;
                }
                player.getInventory().addItem(hammerListener.createHammer());
                sender.sendMessage(languageManager.getFor(player, "received-hammer"));
                break;

            case "fireball":
                if (!isPlayer) {
                    sender.sendMessage(languageManager.get("players-only"));
                    return true;
                }
                player.getInventory().addItem(fireballListener.createFireball());
                player.sendMessage(ChatColor.RED + "你获得了烈焰蛋！右键投掷，命中后爆炸。");
                break;

            case "help":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                sendUsage(sender);
                break;

            case "deploy":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                handleDeployCommand(sender, args);
                break;

            case "config":
                if (!sender.hasPermission("hilltopvillage.config.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                handleConfigCommand(sender, args);
                break;

            case "reloadconfig":
                if (!sender.hasPermission("hilltopvillage.admin")) {
                    sender.sendMessage(languageManager.getFor(player, "no-permission"));
                    return true;
                }
                gameManager.getConfigManager().loadFromFile();
                sender.sendMessage(languageManager.getFor(player, "config-reloaded"));
                break;

            case "lang":
                handleLangCommand(sender, args);
                break;

            default:
                sendUsage(sender);
                break;
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String hover = languageManager.getFor(player, "usage-click-to-copy");

        sender.sendMessage(languageManager.getFor(player, "usage-header"));
        sendClickableLine(sender, "usage-start", "/hilltop start", hover);
        sendClickableLine(sender, "usage-stop", "/hilltop stop", hover);
        sendClickableLine(sender, "usage-join", "/hilltop join", hover);
        sendClickableLine(sender, "usage-leave", "/hilltop leave", hover);
        sendClickableLine(sender, "usage-status", "/hilltop status", hover);
        sendClickableLine(sender, "usage-hammer", "/hilltop hammer", hover);
        sendClickableLine(sender, "usage-lang", "/hilltop lang ", hover);
        if (sender.hasPermission("hilltopvillage.admin")) {
            sendClickableLine(sender, "usage-deploy", "/hilltop deploy", hover);
            sendClickableLine(sender, "usage-config", "/hilltop config", hover);
            sendClickableLine(sender, "usage-reloadconfig", "/hilltop reloadconfig", hover);
        }
    }

    /** 部署命令帮助菜单（点击自动填入聊天栏） */
    private void sendDeployMenu(Player player) {
        String hover = languageManager.getFor(player, "usage-click-to-copy");

        player.sendMessage("");
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.GOLD + "" + ChatColor.BOLD + ">> 部署操作命令 <<")
                .hoverEvent(HoverEvent.showText(Component.text("村民守卫战PLUS — 部署命令"))));

        sendClickableLine(player, "deploy.cmd-setcore", "/hilltop deploy setcore", hover,
                ChatColor.YELLOW + "将你的当前位置设为游戏核心");
        sendClickableLine(player, "deploy.cmd-addspawn", "/hilltop deploy addspawn", hover,
                ChatColor.YELLOW + "将你的当前位置添加为怪物生成点");
        sendClickableLine(player, "deploy.cmd-listspawns", "/hilltop deploy listspawns", hover,
                ChatColor.YELLOW + "查看所有已设置的生成点");
        sendClickableLine(player, "deploy.cmd-delspawn", "/hilltop deploy delspawn ", hover,
                ChatColor.YELLOW + "删除指定编号的生成点（后接编号）");
        sendClickableLine(player, "deploy.cmd-showparticles", "/hilltop deploy showparticles", hover,
                ChatColor.YELLOW + "展示所有生成点的粒子效果（30秒）");
        sendClickableLine(player, "deploy.cmd-clearspawns", "/hilltop deploy clearspawns", hover,
                ChatColor.RED + "清除所有生成点（需二次确认）");
        sendClickableLine(player, "deploy.cmd-confirmclear", "/hilltop deploy confirmclear", hover,
                ChatColor.RED + "确认清除所有生成点");
        sendClickableLine(player, "deploy.cmd-reloadnodes", "/hilltop deploy reloadnodes", hover,
                ChatColor.YELLOW + "重新扫描地图中的能量节点");

        player.sendMessage("");
    }

    /** 发送可点击复制的命令提示行（带描述） */
    private void sendClickableLine(CommandSender sender, String msgKey, String command, String hoverText,
                                    String description) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String msg = languageManager.getFor(player, msgKey);
        String line = description + " - " + msg;

        if (isPlayer) {
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(line)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverText)));
            player.sendMessage(component);
        } else {
            sender.sendMessage(line);
        }
    }

    /** 发送可点击复制的命令提示行 */
    private void sendClickableLine(CommandSender sender, String msgKey, String command, String hoverText) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String msg = languageManager.getFor(player, msgKey);

        if (isPlayer) {
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(msg)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverText)));
            player.sendMessage(component);
        } else {
            sender.sendMessage(msg);
        }
    }

    private void handleConfigCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.get("players-only"));
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
                        admin.sendMessage(languageManager.getFor(admin, "config.repairitem-set", mat.name()));
                    } else {
                        admin.sendMessage(languageManager.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
                if (args[2].equalsIgnoreCase("add") && admin.hasPermission("hilltopvillage.config.nodes")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        if (!cm.getActiveConfig().getNodeBlockTypes().contains(mat)) {
                            cm.getActiveConfig().getNodeBlockTypes().add(mat);
                            cm.saveToFile();
                            admin.sendMessage(languageManager.getFor(admin, "config.node-added", mat.name()));
                        } else {
                            admin.sendMessage(languageManager.getFor(admin, "config.node-exists"));
                        }
                    } else {
                        admin.sendMessage(languageManager.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
                if (args[2].equalsIgnoreCase("remove") && admin.hasPermission("hilltopvillage.config.nodes")) {
                    Material mat = Material.getMaterial(args[3].toUpperCase());
                    if (mat != null) {
                        cm.getActiveConfig().getNodeBlockTypes().remove(mat);
                        cm.saveToFile();
                        admin.sendMessage(languageManager.getFor(admin, "config.node-removed", mat.name()));
                    } else {
                        admin.sendMessage(languageManager.getFor(admin, "config.invalid-material", args[3]));
                    }
                    return;
                }
            }
        }

        MainConfigMenu menu = new MainConfigMenu(admin, cm);
        Bukkit.getPluginManager().registerEvents(menu, this);
        menu.open();
    }

    private void handleDeployCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.get("players-only"));
            return;
        }

        Player admin = (Player) sender;
        AdminDeployManager deploy = gameManager.getAdminDeployManager();

        if (args.length < 2) {
            sendDeployMenu(admin);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "setcore":
                deploy.setCoreLocation(admin.getLocation());
                gameManager.getPlugin().reloadConfig();
                admin.sendMessage(languageManager.getFor(admin, "deploy.setcore-set"));
                admin.sendMessage(languageManager.getFor(admin, "deploy.setcore-coords",
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                admin.getWorld().spawnParticle(Particle.VILLAGER_HAPPY,
                        admin.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                break;

            case "addspawn":
                deploy.addSpawnPoint(admin.getLocation());
                admin.sendMessage(languageManager.getFor(admin, "deploy.addspawn-added"));
                admin.sendMessage(languageManager.getFor(admin, "deploy.addspawn-coords",
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
                    admin.sendMessage(languageManager.getFor(admin, "deploy.listspawns-empty"));
                } else {
                    admin.sendMessage(languageManager.getFor(admin, "deploy.listspawns-header", points.size()));
                    for (int i = 0; i < points.size(); i++) {
                        Location loc = points.get(i);
                        admin.sendMessage(languageManager.getFor(admin, "deploy.listspawns-entry", i,
                                String.format("%.1f", loc.getX()),
                                String.format("%.1f", loc.getY()),
                                String.format("%.1f", loc.getZ())));
                    }
                    admin.sendMessage(languageManager.getFor(admin, "deploy.listspawns-hint"));
                }
                break;

            case "delspawn":
                if (args.length < 3) {
                    admin.sendMessage(languageManager.getFor(admin, "deploy.delspawn-usage"));
                    return;
                }
                try {
                    int index = Integer.parseInt(args[2]);
                    if (deploy.removeSpawnPoint(index)) {
                        admin.sendMessage(languageManager.getFor(admin, "deploy.delspawn-deleted", index));
                    } else {
                        admin.sendMessage(languageManager.getFor(admin, "deploy.delspawn-not-found", index));
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(languageManager.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearspawns":
                if (!pendingClearConfirm.contains(admin.getUniqueId())) {
                    pendingClearConfirm.add(admin.getUniqueId());
                    admin.sendMessage(languageManager.getFor(admin, "deploy.clearspawns-warning", deploy.getSpawnPoints().size()));
                    admin.sendMessage(languageManager.getFor(admin, "deploy.clearspawns-confirm"));
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (pendingClearConfirm.remove(admin.getUniqueId())) {
                            admin.sendMessage(languageManager.getFor(admin, "deploy.clearspawns-expired"));
                        }
                    }, 20L * 15);
                } else {
                    admin.sendMessage(languageManager.getFor(admin, "deploy.clearspawns-already"));
                }
                break;

            case "confirmclear":
                if (pendingClearConfirm.remove(admin.getUniqueId())) {
                    int count = deploy.getSpawnPoints().size();
                    deploy.clearSpawnPoints();
                    admin.sendMessage(languageManager.getFor(admin, "deploy.confirmclear-done", count));
                } else {
                    admin.sendMessage(languageManager.getFor(admin, "deploy.confirmclear-no-pending"));
                }
                break;

            case "reloadnodes":
                deploy.loadSpawnPoints();
                gameManager.getNodeSystem().loadNodesFromWorld();
                admin.sendMessage(languageManager.getFor(admin, "deploy.reloadnodes-done",
                        gameManager.getNodeSystem().getTotalCount()));
                break;

            case "showparticles":
                deploy.showSpawnPointParticles(admin, languageManager);
                break;

            default:
                admin.sendMessage(languageManager.getFor(admin, "deploy.unknown-sub", args[1]));
                admin.sendMessage(languageManager.getFor(admin, "deploy.unknown-sub-hint"));
                break;
        }
    }

    private void handleLangCommand(CommandSender sender, String[] args) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        if (args.length < 2) {
            sender.sendMessage(languageManager.getFor(player, "lang.usage"));
            return;
        }

        String locale = args[1].toLowerCase();
        if (!locale.equals("en") && !locale.equals("zh")) {
            sender.sendMessage(languageManager.getFor(player, "lang.invalid"));
            return;
        }

        languageManager.setLocale(locale);
        if (isPlayer) {
            languageManager.setPlayerLocale(player, locale);
        }
        sender.sendMessage(languageManager.getFor(player, "lang.switched", locale));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("hilltop")) return null;

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            for (String sub : Arrays.asList("start", "stop", "join", "leave", "status", "hammer", "fireball", "help", "deploy", "config", "reloadconfig", "lang")) {
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

    private void generateCommandsDocument() {
        File docFile = new File(getDataFolder(), "commands.md");
        StringBuilder md = new StringBuilder();

        md.append("# 村民守卫战PLUS — 命令文档\n\n");
        md.append("> 本文档由插件在服务器启动时自动生成/更新。\n");
        md.append("> 最后更新: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n\n");
        md.append("---\n\n");

        md.append("## 基础命令\n\n");

        md.append("### `/hilltop join`\n\n");
        md.append("- **功能**: 加入游戏等待队列。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop join`\n");
        md.append("- **注意事项**: 需在游戏状态为 WAITING 或 STARTING 时使用，游戏开始后无法加入。\n\n");

        md.append("### `/hilltop leave`\n\n");
        md.append("- **功能**: 退出游戏。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop leave`\n");
        md.append("- **注意事项**: 游戏进行中退出将视为放弃，已获得的积分和击杀数将丢失。\n\n");

        md.append("### `/hilltop status`\n\n");
        md.append("- **功能**: 查看当前游戏状态。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop status`\n");
        md.append("- **注意事项**: 显示内容包括游戏状态、当前波次、在线玩家数、存活怪物数、能量节点状态等。\n\n");

        md.append("### `/hilltop hammer`\n\n");
        md.append("- **功能**: 获取神圣之锤（Sacred Hammer）。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop hammer`\n");
        md.append("- **注意事项**: 锤子右键可触发蓄力重击（Smash），左键为普通攻击。每位玩家同时只能持有一把锤子。\n\n");

        md.append("### `/hilltop help`\n\n");
        md.append("- **功能**: 查看命令帮助。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop help`\n");
        md.append("- **注意事项**: 管理员专用，显示所有可用命令及简要说明。\n\n");

        md.append("### `/hilltop lang <en|zh>`\n\n");
        md.append("- **功能**: 切换插件语言显示。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: `<en|zh>` — 目标语言代码，en = English，zh = 中文。\n");
        md.append("- **使用示例**: `/hilltop lang en`, `/hilltop lang zh`\n");
        md.append("- **注意事项**: 切换后所有命令返回的消息将以所选语言显示。语言偏好会持久化保存到 config.yml。消息文本可在 `plugins/HilltopVillage/messages.yml` 中自定义编辑。\n\n");

        md.append("---\n\n");
        md.append("## 管理命令\n\n");
        md.append("> 以下命令需要 `hilltopvillage.admin` 权限。\n\n");

        md.append("### `/hilltop start`\n\n");
        md.append("- **功能**: 启动游戏。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop start`\n");
        md.append("- **注意事项**: 需确保核心位置已设置（通过 `/hilltop deploy setcore`），游戏将从等待状态进入运行状态。\n\n");

        md.append("### `/hilltop stop`\n\n");
        md.append("- **功能**: 停止当前游戏。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop stop`\n");
        md.append("- **注意事项**: 强制结束游戏，所有进行中的波次和怪物将被清除，如需重新开始请再次执行 start 命令。\n\n");

        md.append("### `/hilltop deploy`\n\n");
        md.append("- **功能**: 打开管理员部署菜单。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy`\n");
        md.append("- **注意事项**: 显示当前部署状态、所有可用的部署子命令及说明。搭配以下子命令使用。\n\n");

        md.append("### `/hilltop deploy setcore`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标设置为游戏核心位置。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy setcore`\n");
        md.append("- **注意事项**: 核心是村庄守卫的核心建筑，怪物将以此为目标。请站在核心上方执行命令。设置后会播放粒子效果作为视觉反馈。\n\n");

        md.append("### `/hilltop deploy addspawn`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标添加为怪物生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy addspawn`\n");
        md.append("- **注意事项**: 每个生成点会被自动分配一个独特的粒子效果，用于视觉区分。可设置多个生成点。站在想要的位置执行命令即可精准记录坐标。\n\n");

        md.append("### `/hilltop deploy listspawns`\n\n");
        md.append("- **功能**: 列出所有已设置的生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy listspawns`\n");
        md.append("- **注意事项**: 显示每个生成点的编号、坐标。编号用于 delspawn 命令。\n\n");

        md.append("### `/hilltop deploy delspawn <编号>`\n\n");
        md.append("- **功能**: 删除指定编号的生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<编号>` — 生成点编号（从 listspawns 获取）。\n");
        md.append("- **使用示例**: `/hilltop deploy delspawn 2`\n");
        md.append("- **注意事项**: 删除后所有剩余生成点的粒子自动重新分配，确保无重复。\n\n");

        md.append("### `/hilltop deploy showparticles`\n\n");
        md.append("- **功能**: 在所有已设置的生成点周围展示粒子效果。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy showparticles`\n");
        md.append("- **注意事项**: 粒子效果在以每个生成点为中心、半径3格的范围内以螺旋动画展示，默认持续30秒。每个生成点使用其分配的独特粒子类型，便于视觉区分。重复执行会重置计时。\n\n");

        md.append("### `/hilltop deploy clearspawns`\n\n");
        md.append("- **功能**: 清除所有生成点（需二次确认）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearspawns`\n");
        md.append("- **注意事项**: 执行后需在15秒内输入 `/hilltop deploy confirmclear` 确认。超时或未确认则操作取消。清除后怪物将在核心周围随机生成。\n\n");

        md.append("### `/hilltop deploy confirmclear`\n\n");
        md.append("- **功能**: 确认清除所有生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy confirmclear`\n");
        md.append("- **注意事项**: 此命令仅在 `/hilltop deploy clearspawns` 执行后15秒内有效。\n\n");

        md.append("### `/hilltop deploy reloadnodes`\n\n");
        md.append("- **功能**: 重新扫描地图中的能量节点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy reloadnodes`\n");
        md.append("- **注意事项**: 重新从世界方块中加载能量节点，用于地图更新后刷新节点状态。\n\n");

        md.append("### `/hilltop reloadconfig`\n\n");
        md.append("- **功能**: 重新加载游戏配置文件。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop reloadconfig`\n");
        md.append("- **注意事项**: 从 `game-settings.yml` 重新加载所有游戏参数（波次配置、怪物属性、锤子参数、节点设置等），无需重启服务器即可使修改生效。\n\n");

        md.append("---\n\n");
        md.append("## 配置中心命令\n\n");
        md.append("> 以下命令需要 `hilltopvillage.config.admin` 权限。\n\n");

        md.append("### `/hilltop config`\n\n");
        md.append("- **功能**: 打开游戏配置 GUI 菜单。\n");
        md.append("- **权限**: `hilltopvillage.config.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop config`\n");
        md.append("- **注意事项**: 通过 GUI 界面配置游戏规则、锤子参数、节点设置、怪物属性和波次组合。左键/右键/Shift+点击可进行不同操作。关闭菜单后自动保存配置。\n\n");

        md.append("### `/hilltop config nodes repairitem <材料名>`\n\n");
        md.append("- **功能**: 设置修复能量节点所需的物品材料。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — Minecraft 材料名称（如 IRON_INGOT、GOLD_INGOT）。\n");
        md.append("- **使用示例**: `/hilltop config nodes repairitem IRON_INGOT`\n");
        md.append("- **注意事项**: 材料名需使用大写英文，且必须为有效的 Minecraft 材料枚举名。\n\n");

        md.append("### `/hilltop config nodes add <材料名>`\n\n");
        md.append("- **功能**: 添加能量节点的可用方块类型。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — 要添加为能量节点的方块类型（如 CRYING_OBSIDIAN）。\n");
        md.append("- **使用示例**: `/hilltop config nodes add CRYING_OBSIDIAN`\n");
        md.append("- **注意事项**: 添加后方块会被识别为能量节点，玩家可与其交互获取增益效果。重复添加相同材料不会产生效果。\n\n");

        md.append("### `/hilltop config nodes remove <材料名>`\n\n");
        md.append("- **功能**: 移除能量节点的可用方块类型。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — 要移除的方块类型。\n");
        md.append("- **使用示例**: `/hilltop config nodes remove CRYING_OBSIDIAN`\n");
        md.append("- **注意事项**: 移除后该方块类型不再被识别为能量节点。\n\n");

        md.append("---\n\n");
        md.append("## 权限节点\n\n");
        md.append("| 权限节点 | 说明 |\n");
        md.append("|---|---|\n");
        md.append("| `hilltopvillage.admin` | 管理员权限，包含 start/stop/deploy/help/reloadconfig |\n");
        md.append("| `hilltopvillage.config.admin` | 配置中心管理员权限，包含 config 主命令 |\n");
        md.append("| `hilltopvillage.config.gamerules` | 游戏规则配置权限 |\n");
        md.append("| `hilltopvillage.config.hammer` | 锤子参数配置权限 |\n");
        md.append("| `hilltopvillage.config.nodes` | 能量节点配置权限 |\n");
        md.append("| `hilltopvillage.config.monsters` | 怪物属性配置权限 |\n");
        md.append("| `hilltopvillage.config.waves` | 波次组合配置权限 |\n\n");

        md.append("---\n\n");
        md.append("## 注意事项\n\n");
        md.append("1. **命令别名**: `/hilltop` 命令也可使用 `/hv` 或 `/htv` 作为别名。\n");
        md.append("2. **控制台限制**: 除 status/reloadconfig 外，大部分涉及 GUI 或播放效果的命令仅限玩家在游戏内使用。\n");
        md.append("3. **部署流程**: 建议按照 `setcore → addspawn → reloadnodes → start` 的顺序操作。\n");
        md.append("4. **配置保存**: 使用 GUI 关闭菜单时配置会自动保存，`/hilltop reloadconfig` 用于手动重载外部编辑的配置。\n");
        md.append("5. **粒子展示**: `/hilltop deploy showparticles` 仅展示当前已设置的生成点对应粒子。若没有生成点则不会有任何效果。\n");
        md.append("6. **多语言**: 使用 `/hilltop lang <en|zh>` 切换语言。所有消息文本可在 `plugins/HilltopVillage/messages.yml` 中自定义编辑。\n");

        try {
            if (!docFile.getParentFile().exists()) {
                docFile.getParentFile().mkdirs();
            }
            java.nio.file.Files.write(docFile.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            getLogger().info("命令文档已生成: " + docFile.getAbsolutePath());
        } catch (IOException e) {
            getLogger().severe("无法生成命令文档 commands.md: " + e.getMessage());
        }
    }
}
