package com.hilltopvillage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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

import com.hilltopvillage.admin.ArenaAdminService;
import com.hilltopvillage.admin.ArenaResetService;
import com.hilltopvillage.admin.LocationParticlePreview;
import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.command.ArenaCommand;
import com.hilltopvillage.command.HilltopCommandHandler;
import com.hilltopvillage.command.HilltopTabCompleter;
import com.hilltopvillage.command.SetupWizard;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.menu.NumericInputHandler;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.core.PlayerGoodsManager;
import com.hilltopvillage.doc.CommandsDocumentGenerator;
import com.hilltopvillage.gui.ArenaSelectionGUI;
import com.hilltopvillage.mechanics.ArenaAccessListener;
import com.hilltopvillage.mechanics.FireballListener;
import com.hilltopvillage.mechanics.FriendlyFireListener;
import com.hilltopvillage.mechanics.HammerListener;
import com.hilltopvillage.mechanics.LobbyProtectionListener;
import com.hilltopvillage.mechanics.MapRestoreManager;
import com.hilltopvillage.mechanics.NodeListener;
import com.hilltopvillage.mechanics.PlayerLifecycleListener;
import com.hilltopvillage.mechanics.VillagerPreviewManager;
import com.hilltopvillage.mechanics.VillagerSystem;
import com.hilltopvillage.stats.StatsManager;
import com.hilltopvillage.shop.ShopListener;
import com.hilltopvillage.shop.ShopManager;
import com.hilltopvillage.util.LanguageManager;

import java.util.logging.Level;

public class HilltopVillagePlugin extends JavaPlugin implements Listener {

    // Multi-arena system (唯一模式)
    private MultiArenaManager multiArenaManager;

    // Shared managers
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private ShopManager shopManager;
    private PlayerGoodsManager playerGoodsManager;
    private StatsManager statsManager;
    private MapRestoreManager mapRestoreManager;
    /** 部署向导第 5 步的村民预览实体管理器（插件级，跨竞技场唯一；标记与真村民隔离） */
    private VillagerPreviewManager villagerPreviewManager;

    // 竞技场管理 GUI 的共享服务（/hilltop manage）
    private ArenaAdminService arenaAdminService;      // 无状态，仍做单例以便统一注入
    private ArenaResetService arenaResetService;      // 持有 inFlight 重置门
    private LocationParticlePreview locationParticlePreview;  // 持有每玩家的粒子预览任务

    // Listeners
    private HammerListener hammerListener;
    private NodeListener nodeListener;
    private FireballListener fireballListener;
    private ShopListener shopListener;
    private PlayerLifecycleListener playerLifecycleListener;
    private LobbyProtectionListener lobbyProtectionListener;

    // GUI
    private ArenaSelectionGUI arenaSelectionGUI;

