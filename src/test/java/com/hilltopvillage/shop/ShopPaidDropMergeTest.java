package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@code F5-droploss} 的回归网：<b>{@link ShopListener#PAID_DROP_KEY} 必须活过原版并物</b>。
 *
 * <p><b>缺陷原文</b>：{@code ShopListener} 把 {@code PAID_DROP_KEY} 打在 <b>Item 实体</b>的
 * PDC 上（{@code ShopListener.java:159}、{@code :197}）。原版
 * {@code net.minecraft.world.entity.item.ItemEntity#merge} 在合并时从 <b>{@code this}</b>
 * 的栈 {@code copyWithCount} 重建结果，并把 <b>{@code other}</b> 的实体
 * {@code discard(EntityRemoveEvent.Cause.MERGE)}。因此：</p>
 *
 * <ul>
 *   <li>{@code this} 是付过钱的那件 → key 在它自己身上，存活，安全；</li>
 *   <li>{@code this} 是<b>普通</b>那件、付过钱的是 {@code other} → 存活实体没有 key，
 *       付过钱的那件连同它的实体 PDC 一起被 discard → 下次
 *       {@code ArenaInstance.clearDroppedItems()} 把货当垃圾删掉。</li>
 * </ul>
 *
 * <p>哪一方是 {@code this} 取决于同一 tick 内谁先 {@code tick()}，所以这不是罕见竞态，
 * 而是「竞技场商店附近只要有一件同材质普通掉落物就有一半概率」的常态缺陷。</p>
 *
 * <p><b>修复方式</b>：监听 {@link ItemMergeEvent}，在看到合并<b>确实未被取消</b>后，
 * 把 key 预先转移到<b>存活</b>的那个实体（{@link ItemMergeEvent#getTarget()}）上。
 * 事件是全流程唯一的合并闸门（{@code CraftEventFactory.callItemMergeEvent}），
 * 且在主线程、合并执行之前同步触发，因此这一步能可靠地改写存活实体的 PDC。</p>
 *
 * <p><b>本类钉住的三件事</b>：</p>
 * <ol>
 *   <li>{@link MergeSurvival}：带 key + 普通同种 → 合并后存活实体仍被判定为「已付对价」，
 *       且两个合并方向（key 在 entity / key 在 target）都成立；</li>
 *   <li>{@link NoOverclean}：<b>防修过头</b> —— 两件普通掉落物合并后，
 *       存活实体<b>不得</b>被标成已付款，否则 {@code clearDroppedItems} 会永久留它，
 *       竞技场越来越脏；</li>
 *   <li>{@link PickupStillWorks}：合并后的货仍能被玩家捡到（`pickupDelay` 不得被改成永久）。</li>
 * </ol>
 *
 * <p><b>MockBukkit 的能力边界（如实说明）</b>：MockBukkit <b>没有</b>原版
 * {@code ItemEntity#merge} 的实现，{@code ItemMock} 也不参与 tick 合并。因此本类
 * <b>不能</b>做「让两件掉落物真的合并」的端到端验证。这里验证的是
 * <b>{@link ShopListener} 对 {@link ItemMergeEvent} 的响应</b>——即我们自己的判定与打标逻辑——
 * 事件参数与真实 Paper 的实参映射已按
 * {@code CraftEventFactory.callItemMergeEvent} 字节码核对（见报告 0.5）。
 * 也就是说：<b>本类证明「若原版按已知语义触发该事件，标记会活下来」，
 * 不证明「原版一定按该语义触发」</b>。后者只能靠真实服务器验证（见报告第 4 节）。</p>
 */
class ShopPaidDropMergeTest extends MockBukkitTestBase {

    /** 41 = 36 主背包 + 4 护甲 + 1 副手；{@code addItem} 会用到全部 41 个槽位。 */
    private static final int MOCK_INVENTORY_SLOTS = 41;

    // ================================================================ 夹具

    private static InventoryView view(PlayerMock viewer, Inventory top, Inventory bottom) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(view.getPlayer()).thenReturn(viewer);
        when(view.getType()).thenReturn(InventoryType.CHEST);
        when(view.getInventory(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int raw = invocation.getArgument(0);
            if (raw < 0) return null;
            return raw < top.getSize() ? top : bottom;
        });
        when(view.convertSlot(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int raw = invocation.getArgument(0);
            return raw < top.getSize() ? raw : raw - top.getSize();
        });
        return view;
    }

    private static LanguageManager stubLanguage() {
        return mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static ShopItem factoryItem(ShopManager shop, String id) {
        return shop.getShopConfig().getItems().stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("出厂 shop.yml 里应有商品 " + id));
    }

    private static void fillEverySlot(PlayerMock player) {
        for (int i = 0; i < MOCK_INVENTORY_SLOTS; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
    }

    /** 「已付对价」的判据必须与 {@code ArenaInstance.clearDroppedItems} 完全一致。 */
    private static boolean isPaidDrop(Item item) {
        // 标记在 Item **实体**的 PDC 上（与 ArenaInstance.clearDroppedItems 的口径一致）。
        return item.getPersistentDataContainer()
                .has(ShopListener.PAID_DROP_KEY, PersistentDataType.BYTE);
    }

    /** 写入侧：与生产实现 {@code ShopListener#markPaidDrop} 同一容器（实体 PDC）。 */
    private static void markPaid(Item item) {
        item.getPersistentDataContainer()
                .set(ShopListener.PAID_DROP_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    private static List<String> describeDrops(World world) {
        List<String> out = new ArrayList<>();
        for (Item item : world.getEntitiesByClass(Item.class)) {
            out.add(item.getItemStack().getType() + "x" + item.getItemStack().getAmount()
                    + (isPaidDrop(item) ? "[PAID]" : "[plain]"));
        }
        return out;
    }

    // ================================================================ 核心：标记活过合并

    /**
     * <b>核心用例</b>：带 key 的掉落物与普通同种掉落物合并，存活实体仍被识别为「已付对价」。
     *
     * <p>两个方向都必须成立，因为哪一方是「存活者」由原版 tick 顺序决定：</p>
     * <ul>
     *   <li>方向 A：付过钱的是 {@code event.getEntity()}（将被 discard）→ 必须补标到 target；</li>
     *   <li>方向 B：付过钱的是 {@code event.getTarget()}（存活者）→ key 天然保留，不得被清掉。</li>
     * </ul>
     *
     * <p>方向 A 是当前实现真正会丢货的那一半；方向 B 是「别把已经对的改坏」的守卫。</p>
     */
    @Nested
    @DisplayName("核心 —— 合并后存活实体仍被判定为已付对价")
    class MergeSurvival {

        private BareHost host;
        private ShopManager shop;
        private ShopListener listener;
        private LanguageManager lang;
        private World world;

        @BeforeEach
        void setUp() {
            host = loadBareHost();
            lang = stubLanguage();
            shop = new ShopManager(host, lang);
            shop.reload();
            // 真实注册：Node A 依赖 @EventHandler 真的被 Bukkit 分发器找到。
            // 直接调方法只能证明「方法体对」，证明不了「注解/事件类型接得上」。
            listener = MockBukkit_loadRegisteredListener();
            world = server.addSimpleWorld("merge-test-world");
        }

        /** 用 MockBukkit 加载一个真正注册了 {@link ShopListener} 的插件宿主。 */
        private ShopListener MockBukkit_loadRegisteredListener() {
            ShopListener fresh = new ShopListener(shop, lang);
            org.bukkit.Bukkit.getPluginManager().registerEvents(fresh, host);
            return fresh;
        }

        private Item dropAt(Material material, boolean paid) {
            Item item = world.dropItemNaturally(new Location(world, 0.5, 64, 0.5),
                    new ItemStack(material, 1));
            if (paid) {
                markPaid(item);
            }
            return item;
        }

        @Test
        @DisplayName("方向 A：付过钱的那件是 getEntity()（将被 discard）→ 合并后存活者必须带 key")
        void paidDropAsRemovedSideKeepsKeyOnSurvivor() {
            Item paid = dropAt(Material.BOW, true);
            Item plain = dropAt(Material.BOW, false);
            assertTrue(isPaidDrop(paid), "前置条件：paid 应带 key");
            assertFalse(isPaidDrop(plain), "前置条件：plain 不应带 key");

            // 真实实参映射：CraftEventFactory.callItemMergeEvent(other, this)，
            // 故 getEntity() == other（被 discard），getTarget() == this（存活）。
            // 这里构造「付过钱的是 other」= 当前实现会丢 key 的那一半。
            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(paid, plain));

            assertTrue(isPaidDrop(plain),
                    "F5-droploss：付过钱的掉落物作为被合并方（other / getEntity）消失时，"
                            + "key 必须转移到存活实体上。否则 ArenaInstance.clearDroppedItems 会把"
                            + "玩家付过钱的货当普通掉落物删掉 —— 这就是「付了钱拿不到货」本身。"
                            + "当前存活实体：" + describeDrops(world));
        }

        @Test
        @DisplayName("方向 B：付过钱的就是存活者（getTarget）→ key 不得被清掉")
        void paidDropAsSurvivingSideKeepsKey() {
            Item paid = dropAt(Material.BOW, true);
            Item plain = dropAt(Material.BOW, false);

            // 付过钱的是 this（getTarget / 存活），普通的是 other（getEntity / 被 discard）
            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(plain, paid));

            assertTrue(isPaidDrop(paid),
                    "守卫半：付过钱的本来就是存活方，key 天然保留；任何实现都不许把它清掉。"
                            + "当前存活实体：" + describeDrops(world));
        }

        @Test
        @DisplayName("修复后：竞技场重置不会删掉合并后的那件货（两半接得上）")
        void survivorOfMergeSurvivesArenaReset() {
            // 这条用真实竞技场世界：只有把「合并后仍带 key」与「clearDroppedItems 跳过」串起来，
            // 才能证明修复对玩家真的有效，而不只是「PDC 里有个字节」。
            HilltopVillagePlugin plugin = loadFullPluginWithArenas();
            ArenaInstance arena = plugin.getMultiArenaManager().getArena("arena1");
            assertNotNull(arena, "arena1 未被启用 —— 夹具失效");
            World arenaWorld = arena.getGameManager().getGameWorld();
            assertNotNull(arenaWorld, "arena1 的 GameManager 没有世界");

            Location at = new Location(arenaWorld, 0.5, 64, 0.5);
            Item paid = arenaWorld.dropItemNaturally(at, new ItemStack(Material.BOW, 1));
            markPaid(paid);
            Item plain = arenaWorld.dropItemNaturally(at, new ItemStack(Material.BOW, 1));

            ShopListener registered = new ShopListener(plugin.getShopManager(), lang);
            org.bukkit.Bukkit.getPluginManager().registerEvents(registered, plugin);

            // 模拟原版：付过钱的那件被合并掉，存活的是普通那件
            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(paid, plain));
            plain.setItemStack(new ItemStack(Material.BOW, 2));
            paid.remove();

            arena.resetNow();

            List<Item> remaining = new ArrayList<>(arenaWorld.getEntitiesByClass(Item.class));
            assertEquals(1, remaining.size(),
                    "F5-droploss：合并后的那件是玩家花水晶买的货，重置后必须还在。实际："
                            + describeDrops(arenaWorld));
            assertEquals(Material.BOW, remaining.get(0).getItemStack().getType(),
                    "活下来的必须是那件 BOW，实际：" + describeDrops(arenaWorld));
            assertTrue(isPaidDrop(remaining.get(0)),
                    "活下来的那件必须仍被识别为已付对价，实际：" + describeDrops(arenaWorld));
        }
    }

    // ================================================================ 防修过头

    /**
     * <b>防修过头</b>：两件<b>都不带</b> key 的普通掉落物合并，存活实体不得被标成已付款。
     *
     * <p>若实现写成「只要收到 ItemMergeEvent 就给 target 打标」，普通物品会获得永生，
     * {@code clearDroppedItems} 从此再也清不掉它们 —— 修复退化成新的实体泄漏。
     * 这一组就是判据 2。</p>
     */
    @Nested
    @DisplayName("防修过头 —— 普通掉落物合并后仍不带 key（清理必须继续有效）")
    class NoOverclean {

        private BareHost host;
        private ShopManager shop;
        private ShopListener listener;
        private LanguageManager lang;
        private World world;

        @BeforeEach
        void setUp() {
            host = loadBareHost();
            lang = stubLanguage();
            shop = new ShopManager(host, lang);
            shop.reload();
            listener = new ShopListener(shop, lang);
            org.bukkit.Bukkit.getPluginManager().registerEvents(listener, host);
            world = server.addSimpleWorld("no-overclean-world");
        }

        @Test
        @DisplayName("普通 + 普通 合并：存活实体不带 key")
        void plainMergeDoesNotInheritKey() {
            Item a = world.dropItemNaturally(new Location(world, 0.5, 64, 0.5),
                    new ItemStack(Material.STONE, 1));
            Item b = world.dropItemNaturally(new Location(world, 0.5, 64, 0.5),
                    new ItemStack(Material.STONE, 1));
            assertFalse(isPaidDrop(a) || isPaidDrop(b), "前置条件：两件都不应带 key");

            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(a, b));

            assertFalse(isPaidDrop(b),
                    "判据 2（防修过头）：两件普通掉落物合并后，存活实体绝不能变成「已付对价」，"
                            + "否则它会被 clearDroppedItems 永久放行，竞技场世界里的垃圾实体只增不减。"
                            + "实际：" + describeDrops(world));
        }

        @Test
        @DisplayName("竞技场：合并后的普通掉落物仍被 clearDroppedItems 删除（清理没被改坏）")
        void plainMergeStillGetsCleanedUp() {
            HilltopVillagePlugin plugin = loadFullPluginWithArenas();
            ArenaInstance arena = plugin.getMultiArenaManager().getArena("arena1");
            assertNotNull(arena, "arena1 未被启用 —— 夹具失效");
            World arenaWorld = arena.getGameManager().getGameWorld();
            assertNotNull(arenaWorld, "arena1 的 GameManager 没有世界");

            Location at = new Location(arenaWorld, 0.5, 64, 0.5);
            Item a = arenaWorld.dropItemNaturally(at, new ItemStack(Material.STONE, 1));
            Item b = arenaWorld.dropItemNaturally(at, new ItemStack(Material.STONE, 1));

            ShopListener registered = new ShopListener(plugin.getShopManager(), lang);
            org.bukkit.Bukkit.getPluginManager().registerEvents(registered, plugin);

            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(a, b));
            b.setItemStack(new ItemStack(Material.STONE, 2));
            a.remove();

            arena.resetNow();

            assertTrue(arenaWorld.getEntitiesByClass(Item.class).isEmpty(),
                    "判据 2：普通掉落物合并后仍必须被竞技场重置清理。若这里不为空，说明「修复」"
                            + "把 patch 变成了「谁都不删」。实际：" + describeDrops(arenaWorld));
        }
    }

    // ================================================================ 基本功能 + 可捡

    /**
     * <b>基本功能</b>：购买后背包满 → 掉落物带标记 → 清理时被跳过；且合并后<b>仍能被捡起</b>。
     *
     * <p>第二条是方案 (a) 的反例钉子：若有人日后改成「用极长 pickupDelay 阻止合并」，
     * {@code playerTouch} 会在 {@code pickupDelay != 0} 时直接 return（见报告 0.4 字节码），
     * 玩家就再也捡不到货，那等于用另一种方式违反判据 1。</p>
     */
    @Nested
    @DisplayName("基本功能 —— 购买溢出打标 + 合并后仍可捡")
    class PickupStillWorks {

        private LanguageManager lang;

        @BeforeEach
        void setUp() {
            lang = stubLanguage();
            // 刻意不在这里 addPlayer：ShopManager#reload 会遍历 Bukkit.getOnlinePlayers()
            // 并解引用 p.getOpenInventory().getTopInventory()，而 MockBukkit 对「从未打开过
            // 背包的玩家」返回的 view 里 topInventory 是 null → NPE（ShopManager.java:60）。
            // 买家在用例内部、插件启动之后再创建，绕开这个 MockBukkit 的假世界差异。
        }

        @Test
        @DisplayName("购买溢出 → 掉落物带 key → 竞技场重置跳过它")
        void purchaseOverflowIsMarkedAndSurvivesReset() {
            HilltopVillagePlugin plugin = loadFullPluginWithArenas();
            ArenaInstance arena = plugin.getMultiArenaManager().getArena("arena1");
            assertNotNull(arena, "arena1 未被启用 —— 夹具失效");
            World arenaWorld = arena.getGameManager().getGameWorld();
            assertNotNull(arenaWorld, "arena1 的 GameManager 没有世界");

            // 全量插件已启用，此刻才建买家：ShopManager#reload 在 onEnable 期内执行，
            // 那时若有在线玩家且其 view 的 topInventory 为 null 会 NPE（见 setUp 注释）。
            PlayerMock buyer = server.addPlayer("OverflowBuyer");
            buyer.setLocation(new Location(arenaWorld, 0.5, 64, 0.5));
            assertEquals("hilltop_arena1", buyer.getWorld().getName(),
                    "前置条件：买家必须站在竞技场世界里");

            ShopManager realShop = plugin.getShopManager();
            LanguageManager realLang = plugin.getLanguageManager();
            ShopItem bow = factoryItem(realShop, "bow");
            fillEverySlot(buyer);
            buyer.getInventory().setItem(35, realShop.createCurrencyItem(bow.getPrice() + 1));

            ShopListener realListener = new ShopListener(realShop, realLang);
            ShopGUI gui = new ShopGUI(buyer, realShop, realLang);
            gui.open();
            realListener.onInventoryClick(new InventoryClickEvent(
                    view(buyer, gui.getInventory(), buyer.getInventory()),
                    InventoryType.SlotType.CONTAINER, bow.getSlot(), ClickType.LEFT,
                    InventoryAction.PICKUP_ALL));

            List<Item> drops = new ArrayList<>(arenaWorld.getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "前置条件：背包全满买 1 件应恰好掉落 1 件，实际：" + describeDrops(arenaWorld));
            Item dropped = drops.get(0);
            assertTrue(isPaidDrop(dropped),
                    "打标侧：溢出掉落物必须带 PAID_DROP_KEY，实际：" + describeDrops(arenaWorld));

            arena.resetNow();

            List<Item> after = new ArrayList<>(arenaWorld.getEntitiesByClass(Item.class));
            assertEquals(1, after.size(),
                    "跳过侧：带 key 的货必须扛过竞技场重置，实际：" + describeDrops(arenaWorld));
            assertTrue(isPaidDrop(after.get(0)), "重置不得清掉标记");
        }

        @Test
        @DisplayName("合并后的货仍可被玩家捡起（pickupDelay 不得被改成永久）")
        void mergedPaidDropRemainsPickable() {
            BareHost host = loadBareHost();
            ShopListener registered = new ShopListener(new ShopManager(host, lang), lang);
            org.bukkit.Bukkit.getPluginManager().registerEvents(registered, host);

            World world = server.addSimpleWorld("pickup-world");
            Item paid = world.dropItemNaturally(new Location(world, 0.5, 64, 0.5),
                    new ItemStack(Material.BOW, 1));
            markPaid(paid);
            Item plain = world.dropItemNaturally(new Location(world, 0.5, 64, 0.5),
                    new ItemStack(Material.BOW, 1));

            org.bukkit.Bukkit.getPluginManager().callEvent(new ItemMergeEvent(paid, plain));

            assertTrue(isPaidDrop(plain),
                    "方向 A 的最小复现：合并后存活者必须带 key，实际：" + describeDrops(world));
            assertTrue(plain.getPickupDelay() < 32767,
                    "判据 1：合并后的货必须仍能被玩家捡起。若 pickupDelay == 32767，"
                            + "playerTouch 会在 pickupDelay != 0 时直接 return（原版字节码），"
                            + "玩家永远捡不到 —— 那是用「阻止合并」换「拿不到货」，等于没修。"
                            + "实际 pickupDelay=" + plain.getPickupDelay());
        }
    }
}
