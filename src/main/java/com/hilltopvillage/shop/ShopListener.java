package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民商店常驻监听器：右键 NPC 开商店 / NPC 防伤 / GUI 点击购买 + 防刷。
 * 生命周期：onEnable 注册一次（区别于 SetupWizard 的动态 register/unregister 模式）。
 */
public class ShopListener implements Listener {

    private final HilltopVillagePlugin plugin;
    private final ShopManager shopManager;
    private final LanguageManager lang;
    /** 购买冷却（防连点重复扣费刷物品）：玩家 UUID -> 上次购买时间戳 ms */
    private final Map<UUID, Long> buyCooldowns = new ConcurrentHashMap<>();

    /** 购买操作冷却（ms），屏蔽双击/同 tick 多次点击 */
    private static final long BUY_COOLDOWN_MS = 250L;

    public ShopListener(ShopManager shopManager, LanguageManager lang) {
        this.plugin = shopManager.getPlugin();
        this.shopManager = shopManager;
        this.lang = lang;
    }

    /** 右键商店村民：打开商店 GUI + 提示当前货币数量 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!shopManager.getNpcManager().isShopNpc(event.getRightClicked())) return;

        // 阻止原版村民交易界面
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;

        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.2f);

        // ✅ 商店 GUI 开启时发送余额到 ActionBar（不阻塞聊天栏）
        int balance = shopManager.countCurrency(player);
        player.sendActionBar(lang.getFor(player, "shop.currency-balance", String.valueOf(balance)));

        new ShopGUI(player, shopManager, lang).open();
    }

    /** 商店村民无敌保护：任何伤害一律取消 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDamage(EntityDamageEvent event) {
        if (shopManager.getNpcManager().isShopNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * 商店 GUI 点击：一律取消（防 shift/数字键/双击拿取物品），
     * 点的是商店本体（非玩家背包）且槽位对应商品时执行购买。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopGUI gui)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 只处理点商店本体（shift 点背包物品时 clickedInventory 是玩家背包）
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) return;

        ShopItem item = gui.getItemAt(event.getSlot());
        if (item == null) return; // 占位符

        // 购买冷却（同 tick 双击/连点只结算一次）
        Long last = buyCooldowns.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < BUY_COOLDOWN_MS) return;
        buyCooldowns.put(player.getUniqueId(), now);

        purchase(player, item);
    }

    /** 商店 GUI 拖拽一律取消（防拖拽刷物品） */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopGUI) {
            event.setCancelled(true);
        }
    }

    /** 购买流程：扣货币 → 发货 → 背包投递（满则掉脚下） → 反馈 */
    private void purchase(Player player, ShopItem item) {
        int have = shopManager.countCurrency(player);
        if (have < item.getPrice()) {
            player.sendMessage(lang.getFor(player, "shop.not-enough",
                    String.valueOf(item.getPrice() - have)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // 复核返回值：上面刚数过余额，这里理论上不会失败，但忽略返回值会让「将来有人把
        // 本流程挪进异步 / 改成多线程」变成静默错扣（发货但不扣款）。补一行纯防御。
        if (!shopManager.removeCurrency(player, item.getPrice())) {
            player.sendMessage(lang.getFor(player, "shop.not-enough",
                    String.valueOf(item.getPrice() - have)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        ItemStack product = shopManager.deliverItem(item, player);
        if (product == null) {
            // 类型异常：退回货币（deliverItem 已 warning 日志）
            player.getInventory().addItem(shopManager.createCurrencyItem(item.getPrice()));
            return;
        }

        // 背包投递：满则溢出部分掉在脚下
        java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(product);
        if (!overflow.isEmpty()) {
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            player.sendMessage(lang.getFor(player, "shop.inventory-full"));
        }

        player.sendMessage(lang.getFor(player, "shop.purchase-success",
                resolveItemName(player, item), String.valueOf(product.getAmount()),
                String.valueOf(item.getPrice())));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

        // ✅ 购买成功后更新 ActionBar 余额显示
        // 余额 = 购买前余额 - 实际花费（避免第三次全背包扫描）。
        // have 是所有格子的货币总和，扣掉 price 后剩余总量必然等于 have - price，
        // 与货币分布在哪几格无关；removeCurrency 失败与 deliverItem 返回 null 两条路径
        // 都已提前 return，不会走到这里。
        // price <= 0 时 removeCurrency 会直接返回 true 且不扣除任何货币（见 ShopManager#removeCurrency），
        // 此时真实余额仍是 have —— 用 max(0, price) 让这一边界与旧实现逐字一致。
        // （ShopConfigLoader 现已把负数价格钳到 0，但这里仍然保留 max 以覆盖 price == 0 与将来改动）
        int spent = Math.max(0, item.getPrice());
        player.sendActionBar(lang.getFor(player, "shop.currency-balance",
                String.valueOf(have - spent)));
    }

    /** 商品名（消息占位用）：shop.yml 直配优先，否则用 i18n 键值，最后回退材质名 */
    private String resolveItemName(Player player, ShopItem item) {
        if (item.getName() != null && !item.getName().isEmpty()) {
            return org.bukkit.ChatColor.translateAlternateColorCodes('&', item.getName());
        }
        String key = "shop.item." + item.getId() + ".name";
        String msg = lang.getFor(player, key);
        if (!msg.equals(key)) {
            return msg;
        }
        return item.getIcon().name().toLowerCase().replace('_', ' ');
    }

    /** 玩家离线时清理购买冷却记录，防内存泄漏 */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        buyCooldowns.remove(event.getPlayer().getUniqueId());
    }

    /** 获取持有插件的引用（构造注入 ShopManager 时获取） */
    public HilltopVillagePlugin getPlugin() {
        return plugin;
    }
}