    // Commands
    private SetupWizard setupWizard;
    private HilltopCommandHandler commandHandler;
    private ArenaCommand arenaCommand;
    private HilltopTabCompleter tabCompleter;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize shared managers
        this.languageManager = new LanguageManager(this);
        this.configManager = new ConfigManager(this);
        // P2-34 外层兜底：两个数据管理器已各自做文件级容错（损坏文件重命名 .corrupt + 空数据启动），
        // 此处再包一层防御，确保任何未预期的异常（如 dataFolder 无权限）不会让整个插件被禁用。
        // 失败时保持字段为 null —— 所有消费方（ArenaInstance 的 save/restore、record、命令）
        // 都在运行时经 getter 取用，null 优雅降级为「无物品快照 / 无统计」，不阻断对局本体。
        try {
            this.playerGoodsManager = new PlayerGoodsManager(this);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "PlayerGoodsManager 初始化失败（物品快照功能将被禁用）", t);
            this.playerGoodsManager = null;
        }
        try {
            this.statsManager = new StatsManager(this);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "StatsManager 初始化失败（统计功能将被禁用）", t);
            this.statsManager = null;
        }
        this.mapRestoreManager = new MapRestoreManager(this);
        this.villagerPreviewManager = new VillagerPreviewManager(this);
        this.shopManager = new ShopManager(this, languageManager);
        this.shopManager.reload();

        // Initialize multi-arena system
        // 与 BedWars1058 一致：竞技场走「启用队列」串行加载，init 完成才算就绪
        this.multiArenaManager = new MultiArenaManager(this);
        this.multiArenaManager.loadArenaDefinitions();
        // arenas.yml settings.auto-start-arenas=false 时不自动启用，改由 /hilltop enable 手动启用
        if (this.multiArenaManager.isAutoStartArenas()) {
            this.multiArenaManager.enableAllConfiguredArenas();
        } else {
            getLogger().info("arenas.yml settings.auto-start-arenas=false，跳过自动启用竞技场");
        }

        // Initialize arena management services（竞技场管理 GUI 的共享服务）
        // 三者内部都通过 plugin 惰性取 MultiArenaManager / MapRestoreManager / NodeSystem，
        // 构造函数本身不碰这些依赖；仍刻意放在 multiArenaManager 初始化「之后」，
        // 避免将来有人在构造函数里加依赖时踩到初始化顺序的坑。
        this.arenaAdminService = new ArenaAdminService(this);
        this.arenaResetService = new ArenaResetService(this);
        this.locationParticlePreview = new LocationParticlePreview(this);

        // Initialize listeners（多竞技场路由）
        this.hammerListener = new HammerListener(this);
        this.nodeListener = new NodeListener(this);
        this.fireballListener = new FireballListener(this);
        this.playerLifecycleListener = new PlayerLifecycleListener(this);
        this.lobbyProtectionListener = new LobbyProtectionListener(this);

        // Shop listeners
        // 次序（P2-25）：先按配置生成新 NPC（spawnEntity 会强制加载商店坐标所在区块），
        // 再清孤儿——否则硬崩后商店区块未加载时，孤儿实体在清理阶段不可见，随后被生成
        // 过程强制加载并与新 NPC 并存。cleanupOrphans 内部跳过 entity == npc，不会误删新实体。
        this.shopManager.getNpcManager().respawnFromConfig();
        this.shopManager.getNpcManager().cleanupOrphans();
        // 村民预览实体：清掉上次部署会话崩溃残留（按 PDC 标记识别，启动阶段一次）
        this.villagerPreviewManager.cleanupOrphans();
        // P2-12：波次怪启动清扫 —— 崩溃/强杀重启后上一局的 persistent 残怪随世界存盘，
        // 按 hilltopvillage:wave-mob PDC 标记全服清掉（模式与上面两个 cleanupOrphans 一致）。
        // 此刻任何对局都未开始（竞技场走启用队列串行加载），无误删活怪风险；
        // 竞技场世界通常尚未加载，主场景由 ArenaInstance 构造期的实例级清扫兜底。
        com.hilltopvillage.core.WaveManager.cleanupAllOrphans(this);
        this.shopListener = new ShopListener(shopManager, languageManager);
        Bukkit.getPluginManager().registerEvents(shopListener, this);

        // Initialize GUI
        this.arenaSelectionGUI = new ArenaSelectionGUI(this);

        // Initialize commands
        // 注意顺序：HilltopCommandHandler 构造时会读取 getArenaCommand()，
        // 因此 arenaCommand 必须先创建，否则拿到 null（/hilltop arenas / list 会 NPE）
        this.arenaCommand = new ArenaCommand(this);
        this.setupWizard = new SetupWizard(this);
        this.commandHandler = new HilltopCommandHandler(this);
        this.tabCompleter = new HilltopTabCompleter(this);

        // Register listeners
        Bukkit.getPluginManager().registerEvents(this, this);
        // SetupWizard 监听器常驻注册（M-03）：向导 GUI 的事件处理器内部已有标题匹配 +
        // 会话 null 判断，常驻安全；不再按打开/关闭界面反复 unregister/register，
        // 避免任一管理员关闭界面 / saveexit / 掉线时把另一名管理员正开界面的监听一并注销
        Bukkit.getPluginManager().registerEvents(setupWizard, this);
        Bukkit.getPluginManager().registerEvents(hammerListener, this);
        Bukkit.getPluginManager().registerEvents(nodeListener, this);
        Bukkit.getPluginManager().registerEvents(fireballListener, this);
        Bukkit.getPluginManager().registerEvents(new FriendlyFireListener(this), this);
        Bukkit.getPluginManager().registerEvents(playerLifecycleListener, this);
        Bukkit.getPluginManager().registerEvents(lobbyProtectionListener, this);
        // 竞技场世界准入防护（P2-41 / D-4）：非成员（管理员豁免）传送进竞技场世界被拦
        Bukkit.getPluginManager().registerEvents(new ArenaAccessListener(this), this);
        Bukkit.getPluginManager().registerEvents(mapRestoreManager, this);
        Bukkit.getPluginManager().registerEvents(new NumericInputHandler(), this);

        // 村民事件监听器：全局仅此一处注册，之后按世界路由到各竞技场的 VillagerSystem 实例
        // （ArenaInstance 不再逐个注册，避免反复 enable/disable 导致监听器累积泄漏）
        Bukkit.getPluginManager().registerEvents(new VillagerSystem.GlobalListener(this), this);

        // 村民预览实体：取消其一切伤害（绝对无敌），全局注册一次
        Bukkit.getPluginManager().registerEvents(villagerPreviewManager, this);

        // Register tab completer
        getCommand("hilltop").setTabCompleter(tabCompleter);

        // Generate documentation
        new CommandsDocumentGenerator(this).generate();

        getLogger().info("Hilltop Village has been enabled!");
        getLogger().info("Multi-arena system loaded with " + multiArenaManager.getActiveArenas().size() + " arenas");
    }

    @Override
    public void onDisable() {
        // 先停止烈焰蛋的共享任务并移除受追踪投射物，避免竞技场清理期间继续触发命中逻辑
        if (fireballListener != null) {
            try {
                fireballListener.shutdown();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "停止烈焰蛋任务失败（继续禁用流程）", exception);
            }
        }

        // 竞技场管理服务的收尾。位置说明（刻意放在竞技场停止「之前」，且远在 restoreAllNow() 之前）：
        //  · locationParticlePreview.stopAll() 取消的是纯装饰性的粒子任务，若留到竞技场清场时还在跑，
        //    会在世界被回滚的同时继续刷粒子；先取消掉，收尾画面干净。
        //  · arenaResetService.shutdown() 只清空 in-flight 重置门（不中断回滚），清掉后
        //    任何滞留的 MapRestoreManager 回调会因摘不到门而自动失声（见 ArenaResetService#finish）。
        //  · 两者都只取消已有任务 / 清内存标记，不产生新的调度，因此不会与后续停服流程冲突。
        //  · 绝不能挪到 restoreAllNow() 之后：那是禁用流程的最后屏障，其后的清理一旦被跳过就无法补偿；
        //    这里与前面各段一样独立 try/catch，前面任何一段抛异常都不会跳过本段。
        if (arenaResetService != null) {
            try {
                arenaResetService.shutdown();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "清理竞技场重置服务失败（继续禁用流程）", exception);
            }
        }
        if (locationParticlePreview != null) {
            try {
                locationParticlePreview.stopAll();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "停止坐标粒子预览任务失败（继续禁用流程）", exception);
            }
        }

        // 先停止会创建新任务的管理器；随后同步清理竞技场与地图，禁用阶段不再调度后续任务。
        //
        // 竞技场清理会逐个 removePlayer()，而移除最后一名玩家可能触发 GameManager.endGame(false)
        // → ArenaInstance 结算倒计时（无条件 runTaskTimer），在禁用阶段抛出 IllegalPluginAccessException。
        // 异常一旦逃逸就会中断 stopAllArenasNow()，使后面的 restoreAllNow() 永远不执行（地图不回滚），
        // 因此这里每一段都做异常隔离：任一竞技场失败都不能阻止其余竞技场与最终的地图回滚。
        if (multiArenaManager != null) {
            try {
                multiArenaManager.shutdown();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "停止竞技场管理器失败（继续禁用流程）", exception);
            }
            try {
                multiArenaManager.stopAllArenasNow();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "同步停止竞技场失败（继续禁用流程）", exception);
            }
        }

        // 地图回滚是禁用流程的最后屏障：无论竞技场清理是否成功都必须执行
        if (mapRestoreManager != null) {
            try {
                mapRestoreManager.restoreAllNow();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "禁用阶段地图回滚失败", exception);
            }
        }

        // Cleanup shops
        if (shopManager != null) {
            try {
                shopManager.getNpcManager().despawnNpc();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "大厅商店 NPC 清理失败（继续禁用流程）", exception);
            }
        }

        // 村民预览实体是部署期临时装饰，禁用时一并清除（防止随世界存盘；下次启动 cleanupOrphans 兜底）
        if (villagerPreviewManager != null) {
            try {
                villagerPreviewManager.clearAll();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "村民预览实体清理失败（继续禁用流程）", exception);
            }
        }

        // 强制落盘统计（必须在竞技场停止之后：玩家被移出竞技场时才写入本局统计）
        if (statsManager != null) {
            try {
                statsManager.saveNow();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "统计落盘失败", exception);
            }
        }

        // 强制落盘物品快照（与统计同理：防抖窗口内未落盘的变更必须在关服前写完）
        // 放在竞技场停止 / 地图回滚之后的收尾段：玩家离场的 restore() 可能已把快照从内存移除，
        // 但那次移除只登记了防抖任务，异步写盘在禁用阶段不会再触发，故这里必须补一次同步写。
        // 与前面各段一样独立 try/catch，任一段抛异常都不会跳过本段。
        if (playerGoodsManager != null) {
            try {
                playerGoodsManager.saveNow();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "物品快照落盘失败", exception);
            }
        }

        getLogger().info("Hilltop Village has been disabled.");
    }

    // ========== Getters ==========

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PlayerGoodsManager getPlayerGoodsManager() {
        return playerGoodsManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public MapRestoreManager getMapRestoreManager() {
        return mapRestoreManager;
    }

    /** 村民预览实体管理器（向导第 5 步专用；开局前须由调用方清理，见 ArenaInstance#onGameStart） */
    public VillagerPreviewManager getVillagerPreviewManager() {
        return villagerPreviewManager;
    }

    public MultiArenaManager getMultiArenaManager() {
        return multiArenaManager;
    }

    /** 别名方法，用于兼容 ArenaInstance 调用 */
    public MultiArenaManager getArenaManager() {
        return multiArenaManager;
    }

    public SetupWizard getSetupWizard() {
        return setupWizard;
    }

    public HammerListener getHammerListener() {
        return hammerListener;
    }

    public FireballListener getFireballListener() {
        return fireballListener;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public ShopListener getShopListener() {
        return shopListener;
    }

    public NodeListener getNodeListener() {
        return nodeListener;
    }

    public HilltopCommandHandler getCommandHandler() {
        return commandHandler;
    }

    public ArenaSelectionGUI getArenaSelectionGUI() {
        return arenaSelectionGUI;
    }

    public ArenaCommand getArenaCommand() {
        return arenaCommand;
    }

    public LobbyProtectionListener getLobbyProtectionListener() {
        return lobbyProtectionListener;
    }

    // ========== 竞技场管理 GUI 服务 ==========

    /** 竞技场管理 GUI 的只读数据层（任意竞技场的列表/详情 + 单条删除）。 */
    public ArenaAdminService getArenaAdminService() {
        return arenaAdminService;
    }

    /** 竞技场管理 GUI 的「重置竞技场」编排器（持有 in-flight 重置门）。 */
    public ArenaResetService getArenaResetService() {
        return arenaResetService;
    }

    /** 竞技场管理 GUI 的坐标粒子预览（按玩家隔离任务）。 */
    public LocationParticlePreview getLocationParticlePreview() {
        return locationParticlePreview;
    }

    // ========== Event Handlers ==========

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball snowball = (Snowball) event.getEntity();

        if (!"hook_claw".equals(snowball.getCustomName())) return;

        if (event.getHitEntity() instanceof Player) {
            Player hitPlayer = (Player) event.getHitEntity();
            ArenaInstance arena = multiArenaManager.getPlayerArena(hitPlayer);
            if (arena == null) return;
            HookClawHunterGoal.onHookHit(hitPlayer, snowball, arena.getGameManager());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity().getWorld() == null) return;
        ArenaInstance arena = multiArenaManager.getArenaByWorld(event.getEntity().getWorld().getName());
        if (arena == null) return;

        var gameManager = arena.getGameManager();
        if (!gameManager.isGameRunning()) return;

        if (gameManager.getWaveManager().getActiveMobs().contains(event.getEntity())) {
            int currencyDropped = gameManager.getWaveManager().onMobDeath(event.getEntity());

            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                // 非参赛玩家（外部 /tp 进入竞技场世界等）击杀波次怪时 getPlayerData 返回 null，
                // 直接解引用会在事件循环内 NPE。判空后跳过记分即可（世界准入防护见 ArenaAccessListener）。
                PlayerData data = gameManager.getPlayerData(killer);
                if (data != null) {
                    data.addKill();
                    // fix4 反馈6：击杀者同步累计本局获取的能量水晶（战斗掉落口径）
                    data.addCurrencyCollected(currencyDropped);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 崩溃恢复：玩家离线期间若存在未归还的物品快照，登录时归还（不在竞技场时）
        // P2-34：管理器初始化失败时为 null → 跳过恢复（不阻断登录流程）
        if (playerGoodsManager != null
                && playerGoodsManager.has(player) && !multiArenaManager.isInArena(player)) {
            if (playerGoodsManager.restore(player)) {
                languageManager.send(player, "goods-restored");
            }
        }

        // 重连：存在未过期的竞技场重连记录时恢复到竞技场，不再走大厅流程
        if (multiArenaManager.tryRejoin(player)) return;

        // 大厅落地：不在竞技场时统一置于大厅状态
        if (multiArenaManager.isInArena(player)) return;
        multiArenaManager.applyLobbyState(player);
    }

    /** 大厅内死亡（掉虚空等）时在大厅出生点复活；fix5 M-16：非成员的重生点若指向
     *  竞技场世界（床 spawn 绕过准入的残余路径），同样改指大厅并清掉床 spawn。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (multiArenaManager.isInArena(player)) return;

        Location lobby = multiArenaManager.getLobbySpawn();

        // fix5 M-16：重生点落在竞技场世界且玩家不是该场成员 → 覆写重生点为大厅，
        // 并清掉床 spawn（防下次死亡再走同一条路）。成员与旁观者的正常重生不经过这里
        //（上面 isInArena 已放行成员；在竞技场世界内的成员重生由 PlayerLifecycleListener 处理）。
        // fix5 锐评 C-21（X4-F2）：判定改查「实例或定义」两库——disableArena 不卸载世界，
        // 场退役为 DISABLED 后其世界仍可被床 spawn 落回（respawn 不经准入传送拦截、
        // WAITING 期床不入回滚记账）；只查活动实例会漏掉这一姿态。定义库覆盖含已禁用/
        // 未部署的场（与 ArenaAccessListener 传送链的 getArenaByWorld 定义侧口径一致）。
        Location target = event.getRespawnLocation();
        if (target != null && target.getWorld() != null
                && !multiArenaManager.isLobbyWorld(target.getWorld().getName())
                && (multiArenaManager.getArenaByWorld(target.getWorld().getName()) != null
                        || multiArenaManager.getDefinitionManager()
                                .getArenaByWorld(target.getWorld().getName()) != null)) {
            if (lobby != null) {
                event.setRespawnLocation(lobby);
            }
            player.setBedSpawnLocation(null, true);
            return;
        }

        if (!multiArenaManager.isLobbyWorld(player.getWorld().getName())) return;
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // 对局进行中保留重连窗口；否则正常离开竞技场（内部会归还物品并送回大厅）
        if (multiArenaManager.isInArena(player)) {
            multiArenaManager.handleQuit(player);
        }

        // 玩家一走就把物品快照同步写完，不等 2 秒防抖窗口。
        // 理由：restore() 只登记了防抖任务，而玩家退出后 Bukkit 会把「已归还物品」的背包
        // 写进 .dat；若这 2 秒内服务器非优雅关闭（kill / 断电 / OOM），磁盘上仍是归还前的
        // 旧快照 → 下次登录会再归还一次 → 物品翻倍。这是玩家可主动制造的路径
        // （离开竞技场 → 秒退 → 逼服务器重启），因此必须在退出事件当场写盘。
        if (playerGoodsManager != null) {
            try {
                playerGoodsManager.saveNow();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "玩家退出时物品快照落盘失败", exception);
            }
        }

        // Cleanup player data
        hammerListener.cleanupPlayer(player);
        fireballListener.cleanupPlayer(player);
        lobbyProtectionListener.cleanupPlayer(player);
        for (ArenaInstance arena : multiArenaManager.getActiveArenas()) {
            arena.getScoreboard().cleanupPlayer(player);
        }
        // NumericInputHandler 的待处理聊天输入由其自带的 @EventHandler onQuit 清理
        // （静态 pendingInputs / pendingStringInputs，全局唯一监听实例即可覆盖）。
        languageManager.clearPlayerLocale(player.getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return commandHandler.handle(sender, command, label, args);
    }

}
