package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * F2 regression: a {@code MATERIAL} item must not silently fall back to a
 * {@link Material#BARRIER} icon when {@code icon.material} is omitted.
 *
 * <p>{@link ShopConfigLoader#parseItem} initializes {@code icon} to {@code BARRIER} and only
 * rejected a missing {@code icon.material} for {@code EQUIPMENT}. For {@code MATERIAL}, the
 * GUI/delivery icon is built from that same field, so an omitted key silently shipped a barrier
 * block instead of the configured item. The loader must skip such an entry just as it does for
 * {@code EQUIPMENT}, while still honoring a valid {@code MATERIAL} icon.</p>
 */
class ShopConfigMaterialValidationTest extends MockBukkitTestBase {

    private BareHost host;
    private ShopConfigLoader loader;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        loader = new ShopConfigLoader(host);
    }

    @Test
    @DisplayName("F2 契约回归: MATERIAL 缺 icon.material 保留商品并回落 BARRIER，但有告警")
    void materialWithoutIconKeepsBarrierFallbackButWarns() throws IOException {
        LogCapture logs = attachLogCapture(host.getLogger());
        TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                shop:
                  currency:
                    material: EMERALD
                  gui:
                    size: 54
                  items:
                    no_icon:
                      slot: 0
                      price: 1
                      type: MATERIAL
                    with_icon:
                      slot: 1
                      price: 1
                      type: MATERIAL
                      icon:
                        material: DIAMOND_SWORD
                """);

        ShopConfig config = loader.load();
        Map<String, ShopItem> byId = config.getItems().stream()
                .collect(Collectors.toMap(ShopItem::getId, Function.identity()));
        ShopItem missingIcon = byId.get("no_icon");
        ShopItem validIcon = byId.get("with_icon");

        assertAll("F2 MATERIAL icon validation",
                () -> {
                    assertNotNull(missingIcon,
                            "Dropping the item is destructive: the GUI silently loses a slot"
                                    + " and the owner only sees one warning line (items: "
                                    + byId.keySet() + ")");
                    assertEquals(Material.BARRIER, missingIcon.getIcon(),
                            "The pre-existing contract is the visible BARRIER fallback icon,"
                                    + " not a vanished slot");
                },
                () -> {
                    assertNotNull(validIcon,
                            "Positive control: a MATERIAL item with icon.material: DIAMOND_SWORD must"
                                    + " still load (items: " + byId.keySet() + ")");
                    assertEquals("MATERIAL", validIcon.getType(),
                            "The positive control must remain a MATERIAL item");
                    assertEquals(Material.DIAMOND_SWORD, validIcon.getIcon(),
                            "The configured icon must be honored instead of the BARRIER default");
                },
                () -> assertTrue(logs.anyContains("MATERIAL item 'no_icon'"),
                        "Keeping the item must still be observable: the pre-fix code was silent,"
                                + " and silence is the defect F2 should have fixed: " + logs.lines()));
    }
}
