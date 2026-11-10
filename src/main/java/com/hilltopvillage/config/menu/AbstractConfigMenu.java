package com.hilltopvillage.config.menu;

import com.hilltopvillage.util.LanguageManager;
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
    protected final LanguageManager lang;

    public AbstractConfigMenu(Player admin, String title, int size, Plugin plugin, LanguageManager lang) {
        this.admin = admin;
        this.title = title;
        this.size = size;
        this.plugin = plugin;
        this.lang = lang;
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
        int slot = size - 9; // 最后一行左端（45格=slot45，54格=slot45）
        setClickableItem(slot, Material.RED_STAINED_GLASS_PANE,
                lang.getFor(admin, "config.menu.common.back"),
                onBack,
                lang.getFor(admin, "config.menu.common.back-lore"));
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

    protected static String formatValue(String label, double value) {
        return ChatColor.GRAY + label + ": " + ChatColor.AQUA + String.format("%.1f", value);
    }

    protected static String formatValue(String label, int value) {
        return ChatColor.GRAY + label + ": " + ChatColor.AQUA + value;
    }

    /**
     * 浮点步进比较的容差（slice-08-logic-008）。
     *
     * <p>{@code 0.1 + 0.05 × 37 == 1.950000000000001}，下一次 {@code +0.05} 得
     * {@code 2.000000000000001 > 2.0}，被 {@code val <= max} 判为越界 —— 界面上声明到 2.0
     * 的槽位实际永远点不到，而 {@link #formatValue(String, double)} 又把 1.95 显示成 "2.0"，
     * 现场观感就是「+ 按钮坏了」。容差按步长相对取值：只吸收累加误差，不放宽真实越界
     * （0.05 步长的容差为 5e-8，远小于一次步进）。</p>
     */
    protected static double stepTolerance(double step) {
        return Math.max(1e-9, Math.abs(step) * 1e-6);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!event.getWhoClicked().equals(admin)) return;
        // 先按 top inventory 取消：点击可能落在玩家自己的背包（shift-click），此时
        // getClickedInventory() 不是本菜单，若不在这里取消，物品会被塞进 GUI。
        if (!event.getView().getTopInventory().equals(inventory)) return;

        event.setCancelled(true);

        // 取消之后才分发动作：动作只属于本菜单的槽位，点击玩家背包时 slot 会撞上
        // 同号槽位，必须用 getClickedInventory() 把两者区分开。
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(inventory)) return;

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