package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.bukkit.inventory.ItemStack;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.admin.gui.ArenaManageGUI;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaStatus;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.menu.MainConfigMenu;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.diagnostic.TpsDiagnostic;
import com.hilltopvillage.shop.NpcManager;
import com.hilltopvillage.shop.ShopManager;
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
    /** clearvillagers 的二次确认集合（与其它两个清除确认各自独立，避免互相串确认） */
    private final Set<UUID> pendingClearVillagersConfirm = new HashSet<>();

    /** /hilltop shop give 单次发放上限（防手滑输入天文数字刷爆背包 / 卡服） */
    private static final int MAX_SHOP_GIVE = 6400;

    /**
     * 控制台禁止执行的子命令（需要玩家在场）。
     *
     * <p>取「黑名单」而非「白名单」的原因：未知子命令走 default 分支只发帮助文本，
     * 对控制台是无害的，不应被拦；而白名单写法每次新增命令都要记得补名单，
     * 漏补就会把新命令对控制台误拦 —— 这正是本次要修的 bug 的成因。</p>
     *
     * <p>help 不在名单里：它只发文本，控制台可用（走 deployMenuSender.sendUsage 的非玩家分支）。</p>
     */
    private static final Set<String> CONSOLE_BLOCKED_COMMANDS = Set.of(
            "join", "leave", "lobby", "gui", "hammer", "fireball",
            "setup", "deploy", "config", "manage", "build");

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

        String sub = args[0].toLowerCase(Locale.ROOT);

        // 控制台准入：先放行「控制台本来就能用」的管理命令，再拦真正需要玩家的命令。
        //
        // 旧写法在顶层按「非玩家 + 非白名单 → players-only」一刀切，把控制台执行
        // start/stop/enable/disable/arenas/tps 等管理命令也拦掉了（它们只读竞技场状态、
        // 不碰玩家对象，控制台本应可用）。新写法把拦截倒过来：只对明确需要玩家在场的
        // 命令拦截控制台，其余一律走各自 case 内的权限 / 参数检查。
        //
        // 各 case 内部仍保留自己的 players-only 守卫（join/leave/gui/build/manage/…），
        // 因此这里删掉顶层拦截不会让控制台绕过任何「玩家在场」要求 —— 只是把拦截点
        // 下移到各 case，让错误信息更贴近真实原因（无权限报 no-permission，
        // 缺玩家报 players-only，而不是一律 players-only）。
        //
        // 需要玩家在场的命令（控制台执行时报 players-only，行为与旧版一致）：
        // join / leave / lobby / gui / hammer / fireball / setup / deploy / config /
        // manage / build / stats（不带玩家名时）/ top（走各自 case 的守卫，此处不列）。
        //
        // 控制台可用（需 hilltopvillage.admin，行为变化见 README 命令表）：
        // start / stop / arenas / list / enable / disable / tps / shop reload /
        // status / reloadconfig / lang / stats <玩家名> / top <类型> / wave clear / wave skip。
        if (!isPlayer && CONSOLE_BLOCKED_COMMANDS.contains(sub)) {
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
                    sendArenaResolveFailure(sender, player, startArenaId);
                    break;
                }
                GameManager startGm = startArena.getGameManager();
                // Check if game is deployed (spawn points configured)
                if (!startGm.hasSpawnPoints()) {
                    // 走 i18n（按玩家语言）；hint 带竞技场 ID，指引 /hilltop setup 或 /hilltop enable
                    sender.sendMessage(lang.getFor(player, "arena-not-deployed", startArena.getArenaId()));
                    sender.sendMessage(lang.getFor(player, "arena-not-deployed-hint", startArena.getArenaId()));
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
                // fix6 Y1-R03-05：回滚窗专属预检——地图恢复未完成时，「人数不足」不是真实阻塞
                // （玩家此时无法 join，硬屏障在 ArenaInstance.addPlayer），
                // 先报专用文案避免「start 说拉人 / join 说进行中」的自相矛盾。
                if (startArena.getState() == GameState.ENDING
                        || plugin.getMapRestoreManager().isRestoring(startArena.getWorldName())) {
                    sender.sendMessage(lang.getFor(player, "game-start-restoring",
                            startArena.getArenaId()));
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
                    sendArenaResolveFailure(sender, player, stopArenaId);
                    break;
                }
                stopArena.stopGame();
                sender.sendMessage(lang.getFor(player, "game-stopped"));
                break;
            }

            case "join":
                // 入口 CONSOLE_BLOCKED_COMMANDS 已拦截控制台，到这里的一定是玩家
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

                    // fix5 M-20：同场重入预检——显式 ID 解析到当前场（大小写归一化后比对），
                    // 或无参 join 时玩家已在某场（现守卫已放行的等待/倒计时阶段成员），
                    // 都直接按「已在场内」处理。否则 leave→join 同场会触发人数跌破检查
                    //（ArenaInstance 取消倒计时）+ checkAutoStart 的重开倒计时，
                    // 反复执行即可无限拖延开局（R4-02）。想换场请显式指定目标场 ID。
                    // 注：current == null（映射指向已退役实例的残留态）时不拦，交给下方流程自愈。
                    boolean sameArena = args.length < 2 && current != null;
                    if (!sameArena && args.length >= 2 && current != null) {
                        ArenaInstance target = arenaManager.resolveArena(args[1], player);
                        sameArena = target != null
                                && target.getArenaId().equals(current.getArenaId());
                    }
                    if (sameArena) {
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
                    sendArenaResolveFailure(sender, player, statusArenaId);
                    break;
                }
                GameManager statusGm = statusArena.getGameManager();
                sender.sendMessage(lang.getFor(player, "status-header"));
                // 竞技场名：多竞技场服务器上从大厅执行时，只看状态/波次分不清是哪一场。
                // 附注「默认」仅在走回退链时加（未显式给 ID 且玩家不在任何场，
                // 与 resolveArena 的回退判据一致）；后缀走独立 i18n 键，避免在 Java 里硬编码文本。
                boolean explicitArenaId = statusArenaId != null && !statusArenaId.isBlank();
                boolean arenaFallback = !explicitArenaId
                        && (player == null || arenaManager.getPlayerArena(player) == null);
                String arenaNote = arenaFallback ? lang.getFor(player, "status-arena-default") : "";
                sender.sendMessage(lang.getFor(player, "status-arena",
                        statusArena.getArenaId(), arenaNote));
                // fix6 Y3-M-11：经 GameState.i18nKey() 本地化（zh 显示「等待中/运行中…」而非英文枚举名）
                sender.sendMessage(lang.getFor(player, "status-state",
                        lang.getFor(player, statusGm.getState().i18nKey())));
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
                // fix6 Y3-R07-08：撤掉 admin 门——sendUsage 内部按权限裁剪（玩家看不到管理段），
                // 与无参 / default 路径行为对齐（正确入口不应被锁、错误入口反而畅通）。
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
                    // M-01：拆包重拼为完整子命令串（含空白分隔的余下参数）——goto 是唯一双 token
                    // 子命令（goto <N>），此前只传 args[1] 导致数字被丢弃、goto 永远只回用法提示
                    setupWizard.handle(player, String.join(" ",
                            java.util.Arrays.copyOfRange(args, 1, args.length)));
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
                // fix4 反馈：建造模式只对大厅世界生效（LobbyProtectionListener#exempt 仅在
                // inLobby() 为真时放行），在竞技场等世界切换只会给出错误提示。
                // 大厅世界解析沿用 getLobbyWorld()（含未配置时回退主世界的同一口径），
                // 名称比较用 equalsIgnoreCase（理由同 handleLobbyCommand 的 setspawn 守卫）。
                // 已在建造模式的玩家永远允许 toggle off —— 即使此刻已被传送出大厅世界。
                org.bukkit.World lobbyWorld = arenaManager.getLobbyWorld();
                String lobbyWorldName = lobbyWorld != null ? lobbyWorld.getName() : null;
                boolean inLobbyWorld = lobbyWorldName != null
                        && player.getWorld().getName().equalsIgnoreCase(lobbyWorldName);
                if (!inLobbyWorld && !plugin.getLobbyProtectionListener().isBuildMode(player)) {
                    player.sendMessage(lang.getFor(player, "build.not-in-lobby",
                            lobbyWorldName != null ? lobbyWorldName : "?"));
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
                handleLobbyCommand(player, args);
                break;

            case "wave":
                handleWaveCommand(sender, args, player);
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
                if (args.length >= 2 && args[1].equalsIgnoreCase("give")) {
                    handleShopGive(sender, args, player);
                    break;
                }
                sender.sendMessage(lang.getFor(player, "usage-shop-reload"));
                sender.sendMessage(lang.getFor(player, "usage-shop-give"));
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
                    Material mat = Material.getMaterial(args[3].toUpperCase(Locale.ROOT));
                    if (mat != null && mat.isItem()) {
                        cm.getActiveConfig().setRepairCostItem(mat);
                        cm.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.repairitem-set", mat.name()));
                    } else if (mat != null) {
                        // fix6 Y2-01：存在但非可携带物品（WATER/LAVA 等方块态材质）→ ItemStack 构造必抛，
                        // 若放行则商店 REPAIR_ITEM 发货链吃币（扣款后 new ItemStack 抛异常、无退款）
                        admin.sendMessage(lang.getFor(admin, "config.invalid-material-not-item",
                                args[3], mat.name()));
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

    /**
     * fix5 X6-②：命令路径写盘结果收口——消费「最近一次写盘失败」标记并如实播报。
     *
     * <p>背景（C2-04 + R8-07）：部署命令的写操作经 AdminDeployManager.saveSpawnPoints 落盘，
     * 失败时只进控制台 severe；命令路径此前从不消费该标记 →（a）管理员被"已添加"字样误导，
     * 重启后改动丢失；（b）标记悬挂，后续向导 autoSave 的 consumeSaveFailure 会误报上一笔
     * 命令的失败（假警报）。命令每次写盘后立即消费即同时关闭两方向。</p>
     *
     * <p>消费语义安全：saveSpawnPoints 每次写入都无条件重设标记为「本次结果」，
     * 主线程内紧接着消费得到的就是本次写入的准确结果。</p>
     */
    private void reportDeploySaveFailure(Player admin, AdminDeployManager deploy) {
        if (deploy.consumeSaveFailure()) {
            admin.sendMessage(lang.getFor(admin, "deploy.save-failed"));
        }
    }

    private void handleDeployCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("players-only"));
            return;
        }

        Player admin = (Player) sender;

        // 解析目标竞技场：优先按管理员所在世界反查定义（与向导同源）。
        // 旧链「玩家已加入场 → 默认场 → 第一个活动场」在多竞技场下会把站在 arena2 世界
        // 却未 join 的管理员指向 arena1（随后世界校验失败，命令路径无解）；世界反查不中才回退旧链。
        ArenaDefinition worldDef = plugin.getMultiArenaManager().getDefinitionManager()
                .getArenaByWorld(admin.getWorld().getName());
        ArenaInstance deployArena = worldDef != null
                ? arenaManager.getArena(worldDef.getId())
                : arenaManager.resolveArena(null, admin);

        if (deployArena == null && worldDef == null) {
            sendArenaResolveFailure(sender, admin, null);
            return;
        }

        // 部署必须在目标竞技场世界内进行，否则会把大厅坐标误写成竞技场坐标。
        // 世界反查命中的分支天然同世界，无需再校验。
        // C2-01：大小写不敏感比较 —— Bukkit 世界查找不区分大小写（arenas.yml 写 Hilltop_Arena1、
        // 实际文件夹 hilltop_arena1 仍能正常加载游玩），精确 equals 会把站在正确世界里的管理员误拒。
        if (deployArena != null && !admin.getWorld().getName().equalsIgnoreCase(deployArena.getWorldName())) {
            admin.sendMessage(lang.getFor(admin, "deploy.must-be-in-arena-world",
                    deployArena.getDisplayName(), deployArena.getWorldName()));
            return;
        }

        if (args.length < 2) {
            deployMenuSender.sendDeployMenu(admin);
            return;
        }

        // 已配置但尚无活动实例的场（未启用 / 未部署）：help 与 clearshop 无需实例即可工作
        if (deployArena == null) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("help")) {
                deployMenuSender.sendDeployMenu(admin);
                return;
            }
            if (sub.equals("clearshop")) {
                // clearshop 走 ArenaAdminService 的「实例优先 + YAML 兜底」路径，无实例的场也可清除
                switch (plugin.getArenaAdminService().deleteShopNpc(worldDef.getId())) {
                    case OK -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-done"));
                    case ARENA_NOT_FOUND -> admin.sendMessage(lang.getFor(admin, "arena-not-found",
                            worldDef.getId()));
                    case NOT_SET -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-none"));
                    case GAME_IN_PROGRESS -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-in-game"));
                    case WRITE_FAILED -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-write-failed"));
                }
                return;
            }
            admin.sendMessage(lang.getFor(admin, "arena-not-deployed", worldDef.getId()));
            admin.sendMessage(lang.getFor(admin, "arena-not-deployed-hint", worldDef.getId()));
            return;
        }

        GameManager deployGm = deployArena.getGameManager();
        AdminDeployManager deploy = deployGm.getAdminDeployManager();

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "help":
                deployMenuSender.sendDeployMenu(admin);
                break;

            case "setcore":
                deploy.setCoreLocation(admin.getLocation());
                plugin.reloadConfig();
                admin.sendMessage(lang.getFor(admin, "deploy.setcore-set"));
                admin.sendMessage(lang.getFor(admin, "deploy.setcore-coords",
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                reportDeploySaveFailure(admin, deploy);   // fix5 X6-②
                admin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        admin.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                break;

            case "addspawn":
                deploy.addSpawnPoint(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.addspawn-added"));
                // fix6 Y3-M-03：编号 1 基（新增点即第 size() 个，与 listspawns 显示一致）
                admin.sendMessage(lang.getFor(admin, "deploy.addspawn-coords",
                        deploy.getSpawnPoints().size(),
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                reportDeploySaveFailure(admin, deploy);   // fix5 X6-②
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
                        // fix6 Y3-M-03：编号统一为 1 基（与 Manage GUI / 村民 GUI 口径一致，防止跨界面删错）
                        admin.sendMessage(lang.getFor(admin, "deploy.listspawns-entry", i + 1,
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
                    // fix6 Y3-M-03：命令参数与列表显示口径统一为 1 基，内部索引转 0 基。
                    // 显示的 1 基编号直接可粘贴回本命令（此前跨界面差 1，会删错条目）。
                    int shownIndex = Integer.parseInt(args[2]);
                    int index = shownIndex - 1;
                    if (deploy.removeSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-not-found", shownIndex));
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
                    reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclear-no-pending"));
                }
                break;

            case "addmobspawn":
                deploy.addMobSpawnPoint(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.mobspawn-added"));
                // fix6 Y3-M-03：编号 1 基（新增点即第 size() 个，与 listmobspawns 显示一致）
                admin.sendMessage(lang.getFor(admin, "deploy.mobspawn-coords",
                        deploy.getMobSpawnPoints().size(),
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                reportDeploySaveFailure(admin, deploy);   // fix5 X6-②
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
                        // fix6 Y3-M-03：编号统一为 1 基
                        admin.sendMessage(lang.getFor(admin, "deploy.listmobspawns-entry", i + 1,
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
                    // fix6 Y3-M-03：与 listmobspawns 显示口径统一为 1 基，内部索引转 0 基
                    int shownIndex = Integer.parseInt(args[2]);
                    int index = shownIndex - 1;
                    if (deploy.removeMobSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-not-found", shownIndex));
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
                    // C2-02：用本族专用键（旧版复用 clearspawns-already，指引的 confirmclear 不在本 pending 集）
                    admin.sendMessage(lang.getFor(admin, "deploy.clearmobspawns-already"));
                }
                break;

            case "confirmclearmob":
                if (pendingClearMobConfirm.remove(admin.getUniqueId())) {
                    int count = deploy.getMobSpawnPoints().size();
                    deploy.clearMobSpawnPoints();
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclearmob-done", count));
                    reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
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
                // fix6 Y3-M-03：编号 1 基（新增点即第 size() 个，与 listvillagers 显示一致）
                admin.sendMessage(lang.getFor(admin, "deploy.addvillager-coords",
                        deploy.getVillagers().size(),
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                reportDeploySaveFailure(admin, deploy);   // fix5 X6-②
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
                        // fix6 Y3-M-03：编号统一为 1 基
                        admin.sendMessage(lang.getFor(admin, "deploy.listvillagers-entry", i + 1,
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
                    // fix6 Y3-M-03：与 listvillagers 显示口径统一为 1 基（村民 GUI 本就 1 基），内部索引转 0 基
                    int shownIndex = Integer.parseInt(args[2]);
                    int index = shownIndex - 1;
                    if (deploy.removeVillager(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-not-found", shownIndex));
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearvillagers":
                // 与 clearspawns / clearmobspawns 同一套 15 秒二次确认（各自的 pending 集合独立）
                if (!pendingClearVillagersConfirm.contains(admin.getUniqueId())) {
                    pendingClearVillagersConfirm.add(admin.getUniqueId());
                    admin.sendMessage(lang.getFor(admin, "deploy.clearvillagers-warning", deploy.getVillagers().size()));
                    admin.sendMessage(lang.getFor(admin, "deploy.clearvillagers-confirm"));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pendingClearVillagersConfirm.remove(admin.getUniqueId())) {
                            admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-expired"));
                        }
                    }, 20L * 15);
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.clearvillagers-already"));
                }
                break;

            case "confirmclearvillagers":
                if (pendingClearVillagersConfirm.remove(admin.getUniqueId())) {
                    // 先取数量再清除：旧实现清完再报 0，恒报「已清除 0 个」
                    int clearedVillagers = deploy.getVillagers().size();
                    deploy.clearVillagers();
                    admin.sendMessage(lang.getFor(admin, "deploy.clearvillagers-done", clearedVillagers));
                    reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                } else {
                    admin.sendMessage(lang.getFor(admin, "deploy.confirmclearvillagers-no-pending"));
                }
                break;

            case "setshop":
                // 对局进行中拒绝（P2-23）：set 会先 despawn 旧实体再生成新实体，对局中移动
                // 会当场把场内玩家正在使用的商店撤掉、换到管理员脚下 —— 与清除入口同一保护口径。
                // 判定读 live 实例状态（与 ArenaAdminService.deleteShopNpc 同源）；
                // 无实例 = 未开打，放行。
                if (NpcManager.isGameInProgress(deployArena.getState())) {
                    admin.sendMessage(lang.getFor(admin, "deploy.setshop-in-game"));
                    return;
                }
                // pitch 归零对齐向导 confirm-shop（F-4）：存盘与生成都用水平朝向，
                // 实体侧 spawnNpc 本会自愈，但 YAML 里不应写入低头值。
                Location shopLocation = admin.getLocation().clone();
                shopLocation.setPitch(0f);
                // 先生成后存盘（与向导 confirm-shop 同序）：spawnNpc 返回 false（旧 NPC 在别世界
                // 且那场正在对局，跨世界抽走被拒）时不写配置、整次操作原样拒绝 ——
                // 避免「配置指向新位置、实体留在旧世界」的分叉状态
                if (!plugin.getShopManager().getNpcManager().spawnNpc(shopLocation)) {
                    admin.sendMessage(lang.getFor(admin, "deploy.setshop-in-game"));
                    return;
                }
                deploy.setShopNpcLocation(shopLocation);
                admin.sendMessage(lang.getFor(admin, "deploy.setshop-set"));
                admin.sendMessage(lang.getFor(admin, "deploy.setshop-coords",
                        String.format("%.1f", shopLocation.getX()),
                        String.format("%.1f", shopLocation.getY()),
                        String.format("%.1f", shopLocation.getZ())));
                reportDeploySaveFailure(admin, deploy);   // fix5 X6-②
                admin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        admin.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                break;

            case "clearshop":
                // 与向导第 6 步 [清除商店位置] / 管理 GUI 右键商店条目同一服务口径：
                // 对局进行中拒绝（会撤掉场内玩家的商店）、无设置时如实提示、单例实体按世界归属清除
                switch (plugin.getArenaAdminService().deleteShopNpc(deployArena.getArenaId())) {
                    case OK -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-done"));
                    case ARENA_NOT_FOUND -> admin.sendMessage(lang.getFor(admin, "arena-not-found",
                            deployArena.getArenaId()));
                    case NOT_SET -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-none"));
                    case GAME_IN_PROGRESS -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-in-game"));
                    case WRITE_FAILED -> admin.sendMessage(lang.getFor(admin, "deploy.clearshop-write-failed"));
                }
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
    private void handleLobbyCommand(Player player, String[] args) {
        // fix4 反馈9：/hilltop lobby setspawn —— 把当前位置写为大厅出生点（管理员）
        if (args.length >= 2 && args[1].equalsIgnoreCase("setspawn")) {
            if (!player.hasPermission("hilltopvillage.admin")) {
                player.sendMessage(lang.getFor(player, "no-permission"));
                return;
            }
            // 必须站在大厅世界内执行：否则会把竞技场/任意世界的坐标静默写成大厅出生点
            // （与 deploy 系列的世界守卫同一理由，见 handleDeployCommand 的 must-be-in-arena-world）。
            // 大小写不敏感比较（仿 C2-01）：Bukkit 世界查找不区分大小写，arenas.yml 里
            // lobby.world 写成 Lobby 而实际文件夹小写时，精确 equals 会把站在正确世界里的
            // 管理员误拒。大厅世界解析沿用 getLobbyWorld()（含未配置时回退主世界的同一口径）。
            org.bukkit.World lobbyWorld = arenaManager.getLobbyWorld();
            String lobbyWorldName = lobbyWorld != null ? lobbyWorld.getName() : null;
            if (lobbyWorldName == null
                    || !player.getWorld().getName().equalsIgnoreCase(lobbyWorldName)) {
                player.sendMessage(lang.getFor(player, "lobby.setspawn-not-lobby-world",
                        lobbyWorldName != null ? lobbyWorldName : "?"));
                return;
            }
            if (arenaManager.setLobbySpawn(player.getLocation())) {
                player.sendMessage(lang.getFor(player, "lobby.setspawn-done",
                        String.format("%.1f", player.getLocation().getX()),
                        String.format("%.1f", player.getLocation().getY()),
                        String.format("%.1f", player.getLocation().getZ())));
                player.sendMessage(lang.getFor(player, "lobby.setspawn-effect"));
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
            } else {
                player.sendMessage(lang.getFor(player, "lobby.setspawn-failed"));
            }
            return;
        }
        if (args.length >= 2) {
            player.sendMessage(lang.getFor(player, "usage-lobby"));
            if (player.hasPermission("hilltopvillage.admin")) {
                player.sendMessage(lang.getFor(player, "usage-lobby-setspawn"));
            }
            return;
        }

        if (arenaManager.isInArena(player)) {
            arenaManager.leaveArena(player);
            lang.send(player, "arena-left");
            return;
        }
        arenaManager.sendToLobby(player);
        lang.send(player, "lobby-teleported");
    }

    /**
     * enable / disable 的竞技场 ID 解析：显式 ID 优先；未提供时**优先按管理员所在世界反查定义**
     * （多竞技场管理员站在 arena2 世界执行无参 {@code /hilltop enable} 时，
     * {@code resolveArenaId} 的「玩家已加入场」映射在他身上为空，会静默落到默认场），
     * 世界反查不中再走 {@link MultiArenaManager#resolveArenaId} 的旧回退链。
     *
     * @return 竞技场 ID；显式 ID 未命中 / 无任何可解析竞技场时返回 null
     */
    private String resolveArenaIdForAdmin(String explicitId, Player player) {
        if (explicitId != null && !explicitId.isBlank()) {
            return arenaManager.resolveArenaId(explicitId, player);
        }
        if (player != null) {
            ArenaDefinition byWorld = arenaManager.getDefinitionManager()
                    .getArenaByWorld(player.getWorld().getName());
            if (byWorld != null) return byWorld.getId();
        }
        return arenaManager.resolveArenaId(null, player);
    }

    /**
     * start / stop / status（以及 deploy 入口解析失败）共用的解析失败播报。
     *
     * <ul>
     *   <li>显式传了非空 ID 且解析不到实例 → {@code arena-not-found}（打错字的 ID 不再静默改靶）；</li>
     *   <li>未传 ID / 传了 ID 但该场「有定义、无实例」（未启用 / 未部署）→ {@code arena-not-deployed}
     *       + 指引（跑 /hilltop setup 或用 /hilltop enable 启用）；</li>
     *   <li>完全没有已配置竞技场 → 原 {@code no-active-arenas}。</li>
     * </ul>
     *
     * @param explicitId 调用方解析时传入的显式 ID（null / 空白 = 未提供）
     */
    private void sendArenaResolveFailure(CommandSender sender, Player player, String explicitId) {
        boolean explicit = explicitId != null && !explicitId.isBlank();
        if (explicit) {
            // 定义存在但无活动实例 → 走「未启用/未部署」指引；完全查无此场 → arena-not-found
            ArenaDefinition def = arenaManager.getDefinitionManager().getArena(explicitId);
            if (def == null) {
                sender.sendMessage(lang.getFor(player, "arena-not-found", explicitId));
                return;
            }
            sender.sendMessage(lang.getFor(player, "arena-not-deployed", def.getId()));
            sender.sendMessage(lang.getFor(player, "arena-not-deployed-hint", def.getId()));
            return;
        }

        // 未提供 ID：解析失败只可能是「无活动实例」。优先给出定向指引：
        // 玩家所在世界反查到定义 → 该场；否则取默认 / 第一个定义（resolveArenaId 回退链）。
        // 只要 definitionManager 里有对应的场就报「未部署/未启用」+ 指引；
        // 完全没有已配置竞技场才落到 no-active-arenas。
        ArenaDefinition byWorld = player != null
                ? arenaManager.getDefinitionManager().getArenaByWorld(player.getWorld().getName())
                : null;
        String defId = byWorld != null ? byWorld.getId() : null;
        if (defId == null) {
            String resolved = arenaManager.resolveArenaId(null, player);
            // 排除指向已删除定义的陈旧映射：只认 definitionManager 里真实存在的场
            if (resolved != null && arenaManager.getDefinitionManager().getArena(resolved) != null) {
                defId = resolved;
            }
        }
        if (defId != null && arenaManager.getArena(defId) == null) {
            sender.sendMessage(lang.getFor(player, "arena-not-deployed", defId));
            sender.sendMessage(lang.getFor(player, "arena-not-deployed-hint", defId));
            return;
        }
        sender.sendMessage(lang.getFor(player, "no-active-arenas"));
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

        String requestedId = args.length >= 2 ? args[1] : null;
        String arenaId = resolveArenaIdForAdmin(requestedId, player);
        if (arenaId == null) {
            // 显式 ID 打错 → arena-not-found；否则无场可操作
            if (requestedId != null && !requestedId.isBlank()) {
                sender.sendMessage(lang.getFor(player, "arena-not-found", requestedId));
            } else {
                sender.sendMessage(lang.getFor(player, "no-active-arenas"));
            }
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

        String requestedId = args.length >= 2 ? args[1] : null;
        String arenaId = resolveArenaIdForAdmin(requestedId, player);
        if (arenaId == null) {
            if (requestedId != null && !requestedId.isBlank()) {
                sender.sendMessage(lang.getFor(player, "arena-not-found", requestedId));
            } else {
                sender.sendMessage(lang.getFor(player, "no-active-arenas"));
            }
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
     * /hilltop wave <clear|skip> [竞技场]
     * fix4 反馈12：管理员强制清怪 / 强制推进波次（替代已默认禁用的自动超时保险）。
     *
     * <ul>
     *   <li>{@code clear}：清光当前波全部存活怪物并强制完成该波进入休整期。
     *       仅对局进行中（RUNNING）有效；休整期执行会被拒绝并提示改用 {@code skip}。</li>
     *   <li>{@code skip}：休整期跳过倒计时，立即开始下一波；对局进行中执行则
     *       先强制清波再立即开始下一波（一步到位）。</li>
     * </ul>
     */
    private void handleWaveCommand(CommandSender sender, String[] args, Player player) {
        if (!sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(lang.getFor(player, "no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(lang.getFor(player, "usage-wave-clear"));
            sender.sendMessage(lang.getFor(player, "usage-wave-skip"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean skipIntermission = action.equals("skip");
        if (!skipIntermission && !action.equals("clear")) {
            sender.sendMessage(lang.getFor(player, "usage-wave-clear"));
            sender.sendMessage(lang.getFor(player, "usage-wave-skip"));
            return;
        }

        String waveArenaId = args.length >= 3 ? args[2] : null;
        ArenaInstance waveArena = arenaManager.resolveArena(waveArenaId, player);
        if (waveArena == null) {
            sendArenaResolveFailure(sender, player, waveArenaId);
            return;
        }
        GameManager waveGm = waveArena.getGameManager();

        // clear 在休整期没有可清的东西：拒绝并引导到 skip（而非静默等效 skip，
        // 让「cleared」消息始终真实，也避免把「清怪」命令变成意外的跳波按钮）
        if (!skipIntermission && waveGm.getState() == GameState.WAVE_INTERMISSION) {
            sender.sendMessage(lang.getFor(player, "wave-clear-intermission"));
            return;
        }

        GameManager.ForceWaveResult result = waveGm.forceAdvanceWave();

        // skip 且当前波刚被清完（原本 RUNNING）时，再推一步直接开始下一波
        if (skipIntermission && result == GameManager.ForceWaveResult.WAVE_CLEARED
                && waveGm.getState() == GameState.WAVE_INTERMISSION) {
            result = waveGm.forceAdvanceWave();
        }

        switch (result) {
            case WAVE_CLEARED -> sender.sendMessage(lang.getFor(player, "wave-clear-done"));
            case WAVE_SKIPPED -> sender.sendMessage(lang.getFor(player, "wave-skip-done"));
            case NOT_ENOUGH_PLAYERS -> sender.sendMessage(lang.getFor(player, "wave-skip-not-enough-players"));
            case NOT_RUNNING -> sender.sendMessage(lang.getFor(player, "wave-not-running"));
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
        sender.sendMessage(lang.getFor(player, "stats.crystals", String.valueOf(target.getCrystalsCollected())));
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

        sender.sendMessage(lang.getFor(player, "top.header", type.getKey().toUpperCase(Locale.ROOT), String.valueOf(top.size())));
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
            case CRYSTALS -> stats.getCrystalsCollected();
        };
    }

    /**
     * /hilltop shop give &lt;玩家&gt; [数量]
     * 给在线玩家发放<b>真·货币</b>（经 {@link ShopManager#createCurrencyItem} 产出，必带 PDC 标记）。
     *
     * <p>这是 P0-A 修复后服主手工发币的<b>唯一正道</b>：原版 {@code /give} 发出的绿宝石
     * 没有 PDC 标记，{@link ShopManager#isCurrency} 不认，无法当货币消费。
     * 控制台可用（给在线玩家发币）。</p>
     */
    private void handleShopGive(CommandSender sender, String[] args, Player player) {
        if (args.length < 3) {
            sender.sendMessage(lang.getFor(player, "shop.give-usage"));
            return;
        }

        ShopManager shop = plugin.getShopManager();
        if (shop == null || shop.getShopConfig() == null) {
            sender.sendMessage(lang.getFor(player, "shop.give-unavailable"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(lang.getFor(player, "shop.give-player-not-found", args[2]));
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(lang.getFor(player, "shop.give-invalid-amount", args[3]));
                return;
            }
            if (amount <= 0) {
                sender.sendMessage(lang.getFor(player, "shop.give-invalid-amount", args[3]));
                return;
            }
            if (amount > MAX_SHOP_GIVE) {
                sender.sendMessage(lang.getFor(player, "shop.give-too-many", String.valueOf(MAX_SHOP_GIVE)));
                return;
            }
        }

        // 背包放不下的部分掉在目标脚下，避免静默吞币
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(shop.createCurrencyItem(amount));
        for (ItemStack leftover : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }

        sender.sendMessage(lang.getFor(player, "shop.give-success", target.getName(), String.valueOf(amount)));
        target.sendMessage(lang.getFor(target, "shop.give-received", String.valueOf(amount)));
    }

    public void handleLangCommand(CommandSender sender, String[] args) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;

        if (args.length < 2) {
            sender.sendMessage(lang.getFor(player, "lang.usage"));
            return;
        }

        String locale = args[1].toLowerCase(Locale.ROOT);
        if (!locale.equals("en") && !locale.equals("zh")) {
            sender.sendMessage(lang.getFor(player, "lang.invalid"));
            return;
        }

        // fix5 M-14：分离「个人偏好」与「全服默认」——普通玩家只能改自己的会话语言偏好
        // （不落盘）；全服默认语言（含 config.yml 落盘）仅管理员 / 控制台可改。
        // （R9-11：此前任意玩家可通过 /hilltop lang 改写全服默认并 saveConfig。）
        boolean canChangeDefault = !isPlayer || sender.hasPermission("hilltopvillage.admin");
        if (canChangeDefault) {
            lang.setLocale(locale);
        }
        if (isPlayer) {
            lang.setPlayerLocale(player, locale);
        }
        sender.sendMessage(lang.getFor(player, "lang.switched", locale));
    }

}
