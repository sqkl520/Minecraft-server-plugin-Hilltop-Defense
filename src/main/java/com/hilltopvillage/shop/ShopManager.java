package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

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

    /**
     * 重载 shop.yml 并刷新内存快照。
     *
     * <p><b>S09-F1：本方法永不抛异常。</b>商店是<b>功能</b>而不是核心基础设施 ——
     * {@code shop.yml} 为空/损坏/被隔离时，正确行为是让<b>商店</b>降级，而不是让整个插件
     * 起不来。旧实现的第一个语句就是 {@code this.config = loader.load()}，
     * 而 {@link ShopConfigLoader#load()} 对每种不可用读取都抛 {@link IllegalStateException}；
     * {@code onEnable} 又刻意不给 {@code ShopManager} 套 try/catch（核心模块失败即禁用插件），
     * 于是「一份空的 shop.yml」= onEnable 中止 = 没有竞技场、没有世界、没有命令、没有监听器。</p>
     *
     * <p><b>降级口径</b>（两种场景都必须有确定行为）：</p>
     * <ul>
     *   <li><b>已有上一份有效配置</b> → 保留它（{@code /hilltop shop reload} 改坏文件不该把
     *       正在营业的商店清空）。</li>
     *   <li><b>首次启动就没有可用配置</b>（{@code config == null}）→ 落回内置空商店。
     *       「保留上一份」这条在首次启动时无解：此时没有任何可用快照，{@code config} 必须
     *       非 null（{@code ShopGUI} / {@code NpcManager} / {@code createCurrencyItem} 都会解引用它）。
     *       空商店 = 0 件商品，{@link ShopGUI} 因此只剩占位板，{@code getItemAt} 恒返回 null，
     *       点击不会进入 {@link #deliverItem}，购买链自然拒绝而不会 NPE。</li>
     * </ul>
     *
     * <p>只 catch {@link RuntimeException}：{@link Error}（OOM / 类加载失败）不应该被当成
     * 配置问题吞掉。</p>
     *
     * @return true = 新配置已生效；false = 读取失败，正在使用「上一份快照或内置空商店」
     */
    public boolean reload() {
        try {
            this.config = loader.load();
        } catch (RuntimeException failure) {
            // 文案英文（日志一律英文），并指明当前降级到哪种状态，避免管理员以为商店只是「没刷新」
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to load shop.yml — the shop is now running on "
                            + (config == null ? "a built-in empty item list" : "the previously loaded configuration")
                            + " (no items can be bought until the file is fixed, then run /hilltop shop reload)",
                    failure);
            if (config == null) {
                config = emptyShopConfig();
            }
            return false;
        }
        // 已打开的 ShopGUI 在构造期固化了旧 ShopConfig 与 bySlot，reload 不换掉它们，
        // 玩家会继续按旧价成交。这里关闭所有残留 GUI，强制重开以拾取新配置。
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopGUI) {
                p.closeInventory();
            }
        }
        plugin.getLogger().info("Shop loaded " + config.getItems().size() + " items");
        return true;
    }

    /**
     * 内置空商店（S09-F1 的首次启动兜底）：0 件商品 + 出厂货币材质 + 最小合法 GUI 尺寸。
     *
     * <p>{@code currencyMaterial} 必须是可携带物品：{@link #createCurrencyItem} 会对它调
     * {@code new ItemStack}，用 {@code Material.AIR} 之类会抛异常 —— 而这正落在「坏配置也要
     * 能安全降级」的反面。i18n 与 GUI 文案全部留 null，让它们各自回退到默认键。</p>
     */
    private static ShopConfig emptyShopConfig() {
        return new ShopConfig(Material.EMERALD, null, null, null, 27, List.of());
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
        // dc-005：超过单叠上限的数量不得在此裁剪。ItemStack.of(material, n) 只校验 n > 0，
        // 不校验上限；CraftInventory#addItem 会按 getMaxStackSize() 自行拆到多个槽位（余量进
        // 返回的 overflow map，调用方已落地处理）。若这里 Math.min(n, maxStack)，退款与
        // /hilltop shop give（上限 6400）会真的少发币。因此只把配置异常暴露成日志。
        int maxStack = config.getCurrencyMaterial().getMaxStackSize();
        if (amount > maxStack) {
            plugin.getLogger().warning("Shop currency request " + amount + " exceeds max stack "
                    + maxStack + " of " + config.getCurrencyMaterial() + "; addItem() will split it");
        }
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
     * HAMMER 固定 1 个（忽略 amount）；FIREBALL / REPAIR_ITEM / MATERIAL 按 amount；
     * EQUIPMENT 固定 1 个（发货材质 = icon.material，带 name/lore/unbreakable）。
     */
    public ItemStack deliverItem(ShopItem item) {
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
            case "EQUIPMENT":
                return createEquipment(item);
            default:
                plugin.getLogger().warning("Shop deliver: unknown item type '" + item.getType()
                        + "' for '" + item.getId() + "'");
                return null;
        }
    }

    /**
     * 构建 EQUIPMENT 发货物：图标材质即发货材质，固定 1 个。
     *
     * <p>与 MATERIAL 的关键差异：name / lore 不再只作 GUI 图标显示，而是原样
     * 写入发到玩家手里的物品实体（均为空时保留原版外观；lore 一并作为物品
     * lore——GUI 里追加的价格行由 {@code ShopGUI} 单独拼接，不会进入发货物）。
     * {@code unbreakable: true} 时标记不可破坏（服主可选；不配 = 普通耐久）。</p>
     *
     * <p>本版不含附魔字段（附魔延后至下一版本，见设计文档 11.0b）。</p>
     */
    private ItemStack createEquipment(ShopItem item) {
        ItemStack equipment = new ItemStack(item.getIcon(), 1);
        ItemMeta meta = equipment.getItemMeta();
        if (meta == null) {
            // 理论上 item 材质必有 meta；防御性返回裸物品而不是 null（null 会让 ShopListener 走退款分支）
            return equipment;
        }

        if (item.getName() != null && !item.getName().isEmpty()) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', item.getName()));
        }
        if (item.getLore() != null && !item.getLore().isEmpty()) {
            List<String> loreLines = new ArrayList<>(item.getLore().size());
            for (String line : item.getLore()) {
                loreLines.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(loreLines);
        }

        if (item.isUnbreakable()) {
            meta.setUnbreakable(true);
        }

        equipment.setItemMeta(meta);
        return equipment;
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
