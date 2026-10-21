package com.hilltopvillage.gui;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.IArena;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arena selection GUI - displays all available arenas in a chest interface.
 * Players can click on an arena icon to join that arena.
 *
 * Icon sword material indicates arena status (see getStatusText for colors):
 * - DIAMOND_SWORD = WAITING（等待中，绿字，可加入）
 * - IRON_SWORD = RUNNING / WAVE_INTERMISSION（对局中，红字，不可加入）
 * - STONE_SWORD = ENDING / VICTORY / DEFEAT（已结束/重置中，灰字）
 * - WOODEN_SWORD / BARRIER = 未启用 / 未部署 / 地图缺失（不可用）
 *
 * 点击识别采用 InventoryHolder 模式（不依赖标题文本），避免标题被语言/颜色影响。
 */
public class ArenaSelectionGUI implements Listener {

    /** 供 InventoryClickEvent 识别本 GUI（标题可被语言配置改变，不能作为识别依据） */
    public static final class ArenaSelectorHolder implements InventoryHolder {
        private Inventory inventory;

        /**
         * 渲染期的 slot → arenaId 映射。
         *
         * <p>点击时按本映射反查竞技场，而不是重取 `getActiveArenas()` 后按下标取——
         * 列表在 GUI 常开期间可能变动（场被禁用 / 新增 / 加载完成），按下标重取会点到错场的条目。</p>
         */
        private final Map<Integer, String> slotArenaIds = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HilltopVillagePlugin plugin;
    private final MultiArenaManager arenaManager;
    private final LanguageManager languageManager;

