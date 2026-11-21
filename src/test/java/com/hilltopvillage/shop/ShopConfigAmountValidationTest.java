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
 * F4 regression: an explicit non-positive {@code amount} must not become a billable item.
 *
 * <p>{@link ShopConfigLoader#parseItem} reads {@code getInt(path + ".amount", 1)},
 * so {@code amount: 0} and {@code amount: -5} are accepted into {@link ShopItem}.
 * {@code ShopManager#deliverItem} later clamps the delivered stack to one, while the
 * purchase still charges the configured price. The loader must skip such an item and
 * warn with the invalid value, while a missing or null amount keeps the existing
 * default of 1 and an explicit positive amount is honored.</p>
 */
class ShopConfigAmountValidationTest extends MockBukkitTestBase {

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
    @DisplayName("F4: amount: 0 被跳过并告警，不进入 ShopConfig")
    void zeroAmountIsSkipped() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 10
                amount: 0
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNull(probe,
                () -> "An explicit amount: 0 must reject the item instead of delivering a"
                        + " clamped stack while charging the full price; it loaded with amount "
                        + probe.getAmount() + " (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertTrue(logs.anyContains("invalid amount 0"),
                "The skip must warn with the invalid value so the owner can fix shop.yml: "
                        + logs.lines());
        assertTrue(logs.anyContains("item 'probe'"),
                "The warning must name the offending item: " + logs.lines());
    }

    @Test
    @DisplayName("F4: 负 amount 被跳过并告警，不进入 ShopConfig")
    void negativeAmountIsSkipped() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 10
                amount: -5
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNull(probe,
                () -> "An explicit amount: -5 must reject the item instead of delivering a"
                        + " clamped stack while charging the full price; it loaded with amount "
                        + probe.getAmount() + " (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertTrue(logs.anyContains("invalid amount -5"),
                "The skip must warn with the invalid value so the owner can fix shop.yml: "
                        + logs.lines());
        assertTrue(logs.anyContains("item 'probe'"),
                "The warning must name the offending item: " + logs.lines());
    }

    @Test
    @DisplayName("F4 控制组: 缺 amount 键仍按既有默认值 1 加载")
    void missingAmountDefaultsToOne() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 10
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "A missing amount key must keep the existing default of 1 (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(1, probe.getAmount(),
                "A missing amount key must not be treated as an invalid explicit amount");
        assertFalse(logs.anyContains("invalid amount"),
                "A missing amount is valid and must not produce an invalid-amount warning: "
                        + logs.lines());
    }

    @Test
    @DisplayName("F4 控制组: amount: null 仍按既有默认值 1 加载")
    void nullAmountDefaultsToOne() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 10
                amount:
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "An explicit amount: null must keep the existing default of 1 (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(1, probe.getAmount(),
                "An explicit amount: null must not be treated as an invalid explicit amount");
        assertFalse(logs.anyContains("invalid amount"),
                "amount: null is valid and must not produce an invalid-amount warning: "
                        + logs.lines());
    }

    @Test
    @DisplayName("F4 控制组: amount: 3 仍原样加载为 3")
    void positiveAmountIsHonored() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load(singleItem("""
                slot: 0
                price: 10
                amount: 3
                type: MATERIAL
                icon:
                  material: BOW
                """));

        ShopItem probe = byId(config, "probe");
        assertNotNull(probe,
                "A positive explicit amount must still load (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(3, probe.getAmount(),
                "A positive explicit amount must be honored verbatim");
        assertFalse(logs.anyContains("invalid amount"),
                "A valid amount must not produce an invalid-amount warning: " + logs.lines());
    }

    @Test
    @DisplayName("F4: 被 amount 校验跳过的条目不得占用槽位，后续同槽商品不报冲突")
    void invalidAmountDoesNotReserveSlotForFollowingItem() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig config = load("""
                shop:
                  currency:
                    material: EMERALD
                  gui:
                    size: 54
                  items:
                    bad:
                      slot: 3
                      price: 10
                      amount: 0
                      type: MATERIAL
                      icon:
                        material: BOW
                    good:
                      slot: 3
                      price: 10
                      amount: 3
                      type: MATERIAL
                      icon:
                        material: DIAMOND
                """);

        ShopItem bad = byId(config, "bad");
        ShopItem good = byId(config, "good");
        assertNull(bad,
                () -> "amount: 0 must still reject the bad item; it loaded with amount "
                        + bad.getAmount() + " (items: "
                        + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertNotNull(good,
                "The valid same-slot item must still load after the invalid entry is skipped"
                        + " (items: " + config.getItems().stream().map(ShopItem::getId).toList() + ")");
        assertEquals(3, good.getSlot(),
                "The positive control must occupy the same slot as the skipped item");
        assertEquals(3, good.getAmount(),
                "The valid item's positive amount must be honored");
        assertTrue(logs.anyContains("invalid amount 0"),
                "The invalid item must still be reported with its value: " + logs.lines());
        assertFalse(logs.anyContains("conflicts with item 'bad'"),
                "A skipped invalid item must not reserve its slot and create a false conflict"
                        + " warning for the following valid item: " + logs.lines());
    }
}
