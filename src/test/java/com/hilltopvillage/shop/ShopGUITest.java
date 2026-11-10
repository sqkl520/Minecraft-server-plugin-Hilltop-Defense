package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ShopGUI} 的渲染契约 —— 槽位映射、图标外观、占位符不购买。
 *
 * <p><b>为什么它值得测</b>：点击识别的整条链依赖 {@code getItemAt(slot)} 这个反查表。
 * 它和 {@code inventory.setItem(slot, ...)} 是<b>两次独立的写入</b>：只要有一次漏了或错位，
 * 症状是「图标在这里、点了买的是别的东西」——比「打不开」隐蔽得多。此外：</p>
 *
 * <ul>
 *   <li><b>占位符必须 {@code getItemAt()} 返回 null</b>：{@code ShopListener} 用
 *       {@code if (item == null) return;} 挡购买。若某天占位符被塞进 {@code bySlot}，
 *       点玻璃板就会触发一次「price 为几」的购买。</li>
 *   <li><b>价格行必须进 lore</b>：它是玩家唯一能看到的定价来源。lore 追加顺序也重要 ——
 *       配置的 lore 在前、价格行在后，玩家才不会被价格挤掉描述。</li>
 *   <li><b>{@code fillEmpty()} 只填空槽</b>：若它无条件覆盖，商品图标会被玻璃板盖掉。</li>
 * </ul>
 */
class ShopGUITest extends MockBukkitTestBase {

    private BareHost host;
    private ShopManager shop;
    private LanguageManager lang;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws IOException {
        host = loadBareHost();
        // varargs：必须用建 mock 时的默认 Answer（见 ShopManagerTest 的同类注释）
        lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) {
                // getFor(player, "shop.price-line", "<price>") 需要把价格拼进消息里，
                // 否则「价格行存在」这条断言测不到真实内容。
                Object[] args = invocation.getArguments();
                if (args.length > 0 && "shop.price-line".equals(args[args.length > 1 ? 1 : 0])) {
                    return "Price: " + args[args.length - 1];
                }
                return "i18n";
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        shop = new ShopManager(host, lang);
        shop.reload();
        player = server.addPlayer();
    }

    private ShopGUI openGui() {
        ShopGUI gui = new ShopGUI(player, shop, lang);
        gui.open();
        return gui;
    }

    /**
     * 改写 {@code shop.yml} 后重载配置。
     *
     * <p><b>为什么不能直接 {@code shop.reload()}</b>：{@code ShopManager.reload()} 会遍历
     * {@code Bukkit.getOnlinePlayers()} 并读 {@code p.getOpenInventory().getTopInventory()}
     * （slice-12-dc-003 的修复：关掉残留 GUI）。而 MockBukkit 的 {@code PlayerMock} 在
     * <b>从未 openInventory 过</b>时，该 view 的 {@code getTopInventory()} 返回 {@code null}
     * （真实 Bukkit 永远返回有效 view），于是 reload() 在这里 NPE。
     * 这是 MockBukkit 的能力缺口，不是生产缺陷 —— 先让玩家停在一个真实打开的界面上即可绕过，
     * 而已测的语义（reload 关掉 ShopGUI / 不关别人的箱子）在 {@code ShopManagerTest} 里单独覆盖。</p>
     */
    private void reloadWithOpenView() {
        if (player.getOpenInventory() == null
                || player.getOpenInventory().getTopInventory() == null) {
            player.openInventory(server.createInventory(null, 9));
        }
        shop.reload();
    }

    private static ItemStack itemAt(Inventory inv, int slot) {
        return inv.getItem(slot);
    }

    // ================================================================ 槽位映射

    @Nested
    @DisplayName("槽位映射")
    class SlotMapping {

        @Test
        @DisplayName("每件商品都被放进它自己的 slot，且 getItemAt(slot) 反查到同一件")
        void everyItemIsPlacedAtItsSlotAndResolvable() {
            ShopGUI gui = openGui();
            Inventory inv = gui.getInventory();

            for (ShopItem item : shop.getShopConfig().getItems()) {
                ItemStack rendered = itemAt(inv, item.getSlot());
                assertNotNull(rendered,
                        "slot " + item.getSlot() + " 上的商品 '" + item.getId() + "' 没有被渲染");
                assertEquals(item.getIcon(), rendered.getType(),
                        "slot " + item.getSlot() + " 的图标材质应为 " + item.getIcon()
                                + "（商品 " + item.getId() + "）");
                assertSame(item, gui.getItemAt(item.getSlot()),
                        "getItemAt(" + item.getSlot() + ") 必须反查到 " + item.getId()
                                + " —— 点击识别整条链依赖它");
            }
        }

