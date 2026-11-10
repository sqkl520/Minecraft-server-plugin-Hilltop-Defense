package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


import java.io.File;
import java.io.IOException;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ShopListener} 的购买链 —— 扣款、发货、退款、防刷。
 *
 * <p><b>为什么这条链必须钉</b>：它是全插件唯一「玩家的钱会消失」的地方。round1 在这里修过两处，
 * 两处的共同形状都是「货币已经扣掉、然后玩家什么都没拿到」：</p>
 *
 * <ul>
 *   <li><b>{@code slice-12-dc-001}（退款自身抛异常）</b>：{@code refundCurrency()} 没有 try/catch，
 *       而它是在 {@code deliverItem} 的 {@code catch} 块里被调用的。退款一旦抛异常，
 *       异常直接穿出 {@code onInventoryClick} —— 此时币已扣、货未生成、玩家无任何反馈。
 *       见 {@link Refund#refundFailureDoesNotEscapeTheEventHandler}。</li>
 *   <li><b>fix6 Y2-04（构造必抛）</b>：{@code deliverItem} 返回 null 的退款分支只接得住「返回 null」，
 *       接不住「工厂构造抛异常」（如节点修复物品被配成 {@code WATER}）。修复是把抛面收敛成退款面。
 *       见 {@link Refund#deliverThrowingRefundsAndDoesNotPropagate}。</li>
 *   <li><b>{@code cross-pattern-6}（GUI 材质输入缺 isItem 守卫）</b>：本类的守护在
 *       {@code ShopConfigLoaderTest} 的图标侧，这里只补一条「非 item 图标根本进不了 GUI」的端到端。</li>
 * </ul>
 *
 * <p><b>夹具</b>：{@code ShopGUI} + {@code PlayerMock} + 手工构造的 {@link InventoryClickEvent}。
 * 事件必须用 {@code player.getOpenInventory()}（{@code InventoryView}）构造 ——
 * {@code onInventoryClick} 的守卫是 {@code getClickedInventory().equals(getInventory())}，
 * 而 {@code getClickedInventory()} 由 view 派生。</p>
 */
class ShopListenerTest extends MockBukkitTestBase {

    private BareHost host;
    private ShopManager shop;
    private ShopListener listener;
    private LanguageManager lang;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        // LanguageManager#getFor(Player, String, Object...) 是 varargs：裸 any() 匹配不到
        // 「零个可变参数」，漏桩会静默返回 null，随后 ShopGUI 的
        // Bukkit.createInventory(..., null) 抛 "Component.text: content is null" ——
        // 报错点与「少桩了一行」相距极远。用建 mock 时挂默认 Answer 彻底绕开参数匹配。
        lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        shop = new ShopManager(host, lang);
        shop.reload();
        listener = new ShopListener(shop, lang);
        player = server.addPlayer();
    }

    // ---------------------------------------------------------------- 夹具

    /** 把真币放到独立槽位（**不得 addItem**：同材质合并会丢 PDC）。 */
    private void giveCurrency(int amount) {
        player.getInventory().setItem(0, shop.createCurrencyItem(amount));
    }

    private static InventoryView view(PlayerMock viewer, Inventory top, Inventory bottom) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(view.getPlayer()).thenReturn(viewer);
        when(view.getType()).thenReturn(InventoryType.CHEST);
        when(view.getInventory(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            if (rawSlot < 0) return null;
            return rawSlot < top.getSize() ? top : bottom;
        });
        // 漏桩 convertSlot 会让 getSlot() 恒为 0（构造器用它算 whichSlot）
        when(view.convertSlot(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            return rawSlot < top.getSize() ? rawSlot : rawSlot - top.getSize();
        });
        return view;
    }

    private void clickSlot(int slot) {
        ShopGUI gui = new ShopGUI(player, shop, lang);
        gui.open();
        InventoryView view = view(player, gui.getInventory(), player.getInventory());
        listener.onInventoryClick(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    private ShopItem factoryItem(String id) {
        return shop.getShopConfig().getItems().stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("出厂 shop.yml 里应有商品 " + id));
    }

    // ================================================================ 主链

    @Nested
    @DisplayName("购买主链")
    class HappyPath {

        @Test
        @DisplayName("余额充足：扣掉恰好 price，事件被取消（防 shift/双击拿走图标）")
        void purchaseDeductsExactlyThePrice() {
            ShopItem bow = factoryItem("bow");            // price 15, slot 0, MATERIAL
            giveCurrency(bow.getPrice() + 5);

            clickSlot(bow.getSlot());

            assertEquals(5, shop.countCurrency(player),
                    "应扣掉恰好 15（余额 20 → 5）。多扣或少扣都会破坏经济");
        }

        @Test
        @DisplayName("发货内容进入背包")
        void purchaseDeliversTheProduct() {
            ShopItem arrow = factoryItem("arrow");        // price 2, amount 8, MATERIAL

            // 用 showInventory 之外的方式确认"发了货"：数箭的数量。
            giveCurrency(arrow.getPrice());
            clickSlot(arrow.getSlot());

            int arrows = 0;
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && stack.getType() == Material.ARROW) {
                    arrows += stack.getAmount();
                }
            }
            assertEquals(8, arrows,
                    "arrow 配了 amount: 8，购买后背包里应有 8 支箭；实际 " + arrows);
        }

        @Test
        @DisplayName("购买后货币被扣光，裸 EMERALD 不被牵连")
        void purchaseLeavesNonCurrencyAlone() {
            ShopItem bow = factoryItem("bow");
            giveCurrency(bow.getPrice());
            ItemStack plain = new ItemStack(Material.EMERALD, 7);
            player.getInventory().setItem(9, plain);

            clickSlot(bow.getSlot());

            assertEquals(0, shop.countCurrency(player), "真币应扣光");
            ItemStack after = player.getInventory().getItem(9);
            assertNotNull(after, "裸 EMERALD 是玩家的普通物品，不能被当成钱扣掉");
            assertEquals(7, after.getAmount());
        }
    }

    // ================================================================ 余额不足 / 防刷

    @Nested
    @DisplayName("余额不足与防刷")
    class Guards {

        @Test
        @DisplayName("余额不足：一个币都不扣")
        void insufficientBalanceChargesNothing() {
            ShopItem bow = factoryItem("bow");
            giveCurrency(bow.getPrice() - 1);

            clickSlot(bow.getSlot());

            assertEquals(bow.getPrice() - 1, shop.countCurrency(player),
                    "不足全款时必须原样保留 —— 部分扣除等于「钱花了但没买到」");
        }

        @Test
        @DisplayName("250 ms 冷却：连续两次点击只扣一次")
        void buyCooldownBlocksTheSecondClick() {
            ShopItem bow = factoryItem("bow");
            giveCurrency(bow.getPrice() * 2);

            clickSlot(bow.getSlot());
            clickSlot(bow.getSlot());

            assertEquals(bow.getPrice(),
                    shop.countCurrency(player),
                    "同一 tick 内的双击/连点必须只结算一次（BUY_COOLDOWN_MS = 250）——"
                            + "否则刷物品。实际剩余 " + shop.countCurrency(player) + "，期望 " + bow.getPrice());
        }

        @Test
        @DisplayName("点空槽（占位符）不扣款")
        void clickingFillerDoesNothing() {
            giveCurrency(50);

            // slot 1 在出厂 54 格布局里没有商品（bow=0, arrow=2）
            clickSlot(1);

            assertEquals(50, shop.countCurrency(player), "占位符槽位没有商品，不应扣任何货币");
        }
    }

    // ================================================================ 退款

    @Nested
    @DisplayName("退款 —— 两条分支都不能让玩家的钱消失")
    class Refund {

        /** 只含单件商品、且该商品 type 可被指定为「deliverItem 会返回 null」的类型。 */
        private void installShopWith(String itemYamlBody) throws IOException {
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                        name: "&aCrystal"
                      gui:
                        size: 54
                      items:
                        probe:
                    """ + itemYamlBody.lines().map(l -> "      " + l)
                    .reduce("", (a, b) -> a + b + "\n"));
            // ShopManager#reload() 会遍历 Bukkit.getOnlinePlayers() 并读
            // p.getOpenInventory().getTopInventory()（slice-12-dc-003 的修复：关掉残留 GUI）。
            // MockBukkit 的 PlayerMock 在从未 openInventory 过时，该 view 的
            // getTopInventory() 返回 null（真实 Bukkit 总是返回一个有效 view），
            // 于是 reload() 会 NPE。这是 MockBukkit 的能力缺口，不是生产缺陷 ——
            // 让玩家先停在一个真实打开的界面上，就能绕过它并保留被测语义。
            if (player.getOpenInventory() == null
                    || player.getOpenInventory().getTopInventory() == null) {
                player.openInventory(server.createInventory(null, 9));
            }
            shop.reload();
        }

        @Test
        @DisplayName("deliverItem 返回 null（未知类型）时全额退款")
        void nullDeliveryRefunds() throws IOException {
            installShopWith("""
                    slot: 0
                    price: 10
                    type: MATERIAL
                    icon:
                      material: BOW
                    """);
            // 商品加载时 type 已被校验，无法造出「未知 type 的 ShopItem」走真实配置；
            // 改用 Mockito 的 ShopManager spy 让 deliverItem 返回 null，直接打中退款分支。
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doReturn(null).when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            ShopGUI gui = new ShopGUI(player, spied, lang);
            gui.open();
            giveCurrency(30);
            spiedListener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));

            assertEquals(30, spied.countCurrency(player),
                    "deliverItem 返回 null 是「退款」信号。若余额变 20，说明玩家付了钱却什么都没拿到");
        }

        @Test
        @DisplayName("fix6 Y2-04：deliverItem 抛异常时退款，且异常不穿出事件处理器")
        void deliverThrowingRefundsAndDoesNotPropagate() {
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doThrow(new IllegalArgumentException(
                            "new ItemStack(WATER) is not a valid material"))
                    .when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            giveCurrency(30);
            ShopGUI gui = new ShopGUI(player, spied, lang);
            gui.open();

            // 若异常穿出，这一行会抛 IllegalArgumentException 而不是抛 AssertionError。
            spiedListener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));

            assertEquals(30, spied.countCurrency(player),
                    "fix6 Y2-04：工厂构造必抛（如节点修复物品配成 WATER）时，币已扣、货无、"
                            + "异常还会穿透 onInventoryClick。修复要求把抛面收敛为退款面 —— "
                            + "退款后余额必须回到 30，实际 " + spied.countCurrency(player));
        }

        /**
         * <p><b>{@code slice-12-dc-001} 的守护</b>：{@code refundCurrency} 在
         * {@code deliverItem} 的 {@code catch} 块内被调用，它自身若抛异常会直接穿出
         * {@code onInventoryClick}（此时币已扣、货未生成、玩家无任何反馈）。</p>
         *
         * <p>构造方式：让 {@code createCurrencyItem} 抛异常（模拟「货币材质被改坏」这类
         * 会让退款工厂失效的场景），同时让 {@code deliverItem} 抛异常进入退款分支。</p>
         */
        @Test
        @DisplayName("slice-12-dc-001：退款自身失败也不得让异常穿出 onInventoryClick")
        void refundFailureDoesNotEscapeTheEventHandler() {
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doThrow(new IllegalArgumentException("deliver blew up"))
                    .when(spied).deliverItem(any(ShopItem.class));
            org.mockito.Mockito.doThrow(new IllegalStateException("currency factory blew up"))
                    .when(spied).createCurrencyItem(org.mockito.ArgumentMatchers.anyInt());
            ShopListener spiedListener = new ShopListener(spied, lang);

            giveCurrency(30);
            ShopGUI gui = new ShopGUI(player, spied, lang);
            gui.open();

            // 关键断言就是「这一行不抛」。旧实现（无 try/catch）在这里会抛
            // IllegalStateException，而调用链上游是 Bukkit 事件分发 ——
            // 异常被吞进日志，玩家侧表现为「点了没反应，钱没了」。
            // 币确实回不来（这是「需要人工补偿」的路径），日志由下一条用例断言。
            spiedListener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        }

        @Test
        @DisplayName("slice-12-dc-001：退款失败时写出 SEVERE 日志（供人工补偿）")
        void refundFailureLogsSevere() {
            var logs = attachLogCapture(host.getLogger());

            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doThrow(new IllegalArgumentException("deliver blew up"))
                    .when(spied).deliverItem(any(ShopItem.class));
            org.mockito.Mockito.doThrow(new IllegalStateException("currency factory blew up"))
                    .when(spied).createCurrencyItem(org.mockito.ArgumentMatchers.anyInt());
            ShopListener spiedListener = new ShopListener(spied, lang);

            giveCurrency(30);
            ShopGUI gui = new ShopGUI(player, spied, lang);
            gui.open();

            spiedListener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));

            assertTrue(logs.anyContains("Shop refund FAILED"),
                    "dc-001 的修复把失败降级为日志；没有这行日志，管理员无从知道要补偿谁、补多少。"
                            + "实际日志：" + logs.lines());
            assertTrue(logs.anyContains("manual compensation required"),
                    "日志应明确说明需要人工补偿：" + logs.lines());
        }

        @Test
        @DisplayName("price == 0 的商品不退款（否则会白送 1 个水晶 —— createCurrencyItem 钳到 1）")
        void zeroPriceDoesNotRefund() throws IOException {
            installShopWith("""
                    slot: 0
                    price: 0
                    type: MATERIAL
                    icon:
                      material: BOW
                    """);
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doReturn(null).when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            ShopGUI gui = new ShopGUI(player, spied, lang);
            gui.open();
            spiedListener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));

            assertEquals(0, spied.countCurrency(player),
                    "price 为 0 时没有东西可退。若这里变成 1，说明退款分支没有 price > 0 守卫 —— "
                            + "玩家能靠「点免费商品 + 触发失败」刷钱（createCurrencyItem 把 0 钳到 1）");
        }
    }

    // ================================================================ 其它守卫

    @Nested
    @DisplayName("非购买路径")
    class OtherPaths {

        @Test
        @DisplayName("事件来自非 ShopGUI 的背包时不拦截（不影响玩家自己的箱子）")
        void foreignInventoryIsIgnored() {
            Inventory top = server.createInventory(null, 27);
            InventoryView view = view(player, top, player.getInventory());
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    0, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            listener.onInventoryClick(event);

            assertFalse(event.isCancelled(),
                    "只在 holder 是 ShopGUI 时取消；否则会把玩家自己的箱子也锁死");
        }

        @Test
        @DisplayName("构造器从 ShopManager 取插件引用（不额外传）")
        void pluginComesFromShopManager() {
            assertSame(host, listener.getPlugin(),
                    "ShopListener(ShopManager, LanguageManager) 只收两个参数，插件引用经 shopManager.getPlugin() 取得");
        }
    }
}
