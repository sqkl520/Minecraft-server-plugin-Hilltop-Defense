package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * F3 regression: a missing {@code price} key must not be silently priced at 1.
 *
 * <p>{@link ShopConfigLoader#parseItem} reads {@code getInt(path + ".price", 1)}, so an item
 * whose owner forgot {@code price} and an explicit {@code price: 0} are indistinguishable in
 * the result: the former silently becomes a 1-currency item with no diagnostic. The loader must
 * reject/skip an item whose price key is absent or null, while {@code price: 0} stays a valid
 * intentional free item and negative prices keep their existing clamp-to-0 warning.</p>
 */
class ShopConfigPriceValidationTest extends MockBukkitTestBase {

    private BareHost host;
    private ShopConfigLoader loader;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        loader = new ShopConfigLoader(host);
    }

    /** 把给定 YAML 写进宿主数据目录，再走一次真实的 {@link ShopConfigLoader#load()}。 */
    private ShopConfig load(String yaml) throws IOException {
        TestPaths.write(new File(host.getDataFolder(), "shop.yml"), yaml);
        return loader.load();
    }

    private static ShopItem byId(ShopConfig config, String id) {
        Map<String, ShopItem> byId = config.getItems().stream()
                .collect(Collectors.toMap(ShopItem::getId, Function.identity()));
        return byId.get(id);
    }

    /** 一个只有单件商品的极简配置骨架，便于逐条替换其中的某一键。 */
    private static String singleItem(String itemBody) {
        return """
                shop:
                  currency:
                    material: EMERALD
                  gui:
                    size: 54
                  items:
                    probe:
                """ + itemBody.lines()
                .map(line -> "      " + line)
                .collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    @DisplayName("F3 契约回归: 缺 price 键仍按默认 1 加载，但必须告警")
    void missingPriceKeyDefaultsToOneWithWarning() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "A missing price key is a legal configuration: getInt(..., 1) defaults to 1."
                        + " It must load, not be rejected (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(1, probe.getPrice(),
                "A missing price key must keep the documented default of 1");
        assertTrue(logs.anyContains("missing price"),
                "The warning about the implicit default is the actual F3 fix: "
                        + logs.lines());
        assertTrue(logs.anyContains("item 'probe'"),
                "The warning must name the offending item: " + logs.lines());
        assertTrue(logs.anyContains("defaulting to 1"),
                "The warning must name the default actually applied: " + logs.lines());
    }

    @Test
    @DisplayName("F3 契约回归: 显式 price: null 同样按默认 1 加载，但必须告警")
    void nullPriceDefaultsToOneWithWarning() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price:
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "An explicit price: null reads as an unset key, so it must keep the default"
                        + " of 1 and load (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(1, probe.getPrice(),
                "A null price key must keep the documented default of 1");
        assertTrue(logs.anyContains("missing price"),
                "The warning must still fire for a null price: " + logs.lines());
        assertTrue(logs.anyContains("item 'probe'"),
                "The warning must name the offending item: " + logs.lines());
    }

    @Test
    @DisplayName("F3 控制组: price: 0 仍合法加载为免费，且不报缺价/负价告警")
    void zeroPriceIsLegalWithoutWarning() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 0
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "price: 0 is an intentional free item and must still load (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(0, probe.getPrice(),
                "An explicit 0 must be honored as free, not replaced by a default");
        assertFalse(logs.anyContains("missing price"),
                "A present price: 0 must not be reported as missing: " + logs.lines());
        assertFalse(logs.anyContains("negative price"),
                "0 is not negative and must not trigger the clamp warning: " + logs.lines());
    }

    @Test
    @DisplayName("F3 控制组: price: -5 仍钳到 0 并保留负价格告警")
    void negativePriceIsStillClampedToZero() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: -5
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "Negative price is a misconfiguration, not an invalid item; it stays loaded"
                        + " (items: " + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(0, probe.getPrice(),
                "Negative prices must still clamp to 0 so removeCurrency cannot give the item away");
        assertTrue(logs.anyContains("negative price -5"),
                "The existing clamp warning must survive the F3 change: " + logs.lines());
    }
}
