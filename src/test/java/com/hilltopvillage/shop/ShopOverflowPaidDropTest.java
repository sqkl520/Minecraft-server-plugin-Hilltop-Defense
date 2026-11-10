package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
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
 * {@code slice-12-dc-004} 的回归网：<b>商店溢出掉落物不得被竞技场重置吞掉</b>。
 *
 * <p><b>缺陷原文</b>：{@code ShopListener} 在背包满时把购买 / 退款的溢出部分
 * {@code world.dropItemNaturally(...)} 掉在玩家脚下；而 {@code ArenaInstance.clearDroppedItems()}
 * 无差别删除竞技场世界里的<b>每一个</b> {@link Item} 实体。结算与重置之间只要落地一件
 * 已付对价的货，玩家就会「钱扣了、货没了」。</p>
 *
 * <p><b>修复的两半</b>（两半都独立可观测，故本类分三组钉住）：</p>
 * <ol>
 *   <li><b>打标侧</b>（{@link ShopListener}）：溢出掉落物带 {@link ShopListener#PAID_DROP_KEY}。
 *       见 {@link MarkingSide}。</li>
 *   <li><b>跳过侧</b>（{@code ArenaInstance.clearDroppedItems}）：带标记的 {@link Item} 存活，
 *       未带标记的仍被删除。见 {@link SkippingSide}。</li>
 *   <li><b>合成侧</b>：在真实竞技场世界里走完整「购买溢出 → 重置」链路。见 {@link EndToEnd}。</li>
 * </ol>
 *
 * <p><b>为什么不用 mock 的背包</b>：round2 判定「MockBukkit 下强制真溢出不可行」的前提
 * 经实测<b>不成立</b>——真实的 {@code addItem} 溢出可以复现，关键点是
 * {@code PlayerMock} 的背包是 <b>41 格</b>（36 主背包 + 4 护甲 + 1 副手），
 * 而 {@code getStorageContents()} 只报 36 格。按 36 格填满时 {@code addItem} 会落到
 * 编号 36–40 的空槽里，返回空 overflow —— 这正是 round2 卡住的地方。
 * 填满 41 格后 {@code addItem} 立刻返回真溢出，且材质不必相同、PDC 也不会被合并吞掉。</p>
 *
 * <p><b>溢出形态与真实 {@code CraftInventory} 的差异</b>：MockBukkit 的
 * {@code InventoryMock#addItem} 会把同材质栈合并，真实实现同样会合并；两者在「全满时
 * 直接返回原栈」这一点上行为一致，因此本类观察到的溢出形状是保真的。若将来某次升级把
 * 它改坏，{@link MarkingSide#purchaseOverflowDropsTheProduct()} 的前置断言会先红，
 * 不会静默变成「测了个空 drop」。</p>
 */
class ShopOverflowPaidDropTest extends MockBukkitTestBase {

    /** 41 = 36 主背包 + 4 护甲 + 1 副手；{@code addItem} 会用到全部 41 个槽位。 */
    private static final int MOCK_INVENTORY_SLOTS = 41;

    // ================================================================ 夹具

    /**
     * 与 {@code ShopListenerTest} 同款视图桩。
     *
     * <p>{@code getClickedInventory()} 由 view 派生，而 {@code onInventoryClick} 的守卫是
     * 「点击的背包等于事件背包」，所以必须真正回放 {@code getInventory(rawSlot)} 与
     * {@code convertSlot(rawSlot)} 两个语义，否则购买分支根本进不去。</p>
     */
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

    /** {@code LanguageManager#getFor} 是 varargs 重载，裸 {@code any()} 匹配不到零个可变参数。 */
    private static LanguageManager stubLanguage() {
        return mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /** 出厂 {@code shop.yml} 里按 id 取商品；取不到就断言失败（而不是静默跳过）。 */
    private static ShopItem factoryItem(ShopManager shop, String id) {
        return shop.getShopConfig().getItems().stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("出厂 shop.yml 里应有商品 " + id));
    }

    /** 把每个槽位都塞满不可合并的方块，使下一次 {@code addItem} 必然溢出。 */
    private static void fillEverySlot(PlayerMock player) {
        for (int i = 0; i < MOCK_INVENTORY_SLOTS; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
    }

    /** 世界里的掉落物按材质归类，便于在断言消息里直接点名。 */
    private static List<String> describeDrops(World world) {
        List<String> out = new ArrayList<>();
        for (Item item : world.getEntitiesByClass(Item.class)) {
            out.add(item.getItemStack().getType() + "x" + item.getItemStack().getAmount()
                    + (isPaidDrop(item) ? "[PAID]" : "[plain]"));
        }
        return out;
    }

    private static boolean isPaidDrop(Item item) {
        return item.getPersistentDataContainer()
                .has(ShopListener.PAID_DROP_KEY, PersistentDataType.BYTE);
    }

    // ================================================================ 打标侧

    /**
     * <b>打标侧</b>：{@link ShopListener} 发货 / 退款时落到地上的溢出栈必须带
     * {@link ShopListener#PAID_DROP_KEY}。
     *
     * <p>用 {@link BareHost}（{@code onEnable} 留空）即可——这两个分支只碰
     * {@code getWorld()} / {@code getLogger()} / {@code ShopManager}，不解引用任何 manager 链。</p>
     */
    @Nested
    @DisplayName("打标侧 —— 商店溢出的掉落物带 PAID_DROP_KEY")
    class MarkingSide {

        private BareHost host;
        private ShopManager shop;
        private ShopListener listener;
        private LanguageManager lang;
        private PlayerMock player;

        @BeforeEach
        void setUp() {
            host = loadBareHost();
            lang = stubLanguage();
            shop = new ShopManager(host, lang);
            shop.reload();
            listener = new ShopListener(shop, lang);
            player = server.addPlayer();
        }

        /** 点一次商店第 {@code slot} 号商品，走真实的 {@code onInventoryClick} 入口。 */
        private void clickShopSlot(ShopManager manager, ShopListener on, int slot) {
            ShopGUI gui = new ShopGUI(player, manager, lang);
            gui.open();
            on.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL));
        }

        @Test
        @DisplayName("购买溢出：掉落的货带 PAID_DROP_KEY（否则重置会把它删掉 = 钱花了没货）")
        void purchaseOverflowDropsTheProduct() {
            ShopItem bow = factoryItem(shop, "bow");
            fillEverySlot(player);
            // 货币只放在最后一个主背包槽（35）：removeCurrency 从后往前扣，扣完该槽仍非空，
            // 于是背包依旧全满，addItem(BOW) 必然溢出。多放 1 个保证扣完不为空。
            player.getInventory().setItem(35, shop.createCurrencyItem(bow.getPrice() + 1));

            clickShopSlot(shop, listener, bow.getSlot());

            // 前置断言：确认真溢出了。若 MockBukkit 升级后不再溢出，这条会先红，
            // 避免「掉 0 件 → 断言循环 0 次 → 假绿」。
            assertEquals(1, shop.countCurrency(player),
                    "前置条件：应扣掉恰好 " + bow.getPrice() + " 个货币（余额 " + (bow.getPrice() + 1)
                            + " → 1）。若这里不对，购买链根本没走到发货段");
            assertEquals(MOCK_INVENTORY_SLOTS, occupiedSlots(player),
                    "前置条件：扣款后背包必须仍然全满，否则 addItem 不会溢出，本用例什么都没测到");

            List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "背包全满时买 1 件 BOW 应恰好掉落 1 个 Item 实体，实际：" + describeDrops(player.getWorld()));
            Item dropped = drops.get(0);
            assertEquals(Material.BOW, dropped.getItemStack().getType(),
                    "掉在地上的应是购买的商品 BOW，实际：" + describeDrops(player.getWorld()));
            assertTrue(isPaidDrop(dropped),
                    "dc-004：商店溢出的掉落物必须带 " + ShopListener.PAID_DROP_KEY
                            + " 标记，否则 ArenaInstance.clearDroppedItems 会连它一起删掉 —— "
                            + "玩家付了钱却拿不到货。实际：" + describeDrops(player.getWorld()));
        }

        @Test
        @DisplayName("退款溢出：退回来的货币同样带 PAID_DROP_KEY（退款是第二个 drop 调用点）")
        void refundOverflowDropsTheCurrency() {
            ShopItem bow = factoryItem(shop, "bow");
            // 让 deliverItem 返回 null 走退款分支（与 ShopListenerTest 同款手法）。
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doReturn(null).when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            fillEverySlot(player);
            // 退款溢出需要「背包全满 + 余下的货币叠超过单叠上限」：addItem 若能并进原栈就不会溢出。
            // 超过上限的货币栈是受支持的存量状态（dc-005 明确不裁剪；/hilltop shop give 上限 6400）。
            int oversized = bow.getPrice() + 64 + 1;
            player.getInventory().setItem(35, spied.createCurrencyItem(oversized));
            assertTrue(oversized - bow.getPrice() > 64,
                    "前置条件：扣款后残留 " + (oversized - bow.getPrice())
                            + " 必须大于单叠上限 64，否则 addItem 会并回原栈而不产生溢出");

            clickShopSlot(spied, spiedListener, bow.getSlot());

            assertEquals(oversized - bow.getPrice(), spied.countCurrency(player),
                    "前置条件：退款后背包里的货币应是 " + (oversized - bow.getPrice())
                            + " —— 扣掉的 " + bow.getPrice() + " 个因背包放不下而落到地上，"
                            + "没有并回原槽（原槽 " + (oversized - bow.getPrice())
                            + " 已过单叠上限）。若这里不等于预期，说明 addItem 的溢出形态变了");
            List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "退款溢出应恰好掉落 1 件货币，实际：" + describeDrops(player.getWorld()));
            Item dropped = drops.get(0);
            assertEquals(Material.EMERALD, dropped.getItemStack().getType(),
                    "退款掉的应是货币 EMERALD，实际：" + describeDrops(player.getWorld()));
            assertTrue(isPaidDrop(dropped),
                    "dc-004：退款溢出走的是 ShopListener 的第二个 dropItemNaturally 调用点，"
                            + "同样必须打标，否则 dc-001 的补偿会在下次重置时静默蒸发。"
                            + "实际：" + describeDrops(player.getWorld()));
        }

        @Test
        @DisplayName("对照：背包有空间时不掉落任何东西（证明上面两条的 drop 确由溢出产生）")
        void noOverflowMeansNoDrop() {
            ShopItem bow = factoryItem(shop, "bow");
            // 只放货币，其余槽位全空 —— addItem 一定进得了背包。
            player.getInventory().setItem(0, shop.createCurrencyItem(bow.getPrice()));

            clickShopSlot(shop, listener, bow.getSlot());

            assertTrue(player.getWorld().getEntitiesByClass(Item.class).isEmpty(),
                    "背包有空位时商品应进背包，地上不应出现掉落物。若这里出现 drop，"
                            + "说明上一条断言的「1 件掉落物」与溢出无关，测的不是 dc-004。实际："
                            + describeDrops(player.getWorld()));
            assertEquals(Material.BOW, firstOfType(player, Material.BOW),
                    "对照半同时确认商品确实进了背包（否则「没有掉落物」可能只是没发货）");
        }

        private int occupiedSlots(PlayerMock p) {
            int n = 0;
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack != null && stack.getType() != Material.AIR) n++;
            }
            return n;
        }

        private Material firstOfType(PlayerMock p, Material type) {
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack != null && stack.getType() == type) return type;
            }
            return null;
        }
    }

    // ================================================================ 跳过侧

    /**
     * <b>跳过侧</b>：{@code ArenaInstance.clearDroppedItems()} 必须按
     * {@link ShopListener#PAID_DROP_KEY} 放行，其余仍删除。
     *
     * <p>用真实启用的 {@code arena1}（{@code loadFullPluginWithArenas}）——{@code resetNow()}
     * 是 {@code clearDroppedItems} 唯一的公开入口，且它本来就要真实 {@code ArenaInstance}
     * 与 {@code GameManager}。</p>
     */
    @Nested
    @DisplayName("跳过侧 —— clearDroppedItems 放行带标记的掉落物")
    class SkippingSide {

        private HilltopVillagePlugin plugin;
        private ArenaInstance arena;
        private World world;

        @BeforeEach
        void setUp() {
            plugin = loadFullPluginWithArenas();
            arena = plugin.getMultiArenaManager().getArena("arena1");
            assertNotNull(arena, "arena1 未被启用 —— 夹具失效（loadFullPluginWithArenas 的 20 tick 不够？）");
            world = arena.getGameManager().getGameWorld();
            assertNotNull(world, "arena1 的 GameManager 没有世界");
        }

        private Location at() {
            return new Location(world, 0.5, 64, 0.5);
        }

        @Test
        @DisplayName("重置后：带 PAID_DROP_KEY 的存活，未带标记的被删除")
        void markedDropSurvivesWhileUnmarkedIsRemoved() {
            Item marked = world.dropItemNaturally(at(), new ItemStack(Material.BOW, 1));
            marked.getPersistentDataContainer()
                    .set(ShopListener.PAID_DROP_KEY, PersistentDataType.BYTE, (byte) 1);
            Item plain = world.dropItemNaturally(at(), new ItemStack(Material.ARROW, 1));
            assertEquals(2, world.getEntitiesByClass(Item.class).size(),
                    "前置条件：世界里应有两件掉落物");

            arena.resetNow();

            List<Item> remaining = new ArrayList<>(world.getEntitiesByClass(Item.class));
            assertEquals(1, remaining.size(),
                    "重置后应恰好留下 1 件掉落物（带标记的那件），实际：" + describeDrops(world));
            assertTrue(marked.isValid(),
                    "dc-004：带 PAID_DROP_KEY 的掉落物是玩家已付对价的货，竞技场重置不得删除");
            assertFalse(plain.isValid(),
                    "对照半：未带标记的残留掉落物（怪物掉落 / 玩家丢弃）必须照常清理，"
                            + "否则「修复」会退化成「谁都不删」，竞技场越来越脏");
            assertEquals(Material.BOW, remaining.get(0).getItemStack().getType(),
                    "留下的必须是带标记的 BOW，实际：" + describeDrops(world));
        }

        @Test
        @DisplayName("对照：带「别的」PDC 键的掉落物仍被删除（放行判据是键本身，不是「有 PDC 就放行」）")
        void dropWithSomeOtherPdcKeyIsStillRemoved() {
            Item foreign = world.dropItemNaturally(at(), new ItemStack(Material.STONE, 1));
            foreign.getPersistentDataContainer().set(
                    new NamespacedKey("hilltopvillage", "some_other_drop"),
                    PersistentDataType.BYTE, (byte) 1);

            arena.resetNow();

            assertFalse(foreign.isValid(),
                    "放行判据必须是 ShopListener.PAID_DROP_KEY 本身。若写成「有任意 PDC 就跳过」，"
                            + "凡是带任何自定义数据、但仍属于残留垃圾的掉落物都会被永久留下。"
                            + "实际残留：" + describeDrops(world));
            assertTrue(world.getEntitiesByClass(Item.class).isEmpty(),
                    "该场景下重置后世界应无任何掉落物，实际：" + describeDrops(world));
        }
    }

    // ================================================================ 合成侧

    /**
     * <b>合成侧</b>：在真实启用的 {@code arena1} 世界里走完整链路 ——
     * 商店购买溢出落地 → 竞技场重置 → 已付对价的货还在。
     *
     * <p>这一组是唯一能同时证明两半「接得上」的用例：任何一半写错（标记用的键不匹配、
     * 跳过判据用了别的类型、快照/克隆丢了 PDC）都会在这里变红。</p>
     */
    @Nested
    @DisplayName("合成侧 —— 真实竞技场：购买溢出 → 重置 → 货还在")
    class EndToEnd {

        private HilltopVillagePlugin plugin;
        private ArenaInstance arena;
        private World arenaWorld;
        private PlayerMock player;
        private ShopManager shop;
        private ShopListener listener;
        private LanguageManager lang;

        @BeforeEach
        void setUp() {
            plugin = loadFullPluginWithArenas();
            arena = plugin.getMultiArenaManager().getArena("arena1");
            assertNotNull(arena, "arena1 未被启用 —— 夹具失效");
            arenaWorld = server.getWorld("hilltop_arena1");
            assertNotNull(arenaWorld, "hilltop_arena1 世界不存在");

            lang = stubLanguage();
            shop = plugin.getShopManager();
            assertNotNull(shop, "插件没有 ShopManager —— 全量启动失败？");
            listener = new ShopListener(shop, lang);

            player = server.addPlayer("OverflowBuyer");
            // MockBukkit 的 PlayerMock#teleport 在同世界/跨世界都可能返回 false（未实现完整传送），
            // 但 setLocation 会真正改 world 引用 —— 溢出掉落物落在 player.getWorld()。
            player.setLocation(new Location(arenaWorld, 0.5, 64, 0.5));
            assertEquals("hilltop_arena1", player.getWorld().getName(),
                    "前置条件：玩家必须站在竞技场世界里，否则掉落物落进 lobby，重置根本不扫那个世界");
        }

        @Test
        @DisplayName("商店溢出掉的货扛过竞技场重置，同场地的普通残留掉落物被清掉")
        void shopOverflowDropSurvivesArenaReset() {
            ShopItem bow = factoryItem(shop, "bow");
            fillEverySlot(player);
            player.getInventory().setItem(35, shop.createCurrencyItem(bow.getPrice() + 1));

            ShopGUI gui = new ShopGUI(player, shop, lang);
            gui.open();
            listener.onInventoryClick(new InventoryClickEvent(
                    view(player, gui.getInventory(), player.getInventory()),
                    InventoryType.SlotType.CONTAINER, bow.getSlot(), ClickType.LEFT,
                    InventoryAction.PICKUP_ALL));

            // 同一世界里再放一件与商店无关的残留掉落物，作为「清理仍然生效」的对照。
            Item junk = arenaWorld.dropItemNaturally(player.getLocation(), new ItemStack(Material.STONE, 1));

            Collection<Item> before = arenaWorld.getEntitiesByClass(Item.class);
            assertEquals(2, before.size(),
                    "前置条件：重置前竞技场世界里应有 2 件掉落物（1 件商店溢出 + 1 件垃圾），实际："
                            + describeDrops(arenaWorld));
            long paidBefore = before.stream().filter(ShopOverflowPaidDropTest::isPaidDrop).count();
            assertEquals(1, paidBefore,
                    "前置条件：其中恰好 1 件应带 PAID_DROP_KEY，实际：" + describeDrops(arenaWorld));

            arena.resetNow();

            List<Item> after = new ArrayList<>(arenaWorld.getEntitiesByClass(Item.class));
            assertEquals(1, after.size(),
                    "dc-004：重置后应只剩商店溢出那件，实际：" + describeDrops(arenaWorld));
            assertEquals(Material.BOW, after.get(0).getItemStack().getType(),
                    "活下来的必须是玩家花钱买的 BOW，实际：" + describeDrops(arenaWorld));
            assertTrue(isPaidDrop(after.get(0)),
                    "活下来的那件仍应带 PAID_DROP_KEY（重置不得清掉标记）");
            assertFalse(junk.isValid(),
                    "对照半：同世界的普通残留掉落物必须被清理，否则「修复」等于关掉了整个清理功能");
        }
    }
}