        @Test
        @DisplayName("出厂布局的关键槽位逐一核对（bow=0 / arrow=2 / hammer=4 / fireball=6 / sap=8）")
        void factoryLayoutSlots() {
            ShopGUI gui = openGui();

            assertEquals("bow", gui.getItemAt(0).getId());
            assertEquals("arrow", gui.getItemAt(2).getId());
            assertEquals("sacred_hammer", gui.getItemAt(4).getId());
            assertEquals("blaze_fireball", gui.getItemAt(6).getId());
            assertEquals("world_tree_sap", gui.getItemAt(8).getId());
            assertEquals("iron_chestplate", gui.getItemAt(30).getId());
        }

        @Test
        @DisplayName("空槽（占位符）的 getItemAt 返回 null —— ShopListener 据此拒买")
        void fillerSlotsAreNotPurchasable() {
            ShopGUI gui = openGui();

            assertNull(gui.getItemAt(1),
                    "slot 1 在出厂布局里是空的，填了玻璃板。若 getItemAt 返回非 null，"
                            + "点玻璃板会触发一次购买");
            assertNull(gui.getItemAt(9));
            assertNull(gui.getItemAt(53));
            assertNull(gui.getItemAt(999), "越界槽位应返回 null 而不是抛异常");
            assertNull(gui.getItemAt(-1), "负槽位同样应返回 null");
        }

        @Test
        @DisplayName("GUI 尺寸取自配置（54）")
        void inventorySizeComesFromConfig() {
            assertEquals(54, openGui().getInventory().getSize());
            assertSame(player, player, "占位断言，保持 player 被使用");
        }
    }

    // ================================================================ 外观

    @Nested
    @DisplayName("图标外观")
    class Appearance {

        @Test
        @DisplayName("shop.yml 直配 name 被采用并翻译颜色码")
        void configuredNameIsUsed() {
            ItemStack bow = itemAt(openGui().getInventory(), 0);
            assertEquals("§f弓", bow.getItemMeta().getDisplayName(),
                    "出厂 shop.yml 的 bow.name 是 '&f弓'，应被 translateAlternateColorCodes 翻译");
        }

        @Test
        @DisplayName("lore = 配置行（翻译）+ 追加的价格行")
        void loreContainsConfigLinesThenPrice() {
            ItemStack bow = itemAt(openGui().getInventory(), 0);
            List<String> lore = bow.getItemMeta().getLore();

            assertNotNull(lore, "没有 lore 玩家就看不到价格");
            assertEquals(3, lore.size(),
                    "出厂 bow 配了 2 行 lore + 1 行价格 = 3 行；实际：" + lore);
            assertEquals("§7远程射击——专治飞在天上的抛投者", lore.get(0),
                    "配置的 lore 在前，且颜色码已翻译");
            assertEquals("§7箭需另行购买", lore.get(1));
            assertTrue(lore.get(2).contains("15"),
                    "价格行必须在最后一行，且包含实际价格 15；实际：" + lore.get(2));
        }

        @Test
        @DisplayName("商品没有 lore 时，lore 里只有价格行（不是 null）")
        void loreWithoutConfigLinesStillHasPrice() throws IOException {
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      gui:
                        size: 27
                      items:
                        plain:
                          slot: 0
                          price: 42
                          type: MATERIAL
                          icon:
                            material: STONE
                    """);
            reloadWithOpenView();

            ItemStack rendered = itemAt(openGui().getInventory(), 0);
            List<String> lore = rendered.getItemMeta().getLore();

            assertNotNull(lore, "即使没配 lore，价格行也必须存在");
            assertEquals(1, lore.size(), "只有价格行，实际：" + lore);
            assertTrue(lore.get(0).contains("42"), "价格行应含 42：" + lore.get(0));
        }

        @Test
        @DisplayName("fillEmpty：空槽被占位符玻璃板填充（不留 AIR 空洞）")
        void emptySlotsBecomeFillerPanels() {
            Inventory inv = openGui().getInventory();

            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                assertNotNull(stack, "slot " + slot + " 不应为 null（fillEmpty 应已填充）");
                assertTrue(stack.getType() != Material.AIR, "slot " + slot + " 不应是 AIR");
            }
        }

        @Test
        @DisplayName("fillEmpty 不覆盖已渲染的商品图标")
        void fillEmptyDoesNotClobberProducts() {
            Inventory inv = openGui().getInventory();

            assertEquals(Material.BOW, inv.getItem(0).getType(),
                    "slot 0 是 bow 图标，不能被占位符（GRAY_STAINED_GLASS_PANE）覆盖");
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, inv.getItem(1).getType(),
                    "slot 1 是空槽，应由占位符填充");
        }

        @Test
        @DisplayName("i18n 回退：未配 name 时取 shop.item.<id>.name")
        void fallsBackToI18nName() throws IOException {
            LanguageManager named = mock(LanguageManager.class, invocation -> {
                Object[] args = invocation.getArguments();
                if (args.length >= 2 && "shop.item.probe.name".equals(args[1])) {
                    return "Localized Name";
                }
                if (invocation.getMethod().getReturnType() == String.class) return "i18n";
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      gui:
                        size: 27
                      items:
                        probe:
                          slot: 0
                          price: 1
                          type: MATERIAL
                          icon:
                            material: STONE
                    """);
            reloadWithOpenView();

