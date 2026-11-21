package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
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

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * R-2：商店溢出的「<b>部分可容纳</b>」切分形态（背包只剩半格空间，而不是全满）。
 *
 * <p><b>为什么需要单独一个类</b>：{@code ShopOverflowPaidDropTest}（round3 组 C）已经钉住
 * 「背包<b>全满</b>时整件溢出 + 每一片带 {@link ShopListener#PAID_DROP_KEY}」。它<b>没有</b>
 * 覆盖「背包还能装下一部分」这条更常见的路径 —— {@code Inventory#addItem} 在这种形态下会
 * <b>先把一部分并进/放进背包、只把余量交回 overflow</b>。此时三件事必须分别成立，
 * 且它们由完全不同的代码负责喵：</p>
 *
 * <ol>
 *   <li><b>并入的那部分真的在包里</b>（数量对，不是被吞）；</li>
 *   <li><b>只有余量落在地上</b>（{@code overflow} 只是余量，不是整件重复掉落）；</li>
 *   <li><b>地上恰好 N 件、数量恰好等于余量</b>（<b>不是</b>「地上恰好 1 件」这种粗断言）。</li>
 * </ol>
 *
 * <p>只断言「地上有一件货」会同时漏掉「重复掉落」（本该 1 件掉成 2 件）与「入包部分被吞」
 * 两种相反缺陷，所以下面每条都用「包内 + 地上 + 地上数量」三点断言喵。</p>
 *
 * <p><b>本类不复制 {@code EndToEnd}</b>：那条真实竞技场链路单次 35–60 s，太贵了。
 * 这里走 {@code BareHost} + {@code ShopManager} 级别，但<b>从真实的
 * {@code ShopListener#onInventoryClick} 入口进入</b>，够证切分语义喵。</p>
 *
 * <p><b>⚠️ 实测前提（本类夹具的地基，改之前先读；全部由探针实测，不是推测）</b>：</p>
 * <ul>
 *   <li>{@code PlayerMock} 背包 {@code getContents()} 报 <b>41 格</b>（36 主 + 4 甲 + 1 副），
 *       {@code getStorageContents()} 只报 <b>36 格</b>；</li>
 *   <li><b>{@code BOW} 的 maxStackSize 是 1</b>（探针实测 {@code BOW=1 / ARROW=64 / EMERALD=64}）。
 *       所以「半格 BOW」根本不存在 —— 想造「部分可容纳」必须用<b>可堆叠</b>材质：
 *       买箭用 {@code ARROW}、退款用货币 {@code EMERALD}；</li>
 *   <li><b>{@code countCurrency} 只看 {@code getStorageContents()}（36 格）+ 副手</b>，
 *       <b>不含 4 个护甲槽（36-39）</b>。把货币放进 36-39 会「看得见但数不到」——
 *       本类所有货币一律放主背包 0-35 或副手 40；</li>
 *   <li>{@code addItem} <b>先填空槽、再并半满叠</b>（实测：41 格中 40 满 + 副手 60，塞 8 支 →
 *       副手置 64、另一格新开）；因此「造半格剩余而不给空槽」只能靠<b>41 格全占</b>；</li>
 *   <li>{@code InventoryMock} 只按<b>材质</b>合并（{@code ItemStackMock#isSimilar} 反编译确认
 *       最终只比较 {@code ItemType}），<b>合并时把目标的 meta 整个换成来源的</b>，
 *       于是「裸叠 + 带 PDC 叠」合并后 PDC <b>整体消失</b>。这条 MockBukkit 契约差异
 *       使「入包部分是否保留 PDC」在本环境<b>不可断言</b>（见 {@link ResidualRisk}）喵。</li>
 * </ul>
 */
class ShopOverflowPartialFitTest extends MockBukkitTestBase {

    /** 41 = 36 主背包 + 4 护甲 + 1 副手；{@code addItem} 会用全部 41 格。 */
    private static final int MOCK_INVENTORY_SLOTS = 41;
    /** {@code getStorageContents()} 只报 36 格 —— {@code countCurrency} 的扫描范围。 */
    private static final int MAIN_STORAGE_SLOTS = 36;

    // ================================================================ 夹具

    /**
     * 与 {@code ShopOverflowPaidDropTest} 同款视图桩；{@code getClickedInventory()} 由 view 派生，
     * 不桩 {@code getInventory(rawSlot)} / {@code convertSlot(rawSlot)} 两个语义，
     * {@code onInventoryClick} 的「点击的背包等于事件背包」守卫根本进不去喵。
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

    private static boolean isPaidDrop(Item item) {
        // 标记在 Item **实体**的 PDC 上（与 ArenaInstance.clearDroppedItems 的口径一致）。
        return item.getPersistentDataContainer()
                .has(ShopListener.PAID_DROP_KEY, PersistentDataType.BYTE);
    }

    /** 世界里的掉落物摘要，断言失败时直接点名「地上到底有什么」。 */
    private static List<String> describeDrops(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (Item item : player.getWorld().getEntitiesByClass(Item.class)) {
            out.add(item.getItemStack().getType() + "x" + item.getItemStack().getAmount()
                    + (isPaidDrop(item) ? "[PAID]" : "[plain]"));
        }
        return out;
    }

    /** 包里某材质的<b>合计</b>件数（半格 + 另开的格都要数到）。 */
    private static int countInBag(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

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
        player.getInventory().clear();
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

    /** 41 格全部塞满不可合并的方块（占满「空槽」这条路，只剩各叠自身的余量）。 */
    private void fillAllFortyOneSlots() {
        for (int i = 0; i < MOCK_INVENTORY_SLOTS; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
    }

    // ================================================================ 购买侧

    @Nested
    @DisplayName("购买侧 —— 商品只有一部分装得进背包")
    class PurchasePartialFit {

        /**
         * 形态：41 格全占，槽 0 是 {@code ARROW x56}（半满，还能再放 8 支），
         * 槽 35 是付款货币（买箭花 2，放下 3 → 扣完剩 1，该格仍被货币占着，不腾空槽）。
         *
         * <p>买一单 {@code arrow}（8 支）：正好把槽 0 填到 64，<b>overflow 为空</b>。
         * 这是「部分可容纳但刚好装下」的<b>边界</b>形态 —— 若实现的切分算错 1 支，
         * 这里会立刻出现 1 件掉落物喵。</p>
         */
        @Test
        @DisplayName("边界：半格 56 + 买 8 = 恰好 64，一件都不掉")
        void exactFitIntoHalfFullStackDropsNothing() {
            ShopItem arrow = factoryItem(shop, "arrow");
            assertEquals(8, arrow.getAmount(), "出厂 arrow 一次给 8 支，本用例按 8 计算");
            assertEquals(2, arrow.getPrice(), "出厂 arrow 单价 2");

            fillAllFortyOneSlots();
            player.getInventory().setItem(0, new ItemStack(Material.ARROW, 56));
            player.getInventory().setItem(35, shop.createCurrencyItem(3));

            clickShopSlot(shop, listener, arrow.getSlot());

            assertEquals(1, shop.countCurrency(player),
                    "前置条件：应扣掉恰好 " + arrow.getPrice() + " 个货币（3 → 1），该格仍非空 → 不腾空槽");
            assertEquals(64, player.getInventory().getItem(0).getAmount(),
                    "半满 ARROW 叠（56）必须被并入 8 支到 64。若仍是 56，并入路径没被触发");
            assertEquals(64, countInBag(player, Material.ARROW),
                    "包内 ARROW 合计必须是 56 + 8 = 64，不能少（被吞）也不能多（重复发放）");
            assertTrue(player.getWorld().getEntitiesByClass(Item.class).isEmpty(),
                    "恰好装得下时地上不该有任何掉落物。出现掉落物说明切分算错了余量，实际："
                            + describeDrops(player));
        }

        /**
         * 形态：41 格全占，槽 0 是 {@code ARROW x60}（半满，只还能放 4 支），
         * 槽 35 是付款货币（3 → 1，不腾空槽）。买 8 支。
         *
         * <p>预期：4 支并进槽 0（→64）、余下 <b>4 支</b>落地，且地面<b>恰好 1 个</b> Item 实体。
         * 这条是本类真正瞄准的形态喵。</p>
         */
        @Test
        @DisplayName("真部分容纳：并入 4 支到 64，地上恰好 1 件、恰好 4 支")
        void partialFitDropsOnlyTheRemainder() {
            ShopItem arrow = factoryItem(shop, "arrow");
            fillAllFortyOneSlots();
            player.getInventory().setItem(0, new ItemStack(Material.ARROW, 60));
            player.getInventory().setItem(35, shop.createCurrencyItem(3));

            clickShopSlot(shop, listener, arrow.getSlot());

            assertEquals(1, shop.countCurrency(player),
                    "前置条件：付款后应剩 1 个货币（该格仍被货币占着，不会腾出空槽给商品）");
            assertEquals(64, player.getInventory().getItem(0).getAmount(),
                    "半满的 ARROW 叠必须被填到 64（挤入 4 支）。若仍是 60，并入路径没发生");

            List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "余下的 4 支应作为【恰好 1 个】Item 实体落地。多于 1 件 = 重复掉落缺陷；"
                            + "0 件 = 余量被静默吞掉。实际：" + describeDrops(player));
            Item remainder = drops.get(0);
            assertEquals(Material.ARROW, remainder.getItemStack().getType(),
                    "落地的必须是余量 ARROW，实际：" + describeDrops(player));
            assertEquals(4, remainder.getItemStack().getAmount(),
                    "落地数量必须是 8 - 4 = 4（半格只装得下 4 支）。若等于 8，说明 overflow "
                            + "不是「余量」而是整件重复掉落。实际：" + describeDrops(player));
            assertEquals(68, countInBag(player, Material.ARROW) + remainder.getItemStack().getAmount(),
                    "账目守恒：包内 64 + 地上 4 必须等于 60 + 8 = 68");
            assertTrue(isPaidDrop(remainder),
                    "落地余量必须带 PAID_DROP_KEY，否则竞技场重置会把它删掉"
                            + "（dc-004 在「部分容纳」形态下的延伸）喵");
        }
    }

    // ================================================================ 退款侧

    @Nested
    @DisplayName("退款侧 —— 退回的货币只有一部分装得进背包")
    class RefundPartialFit {

        /**
         * 让 {@code deliverItem} 返回 null 走退款分支（同 {@code ShopOverflowPaidDropTest} 手法），
         * 退款金额 = {@code bow.price = 15}。
         *
         * <p>形态：41 格全占，槽 0 是 {@code EMERALD x85}（<b>超过单叠上限的存量真币</b>，
         * dc-005 明确不裁剪）。付款 15 先从这里扣（{@code removeCurrency} 从后往前、主背包全满
         * 只有槽 0 是货币）→ 剩 70。退款 15 想并进这个 70 却<b>已超单叠上限、无空槽可拆</b>
         * → 这 15 个<b>整件落地</b>成恰好 1 件喵。</p>
         */
        @Test
        @DisplayName("退款完全装不下：地上恰好 1 件、数量等于退款额")
        void refundFullyOverflowsInOnePiece() {
            ShopItem bow = factoryItem(shop, "bow");
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doReturn(null).when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            fillAllFortyOneSlots();
            player.getInventory().setItem(0, spied.createCurrencyItem(85));

            clickShopSlot(spied, spiedListener, bow.getSlot());

            assertEquals(70, player.getInventory().getItem(0).getAmount(),
                    "前置条件：扣掉 " + bow.getPrice() + " 后槽 0 应剩 70（85 - 15），"
                            + "且该叠已超单叠上限 64 —— 这正是「退款并进去也装不下」的来源");
            List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "退款 " + bow.getPrice() + " 个货币完全装不下时应恰好落地 1 件，实际："
                            + describeDrops(player));
            Item refund = drops.get(0);
            assertEquals(Material.EMERALD, refund.getItemStack().getType(),
                    "退款掉的应是货币 EMERALD，实际：" + describeDrops(player));
            assertEquals(bow.getPrice(), refund.getItemStack().getAmount(),
                    "落地数量必须等于退款额 " + bow.getPrice() + "（不能少发、不能多发），实际："
                            + describeDrops(player));
            assertTrue(isPaidDrop(refund),
                    "退款溢出是 ShopListener 的第二个 dropItemNaturally 调用点，同样必须带 PAID_DROP_KEY");
        }

        /**
         * 形态：41 格全占，槽 40（副手）是 {@code EMERALD x79}。付款 15 从<b>副手</b>扣
         * （主背包 0-35 全是 DIRT）→ 64。退款 15：{@code addItem} 往副手的 64 里并 ——
         * 已满，并不进；41 格全占无空槽 → 15 个中只有能新开的那部分能进，其余落地。
         * 预期：副手仍 64，落地恰好 1 件 EMERALD x15 喵。
         *
         * <p>（探针实测：副手 60 时 4 个会并进去，余 11 落地；副手 64 时 15 个全落地。）</p>
         */
        @Test
        @DisplayName("退款真部分容纳：并入 4 到 64 + 地上恰好 1 件 11（互补守恒）")
        void refundPartialFitSplitsBetweenBagAndGround() {
            ShopItem bow = factoryItem(shop, "bow");
            ShopManager spied = org.mockito.Mockito.spy(shop);
            org.mockito.Mockito.doReturn(null).when(spied).deliverItem(any(ShopItem.class));
            ShopListener spiedListener = new ShopListener(spied, lang);

            fillAllFortyOneSlots();
            // 79 - 15 = 64：扣完后副手恰好是一整叠，退款只能「并入 0 + 全部落地」。
            // 想要「并入 + 余量」两边都非零，需要扣完后的残留叠在 60..63 之间 ——
            // 用起始 75 得到 60，退款 15 里 4 个并进去、11 个落地。
            player.getInventory().setItem(40, spied.createCurrencyItem(75));

            clickShopSlot(spied, spiedListener, bow.getSlot());

            int offhand = player.getInventory().getItem(40).getAmount();
            assertEquals(64, offhand,
                    "前置条件：付款后副手应剩 60（75 - 15），退款再并入 4 个 → 64。"
                            + "实际读数 " + offhand + " 说明并入/扣款任一环节与预期不符");

            List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
            assertEquals(1, drops.size(),
                    "并入后余下的货币应作为【恰好 1 件】落地，实际：" + describeDrops(player));
            Item remainder = drops.get(0);
            assertEquals(Material.EMERALD, remainder.getItemStack().getType(),
                    "落地余量应是货币 EMERALD，实际：" + describeDrops(player));
            assertEquals(bow.getPrice(), (offhand - 60) + remainder.getItemStack().getAmount(),
                    "账目守恒：退款 " + bow.getPrice() + " = 并入副手的 " + (offhand - 60)
                            + " + 落地的 " + remainder.getItemStack().getAmount());
            assertEquals(11, remainder.getItemStack().getAmount(),
                    "落地数量必须是 15 - 4 = 11（副手 60 只能再并 4 个到 64）。若等于 15，"
                            + "说明并入与掉落重复计算；若等于 0，说明余量被吞。实际：" + describeDrops(player));
            assertTrue(isPaidDrop(remainder), "退款侧的余量同样必须带 PAID_DROP_KEY");
        }
    }

    // ================================================================ 对照

    @Nested
    @DisplayName("对照 —— 同款背包但商品完全装得下时不该有任何掉落")
    class ControlNoOverflow {

        @Test
        @DisplayName("背包有空槽时不掉落（证明上面几条的 drop 确由容量不足产生）")
        void whenItFitsNothingDrops() {
            ShopItem arrow = factoryItem(shop, "arrow");
            // 36 主背包全填满，但槽 35 是付款货币（扣完腾空）—— addItem 会落进这个空槽
            for (int i = 0; i < MAIN_STORAGE_SLOTS; i++) {
                player.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
            }
            player.getInventory().setItem(35, shop.createCurrencyItem(3));

            clickShopSlot(shop, listener, arrow.getSlot());

            assertTrue(player.getWorld().getEntitiesByClass(Item.class).isEmpty(),
                    "付款会腾出一个空槽，8 支箭装得下 → 地上不该有任何掉落物。若这里有 drop，"
                            + "说明上面的「余量」断言与容量无关。实际：" + describeDrops(player));
            assertEquals(arrow.getAmount(), countInBag(player, Material.ARROW),
                    "对照半同时确认商品确实进了背包（否则「没有掉落物」可能只是没发货）");
            assertEquals(1, shop.countCurrency(player), "对照半：付款后余额应为 1");
        }
    }

    // ================================================================ 夹具自检

    @Test
    @DisplayName("夹具自检：41 格背包 / 36 格 storage / BOW 不可堆叠 / 副手可写")
    void fixtureSanity() {
        assertEquals(MOCK_INVENTORY_SLOTS, player.getInventory().getContents().length,
                "PlayerMock 背包必须是 41 格。若这个数变了，本类所有「全占」形态的前提都要重算");
        assertEquals(MAIN_STORAGE_SLOTS, player.getInventory().getStorageContents().length,
                "getStorageContents() 必须只报 36 格 —— countCurrency 的扫描范围依赖它；"
                        + "若变成 41，说明「货币放进护甲槽数不到」这条前提失效");
        assertEquals(1, new ItemStack(Material.BOW, 1).getMaxStackSize(),
                "BOW 必须是不可堆叠的（maxStackSize 1），否则「半格 BOW」的形态会变成可能，"
                        + "本类改用 ARROW / EMERALD 的前提也要重新审视");
        assertEquals(64, new ItemStack(Material.ARROW, 1).getMaxStackSize(),
                "ARROW 必须是 64 叠，购买侧的部分容纳形态按 64 计算");
        assertEquals(64, new ItemStack(Material.EMERALD, 1).getMaxStackSize(),
                "货币材质 EMERALD 必须是 64 叠，退款侧的部分容纳形态按 64 计算");

        player.getInventory().setItem(40, new ItemStack(Material.ARROW, 60));
        assertNotNull(player.getInventory().getItem(40), "副手槽必须可写");
        assertEquals(60, player.getInventory().getItem(40).getAmount(), "副手槽夹具自检");
    }

    // ================================================================ 残余风险

    /**
     * 本类<b>刻意未断言</b>的两件事，以及为什么 —— 它们是真实风险，但在这套夹具里断言会得到
     * 假结论，故登记为残余风险而非写成测试喵。
     *
     * <ol>
     *   <li><b>「并入背包的那一部分是否保留 PDC」</b>。{@code InventoryMock#addItem} 的合并
     *       只按 {@code ItemType} 判相似（{@code ItemStackMock#isSimilar} 反编译确认：
     *       最终只比较 {@code ItemType}，不看 meta），且合并时把<b>目标叠</b>的整个 meta
     *       换成来源叠的 —— 探针实测「{@code GHAST_TEAR x62 裸} + {@code x4 带 item-kind}」
     *       合并后是 {@code x64 <b>没有</b> item-kind}。真实 {@code CraftInventory} 不会这样
     *       （不同 meta 的物品不相似，会另开一格）。因此在本环境断言 PDC 只会测到 MockBukkit
     *       的缺陷。<b>这同时意味着「玩家背包里预存一堆同材质裸物品、买到的带 PDC 商品并入后
     *       PDC 丢失」这条<b>线上风险</b>无法在本环境证伪 —— 只能靠人工实测或真服务器验证喵。</b>
     *       注意这与 dc-004 的「溢出掉落物打标」是两条独立的链：溢出侧的打标由
     *       {@code ShopOverflowPaidDropTest} 钉住，本类不重复。</li>
     *   <li><b>多叠同时半满时的填充顺序</b>。本类只用「单一半满叠 + 其余全占」，只有一条余量
     *       路径。多个半满叠并存时 {@code addItem} 的填充顺序由 {@code InventoryMock} 决定，
     *       与真实 {@code CraftInventory} 不保证一致，断言会得到与线上无关的结论喵。</li>
     * </ol>
     */
    @SuppressWarnings("unused")
    private static final class ResidualRisk {
        private ResidualRisk() {
        }
    }
}
