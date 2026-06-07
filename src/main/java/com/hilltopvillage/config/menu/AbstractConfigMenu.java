package com.hilltopvillage.config.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.*;

public abstract class AbstractConfigMenu implements InventoryHolder, Listener {

    @FunctionalInterface
    public interface ClickAction {
        void onClick(ClickType clickType);
    }

    protected final Player admin;
    protected final Inventory inventory;
    protected final Map<Integer, ClickAction> clickActions;
    protected final String title;
    protected final int size;
    protected final Plugin plugin;

    public AbstractConfigMenu(Player admin, String title, int size, Plugin plugin) {
        this.admin = admin;
        this.title = title;
        this.size = size;
        this.plugin = plugin;
        this.clickActions = new HashMap<>();
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public void open() {
        buildMenu();
        admin.openInventory(inventory);
    }

    /**
     * 重建并重新打开菜单（延迟1tick执行，避免在InventoryClickEvent处理器内
     * 直接调用openInventory()导致的视图状态不一致问题）。
     */
    public void reopen() {
        buildMenu();
        Bukkit.getScheduler().runTask(plugin, () -> {
            admin.openInventory(inventory);
            HandlerList.unregisterAll(this);
            Bukkit.getPluginManager().registerEvents(this, plugin);
        });
    }

    protected abstract void buildMenu();

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getAdmin() {
        return admin;
    }

    protected void setItem(int slot, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    protected void setItemWithData(int slot, Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    protected void setClickableItem(int slot, Material material, String name, ClickAction action, String... lore) {
        setItem(slot, material, name, lore);
        clickActions.put(slot, action);
    }

    protected void setClickableItemWithData(int slot, Material material, String name, List<String> lore, ClickAction action) {
        setItemWithData(slot, material, name, lore);
        clickActions.put(slot, action);
    }

    protected void setBackButton(ClickAction onBack) {
        setClickableItem(size - 5, Material.RED_STAINED_GLASS_PANE,
                ChatColor.RED + "返回上一页",
                onBack,
                ChatColor.GRAY + "点击返回上级菜单");
    }

    protected void setCloseButton() {
        setClickableItem(size - 1, Material.BARRIER,
                ChatColor.RED + "关闭菜单",
                ct -> admin.closeInventory(),
                ChatColor.GRAY + "点击关闭配置菜单");
    }

    protected void fillBorders() {
        ItemStack border = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = border.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            border.setItemMeta(meta);
        }
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, border);
        }
    }

    protected void fillEmpty() {
        ItemStack empty = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = empty.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            empty.setItemMeta(meta);
        }
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null || inventory.getItem(i).getType() == Material.AIR) {
                inventory.setItem(i, empty);
            }
        }
    }

    protected static String formatValue(String label, String value) {
        return ChatColor.GRAY + label + ": " + ChatColor.AQUA + value;
    }

    protected static String formatValue(String label, double value) {
        return ChatColor.GRAY + label + ": " + ChatColor.AQUA + String.format("%.1f", value);
    }

    protected static String formatValue(String label, int value) {
        return ChatColor.GRAY + label + ": " + ChatColor.AQUA + value;
    }

    protected static List<String> lore(String... lines) {
        return Arrays.asList(lines);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!event.getWhoClicked().equals(admin)) return;
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(inventory)) return;

        event.setCancelled(true);
        int slot = event.getSlot();
        ClickAction action = clickActions.get(slot);
        if (action != null) {
            action.onClick(event.getClick());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (!event.getPlayer().equals(admin)) return;
        if (!event.getInventory().equals(inventory)) return;

        HandlerList.unregisterAll(this);
    }
}