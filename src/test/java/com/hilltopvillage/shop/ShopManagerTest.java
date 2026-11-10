package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ShopManager} 的货币语义 —— 「哪些东西算钱、怎么扣、扣多少」。
 *
 * <p><b>为什么这批断言值得写</b>：货币系统有两条<b>互相冲突</b>的历史缺陷，各自的修复方向正好相反：</p>
 * <ul>
 *   <li><b>P0-A / P2-27：只认 PDC</b>。旧实现用「材质 + 显示名回退」判断货币，于是玩家把任意方块
 *       用铁砧 / 命名牌改名成「能量水晶」就能当钱花（假币无限付款）。修复是<b>删掉材质判定</b>，
 *       只认 {@code hilltopvillage:shop-currency} 这个 PDC 标记。见 {@link Counting#plainEmeraldIsNotCurrency}。</li>
 *   <li><b>P2-26：入参钳制</b>。{@code new ItemStack(Material, n)} 对 {@code n <= 0} 直接抛
 *       {@code IllegalArgumentException}，而这条工厂同时服务「击杀掉币」与「购买退款」——
 *       服主把掉币区间配成 0 会让每次击杀都在死亡事件里抛异常。修复是统一兜底为至少 1。
 *       见 {@link Factory#amountIsClampedToAtLeastOne}。</li>
 *   <li><b>dc-005：不按单叠上限裁剪</b>。同一工厂先前被"顺手"加过 {@code Math.min(n, maxStack)}，
 *       于是退款与 {@code /hilltop shop give}（上限 6400）会真的少发币。修复是只打 warning、
 *       由 {@code addItem} 自己拆叠。见 {@link Factory#amountAboveMaxStackIsNotTrimmed}。</li>
 * </ul>
 *
 * <p><b>⚠️ MockBukkit 陷阱（会让整类测试得出荒诞结果）</b>：{@code InventoryMock#addItem()}
 * 会按「材质相同」合并堆叠，而 <b>合并时不保留 PDC</b>。实测：真币 4 + 裸 EMERALD 10 经
 * {@code addItem} 后变成「EMERALD×14 且无 PDC」，于是 {@code countCurrency()} 返回 0。
 * <b>本类一律用 {@code setItem(slot, stack)} 放置货币</b>，绝不 {@code addItem}。</p>
 */
class ShopManagerTest extends MockBukkitTestBase {

    private BareHost host;
    private ShopManager shop;
    private LanguageManager lang;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        // 必须用「建 mock 时挂默认 Answer」，不能逐条 when()：LanguageManager 的
        // get / getFor 都是 varargs 重载，裸 any() 在「零个可变参数」时不命中并静默返回 null。
        // 后果是 ShopGUI 的 Bukkit.createInventory(..., null) 抛
        // "Component.text: content is null" —— 报错点与「少桩了一行」相距极远。
        lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        shop = new ShopManager(host, lang);
        shop.reload();
    }

    /** 真币：走生产工厂，因此必然带 PDC。 */
    private ItemStack currency(int amount) {
        return shop.createCurrencyItem(amount);
    }

    /** 假币：同材质、同显示名，但<b>没有</b> PDC 标记 —— 玩家能自己造出来的东西。 */
    private ItemStack fakeEmerald(int amount) {
        ItemStack item = new ItemStack(Material.EMERALD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Crystal");
        item.setItemMeta(meta);
        return item;
    }

    // ================================================================ 货币工厂

    @Nested
    @DisplayName("createCurrencyItem")
    class Factory {

        @Test
        @DisplayName("P2-26：amount == 0 钳到 1（否则 new ItemStack 抛异常）")
        void zeroAmountIsClampedToOne() {
            ItemStack item = currency(0);
            assertEquals(1, item.getAmount(),
                    "ItemStack(Material, 0) 会抛 IllegalArgumentException；"
                            + "退款分支与击杀掉币都走这条工厂，必须兜底为 1");
        }

        @Test
        @DisplayName("P2-26：负 amount 同样钳到 1")
        void negativeAmountIsClampedToOne() {
            assertEquals(1, currency(-5).getAmount(),
                    "负数量同样会让 ItemStack 构造抛异常（P2-26 的原始触发形态）");
        }

        @Test
        @DisplayName("dc-005：超过单叠上限时不裁剪（按请求量发放）")
        void amountAboveMaxStackIsNotTrimmed() {
            int maxStack = Material.EMERALD.getMaxStackSize();
            int requested = maxStack + 100;

            ItemStack item = currency(requested);

            assertEquals(requested, item.getAmount(),
                    "dc-005：若这里被 Math.min 裁到 " + maxStack + "，退款与 /hilltop shop give（上限 6400）"
                            + "会真的少发币 —— CraftInventory#addItem 会自行按 getMaxStackSize() 拆到多个槽位");
        }

        @Test
        @DisplayName("超过单叠上限时会打 warning（不是静默放行）")
        void amountAboveMaxStackWarns() {
            var logs = attachLogCapture(host.getLogger());
            int maxStack = Material.EMERALD.getMaxStackSize();

            currency(maxStack + 1);

            assertTrue(logs.anyContains("exceeds max stack"),
                    "dc-005 的要求是「不裁剪但要暴露成日志」，否则管理员看到拆叠/溢出时无从判断是配置还是缺陷："
                            + logs.lines());
        }

        @Test
        @DisplayName("未超上限时不打 warning（控制半：证明上面那条不是恒真）")
        void amountWithinMaxStackDoesNotWarn() {
            var logs = attachLogCapture(host.getLogger());

            currency(5);

            assertFalse(logs.anyContains("exceeds max stack"),
                    "正常数量不应告警，否则告警会被噪声淹掉：" + logs.lines());
        }

        @Test
        @DisplayName("货币名优先取 shop.yml 直配值（含颜色码翻译）")
        void displayNameUsesConfiguredName() {
            ItemStack item = currency(1);

            assertEquals("§a能量水晶", item.getItemMeta().getDisplayName(),
                    "出厂 shop.yml 配的是 '&a能量水晶'；颜色码必须被 translateAlternateColorCodes 翻译");
        }

        @Test
        @DisplayName("货币物品带 PDC 标记 —— 这是它成为「钱」的唯一依据")
        void currencyCarriesThePdcMarker() {
            ItemStack item = currency(1);

            assertNotNull(item.getItemMeta());
            assertEquals("1", item.getItemMeta().getPersistentDataContainer()
                            .get(new org.bukkit.NamespacedKey(host, "shop-currency"), PersistentDataType.STRING),
                    "P0-A 的修复让 PDC 成为唯一权威。若这里取不到标记，"
                            + "countCurrency 会把所有真币都当成 0，整条购买链失效");
        }
    }

    // ================================================================ PDC-only

    @Nested
    @DisplayName("countCurrency —— 只认 PDC")
    class Counting {

        @Test
        @DisplayName("P0-A：裸 EMERALD（无 PDC）计数为 0 —— 材质相同也不算钱")
        void plainEmeraldIsNotCurrency() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, fakeEmerald(10));

            assertEquals(0, shop.countCurrency(player),
                    "P0-A：旧实现用「材质 + 显示名回退」判定，玩家把方块改名成货币名就能当钱花。"
                            + "本断言钉住「材质与名称都不是钱，只有 PDC 是」");
        }

        @Test
        @DisplayName("只认 PDC 的证明：同材质是真币、不同材质也是真币（材质已不参与判定）")
        void currencyIsRecognizedRegardlessOfMaterial() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(4));

            assertEquals(4, shop.countCurrency(player),
                    "真币由 createCurrencyItem 发放，必带 PDC，应被计入");

            // 换一种"材质"其实换不了（配置已加载），这里改为构造一个非 EMERALD 材质但带 PDC 的物品，
            // 用来证明判定确实不看材质。
            ItemStack foreign = new ItemStack(Material.DIAMOND, 6);
            ItemMeta meta = foreign.getItemMeta();
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(host, "shop-currency"), PersistentDataType.STRING, "1");
            foreign.setItemMeta(meta);
            player.getInventory().setItem(1, foreign);

            assertEquals(10, shop.countCurrency(player),
                    "P2-27：判定已不含材质条件。若这里只加到 4，说明有人把材质检查加回去了 ——"
                            + "那会让服主 shop reload 换材质后所有存量真币立即作废");
        }

        @Test
        @DisplayName("多个槽位累加（不是只数第一个格子）")
        void multipleSlotsAreSummed() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(3));
            player.getInventory().setItem(7, currency(5));
            player.getInventory().setItem(20, currency(2));

            assertEquals(10, shop.countCurrency(player));
        }

        @Test
        @DisplayName("P2-28：副手计入统计")
        void offHandIsCounted() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItemInOffHand(currency(3));

            assertEquals(3, shop.countCurrency(player),
                    "getStorageContents() 不含副手槽；玩家按 F 换到副手的货币"
                            + "会从余额里「消失」（既不计余额也无法支付）");
        }

        @Test
        @DisplayName("空背包计数为 0 且不抛异常")
        void emptyInventoryIsZero() {
            assertEquals(0, shop.countCurrency(server.addPlayer()));
        }

        @Test
        @DisplayName("副手是裸 EMERALD 时不计入（PDC 判定对副手同样生效）")
        void offHandFakeCurrencyNotCounted() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItemInOffHand(fakeEmerald(8));

            assertEquals(0, shop.countCurrency(player));
        }
    }

    // ================================================================ 扣除

    @Nested
    @DisplayName("removeCurrency")
    class Removing {

        @Test
        @DisplayName("余额充足时精确扣除请求量")
        void removesExactAmount() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(10));

            assertTrue(shop.removeCurrency(player, 4));
            assertEquals(6, shop.countCurrency(player));
        }

        @Test
        @DisplayName("余额不足时<b>不做部分扣除</b>，直接返回 false")
        void insufficientBalanceRemovesNothing() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(3));

            assertFalse(shop.removeCurrency(player, 10),
                    "玩家凑不出全款时必须原样保留，不能扣成 0 —— 否则会「钱花了但没买到」");
            assertEquals(3, shop.countCurrency(player),
                    "失败路径不得留下任何部分扣除");
        }

        @Test
        @DisplayName("只扣真币，裸 EMERALD 一格不动（P2-27 的写侧）")
        void onlyRealCurrencyIsTaken() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(4));
            player.getInventory().setItem(5, fakeEmerald(10));

            assertTrue(shop.removeCurrency(player, 4));

            assertEquals(0, shop.countCurrency(player), "真币应被扣光");
            ItemStack untouched = player.getInventory().getItem(5);
            assertNotNull(untouched, "裸 EMERALD 是玩家的普通物品，绝不能被当成钱扣掉");
            assertEquals(Material.EMERALD, untouched.getType());
            assertEquals(10, untouched.getAmount(), "假币格子必须原封不动");
        }

        @Test
        @DisplayName("amount <= 0 直接返回 true 且不扣任何东西（免费路径）")
        void nonPositiveAmountIsANoOp() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(9));

            assertTrue(shop.removeCurrency(player, 0));
            assertTrue(shop.removeCurrency(player, -3),
                    "price <= 0 的免费商品不应被扣款 —— ShopListener 依赖这个返回 true");
            assertEquals(9, shop.countCurrency(player));
        }

        @Test
        @DisplayName("P2-28 写侧：主背包不够时从副手补扣，并写回 null")
        void offHandIsUsedWhenMainInventoryRunsOut() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(2));
            player.getInventory().setItemInOffHand(currency(5));

            assertTrue(shop.removeCurrency(player, 7),
                    "主背包 2 + 副手 5 = 7，应恰好够");
            assertEquals(0, shop.countCurrency(player),
                    "读写口径必须同源：countCurrency 数副手，removeCurrency 也必须能扣副手，"
                            + "否则玩家会看到「余额够但付不了」");
            ItemStack offHand = player.getInventory().getItemInOffHand();
            assertTrue(offHand == null || offHand.getType() == Material.AIR,
                    "副手被扣光后必须置空，不能留下 amount 0 的幽灵物品；实际：" + offHand);
        }

        @Test
        @DisplayName("扣光整个格子时把槽位置为 null（不留 amount==0 的物品）")
        void emptiedSlotBecomesNull() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(3, currency(5));

            assertTrue(shop.removeCurrency(player, 5));

            ItemStack left = player.getInventory().getItem(3);
            assertTrue(left == null || left.getType() == Material.AIR,
                    "amount 归零的 ItemStack 会让「空中仍有一个物品」这类判断出错；实际：" + left);
        }

        @Test
        @DisplayName("跨多个格子扣除时逐格递减")
        void removesAcrossSlots() {
            PlayerMock player = server.addPlayer();
            player.getInventory().setItem(0, currency(3));
            player.getInventory().setItem(1, currency(4));

            assertTrue(shop.removeCurrency(player, 5));

            assertEquals(2, shop.countCurrency(player),
                    "3 + 4 = 7，扣 5 后应剩 2，且分布在两格之间（不是把一格扣成负数）");
        }
    }

    // ================================================================ 配置生命周期

    @Nested
    @DisplayName("配置生命周期")
    class Lifecycle {

        @Test
        @DisplayName("reload() 后 getShopConfig() 非空且含出厂商品")
        void reloadPopulatesConfig() {
            assertNotNull(shop.getShopConfig(), "reload 之后必须有配置快照");
            assertEquals(16, shop.getShopConfig().getItems().size());
        }

        @Test
        @DisplayName("getShopConfig() 在 reload 之前是 null（构造器不加载）")
        void configIsNullBeforeFirstReload() {
            ShopManager fresh = new ShopManager(host, lang);
            assertNull(fresh.getShopConfig(),
                    "构造器只装配，不读盘 —— 否则启动期会在无世界条件下触发 saveResource 之外的路径");
        }

        @Test
        @DisplayName("reload() 换掉配置实例（不是原地改旧对象）")
        void reloadReplacesTheConfigInstance() {
            ShopConfig first = shop.getShopConfig();
            shop.reload();
            ShopConfig second = shop.getShopConfig();

            assertNotNull(second);
            // 同一份 shop.yml 内容会产出一个新的 ShopConfig 实例；dc-003 的修复依赖
            // 「reload 会关掉已开的 ShopGUI」而不是「旧 GUI 会看到新配置」。
            assertTrue(first != second,
                    "reload 必须重建快照 —— 若复用同一实例，已打开的 ShopGUI 里固化的 bySlot 就无从区分新旧");
        }

        @Test
        @DisplayName("getNpcManager() 在 reload 前后保持同一实例（实体引用稳定）")
        void npcManagerSurvivesReload() {
            NpcManager before = shop.getNpcManager();
            shop.reload();
            assertSame(before, shop.getNpcManager(),
                    "reload 不重建 NpcManager —— 重建会让当前存活的商店村民实体失去引用（无法 despawn）");
        }

        @Test
        @DisplayName("getPlugin() 返回构造时传入的宿主")
        void pluginIsExposed() {
            assertSame(host, shop.getPlugin());
        }

        /**
         * <p><b>{@code slice-12-dc-003}</b>：已打开的 {@code ShopGUI} 在构造期<b>固化</b>了当时的
         * {@code ShopConfig} 和 {@code bySlot}。reload 若不换掉它们，玩家会继续按住店价成交 ——
         * 改价后「刷新了但没生效」，而且完全不可见。</p>
         *
         * <p>修复口径是「关掉所有残留 GUI，强制重开以拾取新配置」，而不是「让旧 GUI 读到新配置」
         * （后者做不到：{@code bySlot} 是构造期快照，且槽位映射可能整个变了）。</p>
         */
        @Test
        @DisplayName("slice-12-dc-003：reload() 关掉已打开的 ShopGUI")
        void reloadClosesOpenShopGui() {
            PlayerMock player = server.addPlayer();
            ShopGUI gui = new ShopGUI(player, shop, lang);
            gui.open();
            assertTrue(topHolder(player) instanceof ShopGUI,
                    "前置条件：玩家当前确实停在商店 GUI 上");

            shop.reload();

            assertFalse(topHolder(player) instanceof ShopGUI,
                    "reload 必须关闭残留的 ShopGUI —— 否则玩家继续按 reload 前的旧价成交"
                            + "（ShopGUI 在构造期固化了 ShopConfig 与 bySlot，不会自己刷新）");
        }

        /**
         * {@code PlayerMock#closeInventory()} 之后 {@code getOpenInventory().getTopInventory()}
         * 返回 <b>null</b>（实测），而真实 Bukkit 会返回一个空 view。
         * 直接链式调 {@code .getHolder()} 会在「已经被关掉」这个正确结果上抛 NPE。
         */
        private Object topHolder(PlayerMock player) {
            if (player.getOpenInventory() == null) return null;
            var top = player.getOpenInventory().getTopInventory();
            return top == null ? null : top.getHolder();
        }

        @Test
        @DisplayName("slice-12-dc-003 控制半：reload() 不关掉非商店界面（不误伤玩家箱子）")
        void reloadDoesNotCloseForeignInventories() {
            PlayerMock player = server.addPlayer();
            org.bukkit.inventory.Inventory chest = server.createInventory(null, 27);
            player.openInventory(chest);
            org.bukkit.inventory.Inventory before = player.getOpenInventory().getTopInventory();
            assertSame(chest, before, "前置条件：玩家已打开一个普通箱子");

            shop.reload();

            assertSame(before, player.getOpenInventory().getTopInventory(),
                    "reload 只应关闭 holder 是 ShopGUI 的界面；把玩家正在用的箱子也弹掉是越权");
        }
    }

    // ================================================================ 发货

    @Nested
    @DisplayName("deliverItem —— 只用 MATERIAL / EQUIPMENT（其余要全量插件）")
    class Delivery {

        private ShopItem item(String type, Material icon, int amount, String name,
                              List<String> lore, boolean unbreakable) {
            return new ShopItem("probe", 0, 1, type, amount, icon, name, lore, unbreakable);
        }

        @Test
        @DisplayName("MATERIAL：按 amount 发放图标材质")
        void materialDeliveryUsesAmount() {
            ItemStack delivered = shop.deliverItem(item("MATERIAL", Material.ARROW, 8, null, null, false));

            assertNotNull(delivered);
            assertEquals(Material.ARROW, delivered.getType());
            assertEquals(8, delivered.getAmount(), "MATERIAL 的 amount 就是发放数量");
        }

        @Test
        @DisplayName("MATERIAL：amount <= 0 钳到 1（不抛异常）")
        void materialDeliveryClampsAmount() {
            ItemStack delivered = shop.deliverItem(item("MATERIAL", Material.ARROW, 0, null, null, false));

            assertNotNull(delivered, "amount 0 会让 new ItemStack 抛异常，必须钳到 1 而不是让购买链炸掉");
            assertEquals(1, delivered.getAmount());
        }

        @Test
        @DisplayName("EQUIPMENT：固定 1 个，name / lore 写进发货物（颜色码翻译）")
        void equipmentDeliveryCarriesNameAndLore() {
            ItemStack delivered = shop.deliverItem(item("EQUIPMENT", Material.DIAMOND_SWORD, 99,
                    "&bDiamond Blade", List.of("&7line one"), false));

            assertNotNull(delivered);
            assertEquals(1, delivered.getAmount(),
                    "EQUIPMENT 固定发 1 件（装备不叠），忽略 amount: 99");
            ItemMeta meta = delivered.getItemMeta();
            assertEquals("§bDiamond Blade", meta.getDisplayName(),
                    "EQUIPMENT 的 name 会应用到玩家手里的实体，必须翻译颜色码");
            assertEquals(List.of("§7line one"), meta.getLore(),
                    "lore 同样写入发货物（GUI 里追加的价格行由 ShopGUI 单独拼，不进这里）");
            assertFalse(meta.isUnbreakable(), "未配 unbreakable 时应为普通耐久");
        }

        @Test
        @DisplayName("EQUIPMENT：unbreakable 标记生效")
        void equipmentCanBeUnbreakable() {
            ItemStack delivered = shop.deliverItem(item("EQUIPMENT", Material.DIAMOND_CHESTPLATE, 1,
                    null, null, true));

            assertNotNull(delivered);
            assertTrue(delivered.getItemMeta().isUnbreakable(),
                    "unbreakable: true 必须真的写进 ItemMeta，否则服主配了没效果");
        }

        @Test
        @DisplayName("EQUIPMENT：name / lore 为 null 时保留原版外观，不抛 NPE")
        void equipmentWithNoNameKeepsVanillaLook() {
            ItemStack delivered = shop.deliverItem(item("EQUIPMENT", Material.IRON_HELMET, 1, null, null, false));

            assertNotNull(delivered);
            assertEquals(Material.IRON_HELMET, delivered.getType());
            assertNull(delivered.getItemMeta().getLore(), "没配 lore 时不应凭空塞一个空 list");
        }

        @Test
        @DisplayName("null 商品返回 null（不抛 NPE）")
        void nullItemReturnsNull() {
            assertNull(shop.deliverItem(null),
                    "ShopListener 对 null 走退款分支；这里抛 NPE 会让货币被扣而货无");
        }

        @Test
        @DisplayName("未知 type 返回 null 并告警（ShopListener 据此退款）")
        void unknownTypeReturnsNull() {
            var logs = attachLogCapture(host.getLogger());

            assertNull(shop.deliverItem(item("BOGUS", Material.STONE, 1, null, null, false)),
                    "deliverItem 的 null 是「退款」信号；返回任何非 null 都会让玩家白拿一件错物");
            assertTrue(logs.anyContains("unknown item type 'BOGUS'"),
                    "未知类型必须告警并点名商品：" + logs.lines());
        }
    }
}
