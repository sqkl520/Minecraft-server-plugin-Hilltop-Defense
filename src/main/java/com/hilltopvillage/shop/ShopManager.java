package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 村民商店管理器：配置生命周期 + 货币物品工厂 + 发货路由 + 背包货币统计/扣除。
 * 货币识别<b>只认 PDC 标记</b>（hilltopvillage:shop-currency），不做材质判定
 * （材质短路会让 {@code shop reload} 换材质后存量真币作废，见 {@link #isCurrency}）。
 * 真币一律由 {@link #createCurrencyItem} 发放。
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
     *
     * <p>入参钳制（P2-26）：{@code ItemStack(Material, int)} 对 amount ≤ 0 直接抛
     * IllegalArgumentException。配置链（fix4 起为 {@code shop.currency-drop-min/-max} 区间，
     * 滚动逻辑在 {@code WaveManager#rollCurrencyDropAmount} 已钳制）异常时，
     * 服主配 0 / 负数会让每次掉币判定命中都在死亡事件里抛异常（吞击杀统计 + 刷日志）；
     * 退款分支同样走本工厂。这里统一兜底为至少 1。</p>
     */
    public ItemStack createCurrencyItem(int amount) {
        amount = Math.max(1, amount);
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

    /** 统计玩家背包中的货币数量（含副手槽，P2-28：与扣除口径保持读写同源） */
    public int countCurrency(Player player) {
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getStorageContents()) {
            if (isCurrency(item)) {
                count += item.getAmount();
            }
        }
        // getStorageContents 不含副手/盔甲槽：副手单独判定并入统计，否则 F 键换到副手的货币
        // 会从余额里「消失」（既不计余额也无法支付）
        ItemStack offHand = inv.getItemInOffHand();
        if (isCurrency(offHand)) {
            count += offHand.getAmount();
        }
        return count;
    }

    /**
     * 从背包移除指定数量货币（主背包从后往前删，不够再从副手扣）。
     * 不足时不部分扣除，直接返回 false。
     */
    public boolean removeCurrency(Player player, int amount) {
        if (amount <= 0) return true;
        if (countCurrency(player) < amount) return false;

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int remaining = amount;

        // 从后往前扣主背包
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

        // 主背包扣完还不够（或一开始就都在副手）：从副手扣，与 countCurrency 的口径一致
        if (remaining > 0) {
            ItemStack offHand = inv.getItemInOffHand();
            if (isCurrency(offHand)) {
                int take = Math.min(offHand.getAmount(), remaining);
                if (offHand.getAmount() > take) {
                    offHand.setAmount(offHand.getAmount() - take);
                    inv.setItemInOffHand(offHand); // 显式写回，确保变更落盘（不依赖引用共享）
                } else {
                    inv.setItemInOffHand(null);
                }
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    /**
     * 判断一个 ItemStack 是否为货币。
     *
     * <p><b>只认 PDC 标记</b>（{@link #currencyKey}）：真币一律由
     * {@link #createCurrencyItem} 发放，必带此标记。材质检查已删除（P2-27）——
     * 它作为前置短路会让服主 {@code shop reload} 更换 {@code currency.material} 后，
     * 所有带 PDC 的存量真币立即作废（材质不等 → 短路否决权威标记）；作为后置独立条件
     * 则排斥换材质前的存量货币。PDC 即唯一权威，与 P0-A 的「只认 PDC」规范一致。</p>
     *
     * <p>曾经的「材质 + 显示名回退」已删除 —— 它会把玩家用铁砧 / 命名牌改名成货币名的
     * 普通方块误判为真币，造成假币无限付款（P0-A）。服主手工发币请改用
     * {@code /hilltop shop give <玩家> [数量]}（见 {@code HilltopCommandHandler}）。</p>
     */
    private boolean isCurrency(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        // 只认 PDC 标记：玩家无法用铁砧 / 命名牌伪造。
        return meta.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING);
    }
}
