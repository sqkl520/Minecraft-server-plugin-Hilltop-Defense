package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 村民商店配置加载器（shop.yml）。
 * 首次运行释放默认模板，之后每次调用 load() 重新解析为 ShopConfig 快照。
 */
public class ShopConfigLoader {

    /** 合法商品类型 */
    private static final Set<String> VALID_TYPES = new HashSet<>(
            Arrays.asList("HAMMER", "FIREBALL", "REPAIR_ITEM", "MATERIAL"));

    /** GUI 允许的尺寸（Bukkit 箱子行数：9/18/27/36/45/54） */
    private static final List<Integer> VALID_GUI_SIZES = Arrays.asList(9, 18, 27, 36, 45, 54);

    private final HilltopVillagePlugin plugin;

    public ShopConfigLoader(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载 shop.yml 为 ShopConfig。
     * 配置缺失/非法的条目跳过并 warning，不阻断整体加载。
     */
    public ShopConfig load() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // ---- 货币 ----
        Material currencyMaterial = Material.EMERALD;
        String currencyMatStr = yaml.getString("shop.currency.material");
        if (currencyMatStr != null && !currencyMatStr.isEmpty()) {
            Material parsed = Material.getMaterial(currencyMatStr);
            if (parsed != null) {
                currencyMaterial = parsed;
            } else {
                plugin.getLogger().warning("shop.yml: unknown currency material '"
                        + currencyMatStr + "', keeping default EMERALD");
            }
        }
        String currencyName = yaml.getString("shop.currency.name");
        if (currencyName != null && currencyName.isEmpty()) {
            currencyName = null;
        }

        // ---- NPC ----
        String npcName = yaml.getString("shop.npc.name");
        if (npcName != null && npcName.isEmpty()) {
            npcName = null;
        }

        // ---- GUI ----
        String guiTitle = yaml.getString("shop.gui.title");
        if (guiTitle != null && guiTitle.isEmpty()) {
            guiTitle = null;
        }
        int guiSize = clampGuiSize(yaml.getInt("shop.gui.size", 27));

        // ---- 商品列表 ----
        List<ShopItem> items = new ArrayList<>();
        ConfigurationSection itemsSection = yaml.getConfigurationSection("shop.items");
        if (itemsSection != null) {
            for (String id : itemsSection.getKeys(false)) {
                ShopItem item = parseItem(yaml, id, guiSize);
                if (item != null) {
                    items.add(item);
                }
            }
        }

        return new ShopConfig(currencyMaterial, currencyName, npcName, guiTitle, guiSize, items);
    }

    /** 解析单个商品，非法条目返回 null 并 warning */
    private ShopItem parseItem(YamlConfiguration yaml, String id, int guiSize) {
        String path = "shop.items." + id;

        String type = yaml.getString(path + ".type", "");
        if (!VALID_TYPES.contains(type)) {
            plugin.getLogger().warning("shop.yml: item '" + id + "' has unknown type '" + type
                    + "' (valid: HAMMER/FIREBALL/REPAIR_ITEM/MATERIAL), skipped");
            return null;
        }

        Material icon = Material.BARRIER;
        String iconMatStr = yaml.getString(path + ".icon.material");
        if (iconMatStr != null && !iconMatStr.isEmpty()) {
            Material parsed = Material.getMaterial(iconMatStr);
            if (parsed == null) {
                plugin.getLogger().warning("shop.yml: item '" + id + "' has unknown icon material '"
                        + iconMatStr + "', skipped");
                return null;
            }
            icon = parsed;
        }

        int slot = yaml.getInt(path + ".slot", -1);
        if (slot < 0 || slot >= guiSize) {
            plugin.getLogger().warning("shop.yml: item '" + id + "' slot " + slot
                    + " out of GUI size " + guiSize + ", skipped");
            return null;
        }

        int price = yaml.getInt(path + ".price", 1);
        int amount = yaml.getInt(path + ".amount", 1);

        String name = yaml.getString(path + ".name");
        if (name != null && name.isEmpty()) {
            name = null;
        }
        List<String> lore = yaml.getStringList(path + ".lore");
        if (lore.isEmpty()) {
            lore = null;
        }

        return new ShopItem(id, slot, price, type, amount, icon, name, lore);
    }

    /** GUI 尺寸校验：合法值原样返回；非法值钳制到不超过原值的最大档（全小则 9）并 warning */
    private int clampGuiSize(int size) {
        if (VALID_GUI_SIZES.contains(size)) {
            return size;
        }
        int clamped = 9;
        for (int valid : VALID_GUI_SIZES) {
            if (size >= valid) {
                clamped = valid;
            }
        }
        plugin.getLogger().warning("shop.yml: invalid GUI size " + size
                + " (valid: 9/18/27/36/45/54), falling back to " + clamped);
        return clamped;
    }
}
