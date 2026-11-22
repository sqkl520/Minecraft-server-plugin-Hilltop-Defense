package com.hilltopvillage.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Level;

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

    /**
     * 清除类子命令（clearspawns / clearmobspawns / clearvillagers）的二次确认类型。
     *
     * <p>历史实现是三套 {@code Set<UUID>}（只记人、不记场、不记时刻），导致管理员在 arena1
     * 发起后被传送到 arena2 再确认时，清掉的是 arena2 的数据（D3 Critical）。</p>
     */
    private enum ClearKind {
        SPAWN, MOB_SPAWN, VILLAGER
    }

    /**
     * 一次待确认的清除请求（D3）。
     *
     * @param arenaId        发起时确定的竞技场规范 ID（<b>确认时必须与此一致</b>，大小写不敏感比较）
     * @param sourceWorldName 发起时该竞技场所在世界名。跨场确认被拒时，提示行里的
     *                        「(world {1})」必须指向<b>发起场</b>的世界，否则会出现
     *                        「请求目标是 arena1（世界 hilltop_arena2）」这种自相矛盾的误导信息
     * @param kind           清除类型
     * @param expiresAt      过期时刻（wall clock millis）。与延迟任务双保险：任务可能因主线程卡顿
     *                       或插件重载而延迟，confirm 时再校验一次
     * @param requestedAt    发起时刻，仅用于日志与「同一请求」的值比较
     *
     * <p>record 的 {@code equals} 按值比较：两个不同时刻的请求 {@code requestedAt} 必不同，
     * 因此 「过期任务 {@code remove(key, fresh)}」 不会误删后续的新请求（D3 既有 bug 的修法）。</p>
     */
    private record PendingClear(String arenaId, String sourceWorldName, ClearKind kind,
                                long expiresAt, long requestedAt) {
        /** 与文案里写死的「15 秒」严格对应：改 TTL 必须同步改 6 个 *-confirm / *-expired 文案。 */
        static final long TTL_MILLIS = 15_000L;

        boolean expired(long now) {
            return now > expiresAt;
        }

        /** 提示行用：世界名缺失时给占位符一个可见值，避免 `{1}` 以字面量露出。 */
        String displaySourceWorld() {
            return sourceWorldName == null || sourceWorldName.isBlank() ? "?" : sourceWorldName;
        }
    }

    /** 复合键：同一管理员的不同 kind 仍互不干扰（保留原「三套集合独立」的语义）。 */
    private record PendingKey(UUID admin, ClearKind kind) {
    }

    /** 单一容器取代三套 {@code Set<UUID>}；值带场 ID 与过期时刻。 */
    private final Map<PendingKey, PendingClear> pendingClears = new ConcurrentHashMap<>();

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
        // 顶层快捷命令 /hub 与 /lobby：等价于 /hilltop lobby（[setspawn] 原样透传）。
        // 必须在下面那条「只认 hilltop」的硬校验之前分流，否则这两条独立命令会被直接 return false，
        // Bukkit 随后打 usage 并对玩家毫无反馈。
        //
        // 参数形态对齐：handleLobbyCommand 用 args[1] 判定 setspawn（见其实现），
        // 也就是它吃的是「/hilltop lobby setspawn」这种<b>带子命令占位</b>的数组。
        // 因此 /hub 传 {"lobby"}、/lobby setspawn 传 {"lobby","setspawn"} —— 前置占位不可省，
        // 否则 args[1] 会越界，setspawn 静默失效。
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if (cmdName.equals("hub") || cmdName.equals("lobby")) {
            if (!(sender instanceof Player shortcutPlayer)) {
                sender.sendMessage(lang.get("players-only"));
                return true;
            }
            if (cmdName.equals("hub") && args.length > 0) {
                // /hub 只接受零参数：明确拒绝多余参数，而不是把它们当成 lobby 的子命令
                // （/hub setspawn 这种误输入会让人以为 hub 也能设置出生点）。
                shortcutPlayer.sendMessage(lang.getFor(shortcutPlayer, "usage-hub"));
                return true;
            }
            String[] forwarded = new String[args.length + 1];
            forwarded[0] = "lobby";
            System.arraycopy(args, 0, forwarded, 1, args.length);
            handleLobbyCommand(shortcutPlayer, forwarded);
            return true;
        }

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
                //  · 对局进行中 → 一律拒绝（不能半路换场，不论人是否还在场世界内）
                //  · 其余情况（等待中的成员、死亡后重生到别处、被外部插件传送等）
                //    → 视为可切换的等待状态，自动离开原竞技场后继续加入
                if (arenaManager.isInArena(player)) {
                    ArenaInstance current = arenaManager.getPlayerArena(player);
                    boolean gameRunning = current != null && current.getGameManager().isGameRunning();

                    // fix-021（slice-06-logic-002）：单条件 gameRunning，去掉原「&& inCurrentWorld」合取。
                    // 合取把「对局中、但人被 /tp 或第三方插件送出世界」漏进下方 leave→join 分支，
                    // leaveArena 触发 GameManager 的 min-players 判负，一名成员即可让队友被判负。
                    if (gameRunning) {
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
                // D1 运维闭环：先解封各 YAML 闸门，再重载（管理员可能已把被隔离的坏文件修好）。
                // reset() 只清缓存态，不保证文件已修好——下一次 load 仍会重新判定，
                // 仍坏则再次锁定只读并重新打日志。不得改成「reset 时主动校验」。
                arenaManager.getDefinitionManager().getArenasGate().reset();
                plugin.getConfigManager().getSettingsGate().reset();
                plugin.getConfigManager().getMonstersGate().reset();
                plugin.getConfigManager().getDisplayGate().reset();
                plugin.getLanguageManager().getMessagesGate().reset();
                // 节点只读标记是静态集合（NODES_READ_ONLY），对任一实例调用即全局清空。
                // 无活动实例时不必特殊处理：NodeSystem 的所有读入口（loadNodesFromConfig /
                // readNodesForWorld / clearNodesForWorld / removeNodeEntryForWorld）在读到 OK 时
                // 本就会把路径移出该集合，第一个成功读盘的动作即完成解封。
                arenaManager.getActiveArenas().stream()
                        .map(ArenaInstance::getGameManager)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .ifPresent(gm -> gm.getNodeSystem().resetReadOnlyGates());
                // 成功提示必须放在所有重载步骤之后：早发会让后续步骤抛出时管理员看到假成功。
                try {
                    // 诊断用：重载前 registry 是否为空。只在「空 → 非空」这一个方向上做启用恢复，
                    // 见下方 enableAllConfiguredArenas 的调用条件。
                    int arenasBefore = arenaManager.getDefinitionManager().getRegistry().size();
                    // arenas.yml 的定义必须在闸门 reset() 之后真的重读一遍。
                    // 少了这一步，被隔离过的 arenas.yml 会出现「闸门已解封、文件可写，但 registry 仍是空集」
                    // 的三态并存：玩家 join 报 no-arenas-available、管理员 enable 报 arena-not-found，
                    // 唯一出路是重启——而 YamlFileGate 的恢复指引恰好告诉管理员 reloadconfig 就够了。
                    // reloadDefinitions 只重建定义、不重建活动实例，见其 javadoc 的安全性说明。
                    int loadedArenas = arenaManager.reloadDefinitions();
                    // 死局恢复：本进程此前一个定义都没有（arenas.yml 被隔离 → registry 被清空），
                    // 现在读回来了。只重建 registry 玩家仍然 join 不到任何场（joinArena 查的是
                    // 活动实例），所以要按重启时的同一套语义把 enabled 的竞技场重新入队。
                    // 条件里的 arenasBefore == 0 是安全闸：日常 reloadconfig（registry 本来就是非空）
                    // 绝不会把管理员运行期 /hilltop disable 掉的竞技场悄悄打开。
                    if (arenasBefore == 0 && loadedArenas > 0
                            && arenaManager.getActiveArenas().isEmpty()
                            && arenaManager.getEnableQueue().isEmpty()) {
                        if (arenaManager.isAutoStartArenas()) {
                            plugin.getLogger().info("Arena registry was empty before this reload; re-queueing"
                                    + " the enabled arenas exactly as a server start would");
                            arenaManager.enableAllConfiguredArenas();
                        } else {
                            plugin.getLogger().info("Arena registry was empty before this reload;"
                                    + " settings.auto-start-arenas is false, so use /hilltop enable to start arenas");
                        }
                    }
                    // #8：loadFromFile() 失败不抛异常（保留内存值并提前 return），所以这里必须
                    // 消费返回值；否则下面的 catch 永远不会触发，config-reloaded 照发。
                    boolean configReloaded = plugin.getConfigManager().loadFromFile();
                    // display.yml 是纯手写文件（插件只读不写），管理员手滑改坏一次就会让闸门把整个进程
                    // 锁成「本进程只读」，此时 loadFromFile() 只会拿上次读到的旧 YamlConfiguration 重解析，
                    // 坏文件的修正永远进不来、布局永久停在默认值。所以这里必须再走一次 reloadDisplay()：
                    // reset() 解闩 + 真读盘 + 解析失败回退 defaults()，让「改坏 → 修好 → reloadconfig」
                    // 这条闭环始终成立，而不是把功能永久锁死到重启。
                    plugin.getConfigManager().reloadDisplay();
                    // 布局换成新的之后立刻推给所有人：刷新任务虽有周期，但管理员刚敲完命令就应看到结果。
                    // start() 是幂等的，同时负责「之前 tab.enabled=false 停过任务、现在改回 true」的重启。
                    if (plugin.getTabListManager() != null) {
                        plugin.getTabListManager().start();
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            plugin.getTabListManager().refreshNow(online);
                        }
                    }
                    plugin.getLanguageManager().reload();
                    if (configReloaded) {
                        sender.sendMessage(lang.getFor(player, "config-reloaded"));
                    } else {
                        // 不说「已重载」：game-settings.yml 没读进来，内存仍是旧值。
                        // messages.yml 是独立文件，仍可能已成功重载，故下面照发 messages-reloaded。
                        sender.sendMessage(lang.getFor(player, "config-reload-failed"));
                    }
                    sender.sendMessage(lang.getFor(player, "messages-reloaded"));
                    // 竞技场定义本次是否真的读到，必须如实播报：count 为 0 说明 arenas.yml 仍不可用
                    // （闸门刚被 reset，真读盘已经重判过一次），此时只发「已重载」会让管理员
                    // 以为竞技场回来了。运行中的对局仍用开局时的定义快照，见 reloadDefinitions 的 javadoc。
                    if (loadedArenas > 0) {
                        sender.sendMessage(lang.getFor(player, "config.arenas-reloaded",
                                String.valueOf(loadedArenas)));
                    } else {
                        sender.sendMessage(lang.getFor(player, "config.arenas-reload-empty"));
                    }
                    // 参数编辑的常规指引（GUI 参数页 / 手改文件后 reloadconfig）
                    sender.sendMessage(lang.getFor(player, "config.reload-arena-notice"));
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Config reload failed (/hilltop reloadconfig)", ex);
                    sender.sendMessage(ChatColor.RED + "Config reload failed part-way; see the console log. "
                            + "Some settings may not have been applied — fix the cause and run /hilltop reloadconfig again.");
                }
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
                    // S09-F1：reload() 现在返回 boolean（不再抛异常）。失败时必须如实播报，
                    // 否则管理员看到「已重载（0 件）」会以为文件读进来了、只是自己写空了。
                    if (!plugin.getShopManager().reload()) {
                        sender.sendMessage(lang.getFor(player, "shop-reload-failed"));
                        break;
                    }
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
                        // 与 deploy 系列同纪律（见 reportDeploySaveFailure 的注释）：内存已改，但写盘
                        // 结果必须消费。saveToFile() 的 false 涵盖只读闸门锁定与 IO 失败两种静默路径，
                        // 丢弃它会让管理员以为已持久化，重启后设置消失。
                        boolean saved = cm.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.repairitem-set", mat.name()));
                        if (!saved) {
                            admin.sendMessage(lang.getFor(admin, "setup.save-failed"));
                        }
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

    /* ========== 清除类二次确认的统一收口（D3：绑定竞技场） ========== */

    private static String warningKey(ClearKind kind) {
        return switch (kind) {
            case SPAWN -> "deploy.clearspawns-warning";
            case MOB_SPAWN -> "deploy.clearmobspawns-warning";
            case VILLAGER -> "deploy.clearvillagers-warning";
        };
    }

    private static String confirmKey(ClearKind kind) {
        return switch (kind) {
            case SPAWN -> "deploy.clearspawns-confirm";
            case MOB_SPAWN -> "deploy.clearmobspawns-confirm";
            case VILLAGER -> "deploy.clearvillagers-confirm";
        };
    }

    private static String alreadyKey(ClearKind kind) {
        return switch (kind) {
            case SPAWN -> "deploy.clearspawns-already";
            case MOB_SPAWN -> "deploy.clearmobspawns-already";
            case VILLAGER -> "deploy.clearvillagers-already";
        };
    }

    private static String doneKey(ClearKind kind) {
        return switch (kind) {
            case SPAWN -> "deploy.confirmclear-done";
            case MOB_SPAWN -> "deploy.confirmclearmob-done";
            case VILLAGER -> "deploy.clearvillagers-done";
        };
    }

    private static String noPendingKey(ClearKind kind) {
        return switch (kind) {
            case SPAWN -> "deploy.confirmclear-no-pending";
            case MOB_SPAWN -> "deploy.confirmclearmob-no-pending";
            case VILLAGER -> "deploy.confirmclearvillagers-no-pending";
        };
    }

    /**
     * 登记一次清除确认请求（三族共用）。
     *
     * <p>三种结局：</p>
     * <ol>
     *   <li>同 kind 同场已有 pending → 播「already」，不覆盖（15 秒窗口不因重复输入而延长）；</li>
     *   <li>同 kind 但换了场（管理员 tp 到别的竞技场后又发了同一条命令）→ 用新请求替换旧请求
     *       并明确播报换场。否则他会对着 arena2 的警告去确认 arena1 —— 正是 D3 缺陷的触发姿势；</li>
     *   <li>无 pending（或旧的已过期）→ 登记并播「15 秒内确认」。</li>
     * </ol>
     *
     * @param arenaId         本次发起场（当前解析场）的规范 ID
     * @param sourceWorldName 该竞技场所在世界名，随 pending 存下；跨场确认被拒时提示行要指向它
     * @return 本次登记的 {@link PendingClear}；被拒绝（同场已有 pending）时返回 {@code null}
     */
    private PendingClear requestClear(Player admin, String arenaId, String sourceWorldName,
                                      ClearKind kind, int count) {
        long now = System.currentTimeMillis();
        PendingKey key = new PendingKey(admin.getUniqueId(), kind);
        PendingClear fresh = new PendingClear(arenaId, sourceWorldName, kind,
                now + PendingClear.TTL_MILLIS, now);

        final PendingClear existing = pendingClears.get(key);
        if (existing != null && !existing.expired(now)) {
            if (existing.arenaId().equalsIgnoreCase(arenaId)) {
                admin.sendMessage(lang.getFor(admin, alreadyKey(kind), arenaId));
                return null;
            }
            pendingClears.put(key, fresh);
            admin.sendMessage(lang.getFor(admin, "deploy.clear-pending-rebound",
                    existing.arenaId(), arenaId));
        } else {
            pendingClears.put(key, fresh);
        }

        admin.sendMessage(lang.getFor(admin, warningKey(kind), count, arenaId));
        admin.sendMessage(lang.getFor(admin, confirmKey(kind), arenaId));

        // 过期任务：必须用双参 remove(key, expected)。无参 remove 会在
        // 「t=0 发起 → t=5 确认 → t=6 再发起」时，于 t=15 把 t=6 的新请求删掉并误播「已超时」（既有 bug）。
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingClears.remove(key, fresh)) {
                admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-expired", arenaId));
            }
        }, PendingClear.TTL_MILLIS / 50L);       // 15_000 / 50 = 300 ticks
        return fresh;
    }

    /**
     * 执行一次清除确认（D3 核心修复点）。
     *
     * <p><b>三条硬校验</b>：① pending 存在且未过期；② pending 的 arenaId 与「当前解析场」一致；
     * ③ deploy 对象仍在。任一不通过都不执行清除，并如实播报原因。</p>
     *
     * <p>校验失败时<b>刻意不消费 pending</b>：管理员可能只是 tp 错了场，15 秒窗口内回到发起场
     * 再 confirm 仍然有效 —— 比「一失足就只能从头再来」安全。</p>
     *
     * @param currentArenaId 本次由「管理员所在世界」重新解析出的场 ID（不是用户输入）
     * @param deploy         本次解析出的部署管理器（校验失败分支下可能为 null）
     * @param clearAction    真正执行清除的动作
     * @param countBefore    清除前取数量（必须先取再清，否则恒报 0）
     *
     * <p>提示行的世界名取自 <b>pending 自己记下的发起场世界</b>，而不是当前场 —— 否则会出现
     * 「请求目标是 arena1（世界 hilltop_arena2）」这种自相矛盾的误导信息。</p>
     */
    private void confirmClear(Player admin, String currentArenaId, AdminDeployManager deploy,
                              ClearKind kind, Runnable clearAction,
                              IntSupplier countBefore) {
        PendingKey key = new PendingKey(admin.getUniqueId(), kind);
        PendingClear pending = pendingClears.get(key);

        if (pending == null) {
            admin.sendMessage(lang.getFor(admin, noPendingKey(kind)));
            return;
        }
        // ① 过期（延迟任务可能因主线程卡顿而未及时执行）
        if (pending.expired(System.currentTimeMillis())) {
            pendingClears.remove(key, pending);
            admin.sendMessage(lang.getFor(admin, "deploy.clearspawns-expired", pending.arenaId()));
            return;
        }
        // ② 场不匹配 —— D3 的正解：拒绝且不消费 pending
        if (!pending.arenaId().equalsIgnoreCase(currentArenaId)) {
            admin.sendMessage(lang.getFor(admin, "deploy.clear-arena-mismatch",
                    pending.arenaId(), currentArenaId));
            admin.sendMessage(lang.getFor(admin, "deploy.clear-arena-mismatch-hint",
                    pending.arenaId(), pending.displaySourceWorld()));
            return;
        }
        // ③ deploy 对象缺失（场被 disable 后重解析拿不到实例）
        if (deploy == null) {
            pendingClears.remove(key, pending);
            admin.sendMessage(lang.getFor(admin, "deploy.clear-arena-gone", pending.arenaId()));
            return;
        }

        pendingClears.remove(key, pending);            // 消费
        int count = countBefore.getAsInt();            // 先取数量再清
        clearAction.run();
        admin.sendMessage(lang.getFor(admin, doneKey(kind), count, pending.arenaId()));
        reportDeploySaveFailure(admin, deploy);
    }

    /** 清除类破坏性操作的对局中守卫（与 setshop / clearshop 同一口径）。 */
    private boolean rejectClearInGame(Player admin, ArenaInstance arena) {
        if (arena != null && NpcManager.isGameInProgress(arena.getState())) {
            admin.sendMessage(lang.getFor(admin, "deploy.clear-in-game", arena.getArenaId()));
            return true;
        }
        return false;
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

            case "setwaiting":
                // 等待区：玩家 /hilltop join 后、开局前的落点（向导第 2 步的 [设为等待区] 同源）。
                // 对局进行中拒绝：等待区是给「下一批进场玩家」用的，对局里改不会有任何即时效果，
                // 却会让管理员误以为改的是当前场内玩家的位置 —— 与 setshop 同一保护口径（读 live 状态）。
                if (NpcManager.isGameInProgress(deployArena.getState())) {
                    admin.sendMessage(lang.getFor(admin, "deploy.setwaiting-in-game"));
                    return;
                }
                // 异世界坐标由 AdminDeployManager.setWaitingLocation 兜底拒收（M-02），
                // 但这里先给一句面向管理员的可读提示，避免「命令没反应」的错觉
                if (!admin.getWorld().getName().equalsIgnoreCase(deployArena.getWorldName())) {
                    admin.sendMessage(lang.getFor(admin, "deploy.must-be-in-arena-world",
                            deployArena.getDisplayName(), deployArena.getWorldName()));
                    return;
                }
                deploy.setWaitingLocation(admin.getLocation());
                admin.sendMessage(lang.getFor(admin, "deploy.setwaiting-set"));
                admin.sendMessage(lang.getFor(admin, "deploy.setwaiting-coords",
                        String.format("%.1f", admin.getLocation().getX()),
                        String.format("%.1f", admin.getLocation().getY()),
                        String.format("%.1f", admin.getLocation().getZ())));
                reportDeploySaveFailure(admin, deploy);
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
                    // S05-F1：先自行校验序号。removeSpawnPoint 现在也会因写盘失败返回 false，
                    // 不先校验就无法区分「序号不存在」与「写盘失败」—— 后者若也报「ID 不存在」
                    // 会把管理员引向错误的排查方向（去数点位，而不是去看磁盘/权限）。
                    if (index < 0 || index >= deploy.getSpawnPoints().size()) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-not-found", shownIndex));
                    } else if (deploy.removeSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delspawn-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        // 序号已在上面校验过，走到这里的 false 只可能是写盘失败。
                        // 必须走 reportDeploySaveFailure 而不是直接 sendMessage：它会**消费**
                        // lastSaveFailed 标记。直接播报会留下标记不消费，于是下一次成功操作
                        // 的 reportDeploySaveFailure 会再多播一条迟到的失败提示。
                        reportDeploySaveFailure(admin, deploy);
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearspawns":
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                requestClear(admin, deployArena.getArenaId(), deployArena.getWorldName(),
                        ClearKind.SPAWN, deploy.getSpawnPoints().size());
                break;

            case "confirmclear":
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                confirmClear(admin, deployArena.getArenaId(), deploy,
                        ClearKind.SPAWN,
                        deploy::clearSpawnPoints,
                        () -> deploy.getSpawnPoints().size());
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
                    // S05-F1：同 delspawn —— 序号校验前置，写盘失败不再伪装成「ID 不存在」
                    if (index < 0 || index >= deploy.getMobSpawnPoints().size()) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-not-found", shownIndex));
                    } else if (deploy.removeMobSpawnPoint(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delmobspawn-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        // 同 delspawn：必须消费 lastSaveFailed 标记，见那里的注释。
                        reportDeploySaveFailure(admin, deploy);
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearmobspawns":
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                requestClear(admin, deployArena.getArenaId(), deployArena.getWorldName(),
                        ClearKind.MOB_SPAWN, deploy.getMobSpawnPoints().size());
                break;

            case "confirmclearmob":
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                confirmClear(admin, deployArena.getArenaId(), deploy,
                        ClearKind.MOB_SPAWN,
                        deploy::clearMobSpawnPoints,
                        () -> deploy.getMobSpawnPoints().size());
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
                    // S05-F1：同 delspawn —— 序号校验前置，写盘失败不再伪装成「ID 不存在」
                    if (index < 0 || index >= deploy.getVillagers().size()) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-not-found", shownIndex));
                    } else if (deploy.removeVillager(index)) {
                        admin.sendMessage(lang.getFor(admin, "deploy.delvillager-deleted", shownIndex));
                        reportDeploySaveFailure(admin, deploy);   // fix5 锐评 C-01（N1 族补齐）
                    } else {
                        // 同 delspawn：必须消费 lastSaveFailed 标记，见那里的注释。
                        reportDeploySaveFailure(admin, deploy);
                    }
                } catch (NumberFormatException e) {
                    admin.sendMessage(lang.getFor(admin, "deploy.delspawn-invalid-number"));
                }
                break;

            case "clearvillagers":
                // 与 clearspawns / clearmobspawns 共用一套二次确认（复合键按 kind 隔离，互不串确认）
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                requestClear(admin, deployArena.getArenaId(), deployArena.getWorldName(),
                        ClearKind.VILLAGER, deploy.getVillagers().size());
                break;

            case "confirmclearvillagers":
                if (rejectClearInGame(admin, deployArena)) {
                    return;
                }
                // countBefore 先取数量再清除：旧实现清完再报 0，恒报「已清除 0 个」
                confirmClear(admin, deployArena.getArenaId(), deploy,
                        ClearKind.VILLAGER,
                        deploy::clearVillagers,
                        () -> deploy.getVillagers().size());
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
            //
            // 降级路径（fix7）：若 lobby.world 为空/指向的世界不存在，「必须站在大厅世界」就成了一条
            // 永远走不通的死路（那个世界根本进不去）。此时允许在任意世界设置，并把 lobby.world
            // 一并写成当前世界 —— 仍是管理员显式执行 + 明确播报，不破坏「防误设」的原意：
            // 只要 lobby.world 指向的世界存在，就仍严格要求在那个世界里执行。
            boolean lobbyWorldMissing = arenaManager.isConfiguredLobbyWorldMissing();
            org.bukkit.World lobbyWorld = arenaManager.getLobbyWorld();
            String lobbyWorldName = lobbyWorld != null ? lobbyWorld.getName() : null;
            if (lobbyWorldMissing
                    && arenaManager.getDefinitionManager()
                            .getArenaByWorld(player.getWorld().getName()) != null) {
                // 降级路径的防误设：大厅与竞技场必须是不同世界（插件据此区分保护规则与游戏逻辑）。
                // 站在竞技场世界里 setspawn 会把 lobby.world 写成竞技场世界 —— 这不是「没得选」，
                // 而是选错了世界，直接拒绝并说明该去哪设。
                player.sendMessage(lang.getFor(player, "lobby.setspawn-arena-world",
                        player.getWorld().getName()));
                return;
            }
            if (!lobbyWorldMissing
                    && (lobbyWorldName == null
                    || !player.getWorld().getName().equalsIgnoreCase(lobbyWorldName))) {
                player.sendMessage(lang.getFor(player, "lobby.setspawn-not-lobby-world",
                        lobbyWorldName != null ? lobbyWorldName : "?"));
                return;
            }
            boolean saved = lobbyWorldMissing
                    ? arenaManager.setLobbySpawnAndWorld(player.getLocation())
                    : arenaManager.setLobbySpawn(player.getLocation());
            if (saved) {
                player.sendMessage(lang.getFor(player, "lobby.setspawn-done",
                        String.format("%.1f", player.getLocation().getX()),
                        String.format("%.1f", player.getLocation().getY()),
                        String.format("%.1f", player.getLocation().getZ())));
                if (lobbyWorldMissing) {
                    // 降级路径必须明确播报「顺手把大厅世界也设了」，否则服主不知道配置被改
                    player.sendMessage(lang.getFor(player, "lobby.setspawn-world-set",
                            player.getWorld().getName()));
                }
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
        // 已站在大厅出生点 2 格内时不再重复传送（无害但会打断视角，每次执行都拉一次镜头）。
        // 人虽在大厅世界但离得远（迷路/掉虚空）仍然照传，避免把「回大厅」变成一条到不了出生点的死命令。
        // 大厅未配置时不做这个跳过判断：那会儿的出生点是主世界占位坐标，误判会让命令什么都不做。
        Location lobbySpawn = arenaManager.getLobbySpawn();
        if (!arenaManager.isConfiguredLobbyWorldMissing()
                && lobbySpawn != null && lobbySpawn.getWorld() != null
                && player.getWorld().equals(lobbySpawn.getWorld())
                && player.getLocation().distanceSquared(lobbySpawn) <= 4.0) {
            lang.send(player, "lobby.already-in-lobby");
            return;
        }
        // 任意世界（竞技场以外的生存世界等）都能回大厅：sendToLobby 内部走跨世界 teleport，
        // 大厅未配置时回退到主世界出生点（设计如此，不在此处拦截）。
        arenaManager.sendToLobby(player);
        lang.send(player, "lobby-teleported");
        // 大厅未配置好时补一句「你落在了主世界」——否则玩家/管理员只会觉得传送地点不对，
        // 却不知道要跑 /hilltop lobby setspawn（8 步向导不涉及大厅，见 SetupWizard 完成提示）。
        if (arenaManager.isConfiguredLobbyWorldMissing()) {
            lang.send(player, "lobby.not-configured-hint");
        }
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

        // slice-03-logic-004：disableArena 在「实例不存在」时返回 arena-already-disabled，但该分支
        // 其实做了事 —— 从启用队列撤下并置 DISABLED。队列中（QUEUED/LOADING）的场点「禁用」会得到
        // 「本来就是禁用」的错误文案。先取状态，把撤队与真·已禁用区分开播报。
        ArenaStatus statusBefore = arenaManager.getStatus(arenaId);
        String result = arenaManager.disableArena(arenaId);

        switch (result) {
            case "deploy.ok" -> sender.sendMessage(lang.getFor(player, "arena-disabled-ok", arenaId));
            case "arena-already-disabled" -> {
                // 队列态被撤下 → 竞技场确实被禁用，播报禁用成功而非「本来就是禁用」
                if (statusBefore.isPending()) {
                    sender.sendMessage(lang.getFor(player, "arena-disabled-ok", arenaId));
                } else {
                    sender.sendMessage(lang.getFor(player, "arena-already-disabled", arenaId));
                }
            }
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