    public ArenaSelectionGUI(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.arenaManager = plugin.getMultiArenaManager();
        this.languageManager = plugin.getLanguageManager();

        // Register this as a listener
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Open the arena selection GUI for a player
     */
    public void openGUI(Player player) {
        ArenaSelectorHolder holder = new ArenaSelectorHolder();
        String title = languageManager.getFor(player, "gui-arena-select-title");
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        List<IArena> arenas = new ArrayList<>(arenaManager.getActiveArenas());

        // Place arena icons (up to 45 arenas, leaving bottom row for navigation)
        // 同时把 slot → arenaId 写进 holder：点击时按映射取场，避免 GUI 常开期间列表变动点错场
        int slot = 0;
        for (IArena arena : arenas) {
            if (slot >= 45) break; // Max 45 arenas (5 rows)

            ItemStack icon = createArenaIcon(arena, player);
            inv.setItem(slot, icon);
            holder.slotArenaIds.put(slot, arena.getArenaId());
            slot++;
        }

        // 没有可用竞技场时给出明确提示，避免玩家看到一个空箱子
        if (arenas.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta meta = empty.getItemMeta();
            meta.setDisplayName(languageManager.getFor(player, "gui-no-arenas"));
            meta.setLore(List.of(languageManager.getFor(player, "gui-no-arenas-lore")));
            empty.setItemMeta(meta);
            inv.setItem(22, empty);
        }

        // Add info item in bottom-right corner
        ItemStack infoIcon = createInfoIcon(player);
        inv.setItem(53, infoIcon);

        player.openInventory(inv);
    }

    /**
     * Create an icon for an arena based on its current state
     */
    private ItemStack createArenaIcon(IArena arena, Player player) {
        GameState state = arena.getState();
        // fix6 Y3-M-06：borderMaterial 死变量已删（曾写入 YELLOW_WOOL 等 5 值但从未被读取——
        // 图例一度承诺四色边框，实际状态区分靠剑的材质 + 状态 lore；图例文案同步改述）。
        Material iconMaterial;

        // Determine icon material based on state
        switch (state) {
            case WAITING:
                iconMaterial = Material.DIAMOND_SWORD;
                break;
            case RUNNING:
            case WAVE_INTERMISSION:
                iconMaterial = Material.IRON_SWORD;
                break;
            case ENDING:
            case VICTORY:
            case DEFEAT:
                iconMaterial = Material.STONE_SWORD;
                break;
            default:
                iconMaterial = Material.WOODEN_SWORD;
                break;
        }

        // 未启用 / 地图缺失 / 未部署：统一显示为灰色的不可用状态
        if (!arena.isEnabled() || !arena.isWorldAvailable() || !arena.isDeployed()) {
            iconMaterial = arena.isWorldAvailable() ? Material.WOODEN_SWORD : Material.BARRIER;
        }

        ItemStack icon = new ItemStack(iconMaterial);
        ItemMeta meta = icon.getItemMeta();

        // Set display name
        meta.setDisplayName(arena.getDisplayName());

        // Build lore
        List<String> lore = new ArrayList<>();
        String statusText = getStatusText(state, arena, player);
        // 不可用的竞技场统一用灰色，避免"未部署"却显示绿色"等待中"造成误解
        boolean playable = arena.isEnabled() && arena.isWorldAvailable() && arena.isDeployed();
        lore.add(languageManager.getFor(player, "gui-arena-lore-status",
                (playable ? getStatusColor(state) : "§7") + statusText));
        lore.add(languageManager.getFor(player, "gui-arena-lore-players",
            String.valueOf(arena.getPlayerCount()), String.valueOf(arena.getMaxPlayers())));
        lore.add(languageManager.getFor(player, "gui-arena-lore-map", arena.getWorldName()));
        lore.add("");

        // Add action hint
        if (canJoin(arena, player)) {
            lore.add(languageManager.getFor(player, "gui-arena-lore-click-join"));
        } else {
            String reason;
            if (!arena.isEnabled()) {
                reason = languageManager.getFor(player, "arena-disabled");
            } else if (!arena.isWorldAvailable()) {
                reason = languageManager.getFor(player, "arena-map-missing");
            } else if (!arena.isDeployed()) {
                reason = languageManager.getFor(player, "arena-not-deployed", arena.getArenaId());
            } else if (state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION) {
                reason = languageManager.getFor(player, "arena-started");
            } else if (arena.getPlayerCount() >= arena.getMaxPlayers()) {
                reason = languageManager.getFor(player, "arena-full");
            } else {
                reason = languageManager.getFor(player, "arena-status-ending");
            }
            lore.add(languageManager.getFor(player, "gui-arena-lore-cannot-join", reason));
            // 管理员提示：未部署的竞技场仍需进世界执行部署向导
            if (player.hasPermission("hilltopvillage.admin") && arena.isWorldAvailable() && !arena.isDeployed()) {
                lore.add(languageManager.getFor(player, "gui-arena-admin-hint"));
            }
        }

        meta.setLore(lore);
        icon.setItemMeta(meta);

        return icon;
    }

    /**
     * Get status text for an arena
     */
    private String getStatusText(GameState state, IArena arena, Player player) {
        if (!arena.isEnabled()) return languageManager.getFor(player, "arena-disabled");
        if (!arena.isWorldAvailable()) return languageManager.getFor(player, "arena-status-map-missing");
        if (!arena.isDeployed()) return languageManager.getFor(player, "arena-status-undeployed");

        switch (state) {
            case WAITING: return languageManager.getFor(player, "arena-status-waiting");
            case STARTING: return languageManager.getFor(player, "arena-status-starting");
            case RUNNING: return languageManager.getFor(player, "arena-status-running");
            case WAVE_INTERMISSION: return languageManager.getFor(player, "arena-status-running");
            case ENDING: return languageManager.getFor(player, "arena-status-ending");
            case VICTORY: return languageManager.getFor(player, "state.victory");
            case DEFEAT: return languageManager.getFor(player, "state.defeat");
            default: return "Unknown";
        }
    }

    /**
     * Get color code for status
     */
    private String getStatusColor(GameState state) {
        switch (state) {
            case WAITING: return "§a";
            case STARTING: return "§e";
            case RUNNING:
            case WAVE_INTERMISSION: return "§c";
            case ENDING:
            case VICTORY:
            case DEFEAT: return "§7";
            default: return "§7";
        }
    }

    /**
     * Check if a player can join an arena
     */
    /**
     * 玩家能否加入该竞技场。
     *
     * <p>管理员对「世界已加载但尚未部署」的竞技场有豁免，便于进图执行部署向导核对位置；
     * 地图存档缺失（世界未加载）的竞技场任何人都不能进。</p>
     */
    private boolean canJoin(IArena arena, Player player) {
        if (!arena.isEnabled()) return false;
        if (!arena.isWorldAvailable()) return false;
        if (!arena.isDeployed() && !player.hasPermission("hilltopvillage.admin")) return false;
        if (arena.getPlayerCount() >= arena.getMaxPlayers()) return false;

        GameState state = arena.getState();
        return state == GameState.WAITING || state == GameState.STARTING;
    }

    /**
     * Create info icon in bottom-right corner
     */
    private ItemStack createInfoIcon(Player player) {
        ItemStack icon = new ItemStack(Material.BOOK);
        ItemMeta meta = icon.getItemMeta();

        meta.setDisplayName(languageManager.getFor(player, "gui-help-title"));

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(languageManager.getFor(player, "gui-help-lore-green"));
        lore.add(languageManager.getFor(player, "gui-help-lore-yellow"));
        lore.add(languageManager.getFor(player, "gui-help-lore-red"));
        lore.add(languageManager.getFor(player, "gui-help-lore-gray"));

        meta.setLore(lore);
        icon.setItemMeta(meta);

        return icon;
    }

    /**
     * Handle inventory click events
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // 通过 InventoryHolder 识别本 GUI（标题会随语言变化，不能作为识别依据）
        if (!(event.getInventory().getHolder() instanceof ArenaSelectorHolder holder)) return;

        // Cancel the click to prevent item movement
        event.setCancelled(true);

        // Check if player clicked
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // 只处理点本 GUI 本体：点击玩家背包时 getSlot() 是 convertSlot 后的槽位（快捷栏 0-8、
        // 主背包 9-35），与竞技场条目槽位 0-44 重叠，会经 slotArenaIds 命中而误触发 join/误报 join-failed
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) return;

        // Check if clicked item exists
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        // Get slot
        int slot = event.getSlot();

        // Info icon - do nothing
        if (slot == 53) return;

        // 按渲染期写入的 slot → arenaId 映射反查（不按下标重取列表：列表可能已变动，
        // 下标会指到别的场）。映射缺失 = 该 slot 本来就没有竞技场条目（空位）。
        String clickedArenaId = holder.slotArenaIds.get(slot);
        if (clickedArenaId == null) return;

        // 按 ID 取当前实例：场已消失（被禁用 / 退役）时提示并静默返回
        IArena arena = arenaManager.getArena(clickedArenaId);
        if (arena == null) {
            languageManager.send(player, "arena-not-found", clickedArenaId);
            return;
        }

        // Check if already in an arena
        IArena currentArena = arenaManager.getPlayerArena(player);
        if (currentArena != null) {
            languageManager.send(player, "already-in-arena");
            player.closeInventory();
            return;
        }

        // Try to join
        if (!canJoin(arena, player)) {
            languageManager.send(player, "join-failed", arena.getArenaId());
            return;
        }

        // Close GUI
        player.closeInventory();

        // Join arena
        arenaManager.joinArena(player, arena.getArenaId());
    }

    /** 阻止拖拽物品到本 GUI（防刷） */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ArenaSelectorHolder)) return;
        event.setCancelled(true);
    }
}
