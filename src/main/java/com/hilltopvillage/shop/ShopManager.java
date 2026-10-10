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
 * 货币识别<b>只认 PDC 标记</b>（hilltopvillage:shop-currency），材质仅作快速短路；
 * 真币一律由 {@link #createCurrencyItem} 发放。详见 {@link #isCurrency}。
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

    public ShopManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.loader = new ShopConfigLoader(plugin);
        this.lang = plugin.getLanguageManager();
        this.currencyKey = new NamespacedKey(plugin, "shop-currency");
        this.npcManager = new NpcManager(plugin);
    }

    /** 构造函数重载（支持外部传入 LanguageManager） */
    public ShopManager(HilltopVillagePlugin plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.loader = new ShopConfigLoader(plugin);
        this.lang = languageManager;
        this.currencyKey = new NamespacedKey(plugin, "shop-currency");
        this.npcManager = new NpcManager(plugin);
    }

    /** 重载 shop.yml 并刷新内存快照 */
    public void reload() {
        this.config = loader.load();
        plugin.getLogger().info("Shop loaded " + config.getItems().size() + " items");
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

    /**
     * 判断一个 ItemStack 是否为货币。
     *
     * <p><b>只认 PDC 标记</b>（{@link #currencyKey}）：真币一律由
     * {@link #createCurrencyItem} 发放，必带此标记。材质检查仅作快速短路。</p>
     *
     * <p>曾经的「材质 + 显示名回退」已删除 —— 它会把玩家用铁砧 / 命名牌改名成货币名的
     * 普通方块误判为真币，造成假币无限付款（P0-A）。服主手工发币请改用
     * {@code /hilltop shop give <玩家> [数量]}（见 {@code HilltopCommandHandler}）。</p>
     */
    private boolean isCurrency(ItemStack item) {
        if (item == null || item.getType() != config.getCurrencyMaterial()) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        // 只认 PDC 标记：玩家无法用铁砧 / 命名牌伪造，材质相同的改名道具一律被拒。
        return meta.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING);
    }
}