            ItemStack rendered = itemAt(new ShopGUI(player, shop, named).getInventory(), 0);

            assertEquals("Localized Name", rendered.getItemMeta().getDisplayName(),
                    "未配 name 时应取 i18n 的 shop.item.<id>.name");
        }

        @Test
        @DisplayName("i18n 也取不到时回退到材质名的可读形式（Diamond sword）")
        void fallsBackToPrettyMaterialName() throws IOException {
            // LanguageManager 取不到键时返回 key 本身；ShopGUI 正是靠 `!msg.equals(key)` 判断这一点的。
            // 所以这里必须让桩「原样回传 key」，返回任意别的字符串都会绕过被测的回退分支。
            LanguageManager echoing = mock(LanguageManager.class, invocation -> {
                Object[] args = invocation.getArguments();
                if ("getFor".equals(invocation.getMethod().getName()) && args.length >= 2
                        && args[1] instanceof String key) {
                    return key;                       // 模拟「查不到 → 返回 key」
                }
                if (invocation.getMethod().getReturnType() == String.class) return "i18n";
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      gui:
                        size: 27
                      items:
                        probe:
                          slot: 0
                          price: 1
                          type: MATERIAL
                          icon:
                            material: DIAMOND_SWORD
                    """);
            reloadWithOpenView();

            ItemStack rendered = new ShopGUI(player, shop, echoing).getInventory().getItem(0);

            assertEquals("Diamond sword", rendered.getItemMeta().getDisplayName(),
                    "LanguageManager 取不到时返回 key 本身，于是回退到 prettyMaterialName："
                            + "小写 + 下划线换空格 + 首字母大写");
        }
    }

    // ================================================================ holder 契约

    @Nested
    @DisplayName("InventoryHolder 契约")
    class HolderContract {

        @Test
        @DisplayName("getInventory().getHolder() 就是本实例（点击识别的唯一依据）")
        void holderIsTheGuiItself() {
            ShopGUI gui = openGui();

            assertSame(gui, gui.getInventory().getHolder(),
                    "ShopListener 用 `event.getInventory().getHolder() instanceof ShopGUI` 识别商店；"
                            + "holder 不是自己，整条购买链就静默失效");
        }

        @Test
        @DisplayName("open() 之后玩家停在商店界面上")
        void openShowsTheInventory() {
            ShopGUI gui = openGui();

            assertNotNull(player.getOpenInventory().getTopInventory(), "open() 之后应有顶层界面");
            assertSame(gui, player.getOpenInventory().getTopInventory().getHolder());
        }

        @Test
        @DisplayName("每次 new 都建新实例与新的 bySlot（reload 后重开即拾取新配置）")
        void eachConstructionIsIndependent() {
            ShopGUI first = openGui();
            ShopGUI second = openGui();

            assertFalse(first == second, "每次打开都新建实例 —— 这是「无需注册/注销监听器」设计的前提");
            assertEquals(first.getInventory().getSize(), second.getInventory().getSize());
            assertNull(first.getItemAt(1), "新实例的 bySlot 也要重新算，空槽仍是 null");
        }

        @Test
        @DisplayName("gui.title 直配优先并翻译颜色码")
        void configuredTitleWins() throws IOException {
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      gui:
                        size: 27
                        title: "&6Village Shop"
                      items: {}
                    """);
            reloadWithOpenView();

            openGui();

            assertEquals("§6Village Shop", player.getOpenInventory().getTitle(),
                    "shop.yml 直配的 title 支持颜色码；取不到 title 说明走进了 i18n 回退");
        }
    }
}
