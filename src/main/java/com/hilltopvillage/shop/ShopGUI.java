package com.hilltopvillage.shop;

import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 村民商店 GUI（InventoryHolder 模式）。
 * 点击识别不依赖 PDC / 点击 map：ShopListener 通过 getHolder() instanceof ShopGUI
 * + slot 反查 bySlot 拿到商品。每次打开都新建实例，无需注册/注销监听器。
 */
public class ShopGUI implements InventoryHolder {

    private final Inventory inventory;
    private final Player player;
    private final ShopManager shopManager;
    private final LanguageManager lang;
    /** 槽位号 -> 商品（ShopListener 购买时反查） */
    private final Map<Integer, ShopItem> bySlot = new HashMap<>();

    public ShopGUI(Player player, ShopManager shopManager, LanguageManager lang) {
        this.player = player;
        this.shopManager = shopManager;
        this.lang = lang;

        ShopConfig cfg = shopManager.getShopConfig();
        // 标题：shop.yml 直配优先（支持颜色码），否则走 i18n 的 shop.gui.title
        String title = (cfg.getGuiTitle() != null && !cfg.getGuiTitle().isEmpty())
                ? ChatColor.translateAlternateColorCodes('&', cfg.getGuiTitle())
                : lang.getFor(player, "shop.gui.title");

        this.inventory = Bukkit.createInventory(this, cfg.getGuiSize(), title);

        for (ShopItem item : cfg.getItems()) {
            inventory.setItem(item.getSlot(), buildDisplayItem(item));
            bySlot.put(item.getSlot(), item);
        }
        fillEmpty();
    }

    /** 打开商店 GUI */
    public void open() {
        player.openInventory(inventory);
    }

    /** 槽位反查商品（占位符/空槽返回 null） */
    public ShopItem getItemAt(int slot) {
        return bySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 构建商品展示图标：icon 材质 + 名称 + lore + 价格行。
     * 名称：shop.yml 直配优先，否则回退 i18n 的 shop.item.<id>.name，均无则用材质名。
     */
    private ItemStack buildDisplayItem(ShopItem item) {
        ItemStack icon = new ItemStack(item.getIcon());
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        meta.setDisplayName(resolveDisplayName(item));
        meta.setLore(buildLore(item));
        icon.setItemMeta(meta);
        return icon;
    }

    private String resolveDisplayName(ShopItem item) {
        if (item.getName() != null && !item.getName().isEmpty()) {
            return ChatColor.translateAlternateColorCodes('&', item.getName());
        }
        // i18n 回退：shop.item.<id>.name（LanguageManager 取不到时返回 key 本身）
        String key = "shop.item." + item.getId() + ".name";
        String msg = lang.getFor(player, key);
        if (!msg.equals(key)) {
            return msg;
        }
        return prettyMaterialName(item.getIcon());
    }

    /** lore：shop.yml 直配行（translate）+ 追加价格行 */
    private List<String> buildLore(ShopItem item) {
        List<String> lines = new ArrayList<>();
        if (item.getLore() != null) {
            for (String line : item.getLore()) {
                lines.add(ChatColor.translateAlternateColorCodes('&', line));
            }
        }
        lines.add(lang.getFor(player, "shop.price-line", String.valueOf(item.getPrice())));
        return lines;
    }

    /** 空位填充灰色玻璃板占位（不可点击购买，getItemAt 返回 null） */
    private void fillEmpty() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack current = inventory.getItem(i);
            if (current == null || current.getType() == Material.AIR) {
                inventory.setItem(i, filler);
            }
        }
    }

    private String prettyMaterialName(Material material) {
        String name = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
