package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * S09-F1 —— 损坏的 {@code shop.yml} 只能让<b>商店</b>降级，不能拖垮整个插件。
 *
 * <p><b>缺陷原貌</b>：{@link ShopConfigLoader#load()} 对每种不可用读取（空文件、解析失败、
 * 已被隔离）都抛 {@link IllegalStateException}；{@link ShopManager#reload()} 的第一个语句就是
 * {@code this.config = loader.load()}，没有 try/catch；{@code onEnable} 又刻意不给
 * {@code ShopManager} 套容错（核心模块失败即禁用插件）。三者叠加的结果是：
 * 一份空的 {@code shop.yml} → {@code onEnable} 抛异常 → 插件永不启用 →
 * <b>没有竞技场、没有世界、没有命令、没有监听器</b>。</p>
 *
 * <p><b>修复口径</b>：{@code reload()} 不抛异常、返回 boolean；失败时保留上一份快照，
 * 首次启动（没有任何快照）落回内置空商店。</p>
 *
 * <p><b>本类两个方向都断言</b>：只断言「不抛」的话，一个把失败静默吞掉、留下
 * {@code config == null} 的实现也会绿 —— 而 {@code config == null} 会让
 * {@code ShopGUI} / {@code NpcManager} / {@code createCurrencyItem} 全部 NPE。
 * 因此每条失败路径都同时断言「配置非 null」与「返回值是 false」。</p>
 */
class ShopReloadDegradeTest extends MockBukkitTestBase {

    /** SnakeYAML 必然拒绝的形态（未闭合的 flow sequence）。 */
    private static final String CORRUPT_SHOP_YML = """
            shop:
              items:
                broken: [unclosed
            """;

    /** 一份可用的自定义配置，用来证明「失败时保留上一份」保留的确实是它。 */
    private static final String VALID_SHOP_YML = """
            shop:
              currency:
                material: DIAMOND
                name: "&bCustom Coin"
              gui:
                size: 27
              items:
                probe:
                  slot: 0
                  price: 42
                  type: MATERIAL
                  icon:
                    material: BOW
            """;

    private BareHost host;
    private ShopManager shop;
    private File shopFile;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        LanguageManager lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        shop = new ShopManager(host, lang);
        shopFile = new File(host.getDataFolder(), "shop.yml");
    }

    // ================================================================ 首次启动就没有可用配置

    @Nested
    @DisplayName("首次启动即遇到坏文件：落回内置空商店（config 必须非 null）")
    class FirstStartWithBrokenFile {

        @Test
        @DisplayName("reload() 不抛异常，返回 false，且 getShopConfig() 非 null")
        void corruptFileOnFirstStartDegradesInsteadOfThrowing() throws IOException {
            TestPaths.write(shopFile, CORRUPT_SHOP_YML);

            // 关键：这里**不能**用 assertThrows —— 一旦抛，onEnable 就会中止，插件根本不启用
            boolean loaded = shop.reload();

            assertFalse(loaded, "坏文件必须如实报失败（供 /hilltop shop reload 播报）");
            assertNotNull(shop.getShopConfig(),
                    "★ 降级后 config 仍必须非 null —— 否则 ShopGUI / NpcManager / "
                            + "createCurrencyItem 会在下一次解引用时 NPE，等于把「启动失败」换成了「运行期随机崩」");
        }

        @Test
        @DisplayName("空商店是 0 件商品 + 可携带的货币材质（不是 AIR，否则 createCurrencyItem 抛异常）")
        void emptyShopIsUsableForCurrencyFactory() throws IOException {
            TestPaths.write(shopFile, CORRUPT_SHOP_YML);
            shop.reload();

            ShopConfig config = shop.getShopConfig();
            assertEquals(0, config.getItems().size(), "内置空商店不凭空造商品");
            assertTrue(config.getCurrencyMaterial().isItem(),
                    "货币材质必须是可携带物品：createCurrencyItem 会对它调 new ItemStack，"
                            + "用 AIR 之类会抛异常 —— 那与「坏配置也能安全降级」正好相反");
            assertTrue(config.getGuiSize() >= 9,
                    "GUI 尺寸必须是合法档位，否则 Bukkit.createInventory 直接拒建");
        }

        @Test
        @DisplayName("空商店下购买链安全拒绝：GUI 里没有任何商品可点，deliverItem 不被调用")
        void emptyShopHasNoPurchasableItems() throws IOException {
            TestPaths.write(shopFile, CORRUPT_SHOP_YML);
            shop.reload();

            PlayerMockHolder holder = new PlayerMockHolder(server.addPlayer("shop-shopper"));
            ShopGUI gui = new ShopGUI(holder.player, shop, mockLang());

            // 遍历整格：没有任何一格能反查到商品 → ShopListener 的 purchase 分支永不进入
            for (int slot = 0; slot < gui.getInventory().getSize(); slot++) {
                org.junit.jupiter.api.Assertions.assertNull(gui.getItemAt(slot),
                        "空商店的槽位 " + slot + " 不该反查到商品；否则玩家能买到不存在的东西");
            }
            // 占位板是灰色玻璃板，不会被误认为商品
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, gui.getInventory().getItem(0).getType(),
                    "空商店只应有占位板");
        }

        @Test
        @DisplayName("空商店的货币工厂仍可用（击杀掉币 / 退款链不因坏配置而崩）")
        void currencyFactorySurvivesEmptyShop() throws IOException {
            TestPaths.write(shopFile, CORRUPT_SHOP_YML);
            shop.reload();

            ItemStack coin = shop.createCurrencyItem(3);

            assertNotNull(coin);
            assertEquals(3, coin.getAmount());
            assertTrue(coin.hasItemMeta(), "真币必须带 PDC，否则 countCurrency 认不出来");
        }
    }

    // ================================================================ 已有快照

    @Nested
    @DisplayName("已有上一份有效配置：坏文件时保留它（reload 改坏文件不该清空在营商店）")
    class KeepPreviousSnapshot {

        @Test
        @DisplayName("第二次 reload 失败 → 仍是原来那份配置实例")
        void failedReloadKeepsPreviousInstance() throws IOException {
            TestPaths.write(shopFile, VALID_SHOP_YML);
            assertTrue(shop.reload(), "前置条件：这份配置必须加载成功");
            ShopConfig previous = shop.getShopConfig();
            assertEquals(1, previous.getItems().size());

            TestPaths.write(shopFile, CORRUPT_SHOP_YML);

            assertFalse(shop.reload(), "坏文件必须报失败");
            assertSame(previous, shop.getShopConfig(),
                    "保留的必须是**同一个实例**（不是「又解析了一遍旧内容」——那说明 loader 真的成功读了坏文件）");
            assertEquals(Material.DIAMOND, shop.getShopConfig().getCurrencyMaterial(),
                    "保留的快照内容必须原样");
        }

        @Test
        @DisplayName("首次有快照、其后文件被删但仍处于隔离态：同样保留快照而不是清空")
        void quarantineAfterSuccessfulLoadKeepsSnapshot() throws IOException {
            TestPaths.write(shopFile, VALID_SHOP_YML);
            shop.reload();
            ShopConfig previous = shop.getShopConfig();

            TestPaths.write(shopFile, CORRUPT_SHOP_YML);
            shop.reload();   // 第一次失败：文件被隔离成 shop.yml.corrupt

            // 第二次：shop.yml 已不存在，但 .corrupt 还在 → 不得重建出厂模板
            assertFalse(shop.reload());
            assertSame(previous, shop.getShopConfig(),
                    "隔离态下继续失败也必须保留上一份快照，两次失败之间不得回落到空商店");
        }
    }

    // ================================================================ 成功路径对照

    @Nested
    @DisplayName("成功路径：返回值必须是 true（证明上面的 false 不是恒真）")
    class SuccessControl {

        @Test
        @DisplayName("正常 shop.yml 加载成功时返回 true")
        void validFileReportsSuccess() {
            assertTrue(shop.reload(), "首次安装写出出厂模板后必须报成功");
            assertNotNull(shop.getShopConfig());
            assertTrue(shop.getShopConfig().getItems().size() > 0,
                    "出厂模板有商品；为 0 说明读的不是模板");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private LanguageManager mockLang() {
        LanguageManager lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(lang.getFor(any(), anyString())).thenReturn("msg");
        when(lang.getFor(any(), anyString(), any(Object[].class))).thenReturn("msg");
        return lang;
    }

    /** 小包装：让 {@code PlayerMock} 的强转在一处完成，避免满屏 import。 */
    private static final class PlayerMockHolder {
        private final org.mockbukkit.mockbukkit.entity.PlayerMock player;

        PlayerMockHolder(org.mockbukkit.mockbukkit.entity.PlayerMock player) {
            this.player = player;
        }
    }
}
