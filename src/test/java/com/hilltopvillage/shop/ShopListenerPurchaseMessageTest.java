package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * F5：{@code shop.purchase-success} 的消息数量必须是「实际买到的数量」，
 * 而不是 {@code addItem} 合并后留在传入 {@code product} 上的余量。
 *
 * <p><b>缺陷机理</b>：{@code ShopListener#purchase} 先把 {@code product} 交给
 * {@code player.getInventory().addItem(product)}，再用 {@code product.getAmount()} 拼消息。
 * Paper 1.21.4 的 {@code CraftInventory#addItem} 在把商品并进已有的半满叠时，会<b>原地修改</b>
 * 调用方传入的 {@code ItemStack}，把它的数量改成没放进去的余量。于是「背包里已有 60 支箭、
 * 买 8 支」这种形态：4 支并进旧叠（60 → 64），4 支溢出，{@code product} 被改成 4，
 * 成功消息就报成 x4 —— 玩家实际到账 8 支（4 支在包里 + 4 支掉在脚下）。</p>
 *
 * <p><b>为什么不用 Mockito / PlayerMock</b>：本用例要钉的是
 * <i>ShopListener 在 addItem 之后读取 product 数量</i> 这个调用顺序缺陷，而不是某个测试框架
 * 是否复刻了 Paper 的原地修改语义。这里用 JDK 动态代理实现 {@code PlayerInventory#addItem}
 * 的桩，精确复刻 Paper 的行为（把传入 stack 的数量改成余量 4，并把余量放进返回的
 * overflow map）；Player / World / Item / PDC 也只实现本流程真正会走到的方法。
 * 这样既不依赖 MockBukkit 的 addItem 实现细节，也不受本机 Mockito inline mock maker
 * 无法 self-attach 的限制。</p>
 *
 * <p><b>红测判据</b>：未修复代码在 addItem 之后读 {@code product.getAmount()} → 捕获到 "4"，
 * 断言期望 "8" 失败；修复后在调用 addItem 之前捕获数量 → 捕获到 "8"，断言通过。</p>
 */
class ShopListenerPurchaseMessageTest extends MockBukkitTestBase {

    /** 出厂 arrow 一次给 8 支；本用例只关心数量语义，价格取 2。 */
    private static final int PURCHASED_AMOUNT = 8;
    /** 模拟「旧叠只能再并进 4 支」后剩下的余量。 */
    private static final int POST_MERGE_REMAINDER = 4;
    private static final int PRICE = 2;

    @Test
    @DisplayName("成功消息报购买量 8，而不是 addItem 合并后的余量 4")
    void successMessageReportsPurchasedAmountNotPostMergeRemainder() {
        BareHost host = loadBareHost();
        ShopItem item = new ShopItem("arrow", 2, PRICE, "MATERIAL",
                PURCHASED_AMOUNT, Material.ARROW, null, List.of(), false);
        ItemStack product = new ItemStack(Material.ARROW, PURCHASED_AMOUNT);

        PlayerInventory bag = proxy(PlayerInventory.class, (proxy, method, args) -> {
            if ("addItem".equals(method.getName())) {
                ItemStack[] passed = (ItemStack[]) args[0];
                assertEquals(1, passed.length, "夹具前提：purchase 每次只投递一个 ItemStack");
                ItemStack stack = passed[0];
                assertEquals(PURCHASED_AMOUNT, stack.getAmount(),
                        "夹具前提：addItem 收到的是完整购买量 " + PURCHASED_AMOUNT);
                // Paper 1.21.4 CraftInventory#addItem 的原地修改：把传入 stack 的数量改成余量，
                // 并把该余量放进 overflow（4 支落地）。这是本用例显式复刻的目标语义。
                stack.setAmount(POST_MERGE_REMAINDER);
                Map<Integer, ItemStack> overflow = new LinkedHashMap<>();
                overflow.put(0, stack);
                return overflow;
            }
            return defaultValue(method.getReturnType());
        });

        PersistentDataContainer pdc = proxy(PersistentDataContainer.class,
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Item dropped = proxy(Item.class, (proxy, method, args) -> {
            if ("getPersistentDataContainer".equals(method.getName())) return pdc;
            return defaultValue(method.getReturnType());
        });
        World world = proxy(World.class, (proxy, method, args) -> {
            if ("dropItemNaturally".equals(method.getName())) return dropped;
            return defaultValue(method.getReturnType());
        });
        List<String> sentMessages = new ArrayList<>();
        Player player = proxy(Player.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getInventory":
                    return bag;
                case "getWorld":
                    return world;
                case "getLocation":
                    // World#dropItemNaturally 的桩忽略坐标，不需要真实 Location。
                    return null;
                case "sendMessage":
                    if (args != null && args.length == 1 && args[0] instanceof String message) {
                        sentMessages.add(message);
                    }
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "F5-test-player";
                default:
                    return defaultValue(method.getReturnType());
            }
        });

        // 捕获 shop.purchase-success 的占位符实参：{0}=商品名、{1}=数量、{2}=价格。
        List<String> capturedAmounts = new ArrayList<>();
        LanguageManager lang = new LanguageManager(host) {
            @Override
            public String getFor(Player p, String key) {
                return "msg";
            }

            @Override
            public String getFor(Player p, String key, Object... args) {
                if ("shop.purchase-success".equals(key) && args != null && args.length >= 3) {
                    capturedAmounts.add(String.valueOf(args[1]));
                }
                return "msg";
            }
        };

        // 只覆盖 purchase 需要的三个协作方法；构造函数仍走真实的 ShopManager 初始化。
        ShopManager shop = new ShopManager(host, lang) {
            @Override
            public int countCurrency(Player p) {
                return 100;
            }

            @Override
            public boolean removeCurrency(Player p, int amount) {
                return true;
            }

            @Override
            public ItemStack deliverItem(ShopItem requested) {
                return product;
            }
        };
        ShopListener listener = new ShopListener(shop, lang);

        invokePurchase(listener, player, item);

        // 夹具自检：addItem 确实原地改写了 product，消息缺陷的触发条件成立。
        assertEquals(POST_MERGE_REMAINDER, product.getAmount(),
                "夹具自检：模拟的 Paper addItem 应把传入 product 改成余量 "
                        + POST_MERGE_REMAINDER + "，否则本用例没有复现缺陷形态");

        assertFalse(capturedAmounts.isEmpty(),
                "购买成功后必须发出 shop.purchase-success 消息（未捕获到说明流程提前返回）");
        assertEquals(List.of(String.valueOf(PURCHASED_AMOUNT)), capturedAmounts,
                "成功消息的数量必须是购买量 " + PURCHASED_AMOUNT + "，而不是 addItem 合并后的余量 "
                        + POST_MERGE_REMAINDER + "。实际捕获：" + capturedAmounts
                        + "（未修复代码在 addItem 之后读 product.getAmount()，会得到余量）");
        assertFalse(sentMessages.isEmpty(), "成功消息应通过 player.sendMessage 发出");
    }

    /** 走真实的 {@code ShopListener#purchase}（private，方法签名在修复前后不变）。 */
    private static void invokePurchase(ShopListener listener, Player player, ShopItem item) {
        try {
            Method purchase = ShopListener.class.getDeclaredMethod(
                    "purchase", Player.class, ShopItem.class);
            purchase.setAccessible(true);
            purchase.invoke(listener, player, item);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法调用 ShopListener#purchase："
                    + failure.getClass().getSimpleName() + " " + failure.getMessage(), failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
