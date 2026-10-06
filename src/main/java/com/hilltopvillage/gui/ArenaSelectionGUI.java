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
import java.util.List;

/**
 * Arena selection GUI - displays all available arenas in a chest interface.
 * Players can click on an arena icon to join that arena.
 *
 * Icon colors indicate arena status:
 * - GREEN wool border = WAITING (can join)
 * - YELLOW wool border = STARTING (can join)
 * - RED wool border = RUNNING (cannot join)
 * - GRAY wool border = DISABLED (cannot join)
 *
 * 点击识别采用 InventoryHolder 模式（不依赖标题文本），避免标题被语言/颜色影响。
 */
public class ArenaSelectionGUI implements Listener {

    /** 供 InventoryClickEvent 识别本 GUI（标题可被语言配置改变，不能作为识别依据） */
    public static final class ArenaSelectorHolder implements InventoryHolder {
        private Inventory inventory;

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
        int slot = 0;
        for (IArena arena : arenas) {
            if (slot >= 45) break; // Max 45 arenas (5 rows)

            ItemStack icon = createArenaIcon(arena, player);
            inv.setItem(slot, icon);
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
        Material iconMaterial;
        Material borderMaterial;

        // Determine icon and border based on state
        switch (state) {
            case WAITING:
                iconMaterial = Material.DIAMOND_SWORD;
                borderMaterial = Material.YELLOW_WOOL;
                break;
            case RUNNING:
            case WAVE_INTERMISSION:
                iconMaterial = Material.IRON_SWORD;
                borderMaterial = Material.RED_WOOL;
                break;
            case ENDING:
            case VICTORY:
            case DEFEAT:
                iconMaterial = Material.STONE_SWORD;
                borderMaterial = Material.GRAY_WOOL;
                break;
            default:
                iconMaterial = Material.WOODEN_SWORD;
                borderMaterial = Material.GRAY_WOOL;
                break;
        }

        // 未启用 / 地图缺失 / 未部署：统一显示为灰色的不可用状态
        if (!arena.isEnabled() || !arena.isWorldAvailable() || !arena.isDeployed()) {
            iconMaterial = arena.isWorldAvailable() ? Material.WOODEN_SWORD : Material.BARRIER;
            borderMaterial = Material.GRAY_WOOL;
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
                reason = languageManager.getFor(player, "arena-not-deployed");
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
        if (!(event.getInventory().getHolder() instanceof ArenaSelectorHolder)) return;

        // Cancel the click to prevent item movement
        event.setCancelled(true);

        // Check if player clicked
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // Check if clicked item exists
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        // Get slot
        int slot = event.getSlot();

        // Info icon - do nothing
        if (slot == 53) return;

        // Get clicked arena
        List<IArena> arenas = new ArrayList<>(arenaManager.getActiveArenas());
        if (slot >= arenas.size()) return;

        IArena arena = arenas.get(slot);

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
