package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 村民商店管理器：配置生命周期 + 货币物品工厂 + 发货路由 + 背包货币统计/扣除。
 * 货币识别 = Material + 显示名（双保险再加 PDC 标记 hilltopvillage:shop-currency）。
 */
public class ShopManager {

    /** 货币 PDC 标记（后续购买 Story 亦复用识别） */
    private final NamespacedKey currencyKey;

    /** 商店村民 NPC 管理器（reload 不重建，保持实体引用稳定） */
    private final NpcManager npcManager;

    private final HilltopVillagePlugin plugin;
    private final ShopConfigLoader loader;
    private final LanguageManager lang;
    private ShopConfig config;

    /**
     * 预编译的货币显示名（供 {@link #isCurrency} 比对）。
     *
     * <p>{@link #isCurrency} 会在扫背包时被逐格调用，而它原来每次都跑一遍
     * {@link ChatColor#translateAlternateColorCodes} + {@code lang.get} —— 这两个值只在
     * shop.yml 重载时才变，属于典型的「热路径上做静态计算」。</p>
     */
    private String cachedCurrencyName;

    public ShopManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.loader = new ShopConfigLoader(plugin);
        this.lang = plugin.getLanguageManager();
        this.currencyKey = new NamespacedKey(plugin, "shop-currency");
        this.npcManager = new NpcManager(plugin);
        // 构造函数内 config 尚未加载（reload 不是自动调的），此处只为让缓存非 null：
        // 走 config == null 分支取 i18n 缺省名，与旧实现逐次调用的返回值一致。
        refreshCurrencyName();
    }

    /** 构造函数重载（支持外部传入 LanguageManager） */
    public ShopManager(HilltopVillagePlugin plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.loader = new ShopConfigLoader(plugin);
        this.lang = languageManager;
        this.currencyKey = new NamespacedKey(plugin, "shop-currency");
        this.npcManager = new NpcManager(plugin);
        refreshCurrencyName();
    }

    /** 重载 shop.yml 并刷新内存快照 */
    public void reload() {
        this.config = loader.load();
        // config 换新后必须重算货币显示名，否则 isCurrency 会拿旧名字比对。
        refreshCurrencyName();
        plugin.getLogger().info("Shop loaded " + config.getItems().size() + " items");
    }

    /**
     * 重新计算 {@link #cachedCurrencyName}。
     * 必须在 {@code config} 赋值之后调用（构造与 {@link #reload()} 两处）。
     */
    private void refreshCurrencyName() {
        String configured = config == null ? null : config.getCurrencyName();
        this.cachedCurrencyName = (configured != null && !configured.isEmpty())
                ? ChatColor.translateAlternateColorCodes('&', configured)
                : lang.get("shop.currency.name");
    }

    public ShopConfig getShopConfig() {
        return config;
    }

    /** 持有插件的引用（ShopListener 等模块复用） */
    public HilltopVillagePlugin getPlugin() {
        return plugin;
    }

    /** 商店村民 NPC 管理（生成/清除/重启恢复/防僵尸实体） */
    public NpcManager getNpcManager() {
        return npcManager;
    }

    /**
     * 构建货币物品（击杀掉落 / 后续购买找零均用此工厂）。
     * 名称优先 shop.yml 的 currency.name，否则 i18n 的 shop.currency.name。
     */
    public ItemStack createCurrencyItem(int amount) {
        ItemStack item = new ItemStack(config.getCurrencyMaterial(), amount);
        ItemMeta meta = item.getItemMeta();

        String configured = config.getCurrencyName();
        String name = (configured != null && !configured.isEmpty())
                ? ChatColor.translateAlternateColorCodes('&', configured)
                : lang.get("shop.currency.name");
        meta.setDisplayName(name);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(currencyKey, PersistentDataType.STRING, "1");

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 按商品类型发货，返回待放入背包的 ItemStack（null = 类型异常）。
     * HAMMER 固定 1 个（忽略 amount）；FIREBALL / REPAIR_ITEM / MATERIAL 按 amount。
     */
    public ItemStack deliverItem(ShopItem item, Player player) {
        if (item == null) return null;
        int amount = Math.max(1, item.getAmount());

        switch (item.getType()) {
            case "HAMMER":
                return plugin.getHammerListener().createHammer();
            case "FIREBALL":
                return plugin.getFireballListener().createFireball().asQuantity(amount);
            case "REPAIR_ITEM":
                return plugin.getNodeListener().createRepairItem(amount);
            case "MATERIAL":
                return new ItemStack(item.getIcon(), amount);
            default:
                plugin.getLogger().warning("Shop deliver: unknown item type '" + item.getType()
                        + "' for '" + item.getId() + "'");
                return null;
        }
    }

    /** 统计玩家背包中的货币数量 */
    public int countCurrency(Player player) {
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getStorageContents()) {
            if (isCurrency(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * 从背包移除指定数量货币（从后往前删）。
     * 不足时不部分扣除，直接返回 false。
     */
    public boolean removeCurrency(Player player, int amount) {
        if (amount <= 0) return true;
        if (countCurrency(player) < amount) return false;

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int remaining = amount;

        // 从后往前扣
        for (int i = contents.length - 1; i >= 0 && remaining > 0; i--) {
            ItemStack item = contents[i];
            if (!isCurrency(item)) continue;

            int take = Math.min(item.getAmount(), remaining);
            if (item.getAmount() > take) {
                item.setAmount(item.getAmount() - take);
            } else {
                inv.setItem(i, null);
            }
            remaining -= take;
        }
        return remaining <= 0;
    }

    /** 判断一个 ItemStack 是否为货币（材质 + 显示名 / PDC 标记） */
    private boolean isCurrency(ItemStack item) {
        if (item == null || item.getType() != config.getCurrencyMaterial()) return false;
        if (!item.hasItemMeta()) return false;

        // hasItemMeta() + getItemMeta() 是两次独立的 meta 构建，只取一次即可
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        // PDC 标记最稳（本插件产出的货币必有）
        if (meta.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING)) {
            return true;
        }
        // 兼容服主手工发放的货币：显示名匹配
        if (!meta.hasDisplayName()) return false;
        return meta.getDisplayName().equals(cachedCurrencyName);
    }
}
