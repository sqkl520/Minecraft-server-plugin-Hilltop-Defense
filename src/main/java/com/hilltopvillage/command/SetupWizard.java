package com.hilltopvillage.command;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SetupWizard implements Listener {

    /** 生成点 GUI 每页条目数（54 格 GUI：slots 0-44 为条目，45-53 为底部操作行） */
    private static final int SPAWNS_PER_PAGE = 45;

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final ConcurrentHashMap<UUID, SetupSession> sessions = new ConcurrentHashMap<>();
    /**
     * 未部署竞技场的「部署期 GameManager」缓存（arenaId → GameManager）。
     *
     * <p>未部署竞技场没有正式 ArenaInstance（{@code MultiArenaManager.getArena()} 返回 null），
     * 因此拿不到挂在实例上的 GameManager / AdminDeployManager / NodeSystem。这里按需创建一个
     * 只依赖 ArenaDefinition + 已加载世界的部署期 GameManager，让向导能把坐标写进 arenas.yml，
     * 从而打破「没部署→没实例→setup 报无活动竞技场」的死锁。部署完成后随
     * {@link #finishDeployment(String)} 丢弃。</p>
     */
    private final ConcurrentHashMap<String, GameManager> deploymentContexts = new ConcurrentHashMap<>();
    /** PDC key：内部 tag 用持久化数据容器存储，不显示给玩家 */
    private final NamespacedKey TAG_KEY;

    public SetupWizard(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.TAG_KEY = new NamespacedKey(plugin, "setup-tag");
    }

    /**
     * 解析本次部署目标竞技场的 GameManager。
     *
     * <p>按<b>玩家当前所在竞技场</b>解析（会话里存的 arenaId，缺失时按所在世界反查定义），
     * 不再写死默认竞技场 —— 这顺带修复了「向导只能部署默认场」的问题。解析交给
     * {@link #deploymentGameManager(String)}：已启用竞技场用其正式实例的 GameManager，
     * 未部署竞技场用部署期 GameManager。</p>
     *
     * @return 目标竞技场的 GameManager；玩家不在任何竞技场世界 / 世界未加载时返回 null
     */
    private GameManager gameManager(Player player) {
        String arenaId = resolveArenaId(player);
        return arenaId == null ? null : deploymentGameManager(arenaId);
    }

    /** 解析部署工具（走 {@link #gameManager(Player)} 的同一套按会话/世界解析逻辑） */
    private AdminDeployManager deploy(Player player) {
        GameManager gm = gameManager(player);
        return gm == null ? null : gm.getAdminDeployManager();
    }

    /**
     * 解析该玩家本次部署的目标竞技场 ID：优先会话里存的 arenaId，缺失时按玩家所在世界反查。
     *
     * @return 竞技场 ID；玩家不在任何已配置竞技场世界时返回 null
     */
    private String resolveArenaId(Player player) {
        SetupSession session = sessions.get(player.getUniqueId());
        if (session != null && session.arenaId != null) return session.arenaId;
        ArenaDefinition def = plugin.getMultiArenaManager().getDefinitionManager()
                .getArenaByWorld(player.getWorld().getName());
        return def == null ? null : def.getId();
    }

    /**
     * 取某竞技场的部署期 GameManager。
     *
     * <p>优先返回已启用竞技场<b>正式实例</b>的 GameManager（部署改动须由它写入，才能让运行中的对局
     * 立即生效，并避免两份 AdminDeployManager 各写一份 arenas.yml 竞态）；没有正式实例（竞技场尚未部署、
     * 未进启用队列）时，惰性创建一个<b>只依赖 ArenaDefinition + 已加载世界</b>的部署期 GameManager 并缓存。</p>
     *
     * <p>这正是打破「没部署→没 core→没实例→setup 报无活动竞技场→永远无法部署」死锁的关键：
     * 部署期 GameManager 的 core 允许为 null（{@link GameManager} 构造函数不解引用 core；向导读 core
     * 走 {@code AdminDeployManager.getCoreLocation()}，对 null 安全），且不启动任何 Bukkit 任务，
     * 部署完成后随 {@link #finishDeployment(String)} 丢弃，GC 安全。</p>
     *
     * @return GameManager；竞技场定义不存在或世界未加载时返回 null
     */
    private GameManager deploymentGameManager(String arenaId) {
        ArenaInstance live = plugin.getMultiArenaManager().getArena(arenaId);
        if (live != null) {
            // 已有正式实例：改用它，顺手丢弃可能残留的部署期上下文（避免两份并存写盘）
            deploymentContexts.remove(arenaId);
            return live.getGameManager();
        }
        GameManager cached = deploymentContexts.get(arenaId);
        if (cached != null) {
            // 每次取用时重读 arenas.yml：部署期上下文的 AdminDeployManager 在构造时缓存了整份文件副本，
            // 两次向导操作之间磁盘可能被外部写入（如管理 GUI 的商店清除、其它竞技场的部署命令），
            // 不刷新的话向导的下一次 saveSpawnPoints() 会拿旧内存值把外部改动整份写回（「清除没生效」）。
            cached.getAdminDeployManager().loadSpawnPoints();
            return cached;
        }
        return deploymentContexts.computeIfAbsent(arenaId, this::createDeploymentContext);
    }

    /** 为未部署竞技场创建部署期 GameManager（core 可为 null；世界未加载返回 null，computeIfAbsent 不缓存） */
    private GameManager createDeploymentContext(String arenaId) {
        ArenaDefinition def = plugin.getMultiArenaManager().getDefinitionManager().getArena(arenaId);
        if (def == null) return null;
        World world = Bukkit.getWorld(def.getWorldName());
        if (world == null) return null;  // 玩家正站在该世界里，正常必然已加载
        return new GameManager(plugin, def.getId(), world,
                def.getCore(), def.getSpawnPoints(), def.getMobSpawnPoints());
    }

    /**
     * 部署完成收尾：让 registry 读到刚写入磁盘的 core，并把竞技场放进启用队列获得正式实例，
     * 使其可被 /hilltop start / join 正常使用；随后丢弃部署期上下文。
     *
     * <p>仅当该竞技场当前还没有正式实例时才 reload+enable（re-setup 已部署竞技场时实例已在跑、无需重启；
     * {@code enabled: false} 的竞技场会被 {@code enableArena} 如实拒绝，符合预期）。</p>
     *
     * @return true = 该竞技场已就绪或已成功入队（玩家可 join）；false = 未能启用，
     *         需要服主检查 arenas.yml（{@code enabled: false} 时按 F-3 文案指引改 true 重启，
     *         或在 {@code auto-start-arenas=false} 时用 /hilltop enable 手动入队）
     */
    private boolean finishDeployment(String arenaId) {
        if (arenaId == null) return false;
        if (plugin.getMultiArenaManager().getArena(arenaId) == null) {
            // 部署数据已由 AdminDeployManager 写入 arenas.yml，但 registry 里的定义仍是旧快照（core 为空），
            // 必须先 reload 让 enableArena 读到非空 core，否则会被「尚未部署核心」拒绝。
            plugin.getMultiArenaManager().reloadDefinitions();
            plugin.getMultiArenaManager().enableArena(arenaId);
        }
        deploymentContexts.remove(arenaId);
        // enableArena 返回 true 覆盖三种情形：已就绪 / 刚入队 / 已在队列中；只有被拒绝才需要服主手动处理
        return plugin.getMultiArenaManager().getArena(arenaId) != null
                || plugin.getMultiArenaManager().getStatus(arenaId).isPending();
    }

    public void start(Player player) {
        // 按玩家当前所在世界解析竞技场定义（不再写死默认竞技场）——这同时修复了
        // 「服主站在未部署竞技场世界里执行 setup 被提示无活动竞技场」的部署死锁。
        ArenaDefinition def = plugin.getMultiArenaManager().getDefinitionManager()
                .getArenaByWorld(player.getWorld().getName());
        if (def == null) {
            player.sendMessage(lang.getFor(player, "setup.no-arena-here"));
            return;
        }
        String arenaId = def.getId();

        // 部署期上下文须可用（世界已加载——玩家就站在里面，正常必然成立）
        if (deploymentGameManager(arenaId) == null) {
            player.sendMessage(lang.getFor(player, "setup.no-arena-here"));
            return;
        }

        // Fix 5: resume in-memory session instead of resetting
        SetupSession existing = sessions.get(player.getUniqueId());
        if (existing != null) {
            existing.arenaId = arenaId;  // 跨重连重建 / 换场时刷新目标竞技场
            player.sendMessage(LegacyComponentSerializer.legacySection()
                    .deserialize(lang.getFor(player, "setup.resuming", String.valueOf(existing.step))));
            HandlerList.unregisterAll(this);
            Bukkit.getPluginManager().registerEvents(this, plugin);
            showStep(player, existing);
            return;
        }
        // Fix 4: restore step from persistent config after disconnect
        int savedStep = plugin.getConfig().getInt("wizard.step." + player.getUniqueId(), 0);
        SetupSession session = new SetupSession();
        session.arenaId = arenaId;
        if (savedStep > 1 && savedStep < TOTAL_STEPS) {
            session.step = savedStep;
            player.sendMessage(LegacyComponentSerializer.legacySection()
                    .deserialize(lang.getFor(player, "setup.resuming", String.valueOf(savedStep))));
            HandlerList.unregisterAll(this);
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
        sessions.put(player.getUniqueId(), session);
        showStep(player, session);
    }

    /** 向导总步骤数（1 世界 → 2 核心 → 3 生成点 → 4 节点 → 5 村民 → 6 商店 → 7 预览 → 8 完成） */
    private static final int TOTAL_STEPS = 8;

    private void showStep(Player player, SetupSession s) {
        // 预览村民只服务于第 5 步村民管理：一旦离开该步就立即移除，
        // 避免它们残留到后续步骤、部署完成乃至开局（与开局生成的真村民混淆）。
        if (s.step != 5) {
            clearVillagerPreviews(player);
        }
        switch (s.step) {
            case 1: showStepWorld(player, s); break;
            case 2: showStepCore(player, s); break;
            case 3: showStepSpawns(player, s); break;
            case 4: showStepNodes(player, s); break;
            case 5: showStepVillagers(player, s); break;
            case 6: showStepShop(player, s); break;
            case 7: showStepPreview(player, s); break;
            case 8: showStepDone(player); break;
        }
    }

    // === Step 1: Confirm World ===
    private void showStepWorld(Player player, SetupSession s) {
        World world = player.getWorld();
        String msg = lang.getFor(player, "setup.step1", "1", "8")
                .replace("{world}", world.getName());
        sendClickableMsg(player, msg + " " + lang.getFor(player, "setup.btn-confirm-world"), "/hilltop setup confirm-world");
    }

    /** goto命令辅助：仅发送Step 1消息（不修改session） */
    private void sendStepWorldMessage(Player player, SetupSession s) {
        World world = player.getWorld();
        String msg = lang.getFor(player, "setup.step1", "1", "8")
                .replace("{world}", world.getName());
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(msg + " " + lang.getFor(player, "setup.btn-confirm-world"))
                .clickEvent(ClickEvent.runCommand("/hilltop setup confirm-world")));
    }

    // === Step 2: Set Core ===
    private void showStepCore(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step2", "2", "8");
        sendClickableMsg(player, msg + " " + lang.getFor(player, "setup.btn-confirm-core"), "/hilltop setup confirm-core");
    }

    /** goto命令辅助：仅发送Step 2消息（不修改session） */
    private void sendStepCoreMessage(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step2", "2", "8");
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(msg + " " + lang.getFor(player, "setup.btn-confirm-core"))
                .clickEvent(ClickEvent.runCommand("/hilltop setup confirm-core")));
    }

    // === Step 3: Spawn Points（聊天栏优先，GUI 按需打开） ===
    private void showStepSpawns(Player player, SetupSession s) {
        // 不再自动打开 GUI：管理员需要先走到生成点位置，GUI 挡视角无法移动喵
        String msg = lang.getFor(player, "setup.step3", "3", "8");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        // STORY-1.2：同一步内追加怪物刷怪点说明（两套点各需 ≥1，不新增向导步骤，总步数仍为 8）
        String mobMsg = lang.getFor(player, "setup.step3-mobspawns", "3", "8");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(mobMsg));
        sendSpawnButtons(player);
    }

    /**
     * 发送 Step 3 的可点击按钮行（添加当前位置 / 添加刷怪点 / 打开生成点总览 / 保存并返回大厅）。
     *
     * <p>F-9：两个子管理界面收进「生成点总览」枢纽，聊天栏只保留一个入口按钮；
     * 全流程唯一的 [完成] 按钮在总览 GUI 内（底行正中 slot 49），聊天栏不再放 [完成] 避免误触。</p>
     */
    private void sendSpawnButtons(Player player) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button(lang.getFor(player, "setup.btn-add"), "/hilltop setup addspawn"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-add-mobspawn"), "/hilltop setup addmobspawn"));
        // F-9：总览入口（内含两个子管理界面与唯一 [完成]）
        buttons.add(new Button(lang.getFor(player, "setup.btn-open-overview"), "/hilltop setup overview"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-save-exit"), "/hilltop setup saveexit"));
        sendButtonLine(player, buttons);
    }

    /**
     * 打开生成点总览 GUI（F-9：第 3 步的枢纽界面——作者需求「将两个管理界面用一个界面链接起来，
     * 在那个链接界面添加完成按钮」）。聊天栏 [打开生成点总览] 按钮与命令 {@code /hilltop setup overview}
     * 均走这里；界面内显示两套点的计数与状态、两个子管理界面入口，以及全流程唯一的 [完成]。
     */
    private void showStepSpawnsOverview(Player player) {
        openWizardGui(player, buildSpawnsOverview(player));
    }

    /** 构建生成点总览界面（不打开；供同步打开与点击后的延迟切换共用） */
    private Inventory buildSpawnsOverview(Player player) {
        Inventory inv = Bukkit.createInventory(player, 54,
                lang.getFor(player, "setup.gui-spawns-overview-title"));
        refreshSpawnsOverview(inv, player);
        return inv;
    }

    /** 打开生成点管理 GUI（可从聊天按钮或命令随时重新打开，不再丢失入口；F-9 起亦可从总览进入） */
    private void showStepSpawnsGui(Player player, SetupSession s) {
        openWizardGui(player, buildSpawnGui(player, s));
    }

    /** 构建生成点管理界面（54 格，带翻页；不打开——F-9 总览内延迟切换复用） */
    private Inventory buildSpawnGui(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 54,
                lang.getFor(player, "setup.gui-spawns-title"));
        refreshSpawnGui(inv, player, s.spawnPage);
        return inv;
    }

    /** 打开怪物刷怪点管理 GUI（STORY-1.2：与生成点 GUI 同布局，数据源为 mob-spawn-points） */
    private void showStepMobSpawnsGui(Player player, SetupSession s) {
        openWizardGui(player, buildMobSpawnGui(player, s));
    }

    /** 构建怪物刷怪点管理界面（不打开；F-9 总览内延迟切换复用） */
    private Inventory buildMobSpawnGui(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 54,
                lang.getFor(player, "setup.gui-mobspawns-title"));
        refreshMobSpawnGui(inv, player, s.mobSpawnPage);
        return inv;
    }

    /**
     * 打开向导 GUI 并保证监听器在位。
     *
     * <p>注册必须放在 {@code openInventory} <b>之后</b>：打开新界面会同步触发旧界面的
     * InventoryCloseEvent（若旧界面是生成点/刷怪点界面，onSetupInventoryClose 会把监听器
     * unregister 掉），先注册后打开会被这一步连带注销，导致新界面点击无反应喵。
     * 打开后再 unregister + register 同时防止重复注册。</p>
     */
    private void openWizardGui(Player player, Inventory inv) {
        player.openInventory(inv);
        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void refreshSpawnGui(Inventory inv, Player player, int page) {
        inv.clear();
        List<org.bukkit.Location> points = deploy(player).getSpawnPoints();
        // Show up to 45 entries per page in slots 0–44
        int startIdx = page * SPAWNS_PER_PAGE;
        int endIdx = Math.min(startIdx + SPAWNS_PER_PAGE, points.size());
        for (int i = startIdx; i < endIdx; i++) {
            org.bukkit.Location loc = points.get(i);
            int slot = i - startIdx;
            inv.setItem(slot, makeItem(Material.COMPASS,
                    lang.getFor(player, "setup.spawn-entry",
                            String.valueOf(i), fmt(loc.getX()), fmt(loc.getY()), fmt(loc.getZ())),
                    lang.getFor(player, "setup.spawn-entry-lore"),
                    "setup-spawn-" + i));
        }
        // slot 45: prev page arrow (or placeholder)
        if (page > 0) {
            inv.setItem(45, makeItem(Material.ARROW, lang.getFor(player, "setup.btn-prev-page"), "setup-prev-page"));
        } else {
            inv.setItem(45, makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", "setup-noop"));
        }
        // slot 47: show particles
        inv.setItem(47, makeItem(Material.FIREWORK_STAR, lang.getFor(player, "setup.btn-show-particles"),
                lang.getFor(player, "setup.btn-show-particles-lore"), "setup-show-particles"));
        // slot 49: add current location
        inv.setItem(49, makeItem(Material.EMERALD_BLOCK, lang.getFor(player, "setup.btn-add"),
                lang.getFor(player, "setup.btn-add-lore"), "setup-add-spawn"));
        // slot 51: F-9 返回总览（[完成] 已统一收敛到总览 GUI，此处不再放第二个完成按钮）
        inv.setItem(51, makeItem(Material.RED_STAINED_GLASS_PANE,
                lang.getFor(player, "setup.btn-back-overview"), "setup-back-overview"));
        // slot 53: next page arrow (or placeholder)
        int maxPage = points.isEmpty() ? 0 : (points.size() - 1) / SPAWNS_PER_PAGE;
        if (page < maxPage) {
            inv.setItem(53, makeItem(Material.ARROW, lang.getFor(player, "setup.btn-next-page"), "setup-next-page"));
        } else {
            inv.setItem(53, makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", "setup-noop"));
        }
    }

    /**
     * 刷新生成点总览 GUI（F-9 枢纽，全 54 格无翻页）。
     *
     * <p>布局：两套点各一行「计数 + 状态」信息物品与子管理入口按钮（对称），
     * 底行唯一 [完成] 居中。校验与旧完成入口完全一致（两套各 ≥1）。</p>
     */
    private void refreshSpawnsOverview(Inventory inv, Player player) {
        inv.clear();
        int spawnCount = deploy(player).getSpawnPoints().size();
        int mobCount = deploy(player).getMobSpawnPoints().size();

        // 第 2 行：玩家出生点（信息 + 状态）
        inv.setItem(11, makeItem(Material.COMPASS,
                lang.getFor(player, "setup.overview-spawn-status",
                        String.valueOf(spawnCount)),
                spawnCount > 0 ? lang.getFor(player, "setup.overview-status-ok")
                        : lang.getFor(player, "setup.overview-status-missing"),
                "setup-noop"));
        // 第 2 行：怪物刷怪点（信息 + 状态）
        inv.setItem(15, makeItem(Material.SPAWNER,
                lang.getFor(player, "setup.overview-mobspawn-status",
                        String.valueOf(mobCount)),
                mobCount > 0 ? lang.getFor(player, "setup.overview-status-ok")
                        : lang.getFor(player, "setup.overview-status-missing"),
                "setup-noop"));
        // 第 3 行：两个子管理界面入口（与上方信息物品垂直对齐）
        inv.setItem(20, makeItem(Material.CHEST,
                lang.getFor(player, "setup.btn-open-gui"),
                lang.getFor(player, "setup.overview-open-gui-lore"), "setup-open-spawns-gui"));
        inv.setItem(24, makeItem(Material.CHEST,
                lang.getFor(player, "setup.btn-open-mobspawn-gui"),
                lang.getFor(player, "setup.overview-open-mobspawn-gui-lore"), "setup-open-mobspawns-gui"));
        // 底行正中：唯一 [完成]（F-9 的核心 —— 全流程只有一个完成入口）
        boolean ready = spawnCount > 0 && mobCount > 0;
        inv.setItem(49, makeItem(ready ? Material.GREEN_WOOL : Material.GRAY_WOOL,
                lang.getFor(player, "setup.btn-done"),
                ready ? lang.getFor(player, "setup.btn-done-lore")
                        : lang.getFor(player, "setup.overview-done-locked-lore"),
                "setup-overview-done"));
    }

    /**
     * 第 3 步完成收口：两套点各 ≥1 才推进到第 4 步（校验与旧双完成按钮完全一致）。
     *
     * <p>调用方：总览 GUI 的 [完成]、旧版 GUI 遗留 tag（{@code setup-spawns-done} /
     * {@code setup-mobspawns-done}）与命令 {@code /hilltop setup spawns-done}——
     * 三处自此共用同一份校验 + 推进 + 保存实现。</p>
     */
    private void tryFinishSpawnsStep(Player player, SetupSession s) {
        if (deploy(player).getSpawnPoints().isEmpty()) {
            player.sendMessage(lang.getFor(player, "setup.spawns-required"));
            return;
        }
        if (deploy(player).getMobSpawnPoints().isEmpty()) {
            player.sendMessage(lang.getFor(player, "setup.mobspawns-required"));
            return;
        }
        HandlerList.unregisterAll(this);
        player.closeInventory();
        s.advance();
        autoSave(player);
        showStep(player, s);
    }

    /** 刷新怪物刷怪点管理 GUI（STORY-1.2：slot 布局与 refreshSpawnGui 完全一致，tags 加 mob- 前缀隔离） */
    private void refreshMobSpawnGui(Inventory inv, Player player, int page) {
        inv.clear();
        List<org.bukkit.Location> points = deploy(player).getMobSpawnPoints();
        // Show up to 45 entries per page in slots 0–44
        int startIdx = page * SPAWNS_PER_PAGE;
        int endIdx = Math.min(startIdx + SPAWNS_PER_PAGE, points.size());
        for (int i = startIdx; i < endIdx; i++) {
            org.bukkit.Location loc = points.get(i);
            int slot = i - startIdx;
            inv.setItem(slot, makeItem(Material.SPAWNER,
                    lang.getFor(player, "setup.mobspawn-entry",
                            String.valueOf(i), fmt(loc.getX()), fmt(loc.getY()), fmt(loc.getZ())),
                    lang.getFor(player, "setup.spawn-entry-lore"),
                    "setup-mobspawn-" + i));
        }
        // slot 45: prev page arrow (or placeholder)
        if (page > 0) {
            inv.setItem(45, makeItem(Material.ARROW, lang.getFor(player, "setup.btn-prev-page"), "setup-mob-prev-page"));
        } else {
            inv.setItem(45, makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", "setup-noop"));
        }
        // slot 47: show particles
        inv.setItem(47, makeItem(Material.FIREWORK_STAR, lang.getFor(player, "setup.btn-show-particles"),
                lang.getFor(player, "setup.btn-particles-lore-mobspawn"), "setup-mob-show-particles"));
        // slot 49: add current location
        inv.setItem(49, makeItem(Material.EMERALD_BLOCK, lang.getFor(player, "setup.btn-add"),
                lang.getFor(player, "setup.btn-add-lore"), "setup-add-mobspawn"));
        // slot 51: F-9 返回总览（[完成] 已统一收敛到总览 GUI，此处不再放第二个完成按钮）
        inv.setItem(51, makeItem(Material.RED_STAINED_GLASS_PANE,
                lang.getFor(player, "setup.btn-back-overview"), "setup-mob-back-overview"));
        // slot 53: next page arrow (or placeholder)
        int maxPage = points.isEmpty() ? 0 : (points.size() - 1) / SPAWNS_PER_PAGE;
        if (page < maxPage) {
            inv.setItem(53, makeItem(Material.ARROW, lang.getFor(player, "setup.btn-next-page"), "setup-mob-next-page"));
        } else {
            inv.setItem(53, makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", "setup-noop"));
        }
    }

    // === Step 4: 节点管理（手动注册）===
    private void showStepNodes(Player player, SetupSession s) {
        // Step 4 改为直接发送说明 + 按钮，不再自动扫描
        String msg = lang.getFor(player, "setup.step4-nodes", "4", "8");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        sendNodeButtons(player);
    }

    /** 发送节点管理按钮行 */
    private void sendNodeButtons(Player player) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button(lang.getFor(player, "setup.btn-add-node"), "/hilltop setup addnode"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-nodes-gui"), "/hilltop setup nodesgui"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-continue"), "/hilltop setup confirm-nodes"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-save-exit"), "/hilltop setup saveexit"));
        sendButtonLine(player, buttons);
    }

    // === Step 5: 村民管理 GUI ===

    /** 打开村民管理 GUI（54 格，参照节点管理） */
    private void showVillagersGui(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 54,
                lang.getFor(player, "setup.gui-villagers-title"));

        refreshVillagersGui(inv, player, s.villagerPage);

        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        player.openInventory(inv);
    }

    /** 刷新村民管理 GUI */
    private void refreshVillagersGui(Inventory inv, Player player, int page) {
        inv.clear();

        List<Location> villagers = deploy(player).getVillagers();

        // 条目区域：slots 0-44
        int startIndex = page * SPAWNS_PER_PAGE;
        int endIndex = Math.min(startIndex + SPAWNS_PER_PAGE, villagers.size());

        for (int i = startIndex; i < endIndex; i++) {
            Location loc = villagers.get(i);
            int slot = i - startIndex;

            ItemStack item = new ItemStack(Material.VILLAGER_SPAWN_EGG);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(lang.getFor(player, "setup.villager-entry",
                        String.valueOf(i + 1))));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7世界: §f" + (loc.getWorld() == null ? "?" : loc.getWorld().getName())));
                lore.add(Component.text("§7坐标: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
                lore.add(Component.text(""));
                lore.add(Component.text(lang.getFor(player, "setup.villager-entry-lore")));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "villager-" + i);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
        }

        // 空列表提示
        if (villagers.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta meta = empty.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(lang.getFor(player, "setup.villagers-empty")));
                meta.lore(List.of(Component.text(lang.getFor(player, "setup.villagers-required"))));
                empty.setItemMeta(meta);
            }
            inv.setItem(22, empty);
        }

        // 底部操作行
        if (page > 0) {
            inv.setItem(45, taggedItem(Material.ARROW, lang.getFor(player, "setup.btn-prev-page"),
                    null, "villager-prev-page"));
        }

        // slot 47: 显示粒子（F-7：黄色光柱 = 待保护村民，与生成点 GUI 布局一致）
        inv.setItem(47, taggedItem(Material.FIREWORK_STAR, lang.getFor(player, "setup.btn-show-particles"),
                lang.getFor(player, "setup.btn-particles-lore-villager"), "villager-show-particles"));

        inv.setItem(49, taggedItem(Material.VILLAGER_SPAWN_EGG,
                lang.getFor(player, "setup.btn-add-villager"),
                lang.getFor(player, "setup.btn-add-villager-lore"), "setup-add-villager-item"));

        inv.setItem(51, taggedItem(Material.LIME_WOOL,
                lang.getFor(player, "setup.btn-done"),
                lang.getFor(player, "setup.btn-done-lore"), "setup-villagers-done"));

        int maxPage = villagers.isEmpty() ? 0 : (villagers.size() - 1) / SPAWNS_PER_PAGE;
        if (page < maxPage) {
            inv.setItem(53, taggedItem(Material.ARROW, lang.getFor(player, "setup.btn-next-page"),
                    null, "villager-next-page"));
        }
    }

    /** 构造带 PDC 标签的物品（GUI 内部按钮统一走这里） */
    private ItemStack taggedItem(Material material, String name, String loreLine, String tag) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (loreLine != null) {
                meta.lore(List.of(Component.text(loreLine)));
            }
            meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, tag);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 村民列表在聊天栏模式下操作后的反馈：
     * 若玩家正开着村民 GUI 则刷新它，否则重发聊天栏按钮行。
     */
    private void refreshVillagerGuiOrResend(Player player, SetupSession s) {
        String title = lang.getFor(player, "setup.gui-villagers-title");
        if (player.getOpenInventory().getTitle() != null
                && player.getOpenInventory().getTitle().equals(title)) {
            refreshVillagersGui(player.getOpenInventory().getTopInventory(), player, s.villagerPage);
        } else {
            sendVillagerButtons(player);
        }
    }

    // === 村民预览实体（第 5 步专用，见 VillagerPreviewManager） ===

    /**
     * 把本次部署竞技场世界内的村民预览实体同步为与已登记位置一一对应（序号 = 下标 + 1）。
     * 进入第 5 步、增删村民后调用；预览实体与开局真村民完全隔离（独立 PDC 标记 + 独立生命周期）。
     */
    private void syncVillagerPreviews(Player player) {
        AdminDeployManager d = deploy(player);
        if (d == null) return;
        World world = arenaWorld(player);
        if (world == null) return;
        plugin.getVillagerPreviewManager().syncPreviews(world, d.getVillagers(), player);
    }

    /** 清理本次部署竞技场世界内的村民预览实体（离开第 5 步 / 会话结束 / 部署完成时调用，幂等） */
    private void clearVillagerPreviews(Player player) {
        World world = arenaWorld(player);
        if (world == null) return;
        plugin.getVillagerPreviewManager().clearInWorld(world);
    }

    /** 本次会话目标竞技场世界（解析不到返回 null） */
    private World arenaWorld(Player player) {
        GameManager gm = gameManager(player);
        return gm == null ? null : gm.getGameWorld();
    }

    /** 打开节点管理 GUI（54格，类似生成点管理） */
    private void showStepNodesGui(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 54,
                lang.getFor(player, "setup.gui-nodes-title"));

        refreshNodesGui(inv, player, s.nodePage);

        // 重新注册监听器（确保GUI可交互）
        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        player.openInventory(inv);
    }

    /** 刷新节点管理 GUI */
    private void refreshNodesGui(Inventory inv, Player player, int page) {
        inv.clear();

        List<Location> nodeList = gameManager(player).getNodeSystem().getAllNodeLocations();

        // 条目区域：slots 0-44（45个）
        int totalPages = nodeList.isEmpty() ? 0 : (nodeList.size() - 1) / 45;
        int startIndex = page * 45;
        int endIndex = Math.min(startIndex + 45, nodeList.size());

        for (int i = startIndex; i < endIndex; i++) {
            Location loc = nodeList.get(i);
            int slot = i - startIndex;

            ItemStack item = new ItemStack(Material.BEACON);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e节点 #" + (i + 1)));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7世界: §f" + loc.getWorld().getName()));
                lore.add(Component.text("§7坐标: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
                lore.add(Component.text(""));
                lore.add(Component.text("§c点击删除"));
                meta.lore(lore);

                // 使用 PDC 保存节点索引
                meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "node-" + i);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
        }

        // 底部操作行：slots 45-53
        // Slot 45: 上一页
        if (page > 0) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta meta = prevPage.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e上一页"));
                meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "node-prev-page");
                prevPage.setItemMeta(meta);
            }
            inv.setItem(45, prevPage);
        }

        // Slot 47: 显示粒子
        ItemStack showParticles = new ItemStack(Material.ENDER_EYE);
        ItemMeta particleMeta = showParticles.getItemMeta();
        if (particleMeta != null) {
            particleMeta.displayName(Component.text(lang.getFor(player, "setup.btn-show-particles")));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7查看所有节点的位置"));
            particleMeta.lore(lore);
            particleMeta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "node-show-particles");
            showParticles.setItemMeta(particleMeta);
        }
        inv.setItem(47, showParticles);

        // Slot 49: 添加当前位置
        ItemStack addNode = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta addMeta = addNode.getItemMeta();
        if (addMeta != null) {
            addMeta.displayName(Component.text(lang.getFor(player, "setup.btn-add-node")));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7站在节点方块上方点击注册"));
            addMeta.lore(lore);
            addMeta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "setup-add-node-item");
            addNode.setItemMeta(addMeta);
        }
        inv.setItem(49, addNode);

        // Slot 51: 完成
        ItemStack done = new ItemStack(Material.LIME_WOOL);
        ItemMeta doneMeta = done.getItemMeta();
        if (doneMeta != null) {
            doneMeta.displayName(Component.text("§a完成"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7共注册 §f" + nodeList.size() + " §7个节点"));
            doneMeta.lore(lore);
            doneMeta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "setup-nodes-done");
            done.setItemMeta(doneMeta);
        }
        inv.setItem(51, done);

        // Slot 53: 下一页
        if (page < totalPages) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta meta = nextPage.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e下一页"));
                meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, "node-next-page");
                nextPage.setItemMeta(meta);
            }
            inv.setItem(53, nextPage);
        }
    }

    // === Step 5: 待保护村民（聊天栏模式，可跳过） ===
    private void showStepVillagers(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step5-villagers",
                "5", String.valueOf(TOTAL_STEPS), String.valueOf(deploy(player).getVillagers().size()));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        // 进入第 5 步：同步预览实体（重连/重入时也能看到已登记村民的位置与序号）
        syncVillagerPreviews(player);
        sendVillagerButtons(player);
    }

    /**
     * 发送 Step 5 的可点击按钮行（添加村民 / 打开管理 GUI / 完成）。
     * 全部走 /hilltop setup 子命令，以便受步骤守卫保护（避免在第 6 步还能操作第 5 步）。
     */
    private void sendVillagerButtons(Player player) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button(lang.getFor(player, "setup.btn-add-villager"), "/hilltop setup addvillager"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-villagers-gui"), "/hilltop setup villagersgui"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-done"), "/hilltop setup villagers-done"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-save-exit"), "/hilltop setup saveexit"));
        sendButtonLine(player, buttons);
    }

    // === Step 6: Shop NPC（聊天栏模式，可跳过） ===
    private void showStepShop(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step5-shop", "6", String.valueOf(TOTAL_STEPS));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        sendShopStatus(player);
        sendShopButtons(player);
    }

    /**
     * 发送 Step 6 的可点击按钮行（设置 / 预览粒子 / 清除 / 继续 / 保存并返回大厅）。
     * 商店为**强制步骤**：未设置商店位置不能完成部署；设置后停留本步可随时重设 / 清除，
     * 由 [继续] 进入第 7 步预览（preview 子命令负责校验并推进）。
     */
    private void sendShopButtons(Player player) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button(lang.getFor(player, "setup.btn-set-shop"), "/hilltop setup confirm-shop"));
        // F-7：商店位置预览（金色光柱；未设置时点按钮会提示「还没有设置商店位置」）
        buttons.add(new Button(lang.getFor(player, "setup.btn-show-particles"), "/hilltop setup showshopparticles"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-clear-shop"), "/hilltop setup clear-shop"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-continue"), "/hilltop setup preview"));
        buttons.add(new Button(lang.getFor(player, "setup.btn-save-exit"), "/hilltop setup saveexit"));
        sendButtonLine(player, buttons);
    }

    /** 发送 Step 6 的当前商店位置状态行（未设置时给出必填提示，清除后能立刻看到变化） */
    private void sendShopStatus(Player player) {
        Location shop = deploy(player).getShopNpcLocation();
        String value = shop == null
                ? lang.getFor(player, "setup.shop-status-unset")
                : fmt(shop.getX()) + ", " + fmt(shop.getY()) + ", " + fmt(shop.getZ());
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(lang.getFor(player, "setup.shop-status", value)));
    }

    /** goto命令辅助：仅发送Step 6消息（不修改session） */
    private void sendStepShopMessage(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step5-shop", "6", String.valueOf(TOTAL_STEPS));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        sendShopStatus(player);
        sendShopButtons(player);
    }

    // === Step 6: Config Preview (GUI) ===
    private void showStepPreview(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 9,
                lang.getFor(player, "setup.gui-preview-title"));
        var cfg = gameManager(player).getConfigManager().getActiveConfig();
        inv.setItem(0, infoItem(Material.GRASS_BLOCK, lang.getFor(player, "setup.preview-world",
                gameManager(player).getGameWorld().getName())));
        // Fix 6: use deploy(player).getCoreLocation() instead of gameManager to avoid stale/null NPE
        org.bukkit.Location core = deploy(player).getCoreLocation();
        if (core != null) {
            inv.setItem(1, infoItem(Material.BEACON, lang.getFor(player, "setup.preview-core",
                    fmt(core.getX()), fmt(core.getY()), fmt(core.getZ()))));
        } else {
            inv.setItem(1, infoItem(Material.BARRIER, lang.getFor(player, "setup.preview-core-not-set")));
        }
        inv.setItem(2, infoItem(Material.SPAWNER, lang.getFor(player, "setup.preview-spawns",
                String.valueOf(deploy(player).getSpawnPoints().size()))));
        inv.setItem(3, infoItem(Material.ZOMBIE_HEAD, lang.getFor(player, "setup.preview-mobspawns",
                String.valueOf(deploy(player).getMobSpawnPoints().size()))));
        inv.setItem(4, infoItem(Material.CRYING_OBSIDIAN, lang.getFor(player, "setup.preview-nodes",
                String.valueOf(gameManager(player).getNodeSystem().getTotalCount()))));
        inv.setItem(5, infoItem(Material.PLAYER_HEAD, lang.getFor(player, "setup.preview-players",
                String.valueOf(cfg.getMinPlayers()), String.valueOf(cfg.getMaxPlayers()))));
        inv.setItem(6, infoItem(Material.CLOCK, lang.getFor(player, "setup.preview-waves",
                String.valueOf(cfg.getVictoryWaves()))));
        // 村民统计：预览 GUI 只有 9 格（slot 7/8 是重置/确认按钮），刷怪点占用 slot 3 后已无空位，
        // 因此村民数拼进 slot 5（人数项）的 lore 第二行，只读展示，不影响 setup-confirm 的强制校验
        ItemStack playersItem = inv.getItem(5);
        if (playersItem != null && playersItem.hasItemMeta()) {
            ItemMeta playersMeta = playersItem.getItemMeta();
            playersMeta.setLore(List.of(ChatColor.translateAlternateColorCodes('&',
                    lang.getFor(player, "setup.preview-villagers",
                            String.valueOf(deploy(player).getVillagers().size())))));
            playersItem.setItemMeta(playersMeta);
        }
        inv.setItem(7, makeItem(Material.RED_WOOL, lang.getFor(player, "setup.btn-reset"),
                lang.getFor(player, "setup.btn-reset-lore"), "setup-reset"));
        inv.setItem(8, makeItem(Material.GREEN_WOOL, lang.getFor(player, "setup.btn-confirm"),
                lang.getFor(player, "setup.btn-confirm-lore"), "setup-confirm"));
        // Fix: Step 3 done 时 unregisterAll 了解听器，这里必须重新注册，否则预览 GUI 点击无反应、物品可被拿走
        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
    }

    // === Step 7: Done ===
    private void showStepDone(Player player) {
        // 收尾前先取出本次部署的目标竞技场（随后要移除会话）
        SetupSession session = sessions.get(player.getUniqueId());
        String arenaId = session != null ? session.arenaId : null;

        // 兜底：向导中途未触发生成（如跳过又手动补配置）时，配置了商店位置则补生成
        AdminDeployManager deploy = deploy(player);
        Location shopNpcLoc = deploy == null ? null : deploy.getShopNpcLocation();
        if (shopNpcLoc != null) {
            plugin.getShopManager().getNpcManager().spawnNpc(shopNpcLoc);
        }
        String msg = lang.getFor(player, "setup.done");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        player.sendMessage(
                LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.done-start"))
                        .clickEvent(ClickEvent.suggestCommand("/hilltop start")));
        sessions.remove(player.getUniqueId());
        // Fix 4: clear persisted step on successful completion
        plugin.getConfig().set("wizard.step." + player.getUniqueId(), null);
        plugin.saveConfig();

        // 部署完成：启用竞技场，使其获得正式实例、可被 /hilltop start 使用
        boolean enableQueued = finishDeployment(arenaId);
        // F-3：按实际结果补启用指引 —— 自动入队成功与「未能启用（如 enabled: false）」给出不同文案，
        // 后者附可点击的 [启用竞技场] 按钮（对 auto-start-arenas=false 直接有效；enabled: false 需先改配置重启）
        if (arenaId != null) {
            if (enableQueued) {
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.enable-done-auto")));
            } else {
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.enable-done-manual")));
                sendClickableMsg(player, lang.getFor(player, "setup.btn-enable-arena", arenaId),
                        "/hilltop enable " + arenaId);
            }
        }
    }

    // === Command Handler (called from onCommand) ===
    public boolean handle(Player player, String sub) {
        SetupSession s = sessions.get(player.getUniqueId());
        if (s == null) return false;

        switch (sub.toLowerCase()) {
            case "confirm-world":
                deploy(player).setCoreLocation(null); // ensure clean, saves world implicitly
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "confirm-core":
                deploy(player).setCoreLocation(player.getLocation());
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "confirm-shop":
                // 聊天按钮入口：记录商店村民位置并立即生成（限步骤 6 使用）
                if (s.step != 6) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                // 只复制玩家的水平朝向（yaw），pitch 归零：村民不低头/抬头（F-4）。
                // 存盘与生成都用这份已归零的坐标，避免低头状态被写进 arenas.yml 后连重启也保留。
                Location shopNpcLocation = player.getLocation().clone();
                shopNpcLocation.setPitch(0f);
                deploy(player).setShopNpcLocation(shopNpcLocation);
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                // 静默保存：粒子/音效已给反馈，避免 setup.saved 刷屏
                autoSaveSilent(player);
                // 立即生成 NPC（spawnNpc 内部先 despawn，幂等可重复设置）
                plugin.getShopManager().getNpcManager().spawnNpc(shopNpcLocation);
                // 刻意不 advance：停留第 6 步允许原地重设 / 清除，避免「设置完立刻跳走、想改只能 goto 6」；
                // 进入第 7 步改由 [继续]（preview 子命令）负责，其校验与本步一致
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.shop-set")));
                sendShopStatus(player);
                sendShopButtons(player);
                break;
            case "clear-shop":
                // 聊天按钮入口：清除商店位置与 NPC 实体（限步骤 6 使用；清除后留在本步）
                if (s.step != 6) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                if (deploy(player).getShopNpcLocation() == null) {
                    player.sendMessage(lang.getFor(player, "setup.shop-clear-none"));
                    sendShopButtons(player);
                    break;
                }
                // 对局进行中拒绝：商店 NPC 是插件级单例，清除会当场撤掉场内玩家正在使用的商店
                // （与管理 GUI 的清除口径一致，见 ArenaAdminService.deleteShopNpc）
                GameState state = gameManager(player).getState();
                if (state == GameState.STARTING || state == GameState.RUNNING
                        || state == GameState.WAVE_INTERMISSION) {
                    player.sendMessage(lang.getFor(player, "setup.shop-clear-in-game"));
                    sendShopButtons(player);
                    break;
                }
                // 清配置：setShopNpcLocation(null) 会同步删除 arenas.yml 的 shop-npc 键并打「已定论」标记
                deploy(player).setShopNpcLocation(null);
                // 清实体：只清「正站在目标竞技场世界」的单例实体，别处（其它竞技场/大厅）的商店保持不动
                plugin.getShopManager().getNpcManager().despawnIfInWorld(
                        gameManager(player).getGameWorld().getName());
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
                autoSaveSilent(player);
                // 不 advance：清除是「重来」的起点，留在第 6 步直接重新设置；
                // 第 7 步预览/最终确认会重新以 setup.shop-required 拦截（商店为必填项，这是预期行为）
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.shop-cleared")));
                sendShopStatus(player);
                sendShopButtons(player);
                break;
            case "showshopparticles":
                // F-7：商店位置预览（限步骤 6 使用；提示由预览方法统一发送）
                if (s.step != 6) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                deploy(player).showShopParticles(player, lang);
                // 重新发送按钮行，与生成点/刷怪点 GUI 的预览行为对齐
                sendShopButtons(player);
                break;
            case "addspawn":
                // 聊天按钮入口：添加玩家当前位置为生成点（限步骤3使用）
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                deploy(player).addSpawnPoint(player.getLocation());
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                // 带序号的精简反馈：只报“生成点 #N 已添加”（&a 绿 = 已完成确认）
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.spawn-added",
                                String.valueOf(deploy(player).getSpawnPoints().size()))));
                // 聊天滚动后按钮不方便点：反馈之后重发按钮行（黄/青 = 可操作按钮，与绿色反馈区分）
                sendSpawnButtons(player);
                // 静默保存：不发 setup.saved 提示，避免 addspawn 高频操作时刷屏
                autoSaveSilent(player);
                break;
            case "addnode":
                // 聊天按钮入口：手动添加节点（检查脚下方块）
                if (s.step != 4) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                int result = gameManager(player).getNodeSystem().addNode(player.getLocation());
                if (result == 0) {
                    int count = gameManager(player).getNodeSystem().getTotalCount();
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.node-added", String.valueOf(count))));
                    player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                    // 重新发送按钮行，让按钮永远在聊天栏底部
                    sendNodeButtons(player);
                } else if (result == 1) {
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.node-not-valid-block")));
                } else if (result == 2) {
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.node-already-exists")));
                }
                break;
            case "nodesgui":
                // 聊天按钮入口：打开节点管理 GUI
                if (s == null) {
                    s = new SetupSession();
                    s.step = 4;
                    sessions.put(player.getUniqueId(), s);
                }
                if (s.step != 4) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                showStepNodesGui(player, s);
                break;
            case "confirm-nodes":
                // 聊天按钮入口：完成节点设置
                if (s.step != 4) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                HandlerList.unregisterAll(this);
                player.closeInventory();
                int nodeCount = gameManager(player).getNodeSystem().getTotalCount();
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.nodes-done", String.valueOf(nodeCount))));
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "rescan":
                // 已废弃：旧的扫描命令，现在引导用户使用新的手动注册方式
                player.sendMessage(lang.getFor(player, "setup.rescan-deprecated"));
                break;
            case "spawngui":
                // 聊天按钮入口：打开生成点管理 GUI（可随时重开，入口不再丢失）
                // listspawns 等部署场景也会路由到此处——无进行中 session 时临时创建一个仅用于 GUI 的会话
                if (s == null) {
                    s = new SetupSession();
                    s.step = 3;
                    sessions.put(player.getUniqueId(), s);
                }
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                showStepSpawnsGui(player, s);
                break;
            case "overview":
                // F-9：聊天按钮入口：打开生成点总览 GUI（第 3 步枢纽，内含唯一 [完成]）
                // 与 spawngui 同样容错：无 session 时临时创建仅用于 GUI 的会话
                if (s == null) {
                    s = new SetupSession();
                    s.step = 3;
                    sessions.put(player.getUniqueId(), s);
                }
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                showStepSpawnsOverview(player);
                break;
            case "show-particles":
                // GUI 按钮入口：显示所有生成点的粒子效果（限步骤3使用）
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                deploy(player).showSpawnPointParticles(player, lang);
                break;
            case "spawns-done":
                // 聊天按钮入口：完成生成点设置（F-9 起收敛到 tryFinishSpawnsStep，与总览 [完成] 同一路径；
                // README 已标注该命令仅向后兼容保留）
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                tryFinishSpawnsStep(player, s);
                break;
            case "addmobspawn":
                // 聊天按钮入口：添加玩家当前位置为怪物刷怪点（限步骤3使用）
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                deploy(player).addMobSpawnPoint(player.getLocation());
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.mobspawn-added",
                                String.valueOf(deploy(player).getMobSpawnPoints().size()))));
                sendSpawnButtons(player);
                autoSaveSilent(player);
                break;
            case "mobspawngui":
                // 聊天按钮入口：打开怪物刷怪点管理 GUI（限步骤3使用）
                if (s.step != 3) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                showStepMobSpawnsGui(player, s);
                break;
            case "villagers-done":
                // 聊天按钮入口：完成村民设置，进入商店步骤（强制至少 1 个村民）
                if (s.step != 5) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                if (deploy(player).getVillagers().isEmpty()) {
                    player.sendMessage(lang.getFor(player, "setup.villagers-required"));
                    sendVillagerButtons(player);
                    break;
                }
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "addvillager":
                // 聊天按钮入口：登记当前位置为村民（限步骤 5 使用）
                if (s.step != 5) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                deploy(player).addVillager(player.getLocation());
                // 在该位置生成/刷新预览村民（编号 = 列表下标 + 1）
                syncVillagerPreviews(player);
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
                autoSaveSilent(player);
                player.sendMessage(lang.getFor(player, "setup.villager-added",
                        String.valueOf(deploy(player).getVillagers().size())));
                refreshVillagerGuiOrResend(player, s);
                break;
            case "villagersgui":
                // 打开村民管理 GUI（限步骤 5 使用）
                if (s.step != 5) {
                    player.sendMessage(lang.getFor(player, "setup.unknown"));
                    break;
                }
                showVillagersGui(player, s);
                break;
            case "preview":
                // 强制项校验（与预览 GUI 内的 [确认保存] 一致）
                if (deploy(player).getSpawnPoints().isEmpty()) {
                    player.sendMessage(lang.getFor(player, "setup.spawns-required"));
                    break;
                }
                if (deploy(player).getMobSpawnPoints().isEmpty()) {
                    player.sendMessage(lang.getFor(player, "setup.mobspawns-required"));
                    break;
                }
                if (deploy(player).getVillagers().isEmpty()) {
                    player.sendMessage(lang.getFor(player, "setup.villagers-required"));
                    break;
                }
                if (deploy(player).getShopNpcLocation() == null) {
                    player.sendMessage(lang.getFor(player, "setup.shop-required"));
                    break;
                }
                if (s.step != 7) {
                    s.step = 7;
                    autoSaveSilent(player);
                }
                // 进入预览 = 离开第 5 步：清掉村民预览实体（此命令直连 showStepPreview，不经 showStep）
                clearVillagerPreviews(player);
                showStepPreview(player, s);
                break;
            case "saveexit":
                // 保存进度并返回大厅（向导进度已按步骤持久化，稍后可在竞技场世界内 /hilltop setup 继续）
                autoSave(player);
                // 会话结束：清掉第 5 步的村民预览实体（玩家此刻还在竞技场世界，可正常解析世界）
                clearVillagerPreviews(player);
                if (player.getOpenInventory().getTitle() != null) {
                    player.closeInventory();
                }
                HandlerList.unregisterAll(this);
                // 必须走「离开竞技场」流程：否则玩家人在大厅、插件仍认为他在竞技场中，
                // 后续 /hilltop join 会被 already-in-arena 拦住
                if (plugin.getMultiArenaManager().isInArena(player)) {
                    plugin.getMultiArenaManager().leaveArena(player);
                } else {
                    plugin.getMultiArenaManager().sendToLobby(player);
                }
                player.sendMessage(lang.getFor(player, "setup.saved-exit"));
                // F-3：提前说明「部署走完后如何让玩家进场」，避免服主卡在最后一步不知要 enable
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.enable-hint")));
                break;
            case "goto":
                String[] args = sub.split(" ", 2);
                if (args.length < 2) {
                    player.sendMessage(lang.getFor(player, "setup.goto-usage"));
                    return true;
                }
                try {
                    int targetStep = Integer.parseInt(args[1]);
                    if (targetStep < 1 || targetStep > TOTAL_STEPS) {
                        player.sendMessage(lang.getFor(player, "setup.goto-invalid-step",
                                String.valueOf(TOTAL_STEPS)));
                        return true;
                    }
                    s.step = targetStep;
                    autoSaveSilent(player);
                    // 预览村民只属于第 5 步：跳进第 5 步就重建，跳到别处就清掉（goto 直连各步发送器、不经 showStep）
                    if (targetStep == 5) {
                        syncVillagerPreviews(player);
                    } else {
                        clearVillagerPreviews(player);
                    }
                    // 根据目标步骤发送相应消息
                    switch (targetStep) {
                        case 1: sendStepWorldMessage(player, s); break;
                        case 2: sendStepCoreMessage(player, s); break;
                        case 3: sendSpawnButtons(player); break;
                        case 4: sendNodeButtons(player); break;
                        case 5: sendVillagerButtons(player); break;
                        case 6: sendStepShopMessage(player, s); break;
                        case 7: showStepPreview(player, s); break;
                        case 8: player.sendMessage(lang.getFor(player, "setup.already-done")); break;
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(lang.getFor(player, "setup.goto-invalid-number"));
                }
                break;
            default:
                player.sendMessage(lang.getFor(player, "setup.unknown"));
        }
        return true;
    }

    private void autoSave(Player player) {
        gameManager(player).getConfigManager().saveToFile();
        // Fix 4: persist current step so it survives disconnect
        SetupSession s = sessions.get(player.getUniqueId());
        if (s != null) {
            plugin.getConfig().set("wizard.step." + player.getUniqueId(), s.step);
            plugin.saveConfig();
        }
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(lang.getFor(player, "setup.saved")));
    }

    /** 静默保存：与 autoSave 相同的持久化逻辑，但不发送 setup.saved 提示（高频操作防刷屏） */
    private void autoSaveSilent(Player player) {
        gameManager(player).getConfigManager().saveToFile();
        SetupSession s = sessions.get(player.getUniqueId());
        if (s != null) {
            plugin.getConfig().set("wizard.step." + player.getUniqueId(), s.step);
            plugin.saveConfig();
        }
    }

    // === GUI Click Handler ===
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        String overviewTitle = lang.getFor(player, "setup.gui-spawns-overview-title");
        String spawnsTitle = lang.getFor(player, "setup.gui-spawns-title");
        String mobSpawnsTitle = lang.getFor(player, "setup.gui-mobspawns-title");
        String nodesTitle = lang.getFor(player, "setup.gui-nodes-title");
        String villagersTitle = lang.getFor(player, "setup.gui-villagers-title");
        String previewTitle = lang.getFor(player, "setup.gui-preview-title");

        // 只处理 setup 向导的 GUI
        if (!title.equals(overviewTitle) && !title.equals(spawnsTitle) && !title.equals(mobSpawnsTitle)
                && !title.equals(nodesTitle) && !title.equals(villagersTitle) && !title.equals(previewTitle)) return;

        // 防御：向导 GUI 内一律禁止移动物品（即使 session 意外丢失）
        event.setCancelled(true);

        SetupSession s = sessions.get(player.getUniqueId());
        if (s == null) return;

        if (title.equals(overviewTitle)) {
            handleSpawnsOverviewClick(player, s, event);
        } else if (title.equals(spawnsTitle)) {
            handleSpawnGuiClick(player, s, event);
        } else if (title.equals(mobSpawnsTitle)) {
            handleMobSpawnGuiClick(player, s, event);
        } else if (title.equals(nodesTitle)) {
            handleNodesGuiClick(player, s, event);
        } else if (title.equals(villagersTitle)) {
            handleVillagersGuiClick(player, s, event);
        } else if (title.equals(previewTitle)) {
            handlePreviewGuiClick(player, s, event);
        }
    }

    /**
     * 生成点总览 GUI 点击处理（F-9 枢纽）。
     *
     * <p>子界面切换必须延迟 1 tick（点击处理器内同步 openInventory 会造成视图状态不一致）：
     * 先用 build* 重建目标界面，再 {@code runTask} 打开；返回总览时重建保证计数新鲜。</p>
     */
    private void handleSpawnsOverviewClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-noop":
                break;
            case "setup-open-spawns-gui": {
                // 进入子管理：重建生成点界面后延迟打开（点击处理器内不可同步 openInventory）
                Inventory target = buildSpawnGui(player, s);
                Bukkit.getScheduler().runTask(plugin, () -> openWizardGui(player, target));
                break;
            }
            case "setup-open-mobspawns-gui": {
                Inventory target = buildMobSpawnGui(player, s);
                Bukkit.getScheduler().runTask(plugin, () -> openWizardGui(player, target));
                break;
            }
            case "setup-overview-done":
                // F-9：全流程唯一 [完成] —— 与旧双完成按钮/命令走同一收口（两套各 ≥1 才推进）
                tryFinishSpawnsStep(player, s);
                break;
            default:
                break;
        }
    }

    /** 村民管理 GUI 点击处理 */
    private void handleVillagersGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "villager-prev-page":
                if (s.villagerPage > 0) {
                    s.villagerPage--;
                    refreshVillagersGui(event.getInventory(), player, s.villagerPage);
                }
                break;
            case "villager-next-page": {
                int maxPage = deploy(player).getVillagers().isEmpty() ? 0
                        : (deploy(player).getVillagers().size() - 1) / SPAWNS_PER_PAGE;
                if (s.villagerPage < maxPage) {
                    s.villagerPage++;
                    refreshVillagersGui(event.getInventory(), player, s.villagerPage);
                }
                break;
            }
            case "villager-show-particles":
                // F-7：村民预览（黄色光柱；空列表提示由预览方法内部统一发送）
                player.closeInventory();
                deploy(player).showVillagerParticles(player, lang);
                refreshVillagerGuiOrResend(player, s);
                break;
            case "setup-add-villager-item":
                // GUI 内添加：音效 + 粒子 + 刷新
                deploy(player).addVillager(player.getLocation());
                syncVillagerPreviews(player);
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
                refreshVillagersGui(event.getInventory(), player, s.villagerPage);
                autoSaveSilent(player);
                break;
            case "setup-villagers-done":
                if (deploy(player).getVillagers().isEmpty()) {
                    player.sendMessage(lang.getFor(player, "setup.villagers-required"));
                    break;
                }
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            default:
                // villager-<index>：点击删除
                if (tag.startsWith("villager-")) {
                    try {
                        int index = Integer.parseInt(tag.substring("villager-".length()));
                        if (deploy(player).removeVillager(index)) {
                            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
                            s.villagerPage = 0;
                            // 删除该村民后同步预览：移除对应实体，后续村民序号整体前移
                            syncVillagerPreviews(player);
                            refreshVillagersGui(event.getInventory(), player, s.villagerPage);
                            autoSaveSilent(player);
                        }
                    } catch (NumberFormatException ignored) {
                        // 非法索引，忽略
                    }
                }
                break;
        }
    }

    private void handleSpawnGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-noop":
                break;
            case "setup-prev-page":
                if (s.spawnPage > 0) {
                    s.spawnPage--;
                    refreshSpawnGui(event.getInventory(), player, s.spawnPage);
                }
                break;
            case "setup-next-page":
                int maxPage = deploy(player).getSpawnPoints().isEmpty() ? 0
                        : (deploy(player).getSpawnPoints().size() - 1) / SPAWNS_PER_PAGE;
                if (s.spawnPage < maxPage) {
                    s.spawnPage++;
                    refreshSpawnGui(event.getInventory(), player, s.spawnPage);
                }
                break;
            case "setup-add-spawn":
                // GUI 内添加：音效+粒子+刷新+重发按钮行（让按钮永远在聊天栏底部，方便关闭GUI后继续添加）
                deploy(player).addSpawnPoint(player.getLocation());
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                refreshSpawnGui(event.getInventory(), player, s.spawnPage);
                autoSaveSilent(player);
                // 重新发送按钮行，让管理员关闭GUI走到下一个点时能快速添加
                sendSpawnButtons(player);
                break;
            case "setup-show-particles":
                player.closeInventory();
                // 提示（含空列表提示）由预览方法统一发送，修复「无点位也报已启动」的双重提示
                deploy(player).showSpawnPointParticles(player, lang);
                // 重新发送按钮行，方便管理员关闭GUI后继续操作
                sendSpawnButtons(player);
                break;
            case "setup-spawns-done":
                // 旧版 GUI 遗留 tag（极少数场景：/reload 时玩家还开着旧版界面）——行为保持不变
                // （校验后完成第 3 步），新流程统一从总览 [完成] 收口
                tryFinishSpawnsStep(player, s);
                break;
            case "setup-back-overview": {
                // F-9：返回生成点总览（子管理界面已不再自带完成按钮）
                Inventory target = buildSpawnsOverview(player);
                Bukkit.getScheduler().runTask(plugin, () -> openWizardGui(player, target));
                break;
            }
            default:
                if (tag.startsWith("setup-spawn-")) {
                    int idx = Integer.parseInt(tag.substring("setup-spawn-".length()));
                    deploy(player).removeSpawnPoint(idx);
                    // If removing caused current page to become empty, go back one page
                    int totalAfter = deploy(player).getSpawnPoints().size();
                    int maxPageAfter = totalAfter == 0 ? 0 : (totalAfter - 1) / SPAWNS_PER_PAGE;
                    if (s.spawnPage > maxPageAfter) s.spawnPage = maxPageAfter;
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.spawn-removed", String.valueOf(idx))));
                    refreshSpawnGui(event.getInventory(), player, s.spawnPage);
                    autoSave(player);
                    sendSpawnButtons(player);
                }
        }
    }

    /**
     * Mob spawn GUI click handler (STORY-1.2: mirrors handleSpawnGuiClick, tags use mob- prefix).
     */
    private void handleMobSpawnGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-noop":
                break;
            case "setup-mob-prev-page":
                if (s.mobSpawnPage > 0) {
                    s.mobSpawnPage--;
                    refreshMobSpawnGui(event.getInventory(), player, s.mobSpawnPage);
                }
                break;
            case "setup-mob-next-page":
                int maxPage = deploy(player).getMobSpawnPoints().isEmpty() ? 0
                        : (deploy(player).getMobSpawnPoints().size() - 1) / SPAWNS_PER_PAGE;
                if (s.mobSpawnPage < maxPage) {
                    s.mobSpawnPage++;
                    refreshMobSpawnGui(event.getInventory(), player, s.mobSpawnPage);
                }
                break;
            case "setup-add-mobspawn":
                deploy(player).addMobSpawnPoint(player.getLocation());
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                refreshMobSpawnGui(event.getInventory(), player, s.mobSpawnPage);
                autoSaveSilent(player);
                sendSpawnButtons(player);
                break;
            case "setup-mob-show-particles":
                player.closeInventory();
                // F-1 修复：原先误调 showSpawnPointParticles（发到玩家出生点）；
                // 现在走刷怪点专用方法（数据源 mob-spawn-points，空列表有独立提示）
                deploy(player).showMobSpawnPointParticles(player, lang);
                sendSpawnButtons(player);
                break;
            case "setup-mobspawns-done":
                // 旧版 GUI 遗留 tag（极少数场景：/reload 时玩家还开着旧版界面）——行为保持不变
                // （校验后完成第 3 步），新流程统一从总览 [完成] 收口
                tryFinishSpawnsStep(player, s);
                break;
            case "setup-mob-back-overview": {
                // F-9：返回生成点总览（子管理界面已不再自带完成按钮）
                Inventory target = buildSpawnsOverview(player);
                Bukkit.getScheduler().runTask(plugin, () -> openWizardGui(player, target));
                break;
            }
            default:
                if (tag.startsWith("setup-mobspawn-")) {
                    int idx = Integer.parseInt(tag.substring("setup-mobspawn-".length()));
                    deploy(player).removeMobSpawnPoint(idx);
                    int totalAfter = deploy(player).getMobSpawnPoints().size();
                    int maxPageAfter = totalAfter == 0 ? 0 : (totalAfter - 1) / SPAWNS_PER_PAGE;
                    if (s.mobSpawnPage > maxPageAfter) s.mobSpawnPage = maxPageAfter;
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.mobspawn-removed", String.valueOf(idx))));
                    refreshMobSpawnGui(event.getInventory(), player, s.mobSpawnPage);
                    autoSave(player);
                    sendSpawnButtons(player);
                }
        }
    }

    private void handleNodesGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-noop":
                break;
            case "setup-add-node-item":
                // GUI 内添加节点：检查脚下方块并注册
                int addResult = gameManager(player).getNodeSystem().addNode(player.getLocation());
                if (addResult == 0) {
                    player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                    refreshNodesGui(event.getInventory(), player, s.nodePage);
                    autoSaveSilent(player);
                    // 重新发送按钮行
                    sendNodeButtons(player);
                } else if (addResult == 1) {
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.node-not-valid-block")));
                } else if (addResult == 2) {
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.node-already-exists")));
                }
                break;
            case "node-show-particles":
                player.closeInventory();
                // 显示节点粒子（空列表提示由预览方法内部统一发送，不再外层补发「已启动」）
                List<Location> nodeLocations = gameManager(player).getNodeSystem().getAllNodeLocations();
                deploy(player).showNodeParticles(player, nodeLocations, lang);
                // 重新发送按钮行，与生成点/刷怪点 GUI 的预览行为对齐
                sendNodeButtons(player);
                break;
            case "setup-nodes-done":
                HandlerList.unregisterAll(this);
                player.closeInventory();
                int nodeCount = gameManager(player).getNodeSystem().getTotalCount();
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.nodes-done", String.valueOf(nodeCount))));
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "node-prev-page":
                if (s.nodePage > 0) {
                    s.nodePage--;
                    refreshNodesGui(event.getInventory(), player, s.nodePage);
                }
                break;
            case "node-next-page":
                List<Location> allNodes = gameManager(player).getNodeSystem().getAllNodeLocations();
                int maxNodePage = allNodes.isEmpty() ? 0 : (allNodes.size() - 1) / 45;
                if (s.nodePage < maxNodePage) {
                    s.nodePage++;
                    refreshNodesGui(event.getInventory(), player, s.nodePage);
                }
                break;
            default:
                if (tag.startsWith("node-")) {
                    // 删除节点（格式: node-0, node-1, ...）
                    // 需要排除 node-show-particles 和 node-prev-page/node-next-page
                    if (!tag.equals("node-show-particles") && !tag.equals("node-prev-page") && !tag.equals("node-next-page")) {
                        try {
                            int nodeIdx = Integer.parseInt(tag.substring("node-".length()));
                            List<Location> nodeList = gameManager(player).getNodeSystem().getAllNodeLocations();
                            if (nodeIdx >= 0 && nodeIdx < nodeList.size()) {
                                Location toRemove = nodeList.get(nodeIdx);
                                gameManager(player).getNodeSystem().removeNode(toRemove);
                                player.sendMessage(LegacyComponentSerializer.legacySection()
                                        .deserialize(lang.getFor(player, "setup.node-deleted", String.valueOf(nodeIdx + 1))));
                                // 如果删除后当前页为空，返回上一页
                                int totalAfterNode = nodeList.size() - 1;
                                int maxNodePageAfter = totalAfterNode == 0 ? 0 : (totalAfterNode - 1) / 45;
                                if (s.nodePage > maxNodePageAfter) s.nodePage = maxNodePageAfter;
                                refreshNodesGui(event.getInventory(), player, s.nodePage);
                                autoSave(player);
                                // 重新发送按钮行，方便用户修改位置后继续添加
                                sendNodeButtons(player);
                            }
                        } catch (NumberFormatException ignored) {
                            // 不是数字索引，忽略
                        }
                    }
                }
        }
    }

    private void handlePreviewGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-confirm":
                // 强制项校验：生成点 / 村民 / 商店都是必须的
                if (deploy(player).getSpawnPoints().isEmpty()) {
                    player.closeInventory();
                    player.sendMessage(lang.getFor(player, "setup.spawns-required"));
                    s.step = 3;
                    showStep(player, s);
                    break;
                }
                // STORY-1.2：怪物刷怪点同样强制（新部署的场必须两套都配）
                if (deploy(player).getMobSpawnPoints().isEmpty()) {
                    player.closeInventory();
                    player.sendMessage(lang.getFor(player, "setup.mobspawns-required"));
                    s.step = 3;
                    showStep(player, s);
                    break;
                }
                if (deploy(player).getVillagers().isEmpty()) {
                    player.closeInventory();
                    player.sendMessage(lang.getFor(player, "setup.villagers-required"));
                    s.step = 5;
                    showStep(player, s);
                    break;
                }
                if (deploy(player).getShopNpcLocation() == null) {
                    player.closeInventory();
                    player.sendMessage(lang.getFor(player, "setup.shop-required"));
                    s.step = 6;
                    showStep(player, s);
                    break;
                }
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "setup-reset":
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.step = 1;
                showStep(player, s);
                break;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        // 会话结束：清掉该玩家第 5 步留下的村民预览实体。必须在移除 session 之前清理，
        // 否则按会话 arenaId 解析目标世界会退化成按「所在世界」解析（离服瞬间仍可用，但越早越稳）。
        clearVillagerPreviews(quitting);
        SetupSession s = sessions.remove(quitting.getUniqueId());
        if (s != null) {
            HandlerList.unregisterAll(this);
        }
    }

    // ESC 关闭 inventory 时 unregister，但不清除 session，允许玩家重新打开喵。
    // F-9 注：总览 ↔ 子管理界面互切时 OpenEvent 会先触发旧界面的本条 CloseEvent，
    // 但 openWizardGui 是「先打开、后重注册」，因此切换结束后监听器必然在位。
    @EventHandler
    public void onSetupInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        SetupSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        String title = event.getView().getTitle();
        String overviewTitle = lang.getFor(player, "setup.gui-spawns-overview-title");
        String spawnsTitle = lang.getFor(player, "setup.gui-spawns-title");
        String mobSpawnsTitle = lang.getFor(player, "setup.gui-mobspawns-title");
        if (title.equals(overviewTitle) || title.equals(spawnsTitle) || title.equals(mobSpawnsTitle)) {
            HandlerList.unregisterAll(this);
        }
    }

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // === Helpers ===
    private ItemStack makeItem(Material mat, String name, String tag) {
        return makeItem(mat, name, null, tag);
    }

    /** 创建 GUI 物品：displayName + 可选 lore 说明 + PDC 内部 tag（tag 不显示给玩家） */
    private ItemStack makeItem(Material mat, String name, String lore, String tag) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (lore != null) {
            meta.setLore(Collections.singletonList(ChatColor.translateAlternateColorCodes('&', lore)));
        }
        if (tag != null) {
            meta.getPersistentDataContainer().set(TAG_KEY, PersistentDataType.STRING, tag);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        item.setItemMeta(meta);
        return item;
    }

    private String getTag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TAG_KEY, PersistentDataType.STRING);
    }

    private void sendClickableMsg(Player player, String msg, String command) {
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(msg)
                .clickEvent(ClickEvent.runCommand(command)));
    }

    /**
     * 发送一行多个可点击按钮：每个按钮独立 ClickEvent.runCommand，段之间用空格分隔。
     * 点击立即执行命令（与 confirm 按钮模式一致），适合聊天栏操作入口。
     */
    private void sendButtonLine(Player player, List<Button> buttons) {
        Component line = Component.empty();
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            line = line.append(LegacyComponentSerializer.legacySection()
                    .deserialize(b.label)
                    .clickEvent(ClickEvent.runCommand(b.command)));
            if (i < buttons.size() - 1) {
                line = line.append(Component.text(" "));
            }
        }
        player.sendMessage(line);
    }

    /** 可点击按钮数据：显示文本 + 点击执行的命令 */
    private record Button(String label, String command) {
    }

    private String fmt(double d) { return String.format("%.1f", d); }

    public static class SetupSession {
        int step = 1;
        /** 本次部署的目标竞技场 ID（按玩家所在世界解析，见 {@link SetupWizard#start(Player)}） */
        String arenaId;
        int spawnPage = 0;     // Fix 3: current page in spawn point GUI
        int mobSpawnPage = 0;  // STORY-1.2: current page in mob spawn GUI
        int nodePage = 0;      // 节点管理 GUI 当前页码
        int villagerPage = 0;  // 村民管理 GUI 当前页码
        void advance() { step++; }
    }
}
